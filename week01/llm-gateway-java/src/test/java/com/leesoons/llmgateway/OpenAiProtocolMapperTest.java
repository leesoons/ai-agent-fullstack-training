package com.leesoons.llmgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.api.OpenAiChatCompletionRequest;
import com.leesoons.llmgateway.api.OpenAiChatMessage;
import com.leesoons.llmgateway.api.OpenAiProtocolMapper;
import com.leesoons.llmgateway.api.OpenAiResponsesRequest;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.model.ChatResponse;
import com.leesoons.llmgateway.model.StreamEvent;
import com.leesoons.llmgateway.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiProtocolMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiProtocolMapper mapper = new OpenAiProtocolMapper(objectMapper);

    @Test
    void mapsChatCompletionRequestToInternalRequest() throws Exception {
        OpenAiChatCompletionRequest source = new OpenAiChatCompletionRequest();
        source.setModel("smart");
        source.setMessages(List.of(message("user", "hello")));
        source.setStream(false);
        source.setResponseFormat(objectMapper.readTree("""
                {"type":"json_schema","json_schema":{"name":"person","strict":true,
                 "schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}
                """));

        ChatRequest target = mapper.toChatRequest(source);

        assertThat(target.getModel()).isEqualTo("smart");
        assertThat(target.getMessages()).extracting(m -> m.getRole()).containsExactly("user");
        assertThat(target.getResponseFormat().isJsonSchema()).isTrue();
        assertThat(target.getResponseFormat().getName()).isEqualTo("person");
        assertThat(target.getResponseFormat().getSchema().path("required").get(0).asText()).isEqualTo("name");
    }

    @Test
    void mapsResponsesInputAndTextFormat() throws Exception {
        OpenAiResponsesRequest source = new OpenAiResponsesRequest();
        source.setModel("smart");
        source.setInput("what is a gateway?");
        source.setText(objectMapper.readTree("""
                {"format":{"type":"json_schema","name":"answer","strict":true,
                 "schema":{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}}}
                """));

        ChatRequest target = mapper.toResponsesRequest(source);

        assertThat(target.getMessages()).extracting(m -> m.getContent()).containsExactly("what is a gateway?");
        assertThat(target.getResponseFormat().getName()).isEqualTo("answer");
    }

    @Test
    void mapsInternalChatResponseToOpenAiShape() {
        ChatResponse source = new ChatResponse();
        source.setRequestId("req_1");
        source.setModel("smart");
        source.setContent("hi");
        source.setUsage(new TokenUsage(11, 7, 2));

        var response = mapper.toChatCompletionResponse(source);

        assertThat(response.object()).isEqualTo("chat.completion");
        assertThat(response.choices().get(0).message().content()).isEqualTo("hi");
        assertThat(response.usage().getPromptTokens()).isEqualTo(11);
        assertThat(response.usage().getCompletionTokens()).isEqualTo(7);
    }

    @Test
    void mapsInternalStreamToOpenAiSseChunks() {
        Flux<ServerSentEvent<StreamEvent>> upstream = Flux.just(
                sse("meta", java.util.Map.of("request_id", "req_stream", "model", "smart")),
                sse("delta", java.util.Map.of("content", "Hel")),
                sse("done", java.util.Map.of("model", "smart")));

        List<String> chunks = mapper.toChatChunkStream(upstream, "smart")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).contains("\"object\":\"chat.completion.chunk\"").contains("\"content\":\"Hel\"");
        assertThat(chunks.get(1)).contains("\"finish_reason\":\"stop\"");
        assertThat(chunks.get(2)).isEqualTo("data: [DONE]\n\n");
    }

    private OpenAiChatMessage message(String role, String content) {
        OpenAiChatMessage message = new OpenAiChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private ServerSentEvent<StreamEvent> sse(String type, Object data) {
        return ServerSentEvent.<StreamEvent>builder(new StreamEvent(type, data)).build();
    }
}
