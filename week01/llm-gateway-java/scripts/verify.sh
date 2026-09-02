#!/usr/bin/env bash
# 一键验证脚本：对运行中的 LLM Gateway 覆盖六大功能点。
# 用法：先启动服务（mvn spring-boot:run），再执行 bash scripts/verify.sh
set -u

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8000}"
AUTH="${GATEWAY_API_KEY:-dev-key}"

ok()  { printf '\033[32m✔ %s\033[0m\n' "$1"; }
fail(){ printf '\033[31m✘ %s\033[0m\n' "$1"; }

echo "== 目标：$GATEWAY_URL =="

# 1. 健康检查
resp=$(curl -s "$GATEWAY_URL/health")
if echo "$resp" | grep -q '"status":"ok"'; then ok "健康检查"; else fail "健康检查 -> $resp"; fi

# 2. 模型列表
resp=$(curl -s "$GATEWAY_URL/v1/models" -H "Authorization: Bearer $AUTH")
if echo "$resp" | grep -q 'deepseek-v4-pro' && echo "$resp" | grep -q 'deepseek-v4-flash'; then ok "模型列表"; else fail "模型列表 -> $resp"; fi

# 3. 非流式调用（OpenAI Responses 协议）
resp=$(curl -s "$GATEWAY_URL/v1/chat" \
  -H "Authorization: Bearer $AUTH" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"说一个词"}]}')
if echo "$resp" | grep -q '"content"'; then ok "非流式调用(OpenAI Responses) -> $resp"; else fail "非流式调用(OpenAI Responses) -> $resp"; fi

# 4. 非流式调用（Anthropic Messages 协议）
resp=$(curl -s "$GATEWAY_URL/v1/chat" \
  -H "Authorization: Bearer $AUTH" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"说一个词"}]}')
if echo "$resp" | grep -q '"content"'; then ok "非流式调用(Anthropic Messages) -> $resp"; else fail "非流式调用(Anthropic Messages) -> $resp"; fi

# 5. 流式输出
resp=$(curl -sN "$GATEWAY_URL/v1/chat" \
  -H "Authorization: Bearer $AUTH" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-pro","stream":true,"messages":[{"role":"user","content":"你好"}]}')
if echo "$resp" | grep -q '"type":"meta"' && echo "$resp" | grep -q '"type":"done"'; then ok "流式 SSE（meta/done）"; else fail "流式 SSE -> $resp"; fi

# 6. 结构化输出
resp=$(curl -s "$GATEWAY_URL/v1/chat" \
  -H "Authorization: Bearer $AUTH" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"返回一个名字"}],"response_format":{"type":"json_schema","name":"person","strict":true,"schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}')
if echo "$resp" | grep -q '"parsed"'; then ok "结构化输出 -> $resp"; else fail "结构化输出 -> $resp"; fi

# 7. Prompt 版本管理：创建 + 渲染 + 引用
create=$(curl -s -X POST "$GATEWAY_URL/v1/prompts" \
  -H "Authorization: Bearer $AUTH" -H "Content-Type: application/json" \
  -d '{"id":"verify-reviewer","name":"verify","role":"system","content":"你是{{ lang }}审查助手","activate":true}')
if echo "$create" | grep -q '"version":1'; then ok "Prompt 创建 v1"; else fail "Prompt 创建 -> $create"; fi

render=$(curl -s -X POST "$GATEWAY_URL/v1/prompts/verify-reviewer/render" \
  -H "Authorization: Bearer $AUTH" -H "Content-Type: application/json" \
  -d '{"variables":{"lang":"Java"}}')
if echo "$render" | grep -q '你是Java审查助手'; then ok "Prompt 渲染"; else fail "Prompt 渲染 -> $render"; fi

# 8. 用量查询
usage=$(curl -s "$GATEWAY_URL/admin/usage?limit=5" -H "Authorization: Bearer $AUTH")
if echo "$usage" | grep -q '"requestId"'; then ok "用量查询（Token/延迟/TTFT）"; else fail "用量查询 -> $usage"; fi

echo "== 验证完成 =="
