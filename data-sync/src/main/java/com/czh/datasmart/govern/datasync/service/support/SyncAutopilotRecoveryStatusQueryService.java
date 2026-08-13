/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryStatusQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryQuarantineReceipt;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryQuarantineReceiptMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryTriggerOutboxMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Reads a low-sensitive, cross-table status view for unattended data-sync recovery.
 *
 * <p>The controller supplies a task that has already passed the normal tenant/project/SELF visibility check.
 * This service then independently reloads the execution and verifies its task, tenant, and project ownership
 * before touching any recovery control-plane table. The second check prevents a caller from combining a visible
 * task ID with an execution ID copied from another task.</p>
 *
 * <p>This class performs no recovery action and writes no state. It merely joins the latest durable case,
 * trigger result, and optional quarantine receipt into the deliberately restricted public projection.</p>
 */
@Service
@RequiredArgsConstructor
public class SyncAutopilotRecoveryStatusQueryService {

    private static final String DEAD_LETTER_OUTBOX_STATE = "DEAD_LETTER";
    private static final String PRODUCER_ATTENTION_REQUIRED = "ATTENTION_REQUIRED";
    private static final String PRODUCER_DEAD_LETTER_REASON =
            "AUTOPILOT_TRIGGER_PRODUCER_DEAD_LETTERED";

    private final SyncExecutionMapper executionMapper;
    private final SyncAutopilotRecoveryCaseMapper caseMapper;
    private final SyncAutopilotRecoveryTriggerOutboxMapper outboxMapper;
    private final SyncAutopilotRecoveryQuarantineReceiptMapper quarantineReceiptMapper;

    /**
     * Queries the latest observable recovery facts for one execution owned by a visible task.
     *
     * @param visibleTask task returned by {@code DataSyncService.getTask} after scope validation
     * @param executionId execution whose recovery lifecycle should be shown
     * @return a low-sensitive status; {@code available=false} means no trigger or case exists yet
     * @throws PlatformBusinessException when the task/execution identity is invalid or crosses a scope boundary
     */
    public SyncAutopilotRecoveryStatusView query(SyncTask visibleTask, Long executionId) {
        requireVisibleTaskAndExecutionId(visibleTask, executionId);
        SyncExecution execution = executionMapper.selectById(executionId);
        verifyExecutionOwnership(visibleTask, execution);

        Long tenantId = visibleTask.getTenantId();
        Long taskId = visibleTask.getId();
        SyncAutopilotRecoveryCase recoveryCase =
                caseMapper.selectLatestByTaskExecution(tenantId, taskId, executionId);
        SyncAutopilotRecoveryTriggerOutbox outbox =
                outboxMapper.selectLatestByTaskExecution(tenantId, taskId, executionId);

        if (recoveryCase == null && outbox == null) {
            return SyncAutopilotRecoveryStatusView.unavailable(
                    taskId, executionId, execution.getExecutionState(), execution.getFinishedAt());
        }

        SyncAutopilotRecoveryQuarantineReceipt quarantineReceipt = recoveryCase == null
                ? null
                : quarantineReceiptMapper.selectLatestByCaseId(recoveryCase.getCaseId());
        SyncExecution currentExecution = currentExecution(execution, recoveryCase, outbox);
        verifyExecutionOwnership(visibleTask, currentExecution);
        return project(currentExecution, recoveryCase, outbox, quarantineReceipt);
    }

    /**
     * Loads the execution currently owned by the recovery lineage instead of always projecting the root failure.
     *
     * <p>Failed-object retry currently requeues the same execution, while future replay actions may create a new
     * execution and update the case. The public URL remains rooted at the original failed execution so callers can
     * keep polling one stable resource, but worker terminal state must come from {@code currentExecutionId}.</p>
     */
    private SyncExecution currentExecution(
            SyncExecution rootExecution,
            SyncAutopilotRecoveryCase recoveryCase,
            SyncAutopilotRecoveryTriggerOutbox outbox) {
        Long currentExecutionId = recoveryCase != null
                ? recoveryCase.getCurrentExecutionId()
                : outbox == null ? rootExecution.getId() : outbox.getCurrentExecutionId();
        if (Objects.equals(rootExecution.getId(), currentExecutionId)) {
            return rootExecution;
        }
        SyncExecution currentExecution = executionMapper.selectById(currentExecutionId);
        if (currentExecution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Autopilot recovery current execution is missing");
        }
        return currentExecution;
    }

