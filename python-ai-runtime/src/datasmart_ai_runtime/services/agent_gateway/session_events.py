"""多 Agent 会话调度 Runtime Event 构建器。

`agentSessionScheduling` 同步响应适合前端立即展示，但商业化 Agent Host 还需要可回放、可投递、可审计
的事件事实。否则用户刷新页面、WebSocket 断线、Java 控制面补索引或 Kafka 消费者延迟时，系统就无法
回答“当时到底有哪些 Agent 参与、为什么降级、是否需要 handoff”。

本模块把调度视图压缩为低敏 Runtime Event：
- 保留 Agent 角色、参与模式、状态、数量和策略轴；
- 不保存用户 objective、prompt、工具参数、SQL、样本数据、记忆正文或模型输出；
- 对 Agent 列表和工具/Skill 列表设置上限，避免未来能力目录膨胀后单条事件过大。
"""

from __future__ import annotations

from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


def build_agent_session_scheduling_runtime_event(
    plan: AgentPlan,
    request: AgentRequest,
    scheduling: Mapping[str, Any],
) -> AgentRuntimeEvent:
    """把会话级多 Agent 调度视图转换为运行时事件。

    参数说明：
    - `plan`：用于复用 requestId、runId、sessionId 和当前事件 sequence；
    - `request`：只读取租户、项目、操作者和 sessionId 兜底，不读取 objective；
    - `scheduling`：来自 `agentSessionScheduling` 的低敏策略视图。

    事件严重级别规则：
    - `BLOCKED` 用 `ERROR`，表示关键模型/预算能力不可用；
    - `DEGRADED` 或 `APPROVAL_REQUIRED` 用 `AUDIT`，表示需要控制面关注或人工接管；
    - `READY` 用 `INFO`，表示正常调度事实。
    """

    first_event = plan.runtime_events[0] if plan.runtime_events else None
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.AGENT_SESSION_SCHEDULING_RECORDED,
        stage="record_agent_session_scheduling",
        message="已记录本轮多 Agent 会话调度策略视图。",
        severity=_event_severity(scheduling),
        tenant_id=request.tenant_id,
        project_id=request.project_id,
        actor_id=request.actor_id,
        request_id=plan.request_id,
        run_id=first_event.run_id if first_event else None,
        session_id=first_event.session_id if first_event else _request_session_id(request),
        sequence=_next_runtime_event_sequence(plan),
        attributes=_scheduling_event_attributes(scheduling),
    )


def _scheduling_event_attributes(scheduling: Mapping[str, Any]) -> dict[str, Any]:
    """生成低敏事件属性。

    这里不把完整 `participatingAgents` 原样写入事件，因为同步响应可以丰富展示，但事件会进入持久化、
    replay 和消息总线，必须更紧凑。事件只保存角色、模式、状态、handoff 等控制面字段，排除每个
    Agent 的长文本激活原因，避免未来策略解释越来越详细时拖慢事件投递。
    """

    agents = tuple(item for item in scheduling.get("participatingAgents", ()) if isinstance(item, Mapping))
    limited_agents = agents[:20]
    policy_axes = scheduling.get("policyAxes") if isinstance(scheduling.get("policyAxes"), Mapping) else {}
    a2a_axis = policy_axes.get("a2aTaskPlanning") if isinstance(policy_axes.get("a2aTaskPlanning"), Mapping) else {}
    status = str(scheduling.get("status") or "UNKNOWN")
    roles = tuple(_string_field(item, "role") for item in limited_agents if _string_field(item, "role"))
    return {
        "eventPayloadVersion": "v1",
        "snapshotType": "AGENT_SESSION_SCHEDULING_POLICY_VIEW",
        "available": bool(scheduling.get("available")),
        "status": status,
        "primaryAgentRole": _string_value(scheduling.get("primaryAgentRole")),
        "participatingAgentCount": _non_negative_int(scheduling.get("participatingAgentCount")),
        "participatingAgentRoles": roles,
        "participatingAgentRolesTruncatedCount": max(0, len(agents) - len(limited_agents)),
        "participationModeCounts": _count_agent_field(limited_agents, "participationMode"),
        "agentStatusCounts": _count_agent_field(limited_agents, "status"),
        "handoffRequired": bool(scheduling.get("handoffRequired")),
        "handoffAgentRoles": tuple(
            _string_field(item, "role")
            for item in limited_agents
            if bool(item.get("requiresHandoff")) and _string_field(item, "role")
        ),
        "intentDomains": _limited_string_tuple(policy_axes.get("intentDomains"), limit=20),
        "selectedSkillCodes": _limited_string_tuple(policy_axes.get("selectedSkillCodes"), limit=20),
        "visibleSkillCodes": _limited_string_tuple(policy_axes.get("visibleSkillCodes"), limit=20),
        "plannedToolNames": _limited_string_tuple(policy_axes.get("plannedToolNames"), limit=20),
        "memoryDependencies": _limited_string_tuple(policy_axes.get("memoryDependencies"), limit=20),
        "modelGatewayAvailable": bool(policy_axes.get("modelGatewayAvailable")),
        "skillAdmissionAllowed": bool(policy_axes.get("skillAdmissionAllowed")),
        "toolBudgetAllowed": bool(policy_axes.get("toolBudgetAllowed")),
        "approvalRequired": bool(policy_axes.get("approvalRequired")),
        "tenantScoped": bool(policy_axes.get("tenantScoped")),
        "projectScoped": bool(policy_axes.get("projectScoped")),
        # A2A task planning 轴只记录 mode、状态、计数和 guardrail code。
        # 这里刻意不记录 taskPublicId/contextPublicId/artifactRef，也不记录 decisionReason 这种自由文本，
        # 因为 runtime event 会进入 replay、投影和消息总线，暴露面比同步响应更大。
        "a2aTaskPlanningAvailable": bool(a2a_axis.get("available")),
        "a2aTaskPlanningSource": _string_value(a2a_axis.get("source")),
        "a2aTaskPlanningMode": _string_value(a2a_axis.get("mode")),
        "a2aTaskPlanningStatus": _string_value(a2a_axis.get("status")),
        "a2aTaskState": _string_value(a2a_axis.get("a2aState")),
        "a2aTaskInternalPhase": _string_value(a2a_axis.get("internalPhase")),
        "a2aTaskTerminal": bool(a2a_axis.get("terminal")),
        "a2aTaskInterrupted": bool(a2a_axis.get("interrupted")),
        "a2aTaskExecutable": bool(a2a_axis.get("executable")),
        "a2aTaskShouldWaitForHuman": bool(a2a_axis.get("shouldWaitForHuman")),
        "a2aTaskSuggestedActions": _limited_string_tuple(a2a_axis.get("suggestedActions"), limit=20),
        "a2aTaskGuardrailCodes": _limited_string_tuple(a2a_axis.get("guardrailCodes"), limit=20),
        "a2aTaskHistoryEventCount": _non_negative_int(a2a_axis.get("historyEventCount")),
        "a2aTaskArtifactReferenceCount": _non_negative_int(a2a_axis.get("artifactReferenceCount")),
        "a2aTaskSensitiveFieldIgnoredCount": _non_negative_int(a2a_axis.get("sensitiveFieldIgnoredCount")),
        "a2aTaskPayloadPolicy": _string_value(a2a_axis.get("payloadPolicy")),
        "displaySummary": _string_value(scheduling.get("displaySummary")),
        "recommendedActionCount": len(tuple(scheduling.get("recommendedActions") or ())),
    }


