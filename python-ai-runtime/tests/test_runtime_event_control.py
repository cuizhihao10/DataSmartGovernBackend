import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_event_control_response
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventConnectionState,
    RuntimeEventControlMessageType,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_event_session import RuntimeEventSessionManager
from datasmart_ai_runtime.services.runtime_event_store import InMemoryRuntimeEventStore


class RuntimeEventControlHandlerTest(unittest.TestCase):
    def test_subscribe_control_message_returns_replay_and_subscription_id(self) -> None:
        store = InMemoryRuntimeEventStore()
        store.append_many(self._events())
        manager = RuntimeEventSessionManager(event_store=store)

        response = build_event_control_response(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "sessionId": "session-control",
                    "afterSequence": 1,
                    "includeSnapshot": True,
                },
            },
            manager,
        )

        subscription = response["subscription"]
        self.assertTrue(response["accepted"])
        self.assertEqual(RuntimeEventControlMessageType.SUBSCRIBE, response["messageType"])
        self.assertEqual(RuntimeEventConnectionState.ACTIVE, subscription["state"])
        self.assertTrue(subscription["subscriptionId"])
        self.assertEqual(1, subscription["lastAckSequence"])
        self.assertEqual((2, 3), tuple(event["sequence"] for event in subscription["replayEnvelope"]["events"]))

    def test_ack_and_heartbeat_control_messages_update_same_subscription(self) -> None:
        manager = RuntimeEventSessionManager()
        subscribed = build_event_control_response(
            {
                "messageType": "subscribe",
                "subscription": {"clientId": "browser-a", "sessionId": "session-control"},
            },
            manager,
        )
        subscription_id = subscribed["subscription"]["subscriptionId"]

        acked = build_event_control_response(
            {"type": "ack", "subscriptionId": subscription_id, "lastSequence": 5},
            manager,
        )
        heartbeat = build_event_control_response(
            {"type": "heartbeat", "subscriptionId": subscription_id, "lastSequence": 6},
            manager,
        )

        self.assertEqual(5, acked["subscription"]["lastAckSequence"])
        self.assertEqual(6, heartbeat["subscription"]["lastAckSequence"])
        self.assertEqual(RuntimeEventConnectionState.ACTIVE, heartbeat["subscription"]["state"])

    def test_reconnect_control_message_uses_after_sequence_for_replay(self) -> None:
        store = InMemoryRuntimeEventStore()
        store.append_many(self._events())
        manager = RuntimeEventSessionManager(event_store=store)
        subscribed = build_event_control_response(
            {
                "type": "subscribe",
                "subscription": {"clientId": "browser-a", "sessionId": "session-control"},
            },
            manager,
        )
        subscription_id = subscribed["subscription"]["subscriptionId"]

        reconnected = build_event_control_response(
            {"type": "reconnect", "subscriptionId": subscription_id, "afterSequence": 2},
            manager,
        )

        self.assertEqual(2, reconnected["subscription"]["lastAckSequence"])
        self.assertEqual((3,), tuple(event["sequence"] for event in reconnected["subscription"]["replayEnvelope"]["events"]))

    def test_unsubscribe_control_message_closes_subscription(self) -> None:
        manager = RuntimeEventSessionManager()
        subscribed = build_event_control_response(
            {
                "type": "subscribe",
                "subscription": {"clientId": "browser-a", "sessionId": "session-control"},
            },
            manager,
        )
        subscription_id = subscribed["subscription"]["subscriptionId"]

        closed = build_event_control_response(
            {"type": "unsubscribe", "subscriptionId": subscription_id, "reason": "user_left_page"},
            manager,
        )

        self.assertEqual(RuntimeEventConnectionState.CLOSED, closed["subscription"]["state"])
        self.assertEqual("user_left_page", closed["subscription"]["closeReason"])

    def test_subscription_without_required_scope_is_rejected(self) -> None:
        manager = RuntimeEventSessionManager()

        response = build_event_control_response(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "tenantId": "tenant-b",
                    "sessionId": "session-control",
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

        self.assertFalse(response["accepted"])
        self.assertEqual("EVENT_CONTROL_NOT_AUTHORIZED", response["error"]["code"])
        self.assertIn("未授权", response["error"]["message"])

    @staticmethod
    def _events():
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试实时事件控制消息",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-control",
            run_id="run-control",
            session_id="session-control",
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        recorder.record(AgentRuntimeEventType.INTENT_ANALYZED, "analyze_intent", "已分析意图。")
        recorder.record(AgentRuntimeEventType.TOOL_PLANNED, "plan_tools", "已规划工具。")
        return recorder.events()


if __name__ == "__main__":
    unittest.main()
