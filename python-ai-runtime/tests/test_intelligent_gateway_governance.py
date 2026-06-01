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
from datasmart_ai_runtime.services.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class IntelligentGatewayGovernanceResponseTest(unittest.TestCase):
    """智能网关统一治理摘要响应测试。

    这些用例验证 API 层能把模型路由、工具预算、workspace 和记忆检索汇总到一个稳定字段中。
    它不重新测试每个治理组件的内部算法，而是保护“前端/Java gateway 可以用一个字段读到治理总览”
    这个产品契约。
    """

    def test_plan_response_contains_unified_gateway_summary(self) -> None:
        """默认计划响应应包含智能网关统一治理摘要。"""

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析数据源结构",
                variables={"datasourceId": "ds-001", "sessionId": "session-a"},
            ),
            self._orchestrator(),
        )

        governance = response["intelligentGatewayGovernance"]

        self.assertTrue(governance["available"])
        self.assertTrue(governance["modelGateway"]["available"])
        self.assertTrue(governance["toolBudget"]["allowed"])
        self.assertEqual("tenant:tenant-a:project:project-a", governance["workspace"]["workspaceKey"])
        self.assertEqual("memory:tenant:tenant-a:project:project-a", governance["workspace"]["memoryNamespace"])
        self.assertGreaterEqual(governance["memory"]["retrievalTargetCount"], 1)
        self.assertIn("模型路由", governance["displaySummary"])

    def test_tool_budget_blocking_is_exposed_in_unified_gateway_summary(self) -> None:
        """工具预算阻断应在统一治理摘要中直接可见。"""

        provider = ToolCallingProvider(
            tuple(
                ModelToolCall(
                    call_id=f"call-metadata-{index}",
                    name="datasource_metadata_read",
                    arguments=f'{{"datasourceId":"ds-{index}"}}',
                )
                for index in range(4)
            )
        )
        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请连续读取多个数据源元数据",
                variables={"datasourceId": "ds-rule", "streamModelIntent": False},
            ),
            self._orchestrator(provider=provider),
        )

        tool_budget = response["intelligentGatewayGovernance"]["toolBudget"]

        self.assertFalse(response["intelligentGatewayGovernance"]["available"])
        self.assertTrue(tool_budget["guarded"])
        self.assertEqual(4, tool_budget["proposedCount"])
        self.assertEqual(3, tool_budget["acceptedCountAfterGuard"])
        self.assertIn("MODEL_TOOL_CALL_BUDGET_AUTO_EXECUTABLE_COUNT_EXCEEDED", tool_budget["budgetIssueCodes"])
        self.assertTrue(
            any("缩小本轮模型工具调用批次" in action for action in response["intelligentGatewayGovernance"]["recommendedActions"])
        )

    @staticmethod
    def _orchestrator(provider: object | None = None) -> AgentOrchestrator:
        """构造测试用编排器。"""

        return AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
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
