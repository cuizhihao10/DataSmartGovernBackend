/**
 * @Author : Cui
 * @Date: 2026/08/11 23:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoverySidecarCompensationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.SyncAutopilotRecoveryTriggerProperties;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoverySidecarCompensation;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoverySidecarCompensationMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists and replays a sidecar operation that failed before the normal Autopilot trigger outbox was known to
 * be durable.
 *
 * <p>The primary receipt publisher uses this service only after isolating an exception from a
 * {@code REQUIRES_NEW} trigger/finalization transaction. Recording is itself a new transaction, so the original
 * execution result and task-management receipt remain independent from both the failed sidecar and its eventual
 * retry. This service is a compact local compensation journal, not a second Kafka outbox: a replay reloads the
 * authoritative entities and calls {@link SyncAutopilotRecoveryTriggerPublisher}, which owns authorization,
 * case transitions, event identity, and the existing durable Kafka outbox.</p>
 *
 * <p>Retry state is bounded and conditionally claimed. The replay is idempotent because both supported target
 * methods use stable receipt/event identities. Logs and rows use only IDs, fixed state/code values, and exception
 * class names; raw exception messages, authorization JSON, issue payloads, SQL, URLs, and credentials are never
 * persisted or logged here.</p>
 */
@Slf4j
@Service
public class SyncAutopilotRecoverySidecarCompensationService {

    private static final int HARD_MAX_BATCH_SIZE = 200;
    private static final int MAX_ISSUE_CODES = 20;

    private static final String TRIGGER_FAILURE = "TRIGGER_FAILURE";
    private static final String SUCCESS_FINALIZATION = "SUCCESS_FINALIZATION";
    private static final String PENDING = "PENDING";
    private static final String RETRY_WAIT = "RETRY_WAIT";
    private static final String DEAD_LETTER = "DEAD_LETTER";

    private final SyncAutopilotRecoverySidecarCompensationMapper compensationMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncAutopilotRecoveryTriggerPublisher triggerPublisher;
    private final SyncAutopilotRecoveryTriggerProperties properties;
    private final ObjectMapper objectMapper;
    private final SyncAutopilotRecoveryMetrics metrics;

