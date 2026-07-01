import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.config import default_model_routes, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.multi_agent.langgraph_execution_plan import (
    LangGraphMultiAgentExecutionPlanWorkflow,
)
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class LangGraphMultiAgentExecutionPlanTest(unittest.TestCase):
    """LangGraph 多智能体执行前计划测试。

    这组测试保护的是“多 Agent 协作能否从诊断图继续推进到执行前合同”。执行前合同会列出工作项、
    协作边、守门策略和下一步动作，但仍然不能执行工具、不能创建审批单、不能写 outbox，也不能泄露
    用户目标、工具参数、SQL、样本数据或模型输出。
    """

    def test_fake_langgraph_api_builds_execution_plan_work_items_and_edges(self) -> None:
        """注入 fake LangGraph API 时，应完整执行多 Agent 执行计划节点链。"""

        workflow = LangGraphMultiAgentExecutionPlanWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )

        diagnostics = workflow.run(
            request=_request(),
            plan=_plan(),
            scheduling=_scheduling(),
            collaboration={"status": "LANGGRAPH_COLLABORATION_EXECUTED"},
        )
        summary = diagnostics.to_summary()
        serialized = str(summary)

        self.assertEqual("LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_BUILT", summary["status"])
        self.assertTrue(summary["compiled"])
        self.assertTrue(summary["executed"])
        self.assertTrue(summary["capabilities"]["agentWorkItemPlanning"])
        self.assertTrue(summary["capabilities"]["collaborationEdgePlanning"])
        self.assertTrue(summary["capabilities"]["guardrailAwareExecutionBoundary"])
        self.assertEqual(
            (
                "langgraph.multi_agent_execution.load_collaboration_context",
                "langgraph.multi_agent_execution.assign_agent_work_items",
                "langgraph.multi_agent_execution.build_collaboration_edges",
                "langgraph.multi_agent_execution.enforce_execution_boundaries",
                "langgraph.multi_agent_execution.finalize_execution_plan",
            ),
            summary["nodeTrace"],
        )
        self.assertEqual("WAITING_HUMAN_OR_PERMISSION_HANDOFF", summary["planStatus"])
        self.assertEqual("PRE_EXECUTION_MULTI_AGENT_PLAN_ONLY", summary["executionBoundary"])
        self.assertFalse(summary["executionPolicy"]["toolExecuted"])
        self.assertFalse(summary["executionPolicy"]["outboxWritten"])
        self.assertFalse(summary["executionPolicy"]["approvalCreated"])
        self.assertTrue(summary["executionPolicy"]["javaControlPlaneRequiredForSideEffects"])
        self.assertTrue(summary["executionPolicy"]["workerReceiptRequiredForSideEffects"])
        self.assertGreaterEqual(summary["workItemCount"], 5)
        roles = {item["agentRole"] for item in summary["workItems"]}
        self.assertIn("MASTER_ORCHESTRATOR", roles)
        self.assertIn("DATASOURCE_AGENT", roles)
        self.assertIn("DATA_QUALITY_AGENT", roles)
        self.assertIn("PERMISSION_AGENT", roles)
        self.assertIn("MEMORY_AGENT", roles)
        self.assertIn("QUALITY_NEEDS_METADATA_CONTEXT", {edge["reasonCode"] for edge in summary["collaborationEdges"]})
        self.assertIn("SIDE_EFFECT_BOUNDARY_GUARDED", {edge["reasonCode"] for edge in summary["collaborationEdges"]})
        self.assertIn("CREATE_OR_WAIT_HOST_APPROVAL_FACT", summary["nextActions"])
        self.assertNotIn("secret-datasource-id", serialized)
        self.assertNotIn("secret business goal", serialized)
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("datasourceId", serialized)

    def test_plan_response_contains_execution_plan_without_sensitive_payload(self) -> None:
        """`/agent/plans` 应返回执行前计划字段，并在禁用 LangGraph 时保持可解释 fallback。"""

        old_workflow = os.environ.get("DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED")
        old_execution_plan = os.environ.get("DATASMART_AI_LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_ENABLED")
        os.environ["DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED"] = "false"
        os.environ["DATASMART_AI_LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_ENABLED"] = "false"
        try:
            response = build_plan_response(
                AgentRequest(
                    tenant_id="tenant-a",
                    project_id="project-a",
                    actor_id="quality-owner",
                    objective="请分析 secret objective 并为 secret-datasource-id 生成质量治理计划",
                    variables={
                        "datasourceId": "secret-datasource-id",
                        "businessGoal": "secret business goal",
                        "sessionId": "session-execution-plan",
                    },
                ),
                AgentOrchestrator(
                    model_routes=ModelRouteRegistry(default_model_routes()),
                    tool_planner=ToolPlanner(default_tool_registry()),
                ),
            )
        finally:
            _restore_env("DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED", old_workflow)
            _restore_env("DATASMART_AI_LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_ENABLED", old_execution_plan)

        execution_plan = response["agentCollaborationExecutionPlan"]
        serialized = str(execution_plan)

        self.assertEqual("langgraph", execution_plan["engine"])
        self.assertEqual("DISABLED", execution_plan["status"])
        self.assertEqual("PRE_EXECUTION_MULTI_AGENT_PLAN_ONLY", execution_plan["executionBoundary"])
        self.assertEqual("LOW_SENSITIVE_MULTI_AGENT_EXECUTION_PLAN_ONLY", execution_plan["payloadPolicy"])
        self.assertFalse(execution_plan["executed"])
        self.assertEqual(0, execution_plan["workItemCount"])
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("secret-datasource-id", serialized)
        self.assertNotIn("secret business goal", serialized)
        self.assertNotIn("datasourceId", serialized)


