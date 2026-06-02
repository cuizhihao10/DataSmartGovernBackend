/**
 * @Author : Cui
 * @Date: 2026/06/02 19:10
 * @Description DataSmartGovernBackend - AgentSkillAdmissionPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent Skill 准入策略视图。
 *
 * <p>该响应会被 Python Runtime、gateway、前端治理卡片和审计系统共同消费。
 * 因此它既要有机器可读字段，也要有可展示的中文说明。</p>
 *
 * <p>安全边界：响应可以包含权限编码摘要和拒绝原因，但不应包含用户 prompt、工具参数、SQL、
 * 样本数据、模型消息或完整 JWT/会话信息。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkillAdmissionPolicyView {

    /**
     * 是否允许本轮启用该 Skill。
     *
     * <p>false 表示 Skill 语义上可能命中，但当前权限、角色、租户开关或 workspace 风险不允许启用。</p>
     */
    private Boolean allowed;

    /**
     * 准入状态。
     *
     * <p>典型值：ALLOWED、CONDITIONAL、DENIED_TENANT_DISABLED、DENIED_MISSING_PERMISSION、
     * DENIED_RISK_ROLE、DENIED_WORKSPACE_RISK。</p>
     */
    private String admissionStatus;

    /**
     * 策略来源。
     *
     * <p>当前为 IN_MEMORY_RULE。后续接数据库策略表、租户套餐、Marketplace 开关后可扩展为
     * TENANT_SKILL_POLICY、MARKETPLACE_POLICY、PROJECT_POLICY 等。</p>
     */
    private String policySource;

    /**
     * 策略版本。
     *
     * <p>用于把 Python Runtime 本轮 Skill admission 和 Java 控制面当时的策略关联起来。
     * 后续进入数据库策略发布后，应替换为正式 policyVersion。</p>
     */
    private String policyVersion;

    /**
     * 命中的策略名称。
     *
     * <p>第一版使用规则名称，例如 TENANT_SKILL_DISABLED、MISSING_REQUIRED_PERMISSION。
     * 后续可映射到数据库策略 ID 或 Marketplace 规则 ID。</p>
     */
    private String matchedPolicy;

    /**
     * 拒绝原因或条件性说明。
     *
     * <p>允许时也可以为空；拒绝或条件性通过时应提供中文原因，便于前端和审计解释。</p>
     */
    private String rejectionReason;

    /**
     * 所需权限摘要。
     *
     * <p>仅返回 Skill descriptor 声明的权限编码，不返回调用主体完整权限全集。</p>
     */
    private List<String> requiredPermissionsSummary;

    /**
     * 已满足权限摘要。
     *
     * <p>只返回 requiredPermissions 中已被 grantedPermissions 覆盖的交集，避免把主体全部权限暴露出去。</p>
     */
    private List<String> satisfiedPermissionsSummary;

    /**
     * 缺失权限摘要。
     *
     * <p>当 allowed=false 且 admissionStatus=DENIED_MISSING_PERMISSION 时，调用方可以用该字段提示管理员补权限。</p>
     */
    private List<String> missingPermissionsSummary;

    /**
     * 策略解释说明。
     */
    private List<String> notes;

    /**
     * 推荐动作。
     */
    private List<String> recommendedActions;
}
