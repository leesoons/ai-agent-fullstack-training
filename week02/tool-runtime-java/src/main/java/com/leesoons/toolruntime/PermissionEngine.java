package com.leesoons.toolruntime;

/**
 * 调用前授权决策组件：按白名单与 RBAC 判定工具是否可执行。
 *
 * <p>白名单和 RBAC 是 {@code BYPASS_PERMISSIONS} 也不能跳过的硬边界。</p>
 */
public class PermissionEngine {

    /**
     * 判定当前上下文是否有权调用该工具。
     *
     * <p>{@code CONFIRM} 在本实现中保留给需要用户确认的工具，
     * 由 {@link ToolRuntime} 在后续阶段触发。</p>
     */
    public PermissionDecision decide(ToolDefinition tool, ExecutionContext context) {
        if (!context.allowedTools().isEmpty() && !context.allowedTools().contains(tool.name())) {
            return PermissionDecision.DENY;
        }
        String permission = tool.policy().permission();
        if (permission != null && !context.permissions().contains(permission)) {
            return PermissionDecision.DENY;
        }
        return PermissionDecision.ALLOW;
    }
}
