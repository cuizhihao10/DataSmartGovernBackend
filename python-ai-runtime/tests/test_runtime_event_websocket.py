import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_event_websocket_payloads
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import RuntimeEventConnectionState
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_event_live_push import RuntimeEventLivePushHub
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_event_session import RuntimeEventSessionManager
from datasmart_ai_runtime.services.runtime_event_store import InMemoryRuntimeEventStore
from datasmart_ai_runtime.services.runtime_event_websocket import RuntimeEventWebSocketConnectionAdapter


class RuntimeEventWebSocketAdapterTest(unittest.TestCase):
    def test_subscribe_message_is_split_into_control_and_event_frames(self) -> None:
        store = InMemoryRuntimeEventStore()
        store.append_many(self._events())
        manager = RuntimeEventSessionManager(event_store=store)

        payloads = build_event_websocket_payloads(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "sessionId": "session-websocket",
                    "afterSequence": 1,
                    "includeSnapshot": True,
                },
                "accessContext": {
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                },
            },
            manager,
        )

        self.assertEqual(2, len(payloads))
        self.assertEqual("control_response", payloads[0]["frameType"])
        self.assertEqual("event_envelope", payloads[1]["frameType"])
        self.assertEqual("session-websocket", payloads[0]["payload"]["subscription"]["sessionId"])
        self.assertEqual((2, 3), tuple(event["sequence"] for event in payloads[1]["payload"]["events"]))

    def test_unauthorized_message_becomes_error_frame(self) -> None:
        manager = RuntimeEventSessionManager()

        payloads = build_event_websocket_payloads(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "tenantId": "tenant-b",
                    "projectId": "project-a",
                    "sessionId": "session-websocket",
                },
                "accessContext": {
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                },
            },
            manager,
        )

        self.assertEqual(1, len(payloads))
        self.assertEqual("error", payloads[0]["frameType"])
        self.assertEqual("EVENT_CONTROL_NOT_AUTHORIZED", payloads[0]["payload"]["error"]["code"])

    def test_connection_adapter_subscribe_then_drains_live_events(self) -> None:
        store = InMemoryRuntimeEventStore()
        manager = RuntimeEventSessionManager(event_store=store)
        live_hub = RuntimeEventLivePushHub(session_manager=manager)
        connection = RuntimeEventWebSocketConnectionAdapter(manager, live_push_hub=live_hub)

        subscribe_payloads = connection.handle_message(self._subscribe_message(include_snapshot=False))

        self.assertEqual(1, len(subscribe_payloads))
        self.assertEqual("control_response", subscribe_payloads[0]["frameType"])
        self.assertIsNotNone(connection.subscription_id)

        published = live_hub.publish(self._events())
        live_payloads = connection.drain_live_payloads()

        self.assertEqual(1, published)
        self.assertEqual(1, len(live_payloads))
        self.assertEqual("event_envelope", live_payloads[0]["frameType"])
        self.assertEqual((1, 2, 3), tuple(event["sequence"] for event in live_payloads[0]["payload"]["events"]))

    def test_connection_adapter_close_unsubscribes_and_is_idempotent(self) -> None:
        manager = RuntimeEventSessionManager()
        connection = RuntimeEventWebSocketConnectionAdapter(manager)
        connection.handle_message(self._subscribe_message(include_snapshot=False))
        subscription_id = connection.subscription_id

        close_payloads = connection.close(reason="browser_tab_closed")
        second_close_payloads = connection.close(reason="duplicate_close")

        self.assertTrue(connection.closed)
        self.assertIsNone(connection.subscription_id)
        self.assertEqual(1, len(close_payloads))
        self.assertEqual("control_response", close_payloads[0]["frameType"])
        self.assertEqual(RuntimeEventConnectionState.CLOSED, manager.snapshot(subscription_id).state)
        self.assertEqual("browser_tab_closed", manager.snapshot(subscription_id).close_reason)
        self.assertEqual((), second_close_payloads)

    def test_connection_adapter_ack_and_heartbeat_advance_last_sequence(self) -> None:
        manager = RuntimeEventSessionManager()
        connection = RuntimeEventWebSocketConnectionAdapter(manager)
        connection.handle_message(self._subscribe_message(include_snapshot=False))
        subscription_id = connection.subscription_id

        connection.handle_message({"type": "ack", "subscriptionId": subscription_id, "lastSequence": 2})
        connection.handle_message({"type": "heartbeat", "subscriptionId": subscription_id, "lastSequence": 3})

        self.assertEqual(3, manager.snapshot(subscription_id).last_ack_sequence)

    def test_connection_adapter_reconnect_returns_replay_frame(self) -> None:
        store = InMemoryRuntimeEventStore()
        store.append_many(self._events())
        manager = RuntimeEventSessionManager(event_store=store)
        connection = RuntimeEventWebSocketConnectionAdapter(manager)
        connection.handle_message(self._subscribe_message(include_snapshot=False))
        subscription_id = connection.subscription_id

        reconnect_payloads = connection.handle_message(
            {
                "type": "reconnect",
                "subscriptionId": subscription_id,
                "afterSequence": 1,
            }
        )

        self.assertEqual(2, len(reconnect_payloads))
        self.assertEqual("control_response", reconnect_payloads[0]["frameType"])
        self.assertEqual("event_envelope", reconnect_payloads[1]["frameType"])
        self.assertEqual((2, 3), tuple(event["sequence"] for event in reconnect_payloads[1]["payload"]["events"]))

    @staticmethod
    def _subscribe_message(include_snapshot: bool = True) -> dict[str, object]:
        return {
            "type": "subscribe",
            "subscription": {
                "clientId": "browser-a",
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "actorId": "user-a",
                "sessionId": "session-websocket",
                "afterSequence": 0,
                "includeSnapshot": include_snapshot,
            },
            "accessContext": {
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "actorId": "user-a",
                "roles": ["operator"],
            },
        }

    @staticmethod
    def _events():
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试 websocket 帧编排",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-websocket",
            run_id="run-websocket",
            session_id="session-websocket",
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        recorder.record(AgentRuntimeEventType.INTENT_ANALYZED, "analyze_intent", "已分析意图。")
        recorder.record(AgentRuntimeEventType.TOOL_PLANNED, "plan_tools", "已规划工具。")
        return recorder.events()


if __name__ == "__main__":
    unittest.main()
