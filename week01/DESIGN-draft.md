# Week 01 作业设计方案（草案）

> 状态：待飞书作业题目确认后收敛。
> 原则：先设计，不实现；实现前与 Eva 确认最终范围。

## 1. 作业目标

围绕官方课程 Week01 的核心主线，交付一个“可运行的 LLM Gateway + 首个 Agent Loop”，而不是只写零散脚本。

预期能力：

- 统一模型调用入口，屏蔽 OpenAI / DeepSeek 等 Provider 差异
- 支持结构化输出、流式输出、Prompt 模板与版本管理
- 具备重试、Fallback、Token/Cost/Latency 追踪
- 用 Agent Loop 完成一个最小长程任务
- 用 Eval 和 pytest 证明系统可运行、可回归

## 2. 目录设计

```text
week01/
├── DESIGN.md
├── llm_gateway/
│   ├── app/
│   │   ├── main.py
│   │   ├── config.py
│   │   ├── schemas.py
│   │   ├── adapters/
│   │   │   ├── base.py
│   │   │   ├── openai_adapter.py
│   │   │   └── deepseek_adapter.py
│   │   ├── core/
│   │   │   ├── errors.py
│   │   │   └── retry.py
│   │   └── services/
│   │       ├── chat.py
│   │       ├── structured.py
│   │       ├── streaming.py
│   │       ├── prompts.py
│   │       └── usage.py
│   └── tests/
├── agent/
│   ├── loop.py
│   └── tools.py
├── eval/
│   ├── cases.json
│   └── runner.py
├── examples/
│   ├── cli.py
│   └── stream_client.py
└── README.md
```

## 3. 核心模块设计

### 3.1 Model Adapter

- 定义统一接口：`chat()`、`stream_chat()`、`structured_output()`
- OpenAI Adapter：基于 `AsyncOpenAI`
- DeepSeek Adapter：基于 OpenAI Compatible 接口
- 统一错误分类：超时、限流、连接失败、鉴权失败、上游错误

### 3.2 Structured Output

- 输入：Pydantic Model / JSON Schema
- 调用：模型原生 Structured Output 或 JSON Mode
- 校验：`model_validate_json`
- 失败策略：把 `ValidationError` 反喂模型，最多重试 2 次；仍失败则返回明确错误

### 3.3 Streaming

- 服务端：FastAPI `StreamingResponse` + SSE
- 客户端：`httpx.AsyncClient` 逐块消费
- 指标：首 Token 延迟、总延迟
- 异常：断线、限流、用户取消

### 3.4 Prompt Template

- 模板：Jinja2 或 `string.Template`
- 版本：`name + version`
- 安全：未定义变量直接报错；用户输入不直接拼接 System Prompt
- Prompt 注入：明确角色边界，用户内容与系统指令分离

### 3.5 LLM Gateway

- 统一请求协议：`LLMRequest` / `LLMResponse`
- 路由：逻辑模型名 → Provider 模型名
- 治理：超时、重试、Fallback、Token/Cost/Latency 记录
- 接口：`/health`、`/chat`、`/chat/stream`

### 3.6 Agent Loop

- Loop：`observe -> think/plan -> act -> observe -> finalize`
- 工具：至少实现一个知识检索工具
- 决策：用 Structured Output 返回 `search` 或 `answer`
- 退出条件：资料充分或达到最大步数

### 3.7 Eval

- 用例：10–20 个 JSON Case
- 指标：成功率、平均步数、平均延迟
- 失败分析：输出错误类型和样本

## 4. 验收标准

- `pytest` 全部通过
- 服务可启动，接口可用
- Agent Loop 至少完成一个 Case
- Eval 能输出成功率
- README 写清楚运行方式、配置项和示例

## 5. 里程碑建议

1. Day1：搭目录、统一请求/响应 Schema、Model Adapter
2. Day2：结构化输出与修复循环
3. Day3：Streaming + CLI 客户端
4. Day4：Prompt 模板与注入防护
5. Day5：Gateway 重试、Fallback、Trace
6. Day6：Agent Loop + Eval
7. Day7：测试、README、最终验收

## 6. 待确认

- 飞书作业是否指定固定题目或固定代码结构
- 是否必须复用官方 `1-7/llm-gateway` 代码
- 是否允许使用 Docker Compose
- 提交物是源码、截图、评测报告，还是三者都要
