"""Agent Runtime 事件存储抽象。

3.40 已经让 replay API 可以按订阅请求筛选调用方传入的事件集合，但生产系统不能依赖前端把事件
原样回传。真正的智能网关需要服务端保存事件，再在断线重连、审计回放或任务详情页打开时按
sessionId/runId/requestId/sequence 查询。

本文件先定义 `RuntimeEventStore` 协议，并提供一个内存实现。内存实现不适合多实例生产部署，但
非常适合当前阶段稳定接口、测试 replay 语义，并为后续 Redis Stream、Kafka、MySQL 审计表或
对象存储归档实现留出同构替换点。
"""

from __future__ import annotations

import json
from dataclasses import asdict
from datetime import datetime
from enum import Enum
from threading import RLock
from typing import Any, Protocol

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventSeverity, AgentRuntimeEventType


class RuntimeEventStore(Protocol):
    """运行时事件存储协议。

    协议只描述最小能力：
    - `append_many(...)`：一次 Agent 计划完成后批量写入事件；
    - `replay(...)`：按订阅请求查询可回放事件。

    后续真实实现可以扩展 TTL、分页、压缩、按租户分区、按 runId 建索引、落库事务等能力，但 API
    层优先依赖这个小协议，避免被某个具体存储技术绑死。
    """

    def append_many(self, events: tuple[AgentRuntimeEvent, ...]) -> None:
        """批量追加事件。"""

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        """按订阅请求返回可回放事件。"""


class InMemoryRuntimeEventStore:
    """内存版运行时事件存储。

    该实现使用一个 list 保存事件，并用 `RLock` 做轻量线程保护。它的定位是本地开发和单元测试：
    - 优点：无外部依赖，便于验证 API/replay/WebSocket 协议；
    - 缺点：进程重启丢失，多实例不共享，不适合生产审计。

    后续生产化时，可以用同样协议替换为：
    - Redis Stream：适合短期实时 replay 和断线续传；
    - Kafka：适合跨服务事件分发和异步审计；
    - MySQL/ClickHouse：适合长期审计查询；
    - MinIO/对象存储：适合低频归档回放。
    """

    def __init__(self, max_events: int = 10000) -> None:
        """初始化内存事件存储。

        `max_events` 是为了避免开发环境长时间运行导致内存无限增长。达到上限后会丢弃最早事件。
        生产环境不应依赖这种裁剪方式，而应使用 TTL、冷热分层或按租户配额控制。
        """

        self._max_events = max(1, max_events)
        self._events: list[AgentRuntimeEvent] = []
        self._lock = RLock()

    def append_many(self, events: tuple[AgentRuntimeEvent, ...]) -> None:
        """批量追加事件并执行容量裁剪。

        这里不对事件去重，因为同一 run 可能确实产生相同类型的多条事件。真实存储如果需要幂等，
        应基于 requestId/runId/sequence 或 envelopeId 做唯一键约束。
        """

        if not events:
            return
        with self._lock:
            self._events.extend(events)
            overflow = len(self._events) - self._max_events
            if overflow > 0:
                del self._events[:overflow]

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        """按订阅请求筛选事件。

        筛选语义与 `RuntimeEventTransportBuilder.build_subscription_replay(...)` 保持一致：多个 ID 条件
        是同时满足关系，`afterSequence` 只返回更大的 sequence，`eventTypes` 是白名单。

        租户、项目、操作者过滤同样在这里执行。虽然 gateway/permission-admin 会做订阅授权，但事件存储
        自身仍应具备基础范围过滤，避免未来某个 replay 入口绕过上层授权后把跨租户事件读出来。
        """

        with self._lock:
            return tuple(event for event in self._events if self._matches(event, request))

    def snapshot(self) -> tuple[AgentRuntimeEvent, ...]:
        """返回当前内存事件快照。

        该方法主要用于测试和调试，不建议生产 API 直接暴露全量事件。
        """

        with self._lock:
            return tuple(self._events)

    @staticmethod
    def _matches(event: AgentRuntimeEvent, request: RuntimeEventSubscriptionRequest) -> bool:
        """判断事件是否满足订阅条件。"""

        if event.sequence is None or event.sequence <= request.after_sequence:
            return False
        if request.tenant_id and event.tenant_id != request.tenant_id:
            return False
        if request.project_id and event.project_id != request.project_id:
            return False
        if request.actor_id and event.actor_id != request.actor_id:
            return False
        if request.session_id and event.session_id != request.session_id:
            return False
        if request.run_id and event.run_id != request.run_id:
            return False
        if request.request_id and event.request_id != request.request_id:
            return False
        if request.event_types and event.event_type not in request.event_types:
            return False
        return True


