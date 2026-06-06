/**
 * @Author : Cui
 * @Date: 2026/06/06 23:14
 * @Description DataSmart Govern Backend - GatewayAgentToolPolicyEnvelopeFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolExecutionReadinessPolicyView;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolPolicyEnvelopeClient;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolPolicyEnvelopeFactory;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolPolicyEnvelopeRequest;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolPolicyEnvelopeView;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationErrorWriter;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 工具治理策略信封过滤器测试。
 *
 * <p>这些用例保护 5.42 的核心目标：
 * - 调用方不能伪造 `X-DataSmart-Tool-Policy-Envelope`；
 * - gateway 可以在本地 fallback 模式下生成低敏 envelope；
 * - 打开远程评估时，gateway 会把 permission-admin 的预算与 readiness 策略写入 Header；
 * - 远程异常时按配置选择 fallback 或 fail-closed。</p>
 */
class GatewayAgentToolPolicyEnvelopeFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 关闭 envelope 注入时仍必须清理外部伪造 Header。
     */
    @Test
    void disabledEnvelopeShouldClearForgedHeader() {
        GatewayContextProperties properties = properties();
        properties.getToolPolicyEnvelope().setEnabled(false);
        StubPolicyClient client = new StubPolicyClient(Mono.just(remoteView()));
        GatewayAgentToolPolicyEnvelopeFilter filter = filter(properties, client);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/plans")
                .mutate()
                .request(MockServerHttpRequest.post("/api/agent/plans")
                        .header(PlatformContextHeaders.TOOL_POLICY_ENVELOPE, "{\"forged\":true}")
                        .build())
                .build(), chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(
                PlatformContextHeaders.TOOL_POLICY_ENVELOPE)).isFalse();
        assertThat(client.calls()).isZero();
    }

    /**
     * 本地 fallback 模式应写入低敏策略 envelope。
     */
    @Test
    void localFallbackShouldWriteLowSensitiveEnvelope() throws Exception {
        GatewayContextProperties properties = properties();
        StubPolicyClient client = new StubPolicyClient(Mono.just(remoteView()));
        GatewayAgentToolPolicyEnvelopeFilter filter = filter(properties, client);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/plans"), chain).block();

        String envelope = chain.exchange().getRequest().getHeaders()
                .getFirst(PlatformContextHeaders.TOOL_POLICY_ENVELOPE);
        Map<String, Object> parsed = parse(envelope);
        Map<String, Object> budget = objectMap(parsed.get("toolCallBudget"));
        Map<String, Object> readiness = objectMap(parsed.get("toolExecutionReadinessPolicy"));
        assertThat(client.calls()).isZero();
        assertThat(budget.get("maxProposedToolCalls")).isEqualTo(5);
        assertThat(budget.get("maxHighRiskToolCalls")).isEqualTo(0);
        assertThat(readiness.get("source")).isEqualTo("gateway-local-fallback");
        assertThat(readiness.get("actorRole")).isEqualTo("PROJECT_OWNER");
        assertThat(envelope).doesNotContain("prompt", "sql", "arguments", "secret", "internalEndpoint");
    }

    /**
     * 远程评估开启时，应使用 permission-admin 返回的策略结果。
     */
    @Test
    void remoteEvaluationShouldWriteRemotePolicyEnvelope() throws Exception {
        GatewayContextProperties properties = properties();
        properties.getToolPolicyEnvelope().setRemoteEvaluationEnabled(true);
        StubPolicyClient client = new StubPolicyClient(Mono.just(remoteView()));
        GatewayAgentToolPolicyEnvelopeFilter filter = filter(properties, client);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/plans"), chain).block();

        String envelope = chain.exchange().getRequest().getHeaders()
                .getFirst(PlatformContextHeaders.TOOL_POLICY_ENVELOPE);
        Map<String, Object> parsed = parse(envelope);
        Map<String, Object> budget = objectMap(parsed.get("toolCallBudget"));
        Map<String, Object> readiness = objectMap(parsed.get("toolExecutionReadinessPolicy"));
        assertThat(client.calls()).isEqualTo(1);
        assertThat(client.lastRequest().getTenantId()).isEqualTo(10L);
        assertThat(client.lastRequest().getProjectId()).isEqualTo("20");
        assertThat(budget.get("policyVersion")).isEqualTo("permission-admin-v9");
        assertThat(budget.get("maxProposedToolCalls")).isEqualTo(9);
        assertThat(readiness.get("source")).isEqualTo("permission-admin");
        assertThat(readiness.get("maxAsyncTools")).isEqualTo(2);
    }

    /**
     * 远程异常且允许回退时，应使用本地保守策略继续转发。
     */
    @Test
    void remoteErrorShouldFallbackWhenFailOpenEnabled() {
        GatewayContextProperties properties = properties();
        properties.getToolPolicyEnvelope().setRemoteEvaluationEnabled(true);
        properties.getToolPolicyEnvelope().setFailOpenOnRemoteError(true);
        StubPolicyClient client = new StubPolicyClient(Mono.error(new IllegalStateException("permission-admin timeout")));
        GatewayAgentToolPolicyEnvelopeFilter filter = filter(properties, client);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/plans"), chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(
                PlatformContextHeaders.TOOL_POLICY_ENVELOPE)).contains("gateway-local-fallback");
    }

    /**
     * 远程异常且关闭回退时，应拒绝本次 Agent 规划。
     */
    @Test
    void remoteErrorShouldFailClosedWhenFallbackDisabled() {
        GatewayContextProperties properties = properties();
        properties.getToolPolicyEnvelope().setRemoteEvaluationEnabled(true);
        properties.getToolPolicyEnvelope().setFailOpenOnRemoteError(false);
        StubPolicyClient client = new StubPolicyClient(Mono.error(new IllegalStateException("permission-admin timeout")));
        GatewayAgentToolPolicyEnvelopeFilter filter = filter(properties, client);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();
        MockServerWebExchange exchange = exchange("/api/agent/plans");

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private GatewayAgentToolPolicyEnvelopeFilter filter(
            GatewayContextProperties properties,
            GatewayAgentToolPolicyEnvelopeClient client) {
        return new GatewayAgentToolPolicyEnvelopeFilter(
                properties,
                client,
                new GatewayAgentToolPolicyEnvelopeFactory(OBJECT_MAPPER),
                new GatewayAuthorizationErrorWriter(OBJECT_MAPPER)
        );
    }

    private GatewayContextProperties properties() {
        return new GatewayContextProperties();
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .header(PlatformContextHeaders.SOURCE_SERVICE, "datasmart-govern-gateway")
                .header(PlatformContextHeaders.TRACE_ID, "trace-001")
                .header(PlatformContextHeaders.TENANT_ID, "10")
                .header(PlatformContextHeaders.ACTOR_ID, "1001")
                .header(PlatformContextHeaders.ACTOR_ROLE, "PROJECT_OWNER")
                .header(PlatformContextHeaders.ACTOR_TYPE, "USER")
                .header(PlatformContextHeaders.WORKSPACE_ID, "workspace-a")
                .header(PlatformContextHeaders.REQUEST_SOURCE, "WEB_UI")
                .header(PlatformContextHeaders.TENANT_PLAN_CODE, "STANDARD")
                .header(PlatformContextHeaders.WORKSPACE_RISK_LEVEL, "NORMAL")
                .header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "20,30")
                .build());
    }

    private static GatewayAgentToolPolicyEnvelopeView remoteView() {
        GatewayAgentToolPolicyEnvelopeView view = new GatewayAgentToolPolicyEnvelopeView();
        view.setAllowed(true);
        view.setPolicySource("IN_MEMORY_RULE");
        view.setPolicyVersion("permission-admin-v9");
        Map<String, Integer> budget = new LinkedHashMap<>();
        budget.put("maxProposedToolCalls", 9);
        budget.put("maxAutoExecutableToolCalls", 3);
        budget.put("maxHighRiskToolCalls", 1);
        budget.put("maxSingleArgumentsBytes", 32768);
        budget.put("maxTotalArgumentsBytes", 65536);
        view.setToolCallBudget(budget);
        GatewayAgentToolExecutionReadinessPolicyView readiness = new GatewayAgentToolExecutionReadinessPolicyView();
        readiness.setSource("permission-admin");
        readiness.setPolicyVersion("permission-admin-v9");
        readiness.setActorRole("PROJECT_OWNER");
        readiness.setTenantPlanCode("STANDARD");
        readiness.setWorkspaceRiskLevel("NORMAL");
        readiness.setWorkerBacklogLevel("NORMAL");
        readiness.setMaxAutoSyncTools(3);
        readiness.setMaxAsyncTools(2);
        readiness.setHighRiskRequiresApproval(true);
        readiness.setCriticalRiskBlocked(true);
        readiness.setAllowDraftWithoutAllParameters(true);
        readiness.setInfluenceCodes(List.of("ROLE_AND_PLAN_BASELINE_POLICY"));
        view.setToolExecutionReadinessPolicy(readiness);
        return view;
    }

    private Map<String, Object> parse(String json) throws Exception {
        return OBJECT_MAPPER.readValue(json, new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static class StubPolicyClient implements GatewayAgentToolPolicyEnvelopeClient {
        private final Mono<GatewayAgentToolPolicyEnvelopeView> response;
        private final AtomicReference<GatewayAgentToolPolicyEnvelopeRequest> lastRequest = new AtomicReference<>();
        private int calls;

        private StubPolicyClient(Mono<GatewayAgentToolPolicyEnvelopeView> response) {
            this.response = response;
        }

        @Override
        public Mono<GatewayAgentToolPolicyEnvelopeView> evaluate(
                GatewayAgentToolPolicyEnvelopeRequest request,
                String traceId) {
            this.calls++;
            this.lastRequest.set(request);
            return response;
        }

        private int calls() {
            return calls;
        }

        private GatewayAgentToolPolicyEnvelopeRequest lastRequest() {
            return lastRequest.get();
        }
    }

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
