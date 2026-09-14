package com.leesoons.llmgateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.leesoons.llmgateway.model.PromptReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * OpenAI Chat Completions 兼容请求，在 API Layer 终止后转为内部 ChatRequest。
 */
public class OpenAiChatCompletionRequest {

    @NotBlank(message = "model must not be blank")
    private String model;

    @NotEmpty(message = "messages must not be empty")
    @Valid
    private List<OpenAiChatMessage> messages;

    private boolean stream = false;
    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("response_format")
    private JsonNode responseFormat;

    @JsonProperty("gateway_prompt")
    private PromptReference gatewayPrompt;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<OpenAiChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<OpenAiChatMessage> messages) {
        this.messages = messages;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public JsonNode getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(JsonNode responseFormat) {
        this.responseFormat = responseFormat;
    }

    public PromptReference getGatewayPrompt() {
        return gatewayPrompt;
    }

    public void setGatewayPrompt(PromptReference gatewayPrompt) {
        this.gatewayPrompt = gatewayPrompt;
    }
}
