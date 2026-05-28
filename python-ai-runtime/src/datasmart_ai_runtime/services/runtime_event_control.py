"""Agent Runtime 实时事件控制消息处理器。

3.42 已经实现了 `RuntimeEventSessionManager`，它负责维护订阅会话状态；但真实网关接入时，前端发来
的是 JSON 控制消息，例如：

- `{"type": "subscribe", "subscription": {...}}`
- `{"type": "ack", "subscriptionId": "...", "lastSequence": 12}`
- `{"type": "heartbeat", "subscriptionId": "...", "lastSequence": 12}`
- `{"type": "reconnect", "subscriptionId": "...", "afterSequence": 12}`
- `{"type": "unsubscribe", "subscriptionId": "...", "reason": "user_left_page"}`

如果每个 WebSocket handler 或 HTTP 路由都自己写 if/else，就会很快出现协议漂移：某个入口叫
`lastSequence`，另一个入口叫 `sequence`；某个入口允许 closed 后 ack，另一个入口不允许。这个
处理器的职责就是把“控制消息协议”集中映射到会话状态机，让后续 FastAPI、Java Gateway、测试工具
和前端 SDK 都围绕同一套状态流转。
"""

from __future__ import annotations

from dataclasses import asdict, replace
from typing import Any

from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventControlMessage,
    RuntimeEventControlMessageType,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_event_authorization import (
    RuntimeEventAccessContext,
    RuntimeEventAuthorizationDecision,
    RuntimeEventSubscriptionAuthorizer,
)
from datasmart_ai_runtime.services.runtime_event_session import (
    RuntimeEventSessionManager,
    RuntimeEventSessionSnapshot,
)


class RuntimeEventControlMessageError(ValueError):
    """控制消息解析或处理错误。

    这里保留独立异常，是为了后续 API 层可以把“消息格式错误”和“订阅状态错误”区分成不同错误码。
    例如消息格式错误可以返回 `EVENT_CONTROL_MESSAGE_INVALID`，订阅不存在则返回
    `EVENT_SUBSCRIPTION_NOT_FOUND`。
    """


