import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator
from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_execution import DurableAgentLoopService, LangGraphDurableCheckpointerService
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.multi_agent import LangGraphMultiAgentTurnRunnerWorkflow


class MultiAgentTurnRunnerTest(unittest.TestCase):
    """受控多 Agent turn runner 测试。

    这组测试保护的是“多 Agent 能力从 execution session 继续推进到 turn 层合同”。turn runner 不负责
    执行工具或调用模型，但必须把下一轮可以如何推进、哪些 specialist 可被 manager-as-tools 调度、
    哪些证据必须交给 Java 控制面补齐讲清楚。
    """

    def test_fake_langgraph_api_builds_turn_attempts_and_manager_as_tools(self) -> None:
        """注入 fake LangGraph API 时，应完整执行 turn runner 节点链。"""

        workflow = LangGraphMultiAgentTurnRunnerWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )

        diagnostics = workflow.run(
            request=_request(),
            plan=_plan(),
            execution_session=_execution_session(),
            command_proposal_templates=_command_templates(),
            durable_loop={"runId": "run-a", "phase": "ready_for_second_turn", "attributes": {"turnDepth": 1}},
        )
        summary = diagnostics.to_summary()
        serialized = str(summary)

        self.assertEqual("LANGGRAPH_MULTI_AGENT_TURN_RUNNER_BUILT", summary["status"])
        self.assertTrue(summary["compiled"])
        self.assertTrue(summary["executed"])
        self.assertEqual(
            (
                "langgraph.multi_agent_turn.load_execution_session",
                "langgraph.multi_agent_turn.select_turn_candidates",
                "langgraph.multi_agent_turn.build_manager_as_tools",
                "langgraph.multi_agent_turn.bind_knowledge_agent_rag_capabilities",
                "langgraph.multi_agent_turn.enforce_runner_policy",
                "langgraph.multi_agent_turn.prepare_control_plane_handoff",
                "langgraph.multi_agent_turn.finalize_turn_runner",
            ),
            summary["nodeTrace"],
        )
        self.assertEqual("READY_FOR_JAVA_CONTROL_PLANE_HANDOFF", summary["runStatus"])
        self.assertEqual("READY_CONTROL_PLANE_HANDOFF", summary["runnerRoute"])
        self.assertEqual("READY_FOR_JAVA_CONTROL_PLANE_HANDOFF", summary["runnerStatus"])
        self.assertEqual(
            "HANDOFF_TO_JAVA_CONTROL_PLANE_THEN_RESUME_FROM_CHECKPOINT",
            summary["loopDecision"],
        )
        graph_capabilities = summary["runtimeGraphContract"]["capabilities"]
        self.assertTrue(graph_capabilities["nodeContractReady"])
        self.assertTrue(graph_capabilities["conditionalEdgesReady"])
        self.assertTrue(graph_capabilities["loopEdgesReady"])
        self.assertTrue(graph_capabilities["resumableStateReady"])
        self.assertTrue(graph_capabilities["runtimeReady"])
        self.assertTrue(summary["capabilities"]["durableTurnStatePlanning"])
        self.assertTrue(summary["capabilities"]["managerAsToolsPlanning"])
        self.assertTrue(summary["capabilities"]["controlPlaneHandoffPlanning"])
        self.assertTrue(summary["capabilities"]["sideEffectGuardrails"])
        self.assertFalse(summary["capabilities"]["knowledgeRagPlanning"])
        self.assertEqual(2, summary["turnAttemptCount"])
        self.assertEqual(0, summary["knowledgeAgentCapabilityCount"])
        self.assertEqual(2, summary["controlPlaneHandoffCount"])
        self.assertFalse(summary["sideEffectBoundary"]["toolExecutedByPython"])
        self.assertFalse(summary["sideEffectBoundary"]["modelCalledByTurnRunner"])
        self.assertFalse(summary["sideEffectBoundary"]["outboxWrittenByPython"])
        self.assertTrue(summary["sideEffectBoundary"]["javaControlPlaneRequiredForSideEffects"])
        self.assertTrue(
            any(tool["agentRole"] == "DATA_QUALITY_AGENT" for tool in summary["managerAsTools"]),
            "specialist Agent 应以 manager-as-tools 低敏描述进入 runner 合同。",
        )
        self.assertIn(
            "JAVA_COMMAND_PROPOSAL_OR_OUTBOX_REQUIRED",
            summary["turnAttempts"][0]["requiredEvidenceCodes"],
        )
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("secret-datasource-id", serialized)
        self.assertNotIn("select * from hidden_customer", serialized)
        self.assertNotIn("toolArguments", serialized)

    def test_knowledge_agent_turn_binds_rag_capability_contract(self) -> None:
        """KNOWLEDGE_AGENT 参与时，turn runner 应输出 RAG 可调度能力合同。

        这里验证的不是 RAG 算法效果，而是多 Agent 执行层是否真正认识“知识 Agent 可以被主控调度
        去执行受控 RAG”。合同必须声明 LangGraph 节点、证据门控和 Java 控制面边界，同时不能泄露
        用户问题、知识正文、sourceUri 或模型回答。
        """

        workflow = LangGraphMultiAgentTurnRunnerWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )

        diagnostics = workflow.run(
            request=_request(),
            plan=_plan(),
            execution_session={
                "status": "READY_FOR_CONTROL_PLANE_HANDOFF",
                "durablePhase": "ready_for_second_turn",
                "workItems": (
                    {
                        "workItemId": "workitem-knowledge-rag",
                        "agentRole": "KNOWLEDGE_AGENT",
                        "deliveryTier": "controlled_scope",
                        "sessionStatus": "READY_FOR_AGENT_TURN",
                        "resumeAction": "PREPARE_RAG_EVIDENCE_TURN",
                        "executionLane": "DOMAIN_SPECIALIST_DRAFT",
                        "plannedToolCount": 1,
                        "visibleSkillCount": 1,
                        "toolArguments": {"question": "secret rag question", "sourceUri": "internal://hidden-doc"},
                    },
                ),
            },
            command_proposal_templates=_command_templates(),
            durable_loop={"runId": "run-rag", "phase": "ready_for_second_turn", "attributes": {"turnDepth": 1}},
        )
        summary = diagnostics.to_summary()
        capability = summary["knowledgeAgentCapabilities"][0]
        serialized = str(summary)

        self.assertTrue(summary["capabilities"]["knowledgeRagPlanning"])
        self.assertEqual(1, summary["knowledgeAgentCapabilityCount"])
        self.assertEqual("knowledge.rag.query", capability["capabilityCode"])
        self.assertEqual("datasmart.agent.governance-rag", capability["langGraphGraphName"])
        self.assertIn("rag_retrieve_knowledge", capability["langGraphNodes"])
        self.assertIn("rag_evidence_gate", capability["langGraphNodes"])
        self.assertIn("RAG_EVIDENCE_GATE_REQUIRED", capability["requiredEvidenceCodes"])
        self.assertFalse(capability["sideEffectBoundary"]["ragExecutedByTurnRunner"])
        self.assertFalse(capability["sideEffectBoundary"]["modelCalledByTurnRunner"])
        self.assertTrue(capability["sideEffectBoundary"]["javaControlPlaneRequiredForSideEffects"])
        self.assertNotIn("secret rag question", serialized)
        self.assertNotIn("internal://hidden-doc", serialized)

    def test_plan_response_contains_turn_runner_and_runtime_event(self) -> None:
        """`/agent/plans` 应返回 turn runner，并把合同写入 runtime event envelope。"""

        workflow = LangGraphMultiAgentTurnRunnerWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="quality-owner",
            objective="secret objective: 请分析客户订单表并生成质量同步方案。",
            variables={
                "sessionId": "session-turn-runner",
                "datasourceId": "secret-datasource-id",
                "sql": "select * from hidden_customer",
            },
        )

        response = build_plan_response(
            request,
            build_default_orchestrator(),
            durable_agent_loop_service=DurableAgentLoopService(),
            multi_agent_turn_runner_workflow=workflow,
        )

        turn_runner = response["agentTurnRunner"]
        events = [
            event
            for event in response["plan"]["runtime_events"]
            if event["event_type"] == AgentRuntimeEventType.AGENT_TURN_RUNNER_RECORDED
        ]
        serialized = str(turn_runner) + str(events)

        self.assertEqual(1, len(events))
        self.assertIn(events[0], response["eventEnvelope"]["events"])
        self.assertEqual(turn_runner["runStatus"], events[0]["attributes"]["runStatus"])
        self.assertEqual("CONTROLLED_MULTI_AGENT_TURN_RUNNER_VIEW", events[0]["attributes"]["snapshotType"])
        self.assertEqual("LOW_SENSITIVE_MULTI_AGENT_TURN_RUNNER_ONLY", turn_runner["payloadPolicy"])
        self.assertFalse(turn_runner["sideEffectBoundary"]["toolExecutedByPython"])
        self.assertFalse(turn_runner["sideEffectBoundary"]["outboxWrittenByPython"])
        self.assertTrue(turn_runner["sideEffectBoundary"]["workerReceiptRequiredForSideEffects"])
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("secret-datasource-id", serialized)
        self.assertNotIn("hidden_customer", serialized)

    def test_plan_response_records_turn_runner_langgraph_checkpoint(self) -> None:
        """注入 LangGraph checkpointer 后，turn runner 应写入可恢复 checkpoint。

        这个用例保护的是“多 Agent runner 不只是一段响应 JSON”：当应用启动期提供统一
        `LangGraphDurableCheckpointerService` 时，`/agent/plans` 应把 turn runner 的低敏状态写入同一条
        durable thread。后续 pause/resume/fork/recover 才能基于真实 checkpoint 工作，而不是只依赖 HTTP
        调用方记住上一次响应。
        """

        workflow = LangGraphMultiAgentTurnRunnerWorkflow(
            langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
        )
        checkpointer = LangGraphDurableCheckpointerService()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="quality-owner",
            objective="secret objective: 请分析客户订单表并生成质量同步方案。",
            variables={
                "sessionId": "session-turn-runner-checkpoint",
                "datasourceId": "secret-datasource-id",
                "sql": "select * from hidden_customer",
            },
        )

        response = build_plan_response(
            request,
            build_default_orchestrator(),
            durable_agent_loop_service=DurableAgentLoopService(),
            multi_agent_turn_runner_workflow=workflow,
            langgraph_checkpointer_service=checkpointer,
        )

        checkpoint_view = response["agentTurnRunnerCheckpoint"]
        thread_id = checkpoint_view["threadId"]
        latest = checkpointer.latest_for_thread(thread_id)
        recovered = checkpointer.recover_multi_agent_state(thread_id).to_summary()
        serialized = str(checkpoint_view) + str(latest.state if latest is not None else {}) + str(recovered)

        self.assertIsNotNone(latest)
        self.assertEqual("datasmart.agent.multi-agent-turn-runner", checkpoint_view["checkpoint"]["graphName"])
        self.assertEqual("multi_agent_turn_wait_human", checkpoint_view["checkpoint"]["nodeName"])
        self.assertEqual("waiting_human", checkpoint_view["checkpoint"]["status"])
        self.assertIn("wait_approval_fact", checkpoint_view["checkpoint"]["nextNodes"])
        self.assertTrue(recovered["found"])
        self.assertIn("DATA_QUALITY_AGENT", recovered["agentRoles"])
        self.assertTrue(recovered["handoffRequired"])
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("secret-datasource-id", serialized)
        self.assertNotIn("hidden_customer", serialized)


