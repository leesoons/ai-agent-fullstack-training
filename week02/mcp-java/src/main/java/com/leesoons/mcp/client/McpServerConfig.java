package com.leesoons.mcp.client;

import java.util.Map;

/**
 * MCP Server 的可配置描述：id / 传输类型 / 端点 / 请求头。
 *
 * <p>把「连接哪个 MCP」从代码里解耦出来，新增一个 Server 只加一条配置，
 * 不必为每个 Server 写一个对接类。</p>
 */
public record McpServerConfig(String id, String type, String url, Map<String, String> headers) {

    public McpServerConfig {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("server id 不能为空");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("server url 不能为空");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static McpServerConfig streamableHttp(String id, String url, Map<String, String> headers) {
        return new McpServerConfig(id, "streamablehttp", url, headers);
    }

    /**
     * 按配置构造客户端。
     */
    public McpClient client() {
        return McpClient.http(url, headers);
    }
}
