/**
 * @Author : Cui
 * @Date: 2026/06/11 23:30
 * @Description DataSmart Govern Backend - AgentToolActionControlledApprovalEvaluationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.List;

/**
 * permission-admin 返回给 task-management dry-run 的审批事实评估结果。
 *
 * <p>dry-run 只关心三个机器语义：approved 是否继续、retryable 是否 defer、decision/issueCodes
 * 如何写入诊断和 receipt。它不关心审批表结构，也不接收审批意见正文。</p>
 *
 * @param approvalFactId 被评估的审批事实 ID。
 * @param approved true 表示审批事实已批准并且作用域匹配。
 * @param retryable true 表示当前应 defer 等待审批事实，而不是永久失败。
 * @param decision 机器判定码。
 * @param reason 人读说明。
 * @param status 审批事实状态。
 * @param policyVersion 命中的策略版本。
 * @param evidenceCodes 低敏通过证据码。
 * @param issueCodes 低敏等待或阻断原因码。
 */
public record AgentToolActionControlledApprovalEvaluationResult(
        String approvalFactId,
        Boolean approved,
        Boolean retryable,
        String decision,
        String reason,
        String status,
        String policyVersion,
        List<String> evidenceCodes,
        List<String> issueCodes
) {

    public AgentToolActionControlledApprovalEvaluationResult {
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }
}
