package com.leesoons.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leesoons.mcp.client.McpClient;
import com.leesoons.mcp.client.McpServerConfig;
import com.leesoons.mcp.host.McpHost;
import com.leesoons.toolruntime.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-5：Streamable HTTP 传输的确定性验证——本地起一个最小 MCP HTTP Server，
 * 走 initialize / tools/list 全链路，不依赖外网。
 */
class StreamableHttpMcpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void connectsDiscoversAndProjectsOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText();
            JsonNode id = request.get("id");
            if (id == null) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            switch (method) {
                case "initialize" -> {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "sess-1");
                    ObjectNode result = response.putObject("result");
                    result.put("protocolVersion", "2024-11-05");
                    result.putObject("capabilities");
                }
                case "tools/list" -> {
                    ObjectNode result = response.putObject("result");
                    result.putArray("tools").addObject()
                            .put("name", "order.lookup")
                            .put("description", "查询订单");
                }
                case "resources/list" -> response.putObject("result").putArray("resources");
                case "prompts/list" -> response.putObject("result").putArray("prompts");
                default -> response.putObject("error").put("code", -32601).put("message", "not found");
            }
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            McpServerConfig config = McpServerConfig.streamableHttp(
                    "my-coffee", url, Map.of("Authorization", "Bearer test"));

            try (McpClient client = config.client()) {
                client.connect();
                List<JsonNode> tools = client.listTools();
                assertEquals(1, tools.size());
                assertEquals("order.lookup", tools.get(0).path("name").asText());

                McpHost host = new McpHost();
                Map<String, ToolDefinition> definitions = host.discoverTools(client, "my-coffee");
                assertTrue(definitions.containsKey("my-coffee_order.lookup"));
            }
        } finally {
            server.stop(0);
        }
    }
}
