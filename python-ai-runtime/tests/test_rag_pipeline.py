"""RAG 管线测试。

这些测试保护的是 RAG 的“原理闭环”，不是某个模型回答质量：
- 文档会被切块；
- 检索会先做范围隔离；
- lexical/vector 融合后能选出证据；
- 上下文压缩和引用会进入结果；
- 没有证据时不会让模型裸答。
"""

import os
import sys
import unittest
from datetime import datetime

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import ProviderType, WorkloadType
from datasmart_ai_runtime.services.memory import DeterministicHashEmbeddingProvider
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService, ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag import (
    InMemoryRagKnowledgeBase,
    RagChunkSourceType,
    RagDocument,
    RagHybridRetriever,
    RagHybridRetrieverSettings,
    RagPipeline,
    RagQuery,
)


class RagPipelineTest(unittest.TestCase):
    """验证 RAG 管线的召回、隔离、压缩和 fallback。"""

    def test_pipeline_retrieves_compresses_and_cites_governance_evidence(self) -> None:
        """RAG 应返回证据引用和可解释分数。"""

        pipeline = self._pipeline(minimum_vector_score=-1.0)

        result = pipeline.answer(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                workspace_key="workspace-a",
                question="数据质量规则生成应该考虑哪些内容？",
                top_k=2,
                generate_answer=False,
            )
        )

        summary = result.to_summary()
        self.assertFalse(summary["generated"])
        self.assertGreaterEqual(len(summary["citations"]), 1)
        self.assertIn("[C1]", summary["compressedContext"])
        self.assertTrue(summary["retrievalSummary"]["hasLexicalSignal"])
        self.assertTrue(summary["retrievalSummary"]["hasVectorSignal"])
        self.assertIn("数据质量", str(summary["citations"]))
        self.assertNotIn("retrievedChunks", summary)
        self.assertNotIn("rerankerInputChunks", summary)
        self.assertNotIn("tenant-b-private", str(summary))

        retrieval_summary = summary["retrievalSummary"]
        self.assertTrue(retrieval_summary["queryDigest"].startswith("sha256:"))
        self.assertTrue(retrieval_summary["evidenceDigest"].startswith("sha256:"))
        self.assertEqual(len(summary["citations"]), retrieval_summary["evidenceCount"])
        self.assertEqual(("document",), retrieval_summary["evidenceSourceTypes"])
        self.assertEqual(
            {"tenantId": "tenant-a", "projectId": "project-a", "workspaceKey": "workspace-a"},
            retrieval_summary["scope"],
        )
        self.assertEqual(len(summary["citations"]), len(retrieval_summary["evidenceRecords"]))
        self.assertNotIn("question", retrieval_summary["querySummary"])
        for evidence in retrieval_summary["evidenceRecords"]:
            self.assertTrue(evidence["evidenceId"].startswith("rag-evidence:"))
            self.assertEqual(retrieval_summary["queryDigest"], evidence["queryDigest"])
            self.assertIn(evidence["sourceType"], {"document", "rule", "metadata", "runbook", "incident", "task_case", "dataset", "memory_export", "wiki", "git_history"})
            self.assertTrue(evidence["sourceUri"])
            self.assertEqual(evidence["sourceUri"], evidence["sourceRef"])
            datetime.fromisoformat(evidence["retrievedAt"].replace("Z", "+00:00"))
            self.assertGreaterEqual(evidence["confidence"], 0.0)
            self.assertLessEqual(evidence["confidence"], 1.0)
            self.assertEqual("HYBRID_RETRIEVAL_SCORE", evidence["confidenceBasis"])
            self.assertEqual("COMPLETE", evidence["sourceStatus"])
            self.assertEqual("2026-08-15T00:00:00Z", evidence["sourceEffectiveAt"])
            self.assertEqual(0.98, evidence["sourceConfidence"])

    def test_scope_filter_blocks_other_tenant_documents_before_ranking(self) -> None:
        """其他租户文档即使命中关键词，也不能进入候选和引用。"""

        pipeline = self._pipeline()

        result = pipeline.answer(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                workspace_key="workspace-a",
                question="tenant-b-private 权限规则是什么？",
                generate_answer=False,
            )
        )

        serialized = str(result.to_summary())
        self.assertNotIn("tenant-b-private", serialized)
        self.assertNotIn("tenant-b-doc", serialized)

    def test_explicit_cross_scope_reference_fails_closed_before_retrieval(self) -> None:
        """问题点名其他租户/项目时，不能用当前范围的相似文档替代目标资料作答。"""

        routes = ModelRouteRegistry(default_model_routes())
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="global-same-topic",
                    title="订单主题 CDC 同步案例 SYN-ORD-602",
                    source_uri="test://global-same-topic",
                    content="SYN-ORD-602 的全局说明与目标私有项目主题高度相似。",
                ),
            )
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(knowledge_base),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="10",
                project_id="101",
                workspace_key="tenant-10-project-101",
                actor_id="owner-a",
                question="请给出租户 10 项目 102 的订单主题 CDC 同步案例 SYN-ORD-602。",
                retrieval_mode="lexical",
                generate_answer=False,
            )
        )

        self.assertEqual((), result.citations)
        self.assertEqual(
            "RAG_QUERY_SCOPE_REFERENCE_CONFLICT",
            result.retrieval_summary["reasonCode"],
        )
        self.assertTrue(result.retrieval_summary["scopeReferenceConflict"])

    def test_explicit_current_scope_reference_remains_retrievable(self) -> None:
        """问题点名的租户/项目与当前授权一致时，范围语义门禁不应误拒绝。"""

        pipeline = self._pipeline(minimum_vector_score=-1.0)

        result = pipeline.answer(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="workspace-a",
                actor_id="owner-a",
                question="请给出租户 tenant-a 项目 project-a 的数据质量规则生成依据。",
                retrieval_mode="lexical",
                generate_answer=False,
            )
        )

        self.assertGreaterEqual(len(result.citations), 1)
        self.assertNotIn("reasonCode", result.retrieval_summary)

    def test_superseded_evidence_is_hidden_unless_query_is_explicit_history_lookup(self) -> None:
        """当前问答不能引用已替代证据，但显式历史追溯仍应保留审计可达性。"""

        routes = ModelRouteRegistry(default_model_routes())
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="current-runbook",
                    title="索引重建现行手册",
                    source_uri="test://current-runbook",
                    source_type=RagChunkSourceType.RUNBOOK,
                    content="索引重建必须先校验文档哈希，再切换现行索引。",
                    metadata={"sourceStatus": "COMPLETE", "evidenceStatus": "current"},
                ),
                RagDocument(
                    document_id="superseded-history",
                    title="索引重建历史方案",
                    source_uri="test://superseded-history",
                    source_type=RagChunkSourceType.GIT_HISTORY,
                    content="索引重建历史方案允许跳过哈希校验，该做法已经废止。",
                    metadata={"sourceStatus": "SUPERSEDED", "evidenceStatus": "superseded"},
                ),
            )
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(knowledge_base),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
        )

        current_result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="索引重建现在应怎样校验并切换？",
                source_types=("runbook", "git_history"),
                retrieval_mode="lexical",
                generate_answer=False,
            )
        )
        history_result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="auditor-a",
                question="索引重建历史方案为什么已经废止？",
                source_types=("git_history",),
                retrieval_mode="lexical",
                generate_answer=False,
            )
        )

        self.assertEqual(("current-runbook",), tuple(item.document_id for item in current_result.citations))
        self.assertEqual(
            ("superseded-history",),
            tuple(item.document_id for item in history_result.citations),
        )

    def test_no_evidence_fails_closed_without_model_generation(self) -> None:
        """没有证据时应拒绝无依据生成。"""

        pipeline = self._pipeline()

        result = pipeline.answer(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                workspace_key="workspace-a",
                question="完全不存在的火星仓库调度策略",
                generate_answer=True,
            )
        )

        self.assertFalse(result.generated, result.to_summary())
        self.assertEqual(0, len(result.citations))
        self.assertEqual(1, result.retrieval_summary["weakEvidenceRejectedCount"])
        self.assertIn("没有召回到足够证据", result.answer)

    def test_vector_retriever_batches_uncached_chunk_embeddings(self) -> None:
        """真实模型评测首次预热应批量向量化 chunk，后续查询复用缓存。"""

        provider = _RecordingEmbeddingProvider()
        knowledge_base = InMemoryRagKnowledgeBase(
            tuple(
                RagDocument(
                    document_id=f"batch-doc-{index}",
                    title=f"批量文档 {index}",
                    source_uri=f"test://batch/{index}",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="workspace-a",
                    content=f"批量向量缓存测试内容 {index}",
                )
                for index in range(4)
            )
        )
        retriever = RagHybridRetriever(
            knowledge_base,
            embedding_provider=provider,
            settings=RagHybridRetrieverSettings(minimum_vector_score=-1.0),
        )
        query = RagQuery(
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="workspace-a",
            actor_id="owner-a",
            question="批量向量缓存",
            retrieval_mode="vector",
            top_k=4,
        )

        self.assertEqual(4, len(retriever.retrieve(query)))
        self.assertEqual(1, len(provider.single_calls))
        self.assertEqual(1, len(provider.batch_calls))
        self.assertEqual(4, len(provider.batch_calls[0]))

        self.assertEqual(4, len(retriever.retrieve(query)))
        self.assertEqual(2, len(provider.single_calls))
        self.assertEqual(1, len(provider.batch_calls))

    def test_reranker_receives_candidate_window_before_top_k_is_applied(self) -> None:
        """专用重排模型必须看到候选窗口，最终引用数量才由 topK 控制。"""

        routes = ModelRouteRegistry(default_model_routes())
        reranker = _RecordingReranker()
        knowledge_base = InMemoryRagKnowledgeBase(
            tuple(
                RagDocument(
                    document_id=f"rerank-doc-{index}",
                    title=f"字段映射恢复案例 {index}",
                    source_uri=f"test://rerank/{index}",
                    content=f"字段映射恢复需要刷新元数据并执行预检，候选编号 {index}。",
                )
                for index in range(5)
            )
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(knowledge_base),
            reranker=reranker,
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="字段映射恢复需要哪些步骤？",
                retrieval_mode="lexical",
                candidate_limit=5,
                top_k=1,
                generate_answer=False,
            )
        )

        self.assertEqual(5, len(reranker.seen_document_ids))
        self.assertEqual(1, len(result.citations))

    @staticmethod
    def _pipeline(*, minimum_vector_score: float = 0.65) -> RagPipeline:
        """构造带确定性 embedding 的测试 RAG 管线。"""

        routes = ModelRouteRegistry(default_model_routes())
        gateway = ModelGatewayGovernanceService(routes)
        providers = ModelProviderRegistry()
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="quality-doc",
                    title="数据质量规则生成",
                    source_uri="test://quality",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="workspace-a",
                    tags=("数据质量", "规则生成"),
                    metadata={
                        "sourceStatus": "COMPLETE",
                        "effectiveAt": "2026-08-15T00:00:00Z",
                        "sourceConfidence": 0.98,
                    },
                    content=(
                        "数据质量规则生成需要结合字段口径、元数据、历史异常、完整性、唯一性、有效性和审批策略。"
                        "高风险清洗动作应先形成草案，再进入人工确认和任务管理。"
                    ),
                ),
                RagDocument(
                    document_id="tenant-b-doc",
                    title="tenant-b-private 权限规则",
                    source_uri="test://tenant-b",
                    tenant_id="tenant-b",
                    project_id="project-b",
                    workspace_key="workspace-b",
                    tags=("tenant-b-private",),
                    content="tenant-b-private 资料只属于 tenant-b，tenant-a 不能检索到。",
                ),
            )
        )
        retriever = RagHybridRetriever(
            knowledge_base,
            embedding_provider=DeterministicHashEmbeddingProvider(dimensions=16),
            settings=RagHybridRetrieverSettings(minimum_vector_score=minimum_vector_score),
        )
        return RagPipeline(
            retriever=retriever,
            model_routes=routes,
            model_gateway=gateway,
            model_providers=providers,
        )


