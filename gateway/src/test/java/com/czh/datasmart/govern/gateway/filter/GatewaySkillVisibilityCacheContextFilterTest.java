/**
 * @Author : Cui
 * @Date: 2026/06/05 19:20
 * @Description DataSmart Govern Backend - GatewaySkillVisibilityCacheContextFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 可见性缓存上下文过滤器测试。
 *
 * <p>这些测试保护的是“缓存只能复用控制面准入判断”这一条安全边界：
 * - 非 Agent 规划路径不能被误写缓存 Header；
 * - 命中路径时必须清理调用方伪造的缓存 Header；
 * - gateway 生成的 key 必须对授权项目顺序不敏感；
 * - traceId 这类请求级追踪字段不能影响 key，否则缓存永远无法命中；
 * - 数据范围表达式变化必须影响 key，否则可能跨权限边界复用准入结果。</p>
 */
class GatewaySkillVisibilityCacheContextFilterTest {

    /**
     * 非目标路径应完全绕过缓存上下文写入。
     */
    @Test
    void nonTargetPathShouldBypassCacheContext() {
        GatewaySkillVisibilityCacheContextFilter filter = new GatewaySkillVisibilityCacheContextFilter(properties(true));
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/models/routes", "20,30", "trace-001", "project_id in (20,30)"), chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(
                PlatformContextHeaders.SKILL_VISIBILITY_CACHE_KEY)).isFalse();
    }

    /**
     * 缓存开关关闭时仍要清理伪造 Header。
     */
    @Test
    void disabledCacheShouldClearForgedHeaders() {
        GatewaySkillVisibilityCacheContextFilter filter = new GatewaySkillVisibilityCacheContextFilter(properties(false));
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/agent/plans")
                .header(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_KEY, "forged-cache-key")
                .header(PlatformContextHeaders.TENANT_PLAN_CODE, "PLATINUM")
                .build());
        filter.filter(exchange, chain).block();

        HttpHeaders headers = chain.exchange().getRequest().getHeaders();
        assertThat(headers.containsKey(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_KEY)).isFalse();
        assertThat(headers.containsKey(PlatformContextHeaders.TENANT_PLAN_CODE)).isFalse();
    }

    /**
     * 命中目标路径时应写入 Python Runtime 可消费的低敏缓存上下文。
     */
    @Test
    void enabledCacheShouldWriteTrustedContextHeaders() {
        GatewaySkillVisibilityCacheContextFilter filter = new GatewaySkillVisibilityCacheContextFilter(properties(true));
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/plans", "20,30", "trace-001", "project_id in (20,30)"), chain).block();

        HttpHeaders headers = chain.exchange().getRequest().getHeaders();
        assertThat(headers.getFirst(PlatformContextHeaders.TENANT_PLAN_CODE)).isEqualTo("STANDARD");
        assertThat(headers.getFirst(PlatformContextHeaders.WORKSPACE_RISK_LEVEL)).isEqualTo("NORMAL");
        assertThat(headers.getFirst(PlatformContextHeaders.TOOL_BUDGET_POLICY_VERSION)).isEqualTo("gateway-default-v1");
        assertThat(headers.getFirst(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_VERSION)).isEqualTo("v1");
        assertThat(headers.getFirst(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_SCOPE))
                .isEqualTo("session-ready-skill-admission");
        assertThat(headers.getFirst(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_TTL_SECONDS)).isEqualTo("300");
        assertThat(headers.getFirst(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_KEY)).isNotBlank();
    }

    /**
     * 授权项目集合顺序不同，但表达同一权限边界时，应生成相同 key。
     */
    @Test
    void authorizedProjectOrderShouldNotChangeCacheKey() {
        HttpHeaders first = exchange("/api/agent/plans", "20,30", "trace-001", "project_id in (20,30)")
                .getRequest().getHeaders();
        HttpHeaders second = exchange("/api/agent/plans", "30,20", "trace-001", "project_id in (20,30)")
                .getRequest().getHeaders();

        assertThat(GatewaySkillVisibilityCacheContextFilter.cacheKey(first, "v1"))
                .isEqualTo(GatewaySkillVisibilityCacheContextFilter.cacheKey(second, "v1"));
    }

    /**
     * traceId 是请求追踪事实，不是权限事实；变化后不应影响缓存 key。
     */
    @Test
    void traceIdShouldNotChangeCacheKey() {
        HttpHeaders first = exchange("/api/agent/plans", "20,30", "trace-001", "project_id in (20,30)")
                .getRequest().getHeaders();
        HttpHeaders second = exchange("/api/agent/plans", "20,30", "trace-002", "project_id in (20,30)")
                .getRequest().getHeaders();

        assertThat(GatewaySkillVisibilityCacheContextFilter.cacheKey(first, "v1"))
                .isEqualTo(GatewaySkillVisibilityCacheContextFilter.cacheKey(second, "v1"));
    }

    /**
     * 数据范围表达式代表权限边界，变化后必须导致 key 变化。
     */
    @Test
    void dataScopeExpressionShouldChangeCacheKey() {
        HttpHeaders first = exchange("/api/agent/plans", "20,30", "trace-001", "project_id in (20,30)")
                .getRequest().getHeaders();
        HttpHeaders second = exchange("/api/agent/plans", "20,30", "trace-001", "project_id = 20")
                .getRequest().getHeaders();

        assertThat(GatewaySkillVisibilityCacheContextFilter.cacheKey(first, "v1"))
                .isNotEqualTo(GatewaySkillVisibilityCacheContextFilter.cacheKey(second, "v1"));
    }

    /**
     * 构造过滤器配置。
     */
    private GatewayContextProperties properties(boolean enabled) {
        GatewayContextProperties properties = new GatewayContextProperties();
        properties.getSkillVisibilityCache().setEnabled(enabled);
        return properties;
    }

    /**
     * 构造带平台控制面 Header 的 mock exchange。
     */
    private MockServerWebExchange exchange(String path,
                                           String projectIds,
                                           String traceId,
                                           String dataScopeExpression) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .header(PlatformContextHeaders.SOURCE_SERVICE, "datasmart-govern-gateway")
                .header(PlatformContextHeaders.TRACE_ID, traceId)
                .header(PlatformContextHeaders.TENANT_ID, "10")
                .header(PlatformContextHeaders.ACTOR_ID, "1001")
                .header(PlatformContextHeaders.ACTOR_ROLE, "PROJECT_OWNER")
                .header(PlatformContextHeaders.ACTOR_TYPE, "USER")
                .header(PlatformContextHeaders.WORKSPACE_ID, "workspace-a")
                .header(PlatformContextHeaders.REQUEST_SOURCE, "WEB_UI")
                .header(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT")
                .header(PlatformContextHeaders.DATA_SCOPE_EXPRESSION, dataScopeExpression)
                .header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, projectIds)
                .header(PlatformContextHeaders.APPROVAL_REQUIRED, "false")
                .build());
    }

    /**
     * 记录过滤器是否进入下游链路，以及进入下游时携带的 exchange。
     */
    private static class RecordingGatewayFilterChain implements GatewayFilterChain {

        private final AtomicBoolean called = new AtomicBoolean(false);
        private final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.called.set(true);
            this.exchange.set(exchange);
            return Mono.empty();
        }

        private boolean called() {
            return called.get();
        }

        private ServerWebExchange exchange() {
            return exchange.get();
        }
    }
}
