package com.leesoons.llmgateway.core;

import org.springframework.http.HttpStatus;

/**
 * 模型输出不是合法 JSON 或不符合 JSON Schema。
 */
public class StructuredOutputException extends GatewayException {

    public StructuredOutputException(String message, Object details) {
        super(message, HttpStatus.UNPROCESSABLE_ENTITY, "structured_output_error",
                GatewayErrorCode.STRUCTURED_OUTPUT_FAILED, null, details);
    }
}
