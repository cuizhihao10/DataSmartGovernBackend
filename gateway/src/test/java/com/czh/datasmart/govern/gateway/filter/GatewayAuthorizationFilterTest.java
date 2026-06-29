/**
 * @Author : Cui
 * @Date: 2026/05/23 17:31
 * @Description DataSmart Govern Backend - GatewayAuthorizationFilterTest.java
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
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 网关授权过滤器测试。
 *
 * <p>本测试关注 gateway 作为“平台入口安全阀”时必须稳定具备的几类行为：
 * 1. permission-admin 允许访问时，gateway 必须把数据范围 Header 继续透传给下游服务；
 * 2. permission-admin 拒绝访问时，强制模式必须返回 403，不能把请求继续转发；
 * 3. 影子模式下即使判定拒绝，也只能记录观察结果并放行，便于灰度验证权限矩阵；
 * 4. permission-admin 超时、不可用或返回异常时，fail-open 与 fail-closed 必须表现明确。
 *
 * <p>这些场景不是“单元测试形式主义”，而是商业化网关上线前最容易出事故的策略边界。
 * 如果没有测试固定行为，后续重构过滤器、缓存、熔断或权限客户端时，很容易把生产环境的安全开关语义改坏。
 */
class GatewayAuthorizationFilterTest {

