import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api_plan_response import build_plan_response
from datasmart_ai_runtime.config import default_model_routes, default_skill_registry, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest, ModelInvocationResult, ModelToolCall
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
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


if __name__ == "__main__":
    unittest.main()
