package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Run 级护栏：在「工具动作发生前」和「每轮结束后」两道关口控制循环。
 *
 * <p>{@link #beforeToolCall} 防单次动作越界（副作用发生前拦截）；{@link #shouldStop}
 * 防循环本身失控（预算耗尽即熔断）。二者叠加才是完整的 Agent 防线。</p>
 */
public final class LoopGuard {

    private final LoopBudgetConfig budget;
    private final long startedAtNanos = System.nanoTime();
    private final Map<String, Integer> repeatedCalls = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private int turns;
    private int toolCalls;
    private long totalTokens;
    private double costUsd;

    public LoopGuard(LoopBudgetConfig budget) {
        this.budget = budget;
    }

    /**
     * 工具执行前的闸门：预算耗尽或检测到重复调用时，在副作用产生前直接拒绝。
     */
    public GuardDecision beforeToolCall(String toolName, String argumentsJson) {
        if (elapsedMillis() >= budget.maxDurationMs()) {
            return new GuardDecision(false, "MAX_DURATION", "Agent Run 已超过总时限");
        }
        if (toolCalls >= budget.maxToolCalls()) {
            return new GuardDecision(false, "MAX_TOOL_CALLS", "工具调用预算已耗尽");
        }
        String signature = toolName + ":" + ApprovalStore.canonical(parse(argumentsJson));
        int count = repeatedCalls.getOrDefault(signature, 0) + 1;
        if (count > budget.maxRepeatedCall()) {
            return new GuardDecision(false, "REPEATED_TOOL_CALL", "检测到相同工具与参数的重复调用");
        }
        repeatedCalls.put(signature, count);
        toolCalls += 1;
        return new GuardDecision(true, "OK", "Loop 工具预算通过");
    }

    /**
     * 记录一轮模型消耗的 token 与费用（由 Agent Loop 在 assistant message 结束时上报）。
     */
    public void observeAssistantUsage(long tokens, double cost) {
        this.totalTokens += tokens;
        this.costUsd += cost;
    }

    /**
     * 记录一轮完成（由 Agent Loop 在每轮结束后上报）。
     */
    public void observeTurn() {
        this.turns += 1;
    }

    /**
     * 每轮结束后的熔断判断：等待审批时无条件停下，否则按预算判定是否继续。
     */
    public StopDecision shouldStop() {
        return shouldStop(null);
    }

    /**
     * @param pendingApprovalId 当前是否存在等待人工审批的审批单
     */
    public StopDecision shouldStop(String pendingApprovalId) {
        if (pendingApprovalId != null) {
            return new StopDecision(true, "AWAITING_APPROVAL", "工具调用正在等待人工审批");
        }
        if (turns >= budget.maxTurns()) {
            return new StopDecision(true, "MAX_TURNS", "Agent Loop 已达到最大轮数");
        }
        if (toolCalls >= budget.maxToolCalls()) {
            return new StopDecision(true, "MAX_TOOL_CALLS", "工具调用预算已耗尽");
        }
        if (totalTokens >= budget.maxTotalTokens()) {
            return new StopDecision(true, "MAX_TOKENS", "Token 预算已耗尽");
        }
        if (costUsd >= budget.maxCostUsd()) {
            return new StopDecision(true, "MAX_COST", "Cost 预算已耗尽");
        }
        if (elapsedMillis() >= budget.maxDurationMs()) {
            return new StopDecision(true, "MAX_DURATION", "Agent Run 已超过总时限");
        }
        return new StopDecision(false, "CONTINUE", "预算允许继续");
    }

    /**
     * 当前预算消耗快照，供调用方观测。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("turns", turns);
        snapshot.put("tool_calls", toolCalls);
        snapshot.put("total_tokens", totalTokens);
        snapshot.put("cost_usd", costUsd);
        snapshot.put("elapsed_ms", elapsedMillis());
        return snapshot;
    }

    private long elapsedMillis() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private JsonNode parse(String argumentsJson) {
        try {
            return objectMapper.readTree(argumentsJson == null ? "null" : argumentsJson);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(argumentsJson);
        }
    }

    /**
     * 工具执行前的闸门决策。
     */
    public record GuardDecision(boolean allow, String code, String reason) {
    }

    /**
     * 循环级熔断决策。
     */
    public record StopDecision(boolean stop, String code, String reason) {
    }
}
