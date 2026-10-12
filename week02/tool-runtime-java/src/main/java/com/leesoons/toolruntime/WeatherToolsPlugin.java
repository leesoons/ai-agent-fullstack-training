package com.leesoons.toolruntime;

import java.util.List;
import java.util.Set;

/**
 * 天气工具插件：注册 query_weather，依赖 tools service。
 */
public final class WeatherToolsPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "weather-tools";
    }

    @Override
    public Set<String> requires() {
        return Set.of("tools");
    }

    @Override
    public List<Dispose> apply(PluginContext context) {
        return List.of(context.registerTool(SampleTools.weatherTool()));
    }
}
