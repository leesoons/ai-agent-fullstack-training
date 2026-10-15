package com.leesoons.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.mcp.client.McpClient;
import com.leesoons.mcp.order.OrderServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证：子 JVM 启动 {@link OrderServer}，用手写 {@link McpClient}
 * 走 stdio 完成握手、发现与调用，证明协议层正确。
 */
class StdioMcpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void initializeThenListAndCallTool() throws Exception {
        try (McpClient client = new McpClient(OrderServer.command())) {
            client.connect();

            List<JsonNode> tools = client.listTools();
            assertThat(tools)
                    .extracting(tool -> tool.path("name").asText())
                    .containsExactlyInAnyOrder("get_order", "create_ticket");

            JsonNode ok = client.callTool("get_order", MAPPER.createObjectNode().put("order_id", "ord_1001"));
            assertThat(ok.path("isError").asBoolean()).isFalse();
            assertThat(ok.path("structuredContent").path("status").asText()).isEqualTo("pending");

            JsonNode missing = client.callTool("get_order", MAPPER.createObjectNode().put("order_id", "ord_missing"));
            assertThat(missing.path("isError").asBoolean()).isTrue();
            assertThat(missing.path("structuredContent").path("code").asText()).isEqualTo("ORDER_NOT_FOUND");
        }
    }

}
