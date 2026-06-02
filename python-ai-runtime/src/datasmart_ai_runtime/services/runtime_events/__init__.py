"""Agent Runtime Event 能力包。

Runtime Event 是 Python AI Runtime 面向“类 Codex/Claude Code 执行体验”的核心基础设施之一。它不是普通日志：
普通日志主要服务开发者排障，而 Runtime Event 需要同时服务前端时间线、智能网关 WebSocket、断线回放、
工具执行审计、控制面 loop 反馈和未来的运营诊断。

本包把原先平铺在 `services/` 目录中的 `runtime_event_*` 文件集中到一个能力域中，便于学习和维护：

- `runtime_event_store` 保存事件事实，可替换为内存或 Redis Stream；
- `runtime_event_session` 管理订阅、ack、heartbeat、reconnect 等会话状态；
- `runtime_event_control` 处理 HTTP/WebSocket 共用的控制消息状态机；
- `runtime_event_checkpoint_store` 保存客户端消费进度，支持断线恢复；
- `runtime_event_outbox_store` 与 `runtime_event_live_push` 支撑实时推送和补偿读取；
- `runtime_event_replay_source` 负责把本地与 Java 控制面 replay source 聚合成统一回放视图；
- `runtime_event_publisher`、`runtime_event_transport` 和 `runtime_event_websocket` 负责事件发布与协议帧适配；
- `runtime_event_visibility` 与 `runtime_event_authorization` 负责低敏展示、订阅边界和最小访问控制。

目录治理原则：
本阶段只把 runtime event 能力域从大 `services` 目录中迁出，并保留历史文件名。这样既能让目录层次更像成熟
平台项目，也能避免一次性重命名类、函数和测试夹具造成无意义回归。后续如果继续增强智能网关事件流，可以在
本包内部再拆成 `stores/`、`control/`、`transport/`、`visibility/` 等子包。
"""

from datasmart_ai_runtime.services.runtime_events.runtime_event_authorization import (
    RuntimeEventAccessContext,
    RuntimeEventAuthorizationDecision,
    RuntimeEventSubscriptionAuthorizer,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_checkpoint_store import (
    InMemoryRuntimeEventCheckpointStore,
    RedisRuntimeEventCheckpointStore,
    RuntimeEventCheckpointStore,
    RuntimeEventSubscriptionCheckpoint,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_components import (
    RuntimeEventComponentSettings,
    RuntimeEventRuntimeComponents,
    build_runtime_event_components,
    runtime_event_component_diagnostics,
    runtime_event_settings_from_env,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_control import (
    RuntimeEventControlHandler,
    RuntimeEventControlMessageError,
    control_message_from_payload,
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
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_events.runtime_event_replay_source import (
    RuntimeEventAckSink,
    RuntimeEventReplayCollection,
    RuntimeEventReplayCoordinator,
    RuntimeEventReplaySource,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_session import (
    RuntimeEventSessionError,
    RuntimeEventSessionManager,
    RuntimeEventSessionSnapshot,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import (
    InMemoryRuntimeEventStore,
    RedisStreamRuntimeEventStore,
    RuntimeEventStore,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_transport import RuntimeEventTransportBuilder
from datasmart_ai_runtime.services.runtime_events.runtime_event_visibility import (
    RuntimeEventVisibilityLevel,
    RuntimeEventVisibilityPolicy,
    RuntimeEventVisibilityStats,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_websocket import (
    RuntimeEventWebSocketConnectionAdapter,
    RuntimeEventWebSocketFrame,
    RuntimeEventWebSocketFrameType,
    build_websocket_frames_from_control_response,
    frames_to_payloads,
)

__all__ = [
    "InMemoryRuntimeEventCheckpointStore",
    "InMemoryRuntimeEventOutboxStore",
    "InMemoryRuntimeEventStore",
    "KafkaRuntimeEventPublisher",
    "NoopRuntimeEventPublisher",
    "RedisRuntimeEventCheckpointStore",
    "RedisRuntimeEventOutboxStore",
    "RedisStreamRuntimeEventStore",
    "RuntimeEventAccessContext",
    "RuntimeEventAckSink",
    "RuntimeEventAuthorizationDecision",
    "RuntimeEventCheckpointStore",
    "RuntimeEventComponentSettings",
    "RuntimeEventControlHandler",
    "RuntimeEventControlMessageError",
    "RuntimeEventLivePushHub",
    "RuntimeEventOutboxStore",
    "RuntimeEventPublisher",
    "RuntimeEventRecorder",
    "RuntimeEventReplayCollection",
    "RuntimeEventReplayCoordinator",
    "RuntimeEventReplaySource",
    "RuntimeEventRuntimeComponents",
    "RuntimeEventSessionError",
    "RuntimeEventSessionManager",
    "RuntimeEventSessionSnapshot",
    "RuntimeEventStore",
    "RuntimeEventSubscriptionAuthorizer",
    "RuntimeEventSubscriptionCheckpoint",
    "RuntimeEventTransportBuilder",
    "RuntimeEventVisibilityLevel",
    "RuntimeEventVisibilityPolicy",
    "RuntimeEventVisibilityStats",
    "RuntimeEventWebSocketConnectionAdapter",
    "RuntimeEventWebSocketFrame",
    "RuntimeEventWebSocketFrameType",
    "build_default_kafka_producer",
    "build_runtime_event_components",
    "build_websocket_frames_from_control_response",
    "control_message_from_payload",
    "frames_to_payloads",
    "runtime_event_component_diagnostics",
    "runtime_event_settings_from_env",
]
