package com.leesoons.llmgateway.model;

/**
 * 供应商无关的工具调用提议。由 Adapter 从上游响应解析而来，供 Agent Loop 交给 Tool Runtime 执行。
 *
 * <p>arguments 保持为 JSON 字符串，与 Week02 Tool Runtime 的 {@code ToolCall.argumentsJson} 对齐。</p>
 */
public class ToolCallMessage {

    private String id;
    private String name;
    private String arguments;

    public ToolCallMessage() {
    }

    public ToolCallMessage(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }
}
