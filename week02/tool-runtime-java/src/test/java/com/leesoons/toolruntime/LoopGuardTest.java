package com.leesoons.toolruntime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-5：Agent Run 级护栏——六维预算 + 重复调用拦截 + 循环熔断。
 */
class LoopGuardTest {

    private LoopGuard guard() {
        return new LoopGuard(new LoopBudgetConfig(3, 5, 1, 100, 1.0, 60_000));
    }

    @Test
    void blocksRepeatedCallBeforeSideEffect() {
        LoopGuard guard = guard();

        LoopGuard.GuardDecision first = guard.beforeToolCall("ticket__create", "{\"order_id\":\"ORD-A-1001\"}");
        LoopGuard.GuardDecision second = guard.beforeToolCall("ticket__create", "{\"order_id\":\"ORD-A-1001\"}");

        assertTrue(first.allow());
        assertFalse(second.allow());
        assertEquals("REPEATED_TOOL_CALL", second.code());
    }

    @Test
    void keyOrderDoesNotAffectRepeatDetection() {
        LoopGuard guard = guard();
        assertTrue(guard.beforeToolCall("t", "{\"a\":1,\"b\":2}").allow());
        assertFalse(guard.beforeToolCall("t", "{\"b\":2,\"a\":1}").allow());
    }

    @Test
    void stopsAfterMaxTurns() {
        LoopGuard guard = new LoopGuard(new LoopBudgetConfig(1, 5, 1, 100, 1.0, 60_000));
        guard.observeTurn();

        LoopGuard.StopDecision stop = guard.shouldStop();

        assertTrue(stop.stop());
        assertEquals("MAX_TURNS", stop.code());
    }

    @Test
    void stopsWhileAwaitingApproval() {
        LoopGuard guard = guard();

        LoopGuard.StopDecision stop = guard.shouldStop("approval-1");

        assertTrue(stop.stop());
        assertEquals("AWAITING_APPROVAL", stop.code());
    }

    @Test
    void stopsWhenToolCallBudgetExhausted() {
        LoopGuard guard = new LoopGuard(new LoopBudgetConfig(5, 2, 10, 100, 1.0, 60_000));
        assertTrue(guard.beforeToolCall("a", "{}").allow());
        assertTrue(guard.beforeToolCall("b", "{}").allow());

        assertFalse(guard.beforeToolCall("c", "{}").allow());
        assertTrue(guard.shouldStop().stop());
        assertEquals("MAX_TOOL_CALLS", guard.shouldStop().code());
    }
}
