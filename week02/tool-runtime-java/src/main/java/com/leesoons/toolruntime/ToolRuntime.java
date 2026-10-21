package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行唯一入口。模型只「提议」，这里才真正「执行」。
 *
 * <p>三阶段：prepare（Schema/策略/白名单/RBAC/预检/审批/依赖）→ execute（超时 + 有限重试 + 写锁）
 * → finalize（输出校验 + 脱敏投影 + 审计）。</p>
 */
public final class ToolRuntime implements AutoCloseable {

    private final Map<String, Boolean> enabled;
    private final Set<String> healthyDependencies;
    private final PermissionEngine permissionEngine;
    private final ApprovalStore approvals;
    private final AuditSink audit;
    private final Map<String, Object> locks = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public ToolRuntime(Map<String, Boolean> enabled, Set<String> healthyDependencies) {
        this(enabled, healthyDependencies, new PermissionEngine(), new ApprovalStore(), new AuditSink(),
                new ObjectMapper(), null);
    }

    public ToolRuntime(Map<String, Boolean> enabled, Set<String> healthyDependencies,
                       PermissionEngine permissionEngine) {
        this(enabled, healthyDependencies, permissionEngine, new ApprovalStore(), new AuditSink(),
                new ObjectMapper(), null);
    }

    public ToolRuntime(Map<String, Boolean> enabled, Set<String> healthyDependencies,
                       PermissionEngine permissionEngine, ApprovalStore approvals) {
        this(enabled, healthyDependencies, permissionEngine, approvals, new AuditSink(),
                new ObjectMapper(), null);
    }

    public ToolRuntime(Map<String, Boolean> enabled, Set<String> healthyDependencies,
                       PermissionEngine permissionEngine, ApprovalStore approvals, AuditSink audit) {
        this(enabled, healthyDependencies, permissionEngine, approvals, audit, new ObjectMapper(), null);
    }

    public ToolRuntime(Map<String, Boolean> enabled,
                       Set<String> healthyDependencies,
                       ObjectMapper objectMapper,
                       ExecutorService executor) {
        this(enabled, healthyDependencies, new PermissionEngine(), new ApprovalStore(), new AuditSink(),
                objectMapper, executor);
    }

    public ToolRuntime(Map<String, Boolean> enabled,
                       Set<String> healthyDependencies,
                       PermissionEngine permissionEngine,
                       ApprovalStore approvals,
                       AuditSink audit,
                       ObjectMapper objectMapper,
                       ExecutorService executor) {
        this.enabled = enabled;
        this.healthyDependencies = healthyDependencies == null ? Set.of() : healthyDependencies;
        this.permissionEngine = permissionEngine == null ? new PermissionEngine() : permissionEngine;
        this.approvals = approvals == null ? new ApprovalStore() : approvals;
        this.audit = audit == null ? new AuditSink() : audit;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.executor = executor == null ? Executors.newVirtualThreadPerTaskExecutor() : executor;
        this.ownsExecutor = executor == null;
    }

    /**
     * 执行一次工具调用，返回统一产物 {@link ToolResult}。
     *
     * <p>三段式：{@link #prepare} 门禁 → {@link #execute} 受控执行 → {@link #finalizeSuccess}。
     * 门禁拒绝通过 {@link PolicyDenied} 抛出，统一在 {@link #finalizeDenied} 审计并返回。</p>
     */
    public ToolResult invoke(ToolSnapshot snapshot, ToolCall call, ExecutionContext context) {
        long startedAt = System.nanoTime();
        PreparedCall prepared;
        try {
            prepared = prepare(snapshot, call, context);
        } catch (PolicyDenied e) {
            return finalizeDenied(call, context, e.code(), e.getMessage(), startedAt);
        }

        ExecutionOutcome outcome = execute(prepared, context);
        if (outcome.output() == null) {
            return finalizeDenied(call, context, outcome.code(), outcome.errorMessage(), startedAt);
        }
        if (!validate(prepared.tool().outputSchema(), outcome.output()).isEmpty()) {
            return finalizeDenied(call, context, "INVALID_OUTPUT", "工具返回结果不符合 Schema", startedAt);
        }
        return finalizeSuccess(prepared, context, outcome.output(), outcome.code(), startedAt);
    }

