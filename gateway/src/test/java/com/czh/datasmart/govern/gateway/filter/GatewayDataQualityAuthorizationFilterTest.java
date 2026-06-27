/**
 * @Author : Cui
 * @Date: 2026/06/28 10:30
 * @Description DataSmart Govern Backend - GatewayDataQualityAuthorizationFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationDecisionCache;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationErrorWriter;
import com.czh.datasmart.govern.gateway.authorization.GatewayInternalServiceEndpointGuard;
import com.czh.datasmart.govern.gateway.authorization.GatewayPermissionDecisionRequest;
import com.czh.datasmart.govern.gateway.authorization.GatewayPermissionDecisionResult;
import com.czh.datasmart.govern.gateway.authorization.PermissionAdminDecisionClient;
import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import com.czh.datasmart.govern.gateway.monitoring.GatewayAuthorizationMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * data-quality 路由授权语义测试。
 *
 * <p>这个测试类专门保护 gateway 的“路径 -> 业务资源类型 + 业务动作”翻译逻辑。
 * data-quality 早期只有 `/api/quality/** -> QUALITY_RULE` 的粗粒度映射；随着治理总览、质量报告、
 * 异常工作台、执行器诊断和 worker 回调陆续落地，继续使用单一资源类型会让 permission-admin 无法区分：
 * 看低敏态势、修改规则、触发执行、伪造机器回调这几类风险完全不同的行为。
 *
 * <p>测试不启动真实 Spring Cloud Gateway，也不调用真实 permission-admin。它通过 mock 权限客户端捕获
 * {@link GatewayPermissionDecisionRequest}，验证 gateway 发送给权限中心的契约是否符合产品语义。
 * 这样后续无论 route-metadata 配置、默认元数据还是过滤器内部匹配逻辑怎么重构，都不容易把质量权限边界退化回 demo 级别。</p>
 */
class GatewayDataQualityAuthorizationFilterTest {

    /**
     * 治理总览是低敏聚合视图，应使用 QUALITY_GOVERNANCE + VIEW。
     *
     * <p>如果这里被误映射为 QUALITY_RULE，普通用户想看质量态势时就必须依赖规则管理资源权限；
     * 如果被误映射为 QUALITY_EXECUTION，又会和执行器诊断、运行触发混在一起。独立的治理资源类型
     * 能让 permission-admin 给普通用户、项目负责人、审计员、运营人员配置不同数据范围。</p>
     */
    @Test
    void governanceOverviewShouldUseQualityGovernanceViewAuthorization() {
        GatewayPermissionDecisionRequest request = capturedDecisionRequest(
                "/api/quality/quality-rules/governance/overview",
                "GET",
                "ORDINARY_USER"
        );

        assertThat(request.getResourceType()).isEqualTo("QUALITY_GOVERNANCE");
        assertThat(request.getAction()).isEqualTo("VIEW");
    }

    /**
     * 异常聚合入口应使用 QUALITY_ANOMALY + VIEW。
     *
     * <p>异常工作台比报告摘要更接近具体业务问题定位，后续还可能扩展样本查看、异常导出或清洗任务创建。
     * 因此它不能长期复用 QUALITY_REPORT，也不能落回 QUALITY_RULE。</p>
     */
    @Test
    void anomalyAggregationShouldUseQualityAnomalyViewAuthorization() {
        GatewayPermissionDecisionRequest request = capturedDecisionRequest(
                "/api/quality/quality-rules/anomalies/aggregation",
                "GET",
                "AUDITOR"
        );

        assertThat(request.getResourceType()).isEqualTo("QUALITY_ANOMALY");
        assertThat(request.getAction()).isEqualTo("VIEW");
    }

    /**
     * 执行器诊断入口应使用 QUALITY_EXECUTION + DIAGNOSE。
     *
     * <p>诊断接口通常暴露 worker 健康状态、积压数量、最近执行耗时等运维信息，风险高于普通报告查看。
     * 单独的 DIAGNOSE 动作可以让运营人员具备排障能力，同时不把诊断能力默认授予普通业务用户。</p>
     */
    @Test
    void executorDiagnosticsShouldUseQualityExecutionDiagnoseAuthorization() {
        GatewayPermissionDecisionRequest request = capturedDecisionRequest(
                "/api/quality/quality-rules/executor/diagnostics",
                "GET",
                "OPERATOR"
        );

        assertThat(request.getResourceType()).isEqualTo("QUALITY_EXECUTION");
        assertThat(request.getAction()).isEqualTo("DIAGNOSE");
    }

