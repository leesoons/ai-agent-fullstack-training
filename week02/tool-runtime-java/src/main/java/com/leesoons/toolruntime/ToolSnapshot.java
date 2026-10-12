package com.leesoons.toolruntime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次 Agent 请求固定使用的只读工具视图，防止中途「路由漂移」。
 *
 * <p>快照一旦生成，模型看到的工具列表（{@link #providerTools()}）与
 * Runtime 查找的工具（{@link #get(String)}）都来自同一份不可变数据，
 * 保证「模型看到的版本」和「Runtime 执行的版本」一致。</p>
 */
public final class ToolSnapshot {

    private final Map<String, ToolDefinition> tools;

    public ToolSnapshot(Map<String, ToolDefinition> tools) {
        this.tools = Map.copyOf(tools);
    }

    /**
     * 输出本次请求下发给模型的可调用工具列表（OpenAI Function Calling 格式）。
     */
    public List<Map<String, Object>> providerTools() {
        return tools.values().stream()
                .map(tool -> tool.schema().toProviderTool())
                .toList();
    }

    /**
     * 按调用方权限投影：只暴露有权限或无权限要求的工具。
     */
    public List<Map<String, Object>> providerToolsFor(Set<String> permissions) {
        return tools.values().stream()
                .filter(tool -> tool.permission() == null
                        || (permissions != null && permissions.contains(tool.permission())))
                .map(tool -> tool.schema().toProviderTool())
                .toList();
    }

    /**
     * 供 Runtime 按工具名查找本次请求锁定的工具版本。
     */
    public ToolDefinition get(String name) {
        return tools.get(name);
    }
}
