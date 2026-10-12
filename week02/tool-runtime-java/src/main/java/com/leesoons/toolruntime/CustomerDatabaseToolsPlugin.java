package com.leesoons.toolruntime;

import java.util.List;
import java.util.Set;

/**
 * 客户库工具插件：注册 query_customer_database，依赖 tools service。
 */
public final class CustomerDatabaseToolsPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "customer-database-tools";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools");
    }

    @Override
    public List<Dispose> apply(PluginContext context) {
        return List.of(context.registerTool(SampleTools.customerDatabaseTool()));
    }
}
