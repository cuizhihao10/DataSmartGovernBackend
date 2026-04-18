package com.czh.datasmart.govern.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关契约过滤器。
 * <p>
 * 这个过滤器的目标不是做复杂安全控制，而是给当前项目补一层“请求链路契约”：
 * 1. 为每个经过网关的请求补充统一 requestId，方便跨模块追踪。
 * 2. 把外部入口前缀标记成 header，帮助后端理解自己是从哪个网关契约进来的。
 * 3. 保留原始路径信息，便于后续排障和 observability 模块聚合。
 * <p>
 * 这属于一种很常见的网关职责：在业务请求真正进入各模块之前，先补全跨模块通用上下文。
 */
@Component
public class GatewayContractFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = request.getHeaders().getFirst("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        String routePrefix = resolveRoutePrefix(request.getPath().value());
        ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-Request-Id", requestId)
                .header("X-Gateway-Route-Prefix", routePrefix)
                .header("X-Gateway-Original-Path", request.getURI().getPath())
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 根据外部请求路径推断它命中了哪类网关前缀。
     * <p>
     * 当前逻辑保持非常显式，目的是让路径契约一眼可读。
     */
    private String resolveRoutePrefix(String path) {
        if (path.startsWith("/api/task/")) {
            return "/api/task/**";
        }
        if (path.startsWith("/api/datasource/")) {
            return "/api/datasource/**";
        }
        if (path.startsWith("/api/quality/")) {
            return "/api/quality/**";
        }
        if (path.startsWith("/api/observability/")) {
            return "/api/observability/**";
        }
        return "/**";
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
