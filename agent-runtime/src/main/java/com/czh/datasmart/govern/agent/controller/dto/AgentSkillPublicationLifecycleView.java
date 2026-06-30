/**
 * @Author : Cui
 * @Date: 2026/06/30 23:17
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Skill 发布生命周期视图。
 *
 * <p>这是管理台、智能网关诊断和未来发布审计页可以消费的低敏视图。
 * 它只返回发布治理字段、状态、时间戳、内容指纹和下一步动作，不返回 prompt、工具参数模板、SQL、样本数据、
 * 模型输出、内部 endpoint 或凭据。</p>
 *
 * @param schemaVersion 视图 schema 版本
 * @param publicationId 发布单 ID
 * @param tenantId 租户 ID，来自请求或 gateway 可信上下文
 * @param projectId 项目 ID，来自请求或 gateway 可信上下文
 * @param skillCode Skill 编码
 * @param version Skill 版本
 * @param displayName 展示名称
 * @param description 低敏能力说明
 * @param domain 治理域
 * @param riskLevel 风险等级
 * @param approvalPolicy 审批策略
 * @param auditRequired 是否要求审计
 * @param tenantScoped 是否声明租户隔离
 * @param projectScoped 是否声明项目隔离
 * @param requiredTools 依赖工具编码
 * @param requiredPermissions 依赖权限编码
 * @param memoryDependencies 记忆依赖类型
 * @param contentFingerprint 内容指纹，用于版本比较和运行时缓存
 * @param status 当前生命周期状态
 * @param allowedNextActions 当前状态下允许的下一步操作
 * @param policyIssues 当前发布单仍存在的治理问题码
 * @param createdBy 创建人
 * @param submittedBy 提交审核人
 * @param reviewedBy 审核人
 * @param deprecatedBy 下线人
 * @param createdAt 创建时间
 * @param submittedAt 提交审核时间
 * @param reviewedAt 审核时间
 * @param deprecatedAt 下线时间
 * @param updatedAt 最近更新时间
 */
public record AgentSkillPublicationLifecycleView(
        String schemaVersion,
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
        String status,
        List<String> allowedNextActions,
        List<String> policyIssues,
        String createdBy,
        String submittedBy,
        String reviewedBy,
        String deprecatedBy,
        Instant createdAt,
        Instant submittedAt,
        Instant reviewedAt,
        Instant deprecatedAt,
        Instant updatedAt
) {
}
