"""会话级 Skill 可见性快照。

全局 Skill Manifest 解决“平台发布了哪些能力”；会话级可见性快照解决“当前用户、当前 workspace、
当前权限事实和当前预算下，模型实际可以看见哪些能力”。这两个层次必须分开，否则产品会出现一个常见问题：
后台市场显示有某个 Skill，但当前会话因为权限、角色、租户开关或风险策略不能使用，模型却仍然看到了它。

本模块只根据已经完成的 `AgentSkillPlan` 生成低敏摘要，不重新拉 Manifest、不重新请求 permission-admin。
这样可以保证响应中的可见 Skill 与本轮 AgentPlan 真正使用的 Skill 一致，避免二次决策漂移。
"""

from __future__ import annotations

from collections import Counter
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.skills import AgentSkillSelection


def build_session_skill_visibility_snapshot(
    plan: AgentPlan,
    request: AgentRequest,
    workspace: dict[str, Any],
    skill_admission: dict[str, Any],
    tool_budget: dict[str, Any],
    model_gateway: dict[str, Any],
) -> dict[str, Any]:
    """构建会话级 Skill 能力快照。

    参数说明：
    - `plan`：本轮 Agent 编排结果，提供已经选中和被拒绝的 Skill；
    - `request`：原始请求，只用于解释租户/项目和可信控制面事实来源；
    - `workspace`：响应组装层生成的 workspace 摘要；
    - `skill_admission`：智能网关已经构建好的 Skill 准入摘要；
    - `tool_budget/model_gateway`：用于说明 Skill 可见性是否还受到预算或模型可用性影响。

    返回值是前端治理卡片、Java gateway、runtime event 或后续 WebSocket 会话状态可以直接消费的低敏对象。
    """

    selected = plan.skill_plan.selected_skills
    rejected = plan.skill_plan.rejected_skills
    trusted_context = _skill_visibility_trust_context(request.variables or {})
    visibility_filters = {
        "tenantId": request.tenant_id,
        "projectId": request.project_id,
        "workspaceKey": workspace["workspaceKey"],
        "permissionFactSource": trusted_context["permissionFactSource"],
        "actorRoleSource": trusted_context["actorRoleSource"],
        "actorRole": trusted_context["actorRole"],
        "grantedPermissionCount": trusted_context["grantedPermissionCount"],
        "tenantSkillEnabled": trusted_context["tenantSkillEnabled"],
        "workspaceRiskLevel": trusted_context["workspaceRiskLevel"],
        "tenantPlanCode": trusted_context["tenantPlanCode"],
        "policyVersion": trusted_context["policyVersion"],
        "legacyRequestVariablesDetected": trusted_context["legacyRequestVariablesDetected"],
        "modelGatewayAvailable": bool(model_gateway.get("available")),
        "toolBudgetAllowed": bool(tool_budget.get("allowed")),
    }
    visible_skills = tuple(_visible_skill_summary(item) for item in selected)
    hidden_skills = tuple(_hidden_skill_summary(item) for item in rejected)
    risk_level_counts = Counter(item.risk_level for item in selected)
    hidden_status_counts = Counter(item.admission_status for item in rejected)
    domain_counts = Counter(item.domain.value for item in selected)
    conditional_count = skill_admission["conditionalSkillCount"]
    return {
        "snapshotType": "SESSION_SKILL_VISIBILITY_SNAPSHOT",
        "snapshotSource": "agent-plan-skill-admission",
        "available": not hidden_skills and bool(tool_budget.get("allowed")) and bool(model_gateway.get("available")),
        "availableSkillCount": plan.skill_plan.available_skill_count,
        "visibleSkillCount": len(visible_skills),
        "hiddenSkillCount": len(hidden_skills),
        "conditionalVisibleSkillCount": conditional_count,
        "visibilityFilters": visibility_filters,
        "visibleSkills": visible_skills,
        "hiddenSkills": hidden_skills,
        "visibleRiskLevelCounts": dict(sorted(risk_level_counts.items())),
        "visibleDomainCounts": dict(sorted(domain_counts.items())),
        "hiddenAdmissionStatusCounts": dict(sorted(hidden_status_counts.items())),
        "displaySummary": _skill_visibility_display_summary(visible_skills, hidden_skills, conditional_count),
        "recommendedActions": _skill_visibility_recommended_actions(
            visible_skills,
            hidden_skills,
            conditional_count,
            trusted_context,
            tool_budget,
        ),
    }


