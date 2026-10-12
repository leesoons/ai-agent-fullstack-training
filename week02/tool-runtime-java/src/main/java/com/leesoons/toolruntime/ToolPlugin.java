package com.leesoons.toolruntime;

import java.util.List;
import java.util.Set;

/**
 * 工具插件：把一组工具注册进 Runtime，并声明自己依赖的 service。
 *
 * <p>工具定义与 Agent Loop 分离，新增工具只需新增插件，不改循环。</p>
 */
public interface ToolPlugin {

    String name();

    Set<String> requires();

    List<Dispose> apply(PluginContext context);
}