class RuntimeEventControlHandler:
    """把实时事件控制消息分发给订阅会话状态机。

    控制处理器不关心消息来自 HTTP 还是 WebSocket，也不关心响应如何发送给前端。它只负责三件事：
    1. 校验控制消息是否携带了当前动作必需的字段；
    2. 调用 `RuntimeEventSessionManager` 完成状态流转；
    3. 把会话快照转换成稳定响应结构，方便 API 层或 WebSocket handler 直接返回。
    """

    def __init__(
        self,
        session_manager: RuntimeEventSessionManager,
        authorizer: RuntimeEventSubscriptionAuthorizer | None = None,
    ) -> None:
        """注入会话管理器。

        这样做让控制处理器可以在单元测试中使用内存 store，也可以在生产环境中使用 Redis/Kafka
        支撑的事件存储和集群会话管理器。
        """

        self._session_manager = session_manager
        self._authorizer = authorizer or RuntimeEventSubscriptionAuthorizer()

    def handle(
        self,
        message: RuntimeEventControlMessage,
        access_context: RuntimeEventAccessContext | None = None,
    ) -> dict[str, Any]:
        """处理一条控制消息并返回协议响应。

        响应固定包含：
        - `messageType`：本次处理的控制消息类型；
        - `subscription`：订阅会话快照；
        - `accepted`：当前消息已被状态机接受。

        如果订阅建立或重连产生 replay envelope，该 envelope 会包含在 `subscription.replayEnvelope` 中。
        """

        snapshot = self._dispatch(message, access_context=access_context)
        return {
            "accepted": True,
            "messageType": message.message_type,
            "subscription": self._snapshot_to_response(snapshot),
        }

    def _dispatch(
        self,
        message: RuntimeEventControlMessage,
        access_context: RuntimeEventAccessContext | None = None,
    ) -> RuntimeEventSessionSnapshot:
        """按控制消息类型分发到会话状态机。"""

        if message.message_type == RuntimeEventControlMessageType.SUBSCRIBE:
            if message.request is None:
                raise RuntimeEventControlMessageError("subscribe 控制消息必须携带 subscription/request。")
            request = self._merge_access_context(message.request, access_context)
            decision = self._authorize(request, access_context)
            if not decision.allowed:
                raise RuntimeEventControlMessageError(f"订阅未授权：{decision.reason}")
            return self._session_manager.subscribe(request)

        if message.message_type == RuntimeEventControlMessageType.ACK:
            subscription_id = self._require_subscription_id(message)
            if message.last_sequence is None:
                raise RuntimeEventControlMessageError("ack 控制消息必须携带 lastSequence。")
            return self._session_manager.acknowledge(subscription_id, message.last_sequence)

        if message.message_type == RuntimeEventControlMessageType.HEARTBEAT:
            subscription_id = self._require_subscription_id(message)
            return self._session_manager.heartbeat(subscription_id, message.last_sequence)

        if message.message_type == RuntimeEventControlMessageType.RECONNECT:
            subscription_id = self._require_subscription_id(message)
            after_sequence = message.after_sequence
            if after_sequence is None and message.request is not None:
                after_sequence = message.request.after_sequence
            return self._session_manager.reconnect(subscription_id, after_sequence=after_sequence)

        if message.message_type == RuntimeEventControlMessageType.UNSUBSCRIBE:
            subscription_id = self._require_subscription_id(message)
            return self._session_manager.unsubscribe(
                subscription_id,
                reason=message.reason or "client_unsubscribe",
            )

        raise RuntimeEventControlMessageError(f"不支持的事件控制消息类型：{message.message_type}")

    @staticmethod
    def _merge_access_context(
        request: RuntimeEventSubscriptionRequest,
        access_context: RuntimeEventAccessContext | None,
    ) -> RuntimeEventSubscriptionRequest:
        """把已认证访问上下文补入订阅请求。

        真实 WebSocket 场景里，前端订阅消息通常只会传 sessionId/runId/afterSequence，不应该让前端
        自己声明“我是哪个角色”。角色、租户、项目、操作者应来自 gateway/JWT/服务端认证上下文。
        因此这里在 subscribe 入会话之前做一次合并：
        - request 已显式传的字段优先保留，方便测试和内部工具；
        - request 缺失的 tenant/project/actor/roles 从 access_context 补齐；
        - 后续 replay/live push 就能基于会话 request 中的 roles 做同一套脱敏策略。
        """

        if access_context is None:
            return request
        return replace(
            request,
            tenant_id=request.tenant_id or access_context.tenant_id,
            project_id=request.project_id or access_context.project_id,
            actor_id=request.actor_id or access_context.actor_id,
            roles=request.roles or access_context.roles,
        )

    def _authorize(
        self,
        request: RuntimeEventSubscriptionRequest,
        access_context: RuntimeEventAccessContext | None,
    ) -> RuntimeEventAuthorizationDecision:
        """对订阅请求执行授权判断。"""

        context = access_context or RuntimeEventAccessContext()
        return self._authorizer.authorize(request, context)

    @staticmethod
    def _require_subscription_id(message: RuntimeEventControlMessage) -> str:
        """读取 subscriptionId，没有时抛出格式错误。"""

        if not message.subscription_id:
            raise RuntimeEventControlMessageError(f"{message.message_type.value} 控制消息必须携带 subscriptionId。")
        return message.subscription_id

    @staticmethod
    def _snapshot_to_response(snapshot: RuntimeEventSessionSnapshot) -> dict[str, Any]:
        """把订阅会话快照转换成 API/Socket 响应结构。

        使用 camelCase 是为了贴近前端和网关协议；内部 Python dataclass 仍保持 snake_case，便于服务层
        阅读与测试。
        """

        replay_envelope = asdict(snapshot.replay_envelope) if snapshot.replay_envelope is not None else None
        return {
            "subscriptionId": snapshot.plan.subscription_id,
            "state": snapshot.state,
            "channel": snapshot.plan.channel,
            "ackMode": snapshot.plan.ack_mode,
            "clientId": snapshot.plan.request.client_id,
            "tenantId": snapshot.plan.request.tenant_id,
            "projectId": snapshot.plan.request.project_id,
            "actorId": snapshot.plan.request.actor_id,
            "roles": snapshot.plan.request.roles,
            "sessionId": snapshot.plan.request.session_id,
            "runId": snapshot.plan.request.run_id,
            "requestId": snapshot.plan.request.request_id,
            "afterSequence": snapshot.plan.request.after_sequence,
            "lastAckSequence": snapshot.last_ack_sequence,
            "connectedAt": snapshot.connected_at.isoformat(),
            "lastHeartbeatAt": snapshot.last_heartbeat_at.isoformat(),
            "updatedAt": snapshot.updated_at.isoformat(),
            "closeReason": snapshot.close_reason,
            "attributes": dict(snapshot.plan.attributes),
            "replayEnvelope": replay_envelope,
        }


def control_message_from_payload(payload: dict[str, Any]) -> RuntimeEventControlMessage:
    """把 API/WebSocket JSON payload 转换为控制消息领域对象。

    兼容字段命名：
    - 消息类型：`type`、`messageType`、`message_type`；
    - 订阅 ID：`subscriptionId`、`subscription_id`；
    - 最后确认序号：`lastSequence`、`last_sequence`；
    - 回放起点：`afterSequence`、`after_sequence`；
    - 订阅请求：`subscription`、`request`。

    这类兼容逻辑放在边界层，领域对象内部保持统一字段，避免核心服务被前端命名细节污染。
    """

    raw_type = payload.get("type") or payload.get("messageType") or payload.get("message_type")
    if raw_type is None:
        raise RuntimeEventControlMessageError("事件控制消息缺少 type/messageType。")
    request_payload = payload.get("subscription") or payload.get("request")
    return RuntimeEventControlMessage(
        message_type=RuntimeEventControlMessageType(raw_type),
        subscription_id=payload.get("subscriptionId") or payload.get("subscription_id"),
        request=_subscription_request_from_payload(request_payload) if request_payload else None,
        last_sequence=_optional_int(payload.get("lastSequence", payload.get("last_sequence"))),
        after_sequence=_optional_int(payload.get("afterSequence", payload.get("after_sequence"))),
        reason=payload.get("reason"),
        attributes=dict(payload.get("attributes", {})),
    )


def _subscription_request_from_payload(payload: dict[str, Any]) -> RuntimeEventSubscriptionRequest:
    """解析控制消息里的订阅请求。"""

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


def _optional_int(value: Any) -> int | None:
    """把可选数字字段转换为 int，空值保持 None。"""

    if value is None:
        return None
    return int(value)


def _as_tuple(value: Any) -> tuple[Any, ...]:
    """把单值或列表字段归一化为 tuple。"""

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
