package com.leesoons.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 响应：要么携带 {@code result}，要么携带 {@code error}，二者互斥。
 */
public record JsonRpcResponse(String jsonrpc, Long id, JsonNode result, JsonRpcError error) {

    public static JsonRpcResponse success(Long id, JsonNode result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    public static JsonRpcResponse failure(Long id, JsonRpcError error) {
        return new JsonRpcResponse("2.0", id, null, error);
    }
}
