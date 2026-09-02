package com.leesoons.llmgateway.service;

import com.leesoons.llmgateway.config.GatewayProperties;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 进程内用量账本。生产环境可替换为 SQLite/PostgreSQL/ClickHouse。
 */
@Service
public class UsageService {

    private static final int MAX_EVENTS = 1000;

    private final GatewayProperties config;
    private final Deque<UsageEvent> events = new ConcurrentLinkedDeque<>();

    public UsageService(GatewayProperties config) {
        this.config = config;
    }

    public void record(UsageEvent event) {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    public List<UsageEvent> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_EVENTS));
        return events.stream().limit(bounded).toList();
    }

    public double calculateCost(String model, int inputTokens, int outputTokens, int cachedTokens) {
        GatewayProperties.Price price = config.getPricing().get(model);
        if (price == null) {
            return 0;
        }
        int freshInput = Math.max(0, inputTokens - cachedTokens);
        double cachedRate = price.getCachedInputPerMillion() != null
                ? price.getCachedInputPerMillion() : price.getInputPerMillion();
        double cost = freshInput * price.getInputPerMillion() / 1_000_000.0;
        cost += cachedTokens * cachedRate / 1_000_000.0;
        cost += outputTokens * price.getOutputPerMillion() / 1_000_000.0;
        return Math.round(cost * 1e10) / 1e10;
    }
}
