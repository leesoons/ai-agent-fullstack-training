package com.leesoons.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leesoons.mcp.transport.StdioClientTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 最小 MCP 客户端：负责 stdio 握手、能力发现与工具调用。
 *
 * <p>它只做「协议管道」，不理解业务语义；命名投影与结果归一化交给 Host 层。</p>
 */
public final class McpClient implements AutoCloseable {

    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000;

    private final StdioClientTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private String protocolVersion;

    public McpClient(List<String> command) {
        this.transport = new StdioClientTransport(command);
    }

    /**
     * 建立连接：启动子进程 → 发送 {@code initialize} → 发送 {@code notifications/initialized}。
     */
    public void connect() throws Exception {
        transport.start();

        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "java-mcp-client");
        clientInfo.put("version", "1.0");

        JsonNode response = request("initialize", params);
        if (response.has("error")) {
            throw new IllegalStateException("initialize 失败: " + response.path("error").path("message").asText());
        }
        protocolVersion = response.path("result").path("protocolVersion").asText("unknown");
        transport.notify("notifications/initialized", null);
    }

    /** 协商后的 MCP 协议版本。 */
    public String protocolVersion() {
        return protocolVersion;
    }

    /**
     * 发现 Server 暴露的工具列表，返回 {@code result.tools} 数组。
     */
    public List<JsonNode> listTools() throws Exception {
        JsonNode response = request("tools/list", mapper.createObjectNode());
        List<JsonNode> tools = new ArrayList<>();
        for (JsonNode tool : response.path("result").path("tools")) {
            tools.add(tool);
        }
        return tools;
    }

    /**
     * 调用远端工具，返回完整响应（含 {@code content} / {@code structuredContent} / {@code isError}）。
     */
    public JsonNode callTool(String name, JsonNode arguments) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params).path("result");
    }

    /** 发现 Server 暴露的资源列表，返回 {@code result.resources} 数组。 */
    public List<JsonNode> listResources() throws Exception {
        JsonNode response = request("resources/list", mapper.createObjectNode());
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : response.path("result").path("resources")) {
            items.add(item);
        }
        return items;
    }

    /** 发现 Server 暴露的提示词列表，返回 {@code result.prompts} 数组。 */
    public List<JsonNode> listPrompts() throws Exception {
        JsonNode response = request("prompts/list", mapper.createObjectNode());
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : response.path("result").path("prompts")) {
            items.add(item);
        }
        return items;
    }

    private JsonNode request(String method, JsonNode params) throws Exception {
        return transport.send(nextId.getAndIncrement(), method, params, DEFAULT_TIMEOUT_MILLIS);
    }

    @Override
    public void close() {
        transport.close();
    }
}
