package com.example.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局过滤器示例：对所有经过网关的请求打印日志。
 *
 * GlobalFilter 对每一条路由都生效，真实项目里可以在这里做：
 *  - 统一鉴权（校验 Token / JWT）
 *  - 限流
 *  - 请求/响应日志
 *  - 黑名单拦截
 *
 * 这是网关"统一治理"能力的落点：写一次，所有服务都生效。
 */
@Slf4j
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod().name();
        String remoteAddr = request.getRemoteAddress() != null
                ? request.getRemoteAddress().getHostString() : "unknown";

        long start = System.currentTimeMillis();
        log.info("[Gateway] 收到请求 -> {} {} from {}", method, path, remoteAddr);

        // chain.filter(exchange) 把请求继续往下传；
        // .then() 是响应返回时的回调，可以在这里记录耗时、响应状态等
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long cost = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            log.info("[Gateway] 请求完成 <- {} {} status={} cost={}ms", method, path, status, cost);
        }));
    }

    /**
     * 过滤器执行顺序，数字越小越先执行。
     * 用 -1 让日志过滤靠前，先记下请求再交给后续（鉴权等）过滤器处理。
     */
    @Override
    public int getOrder() {
        return -1;
    }
}
