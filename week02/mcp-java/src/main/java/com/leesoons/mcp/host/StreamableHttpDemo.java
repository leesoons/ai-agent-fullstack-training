package com.leesoons.mcp.host;

import com.leesoons.mcp.client.McpClient;
import com.leesoons.mcp.client.McpServerConfig;
import com.leesoons.toolruntime.ToolDefinition;

import java.util.Map;

/**
 * Streamable HTTP MCP 演示：以瑞幸「my-coffee」为正式 Server 接入示例。
 *
 * <p>Token 从环境变量 {@code LUCKIN_MCP_TOKEN} 读取，绝不硬编码；缺失时直接失败。
 * 只做「连接 + 协议体检 + 能力发现」，不触发任何远端写操作。</p>
 */
public final class StreamableHttpDemo {

    private static final String MCP_URL = "https://gwmcp.lkcoffee.com/order/user/mcp";

    public static void main(String[] args) throws Exception {
        String token = System.getenv("LUCKIN_MCP_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("缺少 LUCKIN_MCP_TOKEN，live MCP 验收必须提供真实密钥");
        }

        McpServerConfig config = McpServerConfig.streamableHttp(
                "my-coffee", MCP_URL, Map.of("Authorization", "Bearer " + token));
        McpHost host = new McpHost();

        try (McpClient client = config.client()) {
            client.connect();
            McpHost.Contract contract = host.inspect(client, config.id());
            System.out.println("PROTOCOL " + contract.protocol());
            System.out.println("TOOLS " + contract.tools());
            System.out.println("RESOURCES " + contract.resources());
            System.out.println("PROMPTS " + contract.prompts());

            for (Map.Entry<String, ToolDefinition> entry : host.discoverTools(client, config.id()).entrySet()) {
                System.out.println("TOOL " + entry.getKey() + " -> " + entry.getValue().description());
            }
        }
    }
}
