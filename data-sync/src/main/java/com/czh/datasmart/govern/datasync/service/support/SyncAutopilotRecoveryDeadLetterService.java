/**
 * @Author : Cui
 * @Date: 2026/08/12 12:00
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryDeadLetterService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryReceipt;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryReceiptMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryTriggerOutboxMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryConsumerResultStatus;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryTriggerOutboxState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Converges exhausted Autopilot trigger delivery paths into durable, queryable control-plane outcomes.
 *
 * <p>The consumer-side callback path handles a record that reached Agent Runtime but exhausted that listener's
 * retry budget. The producer-side path handles a record that data-sync could not obtain a broker acknowledgement
 * for after its own bounded outbox retries. Those facts must remain separate: only the consumer-side path writes
 * {@code consumer_result_*}; producer convergence records the existing outbox dead-letter fact and, when a
 * previously created local case can still execute, diverts that case to {@code ATTENTION_REQUIRED}.</p>
 *
 * <p>Both paths deliberately avoid accepting a case ID or target state from a caller. They prove the exact
 * {@code eventId + currentExecutionId} against the original outbox, then follow the completed
 * {@code eventId:decision} receipt to the case created by that event. Executable cases are moved through the normal
 * {@code RECOVERY_FAILED} receipt to {@code ATTENTION_REQUIRED}; already bounded or terminal cases keep their
 * authoritative state. The consumer path additionally records its compact result in the same transaction, while
 * the producer path retains the absence of a consumer result as an important truth.</p>
 *
 * <p>No Kafka body, exception message, RAG text, model reasoning, authorization snapshot, SQL, credential, or log
 * content is persisted by this path. The fixed reason code says only that delivery exhausted its retry budget.</p>
 */
@Service
@RequiredArgsConstructor
public class SyncAutopilotRecoveryDeadLetterService {

    private static final String DECISION_RECEIPT_SUFFIX = ":decision";
    private static final String DEAD_LETTER_RECEIPT_SUFFIX = ":dead-lettered";
    private static final String DEAD_LETTER_REASON = "AUTOPILOT_TRIGGER_DEAD_LETTERED";
    private static final String PRODUCER_DEAD_LETTER_RECEIPT_SUFFIX = ":producer-dead-lettered";
    private static final String PRODUCER_DEAD_LETTER_REASON = "AUTOPILOT_TRIGGER_PRODUCER_DEAD_LETTERED";
    private static final String PRODUCER_DISPATCH_ERROR_CODE = "AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED";
    private static final String PRODUCER_DISPATCH_ERROR_SUMMARY =
            "Autopilot recovery trigger could not be delivered";

    private final SyncAutopilotRecoveryTriggerOutboxMapper outboxMapper;
    private final SyncAutopilotRecoveryReceiptMapper receiptMapper;
    private final SyncAutopilotRecoveryCaseMapper caseMapper;
    private final SyncAutopilotRecoveryCaseService caseService;
    private final SyncAutopilotRecoveryTriggerConsumerResultService consumerResultService;

