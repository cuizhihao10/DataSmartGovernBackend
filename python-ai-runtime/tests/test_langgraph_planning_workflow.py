import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.langgraph_planning_workflow import (
    AgentPlanningWorkflowDiagnostics,
    LangGraphAgentPlanningWorkflow,
    LangGraphApi,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class LangGraphPlanningWorkflowTest(unittest.TestCase):
    """LangGraph 工作流外壳测试。

    这些测试不要求本地真实安装 `langgraph`。生产环境通过 `python-ai-runtime[api]` 安装真实依赖，
    单元测试则注入 fake Graph API，验证 DataSmart 自己的低敏状态、fallback 和诊断语义。
    """

    def test_fake_langgraph_api_compiles_and_invokes_workflow_nodes(self) -> None:
        """注入 LangGraph-compatible fake API 时，应执行完整节点链路。"""

        workflow = LangGraphAgentPlanningWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )

        diagnostics = workflow.run(_request())

        self.assertEqual("LANGGRAPH_EXECUTED", diagnostics.status)
        self.assertTrue(diagnostics.dependency_available)
        self.assertTrue(diagnostics.compiled)
        self.assertTrue(diagnostics.executed)
        self.assertFalse(diagnostics.fallback_used)
        self.assertEqual(
            (
                "langgraph.receive_goal",
                "langgraph.governance_gate",
                "langgraph.existing_orchestrator_handoff",
                "langgraph.finalize",
            ),
            diagnostics.node_trace,
        )
        self.assertEqual(
            "PREVIEW_ONLY_NO_TOOL_EXECUTION_NO_OUTBOX_NO_MODEL_CALL",
            diagnostics.control_policy,
        )

    def test_missing_langgraph_dependency_falls_back_without_breaking_plan_path(self) -> None:
        """LangGraph 未安装时，应明确返回缺依赖诊断，而不是让主计划链路失败。"""

        workflow = MissingDependencyWorkflow()

        diagnostics = workflow.run(_request())

        self.assertEqual("DEPENDENCY_MISSING", diagnostics.status)
        self.assertFalse(diagnostics.dependency_available)
        self.assertFalse(diagnostics.compiled)
        self.assertFalse(diagnostics.executed)
        self.assertTrue(diagnostics.fallback_used)
        self.assertEqual("INSTALL_python_ai_runtime_api_OR_langgraph", diagnostics.fallback_reason)

    def test_orchestrator_attaches_workflow_diagnostics_to_agent_plan(self) -> None:
        """AgentOrchestrator 应把 workflow 低敏诊断放入 AgentPlan。"""

        orchestrator = AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            planning_workflow=StaticWorkflow(),
        )

        plan = orchestrator.plan(_request())

        self.assertEqual("langgraph", plan.workflow_diagnostics["engine"])
        self.assertEqual("LANGGRAPH_EXECUTED", plan.workflow_diagnostics["status"])
        self.assertIn("workflow:langgraph_executed", plan.state_trace)
        self.assertIn("build_context", plan.state_trace)


class MissingDependencyWorkflow(LangGraphAgentPlanningWorkflow):
    """测试替身：无论本机是否安装 langgraph，都模拟缺依赖。"""

    def _import_langgraph_api(self):  # noqa: ANN201 - 测试替身保持与生产方法同形状
        return None


class StaticWorkflow:
    """测试替身：给编排器返回稳定 workflow 诊断。"""

    def run(self, request: AgentRequest) -> AgentPlanningWorkflowDiagnostics:
        return AgentPlanningWorkflowDiagnostics(
            engine="langgraph",
            status="LANGGRAPH_EXECUTED",
            enabled=True,
            dependency_available=True,
            compiled=True,
            executed=True,
            graph_nodes=("receive_goal",),
            graph_edges=("START->receive_goal",),
            node_trace=("langgraph.receive_goal",),
            control_policy="PREVIEW_ONLY_NO_TOOL_EXECUTION_NO_OUTBOX_NO_MODEL_CALL",
            fallback_used=False,
        )


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
        objective="请分析 MySQL 数据源结构",
        variables={"datasourceId": "ds-001"},
    )
