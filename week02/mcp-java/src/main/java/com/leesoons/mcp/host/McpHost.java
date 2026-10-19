package com.leesoons.mcp.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leesoons.mcp.client.McpClient;
import com.leesoons.toolruntime.Effect;
import com.leesoons.toolruntime.RiskLevel;
import com.leesoons.toolruntime.ToolDefinition;
import com.leesoons.toolruntime.ToolHandler;
import com.leesoons.toolruntime.ToolPolicy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * MCP Host：把远端 Server 的能力「发现 → 命名投影 → Adapter 归一化」后，
 * 适配成统一的 {@link ToolDefinition}，交给项目唯一的 {@code ToolRuntime}/{@code AgentLoop} 执行。
 *
 * <p>MCP 从此只是「工具的一个来源」，不再是另一套循环。</p>
 */
public final class McpHost {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULT_CHARS = 2_000;

    /**
     * 发现远端工具，做命名投影（{@code serverId_remoteName}），并把远端调用
     * 包装成统一的 {@link ToolHandler}——远端业务结果经 Adapter 归一化后回传。
     */
    public Map<String, ToolDefinition> discoverTools(McpClient client, String serverId) throws Exception {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        for (JsonNode remote : client.listTools()) {
            final String remoteName = remote.path("name").asText();
            String qualifiedName = serverId + "_" + remoteName;
            ToolHandler handler = (params, context) -> {
                JsonNode response = client.callTool(remoteName, params);
                return resultPayload(response);
            };
            definitions.put(qualifiedName, new ToolDefinition(
                    qualifiedName,
                    "1.0.0",
                    remote.path("description").asText(""),
                    remote.path("inputSchema"),
                    null,
                    handler,
                    new ToolPolicy(Effect.READ, RiskLevel.LOW, null,
                            false, false, false, 5.0, 0, false, true),
                    Set.of(), null, null));
        }
        return definitions;
    }

    /**
     * Adapter 归一化：把 MCP {@code tools/call} 的异构结果统一成 Host payload。
     *
     * <p>注意：远端 {@code isError=true} 属于「业务结果」而非「执行失败」，
     * 因此这里归一化后正常回传，让模型读到「订单不存在」等语义并自行调整。</p>
     */
    public static ObjectNode resultPayload(JsonNode toolResult) {
        ObjectNode payload = MAPPER.createObjectNode();
        if (toolResult.path("isError").asBoolean(false)) {
            String text = toolResult.path("content").path(0).path("text").asText("MCP_TOOL_ERROR");
            payload.put("ok", false);
            payload.put("code", "MCP_TOOL_ERROR");
            payload.put("message", truncate(text, MAX_RESULT_CHARS));
            return payload;
        }

        JsonNode structured = toolResult.path("structuredContent");
        if (structured.isMissingNode() || structured.isNull()) {
            structured = MAPPER.getNodeFactory().objectNode()
                    .put("text", toolResult.path("content").path(0).path("text").asText(""));
        }
        if (serialize(structured).length() > MAX_RESULT_CHARS) {
            payload.put("ok", false);
            payload.put("code", "RESULT_TOO_LARGE");
            return payload;
        }

        if (structured.isObject()) {
            structured.fields().forEachRemaining(entry -> payload.set(entry.getKey(), entry.getValue()));
        } else {
            payload.set("value", structured);
        }
        return payload;
    }

    /** 一次接入后的协议契约快照，用于体检 Server 暴露的能力。 */
    public record Contract(String protocol, Set<String> tools, Set<String> resources, Set<String> prompts) {
    }

    public Contract inspect(McpClient client, String serverId) throws Exception {
        Map<String, ToolDefinition> tools = discoverTools(client, serverId);

        Set<String> resourceUris = new LinkedHashSet<>();
        for (JsonNode resource : client.listResources()) {
            resourceUris.add(resource.path("uri").asText());
        }
        Set<String> promptNames = new LinkedHashSet<>();
        for (JsonNode prompt : client.listPrompts()) {
            promptNames.add(prompt.path("name").asText());
        }
        return new Contract(client.protocolVersion(), tools.keySet(), resourceUris, promptNames);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String serialize(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化远端结果", e);
        }
    }
}
