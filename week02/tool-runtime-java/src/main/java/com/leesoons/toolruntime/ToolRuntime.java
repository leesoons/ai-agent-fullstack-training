package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

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
 * <p>三阶段：prepare（查工具/校验/权限/风险/依赖）→ execute（超时执行 + 幂等重试）
 * → finalize（输出校验 + 结果封装）。</p>
 */
public final class ToolRuntime implements AutoCloseable {

    private final Map<String, Boolean> enabled;
    private final Set<String> healthyDependencies;
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public ToolRuntime(Map<String, Boolean> enabled, Set<String> healthyDependencies) {
        this(enabled, healthyDependencies, new ObjectMapper(), null);
    }

    public ToolRuntime(Map<String, Boolean> enabled,
                       Set<String> healthyDependencies,
                       ObjectMapper objectMapper,
                       ExecutorService executor) {
        this.enabled = enabled;
        this.healthyDependencies = healthyDependencies == null ? Set.of() : healthyDependencies;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.executor = executor == null ? Executors.newVirtualThreadPerTaskExecutor() : executor;
        this.ownsExecutor = executor == null;
    }

    /**
     * 执行一次工具调用，返回统一产物 {@link ToolResult}。
     */
    public ToolResult invoke(ToolSnapshot snapshot, ToolCall call, ExecutionContext context) {
        // ---------- prepare ----------
        ToolDefinition tool = snapshot.get(call.name());
        if (tool == null) {
            return error(call, "TOOL_NOT_FOUND", "工具不在当前快照中");
        }
        if (enabled != null && Boolean.FALSE.equals(enabled.get(tool.name()))) {
            return error(call, "TOOL_DISABLED", "工具当前已停用");
        }

        JsonNode params = parseArguments(call.argumentsJson());
        if (params == null) {
            return error(call, "INVALID_ARGUMENT", "工具参数不是合法 JSON");
        }
        if (!validate(tool.inputSchema(), params).isEmpty()) {
            return error(call, "INVALID_ARGUMENT", "工具参数不符合 Schema");
        }
        if (!context.hasPermission(tool.permission())) {
            return error(call, "PERMISSION_DENIED", "调用方没有所需权限");
        }
        if (tool.risk() == RiskLevel.HIGH && !context.approvedCallIds().contains(call.id())) {
            return error(call, "APPROVAL_REQUIRED", "高风险调用需要人工批准");
        }
        for (String dep : tool.dependencies()) {
            if (!healthyDependencies.contains(dep)) {
                return error(call, "DEPENDENCY_UNAVAILABLE", "工具依赖当前不可用");
            }
        }

        // ---------- execute ----------
        ExecutionOutcome outcome = executeWithRetry(tool, params, context);
        if (outcome.output() == null) {
            return error(call, outcome.errorCode(), outcome.errorMessage());
        }

        // ---------- finalize ----------
        if (!validate(tool.outputSchema(), outcome.output()).isEmpty()) {
            return error(call, "INVALID_OUTPUT", "工具返回结果不符合 Schema");
        }
        return new ToolResult(call.id(), tool.name(), outcome.output().toString(), false);
    }

    /**
     * 执行结果：成功时 output 非空、错误码为空；失败时相反。
     */
    private record ExecutionOutcome(JsonNode output, String errorCode, String errorMessage) {
    }

    private ExecutionOutcome executeWithRetry(ToolDefinition tool, JsonNode params, ExecutionContext context) {
        long timeoutMillis = (long) (tool.timeoutSeconds() * 1000);
        int totalAttempts = tool.maxRetries() + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            boolean retryable = false;
            String errorCode = null;
            String errorMessage = null;
            Future<JsonNode> future = null;
            try {
                future = executor.submit(() -> tool.handler().handle(params, context));
                JsonNode output = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                return new ExecutionOutcome(output, null, null);
            } catch (TimeoutException e) {
                if (future != null) {
                    future.cancel(true);
                }
                errorCode = "TIMEOUT";
                errorMessage = "工具调用超时";
                retryable = tool.idempotent();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof TransientToolError) {
                    errorCode = "UPSTREAM_ERROR";
                    errorMessage = cause.getMessage() == null ? "上游服务暂时不可用" : cause.getMessage();
                    retryable = tool.idempotent();
                } else {
                    errorCode = "EXECUTION_FAILED";
                    errorMessage = "工具执行失败";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errorCode = "EXECUTION_FAILED";
                errorMessage = "工具执行被中断";
            }

            if (!retryable || attempt == totalAttempts) {
                return new ExecutionOutcome(null, errorCode, errorMessage);
            }
            sleepBackoff(attempt);
        }
        return new ExecutionOutcome(null, "EXECUTION_FAILED", "工具执行失败");
    }

    private ToolResult error(ToolCall call, String code, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("code", code);
        node.put("message", message);
        return new ToolResult(call.id(), call.name(), node.toString(), true);
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
