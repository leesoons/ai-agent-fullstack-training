package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 链路事件汇聚端口：进程内按发生顺序收集 {@link TraceEvent}，并支持按 trace 聚合查询。
 *
 * <p>与 {@link AuditSink} 分工：Audit 面向「单次工具调用」的合规留痕，
 * Trace 面向「一次 Agent Run」的整条执行链，二者都带 traceId 才能交叉定位。</p>
 *
 * <p>可选 JSONL 持久化：构造时传入 dataDir 后落盘 {@code trace.jsonl}，
 * 重启后 {@link #byTrace} 仍能还原历史执行链；dataDir 为 {@code null} 时保持纯内存。</p>
 */
public final class TraceSink {

    private final List<TraceEvent> events = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dataFile;

    public TraceSink() {
        this(null);
    }

    public TraceSink(Path dataDir) {
        this.dataFile = dataDir == null ? null : dataDir.resolve("trace.jsonl");
        loadFromDisk();
    }

    public void record(TraceEvent event) {
        synchronized (events) {
            events.add(event);
            appendToDisk(event);
        }
    }

    public List<TraceEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public List<TraceEvent> byTrace(String traceId) {
        synchronized (events) {
            return events.stream().filter(event -> traceId.equals(event.traceId())).toList();
        }
    }

    private void loadFromDisk() {
        if (dataFile == null || !Files.exists(dataFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(dataFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                events.add(mapper.readValue(line, TraceEvent.class));
            }
        } catch (IOException e) {
            throw new IllegalStateException("trace 落盘读取失败: " + dataFile, e);
        }
    }

    private void appendToDisk(TraceEvent event) {
        if (dataFile == null) {
            return;
        }
        try {
            Files.createDirectories(dataFile.getParent());
            String line = mapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(dataFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 观测数据尽力而为：落盘失败降级为内存模式，不阻断主流程
            System.err.println("trace 落盘失败，降级为内存模式: " + e.getMessage());
        }
    }
}
