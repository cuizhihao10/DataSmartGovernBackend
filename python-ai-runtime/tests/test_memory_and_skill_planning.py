import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_skill_registry, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis, IntentRiskTag
from datasmart_ai_runtime.domain.memory import AgentMemoryScope, AgentMemoryType
from datasmart_ai_runtime.services.memory_planner import AgentMemoryPlanner
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class MemoryAndSkillPlanningTest(unittest.TestCase):
    def test_memory_plan_uses_project_scope_for_normal_quality_governance(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请为客户主数据生成质量规则",
            variables={"datasourceId": "ds-001", "businessGoal": "客户主数据完整性"},
        )
        intent = IntentAnalysis(
            summary="质量规则设计",
            governance_domains=(GovernanceDomain.DATA_QUALITY,),
            candidate_tools=("quality.rule.suggest",),
            risk_tags=(IntentRiskTag.DRAFT_GENERATION,),
        )
        tool_plans = ToolPlanner(default_tool_registry()).plan(request, intent_analysis=intent)

        memory_plan = AgentMemoryPlanner().plan(request, intent, (), tool_plans)

        self.assertEqual(AgentMemoryScope.PROJECT, memory_plan.default_scope)
        self.assertFalse(memory_plan.approval_required_for_write)
        self.assertIn(AgentMemoryType.SEMANTIC, {target.memory_type for target in memory_plan.retrieval_targets})
        self.assertIn(AgentMemoryType.EPISODIC, {target.memory_type for target in memory_plan.retrieval_targets})
        self.assertIn(AgentMemoryType.EPISODIC, memory_plan.writable_memory_types)

    def test_sensitive_cross_scope_memory_plan_is_session_scoped_and_approval_guarded(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="请导出所有项目手机号异常样本并创建任务",
            variables={"createTask": True},
        )
        intent = IntentAnalysis(
            summary="高风险导出任务",
            governance_domains=(GovernanceDomain.TASK_MANAGEMENT,),
            candidate_tools=("task.create.draft",),
            risk_tags=(IntentRiskTag.DATA_EXPORT, IntentRiskTag.SENSITIVE_DATA, IntentRiskTag.CROSS_SCOPE),
        )
        tool_plans = ToolPlanner(default_tool_registry()).plan(request, intent_analysis=intent)

        memory_plan = AgentMemoryPlanner().plan(request, intent, (), tool_plans)

        self.assertEqual(AgentMemoryScope.SESSION, memory_plan.default_scope)
        self.assertEqual(7, memory_plan.retention_days)
        self.assertTrue(memory_plan.approval_required_for_write)
        self.assertTrue(any("敏感" in note for note in memory_plan.privacy_notes))

    def test_skill_registry_selects_quality_and_task_skills_from_intent(self) -> None:
        intent = IntentAnalysis(
            summary="质量规则并创建任务",
            governance_domains=(GovernanceDomain.DATA_QUALITY, GovernanceDomain.TASK_MANAGEMENT),
            candidate_tools=("quality.rule.suggest", "task.create.draft"),
            risk_tags=(IntentRiskTag.DRAFT_GENERATION, IntentRiskTag.STATE_CHANGE),
        )

        skill_plan = AgentSkillRegistry(default_skill_registry()).select(
            "请为客户主数据生成质量规则，并创建任务",
            intent,
        )

        selected_codes = {selection.skill_code for selection in skill_plan.selected_skills}
        self.assertIn("quality.rule.design", selected_codes)
        self.assertIn("governed.task.creation", selected_codes)
        self.assertTrue(any(AgentMemoryType.EPISODIC in item.memory_dependencies for item in skill_plan.selected_skills))


if __name__ == "__main__":
    unittest.main()
