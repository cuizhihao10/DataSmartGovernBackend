/**
 * @Author : Cui
 * @Date: 2026/08/11 18:40
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerOutboxService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.SyncAutopilotRecoveryTriggerProperties;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryTriggerOutboxMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryTriggerOutboxState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Autopilot 恢复触发事件的 durable outbox 服务。
 *
 * <p>职责分为三步：先用 eventId 幂等落库；再由条件 UPDATE 认领；最后根据 Kafka 结果写
 * DELIVERED 或指数退避状态。业务失败与消息投递失败由此解耦，Kafka 暂时不可用不会让已经
 * 发生的同步失败事实消失。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncAutopilotRecoveryTriggerOutboxService {

    private static final int HARD_MAX_BATCH_SIZE = 200;

    private final SyncAutopilotRecoveryTriggerOutboxMapper outboxMapper;
    private final SyncAutopilotRecoveryTriggerKafkaDispatcher dispatcher;
    private final SyncAutopilotRecoveryTriggerProperties properties;
    private final ObjectMapper objectMapper;
    private final SyncAutopilotRecoveryDeadLetterService deadLetterService;

    /**
     * Durably records a recovery trigger and optionally asks for its first post-commit delivery attempt.
     *
     * <p>The caller supplies a strongly typed, low-sensitive event; it cannot choose a Kafka topic, arbitrary
     * payload, or endpoint. When the feature is disabled this method has no effect. Otherwise it finds or
     * insert-if-absent creates the row by {@code eventId}, so the same failure event is represented once even
     * when the publisher callback is delivered repeatedly.</p>
     *
     * <p>The durable side effect is the outbox row, not a guarantee that Kafka has already consumed it. Existing
     * {@code DELIVERED} and {@code DEAD_LETTER} rows are terminal for immediate dispatch, while eligible rows
     * are handed to an after-commit dispatch path. A send failure preserves retryable database state for the
     * scheduler. Event validation and the fixed event type form the security boundary: untrusted callers cannot
     * smuggle credentials or arbitrary broker routing through this API.</p>
     *
     * @param event validated low-sensitive trigger facts for one failed execution/recovery cycle
     * @throws PlatformBusinessException when a required outbox record cannot be persisted or reloaded
     */
    public void enqueueAndDispatch(SyncAutopilotRecoveryTriggerEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        requireEvent(event);
        SyncAutopilotRecoveryTriggerOutbox outbox = outboxMapper.selectByEventId(event.eventId());
        if (outbox == null) {
            SyncAutopilotRecoveryTriggerOutbox candidate = toOutbox(event);
            outboxMapper.insertIfAbsent(candidate);
            outbox = outboxMapper.selectByEventId(event.eventId());
        }
        if (outbox == null) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "Autopilot recovery trigger outbox was not visible after insert");
        }
        if (!properties.isImmediateDispatchEnabled()
                || SyncAutopilotRecoveryTriggerOutboxState.DELIVERED.name().equals(outbox.getOutboxState())
                || SyncAutopilotRecoveryTriggerOutboxState.DEAD_LETTER.name().equals(outbox.getOutboxState())) {
            return;
        }
        dispatchAfterCommit(outbox);
    }

    /**
     * Defers an immediate Kafka attempt until the surrounding database transaction has committed.
     *
     * <p>The outbox may be created alongside a recovery-case transition. Registering an {@code afterCommit}
     * callback prevents Agent Runtime from observing an event whose case transaction later rolls back. When no
     * Spring transaction is active, such as a scheduler or focused test, it dispatches immediately because the
     * row is already expected to be durable.</p>
     *
     * <p>This method does not change the row directly; {@link #dispatchOne(SyncAutopilotRecoveryTriggerOutbox)}
     * owns conditional claim/delivery state. Registering more than one callback is safe at the outbox level
     * because only one dispatcher can claim the row. It never serializes or exposes new payload data.</p>
     *
     * @param outbox persisted row eligible for an initial best-effort delivery attempt
     */
    private void dispatchAfterCommit(SyncAutopilotRecoveryTriggerOutbox outbox) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchOne(outbox);
                }
            });
            return;
        }
        dispatchOne(outbox);
    }

    /**
     * Claims and compensates a bounded batch of due outbox records.
     *
     * <p>The configured batch size is clamped by a hard ceiling so recovery from a long Kafka outage cannot
     * flood Agent Runtime or monopolize a scheduler thread. Each selected row is independently claimed and
     * dispatched; another service instance may legitimately win a row, in which case it is not counted as this
     * invocation's delivery. The return value counts broker-confirmed rows only.</p>
     *
     * <p>The method may mutate outbox states through {@code dispatchOne}: success becomes {@code DELIVERED},
     * failure becomes bounded retry state or a producer-owned terminal attention outcome. It is safe to call
     * repeatedly because selection and claim are conditional, but delivery is at least once and consumers must
     * still deduplicate by event ID. Disabled configuration returns zero without reading or writing the database.</p>
     *
     * @return number of rows confirmed delivered by this invocation
     */
    public int dispatchDue() {
        if (!properties.isEnabled()) {
            return 0;
        }
        int limit = Math.max(1, Math.min(HARD_MAX_BATCH_SIZE, properties.getBatchSize()));
        List<SyncAutopilotRecoveryTriggerOutbox> due = outboxMapper.selectDue(
                limit, Math.max(1L, properties.getStaleDispatchingSeconds()));
        int delivered = 0;
        for (SyncAutopilotRecoveryTriggerOutbox outbox : due) {
            if (dispatchOne(outbox)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Conditionally claims one row, sends its fixed payload, and persists the delivery outcome.
     *
     * <p>The supplied row is a candidate only: {@code markDispatching} is the durable concurrency arbitration,
     * so another instance may win and cause a {@code false} result without error. Once claimed, broker success
     * changes the row to {@code DELIVERED}; any exception is converted into retry-wait or producer dead-letter
     * convergence by
     * {@link #markFailure(SyncAutopilotRecoveryTriggerOutbox, int, Exception)}.</p>
     *
     * <p>This method intentionally does not throw normal transport failures to an outer business transaction.
     * Its state updates make repeated scheduler calls safe, though downstream delivery remains at least once.
     * It only uses a previously persisted payload and configured dispatcher, preventing a caller from choosing
     * routing or sensitive message contents at dispatch time.</p>
     *
     * @param outbox due or immediately eligible persisted row; {@code null} or unsaved rows are ignored
     * @return {@code true} only after the broker acknowledgement and durable delivered-state update succeed
     */
    boolean dispatchOne(SyncAutopilotRecoveryTriggerOutbox outbox) {
        if (outbox == null || outbox.getId() == null) {
            return false;
        }
        long staleSeconds = Math.max(1L, properties.getStaleDispatchingSeconds());
        if (outboxMapper.markDispatching(outbox.getId(), staleSeconds) != 1) {
            return false;
        }
        int nextAttempt = Math.max(0, outbox.getAttemptCount() == null ? 0 : outbox.getAttemptCount()) + 1;
        try {
            dispatcher.dispatch(outbox);
            if (outboxMapper.markDelivered(outbox.getId()) != 1) {
                throw new IllegalStateException("Autopilot trigger outbox delivery state changed concurrently");
            }
            return true;
        } catch (Exception exception) {
            markFailure(outbox, nextAttempt, exception);
            return false;
        }
    }

    /**
     * Converts a dispatch exception into a bounded retry schedule or producer-side terminal convergence.
     *
     * <p>The attempt count is compared with the row/configured maximum. Before the limit, the row receives a
     * deterministic exponential retry time. At the limit it delegates the complete terminal mutation to
     * {@link SyncAutopilotRecoveryDeadLetterService#recordProducerDeadLettered(SyncAutopilotRecoveryTriggerOutbox)}.
     * That nested transaction changes the outbox to {@code DEAD_LETTER}
     * and converges an already-created local case together, so no committed producer dead-letter can leave an
     * executable case behind. It deliberately does not write the consumer-result columns because a failed broker
     * acknowledgement is not proof that Agent Runtime received or handled the event.</p>
     *
     * <p>Only a stable code and fixed low-sensitive summary are persisted and logged. The exception body, Kafka
     * address, payload, credentials, and authorization data are deliberately excluded. Calling it again for a
     * later failed claim advances that row's bounded retry lifecycle, never retries an arbitrary caller payload.</p>
     *
     * @param outbox claimed persisted row whose send failed
     * @param attempt one-based dispatch attempt count for this claim
     * @param exception transport/serialization exception used only for its safe class name in logging
     */
    private void markFailure(SyncAutopilotRecoveryTriggerOutbox outbox,
                             int attempt,
                             Exception exception) {
        int maxAttempts = Math.max(1, outbox.getMaxAttemptCount() == null
                ? properties.getMaxAttempts() : outbox.getMaxAttemptCount());
        boolean deadLetter = attempt >= maxAttempts;
        String targetState;
        if (deadLetter) {
            targetState = SyncAutopilotRecoveryTriggerOutboxState.DEAD_LETTER.name();
            deadLetterService.recordProducerDeadLettered(outbox);
        } else {
            targetState = SyncAutopilotRecoveryTriggerOutboxState.RETRY_WAIT.name();
            outboxMapper.markFailure(
                    outbox.getId(),
                    targetState,
                    LocalDateTime.now().plusSeconds(backoffSeconds(attempt)),
                    null,
                    "AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED",
                    "Autopilot recovery trigger could not be delivered");
        }
        log.warn("Autopilot recovery trigger dispatch failed, eventId={}, attempt={}, state={}, exceptionType={}",
                outbox.getEventId(), attempt, targetState, exception.getClass().getSimpleName());
    }

    /**
     * Calculates a capped exponential delay for the next retry attempt.
     *
     * <p>The configured base and maximum are normalized to positive values, and the exponent is capped before
     * shifting to avoid overflow or an unbounded outage delay. This pure helper is deterministic and has no
     * state side effect; it merely supplies the next retry timestamp used by {@link #markFailure}.</p>
     *
     * @param attempt one-based failed attempt count; nonpositive values behave like the first attempt
     * @return delay in seconds, never less than the effective base or greater than the effective maximum
     */
    private long backoffSeconds(int attempt) {
        long base = Math.max(1L, properties.getBaseBackoffSeconds());
        long max = Math.max(base, properties.getMaxBackoffSeconds());
        int exponent = Math.max(0, Math.min(20, attempt - 1));
        long multiplier = 1L << exponent;
        if (base > Long.MAX_VALUE / multiplier) {
            return max;
        }
        return Math.min(max, base * multiplier);
    }

    /**
     * Converts a validated event into a new pending outbox entity with a fixed schema and retry budget.
     *
     * <p>Serialization is delegated to {@link #writeEvent(SyncAutopilotRecoveryTriggerEvent)}, which writes only
     * the event's low-sensitive contract. The returned object has no persistence side effect until the caller
     * invokes insert-if-absent. Equivalent event input yields equivalent business fields, which supports event-ID
     * idempotency; it does not accept a caller-selected broker topic or arbitrary payload.</p>
     *
     * @param event validated recovery trigger contract
     * @return new {@code PENDING} outbox row ready for insertion
     */
    private SyncAutopilotRecoveryTriggerOutbox toOutbox(SyncAutopilotRecoveryTriggerEvent event) {
        SyncAutopilotRecoveryTriggerOutbox outbox = new SyncAutopilotRecoveryTriggerOutbox();
        outbox.setEventId(event.eventId());
        outbox.setTenantId(event.tenantId());
        outbox.setProjectId(event.projectId());
        outbox.setSyncTaskId(event.syncTaskId());
        outbox.setRootExecutionId(event.rootExecutionId());
        outbox.setCurrentExecutionId(event.currentExecutionId());
        outbox.setCycle(event.cycle());
        outbox.setPayloadJson(writeEvent(event));
        outbox.setOutboxState(SyncAutopilotRecoveryTriggerOutboxState.PENDING.name());
        outbox.setAttemptCount(0);
        outbox.setMaxAttemptCount(Math.max(1, properties.getMaxAttempts()));
        return outbox;
    }

    /**
     * Serializes the fixed recovery trigger contract into the payload stored with the outbox row.
     *
     * <p>Serialization happens before insertion so a malformed contract cannot leave a partial JSON record in
     * persistence. The helper is deterministic for an equivalent immutable event and has no I/O other than the
     * in-memory mapper operation. It fails closed with a stable platform error rather than falling back to a
     * hand-built payload that could omit the event identity or security-bound authorization digest.</p>
     *
     * @param event validated event to serialize
     * @return complete JSON payload for the fixed outbox schema
     * @throws PlatformBusinessException when the event cannot be serialized
     */
    private String writeEvent(SyncAutopilotRecoveryTriggerEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "Autopilot recovery trigger could not be serialized");
        }
    }

    /**
     * Validates the minimum identity, scope, cycle, and fingerprint facts required before any outbox write.
     *
     * <p>This is a shallow boundary check, not a second policy evaluation. It ensures an event has a stable
     * idempotency key and enough ownership lineage to be routed/deduplicated safely, while the publisher owns
     * authorization parsing. The method is pure and idempotent; invalid input produces {@code BAD_REQUEST}
     * before an outbox row, Kafka send, or retry state can exist.</p>
     *
     * @param event candidate event to validate
     * @throws PlatformBusinessException when required fields are absent, invalid, or unsafe for the contract
     */
    private void requireEvent(SyncAutopilotRecoveryTriggerEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()
                || event.tenantId() == null || event.syncTaskId() == null
                || event.rootExecutionId() == null || event.currentExecutionId() == null
                || event.cycle() < 1 || event.errorFingerprint() == null
                || !event.errorFingerprint().matches("[0-9a-fA-F]{64}")) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery trigger is incomplete");
        }
    }
}
