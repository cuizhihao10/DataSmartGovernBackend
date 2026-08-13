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

from collections.abc import Mapping
from dataclasses import asdict, replace
from typing import Any

from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventControlMessage,
    RuntimeEventControlMessageType,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_events.runtime_event_authorization import (
    RuntimeEventAccessContext,
    RuntimeEventAuthorizationDecision,
    RuntimeEventSubscriptionAuthorizer,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_session import (
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
        *,
        override_request_identity: bool = True,
    ) -> dict[str, Any]:
        """处理一条控制消息并返回协议响应。

        输入是已经完成 JSON 字段归一化的 `RuntimeEventControlMessage`，可选的 `access_context` 是网关
        已认证身份，不能由客户端正文替代。`lastSequence` 和 `afterSequence` 都属于前端 envelope 的全局
        展示序号；`sourceCursors` 则属于 Java 投影、Redis Stream、Kafka 等外部 source 各自的稳定坐标。
        两类游标不能互相代替。

        输出固定包含：
        - `messageType`：本次处理的控制消息类型；
        - `subscription`：订阅会话快照；
        - `accepted`：当前消息已被状态机接受。

        副作用由会话状态机承担：subscribe 创建会话，ack/heartbeat 可推进 envelope ack 或回写外部 ack，
        reconnect 生成 replay。reconnect 收到的 `sourceCursors` 只会用于下一次外部 replay 的起点，绝不
        在控制层直接调用外部 source，因此响应仍只包含既有的低敏订阅和 replay 摘要。

        如果订阅建立或重连产生 replay envelope，该 envelope 会包含在 `subscription.replayEnvelope` 中。
        """

        snapshot = self._dispatch(
            message,
            access_context=access_context,
            override_request_identity=override_request_identity,
        )
        return {
            "accepted": True,
            "messageType": message.message_type,
            "subscription": self._snapshot_to_response(snapshot),
        }

    def _dispatch(
        self,
        message: RuntimeEventControlMessage,
        access_context: RuntimeEventAccessContext | None = None,
        *,
        override_request_identity: bool = True,
    ) -> RuntimeEventSessionSnapshot:
        """按控制消息类型分发到会话状态机。

        这是 WebSocket/HTTP 边界和领域状态机之间唯一的控制分发点。它接收已解析的控制消息，返回更新后
        的不可变会话快照，并把身份合并和授权限制在 subscribe 分支。ack、heartbeat 和 reconnect 只能
        使用已有 subscriptionId，不能借控制帧替换租户、角色或订阅范围。

        对重连而言，`afterSequence` 决定 envelope 级回放从哪里继续；`sourceCursors` 与其并行传给会话
        管理器，决定每个已配置外部 source 的读取起点。两者同时保留，避免 REST/WebSocket 重放把 Java、
        Redis 或 Kafka 的源级游标误当成全局 sequence。
        """

        if message.message_type == RuntimeEventControlMessageType.SUBSCRIBE:
            if message.request is None:
                raise RuntimeEventControlMessageError("subscribe 控制消息必须携带 subscription/request。")
            request = self._merge_access_context(
                message.request,
                access_context,
                override_request_identity=override_request_identity,
            )
            decision = self._authorize(request, access_context)
            if not decision.allowed:
                raise RuntimeEventControlMessageError(f"订阅未授权：{decision.reason}")
            return self._session_manager.subscribe(request)

        if message.message_type == RuntimeEventControlMessageType.ACK:
            subscription_id = self._require_subscription_id(message)
            if message.last_sequence is None:
                raise RuntimeEventControlMessageError("ack 控制消息必须携带 lastSequence。")
            return self._session_manager.acknowledge(
                subscription_id,
                message.last_sequence,
                source_cursors=message.source_cursors,
            )

        if message.message_type == RuntimeEventControlMessageType.HEARTBEAT:
            subscription_id = self._require_subscription_id(message)
            return self._session_manager.heartbeat(
                subscription_id,
                message.last_sequence,
                source_cursors=message.source_cursors,
            )

        if message.message_type == RuntimeEventControlMessageType.RECONNECT:
            subscription_id = self._require_subscription_id(message)
            after_sequence = message.after_sequence
            if after_sequence is None and message.request is not None:
                after_sequence = message.request.after_sequence
            return self._session_manager.reconnect(
                subscription_id,
                after_sequence=after_sequence,
                source_cursors=message.source_cursors,
            )

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
        *,
        override_request_identity: bool = True,
    ) -> RuntimeEventSubscriptionRequest:
        """把已认证访问上下文补入订阅请求。

        真实 WebSocket 场景里，前端订阅消息通常只会传 sessionId/runId/afterSequence，不应该让前端
        自己声明“我是哪个角色”。角色、租户、项目、操作者应来自 gateway/JWT/服务端认证上下文。
        因此这里在 subscribe 入会话之前做一次合并：
        - tenant/project/actor/roles 始终由 access_context 覆盖，防止客户端正文伪造；
        - 只有事件定位字段（session/run/request）和回放参数继续来自订阅正文；
        - 后续 replay/live push 就能基于会话 request 中的 roles 做同一套脱敏策略。
        """

        if access_context is None:
            return request
        if override_request_identity:
            return replace(
                request,
                tenant_id=access_context.tenant_id,
                project_id=access_context.project_id,
                actor_id=access_context.actor_id,
                roles=access_context.roles,
            )
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


