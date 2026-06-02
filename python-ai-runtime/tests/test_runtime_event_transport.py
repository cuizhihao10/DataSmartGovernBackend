import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventAckMode,
    RuntimeEventChannel,
    RuntimeEventDeliveryMode,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_events.runtime_event_transport import RuntimeEventTransportBuilder


class RuntimeEventTransportBuilderTest(unittest.TestCase):
    def test_snapshot_envelope_uses_http_without_ack(self) -> None:
        events = self._events()

        envelope = RuntimeEventTransportBuilder().build_snapshot(events)

        self.assertEqual(RuntimeEventChannel.HTTP_RESPONSE, envelope.channel)
        self.assertEqual(RuntimeEventDeliveryMode.SNAPSHOT, envelope.delivery_mode)
        self.assertEqual(RuntimeEventAckMode.NONE, envelope.ack_mode)
        self.assertEqual("request-001", envelope.request_id)
        self.assertEqual("run-001", envelope.run_id)
        self.assertEqual("session-001", envelope.session_id)
        self.assertEqual(1, envelope.sequence_from)
        self.assertEqual(3, envelope.sequence_to)
        self.assertEqual(events, envelope.events)

    def test_live_envelope_uses_websocket_client_ack(self) -> None:
        events = self._events()

        envelope = RuntimeEventTransportBuilder().build_live(events[1:])

        self.assertEqual(RuntimeEventChannel.WEBSOCKET, envelope.channel)
        self.assertEqual(RuntimeEventDeliveryMode.LIVE, envelope.delivery_mode)
        self.assertEqual(RuntimeEventAckMode.CLIENT_ACK, envelope.ack_mode)
        self.assertTrue(envelope.has_more)
        self.assertEqual(2, envelope.sequence_from)
        self.assertEqual(3, envelope.sequence_to)

    def test_replay_envelope_only_returns_events_after_sequence(self) -> None:
        events = self._events()

        envelope = RuntimeEventTransportBuilder().build_replay(events, after_sequence=1)

        self.assertEqual(RuntimeEventDeliveryMode.REPLAY, envelope.delivery_mode)
        self.assertEqual(1, envelope.replay_from_sequence)
        self.assertEqual((2, 3), tuple(event.sequence for event in envelope.events))

    def test_kafka_audit_envelope_sets_topic_and_broker_ack(self) -> None:
        events = self._events()

        envelope = RuntimeEventTransportBuilder().build_kafka_audit(events)

        self.assertEqual(RuntimeEventChannel.KAFKA, envelope.channel)
        self.assertEqual(RuntimeEventAckMode.BROKER_ACK, envelope.ack_mode)
        self.assertEqual("datasmart.agent-runtime.events", envelope.attributes["topic"])
        self.assertEqual("run-001", envelope.attributes["partitionKey"])

    def test_subscription_plan_uses_session_channel_and_client_ack(self) -> None:
        request = RuntimeEventSubscriptionRequest(
            client_id="client-a",
            session_id="session-001",
            after_sequence=1,
        )

        plan = RuntimeEventTransportBuilder().build_subscription_plan(request)

        self.assertEqual(request, plan.request)
        self.assertEqual(RuntimeEventChannel.WEBSOCKET, plan.channel)
        self.assertEqual(RuntimeEventAckMode.CLIENT_ACK, plan.ack_mode)
        self.assertEqual("client-a", plan.attributes["clientId"])
        self.assertEqual("agent-session:session-001", plan.attributes["channelName"])

    def test_subscription_replay_filters_by_sequence_and_event_type(self) -> None:
        events = self._events()
        request = RuntimeEventSubscriptionRequest(
            client_id="client-a",
            session_id="session-001",
            after_sequence=1,
            event_types=(AgentRuntimeEventType.TOOL_PLANNED,),
        )

        envelope = RuntimeEventTransportBuilder().build_subscription_replay(events, request)

        self.assertEqual(RuntimeEventDeliveryMode.REPLAY, envelope.delivery_mode)
        self.assertEqual(RuntimeEventAckMode.CLIENT_ACK, envelope.ack_mode)
        self.assertEqual(1, envelope.replay_from_sequence)
        self.assertEqual((3,), tuple(event.sequence for event in envelope.events))
        self.assertEqual((AgentRuntimeEventType.TOOL_PLANNED,), tuple(event.event_type for event in envelope.events))

    def test_subscription_replay_filters_by_run_id(self) -> None:
        events = self._events() + self._events(run_id="run-002", session_id="session-002")
        request = RuntimeEventSubscriptionRequest(
            client_id="client-a",
            run_id="run-002",
            after_sequence=0,
        )

        envelope = RuntimeEventTransportBuilder().build_subscription_replay(events, request)

        self.assertTrue(envelope.events)
        self.assertEqual({"run-002"}, {event.run_id for event in envelope.events})

    def test_subscription_replay_applies_visibility_policy_before_envelope(self) -> None:
        """replay envelope 构建前应先执行角色可见性与字段脱敏。

        这条测试对应 Java gateway 转发 `/api/agent/events/ws` 或 HTTP replay 的真实风险：如果 replay
        只按 session/run 过滤而不按角色脱敏，项目负责人可能在断线续传时拿到 prompt、SQL 或 API Key。
        """

        events = self._events() + (
            AgentRuntimeEvent(
                event_type=AgentRuntimeEventType.MEMORY_RETRIEVED,
                stage="retrieve_memory",
                message="已读取长期记忆。",
                session_id="session-001",
                run_id="run-001",
                request_id="request-001",
                sequence=4,
                attributes={"memory": "sensitive memory"},
            ),
            AgentRuntimeEvent(
                event_type=AgentRuntimeEventType.TOOL_PLANNED,
                stage="plan_tools",
                message="已规划 SQL 工具。",
                session_id="session-001",
                run_id="run-001",
                request_id="request-001",
                sequence=5,
                attributes={"sql": "select * from customer"},
            ),
        )
        request = RuntimeEventSubscriptionRequest(
            client_id="client-a",
            roles=("PROJECT_OWNER",),
            session_id="session-001",
            after_sequence=3,
        )

        envelope = RuntimeEventTransportBuilder().build_subscription_replay(events, request)

        self.assertEqual((5,), tuple(event.sequence for event in envelope.events))
        self.assertEqual("***MASKED***", envelope.events[0].attributes["sql"])
        self.assertEqual("PROJECT", envelope.events[0].attributes["_datasmartVisibilityLevel"])

    @staticmethod
    def _events(run_id: str = "run-001", session_id: str = "session-001"):
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试事件传输",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-001",
            run_id=run_id,
            session_id=session_id,
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        recorder.record(AgentRuntimeEventType.INTENT_ANALYZED, "analyze_intent", "已分析意图。")
        recorder.record(AgentRuntimeEventType.TOOL_PLANNED, "plan_tools", "已规划工具。")
        return recorder.events()


if __name__ == "__main__":
    unittest.main()
