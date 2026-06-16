/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactRemoteResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.LocalDateTime;
import java.util.List;

/**
 * permission-admin 审批事实评估结果的 agent-runtime 本地视图。
 *
 * <p>该视图比最终事实包 DTO 更“内部”：它可以接收 permission-admin 返回的 approvalFactId、reason 等字段，
 * 但 {@link AgentToolActionResumeFactBundleService} 在对外响应时会再次裁剪，不把审批事实 ID、审批意见正文、
 * 远程异常 message 或内部 URL 暴露给 Python Runtime/前端/日志采集链路。</p>
 *
 * @param approvalFactId 被评估的审批事实 ID，内部用于排障，不进入事实包公开视图。
 * @param approved true 表示审批事实存在、已批准、未过期且作用域匹配。
 * @param retryable true 表示当前更像“等待事实物化/等待审批完成”，调用方可以稍后重试。
 * @param decision 机器判定码，例如 APPROVED、PENDING、REJECTED、EXPIRED、REMOTE_UNAVAILABLE。
 * @param reason 远端人读说明，服务层不会直接对外透出，避免泄露内部策略细节。
 * @param status permission-admin 中审批事实当前状态。
 * @param policyVersion 审批事实绑定的策略版本。
 * @param expiresAt 审批事实过期时间。
 * @param evidenceCodes 低敏通过证据码。
 * @param issueCodes 低敏阻断或等待原因码。
 */
public record AgentToolActionApprovalFactRemoteResult(
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

    public AgentToolActionApprovalFactRemoteResult {
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }

    /**
     * 构造“本地未评估”的结果。
     *
     * <p>默认配置关闭远程审批事实评估时使用该结果。它不会把 approvalFactId 当作通过事实，
     * 而是明确告诉上层当前缺少服务端验真依据。</p>
     */
    public static AgentToolActionApprovalFactRemoteResult notEvaluated(String issueCode) {
        return new AgentToolActionApprovalFactRemoteResult(
                null,
                false,
                false,
                "NOT_EVALUATED",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(issueCode)
        );
    }

    /**
     * 构造“远端不可用”的结果。
     *
     * <p>这里不保存异常 message，因为异常中可能带有内网地址、Header、token 或 HTTP 原始响应。
     * 对外只保留机器可读 issueCode，详细异常应留在受控日志体系中排查。</p>
     */
    public static AgentToolActionApprovalFactRemoteResult remoteUnavailable(boolean retryable) {
        return new AgentToolActionApprovalFactRemoteResult(
                null,
                false,
                retryable,
                "REMOTE_UNAVAILABLE",
                null,
                null,
                null,
                null,
                List.of(),
                List.of("PERMISSION_ADMIN_APPROVAL_FACT_EVALUATE_UNAVAILABLE")
        );
    }
}
