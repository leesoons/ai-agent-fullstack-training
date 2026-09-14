package com.leesoons.llmgateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI Chat Completions 协议中的一条消息。content 兼容字符串或数组形式。
 */
public class OpenAiChatMessage {

    private String role;
    private Object content;
    private String name;
    @JsonProperty("tool_call_id")
    private String toolCallId;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }
}
