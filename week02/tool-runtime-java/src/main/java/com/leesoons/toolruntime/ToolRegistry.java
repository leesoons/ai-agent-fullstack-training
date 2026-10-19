package com.leesoons.toolruntime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具注册表：持有多个工具版本，并依据路由与启用开关生成请求级快照。
 *
 * <p>内部以 {@code name@version} 为键保存工具，支持同一工具的多版本共存。</p>
 */
public final class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    /**
     * 注册一个工具。要求 name、version 非空且 timeout 为正；重复注册抛异常。
     */
    public Dispose register(ToolDefinition tool) {
        if (tool.name() == null || tool.name().isBlank()
                || tool.version() == null || tool.version().isBlank()
                || tool.policy().timeoutSeconds() <= 0) {
            throw new IllegalArgumentException("tool name, version and positive timeout are required");
        }
        String key = key(tool.name(), tool.version());
        if (tools.containsKey(key)) {
            throw new IllegalArgumentException("duplicate tool: " + key);
        }
        tools.put(key, tool);
        return () -> tools.remove(key);
    }

    /**
     * 生成一次请求的只读快照。
     *
     * @param routes  name -> version 的路由表，决定每个工具用哪个版本
     * @param enabled name -> 是否启用的开关，关闭的工具不进入快照
     */
    public ToolSnapshot snapshot(Map<String, String> routes, Map<String, Boolean> enabled) {
        Map<String, ToolDefinition> selected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : routes.entrySet()) {
            String name = entry.getKey();
            String version = entry.getValue();
            if (enabled != null && Boolean.FALSE.equals(enabled.get(name))) {
                continue;
            }
            ToolDefinition tool = tools.get(key(name, version));
            if (tool == null) {
                throw new IllegalArgumentException("tool not registered: " + name + "@" + version);
            }
            selected.put(name, tool);
        }
        return new ToolSnapshot(selected);
    }

    private String key(String name, String version) {
        return name + "@" + version;
    }
}
