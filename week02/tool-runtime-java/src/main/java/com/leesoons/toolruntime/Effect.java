package com.leesoons.toolruntime;

/**
 * 工具副作用类别：运行时据此决定并发、重试与锁的边界。
 *
 * <ul>
 *   <li>{@code READ}：只读，可并发、可按策略重试瞬态错误</li>
 *   <li>{@code WRITE}：写，需按业务资源串行，非幂等写不得重放</li>
 *   <li>{@code SHELL}：命令执行，plan 模式直接拒绝</li>
 * </ul>
 */
public enum Effect {
    READ,
    WRITE,
    SHELL
}
