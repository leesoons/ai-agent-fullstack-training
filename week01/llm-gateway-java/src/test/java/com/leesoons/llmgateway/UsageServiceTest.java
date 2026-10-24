package com.leesoons.llmgateway;

import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.service.PriceProvider;
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

    @Test
    void usesInjectedPriceProvider() {
        GatewayProperties config = new GatewayProperties();
        config.setDataDir(tempDir.toString());
        PriceProvider fixed = model -> {
            GatewayProperties.Price price = new GatewayProperties.Price();
            price.setInputPerMillion(10);
            price.setOutputPerMillion(20);
            return price;
        };

        UsageService service = new UsageService(config, fixed);

        assertThat(service.calculateCost("any-model", 1_000_000, 0, 0)).isEqualTo(10.0);
    }

    @Test
    void aggregatesCostByTenantAndModel() {
        GatewayProperties config = new GatewayProperties();
        config.setDataDir(tempDir.toString());
        UsageService service = new UsageService(config);

        service.record(usageEvent("tenant-a", "up1", 1.5));
        service.record(usageEvent("tenant-a", "up2", 2.5));
        service.record(usageEvent("tenant-b", "up1", 3.0));

        assertThat(service.aggregateByTenant())
                .containsEntry("tenant-a", 4.0)
                .containsEntry("tenant-b", 3.0);
        assertThat(service.aggregateByModel())
                .containsEntry("up1", 4.5)
                .containsEntry("up2", 2.5);
    }

    private UsageEvent usageEvent(String tenantId, String upstreamModel, double costUsd) {
        UsageEvent event = new UsageEvent();
        event.setTenantId(tenantId);
        event.setRequestedModel(upstreamModel);
        event.setUpstreamModel(upstreamModel);
        event.setCostUsd(costUsd);
        return event;
    }
}