    /**
     * Creates the production compensation service with the existing Autopilot publisher, durable journal mapper,
     * and low-cardinality metrics.
     *
     * <p>The constructor merely wires collaborators. It neither evaluates authorization nor sends Kafka; those
     * actions remain inside the normal sidecar publisher when a future scheduler replay wins a durable claim.</p>
     */
    @Autowired
    public SyncAutopilotRecoverySidecarCompensationService(
            SyncAutopilotRecoverySidecarCompensationMapper compensationMapper,
            SyncTaskMapper taskMapper,
            SyncExecutionMapper executionMapper,
            SyncAutopilotRecoveryTriggerPublisher triggerPublisher,
            SyncAutopilotRecoveryTriggerProperties properties,
            ObjectMapper objectMapper,
            SyncAutopilotRecoveryMetrics metrics) {
        this.compensationMapper = compensationMapper;
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.triggerPublisher = triggerPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * Compatibility constructor for unit tests that exercise journal behavior without a Micrometer registry.
     *
     * <p>Production Spring wiring always selects the seven-argument constructor. Keeping this overload avoids
     * forcing focused state-machine tests to construct infrastructure that is irrelevant to their assertions.</p>
     */
    public SyncAutopilotRecoverySidecarCompensationService(
            SyncAutopilotRecoverySidecarCompensationMapper compensationMapper,
            SyncTaskMapper taskMapper,
            SyncExecutionMapper executionMapper,
            SyncAutopilotRecoveryTriggerPublisher triggerPublisher,
            SyncAutopilotRecoveryTriggerProperties properties,
            ObjectMapper objectMapper) {
        this(compensationMapper, taskMapper, executionMapper, triggerPublisher, properties, objectMapper, null);
    }

    /**
     * Records a failed-execution trigger call for later replay after its independent sidecar transaction threw.
     *
     * <p>The row is keyed by the same safe error fingerprint used by the trigger publisher, so duplicate outer
     * callbacks do not accumulate retry rows. This independent transaction stores only normalized codes and the
     * task/execution identifiers. It does not create a recovery case, publish Kafka, retry a worker, or alter the
     * already durable failure result. A scheduler later re-runs the normal publisher, whose policy and bounded
     * loop checks remain authoritative.</p>
     *
     * @param task task passed to the failed sidecar invocation
     * @param execution failed execution passed to the failed sidecar invocation
     * @param errorCode primary low-sensitive error code
     * @param issueCodes bounded secondary low-sensitive error codes
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedTrigger(SyncTask task,
                                    SyncExecution execution,
                                    String errorCode,
                                    List<String> issueCodes) {
        record(task, execution, TRIGGER_FAILURE, errorCode, issueCodes);
    }

    /**
     * Records a successful-execution finalization call for later replay after its independent transaction threw.
     *
     * <p>A replay invokes only {@code publishSucceeded}; it cannot reinterpret a successful execution as a
     * failed trigger or change the task-management COMPLETE receipt. The recovery case service applies the final
     * transition through its existing stable receipt ID, making duplicate scheduler passes harmless. The durable
     * row contains no policy body, model response, or error payload because none is needed to close a case.</p>
     *
     * @param task task passed to the failed finalization sidecar invocation
     * @param execution completed execution passed to the failed finalization sidecar invocation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccessfulFinalization(SyncTask task, SyncExecution execution) {
        record(task, execution, SUCCESS_FINALIZATION, null, List.of());
    }

    /**
     * Replays one bounded batch of durable compensation rows through the existing sidecar publisher.
     *
     * <p>The method first conditionally claims each due row. It then reloads task and execution from persistence
     * instead of trusting stale serialized entities, verifies ownership, and invokes the corresponding existing
     * publisher method. A normal return is resolved even when policy intentionally performs a safe no-op, because
     * the original sidecar has then reevaluated the authoritative facts. A thrown exception becomes a retry-wait
     * or dead letter under the captured attempt budget. The method itself never creates a Kafka message directly.</p>
     *
     * @return number of compensation rows whose existing sidecar invocation returned normally in this pass
     */
    public int replayDue() {
        if (!isCompensationEnabled()) {
            return 0;
        }
        SyncAutopilotRecoveryTriggerProperties.SidecarCompensation configuration = sidecarCompensation();
        int limit = Math.max(1, Math.min(HARD_MAX_BATCH_SIZE, configuration.getBatchSize()));
        long staleSeconds = Math.max(1L, configuration.getStaleDispatchingSeconds());
        int newlyDeadLettered = compensationMapper.deadLetterExhaustedStaleClaims(staleSeconds, limit);
        recordDeadLetters(newlyDeadLettered);
        int resolved = 0;
        for (SyncAutopilotRecoverySidecarCompensation compensation :
                compensationMapper.selectDue(limit, staleSeconds)) {
            if (replayOne(compensation, staleSeconds)) {
                resolved++;
            }
        }
        return resolved;
    }

    /**
     * Claims and replays one persisted request without wrapping the outbound sidecar call in a scheduler-wide
     * transaction.
     *
     * <p>The claim is durable before invoking the nested {@code REQUIRES_NEW} publisher and owns a random token.
     * A process crash after that claim is recovered by the stale-claim query; a crash after the publisher commits
     * but before this row is resolved merely causes an idempotent publisher replay. The token prevents an old,
     * slow claimant from overwriting a newer stale-claim replay. This separation avoids holding a database
     * transaction open around policy evaluation and outbox dispatch preparation.</p>
     *
     * @param compensation due row selected by the mapper
     * @param staleSeconds timeout after which another scheduler pass may reclaim a stranded row
     * @return {@code true} only when the row was claimed and resolved by this invocation
     */
    boolean replayOne(SyncAutopilotRecoverySidecarCompensation compensation, long staleSeconds) {
        String claimToken = UUID.randomUUID().toString();
        if (compensation == null || compensation.getId() == null
                || compensationMapper.markDispatching(compensation.getId(), staleSeconds, claimToken) != 1) {
            return false;
        }
        int attempt = Math.max(0, valueOrZero(compensation.getAttemptCount())) + 1;
        try {
            SyncTask task = taskMapper.selectById(compensation.getSyncTaskId());
            SyncExecution execution = executionMapper.selectById(compensation.getSyncExecutionId());
            requireReplayScope(task, execution, compensation);
            if (TRIGGER_FAILURE.equals(compensation.getOperation())) {
                triggerPublisher.publishFailed(task, execution, safeCode(compensation.getErrorCode()),
                        readIssueCodes(compensation.getIssueCodesJson()));
            } else if (SUCCESS_FINALIZATION.equals(compensation.getOperation())) {
                triggerPublisher.publishSucceeded(task, execution);
            } else {
                throw new IllegalStateException("Autopilot sidecar compensation has an unknown operation");
            }
            if (compensationMapper.markResolved(compensation.getId(), claimToken) != 1) {
                throw new IllegalStateException("Autopilot sidecar compensation state changed concurrently");
            }
            return true;
        } catch (RuntimeException exception) {
            markFailure(compensation, claimToken, attempt, exception);
            return false;
        }
    }

    /**
     * Inserts a new idempotent compensation row when a duplicate has not already been durably observed.
     *
     * <p>This helper is called only from the two transaction entry points above. It normalizes diagnostic input
     * before both hashing and storage, so two equivalent exceptions produce one key without retaining sensitive
     * content. A concurrent insert is intentionally ignored by {@code ON CONFLICT}; the winner's row is enough
     * for a later scheduler pass to perform the idempotent replay.</p>
     */
    private void record(SyncTask task,
                        SyncExecution execution,
                        String operation,
                        String errorCode,
                        List<String> issueCodes) {
        if (!isCompensationEnabled()) {
            return;
        }
        requireRecordScope(task, execution);
        String safeErrorCode = safeCode(errorCode);
        List<String> safeIssueCodes = safeIssueCodes(issueCodes);
        String errorFingerprint = SyncAutopilotRecoveryTriggerPublisher.errorFingerprint(
                safeErrorCode, safeIssueCodes);
        String key = "autopilot-sidecar:" + SyncAutopilotDigestSupport.sha256(
                operation + "|" + task.getId() + "|" + execution.getId() + "|" + errorFingerprint);
        if (compensationMapper.selectByCompensationKey(key) != null) {
            return;
        }
        SyncAutopilotRecoverySidecarCompensation compensation = new SyncAutopilotRecoverySidecarCompensation();
        compensation.setCompensationKey(key);
        compensation.setOperation(operation);
        compensation.setSyncTaskId(task.getId());
        compensation.setSyncExecutionId(execution.getId());
        compensation.setErrorCode(TRIGGER_FAILURE.equals(operation) ? safeErrorCode : null);
        compensation.setIssueCodesJson(writeIssueCodes(safeIssueCodes));
        compensation.setCompensationState(PENDING);
        compensation.setAttemptCount(0);
        compensation.setMaxAttemptCount(Math.max(1, sidecarCompensation().getMaxAttempts()));
        compensationMapper.insertIfAbsent(compensation);
    }

    /**
     * Validates the task/execution identity before recording a retry fact.
     *
     * <p>Recording cannot repair a malformed caller relationship. Rejecting it prevents a later scheduler from
     * creating a cross-task recovery attempt merely because an outer exception was caught. The check is pure and
     * has no persistence or logging side effect.</p>
     */
    private void requireRecordScope(SyncTask task, SyncExecution execution) {
        if (task == null || task.getId() == null || execution == null || execution.getId() == null
                || !Objects.equals(task.getId(), execution.getSyncTaskId())) {
            throw new IllegalArgumentException("Autopilot sidecar compensation requires one task and execution");
        }
    }

    /**
     * Rechecks database-loaded scope before a durable compensation row is allowed to invoke the sidecar.
     *
     * <p>The journal's IDs are only a replay pointer, not authority. This helper proves the reloaded execution
     * still belongs to its task and that both match the row before policy or case logic is called. It changes no
     * state; an inconsistency is handled as a bounded retry failure and is logged without entity contents.</p>
     */
    private void requireReplayScope(SyncTask task,
                                    SyncExecution execution,
                                    SyncAutopilotRecoverySidecarCompensation compensation) {
        if (task == null || execution == null || !Objects.equals(task.getId(), compensation.getSyncTaskId())
                || !Objects.equals(execution.getId(), compensation.getSyncExecutionId())
                || !Objects.equals(task.getId(), execution.getSyncTaskId())) {
            throw new IllegalStateException("Autopilot sidecar compensation scope is no longer valid");
        }
    }

    /**
     * Reads the bounded JSON code list stored by {@link #record(SyncTask, SyncExecution, String, String, List)}.
     *
     * <p>Only a JSON string array is accepted. Every value is normalized once more so a manually corrupted row
     * cannot make the replay pass raw text into the trigger publisher. The method has no write side effect;
     * malformed durable data is treated as a retryable replay failure by the caller rather than silently dropped.</p>
     */
    private List<String> readIssueCodes(String issueCodesJson) {
        try {
            JsonNode root = objectMapper.readTree(issueCodesJson == null ? "[]" : issueCodesJson);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("Autopilot sidecar issue codes are not an array");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isTextual()) {
                    throw new IllegalArgumentException("Autopilot sidecar issue code is not text");
                }
                values.add(safeCode(item.asText()));
                if (values.size() == MAX_ISSUE_CODES) {
                    break;
                }
            }
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Autopilot sidecar issue codes could not be parsed", exception);
        }
    }

    /**
     * Serializes only already-normalized issue codes for the durable replay row.
     *
     * <p>The helper has no database or transport side effect. Serialization failure aborts the surrounding
     * compensation transaction, which leaves no half-written retry intent for the scheduler to misinterpret.</p>
     */
    private String writeIssueCodes(List<String> issueCodes) {
        try {
            return objectMapper.writeValueAsString(issueCodes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Autopilot sidecar issue codes could not be serialized", exception);
        }
    }

    /**
     * Converts a replay exception into a bounded retry timestamp or terminal dead letter.
     *
     * <p>Only a fixed code, fixed summary, IDs, operation, attempt count, state, and exception class name are
     * retained. Normal sidecar failures never escape to the scheduler loop, preserving isolation between rows;
     * eventual retry invokes the idempotent original publisher again rather than inventing a new recovery action.</p>
     */
    private void markFailure(SyncAutopilotRecoverySidecarCompensation compensation,
                             String claimToken,
                             int attempt,
                             RuntimeException exception) {
        SyncAutopilotRecoveryTriggerProperties.SidecarCompensation configuration = sidecarCompensation();
        int maxAttempts = Math.max(1, compensation.getMaxAttemptCount() == null
                ? configuration.getMaxAttempts() : compensation.getMaxAttemptCount());
        boolean deadLetter = attempt >= maxAttempts;
        LocalDateTime now = LocalDateTime.now();
        String targetState = deadLetter ? DEAD_LETTER : RETRY_WAIT;
        int stateUpdated = compensationMapper.markFailure(
                compensation.getId(),
                claimToken,
                targetState,
                deadLetter ? null : now.plusSeconds(backoffSeconds(attempt)),
                deadLetter ? now : null,
                "AUTOPILOT_SIDECAR_REPLAY_FAILED",
                "Autopilot sidecar replay could not be completed");
        if (stateUpdated == 1 && deadLetter) {
            recordDeadLetters(1);
        }
        if (stateUpdated != 1) {
            log.info("Autopilot sidecar compensation ownership changed before replay failure was recorded, "
                            + "compensationKey={}, taskId={}, executionId={}, operation={}, attempt={}",
                    compensation.getCompensationKey(), compensation.getSyncTaskId(), compensation.getSyncExecutionId(),
                    compensation.getOperation(), attempt);
            return;
        }
        log.warn("Autopilot sidecar compensation replay failed, compensationKey={}, taskId={}, executionId={}, "
                        + "operation={}, attempt={}, state={}, exceptionType={}",
                compensation.getCompensationKey(), compensation.getSyncTaskId(), compensation.getSyncExecutionId(),
                compensation.getOperation(), attempt, targetState, exception.getClass().getSimpleName());
    }

    /**
     * Calculates a capped exponential backoff shared by all replay-operation types.
     *
     * <p>Configuration values are normalized and the shift is capped before multiplication, preventing an
     * arithmetic overflow or unbounded delay. This pure helper changes neither the row nor the trigger outbox.</p>
     */
    private long backoffSeconds(int attempt) {
        SyncAutopilotRecoveryTriggerProperties.SidecarCompensation configuration = sidecarCompensation();
        long base = Math.max(1L, configuration.getBaseBackoffSeconds());
        long max = Math.max(base, configuration.getMaxBackoffSeconds());
        int exponent = Math.max(0, Math.min(20, attempt - 1));
        long multiplier = 1L << exponent;
        return base > Long.MAX_VALUE / multiplier ? max : Math.min(max, base * multiplier);
    }

    /**
     * Determines whether the V23 journal is allowed to record or replay work in the current deployment.
     *
     * <p>The global Autopilot switch is checked together with the V23-specific switch. Keeping both checks here
     * prevents an operator who disables all Autopilot recovery from accidentally leaving a background sidecar
     * scheduler active, while still allowing deployments to pause only V23 compensation during maintenance.</p>
     *
     * @return {@code true} only when both the global and V23 compensation paths are enabled
     */
    private boolean isCompensationEnabled() {
        return properties.isEnabled()
                && properties.getSidecarCompensation() != null
                && properties.getSidecarCompensation().isEnabled();
    }

    /**
     * Returns the bound V23 compensation configuration after callers have established that it is enabled.
     *
     * <p>A missing nested configuration is treated as a programming/configuration error instead of silently
     * falling back to the Kafka outbox budget. That fail-closed behavior prevents the local replay journal from
     * inheriting unrelated retry semantics if a deployment accidentally replaces the nested object with null.</p>
     *
     * @return V23-specific retry and scheduler settings
     * @throws IllegalStateException when the required nested settings are absent
     */
    private SyncAutopilotRecoveryTriggerProperties.SidecarCompensation sidecarCompensation() {
        SyncAutopilotRecoveryTriggerProperties.SidecarCompensation configuration =
                properties.getSidecarCompensation();
        if (configuration == null) {
            throw new IllegalStateException("Autopilot sidecar compensation configuration is missing");
        }
        return configuration;
    }

    /**
     * Adds one low-cardinality dead-letter observation for each row finalized in the current pass.
     *
     * <p>The mapper returns a row count rather than identifiers so no task/execution values enter metrics. A
     * bounded scheduler pass can finalize several crash-stranded claims at once; recording one counter increment
     * per row preserves the actual event count while keeping the metric's only label fixed.</p>
     *
     * @param count number of newly dead-lettered compensation rows
     */
    private void recordDeadLetters(int count) {
        if (metrics == null || count <= 0) {
            return;
        }
        for (int index = 0; index < count; index++) {
            metrics.recordSidecarCompensationDeadLetter();
        }
    }

    /**
     * Produces a bounded, enum-like code list suitable for durable storage and a later idempotent replay.
     *
     * <p>The order and duplicate values are preserved because the trigger publisher's error fingerprint treats
     * them as part of its canonical input. The cap prevents a caller from turning a caught exception into an
     * unbounded journal record. This helper is pure and deliberately lossy for arbitrary text.</p>
     */
    private List<String> safeIssueCodes(List<String> issueCodes) {
        if (issueCodes == null || issueCodes.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String issueCode : issueCodes) {
            result.add(safeCode(issueCode));
            if (result.size() == MAX_ISSUE_CODES) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Normalizes arbitrary diagnostic text before it can enter a journal key, row, log, or replay call.
     *
     * <p>Blank input becomes {@code UNKNOWN}; all other text is uppercased, restricted to a small ASCII
     * whitelist, and capped at the database column length. The transformation is pure and idempotent for an
     * already-normalized code, and intentionally discards exception prose rather than retaining it for retries.</p>
     */
    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-:.]", "_");
        return normalized.substring(0, Math.min(96, normalized.length()));
    }

    /** Returns zero for a nullable persisted attempt count without treating a negative value as a valid attempt. */
    private int valueOrZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
