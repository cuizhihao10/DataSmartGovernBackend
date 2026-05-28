"""Agent Runtime 实时事件 outbox 存储抽象。

`RuntimeEventLivePushHub` 负责判断“哪些订阅应该收到哪些事件”，但它不应该长期负责“这些待发送帧
存在哪里”。如果 outbox 直接写死在 hub 的进程内字典里，多实例部署会出现一个典型问题：

- A 实例接收到了 Agent 事件并把 live frame 放入自己的内存；
- 用户的 WebSocket 因负载均衡或重连落到 B 实例；
- B 实例能通过 Redis checkpoint 恢复 subscriptionId，却读不到 A 实例内存里的 live frame。

因此本文件把 outbox 抽象成可替换协议，并提供两种实现：
- `InMemoryRuntimeEventOutboxStore`：本地开发和单元测试使用，无外部依赖；
- `RedisRuntimeEventOutboxStore`：面向生产多实例的 Redis list 适配器，只依赖最小 Redis 方法集合。

这里仍然不直接引入 `redis` 包，是为了让仓库保持轻依赖。真实部署时可以把 redis-py、Redis Cluster
客户端或企业内部缓存 SDK 作为 client 传入，只要它具备 `rpush/lrange/delete/expire` 等兼容方法。
"""

from __future__ import annotations

import json
from dataclasses import asdict
from datetime import date, datetime
from enum import Enum
from threading import RLock
from typing import Any, Protocol

from datasmart_ai_runtime.services.runtime_event_websocket import (
    RuntimeEventWebSocketFrame,
    RuntimeEventWebSocketFrameType,
)


class RuntimeEventOutboxStore(Protocol):
    """实时事件 WebSocket outbox 存储协议。

    协议刻意只保留两个动作：
    - `enqueue(...)`：把服务端已经包装好的 WebSocket frame 追加到某个订阅的待发送队列；
    - `drain(...)`：一次性取出并清空某个订阅的待发送队列。

    这样可以保持调用方非常稳定：无论底层是内存、Redis list、Redis Stream、Kafka compacted topic，
    还是未来的专用消息网关，LivePushHub 和 WebSocketConnectionAdapter 都只依赖这两个动作。
    """

    def enqueue(self, subscription_id: str, frames: tuple[RuntimeEventWebSocketFrame, ...]) -> None:
        """追加待发送帧。"""

    def drain(self, subscription_id: str) -> tuple[RuntimeEventWebSocketFrame, ...]:
        """取出并清空待发送帧。"""


class InMemoryRuntimeEventOutboxStore:
    """内存版实时事件 outbox。

    该实现适合单元测试、本地开发和单进程 demo。它的行为与 Redis 版本保持一致：同一个
    subscriptionId 下按入队顺序保存 frame，`drain` 后队列被清空。

    注意：它不是生产级实现。进程重启会丢失消息，多实例之间也不会共享 outbox。
    """

    def __init__(self) -> None:
        self._outbox: dict[str, list[RuntimeEventWebSocketFrame]] = {}
        self._lock = RLock()

    def enqueue(self, subscription_id: str, frames: tuple[RuntimeEventWebSocketFrame, ...]) -> None:
        """追加待发送帧。

        空 frames 直接忽略，避免创建无意义 key。真实生产实现也应保持这个行为，减少 Redis 空列表或
        空消息写入。
        """

        if not frames:
            return
        with self._lock:
            self._outbox.setdefault(subscription_id, []).extend(frames)

    def drain(self, subscription_id: str) -> tuple[RuntimeEventWebSocketFrame, ...]:
        """取出并清空某个订阅的待发送帧。"""

        with self._lock:
            return tuple(self._outbox.pop(subscription_id, ()))