    /**
     * 调用前门禁：Schema / deny / plan / 白名单 / RBAC / 预检 / 审批 / 确认 / 依赖。
     */
    private PreparedCall prepare(ToolSnapshot snapshot, ToolCall call, ExecutionContext context) {
        ToolDefinition tool = snapshot.get(call.name());
        if (tool == null) {
            throw new PolicyDenied("TOOL_NOT_FOUND", "工具不在当前快照中");
        }
        ToolPolicy policy = tool.policy();

        if (enabled != null && Boolean.FALSE.equals(enabled.get(tool.name()))) {
            throw new PolicyDenied("TOOL_DISABLED", "工具当前已停用");
        }

        JsonNode params = parseArguments(call.argumentsJson());
        if (params == null) {
            throw new PolicyDenied("INVALID_ARGUMENT", "工具参数不是合法 JSON");
        }
        if (!validate(tool.inputSchema(), params).isEmpty()) {
            throw new PolicyDenied("INVALID_ARGUMENT", "工具参数不符合 Schema");
        }

        if (policy.denied() || !policy.enabled()) {
            throw new PolicyDenied("DENY_RULE", "命中 deny 规则");
        }
        if (context.mode() == PermissionMode.PLAN
                && (policy.effect() == Effect.WRITE || policy.effect() == Effect.SHELL)) {
            throw new PolicyDenied("PLAN_MODE_DENIED", "plan 模式禁止写操作和 Shell");
        }
        if (!context.allowedTools().isEmpty() && !context.allowedTools().contains(tool.name())) {
            throw new PolicyDenied("TOOL_NOT_ALLOWED", "工具不在本轮白名单");
        }

        PermissionDecision decision = permissionEngine.decide(tool, context);
        if (decision == PermissionDecision.DENY) {
            throw new PolicyDenied("PERMISSION_DENIED", "调用方没有所需权限");
        }

        if (tool.precheck() != null) {
            try {
                tool.precheck().precheck(params, context);
            } catch (PolicyDenied e) {
                throw e;
            } catch (Exception e) {
                throw new PolicyDenied("BUSINESS_RULE_DENIED", "业务预检查失败");
            }
        }

        if (policy.requiresApproval()) {
            try {
                approvals.consume(context.approvalId(), tool, params, context);
            } catch (PolicyDenied e) {
                throw e;
            }
        }
        if (policy.requiresConfirmation() && context.mode() != PermissionMode.BYPASS_PERMISSIONS) {
            throw new PolicyDenied("CONFIRMATION_REQUIRED", "该操作需要用户确认");
        }
        for (String dependency : tool.dependencies()) {
            if (!healthyDependencies.contains(dependency)) {
                throw new PolicyDenied("DEPENDENCY_UNAVAILABLE", "工具依赖当前不可用");
            }
        }
        return new PreparedCall(call, tool, params, decision);
    }

    /**
     * 调用中受控执行：读写分治、写锁、超时与有限重试。
     */
    private ExecutionOutcome execute(PreparedCall prepared, ExecutionContext context) {
        return executeWithLock(prepared.tool(), prepared.arguments(), context);
    }

    /**
     * 调用后成功保护：输出校验通过后构造 ToolResult 并写入审计。
     */
    private ToolResult finalizeSuccess(PreparedCall prepared, ExecutionContext context,
                                       JsonNode output, String code, long startedAt) {
        ToolResult result = new ToolResult(
                prepared.call().id(), prepared.tool().name(), output.toString(), false, null);
        audit.write(auditRecord(prepared.call(), context, prepared.tool().name(), true, code, startedAt));
        return result;
    }

