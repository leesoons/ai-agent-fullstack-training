package com.leesoons.llmgateway.adapter;

import com.leesoons.llmgateway.core.GatewayErrorCode;
import com.leesoons.llmgateway.core.GatewayException;
import org.springframework.http.HttpStatus;

/**
 * 两种上游协议，训练目标是屏蔽二者在鉴权、请求体与返回结构上的差异。
 */
public enum AdapterProtocol {

    OPENAI_RESPONSES("openai_responses"),
    ANTHROPIC_MESSAGES("anthropic_messages");

    private final String value;

    AdapterProtocol(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AdapterProtocol from(String value) {
        for (AdapterProtocol protocol : values()) {
            if (protocol.value.equalsIgnoreCase(value)) {
                return protocol;
            }
        }
        throw new GatewayException("Unknown adapter protocol: " + value,
                HttpStatus.INTERNAL_SERVER_ERROR, "gateway_error", GatewayErrorCode.INVALID_REQUEST, "protocol");
    }
}
