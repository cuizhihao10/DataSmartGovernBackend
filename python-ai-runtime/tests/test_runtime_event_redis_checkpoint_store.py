import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import RuntimeEventConnectionState, RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_events.runtime_event_checkpoint_store import RedisRuntimeEventCheckpointStore
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_events.runtime_event_session import RuntimeEventSessionManager
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import InMemoryRuntimeEventStore


class FakeRedisClient:
    def __init__(self) -> None:
        self.storage: dict[str, str] = {}
        self.ttl: dict[str, int] = {}

    def set(self, key: str, value: str, ex: int | None = None) -> None:
        self.storage[key] = value
        if ex is not None:
            self.ttl[key] = ex

    def get(self, key: str):
        return self.storage.get(key)

    def delete(self, key: str) -> None:
        self.storage.pop(key, None)
        self.ttl.pop(key, None)


class RedisRuntimeEventCheckpointStoreTest(unittest.TestCase):
    def test_redis_checkpoint_can_restore_session(self) -> None:
        event_store = InMemoryRuntimeEventStore()
        event_store.append_many(self._events())
        redis_client = FakeRedisClient()
        checkpoint_store = RedisRuntimeEventCheckpointStore(redis_client, ttl_seconds=3600)

        first_manager = RuntimeEventSessionManager(
            event_store=event_store,
            checkpoint_store=checkpoint_store,
        )
        snapshot = first_manager.subscribe(
            RuntimeEventSubscriptionRequest(
                client_id="browser-a",
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                session_id="session-redis",
                source_cursors={"java-agent-runtime-event-projection": 9},
            )
        )
        first_manager.acknowledge(snapshot.plan.subscription_id, 2)

        restored_store = RedisRuntimeEventCheckpointStore(redis_client, ttl_seconds=3600)
        restored_manager = RuntimeEventSessionManager(
            event_store=event_store,
            checkpoint_store=restored_store,
        )
        restored = restored_manager.reconnect(snapshot.plan.subscription_id)

        self.assertEqual(RuntimeEventConnectionState.ACTIVE, restored.state)
        self.assertEqual(2, restored.last_ack_sequence)
        self.assertEqual((3,), tuple(event.sequence for event in restored.replay_envelope.events))
        self.assertEqual(9, restored.plan.request.source_cursors["java-agent-runtime-event-projection"])
        self.assertEqual(3600, redis_client.ttl[next(iter(redis_client.ttl))])

    @staticmethod
    def _events():
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试 redis checkpoint",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-redis",
            run_id="run-redis",
            session_id="session-redis",
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        recorder.record(AgentRuntimeEventType.INTENT_ANALYZED, "analyze_intent", "已分析意图。")
        recorder.record(AgentRuntimeEventType.TOOL_PLANNED, "plan_tools", "已规划工具。")
        return recorder.events()


if __name__ == "__main__":
    unittest.main()
