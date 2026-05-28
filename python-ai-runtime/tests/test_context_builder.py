import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datetime import datetime, timezone

from datasmart_ai_runtime.domain.context import ContextSensitivityLevel, ContextSourceType
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.context_builder import DefaultContextBuilder


class DefaultContextBuilderTest(unittest.TestCase):
    def test_builds_metadata_permission_and_quality_context_blocks(self) -> None:
        blocks = DefaultContextBuilder().build(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请为客户主数据生成质量规则",
                variables={
                    "datasourceId": "ds-001",
                    "businessGoal": "客户手机号完整性校验",
                },
            )
        )

        source_types = {block.source_type for block in blocks}
        self.assertIn(ContextSourceType.USER_OBJECTIVE, source_types)
        self.assertIn(ContextSourceType.PERMISSION_FACT, source_types)
        self.assertIn(ContextSourceType.DATASOURCE_METADATA, source_types)
        self.assertIn(ContextSourceType.QUALITY_RULE_CASE, source_types)

    def test_permission_context_carries_tenant_project_and_actor_metadata(self) -> None:
        blocks = DefaultContextBuilder().build(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="auditor-a",
                objective="解释当前权限边界",
            )
        )

        permission_block = next(block for block in blocks if block.source_type == ContextSourceType.PERMISSION_FACT)
        self.assertEqual("tenant-a", permission_block.metadata["tenantId"])
        self.assertEqual("project-a", permission_block.metadata["projectId"])
        self.assertEqual("auditor-a", permission_block.metadata["actorId"])

    def test_context_blocks_carry_governance_metadata(self) -> None:
        blocks = DefaultContextBuilder().build(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请为客户主数据生成质量规则",
                variables={"datasourceId": "ds-001"},
            )
        )

        for block in blocks:
            self.assertIsNotNone(block.source_id)
            self.assertIsNotNone(block.expires_at)
            self.assertGreater(block.expires_at, datetime.now(timezone.utc))
            self.assertGreater(block.token_estimate, 0)

        permission_block = next(block for block in blocks if block.source_type == ContextSourceType.PERMISSION_FACT)
        datasource_block = next(block for block in blocks if block.source_type == ContextSourceType.DATASOURCE_METADATA)
        self.assertEqual(ContextSensitivityLevel.CONFIDENTIAL, permission_block.sensitivity_level)
        self.assertEqual(ContextSensitivityLevel.CONFIDENTIAL, datasource_block.sensitivity_level)


if __name__ == "__main__":
    unittest.main()
