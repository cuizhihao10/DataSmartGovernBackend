/**
 * @Author : Cui
 * @Date: 2026/08/12 12:00
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryDeadLetterServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryReceipt;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryReceiptMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryTriggerOutboxMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryConsumerResultStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the persistence-owned Kafka DLT convergence boundary.
 */
class SyncAutopilotRecoveryDeadLetterServiceTest {

    /**
     * A trigger that already started recovery must receive the normal failure receipt before its DLT result is stored.
     *
     * <p>The exact case comes from {@code eventId:decision}; task recency or caller-provided case data plays no part.
     * The result callback is captured to prove that only the fixed low-sensitive reason is persisted and that the
     * final status reflects the reloaded {@code ATTENTION_REQUIRED} case.</p>
     */
    @Test
    void shouldMoveStartedCaseToAttentionAndRecordDeadLetterResult() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerOutbox outbox = outbox();
        SyncAutopilotRecoveryCase started = recoveryCase("RECOVERY_STARTED", 2L);
        SyncAutopilotRecoveryCase attention = recoveryCase("ATTENTION_REQUIRED", 3L);
        when(fixture.outboxMapper().selectByEventIdAndCurrentExecutionId("event-1", 1001L))
                .thenReturn(outbox);
        when(fixture.receiptMapper().selectByReceiptId("event-1:decision"))
                .thenReturn(decisionReceipt());
        when(fixture.caseMapper().selectByCaseId(81L)).thenReturn(started, attention);
        when(fixture.consumerResultService().recordConsumerResult(eq("event-1"), any()))
                .thenReturn(resultView());

        SyncAutopilotRecoveryTriggerConsumerResultView result =
                fixture.service().recordDeadLettered("event-1", 1001L);

