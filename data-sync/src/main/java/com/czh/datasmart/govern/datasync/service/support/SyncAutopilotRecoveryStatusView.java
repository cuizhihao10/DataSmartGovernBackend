/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryStatusView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.time.LocalDateTime;

/**
 * Browser-safe projection of one sync execution's unattended Autopilot recovery lifecycle.
 *
 * <p>The projection intentionally contains only identifiers, finite state codes, bounded counters, and
 * timestamps. It does not expose authorization or policy digests, repair fingerprints, event payloads,
 * selected sample IDs, raw errors, SQL, credentials, prompts, model output, or tool arguments. This makes the
 * record suitable for the task detail page and for a real E2E poller without turning an operational status API
 * into a second internal audit database.</p>
 *
 * <p>{@code available} becomes true when either a recovery case or a durable trigger outbox fact exists. A
 * trigger can be rejected before a case is created, so treating only {@code caseId != null} as availability
 * would hide an important terminal result from users and operators. {@code producerDeliveryStatus} is intentionally
 * separate from {@code consumerResultStatus}: the former reports a local broker delivery exhaustion, while the
 * latter is reserved for a real Agent Runtime callback accepted by data-sync.</p>
 */
public record SyncAutopilotRecoveryStatusView(
        boolean available,
        Long syncTaskId,
        Long rootExecutionId,
        Long currentExecutionId,
        String executionState,
        LocalDateTime executionFinishedAt,
        Long caseId,
        String caseState,
        Integer cycle,
        Integer maxCycles,
        String recoveryAction,
        String riskLevel,
        String attentionReason,
        LocalDateTime deadlineAt,
        Long version,
        LocalDateTime caseCreatedAt,
        LocalDateTime caseUpdatedAt,
        String outboxState,
        Integer outboxAttemptCount,
        Integer outboxMaxAttemptCount,
        String outboxLastErrorCode,
        String producerDeliveryStatus,
        String producerDeliveryReasonCode,
        String consumerResultStatus,
        String consumerResultReasonCode,
        LocalDateTime consumerResultAt,
        String retrievalDecision,
        String retrievalStrategy,
        Integer retrievalEvidenceCount,
        String retrievalEvidenceDigest,
        Integer quarantineSelectedCount,
        Integer quarantineAffectedCount,
        String quarantineOperationState,
        String quarantineReceiptState,
        LocalDateTime quarantineUpdatedAt) {

    /**
     * Builds the ordinary no-recovery response while still returning the authoritative execution state.
     *
     * @param syncTaskId visible task that owns the execution
     * @param executionId execution inspected by the caller
     * @param executionState current worker-owned execution state
     * @param executionFinishedAt terminal completion time when the execution has already ended
     * @return an unavailable status projection with every recovery-only field absent
     */
    public static SyncAutopilotRecoveryStatusView unavailable(
            Long syncTaskId,
            Long executionId,
            String executionState,
            LocalDateTime executionFinishedAt) {
        return new SyncAutopilotRecoveryStatusView(
                false,
                syncTaskId,
                executionId,
                executionId,
                executionState,
                executionFinishedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
