package com.leesoons.mcp.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.mcp.client.McpClient;


/**
 * 自连自跑的演示：用子 JVM 启动 {@link OrderServer}，再用 {@link McpClient}
 * 完成 stdio 握手、工具发现与一次只读调用，验证手写协议的正确性。
 */
public final class StdioDemo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        try (McpClient client = new McpClient(OrderServer.command())) {
            client.connect();

            System.out.println("== 工具发现 ==");
            for (JsonNode tool : client.listTools()) {
                System.out.println("- " + tool.path("name").asText() + ": " + tool.path("description").asText());
            }

            System.out.println("== 调用 get_order(ord_1001) ==");
            JsonNode arguments = MAPPER.createObjectNode().put("order_id", "ord_1001");
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(client.callTool("get_order", arguments)));

            System.out.println("== 调用 get_order(ord_missing) ==");
            JsonNode missing = MAPPER.createObjectNode().put("order_id", "ord_missing");
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(client.callTool("get_order", missing)));
        }
    }

}
