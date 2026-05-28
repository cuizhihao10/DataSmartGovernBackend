import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import RuntimeEventConnectionState, RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_event_checkpoint_store import InMemoryRuntimeEventCheckpointStore
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_event_session import RuntimeEventSessionManager
from datasmart_ai_runtime.services.runtime_event_store import InMemoryRuntimeEventStore


class RuntimeEventCheckpointStoreTest(unittest.TestCase):
    def test_reconnect_can_restore_subscription_after_manager_restart(self) -> None:
        event_store = InMemoryRuntimeEventStore()
        event_store.append_many(self._events())
        checkpoint_store = InMemoryRuntimeEventCheckpointStore()

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
                session_id="session-checkpoint",
            )
        )
        first_manager.acknowledge(snapshot.plan.subscription_id, 2)

        restarted_manager = RuntimeEventSessionManager(
            event_store=event_store,
            checkpoint_store=checkpoint_store,
        )
        reconnected = restarted_manager.reconnect(snapshot.plan.subscription_id)

        self.assertEqual(RuntimeEventConnectionState.ACTIVE, reconnected.state)
        self.assertEqual(2, reconnected.last_ack_sequence)
        self.assertEqual((3,), tuple(event.sequence for event in reconnected.replay_envelope.events))
        self.assertEqual(snapshot.plan.subscription_id, reconnected.plan.subscription_id)

    @staticmethod
    def _events():
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试 checkpoint 恢复",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-checkpoint",
            run_id="run-checkpoint",
            session_id="session-checkpoint",
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        recorder.record(AgentRuntimeEventType.INTENT_ANALYZED, "analyze_intent", "已分析意图。")
        recorder.record(AgentRuntimeEventType.TOOL_PLANNED, "plan_tools", "已规划工具。")
        return recorder.events()


if __name__ == "__main__":
    unittest.main()