class _RecordingEmbeddingProvider:
    """记录 query 单条调用和 chunk 批量调用的确定性测试 Provider。"""

    def __init__(self) -> None:
        self.single_calls: list[str] = []
        self.batch_calls: list[tuple[str, ...]] = []

    def embed_text(self, text: str) -> tuple[float, ...]:
        """记录查询向量调用。"""

        self.single_calls.append(text)
        return (1.0, 0.5, 0.25, 0.125)

    def embed_texts(self, texts: tuple[str, ...]) -> tuple[tuple[float, ...], ...]:
        """记录 chunk 批量调用并返回固定向量。"""

        self.batch_calls.append(texts)
        return tuple((1.0, 0.5, 0.25, 0.125) for _ in texts)


class _RecordingReranker:
    """记录重排输入窗口的测试替身，保持候选顺序和分数不变。"""

    def __init__(self) -> None:
        self.seen_document_ids: tuple[str, ...] = ()

    def rerank(
        self,
        query: RagQuery,
        candidates: tuple,
    ) -> tuple:
        """保存候选文档 ID，模拟专用模型完成重排。"""

        self.seen_document_ids = tuple(item.chunk.document_id for item in candidates)
        return candidates

    @staticmethod
    def diagnostics() -> dict[str, object]:
        """返回不含候选正文的测试诊断。"""

        return {"implementation": "_RecordingReranker", "configured": True}


if __name__ == "__main__":
    unittest.main()
