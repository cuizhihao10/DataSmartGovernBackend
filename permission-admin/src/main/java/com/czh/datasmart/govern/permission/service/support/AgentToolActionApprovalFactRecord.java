/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 受控工具动作审批事实记录。
 *
 * <p>当前记录由内存 store 保存，字段刻意保持低敏。后续迁移到 PostgreSQL 时，可以直接映射为
 * `agent_tool_action_approval_fact` 表：以 approvalFactId 做唯一键，以 tenant/project/run/command/tool
 * 做组合索引，以 expiresAt/status 支持过期清理和审批台查询。</p>
 *
 * @param approvalFactId 审批事实 ID。
 * @param tenantId 租户边界。
 * @param projectId 项目边界。
 * @param actorId 被代表 actor。
 * @param sessionId Agent session ID。
 * @param runId Agent run ID。
 * @param commandId 工具动作 commandId。
 * @param toolCode 工具编码。
 * @param policyVersion 策略版本。
 * @param status 审批状态。
 * @param expiresAt 过期时间。
 * @param approvedByActorId 审批人 ID。
 * @param reasonCodes 低敏原因码。
 * @param evidenceCodes 低敏证据码。
 * @param createdAt 登记时间。
 */
public record AgentToolActionApprovalFactRecord(
        String approvalFactId,
        Long tenantId,
        Long projectId,
        String actorId,
        String sessionId,
        String runId,
        String commandId,
        String toolCode,
        String policyVersion,
        String status,
        LocalDateTime expiresAt,
        String approvedByActorId,
        List<String> reasonCodes,
        List<String> evidenceCodes,
        LocalDateTime createdAt
) {

    public AgentToolActionApprovalFactRecord {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }
}
