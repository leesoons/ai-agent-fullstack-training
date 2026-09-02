# 第一周作业设计方案：LLM 统一模型调用服务

> 状态：待实现。
> 原则：本文件只定义设计；代码实现前先确认本方案。

## 1. 作业目标

实现一个统一的 LLM 模型调用服务，用适配器模式屏蔽两种不同 API 协议，并满足流式输出、结构化输出、Prompt 版本管理、可观测性、重试和独立限流六大能力。

建议协议组合：

- `deepseek-v4-pro` -> OpenAI Responses API
- `deepseek-v4-flash` -> Anthropic Messages API

## 2. 总体架构

```text
Client
  -> FastAPI /v1/chat
  -> RequestRouter（按 model 选择 Adapter）
  -> OpenAIResponsesAdapter | AnthropicMessagesAdapter
  -> 上游 Provider

横切能力：
  PromptRegistry（模板版本管理）
  UsageStore（Token 与延迟记录）
  RateLimiter（按模型限流）
  RetryPolicy（指数退避）
  ErrorNormalizer（统一错误码）
```

## 3. 目录结构

```text
week01/
├── DESIGN.md
├── unified-llm-gateway/
│   ├── app/
│   │   ├── main.py
│   │   ├── config.py
│   │   ├── schemas.py
│   │   ├── adapters/
│   │   │   ├── base.py
│   │   │   ├── openai_responses.py
│   │   │   └── anthropic_messages.py
│   │   ├── core/
│   │   │   ├── errors.py
│   │   │   ├── retry.py
│   │   │   └── rate_limit.py
│   │   └── services/
│   │       ├── router.py
│   │       ├── chat.py
│   │       ├── streaming.py
│   │       ├── prompts.py
│   │       └── usage.py
│   ├── prompts/
│   │   ├── knowledge_decision/v1.jinja2
│   │   └── code_reviewer/v1.jinja2
│   ├── tests/
│   └── pyproject.toml
├── examples/
│   ├── curl.md
│   └── verify.py
└── README.md
```

## 4. 统一数据协议

### 4.1 统一请求

```python
class Message(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str

class PromptRef(BaseModel):
    name: str
    version: str
    variables: dict[str, str] = Field(default_factory=dict)

class ResponseFormat(BaseModel):
    type: Literal["json_schema"]
    json_schema: dict[str, Any]

class ChatRequest(BaseModel):
    model: str
    messages: list[Message]
    stream: bool = False
    response_format: ResponseFormat | None = None
    prompt: PromptRef | None = None
    timeout_seconds: float = Field(default=30, gt=0, le=120)
```

### 4.2 统一响应

```python
class TokenUsage(BaseModel):
    input_tokens: int
    output_tokens: int
    cache_read_input_tokens: int = 0
    total_tokens: int

class ChatResponse(BaseModel):
    request_id: str
    model: str
    content: str
    parsed: dict[str, Any] | None = None
    usage: TokenUsage
    latency_ms: int
    ttft_ms: int
    attempts: int
```

## 5. Adapter 设计

统一接口：

```python
class BaseAdapter(Protocol):
    async def chat(self, request: ChatRequest) -> ChatResponse: ...
    def stream(self, request: ChatRequest) -> AsyncIterator[StreamEvent]: ...
```

各 Adapter 负责：

- 鉴权 Header 拼装
- 请求体协议转换
- 返回结构归一化
- Provider 错误归一化
- 结构化输出和流式事件的协议适配

## 6. 动态路由

```python
MODEL_ROUTES = {
    "deepseek-v4-pro": {
        "adapter": "openai_responses",
        "base_url": "...",
        "api_key_env": "DEEPSEEK_V4_PRO_API_KEY",
    },
    "deepseek-v4-flash": {
        "adapter": "anthropic_messages",
        "base_url": "...",
        "api_key_env": "DEEPSEEK_V4_FLASH_API_KEY",
    },
}
```

未知模型返回统一错误码 `MODEL_NOT_FOUND`。

## 7. Prompt 版本管理

