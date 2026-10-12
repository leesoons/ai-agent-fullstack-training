package com.leesoons.toolruntime;

import java.util.Set;

/**
 * 服务端可信执行上下文。权限与审批来自服务端注入，模型不能伪造。
 */
public record ExecutionContext(
        String traceId,
        String userId,
        String tenantId,
        Set<String> permissions,
        Set<String> approvedCallIds) {

    public ExecutionContext {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        approvedCallIds = approvedCallIds == null ? Set.of() : Set.copyOf(approvedCallIds);
    }

    public ExecutionContext(String traceId, String userId, String tenantId, Set<String> permissions) {
        this(traceId, userId, tenantId, permissions, Set.of());
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
