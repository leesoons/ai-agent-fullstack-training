package com.leesoons.agentloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.service.GatewayService;
import com.leesoons.agentcore.AgentLoop;
import com.leesoons.agentcore.ModelClient;
import com.leesoons.toolruntime.SampleTools;
import com.leesoons.toolruntime.ToolRegistry;
import com.leesoons.toolruntime.ToolRuntime;
import com.leesoons.toolruntime.ToolSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * 装配 Agent 运行所需的组件：工具注册表、请求级快照、Tool Runtime、AgentLoop 与 Gateway 粘合层。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ToolRegistry toolRegistry() {
        return SampleTools.registry();
    }

    @Bean
    public ToolSnapshot toolSnapshot(ToolRegistry registry) {
        return registry.snapshot(
                Map.of("query_weather", "1.0.0", "query_customer_database", "1.0.0"),
                Map.of());
    }

    @Bean
    public ToolRuntime toolRuntime() {
        return new ToolRuntime(Map.of(), Set.of("weather-service", "customer-database"));
    }

    @Bean
    public AgentLoop agentLoop(ToolRuntime runtime,
                               @Value("${agent.max-rounds:4}") int maxRounds) {
        return new AgentLoop(runtime, maxRounds);
    }

    @Bean
    public ModelClient modelClient(GatewayService gateway,
                                   ObjectMapper mapper,
                                   @Value("${agent.model:deepseek-v4-pro}") String model,
                                   @Value("${agent.identity:demo}") String identity) {
        return new GatewayModelClient(gateway, mapper, model, identity);
    }
}
