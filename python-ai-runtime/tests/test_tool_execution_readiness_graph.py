import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import ToolExecutionMode, ToolPlan, ToolRiskLevel
from datasmart_ai_runtime.services.tools.tool_execution_readiness import (
    ToolExecutionReadinessPolicy,
    ToolExecutionReadinessService,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness_graph import (
    ToolExecutionReadinessGraphBranch,
    ToolExecutionReadinessGraphBuilder,
    build_tool_execution_readiness_graph_response,
)


class ToolExecutionReadinessGraphTest(unittest.TestCase):
    def test_graph_routes_readiness_decisions_to_low_sensitive_execution_branches(self) -> None:
        """readiness 图谱应把不同工具决策映射成执行前条件分支，且不能泄露工具参数真实值。"""

        plans = (
            ToolPlan(
                tool_name="datasource.metadata.read",
                reason="读取元数据",
                arguments={"datasourceId": "ds-sensitive-graph-001"},
                risk_level=ToolRiskLevel.LOW,
                execution_mode=ToolExecutionMode.SYNC,
                governance_hints={"sensitiveFields": ("datasourceId",), "targetService": "datasource-management"},
            ),
            ToolPlan(
                tool_name="task.create.draft",
                reason="创建治理任务",
                arguments={"taskType": "DATA_QUALITY_SCAN"},
                risk_level=ToolRiskLevel.HIGH,
                execution_mode=ToolExecutionMode.SYNC,
                governance_hints={"targetService": "task-management"},
            ),
            ToolPlan(
                tool_name="quality.rule.suggest",
                reason="生成质量规则草稿",
                arguments={"businessGoal": "手机号唯一性"},
                risk_level=ToolRiskLevel.LOW,
                execution_mode=ToolExecutionMode.DRAFT_ONLY,
                governance_hints={"targetService": "data-quality"},
            ),
            ToolPlan(
                tool_name="data-sync.execute",
                reason="提交异步同步任务",
                arguments={"taskId": "sync-task-sensitive-001"},
                risk_level=ToolRiskLevel.MEDIUM,
                execution_mode=ToolExecutionMode.ASYNC_TASK,
                governance_hints={"targetService": "data-sync"},
            ),
            ToolPlan(
                tool_name="dangerous.export.all",
                reason="高危导出",
                arguments={"sql": "select * from secret_table"},
                risk_level=ToolRiskLevel.CRITICAL,
                execution_mode=ToolExecutionMode.SYNC,
                governance_hints={"targetService": "compliance"},
            ),
        )
        readiness = ToolExecutionReadinessService().evaluate(
            plans,
            policy=ToolExecutionReadinessPolicy(max_auto_sync_tools=3, max_async_tools=1),
            policy_metadata={"source": "unit-test", "policyVersion": "graph-v1"},
        )

        graph = build_tool_execution_readiness_graph_response(readiness)

        self.assertEqual("TOOL_EXECUTION_READINESS_GRAPH", graph["snapshotType"])
        self.assertEqual("LOW_SENSITIVE_GRAPH_METADATA_ONLY", graph["payloadPolicy"])
        self.assertEqual("PRE_EXECUTION_CONDITION_GRAPH_ONLY", graph["executionBoundary"])
        self.assertFalse(graph["durableActionBoundary"]["toolExecuted"])
        self.assertFalse(graph["durableActionBoundary"]["outboxWritten"])
        self.assertEqual(6, graph["nodeCount"])
        self.assertEqual(5, graph["edgeCount"])
        self.assertEqual(1, graph["branchCounts"][ToolExecutionReadinessGraphBranch.READY_TO_EXECUTE.value])
        self.assertEqual(1, graph["branchCounts"][ToolExecutionReadinessGraphBranch.WAITING_APPROVAL.value])
        self.assertEqual(1, graph["branchCounts"][ToolExecutionReadinessGraphBranch.SHOW_DRAFT_FOR_REVIEW.value])
        self.assertEqual(1, graph["branchCounts"][ToolExecutionReadinessGraphBranch.QUEUE_ASYNC_COMMAND.value])
        self.assertEqual(1, graph["branchCounts"][ToolExecutionReadinessGraphBranch.BLOCKED_BEFORE_EXECUTION.value])
        self.assertIn("EXECUTE_READY_TOOLS", graph["nextActions"])
        self.assertIn("SUBMIT_ASYNC_COMMAND", graph["nextActions"])
        self.assertIn("CREATE_OR_WAIT_APPROVAL", graph["nextActions"])
        self.assertIn("SHOW_DRAFT_FOR_REVIEW", graph["nextActions"])
        self.assertIn("ESCALATE_TO_OPERATOR", graph["nextActions"])

        nodes_by_tool = {node["toolName"]: node for node in graph["nodes"] if node["toolName"]}
        self.assertEqual("READY", nodes_by_tool["datasource.metadata.read"]["status"])
        self.assertEqual("INTERRUPTED_WAITING_APPROVAL", nodes_by_tool["task.create.draft"]["status"])
        self.assertEqual("WAITING_REVIEW", nodes_by_tool["quality.rule.suggest"]["status"])
        self.assertEqual("READY_TO_QUEUE", nodes_by_tool["data-sync.execute"]["status"])
        self.assertEqual("BLOCKED", nodes_by_tool["dangerous.export.all"]["status"])
        self.assertIn("datasourceId", nodes_by_tool["datasource.metadata.read"]["sensitiveArgumentNames"])
        self.assertIn("sql", nodes_by_tool["dangerous.export.all"]["sensitiveArgumentNames"])
        self.assertNotIn("ds-sensitive-graph-001", str(graph))
        self.assertNotIn("sync-task-sensitive-001", str(graph))
        self.assertNotIn("select * from secret_table", str(graph))
        self.assertNotIn("手机号唯一性", str(graph))

    def test_empty_tool_plan_still_returns_no_tool_plan_branch(self) -> None:
        """没有 ToolPlan 时仍应返回空计划节点，避免调用方把空图误判为系统错误。"""

        readiness = ToolExecutionReadinessService().evaluate(())
        graph = ToolExecutionReadinessGraphBuilder().build(readiness).to_response()

        self.assertEqual(0, graph["totalCount"])
        self.assertEqual(2, graph["nodeCount"])
        self.assertEqual(1, graph["edgeCount"])
        self.assertEqual({"NO_TOOL_PLAN": 1}, graph["branchCounts"])
        self.assertEqual("no-tool-plan", graph["edges"][0]["toNodeId"])
        self.assertEqual("CONTINUE_TEXT_RESPONSE", graph["nodes"][1]["nextAction"])


if __name__ == "__main__":
    unittest.main()
