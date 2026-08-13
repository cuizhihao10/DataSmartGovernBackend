package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.controller.dto.AgentAutopilotPolicyRequest;
import com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.agent.model.AgentSessionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAutopilotAuthorizationServiceTest {

    private final AgentAutopilotAuthorizationService service = new AgentAutopilotAuthorizationService();

    @Test
    void createsScopedAuthorizationWithBoundedDefaults() {
        AgentAutopilotAuthorizationSnapshot snapshot = service.authorize(
                session(),
                "run-1",
                new AgentAutopilotPolicyRequest(
                        "AUTOPILOT",
                        5,
                        120,
                        "LOW",
                        List.of("RETRY_EXECUTION"),
                        List.of("CHANGE_SCHEMA", "DELETE_DATA", "OVERWRITE_TARGET"),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(7)
                )
        );

        assertEquals("ACTIVE", snapshot.state());
        assertEquals(10L, snapshot.tenantId());
        assertEquals(20L, snapshot.applicationId());
        assertEquals(30L, snapshot.projectId());
        assertEquals("user-7", snapshot.userId());
        assertEquals("user-7", snapshot.actorId());
        assertEquals("agent-master", snapshot.agentId());
        assertEquals("delegation-1", snapshot.delegationId());
        assertEquals(5, snapshot.maxRecoveryCycles());
        assertEquals("LOW", snapshot.maxAutomaticRiskLevel());
        assertNotNull(snapshot.policyDigest());
        assertTrue(snapshot.toMap().containsKey("policyId"));
    }

    @Test
    void rejectsUnknownAutomaticActionInsteadOfLettingModelExpandAuthority() {
        AgentAutopilotPolicyRequest request = new AgentAutopilotPolicyRequest(
                "AUTOPILOT", 5, 120, "LOW",
                List.of("EXECUTE_ARBITRARY_SQL"),
                List.of("CHANGE_SCHEMA"),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

        assertThrows(IllegalArgumentException.class, () -> service.authorize(session(), "run-1", request));
    }

    @Test
    void rejectsKnownRecoveryActionUntilARealExecutorExists() {
        AgentAutopilotPolicyRequest request = new AgentAutopilotPolicyRequest(
                "AUTOPILOT", 5, 120, "LOW",
                List.of("RECONNECT_DATASOURCE"),
                List.of("CHANGE_SCHEMA"),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

        assertThrows(IllegalArgumentException.class, () -> service.authorize(session(), "run-1", request));
    }

    @Test
    void rejectsAutomaticRiskAboveLow() {
        AgentAutopilotPolicyRequest request = new AgentAutopilotPolicyRequest(
                "AUTOPILOT", 5, 120, "HIGH",
                List.of("RETRY_EXECUTION"),
                List.of("CHANGE_SCHEMA"),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

        assertThrows(IllegalArgumentException.class, () -> service.authorize(session(), "run-1", request));
    }

    private AgentSessionRecord session() {
        LocalDateTime now = LocalDateTime.now();
        AgentDelegationRecord delegation = new AgentDelegationRecord(
                "delegation-1", "agent-master", "user-7", 10L, 30L,
                List.of("sync.execution.failed-objects.retry"),
                List.of("RETRY_FAILED_OBJECTS"), List.of("project:30"),
                "ACTIVE", now, now.plusDays(7), null, now);
        AgentSessionRecord session = new AgentSessionRecord(
                "session-1", "agent-master", 10L, 30L, null, "user-7",
                "PROJECT_OWNER", "USER", "30:PROJECT_OWNER", "WEB",
                "create sync", WorkspaceIsolationLevel.PROJECT, "workspace-30", AgentSessionState.ACTIVE,
                delegation, false, null, now, now, now,
                List.<AgentToolBindingRecord>of(), List.of(), List.of());
        session.bindApplicationId(20L);
        return session;
    }
}
