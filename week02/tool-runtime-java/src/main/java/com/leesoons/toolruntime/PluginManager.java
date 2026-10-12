package com.leesoons.toolruntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件管理器：加载/卸载插件，校验依赖，保证注册可撤销。
 */
public final class PluginManager {

    private static final Set<String> AVAILABLE_SERVICES = Set.of("tools");

    private final PluginContext context;
    private final Map<String, List<Dispose>> loaded = new LinkedHashMap<>();

    public PluginManager(PluginContext context) {
        this.context = context;
    }

    public void load(ToolPlugin plugin) {
        Set<String> missing = plugin.requires().stream()
                .filter(requirement -> !AVAILABLE_SERVICES.contains(requirement))
                .collect(java.util.stream.Collectors.toSet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("missing services: " + missing);
        }
        if (loaded.containsKey(plugin.name())) {
            throw new IllegalStateException("duplicate plugin: " + plugin.name());
        }
        loaded.put(plugin.name(), plugin.apply(context));
    }

    public void unload(String pluginName) {
        List<Dispose> disposers = loaded.remove(pluginName);
        if (disposers == null) {
            return;
        }
        for (int i = disposers.size() - 1; i >= 0; i--) {
            disposers.get(i).dispose();
        }
    }
}
