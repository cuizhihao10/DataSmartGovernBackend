"""受控多 Agent turn runner runtime event 构建器。

`agentTurnRunner` 是多 Agent 从“会话视图”走向“可推进 turn 合同”的关键字段。同步 HTTP 顶层字段只适合
当前调用方立即读取；runtime event 则服务 WebSocket replay、Kafka publisher、Java projection 和审计。

事件属性继续坚持低敏白名单：
- 记录 runStatus、turnAttemptCount、managerAsToolsCount、等待/阻断/控制面 handoff 计数；
- 记录有限数量的 turn attempt 控制面字段；
- 不记录 prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint、payloadReference 正文、
  checkpointId、commandId、memoryId 或 artifact body。
"""

from __future__ import annotations

from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


def build_multi_agent_turn_runner_runtime_event(
    plan: AgentPlan,
    request: AgentRequest,
    turn_runner: Mapping[str, Any],
    turn_runner_checkpoint: Mapping[str, Any] | None = None,
) -> AgentRuntimeEvent:
    """把 `agentTurnRunner` 低敏摘要转换为可投递 runtime event。

    `turn_runner_checkpoint` 是可选的 LangGraph durable checkpoint 摘要。这里刻意只接收已经由
    `LangGraphDurableCheckpoint.to_summary()` 和 `recover_multi_agent_state(...).to_summary()` 生成的
    低敏 locator，不读取 checkpoint state 正文。这样 Java 控制面可以知道“后续恢复应定位到哪个
    thread/checkpoint/node”，但仍然看不到 prompt、工具参数、SQL、样本数据或模型输出。
    """

    first_event = plan.runtime_events[0] if plan.runtime_events else None
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.AGENT_TURN_RUNNER_RECORDED,
        stage="record_agent_turn_runner",
        message="已记录本轮受控多 Agent turn runner 合同。",
        severity=_event_severity(turn_runner),
        tenant_id=request.tenant_id,
        project_id=request.project_id,
        actor_id=request.actor_id,
        request_id=plan.request_id,
        run_id=_string_value(turn_runner.get("runId")) or (first_event.run_id if first_event else None),
        session_id=_request_session_id(request) or (first_event.session_id if first_event else None),
        sequence=_next_runtime_event_sequence(plan),
        attributes=_event_attributes(turn_runner, turn_runner_checkpoint),
    )


