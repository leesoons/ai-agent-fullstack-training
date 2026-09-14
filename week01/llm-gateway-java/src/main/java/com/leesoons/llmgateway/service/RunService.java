package com.leesoons.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.core.GatewayErrorCode;
import com.leesoons.llmgateway.core.GatewayException;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.model.StreamEvent;
import com.leesoons.llmgateway.run.RunEvent;
import com.leesoons.llmgateway.run.RunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run 生命周期管理：创建即后台订阅上游流，事件写入内存 replay 缓冲，
 * 订阅者可携带 Last-Event-ID 从任意断点续读；配置 data-dir 后事件落盘，
 * 进程重启后可重新加载历史 Run。
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final GatewayService gatewayService;
    private final GatewayProperties config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public RunService(GatewayService gatewayService) {
        this(gatewayService, new GatewayProperties());
    }

    @Autowired
    public RunService(GatewayService gatewayService, GatewayProperties config) {
        this.gatewayService = gatewayService;
        this.config = config;
    }

    public String create(ChatRequest request, String identity) {
        ChatRequest runRequest = copyAsStream(request);
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        RunState run = new RunState(runId, identity);
        runs.put(runId, run);
        persistHeader(runId, identity);

        Flux<ServerSentEvent<StreamEvent>> upstream = gatewayService.stream(runRequest, identity,
                "chat", new TraceContext(runId, null));
        Disposable disposable = upstream.subscribe(
                sse -> handleEvent(run, sse.data()),
                error -> handleError(run, error),
                run::finishFromLastEvent
        );
        run.attach(disposable);
        return runId;
    }

    public RunState require(String runId, String identity) {
        RunState run = runs.get(runId);
        if (run == null) {
            run = loadRun(runId, identity);
            if (run != null) {
                RunState existing = runs.putIfAbsent(runId, run);
                if (existing != null) {
                    run = existing;
                }
            }
        }
        if (run == null || !run.getOwner().equals(identity)) {
            throw new GatewayException("run not found",
                    HttpStatus.NOT_FOUND, "invalid_request", GatewayErrorCode.INVALID_REQUEST, "run_id");
        }
        return run;
    }

    public String status(String runId, String identity) {
        return require(runId, identity).getStatus();
    }

    public Flux<RunEvent> events(String runId, int afterSeq, String identity) {
        return require(runId, identity).eventsAfter(afterSeq);
    }

    public String cancel(String runId, String identity) {
        RunState run = require(runId, identity);
        boolean changed = run.cancel("user_requested");
        if (changed) {
            persistEvent(runId, "cancelled",
                    Map.of("status", RunState.STATUS_CANCELLED, "reason", "user_requested"));
            run.dispose();
        }
        return run.getStatus();
    }

    private void handleEvent(RunState run, StreamEvent event) {
        RunEvent appended = run.append(event.getType(), event.getData());
        if (appended != null) {
            persistEvent(run.getRunId(), appended.type(), appended.data());
        }
    }

    private void handleError(RunState run, Throwable error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (error instanceof GatewayException gateway) {
            payload.put("type", gateway.getErrorType());
            payload.put("code", gateway.getCode());
            payload.put("message", gateway.getMessage());
        } else {
            payload.put("type", "gateway_error");
            payload.put("code", "internal_error");
            payload.put("message", error.getMessage());
        }
        RunEvent appended = run.append("error", Map.of("error", payload));
        if (appended != null) {
            persistEvent(run.getRunId(), appended.type(), appended.data());
        }
        run.dispose();
    }

    private void persistHeader(String runId, String identity) {
        Path file = runFile(runId);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            String header = mapper.writeValueAsString(Map.of("run_id", runId, "owner", identity))
                    + System.lineSeparator();
            Files.writeString(file, header, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("run_header_persist_failed run_id={} error={}", runId, e.getMessage());
        }
    }

    private void persistEvent(String runId, String type, Object data) {
        Path file = runFile(runId);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(Map.of("type", type, "data", data))
                    + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("run_event_persist_failed run_id={} type={} error={}", runId, type, e.getMessage());
        }
    }

    private RunState loadRun(String runId, String identity) {
        Path file = runFile(runId);
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String owner = null;
            List<JsonNode> events = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                if (node.has("owner")) {
                    owner = node.get("owner").asText();
                } else if (node.has("type")) {
                    events.add(node);
                }
            }
            if (owner == null || !owner.equals(identity)) {
                return null;
            }
            RunState run = new RunState(runId, owner);
            for (JsonNode node : events) {
                run.append(node.get("type").asText(), mapper.convertValue(node.get("data"), Object.class));
            }
            run.finishFromLastEvent();
            return run;
        } catch (IOException e) {
            log.warn("run_load_failed run_id={} error={}", runId, e.getMessage());
            return null;
        }
    }

    private Path runFile(String runId) {
        if (config.getDataDir() == null || config.getDataDir().isBlank()) {
            return null;
        }
        return Path.of(config.getDataDir(), "runs", runId + ".jsonl");
    }

    private ChatRequest copyAsStream(ChatRequest source) {
        ChatRequest copy = new ChatRequest();
        copy.setModel(source.getModel());
        copy.setMessages(new ArrayList<>(source.getMessages()));
        copy.setStream(true);
        copy.setTemperature(source.getTemperature());
        copy.setTopP(source.getTopP());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setResponseFormat(source.getResponseFormat());
        copy.setPrompt(source.getPrompt());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        return copy;
    }
}
