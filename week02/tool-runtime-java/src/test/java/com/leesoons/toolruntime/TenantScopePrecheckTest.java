package com.leesoons.toolruntime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-5 收尾：租户资源范围预检样例——客户编号必须落在当前租户的前缀内。
 */
class TenantScopePrecheckTest {

    @Test
    void tenantReadsOwnCustomer() {
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("customer-database"));
        ToolSnapshot snapshot = SampleTools.registry()
                .snapshot(Map.of("query_customer_database", "1.0.0"), Map.of());

        ExecutionContext ctx = new ExecutionContext("t", "u", "acme", Set.of("database:read"));
        ToolResult result = runtime.invoke(snapshot,
                new ToolCall("c1", "query_customer_database", "{\"customer_id\":\"cust-1001\"}"), ctx);

        assertFalse(result.isError());
    }

    @Test
    void tenantCannotReadOtherTenantCustomer() {
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("customer-database"));
        ToolSnapshot snapshot = SampleTools.registry()
                .snapshot(Map.of("query_customer_database", "1.0.0"), Map.of());

        ExecutionContext ctx = new ExecutionContext("t", "u", "globex", Set.of("database:read"));
        ToolResult result = runtime.invoke(snapshot,
                new ToolCall("c1", "query_customer_database", "{\"customer_id\":\"cust-1001\"}"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("RESOURCE_SCOPE_DENIED"));
    }
}
