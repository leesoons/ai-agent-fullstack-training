# LLM 统一模型调用服务（Java 版）

第一周作业的 Java 实现：一个统一抽象、双协议适配、可观测、具备韧性基础的 LLM Gateway。

本实现参考官方 Python 版 `course_code/week01/1-7/llm-gateway` 的架构与能力边界，改用 **Spring Boot WebFlux + WebClient + Reactor** 落地，并补充了作业要求的 **Anthropic Messages API** 适配器（官方参考实现只覆盖 OpenAI 兼容协议）。

## 技术栈

- JDK 21+（开发环境为 JDK 26，编译目标 21）
- Spring Boot 3.5.x（WebFlux、Validation）
- Reactor（响应式编排、重试、背压）
- Jackson（JSON、JSON Schema 校验）
- Pebble（Jinja 风格 Prompt 模板渲染，严格变量校验）
- networknt json-schema-validator（结构化输出本地校验）
- OkHttp MockWebServer（测试中模拟上游协议）

## 架构

```text
Client / Agent
      │  POST /v1/chat
      ▼
ApiKeyAuthenticator ──► PerModelRateLimiter（按模型令牌桶）
      │
      ▼
GatewayService
  ├─ Prompt 渲染（版本引用 + 变量替换 + 注入）
  ├─ ModelRouter（按 model 路由，priority / weighted_round_robin）
  ├─ 指数退避重试（最多 3 次）
  └─ Structured Output（本地校验 + 纠错循环）
      │
      ▼
LlmAdapter
  ├─ OpenAiResponsesAdapter   deepseek-v4-pro  -> POST /v1/responses
  └─ AnthropicMessagesAdapter deepseek-v4-flash -> POST /v1/messages
      │
      ▼
UsageService（Token 分类统计 / 延迟 / TTFT / 重试 / 回退）
```

## 统一接口

请求统一为：

```json
{
  "model": "deepseek-v4-pro",
  "messages": [{"role": "user", "content": "解释 Agent Loop"}],
  "stream": false,
  "temperature": 0.2,
  "max_tokens": 800,
  "response_format": {
    "type": "json_schema",
    "name": "person",
    "strict": true,
    "schema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}
  },
  "prompt": {"id": "code-reviewer", "version": 1, "variables": {"language": "Java"}}
}
```

`model` 决定路由到哪个协议适配器，调用方无需感知上游鉴权、请求体结构与返回格式差异。

## 快速开始

```bash
# 1. 配置密钥与供应商地址（编辑 src/main/resources/application.yml 或通过环境变量覆盖）
export GATEWAY_API_KEY=dev-key
export DEEPSEEK_V4_PRO_API_KEY=sk-xxx
export DEEPSEEK_V4_FLASH_API_KEY=sk-xxx
# Anthropic 兼容端点地址（如果 DeepSeek 提供，请替换为真实地址）
export DEEPSEEK_ANTHROPIC_BASE_URL=https://your-anthropic-compatible-endpoint

# 2. 启动
mvn spring-boot:run
```

健康检查：

```bash
curl http://localhost:8000/health
```

## curl 示例

### 查看模型列表

```bash
curl -s http://localhost:8000/v1/models -H "Authorization: Bearer dev-key"
```

### 非流式调用（OpenAI Responses 协议）

```bash
curl -s http://localhost:8000/v1/chat \
  -H "Authorization: Bearer dev-key" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"你好"}]}'
```

### 非流式调用（Anthropic Messages 协议）

```bash
curl -s http://localhost:8000/v1/chat \
  -H "Authorization: Bearer dev-key" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"你好"}]}'
```

### 流式输出（SSE）

```bash
curl -N http://localhost:8000/v1/chat \
  -H "Authorization: Bearer dev-key" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-pro","stream":true,"messages":[{"role":"user","content":"写一首短诗"}]}'
```

### 结构化输出

```bash
curl -s http://localhost:8000/v1/chat \
  -H "Authorization: Bearer dev-key" -H "Content-Type: application/json" \
  -d '{
    "model":"deepseek-v4-pro",
    "messages":[{"role":"user","content":"返回一个名字"}],
    "response_format":{"type":"json_schema","name":"person","strict":true,
      "schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}
  }'
```

### Prompt 版本管理

```bash
# 创建模板 v1 并激活
curl -s -X POST http://localhost:8000/v1/prompts \
  -H "Authorization: Bearer dev-key" -H "Content-Type: application/json" \
  -d '{"id":"code-reviewer","name":"代码审查助手","role":"system","content":"你是{{ language }}代码审查助手，重点关注{{ focus }}。","activate":true}'

# 同 id 再次 POST 会生成 v2
curl -s -X POST http://localhost:8000/v1/prompts \
  -H "Authorization: Bearer dev-key" -H "Content-Type: application/json" \
  -d '{"id":"code-reviewer","name":"代码审查助手 v2","role":"system","content":"你是资深{{ language }}工程师，审查{{ focus }}。","activate":true}'

# 引用模板（指定 v1 + 变量替换）
curl -s http://localhost:8000/v1/chat \
  -H "Authorization: Bearer dev-key" -H "Content-Type: application/json" \
  -d '{
    "model":"deepseek-v4-pro",
    "messages":[{"role":"user","content":"审查这段代码：int a=1;"}],
    "prompt":{"id":"code-reviewer","version":1,"variables":{"language":"Java","focus":"并发安全"}}
  }'
```

### 可观测性

```bash
# 最近调用记录（Token 分类、延迟、TTFT、重试、回退）
curl -s http://localhost:8000/admin/usage?limit=20 -H "Authorization: Bearer dev-key"
```

## 六大能力验收对照

| 作业要求 | 实现位置 |
|---------|---------|
| 统一抽象 + 适配器 + model 动态路由 | `adapter/LlmAdapter`、`service/ModelRouter`、`service/GatewayService` |
| 流式 SSE | `service/UpstreamClient.openStream` + `GatewayService.stream`（meta/delta/done/error） |
| 结构化输出 | `service/StructuredOutputService`（本地校验 + 纠错循环） |
| Prompt 版本管理 | `service/PromptService`、`InMemoryPromptStore`（版本、激活、变量替换） |
| 可观测性（Token/延迟/TTFT） | `service/UsageService`、`UsageEvent` |
| 韧性（统一错误码 + 退避重试 + 限流） | `core/*`、`PerModelRateLimiter`、`GatewayService.attempt` |

## 测试

测试使用 `MockWebServer` 模拟两种上游协议，不消耗真实模型额度，覆盖：

- 两个 Adapter 的路由与请求/响应映射、鉴权 Header
- 结构化输出失败后的纠错循环
- Prompt 版本引用与变量替换注入
- SSE 流式事件与 TTFT
- 429 后的指数退避重试
- 按模型限流返回 429
- 未知模型返回 404

```bash
mvn test
```

## 一键验证脚本

先启动服务，再运行：

```bash
bash scripts/verify.sh
```

脚本会依次覆盖：健康检查、模型列表、非流式调用、流式输出、结构化输出、Prompt 版本管理、用量查询与限流证据。

## 说明与边界

- 当前限流、熔断、用量账本为**进程内实现**；多副本部署时应替换为 Redis / 数据库。
- Anthropic 兼容端点地址与协议细节请以 DeepSeek 实际提供的文档为准，通过 `application.yml` 配置。
- 真实密钥只放在环境变量中，不要提交到仓库。
