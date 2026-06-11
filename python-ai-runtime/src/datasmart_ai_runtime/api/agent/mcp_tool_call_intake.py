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
from datasmart_ai_runtime.services.tools import (
    ToolActionControlFlowService,
    ToolActionIntakeService,
    ToolExecutionReadinessService,
)


def build_mcp_tool_call_intake_preview_response(
    payload: Mapping[str, Any] | None,
    *,
    registered_tools: tuple[ToolDefinition, ...] | None = None,
    intake_service: ToolActionIntakeService | None = None,
    readiness_service: ToolExecutionReadinessService | None = None,
    control_flow_service: ToolActionControlFlowService | None = None,
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
    service = control_flow_service or ToolActionControlFlowService(
        intake_service=intake_service,
        readiness_service=readiness_service,
    )
    control_flow = service.from_mcp_tools_call(
        call,
        registered_tools=tools,
        visible_tools=visible_tools,
    )
    return control_flow.to_low_sensitive_response(
        schema_version="datasmart.python-ai-runtime.mcp-tools-call-intake-preview.v1",
        route={
            "method": "POST",
            "path": "/agent/protocol-adapters/mcp/tools-call-intake-preview",
            "intent": "把 MCP tools/call 请求归一为 DataSmart ToolPlan 候选，并执行低敏 readiness 预检。",
        },
        input_payload_policy=_input_payload_policy(payload, call),
    )


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
