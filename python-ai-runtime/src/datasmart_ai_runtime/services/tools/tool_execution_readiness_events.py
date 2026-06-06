"""工具执行准备度 Runtime Event 适配器。

`ToolExecutionReadinessService` 负责判断本轮工具计划是否可执行、需审批、需澄清、需排队或被阻断。
但准备度报告本身还只是 Python 内存对象，如果不转换成 Runtime Event，前端刷新、WebSocket replay、
Java 投影和审计系统仍然看不到“为什么工具没有继续执行”。

本模块专门把 readiness report 压缩成低敏事件。它的安全原则与 Agent 其他事件一致：
- 记录工具名、决策、风险、执行模式、issue/reason code 和计数；
- 不记录工具参数值、SQL、prompt、样本数据、任务 payload 明细、模型输出、凭证或内部 endpoint；
- 限制列表长度，避免模型一次性提出大量工具时撑大事件体。
"""

from __future__ import annotations

from collections import Counter
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness import (
    ToolExecutionReadinessDecision,
    ToolExecutionReadinessReport,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness_graph import (
    build_tool_execution_readiness_graph_response,
)


def build_tool_execution_readiness_runtime_event(
    plan: AgentPlan,
    request: AgentRequest,
    readiness: ToolExecutionReadinessReport,
) -> AgentRuntimeEvent:
    """把工具执行准备度报告转换为可回放的运行时事件。

    事件生命周期：
    - 响应组装层在 `ToolPlan` 已经附加 workspace hint 之后生成 readiness；
    - readiness event 追加到 `plan.runtime_events`，再进入 HTTP snapshot、event store、WebSocket 和 publisher；
    - Java 控制面后续可按事件类型建立 projection，用于展示“等待审批/澄清/预算恢复”的原因。
    """

    first_event = plan.runtime_events[0] if plan.runtime_events else None
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.TOOL_EXECUTION_READINESS_RECORDED,
        stage="record_tool_execution_readiness",
        message="已记录本轮工具执行准备度治理快照。",
        severity=_event_severity(readiness),
        tenant_id=request.tenant_id,
        project_id=request.project_id,
        actor_id=request.actor_id,
        request_id=plan.request_id,
        run_id=first_event.run_id if first_event else None,
        session_id=first_event.session_id if first_event else _request_session_id(request),
        sequence=_next_runtime_event_sequence(plan),
        attributes=_readiness_event_attributes(readiness),
    )


def _readiness_event_attributes(readiness: ToolExecutionReadinessReport) -> dict[str, Any]:
    """生成低敏事件 attributes。

    同步响应中的 `toolExecutionReadiness.items` 可以保留较完整的低敏 item；事件层进一步压缩，
    只保留 Java projection、timeline 和指标聚合真正需要的字段。
    """

    limited_items = readiness.items[:20]
    decision_counts = Counter(item.decision.value for item in readiness.items)
    risk_counts = Counter(item.risk_level for item in readiness.items if item.risk_level)
    mode_counts = Counter(item.execution_mode for item in readiness.items if item.execution_mode)
    graph_attributes = _readiness_graph_event_attributes(readiness)
    return {
        "eventPayloadVersion": "v1",
        "snapshotType": "TOOL_EXECUTION_READINESS",
        "payloadPolicy": "LOW_SENSITIVE_METADATA_ONLY",
        "policySource": _policy_metadata_value(readiness, "source"),
        "policyVersion": _policy_metadata_value(readiness, "policyVersion"),
        "policyInfluenceCodes": tuple(_policy_metadata_sequence(readiness, "influenceCodes"))[:20],
        "tenantPlanCode": _policy_metadata_value(readiness, "tenantPlanCode"),
        "workspaceRiskLevel": _policy_metadata_value(readiness, "workspaceRiskLevel"),
        "workerBacklogLevel": _policy_metadata_value(readiness, "workerBacklogLevel"),
        "totalCount": readiness.total_count,
        "executableCount": readiness.executable_count,
        "approvalRequiredCount": readiness.approval_required_count,
        "clarificationRequiredCount": readiness.clarification_required_count,
        "draftOnlyCount": readiness.draft_only_count,
        "queuedAsyncCount": readiness.queued_async_count,
        "throttledCount": readiness.throttled_count,
        "blockedCount": readiness.blocked_count,
        "hasBlockingDecision": readiness.has_blocking_decision,
        "nextActions": readiness.next_actions[:20],
        "decisionCounts": dict(sorted(decision_counts.items())),
        "riskLevelCounts": dict(sorted(risk_counts.items())),
        "executionModeCounts": dict(sorted(mode_counts.items())),
        "toolNames": tuple(item.tool_name for item in limited_items),
        "toolNamesTruncatedCount": max(0, len(readiness.items) - len(limited_items)),
        **graph_attributes,
        "decisionSummaries": tuple(
            {
                "toolName": item.tool_name,
                "decision": item.decision.value,
                "executable": item.executable,
                "queueRequired": item.queue_required,
                "requiresHumanApproval": item.requires_human_approval,
                "reasonCodes": item.reason_codes[:10],
                "issueCodes": item.issue_codes[:10],
                "parameterIssueCount": item.parameter_issue_count,
                "sensitiveArgumentNames": item.sensitive_argument_names[:10],
                "retryHint": item.retry_hint,
            }
            for item in limited_items
        ),
    }


