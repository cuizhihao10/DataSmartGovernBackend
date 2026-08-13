/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryEvaluationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;

import java.time.LocalDateTime;

/**
 * Inputs available at a recovery decision boundary.
 *
 * <p>The request contains only IDs, bounded counters, enum values, and fingerprints. It must not
 * carry source records, SQL, credentials, prompts, model output, or raw error payloads.</p>
 *
 * <p>This is an in-memory input to the deterministic policy evaluator, not an authorization result or a
 * persistence command. {@code evaluatedAt} and {@code deadlineAt} are UTC-local values: the surrounding
 * PostgreSQL contract uses {@link LocalDateTime} without an offset, so callers must not pass a server-local
 * wall-clock value. {@code evaluatedAt} freezes the clock for reproducible decisions and tests; callers
 * that omit it receive the evaluator's UTC clock. The evaluator validates safety and state boundaries and
 * returns an attention/rejection decision rather than using this record to launch work.</p>
 */
public record SyncAutopilotRecoveryEvaluationRequest(
        SyncAutopilotExecutionMode executionMode,
        Long tenantId,
        Long projectId,
        Long syncTaskId,
        int cycle,
        LocalDateTime deadlineAt,
        String lastErrorFingerprint,
        int repeatedErrorCount,
        SyncAutopilotRecoveryAction action,
        SyncAutopilotRiskLevel riskLevel,
        String repairFingerprint,
        String receiptId,
        int confidenceScore,
        boolean evidenceAvailable,
        boolean automaticRetryFactsVerified,
        LocalDateTime evaluatedAt
) {

    /**
     * 保持现有评估器测试和非重试审批流程的源代码兼容性。自动重试调用方必须使用完整构造器，
     * 以避免意外遗漏 data-sync 持久账本校验。
     */
    public SyncAutopilotRecoveryEvaluationRequest(
            SyncAutopilotExecutionMode executionMode,
            Long tenantId,
            Long projectId,
            Long syncTaskId,
            int cycle,
            LocalDateTime deadlineAt,
            String lastErrorFingerprint,
            int repeatedErrorCount,
            SyncAutopilotRecoveryAction action,
            SyncAutopilotRiskLevel riskLevel,
            String repairFingerprint,
            String receiptId,
            int confidenceScore,
            boolean evidenceAvailable,
            LocalDateTime evaluatedAt) {
        this(executionMode, tenantId, projectId, syncTaskId, cycle, deadlineAt, lastErrorFingerprint,
                repeatedErrorCount, action, riskLevel, repairFingerprint, receiptId, confidenceScore,
                evidenceAvailable, false, evaluatedAt);
    }
}
