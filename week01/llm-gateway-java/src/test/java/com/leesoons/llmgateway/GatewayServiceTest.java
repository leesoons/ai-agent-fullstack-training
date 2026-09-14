package com.leesoons.llmgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.adapter.AnthropicMessagesAdapter;
import com.leesoons.llmgateway.adapter.LlmAdapter;
import com.leesoons.llmgateway.adapter.OpenAiResponsesAdapter;
import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.core.GatewayException;
import com.leesoons.llmgateway.core.PerIdentityRateLimiter;
import com.leesoons.llmgateway.core.PerModelRateLimiter;
import com.leesoons.llmgateway.model.ChatMessage;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.model.ChatResponse;
import com.leesoons.llmgateway.model.PromptCreate;
import com.leesoons.llmgateway.model.PromptReference;
import com.leesoons.llmgateway.model.ResponseFormat;
import com.leesoons.llmgateway.model.StreamEvent;
import com.leesoons.llmgateway.model.TokenUsage;
import com.leesoons.llmgateway.service.GatewayService;
import com.leesoons.llmgateway.service.InMemoryPromptStore;
import com.leesoons.llmgateway.service.ModelRouter;
import com.leesoons.llmgateway.service.PromptService;
import com.leesoons.llmgateway.service.StructuredOutputCache;
import com.leesoons.llmgateway.service.StructuredOutputService;
import com.leesoons.llmgateway.service.UpstreamClient;
import com.leesoons.llmgateway.service.UsageService;
import com.leesoons.llmgateway.service.TraceContext;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void openaiResponsesAdapterRoutesAndNormalizesUsage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(jsonResponse("""
                    {"id":"resp_1","output_text":"hello",
                     "usage":{"input_tokens":10,"output_tokens":5,
                              "input_tokens_details":{"cached_tokens":2}}}"""));

            GatewayProperties cfg = openAiConfig(server);
            Harness h = harness(cfg);

            ChatResponse resp = h.gateway().chat(req("deepseek-v4-pro", "hi"), "test").block();

            assertThat(resp.getContent()).isEqualTo("hello");
            assertThat(resp.getUsage().getInputTokens()).isEqualTo(10);
            assertThat(resp.getUsage().getOutputTokens()).isEqualTo(5);
            assertThat(resp.getUsage().getCachedInputTokens()).isEqualTo(2);

            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getPath()).isEqualTo("/v1/responses");
            assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-openai");
            assertThat(recorded.getBody().readUtf8()).contains("\"model\":\"deepseek-v4-pro\"");
        }
    }

    @Test
    void anthropicMessagesAdapterRoutesAndNormalizesUsage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(jsonResponse("""
                    {"id":"msg_1","type":"message","role":"assistant",
                     "content":[{"type":"text","text":"bonjour"}],
                     "usage":{"input_tokens":12,"output_tokens":3,
                              "cache_read_input_tokens":1,"cache_creation_input_tokens":0}}"""));

            GatewayProperties cfg = baseConfig();
            addProvider(cfg, "p", server.url("/").toString(), "sk-anthropic");
            addModel(cfg, "deepseek-v4-flash", "p", "deepseek-v4-flash", "anthropic_messages");
            Harness h = harness(cfg);

            ChatResponse resp = h.gateway().chat(req("deepseek-v4-flash", "hi"), "test").block();

            assertThat(resp.getContent()).isEqualTo("bonjour");
            assertThat(resp.getUsage().getInputTokens()).isEqualTo(12);
            assertThat(resp.getUsage().getOutputTokens()).isEqualTo(3);
            assertThat(resp.getUsage().getCachedInputTokens()).isEqualTo(1);

            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getPath()).isEqualTo("/v1/messages");
            assertThat(recorded.getHeader("x-api-key")).isEqualTo("sk-anthropic");
            assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        }
    }

    @Test
    void structuredOutputIsValidatedAndRepaired() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(jsonResponse("{\"output_text\":\"{\\\"name\\\":123}\"}"));
            server.enqueue(jsonResponse("{\"output_text\":\"{\\\"name\\\":\\\"Ada\\\"}\"}"));

            GatewayProperties cfg = openAiConfig(server);
            Harness h = harness(cfg);

            ChatRequest request = req("deepseek-v4-pro", "Name");
            ResponseFormat format = new ResponseFormat();
            format.setType("json_schema");
            format.setName("person");
            format.setStrict(true);
            format.setSchema(mapper.readTree("""
                    {"type":"object","properties":{"name":{"type":"string"}},
                     "required":["name"],"additionalProperties":false}"""));
            request.setResponseFormat(format);

            ChatResponse resp = h.gateway().chat(request, "test").block();

            assertThat(resp.getParsed().get("name").asText()).isEqualTo("Ada");
            assertThat(resp.getRetries()).isEqualTo(1);
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void structuredOutputDegradesToCacheWhenRepairExhausted() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(jsonResponse("{\"output_text\":\"{\\\"name\\\":123}\"}"));
            server.enqueue(jsonResponse("{\"output_text\":\"{\\\"name\\\":456}\"}"));

            GatewayProperties cfg = openAiConfig(server);
            cfg.setStructuredOutputRetries(1);
            Harness h = harness(cfg);

            ChatRequest request = req("deepseek-v4-pro", "Name");
            JsonNode schema = mapper.readTree("""
                    {"type":"object","properties":{"name":{"type":"string"}},
                     "required":["name"],"additionalProperties":false}""");
            ResponseFormat format = new ResponseFormat();
            format.setType("json_schema");
            format.setName("person");
            format.setStrict(true);
            format.setSchema(schema);
            request.setResponseFormat(format);

            JsonNode parsed = mapper.readTree("{\"name\":\"Cached Ada\"}");
            h.cache().put(h.cache().key("test", request, schema), "{\"name\":\"Cached Ada\"}", parsed);

            ChatResponse resp = h.gateway().chat(request, "test").block();

            assertThat(resp.getSource()).isEqualTo("cache");
            assertThat(resp.getParsed().get("name").asText()).isEqualTo("Cached Ada");
            assertThat(server.getRequestCount()).isEqualTo(2);
            assertThat(h.usage().recent(10).get(0).getStatus()).isEqualTo("degraded");
        }
    }

    @Test
    void promptIsVersionedRenderedAndInjected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(jsonResponse("{\"output_text\":\"ok\",\"usage\":{}}"));

            GatewayProperties cfg = openAiConfig(server);
            Harness h = harness(cfg);

            PromptCreate create = new PromptCreate();
            create.setId("reviewer");
            create.setName("reviewer");
            create.setRole("system");
            create.setContent("You are {{lang}} reviewer");
            create.setActivate(true);
            h.prompts().createVersion(create);

            ChatRequest request = req("deepseek-v4-pro", "review this code");
            PromptReference ref = new PromptReference();
            ref.setId("reviewer");
            Map<String, Object> variables = new java.util.HashMap<>();
            variables.put("lang", "Java");
            ref.setVariables(variables);
            request.setPrompt(ref);

            h.gateway().chat(request, "test").block();

            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getBody().readUtf8()).contains("You are Java reviewer");
        }
    }

    @Test
    void streamingEmitsMetaDeltaAndDoneWithTtft() {
        String sse = """
                data: {"type":"response.output_text.delta","delta":"Hel"}

                data: {"type":"response.output_text.delta","delta":"lo"}

                data: {"type":"response.completed","response":{"usage":{"input_tokens":3,"output_tokens":2}}}

                data: [DONE]

                """;
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sse));

            GatewayProperties cfg = openAiConfig(server);
            Harness h = harness(cfg);

            ChatRequest request = req("deepseek-v4-pro", "hi");
            request.setStream(true);

            Flux<ServerSentEvent<StreamEvent>> flux = h.gateway().stream(request, "test");

            StepVerifier.create(flux.map(ServerSentEvent::data))
                    .assertNext(event -> assertThat(event.getType()).isEqualTo("meta"))
                    .assertNext(event -> {
                        assertThat(event.getType()).isEqualTo("delta");
                        assertThat(((Map<?, ?>) event.getData()).get("content")).isEqualTo("Hel");
                    })
                    .assertNext(event -> {
                        assertThat(event.getType()).isEqualTo("delta");
                        assertThat(((Map<?, ?>) event.getData()).get("content")).isEqualTo("lo");
                    })
                    .assertNext(event -> {
                        assertThat(event.getType()).isEqualTo("done");
                        Map<?, ?> data = (Map<?, ?>) event.getData();
                        TokenUsage usage = (TokenUsage) data.get("usage");
                        assertThat(usage.getInputTokens()).isEqualTo(3);
                        assertThat(usage.getOutputTokens()).isEqualTo(2);
                        assertThat(data.get("ttft_ms")).isNotNull();
                    })
                    .verifyComplete();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void retryWithBackoffSucceedsAfterTransientFailure() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":{\"message\":\"busy\"}}"));
            server.enqueue(jsonResponse("{\"output_text\":\"hello\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));

            GatewayProperties cfg = openAiConfig(server);
            Harness h = harness(cfg);

            ChatResponse resp = h.gateway().chat(req("deepseek-v4-pro", "hi"), "test").block();

            assertThat(resp.getContent()).isEqualTo("hello");
            assertThat(resp.getRetries()).isEqualTo(1);
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void perModelRateLimitReturns429() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(jsonResponse("{\"output_text\":\"first\",\"usage\":{}}"));
            server.enqueue(jsonResponse("{\"output_text\":\"second\",\"usage\":{}}"));

            GatewayProperties cfg = openAiConfig(server);
            cfg.getRateLimit().setEnabled(true);
            cfg.getRateLimit().setRequestsPerMinute(60);
            cfg.getRateLimit().setBurst(1);
            Harness h = harness(cfg);

            assertThat(h.gateway().chat(req("deepseek-v4-pro", "1"), "test").block().getContent())
                    .isEqualTo("first");

            assertThatThrownBy(() -> h.gateway().chat(req("deepseek-v4-pro", "2"), "test").block())
                    .isInstanceOf(GatewayException.class)
                    .satisfies(error -> {
                        GatewayException gateway = (GatewayException) error;
                        assertThat(gateway.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(gateway.getCode()).isEqualTo("rate_limit_exceeded");
                    });
        }
    }

    @Test
    void unknownModelReturns404() {
        GatewayProperties cfg = baseConfig();
        addProvider(cfg, "p", "http://localhost:1", "sk");
        addModel(cfg, "deepseek-v4-pro", "p", "deepseek-v4-pro", "openai_responses");
        Harness h = harness(cfg);

        assertThatThrownBy(() -> h.gateway().chat(req("unknown-model", "hi"), "test").block())
                .isInstanceOf(GatewayException.class)
                .satisfies(error -> {
                    GatewayException gateway = (GatewayException) error;
                    assertThat(gateway.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(gateway.getCode()).isEqualTo("model_not_found");
                });
    }

    @Test
    void routeIsFilteredByExternalApiCapability() {
        GatewayProperties cfg = baseConfig();
        addProvider(cfg, "p", "http://localhost:1", "sk");
        GatewayProperties.ModelRoute route = new GatewayProperties.ModelRoute();
        GatewayProperties.RouteTarget target = new GatewayProperties.RouteTarget();
        target.setProvider("p");
        target.setModel("deepseek-v4-pro");
        target.setProtocol("openai_responses");
        target.setApi("chat");
        route.setRoutes(List.of(target));
        cfg.getModels().put("deepseek-v4-pro", route);
        Harness h = harness(cfg);

        assertThatThrownBy(() -> h.gateway()
                .chat(req("deepseek-v4-pro", "hi"), "test", "responses", TraceContext.none())
                .block())
                .isInstanceOf(GatewayException.class)
                .satisfies(error -> {
                    GatewayException gateway = (GatewayException) error;
                    assertThat(gateway.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    // ------------------------------------------------------------------
    // 测试脚手架
    // ------------------------------------------------------------------

    private GatewayProperties openAiConfig(MockWebServer server) {
        GatewayProperties cfg = baseConfig();
        addProvider(cfg, "p", server.url("/").toString(), "sk-openai");
        addModel(cfg, "deepseek-v4-pro", "p", "deepseek-v4-pro", "openai_responses");
        return cfg;
    }

    private GatewayProperties baseConfig() {
        GatewayProperties cfg = new GatewayProperties();
        cfg.getRetry().setMaxRetries(3);
        cfg.getRetry().setBaseDelayMillis(1);
        cfg.getRetry().setMaxDelayMillis(5);
        cfg.getRetry().setJitter(0);
        cfg.getRateLimit().setEnabled(false);
        return cfg;
    }

    private void addProvider(GatewayProperties cfg, String name, String baseUrl, String apiKey) {
        GatewayProperties.Provider provider = new GatewayProperties.Provider();
        provider.setBaseUrl(baseUrl);
        provider.setEnabled(true);
        provider.setTimeoutSeconds(10);
        provider.setApiKey(apiKey);
        cfg.getProviders().put(name, provider);
    }

    private void addModel(GatewayProperties cfg, String alias, String provider, String upstreamModel, String protocol) {
        GatewayProperties.ModelRoute route = new GatewayProperties.ModelRoute();
        GatewayProperties.RouteTarget target = new GatewayProperties.RouteTarget();
        target.setProvider(provider);
        target.setModel(upstreamModel);
        target.setProtocol(protocol);
        route.setRoutes(List.of(target));
        cfg.getModels().put(alias, route);
    }

    private Harness harness(GatewayProperties cfg) {
        WebClient client = WebClient.builder().build();
        UpstreamClient upstream = new UpstreamClient(cfg, client);
        List<LlmAdapter> adapters = List.of(
                new OpenAiResponsesAdapter(upstream, mapper),
                new AnthropicMessagesAdapter(upstream, mapper));
        ModelRouter router = new ModelRouter(cfg);
        PromptService prompts = new PromptService(new InMemoryPromptStore());
        UsageService usage = new UsageService(cfg);
        PerModelRateLimiter rateLimiter = new PerModelRateLimiter(cfg);
        PerIdentityRateLimiter identityLimiter = new PerIdentityRateLimiter(cfg);
        StructuredOutputService structured = new StructuredOutputService(mapper);
        StructuredOutputCache cache = new StructuredOutputCache();
        GatewayService gateway = new GatewayService(cfg, router, prompts, usage, rateLimiter, identityLimiter,
                structured, adapters, cache);
        return new Harness(gateway, prompts, usage, cfg, cache);
    }

    private ChatRequest req(String model, String content) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setMessages(List.of(new ChatMessage("user", content)));
        return request;
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private record Harness(GatewayService gateway, PromptService prompts, UsageService usage,
                           GatewayProperties config, StructuredOutputCache cache) {
    }
}
