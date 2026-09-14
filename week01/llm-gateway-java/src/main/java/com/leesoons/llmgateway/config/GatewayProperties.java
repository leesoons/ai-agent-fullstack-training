package com.leesoons.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网关配置，对应 application.yml 中的 llm.*。
 * 参考 Python 版 llm-gateway 的 GatewayConfig，迁移为 Spring 配置绑定。
 */
@ConfigurationProperties(prefix = "llm")
public class GatewayProperties {

    private String serviceName = "llm-gateway-java";
    private List<String> apiKeys = new ArrayList<>();
    private int structuredOutputRetries = 1;
    /** 落盘目录；留空表示仅内存。用于用量账本与 Run 事件的可选持久化。 */
    private String dataDir = "";

    private Retry retry = new Retry();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private RateLimit rateLimit = new RateLimit();

    private Map<String, Provider> providers = new HashMap<>();
    private Map<String, ModelRoute> models = new HashMap<>();
    private Map<String, Price> pricing = new HashMap<>();

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public int getStructuredOutputRetries() {
        return structuredOutputRetries;
    }

    public void setStructuredOutputRetries(int structuredOutputRetries) {
        this.structuredOutputRetries = structuredOutputRetries;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    public Map<String, ModelRoute> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelRoute> models) {
        this.models = models;
    }

    public Map<String, Price> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Price> pricing) {
        this.pricing = pricing;
    }

    public static class Retry {
        /** 首次调用失败后最多重试次数。默认 3，即最多 4 次尝试。 */
        private int maxRetries = 3;
        private long baseDelayMillis = 250;
        private long maxDelayMillis = 4000;
        private double jitter = 0.2;
        private Set<Integer> retryStatuses = new HashSet<>(Set.of(408, 409, 429, 500, 502, 503, 504));

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getBaseDelayMillis() {
            return baseDelayMillis;
        }

        public void setBaseDelayMillis(long baseDelayMillis) {
            this.baseDelayMillis = baseDelayMillis;
        }

        public long getMaxDelayMillis() {
            return maxDelayMillis;
        }

        public void setMaxDelayMillis(long maxDelayMillis) {
            this.maxDelayMillis = maxDelayMillis;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }

        public Set<Integer> getRetryStatuses() {
            return retryStatuses;
        }

        public void setRetryStatuses(Set<Integer> retryStatuses) {
            this.retryStatuses = retryStatuses;
        }
    }

    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private long cooldownSeconds = 30;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getCooldownSeconds() {
            return cooldownSeconds;
        }

        public void setCooldownSeconds(long cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 60;
        private int burst = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }
    }

    public static class Provider {
        private String baseUrl;
        private String apiKey = "";
        private boolean enabled = true;
        private long timeoutSeconds = 120;
        private long connectTimeoutSeconds = 10;
        private Map<String, String> extraHeaders = new HashMap<>();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? null : trimTrailingSlash(baseUrl);
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public long getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(long connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public Map<String, String> getExtraHeaders() {
            return extraHeaders;
        }

        public void setExtraHeaders(Map<String, String> extraHeaders) {
            this.extraHeaders = extraHeaders;
        }

        private static String trimTrailingSlash(String url) {
            String result = url;
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }
    }

    public static class RouteTarget {
        private String provider;
        private String model;
        /** 协议：openai_responses 或 anthropic_messages。 */
        private String protocol = "openai_responses";
        /** 对外 API 能力：chat、responses 或 both。 */
        private String api = "both";
        private int weight = 1;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getApi() {
            return api;
        }

        public void setApi(String api) {
            this.api = api;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }
    }

    public static class ModelRoute {
        private String strategy = "priority";
        private List<RouteTarget> routes = new ArrayList<>();

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public List<RouteTarget> getRoutes() {
            return routes;
        }

        public void setRoutes(List<RouteTarget> routes) {
            this.routes = routes;
        }
    }

    public static class Price {
        private double inputPerMillion = 0;
        private double outputPerMillion = 0;
        private Double cachedInputPerMillion;

        public double getInputPerMillion() {
            return inputPerMillion;
        }

        public void setInputPerMillion(double inputPerMillion) {
            this.inputPerMillion = inputPerMillion;
        }

        public double getOutputPerMillion() {
            return outputPerMillion;
        }

        public void setOutputPerMillion(double outputPerMillion) {
            this.outputPerMillion = outputPerMillion;
        }

        public Double getCachedInputPerMillion() {
            return cachedInputPerMillion;
        }

        public void setCachedInputPerMillion(Double cachedInputPerMillion) {
            this.cachedInputPerMillion = cachedInputPerMillion;
        }
    }
}
