package com.leesoons.llmgateway.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.model.ChatMessage;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.model.TokenUsage;
import com.leesoons.llmgateway.model.ToolCallMessage;
import com.leesoons.llmgateway.service.UpstreamClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API 适配器：deepseek-v4-pro -> /v1/responses。
 */
@Component
public class OpenAiResponsesAdapter implements LlmAdapter {

    private final UpstreamClient upstream;
    private final ObjectMapper objectMapper;

    public OpenAiResponsesAdapter(UpstreamClient upstream, ObjectMapper objectMapper) {
        this.upstream = upstream;
        this.objectMapper = objectMapper;
    }

    @Override
    public AdapterProtocol protocol() {
        return AdapterProtocol.OPENAI_RESPONSES;
    }

    @Override
    public Mono<AdapterResponse> call(GatewayProperties.RouteTarget route,
                                      GatewayProperties.Provider provider,
                                      ChatRequest request,
                                      String requestId) {
        ObjectNode body = buildBody(route, request, false);
        return upstream.requestJson(provider, "/v1/responses", body, authHeaders(provider), requestId)
                .map(this::toAdapterResponse);
    }

    @Override
    public Flux<AdapterStreamFrame> stream(GatewayProperties.RouteTarget route,
                                           GatewayProperties.Provider provider,
                                           ChatRequest request,
                                           String requestId) {
        ObjectNode body = buildBody(route, request, true);
        return upstream.openStream(provider, "/v1/responses", body, authHeaders(provider), requestId)
                .mapNotNull(event -> parseStreamEvent(event.data()));
    }

    private ObjectNode buildBody(GatewayProperties.RouteTarget route, ChatRequest request, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", route.getModel());
        body.put("stream", stream);

        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_output_tokens", request.getMaxTokens());
        }

        StringBuilder instructions = new StringBuilder();
        ArrayNode input = objectMapper.createArrayNode();
        for (ChatMessage message : request.getMessages()) {
            String role = message.getRole() == null ? "user" : message.getRole();
            String content = message.getContent() == null ? "" : message.getContent();
            if ("system".equals(role) || "developer".equals(role)) {
                if (!instructions.isEmpty()) {
                    instructions.append("\n\n");
                }
                instructions.append(content);
            } else if ("tool".equals(role)) {
                // 工具结果 -> function_call_output
                ObjectNode item = objectMapper.createObjectNode();
                item.put("type", "function_call_output");
                item.put("call_id", message.getToolCallId() == null ? "" : message.getToolCallId());
                item.put("output", content);
                input.add(item);
            } else if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                // assistant 工具提议 -> function_call
                for (ToolCallMessage call : message.getToolCalls()) {
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("type", "function_call");
                    item.put("id", call.getId());
                    item.put("call_id", call.getId());
                    item.put("name", call.getName());
                    item.put("arguments", call.getArguments() == null ? "{}" : call.getArguments());
                    input.add(item);
                }
            } else {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("role", role);
                item.put("content", content);
                input.add(item);
            }
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }
        body.set("input", input);

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            ArrayNode tools = objectMapper.createArrayNode();
            for (JsonNode tool : request.getTools()) {
                JsonNode fn = tool.path("function");
                ObjectNode item = objectMapper.createObjectNode();
                item.put("type", "function");
                item.put("name", fn.path("name").asText());
                item.put("description", fn.path("description").asText(""));
                if (fn.hasNonNull("parameters")) {
                    item.set("parameters", fn.get("parameters"));
                }
                tools.add(item);
            }
            body.set("tools", tools);
        }

        if (request.getResponseFormat() != null && request.getResponseFormat().isJsonSchema()) {
            ObjectNode format = objectMapper.createObjectNode();
            format.put("type", "json_schema");
            format.put("name", request.getResponseFormat().getName() == null
                    ? "json_response" : request.getResponseFormat().getName());
            format.put("strict", request.getResponseFormat().isStrict());
            format.set("schema", request.getResponseFormat().getSchema());
            ObjectNode text = objectMapper.createObjectNode();
            text.set("format", format);
            body.set("text", text);
        }
        return body;
    }

    private AdapterResponse toAdapterResponse(JsonNode json) {
        return new AdapterResponse(extractContent(json), null, extractUsage(json), extractToolCalls(json));
    }

    private List<ToolCallMessage> extractToolCalls(JsonNode json) {
        JsonNode output = json.get("output");
        if (output == null || !output.isArray()) {
            return List.of();
        }
        List<ToolCallMessage> calls = new ArrayList<>();
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asText(""))) {
                String id = item.path("call_id").asText(item.path("id").asText(""));
                calls.add(new ToolCallMessage(id,
                        item.path("name").asText(""),
                        item.path("arguments").asText("")));
            }
        }
        return calls;
    }

    private String extractContent(JsonNode json) {
        JsonNode outputText = json.get("output_text");
        if (outputText != null && outputText.isTextual()) {
            return outputText.asText();
        }
        JsonNode output = json.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode part : content) {
                        String type = part.path("type").asText("");
                        if (("output_text".equals(type) || "text".equals(type))
                                && part.path("text").isTextual()) {
                            return part.get("text").asText();
                        }
                    }
                }
            }
        }
        return null;
    }

    private TokenUsage extractUsage(JsonNode json) {
        JsonNode usage = json.path("usage");
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        JsonNode details = usage.has("input_tokens_details")
                ? usage.get("input_tokens_details") : usage.get("prompt_tokens_details");
        int cached = details == null ? 0 : details.path("cached_tokens").asInt(0);
        return new TokenUsage(input, output, cached);
    }

    private AdapterStreamFrame parseStreamEvent(String data) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return null;
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(data);
        } catch (Exception e) {
            return null;
        }
        String type = json.path("type").asText("");
        switch (type) {
            case "response.output_text.delta":
                String delta = json.path("delta").asText("");
                return delta.isEmpty() ? null : AdapterStreamFrame.delta(delta);
            case "response.completed":
                JsonNode response = json.get("response");
                return AdapterStreamFrame.complete(extractUsage(response == null ? json : response));
            case "error":
                return AdapterStreamFrame.error(json.path("error").path("message").asText("upstream stream error"));
            default:
                return null;
        }
    }

    private Map<String, String> authHeaders(GatewayProperties.Provider provider) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + provider.getApiKey());
        }
        return headers;
    }
}
