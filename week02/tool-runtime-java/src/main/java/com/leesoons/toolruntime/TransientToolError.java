package com.leesoons.toolruntime;

/**
 * 上游临时故障。只有幂等工具才允许重试。
 */
public class TransientToolError extends RuntimeException {

    public TransientToolError(String message) {
        super(message);
    }
}
