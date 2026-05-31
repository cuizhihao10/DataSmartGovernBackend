"""Agent Runtime 事件传输契约。

`AgentRuntimeEvent` 描述“发生了什么”，但智能网关还需要一个更外层的 envelope 描述“这些事件将
如何被传输、从哪里开始回放、是否需要确认、是否还有更多数据”。如果没有统一 envelope，HTTP、
WebSocket、Kafka 和审计落库很容易各自定义一套字段，后续前端和 Java 控制面就会出现适配成本。

本文件只定义传输层领域契约，不直接实现 WebSocket、Kafka Producer 或数据库写入。这样 Python
AI Runtime 可以先稳定协议，再逐步交给 Java gateway、Python API 或消息总线适配器落地。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.events import AgentRuntimeEvent
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType


class RuntimeEventChannel(str, Enum):
    """事件传输通道。

    通道描述事件将被送往哪里：
    - `HTTP_RESPONSE`：随同步 API 响应一次性返回，适合当前 `AgentPlan.runtimeEvents`。
    - `WEBSOCKET`：面向前端实时进度推送，适合会话窗口和任务看板。
    - `KAFKA`：面向跨服务异步消费，适合 Java 控制面、审计服务、告警服务订阅。
    - `AUDIT_LOG`：面向持久化审计或离线回放，不一定实时。
    """

    HTTP_RESPONSE = "http_response"
    WEBSOCKET = "websocket"
    KAFKA = "kafka"
    AUDIT_LOG = "audit_log"


class RuntimeEventDeliveryMode(str, Enum):
    """事件投递模式。

    - `SNAPSHOT`：一次性快照，通常包含当前请求已经产生的全部事件；
    - `LIVE`：实时增量，通常一次 envelope 只携带新产生的一条或几条事件；
    - `REPLAY`：断线续传或审计回放，通常从某个 sequence 之后继续返回。
    """

    SNAPSHOT = "snapshot"
    LIVE = "live"
    REPLAY = "replay"


class RuntimeEventAckMode(str, Enum):
    """事件确认策略。

    - `NONE`：不要求客户端确认，适合同步 HTTP 快照；
    - `CLIENT_ACK`：前端或调用方需要确认收到 sequence，用于 WebSocket 断线续传；
    - `BROKER_ACK`：由消息中间件确认投递，适合 Kafka 等 broker 场景。
    """

    NONE = "none"
    CLIENT_ACK = "client_ack"
    BROKER_ACK = "broker_ack"


class RuntimeEventConnectionState(str, Enum):
    """WebSocket/实时事件订阅连接状态。

    这里定义的是“协议层状态”，不是某个 WebSocket 框架的连接对象。这样做有两个好处：
    - Python AI Runtime 可以先稳定连接生命周期语义，后续 FastAPI WebSocket、Java Gateway 或
      其他实时网关都可以复用同一套状态；
    - 前端可以根据状态展示“连接中、实时接收、心跳超时、已关闭”等明确 UI，而不是只依赖底层
      socket 是否断开。

    状态含义：
    - `CONNECTING`：服务端已收到订阅意图，正在建立订阅计划或准备首批 replay/snapshot；
    - `ACTIVE`：订阅已经建立，服务端可以推送 live/replay envelope，并接受客户端 ack；
    - `STALE`：连接长时间未心跳，服务端暂不认为它可靠，但仍可短期保留会话以等待重连；
    - `CLOSED`：客户端主动取消、服务端踢下线、鉴权失败或过期清理后进入终态。
    """

    CONNECTING = "connecting"
    ACTIVE = "active"
    STALE = "stale"
    CLOSED = "closed"


class RuntimeEventControlMessageType(str, Enum):
    """实时事件连接控制消息类型。

    业务事件通过 `AgentRuntimeEvent` 传输，而连接控制消息描述“客户端和服务端如何维护这条事件流”。
    把控制消息单独建模，可以避免把 ack、心跳、重连等基础设施字段混进业务事件 attributes 中。

    - `SUBSCRIBE`：客户端请求订阅某个 session/run/request 的事件；
    - `ACK`：客户端确认已经处理到某个 sequence，服务端可据此计算断线续传起点；
    - `HEARTBEAT`：客户端证明连接仍然存活，也可携带 lastSequence 做轻量确认；
    - `UNSUBSCRIBE`：客户端主动关闭订阅，例如用户离开页面或切换会话；
    - `RECONNECT`：客户端断线后携带 afterSequence 重新接入，服务端应先 replay 再继续 live。
    """

    SUBSCRIBE = "subscribe"
    ACK = "ack"
    HEARTBEAT = "heartbeat"
    UNSUBSCRIBE = "unsubscribe"
    RECONNECT = "reconnect"


@dataclass(frozen=True)
class RuntimeEventSubscriptionRequest:
    """智能网关事件订阅请求。

    该对象描述前端或 Java gateway 想订阅哪一段 Agent 事件流。真实 WebSocket 握手时，这些字段可
    以来自 query 参数、首条 subscribe 消息或网关认证上下文：
    - `client_id`：客户端连接 ID，用于多标签页、多设备和断线重连定位；
    - `session_id`：会话 ID，适合订阅某个用户会话下的所有 Agent 事件；
    - `run_id`：运行 ID，适合订阅某一次具体 Agent 执行；
    - `request_id`：请求 ID，适合同步计划请求后的短时事件回放；
    - `after_sequence`：客户端已处理到的最后 sequence，服务端只返回更大的事件；
    - `source_cursors`：外部 replay source 的源级游标，例如 Java 控制面的 replaySequence。
      它解决的是“全局展示 sequence”和“各事件源内部 cursor”不完全一致的问题：前端可以继续用
      afterSequence 做统一 ack，同时把上一轮 envelope 返回的 sourceCursors 原样带回，服务端再按源头稳定游标增量读取；
    - `event_types`：事件类型白名单，前端可以只订阅审批/告警等关键事件；
    - `include_snapshot`：订阅建立时是否先返回当前快照，再进入实时增量。
    """

    client_id: str
    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    roles: tuple[str, ...] = ()
    session_id: str | None = None
    run_id: str | None = None
    request_id: str | None = None
    after_sequence: int = 0
    source_cursors: dict[str, int] = field(default_factory=dict)
    event_types: tuple[AgentRuntimeEventType, ...] = ()
    include_snapshot: bool = True


@dataclass(frozen=True)
class RuntimeEventControlMessage:
    """实时事件连接控制消息。

    前端或 Java Gateway 通过 WebSocket 发送的消息通常不会全是业务事件，还会包含订阅、确认、心跳、
    取消订阅、重连等“控制指令”。本对象把这些指令统一成领域契约，避免 FastAPI WebSocket、HTTP
    管理接口、测试工具和未来前端 SDK 各自解析一套字段。

    字段说明：
    - `message_type`：控制消息类型，决定服务端应该执行哪种状态流转；
    - `subscription_id`：服务端生成的订阅 ID，除首次 subscribe 外，大多数控制消息都应携带；
    - `request`：订阅请求，仅 `SUBSCRIBE` 必填，`RECONNECT` 也可以携带新的 afterSequence 或过滤条件；
    - `last_sequence`：客户端已处理到的最后事件序号，主要用于 ack 和 heartbeat；
    - `source_cursors`：客户端同时确认的外部事件源游标，例如 Java 控制面的 replaySequence；
      它与 last_sequence 的坐标系不同：last_sequence 是前端看到的 envelope 序号，source_cursors 是各
      后端 source 自己的稳定游标。二者拆开后，WebSocket ack 才能同时服务“前端断线续传”和
      “Java 控制面 cursor 回写”；
    - `after_sequence`：重连时显式指定从哪个 sequence 之后回放；不传时使用服务端保存的最后 ack；
    - `reason`：关闭原因或控制说明，例如用户离开页面、前端切换会话、权限撤销；
    - `attributes`：为未来扩展保留的自由字段，例如前端版本、设备 ID、网络类型、traceId。
    """

    message_type: RuntimeEventControlMessageType
    subscription_id: str | None = None
    request: RuntimeEventSubscriptionRequest | None = None
    last_sequence: int | None = None
    source_cursors: dict[str, int] = field(default_factory=dict)
    after_sequence: int | None = None
    reason: str | None = None
    attributes: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class RuntimeEventSubscriptionPlan:
    """事件订阅计划。

    订阅请求是“客户端想要什么”，订阅计划是“服务端准备如何处理”。把二者拆开，后续网关可以在
    plan 中加入鉴权结果、限流策略、订阅通道名、回放起点和是否需要 ack 等服务端决策。
    """

    subscription_id: str
    request: RuntimeEventSubscriptionRequest
    channel: RuntimeEventChannel = RuntimeEventChannel.WEBSOCKET
    ack_mode: RuntimeEventAckMode = RuntimeEventAckMode.CLIENT_ACK
    attributes: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class RuntimeEventEnvelope:
    """运行时事件传输 envelope。

    字段说明：
    - `envelope_id`：本次 envelope 的唯一 ID，便于日志排查和客户端去重；
    - `schema_version`：事件传输协议版本，后续字段升级时可做兼容判断；
    - `channel/delivery_mode/ack_mode`：描述传输通道、投递模式和确认策略；
    - `request_id/run_id/session_id`：事件流关联 ID，来自事件本身或网关上下文；
    - `sequence_from/sequence_to`：本 envelope 覆盖的事件序列范围；
    - `replay_from_sequence`：回放请求的起点，用于断线续传语义；
    - `has_more`：当前 envelope 后面是否可能还有更多事件；
    - `events`：实际携带的结构化运行时事件；
    - `attributes`：传输层扩展字段，例如 topic、channelName、clientId、partitionKey。
    """

    envelope_id: str
    channel: RuntimeEventChannel
    delivery_mode: RuntimeEventDeliveryMode
    ack_mode: RuntimeEventAckMode
    events: tuple[AgentRuntimeEvent, ...]
    schema_version: str = "agent-runtime-event-envelope/v1"
    request_id: str | None = None
    run_id: str | None = None
    session_id: str | None = None
    sequence_from: int | None = None
    sequence_to: int | None = None
    replay_from_sequence: int | None = None
    has_more: bool = False
    attributes: dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