class FakeStateGraph:
    """LangGraph StateGraph 的最小测试替身。"""

    def __init__(self, schema) -> None:  # noqa: ANN001 - fake 只记录协议形状
        self.schema = schema
        self.nodes = {}
        self.edges = {}

    def add_node(self, node: str, action) -> None:  # noqa: ANN001 - fake 只记录协议形状
        self.nodes[node] = action

    def add_edge(self, start_key: str, end_key: str) -> None:
        self.edges[start_key] = end_key

    def compile(self) -> "FakeCompiledGraph":
        return FakeCompiledGraph(nodes=self.nodes, edges=self.edges)


class FakeCompiledGraph:
    """编译后 LangGraph 的最小测试替身。"""

    def __init__(self, *, nodes, edges) -> None:  # noqa: ANN001 - fake 只记录协议形状
        self.nodes = nodes
        self.edges = edges

    def invoke(self, input):  # noqa: A002, ANN001, ANN201 - 与 LangGraph API 同名
        state = dict(input)
        current = "START"
        while True:
            next_node = self.edges[current]
            if next_node == "END":
                return state
            state = self.nodes[next_node](state)
            current = next_node


def _request() -> AgentRequest:
    return AgentRequest(
        tenant_id="tenant-a",
        project_id="project-a",
        actor_id="user-a",
        objective="secret objective",
        variables={"datasourceId": "secret-datasource-id", "businessGoal": "secret business goal"},
    )


def _plan() -> AgentPlan:
    return AgentPlan(
        request_id="request-a",
        selected_route=None,
        state_trace=("receive_goal",),
        tool_plans=(
            ToolPlan(
                tool_name="quality.rule.suggest",
                reason="should not leak reason text",
                arguments={"datasourceId": "secret-datasource-id", "businessGoal": "secret business goal"},
            ),
        ),
        requires_human_approval=True,
        response_summary="测试计划",
    )


def _scheduling() -> dict:
    return {
        "available": True,
        "status": "APPROVAL_REQUIRED",
        "primaryAgentRole": "MASTER_ORCHESTRATOR",
        "participatingAgents": (
            {
                "role": "MASTER_ORCHESTRATOR",
                "participationMode": "PRIMARY",
                "plannedToolNames": ("quality.rule.suggest",),
                "status": "APPROVAL_REQUIRED",
                "requiresHandoff": True,
            },
            {
                "role": "DATASOURCE_AGENT",
                "participationMode": "SPECIALIST",
                "plannedToolNames": ("datasource.metadata.read",),
                "status": "APPROVAL_REQUIRED",
                "requiresHandoff": False,
            },
            {
                "role": "DATA_QUALITY_AGENT",
                "participationMode": "SPECIALIST",
                "plannedToolNames": ("quality.rule.suggest",),
                "status": "APPROVAL_REQUIRED",
                "requiresHandoff": True,
            },
            {
                "role": "PERMISSION_AGENT",
                "participationMode": "GUARDRAIL",
                "status": "APPROVAL_REQUIRED",
                "requiresHandoff": True,
            },
            {
                "role": "MEMORY_AGENT",
                "participationMode": "GUARDRAIL",
                "memoryDependencies": ("semantic", "episodic"),
                "status": "READY",
                "requiresHandoff": False,
            },
        ),
        "policyAxes": {
            "plannedToolNames": ("quality.rule.suggest", "datasource.metadata.read"),
            "visibleSkillCodes": ("quality.rule.design",),
            "memoryDependencies": ("semantic", "episodic"),
        },
        "handoffRequired": True,
    }


def _restore_env(name: str, value: str | None) -> None:
    if value is None:
        os.environ.pop(name, None)
    else:
        os.environ[name] = value


if __name__ == "__main__":
    unittest.main()
