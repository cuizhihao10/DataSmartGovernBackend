"""MCP durable worker 内部执行路由。

本模块把上一阶段已经完成的 MCP 执行能力暴露成“Java outbox/内部 worker 可调用”的 HTTP 合同。
它不是给前端或模型直接调用的调试接口，也不是绕过 Java 控制面的工具执行入口；它要求调用方携带
Java command proposal、outbox、permission、readiness、approval 等低敏控制面 facts，然后由
`McpDurableWorkerAdapter` 继续执行 admission、真实 MCP 调用、worker receipt 生成和可选 Java receipt
写回。

为什么单独拆出本文件：
- `api/app.py` 只负责应用装配，不继续膨胀业务 handler；
- 未来如果 Java outbox consumer 从 HTTP 迁移到 Kafka/gRPC，这里的 payload 解析、低敏响应和 feedback
  适配逻辑仍可被复用；
- 内部执行入口需要非常清晰地区分“短生命周期 arguments”与“可持久化低敏摘要”，放在独立模块更容易审计。
"""

from __future__ import annotations

from typing import Any, Mapping

from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedback,
)
from datasmart_ai_runtime.services.tools.mcp import (
    McpDurableWorkerAdapter,
    McpDurableWorkerRunRequest,
    McpToolFeedbackAdapter,
)


MCP_DURABLE_WORKER_API_SCHEMA_VERSION = "datasmart.mcp-durable-worker-api.v1"


def register_mcp_durable_worker_routes(
    app: Any,
    *,
    worker_adapter: McpDurableWorkerAdapter,
    feedback_adapter: McpToolFeedbackAdapter | None = None,
) -> None:
    """注册 MCP durable worker 内部执行路由。

    路由设计：
    - `/internal/agent/mcp/durable-worker/run`：Python Runtime 直连内部路径，适合本地 smoke 或同网络服务调用；
    - `/api/internal/agent/mcp/durable-worker/run`：预留给统一 gateway 反向代理后的等价路径。

    安全边界：
    - 路由只接受 Java 控制面 facts，不接受“模型自称已授权”的 admission；
    - `arguments` 只进入 `McpDurableWorkerRunRequest` 的短生命周期内存对象，不会出现在响应 summary 中；
    - 响应中的 `workerResult` 来自 `to_summary()`，不会包含 MCP 工具正文；
    - 如开启 model feedback，只有 `McpToolFeedbackAdapter` 判定安全的小结果才会进入 `modelFeedback.feedback.result`；
    - 真正生产部署时，该路由仍应置于 gateway/service-account/OIDC 或 mTLS 保护之后，本模块只固定业务合同。
    """

    async def _run(payload: dict[str, Any]) -> dict[str, Any]:
        """执行一次 MCP durable worker，并返回低敏结果。

        这里把 handler 抽成内部函数，再绑定两个路径，保证直连路径和 gateway 路径行为完全一致。
        """

        request = mcp_worker_request_from_payload(payload)
        worker_result = await worker_adapter.run(request)
        include_model_feedback = _bool(payload.get("includeModelFeedback"), default=True)
        model_feedback = None
        if include_model_feedback and feedback_adapter is not None:
            build_result = feedback_adapter.build(
                worker_result,
                tool_call_id=_optional_text(payload.get("toolCallId") or payload.get("tool_call_id")),
                workspace_key=_optional_text(payload.get("workspaceKey") or payload.get("workspace_key")),
                current_workspace_key=_optional_text(
                    payload.get("currentWorkspaceKey") or payload.get("current_workspace_key")
                ),
            )
            model_feedback = {
                "summary": build_result.summary,
                "feedback": tool_execution_feedback_to_payload(build_result.feedback),
            }
        return {
            "schemaVersion": MCP_DURABLE_WORKER_API_SCHEMA_VERSION,
            "accepted": True,
            "workerResult": worker_result.to_summary(),
            "receipt": worker_result.receipt.to_summary(),
            "modelFeedback": model_feedback,
            "payloadPolicy": (
                "MCP_ARGUMENTS_NEVER_RETURNED;TOOL_RESULT_BODY_RETURNED_ONLY_IF_FEEDBACK_ADAPTER_ALLOWED_SAFE_INLINE"
            ),
        }

    app.post("/internal/agent/mcp/durable-worker/run")(_run)
    app.post("/api/internal/agent/mcp/durable-worker/run")(_run)


