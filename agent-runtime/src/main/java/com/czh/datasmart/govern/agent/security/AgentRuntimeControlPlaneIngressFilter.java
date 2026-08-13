/**
 * @Author : Cui
 * @Date: 2026/08/12 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeControlPlaneIngressFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.security;

import com.czh.datasmart.govern.agent.config.AgentRuntimeControlPlaneIngressProperties;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Verifies that a public Agent Runtime control-plane request was forwarded by the deployment Gateway.
 *
 * <p>Before this boundary existed, a caller that could reach Agent Runtime directly could attach
 * forged {@code X-DataSmart-Tenant-Id}, actor or role headers. Controllers would then have no
 * cryptographic signal distinguishing those values from headers rebuilt by Gateway. This filter
 * closes that gap by requiring Gateway to remove any client-supplied token and inject a secret that
 * is available only to the deployment path.</p>
 *
 * <p>The filter intentionally protects only public control-plane aliases. {@code /actuator/**}
 * remains available for Docker/Kubernetes health probes. {@code /internal/agent-runtime/**} is
 * excluded because it is a distinct machine-protocol contract already routed by Gateway as a
 * service-account-only endpoint; several existing worker callbacks would otherwise require a
 * coordinated protocol migration. This exemption is not an authorization grant and does not claim
 * that all internal routes already share one token guard. They must retain their own service
 * authorization and network boundary, and Compose does not publish them beyond loopback.</p>
 */
public final class AgentRuntimeControlPlaneIngressFilter extends OncePerRequestFilter {

    /**
     * Fixed public aliases that must always require the Gateway ingress proof when the filter is enabled.
     *
     * <p>These are intentionally code-owned rather than deployment-configurable. A typo, empty list
     * or accidental YAML override must not silently turn an enabled security boundary into an
     * unprotected endpoint. The compatibility alias is included because direct callers can otherwise
     * bypass Gateway's {@code /api/agent/** -> /agent-runtime/**} rewrite.</p>
     */
    private static final List<String> PROTECTED_CONTROL_PLANE_PREFIXES = List.of(
            "/agent-runtime/",
            "/api/agent/"
    );

    /** Runtime settings supplied by environment-specific configuration. */
    private final AgentRuntimeControlPlaneIngressProperties properties;

    /**
     * Creates the ingress guard with a configuration object rather than hard-coded deployment data.
     *
     * @param properties enabled flag, expected token and protected external path aliases
     */
    public AgentRuntimeControlPlaneIngressFilter(AgentRuntimeControlPlaneIngressProperties properties) {
        this.properties = properties;
    }

    /**
     * Limits filter execution to public Agent Runtime aliases while preserving source-test behavior.
     *
     * <p>Returning {@code true} when disabled is defense in depth. Production does not register the
     * filter bean at all when disabled, but the explicit check also makes direct unit tests and any
     * future manual registration obey the same safe development default.</p>
     *
     * @param request current servlet request
     * @return {@code true} when this request is outside the protected ingress boundary
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        return !isProtectedControlPlanePath(request.getRequestURI(), request.getContextPath());
    }

    /**
     * Rejects a request before any controller can interpret caller-controlled identity headers.
     *
     * <p>A blank expected secret is treated exactly like an invalid caller token. The comparison uses
     * {@link MessageDigest#isEqual(byte[], byte[])} so the application does not use ordinary
     * short-circuiting string equality on a deployment credential. Neither the expected nor supplied
     * value is logged, returned, or attached to a request attribute.</p>
     *
     * @param request current public control-plane request
     * @param response response used only for the generic unauthorized status
     * @param filterChain next servlet filter/controller chain
     * @throws ServletException when a later filter or controller raises a servlet error
     * @throws IOException when a later filter writes the response
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String expectedToken = normalizeToken(properties.getSharedToken());
        String suppliedToken = normalizeToken(request.getHeader(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN));

        if (!StringUtils.hasText(expectedToken) || !StringUtils.hasText(suppliedToken)
                || !constantTimeEquals(expectedToken, suppliedToken)) {
            rejectWithoutDisclosingCredentialDetails(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Matches a request path against the configured public aliases with a segment boundary.
     *
     * <p>A simple {@code startsWith("/api/agent")} would also match an unrelated hypothetical path
     * such as {@code /api/agent-admin}. Normalizing every prefix to a trailing slash ensures the
     * exact root and descendants are protected without accidentally widening the boundary.</p>
     *
     * @param requestUri servlet request URI, including an optional context path
     * @param contextPath servlet context path, normally empty in the packaged service
     * @return whether this request reaches a public Agent Runtime controller alias
     */
    private boolean isProtectedControlPlanePath(String requestUri, String contextPath) {
        String applicationPath = removeContextPath(requestUri, contextPath);
        return PROTECTED_CONTROL_PLANE_PREFIXES.stream()
                .anyMatch(prefix -> matchesPrefixBoundary(applicationPath, prefix));
    }

    /**
     * Removes the servlet context prefix so configured aliases remain deployment-independent.
     *
     * @param requestUri URI reported by the servlet container
     * @param contextPath configured servlet context path
     * @return URI relative to the Agent Runtime application
     */
    private String removeContextPath(String requestUri, String contextPath) {
        if (StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    /**
     * Checks the exact route root and route descendants without accepting a similarly named route.
     *
     * @param applicationPath request path relative to Agent Runtime
     * @param normalizedPrefix slash-terminated protected prefix
     * @return whether the request belongs to the protected path family
     */
    private boolean matchesPrefixBoundary(String applicationPath, String normalizedPrefix) {
        String exactPath = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        return applicationPath.equals(exactPath) || applicationPath.startsWith(normalizedPrefix);
    }

    /**
     * Preserves a non-blank deployment secret exactly while rejecting accidental blank values.
     *
     * <p>Secrets are not trimmed. A padded caller header must not authenticate merely because its
     * middle bytes match the configured secret, and a production secret should therefore be a
     * whitespace-free random value supplied by the deployment secret manager.</p>
     *
     * @param token configured or supplied header value
     * @return original token, or an empty string when absent or blank
     */
    private String normalizeToken(String token) {
        return token == null || token.isBlank() ? "" : token;
    }

    /**
     * Compares UTF-8 bytes using the JDK primitive intended for secret material comparison.
     *
     * @param expectedToken secret injected into Agent Runtime deployment configuration
     * @param suppliedToken header injected by Gateway
     * @return whether both tokens have identical bytes
     */
    private boolean constantTimeEquals(String expectedToken, String suppliedToken) {
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Returns a generic failure without revealing which part of credential validation failed.
     *
     * @param response servlet response to stop before controller invocation
     * @throws IOException when the servlet container cannot write the status response
     */
    private void rejectWithoutDisclosingCredentialDetails(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Agent Runtime control-plane authentication is required.");
    }
}