    /**
     * Converts persistence entities into the finite public contract without forwarding sensitive columns.
     *
     * <p>Notice that payload JSON, last-error summary, result/policy/authorization digests, receipt IDs, actor
     * identities, sample IDs, and fingerprints are never read into the response. The only digest intentionally
     * exposed is {@code retrievalEvidenceDigest}, which binds low-sensitive RAG evidence IDs and contains no
     * document text. A producer dead-letter is projected through separate producer fields instead of a fabricated
     * consumer result, because a failed broker acknowledgement cannot prove Agent Runtime received the trigger.</p>
     */
    private SyncAutopilotRecoveryStatusView project(
            SyncExecution execution,
            SyncAutopilotRecoveryCase recoveryCase,
            SyncAutopilotRecoveryTriggerOutbox outbox,
            SyncAutopilotRecoveryQuarantineReceipt quarantineReceipt) {
        Long rootExecutionId = recoveryCase != null
                ? recoveryCase.getRootExecutionId()
                : outbox == null ? execution.getId() : outbox.getRootExecutionId();
        Long currentExecutionId = recoveryCase != null
                ? recoveryCase.getCurrentExecutionId()
                : outbox == null ? execution.getId() : outbox.getCurrentExecutionId();
        return new SyncAutopilotRecoveryStatusView(
                true,
                execution.getSyncTaskId(),
                rootExecutionId,
                currentExecutionId,
                execution.getExecutionState(),
                execution.getFinishedAt(),
                recoveryCase == null ? null : recoveryCase.getCaseId(),
                recoveryCase == null ? null : recoveryCase.getCaseState(),
                recoveryCase == null ? (outbox == null ? null : outbox.getCycle()) : recoveryCase.getCycle(),
                recoveryCase == null ? null : recoveryCase.getMaxCycles(),
                recoveryCase == null ? null : recoveryCase.getRecoveryAction(),
                recoveryCase == null ? null : recoveryCase.getRiskLevel(),
                recoveryCase == null ? null : recoveryCase.getAttentionReason(),
                recoveryCase == null ? null : recoveryCase.getDeadlineAt(),
                recoveryCase == null ? null : recoveryCase.getVersion(),
                recoveryCase == null ? null : recoveryCase.getCreateTime(),
                recoveryCase == null ? null : recoveryCase.getUpdateTime(),
                outbox == null ? null : outbox.getOutboxState(),
                outbox == null ? null : outbox.getAttemptCount(),
                outbox == null ? null : outbox.getMaxAttemptCount(),
                outbox == null ? null : outbox.getLastErrorCode(),
                producerDeliveryStatus(outbox),
                producerDeliveryReasonCode(outbox),
                outbox == null ? null : outbox.getConsumerResultStatus(),
                outbox == null ? null : outbox.getConsumerResultReasonCode(),
                outbox == null ? null : outbox.getConsumedAt(),
                outbox == null ? null : outbox.getRetrievalDecision(),
                outbox == null ? null : outbox.getRetrievalStrategy(),
                outbox == null ? null : outbox.getRetrievalEvidenceCount(),
                outbox == null ? null : outbox.getRetrievalEvidenceDigest(),
                quarantineReceipt == null ? null : quarantineReceipt.getSelectedCount(),
                quarantineReceipt == null ? null : quarantineReceipt.getAffectedCount(),
                quarantineReceipt == null ? null : quarantineReceipt.getOperationState(),
                quarantineReceipt == null ? null : quarantineReceipt.getReceiptState(),
                quarantineReceipt == null ? null : quarantineReceipt.getUpdateTime());
    }

