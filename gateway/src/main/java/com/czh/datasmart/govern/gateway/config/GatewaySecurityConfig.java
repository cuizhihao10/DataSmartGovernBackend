package com.czh.datasmart.govern.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - GatewaySecurityConfig.java
 * @Version:1.0.0
 *
 * 网关安全配置。
 * 当前阶段项目还没有完整的统一认证中心和 RBAC 体系落地，
 * 因此这里采用“显式声明当前放行策略”的方式，而不是依赖 Spring Security 默认行为。
 *
 * 这样做的价值在于：
 * 1. 当前 API 是否放行是可读、可审查的，而不是隐式默认值。
 * 2. 后续接入 JWT、OAuth2 或 RBAC 时，有明确的收口位置。
 * 3. 学习时可以清楚看见网关为什么要使用 WebFlux Security，而不是普通 Servlet Security。
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    /**
     * 当前最小安全策略：
     * 1. 放行健康检查、契约查看和业务 API，确保现阶段开发联调可用。
     * 2. 禁用 CSRF，因为当前主要是无状态 API 场景。
     * 3. 禁用表单登录和 HTTP Basic，避免默认认证页干扰 API 使用。
     *
     * 未来当认证中心成熟后，可以把 `/api/**` 的放行策略改为认证 + 角色校验。
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/gateway/contracts/**", "/actuator/**").permitAll()
                        .pathMatchers("/api/**").permitAll()
                        .anyExchange().permitAll()
                )
                .build();
    }
}
