/**
 * @Author : Cui
 * @Date: 2026/08/11 02:10
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTransitionCommand.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;

/**
 * Low-sensitive optimistic transition command for an existing recovery case.
 *
 * <p>{@code caseId} locates the durable lifecycle record and {@code expectedVersion} prevents an old caller
 * from overwriting a newer transition. {@code receiptType} records the business fact that asks the state
 * machine for a target; callers never provide a target state directly. {@code receiptId} is the durable
 * idempotency key, while nullable execution/cycle/error fields mean "retain the persisted value" when the
 * callback has no new fact to report.</p>
 *
 * <p>The record is immutable and side-effect free. Its service consumer verifies receipt reuse, state legality,
 * scope, and optimistic SQL success before changing the case, so constructing this command grants no authority
 * and cannot advance a state by itself.</p>
 */
public record SyncAutopilotRecoveryTransitionCommand(
        Long caseId,
        Long expectedVersion,
        String receiptId,
        SyncAutopilotRecoveryReceiptType receiptType,
        Long currentExecutionId,
        Integer cycle,
        String errorFingerprint,
        Integer repeatedErrorCount,
        String attentionReason) {
}
