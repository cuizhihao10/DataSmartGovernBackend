"""工具动作 checkpoint 相关路由注册。

把 checkpoint 查询/恢复预检路由从主 `routes.py` 拆出来，是为了保持 Agent API 入口文件可读：
- `routes.py` 负责装配总入口和核心 plan/event/websocket 路由；
- 本模块只负责工具动作 checkpoint 的两个只读/预览路由；
- 后续如果 checkpoint 扩展到 Redis/MySQL、管理员审计、Prometheus 指标或权限中间件，也可以继续在本模块内演进。
"""

from __future__ import annotations

import logging
from typing import Any

from datasmart_ai_runtime.api.agent.runtime_event_delivery import (
    publish_single_runtime_event,
    runtime_event_summary,
)
from datasmart_ai_runtime.api.agent.tool_action_checkpoint_security import (
    attach_checkpoint_security_boundary,
    checkpoint_gateway_signature_error_detail,
    prepare_checkpoint_payload_for_route_security,
)
from datasmart_ai_runtime.api.agent.tool_action_execution_checkpoint import (
    build_tool_action_execution_checkpoint_query_response,
    build_tool_action_execution_checkpoint_resume_preview_response,
)
from datasmart_ai_runtime.api.gateway.signature import GatewaySignatureVerificationError
from datasmart_ai_runtime.services.tools import (
    ToolActionExecutionCheckpointStore,
    build_tool_action_checkpoint_runtime_event,
)


LOGGER = logging.getLogger(__name__)


def register_tool_action_checkpoint_routes(
    app: Any,
    *,
    request_type: type[Any] | None = None,
    checkpoint_store: ToolActionExecutionCheckpointStore | None = None,
    resume_fact_provider: Any | None = None,
    event_store: Any | None = None,
    live_push_hub: Any | None = None,
    event_publisher: Any | None = None,
    metrics_recorder: Any | None = None,
    gateway_signature_required: bool = False,
    gateway_signature_error_factory: Any | None = None,
    gateway_signature_nonce_store: Any | None = None,
    gateway_signature_security_stats: Any | None = None,
) -> None:
    """注册工具动作 checkpoint 查询与恢复预检路由。

    路由设计：
    - `/agent/tool-actions/checkpoints/query`：按 checkpointId 或 threadId 读取低敏 checkpoint；
    - `/agent/tool-actions/checkpoints/resume-preview`：检查审批/澄清/预算/outbox 等恢复事实是否齐备。

    两个路由都不是生产执行入口，不会执行工具、不写 outbox、不派发 worker。

    观测设计：
    - route 层会把响应压缩成低敏 runtime event，并投递到 replay/live/publisher 旁路；
    - route 层也可以把同一事件交给低基数指标器，供 `/agent/metrics` 输出 Prometheus 文本；
    - 事件和指标都不保存原始 checkpointId/threadId、工具参数、SQL、prompt 或 payloadReference。

    安全设计：
    - 通过 `request_type` 接入 FastAPI Request 后，本路由可复用 gateway HMAC 签名校验、nonce 防重放和安全统计；
    - `gateway_signature_required=false` 时保留本地学习/旧测试兼容，但只要请求声称来自 gateway 且配置了密钥，错误签名仍会被拒绝；
    - `gateway_signature_required=true` 时进入 fail-closed：缺少 gateway 来源、签名字段、密钥、timestamp 或 nonce 都会拒绝；
    - 验签通过后，tenantId/actorId 会从 gateway Header 覆盖请求体，避免调用方在 body 中伪造身份。
    """

    def _handle_query_tool_action_checkpoints(
        payload: dict[str, Any],
        http_request: Any | None,
    ) -> dict[str, Any]:
        """查询工具动作执行前图 checkpoint。

        调用方必须提供 checkpointId 或 threadId；当前不允许全局扫描。
        这能避免 checkpoint 查询接口在尚未接入完整认证前被误用成跨租户枚举接口。
        """

        secured_payload, security_boundary = _prepare_secured_checkpoint_payload(
            payload,
            http_request=http_request,
            gateway_signature_required=gateway_signature_required,
            gateway_signature_error_factory=gateway_signature_error_factory,
            gateway_signature_nonce_store=gateway_signature_nonce_store,
            gateway_signature_security_stats=gateway_signature_security_stats,
        )
        response = build_tool_action_execution_checkpoint_query_response(
            secured_payload,
            checkpoint_store=checkpoint_store,
        )
        attach_checkpoint_security_boundary(response, security_boundary)
        _attach_checkpoint_runtime_observability(
            response,
            operation="query",
            payload=secured_payload,
            event_store=event_store,
            live_push_hub=live_push_hub,
            event_publisher=event_publisher,
            metrics_recorder=metrics_recorder,
        )
        return response

    def _handle_preview_tool_action_checkpoint_resume(
        payload: dict[str, Any],
        http_request: Any | None,
    ) -> dict[str, Any]:
        """预检 checkpoint 是否具备恢复执行图的事实条件。

        该接口只判断事实是否齐备，例如 approvalConfirmationId、clarificationFactId、
        payloadReference、policyVersion、outboxConfirmationId 等，不消费事实值、不执行任何副作用。
        """

        secured_payload, security_boundary = _prepare_secured_checkpoint_payload(
            payload,
            http_request=http_request,
            gateway_signature_required=gateway_signature_required,
            gateway_signature_error_factory=gateway_signature_error_factory,
            gateway_signature_nonce_store=gateway_signature_nonce_store,
            gateway_signature_security_stats=gateway_signature_security_stats,
        )
        response = build_tool_action_execution_checkpoint_resume_preview_response(
            secured_payload,
            checkpoint_store=checkpoint_store,
            resume_fact_provider=resume_fact_provider,
        )
        attach_checkpoint_security_boundary(response, security_boundary)
        _attach_checkpoint_runtime_observability(
            response,
            operation="resume_preview",
            payload=secured_payload,
            event_store=event_store,
            live_push_hub=live_push_hub,
            event_publisher=event_publisher,
            metrics_recorder=metrics_recorder,
        )
        return response

    if request_type is None:
        # 单元测试和纯 Python 离线调用没有 FastAPI Request 对象，继续注册单参数 handler。
        # 真实 HTTP 服务会从 `register_agent_runtime_routes(..., request_type=Request)` 传入 Request 类型，
        # 进入下面的双参数 handler，从而读取 gateway Header 并执行 HMAC 校验。
        @app.post("/agent/tool-actions/checkpoints/query")
        def query_tool_action_checkpoints(payload: dict[str, Any]) -> dict[str, Any]:
            return _handle_query_tool_action_checkpoints(payload, http_request=None)

        @app.post("/agent/tool-actions/checkpoints/resume-preview")
        def preview_tool_action_checkpoint_resume(payload: dict[str, Any]) -> dict[str, Any]:
            return _handle_preview_tool_action_checkpoint_resume(payload, http_request=None)

        return

    @app.post("/agent/tool-actions/checkpoints/query")
    def query_tool_action_checkpoints(payload: dict[str, Any], http_request: request_type) -> dict[str, Any]:
        return _handle_query_tool_action_checkpoints(payload, http_request=http_request)

    @app.post("/agent/tool-actions/checkpoints/resume-preview")
    def preview_tool_action_checkpoint_resume(payload: dict[str, Any], http_request: request_type) -> dict[str, Any]:
        return _handle_preview_tool_action_checkpoint_resume(payload, http_request=http_request)


