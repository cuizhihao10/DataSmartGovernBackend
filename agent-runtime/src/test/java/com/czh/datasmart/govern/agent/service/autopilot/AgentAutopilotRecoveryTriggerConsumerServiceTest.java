/**
 * @Author : Cui
 * @Date: 2026/08/11 21:30
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryTriggerConsumerServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the different Kafka acknowledgement behavior for permanent rejection and retryable downstream failures.
 *
 * <p>The tests call the application service directly rather than booting a Kafka broker. A normal return lets the
 * listener acknowledge an offset, whereas an uncaught exception is what activates Spring Kafka retry handling. This
 * focused boundary test therefore makes the desired delivery behavior explicit without requiring infrastructure.</p>
 */
class AgentAutopilotRecoveryTriggerConsumerServiceTest {

    /**
     * A malformed JSON payload cannot be turned into an event on a later delivery, so it is a permanent poison message.
     *
     * <p>The service must return a stable rejection that lets the listener acknowledge the record. No verifier, Python,
     * or data-sync work may run because arbitrary invalid bytes must never cause an external side effect.</p>
     */
    @Test
    void shouldPermanentlyRejectMalformedJsonWithoutCallingDependencies() {
        Fixture fixture = fixture();

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume("{not-valid-json");

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_TRIGGER_JSON_INVALID");
        verifyNoInteractions(fixture.triggerVerifier, fixture.pythonClient, fixture.executionService,
                fixture.dataSyncClient);
    }

    /**
     * A verifier denial means the persisted session, run, or authorization facts do not grant this event execution rights.
     *
     * <p>This is a permanent business outcome, not an availability incident. The test proves the service returns
     * {@code REJECTED} and, crucially, stops before invoking the Python planner or execution service.</p>
     */
    @Test
    void shouldRejectAuthorizationFailureWithoutCallingPython() {
        Fixture fixture = fixture();
        PlatformBusinessException rejection = new PlatformBusinessException(
                PlatformErrorCode.FORBIDDEN, "AUTOPILOT_DELEGATION_INACTIVE");
        when(fixture.triggerVerifier.verify(any())).thenThrow(rejection);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume(validPayload());

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_DELEGATION_INACTIVE");
        verify(fixture.triggerVerifier).verify(any());
        verify(fixture.dataSyncClient).recordTriggerResult(result);
        verifyNoInteractions(fixture.pythonClient, fixture.executionService);
    }