class RedisRuntimeEventOutboxStore:
    """基于 Redis list 的实时事件 outbox 适配器。

    Redis list 适合当前阶段的原因：
    - `RPUSH` 能保持同一订阅内的 frame 顺序；
    - `LRANGE + DELETE` 可以实现简单 drain；
    - TTL 可以防止客户端永久离线后 outbox key 长期滞留；
    - 数据结构简单，便于用 fake client 做单元测试。

    生产上的进一步演进：
    - 如果需要强消费确认，可以升级为 Redis Stream + consumer group；
    - 如果需要跨服务事件广播和审计，可把 RuntimeEventEnvelope 同时写 Kafka；
    - 如果需要长期追溯，应把 envelope 落 MySQL/ClickHouse/对象存储，而不是只依赖 Redis。
    """

    def __init__(
        self,
        client: Any,
        key_prefix: str = "datasmart:agent-runtime:event-outbox",
        ttl_seconds: int | None = 300,
    ) -> None:
        """初始化 Redis outbox。

        `ttl_seconds` 表示队列在没有新消息后最多保留多久。默认 5 分钟是为了覆盖常见浏览器刷新、
        短暂断网和网关重连窗口，同时避免离线客户端积压无限增长。
        """

        self._client = client
        self._key_prefix = key_prefix.rstrip(":")
        self._ttl_seconds = ttl_seconds if ttl_seconds and ttl_seconds > 0 else None

    def enqueue(self, subscription_id: str, frames: tuple[RuntimeEventWebSocketFrame, ...]) -> None:
        """把 frame 追加到 Redis list。

        每个 frame 单独序列化成一条 list item，便于保持顺序，也便于未来做单条消息大小限制和问题
        frame 排查。
        """

        if not frames:
            return
        key = self._key(subscription_id)
        payloads = tuple(
            json.dumps(self._serialize_frame(frame), ensure_ascii=False, separators=(",", ":"))
            for frame in frames
        )
        self._client.rpush(key, *payloads)
        if self._ttl_seconds is not None:
            self._client.expire(key, self._ttl_seconds)

    def drain(self, subscription_id: str) -> tuple[RuntimeEventWebSocketFrame, ...]:
        """从 Redis list 读取所有 frame 并删除 key。

        当前采用 `LRANGE + DELETE`，这是第一版足够清晰的 drain 语义。严格生产场景如果担心两条命令
        之间进程崩溃导致重复或丢失，可以升级为 Lua 脚本或 Redis Stream ack 模式。
        """

        key = self._key(subscription_id)
        raw_items = self._client.lrange(key, 0, -1) or ()
        self._client.delete(key)
        return tuple(self._deserialize_frame(item) for item in raw_items)

    def _key(self, subscription_id: str) -> str:
        """构造 Redis key。"""

        return f"{self._key_prefix}:{subscription_id}"

    @staticmethod
    def _serialize_frame(frame: RuntimeEventWebSocketFrame) -> dict[str, Any]:
        """把 WebSocket frame 转换成 JSON 友好的字典。"""

        return {
            "frameType": frame.frame_type.value,
            "payload": _json_safe(frame.payload),
        }

    @staticmethod
    def _deserialize_frame(raw: Any) -> RuntimeEventWebSocketFrame:
        """把 Redis 中的 JSON 字符串还原为 WebSocket frame。"""

        if isinstance(raw, bytes):
            raw = raw.decode("utf-8")
        payload = json.loads(raw)
        return RuntimeEventWebSocketFrame(
            frame_type=RuntimeEventWebSocketFrameType(payload["frameType"]),
            payload=dict(payload.get("payload") or {}),
        )


def _json_safe(value: Any) -> Any:
    """递归转换成 JSON 可序列化结构。

    Agent 事件 envelope 中可能包含 dataclass、Enum、datetime、tuple 等 Python 对象。真实 WebSocket
    发送前最终都要变成 JSON，因此 outbox 持久化也在这里完成一次规范化，避免 Redis 中出现不可读的
    Python 对象表示。
    """

    if hasattr(value, "__dataclass_fields__"):
        return _json_safe(asdict(value))
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value