def _prepare_secured_checkpoint_payload(
    payload: dict[str, Any],
    *,
    http_request: Any | None,
    gateway_signature_required: bool,
    gateway_signature_error_factory: Any | None,
    gateway_signature_nonce_store: Any | None,
    gateway_signature_security_stats: Any | None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """执行 checkpoint 路由的安全前置处理。

    该函数把错误映射、日志、安全统计统一收口，避免 query 与 resume-preview 两个 handler 复制同一段
    try/except。它只记录低敏字段，不写入完整 Header、签名、nonce、payload 或 checkpoint 正文。
    """

    try:
        return prepare_checkpoint_payload_for_route_security(
            payload,
            http_request,
            gateway_signature_required=gateway_signature_required,
            nonce_store=gateway_signature_nonce_store,
        )
    except GatewaySignatureVerificationError as exc:
        error_detail = checkpoint_gateway_signature_error_detail(http_request, exc)
        LOGGER.warning(
            "Checkpoint 路由 gateway 签名校验失败，code=%s, reason=%s, traceId=%s, sourceService=%s, path=%s",
            error_detail["code"],
            error_detail["reason"],
            error_detail["traceId"],
            error_detail["sourceService"],
            error_detail["path"],
        )
        if gateway_signature_security_stats is not None:
            gateway_signature_security_stats.record_failure(error_detail)
        if gateway_signature_error_factory is not None:
            raise gateway_signature_error_factory(error_detail) from exc
        raise


def _attach_checkpoint_runtime_observability(
    response: dict[str, Any],
    *,
    operation: str,
    payload: dict[str, Any],
    event_store: Any | None,
    live_push_hub: Any | None,
    event_publisher: Any | None,
    metrics_recorder: Any | None,
) -> None:
    """为 checkpoint API 响应附加 runtime event、事件投递结果和指标投递结果。

    该函数是 route 层的“观测胶水”：
    - API helper 仍只负责构造纯业务预览响应，便于单元测试和离线复用；
    - route 层拥有 event_store/live_push_hub/event_publisher/metrics_recorder 等运行时依赖，因此在这里完成旁路投递；
    - 所有旁路都 fail-open，观测失败只进入低敏 delivery 摘要，不影响 checkpoint 查询或恢复预检主结果。
    """

    event = build_tool_action_checkpoint_runtime_event(response, operation=operation, request_payload=payload)
    response["runtimeEvent"] = runtime_event_summary(event)
    response["runtimeEventDelivery"] = publish_single_runtime_event(
        event,
        event_store=event_store,
        live_push_hub=live_push_hub,
        event_publisher=event_publisher,
    )
    response["runtimeMetricDelivery"] = _record_checkpoint_metric(event, metrics_recorder=metrics_recorder)


def _record_checkpoint_metric(event: Any, *, metrics_recorder: Any | None) -> dict[str, Any]:
    """把 checkpoint runtime event 写入低基数指标旁路。"""

    if metrics_recorder is None:
        return {
            "enabled": False,
            "recorded": False,
            "errors": (),
        }
    try:
        recorded = bool(metrics_recorder.record_runtime_event(event))
        return {
            "enabled": True,
            "recorded": recorded,
            "errors": (),
        }
    except Exception as exc:  # pragma: no cover - 依赖真实指标器故障时触发
        return {
            "enabled": True,
            "recorded": False,
            "errors": (
                {
                    "component": "checkpoint_metrics",
                    "errorType": exc.__class__.__name__,
                    "message": str(exc)[:200],
                },
            ),
        }
