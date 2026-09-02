package com.leesoons.llmgateway.adapter;

import com.leesoons.llmgateway.config.GatewayProperties;
import com.leesoons.llmgateway.model.ChatRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 统一 Adapter 接口。每个实现封装一种上游协议的鉴权、请求体映射与响应归一化。
 */
public interface LlmAdapter {

    AdapterProtocol protocol();

    Mono<AdapterResponse> call(GatewayProperties.RouteTarget route,
                               GatewayProperties.Provider provider,
                               ChatRequest request,
                               String requestId);

    Flux<AdapterStreamFrame> stream(GatewayProperties.RouteTarget route,
                                    GatewayProperties.Provider provider,
                                    ChatRequest request,
                                    String requestId);
}
