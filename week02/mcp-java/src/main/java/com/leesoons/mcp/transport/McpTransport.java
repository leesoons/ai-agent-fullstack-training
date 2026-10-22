package com.leesoons.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 客户端传输抽象：屏蔽 stdio 与 Streamable HTTP 的差异。
 *
 * <p>{@link McpClient} 只依赖这个接口做「带 id 的请求」和「无 id 的通知」，
 * 不关心底层是子进程管道还是 HTTP。</p>
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 建立连接（stdio 启动子进程；HTTP 无需特殊握手，由调用方发起 initialize）。
     */
    void start() throws Exception;

    /**
     * 发送一条带 {@code id} 的 JSON-RPC 请求，阻塞等待对应响应。
     */
    JsonNode send(long id, String method, JsonNode params, long timeoutMillis) throws Exception;

    /**
     * 发送一条无 {@code id} 的通知。
     */
    void notify(String method, JsonNode params) throws Exception;

    @Override
    void close();
}
