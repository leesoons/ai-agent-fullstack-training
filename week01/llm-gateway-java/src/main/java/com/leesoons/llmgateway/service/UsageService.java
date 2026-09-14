package com.leesoons.llmgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 进程内用量账本，可选 JSONL 落盘。配置 data-dir 后重启可恢复近期账本。
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private static final int MAX_EVENTS = 1000;

    private final GatewayProperties config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<UsageEvent> events = new ConcurrentLinkedDeque<>();

    public UsageService(GatewayProperties config) {
        this.config = config;
        loadFromDisk();
    }

    public void record(UsageEvent event) {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
        appendToDisk(event);
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

    private void loadFromDisk() {
        Path file = dataFile();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<UsageEvent> loaded = lines.stream()
                    .filter(line -> !line.isBlank())
                    .map(this::parseLine)
                    .toList();
            loaded.forEach(this::addToMemory);
            log.info("usage_ledger_loaded events={} file={}", loaded.size(), file);
        } catch (IOException e) {
            log.warn("usage_ledger_load_failed file={} error={}", file, e.getMessage());
        }
    }

    private void appendToDisk(UsageEvent event) {
        Path file = dataFile();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("usage_ledger_append_failed file={} error={}", file, e.getMessage());
        }
    }

    private void addToMemory(UsageEvent event) {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    private UsageEvent parseLine(String line) {
        try {
            return mapper.readValue(line, UsageEvent.class);
        } catch (IOException e) {
            throw new IllegalStateException("invalid usage ledger line", e);
        }
    }

    private Path dataFile() {
        if (config.getDataDir() == null || config.getDataDir().isBlank()) {
            return null;
        }
        return Path.of(config.getDataDir(), "usage.jsonl");
    }
}
