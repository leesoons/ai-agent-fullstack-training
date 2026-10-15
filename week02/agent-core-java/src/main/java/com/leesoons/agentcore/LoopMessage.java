package com.leesoons.agentcore;

import com.leesoons.toolruntime.ToolCall;

import java.util.List;

/**
 * Agent Loop 内部的消息表示。role 取值 system / user / assistant / tool。
 *
 * <p>assistant 消息携带 {@code toolCalls}，tool 消息携带 {@code toolCallId}。</p>
 */
public record LoopMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public static LoopMessage of(String role, String content) {
        return new LoopMessage(role, content, null, null);
    }

    public static LoopMessage assistant(List<ToolCall> toolCalls) {
        return new LoopMessage("assistant", null, toolCalls, null);
    }

    public static LoopMessage tool(String toolCallId, String content) {
        return new LoopMessage("tool", content, null, toolCallId);
    }
}
