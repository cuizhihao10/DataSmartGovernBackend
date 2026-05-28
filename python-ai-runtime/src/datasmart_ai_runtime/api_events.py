"""Agent 事件 API 边界 helper。

`api.py` 负责 FastAPI 应用装配、工具/Skill 注册表加载和默认编排器创建。事件 replay、订阅控制、
payload 解析这些逻辑如果继续放在同一个文件里，会让 API 入口膨胀成难维护的大文件。本模块把
事件协议适配拆出来，保持职责边界清晰，同时继续让测试从 `datasmart_ai_runtime.api` 兼容导入。
"""

from __future__ import annotations

from dataclasses import asdict, replace
from typing import Any

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.runtime_event_authorization import RuntimeEventAccessContext
from datasmart_ai_runtime.services.runtime_event_control import (
    RuntimeEventControlMessageError,
    RuntimeEventControlHandler,
    control_message_from_payload,
)
from datasmart_ai_runtime.services.runtime_event_session import RuntimeEventSessionManager
from datasmart_ai_runtime.services.runtime_event_store import RuntimeEventStore
from datasmart_ai_runtime.services.runtime_event_replay_source import (
    RuntimeEventReplayCoordinator,
    RuntimeEventReplaySource,
)
from datasmart_ai_runtime.services.runtime_event_websocket import (
    build_websocket_frames_from_control_response,
    frames_to_payloads,
)
from datasmart_ai_runtime.services.runtime_event_transport import RuntimeEventTransportBuilder


def build_event_replay_response(
    subscription_request: RuntimeEventSubscriptionRequest,
    events: tuple[AgentRuntimeEvent, ...] = (),
    event_transport_builder: RuntimeEventTransportBuilder | None = None,
    event_store: RuntimeEventStore | None = None,
    external_replay_sources: tuple[RuntimeEventReplaySource, ...] = (),
) -> dict[str, Any]:
    """构建同步 replay 查询响应。

    该 helper 对应未来 `/agent/events/replay` 一类接口：客户端告诉服务端自己想订阅哪个 session/run、
    已经处理到哪个 sequence，服务端返回匹配的 replay envelope。当前事件仍由调用方传入，是因为
    Python Runtime 尚未接入持久事件存储；后续生产实现可改为从 Redis、MySQL、Kafka compacted
    topic 或 Java 审计表读取事件集合。
    """

    resolved_events = event_store.replay(subscription_request) if event_store is not None else events
    replay_collection = RuntimeEventReplayCoordinator(external_replay_sources).collect(
        tuple(resolved_events),
        subscription_request,
    )
    transport_builder = event_transport_builder or RuntimeEventTransportBuilder()
    envelope = transport_builder.build_subscription_replay(replay_collection.events, subscription_request)
    if replay_collection.external_errors:
        envelope = replace(
            envelope,
            attributes={
                **dict(envelope.attributes),
                "externalReplayErrors": replay_collection.external_errors,
            },
        )
    return {
        "eventEnvelope": asdict(envelope),
    }


def build_event_control_response(
    payload: dict[str, Any],
    session_manager: RuntimeEventSessionManager,
) -> dict[str, Any]:
    """处理实时事件控制消息。

    该 helper 对应未来 WebSocket 收到一条 JSON 控制消息后的核心处理逻辑。它被拆出 API 文件，是为了
    让 HTTP 管理入口、WebSocket handler、命令行调试工具和单元测试共用同一套控制协议。
    """

    handler = RuntimeEventControlHandler(session_manager)
    message = control_message_from_payload(payload)
    access_context = _access_context_from_payload(payload)
    try:
        return handler.handle(message, access_context=access_context)
    except RuntimeEventControlMessageError as exc:
        return {
            "accepted": False,
            "messageType": message.message_type,
            "error": {
                "code": _control_error_code(str(exc)),
                "message": str(exc),
            },
        }


def build_event_websocket_payloads(
    payload: dict[str, Any],
    session_manager: RuntimeEventSessionManager,
) -> tuple[dict[str, Any], ...]:
    """把一条控制消息转换成适合 WebSocket 下发的 payload 序列。"""

    control_response = build_event_control_response(payload, session_manager)
    frames = build_websocket_frames_from_control_response(control_response)
    return frames_to_payloads(frames)


