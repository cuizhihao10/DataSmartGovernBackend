"""把专业 Agent 动作转换为统一 Agent Runtime Event。"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


# 事件是会被 WebSocket、Kafka 和 replay 存储共同消费的持久化边界，因此不能采用“所有标量都安全”
# 的宽松策略。这里明确列出浏览器真正需要的低敏计数、状态和路由摘要；未知字段默认丢弃，后续新增
# 字段必须先经过这份白名单审查，避免模型正文、SQL 或工具参数因为恰好是字符串而进入事件事实。
_LOW_SENSITIVITY_ACTION_ATTRIBUTE_ALLOWLIST = frozenset(
    {
        "actionCount",
        "anomalyCount",
        "answerAvailable",
        "baselineProtected",
        "blockedCount",
        "canStartExecution",
        "candidateCount",
        "checkCount",
        "citationCount",
        "conflictCount",
        "connectorType",
        "durationMs",
        "evidenceAcceptedCount",
        "evidenceAvailable",
        "evidenceCount",
        "factCount",
        "failedCount",
        "failureCount",
        "fieldCount",
        "generated",
        "health",
        "highRiskActionCount",
        "includeColumns",
        "latencyMs",
        "logReferenceCount",
        "maxOutputTokens",
        "maxPollSeconds",
        "maxToolCalls",
        "modelInvoked",
        "modelName",
        "nextPollAfterSeconds",
        "objectCount",
        "objectMappingCount",
        "passedCount",
        "precheckStatus",
        "progressPercent",
        "providerInvoked",
        "providerName",
        "providerSucceeded",
        "readOnly",
        "requiredInputCount",
        "requestedToolCount",
        "rowsProcessed",
        "rowsTotal",
        "selectedCount",
        "side",
        "status",
        "successCount",
        "syncMode",
        "taskKind",
        "taskStatus",
        "terminal",
        "throughputRowsPerSecond",
        "toolName",
        "visibleToolCount",
        "warningCount",
        "writeStrategy",
    }
)


def build_specialist_runtime_events(
    *,
    request: AgentRequest,
    plan: AgentPlan,
    action_events: tuple[Mapping[str, Any], ...],
) -> tuple[AgentRuntimeEvent, ...]:
    """将专业 Agent 的低敏动作摘要接入现有事件总线。

    专业 Agent 的 event sink 使用字典合同，是为了避免领域实现直接依赖 RuntimeEventRecorder。这里作为
    唯一适配边界补齐租户、项目、用户、请求、运行、会话和 sequence，使 WebSocket、Kafka、数据库回放
    与同步响应消费同一种事件结构。
    """

    next_sequence = max((event.sequence or 0 for event in plan.runtime_events), default=0) + 1
    converted: list[AgentRuntimeEvent] = []
    for offset, raw_event in enumerate(action_events):
        status = _text(raw_event.get("status"), "UNKNOWN")
        public_summary = _text(raw_event.get("publicSummary"), "专业 Agent 已更新本轮处理状态。")
        run_id = _text(raw_event.get("runId"), _plan_run_id(plan))
        session_id = _text(raw_event.get("sessionId"), _plan_session_id(plan))
        converted.append(
            AgentRuntimeEvent(
                event_type=AgentRuntimeEventType.SPECIALIST_AGENT_ACTION_RECORDED,
                stage=_text(
                    raw_event.get("action") or raw_event.get("eventType"),
                    "specialist_agent_action",
                ),
                message=public_summary,
                severity=_severity(status),
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                request_id=plan.request_id,
                run_id=run_id,
                session_id=session_id,
                sequence=next_sequence + offset,
                attributes=_public_attributes(raw_event),
            )
        )
    return tuple(converted)


def _public_attributes(raw_event: Mapping[str, Any]) -> dict[str, Any]:
    """按白名单裁剪专业动作，禁止事件系统意外持久化正文或工具参数。"""

    attributes = raw_event.get("attributes") if isinstance(raw_event.get("attributes"), Mapping) else {}
    statistics = raw_event.get("statistics") if isinstance(raw_event.get("statistics"), Mapping) else {}
    return {
        "agentId": _optional_text(raw_event.get("agentId")),
        "agentRole": _optional_text(raw_event.get("agentRole")),
        "turnId": _optional_text(raw_event.get("turnId")),
        "action": _optional_text(raw_event.get("action") or raw_event.get("eventType")),
        "status": _optional_text(raw_event.get("status")),
        "durationMs": _non_negative_int(raw_event.get("durationMs")),
        "errorCode": _optional_text(raw_event.get("errorCode")),
        "statistics": _safe_scalar_mapping(statistics),
        "actionAttributes": _safe_scalar_mapping(attributes),
        "payloadPolicy": "LOW_SENSITIVE_SPECIALIST_RUNTIME_EVENT_ONLY",
    }


def _safe_scalar_mapping(value: Mapping[str, Any]) -> dict[str, Any]:
    """只保留显式允许的低敏标量，默认拒绝未知字段和所有嵌套载荷。

    这里故意不再把“标量”当成安全证明。SQL、Prompt、凭据和工具参数经常都是普通字符串，
    如果只检查值类型，正是这类内容会绕过事件边界。白名单同时用于 ``statistics`` 和
    ``actionAttributes``，即使调用方把敏感内容放错容器，也不会进入统一 Runtime Event。
    """

    result: dict[str, Any] = {}
    for key, item in value.items():
        normalized_key = str(key).strip()
        if normalized_key not in _LOW_SENSITIVITY_ACTION_ATTRIBUTE_ALLOWLIST:
            continue
        if isinstance(item, (str, int, float, bool)) or item is None:
            result[normalized_key] = item
    return result


def _severity(status: str) -> AgentRuntimeEventSeverity:
    """把专业 turn 状态映射为前端和告警系统认识的严重级别。"""

    normalized = status.upper()
    if "FAIL" in normalized or "DENIED" in normalized or "ERROR" in normalized:
        return AgentRuntimeEventSeverity.ERROR
    if "WAIT" in normalized or "NO_EVIDENCE" in normalized or "DEGRADED" in normalized:
        return AgentRuntimeEventSeverity.WARNING
    return AgentRuntimeEventSeverity.INFO


def _plan_run_id(plan: AgentPlan) -> str | None:
    """从既有事件中恢复运行 ID，避免为事件适配另造一个 run。"""

    return next((event.run_id for event in reversed(plan.runtime_events) if event.run_id), None)


def _plan_session_id(plan: AgentPlan) -> str | None:
    """从既有事件中恢复会话 ID。"""

    return next((event.session_id for event in reversed(plan.runtime_events) if event.session_id), None)


def _optional_text(value: Any) -> str | None:
    """规范化可选文本。"""

    text = str(value).strip() if value is not None else ""
    return text or None


def _text(value: Any, default: str) -> str:
    """规范化必填显示文本。"""

    return _optional_text(value) or default


def _non_negative_int(value: Any) -> int:
    """规范化耗时等非负计数。"""

    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0
