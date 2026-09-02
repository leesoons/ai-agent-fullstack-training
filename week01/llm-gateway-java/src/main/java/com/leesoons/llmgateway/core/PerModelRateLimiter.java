package com.leesoons.llmgateway.core;

import com.leesoons.llmgateway.config.GatewayProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按模型独立的进程内令牌桶限流。超限返回 429。
 * 多副本部署时应替换为 Redis 等共享存储。
 */


@Component
public class PerModelRateLimiter {

    private final GatewayProperties.RateLimit config;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public PerModelRateLimiter(GatewayProperties properties) {
        this.config = properties.getRateLimit();
    }

    public Mono<Void> acquire(String model) {
        if (!config.isEnabled()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> check(model));
    }

    private void check(String model) {
        long now = System.nanoTime();
        double refillPerSecond = config.getRequestsPerMinute() / 60.0;
        double capacity = config.getBurst();

        TokenBucket bucket = buckets.computeIfAbsent(model, key -> new TokenBucket(capacity, now));
        synchronized (bucket) {
            double elapsedSeconds = (now - bucket.updatedAtNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.updatedAtNanos = now;
            if (bucket.tokens < 1.0) {
                long retryAfterSeconds = Math.max(1, (long) Math.ceil((1.0 - bucket.tokens) / refillPerSecond));
                throw new GatewayException(
                        "Rate limit exceeded for model '" + model + "'. Retry in " + retryAfterSeconds + "s",
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate_limit_error",
                        GatewayErrorCode.RATE_LIMITED,
                        "model",
                        Map.of("model", model, "retry_after_seconds", retryAfterSeconds));
            }
            bucket.tokens -= 1.0;
        }
    }

    private static final class TokenBucket {
        private double tokens;
        private long updatedAtNanos;

        private TokenBucket(double tokens, long updatedAtNanos) {
            this.tokens = tokens;
            this.updatedAtNanos = updatedAtNanos;
        }
    }
}
