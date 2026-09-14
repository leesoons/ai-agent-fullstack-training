package com.leesoons.llmgateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI Responses 兼容响应。
 */
public record OpenAiResponsesResponse(
        String id,
        String object,
        @JsonProperty("created_at") long createdAt,
        String model,
        @JsonProperty("output_text") String outputText,
        String status,
        OpenAiUsage usage) {
}