- 模板文件：`prompts/{name}/{version}.jinja2`
- 引用方式：请求中的 `prompt: {name, version, variables}`
- 变量替换：Jinja2 + `StrictUndefined`，变量缺失直接报错
- 注入防护：模板作为 System Prompt，用户内容只进入 user message，不直接拼进模板

## 8. 流式输出

- 请求 `stream=true` 时返回 `text/event-stream`
- SSE 事件类型：
  - `meta`：请求 ID、模型、流开始
  - `delta`：文本增量
  - `done`：完整 usage、总延迟、TTFT
  - `error`：统一错误码
- `ttft_ms`：从请求发出到首个 `delta` 的耗时

## 9. 结构化输出

- 请求携带 `response_format`
- 适配器优先映射到 Provider 原生结构化能力
- 若 Provider 不支持或返回不合规，执行统一修复循环：

```text
validate_json
  -> 失败
  -> 将 ValidationError 反喂模型
  -> 最多重试 2 次
  -> 仍失败则返回统一错误码 STRUCTURED_OUTPUT_FAILED
```

## 10. 可观测性

每条记录包含：

- `request_id`
- `model`
- `prompt_name/version`
- `input_tokens`
- `output_tokens`
- `cache_read_input_tokens`
- `total_tokens`
- `latency_ms`
- `ttft_ms`
- `attempts`
- `status`
- `error_code`

暴露：

- `GET /metrics`：整体统计
- `GET /metrics/{model}`：按模型统计

## 11. 韧性设计

### 11.1 统一错误码

| 错误码 | HTTP | 是否可重试 |
|--------|------|-----------|
| `INVALID_REQUEST` | 400 | 否 |
| `MODEL_NOT_FOUND` | 404 | 否 |
| `AUTH_ERROR` | 502 | 否 |
| `RATE_LIMITED` | 429 | 是 |
| `UPSTREAM_TIMEOUT` | 504 | 是 |
| `UPSTREAM_ERROR` | 502 | 是 |
| `STRUCTURED_OUTPUT_FAILED` | 422 | 是 |

### 11.2 指数退避

- 最多重试 3 次
- 退避公式：`min(0.5 * 2 ** (attempt - 1), 4.0)`，加入随机抖动
- 只重试标记为可重试的错误

### 11.3 按模型独立限流

- 每个模型一个 Token Bucket
- 超限直接返回 `429 RATE_LIMITED`
- 配置项：`requests_per_minute`、`burst`

## 12. API 设计

| Method | Path | 说明 |
|--------|------|------|
| `GET` | `/health` | 健康检查 |
| `GET` | `/models` | 模型与协议映射 |
| `POST` | `/v1/chat` | 统一调用入口，支持 stream |
| `GET` | `/prompts` | 列出可用 Prompt 模板 |
| `GET` | `/metrics` | 整体可观测数据 |
| `GET` | `/metrics/{model}` | 单模型可观测数据 |

## 13. 验证方案

### 13.1 自动化测试

用 mock 上游服务验证两种协议：

- OpenAI Responses 协议 mock
- Anthropic Messages 协议 mock

覆盖：

- 路由选择正确
- 两个 Adapter 请求体/返回体映射正确
- 流式输出 SSE 事件正确
- 结构化输出成功和失败修复
- Prompt 版本引用和变量替换
- Token 分类统计和 TTFT
- 重试达到 3 次后停止
- 按模型限流触发 429

### 13.2 curl 验证

README 提供：

- 普通调用
- 流式调用
- 结构化输出调用
- Prompt 模板引用
- 限流触发示例

## 14. 实现顺序

1. 统一 Schema 和错误体系
2. OpenAI Responses Adapter + mock
3. Anthropic Messages Adapter + mock
4. 路由与统一调用入口
5. Prompt 版本管理
6. Streaming
7. Structured Output
8. Usage/Trace
9. Retry 与 Rate Limit
10. 验证脚本、README、curl 示例

## 15. 待确认

- 两个模型是否有真实 API Key，还是先用 mock 验收
- DeepSeek 的 Anthropic Messages API 地址和字段是否与 Anthropic 官方一致
- 是否允许使用 `openai`、`anthropic` SDK，还是必须用裸 `httpx` 展示协议适配
