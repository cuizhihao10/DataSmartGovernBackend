"""受控多 Agent 执行会话 Runtime Event 构建器。

`agentExecutionSession` 已经让同步 `/agent/plans` 响应可以展示“本轮有哪些 Agent work item、
各自停在哪个可恢复阶段、下一步 resume action 是什么”。但同步响应只适合当前调用方立即读取，
不适合 WebSocket 断线恢复、Kafka 投递、Java 控制面投影和审计回放。

本模块把同一份低敏会话摘要压缩成 `agent_execution_session_recorded` runtime event：
- 保留 sessionStatus、deliveryTier、resumeAction、rosterCoverage、sideEffectBoundary 等控制面事实；
- 不保留 prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint、memoryId 或完整异常堆栈；
- 不执行工具、不调用模型、不写 outbox、不创建审批、不派发 worker，只生成可回放事实。

它和 `agent_session_scheduling_recorded` 的区别：
- scheduling event 解释“哪些 Agent 应该参与本轮会话”；
- execution session event 解释“这些 Agent work item 进入受控会话后当前处于什么恢复/等待/交接状态”。
两者合起来，Java 控制面才能既看见调度意图，也看见可恢复执行状态。
"""

from __future__ import annotations

from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


def build_agent_execution_session_runtime_event(
    plan: AgentPlan,
    request: AgentRequest,
    execution_session: Mapping[str, Any],
) -> AgentRuntimeEvent:
    """把 `agentExecutionSession` 低敏摘要转换为可投递 runtime event。

    参数说明：
    - `plan`：用于复用 requestId、现有 runId/sessionId 和 sequence 顺序；
    - `request`：只读取 tenant/project/actor，不读取 objective 或 variables 中的业务正文；
    - `execution_session`：来自 `ControlledMultiAgentExecutionSession.to_summary()` 的低敏摘要。

    事件严重级别：
    - blocked 视为 `ERROR`，表示受控会话无法继续推进；
    - 等待审批、handoff、人工接管视为 `AUDIT`，表示需要控制面或人工介入；
    - degraded 视为 `WARNING`，表示可展示草案但不能自动执行；
    - 其他正常状态视为 `INFO`。
    """

    first_event = plan.runtime_events[0] if plan.runtime_events else None
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.AGENT_EXECUTION_SESSION_RECORDED,
        stage="record_agent_execution_session",
        message="已记录本轮受控多 Agent 执行会话状态视图。",
        severity=_event_severity(execution_session),
        tenant_id=request.tenant_id,
        project_id=request.project_id,
        actor_id=request.actor_id,
        request_id=plan.request_id,
        run_id=_string_value(execution_session.get("runId")) or (first_event.run_id if first_event else None),
        session_id=_string_value(execution_session.get("sessionId"))
        or (first_event.session_id if first_event else _request_session_id(request)),
        sequence=_next_runtime_event_sequence(plan),
        attributes=_execution_session_event_attributes(execution_session),
    )


