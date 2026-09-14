package com.leesoons.llmgateway.controller;

import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.core.ApiKeyAuthenticator;
import com.leesoons.llmgateway.core.GatewayErrorCode;
import com.leesoons.llmgateway.core.GatewayException;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.model.ModelInfo;
import com.leesoons.llmgateway.model.PromptCreate;
import com.leesoons.llmgateway.model.PromptRecord;
import com.leesoons.llmgateway.model.PromptRender;
import com.leesoons.llmgateway.model.StreamEvent;
import com.leesoons.llmgateway.service.GatewayService;
import com.leesoons.llmgateway.service.PromptService;
import com.leesoons.llmgateway.service.RunService;
import com.leesoons.llmgateway.service.UsageEvent;
import com.leesoons.llmgateway.service.UsageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网关 HTTP 入口。
 */
@RestController
public class GatewayController {

    private final GatewayService gatewayService;
    private final PromptService promptService;
    private final UsageService usageService;
    private final GatewayProperties config;
    private final RunService runService;

    public GatewayController(GatewayService gatewayService,
                             PromptService promptService,
                             UsageService usageService,
                             GatewayProperties config,
                             RunService runService) {
        this.gatewayService = gatewayService;
        this.promptService = promptService;
        this.usageService = usageService;
        this.config = config;
        this.runService = runService;
    }

    @PostMapping("/v1/chat")
    public Mono<ResponseEntity<?>> chat(@Valid @RequestBody ChatRequest request, ServerWebExchange exchange) {
        // 流式输出与结构化输出互斥：流式只转发增量文本，无法在结束时对完整 JSON 做 Schema 校验。
        if (request.isStream() && request.getResponseFormat() != null) {
            throw new GatewayException("stream 与 response_format 不能同时使用",
                    HttpStatus.BAD_REQUEST, "invalid_request_error",
                    GatewayErrorCode.INVALID_REQUEST, "response_format");
        }
        String identity = identity(exchange);
        if (request.isStream()) {
            Flux<ServerSentEvent<StreamEvent>> stream = gatewayService.stream(request, identity);
            return Mono.just(ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-transform")
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream));
        }
        return gatewayService.chat(request, identity)
                .map(response -> ResponseEntity.ok()
                        .header("X-Request-ID", response.getRequestId())
                        .body(response));
    }

    @PostMapping("/v1/runs")
    public Mono<Map<String, Object>> createRun(@Valid @RequestBody ChatRequest request,
                                               ServerWebExchange exchange) {
        String identity = identity(exchange);
        return Mono.fromCallable(() -> {
            String runId = runService.create(request, identity);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("run_id", runId);
            result.put("status", runService.status(runId, identity));
            return result;
        });
    }

    @GetMapping("/v1/runs/{runId}/events")
    public Mono<ResponseEntity<Flux<ServerSentEvent<StreamEvent>>>> runEvents(
            @PathVariable String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            ServerWebExchange exchange) {
        String identity = identity(exchange);
        return Mono.defer(() -> {
            int afterSeq = parseAfterSeq(lastEventId);
            Flux<ServerSentEvent<StreamEvent>> events = runService.events(runId, afterSeq, identity)
                    .map(event -> ServerSentEvent.<StreamEvent>builder(
                                    new StreamEvent(event.type(), event.data()))
                            .id(String.valueOf(event.seq()))
                            .event(event.type())
                            .build());
            return Mono.just(ResponseEntity.ok()
                    .header("Cache-Control", "no-cache, no-transform")
                    .header("X-Accel-Buffering", "no")
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(events));
        });
    }

    @PostMapping("/v1/runs/{runId}/cancel")
    public Mono<Map<String, Object>> cancelRun(@PathVariable String runId, ServerWebExchange exchange) {
        String identity = identity(exchange);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("run_id", runId);
            result.put("status", runService.cancel(runId, identity));
            return result;
        });
    }

    private int parseAfterSeq(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(lastEventId.trim());
        } catch (NumberFormatException error) {
            throw new GatewayException("invalid Last-Event-ID",
                    HttpStatus.BAD_REQUEST, "invalid_request", GatewayErrorCode.INVALID_REQUEST, "last_event_id");
        }
    }

    @GetMapping("/v1/models")
    public Mono<Map<String, Object>> models() {
        long now = System.currentTimeMillis() / 1000;
        List<ModelInfo> data = new ArrayList<>();
        config.getModels().forEach((alias, route) -> {
            String protocol = route.getRoutes().isEmpty() ? null : route.getRoutes().get(0).getProtocol();
            data.add(new ModelInfo(alias, now, protocol));
        });
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", data);
        return Mono.just(result);
    }

    @PostMapping("/v1/prompts")
    public Mono<ResponseEntity<PromptRecord>> createPrompt(@Valid @RequestBody PromptCreate create) {
        return Mono.fromCallable(() -> promptService.createVersion(create))
                .map(record -> ResponseEntity.status(HttpStatus.CREATED).body(record));
    }

    @GetMapping("/v1/prompts")
    public Mono<List<PromptRecord>> listPrompts() {
        return Mono.fromCallable(promptService::list);
    }

    @GetMapping("/v1/prompts/{id}")
    public Mono<PromptRecord> getPrompt(@PathVariable String id,
                                        @RequestParam(required = false) Integer version) {
        return Mono.fromCallable(() -> promptService.get(id, version));
    }

    @PostMapping("/v1/prompts/{id}/render")
    public Mono<Map<String, Object>> renderPrompt(@PathVariable String id,
                                                  @Valid @RequestBody PromptRender render) {
        return Mono.fromCallable(() -> promptService.render(id, render.getVariables(), render.getVersion()))
                .map(rendered -> {
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("id", rendered.prompt().getId());
                    result.put("version", rendered.prompt().getVersion());
                    result.put("role", rendered.prompt().getRole());
                    result.put("content", rendered.content());
                    return result;
                });
    }

    @GetMapping("/admin/usage")
    public Mono<Map<String, Object>> usage(@RequestParam(defaultValue = "100") int limit) {
        List<UsageEvent> recent = usageService.recent(limit);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("data", recent);
        return Mono.just(result);
    }

    @GetMapping("/admin/routes")
    public Mono<Map<String, Object>> routes() {
        Map<String, Object> models = new java.util.LinkedHashMap<>();
        config.getModels().forEach((alias, route) -> models.put(alias, route.getRoutes()));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("models", models);
        result.put("circuits", config.getModels().keySet());
        return Mono.just(result);
    }

    @GetMapping("/health")
    public Mono<Map<String, String>> health() {
        return Mono.just(Map.of("status", "ok"));
    }

    private String identity(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ApiKeyAuthenticator.IDENTITY_ATTR);
        return value == null ? "anonymous" : value.toString();
    }
}
