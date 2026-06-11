"""智能网关统一治理摘要 API 适配器。

DataSmart 的智能网关不是单一“模型代理”，而是模型路由、工具调用预算、缓存边界、长期记忆召回、
workspace 隔离和审计事件的组合治理层。前端或 Java gateway 如果分别解析这些字段，会很快形成
脆弱耦合：模型网关字段在一个位置，工具预算事件在 runtimeEvents 里，workspace 又在响应顶层。

本模块提供一份轻量统一摘要：
- 不重新做治理决策，只汇总已经发生的治理事实；
- 不暴露工具参数、prompt、SQL 或样本数据；
- 保持字段稳定，方便后续前端治理卡片、Java 审计记录和智能网关诊断接口复用。
"""

from __future__ import annotations

from typing import Any, Mapping

from datasmart_ai_runtime.api.model_gateway import build_model_gateway_governance_response
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_gateway import build_agent_session_scheduling_policy_view
from datasmart_ai_runtime.services.agent_workspace import AgentWorkspaceContext
from datasmart_ai_runtime.services.skills import build_session_skill_visibility_snapshot


def build_intelligent_gateway_governance_response(
    plan: AgentPlan,
    workspace_context: AgentWorkspaceContext,
    request: AgentRequest,
    skill_manifest_diagnostics: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """构建本次 Agent 计划的智能网关统一治理摘要。

    输入说明：
    - `plan`：编排器已经生成的计划，包含模型网关决策、工具计划、记忆计划、检索报告和 runtime events；
    - `workspace_context`：响应组装层统一生成的工作空间上下文，用于说明缓存、记忆和产物隔离边界。
    - `request`：原始 Agent 请求。这里只读取会话与可信控制面事实来源，不把普通 variables 当成授权事实。

    输出说明：
    - `available` 不是“模型可用”的同义词，而是“本次智能网关没有出现阻断性治理问题”；
    - `toolBudget` 汇总预算守卫结果，若本轮没有模型工具调用预算事件，则返回默认 allowed；
    - `skillManifest` 汇总当前 Python Runtime 已知的 Skill 发布目录证据，用来回答“本轮会话基于哪版能力目录”；
    - `skillVisibility` 汇总当前会话可见 Skill 快照，解释哪些 Skill 进入本轮能力集、哪些被准入策略隐藏；
    - `memory` 只返回目标数量、召回数量和 retriever 标识，不返回任何记忆正文；
    - `recommendedActions` 给出后续产品动作，例如等待审批、缩小工具调用批次或补充 sessionId。
    """

    model_gateway = build_model_gateway_governance_response(plan.model_gateway_decision)
    skill_admission = _skill_admission_summary(plan)
    tool_budget = _tool_budget_summary(plan.runtime_events)
    memory = _memory_summary(plan)
    workspace = workspace_context.to_summary()
    skill_manifest = _skill_manifest_binding_summary(skill_manifest_diagnostics)
    skill_visibility = build_session_skill_visibility_snapshot(
        plan,
        request,
        workspace,
        skill_admission,
        tool_budget,
        model_gateway,
        skill_manifest,
    )
    # 会话级多 Agent 调度视图是智能网关从“模型代理”走向“Agent Host”的关键控制面字段。
    # 它不重新做模型、Skill、工具预算或记忆决策，而是把上面已经生成的治理事实压缩成：
    # - 本轮哪些 Agent 参与；
    # - 谁是主控，谁是专家，谁是权限/记忆/运维防护 Agent；
    # - 是否因为预算、准入、模型路由或记忆缺失而降级；
    # - 哪些 handoff 应进入 Java 控制面审批。
    # 这样前端、Java gateway 和审计系统不需要分别从 plan/tool/runtimeEvents 里拼凑多 Agent 参与事实。
    agent_session_scheduling = build_agent_session_scheduling_policy_view(
        plan,
        request,
        model_gateway=model_gateway,
        skill_admission=skill_admission,
        tool_budget=tool_budget,
        memory=memory,
        skill_visibility=skill_visibility,
    )
    approval_required = bool(plan.requires_human_approval)
    available = bool(model_gateway.get("available")) and skill_admission["allowed"] and tool_budget["allowed"]
    return {
        "available": available,
        "approvalRequired": approval_required,
        "workspace": {
            "workspaceKey": workspace["workspaceKey"],
            "isolationLevel": workspace["isolationLevel"],
            "cacheNamespace": workspace["cacheNamespace"],
            "memoryNamespace": workspace["memoryNamespace"],
            "artifactNamespace": workspace["artifactNamespace"],
        },
        "modelGateway": model_gateway,
        "skillManifest": skill_manifest,
        "skillAdmission": skill_admission,
        "skillVisibility": skill_visibility,
        "agentSessionScheduling": agent_session_scheduling,
        "toolBudget": tool_budget,
        "memory": memory,
        "plannedToolCount": len(plan.tool_plans),
        "plannedToolNames": tuple(tool.tool_name for tool in plan.tool_plans),
        "displaySummary": _display_summary(model_gateway, skill_admission, tool_budget, approval_required),
        "recommendedActions": _recommended_actions(
            model_gateway,
            skill_admission,
            skill_visibility,
            agent_session_scheduling,
            tool_budget,
            memory,
            approval_required,
            skill_manifest,
        ),
    }


def _skill_manifest_binding_summary(diagnostics: Mapping[str, Any] | None) -> dict[str, Any]:
    """把启动诊断快照压缩成同步计划响应可安全暴露的 Manifest 绑定摘要。

    这里不返回完整 Manifest descriptor，也不返回 `nonReadySkills` 明细，原因是 `/agent/plans` 是高频业务接口：
    - 完整 descriptor 可能包含提示词、工具 schema、权限描述或未来插件元数据，不应被每次响应扩散；
    - 非 READY 明细适合运维诊断接口，不适合普通用户计划响应；
    - 会话治理只需要知道“有没有远端 Manifest、指纹是什么、当前是否 fallback、READY/非 READY 数量如何”。

    `bindingStatus` 是面向产品和审计的稳定状态，不直接复用诊断服务的 `status`：
    - 诊断 `status` 更偏运行时健康；
    - 绑定 `bindingStatus` 更偏“这次会话是否具有可追踪的发布目录版本证据”。
    """

    if diagnostics is None:
        return {
            "bindingStatus": "UNBOUND_NOT_CONFIGURED",
            "status": "NOT_CONFIGURED",
            "source": "not-configured",
            "fallback": True,
            "remoteManifestAvailable": False,
            "manifestFingerprint": None,
            "schemaVersion": None,
            "manifestType": None,
            "publicationMode": None,
            "generatedAt": None,
            "lastRefreshAt": None,
            "manifestSkillCount": 0,
            "readySkillCount": 0,
            "nonReadySkillCount": 0,
            "publicationStateCounts": {},
            "riskLevelCounts": {},
            "displaySummary": "本轮计划未注入 Skill Manifest 诊断服务，因此无法绑定发布目录指纹。",
        }

    status = _text(diagnostics, "status") or "UNKNOWN"
    fingerprint = _text(diagnostics, "manifestFingerprint")
    fallback = _bool(diagnostics.get("fallback"), default=True)
    remote_available = _bool(diagnostics.get("remoteManifestAvailable"), default=False)
    return {
        "bindingStatus": _manifest_binding_status(status, fingerprint, fallback, remote_available),
        "status": status,
        "source": _text(diagnostics, "source") or "unknown",
        "fallback": fallback,
        "remoteManifestAvailable": remote_available,
        "manifestFingerprint": fingerprint,
        "schemaVersion": _text(diagnostics, "schemaVersion"),
        "manifestType": _text(diagnostics, "manifestType"),
        "publicationMode": _text(diagnostics, "publicationMode"),
        "generatedAt": _text(diagnostics, "generatedAt"),
        "lastRefreshAt": _text(diagnostics, "lastRefreshAt"),
        "manifestSkillCount": _non_negative_int(diagnostics.get("manifestSkillCount")),
        "readySkillCount": _non_negative_int(diagnostics.get("readySkillCount")),
        "nonReadySkillCount": _non_negative_int(diagnostics.get("nonReadySkillCount")),
        "publicationStateCounts": _int_map(diagnostics.get("publicationStateCounts")),
        "riskLevelCounts": _int_map(diagnostics.get("riskLevelCounts")),
        "displaySummary": _manifest_binding_display_summary(status, fingerprint, fallback, remote_available),
    }


def _manifest_binding_status(
    status: str,
    fingerprint: str | None,
    fallback: bool,
    remote_available: bool,
) -> str:
    """根据诊断事实生成会话级 Manifest 绑定状态。"""

    normalized = status.strip().upper()
    if normalized == "REMOTE_READY" and fingerprint:
        return "BOUND_REMOTE_MANIFEST"
    if normalized == "REMOTE_READY":
        return "REMOTE_READY_WITHOUT_FINGERPRINT"
    if normalized == "DIAGNOSTICS_UNAVAILABLE":
        return "DIAGNOSTICS_UNAVAILABLE"
    if normalized == "REMOTE_UNAVAILABLE_FALLBACK":
        return "REMOTE_UNAVAILABLE_FALLBACK"
    if normalized == "REMOTE_NOT_REFRESHED":
        return "REMOTE_NOT_REFRESHED"
    if fallback and not remote_available:
        return "LOCAL_DEFAULT_OR_FALLBACK"
    return "UNBOUND_UNKNOWN"


def _manifest_binding_display_summary(
    status: str,
    fingerprint: str | None,
    fallback: bool,
    remote_available: bool,
) -> str:
    """生成 Manifest 绑定卡片摘要。"""

    normalized = status.strip().upper()
    if normalized == "REMOTE_READY" and fingerprint:
        return "本轮计划已绑定 Java agent-runtime 发布的 Skill Manifest 指纹。"
    if normalized == "REMOTE_READY":
        return "远端 Skill Manifest 可用，但缺少 contentFingerprint，后续灰度和审计无法精确定位版本。"
    if normalized == "REMOTE_NOT_REFRESHED":
        return "Skill Manifest 诊断服务尚未刷新，本轮计划暂时缺少远端发布目录证据。"
    if normalized == "DIAGNOSTICS_UNAVAILABLE":
        return "Skill Manifest 诊断服务异常，本轮计划无法确认发布目录版本。"
    if fallback and not remote_available:
        return "当前使用本地默认 Skill 或远端不可用回退，建议生产环境接入 Java 发布事实源。"
    return "当前 Manifest 绑定状态未知，建议检查 Python Runtime 与 Java agent-runtime 的发布链路。"


def _skill_admission_summary(plan: AgentPlan) -> dict[str, Any]:
    """构建 Agent Skill 准入治理摘要。

    Skill admission 是工具 schema 暴露前的能力包级防线。这里优先从 `AgentSkillPlan` 读取，而不是重新
    解析 runtime event，是为了保持摘要构建简单可靠：runtime event 适合 replay 和审计，plan 字段适合
    同步响应摘要。两者来自同一份编排结果，不做二次决策。
    """

    selected = tuple(
        {
            "skillCode": item.skill_code,
            "displayName": item.display_name,
            "domain": item.domain.value,
            "riskLevel": item.risk_level,
            "admissionStatus": item.admission_status,
            "admissionReasons": item.admission_reasons,
            "requiredPermissions": item.required_permissions,
        }
        for item in plan.skill_plan.selected_skills
    )
    rejected = tuple(
        {
            "skillCode": item.skill_code,
            "displayName": item.display_name,
            "domain": item.domain.value,
            "riskLevel": item.risk_level,
            "admissionStatus": item.admission_status,
            "admissionReasons": item.admission_reasons,
            "requiredPermissions": item.required_permissions,
        }
        for item in plan.skill_plan.rejected_skills
    )
    conditional_count = sum(1 for item in plan.skill_plan.selected_skills if item.admission_status == "CONDITIONAL")
    return {
        "allowed": not rejected,
        "availableSkillCount": plan.skill_plan.available_skill_count,
        "selectedSkillCount": len(selected),
        "rejectedSkillCount": len(rejected),
        "conditionalSkillCount": conditional_count,
        "selectedSkills": selected,
        "rejectedSkills": rejected,
        "rationale": plan.skill_plan.rationale,
        "displaySummary": _skill_admission_display_summary(selected, rejected, conditional_count),
    }


def _skill_admission_display_summary(
    selected: tuple[dict[str, Any], ...],
    rejected: tuple[dict[str, Any], ...],
    conditional_count: int,
) -> str:
    """生成 Skill 准入卡片摘要。"""

    if rejected:
        return "部分 Agent Skill 命中但未通过权限、角色或风险准入。"
    if conditional_count > 0:
        return "Agent Skill 已条件性启用；生产环境应补充可信权限和角色事实。"
    if selected:
        return "Agent Skill 已完成选择与准入评估。"
    return "本轮未命中明确 Agent Skill。"


def _tool_budget_summary(events: tuple[AgentRuntimeEvent, ...]) -> dict[str, Any]:
    """从 runtime events 中提取工具调用预算守卫摘要。

    新版本优先读取 `MODEL_TOOL_CALL_BUDGET_GUARDED`。同时保留对历史
    stage=`guard_model_tool_call_budget` 的兼容，是为了让旧事件回放、测试夹具或外部 Java replay
    在迁移期间仍能生成治理摘要。这里读取最后一条，是为了兼容未来多模型节点或多轮推理：最后一次
    预算守卫通常最接近最终 ToolPlan。
    """

    budget_events = tuple(
        event
        for event in events
        if event.event_type == AgentRuntimeEventType.MODEL_TOOL_CALL_BUDGET_GUARDED
        or event.stage == "guard_model_tool_call_budget"
    )
    if not budget_events:
        return {
            "allowed": True,
            "guarded": False,
            "budgetIssueCodes": (),
            "displaySummary": "本轮未触发模型工具调用预算阻断。",
        }
    attributes = dict(budget_events[-1].attributes)
    issue_codes = tuple(attributes.get("budgetIssueCodes") or ())
    return {
        "allowed": not issue_codes,
        "guarded": True,
        "proposedCount": attributes.get("proposedCount", 0),
        "acceptedCountBeforeGuard": attributes.get("acceptedCountBeforeGuard", 0),
        "acceptedCountAfterGuard": attributes.get("acceptedCountAfterGuard", 0),
        "autoExecutableCountBeforeGuard": attributes.get("autoExecutableCountBeforeGuard", 0),
        "highRiskCountBeforeGuard": attributes.get("highRiskCountBeforeGuard", 0),
        "totalArgumentsBytes": attributes.get("totalArgumentsBytes", 0),
        "budgetIssueCodes": issue_codes,
        "policy": attributes.get("policy", {}),
        "displaySummary": (
            "智能网关已按工具调用预算阻断部分候选。"
            if issue_codes
            else "智能网关已评估工具调用预算，未发现阻断问题。"
        ),
    }


def _memory_summary(plan: AgentPlan) -> dict[str, Any]:
    """构建长期记忆治理摘要，不返回任何记忆正文。"""

    report = plan.memory_retrieval_report
    return {
        "retrievalTargetCount": len(plan.memory_plan.retrieval_targets),
        "writableMemoryTypeCount": len(plan.memory_plan.writable_memory_types),
        "totalRetrieved": report.total_retrieved,
        "retriever": report.attributes.get("retriever", "custom"),
        "defaultScope": plan.memory_plan.default_scope.value,
        "retentionDays": plan.memory_plan.retention_days,
    }


def _text(mapping: Mapping[str, Any], key: str) -> str | None:
    """从诊断 Map 中读取非空字符串。"""

    value = mapping.get(key)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _bool(value: object | None, *, default: bool) -> bool:
    """读取诊断中的布尔字段，兼容字符串形式的环境变量投影。"""

    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _non_negative_int(value: object | None) -> int:
    """把诊断数量字段转换为非负整数，避免 None 或非法字符串污染响应契约。"""

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


def _int_map(value: object | None) -> dict[str, int]:
    """读取诊断分布 Map，并统一转换为字符串键和非负整数值。"""

    if not isinstance(value, Mapping):
        return {}
    parsed: dict[str, int] = {}
    for raw_key, raw_value in value.items():
        key = str(raw_key).strip()
        if key:
            parsed[key] = _non_negative_int(raw_value)
    return dict(sorted(parsed.items()))


def _display_summary(
    model_gateway: dict[str, Any],
    skill_admission: dict[str, Any],
    tool_budget: dict[str, Any],
    approval_required: bool,
) -> str:
    """生成智能网关卡片的一句话摘要。"""

    if not model_gateway.get("available"):
        return "模型网关不可用或预算不足，Agent 已进入降级治理路径。"
    if not skill_admission["allowed"]:
        return "模型路由可用，但部分 Agent Skill 未通过准入治理。"
    if not tool_budget["allowed"]:
        return "模型路由可用，但工具调用预算已阻断部分候选。"
    if approval_required:
        return "模型路由和工具预算均已通过，但部分工具仍需人工审批。"
    return "模型路由、工具预算、workspace 隔离和记忆检索治理均已完成。"


def _recommended_actions(
    model_gateway: dict[str, Any],
    skill_admission: dict[str, Any],
    skill_visibility: dict[str, Any],
    agent_session_scheduling: dict[str, Any],
    tool_budget: dict[str, Any],
    memory: dict[str, Any],
    approval_required: bool,
    skill_manifest: dict[str, Any],
) -> tuple[str, ...]:
    """汇总智能网关下一步建议。"""

    actions: list[str] = []
    actions.extend(model_gateway.get("recommendedActions") or ())
    if not skill_admission["allowed"]:
        actions.append("检查被拒绝 Skill 的权限、角色、租户开关或风险策略，并由 permission-admin 提供可信准入事实。")
    elif skill_admission["conditionalSkillCount"] > 0:
        actions.append("当前存在条件性启用的 Skill；生产环境应补充 grantedPermissions、actorRole 和策略版本。")
    if skill_visibility["visibilityFilters"]["legacyRequestVariablesDetected"]:
        actions.append("当前会话 Skill 快照检测到旧式角色或权限变量；生产环境应迁移到 trustedControlPlane.skillAdmission。")
    if not tool_budget["allowed"]:
        actions.append("缩小本轮模型工具调用批次，或把高风险/长耗时工具拆到后续确认步骤。")
    if approval_required:
        actions.append("将需审批工具计划提交 Java agent-runtime，等待项目负责人或管理员确认。")
    # 多 Agent 调度建议来自会话策略视图，通常包含 handoff、权限包、工具预算拆分和真实多 Agent runtime
    # 的后续演进建议。这里最多透传前三条，避免治理卡片重复过长；完整建议仍保留在
    # `agentSessionScheduling.recommendedActions` 中。
    for action in tuple(agent_session_scheduling.get("recommendedActions") or ())[:3]:
        if action not in actions:
            actions.append(str(action))
    if memory["retrievalTargetCount"] > 0 and memory["totalRetrieved"] == 0:
        actions.append("本轮没有召回长期记忆；如需要复用历史经验，可先确认 workspace 和记忆写入闭环。")
    binding_status = str(skill_manifest.get("bindingStatus") or "")
    if binding_status in {"UNBOUND_NOT_CONFIGURED", "LOCAL_DEFAULT_OR_FALLBACK"}:
        actions.append("生产环境建议接入 Java agent-runtime Skill Manifest 发布事实源，并把 contentFingerprint 绑定到会话快照。")
    elif binding_status == "REMOTE_READY_WITHOUT_FINGERPRINT":
        actions.append("远端 Skill Manifest 已可用但缺少 contentFingerprint，应补齐指纹以支持灰度、缓存和审计回放。")
    elif binding_status in {"REMOTE_NOT_REFRESHED", "DIAGNOSTICS_UNAVAILABLE", "REMOTE_UNAVAILABLE_FALLBACK"}:
        actions.append("检查 Skill Manifest 诊断刷新、Java agent-runtime 地址和服务间网络，避免能力目录版本证据缺失。")
    if not actions:
        actions.append("可以继续把工具计划提交 Java 控制面生成审计记录。")
    return tuple(actions)
