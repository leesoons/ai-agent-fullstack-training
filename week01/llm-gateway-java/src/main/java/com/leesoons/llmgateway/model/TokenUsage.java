package com.leesoons.llmgateway.model;

/**
 * 归一化后的 Token 消耗，包含分类统计。
 */
public class TokenUsage {

    private int inputTokens;
    private int outputTokens;
    private int cachedInputTokens;
    private int totalTokens;

    public TokenUsage() {
    }

    public TokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.totalTokens = inputTokens + outputTokens;
    }

    public void add(TokenUsage other) {
        this.inputTokens += other.inputTokens;
        this.outputTokens += other.outputTokens;
        this.cachedInputTokens += other.cachedInputTokens;
        this.totalTokens = this.inputTokens + this.outputTokens;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public int getCachedInputTokens() {
        return cachedInputTokens;
    }

    public void setCachedInputTokens(int cachedInputTokens) {
        this.cachedInputTokens = cachedInputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }
}
