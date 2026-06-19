"""工具动作 Adapter Contract API 响应组装。

该模块提供只读 contract 查询 helper，让智能网关、Java agent-runtime、管理台或测试脚本可以看到当前
Python Runtime 对模型 tool_call、MCP tools/call、A2A task/action 的统一接入契约。

它不执行工具、不读取数据库、不访问远端协议端点，只返回低敏契约元数据。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.services.tools import (
    ToolActionIntakeSource,
    default_tool_action_adapter_contract_registry,
)


def build_tool_action_adapter_contracts_response(
    payload: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """构建工具动作 Adapter Contract 查询响应。

    参数说明：
    - `payload.source` 可选，用于只查询某一种入口来源，例如 `MCP_TOOLS_CALL`；
    - 不传 source 时返回全部 contract，适合管理台和平台收敛诊断使用；
    - source 非法时返回空 contracts 和低敏 issue，不抛出 HTTP 层异常，便于网关诊断页展示。
    """

    registry = default_tool_action_adapter_contract_registry()
    source = _source_from_payload(payload)
    if source is None:
        return registry.diagnostics()
    try:
        contract = registry.get(source)
    except ValueError:
        return {
            "schemaVersion": "datasmart.tool-action-adapter-contract-registry.v1",
            "diagnosticType": "TOOL_ACTION_ADAPTER_CONTRACTS",
            "contractCount": 0,
            "contracts": (),
            "issues": (
                {
                    "code": "UNKNOWN_TOOL_ACTION_ADAPTER_SOURCE",
                    "source": str(source),
                    "message": "请求的工具动作入口来源尚未维护 Adapter Contract。",
                },
            ),
            "payloadPolicy": "LOW_SENSITIVE_ADAPTER_CONTRACT_METADATA_ONLY",
        }
    return {
        "schemaVersion": "datasmart.tool-action-adapter-contract-registry.v1",
        "diagnosticType": "TOOL_ACTION_ADAPTER_CONTRACTS",
        "contractCount": 1,
        "contracts": (contract.to_low_sensitive_summary(),),
        "payloadPolicy": "LOW_SENSITIVE_ADAPTER_CONTRACT_METADATA_ONLY",
    }


def _source_from_payload(payload: Mapping[str, Any] | None) -> str | None:
    """从请求参数中读取来源。

    GET 路由会传入 query 参数，测试或未来 POST 诊断也可以传 dict。这里只做字符串归一化，不解析原始
    payload、工具参数或协议正文。
    """

    if not isinstance(payload, Mapping):
        return None
    raw = payload.get("source") or payload.get("sourceType")
    if raw in (None, ""):
        return None
    text = str(raw).strip().upper()
    aliases = {
        "MODEL": ToolActionIntakeSource.MODEL_TOOL_CALL.value,
        "MODEL_TOOL_CALL": ToolActionIntakeSource.MODEL_TOOL_CALL.value,
        "MCP": ToolActionIntakeSource.MCP_TOOLS_CALL.value,
        "MCP_TOOLS_CALL": ToolActionIntakeSource.MCP_TOOLS_CALL.value,
        "A2A": ToolActionIntakeSource.A2A_TASK_ACTION.value,
        "A2A_TASK_ACTION": ToolActionIntakeSource.A2A_TASK_ACTION.value,
    }
    return aliases.get(text, text)


__all__ = ["build_tool_action_adapter_contracts_response"]
