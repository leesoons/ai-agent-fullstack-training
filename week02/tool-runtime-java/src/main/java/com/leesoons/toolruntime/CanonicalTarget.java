package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 规范化业务目标：为写操作生成资源锁键，例如 {@code tenant:order_id}。
 *
 * <p>同一目标的写操作串行执行，防止并发请求重复写或产生竞争。</p>
 */
@FunctionalInterface
public interface CanonicalTarget {

    String target(JsonNode arguments, ExecutionContext context);
}
