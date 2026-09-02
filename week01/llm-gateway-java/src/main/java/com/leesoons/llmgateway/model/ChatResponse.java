package com.leesoons.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 网关统一非流式响应。
 */
public class ChatResponse {

    private String requestId;
    private String model;
    private String content;
    private JsonNode parsed;
    private TokenUsage usage;
    private long latencyMs;
    private Long ttftMs;
    private int attempts;
    private int retries;
    private int fallbacks;
    private String provider;
    private String upstreamModel;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public JsonNode getParsed() {
        return parsed;
    }

    public void setParsed(JsonNode parsed) {
        this.parsed = parsed;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public void setUsage(TokenUsage usage) {
        this.usage = usage;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Long getTtftMs() {
        return ttftMs;
    }

    public void setTtftMs(Long ttftMs) {
        this.ttftMs = ttftMs;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public int getFallbacks() {
        return fallbacks;
    }

    public void setFallbacks(int fallbacks) {
        this.fallbacks = fallbacks;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getUpstreamModel() {
        return upstreamModel;
    }

    public void setUpstreamModel(String upstreamModel) {
        this.upstreamModel = upstreamModel;
    }
}
