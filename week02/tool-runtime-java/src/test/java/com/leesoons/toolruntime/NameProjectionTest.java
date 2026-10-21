package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 2-5：命名投影——内部名投影为合法模型名，并对投影碰撞显式查重。
 */
class NameProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void projectsDotsToDoubleUnderscore() {
        assertEquals("order__get_status", NameProjection.toModelToolName("order.get_status"));
        assertEquals("order__get_status", NameProjection.toModelToolName("order__get_status"));
    }

    @Test
    void rejectsUnsafeProjection() {
        assertThrows(IllegalArgumentException.class, () -> NameProjection.toModelToolName(""));
    }

    @Test
    void registryRejectsProjectedNameCollision() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("order.get_status"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool("order__get_status")));
    }

    private ToolDefinition tool(String name) {
        return new ToolDefinition(
                name, "1.0.0", "desc", objectSchema(), null,
                (params, ctx) -> MAPPER.createObjectNode().put("ok", true),
                new ToolPolicy(Effect.READ, RiskLevel.LOW, null,
                        false, false, false, 1.0, 0, true, true),
                Set.of(), null, null);
    }

    private JsonNode objectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return schema;
    }
}
