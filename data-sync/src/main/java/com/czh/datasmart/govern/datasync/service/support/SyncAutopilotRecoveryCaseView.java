/**
 * @Author : Cui
 * @Date: 2026/08/11 02:10
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryCaseView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;

/**
 * Read-only, browser-safe and service-safe summary of one persisted recovery case.
 *
 * <p>This value is assembled after a decision or transition commits (or an identical receipt is replayed).
 * It exposes the control-plane identifiers, lifecycle state, optimistic version, bounded-cycle information,
 * action code, and safe attention reason needed by the next internal caller. It deliberately excludes the
 * policy body, authorization text, evidence, error payload, source data, credentials, and model output.</p>
 *
 * <p>The record itself has no persistence or state-transition side effect. Callers must use {@code version}
 * as the next expected version rather than inferring that a repeated response advanced the case again.</p>
 */
public record SyncAutopilotRecoveryCaseView(
        Long caseId,
        Long syncTaskId,
        Long rootExecutionId,
        Long currentExecutionId,
        SyncAutopilotRecoveryCaseState state,
        Long version,
        Integer cycle,
        Integer maxCycles,
        String recoveryAction,
        String attentionReason,
        String authorizationDigest,
        String policyDigest) {
}