    /**
     * Persists the bounded DLT outcome and moves an already executable recovery case to operator attention.
     *
     * <p>The input is intentionally limited to the two identities that also existed before Kafka publication. The
     * method may write a {@code RECOVERY_FAILED} case receipt and the first trigger-consumer result. Both writes join
     * this transaction, so a result conflict or infrastructure failure rolls the state transition back instead of
     * leaving a partially reported outcome. Repeating equal input after a committed response loss is idempotent.</p>
     *
     * <p>If planning never created a case, the method still records {@code ATTENTION_REQUIRED} against the original
     * outbox. If an exact decision receipt exists, its case is reloaded and scope-checked. Only
     * {@code AUTO_APPROVED}, {@code MANUALLY_APPROVED}, or {@code RECOVERY_STARTED} need a failure transition;
     * approval-waiting, already-attention, rejected, recovered, and cancelled cases retain their durable state.</p>
     *
     * @param eventId immutable identifier of the data-sync outbox event
     * @param currentExecutionId execution identity copied from that event
     * @return durable low-sensitive consumer-result view accepted for the trigger
     * @throws PlatformBusinessException when the event, execution, receipt, case, or persisted scope is inconsistent
     */
    @Transactional
    public SyncAutopilotRecoveryTriggerConsumerResultView recordDeadLettered(
            String eventId,
            Long currentExecutionId) {
        requireIdentity(eventId, currentExecutionId);
        SyncAutopilotRecoveryTriggerOutbox outbox = outboxMapper
                .selectByEventIdAndCurrentExecutionId(eventId, currentExecutionId);
        if (outbox == null) {
            throw conflict("Autopilot dead-letter result does not match a durable outbox");
        }

        SyncAutopilotRecoveryCase recoveryCase = caseCreatedByEvent(eventId, outbox);
        SyncAutopilotRecoveryCaseState resultState = SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED;
        Long caseId = null;
        if (recoveryCase != null) {
            recoveryCase = convergeExecutableCase(
                    eventId,
                    outbox,
                    recoveryCase,
                    DEAD_LETTER_RECEIPT_SUFFIX,
                    DEAD_LETTER_REASON);
            resultState = caseState(recoveryCase.getCaseState());
            caseId = recoveryCase.getCaseId();
        }

        if (hasConsumerResult(outbox)) {
            return replayExistingConsumerResult(eventId, outbox);
        }

        return consumerResultService.recordConsumerResult(
                eventId,
                new SyncAutopilotRecoveryTriggerConsumerResultCommand(
                        SyncAutopilotRecoveryConsumerResultStatus.valueOf(resultState.name()),
                        DEAD_LETTER_REASON,
                        caseId,
                        currentExecutionId,
                        null,
                        null,
                        null,
                        null));
    }

    /**
     * Atomically records an exhausted producer outbox send and converges any already-created executable case.
     *
     * <p>This method is called only after {@link SyncAutopilotRecoveryTriggerOutboxService} has claimed a row and
     * the configured Kafka dispatcher has failed. It first conditionally changes the row from
     * {@code DISPATCHING} to {@code DEAD_LETTER}; the same transaction then reloads the row by its immutable event
     * identity and follows the completed decision receipt to an exact local case. An
     * {@code AUTO_APPROVED}, {@code MANUALLY_APPROVED}, or {@code RECOVERY_STARTED} case receives the ordinary
     * receipt-backed {@code RECOVERY_FAILED -> ATTENTION_REQUIRED} edge. Terminal and approval-waiting cases keep
     * their existing authoritative state.</p>
     *
     * <p>Importantly, a broker acknowledgement failure is not evidence that Agent Runtime consumed anything. This
     * producer method never calls {@link SyncAutopilotRecoveryTriggerConsumerResultService}, never writes
     * {@code consumer_result_*}, and never invents a case when no decision receipt exists. In that no-case state,
     * the durable outbox {@code DEAD_LETTER} fact is projected separately by the public status query as producer
     * attention. Rolling back this independent transaction leaves the row {@code DISPATCHING}, allowing the stale
     * outbox recovery path to attempt convergence again rather than committing a terminal send failure without its
     * required local control-plane update.</p>
     *
     * @param candidate row previously claimed for one broker send attempt; only its durable identifiers are trusted
     * @return {@code true} when this invocation committed the producer dead-letter fact, {@code false} when another
     *         instance had already changed the claimed row
     * @throws PlatformBusinessException when the claimed row, its decision receipt, or its case scope is inconsistent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordProducerDeadLettered(SyncAutopilotRecoveryTriggerOutbox candidate) {
        requireProducerCandidate(candidate);
        int updated = outboxMapper.markFailure(
                candidate.getId(),
                SyncAutopilotRecoveryTriggerOutboxState.DEAD_LETTER.name(),
                null,
                LocalDateTime.now(),
                PRODUCER_DISPATCH_ERROR_CODE,
                PRODUCER_DISPATCH_ERROR_SUMMARY);
        if (updated != 1) {
            return false;
        }

        SyncAutopilotRecoveryTriggerOutbox outbox = outboxMapper.selectByEventIdAndCurrentExecutionId(
                candidate.getEventId(), candidate.getCurrentExecutionId());
        requireProducerDeadLetteredCandidate(candidate, outbox);
        SyncAutopilotRecoveryCase recoveryCase = caseCreatedByEvent(candidate.getEventId(), outbox);
        if (recoveryCase != null) {
            convergeExecutableCase(
                    candidate.getEventId(),
                    outbox,
                    recoveryCase,
                    PRODUCER_DEAD_LETTER_RECEIPT_SUFFIX,
                    PRODUCER_DEAD_LETTER_REASON);
        }
        return true;
    }

    /**
     * Replays a result that data-sync committed before the listener lost its HTTP response or failed afterward.
     *
     * <p>The trigger-result contract is intentionally first-write immutable. DLT convergence must therefore never
     * replace an existing {@code RECOVERY_STARTED} audit fact with a later delivery fact. Reconstructing the exact
     * command and passing it through the normal result service proves the stored digest and every compact field,
     * while the case transition performed earlier in this transaction still exposes the current bounded state.</p>
     */
    private SyncAutopilotRecoveryTriggerConsumerResultView replayExistingConsumerResult(
            String eventId,
            SyncAutopilotRecoveryTriggerOutbox outbox) {
        SyncAutopilotRecoveryConsumerResultStatus status;
        try {
            status = SyncAutopilotRecoveryConsumerResultStatus.valueOf(outbox.getConsumerResultStatus());
        } catch (RuntimeException exception) {
            throw conflict("Autopilot dead-letter consumer result has an invalid status");
        }
        return consumerResultService.recordConsumerResult(
                eventId,
                new SyncAutopilotRecoveryTriggerConsumerResultCommand(
                        status,
                        outbox.getConsumerResultReasonCode(),
                        outbox.getConsumerResultCaseId(),
                        outbox.getCurrentExecutionId(),
                        outbox.getRetrievalDecision(),
                        outbox.getRetrievalStrategy(),
                        outbox.getRetrievalEvidenceCount(),
                        outbox.getRetrievalEvidenceDigest()));
    }

