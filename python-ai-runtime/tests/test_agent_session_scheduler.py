import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.config import default_model_routes, default_skill_registry, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest, ModelInvocationResult, ModelToolCall
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.multi_agent import LangGraphMultiAgentTurnRunnerWorkflow
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class AgentSessionSchedulerTest(unittest.TestCase):
    """智能网关多 Agent 会话调度策略测试。

    这些用例保护的是商业化 Agent Host 的控制面契约，而不是某个具体模型输出：
    - 主控 Agent 必须始终存在；
    - 专家 Agent 要能根据治理域、Skill 和工具计划参与；
    - 权限、预算、审批和记忆缺口要以低敏摘要形式暴露；
    - 响应不能泄露 prompt、工具原始参数、记忆正文或样本数据。
    """

    def test_full_sync_assistant_schedules_real_multi_agent_roster_and_langgraph_plan(self) -> None:
        response = build_plan_response(
            AgentRequest(
                tenant_id="10",
                project_id="101",
                actor_id="1001",
                objective="把 MySQL 两张客户表全量同步到 PostgreSQL public schema 的同名表",
                variables={
                    "runtimeProfile": "production",
                    "sessionId": "session-full-sync-agents",
                    "grantedPermissions": (
                        "datasource:connection:test",
                        "datasource:metadata:read",
                        "sync:task:create",
                        "sync:task:precheck",
                        "sync:task:publish",
                        "sync:task:run",
                        "sync:execution:view",
                    ),
                    "dataSyncRequest": {
                        "taskName": "客户表全量同步",
                        "sourceDatasourceId": 23,
                        "targetDatasourceId": 24,
                        "syncMode": "FULL",
                        "writeStrategy": "INSERT",
                        "objectMappings": (
                            {
                                "sourceObjectName": "fs_test_customer_source",
                                "targetSchemaName": "public",
                                "targetObjectName": "fs_test_customer_source",
                            },
                            {
                                "sourceObjectName": "fs_test_customer_target",
                                "targetSchemaName": "public",
                                "targetObjectName": "fs_test_customer_target",
                            },
                        ),
                    },
                },
            ),
            self._orchestrator(),
        )

        scheduling = response["intelligentGatewayGovernance"]["agentSessionScheduling"]
        roles = {agent["role"] for agent in scheduling["participatingAgents"]}
        execution_roles = {
            item["agentRole"]
            for item in response["agentCollaborationExecutionPlan"]["workItems"]
        }

        self.assertTrue(
            {
                "MASTER_ORCHESTRATOR",
                "DATASOURCE_AGENT",
                "DATA_SYNC_AGENT",
                "TASK_AGENT",
                "PERMISSION_AGENT",
                "OPS_AGENT",
            }.issubset(roles)
        )
        self.assertTrue(roles.issubset(execution_roles))
        self.assertTrue(scheduling["handoffRequired"])
        self.assertIn("sync.task.run", scheduling["policyAxes"]["plannedToolNames"])
        self.assertIn("sync.execution.status", scheduling["policyAxes"]["plannedToolNames"])

    def test_data_quality_session_schedules_master_quality_datasource_and_memory_agents(self) -> None:
        """数据质量场景应调度主控、数据源、质量和记忆 Agent。

        质量规则设计不是一个单点工具调用：它通常需要先读数据源元数据，再生成质量规则草案，还要参考
        长期记忆中的历史规则、异常模式和审批经验。因此这里期望智能网关把多个专家 Agent 纳入同一
        会话策略视图，而不是只返回一个模糊的“模型回答可用”。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="quality-owner",
                objective="请为客户主数据生成质量规则，并分析相关数据源结构",
                variables={
                    "datasourceId": "ds-quality",
                    "businessGoal": "识别手机号格式、客户编号唯一性和关键字段完整性问题",
                    "grantedPermissions": (
                        "datasource:metadata:read",
                        "quality:rule:draft",
                    ),
                    "actorRole": "PROJECT_OWNER",
                    "sessionId": "session-quality-agents",
                },
            ),
            self._orchestrator(),
            multi_agent_turn_runner_workflow=LangGraphMultiAgentTurnRunnerWorkflow(
                langgraph_api=LangGraphApi(state_graph=FakeStateGraph, start="START", end="END")
            ),
        )

        scheduling = response["intelligentGatewayGovernance"]["agentSessionScheduling"]
        roles = {agent["role"] for agent in scheduling["participatingAgents"]}

        self.assertTrue(scheduling["available"])
        self.assertEqual("READY", scheduling["status"])
        self.assertEqual("MASTER_ORCHESTRATOR", scheduling["primaryAgentRole"])
        self.assertIn("MASTER_ORCHESTRATOR", roles)
        self.assertIn("DATASOURCE_AGENT", roles)
        self.assertIn("DATA_QUALITY_AGENT", roles)
        self.assertIn("MEMORY_AGENT", roles)
        self.assertIn("data_quality", scheduling["policyAxes"]["intentDomains"])
        self.assertIn("quality.rule.design", scheduling["policyAxes"]["selectedSkillCodes"])
        self.assertIn("quality.rule.suggest", scheduling["policyAxes"]["plannedToolNames"])
        self.assertIn("semantic", scheduling["policyAxes"]["memoryDependencies"])
        self.assertIn("episodic", scheduling["policyAxes"]["memoryDependencies"])

    def test_high_risk_task_session_requires_handoff(self) -> None:
        """高风险任务创建场景应暴露审批 handoff。

        任务创建会改变后续执行状态，即使当前只是草案，也应进入 Java 控制面的审批/审计闭环。因此调度
        视图需要同时激活任务 Agent 和权限 Agent，并明确 `handoffRequired=true`。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="task-owner",
                objective="create task and run after approval",
                variables={
                    "createTask": True,
                    "grantedPermissions": ("task:create",),
                    "actorRole": "PROJECT_OWNER",
                    "sessionId": "session-task-handoff",
                },
            ),
            self._orchestrator(),
        )

        scheduling = response["intelligentGatewayGovernance"]["agentSessionScheduling"]
        roles = {agent["role"] for agent in scheduling["participatingAgents"]}

        self.assertTrue(scheduling["available"])
        self.assertEqual("APPROVAL_REQUIRED", scheduling["status"])
        self.assertTrue(scheduling["handoffRequired"])
        self.assertIn("TASK_AGENT", roles)
        self.assertIn("PERMISSION_AGENT", roles)
        self.assertTrue(any(agent["requiresHandoff"] for agent in scheduling["participatingAgents"]))
        self.assertTrue(any("审批" in action or "handoff" in action for action in scheduling["recommendedActions"]))

    def test_governance_rag_session_schedules_knowledge_agent_and_turn_capability(self) -> None:
        """治理知识 RAG 场景应激活 KNOWLEDGE_AGENT，并进入 turn runner 能力合同。

        这个用例保护的是“RAG 不只是独立 HTTP API”：当用户请求平台内部知识问答时，智能网关应调度
        `KNOWLEDGE_AGENT`，执行前计划应出现对应工具名，turn runner 应暴露 `knowledge.rag.query`
        能力合同，后续 Java 控制面才能按同一合同创建 proposal、checkpoint 和 worker receipt。
        """

        raw_question = "质量规则为什么需要元数据证据"
        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="analyst-a",
                objective="请从治理知识库解释质量规则为什么需要元数据证据",
                variables={
                    "knowledgeQuery": raw_question,
                    "grantedPermissions": ("agent:rag:query",),
                    "sessionId": "session-knowledge-rag",
                },
            ),
            self._orchestrator(),
        )

        scheduling = response["intelligentGatewayGovernance"]["agentSessionScheduling"]
        roles = {agent["role"] for agent in scheduling["participatingAgents"]}
        turn_runner = response["agentTurnRunner"]
        serialized = str(scheduling) + str(turn_runner) + str(response["plan"]["tool_plans"])

        self.assertIn("KNOWLEDGE_AGENT", roles)
        self.assertIn("knowledge.rag.query", scheduling["policyAxes"]["plannedToolNames"])
        self.assertIn("knowledge.rag.answer", scheduling["policyAxes"]["selectedSkillCodes"])
        self.assertTrue(turn_runner["capabilities"]["knowledgeRagPlanning"])
        self.assertEqual(1, turn_runner["knowledgeAgentCapabilityCount"])
        self.assertEqual("knowledge.rag.query", turn_runner["knowledgeAgentCapabilities"][0]["capabilityCode"])
        self.assertNotIn(raw_question, serialized)

    def test_tool_budget_degradation_schedules_ops_agent_without_sensitive_payload(self) -> None:
        """工具预算降级应调度运行治理 Agent，且不泄露敏感内容。

        模型一次提出过多工具调用时，智能网关不能假装一切正常；它应提示拆分批次或调整预算。同时摘要
        只能返回工具名、计数和策略结论，不能把模型生成的原始 JSON 参数或用户目标全文写进调度视图。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="operator-a",
                objective="请连续读取多个数据源元数据",
                variables={
                    "datasourceId": "ds-sensitive",
                    "streamModelIntent": False,
                    "sessionId": "session-budget-degraded",
                },
            ),
            self._orchestrator(provider=self._four_metadata_tool_call_provider()),
        )

        scheduling = response["intelligentGatewayGovernance"]["agentSessionScheduling"]
        serialized = str(scheduling)
        roles = {agent["role"] for agent in scheduling["participatingAgents"]}

        self.assertTrue(scheduling["available"])
        self.assertEqual("DEGRADED", scheduling["status"])
        self.assertIn("OPS_AGENT", roles)
        self.assertFalse(scheduling["policyAxes"]["toolBudgetAllowed"])
        self.assertIn("MODEL_TOOL_CALL_BUDGET_BLOCKED", serialized)
        self.assertNotIn("ds-sensitive", serialized)
        self.assertNotIn("请连续读取多个数据源元数据", serialized)
        self.assertNotIn('"datasourceId"', serialized)

    def test_session_scheduling_snapshot_is_recorded_as_runtime_event(self) -> None:
        """多 Agent 会话调度视图应进入 runtime event。

        这个测试保护的是可回放能力：如果调度视图只存在于 HTTP 顶层响应，WebSocket 断线恢复、Kafka
        消费、Java projection 或审计导出都无法知道当时有哪些 Agent 参与。事件属性必须保持低敏，
        只保存角色、计数、状态和策略轴，不保存用户目标、工具参数或 datasourceId。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="quality-owner",
                objective="请为客户主数据生成质量规则，并分析相关数据源结构",
                variables={
                    "datasourceId": "ds-quality-secret",
                    "businessGoal": "识别客户编号唯一性和关键字段完整性问题",
                    "grantedPermissions": (
                        "datasource:metadata:read",
                        "quality:rule:draft",
                    ),
                    "actorRole": "PROJECT_OWNER",
                    "sessionId": "session-scheduling-event",
                },
            ),
            self._orchestrator(),
        )

        scheduling = response["intelligentGatewayGovernance"]["agentSessionScheduling"]
        scheduling_events = tuple(
            event
            for event in response["plan"]["runtime_events"]
            if event["event_type"] == AgentRuntimeEventType.AGENT_SESSION_SCHEDULING_RECORDED
        )

        self.assertEqual(1, len(scheduling_events))
        event = scheduling_events[0]
        attributes = event["attributes"]
        self.assertEqual(event, response["eventEnvelope"]["events"][-1])
        self.assertEqual(len(response["plan"]["runtime_events"]), event["sequence"])
        self.assertEqual("session-scheduling-event", event["session_id"])
        self.assertEqual("AGENT_SESSION_SCHEDULING_POLICY_VIEW", attributes["snapshotType"])
        self.assertEqual(scheduling["status"], attributes["status"])
        self.assertEqual(scheduling["participatingAgentCount"], attributes["participatingAgentCount"])
        self.assertIn("MASTER_ORCHESTRATOR", attributes["participatingAgentRoles"])
        self.assertIn("DATA_QUALITY_AGENT", attributes["participatingAgentRoles"])
        self.assertIn("SPECIALIST", attributes["participationModeCounts"])
        self.assertIn("data_quality", attributes["intentDomains"])
        self.assertIn("quality.rule.design", attributes["selectedSkillCodes"])
        self.assertIn("quality.rule.suggest", attributes["plannedToolNames"])
        self.assertNotIn("ds-quality-secret", str(attributes))
        self.assertNotIn("请为客户主数据生成质量规则", str(attributes))
        self.assertNotIn("businessGoal", str(attributes))

    def test_a2a_authorization_decision_shapes_session_scheduling_and_event(self) -> None:
        """A2A 授权等待态应进入会话调度，并激活任务/权限 Agent。

        这个用例模拟 Java A2A task 查询预览已经被 5.31/5.32 适配器转成 `planningDecision` 后，
        再由 gateway 或 Java 控制面注入 `/agent/plans`。会话调度层只消费低敏 decision，不读取原始
        A2A message，也不把 task id、prompt、工具参数或内部 endpoint 写进 runtime event。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="a2a-operator",
                objective="查看外部 Agent 委派任务的授权状态",
                variables={
                    "trustedControlPlane": {
                        "a2aTaskPlanningDecision": {
                            "mode": "WAIT_FOR_AUTHORIZATION",
                            "status": "WAITING_FOR_CONTROL_PLANE",
                            "executable": False,
                            "shouldStartWorker": False,
                            "shouldWaitForHuman": True,
                            "suggestedActions": ("REQUEST_AUTHORIZATION", "QUERY_TASK_HISTORY"),
                            "guardrails": (
                                "CREDENTIALS_MUST_STAY_OUTSIDE_A2A_MESSAGE_BODY",
                                "APPROVAL_OR_PERMISSION_SCOPE_MUST_BE_CONFIRMED_BY_CONTROL_PLANE",
                            ),
                            "snapshot": {
                                "taskPublicId": "task_pub_auth_secret",
                                "contextPublicId": "ctx_pub_auth_secret",
                                "a2aState": "TASK_STATE_AUTH_REQUIRED",
                                "internalPhase": "APPROVAL_WAITING",
                                "sequence": 4,
                                "terminal": False,
                                "interrupted": True,
                                "historyEventCount": 3,
                                "artifactReferenceCount": 0,
                                "sensitiveFieldIgnoredCount": 2,
                                "payloadPolicy": "SUMMARY_ONLY_LOW_SENSITIVE_CONTROL_PLANE_FIELDS",
                            },
                            "prompt": "原始 A2A message 不能进入调度摘要",
                            "toolArguments": {"datasourceId": "ds-a2a-secret"},
                            "targetEndpoint": "http://internal-a2a-worker.local/tasks",
                        }
                    },
                    "sessionId": "session-a2a-auth",
                },
            ),
            self._orchestrator(),
        )

        scheduling = response["intelligentGatewayGovernance"]["agentSessionScheduling"]
        axis = scheduling["policyAxes"]["a2aTaskPlanning"]
        roles = {agent["role"] for agent in scheduling["participatingAgents"]}
        event = tuple(
            item
            for item in response["plan"]["runtime_events"]
            if item["event_type"] == AgentRuntimeEventType.AGENT_SESSION_SCHEDULING_RECORDED
        )[-1]
        attributes = event["attributes"]

        self.assertTrue(scheduling["available"])
        self.assertEqual("APPROVAL_REQUIRED", scheduling["status"])
        self.assertTrue(scheduling["handoffRequired"])
        self.assertIn("TASK_AGENT", roles)
        self.assertIn("PERMISSION_AGENT", roles)
        self.assertEqual("TRUSTED_CONTROL_PLANE", axis["source"])
        self.assertEqual("WAIT_FOR_AUTHORIZATION", axis["mode"])
        self.assertEqual("TASK_STATE_AUTH_REQUIRED", axis["a2aState"])
        self.assertTrue(axis["taskPublicIdPresent"])
        self.assertTrue(axis["contextPublicIdPresent"])
        self.assertEqual("WAIT_FOR_AUTHORIZATION", attributes["a2aTaskPlanningMode"])
        self.assertEqual("TASK_STATE_AUTH_REQUIRED", attributes["a2aTaskState"])
        self.assertEqual(2, attributes["a2aTaskSensitiveFieldIgnoredCount"])
        serialized = str(scheduling) + str(attributes)
        self.assertNotIn("task_pub_auth_secret", serialized)
        self.assertNotIn("ctx_pub_auth_secret", serialized)
        self.assertNotIn("原始 A2A message", serialized)
        self.assertNotIn("ds-a2a-secret", serialized)
        self.assertNotIn("internal-a2a-worker", serialized)
        self.assertNotIn("toolArguments", serialized)
        self.assertNotIn("targetEndpoint", serialized)

    def test_a2a_unknown_decision_blocks_session_and_schedules_ops_agent(self) -> None:
        """未知或不可信 A2A planning decision 应 fail-closed。

        A2A 协议和 Java task fact 后续都会演进；如果 Python Runtime 看见未知状态，最安全的行为不是猜测
        下一步，而是把会话标记为 BLOCKED，交给运维/协议诊断 Agent 处理。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="a2a-operator",
                objective="诊断一个未知 A2A task 状态",
                variables={
                    "a2aTaskPlanningDecision": {
                        "mode": "REJECTED_OR_DIAGNOSTIC",
                        "status": "BLOCKED",
                        "executable": False,
                        "shouldWaitForHuman": True,
                        "suggestedActions": ("OPEN_DIAGNOSTIC_REVIEW", "STOP_AUTOMATIC_EXECUTION"),
                        "guardrails": (
                            "UNKNOWN_OR_UNTRUSTED_A2A_STATE_FAIL_CLOSED",
                            "CONTROL_PLANE_CONTRACT_VERSION_REVIEW_REQUIRED",
                        ),
                        "snapshot": {
                            "taskPublicId": "task_pub_unknown_secret",
                            "a2aState": "TASK_STATE_UNSPECIFIED",
                            "internalPhase": "DEAD_LETTER",
                            "historyEventCount": 1,
                            "sensitiveFieldIgnoredCount": 1,
                            "payloadPolicy": "SUMMARY_ONLY_LOW_SENSITIVE_CONTROL_PLANE_FIELDS",
                        },
                        "sql": "select * from hidden_table",
                    },
                    "sessionId": "session-a2a-unknown",
                },
            ),
            self._orchestrator(),
        )

        scheduling = response["intelligentGatewayGovernance"]["agentSessionScheduling"]
        axis = scheduling["policyAxes"]["a2aTaskPlanning"]
        roles = {agent["role"] for agent in scheduling["participatingAgents"]}
        event = tuple(
            item
            for item in response["plan"]["runtime_events"]
            if item["event_type"] == AgentRuntimeEventType.AGENT_SESSION_SCHEDULING_RECORDED
        )[-1]
        attributes = event["attributes"]

        self.assertFalse(scheduling["available"])
        self.assertEqual("BLOCKED", scheduling["status"])
        self.assertTrue(scheduling["handoffRequired"])
        self.assertIn("OPS_AGENT", roles)
        self.assertEqual("REQUEST_VARIABLES_COMPATIBILITY_PREVIEW", axis["source"])
        self.assertEqual("REJECTED_OR_DIAGNOSTIC", axis["mode"])
        self.assertEqual("REJECTED_OR_DIAGNOSTIC", attributes["a2aTaskPlanningMode"])
        self.assertIn("UNKNOWN_OR_UNTRUSTED_A2A_STATE_FAIL_CLOSED", attributes["a2aTaskGuardrailCodes"])
        serialized = str(scheduling) + str(attributes)
        self.assertNotIn("task_pub_unknown_secret", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("select *", serialized)

    @staticmethod
    def _orchestrator(provider: object | None = None) -> AgentOrchestrator:
        """构造测试用编排器。"""

        return AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

    @staticmethod
    def _four_metadata_tool_call_provider() -> "ToolCallingProvider":
        """构造一次返回 4 个元数据读取 tool_calls 的 Provider，用于触发预算守卫。"""

        return ToolCallingProvider(
            tuple(
                ModelToolCall(
                    call_id=f"call-metadata-{index}",
                    name="datasource_metadata_read",
                    arguments=f'{{"datasourceId":"ds-{index}"}}',
                )
                for index in range(4)
            )
        )


class ToolCallingProvider:
    """测试用模型 Provider，模拟模型一次返回多个 tool_calls。"""

    def __init__(self, tool_calls: tuple[ModelToolCall, ...]) -> None:
        self._tool_calls = tool_calls

    def invoke(self, request):
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content="tool calling",
            tool_calls=self._tool_calls,
        )


class FakeStateGraph:
    """测试用 LangGraph StateGraph 替身，只模拟 turn runner 需要的节点、边和条件边。"""

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
        self.conditional_edges[source] = (path, dict(path_map))

    def compile(self) -> "FakeCompiledGraph":
        return FakeCompiledGraph(
            nodes=self.nodes,
            edges=self.edges,
            conditional_edges=self.conditional_edges,
        )


class FakeCompiledGraph:
    """编译后图替身，按固定边和条件边推进到 END。"""

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


if __name__ == "__main__":
    unittest.main()
