package com.leesoons.toolruntime;

/**
 * Agent Run 的资源预算：六条硬线，全部来自配置而非写死。
 *
 * <p>由 {@link LoopGuard} 在「动作级」与「循环级」两个时机消费。</p>
 */
public record LoopBudgetConfig(
        int maxTurns,
        int maxToolCalls,
        int maxRepeatedCall,
        long maxTotalTokens,
        double maxCostUsd,
        long maxDurationMs) {

    public LoopBudgetConfig {
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns 必须大于 0");
        }
        if (maxToolCalls <= 0) {
            throw new IllegalArgumentException("maxToolCalls 必须大于 0");
        }
        if (maxRepeatedCall < 0) {
            throw new IllegalArgumentException("maxRepeatedCall 不能为负数");
        }
        if (maxTotalTokens <= 0) {
            throw new IllegalArgumentException("maxTotalTokens 必须大于 0");
        }
        if (maxCostUsd < 0) {
            throw new IllegalArgumentException("maxCostUsd 不能为负数");
        }
        if (maxDurationMs <= 0) {
            throw new IllegalArgumentException("maxDurationMs 必须大于 0");
        }
    }

    /**
     * 只限制轮数的宽松预算，其余维度给足余量；供仅关心轮数的调用方使用。
     */
    public static LoopBudgetConfig ofTurns(int maxTurns) {
        return new LoopBudgetConfig(maxTurns, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE, Double.MAX_VALUE, Long.MAX_VALUE);
    }
}