    /**
     * Treats any populated result column as an existing first-write attempt and lets the result service validate it.
     *
     * <p>This mirrors the consumer-result service's fail-closed corruption rule. A partially populated row is not
     * silently completed from DLT data; replay validation rejects it and keeps the dead-letter record uncommitted.</p>
     */
    private boolean hasConsumerResult(SyncAutopilotRecoveryTriggerOutbox outbox) {
        return outbox.getConsumerResultDigest() != null
                || outbox.getConsumerResultStatus() != null
                || outbox.getConsumerResultReasonCode() != null
                || outbox.getConsumerResultCaseId() != null
                || outbox.getRetrievalDecision() != null
                || outbox.getRetrievalStrategy() != null
                || outbox.getRetrievalEvidenceCount() != null
                || outbox.getRetrievalEvidenceDigest() != null
                || outbox.getConsumedAt() != null;
    }

    /**
     * Resolves the exact case created by this event's decision receipt instead of guessing by task recency.
     *
     * <p>No decision receipt means the listener failed before a case was durably created, which is a valid DLT
     * scenario and returns {@code null}. A present receipt must be completed, represent
     * {@code DECISION_RECORDED}, name a positive case ID, and point to a visible case. These checks prevent a
     * similarly scoped but unrelated recovery attempt from being closed by this event.</p>
     *
     * @param eventId outbox event whose deterministic decision receipt is queried
     * @param outbox original outbox facts used for the later scope check
     * @return exact case created by this event, or {@code null} when no decision committed
     */
    private SyncAutopilotRecoveryCase caseCreatedByEvent(
            String eventId,
            SyncAutopilotRecoveryTriggerOutbox outbox) {
        SyncAutopilotRecoveryReceipt decisionReceipt =
                receiptMapper.selectByReceiptId(eventId + DECISION_RECEIPT_SUFFIX);
        if (decisionReceipt == null) {
            return null;
        }
        if (!"COMPLETED".equals(decisionReceipt.getReceiptState())
                || !SyncAutopilotRecoveryReceiptType.DECISION_RECORDED.name()
                .equals(decisionReceipt.getReceiptType())
                || decisionReceipt.getCaseId() == null
                || decisionReceipt.getCaseId() <= 0) {
            throw conflict("Autopilot dead-letter decision receipt is incomplete");
        }
        SyncAutopilotRecoveryCase recoveryCase =
                caseMapper.selectByCaseId(decisionReceipt.getCaseId());
        if (recoveryCase == null) {
            throw conflict("Autopilot dead-letter decision case is unavailable");
        }
        requireCaseScope(outbox, recoveryCase);
        return recoveryCase;
    }

