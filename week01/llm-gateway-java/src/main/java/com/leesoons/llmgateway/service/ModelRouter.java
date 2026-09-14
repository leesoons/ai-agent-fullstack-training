package com.leesoons.llmgateway.service;

import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.core.GatewayErrorCode;
import com.leesoons.llmgateway.core.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型路由：按 model 别名选择路由，支持 priority 与 weighted_round_robin，附带简单熔断。
 */
@Component
public class ModelRouter {

    private final GatewayProperties config;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public ModelRouter(GatewayProperties config) {
        this.config = config;
    }

    public List<GatewayProperties.RouteTarget> candidates(String model, String api) {
        GatewayProperties.ModelRoute routeConfig = config.getModels().get(model);
        if (routeConfig == null) {
            throw new GatewayException("Unknown model alias: " + model,
                    HttpStatus.NOT_FOUND, "invalid_request_error", GatewayErrorCode.MODEL_NOT_FOUND, "model");
        }
        List<GatewayProperties.RouteTarget> available = routeConfig.getRoutes().stream()
                .filter(target -> {
                    GatewayProperties.Provider provider = config.getProviders().get(target.getProvider());
                    return provider != null && provider.isEnabled()
                            && isAvailable(target.getProvider())
                            && supportsApi(target.getApi(), api);
                })
                .toList();
        if (available.isEmpty()) {
            throw new GatewayException("No healthy route for model '" + model + "'",
                    HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable_error", GatewayErrorCode.NO_HEALTHY_ROUTE, "model");
        }

        if (!"weighted_round_robin".equals(routeConfig.getStrategy())) {
            return available;
        }
        List<GatewayProperties.RouteTarget> weighted = available.stream()
                .flatMap(target -> java.util.stream.IntStream.range(0, Math.max(1, target.getWeight()))
                        .mapToObj(i -> target))
                .toList();
        AtomicLong counter = counters.computeIfAbsent(model, key -> new AtomicLong());
        long offset = Math.floorMod(counter.getAndIncrement(), weighted.size());
        GatewayProperties.RouteTarget primary = weighted.get((int) offset);
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(primary),
                        available.stream().filter(target -> target != primary))
                .toList();
    }

    public void recordSuccess(String provider) {
        circuits.put(provider, new CircuitState());
    }

    private static boolean supportsApi(String routeApi, String requestedApi) {
        return routeApi == null || routeApi.isBlank()
                || "both".equalsIgnoreCase(routeApi)
                || routeApi.equalsIgnoreCase(requestedApi);
    }

    public void recordFailure(String provider) {
        CircuitState state = circuits.computeIfAbsent(provider, key -> new CircuitState());
        state.failures += 1;
        if (state.failures >= config.getCircuitBreaker().getFailureThreshold()) {
            state.openedAtNanos = System.nanoTime();
        }
    }

    public Map<String, Map<String, Object>> status() {
        Map<String, Map<String, Object>> result = new ConcurrentHashMap<>();
        circuits.forEach((provider, state) -> {
            Map<String, Object> info = new java.util.LinkedHashMap<>();
            info.put("failures", state.failures);
            info.put("open", state.openedAtNanos > 0 && !isAvailable(provider));
            result.put(provider, info);
        });
        return result;
    }

    private boolean isAvailable(String provider) {
        CircuitState state = circuits.get(provider);
        if (state == null || state.openedAtNanos == 0) {
            return true;
        }
        long cooldownNanos = config.getCircuitBreaker().getCooldownSeconds() * 1_000_000_000L;
        if (System.nanoTime() - state.openedAtNanos >= cooldownNanos) {
            state.failures = 0;
            state.openedAtNanos = 0;
            return true;
        }
        return false;
    }

    private static final class CircuitState {
        private int failures;
        private long openedAtNanos;
    }
}
