package com.leesoons.llmgateway.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 统一消息模型，屏蔽 OpenAI / Anthropic 两种协议的消息结构差异。
 */
public class ChatMessage {

    @NotBlank(message = "message.role must not be blank")
    private String role;

    private String content;

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
}
