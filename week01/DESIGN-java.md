# 第一周作业设计方案（Java 版）：LLM 统一模型调用服务

> 状态：已实现并验证（2026-09-02）。
> 本方案面向 Java 后端背景，采用 Spring Boot WebFlux + WebClient + Reactor。

## 1. 技术选型

- JDK 17+
- Spring Boot 3.x
- Spring WebFlux
- WebClient
- Reactor
- Jackson
- Bean Validation / 自定义校验
- Maven 或 Gradle

可选依赖：

- `spring-boot-starter-webflux`
- `spring-boot-starter-validation`
- `commons-text`（模板变量替换）
- `reactor-test`（流测试）
- `junit-jupiter`（单元测试）

## 2. 总体架构

```text
Client
  -> Spring WebFlux Controller /v1/chat
  -> ModelRouter（按 model 选择 Adapter）
  -> OpenAiResponsesAdapter | AnthropicMessagesAdapter
  -> WebClient 调用上游 Provider

横切能力：
  PromptRegistry（模板版本管理）
  UsageStore（Token 与延迟记录）
  RateLimiter（按模型限流）
  RetryPolicy（指数退避）
  GatewayError（统一错误码）
```

## 3. 包结构

```text
com.leesoons.llmgateway
├── LlmGatewayApplication.java
├── controller/
│   └── ChatController.java
├── model/
│   ├── ChatRequest.java
│   ├── ChatResponse.java
│   ├── Message.java
│   ├── PromptRef.java
│   ├── ResponseFormat.java
│   ├── TokenUsage.java
│   └── StreamEvent.java
├── adapter/
│   ├── LlmAdapter.java
│   ├── OpenAiResponsesAdapter.java
│   └── AnthropicMessagesAdapter.java
├── service/
│   ├── ChatService.java
│   ├── ModelRouter.java
│   ├── PromptService.java
│   └── UsageService.java
├── core/
│   ├── GatewayErrorCode.java
│   ├── GatewayException.java
│   ├── RetryPolicy.java
│   └── PerModelRateLimiter.java
└── config/
    ├── ModelRouteProperties.java
    └── WebClientConfig.java
```

## 4. 统一数据模型

### 4.1 请求

```java
public record Message(String role, String content) {}

public record PromptRef(String name, String version, Map<String, String> variables) {}

public record ResponseFormat(String type, Map<String, Object> jsonSchema) {}

public record ChatRequest(
    String model,
    List<Message> messages,
    boolean stream,
    ResponseFormat responseFormat,
    PromptRef prompt,
    int timeoutSeconds
) {}
```

### 4.2 响应

```java
public record TokenUsage(
    int inputTokens,
    int outputTokens,
    int cacheReadInputTokens,
    int totalTokens
) {}

public record ChatResponse(
    String requestId,
    String model,
    String content,
    Map<String, Object> parsed,
    TokenUsage usage,
    long latencyMs,
    long ttftMs,
    int attempts
) {}

public record StreamEvent(String type, Object data) {}
```

## 5. Adapter 接口

```java
public interface LlmAdapter {
    String adapterName();
    Mono<ChatResponse> chat(ChatRequest request);
    Flux<StreamEvent> stream(ChatRequest request);
}
```

两个实现：

- `OpenAiResponsesAdapter`：把统一请求转换为 OpenAI Responses API 请求体，调用 `/v1/responses`
- `AnthropicMessagesAdapter`：把统一请求转换为 Anthropic Messages API 请求体，调用 `/v1/messages`

每个 Adapter 负责：

- 鉴权 Header
- 请求体字段映射
- 返回结构归一化
- Provider 错误归一化
- 流式事件归一化

## 6. 动态路由

```yaml
llm:
  routes:
    deepseek-v4-pro:
      adapter: openai_responses
      base-url: https://api.deepseek.com
      api-key-env: DEEPSEEK_V4_PRO_API_KEY
    deepseek-v4-flash:
      adapter: anthropic_messages
      base-url: https://api.deepseek.com
      api-key-env: DEEPSEEK_V4_FLASH_API_KEY
```

`ModelRouter` 根据 `model` 查找 `ModelRoute`，再取对应 `LlmAdapter`。未知模型抛 `MODEL_NOT_FOUND`。

## 7. Prompt 版本管理

- 模板目录：`prompts/{name}/{version}.jinja2` 或 `.mustache`
- 引用：请求中 `prompt: {name, version, variables}`
- 变量替换：`commons-text StringSubstitutor`，配置缺失变量抛异常
- 注入防护：模板作为 System Prompt；用户内容单独进入 user message

示例：

```text
prompts/knowledge_decision/v1.jinja2
你是${product_name}的知识库决策器。资料不足时搜索，资料充分时结束回答。
```

## 8. Streaming 设计

- `ChatService` 根据 `stream` 分支：
  - `false`：返回 `Mono<ChatResponse>`
  - `true`：返回 `Flux<ServerSentEvent<StreamEvent>>`
