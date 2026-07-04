import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag import (
    RAG_COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY,
    RAG_TOOL_CODE,
    RagCommandWorkerReceiptBuilder,
    RagCommandWorkerReceiptEvidence,
    RagCommandWorkerReceiptOutcome,
)


class RagCommandWorkerReceiptTest(unittest.TestCase):
    """RAG -> Java command worker receipt 低敏契约测试。

    RAG 最容易泄露的内容是 question、answer、sourceUri、chunk 文本和 compressedContext。本测试保证 helper 只把
    queryRef、artifactReference 和数量指标写入控制面，正文必须留在受控知识库/对象存储/产物读取链路中。
    """

    def test_builds_low_sensitive_rag_receipt_with_counts_and_artifact_reference(self) -> None:
        """RAG receipt 应包含计数和受控产物引用，但不包含任何 RAG 正文。"""

        receipt = RagCommandWorkerReceiptBuilder().build_receipt(
            RagCommandWorkerReceiptEvidence(
                command_id="taoc_rag_001",
                session_id="session-rag-001",
                run_id="run-rag-001",
                query_ref="rag-query:sha256:abcdef123456",
                tenant_id=10,
                project_id=20,
                actor_id=30,
                task_id=9101,
                task_run_id=9201,
                answer_artifact_reference="agent-artifact:run-rag-001/taoc_rag_001/rag-answer",
                candidate_count=12,
                selected_chunk_count=4,
                citation_count=3,
                retrieval_policy_version="rag-policy.v2",
            )
        )
        summary = receipt.to_summary()
        serialized = json.dumps(summary, ensure_ascii=False)

        self.assertEqual(RagCommandWorkerReceiptOutcome.RAG_QUERY_COMPLETED, receipt.outcome)
        self.assertEqual(RAG_COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY, receipt.payload_policy)
        self.assertEqual("RAG_QUERY_COMPLETED", receipt.java_payload["outcome"])
        self.assertEqual(RAG_TOOL_CODE, receipt.java_payload["toolCode"])
        self.assertEqual("READ_ONLY_QUERY_SUMMARY", receipt.java_payload["workerReceiptMode"])
        self.assertTrue(receipt.java_payload["preCheckPassed"])
        self.assertFalse(receipt.java_payload["sideEffectStarted"])
        self.assertFalse(receipt.java_payload["sideEffectExecuted"])
        self.assertEqual("rag-query:sha256:abcdef123456", receipt.java_payload["auditId"])
        self.assertEqual("agent-artifact:run-rag-001/taoc_rag_001/rag-answer", receipt.java_payload["artifactReference"])
        self.assertEqual(12, summary["ragSummary"]["candidateCount"])
        self.assertEqual(4, summary["ragSummary"]["selectedChunkCount"])
        self.assertEqual(3, summary["ragSummary"]["citationCount"])
        self.assertNotIn("用户原始问题", serialized)
        self.assertNotIn("模型答案正文", serialized)
        self.assertNotIn("sourceUri", serialized)
        self.assertNotIn("document body", serialized)
        self.assertNotIn("compressedContext", serialized)

    def test_rejects_raw_question_like_query_ref_and_unsafe_artifact_reference(self) -> None:
        """queryRef 和 artifactReference 必须是低敏引用，不能是问题正文、URL 或路径。"""

        builder = RagCommandWorkerReceiptBuilder()
        with self.assertRaises(ValueError):
            builder.build_receipt(
                RagCommandWorkerReceiptEvidence(
                    command_id="taoc_rag_002",
                    session_id="session-rag-001",
                    run_id="run-rag-001",
                    query_ref="用户原始问题: 这个表为什么质量校验失败?",
                    answer_artifact_reference="agent-artifact:run-rag-001/taoc_rag_002/rag-answer",
                )
            )
        with self.assertRaises(ValueError):
            builder.build_receipt(
                RagCommandWorkerReceiptEvidence(
                    command_id="taoc_rag_003",
                    session_id="session-rag-001",
                    run_id="run-rag-001",
                    query_ref="rag-query:sha256:abcdef123456",
                    answer_artifact_reference="https://internal.example.local/rag-answer?token=secret",
                )
            )

    def test_rejects_sensitive_recommended_action_to_prevent_prompt_or_answer_leakage(self) -> None:
        """recommendedActions 也可能被滥用成正文通道，因此同样要做敏感片段拦截。"""

        with self.assertRaises(ValueError):
            RagCommandWorkerReceiptBuilder().build_receipt(
                RagCommandWorkerReceiptEvidence(
                    command_id="taoc_rag_004",
                    session_id="session-rag-001",
                    run_id="run-rag-001",
                    query_ref="rag-query:sha256:abcdef123456",
                    answer_artifact_reference="agent-artifact:run-rag-001/taoc_rag_004/rag-answer",
                    recommended_actions=("answer: 模型答案正文不应该进入 receipt",),
                )
            )


if __name__ == "__main__":
    unittest.main()
