package com.leesoons.agentloop;

import com.leesoons.llmgateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 整体 Agent 项目入口。扫描 Gateway 与 AgentLoop 两个包的组件，
 * 把模型接入层（Gateway）与工具执行层（Tool Runtime）组装成一个可访问服务。
 */
@SpringBootApplication(scanBasePackages = {"com.leesoons.llmgateway", "com.leesoons.agentloop"})
@EnableConfigurationProperties(GatewayProperties.class)
public class AgentLoopApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentLoopApplication.class, args);
    }
}
