package com.leesoons.llmgateway.service;

import com.leesoons.llmgateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

/**
 * 默认计价源：从 {@code llm.pricing} 静态配置读取价目表。
 */
@Component
public class ConfigPriceProvider implements PriceProvider {

    private final GatewayProperties config;

    public ConfigPriceProvider(GatewayProperties config) {
        this.config = config;
    }

    @Override
    public GatewayProperties.Price price(String model) {
        return config.getPricing().get(model);
    }
}
