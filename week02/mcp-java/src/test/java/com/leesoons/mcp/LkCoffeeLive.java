package com.leesoons.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.leesoons.mcp.client.McpClient;
import com.leesoons.mcp.client.McpServerConfig;
import com.leesoons.mcp.host.McpHost;
import com.leesoons.toolruntime.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2-5 Live 验收：真实连接瑞幸 my-coffee（Streamable HTTP）。
 *
 * <p>缺 {@code LUCKIN_MCP_TOKEN} 时必须失败，绝不允许静默回退到本地 Fake。
 * 默认不随 {@code mvn test} 运行，显式执行：{@code mvn -pl week02/mcp-java -Dtest=LkCoffeeLive test}。</p>
 */
class LkCoffeeLive {

    private static final String MCP_URL = "https://gwmcp.lkcoffee.com/order/user/mcp";

    @Test
    void discoversRealCoffeeTools() throws Exception {
        String token = System.getenv("LUCKIN_MCP_TOKEN");
        if (token == null || token.isBlank()) {
            fail("缺少 LUCKIN_MCP_TOKEN，live MCP 验收必须提供真实密钥");
        }

        McpServerConfig config = McpServerConfig.streamableHttp(
                "my-coffee", MCP_URL, Map.of("Authorization", "Bearer " + token));
        McpHost host = new McpHost();
        try (McpClient client = config.client()) {
            client.connect();
            List<JsonNode> tools = client.listTools();
            assertFalse(tools.isEmpty(), "my-coffee 至少应暴露一个工具");

            Map<String, ToolDefinition> definitions = host.discoverTools(client, "my-coffee");
            assertFalse(definitions.isEmpty(), "my-coffee 工具应被命名投影为 my-coffee_*");
        }
    }
}
