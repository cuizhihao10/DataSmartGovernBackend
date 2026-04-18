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
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - GatewayContractFilter.java
 * @Version:1.0.0
 *
 * 网关契约过滤器。
 * 它的职责不是做复杂安全控制，而是在请求进入业务模块前补齐一层跨模块上下文，
 * 例如请求 ID、命中的网关前缀、原始路径等，为后续链路追踪和可观测性打基础。
 */
@Component
public class GatewayContractFilter implements GlobalFilter, Ordered {

    /**
     * 统一补充网关上下文请求头。
     * 这样后端模块就不需要各自再重复推断“请求是怎么进来的”。
     */
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
     * 根据请求路径推断它命中了哪个网关前缀。
     * 这里刻意保持显式判断，而不是过度抽象，
     * 目的是让学习者一眼就能把代码和 application.yml 中的路由规则对应起来。
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

    /**
     * 过滤器顺序尽量靠前，让后续过滤链都能拿到补齐后的上下文。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
