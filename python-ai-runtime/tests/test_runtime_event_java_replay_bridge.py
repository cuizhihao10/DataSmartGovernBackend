import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_event_control_response
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_event_session import RuntimeEventSessionManager
from datasmart_ai_runtime.services.runtime_event_store import InMemoryRuntimeEventStore
from datasmart_ai_runtime.services.runtime_event_websocket import RuntimeEventWebSocketConnectionAdapter


class RuntimeEventJavaReplayBridgeTest(unittest.TestCase):
    def test_subscribe_replay_merges_python_local_events_and_java_tool_events(self) -> None:
        store = InMemoryRuntimeEventStore()
        store.append_many(self._python_events())
        java_source = FakeJavaReplaySource()
        manager = RuntimeEventSessionManager(
            event_store=store,
            external_replay_sources=(java_source,),
        )

        response = build_event_control_response(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                    "sessionId": "session-bridge",
                    "runId": "run-bridge",
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

        events = response["subscription"]["replayEnvelope"]["events"]
        event_types = tuple(event["event_type"] for event in events)
        java_event = events[-1]

        self.assertTrue(response["accepted"])
        self.assertIn(AgentRuntimeEventType.CONTEXT_COLLECTED, event_types)
        self.assertIn(AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED, event_types)
        self.assertEqual("java-agent-runtime-event-projection", java_event["attributes"]["_datasmartReplaySource"])
        self.assertTrue(java_event["attributes"]["_datasmartSyntheticReplaySequence"])
        self.assertGreater(java_event["sequence"], 0)
        self.assertEqual("run-bridge", java_source.requests[0].run_id)

    def test_subscribe_replay_returns_source_cursors_for_next_reconnect(self) -> None:
        java_source = FakeJavaReplaySource(sequence=7)
        manager = RuntimeEventSessionManager(external_replay_sources=(java_source,))

        response = build_event_control_response(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                    "sessionId": "session-bridge",
                    "runId": "run-bridge",
                    "afterSequence": 7,
                    "sourceCursors": {"java-agent-runtime-event-projection": 6},
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

        envelope = response["subscription"]["replayEnvelope"]

        self.assertTrue(response["accepted"])
        self.assertEqual(
            6,
            java_source.requests[0].source_cursors["java-agent-runtime-event-projection"],
        )
        self.assertEqual(
            7,
            envelope["attributes"]["sourceCursors"]["java-agent-runtime-event-projection"],
        )
        self.assertEqual(7, envelope["events"][0]["attributes"]["_datasmartOriginalSequence"])

    def test_external_replay_failure_does_not_reject_subscription(self) -> None:
        manager = RuntimeEventSessionManager(external_replay_sources=(FailingReplaySource(),))

        response = build_event_control_response(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "tenantId": "tenant-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                    "sessionId": "session-bridge",
                    "includeSnapshot": True,
                },
                "accessContext": {
                    "tenantId": "tenant-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                },
            },
            manager,
        )

        envelope = response["subscription"]["replayEnvelope"]

        self.assertTrue(response["accepted"])
        self.assertEqual((), tuple(envelope["events"]))
        self.assertEqual("broken-java-source", envelope["attributes"]["externalReplayErrors"][0]["source"])

    def test_websocket_adapter_returns_java_replay_event_frame(self) -> None:
        manager = RuntimeEventSessionManager(external_replay_sources=(FakeJavaReplaySource(),))
        connection = RuntimeEventWebSocketConnectionAdapter(manager)

        payloads = connection.handle_message(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                    "sessionId": "session-bridge",
                    "runId": "run-bridge",
                    "includeSnapshot": True,
                },
                "accessContext": {
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                },
            }
        )

        self.assertEqual(2, len(payloads))
        self.assertEqual("event_envelope", payloads[1]["frameType"])
        self.assertEqual(
            AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,
            payloads[1]["payload"]["events"][0]["event_type"],
        )

    def test_websocket_ack_writes_java_source_cursor_when_present(self) -> None:
        java_source = FakeJavaReplaySource(sequence=7)
        manager = RuntimeEventSessionManager(external_replay_sources=(java_source,))
        connection = RuntimeEventWebSocketConnectionAdapter(manager)
        subscribe_payloads = connection.handle_message(
            {
                "type": "subscribe",
                "subscription": {
                    "clientId": "browser-a",
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                    "sessionId": "session-bridge",
                    "runId": "run-bridge",
                    "includeSnapshot": True,
                },
                "accessContext": {
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "actorId": "user-a",
                    "roles": ["operator"],
                },
            }
        )
        subscription_id = subscribe_payloads[0]["payload"]["subscription"]["subscriptionId"]

        ack_payloads = connection.handle_message(
            {
                "type": "ack",
                "subscriptionId": subscription_id,
                "lastSequence": 1,
                "sourceCursors": {"java-agent-runtime-event-projection": 7},
            }
        )

        self.assertEqual(("browser-a", "run-bridge", "session-bridge", 7), java_source.acks[0])
        self.assertEqual(
            "ACK_ADVANCED",
            ack_payloads[0]["payload"]["subscription"]["attributes"]["externalAckResults"][0]["reason"],
        )

    @staticmethod
    def _python_events() -> tuple[AgentRuntimeEvent, ...]:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试 Java 工具事件 replay 桥接",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-bridge",
            run_id="run-bridge",
            session_id="session-bridge",
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        return recorder.events()


class FakeJavaReplaySource:
    source_name = "java-agent-runtime-event-projection"

    def __init__(self, sequence: int | None = None) -> None:
        self.requests: list[RuntimeEventSubscriptionRequest] = []
        self.acks: list[tuple[str, str | None, str | None, int]] = []
        self.sequence = sequence

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        self.requests.append(request)
        return (
            AgentRuntimeEvent(
                event_type=AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,
                stage="tool_completed",
                message="Java 控制面确认工具执行成功。",
                severity=AgentRuntimeEventSeverity.INFO,
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                request_id=request.request_id,
                run_id=request.run_id,
                session_id=request.session_id,
                sequence=self.sequence,
                attributes={
                    "javaProjectionIdentityKey": "event-tool-001",
                    "toolCode": "datasource.metadata.read",
                    "currentState": "SUCCEEDED",
                },
            ),
        )

    def acknowledge(self, request: RuntimeEventSubscriptionRequest, source_cursor: int) -> dict[str, object]:
        self.acks.append((request.client_id, request.run_id, request.session_id, source_cursor))
        return {
            "source": self.source_name,
            "acknowledgedReplaySequence": source_cursor,
            "advanced": True,
            "reason": "ACK_ADVANCED",
        }


class FailingReplaySource:
    source_name = "broken-java-source"

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        raise RuntimeError("Java 投影查询超时")


if __name__ == "__main__":
    unittest.main()
