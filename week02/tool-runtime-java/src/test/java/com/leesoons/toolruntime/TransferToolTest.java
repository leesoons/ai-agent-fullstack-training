package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Week02 作业二：为治理框架新增「转账」工具，翻译 Python 的 5 个验收测试。
 *
 * <p>全部通过 {@link ToolRuntime#invoke} 走真实链路：
 * 参数校验 → 业务预检 → 权限判断 → 人工审批 → 超时处理 → 结果脱敏 → 审计追踪。</p>
 */
class TransferToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TENANT = "tenant_a";
    private static final String FROM_ACCOUNT = "ACC-A-123456";
    private static final String TO_ACCOUNT = "ACC-A-654321";
    private static final String SMALL_ACCOUNT = "ACC-A-888888";
    private static final String APPROVAL_ID = "approval_tr";

    private ToolRuntime runtime;
    private ApprovalStore approvals;
    private AuditSink audit;
    private ToolSnapshot snapshot;

    @BeforeEach
    void setUp() {
        TransferTool.resetAccounts();
        approvals = new ApprovalStore();
        audit = new AuditSink();
        runtime = new ToolRuntime(Map.of(), Set.of(), new PermissionEngine(), approvals, audit);
        snapshot = snapshot();
    }

    @Test
    void rejectsInjectedArgumentsAndInvalidAmount() throws Exception {
        ExecutionContext ctx = ctx(Set.of("transfer:create"), Set.of("transfer"),
                PermissionMode.DEFAULT, null);

        String injected = json(Map.of(
                "from_account", FROM_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 100.0,
                "approved", true, "user_id", "u_admin"));
        assertInvalidArgument(transfer("c_inject", injected, ctx));

        String malformed = json(Map.of(
                "from_account", "ACC-123456", "to_account", TO_ACCOUNT, "amount", 100.0));
        assertInvalidArgument(transfer("c_format", malformed, ctx));

        String zero = json(Map.of(
                "from_account", FROM_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 0.0));
        assertInvalidArgument(transfer("c_zero", zero, ctx));

        String overSchema = json(Map.of(
                "from_account", FROM_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 100000.1));
        assertInvalidArgument(transfer("c_schema", overSchema, ctx));

        assertEquals(100000.0, TransferTool.balanceOf(TENANT, FROM_ACCOUNT));
    }

    @Test
    void precheckBlocksOverLimitAndInsufficientBalance() throws Exception {
        ExecutionContext ctx = ctx(Set.of("transfer:create"), Set.of("transfer"),
                PermissionMode.DEFAULT, null);

        String overLimit = json(Map.of(
                "from_account", FROM_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 60000.0));
        ToolResult rejected = transfer("c_limit", overLimit, ctx);
        assertTrue(rejected.isError());
        assertEquals("EXCEED_LIMIT", rejected.errorCode());

        String shortArgs = json(Map.of(
                "from_account", SMALL_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 30000.0));
        ToolResult shortResult = transfer("c_balance", shortArgs, ctx);
        assertTrue(shortResult.isError());
        assertEquals("INSUFFICIENT_BALANCE", shortResult.errorCode());

        assertEquals(100000.0, TransferTool.balanceOf(TENANT, FROM_ACCOUNT));
        assertEquals(20000.0, TransferTool.balanceOf(TENANT, SMALL_ACCOUNT));
        assertEquals(5000.0, TransferTool.balanceOf(TENANT, TO_ACCOUNT));
    }

    @Test
    void deniedWithoutPermissionOrWhitelistAndInPlanMode() throws Exception {
        String arguments = json(Map.of(
                "from_account", FROM_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 1000.0));

        ExecutionContext noPermission = ctx(Set.of("order:read", "refund:create", "shell:run"),
                Set.of(), PermissionMode.DEFAULT, null);
        ToolResult rbac = transfer("c_rbac", arguments, noPermission);
        assertTrue(rbac.isError());
        assertEquals("PERMISSION_DENIED", rbac.errorCode());

        ExecutionContext notWhitelisted = ctx(Set.of("transfer:create"),
                Set.of("get_order", "create_refund", "run_shell"), PermissionMode.DEFAULT, null);
        ToolResult whitelist = transfer("c_whitelist", arguments, notWhitelisted);
        assertTrue(whitelist.isError());
        assertEquals("TOOL_NOT_ALLOWED", whitelist.errorCode());

        ExecutionContext planMode = ctx(Set.of("transfer:create"),
                Set.of("transfer"), PermissionMode.PLAN, null);
        ToolResult plan = transfer("c_plan", arguments, planMode);
        assertTrue(plan.isError());
        assertEquals("PLAN_MODE_DENIED", plan.errorCode());

        assertEquals(100000.0, TransferTool.balanceOf(TENANT, FROM_ACCOUNT));
    }

    @Test
    void requiresApprovalBoundToArgumentsAndMasksAccounts() throws Exception {
        String arguments = json(Map.of(
                "from_account", FROM_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 1200.0));
        ExecutionContext ctx = ctx(Set.of("transfer:create"), Set.of("transfer"),
                PermissionMode.DEFAULT, null);

        ToolResult pending = transfer("c_000001", arguments, ctx);
        assertFalse(pending.isError());
        assertEquals("AWAITING_APPROVAL", pending.errorCode());
        assertNotNull(pending.approvalId());
        assertEquals(100000.0, TransferTool.balanceOf(TENANT, FROM_ACCOUNT));

        approvals.approve(APPROVAL_ID, "u", TENANT, "transfer", MAPPER.readTree(arguments));
        ExecutionContext approvedCtx = ctx(Set.of("transfer:create"), Set.of("transfer"),
                PermissionMode.DEFAULT, APPROVAL_ID);

        String tampered = json(Map.of(
                "from_account", FROM_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 9000.0));
        ToolResult tamperedResult = transfer("c_000002", tampered, approvedCtx);
        assertTrue(tamperedResult.isError());
        assertEquals("APPROVAL_REQUIRED", tamperedResult.errorCode());

        ToolResult executed = transfer("c_000003", arguments, approvedCtx);
        assertFalse(executed.isError());
        assertEquals(98800.0, TransferTool.balanceOf(TENANT, FROM_ACCOUNT));
        assertEquals(6200.0, TransferTool.balanceOf(TENANT, TO_ACCOUNT));

        JsonNode content = MAPPER.readTree(executed.content());
        assertFalse(content.path("txn_id").asText().isEmpty());
        assertEquals(FROM_ACCOUNT, content.path("from").asText());

        String modelView = executed.toModelMessage();
        assertTrue(modelView.contains("***"));
        assertFalse(modelView.contains(FROM_ACCOUNT));

        assertFalse(audit.records().isEmpty());
        assertTrue(audit.records().stream()
                .anyMatch(record -> Boolean.TRUE.equals(record.get("ok"))));
    }

    @Test
    void amountOverEightyThousandTimesOutWithoutSideEffect() throws Exception {
        String arguments = json(Map.of(
                "from_account", FROM_ACCOUNT, "to_account", TO_ACCOUNT, "amount", 90000.0));
        approvals.approve(APPROVAL_ID, "u", TENANT, "transfer", MAPPER.readTree(arguments));
        ExecutionContext ctx = ctx(Set.of("transfer:create"), Set.of("transfer"),
                PermissionMode.DEFAULT, APPROVAL_ID);

        ToolResult result = transfer("c_timeout", arguments, ctx);
        assertTrue(result.isError());
        assertEquals("TIMEOUT", result.errorCode());

        assertEquals(100000.0, TransferTool.balanceOf(TENANT, FROM_ACCOUNT));
        assertEquals(5000.0, TransferTool.balanceOf(TENANT, TO_ACCOUNT));
    }

    private void assertInvalidArgument(ToolResult result) {
        assertTrue(result.isError());
        assertEquals("INVALID_ARGUMENT", result.errorCode());
    }

    private ToolResult transfer(String callId, String argumentsJson, ExecutionContext ctx) {
        return runtime.invoke(snapshot, new ToolCall(callId, "transfer", argumentsJson), ctx);
    }

    private ExecutionContext ctx(Set<String> permissions, Set<String> allowedTools,
                                 PermissionMode mode, String approvalId) {
        return new ExecutionContext("t", "u", TENANT, permissions, allowedTools, mode, approvalId);
    }

    private ToolSnapshot snapshot() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(TransferTool.definition());
        return registry.snapshot(Map.of("transfer", "1.0.0"), Map.of());
    }

    private String json(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }
}
