package com.leesoons.llmgateway.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.model.ChatMessage;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.model.ChatResponse;
import com.leesoons.llmgateway.model.ResponseFormat;
import com.leesoons.llmgateway.model.StreamEvent;
import com.leesoons.llmgateway.model.TokenUsage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI Compatible API 与网关内部领域模型之间的转换器。
 * 兼容请求在 API Layer 终止，内部业务逻辑只依赖统一模型。
 */
@Component
public class OpenAiProtocolMapper {

    private final ObjectMapper objectMapper;

    public OpenAiProtocolMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChatRequest toChatRequest(OpenAiChatCompletionRequest source) {
        ChatRequest target = new ChatRequest();
        target.setModel(source.getModel());
        target.setMessages(source.getMessages().stream()
                .map(message -> new ChatMessage(message.getRole(), contentToText(message.getContent())))
                .toList());
        target.setStream(source.isStream());
        target.setTemperature(source.getTemperature());
        target.setTopP(source.getTopP());
        target.setMaxTokens(source.getMaxTokens());
        target.setResponseFormat(mapResponseFormat(source.getResponseFormat()));
        target.setPrompt(source.getGatewayPrompt());
        return target;
    }

    public ChatRequest toResponsesRequest(OpenAiResponsesRequest source) {
        ChatRequest target = new ChatRequest();
        target.setModel(source.getModel());
        target.setMessages(messagesFromResponsesInput(source.getInput()));
        target.setStream(source.isStream());
        target.setTemperature(source.getTemperature());
        target.setTopP(source.getTopP());
        target.setMaxTokens(source.getMaxOutputTokens());
        target.setResponseFormat(mapTextFormat(source.getText()));
        target.setPrompt(source.getGatewayPrompt());
        return target;
    }

    public OpenAiChatCompletionResponse toChatCompletionResponse(ChatResponse source) {
        TokenUsage usage = source.getUsage() == null ? new TokenUsage() : source.getUsage();
        return new OpenAiChatCompletionResponse(
                source.getRequestId(),
                "chat.completion",
                System.currentTimeMillis() / 1000,
                source.getModel(),
                List.of(new OpenAiChatCompletionResponse.Choice(
                        0,
                        new OpenAiChatCompletionResponse.Message("assistant", source.getContent()),
                        "stop")),
                OpenAiUsage.from(usage));
    }

    public OpenAiResponsesResponse toResponsesResponse(ChatResponse source) {
        TokenUsage usage = source.getUsage() == null ? new TokenUsage() : source.getUsage();
        return new OpenAiResponsesResponse(
                source.getRequestId(),
                "response",
                System.currentTimeMillis() / 1000,
                source.getModel(),
                source.getContent(),
                "completed",
                OpenAiUsage.from(usage));
    }

    public Flux<String> toChatChunkStream(Flux<ServerSentEvent<StreamEvent>> upstream, String model) {
        AtomicReference<String> chunkId = new AtomicReference<>(
                "chatcmpl_" + UUID.randomUUID().toString().replace("-", ""));
        return upstream
                .doOnNext(sse -> captureRequestId(sse, chunkId))
                .concatMap(sse -> {
                    String type = sse.data().getType();
                    Object data = sse.data().getData();
                    if ("meta".equals(type)) {
                        return Flux.empty();
                    }
                    if ("delta".equals(type)) {
                        String content = valueOf(((Map<?, ?>) data).get("content"));
                        return Flux.just(sseJson(chatChunk(chunkId.get(), model, content, null)));
                    }
                    if ("done".equals(type)) {
                        return Flux.just(sseJson(chatChunk(chunkId.get(), model, null, "stop")))
                                .concatWith(Flux.just("data: [DONE]\n\n"));
                    }
                    if ("error".equals(type)) {
                        Object error = ((Map<?, ?>) data).get("error");
                        return Flux.just(sseJson(Map.of("error", error)))
                                .concatWith(Flux.just("data: [DONE]\n\n"));
                    }
                    return Flux.empty();
                });
    }

    public Flux<String> toResponsesChunkStream(Flux<ServerSentEvent<StreamEvent>> upstream, String model) {
        return upstream.concatMap(sse -> {
            String type = sse.data().getType();
            Object data = sse.data().getData();
            if ("meta".equals(type)) {
                return Flux.empty();
            }
            if ("delta".equals(type)) {
                String content = valueOf(((Map<?, ?>) data).get("content"));
                return Flux.just(sseJson(Map.of(
                        "type", "response.output_text.delta",
                        "delta", content)));
            }
            if ("done".equals(type)) {
                return Flux.just(sseJson(Map.of(
                        "type", "response.completed",
                        "response", Map.of("model", model))));
            }
            if ("error".equals(type)) {
                Object error = ((Map<?, ?>) data).get("error");
                return Flux.just(sseJson(Map.of("type", "error", "error", error)));
            }
            return Flux.empty();
        });
    }

    private void captureRequestId(ServerSentEvent<StreamEvent> sse, AtomicReference<String> chunkId) {
        StreamEvent event = sse.data();
        if ("meta".equals(event.getType()) && event.getData() instanceof Map<?, ?> meta
                && meta.get("request_id") != null) {
            chunkId.set(String.valueOf(meta.get("request_id")));
        }
    }

    private Map<String, Object> chatChunk(String id, String model, String content, String finishReason) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);

        Map<String, Object> delta = new LinkedHashMap<>();
        if (content != null) {
            delta.put("content", content);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private ResponseFormat mapResponseFormat(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String type = node.path("type").asText(null);
        if (type == null) {
            return null;
        }
        ResponseFormat format = new ResponseFormat();
        format.setType(type);
        if ("json_schema".equals(type)) {
            JsonNode jsonSchema = node.path("json_schema");
            format.setName(jsonSchema.path("name").asText("agent_response"));
            format.setStrict(jsonSchema.path("strict").asBoolean(true));
            if (jsonSchema.has("schema")) {
                format.setSchema(jsonSchema.get("schema"));
            }
        }
        return format;
    }

    private ResponseFormat mapTextFormat(JsonNode text) {
        if (text == null || text.isNull()) {
            return null;
        }
        JsonNode formatNode = text.path("format");
        if (formatNode.isMissingNode() || formatNode.isNull()) {
            return null;
        }
        String type = formatNode.path("type").asText(null);
        if (type == null) {
            return null;
        }
        ResponseFormat format = new ResponseFormat();
        format.setType(type);
        if ("json_schema".equals(type)) {
            format.setName(formatNode.path("name").asText("agent_response"));
            format.setStrict(formatNode.path("strict").asBoolean(true));
            if (formatNode.has("schema")) {
                format.setSchema(formatNode.get("schema"));
            }
        }
        return format;
    }

    private List<ChatMessage> messagesFromResponsesInput(Object input) {
        if (input == null) {
            return List.of(new ChatMessage("user", ""));
        }
        if (input instanceof String text) {
            return List.of(new ChatMessage("user", text));
        }
        if (input instanceof List<?> items) {
            List<ChatMessage> messages = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> message && message.get("role") != null) {
                    messages.add(new ChatMessage(
                            String.valueOf(message.get("role")),
                            contentToText(message.get("content"))));
                } else {
                    messages.add(new ChatMessage("user", contentToText(item)));
                }
            }
            return messages;
        }
        return List.of(new ChatMessage("user", contentToText(input)));
    }

    private String contentToText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            return String.valueOf(content);
        }
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sseJson(Object payload) {
        try {
            return "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SSE payload", e);
        }
    }
}
