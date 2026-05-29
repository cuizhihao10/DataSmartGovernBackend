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
                    "governanceHints": {
                        "sensitiveFields": ["datasourceId"],
                        "outputWorkspaceKey": "tenant:10:project:20",
                        "outputContextPolicy": "model_summary_allowed",
                        "modelContextIncludePaths": ["tableCount", "columns[].name"],
                        "modelContextExcludePaths": ["debugPayload"],
                        "sensitiveResultPaths": ["columns[].sampleValue"],
                    },
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
        self.assertEqual("tenant:10:project:20", feedback.output_workspace_key)
        self.assertEqual("model_summary_allowed", feedback.output_context_policy)
        self.assertEqual(("tableCount", "columns[].name"), feedback.model_context_include_paths)
        self.assertEqual(("debugPayload",), feedback.model_context_exclude_paths)
        self.assertEqual(("columns[].sampleValue",), feedback.sensitive_result_paths)
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

    def test_parse_batch_response_maps_audit_ids_back_to_tool_call_ids(self) -> None:
        payload = {
            "code": 0,
            "data": [
                {
                    "audit": {
                        "auditId": "atea-001",
                        "sessionId": "session-001",
                        "runId": "run-001",
                        "toolCode": "datasource.metadata.read",
                        "state": "SUCCEEDED",
                        "outputSummary": "工具执行成功，输出字段: tableCount",
                    },
                    "output": {"tableCount": 2},
                },
                {
                    "audit": {
                        "auditId": "atea-002",
                        "sessionId": "session-001",
                        "runId": "run-001",
                        "toolCode": "quality.rule.suggest",
                        "state": "PLANNED",
                        "message": "工具计划已生成，等待执行。",
                    },
                    "output": {},
                },
            ],
        }

        feedback = JavaAgentRuntimeToolFeedbackClient.parse_platform_batch_response(
            payload,
            tool_call_ids_by_audit_id={"atea-001": "call-001", "atea-002": "call-002"},
        )

        self.assertEqual(("call-001", "call-002"), tuple(item.tool_call_id for item in feedback))
        self.assertEqual(ToolExecutionFeedbackStatus.SUCCEEDED, feedback[0].status)
        self.assertEqual(ToolExecutionFeedbackStatus.SKIPPED, feedback[1].status)

    def test_parse_execution_policy_response(self) -> None:
        payload = {
            "code": 0,
            "data": {
                "sessionId": "session-001",
                "runId": "run-001",
                "runState": "PLANNING",
                "runTerminal": False,
                "autoExecutableCount": 1,
                "humanActionCount": 0,
                "blockingCount": 0,
                "summaryReasons": ["存在可进入同步自动执行候选的工具。"],
                "recommendedActions": ["可由自动执行器执行。"],
                "items": [
                    {
                        "auditId": "atea-001",
                        "toolCode": "datasource.metadata.read",
                        "state": "PLANNED",
                        "decision": "AUTO_EXECUTABLE",
                        "autoExecutable": True,
                        "requiresHumanAction": False,
                        "blocksRun": False,
                        "reasons": ["同步低风险候选。"],
                        "recommendedActions": ["执行该工具。"],
                    }
                ],
            },
        }

        policy = JavaAgentRuntimeToolFeedbackClient.parse_platform_policy_response(payload)

        self.assertEqual("session-001", policy.session_id)
        self.assertEqual(1, policy.auto_executable_count)
        self.assertEqual("atea-001", policy.items[0].audit_id)
        self.assertTrue(policy.items[0].auto_executable)

    def test_parse_auto_execution_response(self) -> None:
        payload = {
            "code": 0,
            "data": {
                "sessionId": "session-001",
                "runId": "run-001",
                "dryRun": False,
                "requestedLimit": 3,
                "effectiveLimit": 2,
                "executedCount": 1,
                "failedCount": 0,
                "skippedCount": 1,
                "items": [
                    {
                        "auditId": "atea-001",
                        "toolCode": "datasource.metadata.read",
                        "policyDecision": "AUTO_EXECUTABLE",
                        "action": "EXECUTED",
                        "reason": "工具已执行成功。",
                    }
                ],
            },
        }

        summary = JavaAgentRuntimeToolFeedbackClient.parse_platform_auto_execution_response(payload)

        self.assertEqual(1, summary.executed_count)
        self.assertEqual(2, summary.effective_limit)
        self.assertEqual("EXECUTED", summary.item_actions[0]["action"])


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
        self.assertEqual(1, len(fake_client.batch_calls))
        self.assertEqual("session-001", fake_client.batch_calls[0]["session_id"])
        self.assertEqual("run-001", fake_client.batch_calls[0]["run_id"])
        self.assertEqual({"atea-001": "call-001"}, fake_client.batch_calls[0]["tool_call_ids_by_audit_id"])
        self.assertEqual("trace-001", fake_client.batch_calls[0]["trace_id"])
        self.assertEqual(0, len(fake_client.calls))

    def test_provider_auto_executes_sync_candidates_before_batch_query_when_enabled(self) -> None:
        fake_client = FakeFeedbackClient()
        provider = JavaAgentRuntimeToolFeedbackProvider(
            fake_client,
            trace_id="trace-001",
            auto_execute_sync_enabled=True,
            max_auto_executions=2,
        )

        feedback_items = provider.feedback_for(
            (ModelToolCall(call_id="call-001", name="datasource_metadata_read"),),
            (self._tool_plan_with_refs(),),
        )

        self.assertEqual(1, len(feedback_items))
        self.assertEqual(1, len(fake_client.policy_calls))
        self.assertEqual(1, len(fake_client.auto_execute_calls))
        self.assertEqual(("atea-001",), fake_client.auto_execute_calls[0]["audit_ids"])
        self.assertEqual(2, fake_client.auto_execute_calls[0]["max_executions"])
        self.assertFalse(fake_client.auto_execute_calls[0]["dry_run"])
        self.assertIsNotNone(provider.last_auto_execution_summary)
        self.assertEqual(1, provider.last_auto_execution_summary.executed_count)

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
        self.batch_calls = []
        self.policy_calls = []
        self.auto_execute_calls = []

    def get_run_tool_execution_policy(self, **kwargs):
        self.policy_calls.append(kwargs)
        return JavaAgentRuntimeToolFeedbackClient.parse_platform_policy_response(
            {
                "code": 0,
                "data": {
                    "sessionId": kwargs["session_id"],
                    "runId": kwargs["run_id"],
                    "runState": "PLANNING",
                    "runTerminal": False,
                    "autoExecutableCount": 1,
                    "humanActionCount": 0,
                    "blockingCount": 0,
                    "summaryReasons": [],
                    "recommendedActions": [],
                    "items": [
                        {
                            "auditId": "atea-001",
                            "toolCode": "datasource.metadata.read",
                            "state": "PLANNED",
                            "decision": "AUTO_EXECUTABLE",
                            "autoExecutable": True,
                            "requiresHumanAction": False,
                            "blocksRun": False,
                            "reasons": ["测试候选。"],
                            "recommendedActions": [],
                        }
                    ],
                },
            }
        )

    def auto_execute_sync_tools(self, **kwargs):
        self.auto_execute_calls.append(kwargs)
        return JavaAgentRuntimeToolFeedbackClient.parse_platform_auto_execution_response(
            {
                "code": 0,
                "data": {
                    "sessionId": kwargs["session_id"],
                    "runId": kwargs["run_id"],
                    "dryRun": kwargs["dry_run"],
                    "requestedLimit": kwargs["max_executions"] or 0,
                    "effectiveLimit": kwargs["max_executions"] or 0,
                    "executedCount": 1,
                    "failedCount": 0,
                    "skippedCount": 0,
                    "items": [
                        {
                            "auditId": "atea-001",
                            "toolCode": "datasource.metadata.read",
                            "policyDecision": "AUTO_EXECUTABLE",
                            "action": "EXECUTED",
                            "reason": "测试执行成功。",
                        }
                    ],
                },
            }
        )

    def list_run_tool_execution_feedback(self, **kwargs):
        self.batch_calls.append(kwargs)
        return JavaAgentRuntimeToolFeedbackClient.parse_platform_batch_response(
            {
                "code": 0,
                "data": [
                    {
                        "audit": {
                            "auditId": audit_id,
                            "sessionId": kwargs["session_id"],
                            "runId": kwargs["run_id"],
                            "toolCode": "datasource.metadata.read",
                            "state": "SUCCEEDED",
                            "outputSummary": "工具执行成功，输出字段: tableCount",
                        },
                        "output": {"tableCount": 2},
                    }
                    for audit_id in kwargs["tool_call_ids_by_audit_id"]
                ],
            },
            tool_call_ids_by_audit_id=kwargs["tool_call_ids_by_audit_id"],
        )

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
