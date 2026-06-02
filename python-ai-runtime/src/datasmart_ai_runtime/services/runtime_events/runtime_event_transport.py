"""Agent Runtime 事件传输 envelope 构建服务。

当前项目还没有真正启动 WebSocket Server、Kafka Producer 或审计库写入器，但协议设计不能等到
最后才补。`RuntimeEventTransportBuilder` 的职责是把请求级 `AgentRuntimeEvent` 列表包装成统一
envelope：同步 HTTP 可以返回 snapshot，WebSocket 可以发送 live/replay，Kafka 可以发送带 broker
ack 语义的消息。

这个服务只做“打包”，不做真实网络发送。这样可以先用单元测试稳定协议语义，后续无论传输落在
Java gateway 还是 Python API，都能复用同样的数据结构和排序规则。
"""

from __future__ import annotations

from uuid import uuid4

from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventAckMode,
    RuntimeEventChannel,
    RuntimeEventDeliveryMode,
    RuntimeEventEnvelope,
    RuntimeEventSubscriptionPlan,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent
from datasmart_ai_runtime.services.runtime_events.runtime_event_visibility import RuntimeEventVisibilityPolicy


class RuntimeEventTransportBuilder:
    """把运行时事件构造成不同投递模式的 envelope。

    方法设计对应三类真实产品场景：
    - `build_snapshot(...)`：同步接口返回当前全部事件；
    - `build_live(...)`：实时推送新事件，适合 WebSocket；
    - `build_replay(...)`：客户端断线后从某个 sequence 之后继续取事件；
    - `build_kafka_audit(...)`：写入 Kafka 审计 topic，供 Java 控制面或审计服务消费。
    """

    def __init__(self, visibility_policy: RuntimeEventVisibilityPolicy | None = None) -> None:
        """初始化事件传输构建器。

        `visibility_policy` 默认使用 Python Runtime 内置策略。之所以把它注入到 transport builder，
        是因为 replay envelope 的事件过滤和脱敏必须发生在“构建可发送 envelope”之前；如果交给
        FastAPI handler 或 WebSocket adapter 临时处理，很容易出现某个入口忘记脱敏的问题。
        """

        self._visibility_policy = visibility_policy or RuntimeEventVisibilityPolicy()

    def build_snapshot(
        self,
        events: tuple[AgentRuntimeEvent, ...],
        channel: RuntimeEventChannel = RuntimeEventChannel.HTTP_RESPONSE,
        attributes: dict[str, object] | None = None,
    ) -> RuntimeEventEnvelope:
        """构建同步快照 envelope。

        快照一般不需要客户端 ack，因为它跟随 HTTP 响应一起返回；如果响应失败，调用方会整体重试。
        """

        return self._build(
            events=events,
            channel=channel,
            delivery_mode=RuntimeEventDeliveryMode.SNAPSHOT,
            ack_mode=RuntimeEventAckMode.NONE,
            attributes=attributes,
        )

    def build_live(
        self,
        events: tuple[AgentRuntimeEvent, ...],
        channel: RuntimeEventChannel = RuntimeEventChannel.WEBSOCKET,
        attributes: dict[str, object] | None = None,
    ) -> RuntimeEventEnvelope:
        """构建实时增量 envelope。

        WebSocket 场景建议使用 `CLIENT_ACK`：前端收到事件后回传最后处理到的 sequence，服务端即可在
        断线重连时从该 sequence 之后重放，避免用户看不到中间进度。
        """

        return self._build(
            events=events,
            channel=channel,
            delivery_mode=RuntimeEventDeliveryMode.LIVE,
            ack_mode=RuntimeEventAckMode.CLIENT_ACK,
            has_more=True,
            attributes=attributes,
        )

    def build_replay(
        self,
        events: tuple[AgentRuntimeEvent, ...],
        after_sequence: int,
        channel: RuntimeEventChannel = RuntimeEventChannel.WEBSOCKET,
        attributes: dict[str, object] | None = None,
    ) -> RuntimeEventEnvelope:
        """构建断线续传 envelope。

        `after_sequence` 表示客户端已经确认处理到的最后序号，返回值只包含更大的 sequence。这里不
        直接假设事件一定连续，因为生产环境中某些低价值事件可能被采样或压缩；前端仍应按 sequence
        排序展示。
        """

        replay_events = tuple(
            event
            for event in events
            if event.sequence is not None and event.sequence > after_sequence
        )
        return self._build(
            events=replay_events,
            channel=channel,
            delivery_mode=RuntimeEventDeliveryMode.REPLAY,
            ack_mode=RuntimeEventAckMode.CLIENT_ACK,
            replay_from_sequence=after_sequence,
            attributes=attributes,
        )

    def build_subscription_plan(
        self,
        request: RuntimeEventSubscriptionRequest,
        attributes: dict[str, object] | None = None,
    ) -> RuntimeEventSubscriptionPlan:
        """根据订阅请求生成服务端订阅计划。

        当前计划只补充 subscriptionId、默认 WebSocket 通道和 CLIENT_ACK 策略。未来这里可以继续接入
        鉴权、限流、租户隔离、最大回放窗口、事件类型白名单等网关策略。
        """

        merged_attributes = dict(attributes or {})
        merged_attributes.setdefault("clientId", request.client_id)
        if request.tenant_id:
            merged_attributes.setdefault("tenantId", request.tenant_id)
        if request.project_id:
            merged_attributes.setdefault("projectId", request.project_id)
        if request.actor_id:
            merged_attributes.setdefault("actorId", request.actor_id)
        if request.roles:
            merged_attributes.setdefault("roles", request.roles)
        if request.session_id:
            merged_attributes.setdefault("channelName", f"agent-session:{request.session_id}")
        elif request.run_id:
            merged_attributes.setdefault("channelName", f"agent-run:{request.run_id}")
        elif request.request_id:
            merged_attributes.setdefault("channelName", f"agent-request:{request.request_id}")
        else:
            merged_attributes.setdefault("channelName", f"agent-client:{request.client_id}")
        return RuntimeEventSubscriptionPlan(
            subscription_id=str(uuid4()),
            request=request,
            attributes=merged_attributes,
        )

    def build_subscription_replay(
        self,
        events: tuple[AgentRuntimeEvent, ...],
        subscription: RuntimeEventSubscriptionRequest | RuntimeEventSubscriptionPlan,
    ) -> RuntimeEventEnvelope:
        """根据订阅条件构建 WebSocket replay envelope。

        该方法先按 requestId/runId/sessionId/eventTypes/afterSequence 过滤事件，再复用 replay envelope
        语义返回结果。真实网关可以在用户重连后调用它，做到“从我上次确认的 sequence 之后继续发”。
        """

        plan = (
            subscription
            if isinstance(subscription, RuntimeEventSubscriptionPlan)
            else self.build_subscription_plan(subscription)
        )
        request = plan.request
        replay_events = tuple(event for event in events if self._matches_subscription(event, request))
        visible_events = self._visibility_policy.filter_and_mask(replay_events, request)
        return self._build(
            events=visible_events,
            channel=plan.channel,
            delivery_mode=RuntimeEventDeliveryMode.REPLAY,
            ack_mode=plan.ack_mode,
            replay_from_sequence=request.after_sequence,
            has_more=False,
            attributes=plan.attributes,
        )

    def build_kafka_audit(
        self,
        events: tuple[AgentRuntimeEvent, ...],
        topic: str = "datasmart.agent-runtime.events",
        attributes: dict[str, object] | None = None,
    ) -> RuntimeEventEnvelope:
        """构建 Kafka 审计 envelope。

        Kafka 场景需要 broker ack，且通常需要 topic 和 partitionKey。这里先把 topic 放进 attributes，
        partitionKey 默认建议使用 runId/requestId，由真正的 Kafka Producer 适配器再决定。
        """

        merged_attributes = dict(attributes or {})
        merged_attributes.setdefault("topic", topic)
        identity = self._identity_from_events(events)
        merged_attributes.setdefault("partitionKey", identity.get("runId") or identity.get("requestId"))
        return self._build(
            events=events,
            channel=RuntimeEventChannel.KAFKA,
            delivery_mode=RuntimeEventDeliveryMode.LIVE,
            ack_mode=RuntimeEventAckMode.BROKER_ACK,
            has_more=True,
            attributes=merged_attributes,
        )

    @staticmethod
    def _matches_subscription(
        event: AgentRuntimeEvent,
        request: RuntimeEventSubscriptionRequest,
    ) -> bool:
        """判断事件是否匹配订阅请求。

        多个 ID 条件是“同时满足”关系：如果客户端同时传了 sessionId 与 runId，就表示只订阅该会话中
        指定运行的事件。`afterSequence` 用于断线续传，只返回客户端尚未确认处理过的事件。
        """

        if event.sequence is None or event.sequence <= request.after_sequence:
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

    def _build(
        self,
        events: tuple[AgentRuntimeEvent, ...],
        channel: RuntimeEventChannel,
        delivery_mode: RuntimeEventDeliveryMode,
        ack_mode: RuntimeEventAckMode,
        replay_from_sequence: int | None = None,
        has_more: bool = False,
        attributes: dict[str, object] | None = None,
    ) -> RuntimeEventEnvelope:
        """构建 envelope 的统一入口。

        统一入口负责填充 envelopeId、关联 ID 和 sequence 范围，避免每种传输模式都重复实现这些细节。
        """

        identity = self._identity_from_events(events)
        sequence_values = tuple(event.sequence for event in events if event.sequence is not None)
        return RuntimeEventEnvelope(
            envelope_id=str(uuid4()),
            channel=channel,
            delivery_mode=delivery_mode,
            ack_mode=ack_mode,
            events=events,
            request_id=identity.get("requestId"),
            run_id=identity.get("runId"),
            session_id=identity.get("sessionId"),
            sequence_from=min(sequence_values) if sequence_values else None,
            sequence_to=max(sequence_values) if sequence_values else None,
            replay_from_sequence=replay_from_sequence,
            has_more=has_more,
            attributes=dict(attributes or {}),
        )

    @staticmethod
    def _identity_from_events(events: tuple[AgentRuntimeEvent, ...]) -> dict[str, str | None]:
        """从事件集合中提取关联 ID。

        正常情况下同一个 envelope 内的事件来自同一 request/run/session。这里取第一个事件作为来源，
        是为了保持服务简单；后续如果要混合多请求事件，应在更外层按 requestId 分组后再构建 envelope。
        """

        if not events:
            return {"requestId": None, "runId": None, "sessionId": None}
        first = events[0]
        return {
            "requestId": first.request_id,
            "runId": first.run_id,
            "sessionId": first.session_id,
        }
