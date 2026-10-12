package com.leesoons.toolruntime;

/**
 * 插件可用的 service 集合。插件只通过它拿能力，不直接访问 Harness 内部实现。
 */
public record PluginContext(ToolRegistry tools) {

    /**
     * 注册一个工具，返回可撤销句柄。
     */
    public Dispose registerTool(ToolDefinition tool) {
        return tools.register(tool);
    }
}
