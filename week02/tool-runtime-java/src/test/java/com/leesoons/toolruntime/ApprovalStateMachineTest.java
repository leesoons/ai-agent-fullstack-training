package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-5：可恢复审批状态机——PENDING → APPROVED → EXECUTING → CONSUMED/FAILED，claim 抢锁。
 */
class ApprovalStateMachineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fullLifecycleTransitions() throws Exception {
        ApprovalStore store = new ApprovalStore();
        store.createApproval("a1", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1\"}"), 900);

        assertEquals(ApprovalStatus.PENDING, store.getApproval("a1").status());

        store.decide("a1", true, "admin");
        assertEquals(ApprovalStatus.APPROVED, store.getApproval("a1").status());

        assertTrue(store.claim("a1"));
        assertEquals(ApprovalStatus.EXECUTING, store.getApproval("a1").status());

        store.finish("a1", true, null);
        assertEquals(ApprovalStatus.CONSUMED, store.getApproval("a1").status());
    }

    @Test
    void claimIsAtomicOnlyOneWins() throws Exception {
        ApprovalStore store = new ApprovalStore();
        store.createApproval("a1", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1\"}"), 900);
        store.decide("a1", true, "admin");

        assertTrue(store.claim("a1"));
        assertFalse(store.claim("a1"));
    }

    @Test
    void rejectCantBeClaimed() throws Exception {
        ApprovalStore store = new ApprovalStore();
        store.createApproval("a1", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1\"}"), 900);
        store.decide("a1", false, "admin");

        assertFalse(store.claim("a1"));
        assertEquals(ApprovalStatus.REJECTED, store.getApproval("a1").status());
    }

    @Test
    void decidedApprovalCannotBeDecidedAgain() throws Exception {
        ApprovalStore store = new ApprovalStore();
        store.createApproval("a1", "u", "tenant", "refund_tool",
                MAPPER.readTree("{\"order_id\":\"ord_1\"}"), 900);
        store.decide("a1", true, "admin");

        assertThrows(IllegalStateException.class, () -> store.decide("a1", true, "admin"));
    }
}
