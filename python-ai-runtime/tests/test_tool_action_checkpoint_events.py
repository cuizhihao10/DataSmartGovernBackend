import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.tools import (
    ToolActionCheckpointMetrics,
    build_tool_action_checkpoint_runtime_event,
)


class ToolActionCheckpointEventsTest(unittest.TestCase):
    """checkpoint query/resume-preview 事件与指标测试。"""

    def test_query_event_uses_locator_hash_without_leaking_raw_values(self) -> None:
        """checkpoint 查询事件应保留关联 hash，但不保存原始 locator 或敏感字段。"""

        event = build_tool_action_checkpoint_runtime_event(
            {
                "schemaVersion": "datasmart.python-ai-runtime.tool-action-execution-checkpoint-query.v1",
                "previewOnly": True,
                "queryBoundary": "LOW_SENSITIVE_CHECKPOINT_QUERY_ONLY",
                "route": {"method": "POST", "path": "/agent/tool-actions/checkpoints/query"},
                "checkpointCount": 1,
                "queryPolicy": {"includeGraphRun": True, "scopeFilterApplied": True, "globalScanAllowed": False},
                "checkpoints": (
                    {
                        "checkpointId": "tool-action-checkpoint:secret-checkpoint",
                        "threadId": "thread-secret",
                        "sequence": 7,
                        "statusCounts": {"WAITING_APPROVAL_FACT": 1},
                        "graphRunSummary": {
                            "prompt": "raw prompt should not leak",
                            "sql": "select * from hidden_table",
                        },
                    },
                ),
                "accessIssues": (),
                "productionReadiness": {"currentStore": "CONFIGURABLE_IN_MEMORY_OR_REDIS_SHORT_LIVED"},
            },
            operation="query",
            request_payload={
                "checkpointId": "tool-action-checkpoint:secret-checkpoint",
                "threadId": "thread-secret",
                "context": {
                    "tenantId": "tenant-event",
                    "projectId": "project-event",
                    "actorId": "actor-event",
                    "requestId": "request-event",
                    "runId": "run-event",
                    "sessionId": "session-event",
                },
                "prompt": "raw prompt should not leak",
                "sql": "select * from hidden_table",
            },
        )
        serialized = str(event.attributes)

        self.assertEqual(AgentRuntimeEventType.TOOL_ACTION_CHECKPOINT_QUERIED, event.event_type)
        self.assertEqual("tenant-event", event.tenant_id)
        self.assertEqual("found", event.attributes["metricResult"])
        self.assertTrue(event.attributes["checkpointLocatorProvided"])
        self.assertTrue(event.attributes["threadLocatorProvided"])
        self.assertEqual("WAITING_APPROVAL_FACT", next(iter(event.attributes["checkpointStatusCounts"])))
        self.assertNotIn("tool-action-checkpoint:secret-checkpoint", serialized)
        self.assertNotIn("thread-secret", serialized)
        self.assertNotIn("raw prompt should not leak", serialized)
        self.assertNotIn("hidden_table", serialized)

    def test_resume_event_and_metrics_use_low_cardinality_labels(self) -> None:
        """resume-preview 事件应可转换为低基数 Prometheus 指标。"""

        event = build_tool_action_checkpoint_runtime_event(
            {
                "schemaVersion": "datasmart.python-ai-runtime.tool-action-execution-checkpoint-resume-preview.v1",
                "previewOnly": True,
                "resumeBoundary": "CHECKPOINT_RESUME_PREFLIGHT_ONLY",
                "route": {"method": "POST", "path": "/agent/tool-actions/checkpoints/resume-preview"},
                "checkpoint": {"checkpointId": "checkpoint-secret"},
                "resumeFacts": {
                    "acceptedFactTypes": ("APPROVAL_CONFIRMATION_FACT",),
                    "requestAcceptedFactTypes": (),
                    "serverAcceptedFactTypes": ("APPROVAL_CONFIRMATION_FACT",),
                    "serverRejectedFactTypes": (),
                    "requiredFactTypes": ("APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION"),
                    "missingFactTypes": ("OUTBOX_WRITE_CONFIRMATION",),
                    "rejectedFactTypes": (),
                    "ignoredSensitiveFieldCount": 2,
                },
                "serverSideResumeFacts": {"source": "TEST", "factReferenceCount": 1, "errorCodes": ()},
                "resumeDecision": {
                    "readyToResume": False,
                    "decision": "WAITING_FOR_RESUME_FACTS",
                    "nextAction": "COMPLETE_REQUIRED_FACTS_THEN_RETRY_RESUME_PREFLIGHT",
                },
                "sideEffectBoundary": {
                    "toolExecuted": False,
                    "outboxWritten": False,
                    "workerDispatched": False,
                    "approvalCreated": False,
                    "checkpointMutated": False,
                },
                "accessIssues": (),
                "productionReadiness": {"currentMode": "PREFLIGHT_ONLY_WITH_OPTIONAL_SERVER_FACT_PROVIDER"},
            },
            operation="resume_preview",
            request_payload={"checkpointId": "checkpoint-secret"},
        )
        metrics = ToolActionCheckpointMetrics()

        self.assertTrue(metrics.record_runtime_event(event))
        text = metrics.render_prometheus()

        self.assertEqual(AgentRuntimeEventType.TOOL_ACTION_CHECKPOINT_RESUME_PREVIEWED, event.event_type)
        self.assertEqual("waiting", event.attributes["metricResult"])
        self.assertIn('datasmart_ai_tool_action_checkpoint_events_total{operation="resume_preview"', text)
        self.assertIn('result="waiting"', text)
        self.assertIn('datasmart_ai_tool_action_checkpoint_resume_facts_total{fact_state="missing"} 1', text)
        self.assertIn('datasmart_ai_tool_action_checkpoint_resume_facts_total{fact_state="required"} 2', text)
        self.assertNotIn("checkpoint-secret", text)


if __name__ == "__main__":
    unittest.main()
