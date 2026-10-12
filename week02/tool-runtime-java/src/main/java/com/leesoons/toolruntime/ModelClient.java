package com.leesoons.toolruntime;

import java.util.List;
import java.util.Map;

/**
 * 模型调用抽象。Agent Loop 只依赖这个接口，不直接耦合 Gateway 或具体供应商。
 *
 * <p>3.4 会提供 {@code GatewayModelClient} 实现，把 Gateway 包进来。</p>
 */
public interface ModelClient {

    AssistantTurn call(List<LoopMessage> messages, List<Map<String, Object>> tools);

    /**
     * 一轮模型输出：要么是最终文本，要么是一组工具调用提议。
     */
    record AssistantTurn(String content, List<ToolCall> toolCalls) {

        public boolean wantsTools() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