def subscription_request_from_payload(payload: dict[str, Any]) -> RuntimeEventSubscriptionRequest:
    """把 API payload 转换为订阅请求领域对象。

    API 层同时兼容 camelCase 与 snake_case，是为了降低前端和 Python 测试之间的字段命名摩擦。
    领域对象内部仍保持 Python 风格 snake_case，便于服务层代码阅读。
    """

    event_types = tuple(
        AgentRuntimeEventType(item)
        for item in _as_tuple(payload.get("eventTypes", payload.get("event_types", ())))
    )
    return RuntimeEventSubscriptionRequest(
        client_id=str(payload.get("clientId") or payload.get("client_id") or "anonymous-client"),
        tenant_id=payload.get("tenantId") or payload.get("tenant_id"),
        project_id=payload.get("projectId") or payload.get("project_id"),
        actor_id=payload.get("actorId") or payload.get("actor_id"),
        roles=_as_tuple(payload.get("roles", payload.get("role", ()))),
        session_id=payload.get("sessionId") or payload.get("session_id"),
        run_id=payload.get("runId") or payload.get("run_id"),
        request_id=payload.get("requestId") or payload.get("request_id"),
        after_sequence=int(payload.get("afterSequence", payload.get("after_sequence", 0))),
        event_types=event_types,
        include_snapshot=bool(payload.get("includeSnapshot", payload.get("include_snapshot", True))),
    )


def runtime_event_from_payload(payload: dict[str, Any]) -> AgentRuntimeEvent:
    """把 API payload 转换为运行时事件领域对象。

    这里没有解析 `createdAt`，因为 replay 查询只依赖事件类型、关联 ID、sequence 和 attributes。
    后续如果要做审计时间线回放，可以再补充 ISO datetime 解析。
    """

    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType(payload.get("event_type") or payload.get("eventType")),
        stage=str(payload.get("stage", "")),
        message=str(payload.get("message", "")),
        severity=AgentRuntimeEventSeverity(payload.get("severity", AgentRuntimeEventSeverity.INFO)),
        tenant_id=payload.get("tenant_id") or payload.get("tenantId"),
        project_id=payload.get("project_id") or payload.get("projectId"),
        actor_id=payload.get("actor_id") or payload.get("actorId"),
        request_id=payload.get("request_id") or payload.get("requestId"),
        run_id=payload.get("run_id") or payload.get("runId"),
        session_id=payload.get("session_id") or payload.get("sessionId"),
        sequence=payload.get("sequence"),
        attributes=dict(payload.get("attributes", {})),
    )


def _access_context_from_payload(payload: dict[str, Any]) -> RuntimeEventAccessContext:
    """从 API payload 解析实时事件访问上下文。

    控制入口允许调用方以 `accessContext` 或 `authorization` 的形式传入已认证后的上下文信息。这里不
    负责做登录认证，只负责把认证结果转换为授权策略可用的领域对象。
    """

    raw_context = payload.get("accessContext") or payload.get("access_context") or payload.get("authorization") or {}
    roles = _as_tuple(raw_context.get("roles", raw_context.get("role", ())))
    return RuntimeEventAccessContext(
        tenant_id=raw_context.get("tenantId") or raw_context.get("tenant_id"),
        project_id=raw_context.get("projectId") or raw_context.get("project_id"),
        actor_id=raw_context.get("actorId") or raw_context.get("actor_id"),
        roles=roles,
        allowed_session_ids=tuple(raw_context.get("allowedSessionIds", raw_context.get("allowed_session_ids", ()))),
        allowed_run_ids=tuple(raw_context.get("allowedRunIds", raw_context.get("allowed_run_ids", ()))),
        allowed_request_ids=tuple(raw_context.get("allowedRequestIds", raw_context.get("allowed_request_ids", ()))),
        is_platform_admin=bool(raw_context.get("isPlatformAdmin", raw_context.get("is_platform_admin", False))),
        is_tenant_admin=bool(raw_context.get("isTenantAdmin", raw_context.get("is_tenant_admin", False))),
        is_auditor=bool(raw_context.get("isAuditor", raw_context.get("is_auditor", False))),
        attributes=dict(raw_context.get("attributes", {})),
    )


def _control_error_code(message: str) -> str:
    """把控制错误信息归一化成较稳定的错误码。"""

    if "未授权" in message:
        return "EVENT_CONTROL_NOT_AUTHORIZED"
    if "不存在" in message:
        return "EVENT_CONTROL_NOT_FOUND"
    if "已关闭" in message:
        return "EVENT_CONTROL_CLOSED"
    if "超时" in message:
        return "EVENT_CONTROL_STALE"
    if "缺少" in message:
        return "EVENT_CONTROL_INVALID"
    return "EVENT_CONTROL_FAILED"


def _as_tuple(value: Any) -> tuple[Any, ...]:
    """把 API payload 中的数组/单值字段归一化为 tuple。"""

    if value is None:
        return ()
    if isinstance(value, tuple):
        return value
    if isinstance(value, list):
        return tuple(value)
    if isinstance(value, set):
        return tuple(value)
    if isinstance(value, str):
        return (value,)
    return (value,)
