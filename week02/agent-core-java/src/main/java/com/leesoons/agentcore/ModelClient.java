package com.leesoons.agentcore;

import com.leesoons.toolruntime.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * 模型调用抽象。Agent Loop 只依赖这个接口，不直接耦合 Gateway 或具体供应商。
 */
public interface ModelClient {

    AssistantTurn call(List<LoopMessage> messages, List<Map<String, Object>> tools);

    /**
     * 一轮模型输出：要么是最终文本，要么是一组工具调用提议。
     *
     * <p>{@code usage} 为本轮消耗的 Token / 费用，供 {@code LoopGuard} 累计预算；
     * 无用量信息时为 {@code null}。</p>
     */
    record AssistantTurn(String content, List<ToolCall> toolCalls, Usage usage) {

        public AssistantTurn(String content, List<ToolCall> toolCalls) {
            this(content, toolCalls, null);
        }

        public boolean wantsTools() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * 一轮模型的资源消耗。
     */
    record Usage(long totalTokens, double costUsd) {
    }
}
