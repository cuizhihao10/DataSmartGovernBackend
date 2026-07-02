import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.agent_graph import (
    AgentGraphEdgeContract,
    AgentGraphEdgeKind,
    AgentGraphNodeContract,
    AgentGraphRuntimeContract,
    AgentGraphStateContract,
    build_multi_agent_turn_runner_graph_contract,
    review_agent_graph_contract,
)


class AgentGraphRuntimeContractTest(unittest.TestCase):
    """统一 Agent Graph 合同测试。

    这组测试不是只检查 DTO 能否序列化，而是保护项目对 LangGraph 的核心定义：执行型 Agent 图必须具有
    可替换节点、条件边、受控循环、可恢复状态以及 Java 控制面副作用边界。
    """

    def test_multi_agent_turn_runner_contract_is_runtime_ready(self) -> None:
        """Turn Runner 应完整声明节点、条件分支、跨请求循环和恢复状态。"""

        contract = build_multi_agent_turn_runner_graph_contract()
        summary = contract.to_summary()
        capabilities = summary["capabilities"]
        state = summary["state"]

        self.assertTrue(capabilities["runtimeReady"])
        self.assertTrue(capabilities["nodeContractReady"])
        self.assertTrue(capabilities["conditionalEdgesReady"])
        self.assertTrue(capabilities["loopEdgesReady"])
        self.assertTrue(capabilities["resumableStateReady"])
        self.assertTrue(capabilities["lowSensitiveStateReady"])
        self.assertTrue(capabilities["javaControlPlaneBoundaryReady"])
        self.assertEqual(("requestId", "runId", "sessionId"), state["identityFields"])
        self.assertIn("runnerRoute", state["progressFields"])
        self.assertIn("loopDecision", state["progressFields"])
        self.assertIn("prompt", state["forbiddenPayloads"])
        self.assertIn("ToolPlan.arguments", state["forbiddenPayloads"])
        self.assertTrue(any(edge["kind"] == "conditional" for edge in summary["edges"]))
        self.assertTrue(any(edge["kind"] == "loop" for edge in summary["edges"]))

    def test_execution_graph_without_conditional_or_loop_edges_fails_review(self) -> None:
        """固定流水线不能被误报为完整 Durable Agent Runtime。"""

        contract = AgentGraphRuntimeContract(
            graph_id="test.linear_graph",
            graph_name="线性测试图",
            graph_kind="execution",
            purpose="验证审计器不会把固定流水线误判为完整 Agent 状态机。",
            nodes=(
                AgentGraphNodeContract(
                    node_id="only_node",
                    responsibility="执行单一步骤。",
                    input_policy="低敏输入。",
                    output_policy="低敏输出。",
                    side_effect_policy="无副作用。",
                ),
            ),
            edges=(
                AgentGraphEdgeContract(
                    source="START",
                    target="only_node",
                    kind=AgentGraphEdgeKind.DIRECT,
                    control_meaning="固定进入单一步骤。",
                ),
            ),
            state=AgentGraphStateContract(
                schema_name="LinearState",
                schema_version="v1",
                low_sensitive=True,
                serializable=True,
                checkpointable=True,
                resumable=True,
                identity_fields=("runId",),
                progress_fields=("trace",),
                control_fields=(),
                forbidden_payloads=("prompt",),
            ),
            java_control_plane_required=True,
            side_effect_policy="NO_DIRECT_SIDE_EFFECT",
            observability_policy="NODE_TRACE",
            replacement_policy="NODE_REPLACEABLE",
        )

        review = review_agent_graph_contract(contract)

        self.assertFalse(review.conditional_edges_ready)
        self.assertFalse(review.loop_edges_ready)
        self.assertFalse(review.to_summary()["runtimeReady"])
        self.assertIn("CONDITIONAL_EDGE_REQUIRED_FOR_AGENT_STATE_MACHINE", review.missing_capabilities)
        self.assertIn("LOOP_EDGE_REQUIRED_FOR_DURABLE_AGENT_LOOP", review.missing_capabilities)

    def test_observation_graph_can_explicitly_disable_branch_and_loop_requirements(self) -> None:
        """观察型图不应为了通过审计而制造没有业务意义的假分支或假循环。"""

        contract = _linear_graph_contract(
            graph_id="test.observation_graph",
            graph_kind="observation",
            requires_conditional_edges=False,
            requires_loop_edges=False,
        )

        review = review_agent_graph_contract(contract)

        self.assertFalse(review.conditional_edges_ready)
        self.assertFalse(review.loop_edges_ready)
        self.assertTrue(review.to_summary()["runtimeReady"])

def _linear_graph_contract(
    *,
    graph_id: str,
    graph_kind: str,
    requires_conditional_edges: bool,
    requires_loop_edges: bool,
) -> AgentGraphRuntimeContract:
    """构造低敏线性图，供观察型图需求开关测试使用。"""

    return AgentGraphRuntimeContract(
        graph_id=graph_id,
        graph_name="线性观察图",
        graph_kind=graph_kind,
        purpose="验证观察型图可以显式关闭条件边和循环边要求。",
        nodes=(
            AgentGraphNodeContract(
                node_id="observe",
                responsibility="读取并压缩低敏事实。",
                input_policy="低敏输入。",
                output_policy="低敏输出。",
                side_effect_policy="无副作用。",
            ),
        ),
        edges=(
            AgentGraphEdgeContract(
                source="START",
                target="observe",
                kind=AgentGraphEdgeKind.DIRECT,
                control_meaning="固定进入观察步骤。",
            ),
        ),
        state=AgentGraphStateContract(
            schema_name="ObservationState",
            schema_version="v1",
            low_sensitive=True,
            serializable=True,
            checkpointable=True,
            resumable=True,
            identity_fields=("runId",),
            progress_fields=("trace",),
            control_fields=(),
            forbidden_payloads=("prompt",),
        ),
        java_control_plane_required=True,
        side_effect_policy="NO_DIRECT_SIDE_EFFECT",
        observability_policy="NODE_TRACE",
        replacement_policy="NODE_REPLACEABLE",
        requires_conditional_edges=requires_conditional_edges,
        requires_loop_edges=requires_loop_edges,
    )


if __name__ == "__main__":
    unittest.main()
