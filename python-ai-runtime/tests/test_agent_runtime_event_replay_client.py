import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventSeverity, AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_runtime_event_replay_client import (
    AgentRuntimeEventReplayClientError,
    JavaAgentRuntimeEventReplayClient,
)


class JavaAgentRuntimeEventReplayClientTest(unittest.TestCase):
    def test_parse_java_projection_response_to_runtime_event(self) -> None:
        payload = {
            "code": 0,
            "data": {
                "appliedLimit": 200,
                "totalMatched": 1,
                "events": [
                    {
                        "identityKey": "event-tool-001",
                        "schemaVersion": "agent-tool-execution-event.v1",
                        "source": "agent-runtime",
                        "eventType": "agent.tool_execution.state_changed",
                        "stage": "tool_completed",
                        "message": "工具 datasource.metadata.read 执行成功。",
                        "severity": "info",
                        "tenantId": "10",
                        "projectId": "20",
                        "actorId": "1001",
                        "requestId": "trace-001",
                        "runId": "run-001",
                        "sessionId": "session-001",
                        "sequence": None,
                        "replaySequence": 7,
                        "createdAt": "2026-05-28T01:02:03Z",
                        "publishedAt": "2026-05-28T01:02:04Z",
                        "consumedAt": "2026-05-28T01:02:05Z",
                        "attributes": {
                            "toolCode": "datasource.metadata.read",
                            "currentState": "SUCCEEDED",
                        },
                    }
                ],
            },
        }

        events = JavaAgentRuntimeEventReplayClient.parse_platform_response(payload)

        self.assertEqual(1, len(events))
        event = events[0]
        self.assertEqual(AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED, event.event_type)
        self.assertEqual(AgentRuntimeEventSeverity.INFO, event.severity)
        self.assertEqual("run-001", event.run_id)
        self.assertEqual("session-001", event.session_id)
        self.assertEqual(7, event.sequence)
        self.assertEqual("event-tool-001", event.attributes["javaProjectionIdentityKey"])
        self.assertIsNone(event.attributes["javaProjectionProducerSequence"])
        self.assertEqual(7, event.attributes["javaProjectionReplaySequence"])
        self.assertEqual(7, event.attributes["javaProjectionCursor"])
        self.assertEqual("datasource.metadata.read", event.attributes["toolCode"])

    def test_build_query_url_and_headers_from_subscription(self) -> None:
        client = JavaAgentRuntimeEventReplayClient("http://localhost:8086", default_limit=50)
        request = RuntimeEventSubscriptionRequest(
            client_id="browser-a",
            tenant_id="10",
            project_id="20",
            actor_id="1001",
            roles=("project_owner",),
            session_id="session-001",
            run_id="run-001",
            after_sequence=3,
            source_cursors={JavaAgentRuntimeEventReplayClient.source_name: 11},
            event_types=(AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,),
        )

        url = client._build_query_url(request)
        headers = client._build_headers(request)

        self.assertIn("/agent-runtime/runtime-events/replay?", url)
        self.assertIn("clientId=browser-a", url)
        self.assertIn("tenantId=10", url)
        self.assertIn("projectId=20", url)
        self.assertIn("runId=run-001", url)
        self.assertIn("eventType=agent.tool_execution.state_changed", url)
        self.assertIn("limit=50", url)
        self.assertIn("afterSequence=11", url)
        self.assertEqual("PROJECT_OWNER", headers["X-DataSmart-Actor-Role"])
        self.assertEqual("PROJECT", headers["X-DataSmart-Data-Scope-Level"])
        self.assertEqual("20", headers["X-DataSmart-Authorized-Project-Ids"])

    def test_parse_platform_error_raises_replay_client_error(self) -> None:
        with self.assertRaises(AgentRuntimeEventReplayClientError):
            JavaAgentRuntimeEventReplayClient.parse_platform_response(
                {"code": 403, "reason": "TENANT_SCOPE_DENIED", "message": "无权访问"},
            )

    def test_build_ack_payload_uses_java_replay_sequence_not_envelope_sequence(self) -> None:
        client = JavaAgentRuntimeEventReplayClient("http://localhost:8086")
        request = RuntimeEventSubscriptionRequest(
            client_id="browser-a",
            tenant_id="10",
            project_id="20",
            actor_id="1001",
            roles=("operator",),
            session_id="session-001",
            run_id="run-001",
            after_sequence=99,
        )

        payload = client._build_ack_payload(request, source_cursor=7)

        self.assertEqual("browser-a", payload["clientId"])
        self.assertEqual("run-001", payload["runId"])
        self.assertEqual("session-001", payload["sessionId"])
        self.assertEqual(7, payload["acknowledgedReplaySequence"])
        self.assertIn("clientObservedAt", payload)

    def test_parse_java_ack_response_returns_low_risk_summary(self) -> None:
        result = JavaAgentRuntimeEventReplayClient.parse_ack_response(
            {
                "code": 0,
                "data": {
                    "clientId": "browser-a",
                    "subscriptionKey": "run:run-001",
                    "runId": "run-001",
                    "sessionId": None,
                    "acknowledgedReplaySequence": 7,
                    "previousAcknowledgedReplaySequence": 3,
                    "advanced": True,
                    "reason": "ACK_ADVANCED",
                },
            }
        )

        self.assertEqual(JavaAgentRuntimeEventReplayClient.source_name, result["source"])
        self.assertEqual(7, result["acknowledgedReplaySequence"])
        self.assertEqual(3, result["previousAcknowledgedReplaySequence"])
        self.assertTrue(result["advanced"])
        self.assertEqual("ACK_ADVANCED", result["reason"])


if __name__ == "__main__":
    unittest.main()
