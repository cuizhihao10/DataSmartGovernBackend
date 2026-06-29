/**
 * @Author : Cui
 * @Date: 2026/06/29 23:59
 * @Description DataSmart Govern Backend - GatewayJwtAudienceValidator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authentication;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * JWT audience 校验器。
 *
 * <p>该类解决的是 OIDC 生产接入中非常关键但容易被忽略的问题：token 是否“发给当前资源服务器”。
 * Spring Security 默认会校验签名、过期时间、issuer 等事实，但具体 audience 往往需要业务系统自己声明。
 * 对 DataSmart 来说，gateway 是平台统一入口，所以 access token 的 aud 至少应包含 gateway 对应的资源标识。</p>
 *
 * <p>示例：</p>
 * <p>1. Keycloak realm 为 datasmart，gateway client id 为 datasmart-gateway；</p>
 * <p>2. Keycloak 通过 Audience Mapper 把 datasmart-gateway 写入 access token 的 aud；</p>
 * <p>3. 本校验器读取 aud，如果其中包含 datasmart-gateway，则允许继续进入平台 claim 映射；</p>
 * <p>4. 如果 aud 为空或不匹配，即使 JWT 签名合法，也拒绝请求，避免其他系统 token 被混用。</p>
 */
@RequiredArgsConstructor
public class GatewayJwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

    /**
     * 是否启用 audience 校验。
     *
     * <p>保留开关是为了兼容某些本地学习环境或暂未配置 audience mapper 的 IdP。
     * 生产环境应保持启用，否则会降低 access token 的资源绑定强度。</p>
     */
    private final boolean enabled;

    /**
     * 当前 gateway 接受的资源 audience。
     *
     * <p>允许多个值是为了支持蓝绿迁移、历史 client id、不同企业 IdP 命名习惯或多入口网关。
     * 例如 datasmart-gateway、api://datasmart-gateway、urn:datasmart:gateway 可以并存一段时间。</p>
     */
    private final List<String> requiredAudiences;

    /**
     * 校验 JWT aud claim。
     *
     * <p>输入是 Spring Security 已经解析出来的 Jwt 对象；输出是标准 OAuth2TokenValidatorResult。
     * 失败时只返回低敏错误码和概要描述，不返回 token 内容、完整 claim 或任何用户信息。</p>
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!enabled) {
            return OAuth2TokenValidatorResult.success();
        }
        List<String> normalizedRequiredAudiences = normalizeRequiredAudiences();
        if (normalizedRequiredAudiences.isEmpty()) {
            return failure("DataSmart gateway 已启用 audience 校验，但未配置 requiredAudiences");
        }
        List<String> tokenAudiences = token.getAudience();
        if (tokenAudiences == null || tokenAudiences.isEmpty()) {
            return failure("JWT 缺少 aud，无法证明该 token 是发给 DataSmart gateway 的");
        }
        boolean matched = tokenAudiences.stream()
                .filter(audience -> audience != null && !audience.isBlank())
                .map(String::trim)
                .anyMatch(normalizedRequiredAudiences::contains);
        if (!matched) {
            return failure("JWT aud 与 DataSmart gateway 资源标识不匹配");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private List<String> normalizeRequiredAudiences() {
        if (requiredAudiences == null) {
            return List.of();
        }
        return requiredAudiences.stream()
                .filter(audience -> audience != null && !audience.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private OAuth2TokenValidatorResult failure(String description) {
        OAuth2Error error = new OAuth2Error("invalid_token", description, null);
        return OAuth2TokenValidatorResult.failure(error);
    }
}
