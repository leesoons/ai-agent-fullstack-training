package com.leesoons.toolruntime;

/**
 * 一条可观测的链路事件：按 {@code traceId} 串联一次 Agent Run 的完整上下文。
 *
 * <p>{@code eventType} 区分事件种类（如 {@code tool.execution_end}、{@code loop.turn_end}）；
 * {@code code}/{@code detail} 携带状态码与补充说明，供排查时还原「这一轮发生了什么」。</p>
 */
public record TraceEvent(
        String traceId,
        String eventType,
        String toolName,
        String code,
        String detail) {
}
