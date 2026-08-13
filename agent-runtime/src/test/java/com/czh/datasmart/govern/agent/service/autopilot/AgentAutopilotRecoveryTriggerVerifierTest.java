/**
 * @Author : Cui
 * @Date: 2026/08/11 22:10
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryTriggerVerifierTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentSessionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionStore;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that an untrusted recovery Kafka event is accepted only after its
 * root session, root run, authorization snapshot, and active delegation all
 * describe the same security boundary.
 */
class AgentAutopilotRecoveryTriggerVerifierTest {

    private static final String ROOT_SESSION_ID = "session-1";
    private static final String ROOT_RUN_ID = "run-1";
    private static final Long TENANT_ID = 10L;
    private static final Long APPLICATION_ID = 20L;
    private static final Long PROJECT_ID = 30L;
    private static final String USER_ID = "user-1";
    private static final String AGENT_ID = "master-agent";
    private static final String DELEGATION_ID = "delegation-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * A fully consistent event must produce a trusted trigger containing the
     * server-loaded session, root run, and typed authorization snapshot. This
     * protects the normal recovery path from accidentally becoming a test that
     * only proves rejection cases.
     */
    @Test
    void shouldAcceptEventBoundToActiveRootSessionRunAndPersistedSnapshot() throws Exception {
        Fixture fixture = fixture();

        AgentAutopilotVerifiedRecoveryTrigger verified = fixture.verifier().verify(fixture.event());

        assertThat(verified.session()).isSameAs(fixture.session());
        assertThat(verified.rootRun()).isSameAs(fixture.rootRun());
        assertThat(verified.authorization().policyId()).isEqualTo("policy-1");
        assertThat(verified.authorization().rootSessionId()).isEqualTo(ROOT_SESSION_ID);
        assertThat(verified.authorization().rootRunId()).isEqualTo(ROOT_RUN_ID);
        assertThat(verified.deadlineAt()).isAfter(verified.recoveryStartedAt());
    }

    /**
     * The event snapshot remains structurally valid in this scenario, but its
     * digest is replaced. The verifier must recompute the digest instead of
     * trusting the Kafka field, so an attacker cannot attach a forged digest to
     * an otherwise plausible payload.
     */
    @Test
    void shouldRejectEventWithTamperedAuthorizationSnapshotDigest() throws Exception {
        Fixture fixture = fixture();
        AgentAutopilotRecoveryTriggerEvent tampered = event(
                APPLICATION_ID,
                PROJECT_ID,
                USER_ID,
                fixture.event().authorizationSnapshot(),
                "sha256:" + "0".repeat(64));

        assertThatThrownBy(() -> fixture.verifier().verify(tampered))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("AUTOPILOT_AUTHORIZATION_SNAPSHOT_DIGEST_MISMATCH");
    }

    /**
     * A delegation can be revoked after the user approved the root run but
     * before Kafka delivers a recovery event. Re-loading the current delegation
     * must therefore block the event instead of treating the old snapshot as a
     * permanent execution grant.
     */
    @Test
    void shouldRejectEventWhenDelegationWasRevokedAfterAuthorization() throws Exception {
        Fixture fixture = fixture();
        fixture.delegation().revoke();

        assertThatThrownBy(() -> fixture.verifier().verify(fixture.event()))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("AUTOPILOT_DELEGATION_INACTIVE");
    }

    /**
     * A recovery event cannot move a root session into another project, even
     * when the root run and authorization snapshot still exist. Project scope
     * is checked against the freshly loaded session and delegation before any
     * downstream recovery work is allowed.
     */
    @Test
    void shouldRejectEventWhenProjectDoesNotMatchRootSession() throws Exception {
        Fixture fixture = fixture();
        AgentAutopilotRecoveryTriggerEvent crossProject = event(
                APPLICATION_ID,
                PROJECT_ID + 1,
                USER_ID,
                fixture.event().authorizationSnapshot(),
                fixture.event().authorizationSnapshotDigest());

        assertThatThrownBy(() -> fixture.verifier().verify(crossProject))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("AUTOPILOT_SESSION_SCOPE_MISMATCH");
    }

    /**
     * The application boundary is independent from the project boundary. This
     * regression proves that a session identifier from one application cannot
     * be replayed through an event that merely claims a different application.
     */
    @Test
    void shouldRejectEventWhenApplicationDoesNotMatchRootSession() throws Exception {
        Fixture fixture = fixture();
        AgentAutopilotRecoveryTriggerEvent crossApplication = event(
                APPLICATION_ID + 1,
                PROJECT_ID,
                USER_ID,
                fixture.event().authorizationSnapshot(),
                fixture.event().authorizationSnapshotDigest());

        assertThatThrownBy(() -> fixture.verifier().verify(crossApplication))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("AUTOPILOT_SESSION_SCOPE_MISMATCH");
    }

    /**
     * The user who issued the delegation remains part of the authorization
     * boundary even when the session and event use the same agent. Changing only
     * the event user must fail closed, preventing one user's recovery message
     * from being consumed under another user's delegation.
     */
    @Test
    void shouldRejectEventWhenUserDoesNotMatchDelegation() throws Exception {
        Fixture fixture = fixture();
        AgentAutopilotRecoveryTriggerEvent crossUser = event(
                APPLICATION_ID,
                PROJECT_ID,
                "user-2",
                fixture.event().authorizationSnapshot(),
                fixture.event().authorizationSnapshotDigest());

        assertThatThrownBy(() -> fixture.verifier().verify(crossUser))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("AUTOPILOT_SESSION_SCOPE_MISMATCH");
    }

