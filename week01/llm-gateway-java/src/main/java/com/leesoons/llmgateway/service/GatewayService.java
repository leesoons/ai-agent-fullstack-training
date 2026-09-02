package com.leesoons.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leesoons.llmgateway.adapter.AdapterProtocol;
import com.leesoons.llmgateway.adapter.AdapterResponse;
import com.leesoons.llmgateway.adapter.AdapterStreamFrame;
import com.leesoons.llmgateway.adapter.LlmAdapter;
import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.core.GatewayErrorCode;
import com.leesoons.llmgateway.core.GatewayException;
import com.leesoons.llmgateway.core.PerModelRateLimiter;
import com.leesoons.llmgateway.core.StructuredOutputException;
import com.leesoons.llmgateway.core.UpstreamException;
import com.leesoons.llmgateway.model.ChatMessage;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.model.ChatResponse;
import com.leesoons.llmgateway.model.StreamEvent;
import com.leesoons.llmgateway.model.TokenUsage;
import com.leesoons.llmgateway.service.PromptService.RenderedPrompt;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网关编排核心：统一请求 -> 限流 -> Prompt 渲染 -> 路由 -> Adapter -> 重试/回退 -> 结构化校验。
 */
@Service
public class GatewayService {

    private final GatewayProperties config;
    private final ModelRouter router;
    private final PromptService prompts;
    private final UsageService usage;
    private final PerModelRateLimiter rateLimiter;
    private final StructuredOutputService structured;
    private final Map<AdapterProtocol, LlmAdapter> adapters;

    public GatewayService(GatewayProperties config,
                          ModelRouter router,
                          PromptService prompts,
                          UsageService usage,
                          PerModelRateLimiter rateLimiter,
                          StructuredOutputService structured,
                          List<LlmAdapter> adapterList) {
        this.config = config;
        this.router = router;
        this.prompts = prompts;
        this.usage = usage;
        this.rateLimiter = rateLimiter;
        this.structured = structured;
        this.adapters = new LinkedHashMap<>();
        for (LlmAdapter adapter : adapterList) {
            this.adapters.put(adapter.protocol(), adapter);
        }
    }

    public Mono<ChatResponse> chat(ChatRequest request, String identity) {
        String model = request.getModel();
        return rateLimiter.acquire(model)
                .then(Mono.fromCallable(() -> prepare(request)))
                .flatMap(prepared -> doChat(prepared, identity))
                .onErrorResume(error -> Mono.error(normalize(error)));
    }

    public Flux<ServerSentEvent<StreamEvent>> stream(ChatRequest request, String identity) {
        String model = request.getModel();
        return rateLimiter.acquire(model)
                .thenMany(Flux.defer(() -> doStream(prepare(request), identity)))
                .onErrorResume(error -> Flux.error(normalize(error)));
    }

    // ------------------------------------------------------------------
    // 非流式
    // ------------------------------------------------------------------

    private Mono<ChatResponse> doChat(Prepared prepared, String identity) {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
        long started = System.nanoTime();
        UsageEvent event = newUsageEvent(prepared, identity, requestId, false);
        List<GatewayProperties.RouteTarget> candidates = router.candidates(prepared.request().getModel());

        AtomicReference<ChatRequest> working = new AtomicReference<>(prepared.request());
        AtomicInteger structuredAttempt = new AtomicInteger(0);
        JsonNode schema = prepared.schema();

        return callJsonWithStructured(candidates, working, schema, structuredAttempt, event)
                .map(result -> buildChatResponse(result, event, started))
                .onErrorResume(error -> {
                    finalizeError(event, error, started);
                    return Mono.<ChatResponse>error(normalize(error));
                });
    }

    private Mono<RouteResult> callJsonWithStructured(List<GatewayProperties.RouteTarget> candidates,
                                                     AtomicReference<ChatRequest> working,
                                                     JsonNode schema,
                                                     AtomicInteger structuredAttempt,
                                                     UsageEvent event) {
        return callWithFallback(working.get(), candidates, event).flatMap(result -> {
            if (schema == null) {
                return Mono.just(result);
            }
            try {
                JsonNode parsed = structured.validate(result.response.getContent(), schema);
                result.response = new AdapterResponse(result.response.getContent(), parsed, result.response.getUsage());
                return Mono.just(result);
            } catch (StructuredOutputException error) {
                if (structuredAttempt.get() >= config.getStructuredOutputRetries()) {
                    return Mono.<RouteResult>error(error);
                }
                structuredAttempt.incrementAndGet();
                event.setRetries(event.getRetries() + 1);
                ChatRequest repaired = repairRequest(working.get(), result.response.getContent(),
                        structured.repairInstruction(error, schema));
                working.set(repaired);
                return callJsonWithStructured(candidates, working, schema, structuredAttempt, event);
            }
        });
    }

