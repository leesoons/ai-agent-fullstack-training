package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-5 收尾：Trace / 审批单的 JSONL 持久化与重启恢复。
 *
 * <p>验收口径：重启后 PENDING/APPROVED 不丢、Trace 按 traceId 可查；
 * EXECUTING 是进程死亡时的中间态，恢复时保守归位为 FAILED（宁可重审不重放）。</p>
 */
class ApprovalTracePersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dataDir;

    @Test
    void pendingApprovalSurvivesRestart() throws Exception {
        ApprovalStore store = new ApprovalStore(dataDir);
        store.createApproval("a1", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1\"}"), 900);

        ApprovalStore restarted = new ApprovalStore(dataDir);
        assertEquals(ApprovalStatus.PENDING, restarted.getApproval("a1").status());
    }

    @Test
    void approvedApprovalSurvivesRestart() throws Exception {
        ApprovalStore store = new ApprovalStore(dataDir);
        store.createApproval("a1", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1\"}"), 900);
        store.decide("a1", true, "admin");

        ApprovalStore restarted = new ApprovalStore(dataDir);
        assertEquals(ApprovalStatus.APPROVED, restarted.getApproval("a1").status());
        assertTrue(restarted.claim("a1"));
    }

    @Test
    void executingApprovalFailsClosedOnRestart() throws Exception {
        ApprovalStore store = new ApprovalStore(dataDir);
        store.approve("a1", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1\"}"), 900);
        assertTrue(store.claim("a1"));
        assertEquals(ApprovalStatus.EXECUTING, store.getApproval("a1").status());

        // 模拟进程在 EXECUTING 之后、finish 之前死亡，重启后应保守归位 FAILED
        ApprovalStore restarted = new ApprovalStore(dataDir);
        assertEquals(ApprovalStatus.FAILED, restarted.getApproval("a1").status());
    }

    @Test
    void consumedApprovalStaysConsumedAfterRestart() throws Exception {
        ApprovalStore store = new ApprovalStore(dataDir);
        store.approve("a1", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1\"}"), 900);
        store.claim("a1");
        store.finish("a1", true, null);

        ApprovalStore restarted = new ApprovalStore(dataDir);
        assertEquals(ApprovalStatus.CONSUMED, restarted.getApproval("a1").status());
    }

    @Test
    void traceEventsRestoredByTraceId() {
        TraceSink sink = new TraceSink(dataDir);
        sink.record(new TraceEvent("trace-1", "tool.execution_end", "refund_tool", "OK", "detail"));
        sink.record(new TraceEvent("trace-2", "loop.turn_end", null, "OK", "turn"));

        TraceSink restarted = new TraceSink(dataDir);
        assertEquals(2, restarted.events().size());
        assertEquals(1, restarted.byTrace("trace-1").size());
        assertEquals("tool.execution_end", restarted.byTrace("trace-1").get(0).eventType());
    }
}
