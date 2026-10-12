package com.leesoons.toolruntime;

/**
 * 模型返回的工具调用候选：只有 id、name 和 JSON 参数，不是执行许可。
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