    /**
     * Exposes producer-owned attention only when delivery exhausted locally and no consumer fact exists.
     *
     * <p>The outbox {@code DEAD_LETTER} state says data-sync exhausted its own broker retry budget. It does not
     * say a consumer ran, so this helper refuses to reuse the consumer-result fields for that fact. A later valid
     * consumer callback remains visible through its own immutable result fields; the producer state is then still
     * available as transport history but does not impersonate a consumer outcome. The helper is pure and performs
     * no persistence change.</p>
     *
     * @param outbox latest durable trigger row for the visible execution, possibly absent
     * @return {@code ATTENTION_REQUIRED} only for an unconsumed producer dead-letter, otherwise {@code null}
     */
    private String producerDeliveryStatus(SyncAutopilotRecoveryTriggerOutbox outbox) {
        return isUnconsumedProducerDeadLetter(outbox) ? PRODUCER_ATTENTION_REQUIRED : null;
    }

    /**
     * Supplies the fixed low-sensitive reason for the producer-owned public attention projection.
     *
     * <p>The code contains no broker address, exception text, payload, model data, or identifiers. It is emitted
     * only with {@link #producerDeliveryStatus(SyncAutopilotRecoveryTriggerOutbox)} so clients can distinguish a
     * producer transport exhaustion from a real Agent Runtime consumer result without needing internal error
     * columns. The helper is pure and idempotent.</p>
     *
     * @param outbox latest durable trigger row for the visible execution, possibly absent
     * @return fixed producer-dead-letter reason when the public producer status is present, otherwise {@code null}
     */
    private String producerDeliveryReasonCode(SyncAutopilotRecoveryTriggerOutbox outbox) {
        return isUnconsumedProducerDeadLetter(outbox) ? PRODUCER_DEAD_LETTER_REASON : null;
    }

    /**
     * Distinguishes a local send exhaustion from an event that already has a consumer-owned result fact.
     *
     * <p>V22 stores all consumer fields atomically, but this defensive projection treats any populated field as
     * evidence that data-sync must not claim the row was unconsumed. This is conservative for damaged legacy rows:
     * it avoids presenting a local producer assertion as stronger than the actual durable consumer audit data.</p>
     *
     * @param outbox latest durable trigger row to inspect
     * @return {@code true} only for a terminal producer send failure with no consumer-result field populated
     */
    private boolean isUnconsumedProducerDeadLetter(SyncAutopilotRecoveryTriggerOutbox outbox) {
        return outbox != null
                && DEAD_LETTER_OUTBOX_STATE.equals(outbox.getOutboxState())
                && outbox.getConsumerResultDigest() == null
                && outbox.getConsumerResultStatus() == null
                && outbox.getConsumerResultReasonCode() == null
                && outbox.getConsumerResultCaseId() == null
                && outbox.getRetrievalDecision() == null
                && outbox.getRetrievalStrategy() == null
                && outbox.getRetrievalEvidenceCount() == null
                && outbox.getRetrievalEvidenceDigest() == null
                && outbox.getConsumedAt() == null;
    }

    /** Validates the method's trusted aggregate and untrusted path identifier before database access. */
    private void requireVisibleTaskAndExecutionId(SyncTask visibleTask, Long executionId) {
        if (visibleTask == null || visibleTask.getId() == null || visibleTask.getTenantId() == null
                || executionId == null || executionId <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery status identity is incomplete");
        }
    }

    /**
     * Verifies that the execution belongs to the already-authorized task and its durable scope.
     *
     * <p>{@code NOT_FOUND} deliberately avoids revealing whether a foreign execution ID exists.</p>
     */
    private void verifyExecutionOwnership(SyncTask visibleTask, SyncExecution execution) {
        if (execution == null
                || !Objects.equals(visibleTask.getId(), execution.getSyncTaskId())
                || !Objects.equals(visibleTask.getTenantId(), execution.getTenantId())
                || !Objects.equals(visibleTask.getProjectId(), execution.getProjectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Sync execution does not belong to the visible task");
        }
    }
}
