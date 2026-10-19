package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-4 Stage 2：审批摘要防重放与业务预检。
 */
class ToolApprovalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void approvalDigestIsStableAcrossKeyOrder() throws Exception {
        ObjectNode first = MAPPER.createObjectNode();
        first.put("order_id", "ord_1001");
        first.put("amount", "399.00");
        first.put("reason", "质量问题");

        ObjectNode second = MAPPER.createObjectNode();
        second.put("reason", "质量问题");
        second.put("amount", "399.00");
        second.put("order_id", "ord_1001");

        assertEquals(
                ApprovalStore.digest("create_refund", first),
                ApprovalStore.digest("create_refund", second));
    }

    @Test
    void oneTimeApprovalCannotBeReplayed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = refundTool(calls);
        ApprovalStore approvals = new ApprovalStore();
        JsonNode arguments = MAPPER.readTree("{\"order_id\":\"ord_1001\",\"amount\":\"399.00\",\"reason\":\"质量问题\"}");
        approvals.approve("approval_once", "u", "tenant", "refund_tool", arguments);

        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of(), new PermissionEngine(), approvals);
        ToolSnapshot snapshot = snapshot(tool);
        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"),
                PermissionMode.DEFAULT, "approval_once");

        ToolResult first = runtime.invoke(snapshot,
                new ToolCall("c1", "refund_tool", MAPPER.writeValueAsString(arguments)), ctx);
        ToolResult second = runtime.invoke(snapshot,
                new ToolCall("c2", "refund_tool", MAPPER.writeValueAsString(arguments)), ctx);

        assertFalse(first.isError());
        assertTrue(second.isError());
        assertTrue(second.content().contains("APPROVAL_REQUIRED"));
        assertEquals(1, calls.get());
    }

    @Test
    void approvalRejectsChangedAmount() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = refundTool(calls);
        ApprovalStore approvals = new ApprovalStore();
        approvals.approve("approval_amount", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1001\",\"amount\":\"100.00\",\"reason\":\"部分退款\"}"));

        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of(), new PermissionEngine(), approvals);
        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"),
                PermissionMode.DEFAULT, "approval_amount");

        ToolResult result = runtime.invoke(snapshot(tool),
                new ToolCall("c1", "refund_tool",
                        "{\"order_id\":\"ord_1001\",\"amount\":\"399.00\",\"reason\":\"质量问题\"}"),
                ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("APPROVAL_REQUIRED"));
        assertEquals(0, calls.get());
    }

    @Test
    void precheckDeniesBeforeApprovalAndHandler() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolHandler handler = (params, ctx) -> {
            calls.incrementAndGet();
            return MAPPER.createObjectNode().put("ok", true);
        };
        ToolPrecheck precheck = (params, ctx) -> {
            throw new PolicyDenied("BUSINESS_RULE_DENIED", "订单不存在或当前状态不可退款");
        };
        ToolDefinition tool = new ToolDefinition(
                "refund_tool", "1.0.0", "desc",
                objectSchema(), null, handler,
                new ToolPolicy(Effect.WRITE, RiskLevel.HIGH, "refund:create",
                        false, false, true, 1.0, 0, false, true),
                Set.of(), precheck, null);

        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of(), new PermissionEngine(), new ApprovalStore());
        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"), PermissionMode.DEFAULT);
        ToolResult result = runtime.invoke(snapshot(tool),
                new ToolCall("c1", "refund_tool", "{\"order_id\":\"ord_1001\"}"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("BUSINESS_RULE_DENIED"));
        assertEquals(0, calls.get());
    }

    private ToolDefinition refundTool(AtomicInteger calls) {
        ToolHandler handler = (params, ctx) -> {
            calls.incrementAndGet();
            return MAPPER.createObjectNode().put("ok", true);
        };
        return new ToolDefinition(
                "refund_tool", "1.0.0", "desc",
                objectSchema(), null, handler,
                new ToolPolicy(Effect.WRITE, RiskLevel.HIGH, "refund:create",
                        false, false, true, 1.0, 0, false, true),
                Set.of(), null, null);
    }

    private ToolSnapshot snapshot(ToolDefinition tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry.snapshot(Map.of(tool.name(), "1.0.0"), Map.of());
    }

    private ObjectNode objectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return schema;
    }
}
