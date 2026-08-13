/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryPolicyDecision.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;

import java.time.LocalDateTime;

/**
 * Deterministic policy decision ready for persistence in a recovery case.
 *
 * <p>The digests bind the decision to the authorization and canonicalized policy without
 * persisting their sensitive or mutable source text.</p>
 *
 * <p>The state tells the case service whether the candidate is auto-approved, waiting for a human, rejected,
 * or must stop for attention; {@code attentionReason} is a stable low-sensitive code for the latter outcome.
 * This immutable value has no side effect and is safe to recompute from the same policy/request facts. It is
 * not an execution permit by itself: durable receipt handling and later integrations enforce lifecycle and
 * authorization boundaries.</p>
 */
public record SyncAutopilotRecoveryPolicyDecision(
        SyncAutopilotRecoveryCaseState state,
        String attentionReason,
        String authorizationDigest,
        String policyDigest,
        int maxCycles,
        LocalDateTime deadlineAt
) {
}
