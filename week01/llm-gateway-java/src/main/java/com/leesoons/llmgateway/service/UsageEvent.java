package com.leesoons.llmgateway.service;

/**
 * 一次调用的可观测性记录：Token 分类统计、延迟、TTFT、重试与回退。
 */
public class UsageEvent {

    private String requestId;
    private String apiKeyHash;
    private String requestedModel;
    private String provider;
    private String upstreamModel;
    private boolean stream;
    private String status = "success";
    private int statusCode = 200;
    private int inputTokens;
    private int outputTokens;
    private int cachedTokens;
    private double costUsd;
    private double latencyMs;
    private Double firstTokenMs;
    private int retries;
    private int fallbacks;
    private String errorType;
    private String errorMessage;
    private String promptId;
    private Integer promptVersion;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public String getRequestedModel() {
        return requestedModel;
    }

    public void setRequestedModel(String requestedModel) {
        this.requestedModel = requestedModel;
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

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
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

    public int getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(int cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public double getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(double costUsd) {
        this.costUsd = costUsd;
    }

    public double getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(double latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Double getFirstTokenMs() {
        return firstTokenMs;
    }

    public void setFirstTokenMs(Double firstTokenMs) {
        this.firstTokenMs = firstTokenMs;
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

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getPromptId() {
        return promptId;
    }

    public void setPromptId(String promptId) {
        this.promptId = promptId;
    }

    public Integer getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(Integer promptVersion) {
        this.promptVersion = promptVersion;
    }
}
