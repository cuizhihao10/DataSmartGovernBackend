/**
 * @Author : Cui
 * @Date: 2026/05/28 21:10
 * @Description DataSmart Govern Backend - GatewayAgentOutboxAuthorizationFilterTest.java
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
 * Agent 工具执行事件 outbox 的网关授权语义测试。
 *
 * <p>这些用例从 {@link GatewayAuthorizationFilterTest} 中拆出，是为了遵守项目“单文件尽量低于 500 行”的设计约束。
 * outbox 人工补偿属于 Agent 运行时的运维能力，它既不是普通资源创建，也不是普通详情查看：
 * requeue 会重新触发历史事件投递，ignore 会人工归档异常事件，notes 会改变排障事实记录。
 * 因此 gateway 必须把这些入口翻译成独立 action，让 permission-admin 可以配置角色、审批和审计策略。</p>
 */
class GatewayAgentOutboxAuthorizationFilterTest {

    /**
     * Agent 工具执行事件 outbox 人工重新入队接口必须使用恢复类动作，而不能退化为 POST=CREATE。
     *
     * <p>outbox requeue 的业务含义是“把失败或阻断的历史事件重新交给 dispatcher 投递”，
     * 它可能触发前端实时事件补发、审计链路补偿和下游消费者再次处理，因此风险明显高于普通资源创建。
     * 如果 gateway 只按通用 REST 规则把它解释为 CREATE，permission-admin 就无法为运营人员、租户管理员、
     * 平台管理员和服务账号分别配置不同的恢复权限，也无法在审计报表里识别真正发生过的人工补偿动作。</p>
     */
    @Test
    void agentOutboxRequeueShouldUseDedicatedRecoveryAuthorization() {
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(decisionClient);
        MockServerWebExchange exchange = exchangeWithRole(
                "/api/agent/tool-execution-events/outbox/outbox-001/requeue",
                "POST",
                "OPERATOR"
        );
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("REQUEUE_OUTBOX");
        assertThat(captor.getValue().getActorRole()).isEqualTo("OPERATOR");
    }

    /**
     * Agent 工具执行事件 outbox 人工忽略接口必须使用独立归档动作。
     *
     * <p>ignore 会把异常事件转入 IGNORED，不再由 dispatcher 自动补偿。
     * 这类动作在事故复盘中非常敏感：它回答的是“为什么某条事件最终没有继续投递”，
     * 因此需要和重新入队、备注、普通查看分开建模，避免后续审计只能看到模糊的 CREATE/UPDATE。</p>
     */
    @Test
    void agentOutboxIgnoreShouldUseDedicatedIgnoreAuthorization() {
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(decisionClient);
        MockServerWebExchange exchange = exchangeWithRole(
                "/api/agent/tool-execution-events/outbox/outbox-001/ignore",
                "POST",
                "TENANT_ADMINISTRATOR"
        );
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("IGNORE_OUTBOX");
        assertThat(captor.getValue().getActorRole()).isEqualTo("TENANT_ADMINISTRATOR");
    }

    /**
     * Agent 工具执行事件 outbox 查询接口应使用 VIEW_OUTBOX_EVENTS，而不是普通 VIEW。
     *
     * <p>查询 outbox 记录通常发生在排障、审计和运行恢复前，它可能暴露工具审计 ID、投递失败原因、
     * 阻断摘要和最近人工备注。单独建模后，后续可以允许审计员只读查看，却禁止其执行 requeue/ignore；
     * 也可以允许运营人员查看全量异常队列，而普通项目用户只看自己 run 下的脱敏事件。</p>
     */
    @Test
    void agentOutboxQueryShouldUseDedicatedViewOutboxEventsAuthorization() {
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(decisionClient);
        MockServerWebExchange exchange = exchangeWithRole(
                "/api/agent/tool-execution-events/outbox",
                "GET",
                "AUDITOR"
        );
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo("VIEW_OUTBOX_EVENTS");
        assertThat(captor.getValue().getActorRole()).isEqualTo("AUDITOR");
    }

    /**
     * 构造强制授权模式下的 gateway 过滤器。
     *
     * <p>测试目标不是 permission-admin 的真实策略，而是验证 gateway 发出的 resourceType/action 是否正确。
     * 因此这里使用 mock decisionClient，并让真实的 route-metadata 解析逻辑参与执行。</p>
     */
    private GatewayAuthorizationFilter filter(PermissionAdminDecisionClient decisionClient) {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        properties.setEnabled(true);
        properties.setShadowMode(false);
        properties.setFailOpenOnError(false);
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
     * 构造带平台上下文 Header 的 mock 请求。
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
     *
     * <p>测试里只关心 gateway 向 permission-admin 发送的语义是否正确，
     * 所以判定结果保持最小可用字段，避免把 outbox 授权测试和数据范围传播测试耦合在一起。</p>
     */
    private GatewayPermissionDecisionResult allowedDecision() {
        GatewayPermissionDecisionResult decision = new GatewayPermissionDecisionResult();
        decision.setAllowed(true);
        decision.setReason("允许访问");
        decision.setMatchedRoutePolicyId(9101L);
        decision.setRouteEffect("ALLOW");
        decision.setDataScopeLevel("PROJECT");
        decision.setDataScopeExpression("project_id in ${authorizedProjectIds}");
        decision.setAuthorizedProjectIds(List.of(101L, 102L));
        decision.setApprovalRequired(false);
        return decision;
    }

    /**
     * 记录是否发生下游转发的测试链。
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
