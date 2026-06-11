"""MCP tools/call 工具动作意图预检 API 响应组装。

本模块把 MCP 客户端提交的 `tools/call` 风格请求转换为 DataSmart 内部统一的 `ToolPlan` 候选，
然后立即进入工具执行准备度（readiness）和 readiness graph 评估。

为什么只做“预检”而不直接执行：
- MCP 的 `tools/call` 在协议层看起来像一次普通 JSON-RPC 调用，但在企业数据治理产品里，它可能触发
  数据源读取、任务创建、规则生成、导出、写库或外部系统调用；
- DataSmart 的商业化边界要求所有副作用都必须经过 permission-admin、幂等键、outbox、worker receipt、
  审批/澄清和审计链路，不能让外部协议入口绕过控制面；
- 因此本接口只把外部意图归一为低敏摘要，不回显原始参数值，也不写数据库、不发 Kafka、不调用 Java
  执行器，更不连接真实 MCP Server。

支持的输入形态：
- 标准 JSON-RPC 2.0 请求：`{"jsonrpc":"2.0","id":"...","method":"tools/call","params":{...}}`；
- 兼容包装请求：`{"call": {"name":"...", "arguments": {...}}, "visibleToolNames": [...]}`；
- 直接参数请求：`{"name":"...", "arguments": {...}}`，便于本地联调和单元测试。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import ToolDefinition
from datasmart_ai_runtime.services.agent_gateway.a2a_task_mapping_support import count_forbidden_fields
from datasmart_ai_runtime.services.tools import ToolExecutionReadinessService
from datasmart_ai_runtime.services.tools.tool_action_intake import ToolActionIntakeService
from datasmart_ai_runtime.services.tools.tool_execution_readiness_graph import (
    build_tool_execution_readiness_graph_response,
)


def build_mcp_tool_call_intake_preview_response(
    payload: Mapping[str, Any] | None,
    *,
    registered_tools: tuple[ToolDefinition, ...] | None = None,
    intake_service: ToolActionIntakeService | None = None,
    readiness_service: ToolExecutionReadinessService | None = None,
) -> dict[str, Any]:
    """构建 MCP `tools/call` intake preview 响应。

    路由用途：
    - 让 Java agent-runtime、智能网关、外部 MCP Host 或本地联调脚本先验证“这个工具调用意图会被平台如何治理”；
    - 复用 `ToolActionIntakeService`，把 MCP 请求归一成与模型 tool_call 相同的 ToolPlan/readiness 链路；
    - 返回低敏 intake 摘要、readiness 摘要和 readiness graph，帮助后续 OpenClaw/LangGraph 风格节点做条件跳转。

    安全边界：
    - 不执行工具、不写 outbox、不创建审批单、不读取 artifact 正文；
    - 不回显 `arguments` 的真实值、prompt、SQL、样本数据、模型输出、凭证或内部 endpoint；
    - 只返回工具名、参数字段名、风险等级、执行模式、issue/reason code 和数量类统计。
    """

    tools = registered_tools or default_tool_registry()
    call = _mcp_call_from_payload(payload)
    visible_tools = _visible_tools_from_payload(payload, tools)
    intake = (intake_service or ToolActionIntakeService()).from_mcp_tools_call(
        call,
        registered_tools=tools,
        visible_tools=visible_tools,
    )
    readiness = (readiness_service or ToolExecutionReadinessService()).evaluate(
        intake.accepted_tool_plans,
        policy_metadata={
            "source": "mcp_tools_call_preview",
            "protocol": "MCP",
            "previewOnly": True,
            "registeredToolCount": len(tools),
            "visibleToolCount": len(visible_tools) if visible_tools is not None else len(tools),
        },
    )
    readiness_summary = _tool_execution_readiness_summary(readiness)
    readiness_graph = build_tool_execution_readiness_graph_response(readiness)
    intake_summary = intake.to_low_sensitive_summary()
    return {
        "schemaVersion": "datasmart.python-ai-runtime.mcp-tools-call-intake-preview.v1",
        "previewOnly": True,
        "toolExecutionEnabled": False,
        "protocolFamily": "MCP",
        "route": {
            "method": "POST",
            "path": "/agent/protocol-adapters/mcp/tools-call-intake-preview",
            "intent": "把 MCP tools/call 请求归一为 DataSmart ToolPlan 候选，并执行低敏 readiness 预检。",
        },
        "inputPayloadPolicy": _input_payload_policy(payload, call),
        "toolActionIntake": intake_summary,
        "toolExecutionReadiness": readiness_summary,
        "toolExecutionReadinessGraph": readiness_graph,
        "productionReadiness": _production_readiness(intake_summary, readiness_summary),
        "nextSteps": _next_steps(intake_summary, readiness_summary),
    }


def _mcp_call_from_payload(payload: Mapping[str, Any] | None) -> Mapping[str, Any] | None:
    """从多种请求包装中提取真正的 MCP `tools/call` 参数。

    标准 MCP 使用 JSON-RPC 2.0，工具调用方法名为 `tools/call`，实际工具名和参数位于 `params`。
    本函数只做协议形态归一，不做工具存在性、可见性、参数 schema 或权限判断；这些治理规则继续交给
    `ToolActionIntakeService` 和 `ModelToolCallPlanner`，保证模型 tool_call 与 MCP tools/call 使用同一套闸门。
    """

    if not isinstance(payload, Mapping):
        return None

    nested_call = payload.get("call") or payload.get("toolCall")
    if isinstance(nested_call, Mapping):
        return _with_call_id(nested_call, payload.get("id"))

    method = str(payload.get("method") or "").strip()
    params = payload.get("params")
    if method:
        if method != "tools/call" or not isinstance(params, Mapping):
            return None
        return _with_call_id(params, payload.get("id"))

    return payload


def _with_call_id(call: Mapping[str, Any], fallback_call_id: Any) -> Mapping[str, Any]:
    """为嵌套 `params/call` 补齐低敏调用关联 ID。

    MCP JSON-RPC 的 `id` 用于请求/响应关联。DataSmart intake 层只把它当作低敏关联字段处理，方便后续
    runtime event、Java projection 或前端诊断定位同一次预检；它不会参与权限判断，也不会被当成幂等键。
    """

    if "callId" in call or "id" in call or fallback_call_id in (None, ""):
        return call
    copied = dict(call)
    copied["callId"] = str(fallback_call_id)
    return copied


def _visible_tools_from_payload(
    payload: Mapping[str, Any] | None,
    registered_tools: tuple[ToolDefinition, ...],
) -> tuple[ToolDefinition, ...] | None:
    """根据请求中的低敏可见工具名裁剪本轮工具集合。

    真实生产环境中，工具可见性应由 permission-admin、租户套餐、项目空间、用户角色和智能网关共同决定。
    这个 preview 接口支持 `visibleToolNames`，是为了模拟“外部 MCP Host 本轮只被授权看到部分工具”的场景。
    如果调用方不传该字段，则默认使用全部注册工具；如果传空列表，则表示本轮没有任何可见工具，后续会按
    `MODEL_TOOL_CALL_NOT_EXPOSED` 或 unknown tool 进入 readiness 前拒绝分支。
    """

    if not isinstance(payload, Mapping):
        return None
    visible_names = _string_sequence(payload.get("visibleToolNames"))
    if visible_names is None:
        context = payload.get("context")
        if isinstance(context, Mapping):
            visible_names = _string_sequence(context.get("visibleToolNames"))
    if visible_names is None:
        return None
    allowed = set(visible_names)
    return tuple(tool for tool in registered_tools if tool.name in allowed)


def _string_sequence(value: Any) -> tuple[str, ...] | None:
    """把可见工具名列表归一为去空字符串 tuple。

    该字段只允许列表/元组形态，避免把单个字符串误拆成字符序列。返回 `None` 表示调用方没有声明该约束；
    返回空 tuple 则表示调用方明确声明“本轮没有可见工具”。
    """

    if value is None:
        return None
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)):
        return ()
    return tuple(str(item).strip() for item in value if str(item).strip())


def _input_payload_policy(payload: Mapping[str, Any] | None, call: Mapping[str, Any] | None) -> dict[str, Any]:
    """生成输入 payload 的低敏处理策略说明。

    这里刻意只返回“是否检测到 JSON-RPC、方法是否被接受、敏感字段被丢弃数量”等元信息，不返回原始请求体、
    参数值或敏感字段路径。这样调用方可以知道请求为什么进入或未进入 ToolPlan 链路，却不能从响应里恢复
    MCP 原始工具参数。
    """

    method = str(payload.get("method") or "").strip() if isinstance(payload, Mapping) else ""
    return {
        "accepted": "MCP_TOOLS_CALL_JSON_RPC_OR_COMPATIBLE_PARAMS",
        "jsonRpcDetected": bool(method),
        "method": method or "tools/call-compatible",
        "methodAccepted": not method or method == "tools/call",
        "callDetected": isinstance(call, Mapping),
        "notEchoed": True,
        "rawExecutionPayloadAllowed": False,
        "sensitiveFieldHandling": "COUNT_AND_DROP_WITHOUT_FIELD_NAMES_OR_VALUES",
        "sensitiveFieldIgnoredCount": count_forbidden_fields(payload or {}),
    }


def _tool_execution_readiness_summary(readiness: Any) -> dict[str, Any]:
    """把 readiness report 转换成 HTTP 可返回的低敏摘要。

    该函数与 `/agent/plans` 的 readiness 响应保持同一套字段语义，但放在 MCP API helper 内部实现，避免
    导入 `api/agent/plan_response.py` 的私有函数。字段白名单只包含执行前决策、风险等级、参数字段名和 reason code。
    """

    return {
        "snapshotType": "TOOL_EXECUTION_READINESS",
        "payloadPolicy": "LOW_SENSITIVE_METADATA_ONLY",
        "policy": readiness.policy_metadata or {},
        "totalCount": readiness.total_count,
        "executableCount": readiness.executable_count,
        "approvalRequiredCount": readiness.approval_required_count,
        "clarificationRequiredCount": readiness.clarification_required_count,
        "draftOnlyCount": readiness.draft_only_count,
        "queuedAsyncCount": readiness.queued_async_count,
        "throttledCount": readiness.throttled_count,
        "blockedCount": readiness.blocked_count,
        "hasBlockingDecision": readiness.has_blocking_decision,
        "nextActions": readiness.next_actions,
        "items": tuple(
            {
                "planIndex": item.plan_index,
                "toolName": item.tool_name,
                "decision": item.decision.value,
                "executable": item.executable,
                "queueRequired": item.queue_required,
                "requiresHumanApproval": item.requires_human_approval,
                "riskLevel": item.risk_level,
                "executionMode": item.execution_mode,
                "targetService": item.target_service,
                "argumentFieldNames": item.argument_field_names,
                "sensitiveArgumentNames": item.sensitive_argument_names,
                "parameterIssueCount": item.parameter_issue_count,
                "issueCodes": item.issue_codes,
                "reasonCodes": item.reason_codes,
                "retryHint": item.retry_hint,
                "payloadPolicy": item.payload_policy,
            }
            for item in readiness.items
        ),
    }


def _production_readiness(
    intake_summary: Mapping[str, Any],
    readiness_summary: Mapping[str, Any],
) -> dict[str, Any]:
    """生成面向产品路线的生产化缺口说明。

    即使 readiness 显示某个工具 `ready_to_execute`，本 preview 接口也仍然返回 `readyForExecution=false`。
    原因是“准备度通过”只代表执行前条件图允许进入下一跳，不代表真实执行链路已经具备认证、审计、队列、
    幂等、worker receipt 和结果回写能力。
    """

    return {
        "readyForExecution": False,
        "intakeAcceptedToolPlanCount": int(intake_summary.get("acceptedToolPlanCount") or 0),
        "readinessExecutableCount": int(readiness_summary.get("executableCount") or 0),
        "missingProductionRequirements": (
            "MCP_JSON_RPC_SERVER_AND_SESSION_LIFECYCLE",
            "MCP_CLIENT_AUTHENTICATION_AND_TOOL_SCOPE",
            "PERMISSION_ADMIN_POLICY_AND_APPROVAL_BINDING",
            "IDEMPOTENCY_KEY_AND_RATE_LIMIT",
            "OUTBOX_COMMAND_AND_WORKER_RECEIPT",
            "RUNTIME_EVENT_TIMELINE_AND_REPLAY_PROJECTION",
            "TOOL_RESULT_SANITIZATION_AND_ARTIFACT_REFERENCE",
        ),
        "boundary": "本接口只做 MCP tools/call intake/readiness 预检，不代表工具已执行或 MCP Server 已生产可用。",
    }


def _next_steps(
    intake_summary: Mapping[str, Any],
    readiness_summary: Mapping[str, Any],
) -> tuple[str, ...]:
    """根据 intake 与 readiness 结果生成下一步建议。

    这些建议是产品和工程路线提示，不是自动执行命令。真实执行仍需要 Java 控制面和受控 worker 承接。
    """

    if int(intake_summary.get("rejectedBeforeReadinessCount") or 0) > 0:
        return (
            "先检查 MCP 工具名、JSON-RPC method、工具可见性和参数 JSON object 形态，拒绝项不能进入执行器。",
            "将拒绝原因写入低敏 runtime event，方便 MCP Host、智能网关和审计台定位协议或权限问题。",
        )
    if int(readiness_summary.get("clarificationRequiredCount") or 0) > 0:
        return (
            "让 Agent 生成低敏追问，等待用户或上游 MCP Host 补齐必填参数后重新预检。",
            "不要让模型自行猜测数据源、SQL、导出路径、审批理由或其他可能产生副作用的参数。",
        )
    if int(readiness_summary.get("approvalRequiredCount") or 0) > 0:
        return (
            "把该工具计划送入 permission-admin 审批/确认链路，审批通过前不能执行真实副作用。",
            "审批页只展示字段名、风险等级、工具说明和脱敏摘要，不展示原始参数值。",
        )
    if int(readiness_summary.get("executableCount") or 0) > 0:
        return (
            "下一阶段应接 Java outbox/worker receipt，而不是在 HTTP preview handler 内直接执行工具。",
            "为 MCP tools/call 增加幂等键、租户级速率限制、执行超时预算和结果脱敏策略。",
        )
    return (
        "当前没有可进入 readiness 的工具计划，继续保持只读诊断态。",
        "如这是预期工具调用，请先确认工具注册表、可见工具集合和 MCP params.name 是否一致。",
    )
