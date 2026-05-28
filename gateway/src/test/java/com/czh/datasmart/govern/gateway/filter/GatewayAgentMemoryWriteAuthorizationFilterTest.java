/**
 * @Author : Cui
 * @Date: 2026/05/28 23:40
 * @Description DataSmart Govern Backend - GatewayAgentMemoryWriteAuthorizationFilterTest.java
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
 * Agent 长期记忆写入候选的网关授权语义测试。
 *
 * <p>长期记忆候选处在“工具执行结果”和“长期记忆持久化”之间，是一个非常关键的治理闸口。
 * 如果网关只按 HTTP 方法粗略推断，那么：
 * GET 会退化成普通 VIEW，审计员查看候选和查看普通资源无法区分；
 * POST approve/reject 会退化成 CREATE，permission-admin 无法分别配置“谁能批准写入”和“谁能拒绝写入”；
 * 后续接入 Chroma、Neo4j 或 MySQL 写入 worker 时，就可能出现未授权内容被长期保存的问题。</p>
 *
 * <p>本测试不验证 permission-admin 的最终策略是否允许，而是锁定 gateway 发送给 permission-admin 的
 * resourceType/action 契约。只要这个契约稳定，权限中心就可以基于角色、数据范围、审批链和审计规则继续演进。</p>
 */
class GatewayAgentMemoryWriteAuthorizationFilterTest {

    /**
     * 查看候选列表时应使用专门的 VIEW_MEMORY_WRITE_CANDIDATES。
     *
     * <p>候选列表会展示候选来源、scope、状态、风险提示和写入理由，属于审批台/审计台资源。
     * 它不同于普通资源详情查看，因为候选内容一旦批准会影响后续 Agent 的长期上下文和检索结果。</p>
     */
    @Test
    void memoryWriteCandidateListShouldUseDedicatedViewAuthorization() {
        assertAction(
                "/api/agent/memory/write-candidates",
                "GET",
                "AUDITOR",
                "VIEW_MEMORY_WRITE_CANDIDATES"
        );
    }

    /**
     * 查看候选详情时也应继续使用候选查看动作。
     *
     * <p>详情接口通常比列表暴露更多证据，例如候选 ID、工具 auditId、runId、候选状态和拒绝/批准理由。
     * 这里保持与列表同一 action，后续通过数据范围、字段脱敏和详情级审计继续增强，而不是在 gateway 中硬编码复杂策略。</p>
     */
    @Test
    void memoryWriteCandidateDetailShouldUseDedicatedViewAuthorization() {
        assertAction(
                "/api/agent/memory/write-candidates/candidate-001",
                "GET",
                "OPERATOR",
                "VIEW_MEMORY_WRITE_CANDIDATES"
        );
    }

    /**
     * 批准候选必须使用 APPROVE_MEMORY_WRITE，而不能退化为 POST=CREATE。
     *
     * <p>批准动作代表该候选未来可以被异步 worker 写入长期记忆。商业化产品中，这会影响模型后续回答、
     * 工具规划和跨会话上下文，因此需要独立于普通审批动作进行角色控制、审批链控制和审计统计。</p>
     */
    @Test
    void memoryWriteCandidateApproveShouldUseDedicatedApproveAuthorization() {
        assertAction(
                "/api/agent/memory/write-candidates/candidate-001/approve",
                "POST",
                "PROJECT_OWNER",
                "APPROVE_MEMORY_WRITE"
        );
    }

    /**
     * 拒绝候选必须使用 REJECT_MEMORY_WRITE，而不能与批准共用 APPROVE。
     *
     * <p>拒绝也是重要的治理事实：它说明某条候选因为敏感、越权、过期或质量不足不能进入长期记忆。
     * 单独建模后，后续可以统计高频拒绝原因、优化 memoryWritePolicy，并支撑合规审计。</p>
     */
    @Test
    void memoryWriteCandidateRejectShouldUseDedicatedRejectAuthorization() {
        assertAction(
                "/api/agent/memory/write-candidates/candidate-001/reject",
                "POST",
                "PROJECT_OWNER",
                "REJECT_MEMORY_WRITE"
        );
    }

    /**
     * 执行一次完整的 gateway 授权过滤，并断言传给 permission-admin 的动作编码。
     *
     * <p>这里复用真实的 {@link GatewayAuthorizationProperties} 默认 route metadata。
     * 这样测试能够同时保护 Java 代码默认值和 PathPattern 匹配顺序，避免 application.yml 与代码默认值漂移后本地测试仍然误通过。</p>
     */
    private void assertAction(String path, String method, String actorRole, String expectedAction) {
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = filter(decisionClient);
        MockServerWebExchange exchange = exchangeWithRole(path, method, actorRole);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-test-001"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor = forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-test-001"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo(expectedAction);
        assertThat(captor.getValue().getActorRole()).isEqualTo(actorRole);
    }

    /**
     * 构造强制授权模式下的 gateway 过滤器。
     *
     * <p>测试中关闭 shadowMode 和 failOpenOnError，是为了让路径解析、远程判定和转发行为都走生产强制模式。
     * decisionClient 使用 mock，是因为本测试只关注 gateway 是否发出了正确的权限语义，而不依赖真实 permission-admin 服务。</p>
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
     * 构造携带平台上下文 Header 的 mock 请求。
     *
     * <p>gateway 会把这些 Header 组装为 {@link GatewayPermissionDecisionRequest}。
     * 真实环境中它们通常来自认证过滤器或上游身份系统；测试中直接写入，能让用例聚焦在 route metadata 解析。</p>
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
     * 构造 permission-admin 允许访问时的最小判定结果。
     *
     * <p>这里保留数据范围字段，是为了让 gateway 的完整授权成功路径继续执行；
     * 但测试断言只关注 resourceType/action，避免把长期记忆候选授权测试和数据范围透传测试耦合在一起。</p>
     */
    private GatewayPermissionDecisionResult allowedDecision() {
        GatewayPermissionDecisionResult decision = new GatewayPermissionDecisionResult();
        decision.setAllowed(true);
        decision.setReason("允许访问");
        decision.setMatchedRoutePolicyId(9201L);
        decision.setRouteEffect("ALLOW");
        decision.setDataScopeLevel("PROJECT");
        decision.setDataScopeExpression("project_id in ${authorizedProjectIds}");
        decision.setAuthorizedProjectIds(List.of(101L, 102L));
        decision.setApprovalRequired(false);
        return decision;
    }

    /**
     * 记录 gateway 是否继续向下游转发。
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
