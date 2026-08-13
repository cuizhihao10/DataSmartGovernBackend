/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryQuarantineApplyReceipt.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.util.Locale;

/**
 * Minimal durable receipt returned by data-sync after an autonomous quarantine apply request.
 *
 * <p>The receipt is deliberately separate from the recovery-case view. It proves one fixed apply request reached a
 * durable receipt owner, while the recovery case remains the authority for lifecycle state and optimistic version.
 * A response is executable only when both fields identify a completed apply; all other parsed receipts are handled
 * as an attention-required outcome by the execution service.</p>
 */
public record AgentAutopilotRecoveryQuarantineApplyReceipt(
        String receiptId,
        Long caseId,
        Long syncTaskId,
        Long executionId,
        Integer selectedCount,
        Integer affectedCount,
        String operationState,
        String receiptState,
        String previewDigest,
        String actionFingerprint) {

    /**
     * Reports whether data-sync durably completed the exact quarantine operation.
     *
     * <p>The comparison normalizes only enum-style spelling differences. It does not infer success from an HTTP
     * response, a non-empty receipt ID, or an applied-looking operation alone: both durable receipt state and
     * operation state must be present. This method is pure and has no lifecycle or retry side effect.</p>
     *
     * @return {@code true} only for {@code COMPLETED}/{@code APPLIED}
     */
    public boolean isDurablyApplied() {
        return "COMPLETED".equals(code(receiptState))
                && "APPLIED".equals(code(operationState))
                && selectedCount != null
                && selectedCount > 0
                && selectedCount.equals(affectedCount);
    }

    /**
     * Normalizes a compact remote enum value for a local comparison.
     *
     * <p>The helper intentionally returns an empty string for missing input so incomplete receipts fail closed in
     * {@link #isDurablyApplied()}. It performs no remote parsing or state update.</p>
     *
     * @param value receipt field supplied by data-sync
     * @return uppercase underscore-separated comparison text, or an empty string
     */
    private String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