def _event_severity(scheduling: Mapping[str, Any]) -> AgentRuntimeEventSeverity:
    """根据调度状态选择事件严重级别。"""

    status = str(scheduling.get("status") or "").upper()
    if status == "BLOCKED":
        return AgentRuntimeEventSeverity.ERROR
    if status in {"DEGRADED", "APPROVAL_REQUIRED"} or bool(scheduling.get("handoffRequired")):
        return AgentRuntimeEventSeverity.AUDIT
    return AgentRuntimeEventSeverity.INFO


def _count_agent_field(agents: tuple[Mapping[str, Any], ...], field_name: str) -> dict[str, int]:
    """统计 Agent 字段分布，用于 Java projection 和运维报表。"""

    counts: dict[str, int] = {}
    for agent in agents:
        value = _string_field(agent, field_name)
        if not value:
            continue
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def _limited_string_tuple(value: object | None, *, limit: int) -> tuple[str, ...]:
    """把策略轴字段转换为受限字符串元组。

    工具名、Skill code、治理域和记忆类型本身是低敏控制面标识，但数量过多时仍会造成事件膨胀。
    因此事件层统一截断，完整列表继续留在同步响应里。
    """

    if value is None:
        return ()
    if isinstance(value, str):
        items = (value,)
    elif isinstance(value, (list, tuple, set, frozenset)):
        items = tuple(value)
    else:
        items = (value,)
    return tuple(str(item).strip() for item in items if str(item).strip())[:limit]


def _string_field(mapping: Mapping[str, Any], field_name: str) -> str | None:
    """读取映射中的非空字符串字段。"""

    return _string_value(mapping.get(field_name))


def _string_value(value: object | None) -> str | None:
    """把对象转成非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _non_negative_int(value: object | None) -> int:
    """读取非负整数，避免异常值污染事件契约。"""

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

    响应组装层可能已经追加了 Skill 可见性事件、记忆候选事件或二轮推理事件，所以不能简单用
    `len(events) + 1` 假设所有事件都有连续 sequence；取最大值加一更稳。
    """

    sequences = tuple(event.sequence for event in plan.runtime_events if event.sequence is not None)
    return (max(sequences) + 1) if sequences else len(plan.runtime_events) + 1


def _request_session_id(request: AgentRequest) -> str | None:
    """从请求变量读取 sessionId 作为兜底。"""

    value = request.variables.get("sessionId") or request.variables.get("session_id")
    if value is None:
        return None
    text = str(value).strip()
    return text or None
