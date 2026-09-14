package com.leesoons.llmgateway;

import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.service.UsageEvent;
import com.leesoons.llmgateway.service.UsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UsageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsUsageLedgerFromDisk() {
        GatewayProperties config = new GatewayProperties();
        config.setDataDir(tempDir.toString());

        UsageEvent event = new UsageEvent();
        event.setRequestId("req_1");
        event.setRunId("run_1");
        event.setStepId("step_1");
        event.setCallId("req_1");
        event.setStatus("degraded");
        event.setInputTokens(10);
        event.setOutputTokens(5);

        UsageService first = new UsageService(config);
        first.record(event);

        UsageService reloaded = new UsageService(config);
        assertThat(reloaded.recent(10)).hasSize(1);
        UsageEvent restored = reloaded.recent(10).get(0);
        assertThat(restored.getRequestId()).isEqualTo("req_1");
        assertThat(restored.getRunId()).isEqualTo("run_1");
        assertThat(restored.getStepId()).isEqualTo("step_1");
        assertThat(restored.getStatus()).isEqualTo("degraded");
    }
}