    /**
     * Applies the normal receipt-backed failure edge only when the case can still launch or is running recovery.
     *
     * <p>The caller supplies a fixed suffix and fixed low-sensitive reason for either the consumer-DLT or producer
     * outbox path. The command retains the case's error fingerprint and repeated-error count by supplying
     * {@code null}; a transport delivery failure must not pretend to be a new data error. The returned entity is
     * reloaded from the case mapper, so a following public projection uses the committed state rather than an
     * in-memory prediction. Reusing the same suffix yields the same receipt ID and delegates replay safety to the
     * case service's existing optimistic receipt protocol.</p>
     *
     * @param eventId immutable trigger identity used to derive the deterministic receipt ID
     * @param outbox durable outbox scope supplying execution and cycle facts for the transition
     * @param recoveryCase receipt-selected case to inspect and possibly move to attention
     * @param receiptSuffix fixed consumer or producer suffix appended to {@code eventId}
     * @param attentionReason fixed low-sensitive reason written only when a transition is legal
     * @return reloaded persisted case after a transition, or the original terminal/non-executable case unchanged
     * @throws PlatformBusinessException when a requested transition commits but its case cannot be reloaded safely
     */
    private SyncAutopilotRecoveryCase convergeExecutableCase(
            String eventId,
            SyncAutopilotRecoveryTriggerOutbox outbox,
            SyncAutopilotRecoveryCase recoveryCase,
            String receiptSuffix,
            String attentionReason) {
        SyncAutopilotRecoveryCaseState current = caseState(recoveryCase.getCaseState());
        if (current == SyncAutopilotRecoveryCaseState.AUTO_APPROVED
                || current == SyncAutopilotRecoveryCaseState.MANUALLY_APPROVED
                || current == SyncAutopilotRecoveryCaseState.RECOVERY_STARTED) {
            caseService.recordTransition(new SyncAutopilotRecoveryTransitionCommand(
                    recoveryCase.getCaseId(),
                    recoveryCase.getVersion(),
                    eventId + receiptSuffix,
                    SyncAutopilotRecoveryReceiptType.RECOVERY_FAILED,
                    outbox.getCurrentExecutionId(),
                    outbox.getCycle(),
                    null,
                    null,
                    attentionReason));
            SyncAutopilotRecoveryCase persisted = caseMapper.selectByCaseId(recoveryCase.getCaseId());
            if (persisted == null) {
                throw conflict("Autopilot dead-letter case was not visible after transition");
            }
            requireCaseScope(outbox, persisted);
            return persisted;
        }
        return recoveryCase;
    }

