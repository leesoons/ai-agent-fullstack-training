package com.leesoons.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * MCP Streamable HTTP 客户端传输：每条 JSON-RPC 消息通过一个 HTTP POST 送达同一端点。
 *
 * <p>按 MCP 规范，请求头带 {@code Accept: application/json, text/event-stream}；
 * 服务端返回 {@code application/json}（单条响应）或 {@code text/event-stream}（SSE，
 * 逐行 {@code data: ...}）。初始化时服务端可能下发 {@code Mcp-Session-Id}，后续请求原样带回。</p>
 */
public final class StreamableHttpClientTransport implements McpTransport {

    private final String endpoint;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String sessionId;

    public StreamableHttpClientTransport(String endpoint, Map<String, String> headers) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint 不能为空");
        }
        this.endpoint = endpoint;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public void start() {
        // Streamable HTTP 没有独立握手步骤，initialize 由 McpClient 作为第一条请求发出。
    }

    @Override
    public JsonNode send(long id, String method, JsonNode params, long timeoutMillis) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
        headers.forEach(builder::header);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> this.sessionId = value);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MCP HTTP " + response.statusCode() + ": " + response.body());
        }
        return parse(response.headers().firstValue("Content-Type").orElse("application/json"), response.body());
    }

    @Override
    public void notify(String method, JsonNode params) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
        headers.forEach(builder::header);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        // 通知无响应体，仅确认可达即可；失败不阻塞主流程。
        http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode parse(String contentType, String body) throws Exception {
        if (contentType != null && contentType.contains("text/event-stream")) {
            for (String line : body.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    String data = trimmed.substring(5).trim();
                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                        return mapper.readTree(data);
                    }
                }
            }
            return mapper.createObjectNode();
        }
        return mapper.readTree(body);
    }

    @Override
    public void close() {
        // JDK HttpClient 无需显式释放连接池。
    }
}
