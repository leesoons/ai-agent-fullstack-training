package com.leesoons.llmgateway.service;

import com.leesoons.llmgateway.config.GatewayProperties;

/**
 * 计价数据源：按逻辑模型名返回当前价格。
 *
 * <p>与供应商 Adapter 同思路：把「价格从哪来」隔离到实现后面。
 * 默认实现读静态配置；未来接入供应商账单 / 实时价目 API，只需替换实现，
 * {@link UsageService#calculateCost} 无需改动。</p>
 */
public interface PriceProvider {

    /**
     * 返回模型当前价格；未配置或未知模型返回 {@code null}，由调用方按零成本处理。
     */
    GatewayProperties.Price price(String model);
}
