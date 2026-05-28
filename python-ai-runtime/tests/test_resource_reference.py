import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.resource_reference import (
    AgentResourceReference,
    AgentResourceReferenceKind,
)


class AgentResourceReferenceTest(unittest.TestCase):
    """Agent 资源引用协议测试。

    该测试固定 `workspace://`、`memory://`、`minio://` 等 URI 的最小语义，避免后续工具/Skill
    输出引用协议在不同模块里各写一套。
    """

    def test_tool_output_reference_keeps_java_compatible_fields(self) -> None:
        reference = AgentResourceReference.tool_output(
            tool_code="quality.rule.suggest",
            json_path="suggestion.rules[0]",
            audit_id="audit-001",
            run_id="run-001",
            workspace_key="tenant:10:project:20",
        )

        payload = reference.to_payload()

        self.assertEqual(AgentResourceReferenceKind.TOOL_OUTPUT, reference.kind)
        self.assertEqual("quality.rule.suggest", payload["toolCode"])
        self.assertEqual("suggestion.rules[0]", payload["jsonPath"])
        self.assertEqual("audit-001", payload["auditId"])
        self.assertEqual("tenant:10:project:20", payload["workspaceKey"])

    def test_memory_candidate_reference_uses_memory_scheme(self) -> None:
        reference = AgentResourceReference.memory(
            memory_id="candidate-001",
            workspace_key="tenant:10:project:20",
            candidate=True,
        )

        self.assertEqual("memory://candidate/candidate-001", reference.uri)
        self.assertTrue(reference.attributes["candidate"])

    def test_minio_uri_can_be_parsed_as_object_reference(self) -> None:
        reference = AgentResourceReference.from_uri(
            "minio://agent/run-001/quality-rules.json",
            workspace_key="tenant:10:project:20",
        )

        self.assertEqual(AgentResourceReferenceKind.MINIO_OBJECT, reference.kind)
        self.assertEqual("tenant:10:project:20", reference.workspace_key)


if __name__ == "__main__":
    unittest.main()
