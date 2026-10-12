package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 注册表内的完整工具定义，是「模型侧字段」与「Runtime 侧字段」的合体。
 *
 * <p>模型侧：{@code name}、{@code description}、{@code inputSchema}，
 * 通过 {@link #schema()} 投影成对模型开放的 {@link ToolSchema}。</p>
 *
 * <p>Runtime 侧：{@code handler}、{@code outputSchema}、{@code permission}、
 * {@code risk}、{@code timeoutSeconds}、{@code maxRetries}、{@code idempotent}、
 * {@code dependencies}，这些治理与执行字段只由 Runtime 使用，绝不暴露给模型。</p>
 *
 * <p>对应 Python 2-2 的 {@code ToolDefinition}，并融合 2-1 的幂等与重试字段。</p>
 */
public record ToolDefinition(
        String name,
        String version,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        ToolHandler handler,
        String permission,
        RiskLevel risk,
        double timeoutSeconds,
        int maxRetries,
        boolean idempotent,
        Set<String> dependencies) {

    public ToolDefinition {
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
