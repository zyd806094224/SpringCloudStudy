package com.example.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sentinel 网关限流配置。
 *
 * SCA 2023.x 不会自动装配 Sentinel 的 Gateway 适配器，需要手动注册三个组件：
 *   1. SentinelGatewayFilter           —— 全局过滤器，拦截请求并做流控判定（核心）
 *   2. SentinelGatewayBlockExceptionHandler —— 处理被限流时抛出的 BlockException
 *   3. GatewayCallbackManager.setBlockHandler  —— 自定义限流响应体（统一 JSON）
 *
 * 缺少第 1 个会导致：请求经过网关但 Sentinel 没有任何资源，Dashboard 实时监控空白。
 */
@Configuration
public class GatewayConfiguration {

    private final List<ViewResolver> viewResolvers;
    private final ServerCodecConfigurer serverCodecConfigurer;

    public GatewayConfiguration(ObjectProvider<List<ViewResolver>> viewResolversProvider,
                                ServerCodecConfigurer serverCodecConfigurer) {
        this.viewResolvers = viewResolversProvider.getIfAvailable(Collections::emptyList);
        this.serverCodecConfigurer = serverCodecConfigurer;
    }

    /**
     * 注册 Sentinel 网关全局过滤器（核心组件）。
     *
     * 这是让 Sentinel "看到" 网关流量的关键——它会：
     *   1. 把每条网关路由识别为 Sentinel 资源（资源名 = 路由 id，如 service-product）
     *   2. 对每个进入的请求执行流控规则判定
     *   3. 超限则抛出 BlockException
     *
     * 用 @Order(-1000) 让它尽量靠前执行，在路由转发之前就完成限流判定。
     */
    @Bean
    @Order(-1000)
    public SentinelGatewayFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    /**
     * 限流异常处理器：捕获 Sentinel 抛出的 BlockException，转成自定义响应。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler() {
        return new SentinelGatewayBlockExceptionHandler(viewResolvers, serverCodecConfigurer);
    }

    /**
     * 自定义限流响应内容：统一返回 JSON，方便前端处理。
     */
    @PostConstruct
    public void initBlockHandler() {
        BlockRequestHandler handler = (exchange, ex) -> {
            Map<String, Object> body = new HashMap<>();
            body.put("code", 429);
            body.put("msg", "请求过于频繁，请稍后再试");
            body.put("data", null);
            return ServerResponse.status(429)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(body));
        };
        GatewayCallbackManager.setBlockHandler(handler);
    }
}
