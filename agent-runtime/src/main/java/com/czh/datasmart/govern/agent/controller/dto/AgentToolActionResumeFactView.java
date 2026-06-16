/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单个恢复事实的低敏展示视图。
 *
 * <p>事实包视图故意只回答“事实类型是否可用/被拒绝/可重试/缺什么证据”，不返回事实值本身。
 * 例如审批事实只展示 APPROVAL_CONFIRMATION_FACT 是否被 permission-admin 采信，
 * 不展示 approvalFactId、审批意见、审批人、审批备注或权限中心原始 reason。</p>
 *
 * @param factType 事实类型，例如 APPROVAL_CONFIRMATION_FACT、OUTBOX_WRITE_CONFIRMATION。
 * @param source 事实来源，例如 PERMISSION_ADMIN、AGENT_RUNTIME_COMMAND_OUTBOX、RUNTIME_EVENT_PROJECTION。
 * @param status 机器状态：AVAILABLE、MISSING、REJECTED、NOT_EVALUATED、OBSERVED。
 * @param available true 表示该事实类型可被 resume-preview 采信。
 * @param rejected true 表示服务端明确否决该事实类型，调用方自报字段不能覆盖该结论。
 * @param retryable true 表示当前更像“等待事实物化/等待审批完成”，稍后重试可能成功。
 * @param evidenceCodes 低敏通过证据码，不包含事实值和原始 payload。
 * @param issueCodes 低敏问题码，用于前端解释、运维告警和 Python fail-closed 摘要。
 * @param payloadPolicy 本事实视图采用的敏感信息策略。
 * @param checkedAt 服务端检查时间。
 */
public record AgentToolActionResumeFactView(
        String factType,
        String source,
        String status,
        Boolean available,
        Boolean rejected,
        Boolean retryable,
        List<String> evidenceCodes,
        List<String> issueCodes,
        String payloadPolicy,
        Instant checkedAt
) {

    public AgentToolActionResumeFactView {
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }
}
