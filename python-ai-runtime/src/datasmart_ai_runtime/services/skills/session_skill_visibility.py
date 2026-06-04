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
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.domain.skills import AgentSkillSelection


def build_session_skill_visibility_snapshot(
    plan: AgentPlan,
    request: AgentRequest,
    workspace: dict[str, Any],
    skill_admission: dict[str, Any],
    tool_budget: dict[str, Any],
    model_gateway: dict[str, Any],
    skill_manifest: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """构建会话级 Skill 能力快照。

    参数说明：
    - `plan`：本轮 Agent 编排结果，提供已经选中和被拒绝的 Skill；
    - `request`：原始请求，只用于解释租户/项目和可信控制面事实来源；
    - `workspace`：响应组装层生成的 workspace 摘要；
    - `skill_admission`：智能网关已经构建好的 Skill 准入摘要；
    - `tool_budget/model_gateway`：用于说明 Skill 可见性是否还受到预算或模型可用性影响。
    - `skill_manifest`：当前 Python Runtime 已知的 Skill 发布目录绑定摘要。它不是准入决策来源，
      只用于把“本轮会话使用/回退到哪版能力目录”写入快照和事件，方便后续审计、灰度和缓存排障。

    返回值是前端治理卡片、Java gateway、runtime event 或后续 WebSocket 会话状态可以直接消费的低敏对象。
    """

    selected = plan.skill_plan.selected_skills
    rejected = plan.skill_plan.rejected_skills
    trusted_context = _skill_visibility_trust_context(request.variables or {})
    manifest_binding = _manifest_binding_summary(skill_manifest)
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
        "manifestBindingStatus": manifest_binding["bindingStatus"],
        "manifestStatus": manifest_binding["status"],
        "manifestSource": manifest_binding["source"],
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
        "manifestBinding": manifest_binding,
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


def build_session_skill_visibility_runtime_event(
    plan: AgentPlan,
    request: AgentRequest,
    skill_visibility: Mapping[str, Any],
) -> AgentRuntimeEvent:
    """把会话级 Skill 可见性快照转换为可回放的运行时事件。

    为什么需要单独事件：
    - `skillAdmission` 事件回答“语义命中的 Skill 是否通过准入”，偏策略评估；
    - `skillVisibility` 快照回答“当前会话最终能让模型/用户看到哪些能力”，偏会话事实；
    - 两者虽然相关，但面向的产品页面和审计问题不同。如果共用一个事件，后续前端可见性面板、
      Java replay index、Skill Marketplace 使用统计都会被迫解析过宽的准入事件。

    安全边界：
    - 事件 attributes 只保存低敏聚合事实，例如数量、Skill code、风险/领域分布、事实来源；
    - 不写入用户 objective、prompt、SQL、工具参数、权限编码明细、模型输出或记忆正文；
    - `visibleSkillCodes/hiddenSkillCodes` 默认最多保留 20 个，避免未来 Skill 数量增长后单条事件过大。

    生命周期：
    - 该事件由响应组装层在 `intelligentGatewayGovernance` 构建后追加；
    - 追加后再进入 event store、live push、publisher 和 HTTP snapshot envelope；
    - 因此它可以用于断线 replay、Java 控制面补索引和运营审计，而不只是一次性 HTTP 展示字段。
    """

    first_event = plan.runtime_events[0] if plan.runtime_events else None
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.SKILL_VISIBILITY_SNAPSHOT_RECORDED,
        stage="record_skill_visibility_snapshot",
        message="已记录本轮会话级 Skill 可见性快照。",
        severity=(
            AgentRuntimeEventSeverity.AUDIT
            if not bool(skill_visibility.get("available"))
            else AgentRuntimeEventSeverity.INFO
        ),
        tenant_id=request.tenant_id,
        project_id=request.project_id,
        actor_id=request.actor_id,
        request_id=plan.request_id,
        run_id=first_event.run_id if first_event else None,
        session_id=first_event.session_id if first_event else _request_session_id(request),
        sequence=_next_runtime_event_sequence(plan),
        attributes=_skill_visibility_event_attributes(skill_visibility),
    )


