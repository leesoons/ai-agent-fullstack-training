package com.leesoons.llmgateway;

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
import com.leesoons.llmgateway.run.RunEvent;
import com.leesoons.llmgateway.service.GatewayService;
import com.leesoons.llmgateway.service.InMemoryPromptStore;
import com.leesoons.llmgateway.service.ModelRouter;
import com.leesoons.llmgateway.service.PromptService;
import com.leesoons.llmgateway.service.RunService;
import com.leesoons.llmgateway.service.StructuredOutputCache;
import com.leesoons.llmgateway.service.StructuredOutputService;
import com.leesoons.llmgateway.service.UpstreamClient;
import com.leesoons.llmgateway.service.UsageService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runBuffersEventsAndReplaysFromResumeSeq() throws Exception {
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

            RunService runService = new RunService(gateway(server));
            String runId = runService.create(req("deepseek-v4-pro", "hi"), "alice");

            List<RunEvent> fromStart = runService.events(runId, -1, "alice")
                    .collectList().block(Duration.ofSeconds(5));

            assertThat(fromStart).extracting(RunEvent::type)
                    .containsExactly("meta", "delta", "delta", "done");

            int metaSeq = fromStart.get(0).seq();
            List<RunEvent> resumed = runService.events(runId, metaSeq, "alice")
                    .collectList().block(Duration.ofSeconds(5));

            assertThat(resumed).extracting(RunEvent::type)
                    .containsExactly("delta", "delta", "done");
            assertThat(resumed.get(0).seq()).isGreaterThan(metaSeq);
        }
    }

    @Test
    void unknownOrForeignRunIsRejected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: [DONE]\n\n"));

            RunService runService = new RunService(gateway(server));
            String runId = runService.create(req("deepseek-v4-pro", "hi"), "alice");

            assertThatThrownBy(() -> runService.status("run_missing", "alice"))
                    .isInstanceOf(GatewayException.class);
            assertThatThrownBy(() -> runService.status(runId, "mallory"))
                    .isInstanceOf(GatewayException.class);
        }
    }

    private GatewayService gateway(MockWebServer server) {
        GatewayProperties cfg = new GatewayProperties();
        cfg.getRetry().setMaxRetries(3);
        cfg.getRetry().setBaseDelayMillis(1);
        cfg.getRetry().setMaxDelayMillis(5);
        cfg.getRetry().setJitter(0);
        cfg.getRateLimit().setEnabled(false);

        GatewayProperties.Provider provider = new GatewayProperties.Provider();
        provider.setBaseUrl(server.url("/").toString());
        provider.setEnabled(true);
        provider.setTimeoutSeconds(10);
        provider.setApiKey("sk-openai");
        cfg.getProviders().put("p", provider);

        GatewayProperties.ModelRoute route = new GatewayProperties.ModelRoute();
        GatewayProperties.RouteTarget target = new GatewayProperties.RouteTarget();
        target.setProvider("p");
        target.setModel("deepseek-v4-pro");
        target.setProtocol("openai_responses");
        route.setRoutes(List.of(target));
        cfg.getModels().put("deepseek-v4-pro", route);

        UpstreamClient upstream = new UpstreamClient(cfg, WebClient.builder().build());
        List<LlmAdapter> adapters = List.of(
                new OpenAiResponsesAdapter(upstream, mapper),
                new AnthropicMessagesAdapter(upstream, mapper));
        ModelRouter router = new ModelRouter(cfg);
        PromptService prompts = new PromptService(new InMemoryPromptStore());
        UsageService usage = new UsageService(cfg);
        PerModelRateLimiter rateLimiter = new PerModelRateLimiter(cfg);
        PerIdentityRateLimiter identityLimiter = new PerIdentityRateLimiter(cfg);
        StructuredOutputService structured = new StructuredOutputService(mapper);
        return new GatewayService(cfg, router, prompts, usage, rateLimiter, identityLimiter, structured, adapters,
                new StructuredOutputCache());
    }

    private ChatRequest req(String model, String content) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setMessages(List.of(new ChatMessage("user", content)));
        return request;
    }
}
