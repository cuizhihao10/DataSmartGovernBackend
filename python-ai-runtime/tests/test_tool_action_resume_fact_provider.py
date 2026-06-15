import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    EmptyToolActionResumeFactProvider,
    StaticToolActionResumeFactProvider,
    merge_resume_fact_types,
    resume_fact_types_from_mapping,
)


class FakeCheckpoint:
    """只暴露 provider 需要读取的 checkpointId/threadId，避免测试依赖完整 checkpoint store。"""

    checkpoint_id = "checkpoint-provider-001"
    thread_id = "thread-provider-001"


class ToolActionResumeFactProviderTest(unittest.TestCase):
    """工具动作恢复事实源测试。

    这组测试不验证真实 permission-admin 或 outbox 查询，因为当前阶段还没有接入外部控制面。它固定的是更重要的
    安全契约：无论事实来自请求、静态 provider 还是未来远程 provider，响应层都只能消费“事实类型”，不能消费或
    回显事实值。
    """

    def test_resume_fact_types_from_mapping_reads_top_level_and_nested_facts(self) -> None:
        """顶层字段和 resumeFacts 内层字段都应映射为稳定的低敏事实类型。"""

        fact_types = resume_fact_types_from_mapping(
            {
                "graphId": "graph-secret",
                "resumeFacts": {
                    "approvalConfirmationId": "approval-secret",
                    "workerCapacityRecovered": True,
                },
                "outboxConfirmationId": "outbox-secret",
                "arguments": {"secret": "must-not-be-returned"},
            }
        )

        self.assertEqual(
            (
                "GRAPH_OR_CONTRACT_EVIDENCE",
                "APPROVAL_CONFIRMATION_FACT",
                "OUTBOX_WRITE_CONFIRMATION",
                "TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY",
            ),
            fact_types,
        )

    def test_static_provider_resolves_by_checkpoint_id_before_thread_id(self) -> None:
        """静态 provider 应优先使用 checkpointId 精确事实，threadId 只作为兜底来源。"""

        provider = StaticToolActionResumeFactProvider(
            facts_by_checkpoint_id={
                "checkpoint-provider-001": {
                    "approvalConfirmationId": "approval-secret",
                    "payloadReference": "payload-secret",
                }
            },
            facts_by_thread_id={
                "thread-provider-001": {
                    "clarificationFactId": "clarification-secret",
                }
            },
        )

        snapshot = provider.collect(checkpoint=FakeCheckpoint(), request_payload={})
        serialized = str(snapshot.to_summary())

        self.assertEqual("STATIC_RESUME_FACT_PROVIDER", snapshot.source)
        self.assertEqual(("PAYLOAD_REFERENCE", "APPROVAL_CONFIRMATION_FACT"), snapshot.available_fact_types)
        self.assertEqual(2, snapshot.fact_reference_count)
        self.assertNotIn("approval-secret", serialized)
        self.assertNotIn("payload-secret", serialized)
        self.assertNotIn("clarification-secret", serialized)

    def test_empty_provider_and_merge_keep_resume_preview_compatible(self) -> None:
        """空 provider 保持旧行为，合并函数保持顺序去重，便于 API 层稳定输出。"""

        empty_snapshot = EmptyToolActionResumeFactProvider().collect(checkpoint=FakeCheckpoint(), request_payload={})
        merged = merge_resume_fact_types(
            ("PAYLOAD_REFERENCE", "APPROVAL_CONFIRMATION_FACT"),
            ("APPROVAL_CONFIRMATION_FACT", "CLARIFICATION_FACT"),
        )

        self.assertEqual("NO_SERVER_SIDE_FACT_PROVIDER", empty_snapshot.source)
        self.assertEqual((), empty_snapshot.available_fact_types)
        self.assertEqual(("PAYLOAD_REFERENCE", "APPROVAL_CONFIRMATION_FACT", "CLARIFICATION_FACT"), merged)


if __name__ == "__main__":
    unittest.main()