    /**
     * 权限中心允许访问时，网关应放行请求并透传数据范围上下文。
     *
     * <p>这验证了 permission-admin -> gateway -> business-service 的核心授权闭环：
     * permission-admin 不只返回“是否允许”，还会返回 PROJECT/TENANT/SELF 等数据范围信息。
     * gateway 不解析这些业务范围，但必须把它们作为可信 Header 写给下游服务，
     * 后续 task-management、data-quality、data-sync 才能把数据范围真正落到 SQL 查询或业务动作里。
     */
    @Test
    void allowedDecisionPassesRequestAndPropagatesDataScopeHeaders() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchange("/api/task/tasks", "GET");
        GatewayPermissionDecisionResult allowedDecision = allowedDecision();
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision));

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.DATA_SCOPE_LEVEL))
                .isEqualTo("PROJECT");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.DATA_SCOPE_EXPRESSION))
                .isEqualTo("project_id in ${authorizedProjectIds}");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS))
                .isEqualTo("101,102");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(PlatformContextHeaders.APPROVAL_REQUIRED))
                .isEqualTo("false");
    }

    /**
     * 强制模式下，如果权限中心明确拒绝，网关必须直接返回 403。
     *
     * <p>这是生产环境最关键的安全行为：只要策略已经判定拒绝，后端业务服务就不应该再收到请求。
     * 如果这里错误放行，即使业务服务内部还有部分权限判断，也会破坏“入口统一授权”的设计目标，
     * 并增加每个业务模块重复实现安全逻辑的压力。
     */
    @Test
    void deniedDecisionReturnsForbiddenWhenShadowModeDisabled() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchange("/api/quality/rules", "POST");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(deniedDecision("角色无权创建质量规则")));

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
    }

    /**
     * 影子模式下，如果权限中心拒绝，网关仍然放行请求。
     *
     * <p>影子模式用于真实流量灰度：平台可以先观察权限矩阵会拒绝哪些请求，
     * 但不立刻影响用户业务操作。这个能力对企业产品很重要，因为权限策略上线通常需要迁移期，
     * 直接从“无网关强制授权”切到“所有请求严格拦截”，容易误伤存量客户流程。
     */
    @Test
    void deniedDecisionStillPassesRequestWhenShadowModeEnabled() {
        GatewayAuthorizationProperties properties = shadowAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchange("/api/datasource/configs", "DELETE");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(deniedDecision("影子模式观察拒绝")));

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    /**
     * fail-open 开启时，权限中心异常不应阻断请求。
     *
     * <p>该策略适合本地开发、迁移灰度或权限中心尚未完成高可用部署的阶段。
     * 它牺牲了一部分安全强度，换取业务链路连续性，因此生产环境必须谨慎启用。
     */
    @Test
    void permissionAdminErrorPassesRequestWhenFailOpenEnabled() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        properties.setFailOpenOnError(true);
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchange("/api/sync/sync-tasks", "GET");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        when(decisionClient.evaluate(any(), eq("trace-test-001")))
                .thenReturn(Mono.error(new IllegalStateException("permission-admin timeout")));

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    /**
     * fail-closed 开启时，权限中心异常必须返回 403。
     *
     * <p>该策略是生产环境更安全的默认方向：当权限中心不可用时，宁可临时拒绝访问，
     * 也不能让高风险接口在无授权判定的情况下继续执行。
     */
    @Test
    void permissionAdminErrorReturnsForbiddenWhenFailOpenDisabled() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        properties.setFailOpenOnError(false);
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchange("/api/permission/route-policies", "PUT");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        when(decisionClient.evaluate(any(), eq("trace-test-001")))
                .thenReturn(Mono.error(new IllegalStateException("permission-admin malformed envelope")));

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
    }

    /**
     * 公开路径不应调用权限中心。
     *
     * <p>健康检查、网关契约说明等公共路径必须在权限中心故障时仍然可访问，
     * 否则故障排查入口也会被权限中心故障一起拖垮。
     */
    @Test
    void publicPathBypassesPermissionAdminDecision() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchange("/actuator/health", "GET");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        verify(decisionClient, never()).evaluate(any(), any());
    }

    /**
     * 服务账号调用 AgentPlan 接入口时，应通过内部端点保护，并按 AI_RUNTIME/INGEST_PLAN 语义进入权限中心。
     *
     * <p>这条测试保护 3.64 后新增的跨运行时入口：
     * Python Runtime 是服务账号，它可以把 AgentPlan 交给 Java 控制面；
     * 但 gateway 不能把该请求误解释成普通 CREATE，也不能把它归到 SYSTEM_SETTING。
     * 正确语义应该是 `AI_RUNTIME + INGEST_PLAN`，这样 permission-admin 审计才能清楚记录“谁接入了一份 Agent 计划”。
     */
    @Test
    void serviceAccountPlanIngestionShouldUseAiRuntimeIngestPlanAuthorization() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = serviceAccountExchange("/api/agent/plan-ingestions", "POST");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("INGEST_PLAN");
        assertThat(captor.getValue().getActorRole()).isEqualTo("SERVICE_ACCOUNT");
    }

    /**
     * 服务账号请求应把 actorType、workspace 和委托责任链传给 permission-admin。
     *
     * <p>这条测试保护 OIDC/Keycloak 接入后的关键商业化边界：
     * 1. Keycloak 可以签发 `actorType=SERVICE_ACCOUNT` 的机器身份；
     * 2. gateway 不应只把它压缩成一个普通 `actorRole=SERVICE_ACCOUNT`；
     * 3. permission-admin 的判定与审计应能看到服务账号自身、被代表主体、委托类型和策略版本；
     * 4. 后续 data-sync worker、agent-runtime 工具执行、task-management 补偿任务都可以复用同一责任链契约。</p>
     */
    @Test
    void serviceAccountAuthorizationRequestShouldCarryDelegationContext() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = serviceAccountExchange(
                "/api/agent/sessions/session-1/runs/run-1/tool-executions/dag-selected-node-outbox/enqueue",
                "POST");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        GatewayPermissionDecisionRequest request = captor.getValue();
        assertThat(chain.called()).isTrue();
        assertThat(request.getActorRole()).isEqualTo("SERVICE_ACCOUNT");
        assertThat(request.getActorType()).isEqualTo("SERVICE_ACCOUNT");
        assertThat(request.getWorkspaceId()).isEqualTo("system-sync");
        assertThat(request.getRequestSource()).isEqualTo("AGENT_TOOL_CALL");
        assertThat(request.getServiceAccountActorId()).isEqualTo(9101L);
        assertThat(request.getServiceAccountCode()).isEqualTo("datasmart-sync-service");
        assertThat(request.getRepresentedActorId()).isEqualTo("1001");
        assertThat(request.getDelegationType()).isEqualTo("SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR");
        assertThat(request.getDelegationReason()).isEqualTo("AGENT_CONFIRMED_TOOL_OUTBOX");
        assertThat(request.getRequestedPolicyVersion()).isEqualTo("route-policy:860");
    }

    /**
     * agent-runtime 内部 worker/API 入口应使用独立的内部执行动作进入 permission-admin。
     *
     * <p>这条测试把本地守卫和授权语义串起来验证：网关先确认它确实是服务账号主体，
     * 再把 `/api/internal/agent-runtime/**` 翻译为 `AI_RUNTIME + EXECUTE_INTERNAL`。
     * 这样 permission-admin 可以给机器协议配置不同于普通用户会话 API 的策略、审计和告警。
     */
    @Test
    void apiInternalAgentRuntimeEndpointShouldUseExecuteInternalAuthorization() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = serviceAccountExchange(
                "/api/internal/agent-runtime/sessions/session-1/runs/run-1/tool-executions/command-worker-receipts",
                "POST");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("EXECUTE_INTERNAL");
        assertThat(captor.getValue().getActorType()).isEqualTo("SERVICE_ACCOUNT");
    }

    /**
     * Agent 实时事件 WebSocket 握手应按订阅动作进入权限中心。
     *
     * <p>WebSocket 握手在 HTTP 层表现为 GET 请求，如果只沿用通用 REST 语义，gateway 会把它解释成 VIEW。
     * 但实时事件入口的真实业务含义不是“查看一个静态资源”，而是“建立一条持续接收 run/session 事件的长连接”。
     * 这类连接会持续占用网关、Python Runtime 和事件 outbox 资源，也会暴露 Agent 推理过程、工具调用状态和审批等待信息，
     * 因此需要独立的 SUBSCRIBE 语义，方便 permission-admin 后续对普通用户、审计员、项目负责人、运维人员设置不同策略。
     */
    @Test
    void agentEventWebSocketHandshakeShouldUseAiRuntimeSubscribeAuthorization() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchangeWithRole("/api/agent/events/ws", "GET", "PROJECT_OWNER");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("SUBSCRIBE");
        assertThat(captor.getValue().getActorRole()).isEqualTo("PROJECT_OWNER");
    }

    /**
     * Agent 运行时事件查询接口应使用独立的 VIEW_EVENTS 动作。
     *
     * <p>运行时事件不是普通详情页资源：它可能包含模型规划阶段、工具调用结果、审批等待、异常原因、
     * requestId/runId/sessionId 关联链路以及后续的审计证据。若把它退化为普通 VIEW，permission-admin
     * 后续就很难区分“查看一个会话摘要”和“查看完整 Agent 执行轨迹”这两类风险完全不同的行为。</p>
     */
    @Test
    void agentRuntimeEventProjectionQueryShouldUseViewEventsAuthorization() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchangeWithRole("/api/agent/runtime-events", "GET", "AUDITOR");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("VIEW_EVENTS");
        assertThat(captor.getValue().getActorRole()).isEqualTo("AUDITOR");
    }

    @Test
    void agentRuntimeEventReplayAckShouldUseAckEventsAuthorization() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchangeWithRole("/api/agent/runtime-events/replay/acks", "POST", "PROJECT_OWNER");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("ACK_EVENTS");
        assertThat(captor.getValue().getActorRole()).isEqualTo("PROJECT_OWNER");
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
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchangeWithRole("/api/agent/runtime-events/diagnostics", "GET", "OPERATOR");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("DIAGNOSE");
        assertThat(captor.getValue().getActorRole()).isEqualTo("OPERATOR");
    }

    /**
     * 非服务账号调用 AgentPlan 接入口时，应在 gateway 本地保护阶段被拒绝。
     *
     * <p>这里故意不让请求进入 permission-admin，是为了更早阻断明显违反内部协议的调用。
     * 普通用户、项目负责人、运营人员都应通过会话/智能网关入口提交目标，而不是直接伪造 Python AgentPlan。
     */
    @Test
    void nonServiceAccountPlanIngestionShouldBeRejectedBeforePermissionAdmin() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        MockServerWebExchange exchange = exchangeWithRole("/api/agent/plan-ingestions", "POST", "PROJECT_OWNER");
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        verify(decisionClient, never()).evaluate(any(), any());
    }

    /**
     * AgentPlan 内部接入口应具备本地限流保护。
     *
     * <p>模型重试风暴、Python Runtime bug 或服务账号泄露都会首先表现为某个 actor 高频打同一个内部入口。
     * 当前实现是单网关实例固定窗口限流，虽然还不是最终分布式限流，但已经能保护本地 agent-runtime 不被瞬时打满。
     */
    @Test
    void planIngestionShouldReturnTooManyRequestsWhenRateLimitExceeded() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        properties.getInternalServiceEndpoints().getFirst().setMaxRequestsPerMinute(1);
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(properties, decisionClient);
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        RecordingGatewayFilterChain firstChain = new RecordingGatewayFilterChain();
        filter.filter(serviceAccountExchange("/api/agent/plan-ingestions", "POST"), firstChain).block();
        MockServerWebExchange secondExchange = serviceAccountExchange("/api/agent/plan-ingestions", "POST");
        RecordingGatewayFilterChain secondChain = new RecordingGatewayFilterChain();
        filter.filter(secondExchange, secondChain).block();

        assertThat(firstChain.called()).isTrue();
        assertThat(secondChain.called()).isFalse();
        assertThat(secondExchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(secondExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    /**
     * 构造强制授权配置。
     *
     * <p>enabled=true 表示过滤器会调用 permission-admin；
     * shadowMode=false 表示拒绝结果会被真实拦截；
     * failOpenOnError=false 是生产环境更保守的安全策略。
     */
    private GatewayAuthorizationProperties forcedAuthorizationProperties() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        properties.setEnabled(true);
        properties.setShadowMode(false);
        properties.setFailOpenOnError(false);
        return properties;
    }

    /**
     * 构造影子授权配置。
     */
    private GatewayAuthorizationProperties shadowAuthorizationProperties() {
        GatewayAuthorizationProperties properties = forcedAuthorizationProperties();
        properties.setShadowMode(true);
        return properties;
    }

    /**
     * 构造被测过滤器。
     *
     * <p>这里使用真实 GatewayAuthorizationDecisionCache，是为了覆盖过滤器与缓存组件之间的真实协作入口。
     * 测试默认不启用缓存，因此每个用例都能稳定命中 mock 的 permission-admin 客户端。
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
     * 构造带平台上下文 Header 的 mock 请求。
     */
    private MockServerWebExchange exchange(String path, String method) {
        return exchangeWithRole(path, method, "PROJECT_OWNER");
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
     * 构造带服务账号和委托责任链 Header 的 mock 请求。
     */
    private MockServerWebExchange serviceAccountExchange(String path, String method) {
        MockServerHttpRequest request = MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
                .header(PlatformContextHeaders.TRACE_ID, "trace-test-001")
                .header(PlatformContextHeaders.TENANT_ID, "10")
                .header(PlatformContextHeaders.ACTOR_ID, "9101")
                .header(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT")
                .header(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT")
                .header(PlatformContextHeaders.WORKSPACE_ID, "system-sync")
                .header(PlatformContextHeaders.REQUEST_SOURCE, "AGENT_TOOL_CALL")
                .header(PlatformContextHeaders.SERVICE_ACCOUNT_ACTOR_ID, "9101")
                .header(PlatformContextHeaders.SERVICE_ACCOUNT_CODE, "datasmart-sync-service")
                .header(PlatformContextHeaders.REPRESENTED_ACTOR_ID, "1001")
                .header(PlatformContextHeaders.DELEGATION_TYPE, "SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR")
                .header(PlatformContextHeaders.DELEGATION_REASON, "AGENT_CONFIRMED_TOOL_OUTBOX")
                .header(PlatformContextHeaders.REQUESTED_POLICY_VERSION, "route-policy:860")
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
     * 构造拒绝访问的判定结果。
     */
    private GatewayPermissionDecisionResult deniedDecision(String reason) {
        GatewayPermissionDecisionResult decision = new GatewayPermissionDecisionResult();
        decision.setAllowed(false);
        decision.setReason(reason);
        decision.setMatchedRoutePolicyId(9002L);
        decision.setRouteEffect("DENY");
        return decision;
    }

    /**
     * 记录是否发生了下游转发的测试用 GatewayFilterChain。
     *
     * <p>真实 gateway chain 会继续执行路由转发；单元测试里我们只需要知道：
     * 1. 过滤器有没有继续调用 chain；
     * 2. 继续调用时传入的 exchange 是否已经带上数据范围 Header。
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