    private Mono<RouteResult> callWithFallback(ChatRequest request,
                                               List<GatewayProperties.RouteTarget> candidates,
                                               UsageEvent event) {
        return Flux.fromIterable(candidates)
                .index()
                .concatMap(tuple -> {
                    GatewayProperties.RouteTarget route = tuple.getT2();
                    event.setFallbacks(tuple.getT1().intValue());
                    return callRouteWithRetry(request, route, event)
                            .map(response -> {
                                event.setProvider(route.getProvider());
                                event.setUpstreamModel(route.getModel());
                                return new RouteResult(route, response);
                            })
                            .onErrorResume(error -> Mono.empty());
                })
                .next()
                .switchIfEmpty(Mono.error(new GatewayException("All upstream routes failed",
                        HttpStatus.BAD_GATEWAY, "upstream_error", GatewayErrorCode.UPSTREAM_ERROR)));
    }

    private Mono<AdapterResponse> callRouteWithRetry(ChatRequest request,
                                                     GatewayProperties.RouteTarget route,
                                                     UsageEvent event) {
        return attempt(request, route, event, 0);
    }

    private Mono<AdapterResponse> attempt(ChatRequest request,
                                          GatewayProperties.RouteTarget route,
                                          UsageEvent event,
                                          int attemptNumber) {
        LlmAdapter adapter = adapterFor(route);
        GatewayProperties.Provider provider = providerFor(route);
        return adapter.call(route, provider, request, event.getRequestId())
                .doOnSuccess(response -> {
                    router.recordSuccess(route.getProvider());
                    accumulateUsage(event, response.getUsage());
                })
                .onErrorResume(error -> {
                    router.recordFailure(route.getProvider());
                    GatewayException normalized = normalize(error);
                    if (normalized instanceof UpstreamException upstream
                            && upstream.isRetryable()
                            && attemptNumber < config.getRetry().getMaxRetries()) {
                        event.setRetries(event.getRetries() + 1);
                        long delay = backoffMillis(attemptNumber);
                        return Mono.delay(Duration.ofMillis(delay))
                                .then(attempt(request, route, event, attemptNumber + 1));
                    }
                    return Mono.<AdapterResponse>error(normalized);
                });
    }

    private ChatResponse buildChatResponse(RouteResult result, UsageEvent event, long started) {
        event.setStatus("success");
        event.setStatusCode(200);
        event.setLatencyMs(elapsedMillis(started));
        event.setCostUsd(usage.calculateCost(result.route.getModel(),
                event.getInputTokens(), event.getOutputTokens(), event.getCachedTokens()));
        usage.record(event);

        ChatResponse response = new ChatResponse();
        response.setRequestId(event.getRequestId());
        response.setModel(event.getRequestedModel());
        response.setContent(result.response.getContent());
        response.setParsed(result.response.getParsed());
        response.setUsage(new TokenUsage(event.getInputTokens(), event.getOutputTokens(), event.getCachedTokens()));
        response.setLatencyMs((long) event.getLatencyMs());
        response.setAttempts(1 + event.getRetries());
        response.setRetries(event.getRetries());
        response.setFallbacks(event.getFallbacks());
        response.setProvider(result.route.getProvider());
        response.setUpstreamModel(result.route.getModel());
        return response;
    }

    // ------------------------------------------------------------------
    // 流式
    // ------------------------------------------------------------------

    private Flux<ServerSentEvent<StreamEvent>> doStream(Prepared prepared, String identity) {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
        long started = System.nanoTime();
        UsageEvent event = newUsageEvent(prepared, identity, requestId, true);
        List<GatewayProperties.RouteTarget> candidates = router.candidates(prepared.request().getModel());

        AtomicBoolean emitted = new AtomicBoolean(false);

        Flux<ServerSentEvent<StreamEvent>> deltas = streamCandidate(prepared, candidates, 0, event, started, emitted);

        return deltas
                .concatWith(Flux.defer(() -> {
                    finalizeSuccess(event, started);
                    return Flux.just(ServerSentEvent.builder(doneEvent(event, started)).build());
                }))
                .onErrorResume(error -> {
                    GatewayException normalized = normalize(error);
                    finalizeError(event, normalized, started);
                    return Flux.just(ServerSentEvent.builder(errorEvent(normalized)).build());
                })
                .startWith(ServerSentEvent.builder(metaEvent(event)).build());
    }

