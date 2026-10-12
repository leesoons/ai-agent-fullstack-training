package com.leesoons.toolruntime;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 Agent Loop：模型提议 -> Runtime 执行 -> 结果回填 -> 再请求模型，直到得到最终文本。
 *
 * <p>工具定义与 Loop 分离，新增工具不需要改动本类。</p>
 */
public final class AgentLoop {

    private final ToolRuntime runtime;
    private final int maxRounds;

    public AgentLoop(ToolRuntime runtime, int maxRounds) {
        this.runtime = runtime;
        this.maxRounds = maxRounds;
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
        for (int round = 0; round < maxRounds; round++) {
            ModelClient.AssistantTurn turn = client.call(messages, snapshot.providerToolsFor(context.permissions()));
            if (!turn.wantsTools()) {
                return turn.content() == null ? "模型没有返回文本回复" : turn.content();
            }
            messages.add(LoopMessage.assistant(turn.toolCalls()));
            for (ToolCall call : turn.toolCalls()) {
                ToolResult result = runtime.invoke(snapshot, call, context);
                messages.add(LoopMessage.tool(result.toolCallId(), result.content()));
            }
        }
        return "工具调用轮数已达上限";
    }
}
