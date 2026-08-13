/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryCaseState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * Durable states of an autopilot recovery case.
 *
 * <p>{@code AUTO_APPROVED} is still a durable decision rather than proof that a side effect already happened.
 * The agent-runtime recovery executor consumes that decision through internal data-sync APIs: it can apply a
 * receipt-bound quarantine, record {@code RECOVERY_STARTED}, and enqueue an idempotent failed-object retry. The enum
 * itself remains a pure state catalog and never launches an executor, publishes Kafka, invokes Python, or performs I/O.</p>
 */
public enum SyncAutopilotRecoveryCaseState {

    /** Policy permits a low-risk action, but no executor has been started by this state alone. */
    AUTO_APPROVED,
    /** Policy requires an explicit human-controlled approval before recovery can start. */
    WAITING_APPROVAL,
    /** A recorded manual approval may now permit the documented recovery-start receipt. */
    MANUALLY_APPROVED,
    /** A governed recovery execution has started and may later succeed, fail, or be cancelled. */
    RECOVERY_STARTED,
    /** Recovery completed successfully; no further automatic lifecycle edge is allowed. */
    RECOVERED,
    /** Authorization/policy rejected the candidate before recovery execution could start. */
    REJECTED,
    /** A safety guard or failed recovery requires investigation/replanning rather than automatic continuation. */
    ATTENTION_REQUIRED,
    /** A nonterminal case was intentionally stopped; no further automatic lifecycle edge is allowed. */
    CANCELLED;

    /**
     * Indicates whether this state closes the normal recovery lifecycle permanently.
     *
     * <p>Only recovered, rejected, and cancelled cases are terminal here. {@code ATTENTION_REQUIRED} is not
     * terminal because a separate governed human/replanning flow may inspect it, although the ordinary automatic
     * state-machine edges do not resume it. The method is a pure, idempotent classification and does not mutate
     * a case or authorize a transition.</p>
     *
     * @return {@code true} for states with no normal recovery lifecycle continuation
     */
    public boolean isTerminal() {
        return this == RECOVERED || this == REJECTED || this == CANCELLED;
    }
}
