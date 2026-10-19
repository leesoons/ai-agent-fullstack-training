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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registerDuplicateThrows() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(SampleTools.weatherTool());
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(SampleTools.weatherTool()));
    }

    @Test
    void snapshotRespectsRoutesAndEnabled() {
        ToolRegistry registry = SampleTools.registry();
        ToolSnapshot snapshot = registry.snapshot(
                Map.of("query_weather", "1.0.0", "query_customer_database", "1.0.0"),
                Map.of("query_customer_database", false));

        assertEquals(1, snapshot.providerTools().size());
        assertNotNull(snapshot.get("query_weather"));
        assertNull(snapshot.get("query_customer_database"));
    }

    @Test
    void invalidArgumentIsRejected() {
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("weather-service"));
        ToolSnapshot snapshot = SampleTools.registry()
                .snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of("weather:read"));

        ToolResult result = runtime.invoke(snapshot, new ToolCall("c1", "query_weather", "{}"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("INVALID_ARGUMENT"));
    }

    @Test
    void permissionDenied() {
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("weather-service"));
        ToolSnapshot snapshot = SampleTools.registry()
                .snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of());

        ToolResult result = runtime.invoke(snapshot,
                new ToolCall("c1", "query_weather", "{\"city\":\"北京\"}"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("PERMISSION_DENIED"));
    }

    @Test
    void highRiskRequiresApproval() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("delete_order", RiskLevel.HIGH, "order:write"));
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
        ToolSnapshot snapshot = registry.snapshot(Map.of("delete_order", "1.0.0"), Map.of());
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of("order:write"));

        ToolResult result = runtime.invoke(snapshot, new ToolCall("c1", "delete_order", "{}"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("APPROVAL_REQUIRED"));
    }

    @Test
    void dependencyUnavailable() {
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
        ToolSnapshot snapshot = SampleTools.registry()
                .snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of("weather:read"));

        ToolResult result = runtime.invoke(snapshot,
                new ToolCall("c1", "query_weather", "{\"city\":\"北京\"}"), ctx);

        assertTrue(result.isError());
        assertTrue(result.content().contains("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void successResult() {
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("weather-service"));
        ToolSnapshot snapshot = SampleTools.registry()
                .snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of("weather:read"));

        ToolResult result = runtime.invoke(snapshot,
                new ToolCall("c1", "query_weather", "{\"city\":\"北京\",\"unit\":\"celsius\"}"), ctx);

        assertFalse(result.isError());
        assertEquals("c1", result.toolCallId());
        assertTrue(result.content().contains("晴"));
    }

    @Test
    void idempotentToolRetriesTransientError() {
        AtomicInteger calls = new AtomicInteger();
        ToolHandler handler = (params, ctx) -> {
            if (calls.incrementAndGet() == 1) {
                throw new TransientToolError("上游抖动");
            }
            return MAPPER.createObjectNode().put("ok", true);
        };
        ToolDefinition tool = new ToolDefinition(
                "flaky", "1.0.0", "desc",
                objectSchema(), objectSchema(), handler,
                new ToolPolicy(Effect.READ, RiskLevel.LOW, "flaky:read",
                        false, false, false, 1.0, 1, true, true),
                Set.of(), null, null);

        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
        ToolSnapshot snapshot = registry.snapshot(Map.of("flaky", "1.0.0"), Map.of());
        ExecutionContext ctx = new ExecutionContext("t", "u", "tenant", Set.of("flaky:read"));

        ToolResult result = runtime.invoke(snapshot, new ToolCall("c1", "flaky", "{}"), ctx);

        assertFalse(result.isError());
        assertEquals(2, calls.get());
    }

    private ToolDefinition tool(String name, RiskLevel risk, String permission) {
        return new ToolDefinition(name, "1.0.0", "desc",
                objectSchema(), objectSchema(),
                (params, ctx) -> MAPPER.createObjectNode().put("ok", true),
                new ToolPolicy(Effect.READ, risk, permission,
                        false, false, risk == RiskLevel.HIGH, 1.0, 0, true, true),
                Set.of(), null, null);
    }

    private JsonNode objectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return schema;
    }
}
