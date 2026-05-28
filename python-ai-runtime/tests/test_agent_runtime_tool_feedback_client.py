import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    ModelToolCall,
    ToolExecutionMode,
    ToolParameterValidationResult,
    ToolPlan,
    ToolRiskLevel,
)
from datasmart_ai_runtime.services.agent_runtime_tool_feedback_client import (
    AgentRuntimeToolFeedbackClientError,
    JavaAgentRuntimeToolFeedbackClient,
    JavaAgentRuntimeToolFeedbackProvider,
)
from datasmart_ai_runtime.services.model_tool_result_feedback import ToolExecutionFeedbackStatus


class JavaAgentRuntimeToolFeedbackClientTest(unittest.TestCase):
    def test_parse_succeeded_result_to_tool_feedback(self) -> None:
        payload = {
            "code": 0,
            "data": {
                "audit": {
                    "auditId": "atea-001",
                    "sessionId": "session-001",
                    "runId": "run-001",
                    "toolCode": "datasource.metadata.read",
                    "state": "SUCCEEDED",
                    "outputSummary": "工具执行成功，输出字段: datasourceId,tableCount",
                    "governanceHints": {"sensitiveFields": ["datasourceId"]},
                },
                "output": {"datasourceId": 1001, "tableCount": 8},
            },
        }

        feedback = JavaAgentRuntimeToolFeedbackClient.parse_platform_response(
            payload,
            tool_call_id="call-001",
        )

        self.assertEqual("call-001", feedback.tool_call_id)
        self.assertEqual("datasource.metadata.read", feedback.tool_name)
        self.assertEqual(ToolExecutionFeedbackStatus.SUCCEEDED, feedback.status)
        self.assertEqual({"datasourceId": 1001, "tableCount": 8}, feedback.result)
        self.assertEqual(("datasourceId",), feedback.sensitive_fields)
        self.assertEqual("atea-001", feedback.audit_id)
        self.assertEqual("run-001", feedback.run_id)
        self.assertEqual(
            "agent-runtime://sessions/session-001/runs/run-001/tool-executions/atea-001/result",
            feedback.output_ref,
        )

    def test_parse_failed_result_keeps_error_as_feedback_not_success(self) -> None:
        payload = {
            "code": 0,
            "data": {
                "audit": {
                    "auditId": "atea-failed",
                    "sessionId": "session-001",
                    "runId": "run-001",
                    "toolCode": "quality.rule.suggest",
                    "state": "FAILED",
                    "message": "工具适配器执行异常: timeout",
                    "errorCode": "TOOL_ADAPTER_EXCEPTION",
                },
                "output": {"debugPayload": "不应作为成功结果回填"},
            },
        }

        feedback = JavaAgentRuntimeToolFeedbackClient.parse_platform_response(
            payload,
            tool_call_id="call-failed",
        )

        self.assertEqual(ToolExecutionFeedbackStatus.FAILED, feedback.status)
        self.assertEqual("TOOL_ADAPTER_EXCEPTION", feedback.error_code)
        self.assertEqual("FAILED", feedback.result["state"])
        self.assertNotIn("debugPayload", feedback.result)

    def test_parse_platform_error_raises_client_error(self) -> None:
        with self.assertRaises(AgentRuntimeToolFeedbackClientError):
            JavaAgentRuntimeToolFeedbackClient.parse_platform_response(
                {"code": 500, "reason": "BUSINESS_STATE_CONFLICT", "message": "状态不允许"},
                tool_call_id="call-error",
            )


class JavaAgentRuntimeToolFeedbackProviderTest(unittest.TestCase):
    def test_provider_queries_java_when_control_plane_refs_exist(self) -> None:
        fake_client = FakeFeedbackClient()
        provider = JavaAgentRuntimeToolFeedbackProvider(fake_client, trace_id="trace-001")

        feedback_items = provider.feedback_for(
            (ModelToolCall(call_id="call-001", name="datasource_metadata_read"),),
            (self._tool_plan_with_refs(),),
        )

        self.assertEqual(1, len(feedback_items))
        self.assertEqual(ToolExecutionFeedbackStatus.SUCCEEDED, feedback_items[0].status)
        self.assertEqual("session-001", fake_client.calls[0]["session_id"])
        self.assertEqual("run-001", fake_client.calls[0]["run_id"])
        self.assertEqual("atea-001", fake_client.calls[0]["audit_id"])
        self.assertEqual("trace-001", fake_client.calls[0]["trace_id"])

    def test_provider_falls_back_when_refs_are_missing(self) -> None:
        fake_client = FakeFeedbackClient()
        provider = JavaAgentRuntimeToolFeedbackProvider(fake_client)

        feedback_items = provider.feedback_for(
            (ModelToolCall(call_id="call-missing", name="datasource_metadata_read"),),
            (
                ToolPlan(
                    tool_name="datasource.metadata.read",
                    reason="测试缺少 Java 控制面引用时回退模拟反馈。",
                    parameter_validation=ToolParameterValidationResult(can_execute=True),
                    governance_hints={"modelToolCallId": "call-missing"},
                ),
            ),
        )

        self.assertEqual(1, len(feedback_items))
        self.assertEqual(ToolExecutionFeedbackStatus.SUCCEEDED, feedback_items[0].status)
        self.assertEqual(0, len(fake_client.calls))
        self.assertTrue(feedback_items[0].audit_id.startswith("simulated-audit-"))

    def _tool_plan_with_refs(self) -> ToolPlan:
        return ToolPlan(
            tool_name="datasource.metadata.read",
            reason="模型请求读取数据源元数据。",
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
            parameter_validation=ToolParameterValidationResult(can_execute=True),
            governance_hints={
                "modelToolCallId": "call-001",
                "agentRuntimeSessionId": "session-001",
                "agentRuntimeRunId": "run-001",
                "agentRuntimeAuditId": "atea-001",
            },
        )


class FakeFeedbackClient:
    def __init__(self) -> None:
        self.calls = []

    def get_tool_execution_feedback(self, **kwargs):
        self.calls.append(kwargs)
        return JavaAgentRuntimeToolFeedbackClient.parse_platform_response(
            {
                "code": 0,
                "data": {
                    "audit": {
                        "auditId": kwargs["audit_id"],
                        "sessionId": kwargs["session_id"],
                        "runId": kwargs["run_id"],
                        "toolCode": "datasource.metadata.read",
                        "state": "SUCCEEDED",
                        "outputSummary": "工具执行成功，输出字段: tableCount",
                    },
                    "output": {"tableCount": 2},
                },
            },
            tool_call_id=kwargs["tool_call_id"],
        )


if __name__ == "__main__":
    unittest.main()