    /**
     * 手动检测入口应使用 QUALITY_EXECUTION + RUN。
     *
     * <p>POST /run-check 不是创建一条规则，而是触发一次检测执行。把它翻译成 RUN 后，
     * permission-admin 可以把“能创建规则”和“能消耗执行资源、生成报告和异常事实”拆成不同权限。</p>
     */
    @Test
    void manualRuleRunShouldUseQualityExecutionRunAuthorization() {
        GatewayPermissionDecisionRequest request = capturedDecisionRequest(
                "/api/quality/quality-rules/1001/run-check",
                "POST",
                "PROJECT_OWNER"
        );

        assertThat(request.getResourceType()).isEqualTo("QUALITY_EXECUTION");
        assertThat(request.getAction()).isEqualTo("RUN");
    }

    /**
     * worker 成功回调入口应使用 QUALITY_EXECUTION + CALLBACK。
     *
     * <p>CALLBACK 是机器协议语义，正常情况下只允许 SERVICE_ACCOUNT 调用。即使平台管理员也不建议
     * 直接伪造回调，因为回调会改变执行事实和审计链。gateway 先把动作翻译准确，permission-admin
     * 才能用显式 DENY/ALLOW 策略保护这条边界。</p>
     */
    @Test
    void serviceAccountExecutionCallbackShouldUseQualityExecutionCallbackAuthorization() {
        GatewayPermissionDecisionRequest request = capturedDecisionRequest(
                "/api/quality/quality-rules/executor/executions/9001/succeed",
                "POST",
                "SERVICE_ACCOUNT"
        );

        assertThat(request.getResourceType()).isEqualTo("QUALITY_EXECUTION");
        assertThat(request.getAction()).isEqualTo("CALLBACK");
        assertThat(request.getActorRole()).isEqualTo("SERVICE_ACCOUNT");
    }

    /**
     * 捕获一次 gateway 发送给 permission-admin 的权限判定请求。
     *
     * <p>这里刻意复用真实 {@link GatewayAuthorizationFilter}、真实本地缓存组件和真实内部端点保护组件，
     * 只 mock 远程权限客户端。这样测试覆盖的是 gateway 过滤器内部的完整构造链路，而不是单独测试某个私有方法。</p>
     */
    private GatewayPermissionDecisionRequest capturedDecisionRequest(String path, String method, String actorRole) {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        when(decisionClient.evaluate(any(), eq("trace-quality-auth")))
                .thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange(path, method, actorRole), chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-quality-auth"));
        assertThat(chain.called()).isTrue();
        return captor.getValue();
    }

    /**
     * 构造强制授权模式配置。
     *
     * <p>enabled=true 确保过滤器真的调用 permission-admin；shadowMode=false 确保测试路径和生产强制模式一致；
     * failOpenOnError=false 避免权限中心异常时悄悄放行，从而掩盖测试问题。</p>
     */
    private GatewayAuthorizationProperties forcedAuthorizationProperties() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        properties.setEnabled(true);
        properties.setShadowMode(false);
        properties.setFailOpenOnError(false);
        return properties;
    }

    /**
     * 构造被测过滤器。
     */
    private GatewayAuthorizationFilter filter(GatewayAuthorizationProperties properties,
                                              PermissionAdminDecisionClient decisionClient) {
        return new GatewayAuthorizationFilter(
                properties,
                decisionClient,
                new GatewayAuthorizationDecisionCache(properties),
                new GatewayAuthorizationMetrics(new SimpleMeterRegistry()),
                new GatewayInternalServiceEndpointGuard(properties),
                new GatewayAuthorizationErrorWriter(new ObjectMapper())
        );
    }

    /**
     * 构造携带平台上下文 Header 的 mock 请求。
     */
    private MockServerWebExchange exchange(String path, String method, String actorRole) {
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.valueOf(method), path)
                .header(PlatformContextHeaders.TRACE_ID, "trace-quality-auth")
                .header(PlatformContextHeaders.TENANT_ID, "10")
                .header(PlatformContextHeaders.ACTOR_ID, "1001")
                .header(PlatformContextHeaders.ACTOR_ROLE, actorRole)
                .build();
        return MockServerWebExchange.from(request);
    }

    /**
     * 构造允许访问的权限中心响应。
     */
    private GatewayPermissionDecisionResult allowedDecision() {
        GatewayPermissionDecisionResult decision = new GatewayPermissionDecisionResult();
        decision.setAllowed(true);
        decision.setReason("允许访问数据质量权限语义测试入口");
        decision.setMatchedRoutePolicyId(62001L);
        decision.setRouteEffect("ALLOW");
        decision.setDataScopeLevel("PROJECT");
        decision.setDataScopeExpression("project_id IN ${actorProjectIds}");
        decision.setAuthorizedProjectIds(List.of(101L, 102L));
        decision.setApprovalRequired(false);
        return decision;
    }

    /**
     * 记录 gateway 是否继续向下游转发的测试链路。
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
