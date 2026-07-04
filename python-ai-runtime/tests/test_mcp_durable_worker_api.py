"""MCP durable worker 内部 API 合同测试。

本测试不启动真实 FastAPI，也不连接真实 MCP Server。我们只验证 API 层本身的职责：
- Java/outbox 风格 payload 能被转换成 `McpDurableWorkerRunRequest`；
- 内部路由会调用注入的 worker adapter；
- 响应只返回低敏 summary 和可选安全 feedback；
- MCP arguments 不会出现在 worker summary、receipt summary 或普通响应字符串中。
"""

import asyncio
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.mcp_worker import (
    mcp_worker_request_from_payload,
    register_mcp_durable_worker_routes,
)
from datasmart_ai_runtime.services.tools.controlled_command_worker_runner import (
    COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
    CommandWorkerReceiptOutcome,
    ControlledCommandWorkerReceipt,
)
from datasmart_ai_runtime.services.tools.mcp import (
    MCP_DURABLE_EXECUTION_SCHEMA_VERSION,
    McpDurableExecutionStatus,
    McpDurableToolExecutionResult,
    McpDurableWorkerRunResult,
    McpToolCallResult,
    McpToolFeedbackAdapter,
    McpWorkerReceiptDraft,
)


class McpDurableWorkerApiTest(unittest.TestCase):
    """验证 MCP durable worker 内部路由的输入、输出和低敏策略。"""

    def test_payload_builder_accepts_java_style_control_facts(self) -> None:
        """payload builder 应兼容 Java camelCase 字段，并保留短生命周期 arguments。"""

        request = mcp_worker_request_from_payload(
            {
                "serverId": "enterprise",
                "internalToolName": "mcp.enterprise.search",
                "arguments": {"query": "quality rules"},
                "controlFacts": {
                    "tenantId": "10",
                    "projectId": "20",
                    "workspaceKey": "tenant:10:project:20",
                    "actorId": "30",
                    "runId": "run-a",
                    "callId": "call-a",
                    "readinessDecision": "READY",
                    "permissionGranted": True,
                    "allowedInternalToolNames": ["mcp.enterprise.search"],
                },
                "fallbackContext": {"workspaceKey": "tenant:10:project:20"},
                "postToJava": "true",
                "sessionId": "session-a",
                "traceId": "trace-a",
            }
        )

        self.assertEqual("enterprise", request.server_id)
        self.assertEqual("mcp.enterprise.search", request.internal_tool_name)
        self.assertEqual({"query": "quality rules"}, request.arguments)
        self.assertTrue(request.post_to_java)
        self.assertEqual("session-a", request.session_id)
        self.assertEqual("trace-a", request.trace_id)

    def test_route_returns_worker_summary_and_safe_model_feedback(self) -> None:
        """内部路由应返回 worker 低敏 summary 与安全 feedback，且不泄露 arguments。"""

        app = FakeApp()
        worker_adapter = FakeWorkerAdapter()
        register_mcp_durable_worker_routes(
            app,
            worker_adapter=worker_adapter,
            feedback_adapter=McpToolFeedbackAdapter(),
        )
        handler = app.post_routes["/internal/agent/mcp/durable-worker/run"]

        response = asyncio.run(handler(self._payload()))

        self.assertTrue(response["accepted"])
        self.assertEqual("datasmart.mcp-durable-worker-api.v1", response["schemaVersion"])
        self.assertEqual("LOW_SENSITIVE_MCP_WORKER_SUMMARY_ONLY", response["workerResult"]["payloadPolicy"])
        self.assertEqual("cmd-lease:3:0123456789abcdef", response["javaReceiptPayload"]["fencingToken"])
        self.assertNotIn("fencingToken", response["receipt"]["javaPayload"])
        self.assertTrue(response["receipt"]["javaPayload"]["fencingTokenPresent"])
        self.assertEqual("succeeded", response["modelFeedback"]["feedback"]["status"])
        self.assertTrue(response["modelFeedback"]["summary"]["inlineResultAllowed"])
        self.assertEqual("已找到质量规则说明。", response["modelFeedback"]["feedback"]["result"]["contentBlocks"][0]["text"])
        self.assertEqual(1, len(worker_adapter.calls))
        response_text = str(response)
        self.assertNotIn("quality rules", response_text)
        self.assertNotIn("private-search-argument", response_text)

    def test_route_can_skip_model_feedback_for_worker_only_consumers(self) -> None:
        """Java 只需要 worker receipt 时，可以关闭 model feedback 减少响应体。"""

        app = FakeApp()
        register_mcp_durable_worker_routes(
            app,
            worker_adapter=FakeWorkerAdapter(),
            feedback_adapter=McpToolFeedbackAdapter(),
        )
        payload = {**self._payload(), "includeModelFeedback": False}

        response = asyncio.run(app.post_routes["/api/internal/agent/mcp/durable-worker/run"](payload))

        self.assertIsNone(response["modelFeedback"])
        self.assertIsNone(response["modelSecondTurn"])
        self.assertEqual("SUCCEEDED", response["workerResult"]["executionResult"]["status"])

    def test_route_invokes_second_turn_service_with_safe_model_feedback(self) -> None:
        """开启二轮服务时，内部路由应只把安全 feedback 交给模型二轮调用链。"""

        app = FakeApp()
        second_turn_service = FakeSecondTurnService()
        register_mcp_durable_worker_routes(
            app,
            worker_adapter=FakeWorkerAdapter(),
            feedback_adapter=McpToolFeedbackAdapter(),
            second_turn_service=second_turn_service,
        )

        response = asyncio.run(app.post_routes["/internal/agent/mcp/durable-worker/run"](self._payload()))

        self.assertEqual(1, len(second_turn_service.calls))
        self.assertEqual("call-a", second_turn_service.calls[0]["feedback"].tool_call_id)
        self.assertEqual("safe_small_result", second_turn_service.calls[0]["feedback_summary"]["inlineDecisionReason"])
        self.assertEqual("trace-a", second_turn_service.calls[0]["trace_id"])
        self.assertTrue(response["modelSecondTurn"]["executed"])
        response_text = str(response)
        self.assertNotIn("quality rules", response_text)
        self.assertNotIn("private-search-argument", response_text)

    @staticmethod
    def _payload() -> dict[str, object]:
        """生成内部 worker API payload。"""

        return {
            "serverId": "enterprise",
            "internalToolName": "mcp.enterprise.search",
            "arguments": {
                "query": "quality rules",
                "debugNote": "private-search-argument",
            },
            "controlFacts": {
                "tenantId": "10",
                "projectId": "20",
                "workspaceKey": "tenant:10:project:20",
                "actorId": "30",
                "sessionId": "session-a",
                "runId": "run-a",
                "callId": "call-a",
                "readinessDecision": "READY",
                "permissionGranted": True,
                "allowedInternalToolNames": ["mcp.enterprise.search"],
                "auditId": "audit-a",
            },
            "workspaceKey": "tenant:10:project:20",
            "currentWorkspaceKey": "tenant:10:project:20",
            "toolCallId": "call-a",
            "traceId": "trace-a",
        }