def _skill_visibility_event_attributes(skill_visibility: Mapping[str, Any]) -> dict[str, Any]:
    """生成 Skill 可见性事件属性。

    这里故意不直接把完整 `skillVisibility` 原样塞入事件：
    - 响应字段可以相对丰富，方便前端治理卡片展示；
    - 事件字段会被持久化、回放、投递到消息总线，必须更紧凑、更稳定、更低敏；
    - 未来 Java replay index 可以只依赖这些聚合字段做查询和报表，不需要理解完整前端响应结构。
    """

    visible_skill_codes, visible_truncated = _limited_skill_codes(skill_visibility, "visibleSkills")
    hidden_skill_codes, hidden_truncated = _limited_skill_codes(skill_visibility, "hiddenSkills")
    visibility_filters = dict(skill_visibility.get("visibilityFilters") or {})
    manifest_binding = dict(skill_visibility.get("manifestBinding") or {})
    return {
        "eventPayloadVersion": "v1",
        "snapshotType": skill_visibility.get("snapshotType"),
        "snapshotSource": skill_visibility.get("snapshotSource"),
        "available": bool(skill_visibility.get("available")),
        "availableSkillCount": int(skill_visibility.get("availableSkillCount") or 0),
        "visibleSkillCount": int(skill_visibility.get("visibleSkillCount") or 0),
        "hiddenSkillCount": int(skill_visibility.get("hiddenSkillCount") or 0),
        "conditionalVisibleSkillCount": int(skill_visibility.get("conditionalVisibleSkillCount") or 0),
        "permissionFactSource": visibility_filters.get("permissionFactSource"),
        "actorRoleSource": visibility_filters.get("actorRoleSource"),
        "actorRole": visibility_filters.get("actorRole"),
        "grantedPermissionCount": int(visibility_filters.get("grantedPermissionCount") or 0),
        "tenantSkillEnabled": bool(visibility_filters.get("tenantSkillEnabled", True)),
        "workspaceRiskLevel": visibility_filters.get("workspaceRiskLevel"),
        "tenantPlanCode": visibility_filters.get("tenantPlanCode"),
        "policyVersion": visibility_filters.get("policyVersion"),
        "legacyRequestVariablesDetected": bool(visibility_filters.get("legacyRequestVariablesDetected")),
        "modelGatewayAvailable": bool(visibility_filters.get("modelGatewayAvailable")),
        "toolBudgetAllowed": bool(visibility_filters.get("toolBudgetAllowed")),
        "manifestBindingStatus": manifest_binding.get("bindingStatus"),
        "manifestStatus": manifest_binding.get("status"),
        "manifestSource": manifest_binding.get("source"),
        "manifestFingerprint": manifest_binding.get("manifestFingerprint"),
        "manifestSchemaVersion": manifest_binding.get("schemaVersion"),
        "manifestSkillCount": int(manifest_binding.get("manifestSkillCount") or 0),
        "manifestReadySkillCount": int(manifest_binding.get("readySkillCount") or 0),
        "manifestNonReadySkillCount": int(manifest_binding.get("nonReadySkillCount") or 0),
        "manifestFallback": bool(manifest_binding.get("fallback", True)),
        "visibleSkillCodes": visible_skill_codes,
        "visibleSkillCodesTruncatedCount": visible_truncated,
        "hiddenSkillCodes": hidden_skill_codes,
        "hiddenSkillCodesTruncatedCount": hidden_truncated,
        "visibleRiskLevelCounts": dict(skill_visibility.get("visibleRiskLevelCounts") or {}),
        "visibleDomainCounts": dict(skill_visibility.get("visibleDomainCounts") or {}),
        "hiddenAdmissionStatusCounts": dict(skill_visibility.get("hiddenAdmissionStatusCounts") or {}),
        "displaySummary": skill_visibility.get("displaySummary"),
        "recommendedActionCount": len(tuple(skill_visibility.get("recommendedActions") or ())),
    }