def control_message_from_payload(payload: Any) -> RuntimeEventControlMessage:
    """把 API/WebSocket JSON payload 转换为控制消息领域对象。

    兼容字段命名：
    - 消息类型：`type`、`messageType`、`message_type`；
    - 订阅 ID：`subscriptionId`、`subscription_id`；
    - 最后确认序号：`lastSequence`、`last_sequence`；
    - 回放起点：`afterSequence`、`after_sequence`；
    - 订阅请求：`subscription`、`request`。

    这类兼容逻辑放在边界层，领域对象内部保持统一字段，避免核心服务被前端命名细节污染。这里还要区分
    两套游标：`lastSequence`/`afterSequence` 是 envelope 级序号；`sourceCursors` 是外部 source 的源级
    续传位置。对于 reconnect，后者会采用严格的正整数解析，之后仍由会话层检查 source 是否已配置并
    以单调方式合并。ack/heartbeat 保留既有的宽进严出解析与外部 ack 语义。

    输入不是对象、控制类型未知或基础字段无法转换时，调用方会得到稳定的低敏格式错误，而不会回显原始
    WebSocket payload、游标内容或下游连接细节。本函数只构造领域对象，不创建会话，也不会发起 replay。
    """

    if not isinstance(payload, Mapping):
        raise RuntimeEventControlMessageError("事件控制消息必须是 JSON 对象。")

    raw_type = payload.get("type") or payload.get("messageType") or payload.get("message_type")
    if not isinstance(raw_type, str) or not raw_type.strip():
        raise RuntimeEventControlMessageError("事件控制消息缺少 type/messageType。")
    request_payload = payload.get("subscription") or payload.get("request")
    if request_payload is not None and not isinstance(request_payload, Mapping):
        raise RuntimeEventControlMessageError("事件控制消息中的订阅请求格式不正确。")
    attributes = payload.get("attributes", {})
    if not isinstance(attributes, Mapping):
        raise RuntimeEventControlMessageError("事件控制消息字段格式不正确。")
    try:
        message_type = RuntimeEventControlMessageType(raw_type)
        raw_source_cursors = payload.get("sourceCursors", payload.get("source_cursors", {}))
        source_cursors = (
            _reconnect_source_cursors_from_payload(raw_source_cursors)
            if message_type == RuntimeEventControlMessageType.RECONNECT
            else _source_cursors_from_payload(raw_source_cursors)
        )
        return RuntimeEventControlMessage(
            message_type=message_type,
            subscription_id=payload.get("subscriptionId") or payload.get("subscription_id"),
            request=_subscription_request_from_payload(request_payload) if request_payload else None,
            last_sequence=_optional_int(payload.get("lastSequence", payload.get("last_sequence"))),
            source_cursors=source_cursors,
            after_sequence=_optional_int(payload.get("afterSequence", payload.get("after_sequence"))),
            reason=payload.get("reason"),
            attributes=dict(attributes),
        )
    except RuntimeEventControlMessageError:
        raise
    except (TypeError, ValueError) as exc:
        # Enum/int conversion errors can include attacker-controlled raw values, so replace them with a stable message.
        raise RuntimeEventControlMessageError("事件控制消息字段格式不正确。") from exc


