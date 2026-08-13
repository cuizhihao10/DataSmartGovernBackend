/**
 * @Author : Cui
 * @Date: 2026/08/11 10:00
 * @Description DataSmart Govern Backend - PermissionAdminAgentToolActionApprovalClientTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PermissionAdminAgentToolActionApprovalClientTest {

    @Test
    void shouldFailClosedBeforeHttpCallWhenApprovalScopeIsIncomplete() {
        RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);
        PermissionAdminAgentToolActionApprovalClient client = new PermissionAdminAgentToolActionApprovalClient(
                new AgentAsyncToolWorkerProperties(),
                restClientBuilder
        );
        AgentToolActionControlledApprovalEvaluationRequest request = request(null);

        assertThrows(IllegalArgumentException.class, () -> client.evaluate(request));

        verifyNoInteractions(restClientBuilder);
    }

    private AgentToolActionControlledApprovalEvaluationRequest request(String actionFingerprint) {
        return new AgentToolActionControlledApprovalEvaluationRequest(
                "approval:human-001",
                10L,
                7L,
                20L,
                "user-1001",
                "1001",
                "agent-recovery-001",
                "session-proposal",
                "run-proposal",
                "delegation:run-proposal:001",
                "taoc-consume-001",
                "datasource.metadata.read",
                actionFingerprint,
                "tool-readiness-policy.v1",
                "trace-approval-scope-test"
        );
    }
}
