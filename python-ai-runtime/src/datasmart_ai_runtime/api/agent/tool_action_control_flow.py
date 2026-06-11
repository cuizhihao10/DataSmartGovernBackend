"""工具动作统一控制流预览 API 组装器。

该模块面向智能网关、Agent Host、本地调试脚本和未来 LangGraph/OpenClaw 条件节点，提供一个更高层的
只读预览入口：调用方可以提交模型 tool_calls、MCP tools/call 或 A2A task/action，Python Runtime 会
统一返回 intake、readiness、readiness graph、生产化缺口和下一步建议。

它与已有 MCP 专用接口的关系：
- MCP 专用接口继续保留，服务已经对外暴露的协议路径和测试契约；
- 本模块提供“统一控制流”视角，方便后续智能网关不必关心底层来源是模型、MCP 还是 A2A；
- 两者都不执行工具，不写 outbox，不创建审批，不回显原始参数值。
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from typing import Any

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import ModelToolCall, ToolDefinition
from datasmart_ai_runtime.services.agent_gateway.a2a_task_mapping_support import count_forbidden_fields
from datasmart_ai_runtime.services.tools import ToolActionControlFlowService, ToolActionIntakeSource


def build_tool_action_control_flow_preview_response(
    payload: Mapping[str, Any] | None,
    *,
    registered_tools: tuple[ToolDefinition, ...] | None = None,
    control_flow_service: ToolActionControlFlowService | None = None,
) -> dict[str, Any]:
    """构建统一工具动作控制流预览响应。

    支持的输入形态：
    - `{"source":"MODEL_TOOL_CALL","toolCalls":[...]}`：模型返回的工具调用意图；
    - `{"source":"MCP_TOOLS_CALL","method":"tools/call","params":{...}}`：MCP JSON-RPC 或兼容包装；
    - `{"source":"A2A_TASK_ACTION","contract":{...}}`：A2A task/action 控制面合同；
    - 未显式传 `source` 时，会按 `toolCalls`、`method=tools/call`、`task/contract` 等低敏结构做保守推断。

    安全边界：
    - 响应不会回显原始 `arguments` 值、prompt、SQL、样本数据、模型输出、凭证或内部 endpoint；
    - READY 只表示执行前条件图允许进入下一跳，不表示已经执行；
    - 所有真实副作用仍应由 Java 控制面、审批事实、outbox、worker receipt 和审计投影承接。
    """

    tools = registered_tools or default_tool_registry()
    service = control_flow_service or ToolActionControlFlowService()
    source = _source_from_payload(payload)
    visible_tools = _visible_tools_from_payload(payload, tools)

    if source == ToolActionIntakeSource.A2A_TASK_ACTION:
        contract = _a2a_contract_from_payload(payload)
        report = service.from_a2a_task_action(contract)
    elif source == ToolActionIntakeSource.MCP_TOOLS_CALL:
        call = _mcp_call_from_payload(payload)
        report = service.from_mcp_tools_call(
            call,
            registered_tools=tools,
            visible_tools=visible_tools,
        )
    else:
        report = service.from_model_tool_calls(
            _model_tool_calls_from_payload(payload),
            registered_tools=tools,
            visible_tools=visible_tools,
        )

    return report.to_low_sensitive_response(
        route={
            "method": "POST",
            "path": "/agent/tool-actions/control-flow-preview",
            "intent": "统一预览模型 tool_call、MCP tools/call 与 A2A task/action 的执行前控制流。",
        },
        input_payload_policy=_input_payload_policy(payload, source),
    )


def _source_from_payload(payload: Mapping[str, Any] | None) -> ToolActionIntakeSource:
    """从显式 source 或 payload 形态推断工具动作来源。

    推断规则保持保守：如果看不出 MCP 或 A2A，就按模型 tool_call 入口处理，并让后续 intake/readiness
    返回空计划或拒绝结果，而不是猜测成会产生副作用的动作。
    """

    if not isinstance(payload, Mapping):
        return ToolActionIntakeSource.MODEL_TOOL_CALL
    raw_source = str(payload.get("source") or payload.get("sourceType") or "").strip().upper()
    aliases = {
        "MODEL": ToolActionIntakeSource.MODEL_TOOL_CALL,
        "MODEL_TOOL_CALL": ToolActionIntakeSource.MODEL_TOOL_CALL,
        "MCP": ToolActionIntakeSource.MCP_TOOLS_CALL,
        "MCP_TOOLS_CALL": ToolActionIntakeSource.MCP_TOOLS_CALL,
        "A2A": ToolActionIntakeSource.A2A_TASK_ACTION,
        "A2A_TASK_ACTION": ToolActionIntakeSource.A2A_TASK_ACTION,
    }
    if raw_source in aliases:
        return aliases[raw_source]
    if _model_tool_calls_payload(payload):
        return ToolActionIntakeSource.MODEL_TOOL_CALL
    if str(payload.get("method") or "") == "tools/call" or isinstance(payload.get("call") or payload.get("toolCall"), Mapping):
        return ToolActionIntakeSource.MCP_TOOLS_CALL
    if isinstance(payload.get("contract"), Mapping) or isinstance(payload.get("task"), Mapping):
        return ToolActionIntakeSource.A2A_TASK_ACTION
    return ToolActionIntakeSource.MODEL_TOOL_CALL


def _model_tool_calls_from_payload(payload: Mapping[str, Any] | None) -> tuple[ModelToolCall, ...]:
    """把请求中的模型工具调用列表转换为领域对象。

    `arguments` 可以是字符串，也可以是对象。对象会被序列化为 JSON 字符串供现有 schema 校验器复用；
    响应层仍然只返回参数字段名，不返回真实值。
    """

    calls = _model_tool_calls_payload(payload)
    return tuple(_model_tool_call_from_item(item) for item in calls)


def _model_tool_calls_payload(payload: Mapping[str, Any] | None) -> tuple[Mapping[str, Any], ...]:
    """提取 `toolCalls` / `tool_calls` 列表，非法形态返回空 tuple。"""

    if not isinstance(payload, Mapping):
        return ()
    raw_calls = payload.get("toolCalls")
    if raw_calls is None:
        raw_calls = payload.get("tool_calls")
    if not isinstance(raw_calls, Sequence) or isinstance(raw_calls, (str, bytes, bytearray)):
        return ()
    return tuple(item for item in raw_calls if isinstance(item, Mapping))


def _model_tool_call_from_item(item: Mapping[str, Any]) -> ModelToolCall:
    """兼容 OpenAI-compatible 与 DataSmart 本地测试格式的工具调用对象。"""

    function = item.get("function")
    function_mapping = function if isinstance(function, Mapping) else {}
    raw_arguments = item.get("arguments", function_mapping.get("arguments", ""))
    if isinstance(raw_arguments, str):
        arguments = raw_arguments
    else:
        arguments = json.dumps(raw_arguments, ensure_ascii=False, sort_keys=True)
    return ModelToolCall(
        call_id=str(item.get("callId") or item.get("id") or ""),
        type=str(item.get("type") or "function"),
        name=str(item.get("name") or function_mapping.get("name") or ""),
        arguments=arguments,
        raw_call={},
    )


def _mcp_call_from_payload(payload: Mapping[str, Any] | None) -> Mapping[str, Any] | None:
    """从统一入口 payload 中提取 MCP `tools/call` 参数对象。"""

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
    """给 MCP 嵌套参数补齐低敏关联 ID。"""

    if "callId" in call or "id" in call or fallback_call_id in (None, ""):
        return call
    copied = dict(call)
    copied["callId"] = str(fallback_call_id)
    return copied


def _a2a_contract_from_payload(payload: Mapping[str, Any] | None) -> Mapping[str, Any] | None:
    """提取 A2A 合同，兼容直接合同和 `{contract:{...}}` 包装。"""

    if not isinstance(payload, Mapping):
        return None
    contract = payload.get("contract")
    return contract if isinstance(contract, Mapping) else payload


def _visible_tools_from_payload(
    payload: Mapping[str, Any] | None,
    registered_tools: tuple[ToolDefinition, ...],
) -> tuple[ToolDefinition, ...] | None:
    """根据低敏可见工具名裁剪本轮工具目录。"""

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
    """把字符串列表归一为 tuple，区分“未传”和“明确传空列表”。"""

    if value is None:
        return None
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)):
        return ()
    return tuple(str(item).strip() for item in value if str(item).strip())


def _input_payload_policy(payload: Mapping[str, Any] | None, source: ToolActionIntakeSource) -> dict[str, Any]:
    """生成统一入口的低敏输入处理说明。"""

    return {
        "accepted": f"{source.value}_CONTROL_FLOW_PREVIEW",
        "sourceDetected": source.value,
        "notEchoed": True,
        "rawExecutionPayloadAllowed": False,
        "sensitiveFieldHandling": "COUNT_AND_DROP_WITHOUT_FIELD_NAMES_OR_VALUES",
        "sensitiveFieldIgnoredCount": count_forbidden_fields(payload or {}),
    }