class FakeStateGraph:
    """LangGraph StateGraph 的最小测试替身。"""

    def __init__(self, schema) -> None:  # noqa: ANN001 - fake 只记录协议形状
        self.schema = schema
        self.nodes = {}
        self.edges = {}
        self.conditional_edges = {}

    def add_node(self, node: str, action) -> None:  # noqa: ANN001 - fake 只记录协议形状
        self.nodes[node] = action

    def add_edge(self, start_key: str, end_key: str) -> None:
        self.edges[start_key] = end_key

    def add_conditional_edges(self, source: str, path, path_map) -> None:  # noqa: ANN001
        """记录条件路由函数和有限路由表，模拟 LangGraph 的状态机选择。"""

        self.conditional_edges[source] = (path, dict(path_map))

    def compile(self) -> "FakeCompiledGraph":
        return FakeCompiledGraph(
            nodes=self.nodes,
            edges=self.edges,
            conditional_edges=self.conditional_edges,
        )


class FakeCompiledGraph:
    """编译后 LangGraph 的最小测试替身。"""

    def __init__(self, *, nodes, edges, conditional_edges) -> None:  # noqa: ANN001
        self.nodes = nodes
        self.edges = edges
        self.conditional_edges = conditional_edges

    def invoke(self, input):  # noqa: A002, ANN001, ANN201 - 与 LangGraph API 同名
        state = dict(input)
        current = "START"
        while True:
            if current in self.conditional_edges:
                route_selector, route_map = self.conditional_edges[current]
                next_node = route_map[route_selector(state)]
            else:
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
        variables={"datasourceId": "secret-datasource-id", "sql": "select * from hidden_customer"},
    )


