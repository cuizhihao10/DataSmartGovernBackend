/**
 * @Author : Cui
 * @Date: 2026/06/01 10:46
 * @Description DataSmart Govern Backend - GatewayAgentAsyncOutboxAuthorizationTest.java
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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 异步 outbox 网关授权动作测试。
 *
 * <p>该测试单独拆文件，是为了避免继续把通用网关过滤器测试堆成超大类。它保护两个容易被通配规则吞掉的入口：
 * 推荐的 selected-node 入箱必须使用细粒度动作；兼容 Run 级批量入箱必须使用更高风险的独立动作。
 * 这样 permission-admin 才能在后续策略中只给普通审批流开放推荐入口，把粗粒度入口留给受信服务账号或管理员。</p>
 */
class GatewayAgentAsyncOutboxAuthorizationTest {

    @Test
    void selectedNodeOutboxEnqueueShouldUseDedicatedAuthorizationAction() {
        assertAuthorizationAction(
                "/api/agent/sessions/session-1/runs/run-1/tool-executions/dag-selected-node-outbox/enqueue",
                "ENQUEUE_SELECTED_ASYNC_TOOL"
        );
    }

    @Test
    void runLevelOutboxEnqueueShouldUseHigherRiskCompatibilityAction() {
        assertAuthorizationAction(
                "/api/agent/sessions/session-1/runs/run-1/tool-executions/async-command-outbox/enqueue",
                "ENQUEUE_RUN_ASYNC_TOOLS"
        );
    }

    @Test
    void selectedNodeConfirmationQueryShouldUseDedicatedViewAction() {
        assertAuthorizationAction(
                "/api/agent/sessions/session-1/runs/run-1/tool-executions/dag-confirmations",
                "GET",
                "VIEW_TOOL_CONFIRMATIONS"
        );
        assertAuthorizationAction(
                "/api/agent/sessions/session-1/runs/run-1/tool-executions/dag-confirmations/confirmation-1",
                "GET",
                "VIEW_TOOL_CONFIRMATIONS"
        );
    }

    /**
     * 断言 POST 请求被解释成预期业务动作。
     *
     * <p>如果规则顺序错误，请求会落入 `/api/agent/**` 或 `/api/agent/sessions/**`，并退化成普通 CREATE。
     * 测试捕获 gateway 发给 permission-admin 的请求，确保授权语义没有被通配规则弱化。</p>
     */
    private void assertAuthorizationAction(String path, String expectedAction) {
        assertAuthorizationAction(path, "POST", expectedAction);
    }

    private void assertAuthorizationAction(String path, String method, String expectedAction) {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        properties.setEnabled(true);
        properties.setShadowMode(false);
        properties.setFailOpenOnError(false);
        PermissionAdminDecisionClient decisionClient = mock(PermissionAdminDecisionClient.class);
        GatewayAuthorizationFilter filter = new GatewayAuthorizationFilter(
                properties,
                decisionClient,
                new GatewayAuthorizationDecisionCache(properties),
                new GatewayAuthorizationMetrics(new SimpleMeterRegistry()),
                new GatewayInternalServiceEndpointGuard(properties),
                new GatewayAuthorizationErrorWriter(new ObjectMapper()),
                new com.czh.datasmart.govern.gateway.authorization.GatewayServiceAccountDelegationSupport()
        );
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
                        .header(PlatformContextHeaders.TRACE_ID, "trace-agent-outbox-auth")
                        .header(PlatformContextHeaders.TENANT_ID, "10")
                        .header(PlatformContextHeaders.ACTOR_ID, "1001")
                        .header(PlatformContextHeaders.ACTOR_ROLE, "PROJECT_OWNER")
                        .build()
        );
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        when(decisionClient.evaluate(any(), eq("trace-agent-outbox-auth"))).thenReturn(Mono.just(allowedDecision()));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<GatewayPermissionDecisionRequest> captor =
                ArgumentCaptor.forClass(GatewayPermissionDecisionRequest.class);
        verify(decisionClient).evaluate(captor.capture(), eq("trace-agent-outbox-auth"));
        assertThat(chain.called()).isTrue();
        assertThat(captor.getValue().getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(captor.getValue().getAction()).isEqualTo(expectedAction);
    }

    private GatewayPermissionDecisionResult allowedDecision() {
        GatewayPermissionDecisionResult result = new GatewayPermissionDecisionResult();
        result.setAllowed(true);
        result.setReason("测试允许访问");
        return result;
    }

    private static final class RecordingGatewayFilterChain implements GatewayFilterChain {

        private final AtomicBoolean called = new AtomicBoolean(false);

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            called.set(true);
            return Mono.empty();
        }

        boolean called() {
            return called.get();
        }
    }
}
