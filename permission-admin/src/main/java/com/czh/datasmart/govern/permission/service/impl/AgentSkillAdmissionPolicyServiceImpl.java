/**
 * @Author : Cui
 * @Date: 2026/06/02 19:10
 * @Description DataSmartGovernBackend - AgentSkillAdmissionPolicyServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.controller.dto.AgentSkillAdmissionEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentSkillAdmissionPolicyView;
import com.czh.datasmart.govern.permission.service.AgentSkillAdmissionPolicyService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent Skill 准入策略服务第一版实现。
 *
 * <p>这份实现暂时使用内存规则，而不是数据库策略表。原因和工具预算策略类似：
 * 1. 先让 Python Runtime、gateway、permission-admin 对齐 Skill admission 的字段契约；
 * 2. 先沉淀“权限缺失、角色风险、租户开关、workspace 风险”这些业务语义；
 * 3. 等前端治理卡片、Skill Marketplace、租户级开关和审计需求更稳定后，再落数据库。</p>
 *
 * <p>重要边界：本服务只判断 Skill 是否允许被启用，不执行 Skill、不调用模型、不暴露工具参数。
 * 真正的工具执行仍要走 agent-runtime、task-management、data-sync 等控制面和 worker。</p>
 */
@Service
public class AgentSkillAdmissionPolicyServiceImpl implements AgentSkillAdmissionPolicyService {

    private static final Set<String> HIGH_RISK_ROLES = Set.of(
            "PROJECT_OWNER",
            "TENANT_ADMINISTRATOR",
            "PLATFORM_ADMINISTRATOR",
            "OPERATOR",
            "SERVICE_ACCOUNT"
    );

    @Override
    public AgentSkillAdmissionPolicyView evaluate(AgentSkillAdmissionEvaluateRequest request) {
        List<String> notes = new ArrayList<>();
        Set<String> requiredPermissions = normalizeSet(request.getRequiredPermissions());
        Set<String> grantedPermissions = normalizeSet(request.getGrantedPermissions());
        List<String> satisfiedPermissions = intersection(requiredPermissions, grantedPermissions);
        List<String> missingPermissions = missing(requiredPermissions, grantedPermissions);
        String riskLevel = normalize(request.getRiskLevel(), "LOW");
        String actorRole = normalize(request.getActorRole(), "ORDINARY_USER");
        String workspaceRiskLevel = normalize(request.getWorkspaceRiskLevel(), "NORMAL");

        notes.add("Skill " + request.getSkillCode() + " 开始执行准入评估。");
        notes.add("当前策略为内存规则，后续应迁移到 Skill Marketplace/租户策略表。");

        if (Boolean.FALSE.equals(request.getTenantSkillEnabled())) {
            return denied(request, "DENIED_TENANT_DISABLED", "TENANT_SKILL_DISABLED",
                    "租户已关闭该 Skill，当前请求不允许启用。", requiredPermissions,
                    satisfiedPermissions, missingPermissions, notes,
                    "请租户管理员在 Skill Marketplace 或能力管理台重新启用该 Skill。");
        }

        if (!missingPermissions.isEmpty()) {
            return denied(request, "DENIED_MISSING_PERMISSION", "MISSING_REQUIRED_PERMISSION",
                    "缺少启用 Skill 所需权限：" + String.join("、", missingPermissions), requiredPermissions,
                    satisfiedPermissions, missingPermissions, notes,
                    "请在 permission-admin 中为当前角色补充缺失权限，或改由具备权限的项目负责人处理。");
        }

        if (requiredPermissions.isEmpty() && grantedPermissions.isEmpty()) {
            notes.add("请求未携带 requiredPermissions/grantedPermissions，按条件性通过处理。");
            return conditional(request, requiredPermissions, satisfiedPermissions, missingPermissions, notes,
                    "生产环境应由 gateway 或 permission-admin 注入可信权限事实和策略版本。");
        }

        if (isHighRisk(riskLevel) && "CRITICAL".equals(workspaceRiskLevel)) {
            return denied(request, "DENIED_WORKSPACE_RISK", "CRITICAL_WORKSPACE_BLOCKS_HIGH_RISK_SKILL",
                    "CRITICAL workspace 不允许启用 HIGH/CRITICAL 风险 Skill。", requiredPermissions,
                    satisfiedPermissions, missingPermissions, notes,
                    "请降低 workspace 风险、转人工审批，或改用只读/草稿类 Skill。");
        }

        if (isHighRisk(riskLevel) && !HIGH_RISK_ROLES.contains(actorRole)) {
            return denied(request, "DENIED_RISK_ROLE", "HIGH_RISK_SKILL_ROLE_GUARD",
                    "当前角色 " + actorRole + " 不允许启用 " + riskLevel + " 风险 Skill。", requiredPermissions,
                    satisfiedPermissions, missingPermissions, notes,
                    "请由项目负责人、租户管理员、平台管理员、运营人员或服务账号在受控流程中启用该 Skill。");
        }

        notes.add("Skill 准入通过：权限、租户开关、角色风险和 workspace 风险均未触发拒绝规则。");
        return new AgentSkillAdmissionPolicyView(
                true,
                "ALLOWED",
                "IN_MEMORY_RULE",
                policyVersion(request),
                "ALLOW_BY_BASELINE_RULE",
                null,
                List.copyOf(requiredPermissions),
                satisfiedPermissions,
                missingPermissions,
                List.copyOf(notes),
                List.of("可以把 Skill admission 结果注入 Python Runtime，继续执行工具 schema 暴露和 Agent 规划。")
        );
    }

