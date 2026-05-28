import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventSeverity, AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder


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


if __name__ == "__main__":
    unittest.main()
