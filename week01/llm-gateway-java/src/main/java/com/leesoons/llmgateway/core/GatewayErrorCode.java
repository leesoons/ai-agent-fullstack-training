package com.leesoons.llmgateway.core;

/**
 * 统一错误码。参考 Python 版 llm-gateway 的错误语义，迁移为枚举。
 */
public enum GatewayErrorCode {

    INVALID_REQUEST("invalid_request"),
    VALIDATION_ERROR("validation_error"),
    MODEL_NOT_FOUND("model_not_found"),
    AUTH_ERROR("invalid_api_key"),
    RATE_LIMITED("rate_limit_exceeded"),
    UPSTREAM_TIMEOUT("upstream_timeout"),
    UPSTREAM_ERROR("upstream_error"),
    STRUCTURED_OUTPUT_FAILED("invalid_model_output"),
    PROMPT_NOT_FOUND("prompt_not_found"),
    PROMPT_RENDER_ERROR("prompt_render_error"),
    NO_HEALTHY_ROUTE("no_healthy_route");

    private final String code;

    GatewayErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
