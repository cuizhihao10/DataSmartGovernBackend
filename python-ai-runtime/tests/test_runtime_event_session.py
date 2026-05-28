import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventConnectionState,
    RuntimeEventDeliveryMode,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_event_session import (
    RuntimeEventSessionError,
    RuntimeEventSessionManager,
)
from datasmart_ai_runtime.services.runtime_event_store import InMemoryRuntimeEventStore


class MutableClock:
    """测试用可变时钟。

    事件会话状态机依赖心跳时间判断 stale，如果测试直接使用真实时间，就会出现不稳定等待。
    这里用可控时钟让测试可以精确推进时间，并验证生产逻辑中的超时计算。
    """

    def __init__(self) -> None:
        self.current = datetime(2026, 5, 23, 9, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        return self.current

    def advance(self, seconds: int) -> None:
        self.current = self.current + timedelta(seconds=seconds)


class RuntimeEventSessionManagerTest(unittest.TestCase):
    def test_subscribe_creates_active_session_and_replay_envelope(self) -> None:
        store = InMemoryRuntimeEventStore()
        store.append_many(self._events())
        manager = RuntimeEventSessionManager(event_store=store, clock=MutableClock())

        snapshot = manager.subscribe(
            RuntimeEventSubscriptionRequest(
                client_id="browser-tab-a",
                session_id="session-a",
                after_sequence=1,
                include_snapshot=True,
            )
        )

        self.assertEqual(RuntimeEventConnectionState.ACTIVE, snapshot.state)
        self.assertEqual(1, snapshot.last_ack_sequence)
        self.assertIsNotNone(snapshot.replay_envelope)
        self.assertEqual(RuntimeEventDeliveryMode.REPLAY, snapshot.replay_envelope.delivery_mode)
        self.assertEqual((2, 3), tuple(event.sequence for event in snapshot.replay_envelope.events))

    def test_ack_advances_sequence_and_ignores_older_ack(self) -> None:
        manager = RuntimeEventSessionManager(clock=MutableClock())
        snapshot = manager.subscribe(RuntimeEventSubscriptionRequest(client_id="client-a", session_id="session-a"))

        acked = manager.acknowledge(snapshot.plan.subscription_id, 8)
        old_ack = manager.acknowledge(snapshot.plan.subscription_id, 3)

        self.assertEqual(8, acked.last_ack_sequence)
        self.assertEqual(8, old_ack.last_ack_sequence)

    def test_heartbeat_can_update_ack_sequence(self) -> None:
        manager = RuntimeEventSessionManager(clock=MutableClock())
        snapshot = manager.subscribe(RuntimeEventSubscriptionRequest(client_id="client-a", session_id="session-a"))

        heartbeat = manager.heartbeat(snapshot.plan.subscription_id, last_sequence=4)

        self.assertEqual(RuntimeEventConnectionState.ACTIVE, heartbeat.state)
        self.assertEqual(4, heartbeat.last_ack_sequence)

    def test_stale_session_must_reconnect_before_heartbeat(self) -> None:
        clock = MutableClock()
        manager = RuntimeEventSessionManager(heartbeat_timeout_seconds=10, clock=clock)
        snapshot = manager.subscribe(RuntimeEventSubscriptionRequest(client_id="client-a", session_id="session-a"))

        clock.advance(11)
        stale_sessions = manager.mark_stale_sessions()

        self.assertEqual((snapshot.plan.subscription_id,), tuple(item.plan.subscription_id for item in stale_sessions))
        self.assertEqual(RuntimeEventConnectionState.STALE, manager.snapshot(snapshot.plan.subscription_id).state)
        with self.assertRaises(RuntimeEventSessionError):
            manager.heartbeat(snapshot.plan.subscription_id)

    def test_reconnect_from_stale_session_replays_after_last_ack(self) -> None:
        clock = MutableClock()
        store = InMemoryRuntimeEventStore()
        store.append_many(self._events())
        manager = RuntimeEventSessionManager(event_store=store, heartbeat_timeout_seconds=10, clock=clock)
        snapshot = manager.subscribe(RuntimeEventSubscriptionRequest(client_id="client-a", session_id="session-a"))

        manager.acknowledge(snapshot.plan.subscription_id, 2)
        clock.advance(11)
        manager.mark_stale_sessions()
        reconnected = manager.reconnect(snapshot.plan.subscription_id)

        self.assertEqual(RuntimeEventConnectionState.ACTIVE, reconnected.state)
        self.assertEqual(2, reconnected.last_ack_sequence)
        self.assertIsNotNone(reconnected.replay_envelope)
        self.assertEqual((3,), tuple(event.sequence for event in reconnected.replay_envelope.events))

    def test_unsubscribe_closes_session_and_rejects_later_ack(self) -> None:
        manager = RuntimeEventSessionManager(clock=MutableClock())
        snapshot = manager.subscribe(RuntimeEventSubscriptionRequest(client_id="client-a", session_id="session-a"))

        closed = manager.unsubscribe(snapshot.plan.subscription_id, reason="user_left_page")

        self.assertEqual(RuntimeEventConnectionState.CLOSED, closed.state)
        self.assertEqual("user_left_page", closed.close_reason)
        with self.assertRaises(RuntimeEventSessionError):
            manager.acknowledge(snapshot.plan.subscription_id, 1)

    @staticmethod
    def _events():
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试实时事件订阅生命周期",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-a",
            run_id="run-a",
            session_id="session-a",
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        recorder.record(AgentRuntimeEventType.INTENT_ANALYZED, "analyze_intent", "已分析意图。")
        recorder.record(AgentRuntimeEventType.TOOL_PLANNED, "plan_tools", "已规划工具。")
        return recorder.events()


if __name__ == "__main__":
    unittest.main()
