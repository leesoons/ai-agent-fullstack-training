package com.leesoons.mcp.host;

import com.leesoons.mcp.client.McpClient;
import com.leesoons.mcp.order.OrderServer;
import com.leesoons.agentcore.AgentLoop;
import com.leesoons.agentcore.LoopMessage;
import com.leesoons.agentcore.ModelClient;
import com.leesoons.toolruntime.ExecutionContext;
import com.leesoons.toolruntime.ToolCall;
import com.leesoons.toolruntime.ToolDefinition;
import com.leesoons.toolruntime.ToolRegistry;
import com.leesoons.toolruntime.ToolResult;
import com.leesoons.toolruntime.ToolRuntime;
import com.leesoons.toolruntime.ToolSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 工具接入统一 Runtime 的验证：命名投影 + 本地前置校验 + Adapter 归一化 + Agent Loop 闭环。
 */
class McpHostTest {

    @Test
    void discoveryProjectsNamesAndRuntimeGuardsLocally() throws Exception {
        McpHost host = new McpHost();
        try (McpClient client = new McpClient(OrderServer.command())) {
            client.connect();

            Map<String, ToolDefinition> tools = host.discoverTools(client, "orders");
            assertThat(tools.keySet()).containsExactlyInAnyOrder("orders_get_order", "orders_create_ticket");

            McpHost.Contract contract = host.inspect(client, "orders");
            assertThat(contract.protocol()).isEqualTo("2024-11-05");
            assertThat(contract.resources()).contains("policy://order-status");
            assertThat(contract.prompts()).contains("order_assistant");

            ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
            ToolSnapshot snapshot = snapshot(registry(tools), tools);
            ExecutionContext context = new ExecutionContext("http", "demo", "demo", Set.of());

            ToolResult success = runtime.invoke(
                    snapshot,
                    new ToolCall("call_success", "orders_get_order", "{\"order_id\":\"ord_1001\"}"),
                    context);
            assertThat(success.isError()).isFalse();
            assertThat(success.content()).contains("pending");

            ToolResult rejected = runtime.invoke(
                    snapshot,
                    new ToolCall("call_reject", "orders_get_order", "{}"),
                    context);
            assertThat(rejected.isError()).isTrue();
            assertThat(rejected.content()).contains("INVALID_ARGUMENT");

            ToolResult failed = runtime.invoke(
                    snapshot,
                    new ToolCall("call_fail", "orders_get_order", "{\"order_id\":\"ord_missing\"}"),
                    context);
            assertThat(failed.isError()).isFalse();
            assertThat(failed.content()).contains("MCP_TOOL_ERROR");
        }
    }

    @Test
    void agentLoopExecutesMountedMcpTool() throws Exception {
        McpHost host = new McpHost();
        try (McpClient client = new McpClient(OrderServer.command())) {
            client.connect();
            Map<String, ToolDefinition> tools = host.discoverTools(client, "orders");
            ToolSnapshot snapshot = snapshot(registry(tools), tools);
            AgentLoop loop = new AgentLoop(new ToolRuntime(Map.of(), Set.of()), 4);
            ExecutionContext context = new ExecutionContext("http", "demo", "demo", Set.of());

            ModelClient mockModel = (messages, modelTools) -> {
                boolean hasToolResult = messages.stream()
                        .anyMatch(message -> "tool".equals(message.role()));
                if (!hasToolResult) {
                    return new ModelClient.AssistantTurn(
                            null,
                            List.of(new ToolCall("call_1", "orders_get_order", "{\"order_id\":\"ord_1001\"}")));
                }
                return new ModelClient.AssistantTurn("订单 ord_1001 的状态是 pending。", List.of());
            };

            String answer = loop.run(
                    List.of(
                            LoopMessage.of("system", "你是订单助手。查询订单必须调用工具。"),
                            LoopMessage.of("user", "查一下订单 ord_1001 的状态")),
                    snapshot,
                    context,
                    mockModel);

            assertThat(answer).contains("pending");
        }
    }

    private static ToolRegistry registry(Map<String, ToolDefinition> tools) {
        ToolRegistry registry = new ToolRegistry();
        tools.values().forEach(registry::register);
        return registry;
    }

    private static ToolSnapshot snapshot(ToolRegistry registry, Map<String, ToolDefinition> tools) {
        Map<String, String> routes = new LinkedHashMap<>();
        tools.keySet().forEach(name -> routes.put(name, "1.0.0"));
        return registry.snapshot(routes, Map.of());
    }
}
