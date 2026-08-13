/**
 * @Author : Cui
 * @Date: 2026/08/11 21:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerConsumerResultServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryTriggerOutboxMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryConsumerResultStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused durable-idempotency tests for Autopilot trigger consumer-result write-back.
 */
class SyncAutopilotRecoveryTriggerConsumerResultServiceTest {

    private static final String EVENT_ID = "autopilot-trigger:" + "a".repeat(64);
    private static final Long CURRENT_EXECUTION_ID = 1001L;

    /**
     * The first callback must use the mapper's conditional update and return the row it durably wrote.
     *
     * <p>The test deliberately prepares an empty original row followed by the reloaded completed row. This mirrors
     * the service's real database sequence and verifies that the callback digest comes from server-side command
     * facts rather than any request-provided value.</p>
     */
    @Test
    void shouldPersistFirstConsumerResultWithServerComputedDigest() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerConsumerResultCommand command = command(
                SyncAutopilotRecoveryConsumerResultStatus.RECOVERY_STARTED,
                "AUTOPILOT_FAILED_OBJECTS_REQUEUED",
                77L);
        SyncAutopilotRecoveryTriggerOutbox original = outbox();
        SyncAutopilotRecoveryTriggerOutbox persisted = completedOutbox(
                fixture.service(), command);
        when(fixture.mapper().selectByEventIdAndCurrentExecutionId(EVENT_ID, CURRENT_EXECUTION_ID))
                .thenReturn(original, persisted);
        when(fixture.mapper().markConsumerResultIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), eq(77L),
                any(), any(), any(), any()))
                .thenReturn(1);

        SyncAutopilotRecoveryTriggerConsumerResultView view =
                fixture.service().recordConsumerResult(EVENT_ID, command);

        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(fixture.mapper()).markConsumerResultIfAbsent(
                eq(EVENT_ID),
                eq(CURRENT_EXECUTION_ID),
                digest.capture(),
                eq(SyncAutopilotRecoveryConsumerResultStatus.RECOVERY_STARTED.name()),
                eq("AUTOPILOT_FAILED_OBJECTS_REQUEUED"),
                eq(77L),
                eq("SEARCH"),
                eq("RAG"),
                eq(2),
                eq("sha256:" + "c".repeat(64)));
        assertThat(digest.getValue()).isEqualTo(fixture.service().resultDigest(EVENT_ID, command));
        assertThat(view.status()).isEqualTo("RECOVERY_STARTED");
        assertThat(view.reasonCode()).isEqualTo("AUTOPILOT_FAILED_OBJECTS_REQUEUED");
        assertThat(view.caseId()).isEqualTo(77L);
        assertThat(view.retrievalDecision()).isEqualTo("SEARCH");
        assertThat(view.retrievalStrategy()).isEqualTo("RAG");
        assertThat(view.retrievalEvidenceCount()).isEqualTo(2);
        assertThat(view.retrievalEvidenceDigest()).isEqualTo("sha256:" + "c".repeat(64));
        assertThat(view.consumedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 21, 35));
    }

    /**
     * An equal callback must replay the original durable view without changing its first-consumed time.
     *
     * <p>This verifies the at-least-once Kafka and HTTP retry path: an existing digest and all matching compact
     * facts skip the conditional update entirely, so callers observe the same result instead of a second audit
     * write or a timestamp that would make idempotent delivery look like another consumption.</p>
     */
    @Test
    void shouldReplayAnIdenticalConsumerResultWithoutAnotherWrite() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerConsumerResultCommand command = command(
                SyncAutopilotRecoveryConsumerResultStatus.ATTENTION_REQUIRED,
                "AUTOPILOT_RETRY_DISPATCH_FAILED",
                77L);
        SyncAutopilotRecoveryTriggerOutbox persisted = completedOutbox(fixture.service(), command);
        when(fixture.mapper().selectByEventIdAndCurrentExecutionId(EVENT_ID, CURRENT_EXECUTION_ID))
                .thenReturn(persisted);

        SyncAutopilotRecoveryTriggerConsumerResultView view =
                fixture.service().recordConsumerResult(EVENT_ID, command);

        assertThat(view.resultDigest()).isEqualTo(fixture.service().resultDigest(EVENT_ID, command));
        assertThat(view.consumedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 21, 35));
        verify(fixture.mapper(), never()).markConsumerResultIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong(),
                any(), any(), any(), any());
    }

    /**
     * Reusing one event ID with a changed outcome is a conflict, never an update to the first result.
     *
     * <p>Changing only the reason code represents a different business fact even when event and execution IDs are
     * unchanged. The test proves that the service rejects it after inspecting the stored digest and never invokes
     * an overwrite-capable mapper operation.</p>
     */
    @Test
    void shouldFailClosedWhenTheSameEventCarriesDifferentFacts() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerConsumerResultCommand first = command(
                SyncAutopilotRecoveryConsumerResultStatus.RECOVERY_STARTED,
                "AUTOPILOT_FAILED_OBJECTS_REQUEUED",
                77L);
        SyncAutopilotRecoveryTriggerConsumerResultCommand changed = command(
                SyncAutopilotRecoveryConsumerResultStatus.RECOVERY_STARTED,
                "AUTOPILOT_RETRY_DISPATCH_FAILED",
                77L);
        when(fixture.mapper().selectByEventIdAndCurrentExecutionId(EVENT_ID, CURRENT_EXECUTION_ID))
                .thenReturn(completedOutbox(fixture.service(), first));

        assertThatThrownBy(() -> fixture.service().recordConsumerResult(EVENT_ID, changed))
                .isInstanceOf(PlatformBusinessException.class)
                .extracting(exception -> ((PlatformBusinessException) exception).getErrorCode())
                .isEqualTo(PlatformErrorCode.BUSINESS_STATE_CONFLICT);
        verify(fixture.mapper(), never()).markConsumerResultIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong(),
                any(), any(), any(), any());
    }

    /**
     * A conditional-update loser must still replay the winner when the facts are identical.
     *
     * <p>Two data-sync instances can read an empty row simultaneously. One succeeds with the conditional update,
     * while the other receives zero affected rows. The loser reloads and accepts only the exact same stored digest,
     * which makes this race safe without a JVM-local lock.</p>
     */
    @Test
    void shouldReplayTheConcurrentWinnerForEquivalentFacts() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerConsumerResultCommand command = command(
                SyncAutopilotRecoveryConsumerResultStatus.RECOVERED,
                "AUTOPILOT_RECOVERY_ALREADY_SUCCEEDED",
                77L);
        SyncAutopilotRecoveryTriggerOutbox original = outbox();
        SyncAutopilotRecoveryTriggerOutbox winner = completedOutbox(fixture.service(), command);
        when(fixture.mapper().selectByEventIdAndCurrentExecutionId(EVENT_ID, CURRENT_EXECUTION_ID))
                .thenReturn(original, winner);
        when(fixture.mapper().markConsumerResultIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), eq(77L),
                any(), any(), any(), any()))
                .thenReturn(0);

        SyncAutopilotRecoveryTriggerConsumerResultView view =
                fixture.service().recordConsumerResult(EVENT_ID, command);

        assertThat(view.status()).isEqualTo("RECOVERED");
        assertThat(view.reasonCode()).isEqualTo("AUTOPILOT_RECOVERY_ALREADY_SUCCEEDED");
        verify(fixture.mapper()).markConsumerResultIfAbsent(
                eq(EVENT_ID),
                eq(CURRENT_EXECUTION_ID),
                eq(fixture.service().resultDigest(EVENT_ID, command)),
                eq("RECOVERED"),
                eq("AUTOPILOT_RECOVERY_ALREADY_SUCCEEDED"),
                eq(77L),
                eq("SEARCH"),
                eq("RAG"),
                eq(2),
                eq("sha256:" + "c".repeat(64)));
    }

    /**
     * An event ID alone is not enough: the callback execution must match the original durable outbox event.
     */
    @Test
    void shouldRejectAnUnknownEventAndCurrentExecutionPair() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerConsumerResultCommand command = command(
                SyncAutopilotRecoveryConsumerResultStatus.REJECTED,
                "AUTOPILOT_TRIGGER_JSON_INVALID",
                null);
        when(fixture.mapper().selectByEventIdAndCurrentExecutionId(EVENT_ID, CURRENT_EXECUTION_ID))
                .thenReturn(null);

        assertThatThrownBy(() -> fixture.service().recordConsumerResult(EVENT_ID, command))
                .isInstanceOf(PlatformBusinessException.class)
                .extracting(exception -> ((PlatformBusinessException) exception).getErrorCode())
                .isEqualTo(PlatformErrorCode.BUSINESS_STATE_CONFLICT);
        verifyNoInteractionsExceptSelect(fixture.mapper());
    }

    /**
     * Builds a service fixture without Spring so the tests isolate conditional-result persistence behavior.
     */
    private Fixture fixture() {
        SyncAutopilotRecoveryTriggerOutboxMapper mapper =
                mock(SyncAutopilotRecoveryTriggerOutboxMapper.class);
        return new Fixture(new SyncAutopilotRecoveryTriggerConsumerResultService(mapper), mapper);
    }

    /**
     * Creates one valid compact callback command used by the idempotency scenarios.
     */
    private SyncAutopilotRecoveryTriggerConsumerResultCommand command(
            SyncAutopilotRecoveryConsumerResultStatus status,
            String reasonCode,
            Long caseId) {
        return new SyncAutopilotRecoveryTriggerConsumerResultCommand(
                status, reasonCode, caseId, CURRENT_EXECUTION_ID,
                "SEARCH", "RAG", 2, "sha256:" + "c".repeat(64));
    }

    /**
     * Creates the original V21 outbox row before any consumer has written a V22 result.
     */
    private SyncAutopilotRecoveryTriggerOutbox outbox() {
        SyncAutopilotRecoveryTriggerOutbox outbox = new SyncAutopilotRecoveryTriggerOutbox();
        outbox.setId(501L);
        outbox.setEventId(EVENT_ID);
        outbox.setTenantId(10L);
        outbox.setProjectId(20L);
        outbox.setSyncTaskId(31L);
        outbox.setRootExecutionId(1001L);
        outbox.setCurrentExecutionId(CURRENT_EXECUTION_ID);
        outbox.setCycle(1);
        outbox.setPayloadJson("{}");
        outbox.setOutboxState("DELIVERED");
        return outbox;
    }

    /**
     * Creates the fully populated row that the service would reload after an accepted callback.
     */
    private SyncAutopilotRecoveryTriggerOutbox completedOutbox(
            SyncAutopilotRecoveryTriggerConsumerResultService service,
            SyncAutopilotRecoveryTriggerConsumerResultCommand command) {
        SyncAutopilotRecoveryTriggerOutbox outbox = outbox();
        outbox.setConsumerResultDigest(service.resultDigest(EVENT_ID, command));
        outbox.setConsumerResultStatus(command.status().name());
        outbox.setConsumerResultReasonCode(command.reasonCode());
        outbox.setConsumerResultCaseId(command.caseId());
        outbox.setRetrievalDecision(command.retrievalDecision());
        outbox.setRetrievalStrategy(command.retrievalStrategy());
        outbox.setRetrievalEvidenceCount(command.retrievalEvidenceCount());
        outbox.setRetrievalEvidenceDigest(command.retrievalEvidenceDigest());
        outbox.setConsumedAt(LocalDateTime.of(2026, 8, 11, 21, 35));
        return outbox;
    }

    /**
     * Keeps the final assertion explicit: the unknown pair may issue its one select, but cannot write a result.
     */
    private void verifyNoInteractionsExceptSelect(SyncAutopilotRecoveryTriggerOutboxMapper mapper) {
        verify(mapper).selectByEventIdAndCurrentExecutionId(EVENT_ID, CURRENT_EXECUTION_ID);
        verify(mapper, never()).markConsumerResultIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong(),
                any(), any(), any(), any());
    }

    private record Fixture(
            SyncAutopilotRecoveryTriggerConsumerResultService service,
            SyncAutopilotRecoveryTriggerOutboxMapper mapper) {
    }
}
