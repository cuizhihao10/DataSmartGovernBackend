/**
 * @Author : Cui
 * @Date: 2026/06/29 23:58
 * @Description DataSmart Govern Backend - GatewayOidcAuthenticationContextFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.authentication.GatewayAuthenticationCenterService;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationErrorWriter;
import com.czh.datasmart.govern.gateway.config.GatewayAuthenticationCenterProperties;
import com.czh.datasmart.govern.gateway.controller.dto.GatewayAuthenticationPrincipalView;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * OIDC/JWT 身份上下文注入过滤器。
 *
 * <p>该过滤器是“生产认证中心”真正进入网关请求链路的位置：</p>
 * <p>1. Spring Security OAuth2 Resource Server 先校验 Authorization: Bearer JWT；</p>
 * <p>2. 本过滤器从已经认证的 Authentication/Jwt 中提取租户、操作者、角色、类型和 workspace；</p>
 * <p>3. 写入统一的 X-DataSmart-* Header；</p>
 * <p>4. 后续 GatewayAuthorizationFilter 读取这些 Header，再调用 permission-admin 做 RBAC/数据范围授权。</p>
 *
 * <p>它排在 GatewayContractFilter 之后、GatewayDevelopmentIdentityFilter 和 GatewayAuthorizationFilter 之前。
 * GatewayContractFilter 会先清理外部伪造的 X-DataSmart-*，本过滤器再基于可信 JWT 写入新值。
 * 这样可以保证业务服务看到的身份 Header 来自认证中心，而不是来自客户端自报。</p>
 */
@Component
@RequiredArgsConstructor
public class GatewayOidcAuthenticationContextFilter implements GlobalFilter, Ordered {

    private final GatewayAuthenticationCenterProperties authenticationProperties;
    private final GatewayAuthenticationCenterService authenticationCenterService;
    private final GatewayAuthorizationErrorWriter authorizationErrorWriter;

    /**
     * 把 Spring Security 已验证的 JWT 主体转换成平台上下文 Header。
     *
     * <p>如果当前请求是公开路径或认证中心关闭，可能没有 Authentication，本过滤器会透明放行；
     * 真正是否要求认证由 GatewaySecurityConfig 的 authenticated/permitAll 策略决定。</p>
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!authenticationProperties.isEnabled() || !authenticationProperties.getOidc().isEnabled()) {
            return chain.filter(exchange);
        }

        return exchange.getPrincipal()
                .cast(Authentication.class)
                /*
                 * handleAuthenticated 返回的是 Mono<Void>。
                 * 在 Reactor 里 Mono<Void> 完成时不会发出元素，如果后面直接接 switchIfEmpty，
                 * 即使认证分支已经处理完请求，switchIfEmpty 也会误以为“没有元素”而再次放行原始 exchange。
                 * 因此这里用 thenReturn(true) 生成一个布尔哨兵，明确告诉后续链路：认证主体已经被处理过。
                 */
                .flatMap(authentication -> handleAuthenticated(exchange, chain, authentication).thenReturn(Boolean.TRUE))
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(handled -> handled ? Mono.empty() : chain.filter(exchange));
    }

    private Mono<Void> handleAuthenticated(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           Authentication authentication) {
        GatewayAuthenticationPrincipalView principal =
                authenticationCenterService.currentPrincipal(authentication, exchange.getRequest().getHeaders());
        if (!principal.authenticated()) {
            if (authenticationProperties.getOidc().isFailClosedOnMissingRequiredClaims()
                    && authenticationCenterService.missingRequiredBusinessClaims(principal)) {
                return authorizationErrorWriter.writeForbidden(
                        exchange.getResponse(),
                        exchange.getRequest().getHeaders().getFirst(PlatformContextHeaders.TRACE_ID),
                        "OIDC/JWT 已通过签名校验，但缺少 DataSmart 必需的租户、操作者或角色 claim，网关已失败关闭");
            }
            return chain.filter(exchange);
        }

        ServerHttpRequest authenticatedRequest = exchange.getRequest()
                .mutate()
                .headers(headers -> authenticationCenterService.writePlatformIdentityHeaders(headers, principal))
                .build();
        return chain.filter(exchange.mutate().request(authenticatedRequest).build());
    }

    /**
     * 过滤器顺序。
     *
     * <p>-100 的 GatewayContractFilter 先清理伪造 Header；
     * -97 的本过滤器写入 OIDC/JWT 身份；
     * -95 的开发身份过滤器仅用于本地显式开启时覆盖/模拟；
     * -90 的 GatewayAuthorizationFilter 消费最终身份并调用 permission-admin。</p>
     */
    @Override
    public int getOrder() {
        return -97;
    }
}
