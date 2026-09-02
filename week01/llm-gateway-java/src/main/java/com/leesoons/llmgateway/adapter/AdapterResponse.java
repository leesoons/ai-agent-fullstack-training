package com.leesoons.llmgateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.leesoons.llmgateway.model.TokenUsage;

/**
 * Adapter 归一化后的非流式结果。
 */
public class AdapterResponse {

    private String content;
    private JsonNode parsed;
    private TokenUsage usage;

    public AdapterResponse(String content, JsonNode parsed, TokenUsage usage) {
        this.content = content;
        this.parsed = parsed;
        this.usage = usage;
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
}
