package com.leesoons.agentcore;

import com.leesoons.toolruntime.ExecutionContext;
import com.leesoons.toolruntime.SampleTools;
import com.leesoons.toolruntime.ToolCall;
import com.leesoons.toolruntime.ToolRegistry;
import com.leesoons.toolruntime.ToolRuntime;
import com.leesoons.toolruntime.ToolSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals("工具调用轮数已达上限", answer);
    }
}
