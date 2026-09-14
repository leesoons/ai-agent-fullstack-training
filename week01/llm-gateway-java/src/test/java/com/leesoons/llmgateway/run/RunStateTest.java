package com.leesoons.llmgateway.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunStateTest {

    @Test
    void cancelStopsLaterAppendsAndIsReplayable() {
        RunState state = new RunState("run_1", "alice");
        state.append("meta", Map.of("status", "running"));

        assertThat(state.cancel("user_requested")).isTrue();
        assertThat(state.append("delta", Map.of("content", "late"))).isNull();

        List<RunEvent> events = state.eventsAfter(-1).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(RunEvent::type).containsExactly("meta", "cancelled");
        assertThat(state.getStatus()).isEqualTo(RunState.STATUS_CANCELLED);
    }

    @Test
    void terminalEventMarksCompletedAndRejectsFurtherAppends() {
        RunState state = new RunState("run_2", "alice");
        state.append("delta", Map.of("content", "hi"));
        state.append("done", Map.of("status", "ok"));

        assertThat(state.getStatus()).isEqualTo(RunState.STATUS_COMPLETED);
        assertThat(state.append("delta", Map.of("content", "late"))).isNull();

        List<RunEvent> resumed = state.eventsAfter(0).collectList().block(Duration.ofSeconds(5));
        assertThat(resumed).extracting(RunEvent::type).containsExactly("done");
    }
}
