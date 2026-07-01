/**
 * @Author : Cui
 * @Date: 2026/07/01 17:45
 * @Description DataSmart Govern Backend - GatewayAgentRuntimeAuthorizationFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationDecisionCache;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationErrorWriter;
import com.czh.datasmart.govern.gateway.authorization.GatewayInternalServiceEndpointGuard;
import com.czh.datasmart.govern.gateway.authorization.GatewayPermissionDecisionRequest;
import com.czh.datasmart.govern.gateway.authorization.GatewayPermissionDecisionResult;
import com.czh.datasmart.govern.gateway.authorization.GatewayServiceAccountDelegationSupport;
import com.czh.datasmart.govern.gateway.authorization.PermissionAdminDecisionClient;
import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import com.czh.datasmart.govern.gateway.monitoring.GatewayAuthorizationMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent Runtime 细分授权语义测试。
 *
 * <p>这个测试类从 {@link GatewayAuthorizationFilterTest} 中拆出，是为了让主测试文件继续保持在
 * 500 行以内，同时把 AI Runtime 的“事件、诊断、指标”这组高风险只读入口集中维护。</p>
 *
 * <p>为什么这些入口要单独测试：
 * 1. `/api/agent/**` 是很宽的通配规则，如果具体规则顺序错误，运行时事件、replay ack、诊断和 metrics
 *    很容易被解释成普通 VIEW；
 * 2. 这些入口展示的是 Agent 执行轨迹、消费游标、运维诊断或 Prometheus 指标，风险等级不同于普通详情页；
 * 3. gateway、permission-admin、Python Runtime 和本地 smoke 必须对同一路径保持一致语义，否则真实 E2E
 *    会出现“路由能通但权限动作不对”的隐蔽问题。</p>
 */
class GatewayAgentRuntimeAuthorizationFilterTest {

    /**
     * Agent 运行时事件查询接口应使用独立的 VIEW_EVENTS 动作。
     *
     * <p>运行时事件不是普通详情页资源：它可能包含模型规划阶段、工具调用结果、审批等待、异常原因、
     * requestId/runId/sessionId 关联链路以及后续的审计证据。若把它退化为普通 VIEW，permission-admin
     * 后续就很难区分“查看一个会话摘要”和“查看完整 Agent 执行轨迹”这两类风险完全不同的行为。</p>
     */
    @Test
    void agentRuntimeEventProjectionQueryShouldUseViewEventsAuthorization() {
        assertAuthorization("/api/agent/runtime-events", "GET", "AUDITOR", "AI_RUNTIME", "VIEW_EVENTS");
    }

    /**
     * runtime event replay ack 是客户端消费位置写入动作，应使用 ACK_EVENTS。
     *
     * <p>虽然该接口不创建业务任务，也不执行工具，但它会改变前端或客户端的事件消费游标。
     * 如果误判为普通 VIEW，后续就无法单独限制“谁可以确认已经消费到某个 replaySequence”。</p>
     */
    @Test
    void agentRuntimeEventReplayAckShouldUseAckEventsAuthorization() {
        assertAuthorization("/api/agent/runtime-events/replay/acks", "POST", "PROJECT_OWNER", "AI_RUNTIME", "ACK_EVENTS");
    }

    /**
     * Agent 运行时事件诊断接口应使用 DIAGNOSE 动作，并且要优先于 runtime-events 通配查询规则命中。
     *
     * <p>诊断接口会展示 consumer 配置、topic/groupId、投影窗口大小、拒绝原因计数和平均处理耗时，
     * 这些属于运维视角信息。若因为规则顺序错误而被 `/api/agent/runtime-events/**` 命中成 VIEW_EVENTS，
     * 普通审计查看权限就可能意外覆盖运维诊断权限，形成商业化权限边界漏洞。</p>
     */
    @Test
    void agentRuntimeEventDiagnosticsShouldUseDiagnoseAuthorization() {
        assertAuthorization("/api/agent/runtime-events/diagnostics", "GET", "OPERATOR", "AI_RUNTIME", "DIAGNOSE");
    }

    /**
     * Skill 可见性投影诊断应使用 DIAGNOSE，而不是被 runtime-events 通配规则解释成 VIEW_EVENTS。
     *
     * <p>该接口展示的是 Java 控制面是否已经收到 Python Runtime 发布的 Skill visibility snapshot、
     * 是否存在 fallback/duplicate/manifest binding 异常。它是运维健康视角，不是普通事件流浏览。
     * 如果误落入 `/api/agent/runtime-events/**`，审计查看权限就会意外覆盖运维诊断权限。</p>
     */
    @Test
    void skillVisibilitySnapshotDiagnosticsShouldUseDiagnoseAuthorization() {
        assertAuthorization(
                "/api/agent/runtime-events/skill-visibility-snapshots/diagnostics",
                "GET",
                "OPERATOR",
                "AI_RUNTIME",
                "DIAGNOSE"
        );
    }

