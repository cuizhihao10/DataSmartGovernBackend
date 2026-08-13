/**
 * @Author : Cui
 * @Date: 2026/08/12 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeControlPlaneIngressDeploymentContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deployment-level regression checks for the Agent Runtime ingress guard.
 *
 * <p>Unit tests prove the Java filter decision. This companion test proves the two configuration
 * details that make that decision meaningful in Compose: the feature is enabled with a secret supplied
 * by environment, and the direct host port is loopback-only. It does not expose or assert a real
 * secret value.</p>
 */
class AgentRuntimeControlPlaneIngressDeploymentContractTest {

    /**
     * Verifies the source profile is safe for isolated tests while Compose explicitly closes the public ingress gap.
     */
    @Test
    void shouldKeepSourceDefaultDisabledAndEnableLoopbackOnlyComposeDeployment() throws IOException {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"))
                .replace("\r\n", "\n");
        String composeYaml = Files.readString(Path.of("..", "docker-compose.application.yml"))
                .replace("\r\n", "\n");
        String agentRuntimeService = composeService(composeYaml, "agent-runtime", "observability");
        String gatewayService = composeService(composeYaml, "gateway", "frontend");

        assertThat(applicationYaml)
                .contains("control-plane-ingress:")
                .contains("enabled: ${DATASMART_AGENT_RUNTIME_CONTROL_PLANE_INGRESS_ENABLED:false}")
                .contains("shared-token: ${DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN:}");
        assertThat(agentRuntimeService)
                .contains("DATASMART_AGENT_RUNTIME_CONTROL_PLANE_INGRESS_ENABLED: \"true\"")
                .contains("- \"127.0.0.1:8091:8091\"");
        assertThat(gatewayService)
                .contains("DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN:");
    }

    /**
     * Extracts one top-level Compose service block without assuming a fixed number of lines or comments.
     *
     * @param composeYaml complete Compose file
     * @param serviceName service whose settings are required by the ingress contract
     * @param nextServiceName next top-level service used as the extraction boundary
     * @return YAML text belonging only to the requested service
     */
    private String composeService(String composeYaml, String serviceName, String nextServiceName) {
        String serviceMarker = "  " + serviceName + ":\n";
        String nextServiceMarker = "\n  " + nextServiceName + ":\n";
        int start = composeYaml.indexOf(serviceMarker);
        int end = composeYaml.indexOf(nextServiceMarker, start + serviceMarker.length());

        assertThat(start).isGreaterThanOrEqualTo(0);
        return composeYaml.substring(start, end < 0 ? composeYaml.length() : end);
    }
}
