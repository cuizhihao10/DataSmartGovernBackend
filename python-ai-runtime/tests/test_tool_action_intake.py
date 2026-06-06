import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import ModelToolCall
from datasmart_ai_runtime.services.tools import (
    ToolActionIntakeBoundary,
    ToolActionIntakeService,
    ToolActionIntakeSource,
)


class ToolActionIntakeServiceTest(unittest.TestCase):
    """工具动作意图统一入口测试。

    本组测试保护的是“协议入口归一化”能力，而不是工具执行能力：
    - 模型 tool_call 可以进入 DataSmart ToolPlan/readiness/graph；
    - MCP tools/call 也必须先转换为 ToolPlan/readiness/graph，不能直接调用下游服务；
    - A2A task/action 先进入 A2A 控制面决策，不应被伪装成普通 ToolPlan；
    - 所有 summary 只返回低敏摘要，不返回 datasourceId 值、业务目标正文、SQL、prompt 或内部 endpoint。
    """

    def setUp(self) -> None:
        self.service = ToolActionIntakeService()
        self.tools = default_tool_registry()

    def test_model_tool_call_enters_readiness_graph_boundary_without_leaking_arguments(self) -> None:
        """模型工具调用应归一为 ToolPlan，但低敏摘要不能暴露参数值。"""

        report = self.service.from_model_tool_calls(
            (
                ModelToolCall(
                    call_id="call-quality-001",
                    name="quality_rule_suggest",
                    arguments='{"datasourceId":"ds-secret-001","businessGoal":"客户主数据完整性"}',
                ),
            ),
            registered_tools=self.tools,
            visible_tools=tuple(tool for tool in self.tools if tool.name == "quality.rule.suggest"),
        )
        summary = report.to_low_sensitive_summary()
        serialized = str(summary)

        self.assertEqual(ToolActionIntakeSource.MODEL_TOOL_CALL, report.source)
        self.assertEqual(1, len(report.accepted_tool_plans))
        self.assertEqual("quality.rule.suggest", report.accepted_tool_plans[0].tool_name)
        self.assertEqual(ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH.value, summary["items"][0]["boundary"])
        self.assertEqual(("businessGoal", "datasourceId"), summary["items"][0]["toolPlan"]["argumentNames"])
        self.assertNotIn("ds-secret-001", serialized)
        self.assertNotIn("客户主数据完整性", serialized)

    def test_mcp_tools_call_is_relabelled_and_kept_inside_governed_boundary(self) -> None:
        """MCP tools/call 应复用 ToolPlan 治理链路，并重新标记真实协议来源。"""

        report = self.service.from_mcp_tools_call(
            {
                "id": "mcp-call-001",
                "name": "quality.rule.suggest",
                "arguments": {
                    "datasourceId": "ds-secret-002",
                    "businessGoal": "手机号唯一性检查",
                },
                "prompt": "外部 Agent 原始 prompt 不应进入低敏摘要",
                "sql": "select * from secret_table",
                "targetEndpoint": "http://internal-service.local/tools/call",
            },
            registered_tools=self.tools,
            visible_tools=tuple(tool for tool in self.tools if tool.name == "quality.rule.suggest"),
        )
        summary = report.to_low_sensitive_summary()
        serialized = str(summary)
        plan = report.accepted_tool_plans[0]

        self.assertEqual(ToolActionIntakeSource.MCP_TOOLS_CALL, report.source)
        self.assertEqual(1, summary["acceptedToolPlanCount"])
        self.assertEqual("mcp_tools_call", plan.governance_hints["source"])
        self.assertEqual("MCP", plan.governance_hints["protocol"])
        self.assertEqual("mcp-call-001", plan.governance_hints["mcpCallId"])
        self.assertEqual(ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH.value, plan.governance_hints["executionBoundary"])
        self.assertGreater(summary["items"][0]["sensitiveFieldIgnoredCount"], 0)
        self.assertNotIn("ds-secret-002", serialized)
        self.assertNotIn("手机号唯一性检查", serialized)
        self.assertNotIn("secret_table", serialized)
        self.assertNotIn("internal-service", serialized)
        self.assertNotIn("外部 Agent 原始 prompt", serialized)

    def test_mcp_tools_call_for_not_visible_tool_is_rejected_before_readiness(self) -> None:
        """MCP 请求未暴露工具时必须 fail-closed，不能生成 readiness 候选。"""

        report = self.service.from_mcp_tools_call(
            {
                "id": "mcp-call-hidden",
                "name": "task.create.draft",
                "arguments": {"taskType": "DATA_SYNC", "payload": {"hidden": "value"}},
            },
            registered_tools=self.tools,
            visible_tools=tuple(tool for tool in self.tools if tool.name == "datasource.metadata.read"),
        )
        summary = report.to_low_sensitive_summary()

        self.assertEqual((), report.accepted_tool_plans)
        self.assertEqual(1, summary["rejectedBeforeReadinessCount"])
        self.assertEqual(
            ToolActionIntakeBoundary.REJECTED_BEFORE_READINESS.value,
            summary["items"][0]["boundary"],
        )
        self.assertIn("MODEL_TOOL_CALL_NOT_EXPOSED", summary["items"][0]["issueCodes"])

    def test_a2a_task_action_stays_as_control_plane_decision_not_tool_plan(self) -> None:
        """A2A task/action 应进入控制面决策，而不是被伪装成 ToolPlan。"""

        report = self.service.from_a2a_task_action(
            {
                "schemaVersion": "datasmart.agent-runtime.a2a-task-query-preview.v1",
                "previewOnly": True,
                "task": {
                    "taskPublicId": "task_pub_001",
                    "contextPublicId": "ctx_pub_001",
                    "currentState": "TASK_STATE_SUBMITTED",
                    "internalPhase": "POLICY_PRECHECK",
                    "sequence": 1,
                },
                "prompt": "A2A 原始用户消息不能进入摘要",
                "toolArguments": {"datasourceId": "ds-a2a-secret"},
                "targetEndpoint": "http://internal-a2a.local/run",
            }
        )
        summary = report.to_low_sensitive_summary()
        serialized = str(summary)

        self.assertEqual(ToolActionIntakeSource.A2A_TASK_ACTION, report.source)
        self.assertEqual((), report.accepted_tool_plans)
        self.assertEqual(ToolActionIntakeBoundary.A2A_TASK_CONTROL_PLANE_DECISION.value, summary["items"][0]["boundary"])
        self.assertEqual("PRECHECK_REQUIRED", summary["items"][0]["a2aDecision"]["mode"])
        self.assertIn("REQUEST_PERMISSION_PRECHECK", summary["items"][0]["a2aDecision"]["suggestedActions"])
        self.assertNotIn("A2A 原始用户消息", serialized)
        self.assertNotIn("ds-a2a-secret", serialized)
        self.assertNotIn("internal-a2a", serialized)

    def test_invalid_a2a_contract_is_rejected_before_readiness(self) -> None:
        """缺失或未知 A2A 合同应在 readiness 前被拒绝。"""

        report = self.service.from_a2a_task_action(None)
        summary = report.to_low_sensitive_summary()

        self.assertEqual(ToolActionIntakeBoundary.REJECTED_BEFORE_READINESS.value, summary["items"][0]["boundary"])
        self.assertEqual("REJECTED_OR_DIAGNOSTIC", summary["items"][0]["a2aDecision"]["mode"])
        self.assertEqual(1, summary["rejectedBeforeReadinessCount"])


if __name__ == "__main__":
    unittest.main()
