import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventSeverity, AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder


class RuntimeEventRecorderTest(unittest.TestCase):
    def test_recorder_fills_request_context_and_sequence(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="测试事件收集",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-001",
            run_id="run-001",
            session_id="session-001",
        )

        first = recorder.record(
            AgentRuntimeEventType.CONTEXT_COLLECTED,
            "build_context",
            "已收集上下文。",
        )
        second = recorder.record(
            AgentRuntimeEventType.APPROVAL_WAITING,
            "wait_human_approval",
            "等待审批。",
            severity=AgentRuntimeEventSeverity.AUDIT,
            attributes={"toolName": "task.create.draft"},
        )

        self.assertEqual(1, first.sequence)
        self.assertEqual(2, second.sequence)
        self.assertEqual("tenant-a", second.tenant_id)
        self.assertEqual("project-a", second.project_id)
        self.assertEqual("operator-a", second.actor_id)
        self.assertEqual("request-001", second.request_id)
        self.assertEqual("run-001", second.run_id)
        self.assertEqual("session-001", second.session_id)
        self.assertEqual({"toolName": "task.create.draft"}, second.attributes)
        self.assertEqual((first, second), recorder.events())

    def test_recorder_pushes_each_event_to_optional_realtime_sink(self) -> None:
        """实时旁路应逐条收到事件，且旁路异常不能中断主规划。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="验证实时事件旁路",
        )
        delivered = []
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-live-001",
            event_sink=delivered.append,
        )

        event = recorder.record(
            AgentRuntimeEventType.AGENT_PLAN_STARTED,
            "receive_goal",
            "开始规划。",
        )

        self.assertEqual([event], delivered)

        failing = RuntimeEventRecorder(
            request=request,
            request_id="request-live-002",
            event_sink=lambda _: (_ for _ in ()).throw(RuntimeError("client disconnected")),
        )
        recorded = failing.record(
            AgentRuntimeEventType.AGENT_PLAN_COMPLETED,
            "complete_agent_plan",
            "规划完成。",
        )
        self.assertEqual((recorded,), failing.events())


if __name__ == "__main__":
    unittest.main()
