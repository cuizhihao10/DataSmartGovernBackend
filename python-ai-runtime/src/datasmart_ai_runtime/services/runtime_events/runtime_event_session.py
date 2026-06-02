"""Agent Runtime 实时事件订阅会话状态机。

`RuntimeEventTransportBuilder` 负责把事件包装成 envelope，`RuntimeEventStore` 负责保存和回放事件；
本文件负责补上二者之间的“连接生命周期”语义。真实商业产品里的前端事件流不能只做“打开
WebSocket 然后不断推消息”，还必须考虑：

- 客户端刷新、断网、切换标签页后如何从上次 ack 的 sequence 继续接收；
- 服务端如何根据心跳判断连接是否还可靠，避免长期占用资源；
- 用户离开页面时如何主动 unsubscribe，并保留审计可追踪的关闭原因；
- 订阅建立时是否先回放历史事件，再进入实时增量推送；
- 后续 Java Gateway、FastAPI WebSocket、Redis Stream/Kafka 适配器如何共享同一套状态语义。

因此这里先实现一个不绑定具体 WebSocket 框架的内存会话管理器。它不是生产级连接池，但它把
subscribe、ack、heartbeat、unsubscribe、reconnect 这些核心业务状态流转固定下来，后续替换成
Redis 会话表或网关集群共享状态时，外层协议不需要推翻。
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from typing import Protocol

from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventConnectionState,
    RuntimeEventEnvelope,
    RuntimeEventSubscriptionPlan,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_checkpoint_store import (
    RuntimeEventCheckpointStore,
    RuntimeEventSubscriptionCheckpoint,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import RuntimeEventStore
from datasmart_ai_runtime.services.runtime_events.runtime_event_transport import RuntimeEventTransportBuilder
from datasmart_ai_runtime.services.runtime_events.runtime_event_replay_source import (
    RuntimeEventAckSink,
    RuntimeEventReplayCoordinator,
    RuntimeEventReplaySource,
)


class RuntimeEventSessionError(ValueError):
    """事件订阅会话错误。

    当前先使用 `ValueError` 子类表达调用方传入了不存在或已关闭的 subscriptionId。后续接入 HTTP 或
    WebSocket API 时，可以把它映射成统一错误码，例如 `EVENT_SUBSCRIPTION_NOT_FOUND`、
    `EVENT_SUBSCRIPTION_CLOSED`、`EVENT_ACK_SEQUENCE_INVALID` 等。
    """


@dataclass(frozen=True)
class RuntimeEventSessionSnapshot:
    """实时事件订阅会话快照。

    该快照是服务端对外暴露的稳定视图，避免调用方直接拿到内部可变状态。

    字段说明：
    - `plan`：订阅计划，包含 subscriptionId、订阅条件、通道和 ack 策略；
    - `state`：当前连接状态，前端可据此展示连接中、已连接、心跳超时或已关闭；
    - `last_ack_sequence`：客户端确认处理到的最后事件序号，是断线续传的核心依据；
    - `connected_at`：订阅首次建立时间，用于审计和连接寿命统计；
    - `last_heartbeat_at`：最近一次心跳或 ack 时间，用于判断连接是否过期；
    - `updated_at`：最近一次状态变化时间；
    - `replay_envelope`：订阅建立或重连时需要补发的历史事件 envelope，没有补发需求时为空；
    - `close_reason`：关闭原因，例如用户主动离开、心跳超时清理、权限撤销等。
    """

    plan: RuntimeEventSubscriptionPlan
    state: RuntimeEventConnectionState
    last_ack_sequence: int
    connected_at: datetime
    last_heartbeat_at: datetime
    updated_at: datetime
    replay_envelope: RuntimeEventEnvelope | None = None
    close_reason: str | None = None


@dataclass
class _RuntimeEventSessionRecord:
    """会话管理器内部使用的可变记录。

    对外返回不可变 snapshot，对内使用可变 record，是为了让状态流转更清晰：ack、heartbeat、
    reconnect 都是在同一个订阅会话上更新少数字段，不需要每次重建整张会话表。
    """

    snapshot: RuntimeEventSessionSnapshot


class RuntimeEventSessionClock(Protocol):
    """可替换时钟协议。

    生产代码使用 UTC 当前时间；单元测试可以注入固定时钟，稳定验证心跳超时、更新时间等行为。
    """

    def __call__(self) -> datetime:
        """返回当前 UTC 时间。"""


class RuntimeEventSessionManager:
    """管理 Agent Runtime 实时事件订阅会话。

    这个管理器刻意只依赖三个抽象：
    - `RuntimeEventTransportBuilder`：负责生成订阅计划和 replay envelope；
    - `RuntimeEventStore`：负责按订阅条件查询可回放事件；
    - `clock`：负责提供当前时间，方便测试时间相关状态。

    它不直接依赖 FastAPI、Starlette WebSocket、Redis、Kafka 或 Java Gateway，因此可以作为协议级
    状态机被多个入口复用。真实网关接入时，WebSocket handler 只需要把收到的控制消息转成这里的
    subscribe/ack/heartbeat/reconnect/unsubscribe 方法调用即可。
    """

    def __init__(
        self,
        event_store: RuntimeEventStore | None = None,
        checkpoint_store: RuntimeEventCheckpointStore | None = None,
        transport_builder: RuntimeEventTransportBuilder | None = None,
        external_replay_sources: tuple[RuntimeEventReplaySource, ...] = (),
        external_ack_sinks: tuple[RuntimeEventAckSink, ...] | None = None,
        heartbeat_timeout_seconds: int = 45,
        clock: RuntimeEventSessionClock | None = None,
    ) -> None:
        """初始化订阅会话管理器。

        `heartbeat_timeout_seconds` 表示多久没有收到 heartbeat/ack 后认为连接进入 `STALE`。真实生产
        环境中该值应结合前端心跳频率、网关超时、移动端网络抖动和服务端资源成本一起配置。
        """

        self._event_store = event_store
        self._checkpoint_store = checkpoint_store
        self._transport_builder = transport_builder or RuntimeEventTransportBuilder()
        self._replay_coordinator = RuntimeEventReplayCoordinator(external_replay_sources)
        self._external_ack_sinks = tuple(
            external_ack_sinks
            if external_ack_sinks is not None
            else tuple(source for source in external_replay_sources if hasattr(source, "acknowledge"))
        )
        self._heartbeat_timeout = timedelta(seconds=max(1, heartbeat_timeout_seconds))
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._sessions: dict[str, _RuntimeEventSessionRecord] = {}

    def subscribe(self, request: RuntimeEventSubscriptionRequest) -> RuntimeEventSessionSnapshot:
        """建立订阅会话。

        业务流程：
        1. 根据订阅请求构建服务端订阅计划，生成 subscriptionId 和 channelName；
        2. 如果 `include_snapshot=True` 且配置了事件存储，则按 afterSequence 查询可回放事件；
        3. 会话进入 `ACTIVE`，并把 `last_ack_sequence` 初始化为请求中的 afterSequence；
        4. 返回快照给 WebSocket handler，handler 可先发送 replayEnvelope，再进入 live 推送。
        """

        now = self._now()
        plan = self._transport_builder.build_subscription_plan(request)
        replay_envelope = self._build_replay_envelope(plan) if request.include_snapshot else None
        snapshot = RuntimeEventSessionSnapshot(
            plan=plan,
            state=RuntimeEventConnectionState.ACTIVE,
            last_ack_sequence=max(0, request.after_sequence),
            connected_at=now,
            last_heartbeat_at=now,
            updated_at=now,
            replay_envelope=replay_envelope,
        )
        self._sessions[plan.subscription_id] = _RuntimeEventSessionRecord(snapshot=snapshot)
        self._persist_snapshot(snapshot)
        return snapshot

    def acknowledge(
        self,
        subscription_id: str,
        last_sequence: int,
        source_cursors: dict[str, int] | None = None,
    ) -> RuntimeEventSessionSnapshot:
        """记录客户端 ack。

        ack 表示客户端已经展示、处理或持久化到某个 sequence。服务端保存最大 ack 值即可；如果客户端
        因网络重试发来更小的旧 ack，这里会忽略旧值，避免把断线续传起点倒退。

        `source_cursors` 是外部事件源自己的 cursor，例如 Java 控制面的 replaySequence。Python 本地
        ack 与外部 ack 分开处理：本地 ack 一定按最大值推进；外部 ack 失败只写入诊断属性，不回滚本地
        连接状态，避免 Java 短暂不可用导致前端 WebSocket ack 反复失败。
        """

        record = self._require_open_record(subscription_id)
        snapshot = record.snapshot
        acknowledged = max(snapshot.last_ack_sequence, max(0, last_sequence))
        plan = self._plan_with_external_ack_attributes(snapshot.plan, source_cursors)
        record.snapshot = replace(
            snapshot,
            plan=plan,
            state=RuntimeEventConnectionState.ACTIVE,
            last_ack_sequence=acknowledged,
            last_heartbeat_at=self._now(),
            updated_at=self._now(),
            replay_envelope=None,
        )
        self._persist_snapshot(record.snapshot)
        return record.snapshot

    def heartbeat(
        self,
        subscription_id: str,
        last_sequence: int | None = None,
        source_cursors: dict[str, int] | None = None,
    ) -> RuntimeEventSessionSnapshot:
        """处理客户端心跳。

        心跳主要用于证明连接仍然存活。为了减少前端消息数量，心跳也允许携带 lastSequence；如果携带，
        服务端会顺手推进 ack，避免前端必须同时发送 heartbeat 和 ack 两类消息。若心跳携带外部
        `sourceCursors`，也会顺手回写 Java ack cursor，减少实时连接上的控制消息数量。
        """

        record = self._require_open_record(subscription_id)
        snapshot = record.snapshot
        acknowledged = snapshot.last_ack_sequence
        if last_sequence is not None:
            acknowledged = max(acknowledged, max(0, last_sequence))
        now = self._now()
        plan = self._plan_with_external_ack_attributes(snapshot.plan, source_cursors)
        record.snapshot = replace(
            snapshot,
            plan=plan,
            state=RuntimeEventConnectionState.ACTIVE,
            last_ack_sequence=acknowledged,
            last_heartbeat_at=now,
            updated_at=now,
            replay_envelope=None,
        )
        self._persist_snapshot(record.snapshot)
        return record.snapshot

    def reconnect(
        self,
        subscription_id: str,
        after_sequence: int | None = None,
    ) -> RuntimeEventSessionSnapshot:
        """让已有订阅会话重新进入 ACTIVE，并按续传起点生成 replay envelope。

        重连时服务端优先使用调用方显式传入的 afterSequence；如果没有传入，就使用本会话记录的
        `last_ack_sequence`。这样前端既可以完全信任本地保存的最后处理序号，也可以让服务端根据最近
        一次 ack 自动续传。
        """

        record = self._require_open_record(subscription_id, allow_stale=True)
        snapshot = record.snapshot
        replay_from = snapshot.last_ack_sequence if after_sequence is None else max(0, after_sequence)
        request = replace(snapshot.plan.request, after_sequence=replay_from)
        plan = replace(snapshot.plan, request=request)
        now = self._now()
        record.snapshot = replace(
            snapshot,
            plan=plan,
            state=RuntimeEventConnectionState.ACTIVE,
            last_ack_sequence=replay_from,
            last_heartbeat_at=now,
            updated_at=now,
            replay_envelope=self._build_replay_envelope(plan),
            close_reason=None,
        )
        self._persist_snapshot(record.snapshot)
        return record.snapshot

    def mark_stale_sessions(self) -> tuple[RuntimeEventSessionSnapshot, ...]:
        """扫描并标记心跳超时的会话。

        该方法不直接删除会话，而是先进入 `STALE`。这样真实网关可以给客户端一个短暂重连窗口：
        如果用户网络只是抖动，仍可携带 subscriptionId 或 afterSequence 恢复；如果长期不恢复，再由
        上层定时清理任务关闭或删除。
        """

        now = self._now()
        stale: list[RuntimeEventSessionSnapshot] = []
        for record in self._sessions.values():
            snapshot = record.snapshot
            if snapshot.state != RuntimeEventConnectionState.ACTIVE:
                continue
            if now - snapshot.last_heartbeat_at <= self._heartbeat_timeout:
                continue
            record.snapshot = replace(
                snapshot,
                state=RuntimeEventConnectionState.STALE,
                updated_at=now,
                replay_envelope=None,
            )
            self._persist_snapshot(record.snapshot)
            stale.append(record.snapshot)
        return tuple(stale)

    def unsubscribe(self, subscription_id: str, reason: str = "client_unsubscribe") -> RuntimeEventSessionSnapshot:
        """关闭订阅会话。

        取消订阅是终态操作：关闭后不再接受 ack、heartbeat 或 reconnect。生产环境可以在这里补充
        审计事件、释放网关连接资源、减少租户订阅配额计数。
        """

        record = self._require_record(subscription_id)
        snapshot = record.snapshot
        now = self._now()
        record.snapshot = replace(
            snapshot,
            state=RuntimeEventConnectionState.CLOSED,
            updated_at=now,
            replay_envelope=None,
            close_reason=reason,
        )
        self._persist_snapshot(record.snapshot)
        return record.snapshot

    def snapshot(self, subscription_id: str) -> RuntimeEventSessionSnapshot:
        """查询单个订阅会话快照，主要用于测试、管理接口或调试面板。"""

        try:
            return self._require_record(subscription_id).snapshot
        except RuntimeEventSessionError:
            restored = self._restore_record(subscription_id)
            if restored is None:
                raise
            return restored.snapshot

    def list_snapshots(self) -> tuple[RuntimeEventSessionSnapshot, ...]:
        """返回当前所有订阅会话快照。

        真实管理端后续可在此基础上做分页、租户过滤、只看异常连接、按会话或客户端搜索等能力。
        """

        return tuple(record.snapshot for record in self._sessions.values())

    def _build_replay_envelope(self, plan: RuntimeEventSubscriptionPlan) -> RuntimeEventEnvelope:
        """根据订阅计划构建 replay envelope。

        如果尚未配置事件存储，则返回空 replay envelope。这样 WebSocket handler 不需要区分“没有历史
        事件”和“暂时没有接入 store”，协议上都表现为一个合法的 replay 响应。
        """

        local_events = self._event_store.replay(plan.request) if self._event_store else ()
        replay_collection = self._replay_coordinator.collect(local_events, plan.request)
        envelope = self._transport_builder.build_subscription_replay(replay_collection.events, plan)
        envelope_attributes = dict(envelope.attributes)
        if replay_collection.source_cursors:
            # sourceCursors 是 WebSocket 断线重连的源级游标回执。afterSequence 只描述前端已经展示到
            # 哪个 envelope sequence；sourceCursors 则描述 Java runtime-event 投影、未来 Redis Stream、
            # Kafka compacted log 等外部 source 各自已经读到哪里。把它放进 replay envelope，前端或
            # gateway SDK 下次 reconnect 时就可以原样带回，避免外部 source 重复扫描旧事件。
            envelope_attributes["sourceCursors"] = replay_collection.source_cursors
        if replay_collection.external_errors:
            # 下面用 replace 生成 envelope 副本，在 attributes 中标记外部 replay source 的失败摘要。
            # 这样订阅仍然 accepted，前端或诊断工具也能知道 Java 投影曾经查询失败，
            # 而不是误以为确实没有事件。
            envelope_attributes["externalReplayErrors"] = replay_collection.external_errors
        if envelope_attributes == dict(envelope.attributes):
            return envelope
        return replace(envelope, attributes=envelope_attributes)

    def _plan_with_external_ack_attributes(
        self,
        plan: RuntimeEventSubscriptionPlan,
        source_cursors: dict[str, int] | None,
    ) -> RuntimeEventSubscriptionPlan:
        """按 sourceCursors 回写外部 ack，并把低风险结果写入订阅属性。

        订阅属性是控制响应里已经存在的扩展位置。这里会清理上一次 ack 的结果，避免一次失败诊断在
        后续成功 ack 后继续残留，误导前端或运维工具。
        """

        reserved_keys = {"externalAckResults", "externalAckErrors"}
        attributes = {key: value for key, value in plan.attributes.items() if key not in reserved_keys}
        ack_attributes = self._ack_external_sources(plan.request, source_cursors or {})
        if not ack_attributes:
            return replace(plan, attributes=attributes) if attributes != plan.attributes else plan
        attributes.update(ack_attributes)
        return replace(plan, attributes=attributes)

    def _ack_external_sources(
        self,
        request: RuntimeEventSubscriptionRequest,
        source_cursors: dict[str, int],
    ) -> dict[str, tuple[dict[str, object], ...]]:
        """把客户端确认的外部 source cursor 回写给对应 source。

        当前最重要的实现是 Java agent-runtime。后续如果 Redis Stream、Kafka compacted topic 或审计库
        也需要显式 ack，只要实现 `RuntimeEventAckSink` 即可接入这里。
        """

        if not self._external_ack_sinks or not source_cursors:
            return {}
        results: list[dict[str, object]] = []
        errors: list[dict[str, object]] = []
        for sink in self._external_ack_sinks:
            source_cursor = source_cursors.get(sink.source_name)
            if source_cursor is None:
                continue
            try:
                results.append(dict(sink.acknowledge(request, int(source_cursor))))
            except Exception as exc:  # pragma: no cover - 具体外部客户端异常由 source 单测覆盖
                errors.append({"source": sink.source_name, "message": str(exc)})
        attributes: dict[str, tuple[dict[str, object], ...]] = {}
        if results:
            attributes["externalAckResults"] = tuple(results)
        if errors:
            attributes["externalAckErrors"] = tuple(errors)
        return attributes

    def _require_record(self, subscription_id: str) -> _RuntimeEventSessionRecord:
        """按 subscriptionId 获取会话记录，不存在时抛出领域错误。"""

        try:
            return self._sessions[subscription_id]
        except KeyError as exc:
            restored = self._restore_record(subscription_id)
            if restored is not None:
                return restored
            raise RuntimeEventSessionError(f"事件订阅不存在：{subscription_id}") from exc

    def _require_open_record(
        self,
        subscription_id: str,
        allow_stale: bool = False,
    ) -> _RuntimeEventSessionRecord:
        """获取未关闭会话记录。

        ack/heartbeat 只允许 ACTIVE 会话；reconnect 可以允许 STALE 会话恢复。CLOSED 是终态，必须拒绝。
        """

        record = self._require_record(subscription_id)
        state = record.snapshot.state
        if state == RuntimeEventConnectionState.CLOSED:
            raise RuntimeEventSessionError(f"事件订阅已关闭：{subscription_id}")
        if state == RuntimeEventConnectionState.STALE and not allow_stale:
            raise RuntimeEventSessionError(f"事件订阅心跳已超时，请先重连：{subscription_id}")
        return record

    def _now(self) -> datetime:
        """返回带时区的当前时间。

        如果外部注入的时钟返回 naive datetime，这里统一补成 UTC，避免后续比较时出现时区异常。
        """

        current = self._clock()
        if current.tzinfo is None:
            return current.replace(tzinfo=timezone.utc)
        return current

    def _persist_snapshot(self, snapshot: RuntimeEventSessionSnapshot) -> None:
        """把当前会话快照持久化到 checkpoint 存储。"""

        if self._checkpoint_store is None:
            return
        self._checkpoint_store.save(self._snapshot_to_checkpoint(snapshot))

    def _restore_record(self, subscription_id: str) -> _RuntimeEventSessionRecord | None:
        """从 checkpoint 存储恢复会话记录。

        这个恢复路径是为重启和多实例准备的：如果当前进程内没有该 subscriptionId 的会话记录，但
        checkpoint 存储里有最后已知状态，就可以重新构建会话并继续 replay/ack/reconnect 流程。
        """

        if self._checkpoint_store is None:
            return None
        checkpoint = self._checkpoint_store.load(subscription_id)
        if checkpoint is None:
            return None
        request = replace(checkpoint.request, after_sequence=checkpoint.last_ack_sequence)
        plan = self._transport_builder.build_subscription_plan(request)
        plan = replace(plan, subscription_id=checkpoint.subscription_id)
        replay_envelope = self._build_replay_envelope(plan) if request.include_snapshot else None
        snapshot = RuntimeEventSessionSnapshot(
            plan=plan,
            state=checkpoint.state,
            last_ack_sequence=checkpoint.last_ack_sequence,
            connected_at=checkpoint.connected_at,
            last_heartbeat_at=checkpoint.last_heartbeat_at,
            updated_at=checkpoint.updated_at,
            replay_envelope=replay_envelope,
            close_reason=checkpoint.close_reason,
        )
        record = _RuntimeEventSessionRecord(snapshot=snapshot)
        self._sessions[subscription_id] = record
        return record

    @staticmethod
    def _snapshot_to_checkpoint(snapshot: RuntimeEventSessionSnapshot) -> RuntimeEventSubscriptionCheckpoint:
        """把完整会话快照压缩为 checkpoint。"""

        return RuntimeEventSubscriptionCheckpoint(
            subscription_id=snapshot.plan.subscription_id,
            request=snapshot.plan.request,
            state=snapshot.state,
            last_ack_sequence=snapshot.last_ack_sequence,
            connected_at=snapshot.connected_at,
            last_heartbeat_at=snapshot.last_heartbeat_at,
            updated_at=snapshot.updated_at,
            close_reason=snapshot.close_reason,
        )
