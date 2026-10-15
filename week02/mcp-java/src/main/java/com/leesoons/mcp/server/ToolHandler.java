package com.leesoons.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具执行逻辑：入参为校验后的 JSON 参数，出参为业务数据。
 *
 * <p>业务方只写这段逻辑；{@code ok} 标记、错误包装、{@code tools/call}
 * 的协议样板统一由 {@link McpServer} 处理。</p>
 */
@FunctionalInterface
public interface ToolHandler {

    JsonNode execute(JsonNode arguments) throws Exception;
}
