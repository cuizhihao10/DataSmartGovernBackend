/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactEvaluationView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 受控工具动作审批事实评估结果。
 *
 * <p>该视图面向 task-management 和审计台，强调机器可判定字段：
 * approved 决定是否继续执行前链路，retryable 决定是否 defer 等待，decision/issueCodes/evidenceCodes
 * 用于 timeline 和审计聚合。它不包含审批意见正文、工具参数、payload body 或任何敏感上下文。</p>
 *
 * @param approvalFactId 被评估的审批事实 ID。
 * @param approved true 表示审批事实已通过且作用域匹配。
 * @param retryable true 表示当前不应失败，可等待审批事实物化或变为 APPROVED 后重试。
 * @param decision 机器判定码，例如 APPROVED、PENDING、REJECTED、EXPIRED、UNKNOWN、SCOPE_MISMATCH。
 * @param reason 面向排障的人读说明。
 * @param status 审批事实当前状态。
 * @param policyVersion 审批事实绑定的策略版本。
 * @param expiresAt 审批事实过期时间。
 * @param evidenceCodes 低敏通过证据码。
 * @param issueCodes 低敏阻断或等待原因码。
 */
public record AgentToolActionApprovalFactEvaluationView(
        String approvalFactId,
        Boolean approved,
        Boolean retryable,
        String decision,
        String reason,
        String status,
        String policyVersion,
        LocalDateTime expiresAt,
        List<String> evidenceCodes,
        List<String> issueCodes
) {

    public AgentToolActionApprovalFactEvaluationView {
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }
}