def _plan() -> AgentPlan:
    return AgentPlan(
        request_id="request-a",
        selected_route=None,
        state_trace=("receive_goal",),
        tool_plans=(),
        requires_human_approval=False,
        response_summary="测试计划",
    )


def _execution_session() -> dict:
    return {
        "status": "READY_FOR_CONTROL_PLANE_HANDOFF",
        "durablePhase": "ready_for_second_turn",
        "workItems": (
            {
                "workItemId": "workitem-1-master",
                "agentRole": "MASTER_ORCHESTRATOR",
                "deliveryTier": "must_do",
                "sessionStatus": "READY_FOR_CONTROL_PLANE_HANDOFF",
                "resumeAction": "HANDOFF_TO_JAVA_CONTROL_PLANE",
                "executionLane": "PRIMARY_COORDINATION",
                "plannedToolCount": 1,
                "toolArguments": {"sql": "select * from hidden_customer"},
            },
            {
                "workItemId": "workitem-2-quality",
                "agentRole": "DATA_QUALITY_AGENT",
                "deliveryTier": "must_do",
                "sessionStatus": "READY_FOR_AGENT_TURN",
                "resumeAction": "PREPARE_SPECIALIST_NEXT_TURN",
                "executionLane": "DOMAIN_SPECIALIST_DRAFT",
                "plannedToolCount": 1,
                "visibleSkillCount": 1,
            },
        ),
    }


def _command_templates() -> dict:
    return {
        "totalTemplateCount": 1,
        "targetControlPlaneRoutes": (
            {"method": "POST", "path": "/agent-runtime/tool-action-commands/proposals"},
            {"method": "POST", "path": "/api/agent/tool-action-commands/proposals"},
        ),
    }


if __name__ == "__main__":
    unittest.main()
