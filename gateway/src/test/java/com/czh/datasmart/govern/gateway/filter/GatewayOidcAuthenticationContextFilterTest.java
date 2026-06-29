/**
 * @Author : Cui
 * @Date: 2026/06/30 00:10
 * @Description DataSmart Govern Backend - GatewayOidcAuthenticationContextFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.authentication.GatewayAuthenticationAuditEvent;
import com.czh.datasmart.govern.gateway.authentication.GatewayAuthenticationAuditSink;
import com.czh.datasmart.govern.gateway.authentication.GatewayAuthenticationAuditSupport;
import com.czh.datasmart.govern.gateway.authentication.GatewayAuthenticationCenterService;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationErrorWriter;
import com.czh.datasmart.govern.gateway.config.GatewayAuthenticationCenterProperties;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import com.czh.datasmart.govern.gateway.controller.dto.GatewayAuthenticationPrincipalView;
import com.czh.datasmart.govern.gateway.monitoring.GatewayAuthenticationMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OIDC/JWT 身份上下文注入测试。
 *
 * <p>该测试不启动真实 Keycloak，也不伪造 JWT 签名校验。签名校验是 Spring Security Resource Server 的职责；
 * 本测试只验证“已通过 Spring Security 的 JwtAuthenticationToken”进入 gateway 全局过滤器后，是否能稳定转换为
 * DataSmart 平台统一 Header。</p>
 */
class GatewayOidcAuthenticationContextFilterTest {

