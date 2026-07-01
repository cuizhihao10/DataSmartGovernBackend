import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator, build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryRecord,
    AgentMemoryRetrievalReport,
    AgentMemoryRetrievalResult,
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
    AgentMemoryType,
)
from datasmart_ai_runtime.services.agent_workspace import AgentWorkspaceContextBuilder
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.memory import LangGraphMemoryRetrievalWorkflow


class LangGraphMemoryRetrievalWorkflowTest(unittest.TestCase):
    """LangGraph 长期记忆检索观察节点测试。

    这组测试保护的是“retrieve_memory 已经从编排器内部步骤推进到可观测图节点”这件事。图节点只能消费
    低敏记忆计划与召回报告，不能二次暴露长期记忆正文、queryHint、工具参数或用户目标；同时它还要把
    MEMORY_AGENT 与其他专项 Agent 的上下文支持关系放到可观察摘要中，服务后续多 Agent 闭环。
    """

    def test_fake_langgraph_api_observes_memory_retrieval_without_sensitive_payload(self) -> None:
        """注入 fake LangGraph API 时，应完整执行记忆检索观察节点链路。"""

        workflow = LangGraphMemoryRetrievalWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )
        diagnostics = workflow.run(
            memory_plan=_memory_plan(),
            retrieval_report=_retrieval_report(),
            workspace_context=AgentWorkspaceContextBuilder().build(
                AgentRequest(
                    tenant_id="tenant-a",
                    project_id="project-a",
                    actor_id="user-a",
                    objective="secret objective",
                    variables={"sessionId": "session-a"},
                )
            ),
            scheduling=_scheduling(),
            collaboration_execution_plan={"planStatus": "READY_FOR_CONTROL_PLANE_HANDOFF"},
        )
        summary = diagnostics.to_summary()
        serialized = str(summary)

        self.assertEqual("LANGGRAPH_MEMORY_RETRIEVAL_OBSERVED", summary["status"])
        self.assertTrue(summary["compiled"])
        self.assertTrue(summary["executed"])
        self.assertTrue(summary["capabilities"]["langGraphMemoryRetrievalNode"])
        self.assertTrue(summary["capabilities"]["retrievalPlanObserved"])
        self.assertTrue(summary["capabilities"]["retrievalReportObserved"])
        self.assertTrue(summary["capabilities"]["workspaceMemoryBoundaryObserved"])
        self.assertTrue(summary["capabilities"]["multiAgentMemoryContextVisible"])
        self.assertEqual(
            (
                "langgraph.memory_retrieval.load_memory_retrieval_context",
                "langgraph.memory_retrieval.evaluate_retrieval_scope",
                "langgraph.memory_retrieval.summarize_retrieval_report",
                "langgraph.memory_retrieval.bind_memory_agent_context",
                "langgraph.memory_retrieval.finalize_memory_retrieval",
            ),
            summary["nodeTrace"],
        )
        self.assertEqual("RETRIEVAL_AVAILABLE", summary["retrievalStatus"])
        self.assertEqual(2, summary["retrievalTargetCount"])
        self.assertEqual(1, summary["retrievedCount"])
        self.assertEqual({"episodic": 1, "semantic": 1}, summary["retrievalScope"]["memoryTypeCounts"])
        self.assertEqual("project", summary["retrievalScope"]["defaultScope"])
        self.assertEqual("test_retriever", summary["retrievalReport"]["retriever"])
        self.assertFalse(summary["sideEffectBoundary"]["memoryContentReturnedByThisGraph"])
        self.assertFalse(summary["sideEffectBoundary"]["memoryWrittenByThisGraph"])
        self.assertFalse(summary["sideEffectBoundary"]["toolExecuted"])
        self.assertTrue(summary["sideEffectBoundary"]["javaControlPlaneRequiredForMemoryWrite"])
        self.assertTrue(summary["multiAgentMemoryContext"]["memoryAgentScheduled"])
        self.assertEqual("MEMORY_AGENT", summary["multiAgentMemoryContext"]["memoryAgentRole"])
        self.assertIn("DATA_QUALITY_AGENT", summary["multiAgentMemoryContext"]["consumerAgentRoles"])
        self.assertIn("ALLOW_MEMORY_SUMMARY_TO_SUPPORT_SPECIALIST_AGENTS", summary["nextActions"])
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("secret query hint", serialized)
        self.assertNotIn("secret memory content", serialized)
        self.assertNotIn("secret-memory-id", serialized)
        self.assertNotIn("tenant-a", serialized)
        self.assertNotIn("project-a", serialized)

    def test_plan_response_contains_memory_retrieval_workflow_fallback(self) -> None:
        """`/agent/plans` 应返回记忆检索图字段，并在关闭 LangGraph 时给出明确 fallback。"""

        old_enabled = os.environ.get("DATASMART_AI_LANGGRAPH_MEMORY_RETRIEVAL_ENABLED")
        os.environ["DATASMART_AI_LANGGRAPH_MEMORY_RETRIEVAL_ENABLED"] = "false"
        try:
            response = build_plan_response(
                AgentRequest(
                    tenant_id="tenant-a",
                    project_id="project-a",
                    actor_id="user-a",
                    objective="请分析 secret objective 中的 MySQL 数据源",
                    variables={
                        "datasourceId": "ds-sensitive-memory",
                        "businessGoal": "secret business goal",
                        "sessionId": "session-memory-workflow",
                    },
                ),
                build_default_orchestrator(),
            )
        finally:
            _restore_env("DATASMART_AI_LANGGRAPH_MEMORY_RETRIEVAL_ENABLED", old_enabled)

        workflow = response["agentMemoryRetrievalWorkflow"]
        serialized = str(workflow)

        self.assertEqual("langgraph", workflow["engine"])
        self.assertEqual("DISABLED", workflow["status"])
        self.assertFalse(workflow["executed"])
        self.assertEqual("OBSERVE_RETRIEVE_MEMORY_ONLY", workflow["executionBoundary"])
        self.assertEqual("LOW_SENSITIVE_MEMORY_RETRIEVAL_GRAPH_ONLY", workflow["payloadPolicy"])
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("ds-sensitive-memory", serialized)
        self.assertNotIn("secret business goal", serialized)


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


