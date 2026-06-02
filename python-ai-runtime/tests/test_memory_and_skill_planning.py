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
from datasmart_ai_runtime.services.memory.memory_planner import AgentMemoryPlanner
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

    def test_skill_admission_denies_when_required_permission_is_missing(self) -> None:
        """显式权限事实缺少必需权限时，Skill 应进入 rejected，而不是继续暴露给模型。

        这类测试对应真实商业场景：Java gateway 或 permission-admin 已经告诉 Python 当前用户拥有哪些
        权限，Python Runtime 就不能再按“语义命中”启用质量规则设计 Skill。否则模型可能看到不该暴露
        的工具 schema，虽然最终执行前还会有 Java 审批，但用户体验和审计解释都会变差。
        """

        intent = IntentAnalysis(
            summary="质量规则设计",
            governance_domains=(GovernanceDomain.DATA_QUALITY,),
            candidate_tools=("quality.rule.suggest",),
            risk_tags=(IntentRiskTag.DRAFT_GENERATION,),
        )
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请为客户主数据生成质量规则",
            variables={
                "grantedPermissions": ("datasource:metadata:read",),
                "actorRole": "PROJECT_OWNER",
            },
        )

        skill_plan = AgentSkillRegistry(default_skill_registry()).select(request.objective, intent, request=request)

        self.assertFalse(skill_plan.selected_skills)
        self.assertEqual(("quality.rule.design",), tuple(item.skill_code for item in skill_plan.rejected_skills))
        self.assertEqual("DENIED_MISSING_PERMISSION", skill_plan.rejected_skills[0].admission_status)
        self.assertIn("quality:rule:draft", skill_plan.rejected_skills[0].admission_reasons[0])

    def test_skill_admission_denies_high_risk_skill_for_ordinary_user(self) -> None:
        """高风险 Skill 即使满足权限，也需要角色兜底，避免普通用户启用高风险能力包。"""

        intent = IntentAnalysis(
            summary="创建受控任务",
            governance_domains=(GovernanceDomain.TASK_MANAGEMENT,),
            candidate_tools=("task.create.draft",),
            risk_tags=(IntentRiskTag.STATE_CHANGE,),
        )
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请创建同步任务并执行",
            variables={
                "grantedPermissions": ("task:create",),
                "actorRole": "ORDINARY_USER",
            },
        )

        skill_plan = AgentSkillRegistry(default_skill_registry()).select(request.objective, intent, request=request)

        self.assertFalse(skill_plan.selected_skills)
        self.assertEqual(("governed.task.creation",), tuple(item.skill_code for item in skill_plan.rejected_skills))
        self.assertEqual("DENIED_RISK_ROLE", skill_plan.rejected_skills[0].admission_status)

    def test_skill_admission_keeps_conditional_recommendation_without_role_fact(self) -> None:
        """本地学习环境缺少角色事实时，高风险 Skill 仍可条件性推荐，但状态不能伪装成完全通过。"""

        intent = IntentAnalysis(
            summary="创建受控任务",
            governance_domains=(GovernanceDomain.TASK_MANAGEMENT,),
            candidate_tools=("task.create.draft",),
            risk_tags=(IntentRiskTag.STATE_CHANGE,),
        )
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请创建同步任务并执行",
            variables={"grantedPermissions": ("task:create",)},
        )

        skill_plan = AgentSkillRegistry(default_skill_registry()).select(request.objective, intent, request=request)

        self.assertEqual(("governed.task.creation",), tuple(item.skill_code for item in skill_plan.selected_skills))
        self.assertEqual("CONDITIONAL", skill_plan.selected_skills[0].admission_status)
        self.assertTrue(any("actorRole" in reason for reason in skill_plan.selected_skills[0].admission_reasons))


if __name__ == "__main__":
    unittest.main()