    private Flux<ServerSentEvent<StreamEvent>> streamCandidate(Prepared prepared,
                                                               List<GatewayProperties.RouteTarget> candidates,
                                                               int index,
                                                               UsageEvent event,
                                                               long started,
                                                               AtomicBoolean emitted) {
        if (index >= candidates.size()) {
            return Flux.<ServerSentEvent<StreamEvent>>error(new GatewayException("All upstream routes failed",
                    HttpStatus.BAD_GATEWAY, "upstream_error", GatewayErrorCode.UPSTREAM_ERROR));
        }
        GatewayProperties.RouteTarget route = candidates.get(index);
        event.setFallbacks(index);
        event.setProvider(route.getProvider());
        event.setUpstreamModel(route.getModel());
        return streamRoute(prepared, route, event, started, emitted, 0)
                .onErrorResume(error -> {
                    GatewayException normalized = normalize(error);
                    if (emitted.get()) {
                        return Flux.<ServerSentEvent<StreamEvent>>error(normalized);
                    }
                    return streamCandidate(prepared, candidates, index + 1, event, started, emitted);
                });
    }

    private Flux<ServerSentEvent<StreamEvent>> streamRoute(Prepared prepared,
                                                           GatewayProperties.RouteTarget route,
                                                           UsageEvent event,
                                                           long started,
                                                           AtomicBoolean emitted,
                                                           int attemptNumber) {
        LlmAdapter adapter = adapterFor(route);
        GatewayProperties.Provider provider = providerFor(route);
        return adapter.stream(route, provider, prepared.request(), event.getRequestId())
                .concatMap(frame -> mapStreamFrame(frame, event, started, emitted))
                .doOnComplete(() -> router.recordSuccess(route.getProvider()))
                .onErrorResume(error -> {
                    router.recordFailure(route.getProvider());
                    GatewayException normalized = normalize(error);
                    if (!emitted.get()
                            && normalized instanceof UpstreamException upstream
                            && upstream.isRetryable()
                            && attemptNumber < config.getRetry().getMaxRetries()) {
                        event.setRetries(event.getRetries() + 1);
                        long delay = backoffMillis(attemptNumber);
                        return Mono.delay(Duration.ofMillis(delay))
                                .thenMany(streamRoute(prepared, route, event, started, emitted, attemptNumber + 1));
                    }
                    return Flux.<ServerSentEvent<StreamEvent>>error(normalized);
                });
    }

    private Flux<ServerSentEvent<StreamEvent>> mapStreamFrame(AdapterStreamFrame frame,
                                                               UsageEvent event,
                                                               long started,
                                                               AtomicBoolean emitted) {
        switch (frame.getKind()) {
            case DELTA: {
                emitted.set(true);
                if (event.getFirstTokenMs() == null) {
                    event.setFirstTokenMs((double) elapsedMillis(started));
                }
                return Flux.just(ServerSentEvent.builder(deltaEvent(frame.getDelta())).build());
            }
            case COMPLETE: {
                accumulateUsage(event, frame.getUsage());
                return Flux.<ServerSentEvent<StreamEvent>>empty();
            }
            case ERROR:
                return Flux.<ServerSentEvent<StreamEvent>>error(new UpstreamException(frame.getError(),
                        HttpStatus.BAD_GATEWAY, false, null));
            default:
                return Flux.<ServerSentEvent<StreamEvent>>empty();
        }
    }

    // ------------------------------------------------------------------
    // 请求准备
    // ------------------------------------------------------------------

    private Prepared prepare(ChatRequest request) {
        ChatRequest prepared = copy(request);

        String promptId = null;
        Integer promptVersion = null;
        if (request.getPrompt() != null) {
            RenderedPrompt rendered = prompts.render(request.getPrompt().getId(),
                    request.getPrompt().getVariables(), request.getPrompt().getVersion());
            promptId = rendered.prompt().getId();
            promptVersion = rendered.prompt().getVersion();
            ChatMessage system = new ChatMessage(rendered.prompt().getRole(), rendered.content());
            if ("append".equalsIgnoreCase(request.getPrompt().getPosition())) {
                prepared.getMessages().add(system);
            } else {
                prepared.getMessages().add(0, system);
            }
        }

        JsonNode schema = structured.schemaFrom(request);
        if (schema != null) {
            prepared.getMessages().add(0, new ChatMessage("system", structured.instructionFor(schema)));
        }
        return new Prepared(prepared, promptId, promptVersion, schema);
    }

    private ChatRequest copy(ChatRequest source) {
        ChatRequest copy = new ChatRequest();
        copy.setModel(source.getModel());
        copy.setMessages(new ArrayList<>(source.getMessages()));
        copy.setStream(source.isStream());
        copy.setTemperature(source.getTemperature());
        copy.setTopP(source.getTopP());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setResponseFormat(source.getResponseFormat());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        return copy;
    }

    private ChatRequest repairRequest(ChatRequest original, String previousContent, String instruction) {
        ChatRequest repaired = copy(original);
        List<ChatMessage> messages = new ArrayList<>(original.getMessages());
        messages.add(new ChatMessage("assistant", previousContent == null ? "" : previousContent));
        messages.add(new ChatMessage("user", instruction));
        repaired.setMessages(messages);
        return repaired;
    }

