"""Python AI Runtime 实时事件组件组装。

前面几个阶段已经把实时事件能力拆成多个可替换部件：
- `RuntimeEventStore`：保存可回放的运行时事件；
- `RuntimeEventCheckpointStore`：保存订阅 ack、心跳和连接状态；
- `RuntimeEventOutboxStore`：保存待 WebSocket 发送的 live frame；
- `RuntimeEventSessionManager`：维护订阅状态机；
- `RuntimeEventLivePushHub`：匹配订阅并把事件写入 outbox。

这些部件不能只停留在“有接口、有实现”，还需要有一个启动组装层，把本地开发和生产部署的差异通过
配置表达出来。本文件就是这个组装层：它按环境变量选择 in-memory 或 Redis 风格实现，同时保持默认
零依赖可运行。

当前没有把 Redis 作为强制依赖写进 `pyproject.toml`，是有意设计：
- 本地学习、单元测试、离线规划不需要启动 Redis；
- 生产环境显式选择 Redis 时，如果缺少 redis-py，会得到清晰错误；
- 后续企业环境也可以注入兼容 client factory，接 Redis Cluster 或内部缓存 SDK。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import urlsplit, urlunsplit

from datasmart_ai_runtime.services.runtime_events.runtime_event_checkpoint_store import (
    InMemoryRuntimeEventCheckpointStore,
    RedisRuntimeEventCheckpointStore,
    RuntimeEventCheckpointStore,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_live_push import RuntimeEventLivePushHub
from datasmart_ai_runtime.services.runtime_events.runtime_event_outbox_store import (
    InMemoryRuntimeEventOutboxStore,
    RedisRuntimeEventOutboxStore,
    RuntimeEventOutboxStore,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_publisher import (
    KafkaRuntimeEventPublisher,
    NoopRuntimeEventPublisher,
    RuntimeEventPublisher,
    build_default_kafka_producer,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_session import RuntimeEventSessionManager
from datasmart_ai_runtime.services.runtime_events.runtime_event_replay_source import RuntimeEventReplaySource
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import (
    InMemoryRuntimeEventStore,
    RedisStreamRuntimeEventStore,
    RuntimeEventStore,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_transport import RuntimeEventTransportBuilder
from datasmart_ai_runtime.services.runtime_events.runtime_event_visibility import (
    RuntimeEventVisibilityPolicy,
    RuntimeEventVisibilityStats,
)


RedisClientFactory = Callable[[str], Any]
KafkaProducerFactory = Callable[[str, str], Any]


@dataclass(frozen=True)
class RuntimeEventComponentSettings:
    """实时事件组件配置。

    字段说明：
    - `event_store`：事件 replay 存储类型，支持 `in-memory` 与 `redis-stream`；
    - `checkpoint_store`：订阅 checkpoint 存储类型，支持 `in-memory` 与 `redis`；
    - `outbox_store`：live outbox 存储类型，支持 `in-memory` 与 `redis`；
    - `redis_url`：Redis 连接字符串，checkpoint 和 outbox 可以共用；
    - `checkpoint_ttl_seconds`：checkpoint 保留时间，覆盖短期断线重连窗口；
    - `outbox_ttl_seconds`：outbox 保留时间，防止离线客户端无限积压；
    - `heartbeat_timeout_seconds`：服务端认定订阅进入 STALE 的心跳超时。
    """

    event_store: str = "in-memory"
    checkpoint_store: str = "in-memory"
    outbox_store: str = "in-memory"
    redis_url: str = "redis://localhost:6379/0"
    event_stream_key: str = "datasmart:agent-runtime:events"
    event_stream_max_length: int = 10000
    event_replay_max_entries: int = 1000
    checkpoint_ttl_seconds: int = 3600
    outbox_ttl_seconds: int = 300
    heartbeat_timeout_seconds: int = 45
    event_publisher: str = "none"
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_event_topic: str = "datasmart.agent-runtime.events"
    kafka_client_id: str = "datasmart-python-ai-runtime"
    kafka_flush_on_publish: bool = False


@dataclass(frozen=True)
class RuntimeEventRuntimeComponents:
    """实时事件运行时组件集合。

    API 层创建一次该对象，然后在 `/agent/plans`、`/agent/events/replay`、`/agent/events/control` 和
    `/agent/events/ws` 中共享这些实例，避免每个请求重新创建会话表或 outbox。
    """

    event_store: RuntimeEventStore
    checkpoint_store: RuntimeEventCheckpointStore
    outbox_store: RuntimeEventOutboxStore
    event_publisher: RuntimeEventPublisher
    visibility_policy: RuntimeEventVisibilityPolicy
    visibility_stats: RuntimeEventVisibilityStats
    session_manager: RuntimeEventSessionManager
    live_push_hub: RuntimeEventLivePushHub
    external_replay_sources: tuple[RuntimeEventReplaySource, ...]
    settings: RuntimeEventComponentSettings


def runtime_event_settings_from_env(environ: dict[str, str] | None = None) -> RuntimeEventComponentSettings:
    """从环境变量读取实时事件组件配置。

    支持的环境变量：
    - `DATASMART_AI_RUNTIME_EVENT_STORE`：`in-memory` 或 `redis-stream`；
    - `DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_STORE`：`in-memory` 或 `redis`；
    - `DATASMART_AI_RUNTIME_EVENT_OUTBOX_STORE`：`in-memory` 或 `redis`；
    - `DATASMART_AI_RUNTIME_REDIS_URL`：Redis 连接字符串；
    - `DATASMART_AI_RUNTIME_EVENT_STREAM_KEY`：Redis Stream key；
    - `DATASMART_AI_RUNTIME_EVENT_STREAM_MAX_LENGTH`：Redis Stream 近似最大长度；
    - `DATASMART_AI_RUNTIME_EVENT_REPLAY_MAX_ENTRIES`：单次 replay 最多扫描条目数；
    - `DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_TTL_SECONDS`：checkpoint TTL；
    - `DATASMART_AI_RUNTIME_EVENT_OUTBOX_TTL_SECONDS`：outbox TTL；
    - `DATASMART_AI_RUNTIME_EVENT_HEARTBEAT_TIMEOUT_SECONDS`：订阅心跳超时。
    """

    source = environ if environ is not None else os.environ
    return RuntimeEventComponentSettings(
        event_store=_normalize_event_store_type(
            source.get("DATASMART_AI_RUNTIME_EVENT_STORE"),
            default="in-memory",
            variable_name="DATASMART_AI_RUNTIME_EVENT_STORE",
        ),
        checkpoint_store=_normalize_store_type(
            source.get("DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_STORE"),
            default="in-memory",
            variable_name="DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_STORE",
        ),
        outbox_store=_normalize_store_type(
            source.get("DATASMART_AI_RUNTIME_EVENT_OUTBOX_STORE"),
            default="in-memory",
            variable_name="DATASMART_AI_RUNTIME_EVENT_OUTBOX_STORE",
        ),
        redis_url=source.get("DATASMART_AI_RUNTIME_REDIS_URL") or "redis://localhost:6379/0",
        event_stream_key=source.get("DATASMART_AI_RUNTIME_EVENT_STREAM_KEY") or "datasmart:agent-runtime:events",
        event_stream_max_length=_positive_int(
            source.get("DATASMART_AI_RUNTIME_EVENT_STREAM_MAX_LENGTH"),
            default=10000,
        ),
        event_replay_max_entries=_positive_int(
            source.get("DATASMART_AI_RUNTIME_EVENT_REPLAY_MAX_ENTRIES"),
            default=1000,
        ),
        checkpoint_ttl_seconds=_positive_int(
            source.get("DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_TTL_SECONDS"),
            default=3600,
        ),
        outbox_ttl_seconds=_positive_int(
            source.get("DATASMART_AI_RUNTIME_EVENT_OUTBOX_TTL_SECONDS"),
            default=300,
        ),
        heartbeat_timeout_seconds=_positive_int(
            source.get("DATASMART_AI_RUNTIME_EVENT_HEARTBEAT_TIMEOUT_SECONDS"),
            default=45,
        ),
        event_publisher=_normalize_event_publisher_type(
            source.get("DATASMART_AI_RUNTIME_EVENT_PUBLISHER"),
            default="none",
            variable_name="DATASMART_AI_RUNTIME_EVENT_PUBLISHER",
        ),
        kafka_bootstrap_servers=source.get("DATASMART_AI_RUNTIME_KAFKA_BOOTSTRAP_SERVERS") or "localhost:9092",
        kafka_event_topic=source.get("DATASMART_AI_RUNTIME_EVENT_TOPIC") or "datasmart.agent-runtime.events",
        kafka_client_id=source.get("DATASMART_AI_RUNTIME_KAFKA_CLIENT_ID") or "datasmart-python-ai-runtime",
        kafka_flush_on_publish=_truthy(source.get("DATASMART_AI_RUNTIME_KAFKA_FLUSH_ON_PUBLISH")),
    )


def build_runtime_event_components(
    settings: RuntimeEventComponentSettings | None = None,
    redis_client_factory: RedisClientFactory | None = None,
    kafka_producer_factory: KafkaProducerFactory | None = None,
    event_store: RuntimeEventStore | None = None,
    event_publisher: RuntimeEventPublisher | None = None,
    external_replay_sources: tuple[RuntimeEventReplaySource, ...] = (),
) -> RuntimeEventRuntimeComponents:
    """按配置组装实时事件运行时组件。

    组装规则：
    1. `event_store` 可选择内存或 Redis Stream；
    2. checkpoint/outbox 可独立选择 in-memory 或 Redis；
    3. 如果多个组件都选择 Redis，会复用同一个 Redis client，减少连接数；
    4. 如果显式选择 Redis 但没有 redis-py，也没有注入 factory，则抛出清晰错误。

    这个函数是生产化切换的关键入口：后续 FastAPI、命令行 worker、Kafka consumer 或测试工具都应
    优先复用它，而不是各自手动 new 一组 session/live/outbox 对象。
    """

    resolved_settings = settings or runtime_event_settings_from_env()
    redis_client = _build_redis_client_if_needed(resolved_settings, redis_client_factory)
    resolved_event_store = event_store or _build_event_store(resolved_settings, redis_client)
    checkpoint_store = _build_checkpoint_store(resolved_settings, redis_client)
    outbox_store = _build_outbox_store(resolved_settings, redis_client)
    resolved_event_publisher = event_publisher or _build_event_publisher(
        resolved_settings,
        kafka_producer_factory,
    )
    visibility_stats = RuntimeEventVisibilityStats()
    visibility_policy = RuntimeEventVisibilityPolicy(visibility_stats)
    transport_builder = RuntimeEventTransportBuilder(visibility_policy)
    session_manager = RuntimeEventSessionManager(
        event_store=resolved_event_store,
        checkpoint_store=checkpoint_store,
        transport_builder=transport_builder,
        external_replay_sources=external_replay_sources,
        heartbeat_timeout_seconds=resolved_settings.heartbeat_timeout_seconds,
    )
    live_push_hub = RuntimeEventLivePushHub(
        session_manager=session_manager,
        outbox_store=outbox_store,
        transport_builder=transport_builder,
        visibility_policy=visibility_policy,
    )
    return RuntimeEventRuntimeComponents(
        event_store=resolved_event_store,
        checkpoint_store=checkpoint_store,
        outbox_store=outbox_store,
        event_publisher=resolved_event_publisher,
        visibility_policy=visibility_policy,
        visibility_stats=visibility_stats,
        session_manager=session_manager,
        live_push_hub=live_push_hub,
        external_replay_sources=tuple(external_replay_sources),
        settings=resolved_settings,
    )


def runtime_event_component_diagnostics(components: RuntimeEventRuntimeComponents) -> dict[str, Any]:
    """生成实时事件组件诊断信息。

    该函数面向运维排障和联调，不做存活探测，也不直接访问 Redis。它只回答一个非常关键的问题：
    “当前 Python Runtime 启动时到底选择了哪些实时事件组件，以及关键容量/TTL 配置是什么？”

    为什么不直接返回 `settings.__dict__`：
    - Redis URL 可能包含密码，必须脱敏；
    - 需要同时返回实际实现类名，避免配置值与真实组装结果不一致时难以排查；
    - 诊断响应应保持稳定字段，后续 API、日志和健康检查都可以复用。
    """

    settings = components.settings
    return {
        "component": "python-ai-runtime-event-components",
        "eventStore": {
            "configuredType": settings.event_store,
            "implementation": components.event_store.__class__.__name__,
            "streamKey": settings.event_stream_key if settings.event_store == "redis-stream" else None,
            "streamMaxLength": settings.event_stream_max_length if settings.event_store == "redis-stream" else None,
            "replayMaxEntries": settings.event_replay_max_entries,
            "purpose": "保存 Agent runtime events，供 WebSocket reconnect、HTTP replay 和运行详情页短期回放使用。",
        },
        "checkpointStore": {
            "configuredType": settings.checkpoint_store,
            "implementation": components.checkpoint_store.__class__.__name__,
            "ttlSeconds": settings.checkpoint_ttl_seconds if settings.checkpoint_store == "redis" else None,
            "purpose": "保存 subscription ack、heartbeat、state 和 close reason，用于断线重连与会话恢复。",
        },
        "outboxStore": {
            "configuredType": settings.outbox_store,
            "implementation": components.outbox_store.__class__.__name__,
            "ttlSeconds": settings.outbox_ttl_seconds if settings.outbox_store == "redis" else None,
            "purpose": "保存待 WebSocket 发送的 live frame，用于跨实例连接迁移和短时离线缓冲。",
        },
        "eventPublisher": {
            "configuredType": settings.event_publisher,
            "implementation": components.event_publisher.__class__.__name__,
            "topic": settings.kafka_event_topic if settings.event_publisher == "kafka" else None,
            "bootstrapServers": settings.kafka_bootstrap_servers if settings.event_publisher == "kafka" else None,
            "clientId": settings.kafka_client_id if settings.event_publisher == "kafka" else None,
            "flushOnPublish": settings.kafka_flush_on_publish if settings.event_publisher == "kafka" else None,
            "purpose": "把 Python AI Runtime 产生的 Agent runtime events 发布到异步消息总线，供 Java 控制面、审计、告警和观测链路消费。",
        },
        "sessionManager": {
            "implementation": components.session_manager.__class__.__name__,
            "heartbeatTimeoutSeconds": settings.heartbeat_timeout_seconds,
        },
        "externalReplaySources": {
            "enabled": bool(components.external_replay_sources),
            "sources": tuple(source.source_name for source in components.external_replay_sources),
            "ackCapableSources": tuple(
                source.source_name for source in components.external_replay_sources if hasattr(source, "acknowledge")
            ),
            "purpose": (
                "把 Java agent-runtime 投影、未来 Kafka/审计库/对象归档等外部事件源合并进 "
                "HTTP replay 与 WebSocket subscribe/reconnect；其中 ackCapableSources 还会在客户端 "
                "ack/heartbeat 携带 sourceCursors 时回写外部消费游标。"
            ),
        },
        "livePushHub": {
            "implementation": components.live_push_hub.__class__.__name__,
        },
        "visibilityPolicy": {
            "implementation": components.visibility_policy.__class__.__name__,
            "stats": components.visibility_stats.snapshot(),
            "purpose": (
                "统计 WebSocket/replay 事件可见性策略命中情况，包括被过滤事件数、脱敏事件数、"
                "脱敏字段数和角色策略级别命中次数。"
            ),
        },
        "redis": {
            "enabled": _uses_redis(settings),
            "url": _mask_redis_url(settings.redis_url) if _uses_redis(settings) else None,
        },
        "notes": (
            "该诊断只说明启动配置和组件类型，不代表 Redis 当前一定可用；生产环境仍应补充 Redis ping、"
            "stream 长度、outbox 积压和 replay 命中率等运行时指标。"
        ),
    }


def _build_event_publisher(
    settings: RuntimeEventComponentSettings,
    kafka_producer_factory: KafkaProducerFactory | None,
) -> RuntimeEventPublisher:
    """创建 runtime event publisher。

    发布器与 event store / outbox 的定位不同：
    - store/outbox 解决“当前连接或当前用户如何 replay/live push”；
    - publisher 解决“平台其他微服务如何异步消费同一批 Agent runtime events”。

    默认返回 `NoopRuntimeEventPublisher`，是为了让 Python Runtime 在没有 Kafka 的学习环境中仍然可运行；
    一旦配置为 `kafka`，就会创建 Kafka producer，把事件交给平台异步总线。
    """

    if settings.event_publisher == "none":
        return NoopRuntimeEventPublisher()

    factory = kafka_producer_factory or build_default_kafka_producer
    producer = factory(settings.kafka_bootstrap_servers, settings.kafka_client_id)
    return KafkaRuntimeEventPublisher(
        producer,
        topic=settings.kafka_event_topic,
        flush_on_publish=settings.kafka_flush_on_publish,
    )


def _build_event_store(
    settings: RuntimeEventComponentSettings,
    redis_client: Any | None,
) -> RuntimeEventStore:
    """创建 runtime event store。"""

    if settings.event_store == "in-memory":
        return InMemoryRuntimeEventStore()
    return RedisStreamRuntimeEventStore(
        redis_client,
        stream_key=settings.event_stream_key,
        max_stream_length=settings.event_stream_max_length,
        max_replay_entries=settings.event_replay_max_entries,
    )


def _build_checkpoint_store(
    settings: RuntimeEventComponentSettings,
    redis_client: Any | None,
) -> RuntimeEventCheckpointStore:
    """创建 checkpoint store。"""

    if settings.checkpoint_store == "in-memory":
        return InMemoryRuntimeEventCheckpointStore()
    return RedisRuntimeEventCheckpointStore(
        redis_client,
        ttl_seconds=settings.checkpoint_ttl_seconds,
    )


def _build_outbox_store(
    settings: RuntimeEventComponentSettings,
    redis_client: Any | None,
) -> RuntimeEventOutboxStore:
    """创建 outbox store。"""

    if settings.outbox_store == "in-memory":
        return InMemoryRuntimeEventOutboxStore()
    return RedisRuntimeEventOutboxStore(
        redis_client,
        ttl_seconds=settings.outbox_ttl_seconds,
    )


def _build_redis_client_if_needed(
    settings: RuntimeEventComponentSettings,
    redis_client_factory: RedisClientFactory | None,
) -> Any | None:
    """在配置需要 Redis 时创建 Redis client。"""

    if settings.event_store != "redis-stream" and "redis" not in {settings.checkpoint_store, settings.outbox_store}:
        return None
    if redis_client_factory is not None:
        return redis_client_factory(settings.redis_url)
    return _default_redis_client_factory(settings.redis_url)


def _default_redis_client_factory(redis_url: str) -> Any:
    """使用 redis-py 创建 Redis client。

    当前 redis-py 不是默认依赖，所以只有显式选择 Redis 运行模式时才导入。这样本地学习和单元测试
    不会因为没有 Redis 包而失败。
    """

    try:
        import redis  # type: ignore
    except ImportError as exc:  # pragma: no cover - 只有生产显式启用 Redis 且缺依赖时触发
        raise RuntimeError(
            "已配置 Python AI Runtime 使用 Redis 实时事件组件，但当前环境未安装 redis-py。"
            "请安装 redis 包，或把 DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_STORE / "
            "DATASMART_AI_RUNTIME_EVENT_OUTBOX_STORE 改回 in-memory。"
        ) from exc
    return redis.from_url(redis_url)


def _normalize_store_type(value: str | None, default: str, variable_name: str) -> str:
    """规范化 store 类型，并对非法配置快速失败。"""

    normalized = (value or default).strip().lower().replace("_", "-")
    if normalized not in {"in-memory", "redis"}:
        raise ValueError(f"{variable_name} 只支持 in-memory 或 redis，当前值为：{value}")
    return normalized


def _normalize_event_store_type(value: str | None, default: str, variable_name: str) -> str:
    """规范化事件存储类型，并对非法配置快速失败。"""

    normalized = (value or default).strip().lower().replace("_", "-")
    if normalized not in {"in-memory", "redis-stream"}:
        raise ValueError(f"{variable_name} 只支持 in-memory 或 redis-stream，当前值为：{value}")
    return normalized


def _normalize_event_publisher_type(value: str | None, default: str, variable_name: str) -> str:
    """规范化事件发布器类型，并对非法配置快速失败。

    这里用 `none` 表示显式不发布，而不是复用 `in-memory`，是为了避免把“事件总线发布”与“本地事件存储”
    两个概念混在一起。后续如果要增加 `pulsar`、`redpanda` 或内部 event gateway，也可以继续扩展该白名单。
    """

    normalized = (value or default).strip().lower().replace("_", "-")
    if normalized not in {"none", "kafka"}:
        raise ValueError(f"{variable_name} 只支持 none 或 kafka，当前值为：{value}")
    return normalized


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数配置，非法或空值回退默认值。"""

    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


def _truthy(value: str | None) -> bool:
    """读取布尔风格环境变量。

    生产配置常见写法包括 `true/1/yes/on`。其他值统一视为 False，避免拼写错误导致意外开启同步 flush。
    """

    return (value or "").strip().lower() in {"true", "1", "yes", "on"}


def _uses_redis(settings: RuntimeEventComponentSettings) -> bool:
    """判断当前配置是否使用 Redis 相关组件。"""

    return settings.event_store == "redis-stream" or "redis" in {settings.checkpoint_store, settings.outbox_store}


def _mask_redis_url(redis_url: str) -> str:
    """脱敏 Redis URL。

    Redis URL 可能是 `redis://:password@host:6379/0` 或 `redis://user:password@host:6379/0`。
    诊断接口只能暴露连接目标，不能暴露用户名/密码。
    """

    parts = urlsplit(redis_url)
    if not parts.netloc or "@" not in parts.netloc:
        return redis_url
    _, host = parts.netloc.rsplit("@", 1)
    return urlunsplit((parts.scheme, f"***:***@{host}", parts.path, parts.query, parts.fragment))