def _event_attributes(
    turn_runner: Mapping[str, Any],
    turn_runner_checkpoint: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """生成 runtime event attributes 的白名单字段。"""

    attempts = tuple(item for item in _sequence(turn_runner.get("turnAttempts")) if isinstance(item, Mapping))
    limited_attempts = attempts[:20]
    knowledge_capabilities = tuple(
        item for item in _sequence(turn_runner.get("knowledgeAgentCapabilities")) if isinstance(item, Mapping)
    )
    return {
        "eventPayloadVersion": "v1",
        "snapshotType": "CONTROLLED_MULTI_AGENT_TURN_RUNNER_VIEW",
        "schemaVersion": _string_value(turn_runner.get("schemaVersion")),
        "status": _string_value(turn_runner.get("status")) or "UNKNOWN",
        "runStatus": _string_value(turn_runner.get("runStatus")) or "UNKNOWN",
        "sessionStatus": _string_value(turn_runner.get("sessionStatus")) or "UNKNOWN",
        "durablePhase": _string_value(turn_runner.get("durablePhase")) or "not_recorded",
        "currentTurnDepth": _non_negative_int(turn_runner.get("currentTurnDepth")),
        "maxTurnDepth": _non_negative_int(turn_runner.get("maxTurnDepth")),
        "maxConcurrentAgentTurns": _non_negative_int(turn_runner.get("maxConcurrentAgentTurns")),
        "turnAttemptCount": _non_negative_int(turn_runner.get("turnAttemptCount")),
        "waitingAttemptCount": _non_negative_int(turn_runner.get("waitingAttemptCount")),
        "blockedAttemptCount": _non_negative_int(turn_runner.get("blockedAttemptCount")),
        "controlPlaneHandoffCount": _non_negative_int(turn_runner.get("controlPlaneHandoffCount")),
        "managerAsToolsCount": len(_sequence(turn_runner.get("managerAsTools"))),
        "knowledgeAgentCapabilityCount": _non_negative_int(turn_runner.get("knowledgeAgentCapabilityCount")),
        "knowledgeAgentCapabilityCodes": _limited_string_tuple(
            tuple(item.get("capabilityCode") for item in knowledge_capabilities),
            limit=10,
        ),
        "turnAttempts": tuple(_attempt_attributes(item) for item in limited_attempts),
        "turnAttemptsTruncatedCount": max(0, len(attempts) - len(limited_attempts)),
        "nextActions": _limited_string_tuple(turn_runner.get("nextActions"), limit=20),
        "toolExecutedByPython": bool(_mapping(turn_runner.get("sideEffectBoundary")).get("toolExecutedByPython")),
        "modelCalledByTurnRunner": bool(_mapping(turn_runner.get("sideEffectBoundary")).get("modelCalledByTurnRunner")),
        "outboxWrittenByPython": bool(_mapping(turn_runner.get("sideEffectBoundary")).get("outboxWrittenByPython")),
        "approvalCreatedByPython": bool(_mapping(turn_runner.get("sideEffectBoundary")).get("approvalCreatedByPython")),
        "workerDispatchedByPython": bool(_mapping(turn_runner.get("sideEffectBoundary")).get("workerDispatchedByPython")),
        "javaControlPlaneRequiredForSideEffects": bool(
            _mapping(turn_runner.get("sideEffectBoundary")).get("javaControlPlaneRequiredForSideEffects")
        ),
        "workerReceiptRequiredForSideEffects": bool(
            _mapping(turn_runner.get("sideEffectBoundary")).get("workerReceiptRequiredForSideEffects")
        ),
        "executionBoundary": _string_value(turn_runner.get("executionBoundary")),
        "payloadPolicy": _string_value(turn_runner.get("payloadPolicy")),
        "turnRunnerCheckpoint": _checkpoint_locator_attributes(turn_runner_checkpoint),
    }


def _checkpoint_locator_attributes(turn_runner_checkpoint: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """裁剪可进入 runtime event 的 LangGraph checkpoint locator。

    为什么不直接把 `/agent/plans.agentTurnRunnerCheckpoint` 原样放进事件：
    - API 响应未来可能为了前端诊断增加更多字段，如果事件构建器直接透传，就会破坏“事件字段白名单”；
    - Java 控制面只需要恢复定位字段和多 Agent 角色状态摘要，不需要 checkpoint state 正文；
    - checkpointId/threadId 虽然是低敏控制面定位符，但仍应集中裁剪，避免外部调用方伪造额外 payload。
    """

    checkpoint_view = _mapping(turn_runner_checkpoint)
    checkpoint = _mapping(checkpoint_view.get("checkpoint"))
    recovery = _mapping(checkpoint_view.get("multiAgentRecovery"))
    checkpoint_id = _string_value(checkpoint.get("checkpointId"))
    thread_id = _string_value(checkpoint_view.get("threadId")) or _string_value(checkpoint.get("threadId"))
    if not checkpoint_id or not thread_id:
        return None
    return {
        "threadId": thread_id,
        "checkpointId": checkpoint_id,
        "parentCheckpointId": _string_value(checkpoint.get("parentCheckpointId")),
        "graphName": _string_value(checkpoint.get("graphName")),
        "graphVersion": _string_value(checkpoint.get("graphVersion")),
        "nodeName": _string_value(checkpoint.get("nodeName")),
        "checkpointStatus": _string_value(checkpoint.get("status")),
        "checkpointVersion": _non_negative_int(checkpoint.get("checkpointVersion")),
        "nextNodes": _limited_string_tuple(checkpoint.get("nextNodes"), limit=12),
        "resumeRequirementKeys": _limited_string_tuple(checkpoint.get("resumeRequirementKeys"), limit=20),
        "stateTopLevelKeys": _limited_string_tuple(checkpoint.get("stateTopLevelKeys"), limit=20),
        "recoveryFound": bool(recovery.get("found")),
        "recoveryStatus": _string_value(recovery.get("status")),
        "recoveryAgentRoles": _limited_string_tuple(recovery.get("agentRoles"), limit=16),
        "recoveryAgentStatuses": _limited_string_map(recovery.get("agentStatuses"), limit=16),
        "handoffRequired": bool(recovery.get("handoffRequired")),
        "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_TURN_RUNNER_CHECKPOINT_LOCATOR_ONLY",
    }


def _attempt_attributes(attempt: Mapping[str, Any]) -> dict[str, Any]:
    """裁剪单个 turn attempt，只保留控制面字段。"""

    return {
        "turnId": _string_value(attempt.get("turnId")),
        "workItemId": _string_value(attempt.get("workItemId")),
        "agentRole": _string_value(attempt.get("agentRole")) or "UNKNOWN_AGENT",
        "deliveryTier": _string_value(attempt.get("deliveryTier")) or "runtime_governance",
        "turnStatus": _string_value(attempt.get("turnStatus")) or "UNKNOWN",
        "resumeAction": _string_value(attempt.get("resumeAction")),
        "managerToolName": _string_value(attempt.get("managerToolName")),
        "requiredEvidenceCodes": _limited_string_tuple(attempt.get("requiredEvidenceCodes"), limit=20),
        "waitingReasonCodes": _limited_string_tuple(attempt.get("waitingReasonCodes"), limit=20),
        "blockedBy": _limited_string_tuple(attempt.get("blockedBy"), limit=20),
        "plannedToolCount": _non_negative_int(attempt.get("plannedToolCount")),
        "visibleSkillCount": _non_negative_int(attempt.get("visibleSkillCount")),
        "memoryDependencyCount": _non_negative_int(attempt.get("memoryDependencyCount")),
        "payloadPolicy": _string_value(attempt.get("payloadPolicy")),
    }


def _event_severity(turn_runner: Mapping[str, Any]) -> AgentRuntimeEventSeverity:
    """根据 runStatus 选择事件严重级别。"""

    status = str(turn_runner.get("runStatus") or "").upper()
    if status.startswith("BLOCKED"):
        return AgentRuntimeEventSeverity.ERROR
    if "APPROVAL" in status or "HUMAN" in status:
        return AgentRuntimeEventSeverity.AUDIT
    if status.startswith("WAITING"):
        return AgentRuntimeEventSeverity.WARNING
    return AgentRuntimeEventSeverity.INFO


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _sequence(value: Any) -> tuple[Any, ...]:
    if value is None:
        return ()
    if isinstance(value, str):
        return (value,) if value.strip() else ()
    if isinstance(value, (tuple, list, set, frozenset)):
        return tuple(value)
    return ()


def _limited_string_tuple(value: Any, *, limit: int) -> tuple[str, ...]:
    cleaned: list[str] = []
    for item in _sequence(value):
        text = str(item).strip()
        if text:
            cleaned.append(text)
        if len(cleaned) >= limit:
            break
    return tuple(cleaned)


def _limited_string_map(value: Any, *, limit: int) -> dict[str, str]:
    """读取低敏字符串映射，主要用于 role -> status 这类可观测控制面摘要。

    这里不接受嵌套对象，也不保留超长文本；如果未来恢复摘要需要更多结构，应显式新增白名单字段，
    而不是把任意 map 透传到 runtime event。
    """

    if not isinstance(value, Mapping):
        return {}
    cleaned: dict[str, str] = {}
    for raw_key, raw_value in value.items():
        key = _string_value(raw_key)
        item = _string_value(raw_value)
        if key and item:
            cleaned[key] = item
        if len(cleaned) >= limit:
            break
    return cleaned


def _string_value(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _non_negative_int(value: Any) -> int:
    if isinstance(value, int):
        return max(0, value)
    try:
        return max(0, int(str(value).strip()))
    except (TypeError, ValueError):
        return 0


def _next_runtime_event_sequence(plan: AgentPlan) -> int:
    sequences = tuple(event.sequence for event in plan.runtime_events if event.sequence is not None)
    return (max(sequences) + 1) if sequences else len(plan.runtime_events) + 1


def _request_session_id(request: AgentRequest) -> str | None:
    value = request.variables.get("sessionId") or request.variables.get("session_id")
    return _string_value(value)