    /**
     * A Python planner failure after a valid verifier result means durable processing has not finished and Kafka must retry.
     *
     * <p>The failure deliberately uses {@link PlatformBusinessException}: the previous broad catch block incorrectly
     * treated this downstream error exactly like an authorization rejection. Asserting the same object escapes confirms
     * that the consumer service cannot acknowledge the message prematurely.</p>
     */
    @Test
    void shouldPropagatePythonInfrastructureFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        PlatformBusinessException failure = new PlatformBusinessException(
                PlatformErrorCode.BUSINESS_STATE_CONFLICT, "PYTHON_AUTOPILOT_PLANNER_UNAVAILABLE");
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service.consume(validPayload())).isSameAs(failure);

        verify(fixture.triggerVerifier).verify(any());
        verify(fixture.pythonClient).plan(verified);
        verify(fixture.executionService, never()).execute(any(), any());
        verifyNoInteractions(fixture.dataSyncClient);
        verify(fixture.metrics).recordPlanningFailed();
    }

    /**
     * A status outside the Python planning protocol is a retryable contract failure even when a test double bypasses
     * the HTTP client validation.
     *
     * <p>The production client rejects this before returning, but the consumer keeps this defensive boundary so a
     * future client refactor cannot silently count an unknown planner state as success. Throwing after recording
     * {@code FAILED} leaves the Kafka offset unacknowledged and prevents an arbitrary state from reaching execution or
     * the durable result callback.</p>
     */
    @Test
    void shouldPropagateUnknownPlannerStatusForKafkaRetryWithoutCountingSuccess() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("UNRECOGNIZED");

        assertThatThrownBy(() -> fixture.service.consume(validPayload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PYTHON_AUTOPILOT_RECOVERY_PLANNER_STATUS_INVALID");

        verify(fixture.metrics).recordPlanningFailed();
        verify(fixture.metrics, never()).recordPlanningSucceeded();
        verify(fixture.executionService, never()).execute(any(), any());
        verifyNoInteractions(fixture.dataSyncClient);
    }

    /**
     * A Java or data-sync execution failure after Python returns a plan is also retryable rather than a permanent denial.
     *
     * <p>Network, persistence, and downstream response failures can occur at this stage. Propagating the exception lets
     * Spring Kafka redeliver the same event, while the downstream receipt and optimistic-lock design keep that retry
     * idempotent.</p>
     */
    @Test
    void shouldPropagateDownstreamInfrastructureFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        PlatformBusinessException failure = new PlatformBusinessException(
                PlatformErrorCode.BUSINESS_STATE_CONFLICT, "DATA_SYNC_AUTOPILOT_UNAVAILABLE");
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("CANDIDATE_READY");
        when(fixture.executionService.execute(verified, response)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service.consume(validPayload())).isSameAs(failure);

        verify(fixture.triggerVerifier).verify(any());
        verify(fixture.pythonClient).plan(verified);
        verify(fixture.executionService).execute(verified, response);
        verifyNoInteractions(fixture.dataSyncClient);
    }

    /**
     * A Python attention response is a durable terminal handling outcome, not an infrastructure exception.
     *
     * <p>The execution service turns the Python response into a low-sensitive result. The consumer must write that
     * result back before returning so data-sync can close the trigger receipt and Kafka can safely acknowledge it.</p>
     */
    @Test
    void shouldRecordPythonAttentionResultBeforeAcknowledging() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        AgentAutopilotRecoveryExecutionResult attention = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "ATTENTION_REQUIRED", "PYTHON_RECOVERY_REQUIRES_REVIEW", 71L, 41L);
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("ATTENTION_REQUIRED");
        when(fixture.executionService.execute(verified, response)).thenReturn(attention);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume(validPayload());

        assertThat(result).isEqualTo(attention);
        verify(fixture.dataSyncClient).recordTriggerResult(attention);
    }

    /**
     * A permanent evidence rejection becomes safe to acknowledge only after data-sync durably records the fixed result.
     *
     * <p>The execution service has already classified the evidence mismatch as {@code REJECTED}. This consumer test
     * verifies the second half of the boundary: it returns normally only after invoking the durable callback, which is
     * the signal that Spring Kafka may commit the current record instead of sending it to retry or DLT.</p>
     */
    @Test
    void shouldRecordEvidenceRejectionBeforeAcknowledging() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        AgentAutopilotRecoveryExecutionResult rejection = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "REJECTED", "AUTOPILOT_EVIDENCE_DIGEST_MISMATCH", null, 41L);
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("ATTENTION_REQUIRED");
        when(fixture.executionService.execute(verified, response)).thenReturn(rejection);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume(validPayload());

        assertThat(result).isEqualTo(rejection);
        verify(fixture.dataSyncClient).recordTriggerResult(rejection);
        verify(fixture.metrics).recordAttentionRequired();
    }

    /**
     * A result callback is part of durable processing, so its failure must activate Kafka retry instead of ACKing.
     *
     * <p>The test uses {@link IllegalStateException} because broken result envelopes and empty Python responses are
     * technical contracts. The same rule applies to a RestClient failure: no normal return is allowed until data-sync
     * has accepted the result callback.</p>
     */
    @Test
    void shouldPropagateResultWriteBackFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        AgentAutopilotRecoveryExecutionResult result = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "RECOVERY_STARTED", "AUTOPILOT_FAILED_OBJECTS_REQUEUED", 71L, 41L);
        IllegalStateException failure = new IllegalStateException("AUTOPILOT_DATA_SYNC_TRIGGER_RESULT_ENVELOPE_INVALID");
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("CANDIDATE_READY");
        when(fixture.executionService.execute(verified, response)).thenReturn(result);
        doThrow(failure).when(fixture.dataSyncClient).recordTriggerResult(result);

        assertThatThrownBy(() -> fixture.service.consume(validPayload())).isSameAs(failure);

        verify(fixture.dataSyncClient).recordTriggerResult(result);
    }

    /**
     * Supplies the smallest JSON document that Jackson can deserialize into the trigger record.
     *
     * <p>These tests isolate the consumer service's retry boundary, not the verifier's detailed contract checks. The
     * mocked verifier decides whether the sparse event is accepted or rejected, keeping each test focused on one rule.</p>
     */
    private String validPayload() {
        return "{\"eventId\":\"event-1\"}";
    }

    /**
     * Builds an isolated consumer service with real JSON parsing and mocked external collaborators.
     *
     * <p>Keeping {@link ObjectMapper} real verifies the malformed-JSON branch. Mocking authorization verification,
     * Python planning, and Java/data-sync execution avoids a Spring Context, an HTTP service, and Kafka while still
     * checking the exact exception propagation that determines acknowledgement behavior.</p>
     */
    private Fixture fixture() {
        AgentAutopilotRecoveryTriggerVerifier triggerVerifier =
                mock(AgentAutopilotRecoveryTriggerVerifier.class);
        AgentAutopilotRecoveryPythonClient pythonClient = mock(AgentAutopilotRecoveryPythonClient.class);
        AgentAutopilotRecoveryExecutionService executionService =
                mock(AgentAutopilotRecoveryExecutionService.class);
        AgentAutopilotRecoveryDataSyncClient dataSyncClient =
                mock(AgentAutopilotRecoveryDataSyncClient.class);
        AgentAutopilotRecoveryMetrics metrics = mock(AgentAutopilotRecoveryMetrics.class);
        return new Fixture(
                new AgentAutopilotRecoveryTriggerConsumerService(
                        new ObjectMapper(), triggerVerifier, pythonClient, executionService, dataSyncClient, metrics),
                triggerVerifier,
                pythonClient,
                executionService,
                dataSyncClient,
                metrics);
    }

    private record Fixture(
            AgentAutopilotRecoveryTriggerConsumerService service,
            AgentAutopilotRecoveryTriggerVerifier triggerVerifier,
            AgentAutopilotRecoveryPythonClient pythonClient,
            AgentAutopilotRecoveryExecutionService executionService,
            AgentAutopilotRecoveryDataSyncClient dataSyncClient,
            AgentAutopilotRecoveryMetrics metrics) {
    }
}
