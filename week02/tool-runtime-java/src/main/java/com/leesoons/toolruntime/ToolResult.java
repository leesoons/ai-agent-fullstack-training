package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Runtime 回写给模型的消息：必须保留原始 tool_call_id，并标记为不可信数据。
 */
public record ToolResult(String toolCallId, String name, String content, boolean isError,
                           String errorCode, String approvalId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ToolResult(String toolCallId, String name, String content, boolean isError, String errorCode) {
        this(toolCallId, name, content, isError, errorCode, null);
    }

    public ToolResult(String toolCallId, String name, String content, boolean isError) {
        this(toolCallId, name, content, isError, null, null);
    }

    /**
     * 生成模型可见的安全视图：脱敏、限制规模，并显式标记 {@code untrusted:true}。
     */
    public String toModelMessage() {
        JsonNode projected;
        try {
            projected = ResultProjector.projectForModel(MAPPER.readTree(content));
        } catch (Exception e) {
            projected = MAPPER.getNodeFactory().textNode(content);
        }
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("source", "tool");
        wrapper.put("tool_name", name);
        wrapper.put("untrusted", true);
        wrapper.put("ok", !isError);
        wrapper.put("error_code", errorCode);
        wrapper.set("content", projected);
        return wrapper.toString();
    }
}
