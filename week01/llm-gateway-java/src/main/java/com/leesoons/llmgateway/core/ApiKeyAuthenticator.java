package com.leesoons.llmgateway.core;

import com.leesoons.llmgateway.config.GatewayProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * Bearer API Key 鉴权过滤器，作用于 /v1/** 与 /admin/**。
 * 未配置密钥时降级为匿名指纹，方便本地开发。
 */
@Component
public class ApiKeyAuthenticator implements WebFilter {

    public static final String IDENTITY_ATTR = "gateway.identity";

    private final GatewayProperties properties;

    public ApiKeyAuthenticator(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!(path.startsWith("/v1/") || path.startsWith("/admin/"))) {
            return chain.filter(exchange);
        }

        List<String> configured = properties.getApiKeys().stream().filter(k -> k != null && !k.isBlank()).toList();
        if (configured.isEmpty()) {
            exchange.getAttributes().put(IDENTITY_ATTR, "anonymous");
            return chain.filter(exchange);
        }

        String xApiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        final String supplied;
        if (authorization != null && authorization.toLowerCase().startsWith("bearer ")) {
            supplied = authorization.substring(7).trim();
        } else {
            supplied = xApiKey;
        }

        if (supplied == null || configured.stream().noneMatch(candidate -> constantTimeEquals(supplied, candidate))) {
            return writeUnauthorized(exchange);
        }

        exchange.getAttributes().put(IDENTITY_ATTR, fingerprint(supplied));
        return chain.filter(exchange);
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set("Content-Type", "application/json");
        String body = "{\"error\":{\"message\":\"Invalid or missing API key\","
                + "\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"}}";
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    public static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "anonymous";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "anonymous";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
