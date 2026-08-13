/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryStatusQueryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryQuarantineReceipt;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryQuarantineReceiptMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryTriggerOutboxMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the low-sensitive view used to observe unattended recovery without reading internal tables. */
class SyncAutopilotRecoveryStatusQueryServiceTest {

    /** A terminal case exposes lifecycle states and bounded receipt counts, but no hashes or selected IDs. */
    @Test
    void shouldProjectRecoveredCaseAndDurableQuarantineReceipt() {
        Fixture fixture = fixture();
        when(fixture.executionMapper.selectById(41L)).thenReturn(execution(41L, 31L));
        when(fixture.caseMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(recoveryCase());
        when(fixture.outboxMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(outbox());
        when(fixture.quarantineMapper.selectLatestByCaseId(81L)).thenReturn(quarantineReceipt());

        SyncAutopilotRecoveryStatusView view = fixture.service.query(task(), 41L);

        assertThat(view.available()).isTrue();
        assertThat(view.caseState()).isEqualTo("RECOVERED");
        assertThat(view.recoveryAction()).isEqualTo("APPLY_QUARANTINE");
        assertThat(view.consumerResultStatus()).isEqualTo("RECOVERY_STARTED");
        assertThat(view.retrievalDecision()).isEqualTo("SEARCH");
        assertThat(view.retrievalStrategy()).isEqualTo("RAG");
        assertThat(view.retrievalEvidenceCount()).isEqualTo(2);
        assertThat(view.retrievalEvidenceDigest()).isEqualTo("sha256:" + "c".repeat(64));
        assertThat(view.quarantineSelectedCount()).isEqualTo(2);
        assertThat(view.quarantineAffectedCount()).isEqualTo(2);
        assertThat(view.quarantineOperationState()).isEqualTo("APPLIED");
        assertThat(view.quarantineReceiptState()).isEqualTo("COMPLETED");
    }

    /** No recovery case is a valid observable state and must not be presented as an API error. */
    @Test
    void shouldReturnUnavailableWhenExecutionHasNoRecoveryCase() {
        Fixture fixture = fixture();
        when(fixture.executionMapper.selectById(41L)).thenReturn(execution(41L, 31L));
        when(fixture.caseMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(null);
        when(fixture.outboxMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(null);

        SyncAutopilotRecoveryStatusView view = fixture.service.query(task(), 41L);

        assertThat(view.available()).isFalse();
        assertThat(view.syncTaskId()).isEqualTo(31L);
        assertThat(view.currentExecutionId()).isEqualTo(41L);
        verify(fixture.outboxMapper).selectLatestByTaskExecution(11L, 31L, 41L);
        verify(fixture.quarantineMapper, never()).selectLatestByCaseId(81L);
    }

    /** A durable consumer rejection remains observable even when policy stopped before creating a case. */
    @Test
    void shouldExposeTerminalOutboxResultWhenNoRecoveryCaseWasCreated() {
        Fixture fixture = fixture();
        when(fixture.executionMapper.selectById(41L)).thenReturn(execution(41L, 31L));
        when(fixture.caseMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(null);
        SyncAutopilotRecoveryTriggerOutbox rejected = outbox();
        rejected.setConsumerResultStatus("ATTENTION_REQUIRED");
        rejected.setConsumerResultReasonCode("AUTOPILOT_AUTHORIZATION_EXPIRED");
        rejected.setConsumerResultCaseId(null);
        when(fixture.outboxMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(rejected);

        SyncAutopilotRecoveryStatusView view = fixture.service.query(task(), 41L);

        assertThat(view.available()).isTrue();
        assertThat(view.caseId()).isNull();
        assertThat(view.consumerResultStatus()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(view.consumerResultReasonCode()).isEqualTo("AUTOPILOT_AUTHORIZATION_EXPIRED");
        verify(fixture.quarantineMapper, never()).selectLatestByCaseId(81L);
    }

    /**
     * A permanently unavailable broker must surface producer-owned operator attention without inventing a consumer.
     *
     * <p>No Agent Runtime consumer can create a decision receipt, case, or consumer result when the producer never
     * receives a broker acknowledgement. The public projection must therefore expose a separate producer delivery
     * outcome, while preserving {@code null} for consumer-owned facts. This is the regression for the terminal
     * outbox path that previously stopped at {@code DEAD_LETTER} without an actionable public state.</p>
     */
    @Test
    void shouldExposeProducerDeadLetterAsAttentionWithoutFabricatingConsumerResult() {
        Fixture fixture = fixture();
        when(fixture.executionMapper.selectById(41L)).thenReturn(execution(41L, 31L));
        when(fixture.caseMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(null);
        SyncAutopilotRecoveryTriggerOutbox deadLettered = outbox();
        deadLettered.setOutboxState("DEAD_LETTER");
        deadLettered.setLastErrorCode("AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED");
        deadLettered.setConsumerResultStatus(null);
        deadLettered.setConsumerResultReasonCode(null);
        deadLettered.setConsumedAt(null);
        deadLettered.setRetrievalDecision(null);
        deadLettered.setRetrievalStrategy(null);
        deadLettered.setRetrievalEvidenceCount(null);
        deadLettered.setRetrievalEvidenceDigest(null);
        when(fixture.outboxMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(deadLettered);

        SyncAutopilotRecoveryStatusView view = fixture.service.query(task(), 41L);

        assertThat(view.caseId()).isNull();
        assertThat(view.caseState()).isNull();
        assertThat(view.consumerResultStatus()).isNull();
        assertThat(view.producerDeliveryStatus()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(view.producerDeliveryReasonCode())
                .isEqualTo("AUTOPILOT_TRIGGER_PRODUCER_DEAD_LETTERED");
        verify(fixture.quarantineMapper, never()).selectLatestByCaseId(81L);
    }

    /** A stable root URL must still project the worker state of the case's newer current execution. */
    @Test
    void shouldProjectTheCurrentRecoveryExecutionInsteadOfTheRootFailure() {
        Fixture fixture = fixture();
        SyncExecution root = execution(41L, 31L);
        root.setExecutionState("FAILED");
        SyncExecution current = execution(42L, 31L);
        current.setExecutionState("SUCCEEDED");
        current.setFinishedAt(LocalDateTime.of(2026, 8, 12, 2, 0));
        when(fixture.executionMapper.selectById(41L)).thenReturn(root);
        when(fixture.executionMapper.selectById(42L)).thenReturn(current);
        SyncAutopilotRecoveryCase recoveryCase = recoveryCase();
        recoveryCase.setCurrentExecutionId(42L);
        when(fixture.caseMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(recoveryCase);
        when(fixture.outboxMapper.selectLatestByTaskExecution(11L, 31L, 41L)).thenReturn(outbox());
        when(fixture.quarantineMapper.selectLatestByCaseId(81L)).thenReturn(quarantineReceipt());

        SyncAutopilotRecoveryStatusView view = fixture.service.query(task(), 41L);

        assertThat(view.rootExecutionId()).isEqualTo(41L);
        assertThat(view.currentExecutionId()).isEqualTo(42L);
        assertThat(view.executionState()).isEqualTo("SUCCEEDED");
        assertThat(view.executionFinishedAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 2, 0));
        verify(fixture.executionMapper).selectById(42L);
    }

    /** Cross-task execution IDs fail before any recovery control-plane record is queried. */
    @Test
    void shouldRejectExecutionOutsideTheVisibleTask() {
        Fixture fixture = fixture();
        when(fixture.executionMapper.selectById(41L)).thenReturn(execution(41L, 99L));

        assertThatThrownBy(() -> fixture.service.query(task(), 41L))
                .isInstanceOf(PlatformBusinessException.class);

        verify(fixture.caseMapper, never()).selectLatestByTaskExecution(11L, 31L, 41L);
    }

    /** Creates mapper doubles for one isolated projection test. */
    private Fixture fixture() {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncAutopilotRecoveryTriggerOutboxMapper outboxMapper = mock(SyncAutopilotRecoveryTriggerOutboxMapper.class);
        SyncAutopilotRecoveryQuarantineReceiptMapper quarantineMapper =
                mock(SyncAutopilotRecoveryQuarantineReceiptMapper.class);
        return new Fixture(
                new SyncAutopilotRecoveryStatusQueryService(
                        executionMapper, caseMapper, outboxMapper, quarantineMapper),
                executionMapper, caseMapper, outboxMapper, quarantineMapper);
    }

    /** Builds the task aggregate after the normal DataSyncService visibility check. */
    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(11L);
        task.setProjectId(13L);
        return task;
    }

    /** Builds one authoritative execution and allows tests to vary its owning task. */
    private SyncExecution execution(Long executionId, Long taskId) {
        SyncExecution execution = new SyncExecution();
        execution.setId(executionId);
        execution.setSyncTaskId(taskId);
        execution.setTenantId(11L);
        execution.setProjectId(13L);
        return execution;
    }

    /** Builds a terminal recovery case with fields allowed in the public projection. */
    private SyncAutopilotRecoveryCase recoveryCase() {
        SyncAutopilotRecoveryCase recoveryCase = new SyncAutopilotRecoveryCase();
        recoveryCase.setCaseId(81L);
        recoveryCase.setSyncTaskId(31L);
        recoveryCase.setRootExecutionId(41L);
        recoveryCase.setCurrentExecutionId(41L);
        recoveryCase.setCaseState("RECOVERED");
        recoveryCase.setCycle(1);
        recoveryCase.setMaxCycles(3);
        recoveryCase.setRecoveryAction("APPLY_QUARANTINE");
        recoveryCase.setRiskLevel("LOW");
        recoveryCase.setVersion(2L);
        recoveryCase.setDeadlineAt(LocalDateTime.of(2026, 8, 12, 6, 0));
        recoveryCase.setCreateTime(LocalDateTime.of(2026, 8, 12, 1, 0));
        recoveryCase.setUpdateTime(LocalDateTime.of(2026, 8, 12, 1, 5));
        return recoveryCase;
    }

    /** Builds the immutable first consumer outcome for the trigger. */
    private SyncAutopilotRecoveryTriggerOutbox outbox() {
        SyncAutopilotRecoveryTriggerOutbox outbox = new SyncAutopilotRecoveryTriggerOutbox();
        outbox.setRootExecutionId(41L);
        outbox.setCurrentExecutionId(41L);
        outbox.setCycle(1);
        outbox.setOutboxState("DELIVERED");
        outbox.setConsumerResultStatus("RECOVERY_STARTED");
        outbox.setConsumerResultReasonCode("AUTOPILOT_FAILED_OBJECTS_REQUEUED");
        outbox.setRetrievalDecision("SEARCH");
        outbox.setRetrievalStrategy("RAG");
        outbox.setRetrievalEvidenceCount(2);
        outbox.setRetrievalEvidenceDigest("sha256:" + "c".repeat(64));
        outbox.setConsumedAt(LocalDateTime.of(2026, 8, 12, 1, 2));
        return outbox;
    }

    /** Builds the durable proof that preview-bound quarantine completed before retry. */
    private SyncAutopilotRecoveryQuarantineReceipt quarantineReceipt() {
        SyncAutopilotRecoveryQuarantineReceipt receipt = new SyncAutopilotRecoveryQuarantineReceipt();
        receipt.setSelectedCount(2);
        receipt.setAffectedCount(2);
        receipt.setOperationState("APPLIED");
        receipt.setReceiptState("COMPLETED");
        receipt.setUpdateTime(LocalDateTime.of(2026, 8, 12, 1, 1));
        return receipt;
    }

    private record Fixture(
            SyncAutopilotRecoveryStatusQueryService service,
            SyncExecutionMapper executionMapper,
            SyncAutopilotRecoveryCaseMapper caseMapper,
            SyncAutopilotRecoveryTriggerOutboxMapper outboxMapper,
            SyncAutopilotRecoveryQuarantineReceiptMapper quarantineMapper) {
    }
}
