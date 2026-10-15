package com.leesoons.mcp.host;

import com.leesoons.mcp.client.McpClient;
import com.leesoons.mcp.order.OrderServer;
import com.leesoons.toolruntime.ExecutionContext;
import com.leesoons.toolruntime.ToolCall;
import com.leesoons.toolruntime.ToolDefinition;
import com.leesoons.toolruntime.ToolRegistry;
import com.leesoons.toolruntime.ToolResult;
import com.leesoons.toolruntime.ToolRuntime;
import com.leesoons.toolruntime.ToolSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Host 演示：连订单 Server 子进程，做协议契约检查，并把 MCP 工具挂进唯一的
 * {@link ToolRegistry}/{@link ToolRuntime}，验证三种治理结果。
 */
public final class HostDemo {

    public static void main(String[] args) throws Exception {
        McpHost host = new McpHost();
        try (McpClient client = new McpClient(OrderServer.command())) {
            client.connect();

            McpHost.Contract contract = host.inspect(client, "orders");
            System.out.println("PROTOCOL " + contract.protocol());
            System.out.println("TOOLS " + contract.tools());
            System.out.println("RESOURCES " + contract.resources());
            System.out.println("PROMPTS " + contract.prompts());

            Map<String, ToolDefinition> tools = host.discoverTools(client, "orders");
            ToolRegistry registry = new ToolRegistry();
            tools.values().forEach(registry::register);

            Map<String, String> routes = new LinkedHashMap<>();
            tools.keySet().forEach(name -> routes.put(name, "1.0.0"));
            ToolSnapshot snapshot = registry.snapshot(routes, Map.of());
            ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
            ExecutionContext context = new ExecutionContext("http", "demo", "demo", Set.of());

            ToolResult success = runtime.invoke(
                    snapshot,
                    new ToolCall("call_success", "orders_get_order", "{\"order_id\":\"ord_1001\"}"),
                    context);
            System.out.println("CONTRACT SUCCESS isError=" + success.isError() + " content=" + success.content());

            ToolResult rejected = runtime.invoke(
                    snapshot,
                    new ToolCall("call_reject", "orders_get_order", "{}"),
                    context);
            System.out.println("REJECT isError=" + rejected.isError() + " content=" + rejected.content());

            ToolResult failed = runtime.invoke(
                    snapshot,
                    new ToolCall("call_fail", "orders_get_order", "{\"order_id\":\"ord_missing\"}"),
                    context);
            System.out.println("FAIL isError=" + failed.isError() + " content=" + failed.content());
        }
    }
}
