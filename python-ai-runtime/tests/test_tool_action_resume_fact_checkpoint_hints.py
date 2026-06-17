import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import checkpoint_resume_fact_bundle_hints


class FakeCheckpointWithGraphHints:
    """构造只含低敏执行图摘要的 checkpoint。

    这个夹具故意放入 prompt、SQL、arguments、payloadReference 等敏感或半敏感字段，
    用来证明 helper 只提取 Java fact bundle DTO 明确需要的控制面线索，不会把工具正文或 payload 定位符扩散出去。
    """

    graph_run_summary = {
        "steps": (
            {
                "toolName": "datasource.metadata.read",
                "arguments": {"datasourceId": "ds-secret-should-not-leak"},
                "prompt": "raw prompt should not leak",
                "sql": "select * from hidden_table",
                "proposalSubmission": {
                    "requestPayload": {
                        "clientRequestId": "command-from-checkpoint",
                        "requestId": "request-from-checkpoint",
                        "approvalConfirmationId": "approval-from-checkpoint",
                        "clarificationFactId": "clarification-from-checkpoint",
                        "policyVersion": "tool-readiness-policy.v1",
                        "payloadReference": "agent-payload:must-not-be-returned",
                        "graphId": "graph-must-not-be-returned",
                    },
                    "javaProposal": {
                        "toolName": "datasource.metadata.read.from-java",
                        "outboxId": "outbox-from-checkpoint",
                        "payloadReference": "java-payload:must-not-be-returned",
                        "targetEndpoint": "http://internal.service/should-not-leak",
                    },
                },
            },
        )
    }


class ToolActionResumeFactCheckpointHintsTest(unittest.TestCase):
    """checkpoint -> Java fact bundle locator hint 提取测试。"""

    def test_extracts_only_java_supported_low_sensitive_hints(self) -> None:
        """helper 应只返回 Java fact bundle DTO 支持的低敏字段。"""

        hints = checkpoint_resume_fact_bundle_hints(FakeCheckpointWithGraphHints())
        serialized = str(hints)

        self.assertEqual("command-from-checkpoint", hints["commandId"])
        self.assertEqual("outbox-from-checkpoint", hints["outboxId"])
        self.assertEqual("approval-from-checkpoint", hints["approvalFactId"])
        self.assertEqual("clarification-from-checkpoint", hints["clarificationFactId"])
        self.assertEqual("datasource.metadata.read", hints["toolCode"])
        self.assertEqual("tool-readiness-policy.v1", hints["requestedPolicyVersion"])
        self.assertNotIn("payloadReference", hints)
        self.assertNotIn("graphId", hints)
        self.assertNotIn("prompt", hints)
        self.assertNotIn("sql", hints)
        self.assertNotIn("arguments", hints)
        self.assertNotIn("agent-payload:must-not-be-returned", serialized)
        self.assertNotIn("java-payload:must-not-be-returned", serialized)
        self.assertNotIn("internal.service", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("raw prompt should not leak", serialized)
        self.assertNotIn("ds-secret-should-not-leak", serialized)

    def test_missing_or_malformed_graph_summary_returns_empty_hints(self) -> None:
        """脏 checkpoint 不应让 resume-preview 崩溃，而是返回空提示等待显式字段兜底。"""

        class EmptyCheckpoint:
            graph_run_summary = {"steps": ("not-a-mapping",)}

        self.assertEqual({}, checkpoint_resume_fact_bundle_hints(object()))
        self.assertEqual({}, checkpoint_resume_fact_bundle_hints(EmptyCheckpoint()))


if __name__ == "__main__":
    unittest.main()
