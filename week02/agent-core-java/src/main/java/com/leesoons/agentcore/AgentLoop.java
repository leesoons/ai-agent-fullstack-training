package com.leesoons.agentcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leesoons.toolruntime.ExecutionContext;
import com.leesoons.toolruntime.LoopBudgetConfig;
import com.leesoons.toolruntime.LoopGuard;
import com.leesoons.toolruntime.ToolCall;
import com.leesoons.toolruntime.ToolResult;
import com.leesoons.toolruntime.ToolRuntime;
import com.leesoons.toolruntime.ToolSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 编排核心：模型提议 -> Runtime 执行 -> 结果回填 -> 再请求模型，直到得到最终文本。
 *
 * <p>它只依赖「工具快照 + 执行器 + 模型抽象」，不关心工具来自本地还是 MCP；
 * 循环的继续/停止由 {@link LoopGuard} 统一裁决。</p>
 */
public final class AgentLoop {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRuntime runtime;
    private final LoopGuard guard;

    /**
     * 仅限制轮数的便捷构造：其余预算维度给足余量。
     */
    public AgentLoop(ToolRuntime runtime, int maxRounds) {
        this(runtime, new LoopGuard(LoopBudgetConfig.ofTurns(maxRounds)));
    }

    public AgentLoop(ToolRuntime runtime, LoopGuard guard) {
        this.runtime = runtime;
        this.guard = guard;
    }

    /**
     * 运行闭环，返回模型最终回答。
     *
     * @param initial 初始消息（至少含 system/user）
     * @param snapshot 本次请求冻结的工具视图
     * @param context 服务端注入的执行上下文
     * @param client 模型调用抽象
     */
    public String run(List<LoopMessage> initial,
                      ToolSnapshot snapshot,
                      ExecutionContext context,
                      ModelClient client) {
        List<LoopMessage> messages = new ArrayList<>(initial);
        while (true) {
            ModelClient.AssistantTurn turn = client.call(messages, snapshot.providerToolsFor(context.permissions()));
            if (!turn.wantsTools()) {
                return turn.content() == null ? "模型没有返回文本回复" : turn.content();
            }
            messages.add(LoopMessage.assistant(turn.toolCalls()));
            for (ToolCall call : turn.toolCalls()) {
                LoopGuard.GuardDecision decision = guard.beforeToolCall(call.name(), call.argumentsJson());
                if (!decision.allow()) {
                    messages.add(LoopMessage.tool(call.id(), deniedResult(call, decision).toModelMessage()));
                    continue;
                }
                ToolResult result = runtime.invoke(snapshot, call, context);
                messages.add(LoopMessage.tool(result.toolCallId(), result.toModelMessage()));
            }
            guard.observeTurn();
            LoopGuard.StopDecision stop = guard.shouldStop();
            if (stop.stop()) {
                return "Agent 循环停止: " + stop.code() + " - " + stop.reason();
            }
        }
    }

    private ToolResult deniedResult(ToolCall call, LoopGuard.GuardDecision decision) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("code", decision.code());
        node.put("message", decision.reason());
        return new ToolResult(call.id(), call.name(), node.toString(), true, decision.code());
    }
}
