/**
 * @Author : Cui
 * @Date: 2026/08/11 22:20
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryDataSyncClientTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.service.tool.AgentToolDownstreamHttpSupport;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the dedicated data-sync callback used before an Autopilot Kafka trigger is acknowledged.
 *
 * <p>These tests use Spring's in-memory HTTP server rather than a data-sync application. That keeps the scope on the
 * client contract: fixed route, fixed low-sensitive body, no user delegation headers, and strict platform-envelope
 * validation. No Kafka broker, database, or unverified session is required to prove those boundaries.</p>
 */
class AgentAutopilotRecoveryDataSyncClientTest {

    /**
     * A parsed trigger result must use the fixed callback route and only the service-to-service authentication path.
     *
     * <p>The assertions intentionally reject actor and delegation headers. A verifier rejection can precede a
     * trustworthy session or delegation, and result reporting must not manufacture or forward either one merely to
     * close a Kafka receipt.</p>
     */
    @Test
    void shouldRecordTriggerResultWithFixedInternalContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        AgentAutopilotRecoveryExecutionResult result = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "ATTENTION_REQUIRED", "RECOVERY_REQUIRES_REVIEW", 81L, 41L,
                "SEARCH", "RAG", 2, "sha256:" + "c".repeat(64));

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/triggers/event-1/results"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(PlatformContextHeaders.ACTOR_ID))
                .andExpect(headerDoesNotExist(PlatformContextHeaders.AGENT_DELEGATION_ID))
                .andExpect(jsonPath("$.status").value("ATTENTION_REQUIRED"))
                .andExpect(jsonPath("$.reasonCode").value("RECOVERY_REQUIRES_REVIEW"))
                .andExpect(jsonPath("$.caseId").value(81))
                .andExpect(jsonPath("$.currentExecutionId").value(41))
                .andExpect(jsonPath("$.retrievalDecision").value("SEARCH"))
                .andExpect(jsonPath("$.retrievalStrategy").value("RAG"))
                .andExpect(jsonPath("$.retrievalEvidenceCount").value(2))
                .andExpect(jsonPath("$.retrievalEvidenceDigest").value("sha256:" + "c".repeat(64)))
                .andRespond(withSuccess(
                        "{\"code\":0,\"reason\":\"SUCCESS\",\"message\":\"success\",\"data\":{\"recorded\":true}}",
                        MediaType.APPLICATION_JSON));

        client.recordTriggerResult(result);

        server.verify();
    }

    /**
     * An HTTP 200 without the platform success code is a broken integration contract and must remain retryable.
     *
     * <p>Returning normally here would let Kafka commit a result that data-sync may not have persisted. The client
     * therefore uses {@link IllegalStateException}, which the listener-specific retry topic will redeliver before
     * eventually routing the record to its DLT.</p>
     */
    @Test
    void shouldRejectInvalidTriggerResultEnvelopeAsTechnicalFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        AgentAutopilotRecoveryExecutionResult result = new AgentAutopilotRecoveryExecutionResult(
                "event-2", "REJECTED", "AUTOPILOT_DELEGATION_INACTIVE", null, 41L);

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/triggers/event-2/results"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.recordTriggerResult(result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOPILOT_DATA_SYNC_TRIGGER_RESULT_ENVELOPE_INVALID");

        server.verify();
    }

    /**
     * DLT convergence must use one fixed internal route and must not forward represented-user delegation headers.
     *
     * <p>The DLT fact is transport exhaustion, not a new user-authorized tool action. data-sync therefore receives
     * only its configured service credential and the original execution identity, then reloads all case authority
     * from persistence.</p>
     */
    @Test
    void shouldRecordDeadLetterWithFixedInternalContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/triggers/event-3/dead-letter"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(PlatformContextHeaders.ACTOR_ID))
                .andExpect(headerDoesNotExist(PlatformContextHeaders.AGENT_DELEGATION_ID))
                .andExpect(jsonPath("$.currentExecutionId").value(43))
                .andRespond(withSuccess(
                        "{\"code\":0,\"reason\":\"SUCCESS\",\"message\":\"success\",\"data\":{}}",
                        MediaType.APPLICATION_JSON));

        client.recordTriggerDeadLettered("event-3", 43L);

        server.verify();
    }

    /**
     * The autonomous quarantine call must bind every side-effect fact to one fixed internal route and receipt.
     *
     * <p>This test intentionally uses a real HTTP request matcher rather than mocking the client method. It proves
     * that both represented-user and Agent delegation headers are retained, that model output cannot choose the URL
     * or reason, and that the returned receipt is accepted only when all task/execution/digest/fingerprint facts
     * match the request.</p>
     */
    @Test
    void shouldApplyAutonomousQuarantineWithFixedDigestBoundContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        String previewDigest = "e".repeat(64);
        String actionFingerprint = "f".repeat(64);
        AgentAutopilotRecoveryCaseView recoveryCase = new AgentAutopilotRecoveryCaseView(
                81L, 31L, 40L, 41L, "AUTO_APPROVED", 0L, 1, 5,
                "APPLY_QUARANTINE", null, "c".repeat(64), "d".repeat(64));
        AgentAutopilotRecoveryPlanResponse response = new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", "APPLY_QUARANTINE", "LOW", true,
                actionFingerprint, "a".repeat(64), 0.91d, true,
                Map.of(), Map.of(), "SKIP", "STRUCTURED_DIAGNOSTIC", Map.of(), true,
                "autopilot-recovery:event-1", "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY",
                Map.of());
        AgentAutopilotRecoveryQuarantinePreview preview =
                new AgentAutopilotRecoveryQuarantinePreview(
                        previewDigest, List.of(501L, 502L), "agent-runtime://run-1/preview");

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/cases/81/quarantine/apply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(PlatformContextHeaders.ACTOR_ID, "14"))
                .andExpect(header(PlatformContextHeaders.ACTOR_ROLE, "PROJECT_ADMIN"))
                .andExpect(header(PlatformContextHeaders.AGENT_ID, "main-agent"))
                .andExpect(header(PlatformContextHeaders.AGENT_DELEGATION_ID, "delegation-1"))
                .andExpect(jsonPath("$.expectedVersion").value(0))
                .andExpect(jsonPath("$.tenantId").value(11))
                .andExpect(jsonPath("$.projectId").value(13))
                .andExpect(jsonPath("$.syncTaskId").value(31))
                .andExpect(jsonPath("$.executionId").value(41))
                .andExpect(jsonPath("$.cycle").value(1))
                .andExpect(jsonPath("$.authorizationDigest").value("c".repeat(64)))
                .andExpect(jsonPath("$.policyDigest").value("d".repeat(64)))
                .andExpect(jsonPath("$.previewDigest").value(previewDigest))
                .andExpect(jsonPath("$.selectedSampleIds[0]").value(501))
                .andExpect(jsonPath("$.selectedSampleIds[1]").value(502))
                .andExpect(jsonPath("$.actionFingerprint").value(actionFingerprint))
                .andExpect(jsonPath("$.receiptId").value("event-1:quarantine-apply"))
                .andRespond(withSuccess("""
                        {"code":0,"reason":"SUCCESS","message":"success","data":{
                          "receiptId":"event-1:quarantine-apply","caseId":81,"syncTaskId":31,
                          "executionId":41,"selectedCount":2,"affectedCount":2,
                          "operationState":"APPLIED","receiptState":"COMPLETED",
                          "previewDigest":"%s","actionFingerprint":"%s"
                        }}
                        """.formatted(previewDigest, actionFingerprint), MediaType.APPLICATION_JSON));

        AgentAutopilotRecoveryQuarantineApplyReceipt receipt =
                client.applyAutonomousQuarantine(trigger, recoveryCase, response, preview);

        assertThat(receipt.isDurablyApplied()).isTrue();
        server.verify();
    }

    /**
     * The failed-object retry response must provide the exact task/execution and requeued lifecycle states.
     *
     * <p>These fields become the only resource locator admitted to post-recovery Specialist verification. A Map with
     * a missing identifier can no longer fall back to the old Kafka event and masquerade as a real receipt.</p>
     */
    @Test
    void shouldReturnScopeBoundRetryReceipt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);

        server.expect(once(), requestTo(
                        "http://data-sync.test/sync-tasks/31/executions/41/objects/retry"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.idempotencyKey").value("event-1"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"taskId":31,"executionId":41,"retryObjectCount":2,
                         "executionState":"QUEUED","taskState":"RETRYING"}}
                        """, MediaType.APPLICATION_JSON));

        AgentAutopilotRecoveryRetryReceipt receipt = client.retryFailedObjects(trigger());

        assertThat(receipt.matchesRequeuedScope(trigger().event())).isTrue();
        server.verify();
    }

    /** A malformed or cross-scope retry response remains a retryable integration failure. */
    @Test
    void shouldRejectCrossScopeRetryReceipt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        server.expect(once(), requestTo(
                        "http://data-sync.test/sync-tasks/31/executions/41/objects/retry"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"taskId":999,"executionId":41,"retryObjectCount":2,
                         "executionState":"QUEUED","taskState":"RETRYING"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.retryFailedObjects(trigger()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOPILOT_RETRY_RECEIPT_SCOPE_OR_STATE_INVALID");
        server.verify();
    }

    /** Creates a dual-principal trigger suitable for testing governed data-sync writes. */
    private AgentAutopilotVerifiedRecoveryTrigger trigger() {
        AgentAutopilotRecoveryTriggerEvent event = new AgentAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1", "event-1", "session-1", "run-1",
                11L, 12L, 13L, "14", "14", "main-agent", "delegation-1",
                31L, 40L, 41L, 1, 5, "2099-01-01T00:00:00Z", "a".repeat(64),
                0, null, List.of("OBJECT_TRANSFER_FAILED"), Map.of(),
                "sha256:" + "b".repeat(64), "2026-08-12T00:00:00Z");
        com.czh.datasmart.govern.agent.service.session.AgentSessionRecord session = mock(
                com.czh.datasmart.govern.agent.service.session.AgentSessionRecord.class);
        com.czh.datasmart.govern.agent.service.session.AgentRunRecord run = mock(
                com.czh.datasmart.govern.agent.service.session.AgentRunRecord.class);
        com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord delegation = mock(
                com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord.class);
        when(session.getTenantId()).thenReturn(11L);
        when(session.getApplicationId()).thenReturn(12L);
        when(session.getProjectId()).thenReturn(13L);
        when(session.getActorId()).thenReturn("14");
        when(session.getActorRole()).thenReturn("PROJECT_ADMIN");
        when(session.getActorType()).thenReturn("USER");
        when(session.getAgentId()).thenReturn("main-agent");
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getDelegation()).thenReturn(delegation);
        when(delegation.getDelegationId()).thenReturn("delegation-1");
        when(run.getRunId()).thenReturn("run-1");
        return new AgentAutopilotVerifiedRecoveryTrigger(
                event, session, run, mock(AgentAutopilotAuthorizationSnapshot.class),
                OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-12T00:00:00Z"));
    }

    /**
     * Creates a client with only the controlled data-sync base URL required by these transport tests.
     *
     * <p>The support object is real so the test exercises the same URL lookup and service-token method that production
     * uses. It has no configured user session, which demonstrates that the result callback does not need delegation
     * state to be constructed.</p>
     *
     * @param builder RestClient builder intercepted by the current MockRestServiceServer
     * @return client under test with a fixed local data-sync base URL
     */
    private AgentAutopilotRecoveryDataSyncClient client(RestClient.Builder builder) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("data-sync", "http://data-sync.test");
        return new AgentAutopilotRecoveryDataSyncClient(
                builder, new AgentToolDownstreamHttpSupport(properties));
    }
}
