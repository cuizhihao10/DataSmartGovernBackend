/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotRiskLevel.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * Risk classification for a proposed recovery action.
 *
 * <p>Only LOW can become AUTO_APPROVED. HIGH and CRITICAL are still persisted as cases,
 * but must stop at WAITING_APPROVAL so a later human-controlled flow can decide whether to run
 * them.</p>
 */
public enum SyncAutopilotRiskLevel {

    /** Only risk tier that may pass the platform's unattended-recovery ceiling. */
    LOW,
    /** Requires human approval even when the task policy otherwise recognizes the action. */
    HIGH,
    /** Requires human approval and must never be converted to unattended recovery. */
    CRITICAL;

    /**
     * Reports whether this risk tier can enter the automatic-approval branch of policy evaluation.
     *
     * <p>This pure, idempotent classification is intentionally stricter than a task policy: only {@code LOW}
     * returns true, and a true result still requires an active scoped authorization, low-risk action whitelist,
     * evidence, confidence, cycle/deadline budget, and receipt-backed case persistence. It has no side effect
     * and never starts or approves a recovery by itself.</p>
     *
     * @return {@code true} only for {@code LOW}
     */
    public boolean canBeAutomaticallyApproved() {
        return this == LOW;
    }
}
