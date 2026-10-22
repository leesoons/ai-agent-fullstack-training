package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-5 收尾：审批挂起闭环——首次遇审批签发 PENDING 并暂停，人工批准后带 approvalId 恢复执行。
 */
class ApprovalPauseResumeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void requiresApprovalPausesThenResumesAfterHumanDecision() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = refundTool(calls);
        ApprovalStore approvals = new ApprovalStore();
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of(), new PermissionEngine(), approvals);
        ToolSnapshot snapshot = snapshot(tool);

        // 首次：无审批 → 签发 PENDING 并暂停，不执行 handler
        String args = "{\"order_id\":\"ord_1001\"}";
        ExecutionContext first = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"), PermissionMode.DEFAULT);
        ToolResult paused = runtime.invoke(snapshot, new ToolCall("c1", "refund_tool", args), first);

        assertFalse(paused.isError());
        assertEquals("AWAITING_APPROVAL", paused.errorCode());
        assertNotNull(paused.approvalId());
        assertEquals(0, calls.get());

        // 人工批准
        approvals.decide(paused.approvalId(), true, "admin");

        // 恢复：带上 approvalId 重新执行，核销并真正调用 handler
        ExecutionContext resumed = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"),
                PermissionMode.DEFAULT, paused.approvalId());
        ToolResult executed = runtime.invoke(snapshot, new ToolCall("c1", "refund_tool", args), resumed);

        assertFalse(executed.isError());
        assertEquals(1, calls.get());
    }

    @Test
    void usedApprovalIsRejectedInsteadOfPausing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = refundTool(calls);
        ApprovalStore approvals = new ApprovalStore();
        String args = "{\"order_id\":\"ord_1001\"}";
        approvals.approve("a1", "u", "tenant", "refund_tool", MAPPER.readTree(args));

        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of(), new PermissionEngine(), approvals);
        ToolSnapshot snapshot = snapshot(tool);
        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"),
                PermissionMode.DEFAULT, "a1");

        // 第一次核销成功
        assertFalse(runtime.invoke(snapshot, new ToolCall("c1", "refund_tool", args), ctx).isError());
        // 第二次重放已用审批：应拒绝，而不是再次暂停
        ToolResult replay = runtime.invoke(snapshot, new ToolCall("c2", "refund_tool", args), ctx);
        assertTrue(replay.isError());
        assertTrue(replay.content().contains("APPROVAL_REQUIRED"));
        assertEquals(1, calls.get());
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
