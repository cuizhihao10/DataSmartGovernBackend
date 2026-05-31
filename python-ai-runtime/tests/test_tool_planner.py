import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.context import ContextBlock, ContextSourceType
from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolParameterIssueAction
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis, IntentRiskTag
from datasmart_ai_runtime.domain.skills import AgentSkillPlan, AgentSkillSelection
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class ToolPlannerTest(unittest.TestCase):
    def test_uses_intent_candidate_tools_even_when_objective_has_no_quality_keyword(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请根据这批客户主数据给我一个检查方案",
            variables={},
        )
        context_blocks = (
            ContextBlock(
                source_type=ContextSourceType.DATASOURCE_METADATA,
                title="数据源元数据",
                content="客户主数据表元数据",
                metadata={"datasourceId": "ds-from-context"},
            ),
            ContextBlock(
                source_type=ContextSourceType.QUALITY_RULE_CASE,
                title="质量规则案例",
                content="客户手机号完整性规则案例",
                metadata={"businessGoal": "客户手机号完整性校验"},
            ),
        )
        intent_analysis = IntentAnalysis(
            summary="识别为数据质量规则建议场景",
            governance_domains=(GovernanceDomain.DATA_QUALITY,),
            candidate_tools=("quality.rule.suggest",),
            risk_tags=(IntentRiskTag.DRAFT_GENERATION,),
            confidence=0.82,
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )

        self.assertEqual(
            ("datasource.metadata.read", "quality.rule.suggest"),
            tuple(plan.tool_name for plan in plans),
        )
        self.assertEqual("ds-from-context", plans[0].arguments["datasourceId"])
        self.assertEqual("ds-from-context", plans[1].arguments["datasourceId"])
        self.assertEqual("客户手机号完整性校验", plans[1].arguments["businessGoal"])
        self.assertTrue(plans[1].parameter_validation.can_execute)
        self.assertEqual((), plans[1].parameter_validation.issues)

    def test_task_plan_receives_intent_risk_tags_and_missing_parameters(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请生成执行方案",
            variables={},
        )
        intent_analysis = IntentAnalysis(
            summary="识别为任务草案场景",
            governance_domains=(GovernanceDomain.TASK_MANAGEMENT,),
            candidate_tools=("task.create.draft",),
            risk_tags=(IntentRiskTag.STATE_CHANGE, IntentRiskTag.APPROVAL_REQUIRED),
            missing_parameters=("datasourceId",),
            confidence=0.7,
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        self.assertEqual("task.create.draft", plans[0].tool_name)
        self.assertTrue(plans[0].requires_human_approval)
        self.assertEqual(("state_change", "approval_required"), plans[0].arguments["payload"]["intentRiskTags"])
        self.assertEqual(("datasourceId",), plans[0].arguments["payload"]["missingParameters"])

    def test_quality_rule_missing_datasource_can_only_create_draft(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请给我生成客户主数据质量规则",
            variables={"businessGoal": "客户主数据完整性校验"},
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        plan = next(plan for plan in plans if plan.tool_name == "quality.rule.suggest")
        self.assertFalse(plan.parameter_validation.can_execute)
        self.assertTrue(plan.parameter_validation.can_create_draft)
        self.assertEqual("datasourceId", plan.parameter_validation.issues[0].parameter_name)
        self.assertEqual(ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT, plan.parameter_validation.issues[0].action)

    def test_task_plan_carries_high_risk_intent_tags(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="请导出所有项目手机号异常样本并创建任务",
            variables={"createTask": True},
        )
        intent_analysis = IntentAnalysis(
            summary="识别为高风险导出任务",
            governance_domains=(GovernanceDomain.TASK_MANAGEMENT,),
            candidate_tools=("task.create.draft",),
            risk_tags=(
                IntentRiskTag.DATA_EXPORT,
                IntentRiskTag.SENSITIVE_DATA,
                IntentRiskTag.CROSS_SCOPE,
                IntentRiskTag.APPROVAL_REQUIRED,
            ),
            missing_parameters=("exportFormat",),
            confidence=0.86,
        )

        plans = ToolPlanner(default_tool_registry()).plan(
            request=request,
            intent_analysis=intent_analysis,
        )

        task_plan = next(plan for plan in plans if plan.tool_name == "task.create.draft")
        payload = task_plan.arguments["payload"]
        self.assertEqual(
            ("data_export", "sensitive_data", "cross_scope", "approval_required"),
            payload["intentRiskTags"],
        )
        self.assertEqual(("exportFormat",), payload["missingParameters"])

    def test_tool_plan_exposes_governance_hints_from_descriptor_contract(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001"},
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        metadata_plan = next(plan for plan in plans if plan.tool_name == "datasource.metadata.read")
        self.assertTrue(metadata_plan.governance_hints["tenantScoped"])
        self.assertTrue(metadata_plan.governance_hints["projectScoped"])
        self.assertEqual(("datasourceId",), metadata_plan.governance_hints["sensitiveFields"])
        self.assertEqual("semantic", metadata_plan.governance_hints["memoryWritePolicy"])
        self.assertEqual("project_safe", metadata_plan.governance_hints["cachePolicy"])

    def test_model_visible_tools_merge_intent_skill_and_plan_candidates(self) -> None:
        """模型可见工具应来自意图、Skill 和规则计划的并集。

        这条测试保护的是“暴露给模型的工具集合”与“后续规则式工具计划”之间的一致性。真实 Agent
        里，模型需要提前看到可用工具 schema 才能提出 tool_calls；但工具集合又不能是全量平台工具，
        必须由意图和 Skill 裁剪。
        """

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请为订单表生成质量规则并创建任务草稿",
            variables={"datasourceId": "ds-order", "businessGoal": "订单质量校验", "createTask": True},
        )
        intent_analysis = IntentAnalysis(
            summary="识别为质量规则与任务草案场景",
            governance_domains=(GovernanceDomain.DATA_QUALITY, GovernanceDomain.TASK_MANAGEMENT),
            candidate_tools=("quality.rule.suggest",),
            risk_tags=(IntentRiskTag.DRAFT_GENERATION, IntentRiskTag.APPROVAL_REQUIRED),
            confidence=0.9,
        )
        skill_plan = AgentSkillPlan(
            selected_skills=(
                AgentSkillSelection(
                    skill_code="governed.task.creation",
                    display_name="受控任务创建 Skill",
                    domain=GovernanceDomain.TASK_MANAGEMENT,
                    score=0.92,
                    reason="测试场景",
                    required_tools=("task.create.draft", "task.draft.persist"),
                    approval_policy="HUMAN_APPROVAL_REQUIRED",
                ),
            )
        )

        visible_tools = ToolPlanner(default_tool_registry()).model_visible_tools(
            request=request,
            intent_analysis=intent_analysis,
            skill_plan=skill_plan,
        )

        self.assertEqual(
            (
                "quality.rule.suggest",
                "task.create.draft",
                "task.draft.persist",
                "datasource.metadata.read",
            ),
            tuple(tool.name for tool in visible_tools),
        )

    def test_multi_step_governance_chain_uses_explicit_output_references(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请为订单表生成质量规则并创建任务草稿",
            variables={
                "datasourceId": "ds-order",
                "businessGoal": "订单主键唯一性和金额有效性校验",
                "createTask": True,
            },
        )

        plans = ToolPlanner(default_tool_registry()).plan(request=request)

        self.assertEqual(
            (
                "datasource.metadata.read",
                "quality.rule.suggest",
                "task.create.draft",
                "task.draft.persist",
            ),
            tuple(plan.tool_name for plan in plans),
        )

        quality_plan = next(plan for plan in plans if plan.tool_name == "quality.rule.suggest")
        metadata_ref = quality_plan.arguments["metadataRef"]
        self.assertEqual("quality-rule-suggest", quality_plan.governance_hints["planNodeId"])
        self.assertEqual(("datasource-metadata-read",), quality_plan.governance_hints["dependsOn"])
        self.assertEqual(("datasource.metadata.read",), quality_plan.governance_hints["dependsOnTools"])
        self.assertEqual("after-datasource-metadata-read", quality_plan.governance_hints["parallelGroup"])
        self.assertEqual("suggestion", quality_plan.governance_hints["resultAlias"])
        self.assertEqual(
            "datasource.metadata.read",
            metadata_ref["fromTool"],
        )
        self.assertEqual("metadata", metadata_ref["path"])
        self.assertEqual("LATEST_SUCCESS_IN_RUN", metadata_ref["referenceMode"])
        self.assertEqual("tool_output", metadata_ref["resourceReference"]["kind"])
        self.assertEqual("workspace://tool-output/datasource.metadata.read?path=metadata", metadata_ref["resourceReference"]["uri"])

        create_draft_plan = next(plan for plan in plans if plan.tool_name == "task.create.draft")
        self.assertEqual(("quality-rule-suggest",), create_draft_plan.governance_hints["dependsOn"])
        self.assertEqual("taskDraft", create_draft_plan.governance_hints["resultAlias"])
        self.assertEqual("DATA_QUALITY_SCAN", create_draft_plan.arguments["taskType"])
        suggestion_ref = create_draft_plan.arguments["suggestionRef"]
        self.assertEqual(
            "quality.rule.suggest",
            suggestion_ref["resourceReference"]["toolCode"],
        )
        self.assertEqual("suggestion", suggestion_ref["resourceReference"]["jsonPath"])

        persist_plan = next(plan for plan in plans if plan.tool_name == "task.draft.persist")
        self.assertTrue(persist_plan.requires_human_approval)
        self.assertEqual(("task-create-draft",), persist_plan.governance_hints["dependsOn"])
        self.assertEqual("MANUAL_REVIEW", persist_plan.governance_hints["failurePolicy"])
        task_draft_ref = persist_plan.arguments["taskDraftRef"]
        self.assertEqual(
            "task.create.draft",
            task_draft_ref["resourceReference"]["toolCode"],
        )
        self.assertEqual("taskDraft", task_draft_ref["resourceReference"]["jsonPath"])


if __name__ == "__main__":
    unittest.main()
