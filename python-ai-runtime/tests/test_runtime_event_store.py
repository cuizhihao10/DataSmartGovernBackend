import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_event_store import InMemoryRuntimeEventStore, RedisStreamRuntimeEventStore


class FakeRedisStreamClient:
    """测试用 Redis Stream fake。

    只实现 `RedisStreamRuntimeEventStore` 需要的 `xadd/xrange`，避免把 redis-py 作为单元测试依赖。
    """

    def __init__(self) -> None:
        self.entries: list[tuple[str, dict[str, str]]] = []
        self.xadd_calls: list[dict[str, object]] = []

    def xadd(
        self,
        name: str,
        fields: dict[str, str],
        id: str = "*",
        maxlen: int | None = None,
        approximate: bool = True,
    ) -> str:
        stream_id = f"1700000000000-{len(self.entries)}"
        self.entries.append((stream_id, fields))
        self.xadd_calls.append(
            {
                "name": name,
                "id": id,
                "maxlen": maxlen,
                "approximate": approximate,
            }
        )
        return stream_id

    def xrange(self, name: str, min: str = "-", max: str = "+", count: int | None = None):
        items = tuple(self.entries)
        if count is not None:
            items = items[:count]
        return items


class InMemoryRuntimeEventStoreTest(unittest.TestCase):
    def test_replay_filters_by_session_sequence_and_event_type(self) -> None:
        store = InMemoryRuntimeEventStore()
        events = self._events(session_id="session-a")
        store.append_many(events + self._events(session_id="session-b"))

        replayed = store.replay(
            RuntimeEventSubscriptionRequest(
                client_id="client-a",
                session_id="session-a",
                after_sequence=1,
                event_types=(AgentRuntimeEventType.TOOL_PLANNED,),
            )
        )

        self.assertEqual((3,), tuple(event.sequence for event in replayed))
        self.assertEqual({"session-a"}, {event.session_id for event in replayed})
        self.assertEqual({AgentRuntimeEventType.TOOL_PLANNED}, {event.event_type for event in replayed})

    def test_store_trims_oldest_events_when_capacity_is_exceeded(self) -> None:
        store = InMemoryRuntimeEventStore(max_events=2)

        store.append_many(self._events())

        self.assertEqual((2, 3), tuple(event.sequence for event in store.snapshot()))

    def test_replay_filters_by_tenant_project_and_actor_scope(self) -> None:
        store = InMemoryRuntimeEventStore()
        store.append_many(self._events(session_id="session-a"))

        replayed = store.replay(
            RuntimeEventSubscriptionRequest(
                client_id="client-a",
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                session_id="session-a",
            )
        )
        denied = store.replay(
            RuntimeEventSubscriptionRequest(
                client_id="client-a",
                tenant_id="other-tenant",
                session_id="session-a",
            )
        )

        self.assertEqual((1, 2, 3), tuple(event.sequence for event in replayed))
        self.assertEqual((), denied)

    @staticmethod
    def _events(session_id: str = "session-a"):
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试事件存储",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id=f"request-{session_id}",
            run_id=f"run-{session_id}",
            session_id=session_id,
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        recorder.record(AgentRuntimeEventType.INTENT_ANALYZED, "analyze_intent", "已分析意图。")
        recorder.record(AgentRuntimeEventType.TOOL_PLANNED, "plan_tools", "已规划工具。")
        return recorder.events()


class RedisStreamRuntimeEventStoreTest(unittest.TestCase):
    def test_redis_stream_store_replays_matching_events(self) -> None:
        redis_client = FakeRedisStreamClient()
        store = RedisStreamRuntimeEventStore(
            redis_client,
            stream_key="datasmart:test:events",
            max_stream_length=200,
            max_replay_entries=100,
        )
        store.append_many(InMemoryRuntimeEventStoreTest._events(session_id="session-a"))
        store.append_many(InMemoryRuntimeEventStoreTest._events(session_id="session-b"))

        replayed = store.replay(
            RuntimeEventSubscriptionRequest(
                client_id="client-a",
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                session_id="session-a",
                after_sequence=1,
                event_types=(AgentRuntimeEventType.TOOL_PLANNED,),
            )
        )

        self.assertEqual((3,), tuple(event.sequence for event in replayed))
        self.assertEqual({"session-a"}, {event.session_id for event in replayed})
        self.assertEqual("datasmart:test:events", redis_client.xadd_calls[0]["name"])
        self.assertEqual(200, redis_client.xadd_calls[0]["maxlen"])
        self.assertTrue(redis_client.xadd_calls[0]["approximate"])

    def test_redis_stream_store_restores_attributes_and_filters_scope(self) -> None:
        redis_client = FakeRedisStreamClient()
        store = RedisStreamRuntimeEventStore(redis_client)
        store.append_many(InMemoryRuntimeEventStoreTest._events(session_id="session-a"))

        allowed = store.replay(
            RuntimeEventSubscriptionRequest(
                client_id="client-a",
                tenant_id="tenant-a",
                session_id="session-a",
            )
        )
        denied = store.replay(
            RuntimeEventSubscriptionRequest(
                client_id="client-a",
                project_id="other-project",
                session_id="session-a",
            )
        )

        self.assertEqual((1, 2, 3), tuple(event.sequence for event in allowed))
        self.assertEqual((), denied)
        self.assertIsInstance(allowed[0].attributes, dict)


if __name__ == "__main__":
    unittest.main()
