package com.leesoons.agentloop;

import com.leesoons.agentcore.AgentLoop;
import com.leesoons.agentcore.ModelClient;
import com.leesoons.toolruntime.SampleTools;
import com.leesoons.toolruntime.ToolCall;
import com.leesoons.toolruntime.ToolRuntime;
import com.leesoons.toolruntime.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(AgentController.class)
@Import(AgentControllerTest.TestConfig.class)
class AgentControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ModelClient modelClient;

    @Test
    void asksAgentAndReturnsFinalAnswer() {
        when(modelClient.call(any(), any()))
                .thenReturn(new ModelClient.AssistantTurn(null,
                        List.of(new ToolCall("c1", "query_weather", "{\"city\":\"北京\"}"))))
                .thenReturn(new ModelClient.AssistantTurn("北京今天晴", null));

        webTestClient.post().uri("/v1/agent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"查北京天气\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.answer").isEqualTo("北京今天晴");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        ToolSnapshot toolSnapshot() {
            return SampleTools.registry()
                    .snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        }

        @Bean
        ToolRuntime toolRuntime() {
            return new ToolRuntime(Map.of(), Set.of("weather-service"));
        }

        @Bean
        AgentLoop agentLoop(ToolRuntime runtime) {
            return new AgentLoop(runtime, 4);
        }
    }
}