def mcp_worker_request_from_payload(payload: Mapping[str, Any]) -> McpDurableWorkerRunRequest:
    """把 HTTP payload 转换为 `McpDurableWorkerRunRequest`。

    字段映射兼容 camelCase 与 snake_case，方便 Java DTO、Python 测试和未来 Kafka 消息复用同一合同：
    - `serverId/server_id`：MCP Server ID；
    - `internalToolName/internal_tool_name/toolCode`：DataSmart 内部工具名；
    - `arguments`：短生命周期 MCP 实参，必须是对象；
    - `controlFacts/control_facts`：Java 控制面 facts，必须是对象；
    - `fallbackContext/fallback_context`：可选可信上下文补齐字段；
    - `postToJava/post_to_java`：是否把 receipt 写回 Java；
    - `sessionId/session_id`、`traceId/trace_id`：路由和链路追踪低敏字段。

    注意：校验失败时只抛出稳定错误说明，不携带字段值，避免把 arguments 或控制面细节写入异常文本。
    """

    server_id = _required_text(_first(payload, "serverId", "server_id"), "serverId")
    internal_tool_name = _required_text(
        _first(payload, "internalToolName", "internal_tool_name", "toolCode", "tool_code"),
        "internalToolName",
    )
    arguments = _mapping(_first(payload, "arguments"), field_name="arguments", default={})
    control_facts = _mapping(_first(payload, "controlFacts", "control_facts"), field_name="controlFacts")
    fallback_context = _optional_mapping(_first(payload, "fallbackContext", "fallback_context"))
    return McpDurableWorkerRunRequest(
        server_id=server_id,
        internal_tool_name=internal_tool_name,
        arguments=arguments,
        control_facts=control_facts,
        fallback_context=fallback_context,
        execution_node_id=_optional_text(_first(payload, "executionNodeId", "execution_node_id"))
        or "mcp_durable_worker_api",
        post_to_java=_bool(_first(payload, "postToJava", "post_to_java"), default=False),
        session_id=_optional_text(_first(payload, "sessionId", "session_id")),
        trace_id=_optional_text(_first(payload, "traceId", "trace_id")),
    )


def tool_execution_feedback_to_payload(feedback: ToolExecutionFeedback) -> dict[str, Any]:
    """把 `ToolExecutionFeedback` 转成 HTTP 可序列化 payload。

    这里保留 `result`，是因为它已经经过 `McpToolFeedbackAdapter` 的 MCP 专属准入；后续进入二轮模型消息时，
    `ModelToolResultFeedbackBuilder` 仍会再次执行 workspace 与字段级过滤。也就是说，路由层不把自己当成
    唯一安全门，而是输出“可继续被统一 builder 治理”的中间合同。
    """

    return {
        "toolCallId": feedback.tool_call_id,
        "toolName": feedback.tool_name,
        "status": feedback.status.value,
        "summary": feedback.summary,
        "result": feedback.result,
        "errorCode": feedback.error_code,
        "errorMessage": feedback.error_message,
        "auditId": feedback.audit_id,
        "runId": feedback.run_id,
        "outputRef": feedback.output_ref,
        "outputWorkspaceKey": feedback.output_workspace_key,
        "outputContextPolicy": feedback.output_context_policy,
        "modelContextIncludePaths": feedback.model_context_include_paths,
        "modelContextExcludePaths": feedback.model_context_exclude_paths,
        "sensitiveResultPaths": feedback.sensitive_result_paths,
        "modelContextMaxStringLength": feedback.model_context_max_string_length,
        "modelContextMaxListItems": feedback.model_context_max_list_items,
        "modelContextMaxDepth": feedback.model_context_max_depth,
    }


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """按多个兼容字段名读取第一个存在的值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _mapping(value: Any, *, field_name: str, default: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """读取对象字段，并拒绝数组/字符串等不安全形态。"""

    if value is None and default is not None:
        return dict(default)
    if not isinstance(value, Mapping):
        raise ValueError(f"{field_name} 必须是 JSON object。")
    return dict(value)


def _optional_mapping(value: Any) -> dict[str, Any] | None:
    """读取可选对象字段。"""

    if value is None:
        return None
    if not isinstance(value, Mapping):
        raise ValueError("fallbackContext 必须是 JSON object。")
    return dict(value)


def _required_text(value: Any, field_name: str) -> str:
    """读取必填非空字符串。"""

    text = _optional_text(value)
    if not text:
        raise ValueError(f"{field_name} 不能为空。")
    return text


def _optional_text(value: Any) -> str | None:
    """读取可选非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _bool(value: Any, *, default: bool) -> bool:
    """兼容 Java/Python/JSON 的布尔字段解析。"""

    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


__all__ = [
    "MCP_DURABLE_WORKER_API_SCHEMA_VERSION",
    "mcp_worker_request_from_payload",
    "register_mcp_durable_worker_routes",
    "tool_execution_feedback_to_payload",
]
