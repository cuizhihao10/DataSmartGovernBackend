"""MCP 安全 modelFeedback 二轮模型调用测试。

测试目标不是验证真实大模型质量，而是验证链路语义：
- 二轮服务会走项目统一 Query Engine；
- role=tool 消息由 `ModelToolResultFeedbackBuilder` 构建；
- MCP arguments 不会被重建或带入二轮模型上下文；
- 等待审批等不安全状态不会触发模型调用。
"""

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import ModelInvocationResult, ProviderType
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.tools.mcp.model_feedback_second_turn import (
    McpModelFeedbackSecondTurnService,
    mcp_model_feedback_second_turn_settings_from_env,
)


class McpModelFeedbackSecondTurnServiceTest(unittest.TestCase):
    """验证 MCP modelFeedback 能安全进入真实二轮模型调用链。"""

    def test_safe_feedback_invokes_query_engine_without_reconstructing_arguments(self) -> None:
        """安全 feedback 应触发模型调用，但二轮上下文不能包含 MCP arguments。"""

        provider = CapturingProvider()
        service = self._service(provider)

        result = service.run(
            feedback=ToolExecutionFeedback(
                tool_call_id="call-a",
                tool_name="mcp.enterprise.search",
                status=ToolExecutionFeedbackStatus.SUCCEEDED,
                summary="MCP 工具已返回 1 条安全短结果。",
                result={
                    "schemaVersion": "datasmart.mcp-tool-feedback.v1",
                    "contentBlocks": ({"type": "text", "text": "已找到质量规则说明。"},),
                    "structuredContent": {"hitCount": 1, "secret": "secret-value"},
                },
                output_ref="agent-runtime://tool-results/call-a",
                output_workspace_key="tenant:10:project:20",
                output_context_policy="model_summary_allowed",
                sensitive_result_paths=("structuredContent.secret",),
            ),
            feedback_summary={
                "runtimeResultPresent": True,
                "inlineResultAllowed": True,
                "inlineDecisionReason": "safe_small_result",
            },
            control_facts={
                "tenantId": "10",
                "projectId": "20",
                "actorId": "30",
                "runId": "run-a",
                "sessionId": "session-a",
            },
            trace_id="trace-a",
            workspace_key="tenant:10:project:20",
            current_workspace_key="tenant:10:project:20",
        )

        self.assertTrue(result.executed)
        self.assertEqual("模型已完成 MCP 二轮总结。", result.summary)
        self.assertEqual(1, len(provider.requests))
        request_text = str(provider.requests[0].messages)
        self.assertIn("已找到质量规则说明。", request_text)
        self.assertNotIn("private-search-argument", request_text)
        self.assertNotIn("quality rules", request_text)
        self.assertNotIn("secret-value", request_text)
        self.assertEqual("none", provider.requests[0].tool_choice)
        self.assertEqual((), provider.requests[0].available_tools)

    def test_waiting_approval_feedback_is_skipped_before_model_call(self) -> None:
        """等待审批不是终态工具结果，不能让模型提前总结或假设工具已经执行。"""

        provider = CapturingProvider()
        service = self._service(provider)

        result = service.run(
            feedback=ToolExecutionFeedback(
                tool_call_id="call-approval",
                tool_name="mcp.enterprise.write",
                status=ToolExecutionFeedbackStatus.WAITING_APPROVAL,
                summary="工具仍在等待人工审批。",
            ),
            feedback_summary={"runtimeResultPresent": False},
            control_facts={"tenantId": "10", "projectId": "20", "actorId": "30"},
        )

        self.assertFalse(result.executed)
        self.assertTrue(result.skipped)
        self.assertEqual("tool_waiting_approval", result.reason)
        self.assertEqual(0, len(provider.requests))

    def test_env_settings_default_enable_second_turn(self) -> None:
        """默认开启二轮链路，便于本地 dry-run 验证闭环；生产可通过环境变量显式关闭。"""

        settings = mcp_model_feedback_second_turn_settings_from_env({})

        self.assertTrue(settings.enabled)
        self.assertTrue(settings.fail_open)
        self.assertEqual(1024, settings.max_output_tokens)

    @staticmethod
    def _service(provider: "CapturingProvider") -> McpModelFeedbackSecondTurnService:
        """构造只使用 dry-run 路由的测试服务。"""

        routes = ModelRouteRegistry(default_model_routes())
        gateway = ModelGatewayGovernanceService(routes)
        providers = ModelProviderRegistry({ProviderType.DRY_RUN: provider})
        return McpModelFeedbackSecondTurnService(
            model_routes=routes,
            model_gateway=gateway,
            model_providers=providers,
        )


class CapturingProvider:
    """记录模型请求并返回固定响应的 Provider。"""

    def __init__(self) -> None:
        self.requests = []

    def invoke(self, request):
        """保存二轮请求，返回稳定模型结果。"""

        self.requests.append(request)
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content="模型已完成 MCP 二轮总结。",
            prompt_tokens=88,
            completion_tokens=21,
        )


if __name__ == "__main__":
    unittest.main()
