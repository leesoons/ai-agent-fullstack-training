package com.leesoons.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 错误对象。
 *
 * <p>{@code code} 遵循规范约定：-32700 解析错误、-32601 方法不存在、-32602 参数非法、-32603 内部错误。</p>
 */
public record JsonRpcError(int code, String message, JsonNode data) {
}
