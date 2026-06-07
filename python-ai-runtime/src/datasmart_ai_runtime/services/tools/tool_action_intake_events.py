"""工具动作意图 intake Runtime Event 适配器。

`ToolActionIntakeService` 已经把模型 tool_call、MCP tools/call、A2A task/action 等入口统一成
host-level intake 语义。但如果 intake 只存在于同步 HTTP 响应中，前端刷新、WebSocket replay、
Java projection 和审计系统仍然无法追踪“外部 Agent 曾经请求过什么动作，以及平台为什么接受或拒绝”。

本模块把 intake preview 响应压缩成一条低敏 runtime event。它的设计重点不是“记录更多内容”，而是
在不泄露工具参数值的前提下保留可运营、可审计、可回放的控制面事实：
- 来源协议、preview 边界、是否 JSON-RPC、method 是否被接受；
- accepted/rejected 数量、boundary 分布、issue code 分布；
- readiness 的可执行、审批、澄清、阻断和图谱分支摘要；
- durable action 边界，明确本事件没有执行工具、没有写 outbox、没有创建审批单。

注意：事件 attributes 只从已经低敏化的 preview response 中提取，不读取原始 MCP `arguments`。
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Mapping, Sequence
from uuid import uuid4
from typing import Any

from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


def build_tool_action_intake_runtime_event(
    preview_response: Mapping[str, Any],
    *,
    request_payload: Mapping[str, Any] | None = None,
) -> AgentRuntimeEvent:
    """把工具动作 intake preview 响应转换为 runtime event。

    输入说明：
    - `preview_response`：必须是 API helper 已经低敏化后的响应；本函数不会再读取工具参数真实值；
    - `request_payload`：只用于提取 tenant/project/actor/request/run/session 等控制面关联字段，
      不会读取 `arguments`、prompt、SQL、payload 或模型输出正文。

    事件用途：
    - event store：让 `/agent/events/replay` 可以按 requestId/sessionId/runId 回放外部协议工具意图；
    - live push：让前端或智能网关在订阅时看到 MCP/A2A/确认页工具意图进入平台治理；
    - Kafka publisher：让 Java agent-runtime、observability、审计模块后续能消费同一低敏事实。
    """

    context = _event_context_from_payload(request_payload)
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.TOOL_ACTION_INTAKE_RECORDED,
        stage="record_tool_action_intake",
        message="已记录工具动作意图入口治理快照。",
        severity=_event_severity(preview_response),
        tenant_id=context.get("tenantId"),
        project_id=context.get("projectId"),
        actor_id=context.get("actorId"),
        request_id=context.get("requestId") or str(uuid4()),
        run_id=context.get("runId"),
        session_id=context.get("sessionId"),
        sequence=_positive_int(context.get("sequence")) or 1,
        attributes=_event_attributes(preview_response),
    )


def _event_context_from_payload(payload: Mapping[str, Any] | None) -> dict[str, str]:
    """从请求中提取低敏事件关联上下文。

    这里支持三类常见输入位置：
    - 根字段：`tenantId/projectId/actorId/requestId/runId/sessionId/sequence`；
    - `context`：MCP/联调请求常用的低敏控制面包装；
    - `trustedControlPlane`：与 `/agent/plans` 的控制面语义对齐，便于后续 gateway 注入。

    函数刻意不读取 `params.arguments`、`call.arguments` 或其他执行载荷字段，避免事件构建器意外触碰敏感值。
    """

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


def _event_attributes(response: Mapping[str, Any]) -> dict[str, Any]:
    """生成低敏 event attributes。

    attributes 是 Java projection、WebSocket timeline 和审计报表最可能消费的字段，因此要坚持白名单：
    返回数量、枚举、布尔值、工具名、issue/reason code；不返回工具参数值、prompt、SQL、样本数据、
    模型输出、凭证、内部 endpoint 或 artifact 正文。
    """

    input_policy = _mapping(response.get("inputPayloadPolicy"))
    intake = _mapping(response.get("toolActionIntake"))
    readiness = _mapping(response.get("toolExecutionReadiness"))
    graph = _mapping(response.get("toolExecutionReadinessGraph"))
    durable_boundary = _mapping(graph.get("durableActionBoundary"))
    production = _mapping(response.get("productionReadiness"))
    intake_items = tuple(_mapping(item) for item in _sequence(intake.get("items")))
    readiness_items = tuple(_mapping(item) for item in _sequence(readiness.get("items")))

    issue_codes = _limited_unique(
        code
        for item in intake_items
        for code in _sequence(item.get("issueCodes"))
    )
    readiness_reason_codes = _limited_unique(
        code
        for item in readiness_items
        for code in _sequence(item.get("reasonCodes"))
    )
    boundary_counts = _string_key_dict(intake.get("boundaryCounts"))
    graph_branch_counts = _string_key_dict(graph.get("branchCounts"))
    tool_names = _limited_unique(
        item.get("toolName")
        for item in tuple(intake_items) + tuple(readiness_items)
        if str(item.get("toolName") or "").strip()
    )
    return {
        "eventPayloadVersion": "v1",
        "snapshotType": "TOOL_ACTION_INTAKE",
        "payloadPolicy": "LOW_SENSITIVE_TOOL_ACTION_INTAKE_EVENT_ONLY",
        "schemaVersion": _text(response.get("schemaVersion")),
        "protocolFamily": _text(response.get("protocolFamily")),
        "previewOnly": bool(response.get("previewOnly")),
        "toolExecutionEnabled": bool(response.get("toolExecutionEnabled")),
        "jsonRpcDetected": bool(input_policy.get("jsonRpcDetected")),
        "methodAccepted": bool(input_policy.get("methodAccepted")),
        "callDetected": bool(input_policy.get("callDetected")),
        "sensitiveFieldIgnoredCount": _int(input_policy.get("sensitiveFieldIgnoredCount")),
        "source": _text(intake.get("source")),
        "totalCount": _int(intake.get("totalCount")),
        "acceptedToolPlanCount": _int(intake.get("acceptedToolPlanCount")),
        "rejectedBeforeReadinessCount": _int(intake.get("rejectedBeforeReadinessCount")),
        "boundaryCounts": boundary_counts,
        "issueCodes": issue_codes,
        "blockingIssueCount": sum(_int(item.get("blockingIssueCount")) for item in intake_items),
        "toolNames": tool_names,
        "toolNamesTruncatedCount": max(0, len(tuple(intake_items) + tuple(readiness_items)) - len(tool_names)),
        "readinessTotalCount": _int(readiness.get("totalCount")),
        "readinessExecutableCount": _int(readiness.get("executableCount")),
        "readinessApprovalRequiredCount": _int(readiness.get("approvalRequiredCount")),
        "readinessClarificationRequiredCount": _int(readiness.get("clarificationRequiredCount")),
        "readinessDraftOnlyCount": _int(readiness.get("draftOnlyCount")),
        "readinessQueuedAsyncCount": _int(readiness.get("queuedAsyncCount")),
        "readinessThrottledCount": _int(readiness.get("throttledCount")),
        "readinessBlockedCount": _int(readiness.get("blockedCount")),
        "readinessHasBlockingDecision": bool(readiness.get("hasBlockingDecision")),
        "readinessNextActions": _string_tuple(readiness.get("nextActions"))[:20],
        "readinessReasonCodes": readiness_reason_codes,
        "graphExecutionBoundary": _text(graph.get("executionBoundary")),
        "graphNodeCount": _int(graph.get("nodeCount")),
        "graphEdgeCount": _int(graph.get("edgeCount")),
        "graphBranches": _string_tuple(graph.get("branches"))[:20],
        "graphBranchCounts": graph_branch_counts,
        "graphToolExecuted": bool(durable_boundary.get("toolExecuted")),
        "graphOutboxWritten": bool(durable_boundary.get("outboxWritten")),
        "graphApprovalCreated": bool(durable_boundary.get("approvalCreated")),
        "graphWorkerReceiptRequiredForSideEffects": bool(
            durable_boundary.get("workerReceiptRequiredForSideEffects")
        ),
        "productionReadyForExecution": bool(production.get("readyForExecution")),
        "missingProductionRequirements": _string_tuple(production.get("missingProductionRequirements"))[:20],
        "decisionSummaries": _decision_summaries(readiness_items),
    }


def _decision_summaries(readiness_items: tuple[Mapping[str, Any], ...]) -> tuple[dict[str, Any], ...]:
    """从 readiness items 中提取低敏决策摘要。

    这里不返回 `argumentFieldNames`，是为了让事件比同步响应更克制。事件会进入长期 replay/Kafka/projection，
    只需要知道“哪个工具当前是什么决策、为什么”，不需要保存参数字段列表。
    """

    return tuple(
        {
            "toolName": _text(item.get("toolName")),
            "decision": _text(item.get("decision")),
            "executable": bool(item.get("executable")),
            "queueRequired": bool(item.get("queueRequired")),
            "requiresHumanApproval": bool(item.get("requiresHumanApproval")),
            "parameterIssueCount": _int(item.get("parameterIssueCount")),
            "issueCodes": _string_tuple(item.get("issueCodes"))[:10],
            "reasonCodes": _string_tuple(item.get("reasonCodes"))[:10],
            "retryHint": _text(item.get("retryHint")),
        }
        for item in readiness_items[:20]
    )


def _event_severity(response: Mapping[str, Any]) -> AgentRuntimeEventSeverity:
    """根据 intake/readiness 结果选择事件严重级别。"""

    intake = _mapping(response.get("toolActionIntake"))
    readiness = _mapping(response.get("toolExecutionReadiness"))
    if _int(readiness.get("blockedCount")) > 0:
        return AgentRuntimeEventSeverity.ERROR
    if _int(intake.get("rejectedBeforeReadinessCount")) > 0:
        return AgentRuntimeEventSeverity.WARNING
    if (
        _int(readiness.get("approvalRequiredCount")) > 0
        or _int(readiness.get("clarificationRequiredCount")) > 0
        or _int(readiness.get("throttledCount")) > 0
    ):
        return AgentRuntimeEventSeverity.AUDIT
    if _int(readiness.get("draftOnlyCount")) > 0:
        return AgentRuntimeEventSeverity.WARNING
    return AgentRuntimeEventSeverity.INFO


def _first_text(contexts: tuple[Mapping[str, Any], ...], *keys: str) -> str | None:
    """按上下文顺序读取第一个非空文本值。"""

    for context in contexts:
        for key in keys:
            value = context.get(key)
            text = _text(value)
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


def _string_key_dict(value: Any) -> dict[str, int]:
    """把计数字典转换为 `str -> int`，避免 Java 消费端遇到非字符串 key。"""

    if not isinstance(value, Mapping):
        return {}
    counter: Counter[str] = Counter()
    for key, item in value.items():
        key_text = _text(key)
        if key_text:
            counter[key_text] += _int(item)
    return dict(sorted(counter.items()))


def _limited_unique(values: Any, limit: int = 20) -> tuple[str, ...]:
    """保留有限数量的去重字符串，控制事件体积。"""

    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = _text(value)
        if not text or text in seen:
            continue
        seen.add(text)
        result.append(text)
        if len(result) >= limit:
            break
    return tuple(result)


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
