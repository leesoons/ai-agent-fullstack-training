package com.leesoons.llmgateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.leesoons.llmgateway.model.TokenUsage;
import com.leesoons.llmgateway.model.ToolCallMessage;

import java.util.List;

/**
 * Adapter 归一化后的非流式结果。
 */
public class AdapterResponse {

    private final String content;
    private final JsonNode parsed;
    private final TokenUsage usage;
    private final List<ToolCallMessage> toolCalls;

    public AdapterResponse(String content, JsonNode parsed, TokenUsage usage) {
        this(content, parsed, usage, null);
    }

    public AdapterResponse(String content, JsonNode parsed, TokenUsage usage, List<ToolCallMessage> toolCalls) {
        this.content = content;
        this.parsed = parsed;
        this.usage = usage;
        this.toolCalls = toolCalls;
    }

    public String getContent() {
        return content;
    }

    public JsonNode getParsed() {
        return parsed;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public List<ToolCallMessage> getToolCalls() {
        return toolCalls;
    }
}