def _execution_session_event_attributes(execution_session: Mapping[str, Any]) -> dict[str, Any]:
    """生成低敏事件属性。

    这里不原样复制整个 `agentExecutionSession`。原因是 runtime event 会进入更长生命周期：
    Kafka、Java projection、审计 replay 和可能的持久索引都会消费它。为了避免事件变成第二份会话缓存，
    本函数显式白名单字段，并对列表设置上限。
    """

    work_items = tuple(item for item in _sequence(execution_session.get("workItems")) if isinstance(item, Mapping))
    limited_work_items = work_items[:20]
    roster = _mapping(execution_session.get("rosterCoverage"))
    boundary = _mapping(execution_session.get("sideEffectBoundary"))
    active_roles = _limited_string_tuple(execution_session.get("activeRoles"), limit=30)
    return {
        "eventPayloadVersion": "v1",
        "snapshotType": "AGENT_EXECUTION_SESSION_CONTROL_PLANE_VIEW",
        "available": True,
        "status": _string_value(execution_session.get("status")) or "UNKNOWN",
        "durablePhase": _string_value(execution_session.get("durablePhase")) or "not_recorded",
        "durableResumeAction": _string_value(execution_session.get("durableResumeAction")),
        "executionPlanStatus": _string_value(execution_session.get("executionPlanStatus")) or "UNKNOWN",
        "executionSessionSource": _string_value(execution_session.get("source")) or "UNKNOWN",
        "workItemCount": _non_negative_int(execution_session.get("workItemCount")),
        "activeRoles": active_roles,
        "activeRolesTruncatedCount": max(0, len(_sequence(execution_session.get("activeRoles"))) - len(active_roles)),
        "workItems": tuple(_work_item_event_attributes(item) for item in limited_work_items),
        "workItemsTruncatedCount": max(0, len(work_items) - len(limited_work_items)),
        "workItemStatusCounts": _count_work_item_field(limited_work_items, "sessionStatus"),
        "deliveryTierCounts": _count_work_item_field(limited_work_items, "deliveryTier"),
        "resumeActionCounts": _count_work_item_field(limited_work_items, "resumeAction"),
        "sourceStatusCounts": _count_work_item_field(limited_work_items, "sourceStatus"),
        "handoffRequiredWorkItemCount": sum(1 for item in limited_work_items if bool(item.get("handoffRequired"))),
        "waitingReasonCodes": _limited_unique_strings(
            reason
            for item in limited_work_items
            for reason in _sequence(item.get("waitingReasonCodes"))
        ),
        "blockedByCodes": _limited_unique_strings(
            reason
            for item in limited_work_items
            for reason in _sequence(item.get("blockedBy"))
        ),
        "activeMustDoRoles": _limited_string_tuple(roster.get("activeMustDoRoles"), limit=20),
        "standbyMustDoRoles": _limited_string_tuple(roster.get("standbyMustDoRoles"), limit=20),
        "activeControlledScopeRoles": _limited_string_tuple(roster.get("activeControlledScopeRoles"), limit=20),
        "standbyControlledScopeRoles": _limited_string_tuple(roster.get("standbyControlledScopeRoles"), limit=20),
        "deferredLightweightRoles": _limited_string_tuple(roster.get("deferredLightweightRoles"), limit=20),
        "activeRoleCount": _non_negative_int(roster.get("activeRoleCount")),
        "mustDoRoleCount": _non_negative_int(roster.get("mustDoRoleCount")),
        "activeMustDoRoleCount": _non_negative_int(roster.get("activeMustDoRoleCount")),
        "coveragePolicy": _string_value(roster.get("coveragePolicy")),
        "collaborationEdgeCount": _non_negative_int(execution_session.get("collaborationEdgeCount")),
        "handoffContractCount": _non_negative_int(execution_session.get("handoffContractCount")),
        "nextActions": _limited_string_tuple(execution_session.get("nextActions"), limit=20),
        "toolExecutedByPython": bool(boundary.get("toolExecutedByPython")),
        "modelCalledByExecutionSession": bool(boundary.get("modelCalledByExecutionSession")),
        "outboxWrittenByPython": bool(boundary.get("outboxWrittenByPython")),
        "approvalCreatedByPython": bool(boundary.get("approvalCreatedByPython")),
        "workerDispatchedByPython": bool(boundary.get("workerDispatchedByPython")),
        "checkpointMutatedByExecutionSession": bool(boundary.get("checkpointMutatedByExecutionSession")),
        "javaControlPlaneRequiredForSideEffects": bool(boundary.get("javaControlPlaneRequiredForSideEffects")),
        "workerReceiptRequiredForSideEffects": bool(boundary.get("workerReceiptRequiredForSideEffects")),
        "executionBoundary": _string_value(execution_session.get("executionBoundary")),
        "payloadPolicy": _string_value(execution_session.get("payloadPolicy")),
    }