def _limited_skill_codes(
    skill_visibility: Mapping[str, Any],
    field_name: str,
    limit: int = 20,
) -> tuple[tuple[str, ...], int]:
    """提取并截断 Skill code 列表。

    Skill code 是能力目录的低敏标识，适合写入事件；displayName、准入原因和权限要求则更适合留在
    同步响应或受保护的审计详情里。这里使用固定上限，是为了防止未来一个会话命中几十上百个 Skill
    时把 runtime event 变成大对象，影响 WebSocket replay 和 Kafka 投递。
    """

    skills = tuple(skill_visibility.get(field_name) or ())
    codes = tuple(
        str(item.get("skillCode")).strip()
        for item in skills
        if isinstance(item, Mapping) and str(item.get("skillCode") or "").strip()
    )
    return codes[:limit], max(0, len(codes) - limit)


def _manifest_binding_summary(skill_manifest: Mapping[str, Any] | None) -> dict[str, Any]:
    """生成会话快照中的 Manifest 绑定摘要。

    这个函数的职责不是重新解释 Manifest，而是把智能网关层已经归一化的低敏字段再做一次兜底：
    - 如果调用方没有传入 Manifest 诊断，明确标记 `UNBOUND_NOT_CONFIGURED`；
    - 如果字段类型异常，转换为稳定的字符串、布尔值和非负整数；
    - 保留 `manifestFingerprint`，因为它是后续灰度、缓存、审计和回放定位的核心版本证据；
    - 不保留完整 descriptor、工具 schema、权限明细或非 READY Skill 明细，避免会话事件膨胀和敏感面扩大。
    """

    if not isinstance(skill_manifest, Mapping):
        return {
            "bindingStatus": "UNBOUND_NOT_CONFIGURED",
            "status": "NOT_CONFIGURED",
            "source": "not-configured",
            "fallback": True,
            "remoteManifestAvailable": False,
            "manifestFingerprint": None,
            "schemaVersion": None,
            "manifestSkillCount": 0,
            "readySkillCount": 0,
            "nonReadySkillCount": 0,
        }
    return {
        "bindingStatus": _string_value(skill_manifest, "bindingStatus") or "UNBOUND_UNKNOWN",
        "status": _string_value(skill_manifest, "status") or "UNKNOWN",
        "source": _string_value(skill_manifest, "source") or "unknown",
        "fallback": _bool_value(skill_manifest, True, "fallback"),
        "remoteManifestAvailable": _bool_value(skill_manifest, False, "remoteManifestAvailable"),
        "manifestFingerprint": _string_value(skill_manifest, "manifestFingerprint"),
        "schemaVersion": _string_value(skill_manifest, "schemaVersion"),
        "manifestSkillCount": _non_negative_int(skill_manifest.get("manifestSkillCount")),
        "readySkillCount": _non_negative_int(skill_manifest.get("readySkillCount")),
        "nonReadySkillCount": _non_negative_int(skill_manifest.get("nonReadySkillCount")),
    }


def _non_negative_int(value: object | None) -> int:
    """读取非负整数，专门用于 Manifest 数量字段兜底。"""

    if isinstance(value, int):
        return max(0, value)
    if isinstance(value, float):
        return max(0, int(value))
    if value is None:
        return 0
    try:
        return max(0, int(str(value).strip()))
    except ValueError:
        return 0


def _next_runtime_event_sequence(plan: AgentPlan) -> int:
    """计算追加事件的 sequence。

    编排器内部使用 `RuntimeEventRecorder` 从 1 递增生成事件顺序；响应组装层追加治理事件时不再持有
    recorder，因此需要从现有事件中取最大 sequence + 1。这样即使二轮推理、记忆候选等阶段已经追加
    过事件，本事件仍能排在当前计划事件流的最后。
    """

    sequences = tuple(event.sequence for event in plan.runtime_events if event.sequence is not None)
    return (max(sequences) + 1) if sequences else len(plan.runtime_events) + 1


def _request_session_id(request: AgentRequest) -> str | None:
    """从请求变量中读取 sessionId 作为兜底会话关联。

    正常情况下 sessionId 已由编排器写入第一条 runtime event；该方法只处理极简测试或外部构造
    AgentPlan 时没有原始事件的情况，避免新增事件丢失会话维度。
    """

    variables = request.variables or {}
    value = variables.get("sessionId") or variables.get("session_id")
    if value is None:
        return None
    text = str(value).strip()
    return text or None


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
