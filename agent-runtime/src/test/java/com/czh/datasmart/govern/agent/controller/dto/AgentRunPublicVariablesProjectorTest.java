/**
 * @Author : Cui
 * @Date: 2026/08/19 20:50
 * @Description DataSmart Govern Backend - AgentRunPublicVariablesProjectorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Run 公开变量不会把内部授权和幂等事实原样回显。 */
class AgentRunPublicVariablesProjectorTest {

    @Test
    void shouldProjectAutopilotAuthorizationAndDropInternalFacts() {
        Map<String, Object> authorization = Map.ofEntries(
                Map.entry("policyId", "policy-1"),
                Map.entry("policyVersion", "v1"),
                Map.entry("executionMode", "AUTOPILOT"),
                Map.entry("state", "ACTIVE"),
                Map.entry("rootSessionId", "session-1"),
                Map.entry("rootRunId", "run-1"),
                Map.entry("tenantId", 10L),
                Map.entry("applicationId", 20L),
                Map.entry("projectId", 30L),
                Map.entry("userId", "user-1"),
                Map.entry("actorId", "actor-1"),
                Map.entry("agentId", "agent-1"),
                Map.entry("delegationId", "delegation-1"),
                Map.entry("maxRecoveryCycles", 3),
                Map.entry("maxTotalDurationMinutes", 60),
                Map.entry("maxAutomaticRiskLevel", "LOW"),
                Map.entry("allowedRecoveryActions", List.of("RETRY_FAILED_OBJECTS")),
                Map.entry("requireApprovalFor", List.of("DDL")),
                Map.entry("issuedAt", "2026-08-19T12:00:00Z"),
                Map.entry("expiresAt", "2026-08-19T13:00:00Z"),
                Map.entry("policyDigest", "private-policy-digest")
        );
        Map<String, Object> internal = Map.of(
                "source", "PYTHON_AI_RUNTIME_AGENT_PLAN",
                "confirmedExecutionClaim", Map.of("requestFingerprint", "internal-fingerprint"),
                "confirmedExecutionReceipt", Map.of("responseDigest", "internal-receipt"),
                "autopilotAuthorization", authorization,
                "toolPlans", List.of(Map.of(
                        "sequence", 1,
                        "toolCode", "sync.task.run",
                        "arguments", Map.of(
                                "syncMode", "FULL",
                                "jdbcUrl", "jdbc:postgresql://prod.example/secret",
                                "credentialRef", "secret://prod"
                        ),
                        "governanceHints", Map.of(
                                "sensitiveArgumentNames", List.of("jdbcUrl", "credentialRef"),
                                "approvalPolicy", "INTERNAL_ONLY"
                        ),
                        "parameterValidation", Map.of("missingFields", List.of("credentialRef"))
                ))
        );

        Map<String, Object> projected = AgentRunPublicVariablesProjector.project(internal);

        assertTrue(projected.containsKey("source"));
        assertFalse(projected.containsKey("confirmedExecutionClaim"));
        assertFalse(projected.containsKey("confirmedExecutionReceipt"));
        Object snapshot = projected.get("autopilotAuthorization");
        assertInstanceOf(AgentAutopilotSnapshotView.class, snapshot);
        assertFalse(snapshot.toString().contains("tenantId"));
        assertFalse(snapshot.toString().contains("private-policy-digest"));
        assertTrue(snapshot.toString().contains("RETRY_FAILED_OBJECTS"));
        String projectedText = projected.toString();
        assertTrue(projectedText.contains("syncMode"));
        assertTrue(projectedText.contains("sensitiveArgumentCount"));
        assertFalse(projectedText.contains("jdbc:postgresql://prod.example/secret"));
        assertFalse(projectedText.contains("secret://prod"));
        assertFalse(projectedText.contains("INTERNAL_ONLY"));
        assertFalse(projectedText.contains("missingFields"));
    }

    @Test
    void shouldFailClosedWhenDurableAuthorizationIsMalformed() {
        Map<String, Object> projected = AgentRunPublicVariablesProjector.project(Map.of(
                "autopilotAuthorization", Map.of("executionMode", "AUTOPILOT", "policyId", "broken")
        ));

        assertFalse(projected.containsKey("autopilotAuthorization"));
    }
}
