/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryPrincipalContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

/**
 * Dual-principal facts carried by the trusted Agent Runtime call.
 *
 * <p>The represented actor answers whose authorization is being exercised; agent and delegation IDs answer
 * which autonomous subject and initial authorization performed the action. These are audit facts, not a way to
 * broaden task scope, and are accepted only after the internal service token is verified.</p>
 */
public record SyncAutopilotRecoveryPrincipalContext(
        String representedActorId,
        String actorRole,
        String agentId,
        String delegationId,
        String traceId) {
}
