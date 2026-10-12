package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型可见的工具「安全投影」。
 *
 * <p>只携带模型需要的信息：名称、描述、输入参数的 JSON Schema。
 * 不携带 Handler、权限、风险、超时、依赖等治理字段，避免执行细节泄露给模型。</p>
 *
 * <p>对应 Python 2-2 的 {@code ToolSchema}，是 {@link ToolDefinition} 对模型一侧的投影。</p>
 */
public record ToolSchema(String name, String description, JsonNode parameters) {

    /**
     * 输出为 OpenAI Function Calling 兼容的工具描述。
     *
     * <p>返回 {@code {"type": "function", "function": {"name":..., "description":..., "parameters":...}}}
     * 这样 Agent Loop 之后可以直接放进 Gateway 的 tools 请求里。</p>
     */
    public Map<String, Object> toProviderTool() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }
}
