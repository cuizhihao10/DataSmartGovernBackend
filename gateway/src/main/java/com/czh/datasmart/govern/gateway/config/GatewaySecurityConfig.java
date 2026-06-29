package com.czh.datasmart.govern.gateway.config;

import com.czh.datasmart.govern.gateway.authentication.GatewayJwtAudienceValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - GatewaySecurityConfig.java
 * @Version:1.0.0
 *
 * 网关安全配置。
 *
 * <p>本配置现在按生产主流方式接入 OIDC/JWT Resource Server：
 * 1. Keycloak、企业 IdP 或云 IAM 负责登录、MFA、SSO、token 签发和刷新；
 * 2. Spring Security 在 gateway 校验 Bearer JWT 的签名、issuer、过期时间等协议级事实；
 * 3. GatewayOidcAuthenticationContextFilter 把已验证 JWT 的 claim 映射为 X-DataSmart-*；
 * 4. GatewayAuthorizationFilter 再调用 permission-admin 做 RBAC、数据范围和审批语义判定。</p>
 *
 * <p>这样认证与授权不会混在一起：认证回答“你是谁”，授权回答“你能不能做这件事”。</p>
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class GatewaySecurityConfig {

    private final GatewayAuthenticationCenterProperties authenticationCenterProperties;

    /**
     * Gateway WebFlux 安全过滤链。
     *
     * <p>公开端点只保留健康检查、网关契约和认证能力说明。
     * `/auth/session` 与所有 `/api/**` 默认需要 JWT 认证；如果本地开发临时关闭 OIDC，则退回显式 permitAll，
     * 但生产环境应保持 OIDC 开启，并在配置中心中把 authorization.fail-open-on-error 也切为 false。</p>
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        ServerHttpSecurity security = http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);

        boolean oidcEnabled = authenticationCenterProperties.isEnabled()
                && authenticationCenterProperties.getOidc().isEnabled();
        security.authorizeExchange(exchange -> {
            exchange.pathMatchers(
                    "/gateway/contracts/**",
                    "/actuator/**",
                    "/auth/capabilities",
                    "/api/auth/capabilities"
            ).permitAll();
            if (oidcEnabled) {
                exchange.pathMatchers("/auth/session", "/api/auth/session").authenticated();
                exchange.pathMatchers("/api/**").authenticated();
                exchange.anyExchange().authenticated();
            } else {
                exchange.pathMatchers("/auth/session", "/api/auth/session").permitAll();
                exchange.pathMatchers("/api/**").permitAll();
                exchange.anyExchange().permitAll();
            }
        });

        if (oidcEnabled) {
            security.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        }
        return security.build();
    }

    /**
     * OIDC/JWT 解码器。
     *
     * <p>Spring Security Resource Server 需要一个 Reactive JWT Decoder 来完成 token 校验。
     * 这里显式声明 decoder，而不是完全依赖自动配置，是为了把生产必须关注的校验点写清楚：</p>
     * <p>1. `issuer`：确认 token 来自配置的 Keycloak realm、企业 IdP 或云 IAM；</p>
     * <p>2. `exp/nbf`：确认 token 未过期且当前已经生效；</p>
     * <p>3. `audience`：确认 token 是发给 DataSmart gateway 这个资源服务器，而不是其他系统。</p>
     *
     * <p>注意：decoder 只负责协议级校验，不负责业务角色和数据范围。
     * 业务授权仍然由 GatewayAuthorizationFilter 调 permission-admin 完成。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "datasmart.gateway.authentication-center", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "datasmart.gateway.authentication-center.oidc", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public NimbusReactiveJwtDecoder gatewayJwtDecoder() {
        GatewayAuthenticationCenterProperties.OidcJwtProperties oidc = authenticationCenterProperties.getOidc();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withIssuerLocation(authenticationCenterProperties.getIssuer())
                .build();
        OAuth2TokenValidator<Jwt> issuerAndTimestampValidator =
                JwtValidators.createDefaultWithIssuer(authenticationCenterProperties.getIssuer());
        OAuth2TokenValidator<Jwt> audienceValidator = new GatewayJwtAudienceValidator(
                oidc.isAudienceValidationEnabled(),
                oidc.getRequiredAudiences()
        );
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerAndTimestampValidator,
                audienceValidator
        ));
        return decoder;
    }
}
