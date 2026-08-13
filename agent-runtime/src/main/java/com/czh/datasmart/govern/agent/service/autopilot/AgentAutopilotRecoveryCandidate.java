/**
 * @Author : Cui
 * @Date: 2026/08/10 23:59
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryCandidate.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

/**
 * Low-sensitive model proposal evaluated against a persisted authorization.
 * The model cannot supply an approval fact or alter the authorization itself.
 */
public record AgentAutopilotRecoveryCandidate(
        Long tenantId,
        Long applicationId,
        Long projectId,
        String userId,
        String agentId,
        String delegationId,
        String action,
        String riskLevel,
        boolean idempotent,
        String repairFingerprint,
        String errorFingerprint) {
}