    /**
     * Builds the smallest realistic persisted aggregate for this verifier. The
     * session store is mocked only at its boundary; using real session, run,
     * delegation, and snapshot objects keeps this fixture aligned with the
     * production data contract and lets the test exercise the full verification
     * chain rather than Mockito return values alone.
     */
    private Fixture fixture() throws JsonProcessingException {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LocalDateTime localNow = LocalDateTime.now();
        AgentDelegationRecord delegation = new AgentDelegationRecord(
                DELEGATION_ID,
                AGENT_ID,
                USER_ID,
                TENANT_ID,
                PROJECT_ID,
                List.of("sync.execution.failed-objects.retry"),
                List.of("RETRY_EXECUTION"),
                List.of("project:" + PROJECT_ID),
                AgentDelegationRecord.ACTIVE,
                localNow.minusMinutes(1),
                localNow.plusDays(1),
                null,
                localNow);
        AgentAutopilotAuthorizationSnapshot authorization = new AgentAutopilotAuthorizationSnapshot(
                "policy-1",
                "datasmart.autopilot.authorization.v1",
                "ACTIVE",
                ROOT_SESSION_ID,
                ROOT_RUN_ID,
                TENANT_ID,
                APPLICATION_ID,
                PROJECT_ID,
                USER_ID,
                USER_ID,
                AGENT_ID,
                DELEGATION_ID,
                5,
                120,
                "LOW",
                List.of("RETRY_EXECUTION"),
                List.of("CHANGE_SCHEMA"),
                now.minusMinutes(1),
                now.plusHours(2),
                "sha256:" + "d".repeat(64));
        Map<String, Object> persistedSnapshot = new LinkedHashMap<>(authorization.toMap());
        AgentRunRecord rootRun = new AgentRunRecord(
                ROOT_RUN_ID,
                ROOT_SESSION_ID,
                AgentRunState.PLANNING,
                "DATA_SYNC_RECOVERY",
                "recover failed objects",
                false,
                true,
                List.of(),
                Map.of("autopilotAuthorization", persistedSnapshot),
                localNow,
                "authorized");
        AgentSessionRecord session = new AgentSessionRecord(
                ROOT_SESSION_ID,
                AGENT_ID,
                TENANT_ID,
                PROJECT_ID,
                null,
                USER_ID,
                "PROJECT_OWNER",
                "USER",
                PROJECT_ID + ":PROJECT_OWNER",
                "WEB",
                "recover failed objects",
                WorkspaceIsolationLevel.PROJECT,
                "workspace-30",
                AgentSessionState.ACTIVE,
                delegation,
                false,
                null,
                localNow,
                localNow,
                localNow,
                List.<AgentToolBindingRecord>of(),
                List.of(rootRun),
                List.of());
        session.bindApplicationId(APPLICATION_ID);

        Map<String, Object> eventSnapshot = new LinkedHashMap<>(persistedSnapshot);
        AgentAutopilotRecoveryTriggerEvent event = event(
                APPLICATION_ID,
                PROJECT_ID,
                USER_ID,
                eventSnapshot,
                snapshotDigest(eventSnapshot));
        AgentSessionStore sessionStore = mock(AgentSessionStore.class);
        when(sessionStore.findById(ROOT_SESSION_ID)).thenReturn(Optional.of(session));
        return new Fixture(
                new AgentAutopilotRecoveryTriggerVerifier(sessionStore, objectMapper),
                session,
                rootRun,
                delegation,
                event);
    }

    /**
     * Creates an event variation while preserving every unrelated field from
     * the trusted baseline. Keeping the helper narrow makes each rejection test
     * explain exactly which event-side identity value changed and why the
     * verifier must reject it.
     */
    private AgentAutopilotRecoveryTriggerEvent event(Long applicationId,
                                                      Long projectId,
                                                      String userId,
                                                      Map<String, Object> authorizationSnapshot,
                                                      String authorizationSnapshotDigest) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AgentAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1",
                "event-1",
                ROOT_SESSION_ID,
                ROOT_RUN_ID,
                TENANT_ID,
                applicationId,
                projectId,
                userId,
                USER_ID,
                AGENT_ID,
                DELEGATION_ID,
                31L,
                40L,
                41L,
                1,
                5,
                now.plusHours(1).toString(),
                "a".repeat(64),
                0,
                null,
                List.of("OBJECT_TRANSFER_FAILED"),
                authorizationSnapshot,
                authorizationSnapshotDigest,
                now.toString());
    }

    /**
     * Recreates the verifier's digest input: Jackson serializes the exact event
     * snapshot map, then SHA-256 protects those serialized bytes. The test uses
     * the same deterministic operation only to construct a valid producer-side
     * baseline; tampering tests deliberately replace the returned value.
     */
    private String snapshotDigest(Map<String, Object> authorizationSnapshot) throws JsonProcessingException {
        try {
            String json = objectMapper.writeValueAsString(authorizationSnapshot);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not support SHA-256", exception);
        }
    }

    /**
     * Holds the verified aggregate and its event together so every test starts
     * from one coherent persistence state. This avoids hidden coupling between
     * tests while still making a targeted mutation, such as revocation, easy to
     * read at the call site.
     */
    private record Fixture(
            AgentAutopilotRecoveryTriggerVerifier verifier,
            AgentSessionRecord session,
            AgentRunRecord rootRun,
            AgentDelegationRecord delegation,
            AgentAutopilotRecoveryTriggerEvent event) {
    }
}