def _subscription_request_from_payload(payload: Mapping[str, Any]) -> RuntimeEventSubscriptionRequest:
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
        source_cursors=_source_cursors_from_payload(payload.get("sourceCursors", payload.get("source_cursors", {}))),
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


def _source_cursors_from_payload(value: Any) -> dict[str, int]:
    """解析 WebSocket 控制消息里的外部 source 游标。

    控制消息是前端/网关实时连接链路使用的入口，不能只支持 HTTP replay 的 `sourceCursors`。
    前端重连时会同时携带：
    - `afterSequence`：表示展示层 envelope 已经处理到哪个序号；
    - `sourceCursors`：表示 Java 投影、未来 Redis Stream/Kafka 回放源各自读到的稳定 cursor。

    此函数服务于 subscribe、ack 和 heartbeat 的既有兼容路径，采用“宽进严出”策略：只有对象形态、
    sourceName 非空、cursor 可转为正整数时才保留。它的输出只是一份脱离原始 JSON 的整数副本，不会
    修改会话，也不会直接调用 Java/Redis/Kafka。

    reconnect 因为会把游标写回可持久化的回放状态，改用下面的
    `_reconnect_source_cursors_from_payload(...)` 做更严格的边界校验；这能在不改变 ACK/heartbeat 语义
    的前提下阻止布尔值、浮点数或字符串游标进入重连状态。
    """

    if not isinstance(value, dict):
        return {}
    cursors: dict[str, int] = {}
    for key, cursor in value.items():
        source_name = str(key).strip()
        if not source_name:
            continue
        try:
            normalized_cursor = int(cursor)
        except (TypeError, ValueError):
            continue
        if normalized_cursor > 0:
            cursors[source_name] = normalized_cursor
    return cursors


def _reconnect_source_cursors_from_payload(value: Any) -> dict[str, int]:
    """严格解析 reconnect 控制帧中的外部 source 游标。

    `sourceCursors` 的每一项都是“某个外部 source 已读取到哪里”，不是展示给前端的 envelope sequence。
    因此它会影响下一次 Java REST replay、Redis Stream 或 Kafka replay 的扫描起点，不能沿用所有类型都
    可强制转换的宽松规则。

    输入必须是 JSON 对象，键必须是非空字符串，值必须是正整数且不能是 Python `bool`。输出是新的
    `{sourceName: cursor}` 字典；无效项会被逐项丢弃，不会用 0、负数、浮点截断值或字符串覆盖已有会话
    游标。这里尚不知道当前会话装配了哪些 source，未知 source 的 fail-closed 过滤和单调合并由
    `RuntimeEventSessionManager` 完成。

    本函数没有网络、存储或会话副作用。客户端即使带来坏条目，重连仍可使用服务器已保存的可信游标继续
    回放，且响应不会回显被拒绝的原始值。
    """

    if not isinstance(value, Mapping):
        return {}
    cursors: dict[str, int] = {}
    for raw_source_name, raw_cursor in value.items():
        if not isinstance(raw_source_name, str):
            continue
        source_name = raw_source_name.strip()
        if not source_name or isinstance(raw_cursor, bool) or not isinstance(raw_cursor, int) or raw_cursor <= 0:
            continue
        cursors[source_name] = max(cursors.get(source_name, 0), raw_cursor)
    return cursors
