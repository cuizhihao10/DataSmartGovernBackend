import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator
from datasmart_ai_runtime.domain.context import ContextSourceType
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.memory import (
    UserProfileFacetStatus,
    UserProfileMemoryService,
)


class UserProfileMemoryTest(unittest.TestCase):
    def test_profile_facts_are_low_sensitive_and_context_injected(self) -> None:
        """用户画像应进入上下文，但不能保存原始 prompt、SQL 或样本值。"""

        profile_memory = UserProfileMemoryService.default()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective=(
                "请用中文详细说明，尽快帮我做 MySQL 到 PostgreSQL 的增量 ETL 数据同步；"
                "不要保存这段 SQL: select * from customer_profile where phone='13800000000'"
            ),
            variables={"workspaceKey": "workspace-a"},
        )

        result = profile_memory.observe_and_build_context(request)

        self.assertGreaterEqual(len(result.observed_facets), 3)
        self.assertTrue(result.context_blocks)
        self.assertEqual(ContextSourceType.USER_PROFILE, result.context_blocks[0].source_type)
        joined_summary = str(result.to_summary())
        self.assertNotIn("select * from customer_profile", joined_summary)
        self.assertNotIn("13800000000", joined_summary)
        self.assertIn("zh-CN", joined_summary)
        self.assertIn("mysql", joined_summary)

    def test_orchestrator_injects_user_profile_context_before_planning(self) -> None:
        """编排器应在模型路由和工具规划前加载画像上下文。"""

        profile_memory = UserProfileMemoryService.default()
        orchestrator = build_default_orchestrator(user_profile_memory=profile_memory)

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请用中文详细注释，直接推进 MySQL 数据同步工具的闭环收敛。",
                variables={"workspaceKey": "workspace-a"},
            )
        )

        self.assertGreater(plan.user_profile_context["activeFacetCount"], 0)
        self.assertIn(ContextSourceType.USER_PROFILE, {block.source_type for block in plan.context_blocks})
        profile_event = next(event for event in plan.runtime_events if event.stage == "load_user_profile")
        self.assertTrue(profile_event.attributes["profileLoaded"])

    def test_candidate_can_be_activated_or_rejected_by_id(self) -> None:
        """画像候选事实需要可治理，后续才能支持用户画像设置页。"""

        profile_memory = UserProfileMemoryService.default()
        preview = profile_memory.extract_preview(
            {
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "actorId": "user-a",
                "objective": "后续如果做 Kafka CDC，我希望先给表格化方案。",
            }
        )
        candidate = next(
            item
            for item in preview["observedFacets"]
            if item["status"] == UserProfileFacetStatus.CANDIDATE.value
        )

        activated = profile_memory.activate(
            candidate["facetId"],
            operator_id="admin-a",
            reason="用户在设置页确认该偏好。",
        )

        self.assertEqual(UserProfileFacetStatus.ACTIVE.value, activated["status"])
        self.assertEqual("admin-a", activated["attributes"]["decisionOperatorId"])


if __name__ == "__main__":
    unittest.main()
