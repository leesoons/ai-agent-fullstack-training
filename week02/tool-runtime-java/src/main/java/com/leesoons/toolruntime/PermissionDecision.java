package com.leesoons.toolruntime;

/**
 * 鉴权结论：允许、拒绝或需要人工确认。
 *
 * <p>{@code CONFIRM} 由「需要用户确认」的工具在调用前触发，
 * 不是模型或工具参数可以自行声明的状态。</p>
 */
public enum PermissionDecision {
    ALLOW,
    DENY,
    CONFIRM
}
