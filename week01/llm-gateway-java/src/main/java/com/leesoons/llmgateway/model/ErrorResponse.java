package com.leesoons.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI 风格错误响应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private ErrorDetail error;

    public ErrorResponse() {
    }

    public ErrorResponse(ErrorDetail error) {
        this.error = error;
    }

    public ErrorDetail getError() {
        return error;
    }

    public void setError(ErrorDetail error) {
        this.error = error;
    }

    public static class ErrorDetail {
        private String message;
        private String type;
        private String param;
        private String code;
        private Object details;

        public ErrorDetail() {
        }

        public ErrorDetail(String message, String type, String param, String code, Object details) {
            this.message = message;
            this.type = type;
            this.param = param;
            this.code = code;
            this.details = details;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getParam() {
            return param;
        }

        public void setParam(String param) {
            this.param = param;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public Object getDetails() {
            return details;
        }

        public void setDetails(Object details) {
            this.details = details;
        }
    }
}
