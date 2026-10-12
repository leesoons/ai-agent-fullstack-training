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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic Messages API 适配器：deepseek-v4-flash -> /v1/messages。
 */
@Component
public class AnthropicMessagesAdapter implements LlmAdapter {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final UpstreamClient upstream;
    private final ObjectMapper objectMapper;

    public AnthropicMessagesAdapter(UpstreamClient upstream, ObjectMapper objectMapper) {
        this.upstream = upstream;
        this.objectMapper = objectMapper;
    }

    @Override
    public AdapterProtocol protocol() {
        return AdapterProtocol.ANTHROPIC_MESSAGES;
    }

    @Override
    public Mono<AdapterResponse> call(GatewayProperties.RouteTarget route,
                                      GatewayProperties.Provider provider,
                                      ChatRequest request,
                                      String requestId) {
        ObjectNode body = buildBody(route, request, false);
        return upstream.requestJson(provider, "/v1/messages", body, authHeaders(provider), requestId)
                .map(this::toAdapterResponse);
    }

    @Override
    public Flux<AdapterStreamFrame> stream(GatewayProperties.RouteTarget route,
                                           GatewayProperties.Provider provider,
                                           ChatRequest request,
                                           String requestId) {
        ObjectNode body = buildBody(route, request, true);
        AtomicReference<TokenUsage> usage = new AtomicReference<>(new TokenUsage(0, 0, 0));
        return upstream.openStream(provider, "/v1/messages", body, authHeaders(provider), requestId)
                .mapNotNull(event -> parseStreamEvent(event.data(), usage));
    }

    private ObjectNode buildBody(GatewayProperties.RouteTarget route, ChatRequest request, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", route.getModel());
        body.put("stream", stream);
        body.put("max_tokens", request.getMaxTokens() == null ? 1024 : request.getMaxTokens());

        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }

        StringBuilder system = new StringBuilder();
        ArrayNode messages = objectMapper.createArrayNode();
        for (ChatMessage message : request.getMessages()) {
            String role = message.getRole() == null ? "user" : message.getRole();
            String content = message.getContent() == null ? "" : message.getContent();
            if ("system".equals(role) || "developer".equals(role)) {
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                system.append(content);
            } else if ("tool".equals(role)) {
                // 工具结果 -> role=user 的 tool_result 块
                ObjectNode item = objectMapper.createObjectNode();
                item.put("role", "user");
                ArrayNode blocks = objectMapper.createArrayNode();
                ObjectNode result = objectMapper.createObjectNode();
                result.put("type", "tool_result");
                result.put("tool_use_id", message.getToolCallId() == null ? "" : message.getToolCallId());
                result.put("content", content);
                blocks.add(result);
                item.set("content", blocks);
                messages.add(item);
            } else if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                // assistant 工具提议 -> tool_use 块
                ObjectNode item = objectMapper.createObjectNode();
                item.put("role", "assistant");
                ArrayNode blocks = objectMapper.createArrayNode();
                for (ToolCallMessage call : message.getToolCalls()) {
                    ObjectNode block = objectMapper.createObjectNode();
                    block.put("type", "tool_use");
                    block.put("id", call.getId());
                    block.put("name", call.getName());
                    JsonNode input = parseJson(call.getArguments());
                    block.set("input", input == null ? objectMapper.createObjectNode() : input);
                    blocks.add(block);
                }
                item.set("content", blocks);
                messages.add(item);
            } else {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("role", role);
                item.put("content", content);
                messages.add(item);
            }
        }
        if (messages.isEmpty()) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("role", "user");
            fallback.put("content", "");
            messages.add(fallback);
        }
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        body.set("messages", messages);

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            ArrayNode tools = objectMapper.createArrayNode();
            for (JsonNode tool : request.getTools()) {
                JsonNode fn = tool.path("function");
                ObjectNode item = objectMapper.createObjectNode();
                item.put("name", fn.path("name").asText());
                item.put("description", fn.path("description").asText(""));
                if (fn.hasNonNull("parameters")) {
                    item.set("input_schema", fn.get("parameters"));
                }
                tools.add(item);
            }
            body.set("tools", tools);
        }
        return body;
    }

    private AdapterResponse toAdapterResponse(JsonNode json) {
        return new AdapterResponse(extractContent(json), null, extractUsage(json), extractToolCalls(json));
    }

    private List<ToolCallMessage> extractToolCalls(JsonNode json) {
        JsonNode content = json.get("content");
        if (content == null || !content.isArray()) {
            return List.of();
        }
        List<ToolCallMessage> calls = new ArrayList<>();
        for (JsonNode part : content) {
            if ("tool_use".equals(part.path("type").asText(""))) {
                String arguments = part.hasNonNull("input") ? part.get("input").toString() : "{}";
                calls.add(new ToolCallMessage(
                        part.path("id").asText(""),
                        part.path("name").asText(""),
                        arguments));
            }
        }
        return calls;
    }

    private String extractContent(JsonNode json) {
        JsonNode content = json.get("content");
        if (content != null && content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText("")) && part.path("text").isTextual()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part.get("text").asText());
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        return null;
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private TokenUsage extractUsage(JsonNode json) {
        JsonNode usage = json.path("usage");
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int cached = usage.path("cache_read_input_tokens").asInt(0)
                + usage.path("cache_creation_input_tokens").asInt(0);
        return new TokenUsage(input, output, cached);
    }

    private AdapterStreamFrame parseStreamEvent(String data, AtomicReference<TokenUsage> usage) {
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
            case "message_start":
                JsonNode usageNode = json.path("message").path("usage");
                int input = usageNode.path("input_tokens").asInt(0);
                int cached = usageNode.path("cache_read_input_tokens").asInt(0)
                        + usageNode.path("cache_creation_input_tokens").asInt(0);
                usage.set(new TokenUsage(input, 0, cached));
                return null;
            case "content_block_delta":
                JsonNode delta = json.path("delta");
                if ("text_delta".equals(delta.path("type").asText("")) && delta.path("text").isTextual()) {
                    return AdapterStreamFrame.delta(delta.get("text").asText());
                }
                return null;
            case "message_delta":
                int output = json.path("usage").path("output_tokens").asInt(0);
                TokenUsage current = usage.get();
                current.setOutputTokens(output);
                return null;
            case "message_stop":
                return AdapterStreamFrame.complete(usage.get());
            case "error":
                return AdapterStreamFrame.error(json.path("error").path("message").asText("upstream stream error"));
            default:
                return null;
        }
    }

    private Map<String, String> authHeaders(GatewayProperties.Provider provider) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            headers.put("x-api-key", provider.getApiKey());
        }
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        return headers;
    }
}
