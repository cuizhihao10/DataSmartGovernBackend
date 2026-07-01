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
import org.springframework.util.StringUtils;

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
        NimbusReactiveJwtDecoder decoder = buildJwtDecoder(oidc);
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

    /**
     * 根据部署环境选择 JWT 公钥发现方式。
     *
     * <p>多数生产环境里，`issuer` 本身就是 gateway 可以直接访问的企业 IdP 地址，例如
     * `https://idp.example.com/realms/datasmart`。这时使用 `withIssuerLocation(...)` 最清晰：
     * Spring Security 会读取 `/.well-known/openid-configuration`，再从其中的 `jwks_uri` 拉取公钥。</p>
     *
     * <p>本地全容器 E2E 会遇到一个常见但容易误判的问题：宿主机通过
     * `http://localhost:18080` 访问 Keycloak，token 的 `iss` 因而也是 localhost；可是 gateway
     * 运行在容器内，容器里的 localhost 是 gateway 自己。此时如果继续用 issuer discovery，JWT 解码器会访问
     * `gateway 容器自己的 18080` 并失败。</p>
     *
     * <p>因此这里支持显式 `jwkSetUri`：当它存在时，decoder 直接从该地址取公钥；但后续 validator 仍然按
     * `authenticationCenterProperties.issuer` 校验 token 的 issuer。也就是说，网络寻址可以走容器 DNS，
     * 安全语义仍然锚定真实 issuer，不会因为本地部署便利而退化成“只验签不验签发者”。</p>
     */
    private NimbusReactiveJwtDecoder buildJwtDecoder(GatewayAuthenticationCenterProperties.OidcJwtProperties oidc) {
        if (StringUtils.hasText(oidc.getJwkSetUri())) {
            return NimbusReactiveJwtDecoder
                    .withJwkSetUri(oidc.getJwkSetUri())
                    .build();
        }
        return NimbusReactiveJwtDecoder
                .withIssuerLocation(authenticationCenterProperties.getIssuer())
                .build();
    }
}
