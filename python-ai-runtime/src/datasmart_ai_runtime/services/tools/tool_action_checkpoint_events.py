"""工具动作 checkpoint 查询与恢复预检 Runtime Event 适配器。

checkpoint query/resume-preview 是非常典型的 Agent Host 控制面动作：它们不会执行工具，但会读取暂停点、
判断恢复事实是否齐备，并影响后续用户、worker 或外部 Agent 是否继续推进。因此这些动作需要进入
runtime event/replay/audit 链路，而不能只停留在一次 HTTP 响应里。

安全原则：
- 事件只记录 operation、结果枚举、计数、事实类型、低敏 issue code 和副作用边界；
- checkpointId/threadId 只记录短 hash，便于关联排障，不在长期事件里保存原始 locator；
- 不记录 prompt、SQL、arguments、payloadReference、graphId、样本数据、模型输出、凭证或内部 endpoint。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from hashlib import sha256
from typing import Any
from uuid import uuid4

from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


CHECKPOINT_EVENT_PAYLOAD_POLICY = "LOW_SENSITIVE_TOOL_ACTION_CHECKPOINT_EVENT_ONLY"


def build_tool_action_checkpoint_runtime_event(
    response: Mapping[str, Any],
    *,
    operation: str,
    request_payload: Mapping[str, Any] | None = None,
) -> AgentRuntimeEvent:
    """把 checkpoint query/resume-preview 响应转换为低敏 runtime event。

    参数：
    - `response`：已经由 checkpoint API helper 裁剪后的响应；
    - `operation`：固定为 `query` 或 `resume_preview`，用于事件类型、指标标签和 Java projection 路由；
    - `request_payload`：只读取 context/trustedControlPlane/root 的控制面 ID，不读取任何工具载荷正文。
    """

    safe_operation = "resume_preview" if operation == "resume_preview" else "query"
    context = _event_context_from_payload(request_payload)
    event_type = (
        AgentRuntimeEventType.TOOL_ACTION_CHECKPOINT_RESUME_PREVIEWED
        if safe_operation == "resume_preview"
        else AgentRuntimeEventType.TOOL_ACTION_CHECKPOINT_QUERIED
    )
    return AgentRuntimeEvent(
        event_type=event_type,
        stage=f"record_tool_action_checkpoint_{safe_operation}",
        message=_event_message(safe_operation),
        severity=_event_severity(response, safe_operation),
        tenant_id=context.get("tenantId"),
        project_id=context.get("projectId"),
        actor_id=context.get("actorId"),
        request_id=context.get("requestId") or str(uuid4()),
        run_id=context.get("runId"),
        session_id=context.get("sessionId"),
        sequence=_positive_int(context.get("sequence")) or 1,
        attributes=_event_attributes(response, safe_operation, request_payload),
    )


def _event_attributes(
    response: Mapping[str, Any],
    operation: str,
    request_payload: Mapping[str, Any] | None,
) -> dict[str, Any]:
    """生成低敏事件 attributes。"""

    route = _mapping(response.get("route"))
    production = _mapping(response.get("productionReadiness"))
    access_issues = tuple(_mapping(item) for item in _sequence(response.get("accessIssues")))
    locator = _locator_summary(request_payload)
    attrs: dict[str, Any] = {
        "eventPayloadVersion": "v1",
        "snapshotType": "TOOL_ACTION_CHECKPOINT",
        "operation": operation,
        "metricResult": _metric_result(response, operation),
        "payloadPolicy": CHECKPOINT_EVENT_PAYLOAD_POLICY,
        "schemaVersion": _text(response.get("schemaVersion")),
        "previewOnly": bool(response.get("previewOnly", True)),
        "routePath": _text(route.get("path")),
        "routeMethod": _text(route.get("method")),
        "checkpointLocatorProvided": locator["checkpointLocatorProvided"],
        "threadLocatorProvided": locator["threadLocatorProvided"],
        "checkpointLocatorHash": locator["checkpointLocatorHash"],
        "threadLocatorHash": locator["threadLocatorHash"],
        "accessIssueCodes": tuple(_text(item.get("code")) for item in access_issues if _text(item.get("code"))),
        "accessIssueCount": len(access_issues),
        "missingProductionRequirements": _string_tuple(production.get("missingProductionRequirements"))[:20],
        "currentStore": _text(production.get("currentStore")),
        "currentAuditMode": _text(production.get("currentAuditMode")),
        "currentMetricsMode": _text(production.get("currentMetricsMode")),
    }
    if operation == "resume_preview":
        attrs.update(_resume_attributes(response))
    else:
        attrs.update(_query_attributes(response))
    return attrs


def _query_attributes(response: Mapping[str, Any]) -> dict[str, Any]:
    """提取 checkpoint query 的低敏计数。"""

    query_policy = _mapping(response.get("queryPolicy"))
    checkpoints = _sequence(response.get("checkpoints"))
    return {
        "queryBoundary": _text(response.get("queryBoundary")),
        "checkpointCount": _int(response.get("checkpointCount")),
        "includeGraphRun": bool(query_policy.get("includeGraphRun")),
        "scopeFilterApplied": bool(query_policy.get("scopeFilterApplied")),
        "globalScanAllowed": bool(query_policy.get("globalScanAllowed")),
        "checkpointSequenceMax": max((_int(_mapping(item).get("sequence")) for item in checkpoints), default=0),
        "checkpointStatusCounts": _checkpoint_status_counts(checkpoints),
    }


def _resume_attributes(response: Mapping[str, Any]) -> dict[str, Any]:
    """提取 resume-preview 的低敏决策与事实类型计数。"""

    resume_facts = _mapping(response.get("resumeFacts"))
    decision = _mapping(response.get("resumeDecision"))
    server_facts = _mapping(response.get("serverSideResumeFacts"))
    side_effect = _mapping(response.get("sideEffectBoundary"))
    return {
        "resumeBoundary": _text(response.get("resumeBoundary")),
        "readyToResume": bool(decision.get("readyToResume")),
        "resumeDecision": _text(decision.get("decision")),
        "resumeNextAction": _text(decision.get("nextAction")),
        "acceptedFactTypeCount": len(_string_tuple(resume_facts.get("acceptedFactTypes"))),
        "requestAcceptedFactTypeCount": len(_string_tuple(resume_facts.get("requestAcceptedFactTypes"))),
        "serverAcceptedFactTypeCount": len(_string_tuple(resume_facts.get("serverAcceptedFactTypes"))),
        "serverRejectedFactTypeCount": len(_string_tuple(resume_facts.get("serverRejectedFactTypes"))),
        "requiredFactTypes": _string_tuple(resume_facts.get("requiredFactTypes"))[:20],
        "missingFactTypes": _string_tuple(resume_facts.get("missingFactTypes"))[:20],
        "rejectedFactTypes": _string_tuple(resume_facts.get("rejectedFactTypes"))[:20],
        "ignoredSensitiveFieldCount": _int(resume_facts.get("ignoredSensitiveFieldCount")),
        "serverFactSource": _text(server_facts.get("source")),
        "serverFactReferenceCount": _int(server_facts.get("factReferenceCount")),
        "serverFactErrorCodes": _string_tuple(server_facts.get("errorCodes"))[:20],
        "toolExecuted": bool(side_effect.get("toolExecuted")),
        "outboxWritten": bool(side_effect.get("outboxWritten")),
        "workerDispatched": bool(side_effect.get("workerDispatched")),
        "approvalCreated": bool(side_effect.get("approvalCreated")),
        "checkpointMutated": bool(side_effect.get("checkpointMutated")),
    }


def _metric_result(response: Mapping[str, Any], operation: str) -> str:
    """把响应归一成低基数结果枚举，供 event 和 Prometheus 共同使用。"""

    access_issue_codes = tuple(_text(_mapping(item).get("code")) for item in _sequence(response.get("accessIssues")))
    if any(code == "CHECKPOINT_SCOPE_MISMATCH" for code in access_issue_codes):
        return "scope_mismatch"
    if any(code == "CHECKPOINT_ID_OR_THREAD_ID_REQUIRED" for code in access_issue_codes):
        return "missing_locator"
    if access_issue_codes:
        return "not_found"
    if operation == "resume_preview":
        decision = _mapping(response.get("resumeDecision"))
        if bool(decision.get("readyToResume")):
            return "ready"
        if _string_tuple(_mapping(response.get("serverSideResumeFacts")).get("errorCodes")):
            return "provider_error"
        return "waiting"
    return "found" if _int(response.get("checkpointCount")) > 0 else "not_found"


def _event_severity(response: Mapping[str, Any], operation: str) -> AgentRuntimeEventSeverity:
    """根据访问结果选择事件严重级别。"""

    result = _metric_result(response, operation)
    if result in {"scope_mismatch", "provider_error"}:
        return AgentRuntimeEventSeverity.WARNING
    if result in {"missing_locator", "not_found", "waiting"}:
        return AgentRuntimeEventSeverity.AUDIT
    return AgentRuntimeEventSeverity.AUDIT


def _event_message(operation: str) -> str:
    """生成事件中文说明。"""

    if operation == "resume_preview":
        return "已记录工具动作 checkpoint 恢复预检审计快照。"
    return "已记录工具动作 checkpoint 查询审计快照。"


def _event_context_from_payload(payload: Mapping[str, Any] | None) -> dict[str, str]:
    """从请求 payload 中提取低敏事件关联上下文。"""

    if not isinstance(payload, Mapping):
        return {}
    nested_context = payload.get("context")
    trusted_control_plane = payload.get("trustedControlPlane")
    contexts = (
        payload,
        nested_context if isinstance(nested_context, Mapping) else {},
        trusted_control_plane if isinstance(trusted_control_plane, Mapping) else {},
    )
    return {
        "tenantId": _first_text(contexts, "tenantId", "tenant_id"),
        "projectId": _first_text(contexts, "projectId", "project_id"),
        "actorId": _first_text(contexts, "actorId", "actor_id"),
        "requestId": _first_text(contexts, "requestId", "request_id"),
        "runId": _first_text(contexts, "runId", "run_id"),
        "sessionId": _first_text(contexts, "sessionId", "session_id"),
        "sequence": _first_text(contexts, "sequence"),
    }


def _locator_summary(payload: Mapping[str, Any] | None) -> dict[str, Any]:
    """生成 locator 摘要，只返回是否存在和 hash，不返回原始 ID。"""

    context = payload.get("context") if isinstance(payload, Mapping) else None
    context_mapping = context if isinstance(context, Mapping) else {}
    checkpoint_id = _first_text((payload or {}, context_mapping), "checkpointId", "checkpoint_id")
    thread_id = _first_text((payload or {}, context_mapping), "threadId", "thread_id")
    return {
        "checkpointLocatorProvided": bool(checkpoint_id),
        "threadLocatorProvided": bool(thread_id),
        "checkpointLocatorHash": _hash_locator(checkpoint_id),
        "threadLocatorHash": _hash_locator(thread_id),
    }


def _checkpoint_status_counts(checkpoints: tuple[Any, ...]) -> dict[str, int]:
    """汇总返回 checkpoint 的状态计数。"""

    result: dict[str, int] = {}
    for item in checkpoints:
        status_counts = _mapping(_mapping(item).get("statusCounts"))
        for key, value in status_counts.items():
            text = _text(key)
            if text:
                result[text] = result.get(text, 0) + _int(value)
    return dict(sorted(result.items()))


def _hash_locator(value: str | None) -> str | None:
    """对 locator 做短 hash，保留关联能力但不暴露原始 ID。"""

    if not value:
        return None
    return sha256(value.encode("utf-8")).hexdigest()[:16]


def _first_text(contexts: tuple[Mapping[str, Any], ...], *keys: str) -> str | None:
    """按上下文顺序读取第一个非空文本值。"""

    for context in contexts:
        for key in keys:
            text = _text(context.get(key))
            if text:
                return text
    return None


def _mapping(value: Any) -> Mapping[str, Any]:
    """将任意对象安全收敛为 mapping。"""

    return value if isinstance(value, Mapping) else {}


def _sequence(value: Any) -> tuple[Any, ...]:
    """将 list/tuple 等序列收敛为 tuple，排除字符串。"""

    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return tuple(value)
    return ()


def _string_tuple(value: Any) -> tuple[str, ...]:
    """将序列统一转换成去空字符串 tuple。"""

    return tuple(text for text in (_text(item) for item in _sequence(value)) if text)


def _positive_int(value: Any) -> int | None:
    """解析正整数，解析失败时返回 None。"""

    parsed = _int(value)
    return parsed if parsed > 0 else None


def _int(value: Any) -> int:
    """解析整数，解析失败时返回 0。"""

    if isinstance(value, bool):
        return int(value)
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _text(value: Any) -> str:
    """转换成去空文本，只处理低敏标识字段。"""

    if value is None:
        return ""
    return str(value).strip()
