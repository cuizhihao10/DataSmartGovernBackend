import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import ToolExecutionMode, ToolPlan, ToolRiskLevel
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.tools.langgraph_execution_gate import LangGraphExecutionGateWorkflow
from datasmart_ai_runtime.services.tools.tool_execution_readiness import ToolExecutionReadinessService


class LangGraphExecutionGateWorkflowTest(unittest.TestCase):
    """LangGraph 执行门禁条件图测试。

    测试使用 fake StateGraph，不要求 CI 或本地学习环境真实安装 LangGraph；生产路径仍通过相同协议使用
    `langgraph.graph.StateGraph`。这里重点验证 DataSmart 自己的条件路由、低敏摘要和副作用边界。
    """

    def test_approval_readiness_routes_to_human_approval_gate(self) -> None:
        """需要审批的工具应走审批门禁，而不是被误标记为可恢复执行。"""

        workflow = LangGraphExecutionGateWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )
        readiness = ToolExecutionReadinessService().evaluate(
            (
                ToolPlan(
                    tool_name="task.create.draft",
                    reason="创建治理任务草案。",
                    arguments={"taskType": "DATA_QUALITY_SCAN"},
                    risk_level=ToolRiskLevel.HIGH,
                    execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
                    requires_human_approval=True,
                ),
            )
        )

        diagnostics = workflow.run(readiness)
        summary = diagnostics.to_summary()

        self.assertEqual("LANGGRAPH_EXECUTION_GATE_EVALUATED", summary["status"])
        self.assertTrue(summary["compiled"])
        self.assertTrue(summary["executed"])
        self.assertEqual("HUMAN_APPROVAL", summary["gateRoute"])
        self.assertEqual("WAITING_APPROVAL_FACT", summary["gateStatus"])
        self.assertIn("langgraph.execution_gate.human_approval_gate", summary["nodeTrace"])
        self.assertEqual(("APPROVAL_CONFIRMATION_FACT",), summary["resumeGate"]["requiredFactTypes"])
        self.assertFalse(summary["sideEffectBoundary"]["toolExecuted"])
        self.assertFalse(summary["sideEffectBoundary"]["outboxWritten"])

    def test_ready_tool_routes_to_resume_preflight_without_side_effects(self) -> None:
        """READY 工具也只能进入 Java 控制面预检，不能在 Python execution gate 直接执行。"""

        workflow = LangGraphExecutionGateWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )
        readiness = ToolExecutionReadinessService().evaluate(
            (
                ToolPlan(
                    tool_name="datasource.metadata.read",
                    reason="读取元数据。",
                    arguments={"datasourceId": "ds-sensitive-001"},
                    risk_level=ToolRiskLevel.LOW,
                    execution_mode=ToolExecutionMode.SYNC,
                ),
            )
        )

        summary = workflow.run(readiness).to_summary()

        self.assertEqual("RESUME_PREFLIGHT", summary["gateRoute"])
        self.assertEqual("READY_FOR_JAVA_CONTROL_PLANE_PREFLIGHT", summary["gateStatus"])
        self.assertIn("langgraph.execution_gate.resume_gate_preflight", summary["nodeTrace"])
        self.assertIn("WORKER_RECEIPT_PROJECTION", summary["resumeGate"]["requiredFactTypes"])
        contract = summary["resumeGate"]["resumePreflightContract"]
        self.assertEqual(
            "datasmart.python-ai-runtime.execution-gate-resume-preflight.v1",
            contract["schemaVersion"],
        )
        self.assertIn("checkpointId", contract["checkpointLocator"]["fieldNames"])
        self.assertIn("commandId", contract["checkpointLocator"]["fieldNames"])
        self.assertIn("resumeGateGraphPreview", contract["resumeFactBundle"]["javaRoutes"])
        self.assertIn("sideEffectExecuted", contract["workerReceipt"]["publicFieldNames"])
        self.assertTrue(contract["sideEffectPolicy"]["javaControlPlaneRequiredForSideEffects"])
        self.assertTrue(summary["sideEffectBoundary"]["javaControlPlaneRequiredForSideEffects"])
        self.assertTrue(summary["sideEffectBoundary"]["workerReceiptRequiredForSideEffects"])
        self.assertNotIn("ds-sensitive-001", str(summary))

    def test_missing_langgraph_dependency_falls_back_open_for_plan_response(self) -> None:
        """LangGraph 缺失时应返回明确诊断，不应中断计划响应。"""

        readiness = ToolExecutionReadinessService().evaluate(())
        diagnostics = MissingDependencyWorkflow().run(readiness)

        self.assertEqual("DEPENDENCY_MISSING", diagnostics.status)
        self.assertTrue(diagnostics.fallback_used)
        self.assertEqual("INSTALL_python_ai_runtime_api_OR_langgraph", diagnostics.fallback_reason)


class MissingDependencyWorkflow(LangGraphExecutionGateWorkflow):
    """测试替身：强制模拟未安装 LangGraph。"""

    def _import_langgraph_api(self):  # noqa: ANN201 - 测试替身保持与生产方法同形状
        return None


class FakeStateGraph:
    """LangGraph StateGraph 的最小条件边测试替身。"""

    def __init__(self, schema) -> None:  # noqa: ANN001 - fake 只记录协议形状
        self.schema = schema
        self.nodes = {}
        self.edges = {}
        self.conditional_edges = {}

    def add_node(self, node: str, action) -> None:  # noqa: ANN001 - fake 只记录协议形状
        self.nodes[node] = action

    def add_edge(self, start_key: str, end_key: str) -> None:
        self.edges[start_key] = end_key

    def add_conditional_edges(self, source: str, path, path_map) -> None:  # noqa: ANN001 - fake 协议
        self.conditional_edges[source] = (path, path_map)

    def compile(self) -> "FakeCompiledGraph":
        return FakeCompiledGraph(nodes=self.nodes, edges=self.edges, conditional_edges=self.conditional_edges)


class FakeCompiledGraph:
    """编译后 LangGraph 的最小测试替身。"""

    def __init__(self, *, nodes, edges, conditional_edges) -> None:  # noqa: ANN001 - fake 协议
        self.nodes = nodes
        self.edges = edges
        self.conditional_edges = conditional_edges

    def invoke(self, input):  # noqa: A002, ANN001, ANN201 - 与 LangGraph API 同名
        state = dict(input)
        current = "START"
        while True:
            if current in self.conditional_edges:
                router, path_map = self.conditional_edges[current]
                next_node = path_map[router(state)]
            else:
                next_node = self.edges[current]
            if next_node == "END":
                return state
            state = self.nodes[next_node](state)
            current = next_node


if __name__ == "__main__":
    unittest.main()