    /**
     * 异步命令 outbox 诊断应使用 DIAGNOSE，而不是普通 VIEW。
     *
     * <p>该接口用于观察 agent-runtime 到 task-management 的异步命令是否积压、失败、进入死信或等待恢复。
     * 它不执行 dispatch、不 requeue、不修改 outbox，但暴露的是运行可靠性状态，应由运维诊断权限控制。</p>
     */
    @Test
    void asyncTaskCommandOutboxDiagnosticsShouldUseDiagnoseAuthorization() {
        assertAuthorization(
                "/api/agent/async-task-commands/outbox/diagnostics",
                "GET",
                "OPERATOR",
                "AI_RUNTIME",
                "DIAGNOSE"
        );
    }

    /**
     * Python Runtime 指标入口应使用 AI_RUNTIME + DIAGNOSE，而不是落入普通 `/api/agent/**` 查看规则。
     *
     * <p>`/api/agent/metrics` 暴露的是 Prometheus 低基数聚合指标，业务含义接近运维诊断：
     * 它可以展示 LangGraph 记忆检索、执行门禁、模型 Provider 健康和 checkpoint 恢复趋势，
     * 但不应该被解释成普通 Agent 会话详情，也不能被未来的工具执行入口复用为副作用动作。
     * 这个测试保护默认授权元数据，确保 gateway、permission-admin 与 smoke 脚本对该入口的语义一致。</p>
     */
    @Test
    void pythonRuntimeMetricsShouldUseDiagnoseAuthorization() {
        assertAuthorization("/api/agent/metrics", "GET", "OPERATOR", "AI_RUNTIME", "DIAGNOSE");
    }

    /**
     * 执行一次完整的 gateway 授权过滤器调用，并断言传给 permission-admin 的资源类型与动作。
     *
     * <p>这里不直接读取 {@link GatewayAuthorizationProperties} 的 route metadata，是为了覆盖真实路径：
     * HTTP 请求进入 gateway filter 后，先解析平台身份 Header，再匹配路由元数据，最后调用 permission-admin。
     * 这样既能保护路径匹配顺序，也能保护 filter 对 actorRole、traceId 等上下文的转发行为。</p>
     */
    private void assertAuthorization(String path,
                                     String method,
                                     String actorRole,
                                     String expectedResourceType,
                                     String expectedAction) {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchangeWithRole(path, method, actorRole);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo(expectedResourceType);
        assertThat(captor.getValue().getAction()).isEqualTo(expectedAction);
        assertThat(captor.getValue().getActorRole()).isEqualTo(actorRole);
    }

    /**
     * 构造强制授权配置。
     *
     * <p>enabled=true 表示过滤器会调用 permission-admin；
     * shadowMode=false 表示拒绝结果会被真实拦截；
     * failOpenOnError=false 是生产环境更保守的安全策略。</p>
     */
    private GatewayAuthorizationProperties forcedAuthorizationProperties() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        properties.setEnabled(true);
        properties.setShadowMode(false);
        properties.setFailOpenOnError(false);
        return properties;
    }

    /**
     * 构造被测过滤器，保持和主授权测试一致的真实组件组合。
     */
    private GatewayAuthorizationFilter filter(GatewayAuthorizationProperties properties,
                                              PermissionAdminDecisionClient decisionClient) {
        return new GatewayAuthorizationFilter(
                properties,
                decisionClient,
                new GatewayAuthorizationDecisionCache(properties),
                new GatewayAuthorizationMetrics(new SimpleMeterRegistry()),
                new GatewayInternalServiceEndpointGuard(properties),
                new GatewayAuthorizationErrorWriter(new ObjectMapper()),
                new GatewayServiceAccountDelegationSupport()
        );
    }

    /**
     * 构造指定角色的 mock 请求。
     */
    private MockServerWebExchange exchangeWithRole(String path, String method, String actorRole) {
        MockServerHttpRequest request = MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
                .header(PlatformContextHeaders.TRACE_ID, "trace-test-001")
                .header(PlatformContextHeaders.TENANT_ID, "10")
                .header(PlatformContextHeaders.ACTOR_ID, "1001")
                .header(PlatformContextHeaders.ACTOR_ROLE, actorRole)
                .build();
        return MockServerWebExchange.from(request);
    }

    /**
     * 构造允许访问的判定结果。
     */
    private GatewayPermissionDecisionResult allowedDecision() {
        GatewayPermissionDecisionResult decision = new GatewayPermissionDecisionResult();
        decision.setAllowed(true);
        decision.setReason("允许访问");
        decision.setMatchedRoutePolicyId(9001L);
        decision.setRouteEffect("ALLOW");
        decision.setDataScopeLevel("PROJECT");
        decision.setDataScopeExpression("project_id in ${authorizedProjectIds}");
        decision.setAuthorizedProjectIds(List.of(101L, 102L));
        decision.setApprovalRequired(false);
        return decision;
    }

    /**
     * 记录是否发生下游转发的测试用 GatewayFilterChain。
     */
    private static class RecordingGatewayFilterChain implements GatewayFilterChain {

        private final AtomicBoolean called = new AtomicBoolean(false);

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.called.set(true);
            return Mono.empty();
        }

        private boolean called() {
            return called.get();
        }
    }
}
