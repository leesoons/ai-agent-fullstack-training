package com.leesoons.toolruntime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginManagerTest {

    @Test
    void loadAndUnloadPlugin() {
        ToolRegistry registry = new ToolRegistry();
        PluginManager manager = new PluginManager(new PluginContext(registry));
        manager.load(new WeatherToolsPlugin());

        ToolSnapshot snapshot = registry.snapshot(Map.of("query_weather", "1.0.0"), Map.of());
        assertNotNull(snapshot.get("query_weather"));

        manager.unload("weather-tools");

        assertThrows(IllegalArgumentException.class,
                () -> registry.snapshot(Map.of("query_weather", "1.0.0"), Map.of()));
    }

    @Test
    void duplicatePluginThrows() {
        PluginManager manager = new PluginManager(new PluginContext(new ToolRegistry()));
        manager.load(new WeatherToolsPlugin());
        assertThrows(IllegalStateException.class, () -> manager.load(new WeatherToolsPlugin()));
    }

    @Test
    void missingServiceThrows() {
        PluginManager manager = new PluginManager(new PluginContext(new ToolRegistry()));
        ToolPlugin broken = new ToolPlugin() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public Set<String> requires() {
                return Set.of("nonexistent");
            }

            @Override
            public List<Dispose> apply(PluginContext context) {
                return List.of();
            }
        };
        assertThrows(IllegalStateException.class, () -> manager.load(broken));
    }

    @Test
    void providerToolsFiltersByPermission() {
        ToolRegistry registry = SampleTools.registry();
        ToolSnapshot snapshot = registry.snapshot(
                Map.of("query_weather", "1.0.0", "query_customer_database", "1.0.0"),
                Map.of());

        assertEquals(1, snapshot.providerToolsFor(Set.of("weather:read")).size());
        assertEquals(2, snapshot.providerToolsFor(Set.of("weather:read", "database:read")).size());
    }
}
