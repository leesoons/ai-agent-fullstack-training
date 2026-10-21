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
 * 2-5：硬超时 + 有限重试——超时只允许只读重试，写操作绝不重放；fallback 自身不重试。
 */
class ToolTimeoutAndFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writeTimeoutNeverRetriesEvenIfIdempotent() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = new ToolDefinition(
                "create_order", "1.0.0", "desc", objectSchema(), null,
                (params, ctx) -> {
                    calls.incrementAndGet();
                    sleep(500);
                    return MAPPER.createObjectNode().put("ok", true);
                },
                new ToolPolicy(Effect.WRITE, RiskLevel.HIGH, "order:write",
                        false, false, false, 0.05, 2, true, true),
                Set.of(), null, null);

        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
        ToolSnapshot snapshot = snapshot(tool);
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of("order:write"));

        ToolResult result = runtime.invoke(snapshot, new ToolCall("c1", "create_order", "{}"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("TIMEOUT"));
        assertEquals(1, calls.get());
    }

    @Test
    void readTimeoutRetriesUpToMaxRetries() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = new ToolDefinition(
                "slow_read", "1.0.0", "desc", objectSchema(), null,
                (params, ctx) -> {
                    calls.incrementAndGet();
                    sleep(500);
                    return MAPPER.createObjectNode().put("ok", true);
                },
                new ToolPolicy(Effect.READ, RiskLevel.LOW, "read:perm",
                        false, false, false, 0.05, 2, true, true),
                Set.of(), null, null);

        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
        ToolSnapshot snapshot = snapshot(tool);
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of("read:perm"));

        ToolResult result = runtime.invoke(snapshot, new ToolCall("c1", "slow_read", "{}"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("TIMEOUT"));
        assertEquals(3, calls.get());
    }

    @Test
    void fallbackRunsAfterRetryableFailure() {
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger fallback = new AtomicInteger();
        ToolDefinition tool = new ToolDefinition(
                "order_lookup", "1.0.0", "desc", objectSchema(), null,
                (params, ctx) -> {
                    primary.incrementAndGet();
                    throw new TransientToolError("订单服务不可用");
                },
                new ToolPolicy(Effect.READ, RiskLevel.LOW, "order:read",
                        false, false, false, 1.0, 1, true, true),
                Set.of(), null, null,
                (params, ctx) -> {
                    fallback.incrementAndGet();
                    return MAPPER.createObjectNode().put("status", "unknown");
                });

        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
        ToolSnapshot snapshot = snapshot(tool);
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of("order:read"));

        ToolResult result = runtime.invoke(snapshot, new ToolCall("c1", "order_lookup", "{}"), ctx);

        assertFalse(result.isError());
        assertEquals(2, primary.get());
        assertEquals(1, fallback.get());
    }

    private ToolSnapshot snapshot(ToolDefinition tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry.snapshot(Map.of(tool.name(), "1.0.0"), Map.of());
    }

    private JsonNode objectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
