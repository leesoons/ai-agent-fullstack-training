package com.leesoons.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 MCP Server 框架：负责 JSON-RPC 分发与工具协议样板。
 *
 * <p>业务方通过声明式 API 注册工具（{@code name + description + inputSchema + handler}）、
 * 资源与提示词；{@code initialize / tools/list / tools/call / resources/list / prompts/list}
 * 的协议样板全部由本类收口，新增业务能力不需要重写任何 JSON-RPC 逻辑。</p>
 */
public final class McpServer {

    /** 单个 JSON-RPC 方法的处理逻辑：入参 params，出参 result。 */
    public interface Handler {
        JsonNode handle(JsonNode params) throws Exception;
    }

    private record ToolEntry(String name, String description, JsonNode inputSchema, ToolHandler handler) {
    }

    private final String name;
    private final String version;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Handler> handlers = new LinkedHashMap<>();
    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private final List<ObjectNode> resources = new ArrayList<>();
    private final List<ObjectNode> prompts = new ArrayList<>();

    public McpServer(String name, String version) {
        this.name = name;
        this.version = version;
        handlers.put("initialize", this::initialize);
        handlers.put("tools/list", this::listTools);
        handlers.put("tools/call", this::callTool);
        handlers.put("resources/list", this::listResources);
        handlers.put("prompts/list", this::listPrompts);
    }

    /**
     * 声明式注册一个工具：协议样板（发现、分发、错误包装）由框架统一处理。
     */
    public McpServer registerTool(String toolName, String description, JsonNode inputSchema, ToolHandler handler) {
        tools.put(toolName, new ToolEntry(toolName, description, inputSchema, handler));
        return this;
    }

    public McpServer registerResource(String uri, String resourceName) {
        ObjectNode resource = mapper.createObjectNode();
        resource.put("uri", uri);
        resource.put("name", resourceName);
        resources.add(resource);
        return this;
    }

    public McpServer registerPrompt(String promptName, String description) {
        ObjectNode prompt = mapper.createObjectNode();
        prompt.put("name", promptName);
        prompt.put("description", description);
        prompts.add(prompt);
        return this;
    }

    private JsonNode initialize(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.set("capabilities", mapper.createObjectNode());
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", name);
        serverInfo.put("version", version);
        return result;
    }

    private JsonNode listTools(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode toolList = result.putArray("tools");
        for (ToolEntry tool : tools.values()) {
            ObjectNode node = toolList.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    private JsonNode callTool(JsonNode params) {
        String toolName = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        ToolEntry tool = tools.get(toolName);
        if (tool == null) {
            return wrapResult(toolName, errorPayload("TOOL_NOT_FOUND"), true);
        }
        try {
            ObjectNode payload = (ObjectNode) tool.handler().execute(arguments);
            payload.put("ok", true);
            return wrapResult(toolName, payload, false);
        } catch (ToolError e) {
            return wrapResult(toolName, errorPayload(e.code()), true);
        } catch (Exception e) {
            return wrapResult(toolName, errorPayload("INTERNAL_ERROR"), true);
        }
    }

    private JsonNode listResources(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        result.set("resources", mapper.valueToTree(resources));
        return result;
    }

    private JsonNode listPrompts(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        result.set("prompts", mapper.valueToTree(prompts));
        return result;
    }

    private JsonNode wrapResult(String toolName, ObjectNode payload, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", isError ? payload.path("code").asText() : toolName + " 执行成功");
        result.set("structuredContent", payload);
        result.put("isError", isError);
        return result;
    }

    private ObjectNode errorPayload(String code) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("ok", false);
        payload.put("code", code);
        return payload;
    }

    /**
     * 阻塞式 stdio 主循环：逐行读请求，分发处理，逐行写响应。
     */
    public void runStdio() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode request = mapper.readTree(line);
            Long id = request.hasNonNull("id") ? request.get("id").asLong() : null;
            String method = request.path("method").asText();
            if (id == null) {
                // 通知（如 notifications/initialized），不回响应。
                continue;
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            Handler handler = handlers.get(method);
            try {
                if (handler == null) {
                    response.set("error", errorNode(-32601, "Method not found: " + method));
                } else {
                    response.set("result", handler.handle(request.path("params")));
                }
            } catch (Exception e) {
                response.set("error", errorNode(-32603, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }

            writer.write(mapper.writeValueAsString(response));
            writer.newLine();
            writer.flush();
        }
    }

    private ObjectNode errorNode(int code, String message) {
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        return error;
    }
}
