"""Agent Runtime 实时事件 WebSocket 帧编排适配层。

3.44 之前我们已经有了：
- `RuntimeEventSessionManager`：维护订阅会话状态；
- `RuntimeEventControlHandler`：把控制消息分发到状态机；
- `build_event_control_response(...)`：返回同步 HTTP 风格的控制响应。

但真正的 WebSocket handler 还需要再多一层编排：一条控制消息在 socket 上可能对应不止一个下行帧。
例如 subscribe 或 reconnect 之后，服务端通常要先发一条“控制响应”，再补发一条 replay/event envelope。

本文件就是这个轻量编排层。它不绑定 FastAPI 的 `WebSocket` 对象，不负责真正收发网络消息，只负责：
- 把一次控制响应拆成一个或多个可发送帧；
- 给 WebSocket handler 一个统一的消息循环结果；
- 让 HTTP、WebSocket 和未来的测试工具复用同一套帧语义。
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
from typing import Any


class RuntimeEventWebSocketFrameType(str, Enum):
    """实时事件 WebSocket 下行帧类型。

    - `CONTROL_RESPONSE`：控制消息的应答，例如 subscribe 成功、ack 成功或 unsubscribe 关闭；
    - `EVENT_ENVELOPE`：实际的事件 envelope，通常是 replay 或 live 推送；
    - `ERROR`：控制消息处理失败时的结构化错误帧。
    """

    CONTROL_RESPONSE = "control_response"
    EVENT_ENVELOPE = "event_envelope"
    ERROR = "error"


@dataclass(frozen=True)
class RuntimeEventWebSocketFrame:
    """WebSocket 下行帧的统一结构。

    这里把每个下行消息都建模成一个 frame，便于未来在真实 WebSocket handler 中显式发送：
    - 一个控制消息可以拆成多个 frame；
    - 前端可以根据 `frameType` 区分“状态变化”和“事件内容”；
    - 测试工具也可以直接验证 frame 顺序，而不需要理解服务端内部如何组织响应。
    """

    frame_type: RuntimeEventWebSocketFrameType
    payload: dict[str, Any]


def build_websocket_frames_from_control_response(response: dict[str, Any]) -> tuple[RuntimeEventWebSocketFrame, ...]:
    """把控制响应拆成适合 WebSocket 下发的帧序列。

    约定：
    - `accepted=false` 时，直接返回一个 `ERROR` 帧，便于前端统一弹窗或重试；
    - `accepted=true` 时，始终返回一条 `CONTROL_RESPONSE` 帧；
    - 如果控制响应里包含 `subscription.replayEnvelope`，则额外返回一条 `EVENT_ENVELOPE` 帧。

    这样 WebSocket handler 不必手工处理嵌套结构，只要顺序发送 frames 即可。
    """

    if not response.get("accepted", True):
        return (
            RuntimeEventWebSocketFrame(
                frame_type=RuntimeEventWebSocketFrameType.ERROR,
                payload=dict(response),
            ),
        )

    subscription = dict(response.get("subscription", {}))
    replay_envelope = subscription.pop("replayEnvelope", None)
    frames = [
        RuntimeEventWebSocketFrame(
            frame_type=RuntimeEventWebSocketFrameType.CONTROL_RESPONSE,
            payload={
                "accepted": True,
                "messageType": response.get("messageType"),
                "subscription": subscription,
            },
        )
    ]
    if replay_envelope is not None:
        frames.append(
            RuntimeEventWebSocketFrame(
                frame_type=RuntimeEventWebSocketFrameType.EVENT_ENVELOPE,
                payload=dict(replay_envelope),
            )
        )
    return tuple(frames)


def frames_to_payloads(frames: tuple[RuntimeEventWebSocketFrame, ...]) -> tuple[dict[str, Any], ...]:
    """把 WebSocket 帧转换成可直接 `send_json(...)` 的 payload 列表。"""

    return tuple(
        {
            "frameType": frame.frame_type,
            "payload": _normalize_payload(frame.payload),
        }
        for frame in frames
    )


class RuntimeEventWebSocketConnectionAdapter:
    """单个 WebSocket 连接的框架无关生命周期适配器。

    这个类刻意不接收 FastAPI/Starlette 的 `WebSocket` 对象，也不直接调用
    `send_json(...)` 或 `receive_json(...)`。它只表达“一个客户端连接”在业务协议上的
    生命周期：
    - 收到 subscribe/reconnect/ack/heartbeat/unsubscribe 控制消息后，交给统一控制处理器；
    - 从控制响应中识别当前连接绑定的 subscriptionId；
    - 需要发送给客户端的数据统一转换成 `frameType + payload` 的 JSON 结构；
    - live push hub 中积压的事件由连接按自己的 subscriptionId 主动 drain；
    - socket 断开时自动 unsubscribe，避免服务端长期保留无主订阅。

    这样拆分有两个商业化价值：
    1. FastAPI、Java Gateway、命令行调试器、未来前端 SDK 的测试桩都可以复用同一套
       生命周期规则，不会出现“HTTP 控制入口允许 ack，WebSocket 入口却忘了更新 ack”
       之类协议漂移。
    2. 当前仍然使用内存 session/live hub，但外层已经形成稳定 seam。后续替换为
       Redis Stream、Kafka 或集群共享 session store 时，连接入口不需要大面积重写。
    """

    def __init__(
        self,
        session_manager: Any,
        live_push_hub: Any | None = None,
    ) -> None:
        """创建连接适配器。

        参数说明：
        - `session_manager`：实时事件订阅状态机，负责 subscribe、ack、heartbeat、
          reconnect、unsubscribe 的真实状态流转。
        - `live_push_hub`：可选的实时推送中枢。单元测试或只做控制协议验证时可以不传；
          真实 WebSocket 连接通常会传入它，并周期性调用 `drain_live_payloads()`。

        适配器内部只保存“当前连接绑定的 subscriptionId”和“连接是否已经关闭”两个
        轻量状态，不复制 session manager 的领域状态，避免双写和不一致。
        """

        self._session_manager = session_manager
        self._live_push_hub = live_push_hub
        self._subscription_id: str | None = None
        self._closed = False

    @property
    def subscription_id(self) -> str | None:
        """返回当前连接绑定的订阅 ID。

        首次连接建立后通常为空；当客户端发送 `subscribe` 或 `reconnect` 且控制处理成功时，
        该字段会被更新。测试、日志和真实 WebSocket handler 可以读取它来定位当前连接，
        但不要绕过 `handle_message(...)` 直接修改它。
        """

        return self._subscription_id

    @property
    def closed(self) -> bool:
        """标记当前适配器是否已经执行过关闭流程。"""

        return self._closed

    def handle_message(self, message: dict[str, Any]) -> tuple[dict[str, Any], ...]:
        """处理客户端发来的单条控制消息，并返回应下发的 WebSocket payload。

        输入通常来自 WebSocket `receive_json()`：
        - `subscribe`：建立订阅，可能同时返回 replay event envelope；
        - `ack`：推进客户端已处理序号，用于断线续传；
        - `heartbeat`：维持连接活性，也可顺手携带 lastSequence；
        - `reconnect`：恢复已有订阅并按 afterSequence 补发；
        - `unsubscribe`：主动关闭订阅。

        方法本身不做网络发送，调用方只需要把返回的 payload 顺序写入 socket 即可。
        如果连接已经关闭，继续收到消息会返回稳定 error frame，而不是重新打开已关闭连接。
        """

        if self._closed:
            return self._build_closed_error_payloads()

        # 惰性导入可以避免 `api_events -> runtime_event_websocket` 的模块循环。
        # 协议入口仍复用 api_events 中的控制响应构建逻辑，防止 HTTP 和 WebSocket 分叉。
        from datasmart_ai_runtime.api_events import build_event_control_response

        response = build_event_control_response(message, self._session_manager)
        frames = build_websocket_frames_from_control_response(response)
        payloads = frames_to_payloads(frames)
        self._refresh_subscription_binding(response)
        return payloads

    def drain_live_payloads(self) -> tuple[dict[str, Any], ...]:
        """拉取当前订阅积压的 live 事件 payload。

        live push hub 的职责是“把新事件放入各订阅 outbox”，而连接适配器的职责是
        “只从自己绑定的 subscriptionId drain”。这种 pull 式设计比让 hub 直接持有
        socket 更容易测试，也更容易在生产上做背压、限速、连接迁移和消息重放。

        如果连接尚未订阅、已经关闭，或当前没有配置 live hub，则返回空 tuple。
        """

        if self._closed or not self._subscription_id or self._live_push_hub is None:
            return ()
        return tuple(self._live_push_hub.drain_payloads(self._subscription_id))

    def close(self, reason: str = "websocket_closed") -> tuple[dict[str, Any], ...]:
        """关闭当前连接并尽量同步取消订阅。

        真实 WebSocket 断开时，底层框架可能不会再允许发送消息；因此本方法返回的 payload
        主要用于单元测试、可观测日志或“优雅关闭”场景。即使下游 unsubscribe 失败，适配器
        也会进入 closed 状态，确保调用方重复 close 不会产生二次副作用。
        """

        if self._closed:
            return ()

        subscription_id = self._subscription_id
        self._closed = True
        self._subscription_id = None
        if not subscription_id:
            return ()

        from datasmart_ai_runtime.api_events import build_event_control_response

        response = build_event_control_response(
            {
                "type": "unsubscribe",
                "subscriptionId": subscription_id,
                "reason": reason,
            },
            self._session_manager,
        )
        frames = build_websocket_frames_from_control_response(response)
        return frames_to_payloads(frames)

    def _refresh_subscription_binding(self, response: dict[str, Any]) -> None:
        """根据控制响应刷新当前连接绑定的 subscriptionId。

        控制响应是唯一可信来源：只有状态机接受了 subscribe/reconnect，连接才绑定订阅；
        如果 unsubscribe 返回 CLOSED，连接本地也要清空绑定，避免继续 drain live outbox。
        """

        if not response.get("accepted"):
            return
        subscription = response.get("subscription") or {}
        subscription_id = subscription.get("subscriptionId")
        state = subscription.get("state")
        message_type = response.get("messageType")
        message_type_value = getattr(message_type, "value", message_type)

        if message_type_value in {"subscribe", "reconnect"} and subscription_id:
            self._subscription_id = str(subscription_id)
            return
        if message_type_value == "unsubscribe" or state == "closed":
            self._subscription_id = None

    @staticmethod
    def _build_closed_error_payloads() -> tuple[dict[str, Any], ...]:
        """构造连接已关闭后的稳定错误帧。"""

        return frames_to_payloads(
            (
                RuntimeEventWebSocketFrame(
                    frame_type=RuntimeEventWebSocketFrameType.ERROR,
                    payload={
                        "accepted": False,
                        "messageType": "connection_closed",
                        "error": {
                            "code": "EVENT_WEBSOCKET_CONNECTION_CLOSED",
                            "message": "WebSocket 连接已经关闭，不能继续处理控制消息。",
                        },
                    },
                ),
            )
        )


def _normalize_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """递归把 dataclass/Enum 转换成更适合 JSON 发送的结构。"""

    normalized: dict[str, Any] = {}
    for key, value in payload.items():
        if hasattr(value, "__dataclass_fields__"):
            normalized[key] = _normalize_payload(asdict(value))
        elif isinstance(value, Enum):
            normalized[key] = value.value
        elif isinstance(value, dict):
            normalized[key] = _normalize_payload(value)
        elif isinstance(value, tuple):
            normalized[key] = [
                _normalize_value(item) for item in value
            ]
        else:
            normalized[key] = _normalize_value(value)
    return normalized


def _normalize_value(value: Any) -> Any:
    """归一化单个值，给 JSON 输出使用。"""

    if hasattr(value, "__dataclass_fields__"):
        return _normalize_payload(asdict(value))
    if isinstance(value, Enum):
        return value.value
    return value
