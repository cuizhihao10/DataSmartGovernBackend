"""工具动作 checkpoint 相关路由注册。

把 checkpoint 查询/恢复预检路由从主 `routes.py` 拆出来，是为了保持 Agent API 入口文件可读：
- `routes.py` 负责装配总入口和核心 plan/event/websocket 路由；
- 本模块只负责工具动作 checkpoint 的两个只读/预览路由；
- 后续如果 checkpoint 扩展到 Redis/MySQL、管理员审计、Prometheus 指标或权限中间件，也可以继续在本模块内演进。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.api.agent.runtime_event_delivery import (
    publish_single_runtime_event,
    runtime_event_summary,
)
from datasmart_ai_runtime.api.agent.tool_action_execution_checkpoint import (
    build_tool_action_execution_checkpoint_query_response,
    build_tool_action_execution_checkpoint_resume_preview_response,
)
from datasmart_ai_runtime.services.tools import (
    ToolActionExecutionCheckpointStore,
    build_tool_action_checkpoint_runtime_event,
)


def register_tool_action_checkpoint_routes(
    app: Any,
    *,
    checkpoint_store: ToolActionExecutionCheckpointStore | None = None,
    resume_fact_provider: Any | None = None,
    event_store: Any | None = None,
    live_push_hub: Any | None = None,
    event_publisher: Any | None = None,
    metrics_recorder: Any | None = None,
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
    """

    @app.post("/agent/tool-actions/checkpoints/query")
    def query_tool_action_checkpoints(payload: dict[str, Any]) -> dict[str, Any]:
        """查询工具动作执行前图 checkpoint。

        调用方必须提供 checkpointId 或 threadId；当前不允许全局扫描。
        这能避免 checkpoint 查询接口在尚未接入完整认证前被误用成跨租户枚举接口。
        """

        response = build_tool_action_execution_checkpoint_query_response(
            payload,
            checkpoint_store=checkpoint_store,
        )
        _attach_checkpoint_runtime_observability(
            response,
            operation="query",
            payload=payload,
            event_store=event_store,
            live_push_hub=live_push_hub,
            event_publisher=event_publisher,
            metrics_recorder=metrics_recorder,
        )
        return response

    @app.post("/agent/tool-actions/checkpoints/resume-preview")
    def preview_tool_action_checkpoint_resume(payload: dict[str, Any]) -> dict[str, Any]:
        """预检 checkpoint 是否具备恢复执行图的事实条件。

        该接口只判断事实是否齐备，例如 approvalConfirmationId、clarificationFactId、
        payloadReference、policyVersion、outboxConfirmationId 等，不消费事实值、不执行任何副作用。
        """

        response = build_tool_action_execution_checkpoint_resume_preview_response(
            payload,
            checkpoint_store=checkpoint_store,
            resume_fact_provider=resume_fact_provider,
        )
        _attach_checkpoint_runtime_observability(
            response,
            operation="resume_preview",
            payload=payload,
            event_store=event_store,
            live_push_hub=live_push_hub,
            event_publisher=event_publisher,
            metrics_recorder=metrics_recorder,
        )
        return response


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