def _visible_skill_summary(selection: AgentSkillSelection) -> dict[str, Any]:
    """构建可见 Skill 的低敏摘要。

    不直接返回 admissionReasons，是因为它可能包含策略说明或权限编码；完整解释已经在
    `skillAdmission.selectedSkills` 中保留。会话级可见性快照更偏“能力目录卡片”，只保留数量和状态。
    """

    return {
        "skillCode": selection.skill_code,
        "displayName": selection.display_name,
        "domain": selection.domain.value,
        "riskLevel": selection.risk_level,
        "admissionStatus": selection.admission_status,
        "requiredToolCount": len(selection.required_tools),
        "requiredPermissionCount": len(selection.required_permissions),
        "memoryDependencyCount": len(selection.memory_dependencies),
        "requiresApproval": _skill_requires_approval(selection),
        "score": selection.score,
    }


def _hidden_skill_summary(selection: AgentSkillSelection) -> dict[str, Any]:
    """构建被隐藏 Skill 的低敏摘要。"""

    return {
        "skillCode": selection.skill_code,
        "displayName": selection.display_name,
        "domain": selection.domain.value,
        "riskLevel": selection.risk_level,
        "admissionStatus": selection.admission_status,
        "requiredToolCount": len(selection.required_tools),
        "requiredPermissionCount": len(selection.required_permissions),
        "memoryDependencyCount": len(selection.memory_dependencies),
        "hideReasonCount": len(selection.admission_reasons),
    }


def _skill_visibility_display_summary(
    visible_skills: tuple[dict[str, Any], ...],
    hidden_skills: tuple[dict[str, Any], ...],
    conditional_count: int,
) -> str:
    """生成会话 Skill 快照卡片摘要。"""

    if hidden_skills:
        return "当前会话存在语义命中但被权限、角色或风险策略隐藏的 Skill。"
    if conditional_count:
        return "当前会话 Skill 已可见，但部分能力缺少可信控制面事实，只能条件性启用。"
    if visible_skills:
        return "当前会话已生成可见 Skill 能力快照。"
    return "当前会话未生成明确可见 Skill，Agent 将退回通用工具规划。"


def _skill_visibility_recommended_actions(
    visible_skills: tuple[dict[str, Any], ...],
    hidden_skills: tuple[dict[str, Any], ...],
    conditional_count: int,
    trusted_context: dict[str, Any],
    tool_budget: dict[str, Any],
) -> tuple[str, ...]:
    """生成会话 Skill 快照的推荐动作。"""

    actions: list[str] = []
    if hidden_skills:
        actions.append("检查 hiddenSkills 的 admissionStatus，并由 permission-admin 补齐权限、角色、租户开关或风险策略。")
    if conditional_count:
        actions.append("当前存在条件性可见 Skill；生产环境应由 gateway 注入 trustedControlPlane.skillAdmission。")
    if trusted_context["legacyRequestVariablesDetected"]:
        actions.append("检测到旧式请求变量中的角色或权限事实；生产环境应迁移到 trustedControlPlane 保留命名空间。")
    if not tool_budget.get("allowed", True):
        actions.append("工具预算已阻断部分候选；建议缩小当前会话可见 Skill 或拆分高风险工具步骤。")
    if not visible_skills and not hidden_skills:
        actions.append("本轮未命中 Skill；可增加 triggerKeywords、优化意图识别或补充治理域 Skill。")
    if not actions:
        actions.append("可以把当前会话可见 Skill 快照写入 Java 控制面或 runtime event，便于后续 replay。")
    return tuple(actions)


