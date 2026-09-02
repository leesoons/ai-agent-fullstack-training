package com.leesoons.llmgateway.core;

import org.springframework.http.HttpStatus;

/**
 * 上游调用失败。retryable 决定是否参与指数退避重试。
 */
public class UpstreamException extends GatewayException {

    private final boolean retryable;

    public UpstreamException(String message, HttpStatus status, boolean retryable, Object details) {
        super(message, status, "upstream_error", GatewayErrorCode.UPSTREAM_ERROR, null, details);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
