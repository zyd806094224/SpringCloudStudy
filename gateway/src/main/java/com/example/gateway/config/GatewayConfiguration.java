package com.example.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sentinel 网关限流配置（网关层只做路由级限流）。
 *
 * 网关层负责粗粒度限流：对整条路由（如 service-order、service-product）做 QPS 限制。
 * 接口级限流（如 getTestOrder）由业务服务内部用 @SentinelResource 注解实现，不在网关做。
 *
 * ⚠️ SCA 2023.x 的自动数据源配置在 gateway（WebFlux）下不稳定，
 *    这里直接用 NacosDataSource 手动从 Nacos 拉规则，绕开 SCA 自动装配。
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
     * 手动从 Nacos 加载网关流控规则。
     * 监听 gateway-sentinel-gw-flow.json，启动时拉一次，后续变更自动推送。
     *
     * 连接信息复用 spring.cloud.nacos.*（和服务发现、配置中心用同一个 Nacos），
     * 不再单独配 spring.cloud.sentinel.datasource，避免 SCA 对 gw-flow 缺少 converter 报错。
     */
    @Value("${spring.cloud.nacos.server-addr}")
    private String nacosServerAddr;
    @Value("${spring.cloud.nacos.username}")
    private String nacosUsername;
    @Value("${spring.cloud.nacos.password}")
    private String nacosPassword;
    @Value("${spring.cloud.nacos.discovery.namespace}")
    private String nacosNamespace;
    private static final String NACOS_GROUP_ID = "SENTINEL_GROUP";
    private static final String NACOS_DATA_ID = "gateway-sentinel-gw-flow.json";

    @PostConstruct
    public void initNacosDataSource() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("serverAddr", nacosServerAddr);
        props.setProperty("username", nacosUsername);
        props.setProperty("password", nacosPassword);
        props.setProperty("namespace", nacosNamespace);

        ReadableDataSource<String, Set<GatewayFlowRule>> ds = new NacosDataSource<>(
                props, NACOS_GROUP_ID, NACOS_DATA_ID,
                source -> {
                    // FAIL_ON_UNKNOWN_PROPERTIES=false：GatewayFlowRule 只认 9 个字段，
                    // 若 Nacos 中带了 comment 等额外字段（比如 Dashboard 下发的），忽略掉而不是整个规则集解析失败。
                    ObjectMapper mapper = new ObjectMapper()
                            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    try {
                        return mapper.readValue(source, new TypeReference<Set<GatewayFlowRule>>() {});
                    } catch (Exception e) {
                        System.err.println("[Sentinel] 解析 Nacos 网关流控规则失败: " + e.getMessage());
                        return Collections.emptySet();
                    }
                });
        GatewayRuleManager.register2Property(ds.getProperty());
        System.out.println("[Sentinel] Nacos 数据源已注册，监听 dataId=" + NACOS_DATA_ID);
    }

    /**
     * 注册 Sentinel 网关全局过滤器（核心组件）。
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