- SSE 事件类型：`meta`、`delta`、`done`、`error`
- `ttftMs`：从请求发出到第一个 `delta` 的时间
- Controller 返回 `text/event-stream`

## 9. Structured Output 设计

- 请求携带 `responseFormat`
- Adapter 优先映射到 Provider 原生结构化能力
- 如果返回内容不是合法 JSON 或不匹配 Schema：

```text
Jackson 校验失败
  -> 将错误信息反喂模型
  -> 最多重试 2 次
  -> 仍失败则返回 STRUCTURED_OUTPUT_FAILED
```

## 10. 可观测性

`UsageService` 记录：

- `requestId`
- `model`
- `promptName/version`
- `inputTokens / outputTokens / cacheReadInputTokens / totalTokens`
- `latencyMs`
- `ttftMs`
- `attempts`
- `status`
- `errorCode`

接口：

- `GET /metrics`
- `GET /metrics/{model}`

## 11. 韧性设计

### 11.1 统一错误码

```java
public enum GatewayErrorCode {
    INVALID_REQUEST,
    MODEL_NOT_FOUND,
    AUTH_ERROR,
    RATE_LIMITED,
    UPSTREAM_TIMEOUT,
    UPSTREAM_ERROR,
    STRUCTURED_OUTPUT_FAILED
}
```

`GatewayException` 同时携带：

- `GatewayErrorCode code`
- `HttpStatus status`
- `boolean retryable`
- 对外安全 message

### 11.2 指数退避

使用 Reactor Retry：

```java
Mono.defer(() -> upstreamCall())
    .retryWhen(
        Retry.backoff(3, Duration.ofMillis(500))
            .jitter(0.2)
            .filter(throwable -> isRetryable(throwable))
    )
```

只重试可重试错误，最多 3 次。

### 11.3 按模型独立限流

```java
public class PerModelRateLimiter {
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    public Mono<Void> acquire(String model) { ... }
}
```

超限抛 `RATE_LIMITED`，HTTP 429。

## 12. API 设计

| Method | Path | 说明 |
|--------|------|------|
| `GET` | `/health` | 健康检查 |
| `GET` | `/models` | 模型与协议映射 |
| `POST` | `/v1/chat` | 统一调用，支持 `stream=true` |
| `GET` | `/prompts` | 列出 Prompt 模板 |
| `GET` | `/metrics` | 整体指标 |
| `GET` | `/metrics/{model}` | 单模型指标 |

## 13. 验证方案

使用 JUnit 5 + WebTestClient + MockWebServer：

- 模拟 OpenAI Responses API
- 模拟 Anthropic Messages API

覆盖：

- 路由选择正确
- 两个 Adapter 请求/响应映射正确
- 流式 SSE 事件正确
- 结构化输出成功和修复循环
- Prompt 版本引用和变量替换
- Token 统计和 TTFT
- 重试 3 次后停止
- 按模型限流返回 429

README 提供 curl 示例：

- 普通调用
- 流式调用
- 结构化输出
- Prompt 模板引用
- 限流触发

## 14. 实现顺序

1. 项目骨架和统一模型
2. WebClient 配置与错误体系
3. OpenAI Responses Adapter + mock
4. Anthropic Messages Adapter + mock
5. ModelRouter 与 ChatService
6. Prompt 版本管理
7. Streaming
8. Structured Output
9. Usage/Trace
10. Retry 与 Rate Limit
11. 测试、README、curl 示例

## 15. 待确认

- 课程是否强制 Python；如果强制，本方案只能作为概念参照
- 是否允许真实 API Key，还是先用 MockWebServer 验收
- DeepSeek 的 Anthropic Messages API 地址和字段是否与 Anthropic 官方一致

## 16. 踩坑与修复记录（2026-09-02）

| 现象 | 根因 | 修复 |
|------|------|------|
| `/v1/chat` 返回 `500 internal_error` | 熔断打开后 `ModelRouter.candidates()` 在 `doChat` 里同步抛异常，绕过 `onErrorResume` 归一化 | 在 `GatewayService.chat()/stream()` 最外层加 `onErrorResume(normalize)` |
| 非流式 `/v1/chat` 返回 `text/event-stream` + 空 body + `UnsupportedOperationException` | `@RestController` 返回 `Mono<ServerResponse>`（函数式风格），Spring 当普通对象 JSON 序列化失败，且响应已提交无法写错误头 | 改为返回 `Mono<ResponseEntity<?>>` |
| 结构化输出 `parsed` 恒为 `null` | Jackson 默认不映射 `response_format`→`responseFormat`，schema 被静默丢弃（verify 脚本 `grep "parsed"` 是假阳性） | `ChatRequest` 加 `@JsonProperty("response_format"/"max_tokens"/"top_p"/"timeout_seconds")` |
| `git push` 报 `remote-https is not a git command` | conda base 自带的 git 缺少 remote-https 组件 | 切系统 git 或 `alias git=/usr/bin/git` |
