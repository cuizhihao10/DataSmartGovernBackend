"""智能网关中的 Agent 执行闭环压缩视图。

`agentExecutionClosure` 是面向执行链路的详细闭环卡片，包含 completedStages、sideEffectBoundary、
controlPlaneHandoff 等字段。智能网关不应该把它原样复制一份，否则响应会膨胀，也会让网关卡片和
执行卡片职责重叠。

本模块负责把闭环卡片压缩为“智能网关是否可以继续推进”的治理摘要：
- 当前停在哪个闭环阶段；
- 是否已经有 Java 控制面交接导航；
- outbox 预检候选数量是多少；
- 还缺哪些低敏证据 code；
- 下一步应该补 graph/payloadReference、等待反馈，还是继续普通文本响应。

该摘要仍然只使用低敏字段，不读取任何工具参数、prompt、SQL、样本、模型输出或内部 endpoint。
"""

from __future__ import annotations

from typing import Any, Mapping


def build_execution_closure_gateway_summary(agent_execution_closure: Mapping[str, Any] | None) -> dict[str, Any]:
    """构建智能网关可展示的执行闭环摘要。

    参数说明：
    - `agent_execution_closure` 是 `/agent/plans.agentExecutionClosure` 的低敏摘要；
    - 如果调用方还没有构建该摘要，智能网关会返回 `available=false`，而不是隐式推断闭环状态；
    - 这样可以避免网关重新解析 plan/readiness/control-plane 字段，保持“闭环服务负责判断，网关负责聚合”。
    """

    if not isinstance(agent_execution_closure, Mapping):
        return {
            "snapshotType": "INTELLIGENT_GATEWAY_EXECUTION_CLOSURE_SUMMARY",
            "payloadPolicy": "LOW_SENSITIVE_GATEWAY_EXECUTION_CLOSURE_ONLY",
            "available": False,
            "reason": "AGENT_EXECUTION_CLOSURE_NOT_BUILT",
            "closurePhase": None,
            "closedLoopLevel": None,
            "controlPlaneHandoffAvailable": False,
            "outboxPreflightCandidateCount": 0,
            "handoffMissingEvidenceCodes": (),
            "handoffNextAction": None,
        }

    handoff = agent_execution_closure.get("controlPlaneHandoff")
    handoff = handoff if isinstance(handoff, Mapping) else {}
    return {
        "snapshotType": "INTELLIGENT_GATEWAY_EXECUTION_CLOSURE_SUMMARY",
        "payloadPolicy": "LOW_SENSITIVE_GATEWAY_EXECUTION_CLOSURE_ONLY",
        "available": True,
        "sourceSnapshotType": _text(agent_execution_closure.get("snapshotType")),
        "sourcePayloadPolicy": _text(agent_execution_closure.get("payloadPolicy")),
        "closurePhase": _text(agent_execution_closure.get("closurePhase")),
        "closedLoopLevel": _text(agent_execution_closure.get("closedLoopLevel")),
        "blockingGates": _string_tuple(agent_execution_closure.get("blockingGates")),
        "missingRuntimeEvidence": _string_tuple(agent_execution_closure.get("missingRuntimeEvidence")),
        "nextActions": _string_tuple(agent_execution_closure.get("nextActions")),
        "counts": _allowed_counts(agent_execution_closure.get("counts")),
        "sideEffectBoundary": _side_effect_boundary(agent_execution_closure.get("sideEffectBoundary")),
        "controlPlaneHandoffAvailable": bool(handoff.get("available")),
        "outboxPreflightCandidateCount": _int(handoff.get("outboxPreflightCandidateCount")),
        "handoffMissingEvidenceCodes": _string_tuple(handoff.get("missingEvidenceCodes")),
        "handoffNextAction": _text(handoff.get("nextAction")),
        "handoffPayloadPolicy": _text(handoff.get("payloadPolicy")),
    }


def _allowed_counts(value: Any) -> dict[str, int]:
    """只保留智能网关诊断需要的低敏数量字段。"""

    if not isinstance(value, Mapping):
        return {}
    allowed_keys = (
        "toolPlanCount",
        "executableToolCount",
        "approvalRequiredToolCount",
        "clarificationRequiredToolCount",
        "draftOnlyToolCount",
        "queuedAsyncToolCount",
        "throttledToolCount",
        "blockedToolCount",
    )
    return {key: _int(value.get(key)) for key in allowed_keys if key in value}


def _side_effect_boundary(value: Any) -> dict[str, bool]:
    """裁剪副作用边界，只保留布尔事实，不透传说明正文。"""

    if not isinstance(value, Mapping):
        return {}
    allowed_keys = (
        "pythonRuntimeExecutedTool",
        "pythonRuntimeWroteOutbox",
        "pythonRuntimeCreatedApproval",
        "javaControlPlaneIngested",
        "controlPlaneFeedbackObserved",
        "runtimeEventReplayUsed",
        "workerReceiptRequiredForSideEffects",
    )
    return {key: bool(value.get(key)) for key in allowed_keys if key in value}


def _string_tuple(value: Any) -> tuple[str, ...]:
    """把低敏 code 列表规范化为 tuple，并避免无限长响应。"""

    if value is None:
        return ()
    if isinstance(value, str):
        candidates = value.split(",")
    elif isinstance(value, (list, tuple, set, frozenset)):
        candidates = value
    else:
        return ()
    items: list[str] = []
    for item in candidates:
        text = _text(item)
        if text and text not in items:
            items.append(text)
        if len(items) >= 12:
            break
    return tuple(items)


def _text(value: Any) -> str | None:
    """读取非空字符串，空值统一返回 None。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _int(value: Any, *, default: int = 0) -> int:
    """读取非负整数，非法值回退到默认值。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed >= 0 else default
