"""Agent Runtime 实时事件 live push 调度器。

3.45 我们已经把 WebSocket 帧编排出来了，但还缺真正的“事件从哪儿来、往哪儿推”的统一出口。
本文件定义一个轻量的 live push hub：它接收新产生的结构化事件，然后把它们转成事件 envelope 帧，
放入匹配订阅会话的出站队列中。

这个 hub 不直接依赖 WebSocket 对象，也不负责网络发送。它只解决两件事：
- 哪些活跃订阅会收到这批事件；
- 这些事件应被包装成什么样的 live 帧。

这样，`RuntimeEventRecorder`、`/agent/plans`、未来 Kafka Consumer 或任务回调都可以把事件交给同一条
live push 通路，而真正的 WebSocket handler 只需要定期 drain 自己连接对应的出站队列。
"""

from __future__ import annotations

from dataclasses import asdict
from typing import Any

from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventConnectionState,
    RuntimeEventEnvelope,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent
from datasmart_ai_runtime.services.runtime_events.runtime_event_session import RuntimeEventSessionManager
from datasmart_ai_runtime.services.runtime_events.runtime_event_outbox_store import (
    InMemoryRuntimeEventOutboxStore,
    RuntimeEventOutboxStore,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_transport import RuntimeEventTransportBuilder
from datasmart_ai_runtime.services.runtime_events.runtime_event_visibility import RuntimeEventVisibilityPolicy
from datasmart_ai_runtime.services.runtime_events.runtime_event_websocket import (
    RuntimeEventWebSocketFrame,
    RuntimeEventWebSocketFrameType,
    frames_to_payloads,
)


class RuntimeEventLivePushHub:
    """实时事件 live push 中枢。

    hub 的职责是“匹配订阅并构造 frame”，而不是亲自管理 outbox 存储。这样拆开后：
    - 本地开发可以继续使用内存 outbox；
    - 多实例部署可以切换到 Redis outbox；
    - 未来如果接 Kafka/Redis Stream，只需要新增 store 实现，不需要改事件匹配规则。
    """

    def __init__(
        self,
        session_manager: RuntimeEventSessionManager,
        outbox_store: RuntimeEventOutboxStore | None = None,
        transport_builder: RuntimeEventTransportBuilder | None = None,
        visibility_policy: RuntimeEventVisibilityPolicy | None = None,
    ) -> None:
        """初始化 live push 中枢。

        `outbox_store` 默认使用内存实现，保持现有测试和本地运行零依赖。生产环境建议传入 Redis 版本，
        让 live frame 能跨 Python Runtime 实例被 WebSocket 连接 drain。

        `visibility_policy` 用于在 live frame 入队前做事件类型过滤和字段脱敏。它必须位于 live push
        层，而不是 WebSocket handler 层，因为 outbox 可能落 Redis：一旦未经脱敏的 frame 写入
        outbox，后续任何实例 drain 时都可能把敏感内容发出去。
        """

        self._session_manager = session_manager
        self._outbox_store = outbox_store or InMemoryRuntimeEventOutboxStore()
        self._transport_builder = transport_builder or RuntimeEventTransportBuilder()
        self._visibility_policy = visibility_policy or RuntimeEventVisibilityPolicy()

    def publish(self, events: tuple[AgentRuntimeEvent, ...]) -> int:
        """把一批新事件分发到活跃订阅的出站队列。

        返回值是本次总共入队的 frame 数量，便于调试和测试。

        注意：这里按“订阅”入队，而不是按“连接对象”直接发送。原因是 WebSocket 连接可能断开、
        重连或迁移到另一个实例；只要 subscriptionId 仍可恢复，新的连接就可以继续 drain outbox。
        """

        if not events:
            return 0
        enqueued = 0
        snapshots = self._session_manager.list_snapshots()
        for snapshot in snapshots:
            if snapshot.state != RuntimeEventConnectionState.ACTIVE:
                continue
            matched = self._match_events(events, snapshot.plan.request, snapshot.last_ack_sequence)
            if not matched:
                continue
            visible_events = self._visibility_policy.filter_and_mask(matched, snapshot.plan.request)
            if not visible_events:
                continue
            envelope = self._transport_builder.build_live(
                visible_events,
                attributes={
                    "subscriptionId": snapshot.plan.subscription_id,
                    "channelName": snapshot.plan.attributes.get("channelName"),
                    "pushMode": "live",
                    "visibilityPolicyApplied": True,
                },
            )
            self._outbox_store.enqueue(
                snapshot.plan.subscription_id,
                (
                    RuntimeEventWebSocketFrame(
                        frame_type=RuntimeEventWebSocketFrameType.EVENT_ENVELOPE,
                        payload=asdict(envelope),
                    ),
                )
            )
            enqueued += 1
        return enqueued

    def drain(self, subscription_id: str) -> tuple[RuntimeEventWebSocketFrame, ...]:
        """清空某个订阅的出站队列并返回 frame。"""

        return self._outbox_store.drain(subscription_id)

    def drain_payloads(self, subscription_id: str) -> tuple[dict[str, Any], ...]:
        """返回某个订阅的可发送 payload。"""

        return frames_to_payloads(self.drain(subscription_id))

    @staticmethod
    def _match_events(
        events: tuple[AgentRuntimeEvent, ...],
        request: RuntimeEventSubscriptionRequest,
        last_ack_sequence: int,
    ) -> tuple[AgentRuntimeEvent, ...]:
        """按订阅条件筛选当前批次中真正应该 live push 的事件。"""

        matched: list[AgentRuntimeEvent] = []
        for event in events:
            if event.sequence is None or event.sequence <= last_ack_sequence:
                continue
            if request.tenant_id and event.tenant_id != request.tenant_id:
                continue
            if request.project_id and event.project_id != request.project_id:
                continue
            if request.session_id and event.session_id != request.session_id:
                continue
            if request.run_id and event.run_id != request.run_id:
                continue
            if request.request_id and event.request_id != request.request_id:
                continue
            if request.event_types and event.event_type not in request.event_types:
                continue
            matched.append(event)
        return tuple(matched)
