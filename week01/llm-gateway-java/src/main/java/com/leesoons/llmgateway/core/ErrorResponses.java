package com.leesoons.llmgateway.core;

import com.leesoons.llmgateway.model.ErrorResponse;

/**
 * 构造 OpenAI 风格错误响应。
 */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static ErrorResponse of(GatewayException error) {
        return new ErrorResponse(new ErrorResponse.ErrorDetail(
                error.getMessage(),
                error.getErrorType(),
                error.getParam(),
                error.getCode(),
                error.getDetails()
        ));
    }
}
