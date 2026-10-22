package com.leesoons.agentloop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.model.ChatMessage;
import com.leesoons.llmgateway.model.ChatRequest;
import com.leesoons.llmgateway.model.ChatResponse;
import com.leesoons.llmgateway.model.TokenUsage;
import com.leesoons.llmgateway.model.ToolCallMessage;
import com.leesoons.llmgateway.service.GatewayService;
import com.leesoons.agentcore.LoopMessage;
import com.leesoons.agentcore.ModelClient;
import com.leesoons.toolruntime.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 Week01 Gateway 包装成 Agent Loop 的 ModelClient。
 *
 * <p>负责两边类型转换：LoopMessage -> ChatMessage、providerTools -> tools、
 * ChatResponse.toolCalls -> ToolCall。Gateway 本身不感知工具语义，只做协议透传。</p>
 */
public final class GatewayModelClient implements ModelClient {

    private final GatewayService gateway;
    private final ObjectMapper mapper;
    private final String model;
    private final String identity;

    public GatewayModelClient(GatewayService gateway, ObjectMapper mapper, String model, String identity) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.model = model;
        this.identity = identity;
    }

    @Override
    public AssistantTurn call(List<LoopMessage> messages, List<Map<String, Object>> tools) {
        ChatRequest request = new ChatRequest();
        request.setModel(model);
        request.setMessages(toChatMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            List<JsonNode> toolNodes = new ArrayList<>();
            for (Map<String, Object> tool : tools) {
                toolNodes.add(mapper.valueToTree(tool));
            }
            request.setTools(toolNodes);
        }

        ChatResponse response = gateway.chat(request, identity).block();
        if (response == null) {
            return new AssistantTurn("网关未返回响应", null);
        }
        TokenUsage usage = response.getUsage();
        ModelClient.Usage turnUsage = usage == null
                ? null
                : new ModelClient.Usage(usage.getTotalTokens(), usage.getCostUsd());
        return new AssistantTurn(response.getContent(), toToolCalls(response.getToolCalls()), turnUsage);
    }

    private List<ChatMessage> toChatMessages(List<LoopMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (LoopMessage m : messages) {
            ChatMessage cm = new ChatMessage(m.role(), m.content());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<ToolCallMessage> tcs = new ArrayList<>();
                for (ToolCall c : m.toolCalls()) {
                    tcs.add(new ToolCallMessage(c.id(), c.name(), c.argumentsJson()));
                }
                cm.setToolCalls(tcs);
            }
            if (m.toolCallId() != null) {
                cm.setToolCallId(m.toolCallId());
            }
            result.add(cm);
        }
        return result;
    }

    private List<ToolCall> toToolCalls(List<ToolCallMessage> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (ToolCallMessage tc : toolCalls) {
            result.add(new ToolCall(tc.getId(), tc.getName(), tc.getArguments()));
        }
        return result;
    }
}
