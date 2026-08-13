/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryAction.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * Whitelisted recovery actions known by data-sync.
 *
 * <p>The boolean is a platform safety ceiling, independent of a task's allowedActions policy.
 * A task may narrow the set further, but it cannot promote replay or backfill to unattended
 * execution merely by putting that action into JSON.</p>
 */
public enum SyncAutopilotRecoveryAction {

    /** Re-run an already configured execution; only this whitelisted low-risk action may be unattended. */
    RETRY_EXECUTION(true),
    /**
     * Quarantine a digest-bound set of retryable dirty rows without deleting or editing source data.
     *
     * <p>This action is low risk only when the dedicated Autopilot endpoint revalidates the persisted preview,
     * exact {@code PRIMARY_KEY_EQ} selectors, task-local authorization, case budget, and durable receipt. The
     * ordinary browser-facing quarantine endpoint keeps its explicit confirmation requirement.</p>
     */
    APPLY_QUARANTINE(true),
    /** Reconnect an external datasource, which may change external-system state and needs approval. */
    RECONNECT_DATASOURCE(false),
    /** Resume from a persisted checkpoint without changing source/target configuration. */
    RESUME_FROM_CHECKPOINT(true),
    /** Replay only previously failed shards under the existing bounded task definition. */
    REPLAY_FAILED_SHARDS(true),
    /** Refresh metadata, requiring approval because discovered shape may change later execution behavior. */
    REFRESH_METADATA(false),
    /** Change a schema contract; never eligible for unattended recovery. */
    CHANGE_SCHEMA(false),
    /** Change connection credentials; never carried or applied by this recovery control plane. */
    CHANGE_CREDENTIAL(false),
    /** Delete data; always requires an explicit human-controlled flow. */
    DELETE_DATA(false),
    /** Overwrite a target; always requires an explicit human-controlled flow. */
    OVERWRITE_TARGET(false),
    /** Broaden source or target data scope; always requires an explicit human-controlled flow. */
    EXPAND_DATA_SCOPE(false),

    /** Compatibility alias for retrying failed objects under the same limited execution scope. */
    RETRY_FAILED_OBJECTS(true),
    /** Compatibility alias retained for old APIs; it still requires approval in this policy ceiling. */
    REPLAY_FROM_CHECKPOINT(false),
    /** Backfill can alter data volume/time scope and therefore always requires approval. */
    BACKFILL_WINDOW(false);

    private final boolean automaticLowRiskWhitelisted;

    SyncAutopilotRecoveryAction(boolean automaticLowRiskWhitelisted) {
        this.automaticLowRiskWhitelisted = automaticLowRiskWhitelisted;
    }

    /**
     * Reports the platform-wide ceiling for unattended low-risk recovery of this action.
     *
     * <p>The flag is independent of a task's policy list: a task can further restrict an action but cannot turn
     * a {@code false} action into automatic work by editing JSON. This read-only enum property is pure and
     * idempotent, has no state effect, and is only one required guard alongside scope, confidence, evidence,
     * risk, cycle, deadline, and persisted authorization checks.</p>
     *
     * @return {@code true} only when this action is in the platform's unattended low-risk whitelist
     */
    public boolean isAutomaticLowRiskWhitelisted() {
        return automaticLowRiskWhitelisted;
    }
}
