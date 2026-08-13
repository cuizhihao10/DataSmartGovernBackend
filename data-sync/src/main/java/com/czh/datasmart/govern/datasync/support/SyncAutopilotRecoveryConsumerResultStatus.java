/**
 * @Author : Cui
 * @Date: 2026/08/11 21:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryConsumerResultStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * Finite low-sensitive outcomes that an Autopilot trigger consumer may write back to its source outbox.
 *
 * <p>This enum describes what the authenticated consumer observed. It is not a command to move a recovery case
 * into that state; case transitions continue to be validated by the existing state machine and receipt flow.
 * Keeping the result set finite prevents model prose, exception bodies, or arbitrary consumer text from being
 * stored as a durable callback status.</p>
 */
public enum SyncAutopilotRecoveryConsumerResultStatus {

    /** A case was policy-approved, but this callback does not itself launch a worker. */
    AUTO_APPROVED,
    /** The proposed recovery requires a governed human approval. */
    WAITING_APPROVAL,
    /** A governed manual approval was observed by the consumer. */
    MANUALLY_APPROVED,
    /** The bounded recovery execution was started for the trigger's current execution. */
    RECOVERY_STARTED,
    /** The recovery case was already or newly completed successfully. */
    RECOVERED,
    /** Authorization, scope, or policy permanently rejected the trigger. */
    REJECTED,
    /** The consumer stopped automatic handling and requires investigation or replanning. */
    ATTENTION_REQUIRED,
    /** The governed recovery lifecycle was intentionally cancelled. */
    CANCELLED,
    /** An upstream planner returned a bounded failure result before a case transition could be recorded. */
    FAILED
}