    private AgentSkillAdmissionPolicyView conditional(AgentSkillAdmissionEvaluateRequest request,
                                                       Set<String> requiredPermissions,
                                                       List<String> satisfiedPermissions,
                                                       List<String> missingPermissions,
                                                       List<String> notes,
                                                       String reason) {
        notes.add(reason);
        return new AgentSkillAdmissionPolicyView(
                true,
                "CONDITIONAL",
                "IN_MEMORY_RULE",
                policyVersion(request),
                "CONDITIONAL_MISSING_PERMISSION_FACTS",
                reason,
                List.copyOf(requiredPermissions),
                satisfiedPermissions,
                missingPermissions,
                List.copyOf(notes),
                List.of("请补充 actorRole、grantedPermissions 和正式 policyVersion，避免生产环境条件性放行。")
        );
    }

    private AgentSkillAdmissionPolicyView denied(AgentSkillAdmissionEvaluateRequest request,
                                                 String status,
                                                 String matchedPolicy,
                                                 String reason,
                                                 Set<String> requiredPermissions,
                                                 List<String> satisfiedPermissions,
                                                 List<String> missingPermissions,
                                                 List<String> notes,
                                                 String recommendedAction) {
        notes.add(reason);
        return new AgentSkillAdmissionPolicyView(
                false,
                status,
                "IN_MEMORY_RULE",
                policyVersion(request),
                matchedPolicy,
                reason,
                List.copyOf(requiredPermissions),
                satisfiedPermissions,
                missingPermissions,
                List.copyOf(notes),
                List.of(recommendedAction)
        );
    }

    private String policyVersion(AgentSkillAdmissionEvaluateRequest request) {
        return "agent-skill-admission:v1:"
                + normalize(request.getSkillCode(), "UNKNOWN_SKILL") + ":"
                + normalize(request.getRiskLevel(), "LOW") + ":"
                + normalize(request.getActorRole(), "ORDINARY_USER") + ":"
                + normalize(request.getWorkspaceRiskLevel(), "NORMAL") + ":"
                + normalize(request.getTenantPlanCode(), "STANDARD") + ":"
                + Boolean.TRUE.equals(request.getTenantSkillEnabled());
    }

    private boolean isHighRisk(String riskLevel) {
        return "HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel);
    }

    private Set<String> normalizeSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            result.add(value.trim());
        }
        return result;
    }

    private List<String> intersection(Set<String> required, Set<String> granted) {
        return required.stream()
                .filter(granted::contains)
                .toList();
    }

    private List<String> missing(Set<String> required, Set<String> granted) {
        return required.stream()
                .filter(permission -> !granted.contains(permission))
                .toList();
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }
}