    /**
     * 批量调度：纯读并发；混入写操作时整批串行，保住模型给出的因果顺序。
     */
    public List<ToolResult> invokeBatch(ToolSnapshot snapshot, List<ToolCall> calls, ExecutionContext context) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        boolean hasWrite = calls.stream()
                .map(call -> snapshot.get(call.name()))
                .anyMatch(tool -> tool != null && tool.policy().effect() == Effect.WRITE);
        if (hasWrite) {
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : calls) {
                results.add(invoke(snapshot, call, context));
            }
            return results;
        }

        try (ExecutorService batchExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResult>> futures = new ArrayList<>();
            for (ToolCall call : calls) {
                futures.add(batchExecutor.submit(() -> invoke(snapshot, call, context)));
            }
            List<ToolResult> results = new ArrayList<>();
            for (Future<ToolResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("批量执行失败", e);
        }
    }

    /**
     * 执行结果：成功时 output 非空、错误码为空；失败时相反。
     */
    private record ExecutionOutcome(JsonNode output, String code, String errorMessage) {
    }

    private ExecutionOutcome executeWithLock(ToolDefinition tool, JsonNode params, ExecutionContext context) {
        if (tool.policy().effect() == Effect.READ) {
            return executeWithRetry(tool, params, context);
        }
        String key = tool.canonicalTarget() == null
                ? tool.name() + ":" + context.tenantId()
                : tool.canonicalTarget().target(params, context);
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            return executeWithRetry(tool, params, context);
        }
    }

    private ExecutionOutcome executeWithRetry(ToolDefinition tool, JsonNode params, ExecutionContext context) {
        ToolPolicy policy = tool.policy();
        long timeoutMillis = (long) (policy.timeoutSeconds() * 1000);
        int totalAttempts = policy.maxRetries() + 1;
        String lastErrorCode = null;
        String lastErrorMessage = null;
        boolean lastRetryable = false;

        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            Future<JsonNode> future = null;
            try {
                future = executor.submit(() -> tool.handler().handle(params, context));
                JsonNode output = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                return new ExecutionOutcome(output, "OK", null);
            } catch (TimeoutException e) {
                cancel(future);
                lastErrorCode = "TIMEOUT";
                lastErrorMessage = "工具调用超时";
                // 超时意味着结果不可知：只允许「只读」操作重试，写操作即使被误标为
                // idempotent 也绝不重放，避免重复下单/扣款等幽灵副作用。
                lastRetryable = policy.effect() == Effect.READ;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof TransientToolError) {
                    lastErrorCode = "UPSTREAM_ERROR";
                    lastErrorMessage = cause.getMessage() == null ? "上游服务暂时不可用" : cause.getMessage();
                    lastRetryable = policy.idempotent();
                } else {
                    lastErrorCode = "EXECUTION_FAILED";
                    lastErrorMessage = "工具执行失败";
                    lastRetryable = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastErrorCode = "EXECUTION_FAILED";
                lastErrorMessage = "工具执行被中断";
                lastRetryable = false;
            }

            if (!lastRetryable || attempt == totalAttempts) {
                break;
            }
            sleepBackoff(attempt);
        }

        if (tool.fallbackHandler() != null && lastRetryable) {
            return executeFallback(tool, params, context, timeoutMillis);
        }
        return new ExecutionOutcome(null, lastErrorCode, lastErrorMessage);
    }

    /**
     * 最后一道防线：主执行因可重试错误失败时降级执行，自身超时不再进入重试。
     */
    private ExecutionOutcome executeFallback(ToolDefinition tool, JsonNode params,
                                             ExecutionContext context, long timeoutMillis) {
        Future<JsonNode> future = null;
        try {
            future = executor.submit(() -> tool.fallbackHandler().handle(params, context));
            JsonNode output = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return new ExecutionOutcome(output, "FALLBACK_OK", null);
        } catch (TimeoutException e) {
            cancel(future);
            return new ExecutionOutcome(null, "TIMEOUT", "降级处理超时");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return new ExecutionOutcome(null, "FALLBACK_FAILED",
                    cause.getMessage() == null ? "降级处理失败" : cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecutionOutcome(null, "FALLBACK_FAILED", "降级处理被中断");
        }
    }

    private void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private ToolResult finalizeDenied(ToolCall call, ExecutionContext context,
                                      String code, String message, long startedAt) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("code", code);
        node.put("message", message);
        audit.write(auditRecord(call, context, call.name(), false, code, startedAt));
        return new ToolResult(call.id(), call.name(), node.toString(), true, code);
    }

    private Map<String, Object> auditRecord(ToolCall call, ExecutionContext context,
                                            String toolName, boolean ok, String code, long startedAt) {
        double durationMs = (System.nanoTime() - startedAt) / 1_000_000.0;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("trace_id", context.traceId());
        record.put("tool_call_id", call.id());
        record.put("tool_name", toolName);
        record.put("ok", ok);
        record.put("code", code);
        record.put("error_code", ok ? null : code);
        record.put("duration_ms", durationMs);
        return record;
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            return null;
        }
    }

    private Set<ValidationMessage> validate(JsonNode schemaNode, JsonNode instance) {
        if (schemaNode == null || schemaNode.isMissingNode()) {
            return Set.of();
        }
        JsonSchema schema = schemaFactory.getSchema(schemaNode);
        return schema.validate(instance);
    }

    private void sleepBackoff(int attempt) {
        long backoffMs = Math.min(250L * (1L << (attempt - 1)), 2000L);
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }
}
