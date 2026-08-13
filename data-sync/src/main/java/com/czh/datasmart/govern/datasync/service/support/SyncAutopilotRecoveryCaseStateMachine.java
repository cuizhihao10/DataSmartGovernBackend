/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryCaseStateMachine.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import org.springframework.stereotype.Component;

/**
 * Defines every legal durable transition for an autopilot recovery case.
 *
 * <p>The service combines this declaration with an optimistic conditional SQL update. Keeping the
 * graph here makes business legality readable, while the mapper prevents another instance from
 * applying the same transition to a stale state or version.</p>
 */
@Component
public class SyncAutopilotRecoveryCaseStateMachine {

    /**
     * Maps a durable receipt type to the one target state that is legal from the current state.
     *
     * <p>{@code currentState} is the state read from persistence and {@code receiptType} is a named business
     * fact, not a target chosen by a caller. The result is only a state-machine decision: it does not mutate a
     * case, write a receipt, or start recovery work. The service pairs this pure, deterministic calculation
     * with an optimistic conditional update, so retries need a matching receipt and a current version before
     * the durable transition can occur.</p>
     *
     * @param currentState persisted state before the requested lifecycle fact is applied
     * @param receiptType low-sensitive fact that requests a documented transition edge
     * @return the sole legal target state for this state/fact combination
     * @throws IllegalArgumentException when either input is absent or the requested edge is not legal
     */
    public SyncAutopilotRecoveryCaseState targetState(SyncAutopilotRecoveryCaseState currentState,
                                                       SyncAutopilotRecoveryReceiptType receiptType) {
        if (currentState == null || receiptType == null) {
            throw new IllegalArgumentException("Recovery case state and receipt type are required");
        }
        return switch (receiptType) {
            case MANUAL_APPROVED -> requiredTarget(
                    currentState == SyncAutopilotRecoveryCaseState.WAITING_APPROVAL,
                    currentState, receiptType, SyncAutopilotRecoveryCaseState.MANUALLY_APPROVED);
            case RECOVERY_STARTED -> requiredTarget(
                    currentState == SyncAutopilotRecoveryCaseState.AUTO_APPROVED
                            || currentState == SyncAutopilotRecoveryCaseState.MANUALLY_APPROVED,
                    currentState, receiptType, SyncAutopilotRecoveryCaseState.RECOVERY_STARTED);
            case RECOVERY_SUCCEEDED -> requiredTarget(
                    currentState == SyncAutopilotRecoveryCaseState.RECOVERY_STARTED,
                    currentState, receiptType, SyncAutopilotRecoveryCaseState.RECOVERED);
            case RECOVERY_FAILED -> requiredTarget(
                    currentState == SyncAutopilotRecoveryCaseState.AUTO_APPROVED
                            || currentState == SyncAutopilotRecoveryCaseState.MANUALLY_APPROVED
                            || currentState == SyncAutopilotRecoveryCaseState.RECOVERY_STARTED,
                    currentState, receiptType, SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED);
            case CANCELLED -> requiredTarget(!currentState.isTerminal()
                            && currentState != SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED,
                    currentState, receiptType, SyncAutopilotRecoveryCaseState.CANCELLED);
            case DECISION_RECORDED -> throw new IllegalArgumentException(
                    "DECISION_RECORDED creates a case but cannot transition an existing case");
        };
    }

    /**
     * Checks a direct state-to-state edge used by a local guard that diverts executable work to attention.
     *
     * <p>This predicate is intentionally narrower than {@link #targetState(SyncAutopilotRecoveryCaseState,
     * SyncAutopilotRecoveryReceiptType)}: it has no receipt and therefore only answers whether the current
     * state may move to the supplied target. It is pure, idempotent, and side-effect free. A {@code true}
     * result is not authorization to update the database; callers must still apply optimistic locking and
     * record an auditable receipt where the workflow requires one.</p>
     *
     * @param currentState persisted state to inspect
     * @param targetState proposed guard target, usually {@code ATTENTION_REQUIRED} or {@code CANCELLED}
     * @return {@code true} only for a documented non-terminal lifecycle edge
     */
    public boolean allowsTransition(SyncAutopilotRecoveryCaseState currentState,
                                    SyncAutopilotRecoveryCaseState targetState) {
        if (currentState == null || targetState == null) {
            return false;
        }
        return switch (currentState) {
            case AUTO_APPROVED, MANUALLY_APPROVED -> targetState == SyncAutopilotRecoveryCaseState.RECOVERY_STARTED
                    || targetState == SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED
                    || targetState == SyncAutopilotRecoveryCaseState.CANCELLED;
            case RECOVERY_STARTED -> targetState == SyncAutopilotRecoveryCaseState.RECOVERED
                    || targetState == SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED
                    || targetState == SyncAutopilotRecoveryCaseState.CANCELLED;
            case WAITING_APPROVAL -> targetState == SyncAutopilotRecoveryCaseState.MANUALLY_APPROVED
                    || targetState == SyncAutopilotRecoveryCaseState.CANCELLED;
            default -> false;
        };
    }

    /**
     * Returns a preselected target only after the caller's transition predicate has passed.
     *
     * <p>Keeping this small guard separate makes every {@code targetState} switch arm declare its allowed
     * source states next to its target. It is pure and idempotent, performs no lifecycle update, and fails
     * closed instead of allowing a malformed receipt to skip the state-machine boundary.</p>
     *
     * @param allowed result of the transition-specific source-state check
     * @param currentState source state included in a safe diagnostic when the edge is illegal
     * @param receiptType requested receipt fact included in a safe diagnostic when the edge is illegal
     * @param targetState target state to return when the edge is allowed
     * @return {@code targetState} when {@code allowed} is true
     * @throws IllegalArgumentException when the requested edge is not legal
     */
    private SyncAutopilotRecoveryCaseState requiredTarget(boolean allowed,
                                                          SyncAutopilotRecoveryCaseState currentState,
                                                          SyncAutopilotRecoveryReceiptType receiptType,
                                                          SyncAutopilotRecoveryCaseState targetState) {
        if (!allowed) {
            throw new IllegalArgumentException("Illegal autopilot recovery transition: "
                    + currentState + " -> " + receiptType);
        }
        return targetState;
    }
}
