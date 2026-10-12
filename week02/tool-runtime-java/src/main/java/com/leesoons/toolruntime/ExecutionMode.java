package com.leesoons.toolruntime;

/**
 * 工具执行模式：读操作可并发，写操作应串行。
 */
public enum ExecutionMode {
    SEQUENTIAL,
    PARALLEL
}
