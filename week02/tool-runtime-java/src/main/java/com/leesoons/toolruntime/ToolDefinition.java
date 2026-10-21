package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 注册表内的完整工具定义，是「模型侧字段」与「Runtime 侧字段」的合体。
 *
 * <p>模型侧：{@code name}、{@code description}、{@code inputSchema}，
 * 通过 {@link #schema()} 投影成对模型开放的 {@link ToolSchema}。</p>
 *
 * <p>Runtime 侧：{@code handler}、{@code fallbackHandler}、{@code outputSchema}、
 * {@code policy}、{@code dependencies}、{@code precheck}、{@code canonicalTarget}，
 * 这些字段只由 Runtime 使用。</p>
 */
public record ToolDefinition(
        String name,
        String version,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        ToolHandler handler,
        ToolPolicy policy,
        Set<String> dependencies,
        ToolPrecheck precheck,
        CanonicalTarget canonicalTarget,
        ToolHandler fallbackHandler) {

    public ToolDefinition(
            String name,
            String version,
            String description,
            JsonNode inputSchema,
            JsonNode outputSchema,
            ToolHandler handler,
            ToolPolicy policy,
            Set<String> dependencies,
            ToolPrecheck precheck,
            CanonicalTarget canonicalTarget) {
        this(name, version, description, inputSchema, outputSchema, handler, policy,
                dependencies, precheck, canonicalTarget, null);
    }

    public ToolDefinition {
        if (policy == null) {
            throw new IllegalArgumentException("tool policy 不能为空");
        }
        dependencies = (dependencies == null || dependencies.isEmpty())
                ? Set.of()
                : Set.copyOf(dependencies);
    }

    /**
     * 投影出模型可见的安全视图。
     */
    public ToolSchema schema() {
        return new ToolSchema(name, description, inputSchema);
    }
}
