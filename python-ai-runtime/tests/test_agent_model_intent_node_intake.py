import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes, default_skill_registry, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest, ModelInvocationResult, ModelToolCall
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_model_intent_node import AgentModelIntentNode
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner
from datasmart_ai_runtime.services.tools import ToolActionIntakeBoundary, ToolActionIntakeService, ToolActionIntakeSource


class AgentModelIntentNodeIntakeTest(unittest.TestCase):
    """模型意图节点与统一工具动作入口的集成测试。

    `ToolActionIntakeService` 已经有独立测试覆盖 MCP/A2A/模型三类入口；这里额外保护主链路迁移：
    AgentOrchestrator 中的模型 tool_call 不应再绕过 intake 直接调用 `ModelToolCallPlanner`。
    """

    def test_model_tool_calls_flow_through_tool_action_intake_service(self) -> None:
        """模型 tool_call 主链路应经过统一 intake，再继续写原有治理事件。"""

        routes = ModelRouteRegistry(default_model_routes())
        tools = default_tool_registry()
        tool_planner = ToolPlanner(tools)
        provider = ToolCallingModelProviderRegistry(
            tool_calls=(
                ModelToolCall(
                    call_id="call-intake-quality",
                    name="quality_rule_suggest",
                    arguments='{"datasourceId":"ds-intake-secret","businessGoal":"统一入口质量规则"}',
                ),
            )
        )
        intake = RecordingToolActionIntakeService()
        model_intent_node = AgentModelIntentNode(
            model_providers=provider,
            model_gateway=ModelGatewayGovernanceService(routes),
            tool_planner=tool_planner,
            tool_action_intake_service=intake,
        )
        orchestrator = AgentOrchestrator(
            model_routes=routes,
            tool_planner=tool_planner,
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
            model_intent_node=model_intent_node,
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请为客户主数据生成质量规则",
                variables={"datasourceId": "ds-intake-secret", "businessGoal": "规则式业务目标"},
            )
        )
        summary = intake.summaries[0]
        serialized_summary = str(summary)

        self.assertEqual(1, intake.call_count)
        self.assertEqual(ToolActionIntakeSource.MODEL_TOOL_CALL.value, summary["source"])
        self.assertEqual(1, summary["acceptedToolPlanCount"])
        self.assertEqual(ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH.value, summary["items"][0]["boundary"])
        self.assertNotIn("ds-intake-secret", serialized_summary)
        self.assertNotIn("统一入口质量规则", serialized_summary)
        self.assertIn("govern_model_tool_calls", plan.state_trace)
        self.assertIn(
            AgentRuntimeEventType.MODEL_TOOL_CALL_ACCEPTED,
            {event.event_type for event in plan.runtime_events},
        )


class RecordingToolActionIntakeService(ToolActionIntakeService):
    """记录主链路是否真实调用了 intake 服务的测试替身。"""

    def __init__(self) -> None:
        super().__init__()
        self.call_count = 0
        self.summaries: list[dict[str, object]] = []

    def from_model_tool_calls(self, tool_calls, *, registered_tools, visible_tools=None):
        self.call_count += 1
        report = super().from_model_tool_calls(
            tool_calls,
            registered_tools=registered_tools,
            visible_tools=visible_tools,
        )
        self.summaries.append(report.to_low_sensitive_summary())
        return report


class ToolCallingModelProviderRegistry:
    """只返回模型 tool_call 的测试 Provider，不访问真实模型。"""

    def __init__(self, tool_calls: tuple[ModelToolCall, ...]) -> None:
        self._tool_calls = tool_calls

    def invoke(self, request):
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content="captured with tool calls",
            tool_calls=self._tool_calls,
        )


if __name__ == "__main__":
    unittest.main()
