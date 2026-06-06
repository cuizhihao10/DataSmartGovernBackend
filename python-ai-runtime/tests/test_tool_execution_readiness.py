import os
import sys
import unittest
from dataclasses import replace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest, ModelToolCall, ToolExecutionMode, ToolPlan, ToolRiskLevel
from datasmart_ai_runtime.services.model_gateway.model_tool_call_planner import ModelToolCallPlanner
from datasmart_ai_runtime.services.tools.tool_execution_readiness import (
    ToolExecutionReadinessDecision,
    ToolExecutionReadinessPolicy,
    ToolExecutionReadinessService,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness_policy_provider import (
    ToolExecutionReadinessPolicyProvider,
)


class ToolExecutionReadinessServiceTest(unittest.TestCase):
    def test_model_tool_plans_are_split_into_ready_draft_and_approval(self) -> None:
        """模型提出的工具计划应被拆分成自动执行、草案展示和审批等待三类准备度。"""

        tools = default_tool_registry()
        report = ModelToolCallPlanner().plan(
            tool_calls=(
                ModelToolCall(
                    call_id="call_meta",
                    name="datasource.metadata.read",
                    arguments="{\"datasourceId\":\"ds-sensitive-001\"}",
                ),
                ModelToolCall(
                    call_id="call_quality",
                    name="quality.rule.suggest",
                    arguments="{\"datasourceId\":\"ds-sensitive-001\",\"businessGoal\":\"客户主数据完整性\"}",
                ),
                ModelToolCall(
                    call_id="call_task",
                    name="task.create.draft",
                    arguments="{\"taskType\":\"DATA_QUALITY_SCAN\",\"payload\":{\"datasourceId\":\"ds-sensitive-001\"}}",
                ),
            ),
            registered_tools=tools,
            visible_tools=tools,
        )

        readiness = ToolExecutionReadinessService().evaluate(report.accepted_tool_plans)

        self.assertEqual(3, readiness.total_count)
        self.assertEqual(
            (
                ToolExecutionReadinessDecision.READY_TO_EXECUTE,
                ToolExecutionReadinessDecision.DRAFT_ONLY,
                ToolExecutionReadinessDecision.WAITING_APPROVAL,
            ),
            tuple(item.decision for item in readiness.items),
        )
        self.assertIn("EXECUTE_READY_TOOLS", readiness.next_actions)
        self.assertIn("CREATE_OR_WAIT_APPROVAL", readiness.next_actions)
        self.assertIn("SHOW_DRAFT_FOR_REVIEW", readiness.next_actions)
        self.assertEqual(1, readiness.approval_required_count)
        self.assertEqual(("datasourceId",), readiness.items[0].sensitive_argument_names)
        self.assertEqual(("payload",), readiness.items[2].sensitive_argument_names)

    def test_missing_required_parameter_requests_clarification_without_leaking_arguments(self) -> None:
        """缺少必须参数时应要求澄清，并且准备度快照不能泄露真实参数值。"""

        tools = default_tool_registry()
        report = ModelToolCallPlanner().plan(
            tool_calls=(ModelToolCall(call_id="call_meta", name="datasource.metadata.read", arguments="{}"),),
            registered_tools=tools,
            visible_tools=tuple(tool for tool in tools if tool.name == "datasource.metadata.read"),
        )

        readiness = ToolExecutionReadinessService().evaluate(report.accepted_tool_plans)
        item = readiness.items[0]

        self.assertEqual(ToolExecutionReadinessDecision.NEEDS_CLARIFICATION, item.decision)
        self.assertFalse(item.executable)
        self.assertEqual(("PARAMETER_CAN_FILL_FROM_CONTEXT:datasourceId",), item.issue_codes)
        self.assertEqual(("REQUEST_USER_CLARIFICATION",), readiness.next_actions)
        self.assertNotIn("ds-", str(item))

    def test_sync_and_async_budget_can_throttle_excessive_tool_calls(self) -> None:
        """单轮工具过多时应进入节流，避免模型一次性压垮后端服务或异步队列。"""

        sync_plan = ToolPlan(
            tool_name="datasource.metadata.read",
            reason="读取元数据。",
            arguments={"datasourceId": "hidden"},
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
        )
        async_plan = ToolPlan(
            tool_name="data-sync.execute",
            reason="执行数据同步任务。",
            arguments={"taskId": "hidden"},
            risk_level=ToolRiskLevel.MEDIUM,
            execution_mode=ToolExecutionMode.ASYNC_TASK,
        )

        readiness = ToolExecutionReadinessService().evaluate(
            (sync_plan, replace(sync_plan, tool_name="datasource.metadata.read.2"), async_plan),
            policy=ToolExecutionReadinessPolicy(max_auto_sync_tools=1, max_async_tools=0),
        )

        self.assertEqual(ToolExecutionReadinessDecision.READY_TO_EXECUTE, readiness.items[0].decision)
        self.assertEqual(ToolExecutionReadinessDecision.THROTTLED, readiness.items[1].decision)
        self.assertEqual(ToolExecutionReadinessDecision.THROTTLED, readiness.items[2].decision)
        self.assertEqual(2, readiness.throttled_count)
        self.assertIn("WAIT_FOR_TOOL_BUDGET", readiness.next_actions)

    def test_critical_risk_is_blocked_before_any_execution_path(self) -> None:
        """CRITICAL 风险工具默认阻断，必须等待平台管理员策略显式放行。"""

        plan = ToolPlan(
            tool_name="dangerous.export.all",
            reason="导出全量敏感数据。",
            arguments={"sql": "select * from sensitive_table"},
            risk_level=ToolRiskLevel.CRITICAL,
            execution_mode=ToolExecutionMode.SYNC,
        )

        readiness = ToolExecutionReadinessService().evaluate((plan,))

        self.assertEqual(ToolExecutionReadinessDecision.BLOCKED, readiness.items[0].decision)
        self.assertEqual(("sql",), readiness.items[0].sensitive_argument_names)
        self.assertEqual(("CRITICAL_RISK_BLOCKED",), readiness.items[0].reason_codes)
        self.assertIn("ESCALATE_TO_OPERATOR", readiness.next_actions)

    def test_trusted_control_plane_policy_can_reduce_execution_budget(self) -> None:
        """受控控制面策略应能按角色、租户套餐、workspace 风险和 worker backlog 收紧 readiness 预算。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="auditor-a",
            objective="请读取数据源元数据",
            variables={
                "trustedControlPlane": {
                    "toolExecutionReadinessPolicy": {
                        "policyVersion": "perm-tool-readiness-v3",
                        "actorRole": "AUDITOR",
                        "tenantPlanCode": "TRIAL",
                        "workspaceRiskLevel": "HIGH",
                        "workerBacklogLevel": "CRITICAL",
                        "maxAutoSyncTools": 5,
                        "maxAsyncTools": 4,
                    }
                },
                "datasourceId": "ds-sensitive-003",
            },
        )
        snapshot = ToolExecutionReadinessPolicyProvider().policy_for(request)
        plan = ToolPlan(
            tool_name="datasource.metadata.read",
            reason="读取元数据",
            arguments={"datasourceId": "ds-sensitive-003"},
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
        )

        readiness = ToolExecutionReadinessService().evaluate(
            (plan,),
            policy=snapshot.policy,
            policy_metadata=snapshot.to_low_sensitive_summary(),
        )

        self.assertEqual("trusted-control-plane", snapshot.source)
        self.assertEqual(0, snapshot.policy.max_auto_sync_tools)
        self.assertEqual(0, snapshot.policy.max_async_tools)
        self.assertIn("READ_ONLY_ROLE_BLOCKS_AUTO_EXECUTION", snapshot.influence_codes)
        self.assertIn("WORKER_BACKLOG_BLOCKS_TOOL_BUDGET", snapshot.influence_codes)
        self.assertEqual(ToolExecutionReadinessDecision.THROTTLED, readiness.items[0].decision)
        self.assertEqual("trusted-control-plane", readiness.policy_metadata["source"])
        self.assertNotIn("ds-sensitive-003", str(readiness.policy_metadata))


if __name__ == "__main__":
    unittest.main()
