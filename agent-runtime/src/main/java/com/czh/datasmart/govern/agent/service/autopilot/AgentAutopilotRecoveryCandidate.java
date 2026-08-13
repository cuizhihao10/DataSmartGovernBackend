/**
 * @Author : Cui
 * @Date: 2026/08/10 23:59
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryCandidate.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.util.Map;

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
        String errorFingerprint,
        Map<String, Object> autopilotRecoveryFacts) {

    /**
     * 保持历史候选构造器的源码兼容性，同时明确新增的重试事实准入条件。
     * 空映射会被有意判定为不具备自动重试资格，避免缺少依据时触发重试。
     */
    public AgentAutopilotRecoveryCandidate(
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
        this(tenantId, applicationId, projectId, userId, agentId, delegationId, action, riskLevel,
                idempotent, repairFingerprint, errorFingerprint, Map.of());
    }
}