def _work_item_event_attributes(item: Mapping[str, Any]) -> dict[str, Any]:
    """裁剪单个 Agent work item。

    work item 只保留角色、状态、恢复动作、依赖角色和数量字段。即使上游未来在 work item 中加入
    tool arguments、SQL、样本数据或模型摘要，本函数也不会读取这些字段。
    """

    return {
        "workItemId": _string_value(item.get("workItemId")),
        "agentRole": _string_value(item.get("agentRole")) or "UNKNOWN_AGENT",
        "deliveryTier": _string_value(item.get("deliveryTier")) or "runtime_governance",
        "participationMode": _string_value(item.get("participationMode")) or "UNKNOWN",
        "sessionStatus": _string_value(item.get("sessionStatus")) or "UNKNOWN",
        "resumeAction": _string_value(item.get("resumeAction")) or "WAIT_FOR_SESSION_ORCHESTRATOR_REVIEW",
        "executionLane": _string_value(item.get("executionLane")),
        "dependsOnRoles": _limited_string_tuple(item.get("dependsOnRoles"), limit=20),
        "handoffRequired": bool(item.get("handoffRequired")),
        "plannedToolCount": _non_negative_int(item.get("plannedToolCount")),
        "visibleSkillCount": _non_negative_int(item.get("visibleSkillCount")),
        "memoryDependencyCount": _non_negative_int(item.get("memoryDependencyCount")),
        "waitingReasonCodes": _limited_string_tuple(item.get("waitingReasonCodes"), limit=20),
        "blockedBy": _limited_string_tuple(item.get("blockedBy"), limit=20),
        "durablePhase": _string_value(item.get("durablePhase")) or "not_recorded",
        "sourceStatus": _string_value(item.get("sourceStatus")) or "UNKNOWN",
        "payloadPolicy": _string_value(item.get("payloadPolicy")),
    }


def _event_severity(execution_session: Mapping[str, Any]) -> AgentRuntimeEventSeverity:
    """根据会话状态选择事件严重级别。"""

    status = str(execution_session.get("status") or "").upper()
    if status.startswith("BLOCKED"):
        return AgentRuntimeEventSeverity.ERROR
    if status in {"WAITING_APPROVAL_OR_HANDOFF", "WAITING_HUMAN_TAKEOVER"}:
        return AgentRuntimeEventSeverity.AUDIT
    if status in {"WAITING_CONTROL_PLANE_FEEDBACK", "DEGRADED_DRAFT_ONLY"}:
        return AgentRuntimeEventSeverity.WARNING
    return AgentRuntimeEventSeverity.INFO


def _count_work_item_field(items: tuple[Mapping[str, Any], ...], field_name: str) -> dict[str, int]:
    """统计 work item 字段分布，供 Java projection 和报表聚合。"""

    counts: dict[str, int] = {}
    for item in items:
        value = _string_value(item.get(field_name))
        if value:
            counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def _mapping(value: Any) -> Mapping[str, Any]:
    """安全读取字典字段。"""

    return value if isinstance(value, Mapping) else {}


def _sequence(value: Any) -> tuple[Any, ...]:
    """安全读取序列字段；字符串按单个值处理。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return (value,) if value.strip() else ()
    if isinstance(value, (list, tuple, set, frozenset)):
        return tuple(value)
    return ()


def _limited_string_tuple(value: Any, *, limit: int) -> tuple[str, ...]:
    """把候选值转换为受限字符串元组。"""

    return _limited_unique_strings(_sequence(value), limit=limit, unique=False)


def _limited_unique_strings(values: Any, *, limit: int = 20, unique: bool = True) -> tuple[str, ...]:
    """清理、去重并截断字符串集合。"""

    cleaned: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value).strip()
        if not text:
            continue
        if unique and text in seen:
            continue
        cleaned.append(text)
        seen.add(text)
        if len(cleaned) >= limit:
            break
    return tuple(cleaned)


def _string_value(value: Any) -> str | None:
    """把对象转换为非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _non_negative_int(value: Any) -> int:
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
    """计算追加事件的 sequence。"""

    sequences = tuple(event.sequence for event in plan.runtime_events if event.sequence is not None)
    return (max(sequences) + 1) if sequences else len(plan.runtime_events) + 1


def _request_session_id(request: AgentRequest) -> str | None:
    """从请求变量读取 sessionId 作为兜底。"""

    value = request.variables.get("sessionId") or request.variables.get("session_id")
    return _string_value(value)
