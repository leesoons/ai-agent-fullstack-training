package com.leesoons.mcp.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leesoons.mcp.server.McpServer;
import com.leesoons.mcp.server.ToolError;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单服务 MCP Server，对照 Python {@code mcp_server_v1.py}。
 *
 * <p>这里只声明「有哪些工具 + 每个工具的业务逻辑」，JSON-RPC 与
 * {@code tools/list}/{@code tools/call} 的协议样板全部由 {@link McpServer} 收口。</p>
 */
public final class OrderServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Map<String, Object>> ORDERS = new LinkedHashMap<>();

    static {
        ORDERS.put("ord_1001", Map.of("order_id", "ord_1001", "status", "pending", "amount_cents", 2999));
        ORDERS.put("ord_1002", Map.of("order_id", "ord_1002", "status", "shipped", "amount_cents", 1599));
    }

    public static void main(String[] args) throws Exception {
        McpServer server = new McpServer("order-service", "1.0.0");
        server.registerTool(
                "get_order",
                "按订单号查询订单状态。找不到订单时返回工具错误。",
                schema("""
                        {"type":"object",
                         "properties":{"order_id":{"type":"string"}},
                         "required":["order_id"],
                         "additionalProperties":false}
                        """),
                OrderServer::getOrder);
        server.registerTool(
                "create_ticket",
                "为已存在的订单创建售后工单。",
                schema("""
                        {"type":"object",
                         "properties":{"order_id":{"type":"string"},"reason":{"type":"string","minLength":4,"maxLength":100}},
                         "required":["order_id","reason"],
                         "additionalProperties":false}
                        """),
                OrderServer::createTicket);
        server.registerResource("policy://order-status", "订单状态说明");
        server.registerPrompt("order_assistant", "生成订单查询任务模板");
        server.runStdio();
    }

    private static JsonNode getOrder(JsonNode arguments) {
        String orderId = arguments.path("order_id").asText();
        Map<String, Object> order = ORDERS.get(orderId);
        if (order == null) {
            throw new ToolError("ORDER_NOT_FOUND");
        }
        ObjectNode payload = MAPPER.createObjectNode();
        order.forEach(payload::putPOJO);
        return payload;
    }

    private static JsonNode createTicket(JsonNode arguments) {
        String orderId = arguments.path("order_id").asText();
        String reason = arguments.path("reason").asText();
        if (!ORDERS.containsKey(orderId)) {
            throw new ToolError("ORDER_NOT_FOUND");
        }
        if (reason.length() < 4 || reason.length() > 100) {
            throw new ToolError("INVALID_REASON");
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("ticket_id", "ticket_2001");
        payload.put("order_id", orderId);
        return payload;
    }

    private static JsonNode schema(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid embedded schema", e);
        }
    }

    /** 构造「启动本 Server 子进程」的命令，供 stdio 客户端自连自跑。 */
    public static List<String> command() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        return List.of(javaBin, "-cp", classpath, OrderServer.class.getName());
    }
}
