/**
 * @Author : Cui
 * @Date: 2026/08/12 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeControlPlaneIngressFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.security;

import com.czh.datasmart.govern.agent.config.AgentRuntimeControlPlaneIngressProperties;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused regression tests for the Gateway-to-Agent Runtime ingress trust boundary.
 *
 * <p>These tests intentionally exercise the servlet filter directly instead of constructing the
 * full Agent Runtime application. The security decision is made before controller dependencies,
 * Kafka listeners, PostgreSQL stores or Python bridges are relevant. Keeping the test this narrow
 * makes the P0 regression obvious and keeps default-disabled source tests independent of a secret.</p>
 */
class AgentRuntimeControlPlaneIngressFilterTest {

    /**
     * Keeps existing standalone source tests and learning-mode controller tests usable by default.
     */
    @Test
    void shouldPassThroughWhenIngressProtectionIsDisabled() throws Exception {
        AgentRuntimeControlPlaneIngressProperties properties = properties(false, "deployment-secret");
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletResponse response = invoke(
                properties,
                request("/agent-runtime/sessions/session-1", null),
                chainInvoked
        );

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * Proves that forged identity headers cannot substitute for the Gateway-injected deployment secret.
     */
    @Test
    void shouldRejectForgedIdentityHeadersWhenIngressIsEnabledAndTokenIsMissing() throws Exception {
        AgentRuntimeControlPlaneIngressProperties properties = properties(true, "deployment-secret");
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        MockHttpServletRequest request = request("/agent-runtime/sessions/session-1", null);
        request.addHeader(PlatformContextHeaders.TENANT_ID, "1");
        request.addHeader(PlatformContextHeaders.PROJECT_ID, "100");
        request.addHeader(PlatformContextHeaders.ACTOR_ID, "999");
        request.addHeader(PlatformContextHeaders.ACTOR_ROLE, "PLATFORM_ADMINISTRATOR");

        MockHttpServletResponse response = invoke(properties, request, chainInvoked);

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * Accepts the token after Gateway has rebuilt request context and injected its deployment secret.
     */
    @Test
    void shouldAllowGatewayRewrittenControlPlanePathWithCorrectToken() throws Exception {
        AgentRuntimeControlPlaneIngressProperties properties = properties(true, "deployment-secret");
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletResponse response = invoke(
                properties,
                request("/agent-runtime/sessions/session-1", "deployment-secret"),
                chainInvoked
        );

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * Covers the compatibility alias so a direct caller cannot bypass protection by avoiding Gateway rewriting.
     */
    @Test
    void shouldProtectLegacyApiAgentAliasToo() throws Exception {
        AgentRuntimeControlPlaneIngressProperties properties = properties(true, "deployment-secret");
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletResponse response = invoke(
                properties,
                request("/api/agent/sessions/session-1", "wrong-secret"),
                chainInvoked
        );

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * Rejects a near-match instead of trimming a caller-controlled credential into a valid secret.
     */
    @Test
    void shouldRejectPaddedTokenRatherThanNormalizingItIntoTheConfiguredSecret() throws Exception {
        AgentRuntimeControlPlaneIngressProperties properties = properties(true, "deployment-secret");
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        MockHttpServletResponse response = invoke(
                properties,
                request("/agent-runtime/sessions/session-1", " deployment-secret "),
                chainInvoked
        );

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * Documents the narrow exemptions: health probes and internal worker protocols are not public aliases.
     */
    @Test
    void shouldLeaveActuatorAndInternalWorkerRoutesToTheirSeparateBoundaries() throws Exception {
        AgentRuntimeControlPlaneIngressProperties properties = properties(true, "deployment-secret");
        AtomicBoolean actuatorChainInvoked = new AtomicBoolean(false);
        AtomicBoolean internalChainInvoked = new AtomicBoolean(false);

        MockHttpServletResponse actuatorResponse = invoke(
                properties,
                request("/actuator/health", null),
                actuatorChainInvoked
        );
        MockHttpServletResponse internalResponse = invoke(
                properties,
                request("/internal/agent-runtime/sessions/session-1/runs/run-1/tool-executions/command-worker-receipts", null),
                internalChainInvoked
        );

        assertThat(actuatorChainInvoked).isTrue();
        assertThat(actuatorResponse.getStatus()).isEqualTo(200);
        assertThat(internalChainInvoked).isTrue();
        assertThat(internalResponse.getStatus()).isEqualTo(200);
    }

    /**
     * Creates an isolated configuration object without placing the actual deployment secret in test output.
     *
     * @param enabled whether the filter should enforce the ingress proof
     * @param sharedToken expected secret used only in this in-memory test
     * @return configured properties for one filter invocation
     */
    private AgentRuntimeControlPlaneIngressProperties properties(boolean enabled, String sharedToken) {
        AgentRuntimeControlPlaneIngressProperties properties = new AgentRuntimeControlPlaneIngressProperties();
        properties.setEnabled(enabled);
        properties.setSharedToken(sharedToken);
        return properties;
    }

    /**
     * Builds a request that represents either a direct caller or a request already forwarded by Gateway.
     *
     * @param path public, internal or actuator request path
     * @param internalToken token header supplied only by the trusted Gateway path
     * @return mutable mock servlet request
     */
    private MockHttpServletRequest request(String path, String internalToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (internalToken != null) {
            request.addHeader(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, internalToken);
        }
        return request;
    }

    /**
     * Runs a request through the filter and records whether execution could reach a downstream controller.
     *
     * @param properties current ingress configuration
     * @param request request under test
     * @param chainInvoked output flag representing controller-chain reachability
     * @return servlet response after the ingress decision
     * @throws Exception propagated servlet test failure
     */
    private MockHttpServletResponse invoke(AgentRuntimeControlPlaneIngressProperties properties,
                                           MockHttpServletRequest request,
                                           AtomicBoolean chainInvoked) throws Exception {
        AgentRuntimeControlPlaneIngressFilter filter = new AgentRuntimeControlPlaneIngressFilter(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> chainInvoked.set(true);
        filter.doFilter(request, response, chain);
        return response;
    }
}
