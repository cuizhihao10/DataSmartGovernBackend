"""Agent Runtime 实时事件订阅 checkpoint 存储。

实时事件订阅不是一次性请求，而是一条可能跨刷新、跨断线、跨重连的长生命周期状态流。即便现在还
没有真正接 Redis/数据库，`ack` 与最后一次心跳也不应长期只保存在进程内内存里，否则一旦服务重启，
前端就只能从头订阅。

本文件先定义一个很小的 checkpoint 抽象：
- 记录 subscriptionId 对应的订阅请求和最近确认的 sequence；
- 记录连接状态、最近心跳和关闭原因；
- 为后续 Redis、MySQL、ClickHouse、对象存储或网关集群共享状态预留替换点。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from threading import RLock
from typing import Any, Protocol

from datasmart_ai_runtime.domain.event_transport import RuntimeEventConnectionState, RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType


@dataclass(frozen=True)
class RuntimeEventSubscriptionCheckpoint:
    """实时事件订阅 checkpoint。

    这个对象的粒度比完整会话快照更轻，只保留恢复所需的最小字段：
    - `subscription_id`：checkpoint 主键；
    - `request`：恢复时重新构建订阅计划和 replay 的关键输入；
    - `state`：最后一次已知连接状态；
    - `last_ack_sequence`：客户端确认处理到的最后序号；
    - `connected_at` / `last_heartbeat_at` / `updated_at`：生命周期时间线；
    - `close_reason`：如果已关闭，则记录关闭原因。
    """

    subscription_id: str
    request: RuntimeEventSubscriptionRequest
    state: RuntimeEventConnectionState
    last_ack_sequence: int
    connected_at: datetime
    last_heartbeat_at: datetime
    updated_at: datetime
    close_reason: str | None = None


class RuntimeEventCheckpointStore(Protocol):
    """实时事件 checkpoint 存储协议。"""

    def save(self, checkpoint: RuntimeEventSubscriptionCheckpoint) -> None:
        """保存或更新一个 checkpoint。"""

    def load(self, subscription_id: str) -> RuntimeEventSubscriptionCheckpoint | None:
        """按 subscriptionId 读取 checkpoint。"""

    def delete(self, subscription_id: str) -> None:
        """删除一个 checkpoint。"""


class InMemoryRuntimeEventCheckpointStore:
    """内存 checkpoint 存储。

    这个实现主要用于本地开发、单元测试和协议验证。它并不是生产级持久化，但和事件存储一样，
    用来把接口先固定下来，后续替换成 Redis hash、MySQL checkpoint 表或专用网关状态表时不需要改
    调用方。
    """

    def __init__(self) -> None:
        self._checkpoints: dict[str, RuntimeEventSubscriptionCheckpoint] = {}
        self._lock = RLock()

    def save(self, checkpoint: RuntimeEventSubscriptionCheckpoint) -> None:
        """保存或覆盖 checkpoint。"""

        with self._lock:
            self._checkpoints[checkpoint.subscription_id] = checkpoint

    def load(self, subscription_id: str) -> RuntimeEventSubscriptionCheckpoint | None:
        """读取 checkpoint。"""

        with self._lock:
            return self._checkpoints.get(subscription_id)

    def delete(self, subscription_id: str) -> None:
        """删除 checkpoint。"""

        with self._lock:
            self._checkpoints.pop(subscription_id, None)

    def snapshot(self) -> tuple[RuntimeEventSubscriptionCheckpoint, ...]:
        """返回当前所有 checkpoint，便于测试和调试。"""

        with self._lock:
            return tuple(self._checkpoints.values())


class RedisRuntimeEventCheckpointStore:
    """基于 Redis 的实时事件 checkpoint 存储适配器。

    这个实现不直接依赖 `redis` Python 包类型，而是只要求传入的 `client` 具备 `get`、`set`、
    `delete` 这三个方法。这样本地测试可以使用 fake client，生产环境可以直接接 Redis 客户端、
    Redis Cluster 客户端或任何兼容接口的网关缓存层。
    """

    def __init__(
        self,
        client: Any,
        key_prefix: str = "datasmart:agent-runtime:checkpoint",
        ttl_seconds: int | None = None,
    ) -> None:
        """初始化 Redis checkpoint 存储。"""

        self._client = client
        self._key_prefix = key_prefix.rstrip(":")
        self._ttl_seconds = ttl_seconds if ttl_seconds and ttl_seconds > 0 else None

    def save(self, checkpoint: RuntimeEventSubscriptionCheckpoint) -> None:
        """保存或覆盖 checkpoint。"""

        payload = json.dumps(self._serialize(checkpoint), ensure_ascii=False, separators=(",", ":"))
        if self._ttl_seconds is None:
            self._client.set(self._key(checkpoint.subscription_id), payload)
        else:
            self._client.set(self._key(checkpoint.subscription_id), payload, ex=self._ttl_seconds)

    def load(self, subscription_id: str) -> RuntimeEventSubscriptionCheckpoint | None:
        """读取 checkpoint。"""

        raw = self._client.get(self._key(subscription_id))
        if raw is None:
            return None
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8")
        if not raw:
            return None
        return self._deserialize(json.loads(raw))

    def delete(self, subscription_id: str) -> None:
        """删除 checkpoint。"""

        self._client.delete(self._key(subscription_id))

    def _key(self, subscription_id: str) -> str:
        """构造 Redis key。"""

        return f"{self._key_prefix}:{subscription_id}"

    @staticmethod
    def _serialize(checkpoint: RuntimeEventSubscriptionCheckpoint) -> dict[str, Any]:
        """把 checkpoint 转换成 JSON 友好的字典。"""

        request = checkpoint.request
        return {
            "subscriptionId": checkpoint.subscription_id,
            "request": {
                "clientId": request.client_id,
                "tenantId": request.tenant_id,
                "projectId": request.project_id,
                "actorId": request.actor_id,
                "roles": list(request.roles),
                "sessionId": request.session_id,
                "runId": request.run_id,
                "requestId": request.request_id,
                "afterSequence": request.after_sequence,
                # sourceCursors 必须跟随 checkpoint 持久化。否则多实例恢复或 Python Runtime 重启后，
                # 服务端只知道前端展示层 ack 到哪个 envelope sequence，却不知道 Java 投影等外部 source
                # 已经读取到哪个源级 cursor，容易在恢复订阅时重复拉取旧控制面事件。
                "sourceCursors": dict(request.source_cursors),
                "eventTypes": [item.value for item in request.event_types],
                "includeSnapshot": request.include_snapshot,
            },
            "state": checkpoint.state.value,
            "lastAckSequence": checkpoint.last_ack_sequence,
            "connectedAt": checkpoint.connected_at.isoformat(),
            "lastHeartbeatAt": checkpoint.last_heartbeat_at.isoformat(),
            "updatedAt": checkpoint.updated_at.isoformat(),
            "closeReason": checkpoint.close_reason,
        }

    @staticmethod
    def _deserialize(payload: dict[str, Any]) -> RuntimeEventSubscriptionCheckpoint:
        """把 JSON 字典还原为 checkpoint。"""

        request_payload = payload["request"]
        request = RuntimeEventSubscriptionRequest(
            client_id=str(request_payload.get("clientId") or request_payload.get("client_id") or "anonymous-client"),
            tenant_id=request_payload.get("tenantId") or request_payload.get("tenant_id"),
            project_id=request_payload.get("projectId") or request_payload.get("project_id"),
            actor_id=request_payload.get("actorId") or request_payload.get("actor_id"),
            roles=tuple(request_payload.get("roles", ())),
            session_id=request_payload.get("sessionId") or request_payload.get("session_id"),
            run_id=request_payload.get("runId") or request_payload.get("run_id"),
            request_id=request_payload.get("requestId") or request_payload.get("request_id"),
            after_sequence=int(request_payload.get("afterSequence", request_payload.get("after_sequence", 0))),
            source_cursors=_source_cursors_from_payload(
                request_payload.get("sourceCursors", request_payload.get("source_cursors", {}))
            ),
            event_types=tuple(AgentRuntimeEventType(item) for item in request_payload.get("eventTypes", ())),
            include_snapshot=bool(request_payload.get("includeSnapshot", request_payload.get("include_snapshot", True))),
        )
        return RuntimeEventSubscriptionCheckpoint(
            subscription_id=str(payload.get("subscriptionId") or payload.get("subscription_id")),
            request=request,
            state=RuntimeEventConnectionState(payload.get("state")),
            last_ack_sequence=int(payload.get("lastAckSequence", payload.get("last_ack_sequence", 0))),
            connected_at=datetime.fromisoformat(payload.get("connectedAt") or payload.get("connected_at")),
            last_heartbeat_at=datetime.fromisoformat(payload.get("lastHeartbeatAt") or payload.get("last_heartbeat_at")),
            updated_at=datetime.fromisoformat(payload.get("updatedAt") or payload.get("updated_at")),
            close_reason=payload.get("closeReason") or payload.get("close_reason"),
        )


def _source_cursors_from_payload(value: Any) -> dict[str, int]:
    """解析 checkpoint 中保存的 source 游标。

    Redis checkpoint 是重启恢复和多实例接管的关键状态。这里不直接信任 JSON 内容，
    而是按 sourceName 非空、cursor 为正整数的规则重建游标，避免历史脏数据或手工修复数据导致
    replay client 把负数、空 source 或不可解析值继续传给 Java 查询接口。
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
