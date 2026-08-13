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
 * <p>记录既可由测试用内存 store 保存，也可映射到 PostgreSQL
 * {@code agent_tool_action_approval_fact} 表。所有字段刻意保持低敏：唯一键定位审批事实，
 * 双主体和运行范围限制谁能使用它，服务端动作指纹绑定允许执行的确切 command/tool，
 * 过期时间和状态则支持审批台查询、失效控制与审计追踪。</p>
 *
 * @param approvalFactId 审批事实 ID。
 * @param tenantId 租户边界。
 * @param applicationId 应用边界，防止不同应用复用同一审批事实。
 * @param projectId 项目边界。
 * @param userId Agent 代表的最终用户。
 * @param actorId 被代表 actor。
 * @param agentId 实际执行受控动作的 Agent。
 * @param sessionId Agent session ID。
 * @param runId Agent run ID。
 * @param delegationId 当前用户授予 Agent 的委托事实 ID。
 * @param commandId 工具动作 commandId。
 * @param toolCode 工具编码。
 * @param actionFingerprint permission-admin 根据可信范围和动作定位字段计算的 SHA-256 绑定。
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
        Long applicationId,
        Long projectId,
        String userId,
        String actorId,
        String agentId,
        String sessionId,
        String runId,
        String delegationId,
        String commandId,
        String toolCode,
        String actionFingerprint,
        String policyVersion,
        String status,
        LocalDateTime expiresAt,
        String approvedByActorId,
        List<String> reasonCodes,
        List<String> evidenceCodes,
        LocalDateTime createdAt
) {

    /**
     * Adapts a pre-fingerprint approval fact to the expanded record shape.
     *
     * <p>The inputs are the complete legacy scope, decision, audit-code, and
     * timestamp fields. The constructor outputs the current record shape by
     * delegating with {@code actionFingerprint = null}. It exists only for source
     * compatibility and migration/test reads: such records remain readable for
     * audit, but evaluation rejects them as authorization evidence until a
     * trusted registration creates a server-calculated fingerprint. This prevents
     * a legacy caller from silently becoming the authority for the new field.</p>
     *
     * @param approvalFactId stable legacy approval fact locator
     * @param tenantId tenant boundary from the legacy fact
     * @param applicationId application boundary from the legacy fact
     * @param projectId project boundary from the legacy fact
     * @param userId human identity represented by the Agent
     * @param actorId acting identity recorded by the legacy workflow
     * @param agentId Agent identity that would consume the decision
     * @param sessionId Agent session locator
     * @param runId Agent run locator
     * @param delegationId legacy delegation proof locator
     * @param commandId controlled command locator
     * @param toolCode controlled tool identifier
     * @param policyVersion policy snapshot tied to the fact
     * @param status lifecycle state recorded by the legacy workflow
     * @param expiresAt optional legacy expiration time
     * @param approvedByActorId actor who made the approval decision
     * @param reasonCodes low-sensitive decision reasons
     * @param evidenceCodes low-sensitive audit evidence
     * @param createdAt original record creation time
     */
    public AgentToolActionApprovalFactRecord(
            String approvalFactId,
            Long tenantId,
            Long applicationId,
            Long projectId,
            String userId,
            String actorId,
            String agentId,
            String sessionId,
            String runId,
            String delegationId,
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
        this(
                approvalFactId,
                tenantId,
                applicationId,
                projectId,
                userId,
                actorId,
                agentId,
                sessionId,
                runId,
                delegationId,
                commandId,
                toolCode,
                null,
                policyVersion,
                status,
                expiresAt,
                approvedByActorId,
                reasonCodes,
                evidenceCodes,
                createdAt
        );
    }

    public AgentToolActionApprovalFactRecord {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }
}
