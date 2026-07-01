import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.config import default_model_routes, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.langgraph_multi_agent_collaboration import (
    LangGraphMultiAgentCollaborationWorkflow,
)
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class LangGraphMultiAgentCollaborationTest(unittest.TestCase):
    """LangGraph 多智能体协作图测试。

    这组测试保护的是“LangGraph 是否真正参与多智能体协作控制面”，而不是测试真实工具执行。
    当前协作图只消费低敏会话调度事实，输出全局状态、规划 Agent 覆盖和 handoff 摘要，不能泄露
    objective、工具参数、SQL、样本数据或模型输出。
    """

    def test_fake_langgraph_api_executes_multi_agent_collaboration_graph(self) -> None:
        """注入 fake LangGraph API 时，应执行完整多 Agent 协作节点链。"""

        workflow = LangGraphMultiAgentCollaborationWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )

        diagnostics = workflow.run(
            request=_request(),
            plan=_plan(),
            scheduling=_scheduling(),
        )
        summary = diagnostics.to_summary()

        self.assertEqual("LANGGRAPH_COLLABORATION_EXECUTED", summary["status"])
        self.assertTrue(summary["compiled"])
        self.assertTrue(summary["executed"])
        self.assertTrue(summary["capabilities"]["complexFlowOrchestration"])
        self.assertTrue(summary["capabilities"]["globalStateManagement"])
        self.assertTrue(summary["capabilities"]["multiAgentCollaboration"])
        self.assertEqual(
            (
                "langgraph.multi_agent.ingest_session_scheduling",
                "langgraph.multi_agent.map_agent_roster",
                "langgraph.multi_agent.synchronize_global_state",
                "langgraph.multi_agent.evaluate_handoff_policy",
                "langgraph.multi_agent.finalize_collaboration",
            ),
            summary["nodeTrace"],
        )
        self.assertIn("MASTER_ORCHESTRATOR", summary["implementedProductAgentRoles"])
        self.assertIn("DATASOURCE_ACCESS_AGENT", summary["implementedProductAgentRoles"])
        self.assertIn("DATA_QUALITY_AGENT", summary["implementedProductAgentRoles"])
        self.assertIn("ETL_DEVELOPMENT_AGENT", summary["missingProductAgentRoles"])
        self.assertIn("DATA_ASSET_AGENT", summary["missingProductAgentRoles"])
        self.assertEqual("PERMISSION_OR_HUMAN_APPROVAL", summary["handoffRoute"])
        self.assertEqual("APPROVAL_REQUIRED", summary["globalState"]["schedulingStatus"])
        self.assertEqual(4, summary["globalState"]["participatingAgentCount"])
        self.assertEqual("SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT", summary["globalState"]["checkpointPolicy"])

    def test_plan_response_contains_collaboration_workflow_without_sensitive_payload(self) -> None:
        """`/agent/plans` 响应应包含多智能体协作诊断，且保持低敏。"""

        old_value = os.environ.get("DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED")
        os.environ["DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED"] = "false"
        try:
            response = build_plan_response(
                AgentRequest(
                    tenant_id="tenant-a",
                    project_id="project-a",
                    actor_id="quality-owner",
                    objective="请分析 secret objective 并为 ds-secret 生成质量规则",
                    variables={
                        "datasourceId": "ds-secret",
                        "businessGoal": "secret business goal",
                        "sessionId": "session-collaboration",
                    },
                ),
                AgentOrchestrator(
                    model_routes=ModelRouteRegistry(default_model_routes()),
                    tool_planner=ToolPlanner(default_tool_registry()),
                ),
            )
        finally:
            if old_value is None:
                os.environ.pop("DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED", None)
            else:
                os.environ["DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED"] = old_value

        collaboration = response["agentCollaborationWorkflow"]
        serialized = str(collaboration)

        self.assertEqual("langgraph", collaboration["engine"])
        self.assertEqual("DISABLED", collaboration["status"])
        self.assertEqual(8, collaboration["plannedProductAgentCount"])
        self.assertIn("MASTER_ORCHESTRATOR", collaboration["missingProductAgentRoles"])
        self.assertIn("LOW_SENSITIVE_MULTI_AGENT_CONTROL_STATE_ONLY", collaboration["payloadPolicy"])
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("ds-secret", serialized)
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
        objective="请生成质量规则",
        variables={"sessionId": "session-a"},
    )


def _plan() -> AgentPlan:
    return AgentPlan(
        request_id="request-a",
        selected_route=None,
        state_trace=("receive_goal",),
        tool_plans=(),
        requires_human_approval=True,
        response_summary="测试计划",
    )


def _scheduling() -> dict:
    return {
        "available": True,
        "status": "APPROVAL_REQUIRED",
        "primaryAgentRole": "MASTER_ORCHESTRATOR",
        "participatingAgentCount": 4,
        "participatingAgents": (
            {"role": "MASTER_ORCHESTRATOR", "participationMode": "PRIMARY"},
            {"role": "DATASOURCE_AGENT", "participationMode": "SPECIALIST"},
            {"role": "DATA_QUALITY_AGENT", "participationMode": "SPECIALIST"},
            {"role": "PERMISSION_AGENT", "participationMode": "GUARDRAIL"},
        ),
        "policyAxes": {
            "plannedToolNames": ("quality.rule.suggest",),
            "memoryDependencies": ("semantic", "episodic"),
        },
        "handoffRequired": True,
    }


if __name__ == "__main__":
    unittest.main()
