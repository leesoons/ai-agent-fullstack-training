package com.leesoons.llmgateway.core;

import com.leesoons.llmgateway.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.List;
import java.util.Map;

/**
 * 将网关异常与校验异常统一转换为 OpenAI 风格错误 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException error) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(error.getStatus());
        if (error.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header("Retry-After", "1");
        }
        return builder.body(ErrorResponses.of(error));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException error) {
        List<Map<String, Object>> details = error.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.<String, Object>of(
                        "field", field.getField(),
                        "message", field.getDefaultMessage() == null ? "" : field.getDefaultMessage(),
                        "rejected_value", String.valueOf(field.getRejectedValue())))
                .toList();
        ErrorResponse response = new ErrorResponse(new ErrorResponse.ErrorDetail(
                "Invalid request", "invalid_request_error", null, "validation_error", details));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception error) {
        log.error("Unexpected gateway error", error);
        ErrorResponse response = new ErrorResponse(new ErrorResponse.ErrorDetail(
                "Internal gateway error", "gateway_error", null, "internal_error", null));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
