package com.leesoons.toolruntime;

/**
 * Runtime 回写给模型的消息：必须保留原始 tool_call_id。
 */
public record ToolResult(String toolCallId, String name, String content, boolean isError) {
}
