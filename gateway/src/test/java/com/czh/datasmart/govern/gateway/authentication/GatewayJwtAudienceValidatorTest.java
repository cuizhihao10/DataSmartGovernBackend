/**
 * @Author : Cui
 * @Date: 2026/06/29 23:59
 * @Description DataSmart Govern Backend - GatewayJwtAudienceValidatorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authentication;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway JWT audience 校验器测试。
 *
 * <p>测试重点不是验证 RSA 签名，而是验证 DataSmart 自己增加的资源绑定策略：
 * 只有 aud 指向 gateway 的 access token 才允许进入后续业务身份映射。</p>
 */
class GatewayJwtAudienceValidatorTest {

    @Test
    void matchingAudienceShouldPass() {
        GatewayJwtAudienceValidator validator = new GatewayJwtAudienceValidator(
                true,
                List.of("datasmart-gateway")
        );

        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of("datasmart-gateway")));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void missingAudienceShouldFailWhenValidationEnabled() {
        GatewayJwtAudienceValidator validator = new GatewayJwtAudienceValidator(
                true,
                List.of("datasmart-gateway")
        );

        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of()));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void validationDisabledShouldAllowMigrationWindow() {
        GatewayJwtAudienceValidator validator = new GatewayJwtAudienceValidator(
                false,
                List.of("datasmart-gateway")
        );

        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of("other-service")));

        assertThat(result.hasErrors()).isFalse();
    }

    private Jwt jwt(List<String> audiences) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer("http://localhost:18080/realms/datasmart")
                .subject("subject-001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .audience(audiences)
                .claims(claims -> claims.putAll(Map.of(
                        "datasmart_tenant_id", 1,
                        "datasmart_actor_id", 1001,
                        "datasmart_actor_role", "PROJECT_OWNER"
                )))
                .build();
    }
}
