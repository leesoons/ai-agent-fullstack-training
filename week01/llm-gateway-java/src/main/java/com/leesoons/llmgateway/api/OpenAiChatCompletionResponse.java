package com.leesoons.llmgateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Chat Completions 兼容响应。
 */
public record OpenAiChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        OpenAiUsage usage) {

    public record Choice(int index, Message message, @JsonProperty("finish_reason") String finishReason) {
    }

    public record Message(String role, String content) {
    }
}