    /**
     * 已验证 JWT 携带完整 DataSmart claim 时，gateway 应写入下游服务消费的身份上下文 Header。
     */
    @Test
    void verifiedJwtShouldWritePlatformIdentityHeaders() {
        FilterFixture fixture = filterFixture();
        GatewayOidcAuthenticationContextFilter filter = fixture.filter();
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        ServerWebExchange exchange = exchange(jwt(Map.of(
                "datasmart_tenant_id", 10L,
                "datasmart_actor_id", 1001L,
                "datasmart_actor_role", "PROJECT_OWNER",
                "datasmart_actor_type", "USER",
                "datasmart_workspace_id", "workspace-a"
        )));
        Principal principal = exchange.getPrincipal().block();
        assertThat(principal).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(((JwtAuthenticationToken) principal).isAuthenticated()).isTrue();
        GatewayAuthenticationPrincipalView view = authenticationCenterService()
                .currentPrincipal((Authentication) principal, exchange.getRequest().getHeaders());
        assertThat(view.authenticated()).isTrue();
        assertThat(view.actorRole()).isEqualTo("PROJECT_OWNER");

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.TENANT_ID))
                .isEqualTo("10");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.ACTOR_ID))
                .isEqualTo("1001");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.ACTOR_ROLE))
                .isEqualTo("PROJECT_OWNER");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.ACTOR_TYPE))
                .isEqualTo("USER");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.WORKSPACE_ID))
                .isEqualTo("workspace-a");
        assertThat(fixture.auditSink().events()).hasSize(1);
        GatewayAuthenticationAuditEvent auditEvent = fixture.auditSink().events().getFirst();
        assertThat(auditEvent.outcome()).isEqualTo("RESOLVED");
        assertThat(auditEvent.authenticationType()).isEqualTo("OIDC_JWT");
        assertThat(auditEvent.tenantId()).isEqualTo(10L);
        assertThat(auditEvent.actorId()).isEqualTo(1001L);
        assertThat(auditEvent.actorRole()).isEqualTo("PROJECT_OWNER");
        assertThat(auditEvent.requestPath()).isEqualTo("/api/task/tasks");
        assertThat(auditEvent.payloadPolicy()).contains("NO_TOKEN");
        assertThat(fixture.registry().find("datasmart.gateway.authentication.outcome")
                .tag("outcome", "RESOLVED")
                .tag("auth_type", "OIDC_JWT")
                .tag("actor_type", "USER")
                .tag("primary_issue", "NONE")
                .counter().count()).isEqualTo(1.0d);
    }

    /**
     * Keycloak 常见 `realm_access.roles` 结构也应能映射到平台角色。
     */
    @Test
    void keycloakRealmRolesShouldBeMappedToPlatformRole() {
        FilterFixture fixture = filterFixture();
        GatewayOidcAuthenticationContextFilter filter = fixture.filter();
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        ServerWebExchange exchange = exchange(jwt(Map.of(
                "datasmart_tenant_id", "20",
                "datasmart_actor_id", "2001",
                "realm_access", Map.of("roles", List.of("DATASMART_OPERATOR"))
        )));

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.ACTOR_ROLE))
                .isEqualTo("OPERATOR");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.WORKSPACE_ID))
                .isEqualTo("default");
        assertThat(fixture.auditSink().events())
                .extracting(GatewayAuthenticationAuditEvent::outcome)
                .containsExactly("RESOLVED");
    }

    /**
     * JWT 合法但缺少业务必需 claim 时必须失败关闭。
     *
     * <p>这是生产安全底线：不能因为 token 通过签名校验，就在缺少 tenantId、actorId 或 role 的情况下继续进入业务路由。
     * 否则请求会退化成匿名身份或默认租户，破坏多租户边界和审计可追溯性。</p>
     */
    @Test
    void missingRequiredBusinessClaimsShouldFailClosed() {
        FilterFixture fixture = filterFixture();
        GatewayOidcAuthenticationContextFilter filter = fixture.filter();
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        ServerWebExchange exchange = exchange(jwt(Map.of(
                "datasmart_tenant_id", 10L,
                "datasmart_actor_id", 1001L
        )));

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        assertThat(fixture.auditSink().events()).hasSize(1);
        GatewayAuthenticationAuditEvent auditEvent = fixture.auditSink().events().getFirst();
        assertThat(auditEvent.outcome()).isEqualTo("REJECTED");
        assertThat(auditEvent.issueCodes())
                .contains("OIDC_JWT_CONTEXT_INCOMPLETE", "OIDC_JWT_FAIL_CLOSED_MISSING_REQUIRED_CLAIMS");
        assertThat(fixture.registry().find("datasmart.gateway.authentication.outcome")
                .tag("outcome", "REJECTED")
                .tag("auth_type", "OIDC_JWT")
                .tag("actor_type", "USER")
                .tag("primary_issue", "OIDC_JWT_CONTEXT_INCOMPLETE")
                .counter().count()).isEqualTo(1.0d);
    }

    private FilterFixture filterFixture() {
        GatewayAuthenticationCenterProperties properties = new GatewayAuthenticationCenterProperties();
        GatewayAuthenticationCenterService authenticationCenterService = authenticationCenterService(properties);
        CapturingGatewayAuthenticationAuditSink auditSink = new CapturingGatewayAuthenticationAuditSink();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthenticationAuditSupport authenticationAuditSupport = new GatewayAuthenticationAuditSupport(
                List.of(auditSink),
                new GatewayAuthenticationMetrics(registry)
        );
        GatewayOidcAuthenticationContextFilter filter = new GatewayOidcAuthenticationContextFilter(
                properties,
                authenticationCenterService,
                authenticationAuditSupport,
                new GatewayAuthorizationErrorWriter(new ObjectMapper())
        );
        return new FilterFixture(filter, auditSink, registry);
    }

    private GatewayAuthenticationCenterService authenticationCenterService() {
        return authenticationCenterService(new GatewayAuthenticationCenterProperties());
    }

    private GatewayAuthenticationCenterService authenticationCenterService(GatewayAuthenticationCenterProperties properties) {
        return new GatewayAuthenticationCenterService(properties, new GatewayContextProperties());
    }

    private ServerWebExchange exchange(Jwt jwt) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of());
        authentication.setAuthenticated(true);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/task/tasks")
                        .header(PlatformContextHeaders.TRACE_ID, "trace-oidc-test")
                        .build()
        );
        return new ServerWebExchangeDecorator(exchange) {

            @Override
            @SuppressWarnings("unchecked")
            public <T extends Principal> Mono<T> getPrincipal() {
                return Mono.just((T) authentication);
            }
        };
    }

    private Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer("http://localhost:18080/realms/datasmart")
                .subject("subject-001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        claims.forEach(builder::claim);
        return builder.build();
    }

    /**
     * 过滤器测试夹具。
     *
     * <p>把 filter、审计 sink 和 registry 放在一起，方便每个测试既验证请求是否继续向下游流转，
     * 也验证认证审计事件和指标是否同步产生。</p>
     */
    private record FilterFixture(GatewayOidcAuthenticationContextFilter filter,
                                 CapturingGatewayAuthenticationAuditSink auditSink,
                                 SimpleMeterRegistry registry) {
    }

    /**
     * 测试用内存审计 sink。
     *
     * <p>生产代码使用日志 sink，单元测试不依赖日志断言，而是通过这个捕获器确认事件内容。
     * 捕获器只在测试中保存低敏事件，不会接触 token 或完整 claim。</p>
     */
    private static class CapturingGatewayAuthenticationAuditSink implements GatewayAuthenticationAuditSink {

        private final List<GatewayAuthenticationAuditEvent> events = new ArrayList<>();

        @Override
        public void emit(GatewayAuthenticationAuditEvent event) {
            events.add(event);
        }

        private List<GatewayAuthenticationAuditEvent> events() {
            return events;
        }
    }

    private static class RecordingGatewayFilterChain implements GatewayFilterChain {

        private boolean called;
        private ServerWebExchange exchange;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.called = true;
            this.exchange = exchange;
            return Mono.empty();
        }

        private boolean called() {
            return called;
        }

        private ServerWebExchange exchange() {
            return exchange;
        }
    }
}
