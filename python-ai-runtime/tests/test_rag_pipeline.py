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
        self.assertNotIn("tenant-b-private", str(summary))

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


if __name__ == "__main__":
    unittest.main()
