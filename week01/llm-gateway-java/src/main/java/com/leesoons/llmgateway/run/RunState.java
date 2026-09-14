package com.leesoons.llmgateway.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次流式 Run 的内存状态：replay 缓冲全部事件，订阅者可携带 afterSeq 断点续读。
 */
public final class RunState {

    private static final Logger log = LoggerFactory.getLogger(RunState.class);

    public static final String STATUS_CREATED = "created";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED);
    private static final Set<String> TERMINAL_EVENT_TYPES = Set.of("done", "error", "cancelled");

    private final Object lock = new Object();
    private final String runId;
    private final String owner;
    private final AtomicInteger nextSeq = new AtomicInteger();
    private final AtomicReference<String> status = new AtomicReference<>(STATUS_CREATED);
    private final Sinks.Many<RunEvent> sink = Sinks.many().replay().all();

    private volatile Disposable subscription;
    private volatile String lastEventType;

    public RunState(String runId, String owner) {
        this.runId = runId;
        this.owner = owner;
    }

    public String getRunId() {
        return runId;
    }

    public String getOwner() {
        return owner;
    }

    public String getStatus() {
        return status.get();
    }

    /**
     * 追加一条事件。Run 已进入终态后拒绝追加，防止取消后还写入过期 delta。
     */
    public RunEvent append(String type, Object data) {
        synchronized (lock) {
            if (TERMINAL_STATUSES.contains(status.get())) {
                return null;
            }
            RunEvent event = emit(type, data);
            if (TERMINAL_EVENT_TYPES.contains(type)) {
                status.set(statusFor(type));
                completeSink();
            }
            return event;
        }
    }

    /**
     * 用户取消：先置终态再补发 cancelled 事件，保证取消后的生产者事件被丢弃。
     */
    public boolean cancel(String reason) {
        synchronized (lock) {
            if (TERMINAL_STATUSES.contains(status.get())) {
                return false;
            }
            status.set(STATUS_CANCELLED);
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("status", STATUS_CANCELLED);
            data.put("reason", reason);
            emit("cancelled", data);
            completeSink();
            return true;
        }
    }

    /**
     * 上游流正常结束时兜底：若未经过 done/error/cancelled，标记失败。
     */
    public void finishFromLastEvent() {
        synchronized (lock) {
            if (TERMINAL_STATUSES.contains(status.get())) {
                return;
            }
            if ("done".equals(lastEventType)) {
                status.set(STATUS_COMPLETED);
            } else {
                status.set(STATUS_FAILED);
            }
            completeSink();
        }
    }

    public void attach(Disposable disposable) {
        this.subscription = disposable;
    }

    public void dispose() {
        Disposable disposable = this.subscription;
        if (disposable != null) {
            disposable.dispose();
        }
    }

    /**
     * 从 afterSeq + 1 开始重放缓冲事件，遇到终态事件后自动结束。
     */
    public Flux<RunEvent> eventsAfter(int afterSeq) {
        return sink.asFlux()
                .filter(event -> event.seq() > afterSeq)
                .takeUntil(event -> TERMINAL_EVENT_TYPES.contains(event.type()));
    }

    private RunEvent emit(String type, Object data) {
        RunEvent event = new RunEvent(nextSeq.getAndIncrement(), type, data);
        lastEventType = type;
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("run_event_emit_failed run_id={} seq={} type={} result={}", runId, event.seq(), type, result);
        }
        return event;
    }

    private static String statusFor(String type) {
        if ("done".equals(type)) {
            return STATUS_COMPLETED;
        }
        if ("error".equals(type)) {
            return STATUS_FAILED;
        }
        return STATUS_CANCELLED;
    }

    private void completeSink() {
        Sinks.EmitResult result = sink.tryEmitComplete();
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_TERMINATED) {
            log.warn("run_sink_complete_failed run_id={} result={}", runId, result);
        }
    }
}
