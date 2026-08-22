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
from dataclasses import replace
from datetime import datetime
from unittest.mock import patch

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
    RagChunk,
    RagChunkSourceType,
    RagDocument,
    RagHybridRetriever,
    RagHybridRetrieverSettings,
    RagPipeline,
    RagPipelineSettings,
    RagQuery,
    RagScoredChunk,
    chunk_document,
)
from datasmart_ai_runtime.services.rag.text import rag_query_document_intent_score
from datasmart_ai_runtime.services.rag import text as rag_text
from datasmart_ai_runtime.services.rag import pipeline as rag_pipeline


class RagPipelineTest(unittest.TestCase):
    """验证 RAG 管线的召回、隔离、压缩和 fallback。"""

    def test_semantic_lifecycle_evidence_can_pass_without_query_word_overlap(self) -> None:
        """状态快照类资料即使没有题干原词，也能凭明确职责和向量信号进入证据集。"""

        candidate = RagScoredChunk(
            chunk=RagChunk(
                chunk_id="agent-state#0",
                document_id="agent-state",
                chunk_index=0,
                title="Agent 全链路状态快照",
                text="自动处理完成后进入 FINAL_VERIFICATION。",
                source_uri="test://agent-state",
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                source_type=RagChunkSourceType.MEMORY_EXPORT,
                metadata={
                    "category": "agent_state_snapshot",
                    "contentFormat": "json",
                },
            ),
            vector_score=0.54,
            rerank_score=0.01,
            final_score=0.01,
        )
        query = RagQuery(
            tenant_id="*",
            project_id="*",
            workspace_key="*",
            actor_id="owner-a",
            question="一件事情经过自动处理后，下一步会走向哪个收尾环节？",
            generate_answer=False,
        )

        self.assertTrue(
            rag_pipeline._has_sufficient_evidence(
                candidate,
                RagPipelineSettings(
                    minimum_vector_score=0.45,
                    minimum_unanchored_vector_score=0.82,
                ),
                query,
            )
        )

    def test_document_level_aggregation_keeps_semantic_and_lexical_chunks_of_same_document(self) -> None:
        """同一份结构化资料的词法 chunk 和向量 chunk 不应互相覆盖导致整份资料丢失。"""

        def make_candidate(chunk_id: str, *, lexical: float, vector: float, final: float) -> RagScoredChunk:
            return RagScoredChunk(
                chunk=RagChunk(
                    chunk_id=chunk_id,
                    document_id="recovery-ledger",
                    chunk_index=0,
                    title="数据库恢复与证据台账快照",
                    text="台账保存补救依据、修复动作和最终验证。",
                    source_uri="test://recovery-ledger",
                    tenant_id="*",
                    project_id="*",
                    workspace_key="*",
                    source_type=RagChunkSourceType.DATASET,
                    metadata={
                        "category": "database_recovery_ledger",
                        "contentFormat": "sql",
                    },
                ),
                lexical_score=lexical,
                vector_score=vector,
                match_terms=("台账",) if lexical else (),
                rerank_score=final,
                final_score=final,
            )

        query = RagQuery(
            tenant_id="*",
            project_id="*",
            workspace_key="*",
            actor_id="owner-a",
            question="保存的台账如何把补救过程、依据、动作和最后确认连起来？",
            source_types=("dataset",),
            generate_answer=False,
        )
        candidates = (
            make_candidate("recovery-ledger#semantic", lexical=0.0, vector=0.70, final=0.019),
            make_candidate("recovery-ledger#lexical", lexical=0.90, vector=0.0, final=0.006),
        )

        gated = tuple(
            item
            for item in candidates
            if rag_pipeline._has_sufficient_evidence(item, RagPipelineSettings(minimum_vector_score=0.45), query)
        )
        selected = rag_pipeline._prune_redundant_reranked_evidence(
            gated,
            RagPipelineSettings(
                minimum_vector_score=0.45,
                minimum_relative_rerank_score=0.82,
            ),
            query=query,
        )

        self.assertEqual(("recovery-ledger",), tuple(item.chunk.document_id for item in selected))
        self.assertEqual("recovery-ledger#semantic", selected[0].chunk.chunk_id)

    def test_vector_stage_metrics_explain_candidate_survival_without_exposing_bodies(self) -> None:
        """结果应报告向量候选经过窗口、门禁和选择的数量，方便定位后续丢失层。"""

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

        metrics = result.retrieval_summary["vectorStageMetrics"]
        self.assertGreaterEqual(metrics["vectorRetrievedCount"], metrics["vectorInRerankerCount"])
        self.assertGreaterEqual(metrics["vectorRerankedCount"], metrics["vectorAcceptedCount"])
        self.assertGreaterEqual(metrics["vectorAcceptedCount"], metrics["vectorSelectedCount"])
        self.assertIn("vectorRerankerWindowCoverage", metrics)
        self.assertNotIn("text", metrics)

    def test_high_confidence_vector_only_evidence_survives_rerank_relative_pruning(self) -> None:
        """高置信度语义候选即使远端分数偏低，也不能被相对裁剪无条件吞掉。"""

        def make_candidate(document_id: str, *, vector: float, final: float) -> RagScoredChunk:
            return RagScoredChunk(
                chunk=RagChunk(
                    chunk_id=f"{document_id}#0",
                    document_id=document_id,
                    chunk_index=0,
                    title=f"语义证据 {document_id}",
                    text="字段映射恢复需要查看失败日志、补齐默认值并完成最终验证。",
                    source_uri=f"test://{document_id}",
                    tenant_id="*",
                    project_id="*",
                    workspace_key="*",
                    source_type=RagChunkSourceType.RUNBOOK,
                    metadata={"category": "field_mapping_recovery", "contentFormat": "md"},
                ),
                lexical_score=0.0,
                vector_score=vector,
                rerank_score=final,
                final_score=final,
            )

        query = RagQuery(
            tenant_id="*",
            project_id="*",
            workspace_key="*",
            actor_id="owner-a",
            question="如何依据失败日志完成字段恢复和最终验证？",
            generate_answer=False,
        )
        candidates = (
            make_candidate("primary", vector=0.60, final=1.0),
            make_candidate("semantic-recovery", vector=0.90, final=0.20),
        )
        gated = tuple(
            item
            for item in candidates
            if rag_pipeline._has_sufficient_evidence(item, RagPipelineSettings(), query)
        )

        selected = rag_pipeline._prune_redundant_reranked_evidence(
            gated,
            RagPipelineSettings(minimum_relative_rerank_score=0.82),
            query=query,
        )

        self.assertIn("semantic-recovery", {item.chunk.document_id for item in selected})

    def test_query_intent_prior_cannot_overwrite_remote_reranker_order(self) -> None:
        """职责先验只能打破近似平分，不能盖过真实 Cross-Encoder 的相关性排序。"""

        generic_chunk = RagChunk(
            chunk_id="generic#0",
            document_id="generic",
            chunk_index=0,
            title="通用同步说明",
            text="同步任务的通用说明。",
            source_uri="test://generic",
            tenant_id="*",
            project_id="*",
            workspace_key="*",
            source_type=RagChunkSourceType.DOCUMENT,
            metadata={"category": "product_features", "contentFormat": "docx"},
        )
        mapping_chunk = RagChunk(
            chunk_id="mapping#0",
            document_id="mapping",
            chunk_index=0,
            title="字段映射案例",
            text="字段映射案例表格。",
            source_uri="test://mapping",
            tenant_id="*",
            project_id="*",
            workspace_key="*",
            source_type=RagChunkSourceType.DATASET,
            metadata={"category": "field_mapping_case", "contentFormat": "xlsx"},
        )
        candidates = (
            RagScoredChunk(
                chunk=generic_chunk,
                rerank_score=0.010,
                final_score=0.010,
            ),
            RagScoredChunk(
                chunk=mapping_chunk,
                rerank_score=0.008,
                final_score=0.008,
            ),
        )

        adjusted = rag_pipeline._apply_query_intent_prior(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="字段映射案例表格记录了哪些默认值和非空约束？",
                source_types=("dataset",),
                generate_answer=False,
            ),
            candidates,
            boost=0.08,
        )

        self.assertEqual("generic", adjusted[0].chunk.document_id)
        self.assertGreater(adjusted[0].final_score, adjusted[1].final_score)

    def test_open_semantic_questions_activate_their_document_responsibilities(self) -> None:
        """不含资料原词的自然问法仍应路由到正确职责，未知业务实体则不能获得高职责分。"""

        scenarios = (
            (
                "查资料时，怎样保证不会把别的团队内容混进当前答复？",
                RagDocument(
                    document_id="scope-filter",
                    title="RAG 范围过滤架构说明",
                    source_uri="test://scope-filter",
                    source_type=RagChunkSourceType.DOCUMENT,
                    tags=("架构", "rag", "范围隔离"),
                    content="检索前按租户和项目执行范围过滤。",
                ),
            ),
            (
                "一项工作结束后，处理结果怎样准确回到最初发起的那件事上？",
                RagDocument(
                    document_id="event-bridge",
                    title="异步事件桥接架构说明",
                    source_uri="test://event-bridge",
                    source_type=RagChunkSourceType.WIKI,
                    tags=("架构", "事件", "关联"),
                    content="回执通过 correlationId 关联原任务。",
                ),
            ),
            (
                "给出结论时，怎样让人能追查它依据了哪份材料，并在找不到材料时停下来？",
                RagDocument(
                    document_id="citation-evidence",
                    title="可引用证据链架构说明",
                    source_uri="test://citation-evidence",
                    source_type=RagChunkSourceType.DOCUMENT,
                    tags=("架构", "引用", "证据"),
                    content="回答绑定引用来源，无证据时拒绝生成。",
                ),
            ),
            (
                "准备启用一批新资料时，先怎样把关，才不会把有问题的内容带进来？",
                RagDocument(
                    document_id="index-rebuild",
                    title="RAG 索引重建 Runbook",
                    source_uri="test://index-rebuild",
                    source_type=RagChunkSourceType.RUNBOOK,
                    tags=("运维", "rag", "索引重建"),
                    content="索引发布前核验文档哈希并执行质量门禁。",
                ),
            ),
            (
                "订单变更已经写到接收系统后，什么时候才可记录处理进度？",
                RagDocument(
                    document_id="cdc-order",
                    title="订单主题 CDC 同步案例",
                    source_uri="test://cdc-order",
                    source_type=RagChunkSourceType.TASK_CASE,
                    tags=("同步", "cdc", "订单主题"),
                    content="目标提交成功后才推进 checkpoint。",
                ),
            ),
            (
                "有人声称自己能操作某项数据时，系统还要核实哪些实际许可才可放行？",
                RagDocument(
                    document_id="least-privilege",
                    title="最小权限治理规则",
                    source_uri="test://least-privilege",
                    source_type=RagChunkSourceType.RULE,
                    tags=("治理", "权限", "rbac"),
                    content="根据可信授权事实执行最小权限校验。",
                ),
            ),
        )

        for question, document in scenarios:
            with self.subTest(document=document.document_id):
                score = rag_query_document_intent_score(
                    question,
                    chunk_document(document)[0],
                )
                self.assertGreaterEqual(score, 0.85)

        unknown_score = rag_query_document_intent_score(
            "量子账本回灌作业应如何审批？",
            chunk_document(scenarios[-1][1])[0],
        )
        self.assertLess(unknown_score, 0.85)

    def test_broad_category_does_not_promote_unrelated_same_type_documents(self) -> None:
        """同为 Runbook 或规则资料不等于回答同一问题，宽泛类别必须有主题标签配合。"""

        query = "准备启用一批新资料时，先怎样把关，才不会把有问题的内容带进来？"
        target = RagDocument(
            document_id="index-rebuild",
            title="RAG 索引重建 Runbook",
            source_uri="test://index-rebuild",
            source_type=RagChunkSourceType.RUNBOOK,
            tags=("运维", "rag", "索引重建"),
            content="索引发布前核验文档哈希并执行质量门禁。",
            metadata={"category": "runbook", "contentFormat": "md"},
        )
        unrelated = RagDocument(
            document_id="kafka-backlog",
            title="事件积压处置 Runbook",
            source_uri="test://kafka-backlog",
            source_type=RagChunkSourceType.RUNBOOK,
            tags=("运维", "Kafka", "积压"),
            content="消费者积压时检查 lag 并按 Runbook 处置。",
            metadata={"category": "runbook", "contentFormat": "md"},
        )

        target_score = rag_query_document_intent_score(query, chunk_document(target)[0])
        unrelated_score = rag_query_document_intent_score(query, chunk_document(unrelated)[0])

        self.assertGreaterEqual(target_score, 0.85)
        self.assertLess(unrelated_score, 0.85)

    def test_multi_facet_lexical_score_tokenizes_chunk_body_only_once(self) -> None:
        """同一 chunk 的多个查询变体必须复用词法画像，避免按 facet 重复分词。"""

        chunk = RagChunk(
            chunk_id="lexical-profile#0",
            document_id="lexical-profile",
            chunk_index=0,
            title="连接器容量与限流说明",
            text="API 目标限流后降低批量和并发，并核对连接器容量与最终验证。",
            source_uri="test://lexical-profile",
            tenant_id="*",
            project_id="*",
            workspace_key="*",
            source_type=RagChunkSourceType.DOCUMENT,
        )
        original_tokenizer = rag_text.tokenize_for_rag
        tokenized_texts: list[str] = []

        def recording_tokenizer(text: str) -> tuple[str, ...]:
            """记录分词输入并委托真实实现，保证测试仍验证原有分数路径。"""

            tokenized_texts.append(text)
            return original_tokenizer(text)

        with patch.object(rag_text, "tokenize_for_rag", side_effect=recording_tokenizer):
            score = rag_text.lexical_score_for_query(
                "请结合 API 限流、连接器容量和最终验证给出处理顺序。",
                chunk,
            )

        self.assertGreater(score.score, 0.0)
        self.assertEqual(1, tokenized_texts.count(chunk.text))

    def test_operations_command_intent_uses_category_filter_without_runtime_name_error(self) -> None:
        """运维流程中的只读命令应由职责过滤器选择，意图激活阶段不能读取不存在的类别变量。"""

        command_chunk = RagChunk(
            chunk_id="operations-command#0",
            document_id="operations-command",
            chunk_index=0,
            title="运维只读命令参考",
            text="运维流程先执行只读命令定位异常，再决定是否进入受治理修复。",
            source_uri="test://operations-command",
            tenant_id="*",
            project_id="*",
            workspace_key="*",
            source_type=RagChunkSourceType.RUNBOOK,
            metadata={"category": "operations_command_reference", "contentFormat": "txt"},
        )

        score = rag_query_document_intent_score(
            "运维流程中应先执行哪些只读命令定位同步异常？",
            command_chunk,
        )

        self.assertGreater(score, 0.0)

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

        # 职责门槛会直接影响多证据引用集合，必须进入低敏诊断，便于线上复现一次排序决策。
        self.assertEqual(
            0.85,
            pipeline.diagnostics()["settings"]["multiEvidenceResponsibilityIntentThreshold"],
        )

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

    def test_project_document_overlays_global_baseline_with_same_artifact_code(self) -> None:
        """项目资料存在时，同一逻辑资料的全局基线不得再进入候选窗口。

        全局资料是项目没有自有资料时的回退知识，不是与项目事实并列的第二份答案。如果两者使用同一个
        ``artifactCode``，检索器必须在调用 Embedding 或 Reranker 之前选择范围更具体的项目版本。否则
        外部模型既会看到重复正文，也可能把全局默认值误当成项目当前配置。
        """

        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="global-runbook",
                    title="字段映射恢复手册",
                    source_uri="test://global-runbook",
                    content="字段映射失败后刷新元数据并重新预检。",
                    metadata={"artifactCode": "RUNBOOK-MAPPING-001"},
                ),
                RagDocument(
                    document_id="project-runbook",
                    title="字段映射恢复手册",
                    source_uri="test://project-runbook",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="workspace-a",
                    content="项目字段映射失败后刷新元数据并重新预检。",
                    metadata={"artifactCode": "RUNBOOK-MAPPING-001"},
                ),
            )
        )
        retriever = RagHybridRetriever(knowledge_base)

        candidates = retriever.retrieve(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="workspace-a",
                actor_id="owner-a",
                question="字段映射失败后怎样恢复？",
                retrieval_mode="lexical",
            )
        )

        self.assertEqual(
            ("project-runbook",),
            tuple(dict.fromkeys(item.chunk.document_id for item in candidates)),
        )

    def test_global_baseline_remains_available_without_project_overlay(self) -> None:
        """项目没有同一逻辑资料时，全局基线仍应作为授权范围内的回退证据。"""

        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="global-runbook",
                    title="Kafka 积压恢复手册",
                    source_uri="test://global-runbook",
                    content="Kafka 积压时先检查消费延迟和分区负载。",
                    metadata={"artifactCode": "RUNBOOK-KAFKA-001"},
                ),
            )
        )
        retriever = RagHybridRetriever(knowledge_base)

        candidates = retriever.retrieve(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="workspace-a",
                actor_id="owner-a",
                question="Kafka 积压时先检查什么？",
                retrieval_mode="lexical",
            )
        )

        self.assertEqual(
            ("global-runbook",),
            tuple(dict.fromkeys(item.chunk.document_id for item in candidates)),
        )

    def test_exact_search_prefers_metadata_owner_over_body_cross_reference(self) -> None:
        """正文顺带引用资料码时，精确搜索仍只能返回拥有该码和锚点的主资料。"""

        routes = ModelRouteRegistry(default_model_routes())
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="exact-owner",
                    title="Kafka 积压 Runbook",
                    source_uri="test://exact-owner",
                    content="资料码 OPS-KAF-208 要求先检查消费延迟和失败比例。",
                    metadata={
                        "artifactCode": "OPS-KAF-208",
                        "retrievalAnchor": "global:runbook-kafka-backlog",
                    },
                ),
                RagDocument(
                    document_id="api-cross-reference",
                    title="综合接口说明",
                    source_uri="test://api-cross-reference",
                    content=(
                        "接口响应可能引用 OPS-KAF-208 和 global:runbook-kafka-backlog，"
                        "但本文件不是该 Runbook 的定义来源。"
                    ),
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
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question=(
                    "精确码 OPS-KAF-208 规定先看什么？"
                    "请只依据 global:runbook-kafka-backlog 回答。"
                ),
                retrieval_mode="exact_search",
                generate_answer=False,
            )
        )

        self.assertEqual(("exact-owner",), tuple(item.document_id for item in result.citations))
        self.assertEqual(("exact-owner",), tuple(item.chunk.document_id for item in result.reranker_input_chunks))
        self.assertEqual(1.0, result.selected_chunks[0].exact_score)

    def test_superseded_identifier_routes_to_current_document_without_exposing_history(self) -> None:
        """普通查询给出旧资料码时，应沿替代关系读取现行资料而不是返回过期正文。"""

        routes = ModelRouteRegistry(default_model_routes())
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="history-index-v1",
                    title="已过期索引重建方案",
                    source_uri="test://history-index-v1",
                    source_type=RagChunkSourceType.GIT_HISTORY,
                    content="HIS-RAG-001 曾允许在哈希核验前切换索引。",
                    metadata={
                        "artifactCode": "HIS-RAG-001",
                        "retrievalAnchor": "global:history-index-v1",
                        "evidenceStatus": "superseded",
                        "sourceStatus": "SUPERSEDED",
                        "supersededBy": "runbook-index-current",
                    },
                ),
                RagDocument(
                    document_id="runbook-index-current",
                    title="现行索引重建 Runbook",
                    source_uri="test://runbook-index-current",
                    source_type=RagChunkSourceType.RUNBOOK,
                    content="现行规则要求先冻结写入并核验内容哈希，再切换索引。",
                    metadata={
                        "artifactCode": "OPS-RAG-503",
                        "retrievalAnchor": "global:runbook-index-current",
                        "evidenceStatus": "current",
                        "sourceStatus": "COMPLETE",
                    },
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
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="历史码 HIS-RAG-001 与当前规则冲突时，现在应依据什么执行？",
                retrieval_mode="hybrid",
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("runbook-index-current",),
            tuple(item.document_id for item in result.citations),
        )
        self.assertNotIn("history-index-v1", str(result.to_summary()))
        self.assertTrue(result.selected_chunks[0].exact_match_identifiers[0].startswith("replacement:"))

    def test_cross_tenant_superseded_relation_cannot_select_current_document(self) -> None:
        """其他租户的旧资料关系不能影响当前租户的现行资料选择。

        ``supersededBy`` 只是一条版本关系，不是跨范围授权。即使 tenant-b 的历史资料恰好指向
        tenant-a 中真实存在的文档，tenant-a 查询也不能读取或使用这条历史关系，否则第三方租户可以
        通过伪造旧资料元数据改变当前租户的精确检索结果。
        """

        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="tenant-b-history",
                    title="其他租户的历史资料",
                    source_uri="test://tenant-b/history",
                    tenant_id="tenant-b",
                    project_id="project-b",
                    workspace_key="space-b",
                    source_type=RagChunkSourceType.GIT_HISTORY,
                    content="HIS-CROSS-901 是 tenant-b 的历史资料码。",
                    metadata={
                        "artifactCode": "HIS-CROSS-901",
                        "sourceStatus": "SUPERSEDED",
                        "supersededBy": "tenant-a-current",
                    },
                ),
                RagDocument(
                    document_id="tenant-a-current",
                    title="当前租户现行资料",
                    source_uri="test://tenant-a/current",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="space-a",
                    content="当前租户自己的现行执行规则。",
                    metadata={"sourceStatus": "COMPLETE"},
                ),
            )
        )

        replacements = knowledge_base.replacement_chunks_for_query(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="space-a",
                actor_id="owner-a",
                question="HIS-CROSS-901 现在对应哪份资料？",
                generate_answer=False,
            ),
            ("his-cross-901",),
        )

        self.assertEqual((), replacements)

    def test_plain_exact_overlap_does_not_bypass_evidence_thresholds(self) -> None:
        """普通字段或错误码命中不能伪装成受治理的精确取证指令。

        用户明确按资料码读取、或知识库沿 ``supersededBy`` 找到现行替代时，可以使用确定性精确
        通道；普通问句中偶然出现字段名时，候选仍必须通过词法或向量证据门禁。
        """

        chunk = RagChunk(
            chunk_id="weak-exact#0",
            document_id="weak-exact",
            chunk_index=0,
            title="无关字段说明",
            text="这里只提到 order_event_id，没有回答用户问题。",
            source_uri="test://weak-exact",
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="space-a",
            source_type=RagChunkSourceType.DOCUMENT,
        )
        weak_candidate = RagScoredChunk(
            chunk=chunk,
            exact_score=0.01,
            exact_match_identifiers=("order_event_id",),
        )
        settings = RagPipelineSettings(
            minimum_lexical_score=0.5,
            minimum_vector_score=0.9,
        )
        ordinary_query = RagQuery(
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="space-a",
            actor_id="owner-a",
            question="order_event_id 在月球归档流程中如何处理？",
            generate_answer=False,
        )

        self.assertFalse(
            rag_pipeline._has_sufficient_evidence(weak_candidate, settings, ordinary_query)
        )
        self.assertTrue(
            rag_pipeline._has_sufficient_evidence(
                replace(
                    weak_candidate,
                    exact_score=1.0,
                    exact_match_identifiers=("OPS-EXACT-901",),
                ),
                settings,
                replace(
                    ordinary_query,
                    question="请只依据精确资料码 OPS-EXACT-901 回答。",
                    retrieval_mode="exact_search",
                ),
            )
        )
        self.assertTrue(
            rag_pipeline._has_sufficient_evidence(
                replace(
                    weak_candidate,
                    exact_score=0.82,
                    exact_match_identifiers=("replacement:current-runbook",),
                ),
                settings,
                ordinary_query,
            )
        )

    def test_explicit_document_code_keeps_only_the_strongest_exact_owner(self) -> None:
        """显式指定资料码时，正文中只命中字段名的邻居不能成为第二条引用。

        一个问题可以同时包含 ``region_code`` 这样的字段名和 ``CSV-PROF-799`` 这样的资料码。两者都
        会进入 exact 召回，但语义完全不同：前者用于发现互补证据，后者表达用户明确指定的资料所有者。
        管线必须比较 exact 匹配强度，只保留完整拥有资料码和字段锚点的当前资料，不能把所有
        ``exact_score > 0`` 的候选都当作受保护引用。
        """

        documents = (
            RagDocument(
                document_id="field-profile-owner",
                title="字段画像统计",
                source_uri="test://field-profile-owner",
                source_type=RagChunkSourceType.DATASET,
                content="region_code 的 null_ratio 和默认值用于判断非空修复是否安全。",
                metadata={
                    "artifactCode": "CSV-PROF-799",
                    "category": "field_profile_statistics",
                },
            ),
            RagDocument(
                document_id="mapping-neighbour",
                title="字段映射案例",
                source_uri="test://mapping-neighbour",
                source_type=RagChunkSourceType.DATASET,
                content="region_code 发生非空失败时可以先补齐映射并执行预检。",
                metadata={"category": "field_mapping_case"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {"field-profile-owner": 0.82, "mapping-neighbour": 0.96},
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question=(
                    "字段画像如何判断 region_code 的默认值和非空修复是否安全？"
                    "请依据精确码 CSV-PROF-799 和原始 CSV 资料回答。"
                ),
                retrieval_mode="hybrid",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("field-profile-owner",),
            tuple(item.document_id for item in result.citations),
        )

    def test_current_replacement_survives_a_low_remote_reranker_score(self) -> None:
        """旧资料码的受治理现行替代不能被远端绝对分数下限误删。

        ``supersededBy`` 是知识摄取阶段已经校验过的当前版本关系，不是模糊语义相似度。远端模型可能
        因现行正文不再重复旧资料码而给出很低分；此时仍应引用同范围、非过期的替代资料，同时继续
        隐藏历史正文。该保护只适用于关系命中的当前资料，不会放宽普通向量近邻的拒答门槛。
        """

        routes = ModelRouteRegistry(default_model_routes())
        documents = (
            RagDocument(
                document_id="history-cdc-v1",
                title="旧 CDC 位点规则",
                source_uri="test://history-cdc-v1",
                source_type=RagChunkSourceType.GIT_HISTORY,
                content="HIS-CDC-002 曾要求提前推进位点。",
                metadata={
                    "artifactCode": "HIS-CDC-002",
                    "sourceStatus": "SUPERSEDED",
                    "supersededBy": "sync-cdc-current",
                },
            ),
            RagDocument(
                document_id="sync-cdc-current",
                title="现行 CDC 同步规则",
                source_uri="test://sync-cdc-current",
                source_type=RagChunkSourceType.TASK_CASE,
                content="现行规则只在目标提交成功后推进 checkpoint，并保留幂等位点。",
                metadata={
                    "retrievalAnchor": "global:sync-cdc-current",
                    "sourceStatus": "COMPLETE",
                },
            ),
            RagDocument(
                document_id="generic-current-rule",
                title="通用当前执行规则",
                source_uri="test://generic-current-rule",
                source_type=RagChunkSourceType.TASK_CASE,
                content="当前执行规则要求记录任务结果。",
            ),
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(
                {"sync-cdc-current": 0.001, "generic-current-rule": 0.90}
            ),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(minimum_absolute_rerank_score=0.005),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="历史记录 HIS-CDC-002 与当前规则冲突时，现在应依据什么执行？",
                retrieval_mode="hybrid",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("sync-cdc-current",),
            tuple(item.document_id for item in result.citations),
        )
        self.assertNotIn("history-cdc-v1", str(result.to_summary()))

    def test_event_bridge_natural_question_uses_responsibility_backed_evidence(self) -> None:
        """事件桥接自然问法应由职责、正文和重排信号共同通过证据门禁。"""

        routes = ModelRouteRegistry(default_model_routes())
        document = RagDocument(
            document_id="event-bridge-architecture",
            title="异步事件桥接架构说明",
            source_uri="test://event-bridge-architecture",
            source_type=RagChunkSourceType.WIKI,
            tags=("架构", "Kafka", "事件"),
            content=(
                "业务服务与 AI Runtime 通过异步事件桥接。回执必须携带稳定 taskId、runId、"
                "correlationId 和幂等键，消费端校验事件版本后再关联原任务。"
            ),
            metadata={"category": "architecture", "contentFormat": "md"},
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase((document,))),
            reranker=_ScoreReranker({"event-bridge-architecture": 0.72}),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="服务和智能运行时之间怎样避免把回执接错任务？",
                retrieval_mode="hybrid",
                source_types=("wiki",),
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("event-bridge-architecture",),
            tuple(item.document_id for item in result.citations),
        )

        unknown = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="月球计费令牌的轮换周期和冻结阈值是什么？",
                retrieval_mode="hybrid",
                source_types=("wiki",),
                generate_answer=False,
            )
        )
        self.assertEqual((), unknown.citations)

    def test_single_document_multi_fact_question_does_not_add_nearby_reports(self) -> None:
        """一份职责明确的报告已覆盖多个事实时，不应因连接词自动补齐其他报告。"""

        documents = (
            RagDocument(
                document_id="rag-agent-quality-report",
                title="RAG 与 Agent 决策质量评测报告",
                source_uri="test://rag-agent-quality-report",
                content=(
                    "RAG 与 Agent 评测同时检查 Recall、引用精确率、拒答 F1、范围隔离和治理门禁。"
                ),
                metadata={"category": "rag_agent_evaluation_report", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="performance-report",
                title="平台性能测试报告",
                source_uri="test://performance-report",
                content="性能报告记录 Recall 评测作业的吞吐、延迟和资源占用。",
                metadata={"category": "performance_test_report", "contentFormat": "docx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {"rag-agent-quality-report": 0.95, "performance-report": 0.91},
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="RAG 与 Agent 评测为什么不能只看 Recall，还要看引用、拒答和治理？",
                retrieval_mode="hybrid",
                source_types=("document",),
                top_k=4,
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("rag-agent-quality-report",),
            tuple(item.document_id for item in result.citations),
        )

    def test_cross_format_narrative_role_beats_schema_incident_neighbor(self) -> None:
        """事故时间线问题应优先引用运维记录，不被字段事故复盘的相似词带偏。"""

        documents = (
            RagDocument(
                document_id="operations-record",
                title="同步平台运维记录",
                source_uri="test://operations-record",
                source_type=RagChunkSourceType.INCIDENT,
                content=(
                    "字段非空约束事故的时间线、自动修复和验证结果如下：先定位用户影响，"
                    "再记录修复动作并完成最终验证。"
                ),
                metadata={"category": "operations_record", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="schema-postmortem",
                title="来源 Schema 漂移事故复盘",
                source_uri="test://schema-postmortem",
                source_type=RagChunkSourceType.INCIDENT,
                content="字段非空失败可能由 Schema 漂移和元数据未刷新造成。",
                metadata={"category": "incident_schema_drift", "contentFormat": "docx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {"operations-record": 0.42, "schema-postmortem": 0.98},
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="字段非空约束事故的时间线、自动修复和验证结果是什么？",
                retrieval_mode="hybrid",
                source_types=("incident",),
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("operations-record",),
            tuple(item.document_id for item in result.citations),
        )

    def test_natural_ood_chronology_wording_routes_to_operations_record(self) -> None:
        """未出现在意图提示表中的自然同义问法也应识别运维处置记录职责。

        这条回归刻意不用“运维记录、事故时间线、自动修复和验证结果”等黄金集措辞，而使用值守人员
        更可能输入的“发现到复核的处置经过”。职责路由应依赖“故障处置 + 经过/复核”的概念组合，
        不能靠照抄某一道评测题的长短语。
        """

        documents = (
            RagDocument(
                document_id="operations-record-ood",
                title="同步平台值守处置记录",
                source_uri="test://operations-record-ood",
                source_type=RagChunkSourceType.INCIDENT,
                content="非空约束故障从用户报障、定位、处置到复核的完整经过均已留痕。",
                metadata={"category": "operations_record", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="schema-postmortem-ood",
                title="Schema 漂移根因复盘",
                source_uri="test://schema-postmortem-ood",
                source_type=RagChunkSourceType.INCIDENT,
                content="非空约束故障可能由 Schema 漂移和元数据未刷新造成。",
                metadata={"category": "incident_schema_drift", "contentFormat": "docx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {"operations-record-ood": 0.42, "schema-postmortem-ood": 0.98},
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="值守人员留下的非空约束故障，从发现到复核的处置经过是什么？",
                retrieval_mode="hybrid",
                source_types=("incident",),
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("operations-record-ood",),
            tuple(item.document_id for item in result.citations),
        )

    def test_unknown_entity_with_only_generic_lexical_hits_is_rejected(self) -> None:
        """知识库外实体不能仅凭“规则、调度、阈值”等泛词通过证据门禁。"""

        routes = ModelRouteRegistry(default_model_routes())
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="generic-scheduling-rule",
                    title="任务调度规则",
                    source_uri="test://generic-scheduling-rule",
                    content="当前任务调度规则包含并发阈值和审批步骤。",
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
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="火星冷链调度规则的当前阈值是多少？",
                retrieval_mode="lexical",
                generate_answer=False,
            )
        )

        self.assertEqual((), result.citations)
        self.assertEqual("no_evidence", result.model_summary["reason"])

    def test_unknown_entity_gate_accepts_document_that_covers_the_entity(self) -> None:
        """泛词门禁不能误伤真正覆盖问题实体的资料。"""

        routes = ModelRouteRegistry(default_model_routes())
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="mars-cold-chain-rule",
                    title="火星冷链调度规则",
                    source_uri="test://mars-cold-chain-rule",
                    content="火星冷链调度规则的当前阈值为 18，超过阈值时暂停并告警。",
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
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="火星冷链调度规则的当前阈值是多少？",
                retrieval_mode="lexical",
                generate_answer=False,
            )
        )

        self.assertEqual(("mars-cold-chain-rule",), tuple(item.document_id for item in result.citations))

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

    def test_vector_cache_isolates_same_text_by_sensitivity_and_propagates_query_level(self) -> None:
        """相同正文跨分级不得共用向量，查询与每个 chunk 的分级必须传到模型边界。"""

        provider = _RecordingEmbeddingProvider()
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="internal-copy",
                    title="字段映射恢复手册",
                    source_uri="test://internal-copy",
                    content="字段映射缺失后刷新元数据并重新预检。",
                    sensitivity_level="internal",
                ),
                RagDocument(
                    document_id="restricted-copy",
                    title="字段映射恢复手册",
                    source_uri="test://restricted-copy",
                    content="字段映射缺失后刷新元数据并重新预检。",
                    sensitivity_level="restricted",
                ),
            )
        )
        retriever = RagHybridRetriever(
            knowledge_base,
            embedding_provider=provider,
            settings=RagHybridRetrieverSettings(minimum_vector_score=-1.0),
        )

        result = retriever.retrieve(
            RagQuery(
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                actor_id="owner-a",
                question="字段映射恢复",
                sensitivity_level="restricted",
                retrieval_mode="vector",
                top_k=2,
            )
        )

        self.assertEqual(2, len(result))
        self.assertEqual(("restricted",), tuple(provider.single_sensitivity_levels))
        self.assertEqual(1, len(provider.batch_calls))
        self.assertEqual(2, len(provider.batch_calls[0]))
        self.assertEqual(
            (("internal", "restricted"),),
            tuple(provider.batch_sensitivity_levels),
        )

    def test_vector_cache_reuses_same_content_across_governed_scope_copies(self) -> None:
        """范围标签由权限过滤承载时，同一语义正文不应向 Embedding Provider 重复计费。"""

        provider = _RecordingEmbeddingProvider()
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="global-copy",
                    title="字段映射恢复手册",
                    source_uri="test://global-copy",
                    content="全局产品基线，字段映射缺失后刷新元数据并重新预检。",
                    metadata={"artifactCode": "MAPPING-RECOVERY-001"},
                ),
                RagDocument(
                    document_id="project-copy",
                    title="字段映射恢复手册",
                    source_uri="test://project-copy",
                    tenant_id="10",
                    project_id="101",
                    workspace_key="tenant-10-project-101",
                    content="租户 10 项目 101 合成演示空间，字段映射缺失后刷新元数据并重新预检。",
                    metadata={"artifactCode": "MAPPING-RECOVERY-001"},
                ),
            )
        )
        retriever = RagHybridRetriever(
            knowledge_base,
            embedding_provider=provider,
            settings=RagHybridRetrieverSettings(minimum_vector_score=-1.0),
        )

        global_result = retriever.retrieve(
            RagQuery(
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                actor_id="owner-a",
                question="字段映射恢复",
                retrieval_mode="vector",
            )
        )
        project_result = retriever.retrieve(
            RagQuery(
                tenant_id="10",
                project_id="101",
                workspace_key="tenant-10-project-101",
                actor_id="owner-a",
                question="字段映射恢复",
                retrieval_mode="vector",
            )
        )

        self.assertEqual(("global-copy",), tuple(item.chunk.document_id for item in global_result))
        self.assertEqual(("project-copy",), tuple(item.chunk.document_id for item in project_result))
        self.assertEqual(1, len(provider.batch_calls))
        self.assertEqual(1, len(provider.batch_calls[0]))

    def test_large_in_memory_corpus_uses_parent_child_vector_routing(self) -> None:
        """大语料应向量化父级路由摘要，而不是在首次查询时全量向量化所有子 chunk。"""

        provider = _RecordingEmbeddingProvider()
        content = "\n\n".join(
            f"第 {index} 段字段映射恢复说明：" + ("甲" * 480)
            for index in range(12)
        )
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="large-runbook",
                    title="大型字段映射恢复手册",
                    source_uri="test://large-runbook",
                    content=content,
                    sensitivity_level="restricted",
                    metadata={"artifactCode": "LARGE-MAPPING-001"},
                ),
            )
        )
        retriever = RagHybridRetriever(
            knowledge_base,
            embedding_provider=provider,
            settings=RagHybridRetrieverSettings(
                minimum_vector_score=-1.0,
                hierarchical_vector_minimum_chunks=5,
                vector_routing_group_size=3,
                vector_routing_candidate_limit=2,
                vector_routing_chunks_per_group=1,
            ),
        )

        retriever.retrieve(
            RagQuery(
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                actor_id="owner-a",
                question="字段映射恢复",
                sensitivity_level="restricted",
                retrieval_mode="hybrid",
            )
        )

        self.assertEqual(1, len(provider.single_calls))
        self.assertEqual(1, len(provider.batch_calls))
        self.assertEqual(4, len(provider.batch_calls[0]))
        self.assertEqual(("restricted",), tuple(provider.single_sensitivity_levels))
        self.assertEqual(
            (("restricted", "restricted", "restricted", "restricted"),),
            tuple(provider.batch_sensitivity_levels),
        )
        diagnostics = retriever.diagnostics()["retriever"]
        self.assertEqual(0, diagnostics["embeddingCacheSize"])
        self.assertEqual(4, diagnostics["routingEmbeddingCacheSize"])

    def test_vector_routing_quota_prevents_one_large_document_from_filling_window(self) -> None:
        """同一长文档的多个路由组不能挤掉其他文档的候选位置。"""

        provider = _RecordingEmbeddingProvider()
        long_content = "\n\n".join(
            f"长文档第 {index} 段：" + ("甲" * 480)
            for index in range(8)
        )
        knowledge_base = InMemoryRagKnowledgeBase(
            (
                RagDocument(
                    document_id="large-document",
                    title="大型手册",
                    source_uri="test://large-document",
                    content=long_content,
                ),
                RagDocument(
                    document_id="small-document",
                    title="短手册",
                    source_uri="test://small-document",
                    content="短文档提供另一份独立证据。",
                ),
            )
        )
        retriever = RagHybridRetriever(
            knowledge_base,
            embedding_provider=provider,
            settings=RagHybridRetrieverSettings(
                minimum_vector_score=-1.0,
                hierarchical_vector_minimum_chunks=3,
                vector_routing_group_size=2,
                vector_routing_candidate_limit=2,
                vector_routing_groups_per_document=1,
                vector_routing_chunks_per_group=1,
            ),
        )

        candidates = retriever.retrieve(
            RagQuery(
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                actor_id="owner-a",
                question="不存在于正文的纯向量问题",
                retrieval_mode="vector",
                candidate_limit=8,
            )
        )

        self.assertEqual(
            {"large-document", "small-document"},
            {item.chunk.document_id for item in candidates},
        )

    def test_hybrid_candidate_window_reserves_vector_only_evidence(self) -> None:
        """大量词法命中不能在 Reranker 前挤掉向量通道的高排名候选。"""

        retriever = RagHybridRetriever(InMemoryRagKnowledgeBase(()))

        def chunk(document_id: str) -> RagChunk:
            return RagChunk(
                chunk_id=f"{document_id}#1",
                document_id=document_id,
                chunk_index=0,
                title=document_id,
                text=document_id,
                source_uri=f"test://{document_id}",
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                source_type=RagChunkSourceType.DOCUMENT,
            )

        lexical_ranked = tuple(
            RagScoredChunk(
                chunk=chunk(f"lexical-{index}"),
                lexical_score=1.0 - index / 100.0,
            )
            for index in range(40)
        )
        vector_ranked = (
            RagScoredChunk(
                chunk=chunk("vector-target"),
                vector_score=0.92,
            ),
        )
        fused = retriever._fuse(lexical_ranked, vector_ranked)

        window = retriever._bounded_candidate_window(
            fused,
            lexical_ranked=lexical_ranked,
            vector_ranked=vector_ranked,
            candidate_limit=32,
        )

        self.assertEqual(32, len(window))
        self.assertIn("vector-target", {item.chunk.document_id for item in window})

    def test_rrf_uses_document_rank_instead_of_long_document_chunk_rank(self) -> None:
        """RRF 不应让同一长文档的重复分块占用其他文档的排名位置。"""

        retriever = RagHybridRetriever(InMemoryRagKnowledgeBase(()))

        def chunk(document_id: str, index: int) -> RagChunk:
            return RagChunk(
                chunk_id=f"{document_id}#{index}",
                document_id=document_id,
                chunk_index=index,
                title=document_id,
                text=f"{document_id} 分块 {index}",
                source_uri=f"test://{document_id}",
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                source_type=RagChunkSourceType.DOCUMENT,
            )

        lexical_ranked = tuple(
            RagScoredChunk(
                chunk=chunk("long-document", index),
                lexical_score=1.0 - index / 100.0,
            )
            for index in range(40)
        ) + (
            RagScoredChunk(chunk=chunk("independent-document", 0), lexical_score=0.2),
        )
        fused = retriever._fuse(lexical_ranked, ())

        self.assertEqual(
            ("long-document", "independent-document"),
            tuple(item.chunk.document_id for item in fused),
        )
        self.assertEqual(2, len(fused))

    def test_candidate_window_limits_chunks_from_same_document_before_rerank(self) -> None:
        """超长文档不能用重复分块占满 Reranker 候选窗口。"""

        retriever = RagHybridRetriever(
            InMemoryRagKnowledgeBase(()),
            settings=RagHybridRetrieverSettings(
                max_candidate_chunks_per_document=1,
                hybrid_vector_candidate_ratio=0.5,
            ),
        )

        def chunk(document_id: str, chunk_index: int) -> RagChunk:
            return RagChunk(
                chunk_id=f"{document_id}#{chunk_index}",
                document_id=document_id,
                chunk_index=chunk_index,
                title=document_id,
                text=f"{document_id} 的第 {chunk_index} 个分块",
                source_uri=f"test://{document_id}",
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                source_type=RagChunkSourceType.DOCUMENT,
            )

        lexical_ranked = tuple(
            RagScoredChunk(
                chunk=chunk("oversized-manual", index),
                lexical_score=1.0 - index / 100.0,
            )
            for index in range(40)
        ) + (
            RagScoredChunk(
                chunk=chunk("independent-runbook", 0),
                lexical_score=0.5,
            ),
        )
        vector_ranked = (
            RagScoredChunk(chunk=chunk("vector-target", 0), vector_score=0.92),
            RagScoredChunk(chunk=chunk("vector-support", 0), vector_score=0.88),
        )
        fused = retriever._fuse(lexical_ranked, vector_ranked)

        window = retriever._bounded_candidate_window(
            fused,
            lexical_ranked=lexical_ranked,
            vector_ranked=vector_ranked,
            candidate_limit=4,
        )

        document_ids = tuple(item.chunk.document_id for item in window)
        self.assertEqual(4, len(window))
        self.assertEqual(1, document_ids.count("oversized-manual"))
        self.assertIn("independent-runbook", document_ids)
        self.assertIn("vector-target", document_ids)

    def test_rrf_keeps_a_secondary_chunk_for_an_uncovered_multi_evidence_facet(self) -> None:
        """长文档的第二个候选块应回答新 facet，而不是重复整句最高分块。"""

        retriever = RagHybridRetriever(
            InMemoryRagKnowledgeBase(()),
            settings=RagHybridRetrieverSettings(max_candidate_chunks_per_document=2),
        )

        def chunk(chunk_index: int, text: str) -> RagChunk:
            return RagChunk(
                chunk_id=f"recovery-events#{chunk_index}",
                document_id="recovery-events",
                chunk_index=chunk_index,
                title="恢复事件流水",
                text=text,
                source_uri="test://recovery-events",
                tenant_id="*",
                project_id="*",
                workspace_key="*",
                source_type=RagChunkSourceType.INCIDENT,
                metadata={"category": "recovery_events", "contentFormat": "jsonl"},
            )

        lexical_ranked = (
            RagScoredChunk(
                chunk=chunk(0, "接口标识追踪到 Recovery 修复。"),
                lexical_score=1.0,
                match_terms=("接口", "recovery", "修复"),
            ),
            RagScoredChunk(
                chunk=chunk(1, "接口标识与 Recovery 修复的重复说明。"),
                lexical_score=0.9,
                match_terms=("接口", "recovery", "修复"),
            ),
            RagScoredChunk(
                chunk=chunk(2, "FAILED_OBJECT_REPLAYED 分片 replay 后进入最终验证。"),
                lexical_score=0.8,
                match_terms=("replay", "最终", "验证"),
            ),
        )

        fused = retriever._fuse(
            lexical_ranked,
            (),
            query=RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="接口标识追踪到 Recovery 修复、分片 replay 和最终验证",
                generate_answer=False,
            ),
        )

        selected_chunk_ids = {item.chunk.chunk_id for item in fused}
        self.assertEqual(
            {"recovery-events#0", "recovery-events#2"},
            selected_chunk_ids,
        )
        self.assertNotIn("recovery-events#1", selected_chunk_ids)

    def test_structured_replay_event_code_is_retrievable_from_business_wording(self) -> None:
        """用户说“分片 replay”时，应能命中 JSONL 使用的稳定事件码。"""

        pipeline = self._category_pipeline(
            (
                RagDocument(
                    document_id="recovery-event-code",
                    title="恢复事件流水",
                    source_uri="test://recovery-event-code",
                    source_type=RagChunkSourceType.INCIDENT,
                    content="eventType=FAILED_OBJECT_REPLAYED；失败对象已重放。",
                    metadata={"category": "recovery_events", "contentFormat": "jsonl"},
                ),
            ),
            {"recovery-event-code": 0.90},
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="分片 replay 的事件记录是什么？",
                retrieval_mode="lexical",
                top_k=1,
                generate_answer=False,
            )
        )

        self.assertEqual(("recovery-event-code",), tuple(item.document_id for item in result.citations))

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

    def test_pipeline_snapshot_matches_query_aware_reranker_preparation(self) -> None:
        """管线评测快照必须与查询感知的 Provider 准备窗口完全一致。"""

        routes = ModelRouteRegistry(default_model_routes())
        reranker = _PreparedWindowReranker(limit=2)
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(
                InMemoryRagKnowledgeBase(
                    tuple(
                        RagDocument(
                            document_id=f"prepared-doc-{index}",
                            title=f"恢复证据 {index}",
                            source_uri=f"test://prepared/{index}",
                            content=f"失败分片 replay 后执行最终验证，证据编号 {index}。",
                        )
                        for index in range(4)
                    )
                )
            ),
            reranker=reranker,
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
        )
        query = RagQuery(
            tenant_id="*",
            project_id="*",
            actor_id="owner-a",
            question="失败分片 replay 后如何执行最终验证？",
            retrieval_mode="lexical",
            candidate_limit=4,
            generate_answer=False,
        )

        result = pipeline.answer(query)

        snapshot_ids = tuple(item.chunk.document_id for item in result.reranker_input_chunks)
        self.assertEqual(query.question, reranker.prepared_question)
        self.assertEqual(reranker.prepared_document_ids, reranker.seen_document_ids)
        self.assertEqual(reranker.prepared_document_ids, snapshot_ids)
        self.assertEqual(2, len(snapshot_ids))

    def test_adaptive_citation_pruning_drops_documents_far_below_best_rerank_score(self) -> None:
        """相关性分差已经很大时，不应为了凑满 topK 返回多余引用。"""

        pipeline = self._scored_pipeline(
            {"primary": 0.96, "related": 0.51, "noise": 0.22},
            minimum_relative_score=0.82,
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="字段映射缺失应怎样恢复？",
                retrieval_mode="lexical",
                candidate_limit=8,
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(("primary",), tuple(item.document_id for item in result.citations))
        self.assertEqual(1, result.retrieval_summary["selectedCount"])

    def test_adaptive_citation_pruning_keeps_complementary_high_score_documents(self) -> None:
        """多个文档分数接近时，应保留互补证据而不是强制退化成单文档回答。"""

        pipeline = self._scored_pipeline(
            {"primary": 0.96, "related": 0.91, "noise": 0.22},
            minimum_relative_score=0.82,
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="字段映射缺失应怎样恢复？",
                retrieval_mode="lexical",
                candidate_limit=8,
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("primary", "related"),
            tuple(item.document_id for item in result.citations),
        )

    def test_multi_evidence_coverage_keeps_lower_scored_complementary_document(self) -> None:
        """多证据问题应保留独立覆盖子问题的资料，不能只按整句最高分裁掉。"""

        routes = ModelRouteRegistry(default_model_routes())
        documents = (
            RagDocument(
                document_id="current-sync-case",
                title="CDC 检查点推进案例",
                source_uri="test://current-sync-case",
                content="目标端成功提交后推进 CDC 检查点，并记录一致性位点。",
            ),
            RagDocument(
                document_id="historical-offset-incident",
                title="历史位点间隙事故",
                source_uri="test://historical-offset-incident",
                content="历史位点间隙由检查点提前确认导致，恢复时从最后安全位点回放。",
            ),
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(
                {
                    "historical-offset-incident": 0.96,
                    "current-sync-case": 0.50,
                }
            ),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(
                minimum_relative_rerank_score=0.82,
                multi_evidence_relative_rerank_score=0.55,
            ),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="怎样推进 CDC 检查点并避免重现历史位点间隙？",
                retrieval_mode="lexical",
                top_k=2,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"current-sync-case", "historical-offset-incident"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_split_ignores_short_question_scaffold(self) -> None:
        """“请结合”中的单字“请”不能让后续三个真实证据面全部停止拆分。"""

        routes = ModelRouteRegistry(default_model_routes())
        documents = (
            RagDocument(
                document_id="operations-guide",
                title="数据同步运维流程",
                source_uri="test://operations-guide",
                content="运维流程要求先读取任务状态和执行日志，再按责任服务继续排查。",
            ),
            RagDocument(
                document_id="connector-capacity",
                title="连接器容量快照",
                source_uri="test://connector-capacity",
                content="连接器容量包含版本、并发上限、限流配额和当前负载。",
            ),
            RagDocument(
                document_id="incident-history",
                title="历史运维记录",
                source_uri="test://incident-history",
                content="历史记录保存事故根因、处理时间线、回滚和恢复验证。",
            ),
            RagDocument(
                document_id="generic-postmortem",
                title="外键事故复盘",
                source_uri="test://generic-postmortem",
                content="本次排查顺序包含读取日志、核对配置和执行恢复验证。",
            ),
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(
                {
                    "operations-guide": 0.96,
                    "connector-capacity": 0.50,
                    "incident-history": 0.45,
                    "generic-postmortem": 0.90,
                }
            ),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(
                minimum_relative_rerank_score=0.82,
                multi_evidence_relative_rerank_score=0.55,
            ),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="请结合运维流程、连接器容量和历史记录给出本次排查顺序。",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"operations-guide", "connector-capacity", "incident-history"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_coverage_keeps_two_character_and_trailing_facets(self) -> None:
        """批量、并发、超时等两字主题和列表末尾的运行结果都必须参与证据覆盖。"""

        routes = ModelRouteRegistry(default_model_routes())
        documents = (
            RagDocument(
                document_id="config-snapshot",
                title="任务配置版本快照",
                source_uri="test://config-snapshot",
                content="配置版本记录发布人与上一版本。",
            ),
            RagDocument(
                document_id="successful-parameters",
                title="成功任务参数基线",
                source_uri="test://successful-parameters",
                content="最近成功任务同时保存配置版本、批量、并发和超时参数。",
            ),
            RagDocument(
                document_id="successful-runs",
                title="历史成功运行结果",
                source_uri="test://successful-runs",
                content="最终运行结果保存成功状态、完成时间、读取行数和写入行数。",
            ),
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(
                {
                    "config-snapshot": 0.96,
                    "successful-parameters": 0.50,
                    "successful-runs": 0.45,
                }
            ),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(
                minimum_relative_rerank_score=0.82,
                multi_evidence_relative_rerank_score=0.55,
            ),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="请还原最近成功任务的配置版本、批量、并发、超时和最终运行结果。",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"successful-parameters", "successful-runs"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_facet_accepts_multiple_independent_two_character_terms(self) -> None:
        """同一 facet 命中多个独立两字业务词时，应视为强覆盖而不是泛词噪音。"""

        documents = (
            RagDocument(
                document_id="scope-filter",
                title="RAG 检索隔离",
                source_uri="test://scope-filter",
                content="检索隔离要求先按租户、项目和应用范围过滤，再执行排序。",
            ),
            RagDocument(
                document_id="authorization-facts",
                title="最小权限与授权事实",
                source_uri="test://authorization-facts",
                content="授权事实来自权限决策，必须满足最小权限和审计边界。",
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "scope-filter": 0.96,
                "authorization-facts": 0.72,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="检索隔离和授权事实需要共同满足哪些边界？",
                retrieval_mode="lexical",
                top_k=2,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"scope-filter", "authorization-facts"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_gate_accepts_one_strong_term_for_short_facet(self) -> None:
        """短 facet 在标题中强命中一个业务词时，不应套用整句的双 token 门槛。"""

        routes = ModelRouteRegistry(default_model_routes())
        documents = (
            RagDocument(
                document_id="authentication-api",
                title="认证接口说明",
                source_uri="test://authentication-api",
                content="认证接口返回会话标识。",
            ),
            RagDocument(
                document_id="repair-audit",
                title="修复审计事件",
                source_uri="test://repair-audit",
                content="修复审计记录调用主体、资源动作和越权判定。",
            ),
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(
                {
                    "repair-audit": 0.96,
                    "authentication-api": 0.50,
                }
            ),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(
                minimum_relative_rerank_score=0.82,
                multi_evidence_relative_rerank_score=0.55,
            ),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="认证和修复审计如何证明一次调用没有越权？",
                retrieval_mode="lexical",
                top_k=2,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"authentication-api", "repair-audit"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_coverage_prefers_one_document_covering_more_facets(self) -> None:
        """一份资料能可靠覆盖多个 facet 时，应避免再为每个 facet 各补一份重复证据。"""

        routes = ModelRouteRegistry(default_model_routes())
        documents = (
            RagDocument(
                document_id="capacity-only",
                title="连接器容量说明",
                source_uri="test://capacity-only",
                content="连接器容量包含并发上限和限流配额。",
            ),
            RagDocument(
                document_id="incident-only",
                title="历史事故说明",
                source_uri="test://incident-only",
                content="历史事故包含根因、时间线和恢复验证。",
            ),
            RagDocument(
                document_id="combined-runbook",
                title="连接器容量与历史事故联合排查",
                source_uri="test://combined-runbook",
                content="联合 Runbook 同时核对连接器容量和历史事故，再给出排查顺序。",
            ),
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(
                {
                    "capacity-only": 0.96,
                    "incident-only": 0.94,
                    "combined-runbook": 0.60,
                }
            ),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(
                minimum_relative_rerank_score=0.82,
                multi_evidence_relative_rerank_score=0.55,
                multi_evidence_facet_relative_score=0.80,
            ),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="请结合连接器容量和历史事故给出排查顺序。",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("combined-runbook",),
            tuple(item.document_id for item in result.citations),
        )

    def test_multi_evidence_companion_stage_uses_new_categories_after_facets_are_covered(self) -> None:
        """全部 facet 已由综合资料覆盖后，topK 仍应补入不同职责类别的高意图资料。"""

        routes = ModelRouteRegistry(default_model_routes())
        documents = (
            RagDocument(
                document_id="combined-api-recovery",
                title="接口合同与 Recovery 事件综合参考",
                source_uri="test://combined-api-recovery",
                content=(
                    "接口合同说明任务 API 与 WebSocket 事件；Recovery 事件记录恢复过程；"
                    "状态快照用于关联 Agent 节点、事件和最终验证。"
                ),
                metadata={
                    "category": "api_contract_snapshot",
                    "contentFormat": "docx",
                },
            ),
            RagDocument(
                document_id="api-contract-detail",
                title="接口合同参数详表",
                source_uri="test://api-contract-detail",
                content="接口合同补充任务 API 的请求参数、响应字段和 WebSocket 事件格式。",
                metadata={
                    "category": "api_contract_snapshot",
                    "contentFormat": "docx",
                },
            ),
            RagDocument(
                document_id="recovery-events",
                title="Recovery 事件字典",
                source_uri="test://recovery-events",
                source_type=RagChunkSourceType.INCIDENT,
                content="Recovery 事件记录动作、原因、最终验证和回滚结果。",
                metadata={
                    "category": "recovery_events",
                    "contentFormat": "jsonl",
                },
            ),
            RagDocument(
                document_id="agent-state-snapshot",
                title="Agent 状态快照",
                source_uri="test://agent-state-snapshot",
                source_type=RagChunkSourceType.MEMORY_EXPORT,
                content="状态快照保存 Agent 节点、Kafka 事件、当前状态和恢复位点。",
                metadata={
                    "category": "agent_state_snapshot",
                    "contentFormat": "json",
                },
            ),
        )
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(
                {
                    "combined-api-recovery": 0.96,
                    "api-contract-detail": 0.90,
                    "recovery-events": 0.72,
                    "agent-state-snapshot": 0.70,
                }
            ),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(
                minimum_relative_rerank_score=0.55,
                multi_evidence_relative_rerank_score=0.55,
            ),
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="请结合接口合同、Recovery 事件和状态快照说明如何关联。",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {
                "combined-api-recovery",
                "recovery-events",
                "agent-state-snapshot",
            },
            {item.document_id for item in result.citations},
        )
        self.assertNotIn("api-contract-detail", {item.document_id for item in result.citations})

    def test_multi_evidence_mapping_recovery_keeps_manual_case_and_worker_log(self) -> None:
        """字段映射恢复问题应同时保留恢复手册、字段案例和 Worker 日志。"""

        documents = (
            RagDocument(
                document_id="mapping-recovery-manual",
                title="字段映射恢复手册",
                source_uri="test://mapping-recovery-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="字段映射失败时先刷新元数据，核对默认值和非空约束，再执行预检。",
                metadata={"category": "recovery_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="field-mapping-case",
                title="字段映射历史任务案例",
                source_uri="test://field-mapping-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="字段案例记录 source.customer_id 到 target.customer_id 的映射、默认值和非空校验结果。",
                metadata={"category": "field_mapping_case", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="worker-mapping-log",
                title="Worker 执行日志字段错误样本",
                source_uri="test://worker-mapping-log",
                source_type=RagChunkSourceType.INCIDENT,
                content="Worker 执行日志显示 errorCode=FIELD_MAPPING_MISSING，字段映射缺失后任务停止。",
                metadata={"category": "worker_execution", "contentFormat": "log"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "mapping-recovery-manual": 0.96,
                "field-mapping-case": 0.74,
                "worker-mapping-log": 0.72,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="请结合字段映射、恢复手册、字段案例和 Worker 执行日志给出排查顺序。",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"mapping-recovery-manual", "field-mapping-case", "worker-mapping-log"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_kafka_case_is_before_generic_success_parameters(self) -> None:
        """Kafka 任务案例问题应优先引用 Kafka 职责资料，而不是泛化成功参数表。"""

        documents = (
            RagDocument(
                document_id="kafka-task-case",
                title="Kafka 同步积压任务案例",
                source_uri="test://kafka-task-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="Kafka 任务案例记录消费者 lag、DLT、batch 和 channel 调整后的验证结果。",
                metadata={"category": "kafka_task_cases", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="generic-success-parameters",
                title="成功任务参数基线",
                source_uri="test://generic-success-parameters",
                source_type=RagChunkSourceType.TASK_CASE,
                content="成功任务参数记录 batch、channel、timeout 和最终运行结果。",
                metadata={"category": "successful_task_case", "contentFormat": "xlsx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "kafka-task-case": 0.95,
                "generic-success-parameters": 0.72,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="Kafka 同步积压任务案例和成功任务参数如何选择？",
                retrieval_mode="lexical",
                top_k=2,
                generate_answer=False,
            )
        )

        document_ids = tuple(item.document_id for item in result.citations)
        self.assertIn("kafka-task-case", document_ids)
        self.assertIn("generic-success-parameters", document_ids)
        self.assertLess(document_ids.index("kafka-task-case"), document_ids.index("generic-success-parameters"))

    def test_multi_evidence_rate_limit_keeps_incident_api_case_and_inventory(self) -> None:
        """限流排查应把事故事实、API 案例和连接器能力清单组合起来。"""

        documents = (
            RagDocument(
                document_id="rate-limit-incident",
                title="目标端限流历史事故",
                source_uri="test://rate-limit-incident",
                source_type=RagChunkSourceType.INCIDENT,
                content="历史事故记录目标端 429 限流的根因、处理时间线、回滚和恢复验证。",
                metadata={"category": "incident_rate_limit", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="rate-limit-api-case",
                title="API 任务案例限流参数",
                source_uri="test://rate-limit-api-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="API 任务案例说明收到 429 后如何降低 batch、channel 并执行有界重试。",
                metadata={"category": "api_task_cases", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="connector-inventory",
                title="连接器版本与容量清单",
                source_uri="test://connector-inventory",
                source_type=RagChunkSourceType.METADATA,
                content="连接器清单包含版本、限流阈值、并发上限和容量检查结果。",
                metadata={"category": "connector_inventory", "contentFormat": "csv"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "rate-limit-incident": 0.96,
                "rate-limit-api-case": 0.74,
                "connector-inventory": 0.72,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="目标限流时请结合历史事故、API 任务案例和连接器清单给出处理顺序。",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"rate-limit-incident", "rate-limit-api-case", "connector-inventory"},
            {item.document_id for item in result.citations},
        )

    def test_online_model_role_prior_distinguishes_alert_inventory_and_observability_documents(self) -> None:
        """真实模型重排后，Manifest 职责仍应纠正三个容易混淆的资料类别。"""

        documents = (
            RagDocument(
                document_id="alert-history",
                title="告警历史与响应记录",
                source_uri="test://alert-history",
                source_type=RagChunkSourceType.INCIDENT,
                content="告警历史记录哪些告警会触发 Recovery，哪些只通知运维人员。",
                metadata={"category": "alert_history", "contentFormat": "csv"},
            ),
            RagDocument(
                document_id="connector-inventory",
                title="连接器版本与容量清单",
                source_uri="test://connector-inventory",
                source_type=RagChunkSourceType.METADATA,
                content="连接器清单列出版本，并说明 CDC 和 checkpoint replay 支持情况。",
                metadata={"category": "connector_inventory", "contentFormat": "csv"},
            ),
            RagDocument(
                document_id="connector-capabilities",
                title="连接器能力与容量快照",
                source_uri="test://connector-capabilities",
                source_type=RagChunkSourceType.METADATA,
                content="连接器能力快照记录当前最大批量、并发和超时。",
                metadata={"category": "connector_capabilities", "contentFormat": "json"},
            ),
            RagDocument(
                document_id="observability-manual",
                title="日志、指标、追踪与告警运维手册",
                source_uri="test://observability-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="使用日志、指标和 trace 定位跨 Agent 与 Worker 的同步失败。",
                metadata={"category": "observability_operations_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="error-catalog",
                title="同步与 Agent 错误码目录",
                source_uri="test://error-catalog",
                source_type=RagChunkSourceType.RUNBOOK,
                content="错误码目录记录组件、重试资格和人工接管条件。",
                metadata={"category": "error_code_catalog", "contentFormat": "txt"},
            ),
        )

        # 先直接检查职责分，保证回归关注的是通用 category 合同，而不是某一个测试文档 ID。
        chunks = {
            document.document_id: chunk_document(document)[0]
            for document in documents
        }
        alert_query = "哪些告警会触发 Recovery，哪些只通知运维人员？"
        connector_query = "连接器清单中哪些版本支持 CDC 和 checkpoint replay？"
        observability_query = "如何使用日志、指标和 trace 定位一次跨 Agent 与 Worker 的同步失败？"
        self.assertGreater(
            rag_query_document_intent_score(alert_query, chunks["alert-history"]),
            rag_query_document_intent_score(alert_query, chunks["error-catalog"]),
        )
        self.assertGreater(
            rag_query_document_intent_score(connector_query, chunks["connector-inventory"]),
            rag_query_document_intent_score(connector_query, chunks["connector-capabilities"]),
        )
        # 多证据拆分会把“API 目标限流”和“连接器容量降低压力”分成两个 facet。后一个 facet
        # 仍应使用整句上下文在 inventory/capabilities 之间消歧，否则真实 16 条 Provider 窗口会
        # 把带版本和限流阈值的清单替换成泛化能力快照。
        connector_capacity_facet = "连接器容量降低压力"
        connector_capacity_context = "API 目标限流时怎样依据任务参数和连接器容量降低压力？"
        self.assertGreater(
            rag_query_document_intent_score(
                connector_capacity_facet,
                chunks["connector-inventory"],
                context_text=connector_capacity_context,
            ),
            rag_query_document_intent_score(
                connector_capacity_facet,
                chunks["connector-capabilities"],
                context_text=connector_capacity_context,
            ),
        )
        self.assertGreater(
            rag_query_document_intent_score(observability_query, chunks["observability-manual"]),
            rag_query_document_intent_score(observability_query, chunks["error-catalog"]),
        )

        # 模拟真实 Cross-Encoder 把正文更长的错误码目录打得更高。调用方已限定 runbook 后，最终
        # 引用仍应收敛到职责明确的可观测性手册，而不是为了填满 topK 追加同来源的泛化资料。
        pipeline = self._category_pipeline(
            documents,
            {
                "alert-history": 0.50,
                "connector-inventory": 0.50,
                "connector-capabilities": 0.50,
                "observability-manual": 0.70,
                "error-catalog": 0.95,
            },
        )
        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question=observability_query,
                retrieval_mode="lexical",
                source_types=("runbook",),
                top_k=5,
                generate_answer=False,
            )
        )
        self.assertEqual(
            ("observability-manual",),
            tuple(item.document_id for item in result.citations),
        )

    def test_platform_lifecycle_query_prefers_deployment_or_disaster_manual_over_component_manual(self) -> None:
        """平台部署与灾备顺序不能被只负责单个组件故障的手册抢答。"""

        documents = (
            RagDocument(
                document_id="deployment-manual",
                title="DataSmart Govern 部署手册",
                source_uri="test://deployment-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="部署时依次验证 Java、Kafka、pgvector 和 AI Runtime。",
                metadata={"category": "deployment_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="disaster-manual",
                title="备份、恢复与灾难演练手册",
                source_uri="test://disaster-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="灾难恢复依次恢复数据库、对象、Kafka 位点和服务。",
                metadata={"category": "backup_disaster_recovery_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="kafka-manual",
                title="Kafka 运维手册",
                source_uri="test://kafka-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="Kafka 手册负责消费者积压、重复消费和 DLT 恢复。",
                metadata={"category": "kafka_operations_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="pgvector-manual",
                title="pgvector 运维手册",
                source_uri="test://pgvector-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="pgvector 手册负责向量维度、索引和慢查询诊断。",
                metadata={"category": "postgresql_pgvector_manual", "contentFormat": "docx"},
            ),
        )
        chunks = {
            document.document_id: chunk_document(document)[0]
            for document in documents
        }
        deployment_query = "部署 DataSmart Govern 时如何依次验证 Java、Kafka、pgvector 和 AI Runtime？"
        disaster_query = "平台灾难恢复时应按什么顺序恢复数据库、对象、Kafka 位点和服务？"

        self.assertGreater(
            rag_query_document_intent_score(deployment_query, chunks["deployment-manual"]),
            rag_query_document_intent_score(deployment_query, chunks["kafka-manual"]),
        )
        self.assertGreater(
            rag_query_document_intent_score(deployment_query, chunks["deployment-manual"]),
            rag_query_document_intent_score(deployment_query, chunks["pgvector-manual"]),
        )
        self.assertGreater(
            rag_query_document_intent_score(disaster_query, chunks["disaster-manual"]),
            rag_query_document_intent_score(disaster_query, chunks["kafka-manual"]),
        )

    def test_natural_multiformat_queries_activate_semantic_manual_responsibilities(self) -> None:
        """自然中文问法也要激活正确资料职责，避免 Embedding 命中后被证据门禁再次丢弃。

        真实跨格式评测中的问题通常不会复述 Manifest 的 category 或资料标题，例如“新环境上线前
        健康检查怎么排”“外部服务答复不全怎么办”“消费组和分区堆积最严重在哪里”。如果职责先验
        只识别“部署、Provider、Kafka”这些显式词，目标文档虽然已经被 Embedding 召回，仍会因为
        ``minimumUnanchoredVectorScore`` 或相对重排裁剪被通用手册抢走。本回归只验证受控意图分：
        它不能直接生成答案，也不能扩大候选范围，但必须足够高以支持后续 evidence gate 的职责保护。
        """

        documents = (
            RagDocument(
                document_id="deployment-manual",
                title="DataSmart Govern 部署手册",
                source_uri="test://deployment-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="部署时依次完成基础服务健康检查，并验证 Java、Kafka、pgvector 和 AI Runtime。",
                metadata={"category": "deployment_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="operations-manual",
                title="DataSmart Govern 运维手册",
                source_uri="test://operations-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="运维手册记录常规日志、指标与服务排查流程。",
                metadata={"category": "operations_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="provider-manual",
                title="模型 Provider 运维手册",
                source_uri="test://provider-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="外部智能服务不稳定、被限流或答复不全时，执行重试、降级和响应完整性校验。",
                metadata={"category": "model_provider_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="pgvector-manual",
                title="PostgreSQL pgvector 运维手册",
                source_uri="test://pgvector-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="资料匹配变慢或结果对不上时，检查存放设置、向量维度和索引。",
                metadata={"category": "postgresql_pgvector_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="kafka-log",
                title="Kafka 消费积压诊断日志",
                source_uri="test://kafka-log",
                source_type=RagChunkSourceType.INCIDENT,
                content="记录消费组、分区和消息堆积情况。",
                metadata={"category": "kafka_lag_log", "contentFormat": "log"},
            ),
            RagDocument(
                document_id="kafka-postmortem",
                title="Kafka 积压事故复盘",
                source_uri="test://kafka-postmortem",
                source_type=RagChunkSourceType.INCIDENT,
                content="复盘 Kafka 积压事故的根因和 DLT 处置过程。",
                metadata={"category": "incident_kafka_backlog", "contentFormat": "docx"},
            ),
        )
        chunks = {
            document.document_id: chunk_document(document)[0]
            for document in documents
        }

        deployment_question = "新环境上线前，基础服务的健康检查先后怎样安排？"
        provider_question = "外部智能服务不稳定、请求被限制或答复不全时，怎样稳妥继续？"
        pgvector_question = "资料匹配变慢或返回结果对不上时，该从哪些存放设置查起？"
        kafka_question = "消息处理变慢时，哪个消费组和分区堆积最严重？"

        deployment_score = rag_query_document_intent_score(
            deployment_question,
            chunks["deployment-manual"],
        )
        provider_score = rag_query_document_intent_score(
            provider_question,
            chunks["provider-manual"],
        )
        pgvector_score = rag_query_document_intent_score(
            pgvector_question,
            chunks["pgvector-manual"],
        )
        kafka_score = rag_query_document_intent_score(
            kafka_question,
            chunks["kafka-log"],
        )

        self.assertGreaterEqual(deployment_score, 0.85)
        self.assertGreaterEqual(provider_score, 0.85)
        self.assertGreaterEqual(pgvector_score, 0.85)
        self.assertGreaterEqual(kafka_score, 0.85)
        self.assertGreater(
            deployment_score,
            rag_query_document_intent_score(deployment_question, chunks["operations-manual"]),
        )
        self.assertGreater(
            provider_score,
            rag_query_document_intent_score(provider_question, chunks["operations-manual"]),
        )

    def test_multi_evidence_checkpoint_keeps_incident_replay_and_recovery_decision(self) -> None:
        """Checkpoint 恢复问题应同时引用事故、失败分片 replay 和 Recovery 决策。"""

        documents = (
            RagDocument(
                document_id="checkpoint-incident",
                title="Checkpoint 位点事故复盘",
                source_uri="test://checkpoint-incident",
                source_type=RagChunkSourceType.INCIDENT,
                content="Checkpoint 事故由位点提前确认引起，复盘记录根因、影响范围和最后安全位点。",
                metadata={"category": "incident_checkpoint", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="replay-case",
                title="失败分片 replay 任务案例",
                source_uri="test://replay-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="失败分片 replay 案例从最后安全位点恢复对象，并验证重复写入保护。",
                metadata={"category": "recovery_replay_cases", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="recovery-decision",
                title="Recovery 决策追踪",
                source_uri="test://recovery-decision",
                source_type=RagChunkSourceType.INCIDENT,
                content="Recovery 决策记录 actionCode、decisionReason、授权边界和最终验证。",
                metadata={"category": "recovery_decision_trace", "contentFormat": "jsonl"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "checkpoint-incident": 0.96,
                "replay-case": 0.74,
                "recovery-decision": 0.72,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="Checkpoint 事故、失败分片 replay 和 Recovery 决策如何恢复？",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"checkpoint-incident", "replay-case", "recovery-decision"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_uses_category_roles_instead_of_generic_full_sentence_overlap(self) -> None:
        """通用资料即使复述整句，也不能冒充接口、全量案例和恢复台账三种职责。"""

        documents = (
            RagDocument(
                document_id="data-sync-api",
                title="数据同步执行接口",
                source_uri="test://data-sync-api",
                source_type=RagChunkSourceType.DOCUMENT,
                content="全量任务通过执行接口返回 executionId，并可按该标识查询失败对象。",
                metadata={"category": "api_data_sync_reference", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="full-load-case",
                title="全量同步任务案例",
                source_uri="test://full-load-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="全量任务案例保存 executionId、失败对象、对象级结果和重跑参数。",
                metadata={"category": "full_load_task_cases", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="recovery-ledger",
                title="数据库恢复台账",
                source_uri="test://recovery-ledger",
                source_type=RagChunkSourceType.DATASET,
                content="恢复台账关联失败对象、recoveryCaseId、修复动作和最终验证结果。",
                metadata={"category": "database_recovery_ledger", "contentFormat": "sql"},
            ),
            RagDocument(
                document_id="generic-api-case",
                title="API 任务通用案例",
                source_uri="test://generic-api-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="全量任务从执行接口关联到失败对象、恢复台账和最终验证。",
                metadata={"category": "api_task_cases", "contentFormat": "xlsx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "generic-api-case": 0.99,
                "recovery-ledger": 0.82,
                "data-sync-api": 0.80,
                "full-load-case": 0.78,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="全量任务怎样从执行接口关联到失败对象、恢复台账和最终验证？",
                retrieval_mode="lexical",
                source_types=("dataset", "document", "task_case"),
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"data-sync-api", "full-load-case", "recovery-ledger"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_requires_specialized_roles_for_checkpoint_replay_facets(self) -> None:
        """决策轨迹提到全部术语时，仍应由事故、replay 案例和决策资料分别举证。"""

        documents = (
            RagDocument(
                document_id="checkpoint-postmortem",
                title="Checkpoint 漂移事故复盘",
                source_uri="test://checkpoint-postmortem",
                source_type=RagChunkSourceType.INCIDENT,
                content="Checkpoint 漂移由提前确认位点引起，复盘给出最后安全位点和根因。",
                metadata={"category": "incident_checkpoint", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="recovery-replay-case",
                title="失败对象 replay 任务案例",
                source_uri="test://recovery-replay-case",
                source_type=RagChunkSourceType.INCIDENT,
                content="失败对象 replay 案例从最后安全位点恢复，并核验幂等与重复写入保护。",
                metadata={"category": "recovery_replay_cases", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="recovery-decision-trace",
                title="Recovery 修复决策轨迹",
                source_uri="test://recovery-decision-trace",
                source_type=RagChunkSourceType.INCIDENT,
                content="决策轨迹同时记录 Checkpoint 漂移、安全位点、修复决策和失败对象 replay 一致性。",
                metadata={"category": "recovery_decision_trace", "contentFormat": "jsonl"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "recovery-decision-trace": 0.99,
                "checkpoint-postmortem": 0.78,
                "recovery-replay-case": 0.76,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="Checkpoint 漂移后怎样证明安全位点、修复决策和失败对象 replay 一致？",
                retrieval_mode="lexical",
                source_types=("incident",),
                top_k=6,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"checkpoint-postmortem", "recovery-replay-case", "recovery-decision-trace"},
            {item.document_id for item in result.citations},
        )

    def test_multi_evidence_replay_uses_lifecycle_context_to_select_recovery_events(self) -> None:
        """接口追踪到最终验证的 replay facet 应引用事件流水，而不是配置案例。"""

        documents = (
            RagDocument(
                document_id="api-websocket-reference",
                title="任务 API 与 WebSocket 接口合同",
                source_uri="test://api-websocket-reference",
                source_type=RagChunkSourceType.DOCUMENT,
                content="接口合同使用 executionId 和 traceId 追踪任务执行、Agent 状态和 Recovery。",
                metadata={"category": "api_contract_snapshot", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="agent-state-snapshot",
                title="Agent 全链路状态快照",
                source_uri="test://agent-state-snapshot",
                source_type=RagChunkSourceType.MEMORY_EXPORT,
                content="状态快照按 traceId 关联 Agent 节点、Recovery 修复和最终验证状态。",
                metadata={"category": "agent_state_snapshot", "contentFormat": "json"},
            ),
            RagDocument(
                document_id="recovery-events",
                title="Recovery 事件流水",
                source_uri="test://recovery-events",
                source_type=RagChunkSourceType.INCIDENT,
                content="事件流水记录分片 replay、修复动作、traceId、事件时间和最终验证结果。",
                metadata={"category": "recovery_events", "contentFormat": "jsonl"},
            ),
            RagDocument(
                document_id="replay-case",
                title="分片 replay 配置案例",
                source_uri="test://replay-case",
                source_type=RagChunkSourceType.INCIDENT,
                content="分片 replay 案例说明安全位点、批量参数和重放配置。",
                metadata={"category": "recovery_replay_cases", "contentFormat": "xlsx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "replay-case": 0.99,
                "recovery-events": 0.82,
                "agent-state-snapshot": 0.80,
                "api-websocket-reference": 0.78,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="怎样从接口标识追踪到 Recovery 修复、分片 replay 和最终验证？",
                retrieval_mode="lexical",
                source_types=("document", "incident", "memory_export"),
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"api-websocket-reference", "agent-state-snapshot", "recovery-events"},
            {item.document_id for item in result.citations},
        )
        self.assertNotIn("replay-case", {item.document_id for item in result.citations})

    def test_multi_evidence_does_not_add_config_snapshot_after_success_case_is_selected(self) -> None:
        """最近成功任务问题已经有参数案例时，不应再补入泛化配置版本快照。"""

        documents = (
            RagDocument(
                document_id="successful-parameters",
                title="最近成功任务参数案例",
                source_uri="test://successful-parameters",
                source_type=RagChunkSourceType.TASK_CASE,
                content="最近成功任务保存配置版本、batch、channel、timeout 和最终运行结果。",
                metadata={"category": "successful_task_case", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="config-snapshot",
                title="任务配置版本与差异快照",
                source_uri="test://config-snapshot",
                source_type=RagChunkSourceType.TASK_CASE,
                content="配置版本只记录 previousVersion、configVersion 和发布差异。",
                metadata={"category": "task_config_versions", "contentFormat": "json"},
            ),
            RagDocument(
                document_id="successful-runs",
                title="历史成功运行记录",
                source_uri="test://successful-runs",
                source_type=RagChunkSourceType.TASK_CASE,
                content="最终运行结果记录 SUCCEEDED、completed_at 和写入行数。",
                metadata={"category": "successful_runs", "contentFormat": "csv"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "successful-parameters": 0.90,
                "config-snapshot": 0.88,
                "successful-runs": 0.60,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="请还原最近成功任务的配置版本、批量、并发、超时和最终运行结果。",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"successful-parameters", "successful-runs"},
            {item.document_id for item in result.citations},
        )

    def test_successful_run_statistics_do_not_activate_data_quality_case_role(self) -> None:
        """成功运行中的脏数据数量是运行统计，不应被数据质量案例库抢答。"""

        documents = (
            RagDocument(
                document_id="successful-runs",
                title="历史成功运行记录",
                source_uri="test://successful-runs",
                source_type=RagChunkSourceType.TASK_CASE,
                content="成功运行记录包含配置版本、完成时间、写入行数和脏数据数量。",
                metadata={"category": "successful_runs", "contentFormat": "csv"},
            ),
            RagDocument(
                document_id="quality-cases",
                title="数据质量与脏数据处置案例库",
                source_uri="test://quality-cases",
                source_type=RagChunkSourceType.TASK_CASE,
                content="脏数据案例包含数量、质量规则、隔离、修复和继续或停止决策。",
                metadata={"category": "data_quality_cases", "contentFormat": "xlsx"},
            ),
        )
        chunks = {document.document_id: chunk_document(document)[0] for document in documents}
        question = "最近一次成功运行的配置版本和脏数据数量是多少？"
        self.assertGreater(
            rag_query_document_intent_score(question, chunks["successful-runs"]),
            rag_query_document_intent_score(question, chunks["quality-cases"]),
        )

        # 模拟 Cross-Encoder 更偏爱正文较长的质量案例；响应边界仍应按资料职责收敛到运行记录。
        pipeline = self._category_pipeline(
            documents,
            {"quality-cases": 0.96, "successful-runs": 0.10},
        )
        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question=question,
                retrieval_mode="lexical",
                source_types=("task_case",),
                top_k=5,
                generate_answer=False,
            )
        )

        self.assertEqual(
            ("successful-runs",),
            tuple(item.document_id for item in result.citations),
        )

        quality_question = "成功运行后应按什么质量规则隔离脏数据并决定继续或停止？"
        self.assertGreater(
            rag_query_document_intent_score(quality_question, chunks["quality-cases"]),
            0.0,
        )

    def test_multi_evidence_mode_context_prefers_kafka_case_over_generic_task_case(self) -> None:
        """Kafka 积压问题的“任务参数”应绑定 Kafka 案例，不应漂移到成功任务基线。"""

        documents = (
            RagDocument(
                document_id="kafka-case",
                title="Kafka 流式同步任务案例",
                source_uri="test://kafka-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="Kafka 任务参数包含 batch、channel、消费者 lag 和 DLT 处置。",
                metadata={"category": "kafka_task_cases", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="generic-success",
                title="成功同步任务参数案例",
                source_uri="test://generic-success",
                source_type=RagChunkSourceType.TASK_CASE,
                content="成功任务参数包含 batch、channel、timeout 和最终结果。",
                metadata={"category": "successful_task_case", "contentFormat": "xlsx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {"kafka-case": 0.92, "generic-success": 0.90},
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="Kafka 同步积压时如何结合任务参数和 DLT 规则处置？",
                retrieval_mode="lexical",
                top_k=2,
                generate_answer=False,
            )
        )

        self.assertIn("kafka-case", {item.document_id for item in result.citations})
        self.assertNotIn("generic-success", {item.document_id for item in result.citations})

    def test_multi_evidence_kafka_roles_do_not_use_postmortem_as_log_and_dlt_manual(self) -> None:
        """Kafka 排障应分别保留任务案例、消费者日志和 DLT 手册，而不是让事故复盘包办三种职责。"""

        documents = (
            RagDocument(
                document_id="kafka-case",
                title="Kafka 流式同步任务案例",
                source_uri="test://kafka-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="Kafka 任务参数包含 batch、channel 和消费者组配置。",
                metadata={"category": "kafka_task_cases", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="kafka-lag-log",
                title="Kafka 消费积压诊断日志",
                source_uri="test://kafka-lag-log",
                source_type=RagChunkSourceType.INCIDENT,
                content="消费者日志记录 groupLag、consumer lag 和积压分区。",
                metadata={"category": "kafka_lag_log", "contentFormat": "log"},
            ),
            RagDocument(
                document_id="kafka-manual",
                title="Kafka 与 DLT 运维手册",
                source_uri="test://kafka-manual",
                source_type=RagChunkSourceType.RUNBOOK,
                content="DLT 规则说明失败消息的受治理处置、回放和验证步骤。",
                metadata={"category": "kafka_operations_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="kafka-postmortem",
                title="Kafka 积压事故复盘",
                source_uri="test://kafka-postmortem",
                source_type=RagChunkSourceType.INCIDENT,
                content="事故复盘同时提到 Kafka 积压、消费者日志、DLT 和任务参数。",
                metadata={"category": "incident_kafka_backlog", "contentFormat": "docx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "kafka-postmortem": 0.96,
                "kafka-case": 0.92,
                "kafka-manual": 0.90,
                "kafka-lag-log": 0.88,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="Kafka 同步积压时如何结合任务参数、消费者日志和 DLT 规则处置？",
                source_types=("incident", "runbook", "task_case"),
                retrieval_mode="lexical",
                top_k=6,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"kafka-case", "kafka-lag-log", "kafka-manual"},
            {item.document_id for item in result.citations},
        )

    def test_natural_kafka_operations_facets_keep_runbook_after_remote_window(self) -> None:
        """自然中文只描述“消息堵塞/消费端现象/失败处置”时，仍应保留 Kafka Runbook。

        真实 SiliconFlow 评测暴露了一个只有线上模型窗口才会出现的边界：Kafka 运维手册正文使用
        DLT、积压和回放等规范词，而用户 facet 只说“失败处置”。如果最终覆盖阶段只比较局部字面
        词，通用数据质量案例会凭“失败/处置”抢走该 facet。管线现在允许明确的
        ``kafka_operations_manual`` category 借用最小 Kafka 上下文，但仍要求候选已经有整句词法
        或向量信号；本测试验证的是职责补足，不是把 category 当作无条件答案。
        """

        documents = (
            RagDocument(
                document_id="natural-kafka-case",
                title="Kafka 流式同步任务案例",
                source_uri="test://natural-kafka-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="Kafka 任务配置包含分区、offset、乱序和失败重试参数。",
                metadata={"category": "kafka_task_cases", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="natural-kafka-log",
                title="Kafka 消费端诊断日志",
                source_uri="test://natural-kafka-log",
                source_type=RagChunkSourceType.INCIDENT,
                content="消费者日志记录 groupLag、积压分区和当前消费延迟。",
                metadata={"category": "kafka_lag_log", "contentFormat": "log"},
            ),
            RagDocument(
                document_id="natural-kafka-runbook",
                title="Kafka DLT 运维处置手册",
                source_uri="test://natural-kafka-runbook",
                source_type=RagChunkSourceType.RUNBOOK,
                content="DLT 手册说明失败消息隔离、受治理回放和恢复验证步骤。",
                metadata={"category": "kafka_operations_manual", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="natural-quality-case",
                title="数据质量失败处置案例",
                source_uri="test://natural-quality-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="数据质量案例记录失败数据隔离、处置动作和复核结果。",
                metadata={"category": "data_quality_cases", "contentFormat": "xlsx"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "natural-quality-case": 0.98,
                "natural-kafka-case": 0.90,
                "natural-kafka-log": 0.88,
                "natural-kafka-runbook": 0.86,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="消息处理堵塞时，怎样同时考虑配置、消费端现象和失败处置？",
                source_types=("incident", "runbook", "task_case"),
                retrieval_mode="lexical",
                top_k=6,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"natural-kafka-case", "natural-kafka-log", "natural-kafka-runbook"},
            {item.document_id for item in result.citations},
        )
        self.assertNotIn("natural-quality-case", {item.document_id for item in result.citations})

    def test普通字段标识符不会冻结多证据选择(self) -> None:
        """普通字段名命中 exact 通道时，仍必须保留手册、案例和执行日志的互补证据。"""

        documents = (
            RagDocument(
                document_id="field-profile-statistics",
                title="字段画像统计",
                source_uri="test://field-profile-statistics",
                source_type=RagChunkSourceType.METADATA,
                content="region_code 字段画像包含 null_ratio、distinct_count 和字段统计。",
                metadata={"category": "field_profile", "contentFormat": "json"},
            ),
            RagDocument(
                document_id="manual-schema-recovery",
                title="Schema 恢复手册",
                source_uri="test://manual-schema-recovery",
                source_type=RagChunkSourceType.DOCUMENT,
                content="字段映射失败后核对默认值和非空约束，刷新元数据并重新预检。",
                metadata={"category": "schema_recovery", "contentFormat": "docx"},
            ),
            RagDocument(
                document_id="field-mapping-case",
                title="字段映射案例",
                source_uri="test://field-mapping-case",
                source_type=RagChunkSourceType.TASK_CASE,
                content="字段案例记录 region_code 的源字段、目标字段、是否可空、默认值和映射验证结果。",
                metadata={"category": "field_mapping_case", "contentFormat": "xlsx"},
            ),
            RagDocument(
                document_id="worker-execution",
                title="Worker 执行日志",
                source_uri="test://worker-execution",
                source_type=RagChunkSourceType.INCIDENT,
                content="Worker 日志显示 errorCode=FIELD_MAPPING_MISSING，字段映射缺失后任务停止。",
                metadata={"category": "worker_execution", "contentFormat": "log"},
            ),
        )
        pipeline = self._category_pipeline(
            documents,
            {
                "field-profile-statistics": 0.96,
                "manual-schema-recovery": 0.74,
                "field-mapping-case": 0.73,
                "worker-execution": 0.72,
            },
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="region_code 是字段映射失败的典型问题吗？请结合恢复手册、字段案例和 Worker 执行日志给出排查顺序。",
                retrieval_mode="lexical",
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual(
            {"manual-schema-recovery", "field-mapping-case", "worker-execution"},
            {item.document_id for item in result.citations},
        )
        self.assertNotIn("field-profile-statistics", {item.document_id for item in result.citations})

    def test_absolute_rerank_floor_rejects_nearest_but_unsupported_candidate(self) -> None:
        """专用 Reranker 认为所有候选都极弱时，应拒绝把“最近邻”冒充答案。"""

        pipeline = self._scored_pipeline(
            {"primary": 0.0044, "related": 0.0031, "noise": 0.0002},
            minimum_relative_score=0.82,
            minimum_absolute_score=0.005,
        )

        result = pipeline.answer(
            RagQuery(
                tenant_id="*",
                project_id="*",
                actor_id="owner-a",
                question="火星冷链调度规则的当前阈值是多少？",
                retrieval_mode="lexical",
                candidate_limit=8,
                top_k=3,
                generate_answer=False,
            )
        )

        self.assertEqual((), result.citations)
        self.assertEqual(0, result.retrieval_summary["selectedCount"])

    @staticmethod
    def _scored_pipeline(
        scores: dict[str, float],
        *,
        minimum_relative_score: float,
        minimum_absolute_score: float = 0.0,
    ) -> RagPipeline:
        """构造可以精确控制 Reranker 分差的测试管线。"""

        routes = ModelRouteRegistry(default_model_routes())
        documents = tuple(
            RagDocument(
                document_id=document_id,
                title=f"字段映射恢复证据 {document_id}",
                source_uri=f"test://{document_id}",
                content="字段映射缺失后需要刷新元数据、补齐映射并执行预检。",
            )
            for document_id in scores
        )
        return RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(scores),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(
                minimum_absolute_rerank_score=minimum_absolute_score,
                minimum_relative_rerank_score=minimum_relative_score,
            ),
        )

    @staticmethod
    def _category_pipeline(
        documents: tuple[RagDocument, ...],
        scores: dict[str, float],
    ) -> RagPipeline:
        """构造带职责 category 和可控重排分数的多证据测试管线。"""

        routes = ModelRouteRegistry(default_model_routes())
        return RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            reranker=_ScoreReranker(scores),
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            settings=RagPipelineSettings(
                minimum_relative_rerank_score=0.55,
                multi_evidence_relative_rerank_score=0.55,
            ),
        )

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
        self.single_sensitivity_levels: list[str] = []
        self.batch_sensitivity_levels: list[tuple[str, ...]] = []

    def embed_text(
        self,
        text: str,
        *,
        sensitivity_level: str = "internal",
    ) -> tuple[float, ...]:
        """记录查询向量调用及其外发分级。"""

        self.single_calls.append(text)
        self.single_sensitivity_levels.append(sensitivity_level)
        return (1.0, 0.5, 0.25, 0.125)

    def embed_texts(
        self,
        texts: tuple[str, ...],
        *,
        sensitivity_levels: tuple[str, ...] | None = None,
    ) -> tuple[tuple[float, ...], ...]:
        """记录 chunk 批量调用及逐条分级，并返回固定向量。"""

        self.batch_calls.append(texts)
        self.batch_sensitivity_levels.append(
            sensitivity_levels or (("internal",) * len(texts))
        )
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


class _PreparedWindowReranker:
    """模拟有独立外发上限的 Provider，并记录准备、请求两个阶段。"""

    def __init__(self, *, limit: int) -> None:
        self._limit = limit
        self.prepared_question = ""
        self.prepared_document_ids: tuple[str, ...] = ()
        self.seen_document_ids: tuple[str, ...] = ()

    def prepare_candidates(self, candidates: tuple, *, query: RagQuery | None = None) -> tuple:
        """按固定上限返回窗口，并证明管线传入了当前查询。"""

        self.prepared_question = query.question if query is not None else ""
        prepared = tuple(candidates[: self._limit])
        self.prepared_document_ids = tuple(item.chunk.document_id for item in prepared)
        return prepared

    def rerank(self, query: RagQuery, candidates: tuple) -> tuple:
        """记录真正进入重排阶段的候选并保持顺序。"""

        self.seen_document_ids = tuple(item.chunk.document_id for item in candidates)
        return candidates

    @staticmethod
    def diagnostics() -> dict[str, object]:
        """返回低敏测试诊断。"""

        return {"implementation": "_PreparedWindowReranker", "configured": True}


class _ScoreReranker:
    """按文档 ID 注入归一化分数，用于验证 Reranker 后的证据裁剪。"""

    def __init__(self, scores: dict[str, float]) -> None:
        self._scores = dict(scores)

    def rerank(self, query: RagQuery, candidates: tuple) -> tuple:
        """复制候选并写入可预测分数，模拟专用 Cross-Encoder 输出。"""

        reranked = tuple(
            replace(
                item,
                rerank_score=self._scores[item.chunk.document_id],
                final_score=self._scores[item.chunk.document_id],
            )
            for item in candidates
        )
        return tuple(sorted(reranked, key=lambda item: item.final_score, reverse=True))

    @staticmethod
    def diagnostics() -> dict[str, object]:
        """返回低敏测试诊断。"""

        return {"implementation": "_ScoreReranker", "configured": True}


if __name__ == "__main__":
    unittest.main()
