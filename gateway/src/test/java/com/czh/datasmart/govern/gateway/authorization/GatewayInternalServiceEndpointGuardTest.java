/**
 * @Author : Cui
 * @Date: 2026/06/30 00:00
 * @Description DataSmart Govern Backend - GatewayInternalServiceEndpointGuardTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部服务端点守卫测试。
 *
 * <p>这组测试只验证 gateway 本地的第一道机器协议边界，不启动完整 Spring Cloud Gateway。
 * 这样做的原因是：内部端点保护属于入口安全规则，应该用小而稳定的单元测试固定“什么请求能进入
 * permission-admin、什么请求必须在网关本地 fail-closed”。如果每次都拉起完整路由链，测试成本更高，
 * 也更难定位到底是路径匹配、身份 Header、限流还是权限中心 mock 出了问题。
 *
 * <p>业务背景：
 * `agent-runtime` 已经拥有不少 `/internal/agent-runtime/**` 机器协议入口，例如 worker lease、worker receipt、
 * payload materialization。它们不是普通用户 API，而是 Python Runtime、worker、调度器或未来服务网格内部组件
 * 使用的控制面入口。因此 gateway 必须同时校验：
 * 1. actorRole=SERVICE_ACCOUNT，表示具备服务账号角色；
 * 2. actorType=SERVICE_ACCOUNT，表示 token 本身代表机器主体；
 * 3. 可选 internal token / HMAC / mTLS 等后续增强；
 * 4. 本地限流，避免 worker 重试风暴打满 agent-runtime。
 */
class GatewayInternalServiceEndpointGuardTest {

    /**
     * 服务账号角色与服务账号主体类型同时满足时，AgentPlan 内部接入口才允许继续进入 permission-admin。
     */
    @Test
    void serviceAccountRoleAndActorTypeShouldPassPlanIngestionGuard() {
        GatewayInternalServiceEndpointGuard guard = new GatewayInternalServiceEndpointGuard(new GatewayAuthorizationProperties());

        GatewayInternalServiceEndpointGuard.GuardDecision decision =
                guard.evaluate(request("/api/agent/plan-ingestions", "SERVICE_ACCOUNT", "SERVICE_ACCOUNT"));

        assertThat(decision.protectedEndpoint()).isTrue();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.endpointName()).isEqualTo("agent-plan-ingestion");
    }

    /**
     * 只有 SERVICE_ACCOUNT 角色但缺少 SERVICE_ACCOUNT 主体类型时必须拒绝。
     *
     * <p>这是生产级 OIDC/Keycloak 场景里非常重要的差异：role 可能因为策略授权而变化，
     * actorType 才表达“这是机器身份而不是人类用户”。内部 worker/API 入口不能被人类 token 直接调用。
     */
    @Test
    void serviceAccountRoleWithoutServiceAccountActorTypeShouldBeDenied() {
        GatewayInternalServiceEndpointGuard guard = new GatewayInternalServiceEndpointGuard(new GatewayAuthorizationProperties());

        GatewayInternalServiceEndpointGuard.GuardDecision decision =
                guard.evaluate(request("/api/agent/plan-ingestions", "SERVICE_ACCOUNT", "USER"));

        assertThat(decision.protectedEndpoint()).isTrue();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.status().value()).isEqualTo(403);
        assertThat(decision.reason()).contains("actorType=USER");
    }

    /**
     * agent-runtime 的 `/api/internal/agent-runtime/**` worker 回执入口应被默认内部端点规则保护。
     */
    @Test
    void apiInternalAgentRuntimeWorkerReceiptShouldRequireServiceAccountIdentity() {
        GatewayInternalServiceEndpointGuard guard = new GatewayInternalServiceEndpointGuard(new GatewayAuthorizationProperties());

        GatewayInternalServiceEndpointGuard.GuardDecision denied = guard.evaluate(request(
                "/api/internal/agent-runtime/sessions/session-1/runs/run-1/tool-executions/command-worker-receipts",
                "PROJECT_OWNER",
                "USER"));

        assertThat(denied.protectedEndpoint()).isTrue();
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.endpointName()).isEqualTo("agent-runtime-command-worker-receipts");

        GatewayInternalServiceEndpointGuard.GuardDecision allowed = guard.evaluate(request(
                "/api/internal/agent-runtime/sessions/session-1/runs/run-1/tool-executions/command-worker-receipts",
                "SERVICE_ACCOUNT",
                "SERVICE_ACCOUNT"));

        assertThat(allowed.protectedEndpoint()).isTrue();
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.endpointName()).isEqualTo("agent-runtime-command-worker-receipts");
    }

    /**
     * 带 `/**` 的 lease 子路径也必须命中内部保护规则。
     *
     * <p>lease 入口通常会包含 claim、heartbeat、release 等子动作。测试这里的子路径匹配，
     * 是为了避免未来只保护了基础路径，却把具体 worker 租约动作意外暴露成普通 API。
     */
    @Test
    void commandWorkerLeaseSubPathShouldMatchInternalEndpointRule() {
        GatewayInternalServiceEndpointGuard guard = new GatewayInternalServiceEndpointGuard(new GatewayAuthorizationProperties());

        GatewayInternalServiceEndpointGuard.GuardDecision decision = guard.evaluate(request(
                "/api/internal/agent-runtime/sessions/session-1/runs/run-1/tool-executions/command-worker-leases/claims",
                "SERVICE_ACCOUNT",
                "SERVICE_ACCOUNT"));

        assertThat(decision.protectedEndpoint()).isTrue();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.endpointName()).isEqualTo("agent-runtime-command-worker-leases");
    }

    private MockServerHttpRequest request(String path, String actorRole, String actorType) {
        return MockServerHttpRequest.method(HttpMethod.POST, path)
                .header(PlatformContextHeaders.TRACE_ID, "trace-test-001")
                .header(PlatformContextHeaders.TENANT_ID, "10")
                .header(PlatformContextHeaders.ACTOR_ID, "9101")
                .header(PlatformContextHeaders.ACTOR_ROLE, actorRole)
                .header(PlatformContextHeaders.ACTOR_TYPE, actorType)
                .build();
    }
}
