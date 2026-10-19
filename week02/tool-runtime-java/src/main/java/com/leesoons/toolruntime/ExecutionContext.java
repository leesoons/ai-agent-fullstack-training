package com.leesoons.toolruntime;

import java.util.Set;

/**
 * 服务端可信执行上下文。权限、白名单、模式、审批与幂等键来自服务端注入，模型不能伪造。
 */
public record ExecutionContext(
        String traceId,
        String userId,
        String tenantId,
        Set<String> permissions,
        Set<String> allowedTools,
        PermissionMode mode,
        String approvalId,
        String idempotencyKey) {

    public ExecutionContext {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        mode = mode == null ? PermissionMode.DEFAULT : mode;
    }

    /**
     * 兼容旧四参构造：白名单为空表示不启用白名单过滤，模式默认 {@code DEFAULT}。
     */
    public ExecutionContext(String traceId, String userId, String tenantId, Set<String> permissions) {
        this(traceId, userId, tenantId, permissions, Set.of(), PermissionMode.DEFAULT, null, null);
    }

    /**
     * 显式白名单与执行模式，供治理阶段使用。
     */
    public ExecutionContext(String traceId, String userId, String tenantId,
                            Set<String> permissions, Set<String> allowedTools, PermissionMode mode) {
        this(traceId, userId, tenantId, permissions, allowedTools, mode, null, null);
    }

    /**
     * 显式白名单、执行模式与一次性审批凭证。
     */
    public ExecutionContext(String traceId, String userId, String tenantId,
                            Set<String> permissions, Set<String> allowedTools,
                            PermissionMode mode, String approvalId) {
        this(traceId, userId, tenantId, permissions, allowedTools, mode, approvalId, null);
    }

    public boolean hasPermission(String permission) {
        // 无权限要求的工具（permission == null）对任何调用方开放。
        return permission == null || permissions.contains(permission);
    }
}
