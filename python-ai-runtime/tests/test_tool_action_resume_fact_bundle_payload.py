import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools.tool_action_resume_fact_bundle_payload import (
    SERVER_BACKED_FACT_TYPES,
    build_resume_fact_bundle_payload,
)


class FakeCheckpoint:
    """只暴露恢复事实 DTO 构造所需的低敏 checkpoint 字段。"""

    checkpoint_id = "checkpoint-payload-001"
    thread_id = "thread-payload-001"
    tenant_id = "101"
    project_id = "202"
    actor_id = "1001"
    request_id = "command-from-checkpoint"
    run_id = "run-payload-001"
    session_id = "session-payload-001"
    resume_requirements = ("APPROVAL_CONFIRMATION_FACT", "CLARIFICATION_FACT", "OUTBOX_WRITE_CONFIRMATION")
    graph_run_summary = {
        "steps": (
            {
                "toolName": "datasource.metadata.read",
                "proposalSubmission": {
                    "requestPayload": {
                        "clientRequestId": "command-from-graph",
                        "approvalConfirmationId": "approval-from-graph",
                        "clarificationFactId": "clarification-from-graph",
                        "policyVersion": "tool-readiness-policy.v1",
                        "payloadReference": "agent-payload:secret",
                        "prompt": "raw prompt should never enter java fact query",
                        "sql": "select * from hidden_table",
                    },
                    "javaProposal": {
                        "outboxId": "outbox-from-graph",
                        "targetEndpoint": "http://internal-worker.local/tools",
                    },
                },
            },
        )
    }


class ToolActionResumeFactBundlePayloadTest(unittest.TestCase):
    """Java 恢复事实查询 DTO 构造器测试。

    这组测试把 DTO 构造规则从 HTTP 客户端测试中单独固定下来。这样后续无论 fact bundle provider、
    gate graph provider、MCP adapter 还是 OpenClaw-style runner 复用该 builder，都能共享同一套
    “低敏定位字段可发送，业务正文不可发送”的契约。
    """

    def test_payload_uses_request_values_before_checkpoint_hints(self) -> None:
        """请求显式字段优先于 checkpoint hint，但仍只输出低敏控制面定位字段。"""

        payload = build_resume_fact_bundle_payload(
            checkpoint=FakeCheckpoint(),
            request_payload={
                "checkpointId": "checkpoint-from-request",
                "threadId": "thread-from-request",
                "runId": "run-from-request",
                "sessionId": "session-from-request",
                "commandId": "command-from-request",
                "outboxConfirmationId": "outbox-from-request",
                "resumeFacts": {
                    "approvalConfirmationId": "approval-from-request",
                    "clarificationFactId": "clarification-from-request",
                    "policyVersion": "tool-readiness-policy.from-request",
                },
                "context": {"tenantId": "303", "projectId": "404", "actorId": "2002"},
                "params": {
                    "name": "datasource.metadata.read",
                    "arguments": {"datasourceId": "ds-secret"},
                },
                "prompt": "raw prompt should not leak",
                "sql": "select * from secret_table",
            },
        )

        self.assertEqual("checkpoint-from-request", payload["checkpointId"])
        self.assertEqual("thread-from-request", payload["threadId"])
        self.assertEqual("run-from-request", payload["runId"])
        self.assertEqual("session-from-request", payload["sessionId"])
        self.assertEqual("command-from-request", payload["commandId"])
        self.assertEqual("outbox-from-request", payload["outboxId"])
        self.assertEqual("approval-from-request", payload["approvalFactId"])
        self.assertEqual("clarification-from-request", payload["clarificationFactId"])
        self.assertEqual("tool-readiness-policy.from-request", payload["requestedPolicyVersion"])
        self.assertEqual(303, payload["tenantId"])
        self.assertEqual(404, payload["projectId"])
        self.assertEqual("2002", payload["actorId"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", payload["requiredFactTypes"])
        self.assertIn("CLARIFICATION_FACT", payload["requiredFactTypes"])
        self.assertIn("OUTBOX_WRITE_CONFIRMATION", payload["requiredFactTypes"])
        self.assertIn("WORKER_RECEIPT_PROJECTION", payload["requiredFactTypes"])
        self.assertNotIn("arguments", payload)
        self.assertNotIn("prompt", payload)
        self.assertNotIn("sql", payload)
        self.assertNotIn("payloadReference", payload)

    def test_payload_falls_back_to_checkpoint_graph_hints_without_leaking_sensitive_body(self) -> None:
        """请求只给 checkpoint 时，应从图摘要恢复定位符，但不能携带图里的正文噪声。"""

        payload = build_resume_fact_bundle_payload(
            checkpoint=FakeCheckpoint(),
            request_payload={"context": {"traceId": "trace-payload-001"}},
        )
        serialized = str(payload)

        self.assertEqual("command-from-graph", payload["commandId"])
        self.assertEqual("outbox-from-graph", payload["outboxId"])
        self.assertEqual("approval-from-graph", payload["approvalFactId"])
        self.assertEqual("clarification-from-graph", payload["clarificationFactId"])
        self.assertEqual("datasource.metadata.read", payload["toolCode"])
        self.assertEqual("tool-readiness-policy.v1", payload["requestedPolicyVersion"])
        self.assertNotIn("agent-payload:secret", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("internal-worker", serialized)
        self.assertNotIn("raw prompt", serialized)

    def test_default_required_fact_types_stay_server_backed_and_ordered_for_resume_preview(self) -> None:
        """没有明确事实需求时，默认仍回查审批、outbox 和 worker receipt。"""

        class EmptyCheckpoint:
            checkpoint_id = None
            resume_requirements = ()

        payload = build_resume_fact_bundle_payload(checkpoint=EmptyCheckpoint(), request_payload={})

        self.assertEqual(
            ["APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION", "WORKER_RECEIPT_PROJECTION"],
            payload["requiredFactTypes"],
        )
        self.assertTrue(set(payload["requiredFactTypes"]).issubset(SERVER_BACKED_FACT_TYPES))


if __name__ == "__main__":
    unittest.main()
