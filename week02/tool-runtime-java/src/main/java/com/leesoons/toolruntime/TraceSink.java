package com.leesoons.toolruntime;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路事件汇聚端口：进程内按发生顺序收集 {@link TraceEvent}，并支持按 trace 聚合查询。
 *
 * <p>与 {@link AuditSink} 分工：Audit 面向「单次工具调用」的合规留痕，
 * Trace 面向「一次 Agent Run」的整条执行链，二者都带 traceId 才能交叉定位。</p>
 */
public final class TraceSink {

    private final List<TraceEvent> events = new ArrayList<>();

    public void record(TraceEvent event) {
        synchronized (events) {
            events.add(event);
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
}
