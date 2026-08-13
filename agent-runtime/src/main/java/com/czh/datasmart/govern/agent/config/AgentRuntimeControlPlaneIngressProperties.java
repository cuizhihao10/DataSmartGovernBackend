/**
 * @Author : Cui
 * @Date: 2026/08/12 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeControlPlaneIngressProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deployment-time authentication settings for the externally reachable Agent Runtime control plane.
 *
 * <p>This is deliberately a small ingress proof, not a replacement for user authentication or
 * object-level authorization. Gateway first authenticates the user and rebuilds trusted tenant,
 * project and actor headers. It then injects the deployment secret defined here. Agent Runtime
 * accepts those rebuilt identity headers only after this filter proves that the request crossed
 * that Gateway boundary.</p>
 *
 * <p>The default remains disabled so focused source tests and a single-module learning startup do
 * not suddenly require a Docker secret. Compose and every production deployment must explicitly
 * enable it. When it is enabled but the configured secret is blank, protected requests fail closed;
 * an empty value never means "allow anonymous access".</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.agent-runtime.control-plane-ingress")
public class AgentRuntimeControlPlaneIngressProperties {

    /**
     * Enables the servlet ingress filter.
     *
     * <p>It is intentionally false in source configuration. Deployment manifests set it to true
     * only when the same secret is injected into both Gateway and Agent Runtime.</p>
     */
    private boolean enabled = false;

    /**
     * Shared deployment secret expected in {@code X-DataSmart-Internal-Service-Token}.
     *
     * <p>The value is read from environment or a secret manager, never from a browser request body,
     * database record, audit event or log line. It validates the Gateway-to-service hop only; it
     * does not grant a user permission by itself.</p>
     */
    private String sharedToken = "";

}
