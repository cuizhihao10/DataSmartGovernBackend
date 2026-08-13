/**
 * @Author : Cui
 * @Date: 2026/08/11 21:35
 * @Description DataSmart Govern Backend - DataSyncAutopilotRecoveryControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryTriggerConsumerResultRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAutopilotRecoveryDeadLetterRequest;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryCaseService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryAutonomousQuarantineService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryDeadLetterService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultCommand;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerConsumerResultView;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryConsumerResultStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused HTTP-boundary tests for the fixed Autopilot consumer-result callback contract.
 */
class DataSyncAutopilotRecoveryControllerTest {

    /** Test-only credential used to exercise the same configured-token branch as a deployment. */
    private static final String TEST_INTERNAL_TOKEN = "unit-test-internal-token";

    /**
     * The route must normalize documented enum text and delegate only the narrow result facts to the service.
     *
     * <p>The test passes the configured token value when one exists, so it remains valid in local environments
     * where internal-token protection is intentionally enabled. It also proves that response data is the restricted
     * view returned by the durable service rather than an echoed request or outbox payload.</p>
     */
    @Test
    void shouldRecordTheFixedTriggerConsumerResultContract() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        DataSyncAutopilotRecoveryController controller =
                new DataSyncAutopilotRecoveryController(
                        caseService, consumerResultService, quarantineService, deadLetterService, TEST_INTERNAL_TOKEN);
        SyncAutopilotRecoveryTriggerConsumerResultView expected =
                new SyncAutopilotRecoveryTriggerConsumerResultView(
                        "autopilot-trigger:" + "a".repeat(64),
                        1001L,
                        "RECOVERY_STARTED",
                        "AUTOPILOT_FAILED_OBJECTS_REQUEUED",
                        77L,
                        "b".repeat(64),
                        "SEARCH",
                        "RAG",
                        2,
                        "sha256:" + "c".repeat(64),
                        LocalDateTime.of(2026, 8, 11, 21, 35));
        when(consumerResultService.recordConsumerResult(eq(expected.eventId()), any())).thenReturn(expected);

        var response = controller.recordTriggerConsumerResult(
                expected.eventId(),
                new SyncAutopilotRecoveryTriggerConsumerResultRequest(
                        "recovery-started",
                        "autopilot-failed-objects-requeued",
                        77L,
                        1001L,
                        "search",
                        "rag",
                        2,
                        "sha256:" + "C".repeat(64)),
                TEST_INTERNAL_TOKEN,
                "trace-1");

        ArgumentCaptor<SyncAutopilotRecoveryTriggerConsumerResultCommand> command =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerConsumerResultCommand.class);
        verify(consumerResultService).recordConsumerResult(eq(expected.eventId()), command.capture());
        assertThat(command.getValue().status())
                .isEqualTo(SyncAutopilotRecoveryConsumerResultStatus.RECOVERY_STARTED);
        assertThat(command.getValue().reasonCode()).isEqualTo("AUTOPILOT_FAILED_OBJECTS_REQUEUED");
        assertThat(command.getValue().caseId()).isEqualTo(77L);
        assertThat(command.getValue().currentExecutionId()).isEqualTo(1001L);
        assertThat(command.getValue().retrievalDecision()).isEqualTo("SEARCH");
        assertThat(command.getValue().retrievalStrategy()).isEqualTo("RAG");
        assertThat(command.getValue().retrievalEvidenceCount()).isEqualTo(2);
        assertThat(command.getValue().retrievalEvidenceDigest()).isEqualTo("sha256:" + "c".repeat(64));
        assertThat(response.getData()).isEqualTo(expected);
        assertThat(response.getTraceId()).isEqualTo("trace-1");
    }

    /**
     * Free-form reason text must be rejected at the HTTP boundary before the durable service is invoked.
     */
    @Test
    void shouldRejectFreeFormConsumerReasonText() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        DataSyncAutopilotRecoveryController controller =
                new DataSyncAutopilotRecoveryController(
                        caseService, consumerResultService, quarantineService, deadLetterService, TEST_INTERNAL_TOKEN);

        assertThatThrownBy(() -> controller.recordTriggerConsumerResult(
                "autopilot-trigger:" + "a".repeat(64),
                new SyncAutopilotRecoveryTriggerConsumerResultRequest(
                        "RECOVERY_STARTED",
                        "the model said retry this database because timeout",
                        77L,
                        1001L,
                        null,
                        null,
                        null,
                        null),
                TEST_INTERNAL_TOKEN,
                "trace-2"))
                .isInstanceOf(PlatformBusinessException.class);
        verifyNoInteractions(consumerResultService);
    }

    /**
     * The DLT route must delegate only event and execution identity to the persistence-owned convergence service.
     */
    @Test
    void shouldRecordTheFixedDeadLetterContract() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        DataSyncAutopilotRecoveryController controller =
                new DataSyncAutopilotRecoveryController(
                        caseService, consumerResultService, quarantineService, deadLetterService, TEST_INTERNAL_TOKEN);
        SyncAutopilotRecoveryTriggerConsumerResultView expected =
                new SyncAutopilotRecoveryTriggerConsumerResultView(
                        "event-4", 1004L, "ATTENTION_REQUIRED",
                        "AUTOPILOT_TRIGGER_DEAD_LETTERED", 84L, "d".repeat(64),
                        null, null, null, null, LocalDateTime.of(2026, 8, 12, 12, 0));
        when(deadLetterService.recordDeadLettered("event-4", 1004L)).thenReturn(expected);

        var response = controller.recordTriggerDeadLetter(
                " event-4 ",
                new SyncAutopilotRecoveryDeadLetterRequest(1004L),
                TEST_INTERNAL_TOKEN,
                "trace-dlt");

        verify(deadLetterService).recordDeadLettered("event-4", 1004L);
        assertThat(response.getData()).isEqualTo(expected);
        assertThat(response.getTraceId()).isEqualTo("trace-dlt");
    }

    /**
     * A missing deployment credential must stop the request before any recovery service can mutate state.
     */
    @Test
    void shouldRejectAnInternalRouteWhenServiceTokenIsNotConfigured() {
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryAutonomousQuarantineService quarantineService =
                mock(SyncAutopilotRecoveryAutonomousQuarantineService.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        DataSyncAutopilotRecoveryController controller = new DataSyncAutopilotRecoveryController(
                caseService, consumerResultService, quarantineService, deadLetterService, " ");

        assertThatThrownBy(() -> controller.recordTriggerDeadLetter(
                "event-4", new SyncAutopilotRecoveryDeadLetterRequest(1004L), null, "trace-missing-token"))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("authentication is not configured");
        verifyNoInteractions(caseService, consumerResultService, quarantineService, deadLetterService);
    }

}
