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

    /**
     * 缺失或空的恢复动作字段必须回到最小默认集合，不能因平台目录新增动作而静默扩权。
     *
     * <p>这两个输入分别模拟 JSON 中省略 {@code allowedRecoveryActions} 与显式提交空数组的情况。它们都不是
     * 用户对高级恢复动作的同意，因此快照只能保留旧的重试和隔离动作；断言精确列表也能防止以后有人把完整
     * 白名单误用为默认值。</p>
     */
    @Test
    void keepsConservativeDefaultsWhenAllowedRecoveryActionsAreMissingOrEmpty() {
        List<String> expectedDefaults = List.of("RETRY_EXECUTION", "APPLY_QUARANTINE");
        AgentAutopilotAuthorizationSnapshot missingActions = service.authorize(
                session(),
                "run-default-missing",
                new AgentAutopilotPolicyRequest(
                        "AUTOPILOT", 5, 120, "LOW",
                        null,
                        List.of("CHANGE_SCHEMA"),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)));
        AgentAutopilotAuthorizationSnapshot emptyActions = service.authorize(
                session(),
                "run-default-empty",
                new AgentAutopilotPolicyRequest(
                        "AUTOPILOT", 5, 120, "LOW",
                        List.of(),
                        List.of("CHANGE_SCHEMA"),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)));

        assertEquals(expectedDefaults, AgentAutopilotAuthorizationService.DEFAULT_AUTOMATIC_ACTIONS);
        assertEquals(expectedDefaults, missingActions.allowedRecoveryActions());
        assertEquals(expectedDefaults, emptyActions.allowedRecoveryActions());
    }

    /**
     * 完整目录只能在用户显式列出全部业务码时进入授权快照。
     *
     * <p>该测试区分“平台不认识动作”和“平台认识但默认不授予动作”：八项业务码都在白名单内，所以明确请求时
     * 必须通过；上一个测试已经证明，省略同一字段时不会得到这六项高级动作。快照保留请求顺序，便于摘要和
     * 后续审计稳定复算。</p>
     */
    @Test
    void allowsCompleteAutomaticActionCatalogWhenExplicitlyRequested() {
        List<String> requestedActions = List.of(
                "RETRY_EXECUTION",
                "APPLY_QUARANTINE",
                "ROLLBACK_EXECUTION_POLICY",
                "TUNE_EXECUTION_POLICY",
                "REFRESH_METADATA",
                "RESUME_FROM_CHECKPOINT",
                "REPLAY_FAILED_SHARDS",
                "REPAIR_FIELD_MAPPING");

        AgentAutopilotAuthorizationSnapshot snapshot = service.authorize(
                session(),
                "run-explicit-catalog",
                new AgentAutopilotPolicyRequest(
                        "AUTOPILOT", 5, 120, "LOW",
                        requestedActions,
                        List.of("CHANGE_SCHEMA"),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)));

        assertEquals(requestedActions, snapshot.allowedRecoveryActions());
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
