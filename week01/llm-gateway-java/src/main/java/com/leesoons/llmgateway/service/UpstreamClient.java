package com.leesoons.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.core.UpstreamException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 对上游供应商的 HTTP 客户端。统一处理超时、错误状态码与异常归一化。
 */
@Component
public class UpstreamClient {

    private final GatewayProperties config;
    private final WebClient webClient;

    public UpstreamClient(GatewayProperties config, WebClient webClient) {
        this.config = config;
        this.webClient = webClient;
    }

    public Mono<JsonNode> requestJson(GatewayProperties.Provider provider, String path, Object body,
                                      Map<String, String> protocolHeaders, String requestId) {
        String url = provider.getBaseUrl() + path;
        return webClient.post()
                .uri(url)
                .headers(headers -> headers.setAll(buildHeaders(provider, protocolHeaders, requestId, false)))
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .flatMap(message -> Mono.error(buildHttpError(provider, response.statusCode(), message))))
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(provider.getTimeoutSeconds()))
                .onErrorMap(this::normalizeError);
    }

    public Flux<ServerSentEvent<String>> openStream(GatewayProperties.Provider provider, String path, Object body,
                                                    Map<String, String> protocolHeaders, String requestId) {
        String url = provider.getBaseUrl() + path;
        return webClient.post()
                .uri(url)
                .headers(headers -> headers.setAll(buildHeaders(provider, protocolHeaders, requestId, true)))
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .flatMap(message -> Mono.error(buildHttpError(provider, response.statusCode(), message))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .timeout(Duration.ofSeconds(provider.getTimeoutSeconds()));
    }

    private Map<String, String> buildHeaders(GatewayProperties.Provider provider,
                                             Map<String, String> protocolHeaders,
                                             String requestId,
                                             boolean sse) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", sse ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE);
        headers.put("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        headers.put("X-Request-ID", requestId);
        headers.putAll(provider.getExtraHeaders());
        headers.putAll(protocolHeaders);
        return headers;
    }

    private Throwable normalizeError(Throwable error) {
        if (error instanceof UpstreamException upstream) {
            return upstream;
        }
        if (error instanceof TimeoutException) {
            return new UpstreamException("upstream timed out", HttpStatus.GATEWAY_TIMEOUT, true, null);
        }
        if (error instanceof org.springframework.web.reactive.function.client.WebClientRequestException requestException) {
            return new UpstreamException("upstream unreachable: " + requestException.getMessage(),
                    HttpStatus.BAD_GATEWAY, true, null);
        }
        // 其余（例如 JSON 解析失败）视为不可重试的上游错误。
        return new UpstreamException("upstream error: " + error.getMessage(), HttpStatus.BAD_GATEWAY, false, null);
    }

    private UpstreamException buildHttpError(GatewayProperties.Provider provider, HttpStatusCode statusCode, String body) {
        int status = statusCode.value();
        boolean retryable = config.getRetry().getRetryStatuses().contains(status);
        HttpStatus httpStatus = status >= 400 && status < 500 ? HttpStatus.valueOf(status) : HttpStatus.BAD_GATEWAY;
        return new UpstreamException(provider.getBaseUrl() + ": HTTP " + status + " " + truncate(body, 1000),
                httpStatus, retryable, truncate(body, 1000));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
