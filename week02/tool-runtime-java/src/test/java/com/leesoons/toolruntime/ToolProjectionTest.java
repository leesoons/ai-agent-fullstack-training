package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-4 Stage 3：结果脱敏、模型投影、审计与写锁串行。
 */
class ToolProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void redactMasksSensitiveKeysAndEmail() {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("order_id", "ord_1001");
        value.put("access_token", "do-not-leak");
        value.put("customer_email", "alice@example.com");

        JsonNode redacted = ResultProjector.redact(value);

        assertEquals("***", redacted.path("access_token").asText());
        assertEquals("***@***", redacted.path("customer_email").asText());
        assertEquals("ord_1001", redacted.path("order_id").asText());
    }

    @Test
    void projectForModelTruncatesLongText() {
        String longText = "x".repeat(2100);
        JsonNode projected = ResultProjector.projectForModel(MAPPER.getNodeFactory().textNode(longText));

        assertTrue(projected.asText().endsWith("[文本过长，已截断]"));
        assertTrue(projected.asText().length() < 2100);
    }

    @Test
    void toolResultMarksModelPayloadAsUntrusted() {
        ToolHandler handler = (params, ctx) -> {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("ok", true);
            out.put("access_token", "secret");
            return out;
        };
        ToolDefinition tool = tool("read_tool", Effect.READ, handler, null, null);
        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("read_tool:perm"), Set.of("read_tool"), PermissionMode.DEFAULT);
        ToolResult result = new ToolRuntime(Map.of(), Set.of()).invoke(
                snapshot(tool), new ToolCall("c1", "read_tool", "{}"), ctx);

        String modelMessage = result.toModelMessage();
        assertTrue(modelMessage.contains("\"untrusted\":true"));
        assertTrue(modelMessage.contains("\"access_token\":\"***\""));
    }

    @Test
    void auditRecordsDenyAndSuccess() {
        ToolHandler handler = (params, ctx) -> MAPPER.createObjectNode().put("ok", true);
        ToolDefinition tool = tool("read_tool", Effect.READ, handler, null, null);
        AuditSink audit = new AuditSink();
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of(), new PermissionEngine(), new ApprovalStore(), audit);
        ToolSnapshot snapshot = snapshot(tool);

        ExecutionContext denied = new ExecutionContext(
                "t", "u", "tenant", Set.of(), Set.of("read_tool"), PermissionMode.DEFAULT);
        runtime.invoke(snapshot, new ToolCall("c1", "read_tool", "{}"), denied);

        ExecutionContext allowed = new ExecutionContext(
                "t", "u", "tenant", Set.of("read_tool:perm"), Set.of("read_tool"), PermissionMode.DEFAULT);
        runtime.invoke(snapshot, new ToolCall("c2", "read_tool", "{}"), allowed);

        assertEquals(2, audit.records().size());
        assertEquals("PERMISSION_DENIED", audit.records().get(0).get("error_code"));
        assertEquals(true, audit.records().get(1).get("ok"));
    }

    @Test
    void invokeBatchPureReadReturnsAllResults() {
        ToolHandler handler = (params, ctx) -> MAPPER.createObjectNode().put("ok", true);
        ToolDefinition tool = tool("read_tool", Effect.READ, handler, null, null);
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
        ToolSnapshot snapshot = snapshot(tool);
        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("read_tool:perm"), Set.of("read_tool"), PermissionMode.DEFAULT);

        List<ToolResult> results = runtime.invokeBatch(snapshot, List.of(
                new ToolCall("c1", "read_tool", "{}"),
                new ToolCall("c2", "read_tool", "{}")), ctx);

        assertEquals(2, results.size());
        assertFalse(results.get(0).isError());
        assertFalse(results.get(1).isError());
    }

    @Test
    void writeToolSerializesByCanonicalTarget() throws Exception {
        List<long[]> spans = Collections.synchronizedList(new ArrayList<>());
        ToolHandler handler = (params, ctx) -> {
            long start = System.nanoTime();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long end = System.nanoTime();
            synchronized (spans) {
                spans.add(new long[]{start, end});
            }
            return MAPPER.createObjectNode().put("ok", true);
        };
        ToolDefinition tool = tool("write_tool", Effect.WRITE, handler,
                (params, ctx) -> "tenant:ord_1001", null);
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of());
        ToolSnapshot snapshot = snapshot(tool);
        ExecutionContext ctx = new ExecutionContext(
                "t", "u", "tenant", Set.of("write_tool:perm"), Set.of("write_tool"), PermissionMode.DEFAULT);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> first = pool.submit(() -> runtime.invoke(snapshot,
                new ToolCall("c1", "write_tool", "{}"), ctx));
        Future<?> second = pool.submit(() -> runtime.invoke(snapshot,
                new ToolCall("c2", "write_tool", "{}"), ctx));
        first.get();
        second.get();
        pool.shutdown();

        assertEquals(2, spans.size());
        long[] a = spans.get(0);
        long[] b = spans.get(1);
        assertTrue(a[1] <= b[0] || b[1] <= a[0], "写操作应串行，时间区间不能重叠");
    }

    private ToolDefinition tool(String name, Effect effect, ToolHandler handler,
                                CanonicalTarget canonicalTarget, ToolPrecheck precheck) {
        return new ToolDefinition(
                name, "1.0.0", "desc", objectSchema(), null, handler,
                new ToolPolicy(effect, RiskLevel.LOW, name + ":perm",
                        false, false, false, 1.0, 0, true, true),
                Set.of(), precheck, canonicalTarget);
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
