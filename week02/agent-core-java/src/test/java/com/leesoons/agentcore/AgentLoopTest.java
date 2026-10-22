package com.leesoons.agentcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.toolruntime.ApprovalStore;
import com.leesoons.toolruntime.AuditSink;
import com.leesoons.toolruntime.Effect;
import com.leesoons.toolruntime.ExecutionContext;
import com.leesoons.toolruntime.PermissionEngine;
import com.leesoons.toolruntime.PermissionMode;
import com.leesoons.toolruntime.RiskLevel;
import com.leesoons.toolruntime.SampleTools;
import com.leesoons.toolruntime.ToolCall;
import com.leesoons.toolruntime.ToolDefinition;
import com.leesoons.toolruntime.ToolHandler;
import com.leesoons.toolruntime.ToolPolicy;
import com.leesoons.toolruntime.ToolRegistry;
import com.leesoons.toolruntime.ToolRuntime;
import com.leesoons.toolruntime.ToolSnapshot;
import com.leesoons.toolruntime.TraceEvent;
import com.leesoons.toolruntime.TraceSink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {

    @Test
    void loopExecutesToolThenReturnsFinalAnswer() {
        ToolRegistry registry = SampleTools.registry();
        ToolSnapshot snapshot = registry.snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("weather-service"));
        ExecutionContext context = new ExecutionContext("t", "u", "tenant", Set.of("weather:read"));
        AgentLoop loop = new AgentLoop(runtime, 4);

        AtomicInteger calls = new AtomicInteger();
        ModelClient client = (messages, tools) -> {
            if (calls.incrementAndGet() == 1) {
                return new ModelClient.AssistantTurn(null,
                        List.of(new ToolCall("c1", "query_weather", "{\"city\":\"北京\"}")));
            }
            boolean hasToolResult = messages.stream()
                    .anyMatch(m -> "tool".equals(m.role()) && m.content().contains("晴"));
            return new ModelClient.AssistantTurn(hasToolResult ? "北京今天晴" : "缺工具结果", null);
        };

        List<LoopMessage> initial = List.of(LoopMessage.of("user", "查北京天气"));
        String answer = loop.run(initial, snapshot, context, client);

        assertEquals("北京今天晴", answer);
        assertEquals(2, calls.get());
    }

    @Test
    void loopStopsWhenMaxRoundsReached() {
        ToolRegistry registry = SampleTools.registry();
        ToolSnapshot snapshot = registry.snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("weather-service"));
        ExecutionContext context = new ExecutionContext("t", "u", "tenant", Set.of("weather:read"));
        AgentLoop loop = new AgentLoop(runtime, 2);

        ModelClient client = (messages, tools) -> new ModelClient.AssistantTurn(null,
                List.of(new ToolCall("c1", "query_weather", "{\"city\":\"北京\"}")));

        String answer = loop.run(List.of(LoopMessage.of("user", "查天气")), snapshot, context, client);

        assertEquals("Agent 循环停止: MAX_TURNS - Agent Loop 已达到最大轮数", answer);
    }

    @Test
    void loopStopsAwaitingApproval() {
        ToolDefinition tool = refundTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        ToolSnapshot snapshot = registry.snapshot(Map.of("refund_tool", "1.0.0"), Map.of());
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of(), new PermissionEngine(), new ApprovalStore());
        ExecutionContext context = new ExecutionContext(
                "t", "u", "tenant", Set.of("refund:create"), Set.of("refund_tool"), PermissionMode.DEFAULT);
        AgentLoop loop = new AgentLoop(runtime, 4);

        ModelClient client = (messages, tools) -> new ModelClient.AssistantTurn(null,
                List.of(new ToolCall("c1", "refund_tool", "{\"order_id\":\"ord_1001\"}")));

        String answer = loop.run(List.of(LoopMessage.of("user", "帮我退款")), snapshot, context, client);

        assertTrue(answer.contains("AWAITING_APPROVAL"));
        assertTrue(answer.contains("approvalId="));
    }

    @Test
    void traceEventsGroupedByTraceId() {
        ToolRegistry registry = SampleTools.registry();
        ToolSnapshot snapshot = registry.snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        TraceSink traceSink = new TraceSink();
        ToolRuntime runtime = new ToolRuntime(Map.of(), Set.of("weather-service"),
                new PermissionEngine(), new ApprovalStore(), new AuditSink(),
                new ObjectMapper(), null, traceSink);
        ExecutionContext context = new ExecutionContext("trace-1", "u", "tenant", Set.of("weather:read"));
        AgentLoop loop = new AgentLoop(runtime, 4, traceSink);

        AtomicInteger calls = new AtomicInteger();
        ModelClient client = (messages, tools) -> {
            if (calls.incrementAndGet() == 1) {
                return new ModelClient.AssistantTurn(null,
                        List.of(new ToolCall("c1", "query_weather", "{\"city\":\"北京\"}")));
            }
            return new ModelClient.AssistantTurn("北京今天晴", null);
        };

        loop.run(List.of(LoopMessage.of("user", "查天气")), snapshot, context, client);

        List<TraceEvent> events = traceSink.byTrace("trace-1");
        assertTrue(events.stream().anyMatch(e -> "tool.execution_end".equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> "loop.turn_end".equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> "loop.final_answer".equals(e.eventType())));
    }

    private ToolDefinition refundTool() {
        ObjectMapper mapper = new ObjectMapper();
        ToolHandler handler = (params, ctx) -> mapper.createObjectNode().put("ok", true);
        return new ToolDefinition(
                "refund_tool", "1.0.0", "desc",
                mapper.createObjectNode().put("type", "object"), null, handler,
                new ToolPolicy(Effect.WRITE, RiskLevel.HIGH, "refund:create",
                        false, false, true, 1.0, 0, false, true),
                Set.of(), null, null);
    }
}
