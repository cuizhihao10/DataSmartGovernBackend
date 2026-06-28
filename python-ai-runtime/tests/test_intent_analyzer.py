import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentRiskTag
from datasmart_ai_runtime.services.context_builder import DefaultContextBuilder
from datasmart_ai_runtime.services.intent_analyzer import RuleBasedIntentAnalyzer


class RuleBasedIntentAnalyzerTest(unittest.TestCase):
    def test_quality_task_intent_contains_domains_tools_and_risk_tags(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请为客户主数据生成质量规则，并创建一个同步任务执行",
            variables={"datasourceId": "ds-001", "createTask": True},
        )
        context_blocks = DefaultContextBuilder().build(request)

        analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        self.assertIn(GovernanceDomain.DATASOURCE, analysis.governance_domains)
        self.assertIn(GovernanceDomain.DATA_QUALITY, analysis.governance_domains)
        self.assertIn(GovernanceDomain.DATA_SYNC, analysis.governance_domains)
        self.assertIn("quality.rule.suggest", analysis.candidate_tools)
        self.assertIn("task.create.draft", analysis.candidate_tools)
        self.assertIn(IntentRiskTag.APPROVAL_REQUIRED, analysis.risk_tags)
        self.assertGreaterEqual(analysis.confidence, 0.8)

    def test_quality_intent_without_datasource_reports_missing_parameter(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请生成一组质量校验规则",
        )
        context_blocks = DefaultContextBuilder().build(request)

        analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        self.assertIn("datasourceId", analysis.missing_parameters)
        self.assertLess(analysis.confidence, 0.8)

    def test_quality_remediation_intent_targets_specialized_draft_tool(self) -> None:
        """质量异常治理任务应命中特化草案工具，而不是退回普通规则生成或泛化任务草案。

        这里保护的是产品语义边界：用户说“异常复核/派单/治理任务”时，Agent 应该把已发现异常转成
        data-quality 的低敏治理任务草案；用户说“生成质量规则”时，才进入 quality.rule.suggest。
        """

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请把 77 号质量报告里的高危异常派单治理，创建异常复核任务草案",
            variables={"reportId": 77, "severity": "HIGH", "createRemediationTask": True},
        )
        context_blocks = DefaultContextBuilder().build(request)

        analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        self.assertIn(GovernanceDomain.DATA_QUALITY, analysis.governance_domains)
        self.assertIn(GovernanceDomain.TASK_MANAGEMENT, analysis.governance_domains)
        self.assertIn("quality.remediation.task.draft", analysis.candidate_tools)
        self.assertNotIn("quality.rule.suggest", analysis.candidate_tools)
        self.assertNotIn("task.create.draft", analysis.candidate_tools)
        self.assertIn(IntentRiskTag.DRAFT_GENERATION, analysis.risk_tags)
        self.assertIn(IntentRiskTag.STATE_CHANGE, analysis.risk_tags)
        self.assertIn(IntentRiskTag.APPROVAL_REQUIRED, analysis.risk_tags)
        self.assertNotIn("remediationScope", analysis.missing_parameters)

    def test_export_sensitive_cross_scope_intent_has_high_risk_tags(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="auditor-a",
            objective="请导出所有项目的客户手机号和身份证异常样本为 Excel",
        )
        context_blocks = DefaultContextBuilder().build(request)

        analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        self.assertIn(IntentRiskTag.DATA_EXPORT, analysis.risk_tags)
        self.assertIn(IntentRiskTag.SENSITIVE_DATA, analysis.risk_tags)
        self.assertIn(IntentRiskTag.CROSS_SCOPE, analysis.risk_tags)
        self.assertIn(IntentRiskTag.APPROVAL_REQUIRED, analysis.risk_tags)
        self.assertIn("exportFormat", analysis.missing_parameters)

    def test_write_sql_intent_has_state_change_and_approval_tags(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="请执行 SQL 删除重复客户记录",
        )
        context_blocks = DefaultContextBuilder().build(request)

        analysis = RuleBasedIntentAnalyzer().analyze(request, context_blocks)

        self.assertIn(IntentRiskTag.WRITE_SQL, analysis.risk_tags)
        self.assertIn(IntentRiskTag.STATE_CHANGE, analysis.risk_tags)
        self.assertIn(IntentRiskTag.APPROVAL_REQUIRED, analysis.risk_tags)


if __name__ == "__main__":
    unittest.main()
