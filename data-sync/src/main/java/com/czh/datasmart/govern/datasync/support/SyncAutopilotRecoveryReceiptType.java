/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryReceiptType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * Low-sensitive receipts accepted by the autopilot recovery case state machine.
 *
 * <p>DECISION_RECORDED is written while creating the case and does not itself transition state.
 * The other receipt types are mapped to one legal state transition by
 * {@code SyncAutopilotRecoveryCaseStateMachine}.</p>
 */
public enum SyncAutopilotRecoveryReceiptType {

    /** Idempotent decision receipt that creates/records a case but does not transition an existing one. */
    DECISION_RECORDED,
    /** Human approval fact moving WAITING_APPROVAL to MANUALLY_APPROVED. */
    MANUAL_APPROVED,
    /** Execution-start fact moving an approved case to RECOVERY_STARTED. */
    RECOVERY_STARTED,
    /** Execution-success fact moving a started case to RECOVERED. */
    RECOVERY_SUCCEEDED,
    /** Failed recovery fact moving an executable case to ATTENTION_REQUIRED. */
    RECOVERY_FAILED,
    /** Explicit stop fact moving an eligible nonterminal case to CANCELLED. */
    CANCELLED
}