class FakeApp:
    """极简 FastAPI 替身，用于捕获 `@app.post(...)` 注册的 handler。"""

    def __init__(self) -> None:
        self.post_routes = {}

    def post(self, path):
        """模拟 FastAPI post decorator。"""

        def decorator(handler):
            self.post_routes[path] = handler
            return handler

        return decorator


class FakeWorkerAdapter:
    """返回固定 MCP worker result 的 fake adapter。"""

    def __init__(self) -> None:
        self.calls = []

    async def run(self, request):
        """记录请求并返回包含安全短结果的 worker result。"""

        self.calls.append(request)
        runtime_result = McpToolCallResult(
            server_id=request.server_id,
            internal_tool_name=request.internal_tool_name,
            is_error=False,
            content_blocks=({"type": "text", "text": "已找到质量规则说明。"},),
            structured_content={"hitCount": 1},
            result_byte_count=128,
            truncated=False,
            result_digest="d" * 64,
        )
        execution_result = McpDurableToolExecutionResult(
            status=McpDurableExecutionStatus.SUCCEEDED,
            server_id=request.server_id,
            internal_tool_name=request.internal_tool_name,
            execution_node_id=request.execution_node_id,
            admission_source="MCP_TOOLS_CALL",
            runtime_result=runtime_result,
            worker_receipt_draft=McpWorkerReceiptDraft(
                schema_version=MCP_DURABLE_EXECUTION_SCHEMA_VERSION,
                run_id="run-a",
                call_id="call-a",
                internal_tool_name=request.internal_tool_name,
                status=McpDurableExecutionStatus.SUCCEEDED,
                result_summary=runtime_result.to_summary(),
            ),
        )
        receipt = ControlledCommandWorkerReceipt(
            schema_version=COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
            outcome=CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED,
            java_payload={
                "commandId": "call-a",
                "runId": "run-a",
                "toolCode": request.internal_tool_name,
                "targetService": "python-ai-runtime-mcp-client",
                "outcome": "EXECUTION_SUCCEEDED",
                "auditId": "audit-a",
                "artifactReference": "agent-artifact:run-a/mcp.enterprise.search/mcp-result-dddddddd",
                "workerLeaseRequired": True,
                "fencingToken": "cmd-lease:3:0123456789abcdef",
                "workerLeaseVersion": 3,
                "workerLeaseExpiresAtMs": 4102444800000,
            },
            execution_performed=True,
        )
        return McpDurableWorkerRunResult(receipt=receipt, execution_result=execution_result)


class FakeSecondTurnResult:
    """二轮模型服务返回值替身，只暴露 API 层需要的 to_summary。"""

    def to_summary(self):
        """返回低敏二轮摘要。"""

        return {
            "schemaVersion": "datasmart.mcp-model-feedback-second-turn.v1",
            "executed": True,
            "skipped": False,
            "reason": "model_second_turn_completed",
            "summary": "二轮模型已根据安全 MCP 反馈生成总结。",
            "payloadPolicy": "LOW_SENSITIVE_MODEL_SECOND_TURN_SUMMARY_ONLY",
        }


class FakeSecondTurnService:
    """记录 API 层传入二轮服务的安全 feedback。"""

    def __init__(self) -> None:
        self.calls = []

    def run(self, **kwargs):
        """保存调用参数并返回固定二轮摘要。"""

        self.calls.append(kwargs)
        return FakeSecondTurnResult()


if __name__ == "__main__":
    unittest.main()
