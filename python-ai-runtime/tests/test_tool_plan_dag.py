import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import ToolExecutionMode, ToolPlan, ToolRiskLevel
from datasmart_ai_runtime.services.tool_plan_dag import ToolPlanDagAnnotator


class ToolPlanDagAnnotatorTest(unittest.TestCase):
    """验证 Python ToolPlan DAG hint 生成逻辑。

    这些测试保护的是 Python -> Java 的跨语言编排契约。Python 规划阶段不会执行工具，但必须把依赖、
    节点 ID、失败策略和结果别名写清楚，否则 Java 只能靠线性顺序猜测多工具关系。
    """

    def test_nested_tool_output_references_become_explicit_dag_dependencies(self) -> None:
        plans = ToolPlanDagAnnotator().annotate(
            (
                ToolPlan(
                    tool_name="datasource.metadata.read",
                    reason="读取元数据。",
                    risk_level=ToolRiskLevel.LOW,
                    execution_mode=ToolExecutionMode.SYNC,
                ),
                ToolPlan(
                    tool_name="quality.rule.suggest",
                    reason="生成规则。",
                    arguments={
                        "metadataRef": {
                            "resourceReference": {
                                "kind": "tool_output",
                                "toolCode": "datasource.metadata.read",
                                "jsonPath": "metadata",
                            }
                        }
                    },
                    risk_level=ToolRiskLevel.MEDIUM,
                    execution_mode=ToolExecutionMode.DRAFT_ONLY,
                ),
            )
        )

        metadata_plan, quality_plan = plans
        self.assertEqual("datasource-metadata-read", metadata_plan.governance_hints["planNodeId"])
        self.assertEqual((), metadata_plan.governance_hints["dependsOn"])
        self.assertEqual("read-only-probe", metadata_plan.governance_hints["parallelGroup"])
        self.assertEqual(("datasource-metadata-read",), quality_plan.governance_hints["dependsOn"])
        self.assertEqual(("datasource.metadata.read",), quality_plan.governance_hints["dependsOnTools"])
        self.assertEqual("suggestion", quality_plan.governance_hints["resultAlias"])

    def test_implicit_business_dependencies_protect_model_generated_plans(self) -> None:
        plans = ToolPlanDagAnnotator().annotate(
            (
                ToolPlan(tool_name="datasource.metadata.read", reason="读取元数据。"),
                ToolPlan(
                    tool_name="quality.rule.suggest",
                    reason="模型直接提出质量规则工具，但没有显式 metadataRef。",
                    arguments={"datasourceId": "ds-001", "businessGoal": "客户主数据完整性"},
                ),
            )
        )

        quality_plan = plans[1]
        self.assertEqual(("datasource-metadata-read",), quality_plan.governance_hints["dependsOn"])
        self.assertEqual(("datasource.metadata.read",), quality_plan.governance_hints["dependsOnTools"])
        self.assertEqual((), quality_plan.governance_hints["unresolvedDependsOnTools"])

    def test_unresolved_reference_is_kept_for_java_preflight_diagnostics(self) -> None:
        plans = ToolPlanDagAnnotator().annotate(
            (
                ToolPlan(
                    tool_name="task.create.draft",
                    reason="测试缺失前置工具引用。",
                    arguments={"suggestionRef": {"fromTool": "quality.rule.suggest", "path": "suggestion"}},
                ),
            )
        )

        draft_plan = plans[0]
        self.assertEqual((), draft_plan.governance_hints["dependsOn"])
        self.assertEqual(("quality.rule.suggest",), draft_plan.governance_hints["unresolvedDependsOnTools"])
        self.assertEqual("taskDraft", draft_plan.governance_hints["resultAlias"])


if __name__ == "__main__":
    unittest.main()