class RedisStreamRuntimeEventStore:
    """基于 Redis Stream 的运行时事件存储适配器。

    Redis Stream 适合本项目当前阶段的“短期 replay + 断线续传”：
    - `XADD` 负责追加事件，并可以通过 `MAXLEN ~ N` 做近似长度裁剪；
    - `XRANGE` 负责按 Stream ID 范围读取历史条目；
    - 每条 stream entry 保存一条 `AgentRuntimeEvent` 的 JSON 友好字段。

    这仍然不是最终长期审计库。Redis Stream 更适合短窗口实时回放；长期审计、合规追溯和离线分析，
    后续应同步写入 Kafka、MySQL、ClickHouse 或对象存储。
    """

    def __init__(
        self,
        client: Any,
        stream_key: str = "datasmart:agent-runtime:events",
        max_stream_length: int = 10000,
        max_replay_entries: int = 1000,
    ) -> None:
        """初始化 Redis Stream 事件存储。

        `client` 只要求具备 `xadd` 与 `xrange` 方法，因此单元测试可以使用 fake client，生产环境可以
        使用 redis-py 或兼容 Redis Stream 的企业缓存 SDK。
        """

        self._client = client
        self._stream_key = stream_key
        self._max_stream_length = max(1, max_stream_length)
        self._max_replay_entries = max(1, max_replay_entries)

    def append_many(self, events: tuple[AgentRuntimeEvent, ...]) -> None:
        """批量追加事件到 Redis Stream。

        每个 `AgentRuntimeEvent` 写成一条 stream entry，便于按事件粒度回放、排查和未来扩展消费组。
        这里不强行使用业务 sequence 作为 Redis Stream ID，因为 sequence 只在 run/session 内有意义，
        而 Redis Stream ID 需要在同一个 stream 内单调递增；使用 `*` 由 Redis 生成更稳妥。
        """

        for event in events:
            self._client.xadd(
                self._stream_key,
                self._serialize_event(event),
                id="*",
                maxlen=self._max_stream_length,
                approximate=True,
            )

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        """从 Redis Stream 读取并按订阅请求过滤事件。

        第一版使用 `XRANGE stream - + COUNT N` 读取最近窗口中的候选条目，然后在应用层按租户、项目、
        session、run、request、sequence、eventType 过滤。后续数据量增大后，可以演进为按 tenant/session
        分 stream，或为长期审计查询引入 MySQL/ClickHouse 索引。
        """

        entries = self._client.xrange(
            self._stream_key,
            min="-",
            max="+",
            count=self._max_replay_entries,
        )
        events = tuple(self._deserialize_event(fields) for _, fields in entries)
        return tuple(event for event in events if InMemoryRuntimeEventStore._matches(event, request))

    @staticmethod
    def _serialize_event(event: AgentRuntimeEvent) -> dict[str, str]:
        """把事件转换成 Redis Stream field-value 字典。

        Redis Stream field-value 最稳妥的跨客户端表达是字符串，因此这里统一转成字符串。复杂 attributes
        使用 JSON 保存，时间使用 ISO-8601 保存。
        """

        return {
            "eventType": event.event_type.value,
            "stage": event.stage,
            "message": event.message,
            "severity": event.severity.value,
            "tenantId": event.tenant_id or "",
            "projectId": event.project_id or "",
            "actorId": event.actor_id or "",
            "requestId": event.request_id or "",
            "runId": event.run_id or "",
            "sessionId": event.session_id or "",
            "sequence": "" if event.sequence is None else str(event.sequence),
            "attributes": json.dumps(_json_safe(event.attributes), ensure_ascii=False, separators=(",", ":")),
            "createdAt": event.created_at.isoformat(),
        }

    @staticmethod
    def _deserialize_event(fields: dict[Any, Any]) -> AgentRuntimeEvent:
        """把 Redis Stream field-value 字典还原成运行时事件。"""

        normalized = {_to_text(key): _to_text(value) for key, value in fields.items()}
        sequence = normalized.get("sequence")
        return AgentRuntimeEvent(
            event_type=AgentRuntimeEventType(normalized["eventType"]),
            stage=normalized.get("stage", ""),
            message=normalized.get("message", ""),
            severity=AgentRuntimeEventSeverity(normalized.get("severity", AgentRuntimeEventSeverity.INFO.value)),
            tenant_id=_empty_to_none(normalized.get("tenantId")),
            project_id=_empty_to_none(normalized.get("projectId")),
            actor_id=_empty_to_none(normalized.get("actorId")),
            request_id=_empty_to_none(normalized.get("requestId")),
            run_id=_empty_to_none(normalized.get("runId")),
            session_id=_empty_to_none(normalized.get("sessionId")),
            sequence=None if not sequence else int(sequence),
            attributes=json.loads(normalized.get("attributes") or "{}"),
            created_at=datetime.fromisoformat(normalized["createdAt"]),
        )


def _json_safe(value: Any) -> Any:
    """递归转换为 JSON 友好结构。"""

    if hasattr(value, "__dataclass_fields__"):
        return _json_safe(asdict(value))
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value


def _to_text(value: Any) -> str:
    """把 Redis 返回的 bytes/str 统一成 str。"""

    if isinstance(value, bytes):
        return value.decode("utf-8")
    return str(value)


def _empty_to_none(value: str | None) -> str | None:
    """把 Redis 中用于占位的空字符串还原为 None。"""

    if value is None or value == "":
        return None
    return value
