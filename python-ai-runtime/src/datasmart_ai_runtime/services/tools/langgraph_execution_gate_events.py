"""LangGraph execution gate Runtime Event 适配器。

`LangGraphExecutionGateWorkflow` 已经把 readiness/resume gate 迁成条件图；但如果它只存在于同步
HTTP 响应里，WebSocket replay、Kafka publisher、Java projection 和审计系统仍然无法看到本轮工具
执行前到底卡在哪个门禁。因此本模块把 execution gate 诊断压缩为一条低敏 runtime event。

安全原则：
- 只记录图状态、dominant route、readiness 计数、resume fact 类型和副作用边界；
- 不记录用户 objective、prompt、SQL、工具参数真实值、样本数据、模型输出、token、内部 endpoint 或异常堆栈；
- 列表字段限制长度，避免模型一次性提出过多工具时撑大事件体。
"""

from __future__ import annotations

from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


def build_langgraph_execution_gate_runtime_event(
    plan: AgentPlan,
    request: AgentRequest,
    execution_gate_summary: Mapping[str, Any],
) -> AgentRuntimeEvent:
    """把 LangGraph execution gate 摘要转换为可回放运行时事件。

    事件生命周期：
    - 响应组装层先生成 readiness report，再运行 execution gate workflow；
    - 本函数把 workflow 的低敏摘要追加到 `plan.runtime_events`；
    - 后续 HTTP snapshot、event store、WebSocket live/replay、Kafka publisher 和 Java projection 都能消费同一条事实。
    """

    first_event = plan.runtime_events[0] if plan.runtime_events else None
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.AGENT_EXECUTION_GATE_RECORDED,
        stage="record_agent_execution_gate",
        message="已记录 LangGraph 工具执行门禁条件图快照。",
        severity=_event_severity(execution_gate_summary),
        tenant_id=request.tenant_id,
        project_id=request.project_id,
        actor_id=request.actor_id,
        request_id=plan.request_id,
        run_id=first_event.run_id if first_event else None,
        session_id=first_event.session_id if first_event else _request_session_id(request),
        sequence=_next_runtime_event_sequence(plan),
        attributes=_event_attributes(execution_gate_summary),
    )


def _event_attributes(summary: Mapping[str, Any]) -> dict[str, Any]:
    """生成低敏 event attributes。"""

    side_effect_boundary = _mapping(summary.get("sideEffectBoundary"))
    resume_gate = _mapping(summary.get("resumeGate"))
    readiness_counts = _mapping(summary.get("readinessCounts"))
    branch_counts = _mapping(summary.get("readinessBranchCounts"))
    conditional_routes = _mapping(summary.get("conditionalRoutes"))
    return {
        "eventPayloadVersion": "v1",
        "snapshotType": "LANGGRAPH_EXECUTION_GATE_WORKFLOW",
        "payloadPolicy": "LOW_SENSITIVE_GATE_METADATA_ONLY",
        "engine": _text(summary.get("engine")),
        "status": _text(summary.get("status")),
        "gateRoute": _text(summary.get("gateRoute")),
        "gateStatus": _text(summary.get("gateStatus")),
        "compiled": bool(summary.get("compiled")),
        "executed": bool(summary.get("executed")),
        "fallbackUsed": bool(summary.get("fallbackUsed")),
        "fallbackReason": _text(summary.get("fallbackReason")),
        "nodeTrace": _string_tuple(summary.get("nodeTrace"), limit=20),
        "graphNodeCount": len(_string_tuple(summary.get("graphNodes"), limit=100)),
        "graphEdgeCount": len(_string_tuple(summary.get("graphEdges"), limit=100)),
        "conditionalRouteCount": len(conditional_routes),
        "conditionalRoutes": dict(sorted((str(k), str(v)) for k, v in conditional_routes.items())),
        "readinessCounts": _int_mapping(readiness_counts),
        "readinessBranchCounts": _int_mapping(branch_counts),
        "readinessNextActions": _string_tuple(summary.get("readinessNextActions"), limit=20),
        "resumeGateStatus": _text(resume_gate.get("status")),
        "resumePreviewReady": bool(resume_gate.get("resumePreviewReady")),
        "resumeRequiredFactTypes": _string_tuple(resume_gate.get("requiredFactTypes"), limit=20),
        "toolExecuted": bool(side_effect_boundary.get("toolExecuted")),
        "outboxWritten": bool(side_effect_boundary.get("outboxWritten")),
        "approvalCreated": bool(side_effect_boundary.get("approvalCreated")),
        "checkpointMutated": bool(side_effect_boundary.get("checkpointMutated")),
        "workerDispatched": bool(side_effect_boundary.get("workerDispatched")),
        "javaControlPlaneRequiredForSideEffects": bool(
            side_effect_boundary.get("javaControlPlaneRequiredForSideEffects")
        ),
        "workerReceiptRequiredForSideEffects": bool(
            side_effect_boundary.get("workerReceiptRequiredForSideEffects")
        ),
    }


def _event_severity(summary: Mapping[str, Any]) -> AgentRuntimeEventSeverity:
    """根据 dominant gate 选择事件严重级别。"""

    gate_route = _text(summary.get("gateRoute")) or ""
    if gate_route == "BLOCKED":
        return AgentRuntimeEventSeverity.ERROR
    if gate_route in {"HUMAN_INPUT", "HUMAN_APPROVAL", "CAPACITY_WAIT"}:
        return AgentRuntimeEventSeverity.AUDIT
    if gate_route == "DRAFT_REVIEW":
        return AgentRuntimeEventSeverity.WARNING
    return AgentRuntimeEventSeverity.INFO


def _mapping(value: Any) -> Mapping[str, Any]:
    """安全读取字典字段。"""

    return value if isinstance(value, Mapping) else {}


def _int_mapping(value: Mapping[str, Any]) -> dict[str, int]:
    """把计数字典归一为稳定整数映射。"""

    result: dict[str, int] = {}
    for key, item in value.items():
        try:
            result[str(key)] = int(item)
        except (TypeError, ValueError):
            result[str(key)] = 0
    return dict(sorted(result.items()))


def _string_tuple(value: Any, *, limit: int) -> tuple[str, ...]:
    """把外部序列转换为定长字符串元组。"""

    if isinstance(value, str):
        return (value[:200],)
    if not isinstance(value, (list, tuple, set, frozenset)):
        return ()
    return tuple(str(item)[:200] for item in value if str(item).strip())[:limit]


def _text(value: Any) -> str | None:
    """读取非空字符串，避免把复杂对象直接塞进事件。"""

    if value is None:
        return None
    text = str(value).strip()
    return text[:200] if text else None


def _next_runtime_event_sequence(plan: AgentPlan) -> int:
    """计算追加事件 sequence。"""

    sequences = tuple(event.sequence for event in plan.runtime_events if event.sequence is not None)
    return (max(sequences) + 1) if sequences else len(plan.runtime_events) + 1


def _request_session_id(request: AgentRequest) -> str | None:
    """从请求变量读取 sessionId 作为兜底。"""

    value = request.variables.get("sessionId") or request.variables.get("session_id")
    if value is None:
        return None
    text = str(value).strip()
    return text or None
