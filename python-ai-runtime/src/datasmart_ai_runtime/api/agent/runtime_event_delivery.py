"""Agent API 路由复用的 Runtime Event 投递工具。

本模块把“把一条低敏 Runtime Event 写入 replay store、live push、异步 publisher”这件事从具体路由中拆出来。

为什么需要单独模块：
- MCP tools/call intake、checkpoint query、resume-preview 等入口都需要把“只读预览动作曾发生”记录成事件；
- 如果每个 route 都复制一份 `event_store/live_push_hub/event_publisher` 投递代码，旁路失败策略和响应字段很容易漂移；
- 统一 helper 后，新增协议入口时只需要关注“如何构建低敏事件”，而不用重复理解三条事件旁路的细节。

设计边界：
- 投递失败采用 fail-open：事件旁路用于审计和观测，不应把 preview-only 主响应打成 500；
- 错误摘要只包含组件名、异常类型和短消息，不返回堆栈、连接串、内部 endpoint、token 或生产配置；
- `runtime_event_summary(...)` 只返回事件低敏字段和已经白名单压缩过的 attributes。
"""

from __future__ import annotations

from typing import Any


def publish_single_runtime_event(
    event: Any,
    *,
    event_store: Any | None,
    live_push_hub: Any | None,
    event_publisher: Any | None,
) -> dict[str, Any]:
    """把单条 runtime event 写入 replay/live/publisher 三条旁路。

    参数说明：
    - `event_store`：用于 `/agent/events/replay` 的事件事实存储；
    - `live_push_hub`：用于 WebSocket 订阅者的实时推送缓冲；
    - `event_publisher`：用于 Kafka 或未来事件总线的异步发布。

    返回值是低敏投递摘要，适合直接放入 preview 响应，方便本地联调和 Java 控制面确认事件是否被旁路接收。
    """

    events = (event,)
    errors: list[dict[str, str]] = []
    stored = False
    live_pushed_count = 0
    published_count = 0
    if event_store is not None:
        try:
            event_store.append_many(events)
            stored = True
        except Exception as exc:  # pragma: no cover - 依赖真实外部存储故障时触发
            errors.append(runtime_event_delivery_error("event_store", exc))
    if live_push_hub is not None:
        try:
            live_pushed_count = int(live_push_hub.publish(events))
        except Exception as exc:  # pragma: no cover - 依赖真实 WebSocket outbox 故障时触发
            errors.append(runtime_event_delivery_error("live_push_hub", exc))
    if event_publisher is not None:
        try:
            published_count = int(event_publisher.publish(events))
        except Exception as exc:  # pragma: no cover - 依赖真实 Kafka producer 故障时触发
            errors.append(runtime_event_delivery_error("event_publisher", exc))
    return {
        "eventStoreEnabled": event_store is not None,
        "livePushEnabled": live_push_hub is not None,
        "publisherEnabled": event_publisher is not None,
        "stored": stored,
        "livePushedCount": live_pushed_count,
        "publishedCount": published_count,
        "errors": tuple(errors),
    }


def runtime_event_summary(event: Any) -> dict[str, Any]:
    """生成响应中可展示的 runtime event 低敏摘要。"""

    return {
        "eventType": event.event_type.value,
        "stage": event.stage,
        "message": event.message,
        "severity": event.severity.value,
        "tenantId": event.tenant_id,
        "projectId": event.project_id,
        "actorId": event.actor_id,
        "requestId": event.request_id,
        "runId": event.run_id,
        "sessionId": event.session_id,
        "sequence": event.sequence,
        "attributes": dict(event.attributes or {}),
        "createdAt": event.created_at.isoformat(),
    }


def runtime_event_delivery_error(component: str, exc: Exception) -> dict[str, str]:
    """把事件旁路异常收敛为低敏错误摘要。"""

    return {
        "component": component,
        "errorType": exc.__class__.__name__,
        "message": str(exc)[:200],
    }
