"""Python Runtime API 事件回放响应测试。

该文件从 API bootstrap 综合测试中拆出，专门验证订阅过滤和外部 Java 投影游标。拆分只改变测试
组织方式，不改变生产 API；独立场景文件也让断线续传、source cursor 和合成 sequence 的失败更容易定位。
"""

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator, build_event_replay_response
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventAckMode,
    RuntimeEventChannel,
    RuntimeEventDeliveryMode,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


class FakeReplaySource:
    """模拟 Java agent-runtime 投影源，只返回低敏工具状态事件。"""

    source_name = "java-agent-runtime-event-projection"

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        return (
            AgentRuntimeEvent(
                event_type=AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,
                stage="tool_completed",
                message="Java 控制面工具事件",
                severity=AgentRuntimeEventSeverity.INFO,
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                run_id=request.run_id,
                session_id=request.session_id,
                sequence=3,
                attributes={"toolCode": "datasource.metadata.read"},
            ),
        )


class ApiEventReplayTest(unittest.TestCase):
    """验证 API 回放 envelope 的筛选、ack 和跨源游标语义。"""

    def test_event_replay_response_filters_events_by_subscription(self) -> None:
        plan = build_default_orchestrator().plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请先分析这个 MySQL 数据源的表结构",
                variables={"datasourceId": "ds-001", "sessionId": "session-a"},
            )
        )
        subscription = RuntimeEventSubscriptionRequest(
            client_id="client-a",
            session_id="session-a",
            after_sequence=1,
            event_types=(AgentRuntimeEventType.TOOL_PLANNED,),
        )

        envelope = build_event_replay_response(subscription, plan.runtime_events)["eventEnvelope"]

        self.assertEqual(RuntimeEventChannel.WEBSOCKET, envelope["channel"])
        self.assertEqual(RuntimeEventDeliveryMode.REPLAY, envelope["delivery_mode"])
        self.assertEqual(RuntimeEventAckMode.CLIENT_ACK, envelope["ack_mode"])
        self.assertEqual(1, envelope["replay_from_sequence"])
        self.assertEqual((AgentRuntimeEventType.TOOL_PLANNED,), tuple(event["event_type"] for event in envelope["events"]))
        self.assertTrue(all(event["session_id"] == "session-a" for event in envelope["events"]))

    def test_event_replay_response_exposes_external_source_cursors(self) -> None:
        subscription = RuntimeEventSubscriptionRequest(
            client_id="client-a",
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            roles=("operator",),
            session_id="session-a",
            run_id="run-a",
            after_sequence=3,
            source_cursors={"java-agent-runtime-event-projection": 2},
        )

        envelope = build_event_replay_response(
            subscription,
            external_replay_sources=(FakeReplaySource(),),
        )["eventEnvelope"]

        self.assertEqual(3, envelope["attributes"]["sourceCursors"]["java-agent-runtime-event-projection"])
        self.assertEqual(3, envelope["events"][0]["attributes"]["_datasmartOriginalSequence"])
        self.assertTrue(envelope["events"][0]["attributes"]["_datasmartSyntheticReplaySequence"])


if __name__ == "__main__":
    unittest.main()
