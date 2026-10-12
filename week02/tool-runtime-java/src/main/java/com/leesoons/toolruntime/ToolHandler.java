package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具的实际执行逻辑。只由 {@link ToolRuntime} 调用，永远不序列化给模型。
 *
 * <p>{@code params} 是已经通过 inputSchema 校验的输入参数；
 * 返回值会再用 outputSchema 校验。</p>
 *
 * <p>抛出 {@link TransientToolError} 表示上游临时故障，只有幂等工具才允许重试。</p>
 */
@FunctionalInterface
public interface ToolHandler {

    JsonNode handle(JsonNode params, ExecutionContext context) throws Exception;
}
