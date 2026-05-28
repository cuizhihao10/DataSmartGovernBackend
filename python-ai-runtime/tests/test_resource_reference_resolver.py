import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.resource_reference import AgentResourceReference
from datasmart_ai_runtime.services.resource_reference_resolver import (
    AgentResourceReferenceDecision,
    AgentResourceReferenceResolver,
)


class AgentResourceReferenceResolverTest(unittest.TestCase):
    """Agent 资源引用治理解析器测试。

    Resolver 当前不读取真实资源，只做治理判断。这些测试固定最重要的安全语义：
    - workspace 不一致要阻断；
    - audit_only 可以继续给审计链路，但不能进入模型上下文；
    - model_summary_allowed 可以进入模型上下文；
    - 外部未知引用默认阻断。
    """

    def test_allows_model_summary_reference_in_same_workspace(self) -> None:
        reference = AgentResourceReference.memory(
            memory_id="candidate-001",
            workspace_key="tenant:10:project:20",
            candidate=True,
            context_policy="model_summary_allowed",
        )

        resolution = AgentResourceReferenceResolver().resolve(
            reference,
            current_workspace_key="tenant:10:project:20",
        )

        self.assertEqual(AgentResourceReferenceDecision.ALLOWED, resolution.decision)
        self.assertTrue(resolution.model_context_allowed)
        self.assertEqual("agent_memory_service", resolution.resolver_hint)

    def test_allows_audit_only_reference_but_blocks_model_context(self) -> None:
        reference = AgentResourceReference.minio_object(
            bucket="agent",
            object_key="run-001/report.xlsx",
            workspace_key="tenant:10:project:20",
            context_policy="audit_only",
        )

        resolution = AgentResourceReferenceResolver().resolve(
            reference,
            current_workspace_key="tenant:10:project:20",
        )

        self.assertEqual(AgentResourceReferenceDecision.ALLOWED, resolution.decision)
        self.assertFalse(resolution.model_context_allowed)
        self.assertEqual("minio_object_store", resolution.resolver_hint)

    def test_blocks_workspace_mismatch(self) -> None:
        reference = AgentResourceReference.workspace_artifact(
            workspace_key="tenant:10:project:other",
            artifact_path="reports/rule.md",
        )

        resolution = AgentResourceReferenceResolver().resolve(
            reference,
            current_workspace_key="tenant:10:project:20",
        )

        self.assertEqual(AgentResourceReferenceDecision.BLOCKED, resolution.decision)
        self.assertIn("WORKSPACE_KEY_MISMATCH", resolution.issues)
        self.assertFalse(resolution.model_context_allowed)

    def test_blocks_external_uri_by_default(self) -> None:
        resolution = AgentResourceReferenceResolver().resolve(
            "https://example.com/file.csv",
            current_workspace_key="tenant:10:project:20",
            expected_workspace_required=False,
        )

        self.assertEqual(AgentResourceReferenceDecision.BLOCKED, resolution.decision)
        self.assertIn("EXTERNAL_REFERENCE_NOT_ALLOWED", resolution.issues)
        self.assertEqual("external_resource_gateway", resolution.resolver_hint)

    def test_accepts_payload_dict_for_future_api_and_event_inputs(self) -> None:
        resolution = AgentResourceReferenceResolver().resolve(
            {
                "kind": "tool_output",
                "uri": "workspace://tool-output/datasource.metadata.read?path=metadata",
                "workspaceKey": "tenant:10:project:20",
                "toolCode": "datasource.metadata.read",
                "jsonPath": "metadata",
                "contextPolicy": "model_summary_allowed",
            },
            current_workspace_key="tenant:10:project:20",
        )

        self.assertEqual(AgentResourceReferenceDecision.ALLOWED, resolution.decision)
        self.assertTrue(resolution.model_context_allowed)
        self.assertEqual("java_tool_output_store", resolution.resolver_hint)


if __name__ == "__main__":
    unittest.main()
