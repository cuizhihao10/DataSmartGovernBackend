/**
 * @Author : Cui
 * @Date: 2026/08/12 00:00
 * @Description DataSmart Govern Backend - GatewayAgentRuntimeIngressRouteContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the Gateway half of Agent Runtime ingress authentication.
 *
 * <p>The global Gateway contract filter already clears untrusted platform headers. This route-level
 * contract repeats removal of the internal-token header and injects the deployment secret immediately
 * before proxying to Agent Runtime. Testing the route text prevents a future route reorder or cleanup
 * from silently restoring the direct-header forgery path that the Agent Runtime servlet filter blocks.</p>
 */
class GatewayAgentRuntimeIngressRouteContractTest {

    /**
     * Ensures the generic Java Agent Runtime route removes a caller token before injecting its own secret.
     */
    @Test
    void shouldReplaceIncomingTokenOnlyOnAgentRuntimeGatewayRoute() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"))
                .replace("\r\n", "\n");
        int routeStart = yaml.indexOf("- id: agent-runtime\n");
        int nextRoute = yaml.indexOf("\n        - id:", routeStart + 1);
        String route = yaml.substring(routeStart, nextRoute < 0 ? yaml.length() : nextRoute);

        String removeFilter = "RemoveRequestHeader=X-DataSmart-Internal-Service-Token";
        String injectFilter = "SetRequestHeader=X-DataSmart-Internal-Service-Token, ${DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN:}";
        String rewriteFilter = "RewritePath=/api/agent/(?<segment>.*), /agent-runtime/$\\{segment}";

        assertThat(routeStart).isGreaterThanOrEqualTo(0);
        assertThat(route)
                .contains("Path=/api/agent/**")
                .contains(removeFilter)
                .contains(injectFilter)
                .contains(rewriteFilter);
        assertThat(route.indexOf(removeFilter)).isLessThan(route.indexOf(injectFilter));
        assertThat(route.indexOf(injectFilter)).isLessThan(route.indexOf(rewriteFilter));
    }
}