def _memory_plan() -> AgentMemoryPlan:
    return AgentMemoryPlan(
        retrieval_targets=(
            AgentMemoryRetrievalTarget(
                memory_type=AgentMemoryType.SEMANTIC,
                scope=AgentMemoryScope.PROJECT,
                query_hint="secret query hint",
                reason="secret reason",
                max_items=3,
            ),
            AgentMemoryRetrievalTarget(
                memory_type=AgentMemoryType.EPISODIC,
                scope=AgentMemoryScope.PROJECT,
                query_hint="secret query hint",
                reason="secret reason",
                max_items=2,
            ),
        ),
        writable_memory_types=(AgentMemoryType.EPISODIC,),
        default_scope=AgentMemoryScope.PROJECT,
        approval_required_for_write=True,
    )


def _retrieval_report() -> AgentMemoryRetrievalReport:
    target = _memory_plan().retrieval_targets[0]
    return AgentMemoryRetrievalReport(
        results=(
            AgentMemoryRetrievalResult(
                target=target,
                memories=(
                    AgentMemoryRecord(
                        memory_id="secret-memory-id",
                        memory_type=AgentMemoryType.SEMANTIC,
                        scope=AgentMemoryScope.PROJECT,
                        tenant_id="tenant-a",
                        project_id="project-a",
                        title="secret title",
                        content="secret memory content",
                    ),
                ),
            ),
        ),
        total_retrieved=1,
        retrieval_notes=("secret note",),
        attributes={"retriever": "test retriever"},
    )


def _scheduling() -> dict:
    return {
        "status": "READY",
        "participatingAgents": (
            {"role": "MASTER_ORCHESTRATOR", "participationMode": "PRIMARY"},
            {"role": "DATA_QUALITY_AGENT", "participationMode": "SPECIALIST"},
            {"role": "MEMORY_AGENT", "participationMode": "GUARDRAIL"},
        ),
        "policyAxes": {
            "memoryDependencies": ("semantic", "episodic"),
        },
    }


def _restore_env(name: str, value: str | None) -> None:
    if value is None:
        os.environ.pop(name, None)
    else:
        os.environ[name] = value


if __name__ == "__main__":
    unittest.main()
