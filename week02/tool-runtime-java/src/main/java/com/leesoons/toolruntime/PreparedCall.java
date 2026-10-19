package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 已通过调用前治理的内部对象：携带解析后的参数与授权结论，供 execute 阶段使用。
 */
public record PreparedCall(
        ToolCall call,
        ToolDefinition tool,
        JsonNode arguments,
        PermissionDecision decision) {
}
