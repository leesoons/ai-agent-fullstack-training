package com.leesoons.llmgateway.api;

import com.leesoons.llmgateway.core.ApiKeyAuthenticator;
import com.leesoons.llmgateway.core.GatewayErrorCode;
import com.leesoons.llmgateway.core.GatewayException;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.service.GatewayService;
import com.leesoons.llmgateway.service.TraceContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenAI Compatible API 入口：把外部兼容协议在 API Layer 转成内部领域模型。
 */
@RestController
public class OpenAiCompatController {

    private final GatewayService gatewayService;
    private final OpenAiProtocolMapper protocolMapper;

    public OpenAiCompatController(GatewayService gatewayService, OpenAiProtocolMapper protocolMapper) {
        this.gatewayService = gatewayService;
        this.protocolMapper = protocolMapper;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<?>> chatCompletions(@Valid @RequestBody OpenAiChatCompletionRequest payload,
                                                   ServerWebExchange exchange) {
        String identity = identity(exchange);
        rejectStreamWithSchema(payload.isStream(), payload.getResponseFormat() != null);
        ChatRequest request = protocolMapper.toChatRequest(payload);
        TraceContext trace = trace(exchange);

        if (payload.isStream()) {
            Flux<String> stream = protocolMapper.toChatChunkStream(
                    gatewayService.stream(request, identity, "chat", trace), payload.getModel());
            return Mono.just(ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-transform")
                    .header("X-Accel-Buffering", "no")
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream));
        }
        return gatewayService.chat(request, identity, "chat", trace)
                .map(response -> ResponseEntity.ok()
                        .header("X-Request-ID", response.getRequestId())
                        .body(protocolMapper.toChatCompletionResponse(response)));
    }

    @PostMapping("/v1/responses")
    public Mono<ResponseEntity<?>> responses(@Valid @RequestBody OpenAiResponsesRequest payload,
                                             ServerWebExchange exchange) {
        String identity = identity(exchange);
        rejectStreamWithSchema(payload.isStream(), payload.getText() != null
                && payload.getText().hasNonNull("format"));
        ChatRequest request = protocolMapper.toResponsesRequest(payload);
        TraceContext trace = trace(exchange);

        if (payload.isStream()) {
            Flux<String> stream = protocolMapper.toResponsesChunkStream(
                    gatewayService.stream(request, identity, "responses", trace), payload.getModel());
            return Mono.just(ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-transform")
                    .header("X-Accel-Buffering", "no")
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream));
        }
        return gatewayService.chat(request, identity, "responses", trace)
                .map(response -> ResponseEntity.ok()
                        .header("X-Request-ID", response.getRequestId())
                        .body(protocolMapper.toResponsesResponse(response)));
    }

    private void rejectStreamWithSchema(boolean stream, boolean hasSchema) {
        if (stream && hasSchema) {
            throw new GatewayException("stream 与 structured output 不能同时使用",
                    HttpStatus.BAD_REQUEST, "invalid_request_error",
                    GatewayErrorCode.INVALID_REQUEST, "response_format");
        }
    }

    private TraceContext trace(ServerWebExchange exchange) {
        String runId = exchange.getRequest().getHeaders().getFirst("X-Run-ID");
        String stepId = exchange.getRequest().getHeaders().getFirst("X-Step-ID");
        return new TraceContext(runId, stepId);
    }

    private String identity(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ApiKeyAuthenticator.IDENTITY_ATTR);
        return value == null ? "anonymous" : value.toString();
    }
}
