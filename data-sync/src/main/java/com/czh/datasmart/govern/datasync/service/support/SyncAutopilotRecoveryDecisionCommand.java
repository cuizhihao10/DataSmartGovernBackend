/**
 * @Author : Cui
 * @Date: 2026/08/11 02:10
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryDecisionCommand.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Strongly typed, low-sensitive facts used to persist or replay one governed recovery decision.
 *
 * <p>The controller maps its wire DTO into this command after basic shape and enum validation. The service
 * then treats {@code tenantId}, task/execution IDs, and {@code receiptId} as security and idempotency inputs:
 * it reloads persisted ownership and policy rather than trusting this value object as authority. Fingerprints,
 * counters, action, risk, confidence, and evidence describe a candidate decision without carrying raw error
 * content, SQL, credentials, prompts, or model output.</p>
 *
 * <p>Creating the record has no side effect and does not normalize or validate all fields. Identical command
 * facts produce the same receipt digest; changing a fact while reusing {@code receiptId} is intentionally a
 * conflict at the persistence boundary.</p>
 */
public record SyncAutopilotRecoveryDecisionCommand(
        Long tenantId,
        Long projectId,
        Long syncTaskId,
        Long rootExecutionId,
        Long currentExecutionId,
        int cycle,
        LocalDateTime deadlineAt,
        String errorFingerprint,
        int repeatedErrorCount,
        SyncAutopilotRecoveryAction action,
        SyncAutopilotRiskLevel riskLevel,
        String repairFingerprint,
        String receiptId,
        int confidenceScore,
        boolean evidenceAvailable,
        Map<String, Object> autopilotRecoveryFacts) {

    /**
     * 保持旧的进程内调用方源代码兼容。空事实映射刻意不具备无人值守重试资格；案例服务还会根据
     * 当前 execution 的持久账本再次校验。
     */
    public SyncAutopilotRecoveryDecisionCommand(
            Long tenantId,
            Long projectId,
            Long syncTaskId,
            Long rootExecutionId,
            Long currentExecutionId,
            int cycle,
            LocalDateTime deadlineAt,
            String errorFingerprint,
            int repeatedErrorCount,
            SyncAutopilotRecoveryAction action,
            SyncAutopilotRiskLevel riskLevel,
            String repairFingerprint,
            String receiptId,
            int confidenceScore,
            boolean evidenceAvailable) {
        this(tenantId, projectId, syncTaskId, rootExecutionId, currentExecutionId, cycle, deadlineAt,
                errorFingerprint, repeatedErrorCount, action, riskLevel, repairFingerprint, receiptId,
                confidenceScore, evidenceAvailable, Map.of());
    }
}