def _skill_visibility_trust_context(variables: Mapping[str, Any]) -> dict[str, Any]:
    """提取 Skill 可见性快照需要的可信事实来源摘要。

    这里的核心不是“相信变量中的权限”，而是把事实来源讲清楚。
    响应只暴露权限数量，不暴露权限编码明细，避免诊断面变成权限清单泄漏面。
    """

    trusted_root = variables.get("trustedControlPlane")
    trusted_skill = trusted_root.get("skillAdmission") if isinstance(trusted_root, Mapping) else None
    legacy_detected = any(key in variables for key in ("actorRole", "actor_role", "role", "grantedPermissions", "granted_permissions"))
    if isinstance(trusted_skill, Mapping):
        granted = _string_tuple(trusted_skill.get("grantedPermissions") or trusted_skill.get("granted_permissions"))
        actor_role = _string_value(trusted_skill, "actorRole", "actor_role", "role") or "UNKNOWN"
        return {
            "permissionFactSource": "trusted-control-plane",
            "actorRoleSource": "trusted-control-plane",
            "actorRole": actor_role,
            "grantedPermissionCount": len(granted),
            "tenantSkillEnabled": _bool_value(trusted_skill, True, "tenantSkillEnabled", "tenant_skill_enabled"),
            "workspaceRiskLevel": _string_value(trusted_skill, "workspaceRiskLevel", "workspace_risk_level") or "NORMAL",
            "tenantPlanCode": _string_value(trusted_skill, "tenantPlanCode", "tenant_plan_code") or "STANDARD",
            "policyVersion": _string_value(trusted_skill, "policyVersion", "policy_version"),
            "legacyRequestVariablesDetected": legacy_detected,
        }
    if legacy_detected:
        granted = _string_tuple(variables.get("grantedPermissions") or variables.get("granted_permissions"))
        return {
            "permissionFactSource": "legacy-request-variables",
            "actorRoleSource": "legacy-request-variables",
            "actorRole": _string_value(variables, "actorRole", "actor_role", "role") or "UNKNOWN",
            "grantedPermissionCount": len(granted),
            "tenantSkillEnabled": _bool_value(variables, True, "tenantSkillEnabled", "tenant_skill_enabled"),
            "workspaceRiskLevel": _string_value(variables, "workspaceRiskLevel", "workspace_risk_level") or "NORMAL",
            "tenantPlanCode": _string_value(variables, "tenantPlanCode", "tenant_plan_code") or "STANDARD",
            "policyVersion": _string_value(variables, "policyVersion", "policy_version"),
            "legacyRequestVariablesDetected": True,
        }
    return {
        "permissionFactSource": "missing",
        "actorRoleSource": "missing",
        "actorRole": "UNKNOWN",
        "grantedPermissionCount": 0,
        "tenantSkillEnabled": True,
        "workspaceRiskLevel": "NORMAL",
        "tenantPlanCode": "STANDARD",
        "policyVersion": None,
        "legacyRequestVariablesDetected": False,
    }


def _skill_requires_approval(selection: AgentSkillSelection) -> bool:
    """根据审批策略和风险等级判断可见 Skill 是否需要确认。"""

    approval_policy = str(selection.approval_policy or "NONE").upper()
    risk_level = str(selection.risk_level or "LOW").upper()
    return approval_policy not in {"", "NONE", "AUDIT_ONLY"} or risk_level in {"HIGH", "CRITICAL"}


def _string_value(mapping: Mapping[str, Any], *keys: str) -> str | None:
    """读取非空字符串。"""

    for key in keys:
        if key in mapping:
            value = mapping[key]
            if value is None:
                return None
            text = str(value).strip()
            return text or None
    return None


def _string_tuple(value: object | None) -> tuple[str, ...]:
    """把列表或逗号分隔文本转换为字符串元组。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return tuple(item.strip() for item in value.split(",") if item.strip())
    if isinstance(value, (list, tuple, set, frozenset)):
        return tuple(str(item).strip() for item in value if str(item).strip())
    return (str(value).strip(),) if str(value).strip() else ()


def _bool_value(mapping: Mapping[str, Any], default: bool, *keys: str) -> bool:
    """读取常见布尔表示。"""

    for key in keys:
        if key not in mapping:
            continue
        value = mapping[key]
        if isinstance(value, bool):
            return value
        return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}
    return default