        ArgumentCaptor<SyncAutopilotRecoveryTransitionCommand> transition =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTransitionCommand.class);
        verify(fixture.caseService()).recordTransition(transition.capture());
        assertThat(transition.getValue().caseId()).isEqualTo(81L);
        assertThat(transition.getValue().expectedVersion()).isEqualTo(2L);
        assertThat(transition.getValue().receiptId()).isEqualTo("event-1:dead-lettered");
        assertThat(transition.getValue().receiptType().name()).isEqualTo("RECOVERY_FAILED");
        assertThat(transition.getValue().attentionReason()).isEqualTo("AUTOPILOT_TRIGGER_DEAD_LETTERED");

        ArgumentCaptor<SyncAutopilotRecoveryTriggerConsumerResultCommand> consumerResult =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerConsumerResultCommand.class);
        verify(fixture.consumerResultService()).recordConsumerResult(eq("event-1"), consumerResult.capture());
        assertThat(consumerResult.getValue().status())
                .isEqualTo(SyncAutopilotRecoveryConsumerResultStatus.ATTENTION_REQUIRED);
        assertThat(consumerResult.getValue().reasonCode()).isEqualTo("AUTOPILOT_TRIGGER_DEAD_LETTERED");
        assertThat(consumerResult.getValue().caseId()).isEqualTo(81L);
        assertThat(consumerResult.getValue().retrievalDecision()).isNull();
        assertThat(result).isEqualTo(resultView());
    }

    /**
     * A delivery failure before case creation must still become a durable attention result for the original outbox.
     */
    @Test
    void shouldRecordAttentionWhenNoDecisionReceiptCommitted() {
        Fixture fixture = fixture();
        when(fixture.outboxMapper().selectByEventIdAndCurrentExecutionId("event-1", 1001L))
                .thenReturn(outbox());
        when(fixture.receiptMapper().selectByReceiptId("event-1:decision")).thenReturn(null);
        when(fixture.consumerResultService().recordConsumerResult(eq("event-1"), any()))
                .thenReturn(resultView());

        fixture.service().recordDeadLettered("event-1", 1001L);

        ArgumentCaptor<SyncAutopilotRecoveryTriggerConsumerResultCommand> command =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerConsumerResultCommand.class);
        verify(fixture.consumerResultService()).recordConsumerResult(eq("event-1"), command.capture());
        assertThat(command.getValue().status())
                .isEqualTo(SyncAutopilotRecoveryConsumerResultStatus.ATTENTION_REQUIRED);
        assertThat(command.getValue().caseId()).isNull();
    }

    /**
     * A broker outage must create producer-owned attention without claiming that Agent Runtime consumed the trigger.
     *
     * <p>The final outbox mutation and any local case transition belong to one producer transaction. With no
     * decision receipt, no case can honestly exist, so this test proves that the service commits only the terminal
     * outbox fact and leaves every consumer-result collaboration untouched. The public status projection derives
     * its separate producer attention state from that fact.</p>
     */
    @Test
    void shouldDeadLetterProducerWithoutFabricatingConsumerResultWhenNoDecisionExists() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerOutbox candidate = producerCandidate();
        SyncAutopilotRecoveryTriggerOutbox deadLettered = producerDeadLetteredOutbox();
        when(fixture.outboxMapper().markFailure(
                eq(71L), eq("DEAD_LETTER"), eq(null), any(),
                eq("AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED"),
                eq("Autopilot recovery trigger could not be delivered")))
                .thenReturn(1);
        when(fixture.outboxMapper().selectByEventIdAndCurrentExecutionId("event-1", 1001L))
                .thenReturn(deadLettered);
        when(fixture.receiptMapper().selectByReceiptId("event-1:decision")).thenReturn(null);

        boolean recorded = fixture.service().recordProducerDeadLettered(candidate);

        assertThat(recorded).isTrue();
        verify(fixture.outboxMapper()).markFailure(
                eq(71L), eq("DEAD_LETTER"), eq(null), any(),
                eq("AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED"),
                eq("Autopilot recovery trigger could not be delivered"));
        verifyNoInteractions(fixture.caseService(), fixture.consumerResultService());
    }

    /**
     * A producer terminal write that loses the optimistic race is a harmless replay and must not alter a case.
     *
     * <p>Only the instance that changed {@code DISPATCHING} to {@code DEAD_LETTER} may follow the decision receipt
     * and transition a case. A later scheduler or after-commit callback observes zero rows updated, returns false,
     * and leaves the prior transaction's durable outcome untouched. This keeps repeated terminal failure handling
     * idempotent without rewriting a consumer result.</p>
     */
    @Test
    void shouldTreatAlreadyConvergedProducerDeadLetterAsIdempotentNoOp() {
        Fixture fixture = fixture();
        when(fixture.outboxMapper().markFailure(
                eq(71L), eq("DEAD_LETTER"), eq(null), any(),
                eq("AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED"),
                eq("Autopilot recovery trigger could not be delivered")))
                .thenReturn(0);

        boolean recorded = fixture.service().recordProducerDeadLettered(producerCandidate());

        assertThat(recorded).isFalse();
        verifyNoInteractions(
                fixture.receiptMapper(), fixture.caseMapper(), fixture.caseService(), fixture.consumerResultService());
    }

    /**
     * A case that was already created before an acknowledgement loss must be closed with a producer-specific receipt.
     *
     * <p>This covers the Kafka acknowledgement ambiguity where a message may have reached Agent Runtime but the
     * producer cannot prove it. The local case is still stopped fail-closed, but V22 consumer-result persistence is
     * never invoked because the producer has no consumer result to attest. The deterministic receipt makes a later
     * replay safe under the case service's ordinary optimistic-lock protocol.</p>
     */
    @Test
    void shouldMoveExistingCaseToAttentionWithoutWritingConsumerResultForProducerDeadLetter() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryCase started = recoveryCase("RECOVERY_STARTED", 2L);
        SyncAutopilotRecoveryCase attention = recoveryCase("ATTENTION_REQUIRED", 3L);
        when(fixture.outboxMapper().markFailure(
                eq(71L), eq("DEAD_LETTER"), eq(null), any(),
                eq("AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED"),
                eq("Autopilot recovery trigger could not be delivered")))
                .thenReturn(1);
        when(fixture.outboxMapper().selectByEventIdAndCurrentExecutionId("event-1", 1001L))
                .thenReturn(producerDeadLetteredOutbox());
        when(fixture.receiptMapper().selectByReceiptId("event-1:decision"))
                .thenReturn(decisionReceipt());
        when(fixture.caseMapper().selectByCaseId(81L)).thenReturn(started, attention);

        boolean recorded = fixture.service().recordProducerDeadLettered(producerCandidate());

        assertThat(recorded).isTrue();
        ArgumentCaptor<SyncAutopilotRecoveryTransitionCommand> transition =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTransitionCommand.class);
        verify(fixture.caseService()).recordTransition(transition.capture());
        assertThat(transition.getValue().receiptId()).isEqualTo("event-1:producer-dead-lettered");
        assertThat(transition.getValue().receiptType().name()).isEqualTo("RECOVERY_FAILED");
        assertThat(transition.getValue().attentionReason())
                .isEqualTo("AUTOPILOT_TRIGGER_PRODUCER_DEAD_LETTERED");
        verifyNoInteractions(fixture.consumerResultService());
    }

    /** Builds isolated mapper/service collaborators for one DLT convergence scenario. */
    private Fixture fixture() {
        SyncAutopilotRecoveryTriggerOutboxMapper outboxMapper =
                mock(SyncAutopilotRecoveryTriggerOutboxMapper.class);
        SyncAutopilotRecoveryReceiptMapper receiptMapper =
                mock(SyncAutopilotRecoveryReceiptMapper.class);
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService =
                mock(SyncAutopilotRecoveryTriggerConsumerResultService.class);
        SyncAutopilotRecoveryDeadLetterService service = new SyncAutopilotRecoveryDeadLetterService(
                outboxMapper, receiptMapper, caseMapper, caseService, consumerResultService);
        return new Fixture(service, outboxMapper, receiptMapper, caseMapper, caseService, consumerResultService);
    }

    /** Creates the authoritative original outbox scope used by both tests. */
    private SyncAutopilotRecoveryTriggerOutbox outbox() {
        SyncAutopilotRecoveryTriggerOutbox outbox = new SyncAutopilotRecoveryTriggerOutbox();
        outbox.setEventId("event-1");
        outbox.setTenantId(10L);
        outbox.setProjectId(20L);
        outbox.setSyncTaskId(31L);
        outbox.setRootExecutionId(1001L);
        outbox.setCurrentExecutionId(1001L);
        outbox.setCycle(1);
        return outbox;
    }

    /** Builds the complete row handed from a successful producer claim into terminal convergence. */
    private SyncAutopilotRecoveryTriggerOutbox producerCandidate() {
        SyncAutopilotRecoveryTriggerOutbox outbox = outbox();
        outbox.setId(71L);
        outbox.setOutboxState("DISPATCHING");
        outbox.setAttemptCount(3);
        outbox.setMaxAttemptCount(3);
        return outbox;
    }

    /** Builds the same immutable row after data-sync has durably marked the producer delivery as terminal. */
    private SyncAutopilotRecoveryTriggerOutbox producerDeadLetteredOutbox() {
        SyncAutopilotRecoveryTriggerOutbox outbox = producerCandidate();
        outbox.setOutboxState("DEAD_LETTER");
        return outbox;
    }

    /** Creates the exact completed decision receipt owned by {@code event-1}. */
    private SyncAutopilotRecoveryReceipt decisionReceipt() {
        SyncAutopilotRecoveryReceipt receipt = new SyncAutopilotRecoveryReceipt();
        receipt.setReceiptId("event-1:decision");
        receipt.setCaseId(81L);
        receipt.setReceiptType("DECISION_RECORDED");
        receipt.setReceiptState("COMPLETED");
        return receipt;
    }

    /** Creates a case in the supplied lifecycle state while retaining the outbox scope. */
    private SyncAutopilotRecoveryCase recoveryCase(String state, Long version) {
        SyncAutopilotRecoveryCase recoveryCase = new SyncAutopilotRecoveryCase();
        recoveryCase.setCaseId(81L);
        recoveryCase.setTenantId(10L);
        recoveryCase.setProjectId(20L);
        recoveryCase.setSyncTaskId(31L);
        recoveryCase.setRootExecutionId(1001L);
        recoveryCase.setCurrentExecutionId(1001L);
        recoveryCase.setCaseState(state);
        recoveryCase.setVersion(version);
        return recoveryCase;
    }

    /** Returns a representative low-sensitive callback view from the mocked result service. */
    private SyncAutopilotRecoveryTriggerConsumerResultView resultView() {
        return new SyncAutopilotRecoveryTriggerConsumerResultView(
                "event-1", 1001L, "ATTENTION_REQUIRED", "AUTOPILOT_TRIGGER_DEAD_LETTERED",
                81L, "a".repeat(64), null, null, null, null,
                LocalDateTime.of(2026, 8, 12, 12, 0));
    }

    private record Fixture(
            SyncAutopilotRecoveryDeadLetterService service,
            SyncAutopilotRecoveryTriggerOutboxMapper outboxMapper,
            SyncAutopilotRecoveryReceiptMapper receiptMapper,
            SyncAutopilotRecoveryCaseMapper caseMapper,
            SyncAutopilotRecoveryCaseService caseService,
            SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService) {
    }
}
