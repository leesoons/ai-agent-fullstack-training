package com.leesoons.agentloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.adapter.AnthropicMessagesAdapter;
import com.leesoons.llmgateway.adapter.LlmAdapter;
import com.leesoons.llmgateway.adapter.OpenAiResponsesAdapter;
import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.core.PerIdentityRateLimiter;
import com.leesoons.llmgateway.core.PerModelRateLimiter;
import com.leesoons.llmgateway.service.GatewayService;
import com.leesoons.llmgateway.service.InMemoryPromptStore;
import com.leesoons.llmgateway.service.ModelRouter;
import com.leesoons.llmgateway.service.PromptService;
import com.leesoons.llmgateway.service.StructuredOutputCache;
import com.leesoons.llmgateway.service.StructuredOutputService;
import com.leesoons.llmgateway.service.UpstreamClient;
import com.leesoons.llmgateway.service.UsageService;
import com.leesoons.toolruntime.AgentLoop;
import com.leesoons.toolruntime.ExecutionContext;
import com.leesoons.toolruntime.LoopMessage;
import com.leesoons.toolruntime.SampleTools;
import com.leesoons.toolruntime.ToolRegistry;
import com.leesoons.toolruntime.ToolRuntime;
import com.leesoons.toolruntime.ToolSnapshot;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fullLoopWithMockGateway() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            // 第一轮：模型返回 function_call
            server.enqueue(jsonResponse(
                    "{\"id\":\"resp_1\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc_1\","
                    + "\"call_id\":\"call_1\",\"name\":\"query_weather\","
                    + "\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}],\"usage\":{}}"));
            // 第二轮：模型基于工具结果返回最终文本
            server.enqueue(jsonResponse("{\"id\":\"resp_2\",\"output_text\":\"北京今天晴\",\"usage\":{}}"));

            GatewayService gateway = gateway(server);

            ToolRegistry registry = SampleTools.registry();
            ToolSnapshot snapshot = registry.snapshot(Map.of("query_weather", "1.0.0"), Map.of());
            ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("weather-service"));
            ExecutionContext context = new ExecutionContext("t", "u", "tenant", Set.of("weather:read"));
            GatewayModelClient client = new GatewayModelClient(gateway, mapper, "deepseek-v4-pro", "test");
            AgentLoop loop = new AgentLoop(runtime, 4);

            String answer = loop.run(List.of(LoopMessage.of("user", "查北京天气")), snapshot, context, client);

            assertThat(answer).isEqualTo("北京今天晴");
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    private GatewayService gateway(MockWebServer server) {
        GatewayProperties cfg = new GatewayProperties();
        cfg.getRetry().setMaxRetries(0);
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

        WebClient webClient = WebClient.builder().build();
        UpstreamClient upstream = new UpstreamClient(cfg, webClient);
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
        return new GatewayService(cfg, router, prompts, usage, rateLimiter, identityLimiter,
                structured, adapters, cache);
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
