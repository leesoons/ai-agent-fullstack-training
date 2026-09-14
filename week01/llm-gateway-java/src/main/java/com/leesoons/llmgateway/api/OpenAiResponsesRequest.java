package com.leesoons.llmgateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.leesoons.llmgateway.model.PromptReference;
import jakarta.validation.constraints.NotBlank;

/**
 * OpenAI Responses 兼容请求。input 与 text 在 API Layer 统一转成内部领域模型。
 */
public class OpenAiResponsesRequest {

    @NotBlank(message = "model must not be blank")
    private String model;

    private Object input;
    private boolean stream = false;
    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("max_output_tokens")
    private Integer maxOutputTokens;

    private JsonNode text;

    @JsonProperty("gateway_prompt")
    private PromptReference gatewayPrompt;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
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

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public JsonNode getText() {
        return text;
    }

    public void setText(JsonNode text) {
        this.text = text;
    }

    public PromptReference getGatewayPrompt() {
        return gatewayPrompt;
    }

    public void setGatewayPrompt(PromptReference gatewayPrompt) {
        this.gatewayPrompt = gatewayPrompt;
    }
}
