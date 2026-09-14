package com.leesoons.llmgateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.leesoons.llmgateway.model.TokenUsage;

/**
 * OpenAI 兼容的 Token 用量对象。
 */
public class OpenAiUsage {

    @JsonProperty("prompt_tokens")
    private int promptTokens;

    @JsonProperty("completion_tokens")
    private int completionTokens;

    @JsonProperty("total_tokens")
    private int totalTokens;

    public static OpenAiUsage from(TokenUsage usage) {
        OpenAiUsage result = new OpenAiUsage();
        result.promptTokens = usage.getInputTokens();
        result.completionTokens = usage.getOutputTokens();
        result.totalTokens = usage.getTotalTokens();
        return result;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }
}
