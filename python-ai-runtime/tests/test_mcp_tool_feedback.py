"""MCP 工具结果进入模型二轮反馈前的安全适配测试。

这些测试不连接真实 MCP Server，而是直接构造 worker 结果。原因是本文件验证的不是协议连通性，
而是“真实工具结果如何被允许或拒绝进入模型上下文”的治理逻辑：
- 安全短结果可以成为 `ToolExecutionFeedback.result`；
- 大结果、截断结果或疑似敏感结果只能留下摘要与 artifactReference；
- 执行失败或 admission 失败不能把远端错误正文回填给模型。
"""

import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ModelToolResultFeedbackBuilder,
    ToolExecutionFeedbackStatus,
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
    McpToolFeedbackAdapterSettings,
    McpWorkerReceiptDraft,
    mcp_feedback_summary_json,
)


class McpToolFeedbackAdapterTest(unittest.TestCase):
    """验证 MCP worker result 到模型二轮 feedback 的安全边界。"""

    def test_safe_short_result_can_enter_model_tool_feedback(self) -> None:
        """成功且足够小的纯文本/结构化结果应允许进入模型二轮 tool message。"""

        adapter = McpToolFeedbackAdapter()
        build_result = adapter.build(
            self._worker_result(
                runtime_result=McpToolCallResult(
                    server_id="enterprise",
                    internal_tool_name="mcp.enterprise.search",
                    is_error=False,
                    content_blocks=({"type": "text", "text": "发现 2 条与数据质量规则相关的内部知识。"},),
                    structured_content={"hitCount": 2, "topTitles": ["规则生成说明", "质量稽核策略"]},
                    result_byte_count=320,
                    truncated=False,
                    result_digest="a" * 64,
                )
            ),
            workspace_key="tenant:10:project:20",
            current_workspace_key="tenant:10:project:20",
        )

        self.assertEqual(ToolExecutionFeedbackStatus.SUCCEEDED, build_result.feedback.status)
        self.assertEqual("model_summary_allowed", build_result.feedback.output_context_policy)
        self.assertTrue(build_result.summary["inlineResultAllowed"])
        self.assertIn("contentBlocks", build_result.feedback.result)
        self.assertEqual(2, build_result.feedback.result["structuredContent"]["hitCount"])

        bundle = ModelToolResultFeedbackBuilder().build(
            tool_calls=(self._model_tool_call(),),
            feedback_items=(build_result.feedback,),
            current_workspace_key="tenant:10:project:20",
        )
        payload = json.loads(bundle.messages[-1].content)
        self.assertEqual("succeeded", payload["status"])
        self.assertIn("发现 2 条", payload["result"]["contentBlocks"][0]["text"])
        self.assertEqual(2, payload["result"]["structuredContent"]["hitCount"])

    def test_large_result_uses_artifact_reference_without_inline_body(self) -> None:
        """过大结果只应返回低敏摘要和 artifactReference，不应把正文放入 feedback.result。"""

        adapter = McpToolFeedbackAdapter(McpToolFeedbackAdapterSettings(max_inline_result_bytes=128))
        build_result = adapter.build(
            self._worker_result(
                runtime_result=McpToolCallResult(
                    server_id="enterprise",
                    internal_tool_name="mcp.enterprise.search",
                    is_error=False,
                    content_blocks=({"type": "text", "text": "large body should not appear"},),
                    structured_content={"rows": ["row-001", "row-002"]},
                    result_byte_count=4096,
                    truncated=False,
                    result_digest="b" * 64,
                ),
                artifact_reference="agent-artifact:run-a/mcp.enterprise.search/mcp-result-bbbbbbbb",
            )
        )

        self.assertEqual({}, build_result.feedback.result)
        self.assertEqual("audit_only", build_result.feedback.output_context_policy)
        self.assertFalse(build_result.summary["inlineResultAllowed"])
        self.assertEqual("runtime_result_too_large_for_model", build_result.summary["inlineDecisionReason"])
        self.assertEqual(
            "agent-artifact:run-a/mcp.enterprise.search/mcp-result-bbbbbbbb",
            build_result.feedback.output_ref,
        )
        self.assertNotIn("large body should not appear", mcp_feedback_summary_json(build_result))

    def test_sensitive_structured_content_is_not_inlined(self) -> None:
        """结构化结果中出现 token/password 等敏感 key 时，整段结果不应进入模型。"""

        build_result = McpToolFeedbackAdapter().build(
            self._worker_result(
                runtime_result=McpToolCallResult(
                    server_id="enterprise",
                    internal_tool_name="mcp.enterprise.search",
                    is_error=False,
                    content_blocks=({"type": "text", "text": "普通摘要"},),
                    structured_content={"apiToken": "secret-token-value", "hitCount": 1},
                    result_byte_count=256,
                    truncated=False,
                    result_digest="c" * 64,
                )
            )
        )

        self.assertEqual({}, build_result.feedback.result)
        self.assertFalse(build_result.summary["inlineResultAllowed"])
        self.assertEqual("structured_content_contains_sensitive_key", build_result.summary["inlineDecisionReason"])
        self.assertNotIn("secret-token-value", str(build_result.summary))

    def test_failed_execution_returns_error_feedback_without_remote_body(self) -> None:
        """MCP 执行失败时应生成 failed feedback，但不能回显远端错误正文。"""

        failed_execution = McpDurableToolExecutionResult(
            status=McpDurableExecutionStatus.FAILED_TOOL_CALL,
            server_id="enterprise",
            internal_tool_name="mcp.enterprise.search",
            execution_node_id="mcp_durable_worker",
            admission_source="MCP_TOOLS_CALL",
            error_code="MCP_TOOL_CALL_FAILED",
            worker_receipt_draft=self._draft(status=McpDurableExecutionStatus.FAILED_TOOL_CALL),
        )
        build_result = McpToolFeedbackAdapter().build(
            McpDurableWorkerRunResult(
                receipt=self._receipt(
                    outcome=CommandWorkerReceiptOutcome.EXECUTION_FAILED,
                    artifact_reference=None,
                ),
                execution_result=failed_execution,
            )
        )

        self.assertEqual(ToolExecutionFeedbackStatus.FAILED, build_result.feedback.status)
        self.assertEqual("MCP_TOOL_CALL_FAILED", build_result.feedback.error_code)
        self.assertEqual({}, build_result.feedback.result)
        self.assertIn("远端错误正文已按安全策略隐藏", build_result.feedback.error_message)
        self.assertNotIn("stacktrace", str(build_result.feedback))

    @staticmethod
    def _worker_result(
        *,
        runtime_result: McpToolCallResult,
        artifact_reference: str | None = "agent-artifact:run-a/mcp.enterprise.search/mcp-result-aaaaaaaa",
    ) -> McpDurableWorkerRunResult:
        """构造成功 worker result，正文只存在 runtime_result。"""

        execution_result = McpDurableToolExecutionResult(
            status=McpDurableExecutionStatus.SUCCEEDED,
            server_id="enterprise",
            internal_tool_name="mcp.enterprise.search",
            execution_node_id="mcp_durable_worker",
            admission_source="MCP_TOOLS_CALL",
            runtime_result=runtime_result,
            worker_receipt_draft=McpToolFeedbackAdapterTest._draft(
                status=McpDurableExecutionStatus.SUCCEEDED,
                result_summary=runtime_result.to_summary(),
            ),
        )
        return McpDurableWorkerRunResult(
            receipt=McpToolFeedbackAdapterTest._receipt(
                outcome=CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED,
                artifact_reference=artifact_reference,
            ),
            execution_result=execution_result,
        )

    @staticmethod
    def _receipt(
        *,
        outcome: CommandWorkerReceiptOutcome,
        artifact_reference: str | None,
    ) -> ControlledCommandWorkerReceipt:
        """构造低敏 Java worker receipt。"""

        payload = {
            "commandId": "call-a",
            "runId": "run-a",
            "toolCode": "mcp.enterprise.search",
            "targetService": "python-ai-runtime-mcp-client",
            "outcome": outcome.value,
            "artifactReference": artifact_reference,
            "auditId": "audit-a",
        }
        return ControlledCommandWorkerReceipt(
            schema_version=COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
            outcome=outcome,
            java_payload={key: value for key, value in payload.items() if value is not None},
            execution_performed=outcome == CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED,
        )

    @staticmethod
    def _draft(
        *,
        status: McpDurableExecutionStatus,
        result_summary=None,
    ) -> McpWorkerReceiptDraft:
        """构造 durable execution receipt draft。"""

        return McpWorkerReceiptDraft(
            schema_version=MCP_DURABLE_EXECUTION_SCHEMA_VERSION,
            run_id="run-a",
            call_id="call-a",
            internal_tool_name="mcp.enterprise.search",
            status=status,
            result_summary=result_summary or {},
            error_code=None if status == McpDurableExecutionStatus.SUCCEEDED else "MCP_TOOL_CALL_FAILED",
        )

    @staticmethod
    def _model_tool_call():
        """构造与 worker commandId 对齐的模型 tool_call。"""

        from datasmart_ai_runtime.domain.contracts import ModelToolCall

        return ModelToolCall(
            call_id="call-a",
            type="function",
            name="mcp.enterprise.search",
            arguments="{\"query\":\"quality rules\"}",
        )


if __name__ == "__main__":
    unittest.main()
