import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import ModelInvocationChunk, ModelToolCallDelta
from datasmart_ai_runtime.services.model_gateway.model_tool_call_aggregator import ModelToolCallDeltaAggregator


class ModelToolCallDeltaAggregatorTest(unittest.TestCase):
    def test_aggregates_streaming_tool_call_deltas_by_index(self) -> None:
        """同一个 index 的 name 和 arguments 应被按顺序拼接。

        OpenAI-compatible streaming 下，工具参数通常会被拆成多段。聚合器必须稳定复原字符串，
        否则后续参数校验、审批和 Java agent-runtime 执行都无法拿到完整候选。
        """

        chunks = (
            _chunk(
                1,
                (
                    ModelToolCallDelta(
                        index=0,
                        call_id="call_quality_001",
                        type="function",
                        name_delta="quality.",
                        arguments_delta="{\"datasourceId\":",
                    ),
                ),
            ),
            _chunk(
                2,
                (
                    ModelToolCallDelta(
                        index=0,
                        name_delta="rule.suggest",
                        arguments_delta="\"ds-001\"}",
                    ),
                ),
            ),
        )

        report = ModelToolCallDeltaAggregator.from_chunks(chunks)

        self.assertFalse(report.has_issues)
        self.assertEqual(2, report.source_chunk_count)
        self.assertEqual(2, report.source_delta_count)
        self.assertEqual(1, len(report.tool_calls))
        tool_call = report.tool_calls[0]
        self.assertEqual("call_quality_001", tool_call.call_id)
        self.assertEqual("function", tool_call.type)
        self.assertEqual("quality.rule.suggest", tool_call.name)
        self.assertEqual("{\"datasourceId\":\"ds-001\"}", tool_call.arguments)

    def test_aggregates_multiple_parallel_tool_calls_by_index_order(self) -> None:
        """多个工具调用交错返回时，应按 index 分别聚合并稳定排序。"""

        chunks = (
            _chunk(
                1,
                (
                    ModelToolCallDelta(index=1, call_id="call_task", type="function", name_delta="task.create."),
                    ModelToolCallDelta(index=0, call_id="call_meta", type="function", name_delta="datasource."),
                ),
            ),
            _chunk(
                2,
                (
                    ModelToolCallDelta(index=0, name_delta="metadata.read", arguments_delta="{\"datasourceId\":\"ds\"}"),
                    ModelToolCallDelta(index=1, name_delta="draft", arguments_delta="{\"taskType\":\"SCAN\"}"),
                ),
            ),
        )

        report = ModelToolCallDeltaAggregator.from_chunks(chunks)

        self.assertEqual(("datasource.metadata.read", "task.create.draft"), tuple(call.name for call in report.tool_calls))
        self.assertEqual(("call_meta", "call_task"), tuple(call.call_id for call in report.tool_calls))
        self.assertFalse(report.has_issues)

    def test_reports_structure_issues_without_throwing(self) -> None:
        """缺少 name/id/arguments 时应输出 issue，而不是直接抛异常中断 Agent loop。"""

        report = ModelToolCallDeltaAggregator.from_chunks(
            (
                _chunk(
                    1,
                    (
                        ModelToolCallDelta(index=0, type="function"),
                    ),
                ),
            )
        )

        self.assertTrue(report.has_issues)
        self.assertEqual(1, len(report.tool_calls))
        self.assertEqual(
            (
                "MODEL_TOOL_CALL_NAME_MISSING",
                "MODEL_TOOL_CALL_ARGUMENTS_EMPTY",
                "MODEL_TOOL_CALL_ID_MISSING",
            ),
            tuple(issue.code for issue in report.issues),
        )

    def test_incremental_accept_chunk_matches_batch_aggregation(self) -> None:
        """实时增量聚合与一次性 replay 聚合应得到一致结果。"""

        chunks = (
            _chunk(
                1,
                (
                    ModelToolCallDelta(
                        index=0,
                        call_id="call_quality_002",
                        type="function",
                        name_delta="quality.rule.suggest",
                        arguments_delta="{",
                    ),
                ),
            ),
            _chunk(2, (ModelToolCallDelta(index=0, arguments_delta="\"businessGoal\":\"完整性\"}"),)),
        )
        incremental = ModelToolCallDeltaAggregator()
        for chunk in chunks:
            incremental.accept_chunk(chunk)

        incremental_report = incremental.build_report()
        batch_report = ModelToolCallDeltaAggregator.from_chunks(chunks)

        self.assertEqual(batch_report.tool_calls, incremental_report.tool_calls)
        self.assertEqual(batch_report.issues, incremental_report.issues)


def _chunk(sequence: int, deltas: tuple[ModelToolCallDelta, ...]) -> ModelInvocationChunk:
    return ModelInvocationChunk(
        provider_name="provider-test",
        model_name="model-test",
        sequence=sequence,
        tool_call_deltas=deltas,
    )


if __name__ == "__main__":
    unittest.main()
