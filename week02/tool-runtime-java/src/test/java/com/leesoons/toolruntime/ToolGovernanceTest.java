package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-4 Stage 1：参数契约、白名单、plan 禁写、deny 规则与确认门禁。
 */
class ToolGovernanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void planModeDeniesWriteBeforeHandler() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = tool("write_tool", new ToolPolicy(
                        Effect.WRITE, RiskLevel.LOW, "order:write",
                        false, false, false, 1.0, 0, false, true),
                (params, ctx) -> {
                    calls.incrementAndGet();
                    return MAPPER.createObjectNode().put("ok", true);
                });

        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("order:write"), Set.of("write_tool"), PermissionMode.PLAN);
        ToolResult result = invoke(tool, ctx, "{\"order_id\":\"ord_1001\"}");

        assertTrue(result.isError());
        assertTrue(result.content().contains("PLAN_MODE_DENIED"));
        assertEquals(0, calls.get());
    }

    @Test
    void whitelistShortCircuitsBeforeHandler() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = tool("secret_tool", new ToolPolicy(
                        Effect.READ, RiskLevel.LOW, "secret:read",
                        false, false, false, 1.0, 0, true, true),
                (params, ctx) -> {
                    calls.incrementAndGet();
                    return MAPPER.createObjectNode().put("ok", true);
                });

        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("secret:read"), Set.of("other_tool"), PermissionMode.DEFAULT);
        ToolResult result = invoke(tool, ctx, "{\"order_id\":\"ord_1001\"}");

        assertTrue(result.isError());
        assertTrue(result.content().contains("TOOL_NOT_ALLOWED"));
        assertEquals(0, calls.get());
    }

    @Test
    void denyRuleShortCircuitsBeforeHandler() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = tool("blocked_tool", new ToolPolicy(
                        Effect.READ, RiskLevel.LOW, "blocked:read",
                        true, false, false, 1.0, 0, true, true),
                (params, ctx) -> {
                    calls.incrementAndGet();
                    return MAPPER.createObjectNode().put("ok", true);
                });

        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("blocked:read"), Set.of("blocked_tool"), PermissionMode.DEFAULT);
        ToolResult result = invoke(tool, ctx, "{\"order_id\":\"ord_1001\"}");

        assertTrue(result.isError());
        assertTrue(result.content().contains("DENY_RULE"));
        assertEquals(0, calls.get());
    }

    @Test
    void confirmationBlocksInDefaultAndAllowsInBypass() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = tool("refund_tool", new ToolPolicy(
                        Effect.WRITE, RiskLevel.HIGH, "refund:create",
                        false, true, false, 1.0, 0, false, true),
                (params, ctx) -> {
                    calls.incrementAndGet();
                    return MAPPER.createObjectNode().put("ok", true);
                });

        ExecutionContext normal = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"), PermissionMode.DEFAULT);
        assertTrue(invoke(tool, normal, "{\"order_id\":\"ord_1001\"}")
                .content().contains("CONFIRMATION_REQUIRED"));
        assertEquals(0, calls.get());

        ExecutionContext bypass = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"),
                PermissionMode.BYPASS_PERMISSIONS);
        ToolResult allowed = invoke(tool, bypass, "{\"order_id\":\"ord_1001\"}");
        assertFalse(allowed.isError());
        assertEquals(1, calls.get());
    }

    @Test
    void schemaRejectsForgedApprovalField() {
        ToolDefinition tool = tool("refund_tool", new ToolPolicy(
                        Effect.WRITE, RiskLevel.HIGH, "refund:create",
                        false, false, false, 1.0, 0, false, true),
                (params, ctx) -> MAPPER.createObjectNode().put("ok", true));

        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"), PermissionMode.DEFAULT);
        ToolResult result = invoke(tool, ctx,
                "{\"order_id\":\"ord_1001\",\"approved\":true}");

        assertTrue(result.isError());
        assertTrue(result.content().contains("INVALID_ARGUMENT"));
    }

    private ToolDefinition tool(String name, ToolPolicy policy, ToolHandler handler) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.set("order_id", MAPPER.createObjectNode().put("type", "string"));
        ArrayNode required = MAPPER.createArrayNode();
        required.add("order_id");
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return new ToolDefinition(name, "1.0.0", "desc", schema, null, handler, policy, Set.of(), null, null);
    }

    private ToolSnapshot snapshot(ToolDefinition tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry.snapshot(Map.of(tool.name(), "1.0.0"), Map.of());
    }

    private ToolResult invoke(ToolDefinition tool, ExecutionContext ctx, String argumentsJson) {
        return new ToolRuntime(Map.of(), Set.of()).invoke(
                snapshot(tool),
                new ToolCall("c1", tool.name(), argumentsJson),
                ctx);
    }
}
