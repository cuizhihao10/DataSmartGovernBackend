/**
 * @Author : Cui
 * @Date: 2026/06/02 19:10
 * @Description DataSmartGovernBackend - AgentSkillAdmissionEvaluateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Agent Skill 准入评估请求。
 *
 * <p>Skill admission 和普通路由权限 evaluate 不是同一层问题：
 * 普通权限 evaluate 关注“某个 HTTP 路由或资源动作是否允许访问”；
 * Skill admission 关注“某个 Agent 能力包是否允许在本轮被启用，并进一步影响工具 schema 暴露、记忆依赖和执行链路”。</p>
 *
 * <p>这个 DTO 保留了 Skill 自身声明、调用主体上下文和租户开关三个维度：
 * 1. Skill 自身声明：skillCode、riskLevel、requiredPermissions；
 * 2. 调用主体上下文：actorRole、grantedPermissions；
 * 3. 租户治理上下文：tenantSkillEnabled、workspaceRiskLevel、tenantPlanCode。</p>
 *
 * <p>当前第一版不会访问数据库，也不会直接读取 Skill Marketplace 配置。
 * 先稳定契约，是为了让 Python Runtime、gateway 和后续管理后台对齐字段语义。</p>
 */
@Data
public class AgentSkillAdmissionEvaluateRequest {

    /**
     * 租户 ID。
     *
     * <p>当前内存规则不会直接查询租户表，但该字段会进入策略版本摘要。
     * 后续接租户级 Skill 开关、套餐能力矩阵、客户合同权限时，需要用它定位租户上下文。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>同一租户下不同项目可能有不同 workspace 风险、数据范围和项目成员角色。
     * 当前字段主要用于审计和策略版本，后续可接项目级 Skill 开关。</p>
     */
    private String projectId;

    /**
     * workspace 隔离键。
     *
     * <p>Agent 的工具、记忆、缓存和产物已经按 workspace 隔离。
     * Skill 准入也需要理解 workspace，因为高敏 workspace 可能禁止某些自动化能力包。</p>
     */
    private String workspaceKey;

    /**
     * Skill 稳定编码。
     *
     * <p>必填。它是 Skill Marketplace、Python Runtime、审计事件和前端治理卡片之间的关联主键。</p>
     */
    @NotBlank(message = "skillCode 不能为空")
    private String skillCode;

    /**
     * Skill 风险等级。
     *
     * <p>推荐值：LOW、MEDIUM、HIGH、CRITICAL。
     * 高风险 Skill 不应只凭语义命中启用，必须结合角色、权限和租户开关判断。</p>
     */
    private String riskLevel;

    /**
     * Skill 所需权限编码。
     *
     * <p>这些权限来自 Skill descriptor，例如 quality:rule:draft、task:create。
     * permission-admin 只判断调用方是否拥有这些权限，不直接执行任何工具。</p>
     */
    private List<String> requiredPermissions;

    /**
     * 调用主体已被授予的权限编码摘要。
     *
     * <p>这里接收的是“可信控制面事实”，不应由普通前端用户自行填写。
     * 后续推荐由 gateway、agent-runtime 或 permission-admin 自身根据 JWT/会话/角色查询后注入。</p>
     */
    private List<String> grantedPermissions;

    /**
     * 调用主体角色。
     *
     * <p>推荐值来自 PermissionRoleCode，例如 ORDINARY_USER、PROJECT_OWNER、OPERATOR、AUDITOR、
     * TENANT_ADMINISTRATOR、PLATFORM_ADMINISTRATOR、SERVICE_ACCOUNT。</p>
     */
    private String actorRole;

    /**
     * 租户级 Skill 开关。
     *
     * <p>Marketplace 或管理后台上线后，租户管理员可能关闭某个 Skill。
     * 当前字段用于提前固定契约：false 时即使权限满足，也应拒绝准入。</p>
     */
    private Boolean tenantSkillEnabled = Boolean.TRUE;

    /**
     * workspace 风险等级。
     *
     * <p>推荐值：LOW、NORMAL、HIGH、CRITICAL。
     * CRITICAL workspace 对 HIGH/CRITICAL Skill 默认强拒绝，避免高敏空间自动启用高风险能力包。</p>
     */
    private String workspaceRiskLevel;

    /**
     * 租户套餐编码。
     *
     * <p>当前只做策略版本与解释预留，后续可接 FREE/STANDARD/ENTERPRISE/PLATFORM_INTERNAL 的能力矩阵。</p>
     */
    private String tenantPlanCode;
}