    /**
     * Validates the minimum durable identity carried from the producer's successful outbox claim.
     *
     * <p>The dispatcher owns the mutable payload and retry count, while this transaction needs only the primary
     * key plus immutable event/execution identity to safely mark and reload the row. Rejecting an incomplete
     * candidate before the conditional update prevents an internal caller from issuing a broad state change or
     * converging a case selected from a guessed event ID. The validation is pure and has no persistence side
     * effect.</p>
     *
     * @param candidate previously claimed producer outbox row
     * @throws PlatformBusinessException when the row cannot identify one exact durable trigger
     */
    private void requireProducerCandidate(SyncAutopilotRecoveryTriggerOutbox candidate) {
        if (candidate == null || candidate.getId() == null || candidate.getId() <= 0
                || candidate.getEventId() == null || candidate.getEventId().isBlank()
                || candidate.getEventId().length() > 96
                || candidate.getCurrentExecutionId() == null || candidate.getCurrentExecutionId() <= 0) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "Autopilot producer dead-letter candidate is incomplete");
        }
    }

    /**
     * Proves that the conditional producer update committed the expected immutable row and terminal state.
     *
     * <p>Reloading after the update prevents a stale in-memory candidate from selecting a receipt or case. The
     * identifier pair, database ID, tenant/task lineage, cycle, and {@code DEAD_LETTER} state must all agree.
     * This makes the following case transition depend on the producer's committed local failure fact, not merely
     * on a dispatcher exception that might have lost an optimistic update race.</p>
     *
     * @param candidate row supplied by the dispatcher before the conditional write
     * @param persisted row reloaded from the durable outbox after that write
     * @throws PlatformBusinessException when the stored terminal outbox cannot prove the claimed producer identity
     */
    private void requireProducerDeadLetteredCandidate(
            SyncAutopilotRecoveryTriggerOutbox candidate,
            SyncAutopilotRecoveryTriggerOutbox persisted) {
        if (persisted == null
                || !Objects.equals(candidate.getId(), persisted.getId())
                || !Objects.equals(candidate.getEventId(), persisted.getEventId())
                || !Objects.equals(candidate.getCurrentExecutionId(), persisted.getCurrentExecutionId())
                || !Objects.equals(candidate.getTenantId(), persisted.getTenantId())
                || !Objects.equals(candidate.getProjectId(), persisted.getProjectId())
                || !Objects.equals(candidate.getSyncTaskId(), persisted.getSyncTaskId())
                || !Objects.equals(candidate.getRootExecutionId(), persisted.getRootExecutionId())
                || !Objects.equals(candidate.getCycle(), persisted.getCycle())
                || !SyncAutopilotRecoveryTriggerOutboxState.DEAD_LETTER.name()
                .equals(persisted.getOutboxState())) {
            throw conflict("Autopilot producer dead-letter outbox is inconsistent");
        }
    }

    /**
     * Proves that the receipt-selected case belongs to the original outbox tenant, project, task, and lineage.
     *
     * <p>The decision receipt already gives exact event-to-case linkage. These additional comparisons protect
     * against corrupted receipt data and ensure that an internal caller cannot use a valid event ID to affect a
     * case from another tenant or task. The method is pure and makes no persistence change.</p>
     */
    private void requireCaseScope(
            SyncAutopilotRecoveryTriggerOutbox outbox,
            SyncAutopilotRecoveryCase recoveryCase) {
        if (!Objects.equals(outbox.getTenantId(), recoveryCase.getTenantId())
                || !Objects.equals(outbox.getProjectId(), recoveryCase.getProjectId())
                || !Objects.equals(outbox.getSyncTaskId(), recoveryCase.getSyncTaskId())
                || !Objects.equals(outbox.getRootExecutionId(), recoveryCase.getRootExecutionId())) {
            throw conflict("Autopilot dead-letter case is outside the trigger scope");
        }
    }

    /** Validates the only caller-controlled lookup values before any mapper is invoked. */
    private void requireIdentity(String eventId, Long currentExecutionId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 96
                || currentExecutionId == null || currentExecutionId <= 0) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "Autopilot dead-letter trigger identity is invalid");
        }
    }

    /** Converts a persisted case-state code into the finite lifecycle enum and fails closed on corruption. */
    private SyncAutopilotRecoveryCaseState caseState(String value) {
        try {
            return SyncAutopilotRecoveryCaseState.valueOf(value);
        } catch (RuntimeException exception) {
            throw conflict("Autopilot dead-letter case has an invalid state");
        }
    }

    /** Creates a compact conflict that never echoes event, payload, tenant, task, or case identifiers. */
    private PlatformBusinessException conflict(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, message);
    }
}
