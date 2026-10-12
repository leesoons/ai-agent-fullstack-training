package com.leesoons.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 统一消息模型，屏蔽 OpenAI / Anthropic 两种协议的消息结构差异。
 *
 * <p>assistant 消息可携带 {@code tool_calls}，tool 角色消息携带 {@code tool_call_id}。</p>
 */
public class ChatMessage {

    @NotBlank(message = "message.role must not be blank")
    private String role;

    private String content;

    @JsonProperty("tool_calls")
    private List<ToolCallMessage> toolCalls;

    @JsonProperty("tool_call_id")
    private String toolCallId;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolCallMessage> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCallMessage> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }
}