def _readiness_graph_event_attributes(readiness: ToolExecutionReadinessReport) -> dict[str, Any]:
    """把 readiness graph 压缩成 runtime event 低敏摘要。

    `/agent/plans` 同步响应可以返回完整的图谱 nodes/edges，因为它面向本次请求的即时治理卡片；
    runtime event 则需要更克制：事件会进入 replay、Kafka、Java projection 和审计链路，体积和敏感边界都更严格。

    因此这里刻意只保留：
    - 图谱版本、快照类型和 payload policy，方便 Java 判断字段语义；
    - execution boundary，提醒消费方这仍是执行前条件图；
    - node/edge/branch 计数，方便 timeline 和报表展示；
    - durable action boundary，明确本事件没有执行工具、没有写 outbox、没有创建审批单。

    注意：这里不复制 `nodes`、`edges`、工具参数值、SQL、prompt、样本数据或模型输出。
    """

    graph = build_tool_execution_readiness_graph_response(readiness)
    durable_boundary = graph.get("durableActionBoundary")
    if not isinstance(durable_boundary, dict):
        durable_boundary = {}
    return {
        "graphSnapshotType": graph.get("snapshotType"),
        "graphPayloadPolicy": graph.get("payloadPolicy"),
        "graphVersion": graph.get("graphVersion"),
        "graphExecutionBoundary": graph.get("executionBoundary"),
        "graphNodeCount": graph.get("nodeCount"),
        "graphEdgeCount": graph.get("edgeCount"),
        "graphBranches": tuple(str(branch) for branch in graph.get("branches", ()) if str(branch).strip())[:20],
        "graphBranchCounts": dict(graph.get("branchCounts") or {}),
        "graphToolExecuted": bool(durable_boundary.get("toolExecuted")),
        "graphOutboxWritten": bool(durable_boundary.get("outboxWritten")),
        "graphApprovalCreated": bool(durable_boundary.get("approvalCreated")),
        "graphWorkerReceiptRequiredForSideEffects": bool(
            durable_boundary.get("workerReceiptRequiredForSideEffects")
        ),
    }


def _policy_metadata_value(readiness: ToolExecutionReadinessReport, key: str) -> Any:
    """从 readiness report 中读取低敏策略元数据。

    策略元数据只允许包含来源、版本、套餐、风险、backlog 和影响码等枚举/摘要字段。这里不做深层对象
    展开，避免未来调用方误把完整权限对象或内部策略表达式放进 runtime event。
    """

    if not readiness.policy_metadata:
        return None
    value = readiness.policy_metadata.get(key)
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def _policy_metadata_sequence(readiness: ToolExecutionReadinessReport, key: str) -> tuple[str, ...]:
    """读取策略影响码列表，并统一转成字符串元组。"""

    if not readiness.policy_metadata:
        return ()
    value = readiness.policy_metadata.get(key)
    if isinstance(value, str):
        return (value,)
    if isinstance(value, (list, tuple, set, frozenset)):
        return tuple(str(item) for item in value if str(item).strip())
    return ()


def _event_severity(readiness: ToolExecutionReadinessReport) -> AgentRuntimeEventSeverity:
    """根据准备度结果选择事件严重级别。"""

    if readiness.blocked_count:
        return AgentRuntimeEventSeverity.ERROR
    if readiness.approval_required_count or readiness.clarification_required_count or readiness.throttled_count:
        return AgentRuntimeEventSeverity.AUDIT
    if readiness.draft_only_count:
        return AgentRuntimeEventSeverity.WARNING
    return AgentRuntimeEventSeverity.INFO


def _next_runtime_event_sequence(plan: AgentPlan) -> int:
    """计算追加事件 sequence，兼容前序事件已被其他响应组装步骤追加的情况。"""

    sequences = tuple(event.sequence for event in plan.runtime_events if event.sequence is not None)
    return (max(sequences) + 1) if sequences else len(plan.runtime_events) + 1


def _request_session_id(request: AgentRequest) -> str | None:
    """从请求变量读取 sessionId 作为兜底。"""

    value = request.variables.get("sessionId") or request.variables.get("session_id")
    if value is None:
        return None
    text = str(value).strip()
    return text or None
