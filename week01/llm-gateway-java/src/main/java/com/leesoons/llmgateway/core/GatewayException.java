package com.leesoons.llmgateway.core;

import org.springframework.http.HttpStatus;

/**
 * 网关统一异常，最终由 GlobalExceptionHandler 转成 OpenAI 风格错误 JSON。
 */
public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String errorType;
    private final String code;
    private final String param;
    private final Object details;

    public GatewayException(String message, HttpStatus status, String errorType, GatewayErrorCode code) {
        this(message, status, errorType, code, null, null);
    }

    public GatewayException(String message, HttpStatus status, String errorType, GatewayErrorCode code, String param) {
        this(message, status, errorType, code, param, null);
    }

    public GatewayException(String message, HttpStatus status, String errorType, GatewayErrorCode code,
                            String param, Object details) {
        super(message);
        this.status = status;
        this.errorType = errorType;
        this.code = code == null ? null : code.getCode();
        this.param = param;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getCode() {
        return code;
    }

    public String getParam() {
        return param;
    }

    public Object getDetails() {
        return details;
    }
}
