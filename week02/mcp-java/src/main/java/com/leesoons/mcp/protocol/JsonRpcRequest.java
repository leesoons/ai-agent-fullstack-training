package com.leesoons.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 请求。
 *
 * <p>带 {@code id} 的是「请求」，期望一条响应；不带 {@code id} 的是「通知」
 * （如 {@code notifications/initialized}），服务端不应回响应。</p>
 */
public record JsonRpcRequest(String jsonrpc, Long id, String method, JsonNode params) {

    public static JsonRpcRequest of(Long id, String method, JsonNode params) {
        return new JsonRpcRequest("2.0", id, method, params);
    }

    public boolean isNotification() {
        return id == null;
    }
}
