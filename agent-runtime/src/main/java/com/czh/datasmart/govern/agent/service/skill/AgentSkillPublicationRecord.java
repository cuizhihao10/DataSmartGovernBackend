/**
 * @Author : Cui
 * @Date: 2026/06/30 23:11
 * @Description DataSmart Govern Backend - AgentSkillPublicationRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.skill;

import java.time.Instant;
import java.util.List;

/**
 * Agent Skill 发布单内部记录。
 *
 * <p>该记录是 service/store 层使用的“控制面事实”，不是直接暴露给前端的 API DTO。
 * 它只保存发布生命周期所需的低敏元数据：Skill 编码、版本、治理域、风险等级、审批策略、工具依赖、
 * 权限依赖、记忆依赖、状态、操作者和时间戳。它刻意不保存 Skill prompt、工具参数模板、SQL、样本数据、
 * 模型输出、内部 endpoint 或凭据，避免发布市场变成敏感内容仓库。</p>
 *
 * <p>为什么不直接复用 {@code AgentSkillDescriptorView}？
 * descriptor 是“运行时读取 Skill 能力”的只读视图；发布单还需要表达创建人、审核人、下架人、状态流转时间、
 * 拒绝/下线原因和内容指纹。把二者分开，可以让创建发布流程、运行时消费流程和未来数据库表分别演进。</p>
 */
public record AgentSkillPublicationRecord(
        String publicationId,
        String tenantId,
        String projectId,
        String skillCode,
        String version,
        String displayName,
        String description,
        String domain,
        String riskLevel,
        String approvalPolicy,
        Boolean auditRequired,
        Boolean tenantScoped,
        Boolean projectScoped,
        List<String> requiredTools,
        List<String> requiredPermissions,
        List<String> memoryDependencies,
        String contentFingerprint,
        AgentSkillPublicationLifecycleStatus status,
        String createdBy,
        String submittedBy,
        String reviewedBy,
        String deprecatedBy,
        String reviewComment,
        String rejectionReason,
        String deprecationReason,
        Instant createdAt,
        Instant submittedAt,
        Instant reviewedAt,
        Instant deprecatedAt,
        Instant updatedAt
) {

    /**
     * 把草稿推进到审核中。
     *
     * <p>该方法不修改 Skill 元数据，只写入提交人、提交时间和低敏说明。这样做是为了让“提交审核”成为明确
     * 审计动作，而不是悄悄把 DRAFT 状态改成 IN_REVIEW。</p>
     */
    public AgentSkillPublicationRecord submitForReview(String operatorId, String comment, Instant now) {
        return new AgentSkillPublicationRecord(
                publicationId, tenantId, projectId, skillCode, version, displayName, description, domain,
                riskLevel, approvalPolicy, auditRequired, tenantScoped, projectScoped, requiredTools,
                requiredPermissions, memoryDependencies, contentFingerprint,
                AgentSkillPublicationLifecycleStatus.IN_REVIEW,
                createdBy, operatorId, reviewedBy, deprecatedBy, comment, rejectionReason,
                deprecationReason, createdAt, now, reviewedAt, deprecatedAt, now
        );
    }

    /**
     * 把审核中发布单推进为 READY。
     *
     * <p>READY 只代表“发布目录资格已通过”，并不代表某次 Agent 会话可以直接执行该 Skill。
     * 真正执行时仍需要 gateway、permission-admin、tool readiness、审批/澄清和工具 outbox 共同确认。</p>
     */
    public AgentSkillPublicationRecord approve(String operatorId, String comment, Instant now) {
        return new AgentSkillPublicationRecord(
                publicationId, tenantId, projectId, skillCode, version, displayName, description, domain,
                riskLevel, approvalPolicy, auditRequired, tenantScoped, projectScoped, requiredTools,
                requiredPermissions, memoryDependencies, contentFingerprint,
                AgentSkillPublicationLifecycleStatus.READY,
                createdBy, submittedBy, operatorId, deprecatedBy, comment, rejectionReason,
                deprecationReason, createdAt, submittedAt, now, deprecatedAt, now
        );
    }

    /**
     * 把审核中发布单拒绝。
     *
     * <p>拒绝不删除记录，因为商业化发布治理需要保留“为什么没有上线”的证据。后续如果用户调整 Skill，
     * 建议新建版本重新提交，避免覆盖旧审批链。</p>
     */
    public AgentSkillPublicationRecord reject(String operatorId, String reason, Instant now) {
        return new AgentSkillPublicationRecord(
                publicationId, tenantId, projectId, skillCode, version, displayName, description, domain,
                riskLevel, approvalPolicy, auditRequired, tenantScoped, projectScoped, requiredTools,
                requiredPermissions, memoryDependencies, contentFingerprint,
                AgentSkillPublicationLifecycleStatus.REJECTED,
                createdBy, submittedBy, operatorId, deprecatedBy, reviewComment, reason,
                deprecationReason, createdAt, submittedAt, now, deprecatedAt, now
        );
    }

    /**
     * 把 READY 发布单下线为 DEPRECATED。
     *
     * <p>DEPRECATED 保留内容指纹和历史元数据，便于排查“某次运行为什么曾经能看到这个 Skill”，也便于未来做
     * 版本回滚、客户环境差异对比和审计报表。</p>
     */
    public AgentSkillPublicationRecord deprecate(String operatorId, String reason, Instant now) {
        return new AgentSkillPublicationRecord(
                publicationId, tenantId, projectId, skillCode, version, displayName, description, domain,
                riskLevel, approvalPolicy, auditRequired, tenantScoped, projectScoped, requiredTools,
                requiredPermissions, memoryDependencies, contentFingerprint,
                AgentSkillPublicationLifecycleStatus.DEPRECATED,
                createdBy, submittedBy, reviewedBy, operatorId, reviewComment, rejectionReason,
                reason, createdAt, submittedAt, reviewedAt, now, now
        );
    }
}
