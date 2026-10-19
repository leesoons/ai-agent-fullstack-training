package com.leesoons.toolruntime;

/**
 * 工具治理策略：集中声明副作用、风险、权限、审批与执行边界。
 *
 * <p>这些字段只由运行时使用，绝不投影给模型；模型只能看到
 * {@link ToolDefinition#schema()} 里的名称、描述与参数 Schema。</p>
 */
public record ToolPolicy(
        Effect effect,
        RiskLevel risk,
        String permission,
        boolean denied,
        boolean requiresConfirmation,
        boolean requiresApproval,
        double timeoutSeconds,
        int maxRetries,
        boolean idempotent,
        boolean enabled) {

    public ToolPolicy {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须大于 0");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负数");
        }
    }
}
