import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import ModelToolCall
from datasmart_ai_runtime.services.model_tool_result_feedback import (
    ModelToolResultFeedbackBuilder,
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)


class ModelToolResultFeedbackTest(unittest.TestCase):
    def test_builds_assistant_and_tool_messages_for_next_model_turn(self) -> None:
        """工具结果回填应保留 assistant tool_calls 和 role=tool 的 tool_call_id。"""

        tool_call = ModelToolCall(
            call_id="call_quality_001",
            type="function",
            name="quality.rule.suggest",
            arguments="{\"datasourceId\":\"ds-001\"}",
            raw_call={
                "id": "call_quality_001",
                "type": "function",
                "function": {
                    "name": "quality_rule_suggest",
                    "arguments": "{\"datasourceId\":\"ds-001\"}",
                },
            },
        )
        bundle = ModelToolResultFeedbackBuilder().build(
            tool_calls=(tool_call,),
            feedback_items=(
                ToolExecutionFeedback(
                    tool_call_id="call_quality_001",
                    tool_name="quality.rule.suggest",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="已生成 3 条质量规则草案。",
                    result={"ruleCount": 3, "datasourceId": "ds-001"},
                    audit_id="audit-001",
                    run_id="run-001",
                    output_ref="minio://agent/run-001/quality-rules.json",
                    output_workspace_key="tenant:10:project:20",
                    output_context_policy="model_summary_allowed",
                    sensitive_fields=("datasourceId",),
                ),
            ),
            current_workspace_key="tenant:10:project:20",
        )

        self.assertTrue(bundle.complete)
        self.assertEqual(2, len(bundle.messages))
        assistant_message, tool_message = bundle.messages
        self.assertEqual("assistant", assistant_message.role)
        self.assertEqual((tool_call,), assistant_message.tool_calls)
        self.assertEqual("tool", tool_message.role)
        self.assertEqual("call_quality_001", tool_message.tool_call_id)
        payload = json.loads(tool_message.content)
        self.assertEqual("succeeded", payload["status"])
        self.assertEqual("minio_object", payload["outputReference"]["kind"])
        self.assertEqual("minio://agent/run-001/quality-rules.json", payload["outputReference"]["uri"])
        self.assertEqual("agent", payload["outputReference"]["attributes"]["bucket"])
        self.assertEqual("run-001/quality-rules.json", payload["outputReference"]["attributes"]["objectKey"])
        self.assertTrue(payload["outputReferenceResolution"]["modelContextAllowed"])
        self.assertEqual("***MASKED***", payload["result"]["datasourceId"])
        self.assertEqual(3, payload["result"]["ruleCount"])

    def test_blocks_audit_only_output_result_from_model_context(self) -> None:
        """审计专用输出引用不应把结构化 result 放入下一轮模型上下文。"""

        tool_call = ModelToolCall(
            call_id="call_audit_only",
            type="function",
            name="datasource.metadata.read",
            arguments="{\"datasourceId\":\"ds-001\"}",
        )

        bundle = ModelToolResultFeedbackBuilder().build(
            tool_calls=(tool_call,),
            feedback_items=(
                ToolExecutionFeedback(
                    tool_call_id="call_audit_only",
                    tool_name="datasource.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="工具结果已生成，但仅允许审计台查看完整结构化内容。",
                    result={"tableCount": 12, "sampleRows": ("row-1", "row-2")},
                    audit_id="audit-002",
                    run_id="run-002",
                    output_ref="agent-runtime://tool-results/call_audit_only",
                    output_workspace_key="tenant:10:project:20",
                    output_context_policy="audit_only",
                ),
            ),
            current_workspace_key="tenant:10:project:20",
        )

        self.assertTrue(bundle.complete)
        payload = json.loads(bundle.messages[-1].content)
        self.assertEqual({}, payload["result"])
        self.assertFalse(payload["outputReferenceResolution"]["modelContextAllowed"])
        self.assertEqual("allowed", payload["outputReferenceResolution"]["decision"])
        self.assertEqual("audit_only", payload["outputReference"]["contextPolicy"])

    def test_blocks_workspace_mismatch_output_result_from_model_context(self) -> None:
        """跨工作空间输出引用即使带有 result，也不能进入当前模型上下文。"""

        tool_call = ModelToolCall(
            call_id="call_other_workspace",
            type="function",
            name="quality.rule.suggest",
            arguments="{}",
        )

        bundle = ModelToolResultFeedbackBuilder().build(
            tool_calls=(tool_call,),
            feedback_items=(
                ToolExecutionFeedback(
                    tool_call_id="call_other_workspace",
                    tool_name="quality.rule.suggest",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="工具结果引用属于另一个 workspace，当前仅保留引用和阻断原因。",
                    result={"ruleCount": 99},
                    output_ref="minio://agent/other-workspace/rules.json",
                    output_workspace_key="tenant:10:project:other",
                    output_context_policy="model_summary_allowed",
                ),
            ),
            current_workspace_key="tenant:10:project:20",
        )

        payload = json.loads(bundle.messages[-1].content)
        self.assertEqual({}, payload["result"])
        self.assertEqual("blocked", payload["outputReferenceResolution"]["decision"])
        self.assertIn("WORKSPACE_KEY_MISMATCH", payload["outputReferenceResolution"]["issues"])

    def test_reports_missing_and_extra_feedback_ids(self) -> None:
        """缺少或多余工具结果应被显式报告，避免下一轮模型请求结构不完整。"""

        bundle = ModelToolResultFeedbackBuilder().build(
            tool_calls=(
                ModelToolCall(call_id="call_expected", name="datasource.metadata.read", arguments="{}"),
            ),
            feedback_items=(
                ToolExecutionFeedback(
                    tool_call_id="call_extra",
                    tool_name="unknown",
                    status=ToolExecutionFeedbackStatus.FAILED,
                    summary="多余结果。",
                ),
            ),
        )

        self.assertFalse(bundle.complete)
        self.assertEqual(("call_expected",), bundle.missing_feedback_call_ids)
        self.assertEqual(("call_extra",), bundle.extra_feedback_call_ids)


if __name__ == "__main__":
    unittest.main()