    // ------------------------------------------------------------------
    // 事件与用量
    // ------------------------------------------------------------------

    private UsageEvent newUsageEvent(Prepared prepared, String identity, String requestId, boolean stream) {
        UsageEvent event = new UsageEvent();
        event.setRequestId(requestId);
        event.setApiKeyHash(identity);
        event.setRequestedModel(prepared.request().getModel());
        event.setStream(stream);
        event.setPromptId(prepared.promptId());
        event.setPromptVersion(prepared.promptVersion());
        return event;
    }

    private void accumulateUsage(UsageEvent event, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        event.setInputTokens(event.getInputTokens() + usage.getInputTokens());
        event.setOutputTokens(event.getOutputTokens() + usage.getOutputTokens());
        event.setCachedTokens(event.getCachedTokens() + usage.getCachedInputTokens());
    }

    private void finalizeSuccess(UsageEvent event, long started) {
        event.setStatus("success");
        event.setStatusCode(200);
        event.setLatencyMs(elapsedMillis(started));
        if (event.getUpstreamModel() != null) {
            event.setCostUsd(usage.calculateCost(event.getUpstreamModel(),
                    event.getInputTokens(), event.getOutputTokens(), event.getCachedTokens()));
        }
        usage.record(event);
    }

    private void finalizeError(UsageEvent event, Throwable error, long started) {
        GatewayException normalized = normalize(error);
        event.setStatus("error");
        event.setStatusCode(normalized.getStatus().value());
        event.setErrorType(normalized.getErrorType());
        event.setErrorMessage(normalized.getMessage());
        event.setLatencyMs(elapsedMillis(started));
        usage.record(event);
    }

    private StreamEvent metaEvent(UsageEvent event) {
        return new StreamEvent("meta", Map.of(
                "request_id", event.getRequestId(),
                "model", event.getRequestedModel()));
    }

    private StreamEvent deltaEvent(String delta) {
        return new StreamEvent("delta", Map.of("content", delta));
    }

    private StreamEvent doneEvent(UsageEvent event, long started) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", event.getRequestedModel());
        data.put("provider", event.getProvider());
        data.put("upstream_model", event.getUpstreamModel());
        data.put("usage", new TokenUsage(event.getInputTokens(), event.getOutputTokens(), event.getCachedTokens()));
        data.put("latency_ms", (long) elapsedMillis(started));
        data.put("ttft_ms", event.getFirstTokenMs());
        data.put("retries", event.getRetries());
        data.put("fallbacks", event.getFallbacks());
        return new StreamEvent("done", data);
    }

    private StreamEvent errorEvent(GatewayException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", error.getMessage());
        payload.put("type", error.getErrorType());
        payload.put("code", error.getCode());
        return new StreamEvent("error", Map.of("error", payload));
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private LlmAdapter adapterFor(GatewayProperties.RouteTarget route) {
        AdapterProtocol protocol = AdapterProtocol.from(route.getProtocol());
        LlmAdapter adapter = adapters.get(protocol);
        if (adapter == null) {
            throw new GatewayException("No adapter registered for protocol " + protocol.getValue(),
                    HttpStatus.INTERNAL_SERVER_ERROR, "gateway_error", GatewayErrorCode.INVALID_REQUEST, "protocol");
        }
        return adapter;
    }

    private GatewayProperties.Provider providerFor(GatewayProperties.RouteTarget route) {
        GatewayProperties.Provider provider = config.getProviders().get(route.getProvider());
        if (provider == null) {
            throw new GatewayException("Route references unknown provider: " + route.getProvider(),
                    HttpStatus.INTERNAL_SERVER_ERROR, "gateway_error", GatewayErrorCode.INVALID_REQUEST, "provider");
        }
        return provider;
    }

    private long backoffMillis(int attemptNumber) {
        long base = config.getRetry().getBaseDelayMillis() * (1L << attemptNumber);
        long capped = Math.min(base, config.getRetry().getMaxDelayMillis());
        double jitter = 1.0 + config.getRetry().getJitter() * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        return Math.max(0, (long) (capped * jitter));
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private GatewayException normalize(Throwable error) {
        if (error instanceof GatewayException gateway) {
            return gateway;
        }
        return new UpstreamException("upstream error: " + error.getMessage(),
                HttpStatus.BAD_GATEWAY, false, null);
    }

    private record Prepared(ChatRequest request, String promptId, Integer promptVersion, JsonNode schema) {
    }

    private static final class RouteResult {
        private GatewayProperties.RouteTarget route;
        private AdapterResponse response;

        private RouteResult(GatewayProperties.RouteTarget route, AdapterResponse response) {
            this.route = route;
            this.response = response;
        }
    }
}
