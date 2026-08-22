"""可解释 RAG 管线。

本模块把 RAG 拆成一组明确步骤，而不是只调用框架 API：

1. `retrieve`：从知识库按硬隔离边界召回候选；
2. `rerank`：把完整候选窗口交给规则实现或显式配置的专用 Reranker；
3. `evidence gate + MMR`：先拒绝弱证据，再从合格候选中选出相关且不重复的最终证据；
4. `compress`：按上下文预算压缩证据，避免把整篇文档塞给模型；
5. `generate`：通过统一 ModelQueryEngine 调用治理问答模型；
6. `cite`：把答案和证据引用绑定，降低幻觉并提升可审计性。

当前已支持硅基流动 BGE Reranker，并可把知识库存储切换为 PostgreSQL/pgvector。后续接入其他专用模型、
Neo4j GraphRAG 或 MinIO 文档解析时，不应改变上层 API、范围隔离和证据门禁合同。
"""

from __future__ import annotations

import hashlib
import math
import re
from datetime import datetime, timezone
from dataclasses import dataclass, replace
from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationRequest,
    ModelMessage,
    WorkloadType,
)
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_provider_metadata import build_model_provider_metadata
from datasmart_ai_runtime.services.model_gateway.model_query_engine import ModelQueryEngine, estimate_prompt_tokens
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag.graph_rag import (
    GraphRagPathStep,
    GraphRagProvider,
    GraphRagQuery,
    GraphRagReasonCode,
    GraphRagResult,
    GraphRagResultStatus,
)
from datasmart_ai_runtime.services.rag.knowledge_base import RagHybridRetriever
from datasmart_ai_runtime.services.rag.models import RagCitation, RagPipelineResult, RagQuery, RagScoredChunk
from datasmart_ai_runtime.services.rag.reranker_provider import RagReranker
from datasmart_ai_runtime.services.rag.retrieval_router import (
    RagRetrievalDecision,
    RagRetrievalDecisionRouter,
)
from datasmart_ai_runtime.services.rag.text import (
    compress_chunk_text,
    distinctive_rag_query_terms,
    extract_rag_exact_identifiers,
    lexical_score_for_query,
    normalize_rag_query_facet,
    normalize_rag_retrieval_question,
    rag_query_document_intent_score,
    rag_query_requests_explicit_exact,
    rag_query_requests_multiple_evidence,
    rag_query_variant_has_substantive_signal,
    split_rag_query_variants,
    tokenize_for_rag,
)


# 只识别用户明确写出的“租户 + 项目”范围，不猜测自然语言里的公司名、部门名或数字。
# 这种保守策略既能拦住黄金集中的跨范围请求，也不会因为普通业务描述恰好包含数字而误拒绝。
_EXPLICIT_SCOPE_REFERENCE_PATTERN = re.compile(
    r"租户\s*[:：]?\s*([A-Za-z0-9_.-]+)\s*(?:的)?\s*项目\s*[:：]?\s*([A-Za-z0-9_.-]+)",
    re.IGNORECASE,
)

# 多证据集合覆盖的职责门槛。它不是模型置信度，也不是权限判断；它只表示“如果候选资料已经在
# Manifest 中声明了与当前 facet 对应的明确 category，那么只有同样达到该职责匹配强度的资料才能
# 宣告该 facet 已经被覆盖”。没有任何 category 候选时，算法才退回到词法证据，兼容没有完善元数据的
# 老资料和真实企业临时文档。
_MULTI_EVIDENCE_RESPONSIBILITY_INTENT_THRESHOLD = 0.85


@dataclass(frozen=True)
class RagPipelineSettings:
    """RAG 管线运行参数。

    - `temperature`：RAG 答案应偏稳定，默认低温；
    - `max_output_tokens`：限制生成长度，避免问答接口变成长报告生成器；
    - `citation_snippet_chars`：引用摘要长度；
    - `fallback_when_no_evidence`：没有证据时是否返回安全 fallback，而不是让模型裸答；
    - `minimum_lexical_score`：词项召回最低证据分，防止只命中“策略”“规则”等泛词就生成；
    - `minimum_match_terms`：词项召回至少命中的 token 数，避免单个弱词误召回；
    - `minimum_vector_score`：向量证据最低分，和 retriever 的向量阈值保持同一类安全语义。
    - `minimum_absolute_rerank_score`：专用 Reranker 的绝对证据下限；规则重排默认不启用。
    - `minimum_relative_rerank_score`：候选相对最高 Reranker 分数的最低比例，用于裁掉明显次相关引用。

    证据门控是 RAG 商业化落地里非常重要的一层：向量库、全文索引和 reranker 都可能给出“看起来最像”的
    候选，但治理问答需要的是“足够可引用”的候选。这里把门槛放在生成前，可以保证无证据时 fail-closed，
    而不是让模型根据弱相关片段自由发挥。
    """

    temperature: float = 0.1
    max_output_tokens: int = 1024
    citation_snippet_chars: int = 260
    fallback_when_no_evidence: bool = True
    minimum_lexical_score: float = 0.35
    minimum_match_terms: int = 2
    # 与检索器保持一致。该值是“进入候选集”的模型标定值，不是最终回答可信度。
    minimum_vector_score: float = 0.45
    minimum_unanchored_vector_score: float = 0.82
    minimum_absolute_rerank_score: float = 0.0
    minimum_relative_rerank_score: float = 0.82
    multi_evidence_relative_rerank_score: float = 0.55
    multi_evidence_facet_relative_score: float = 0.80
    multi_evidence_responsibility_intent_threshold: float = (
        _MULTI_EVIDENCE_RESPONSIBILITY_INTENT_THRESHOLD
    )
    query_intent_boost: float = 0.08


@dataclass(frozen=True)
class _MultiEvidenceFacetSignal:
    """保存一个候选资料对单个多证据 facet 的可解释支持强度。

    整句 Reranker 分数适合判断“整体上像不像这个问题”，但不能说明一份资料究竟回答了问题的哪一
    部分。这个小结构把词法、职责先验和归一化后的 facet 质量放在一起，供后面的有限集合覆盖使用。
    它只存在于当前查询过程，不会写入持久化表或对外响应。
    """

    quality: float
    intent_score: float
    lexical_score: float
    matched_terms: tuple[str, ...]


class RagHeuristicReranker:
    """轻量可解释 reranker。

    真正生产中可以使用专用 reranker 模型。但为了不把 RAG 原理完全黑盒化，这里先实现一版可解释规则：
    - 问题词在标题中命中，加分；
    - 词项召回命中越多，加分；
    - sourceType 为 rule/runbook 时在治理问答中略加权；
    - 仍保留上游 fused score，避免 rerank 完全推翻召回排序。
    """

    def rerank(self, query: RagQuery, candidates: tuple[RagScoredChunk, ...]) -> tuple[RagScoredChunk, ...]:
        """返回重排后的候选。"""

        query_terms = tokenize_for_rag(normalize_rag_retrieval_question(query.question))
        reranked: list[RagScoredChunk] = []
        for candidate in candidates:
            lexical = lexical_score_for_query(query.question, candidate.chunk)
            title_boost = 0.08 if any(term in candidate.chunk.title.lower() for term in query_terms) else 0.0
            governance_boost = 0.04 if candidate.chunk.source_type.value in {"rule", "runbook"} else 0.0
            # exactScore 是用户明确给出的稳定定位意图，给予足够大的本地加分，避免远端/规则重排把
            # 精确资料码误当成普通语义词。它不会越过上游范围过滤，也不会改变来源可信度字段。
            exact_boost = max(0.0, min(1.0, candidate.exact_score)) * 0.5
            rerank_score = (
                candidate.fused_score
                + lexical.score * 0.12
                + title_boost
                + governance_boost
                + exact_boost
            )
            reranked.append(
                RagScoredChunk(
                    chunk=candidate.chunk,
                    lexical_score=max(candidate.lexical_score, lexical.score),
                    vector_score=candidate.vector_score,
                    fused_score=candidate.fused_score,
                    rerank_score=rerank_score,
                    diversity_penalty=candidate.diversity_penalty,
                    final_score=rerank_score - candidate.diversity_penalty * 0.2,
                    match_terms=tuple(sorted(set(candidate.match_terms) | set(lexical.match_terms))),
                    exact_score=candidate.exact_score,
                    exact_match_identifiers=candidate.exact_match_identifiers,
                )
            )
        return tuple(sorted(reranked, key=lambda item: item.final_score, reverse=True))

    @staticmethod
    def diagnostics() -> dict[str, object]:
        """说明当前使用可解释规则重排，不冒充专用模型。"""

        return {
            "implementation": "RagHeuristicReranker",
            "providerType": "local-heuristic",
            "model": None,
            "configured": True,
            "productionModel": False,
            "failClosed": False,
            "payloadPolicy": "RAG_RERANK_DIAGNOSTICS_NO_QUERY_OR_DOCUMENT_BODY",
        }


class RagContextCompressor:
    """RAG 证据压缩器。

    Compressor 的核心原则是“让模型看到足够回答问题的证据，而不是看到所有检索到的文本”。当前实现按
    citation 顺序分配字符预算，并优先保留包含查询词的句子。
    """

    def compress(
        self,
        query: RagQuery,
        chunks: tuple[RagScoredChunk, ...],
        *,
        snippet_chars: int,
    ) -> tuple[str, tuple[RagCitation, ...]]:
        """生成带编号的证据上下文与引用列表。"""

        if not chunks:
            return "", ()
        query_terms = tokenize_for_rag(normalize_rag_retrieval_question(query.question))
        per_chunk_budget = max(160, query.max_context_chars // max(len(chunks), 1))
        context_parts: list[str] = []
        citations: list[RagCitation] = []
        used_chars = 0
        for index, scored in enumerate(chunks, start=1):
            remaining = query.max_context_chars - used_chars
            if remaining <= 80:
                break
            snippet = compress_chunk_text(
                scored.chunk.text,
                query_terms,
                max_chars=min(per_chunk_budget, remaining),
            )
            citation_id = f"C{index}"
            context_piece = (
                f"[{citation_id}] 标题：{scored.chunk.title}\n"
                f"来源：{scored.chunk.source_uri}\n"
                f"证据：{snippet}"
            )
            used_chars += len(context_piece)
            context_parts.append(context_piece)
            citations.append(
                RagCitation(
                    citation_id=citation_id,
                    document_id=scored.chunk.document_id,
                    chunk_id=scored.chunk.chunk_id,
                    title=scored.chunk.title,
                    source_uri=scored.chunk.source_uri,
                    snippet=snippet[:snippet_chars],
                    final_score=scored.final_score,
                )
            )
        return "\n\n".join(context_parts), tuple(citations)


class RagPipeline:
    """DataSmart 治理 RAG 管线。"""

    def __init__(
        self,
        *,
        retriever: RagHybridRetriever,
        model_routes: ModelRouteRegistry,
        model_gateway: ModelGatewayGovernanceService,
        model_providers: ModelProviderRegistry,
        reranker: RagReranker | None = None,
        compressor: RagContextCompressor | None = None,
        query_engine: ModelQueryEngine | None = None,
        settings: RagPipelineSettings | None = None,
        graph_rag_provider: GraphRagProvider | None = None,
        retrieval_decision_router: RagRetrievalDecisionRouter | None = None,
    ) -> None:
        self._retriever = retriever
        self._model_routes = model_routes
        self._model_gateway = model_gateway
        self._model_providers = model_providers
        self._reranker = reranker or RagHeuristicReranker()
        self._compressor = compressor or RagContextCompressor()
        self._query_engine = query_engine or ModelQueryEngine(
            model_gateway=self._model_gateway,
            model_providers=self._model_providers,
        )
        self._settings = settings or RagPipelineSettings()
        # GraphRAG 是普通 chunk RAG 的独立分支。Provider 由装配层注入，避免在管线内部偷偷创建
        # 一个没有权限上下文的图数据库连接；未注入时 graph 模式会明确返回不可用，而不会退回
        # 普通 RAG 猜测关系答案。
        self._graph_rag_provider = graph_rag_provider
        # `auto` 模式的决策器复用同一个 ModelQueryEngine，确保路径判断也经过预算、限流、Provider
        # 健康和低敏审计。显式 retrievalMode 仍然不调用模型，便于精确评测和兼容旧客户端。
        self._retrieval_decision_router = retrieval_decision_router or RagRetrievalDecisionRouter(
            model_routes=self._model_routes,
            query_engine=self._query_engine,
            graph_available=self._graph_rag_provider is not None,
        )

    def answer(self, query: RagQuery) -> RagPipelineResult:
        """执行完整 RAG 问答。

        如果没有证据，默认不会让模型直接裸答，因为治理场景下无依据回答容易造成规则误导。调用方可以通过
        settings 调整，但推荐生产保持 fail-closed。
        """

        validated_query = _validate_query(query)
        if _has_explicit_scope_reference_conflict(validated_query):
            # 范围冲突必须发生在 retriever 之前。否则即使底层正确过滤了私有文档，
            # 相似的全局文档仍可能被模型误当成用户点名项目的资料，形成“没有泄露原文、
            # 但用错误范围证据作答”的治理缺陷。
            retrieval_summary = self._retrieval_summary(
                query=validated_query,
                retrieved=(),
                gated=(),
                selected=(),
                compressed_context="",
            )
            retrieval_summary.update(
                {
                    "reasonCode": "RAG_QUERY_SCOPE_REFERENCE_CONFLICT",
                    "scopeReferenceConflict": True,
                }
            )
            return RagPipelineResult(
                answer="问题点名的租户或项目超出当前授权范围，已拒绝检索和生成。请切换到已授权项目或申请相应权限。",
                citations=(),
                selected_chunks=(),
                compressed_context="",
                retrieval_summary=retrieval_summary,
                model_summary={"skipped": True, "reason": "scope_reference_conflict"},
                generated=False,
            )
        decision = self._resolve_retrieval_decision(validated_query)
        if decision.mode == "graph":
            return self._answer_graph(validated_query, decision=decision)
        if decision.mode == "hybrid_graph":
            return self._answer_hybrid_graph(validated_query, decision)
        document_query = replace(validated_query, retrieval_mode=decision.mode)
        return self._answer_documents(document_query, decision=decision)

    def _resolve_retrieval_decision(self, query: RagQuery) -> RagRetrievalDecision:
        """解析显式模式或执行一次模型自主路由。

        显式模式是评测和后台运维的稳定复现入口；``auto`` 才表示“把检索路径交给当前 Agent
        模型判断”。无论哪种方式，返回的决策都会进入 retrievalSummary，便于运营人员区分模型选择、
        规则兜底和固定模式，而不是只看到一个模糊的 hybrid 字段。
        """

        if query.retrieval_mode == "auto":
            return self._retrieval_decision_router.decide(query)
        return RagRetrievalDecision(
            mode=query.retrieval_mode,
            decision_source="EXPLICIT_REQUEST",
            reason="调用方显式指定了检索模式。",
            confidence=1.0,
            requested_mode=query.retrieval_mode,
        )

    def _answer_documents(
        self,
        query: RagQuery,
        *,
        decision: RagRetrievalDecision,
    ) -> RagPipelineResult:
        """执行普通文档 RAG 的召回、重排、门控、压缩和回答生成。

        该方法从原来的 ``answer`` 主流程拆出，使 ``hybrid_graph`` 可以复用完全相同的文档证据质量
        规则。这样联合模式不会因为增加 GraphRAG 而绕过 BGE Reranker、MMR、范围过滤或引用绑定。
        """

        retrieved = self._retriever.retrieve(query)
        # 远端 Reranker 可能有独立的候选上限。优先调用 Provider 暴露的准备协议，使评测快照与真实
        # HTTP 外发窗口一致；本地规则 Reranker 没有该限制时保持完整召回窗口，仍然不会在 topK 前截断。
        reranker_input = _prepare_reranker_input(self._reranker, query, retrieved)
        reranked = self._reranker.rerank(query, reranker_input)
        reranked = _prioritize_exact_matches(reranked, query)
        # 远端 Reranker 或本地规则只能看到“查询 + 候选正文”。在治理资料中，category/sourceType/格式
        # 还表达了文档职责。这里把该先验作为很小的二阶段加分，并再次执行 exact 优先，确保它不能
        # 抢过用户明确指定的资料码，也不能绕过后面的证据门禁。
        reranked = _apply_query_intent_prior(
            query,
            reranked,
            boost=self._settings.query_intent_boost,
        )
        reranked = _prioritize_exact_matches(reranked, query)
        reranked = _protect_governed_exact_evidence(reranked, query)
        gated = tuple(
            item
            for item in reranked
            if _has_sufficient_evidence(item, self._settings, query)
        )
        citation_candidates = _prune_redundant_reranked_evidence(
            gated,
            self._settings,
            query=query,
        )
        selection_limit = max(1, min(query.top_k, 20))
        if rag_query_requests_multiple_evidence(query.question):
            # 多证据裁剪阶段已经用 facet 覆盖、资料职责和来源多样性完成了一次有界集合选择。
            # 这里不能再次把完整候选交给 MMR：MMR 只知道 finalScore 和文本相似度，不知道某份
            # 资料承担的是“接口、事故、日志”中的哪一面，容易用一份高分重复资料替换刚刚保住的
            # 低分互补资料。保留裁剪器的插入顺序，使“先覆盖缺失 facet，再补充职责”的决策成为
            # 最终引用事实；候选仍然已经通过范围过滤、Reranker 和 evidence gate。
            selected = citation_candidates[:selection_limit]
        else:
            # 单证据查询没有 facet 覆盖合同，继续使用 MMR 消除同一文档的相邻 chunk，避免改变
            # 原有单文档引用精度和上下文去重行为。
            selected = self._retriever.select_diverse(
                citation_candidates,
                top_k=selection_limit,
            )
        compressed_context, citations = self._compressor.compress(
            query,
            selected,
            snippet_chars=self._settings.citation_snippet_chars,
        )
        retrieval_summary = self._retrieval_summary(
            query=query,
            retrieved=retrieved,
            reranker_input=reranker_input,
            reranked=reranked,
            gated=gated,
            selected=selected,
            compressed_context=compressed_context,
        )
        retrieval_summary.update(decision.to_summary())
        if not selected and self._settings.fallback_when_no_evidence:
            return RagPipelineResult(
                answer="当前知识库没有召回到足够证据，已拒绝无依据生成。请补充项目文档、规则库或扩大检索范围。",
                citations=(),
                selected_chunks=(),
                compressed_context="",
                retrieval_summary=retrieval_summary,
                model_summary={"skipped": True, "reason": "no_evidence"},
                generated=False,
                retrieved_chunks=retrieved,
                reranker_input_chunks=reranker_input,
                reranked_chunks=reranked,
                gated_chunks=gated,
            )
        if not query.generate_answer:
            return RagPipelineResult(
                answer=_evidence_only_answer(citations),
                citations=citations,
                selected_chunks=selected,
                compressed_context=compressed_context,
                retrieval_summary=retrieval_summary,
                model_summary={"skipped": True, "reason": "generate_answer_false"},
                generated=False,
                retrieved_chunks=retrieved,
                reranker_input_chunks=reranker_input,
                reranked_chunks=reranked,
                gated_chunks=gated,
            )
        answer, model_summary = self._generate_answer(query, compressed_context, citations)
        return RagPipelineResult(
            answer=answer,
            citations=citations,
            selected_chunks=selected,
            compressed_context=compressed_context,
            retrieval_summary=retrieval_summary,
            model_summary=model_summary,
            generated=not bool(model_summary.get("errorCode")),
            retrieved_chunks=retrieved,
            reranker_input_chunks=reranker_input,
            reranked_chunks=reranked,
            gated_chunks=gated,
        )

    def _answer_hybrid_graph(
        self,
        query: RagQuery,
        decision: RagRetrievalDecision,
    ) -> RagPipelineResult:
        """联合执行普通文档 RAG 与 GraphRAG，并统一证据门禁。

        联合模式不是把两个答案简单拼接，而是先分别获得两类可引用证据，再用同一个回答模型生成：

        * 普通文档引用使用 ``C1``、``C2``，保留手册、日志、事故和任务案例原文；
        * 图关系引用使用 ``G1``、``G2``，保留实体、关系、时间、可信度和来源链；
        * 图关系发生冲突、别名歧义或来源不完整时，整体拒答，不能让普通文档掩盖结构化事实冲突。

        文档侧仍完整复用 BGE Embedding、BGE Reranker、MMR 和证据门禁，因此 GraphRAG 不是对现有
        RAG 的替换，而是按问题需要动态加入的一条受治理证据通道。
        """

        document_query = replace(query, retrieval_mode="hybrid", generate_answer=False)
        document_result = self._answer_documents(document_query, decision=decision)

        graph_query = replace(query, retrieval_mode="graph", generate_answer=False)
        graph_result = self._answer_graph(graph_query, decision=decision)
        graph_status = str(graph_result.retrieval_summary.get("graphStatus") or "")
        graph_reason = graph_result.graph_refusal_reason

        # 冲突、歧义和来源不完整属于关系事实的硬拒答条件。即使普通文档召回成功，也不能用文档
        # 相似度替代一条已经被图证据判定为不确定的关系边。
        hard_graph_refusal_reasons = {
            GraphRagReasonCode.CONFLICTING_CURRENT_EDGES.value,
            GraphRagReasonCode.AMBIGUOUS_ALIAS.value,
            GraphRagReasonCode.INCOMPLETE_PROVENANCE.value,
        }
        if graph_reason in hard_graph_refusal_reasons:
            refusal_summary = dict(graph_result.retrieval_summary)
            refusal_summary.update(
                {
                    "retrievalMode": "hybrid_graph",
                    "decisionMode": decision.mode,
                    "graphEvidenceGate": "REFUSED",
                    "documentEvidenceSuppressed": True,
                    "graphStatus": graph_status,
                }
            )
            return RagPipelineResult(
                answer=graph_result.answer,
                citations=(),
                selected_chunks=(),
                compressed_context="",
                retrieval_summary=refusal_summary,
                model_summary={"skipped": True, "reason": graph_reason},
                generated=False,
                graph_path=graph_result.graph_path,
                graph_citations=(),
                graph_refusal_reason=graph_reason,
            )

        graph_citations = tuple(graph_result.citations)
        document_citations = tuple(document_result.citations)
        combined_citations = document_citations + graph_citations
        combined_context = _combine_hybrid_context(
            document_result.compressed_context,
            graph_result.compressed_context,
            max_chars=query.max_context_chars,
        )
        retrieval_summary = dict(document_result.retrieval_summary)
        retrieval_summary.update(
            {
                "retrievalMode": "hybrid_graph",
                "decisionMode": decision.mode,
                "graphStatus": graph_status,
                "graphReasonCode": graph_result.retrieval_summary.get("graphReasonCode"),
                "graphRequestedHops": graph_result.retrieval_summary.get("graphRequestedHops"),
                "graphHopCount": graph_result.retrieval_summary.get("graphHopCount", 0),
                "graphEntityResolution": graph_result.retrieval_summary.get("graphEntityResolution"),
                "graphPath": graph_result.graph_path,
                "graphCitations": graph_result.graph_citations,
                "graphEvidenceCount": len(graph_citations),
                "documentEvidenceCount": len(document_citations),
                "compressedContextChars": len(combined_context),
                "graphProviderUnavailable": graph_reason == "GRAPH_PROVIDER_UNAVAILABLE",
            }
        )

        if not combined_citations and self._settings.fallback_when_no_evidence:
            return RagPipelineResult(
                answer="当前没有召回到足够的文档或关系证据，已拒绝无依据生成。",
                citations=(),
                selected_chunks=(),
                compressed_context="",
                retrieval_summary=retrieval_summary,
                model_summary={"skipped": True, "reason": "no_hybrid_graph_evidence"},
                generated=False,
                graph_path=graph_result.graph_path,
                graph_citations=graph_result.graph_citations,
                graph_refusal_reason=graph_reason,
            )
        if not query.generate_answer:
            return RagPipelineResult(
                answer=_evidence_only_answer(combined_citations),
                citations=combined_citations,
                selected_chunks=document_result.selected_chunks,
                compressed_context=combined_context,
                retrieval_summary=retrieval_summary,
                model_summary={"skipped": True, "reason": "generate_answer_false"},
                generated=False,
                retrieved_chunks=document_result.retrieved_chunks,
                reranker_input_chunks=document_result.reranker_input_chunks,
                reranked_chunks=document_result.reranked_chunks,
                gated_chunks=document_result.gated_chunks,
                graph_path=graph_result.graph_path,
                graph_citations=graph_result.graph_citations,
                graph_refusal_reason=graph_reason,
            )

        answer, model_summary = self._generate_answer(query, combined_context, combined_citations)
        return RagPipelineResult(
            answer=answer,
            citations=combined_citations,
            selected_chunks=document_result.selected_chunks,
            compressed_context=combined_context,
            retrieval_summary=retrieval_summary,
            model_summary=model_summary,
            generated=not bool(model_summary.get("errorCode")),
            retrieved_chunks=document_result.retrieved_chunks,
            reranker_input_chunks=document_result.reranker_input_chunks,
            reranked_chunks=document_result.reranked_chunks,
            gated_chunks=document_result.gated_chunks,
            graph_path=graph_result.graph_path,
            graph_citations=graph_result.graph_citations,
            graph_refusal_reason=graph_reason,
        )

    def _answer_graph(
        self,
        query: RagQuery,
        *,
        decision: RagRetrievalDecision | None = None,
    ) -> RagPipelineResult:
        """执行 GraphRAG 分支，并把每一跳的关系边转换成完整引用链。

        GraphRAG 与普通 RAG 的职责不同：普通 RAG 返回文档 chunk，GraphRAG 返回“实体 A 通过某条
        有来源、有效期和可信度的关系边到达实体 B”的路径。这里不把图结果伪装成 chunk 分数，也不
        在图查询失败时回退到普通语义近邻，否则调用方无法区分“有事实但图路径冲突”和“只是相似文档”。
        """

        empty_summary = self._retrieval_summary(
            query=query,
            retrieved=(),
            gated=(),
            selected=(),
            compressed_context="",
        )
        if decision is not None:
            empty_summary.update(decision.to_summary())
        if self._graph_rag_provider is None:
            reason = "GRAPH_PROVIDER_UNAVAILABLE"
            empty_summary.update(
                {
                    "graphStatus": GraphRagResultStatus.NOT_APPLICABLE.value,
                    "graphReasonCode": reason,
                    "graphPath": (),
                    "graphCitations": (),
                }
            )
            return RagPipelineResult(
                answer="当前未配置 GraphRAG 关系数据源，无法安全回答关系链问题。",
                citations=(),
                selected_chunks=(),
                compressed_context="",
                retrieval_summary=empty_summary,
                model_summary={"skipped": True, "reason": reason},
                generated=False,
                graph_refusal_reason=reason,
            )

        graph_query = GraphRagQuery(
            question=query.question,
            tenant=query.tenant_id,
            application=query.application_id,
            project=query.project_id,
            # 仅为旧 GraphRagQuery 构造器保留迁移期输入；Provider 的授权和 Cypher
            # 已不再使用该字段，新业务请求应始终传 applicationId。
            workspace=query.workspace_key,
            sensitivity=query.sensitivity_level,
            max_hops=query.graph_max_hops,
            start_entity=query.graph_start_entity,
            relation=query.graph_relation,
            hops=query.graph_hops,
            as_of=query.graph_as_of,
        )
        try:
            provider_query = getattr(self._graph_rag_provider, "query", None)
            if callable(provider_query):
                graph_result = provider_query(graph_query)
            else:
                graph_result = self._graph_rag_provider.retrieve(graph_query)
        except Exception:  # noqa: BLE001 - 图 Provider 错误不能把内部细节泄露给 API。
            reason = "GRAPH_PROVIDER_ERROR"
            empty_summary.update(
                {
                    "graphStatus": GraphRagResultStatus.REFUSAL.value,
                    "graphReasonCode": reason,
                    "graphPath": (),
                    "graphCitations": (),
                }
            )
            return RagPipelineResult(
                answer="GraphRAG 关系数据源暂时不可用，系统拒绝用普通相似资料替代关系事实。",
                citations=(),
                selected_chunks=(),
                compressed_context="",
                retrieval_summary=empty_summary,
                model_summary={"skipped": True, "reason": reason},
                generated=False,
                graph_refusal_reason=reason,
            )

        if not isinstance(graph_result, GraphRagResult):
            raise TypeError("GraphRAG Provider 必须返回 GraphRagResult")
        graph_path = tuple(step.to_dict() for step in graph_result.path)
        graph_citations = _graph_citation_records(graph_result.path)
        empty_summary.update(
            {
                "graphStatus": graph_result.status,
                "graphReasonCode": graph_result.reason_code,
                "graphRequestedHops": graph_result.requested_hops,
                "graphHopCount": graph_result.hop_count,
                "graphEntityResolution": graph_result.entity_resolution,
                "graphPath": graph_path,
                "graphCitations": graph_citations,
            }
        )
        compressed_context = _graph_context(graph_result.path)
        citations = tuple(
            RagCitation(
                citation_id=str(item["citationId"]),
                document_id=str(item["sourceDocumentId"]),
                chunk_id=str(item["sourceChunkId"]),
                title=str(item["title"]),
                source_uri=str(item["sourceUri"]),
                snippet=str(item["snippet"]),
                final_score=float(item["confidence"]),
            )
            for item in graph_citations
        )
        if graph_result.status == GraphRagResultStatus.SUCCESS.value:
            answer = graph_result.answer or "GraphRAG 已形成关系路径，但目标实体名称为空。"
            refusal_reason = None
            model_summary = {"skipped": True, "reason": "graph_structured_answer"}
        else:
            answer = graph_result.message or "当前有效关系证据不足，无法安全回答。"
            refusal_reason = graph_result.reason_code
            model_summary = {"skipped": True, "reason": graph_result.reason_code or graph_result.status}
        return RagPipelineResult(
            answer=answer,
            citations=citations,
            selected_chunks=(),
            compressed_context=compressed_context,
            retrieval_summary=empty_summary,
            model_summary=model_summary,
            generated=False,
            graph_path=graph_path,
            graph_citations=graph_citations,
            graph_refusal_reason=refusal_reason,
        )

    def diagnostics(self) -> dict[str, Any]:
        """返回低敏 RAG 运行诊断。"""

        graph_diagnostics: dict[str, Any]
        if self._graph_rag_provider is None:
            graph_diagnostics = {
                "enabled": False,
                "provider": "none",
                "reasonCode": "GRAPH_PROVIDER_NOT_CONFIGURED",
            }
        else:
            provider_diagnostics = getattr(self._graph_rag_provider, "diagnostics", None)
            graph_diagnostics = (
                dict(provider_diagnostics())
                if callable(provider_diagnostics)
                else {"enabled": True, "provider": type(self._graph_rag_provider).__name__}
            )
            graph_diagnostics.setdefault("enabled", True)
        return {
            "component": "datasmart-governance-rag-pipeline",
            "retriever": self._retriever.diagnostics(),
            "reranker": self._reranker.diagnostics(),
            "graphRag": graph_diagnostics,
            "settings": {
                "temperature": self._settings.temperature,
                "maxOutputTokens": self._settings.max_output_tokens,
                "citationSnippetChars": self._settings.citation_snippet_chars,
                "fallbackWhenNoEvidence": self._settings.fallback_when_no_evidence,
                "minimumLexicalScore": self._settings.minimum_lexical_score,
                "minimumMatchTerms": self._settings.minimum_match_terms,
                "minimumVectorScore": self._settings.minimum_vector_score,
                "minimumUnanchoredVectorScore": self._settings.minimum_unanchored_vector_score,
                "minimumAbsoluteRerankScore": self._settings.minimum_absolute_rerank_score,
                "minimumRelativeRerankScore": self._settings.minimum_relative_rerank_score,
                "multiEvidenceRelativeRerankScore": self._settings.multi_evidence_relative_rerank_score,
                "multiEvidenceFacetRelativeScore": self._settings.multi_evidence_facet_relative_score,
                "multiEvidenceResponsibilityIntentThreshold": (
                    self._settings.multi_evidence_responsibility_intent_threshold
                ),
                "queryIntentBoost": self._settings.query_intent_boost,
            },
            "algorithmStages": (
                "scope_filter",
                "chunking",
                "lexical_score",
                "optional_vector_score",
                "rrf_fusion",
                "rerank",
                "evidence_gate",
                "mmr_diversity",
                "context_compression",
                "model_generation",
                "citation_binding",
            ),
            "payloadPolicy": "RAG_DIAGNOSTICS_NO_QUERY_OR_DOCUMENT_BODY",
        }

    def _generate_answer(
        self,
        query: RagQuery,
        compressed_context: str,
        citations: tuple[RagCitation, ...],
    ) -> tuple[str, dict[str, Any]]:
        """调用治理问答模型生成最终答案。"""

        route = self._model_routes.route_for(WorkloadType.GOVERNANCE_QA)
        messages = _rag_messages(query, compressed_context, citations)
        context = ModelGatewayRequestContext(
            tenant_id=query.tenant_id,
            project_id=query.project_id,
            actor_id=query.actor_id,
            workload=WorkloadType.GOVERNANCE_QA,
            estimated_prompt_tokens=estimate_prompt_tokens(messages),
            estimated_completion_tokens=self._settings.max_output_tokens,
            trace_id=query.trace_id,
            attributes={
                "sessionId": query.session_id,
                "source": "governance_rag_pipeline",
                "citationCount": len(citations),
            },
        )
        model_request = ModelInvocationRequest(
            route=route,
            messages=messages,
            temperature=self._settings.temperature,
            max_output_tokens=self._settings.max_output_tokens,
            trace_id=query.trace_id,
            tool_choice="none",
            available_tools=(),
            provider_metadata=build_model_provider_metadata(context),
        )
        result = self._query_engine.invoke(model_request, context=context)
        model_summary = result.to_summary()
        model_summary["errorCode"] = result.result.error_code
        if result.result.error_code:
            return _evidence_only_answer(citations), model_summary
        return result.result.content, model_summary

    @staticmethod
    def _retrieval_summary(
        *,
        query: RagQuery,
        retrieved: tuple[RagScoredChunk, ...],
        gated: tuple[RagScoredChunk, ...],
        selected: tuple[RagScoredChunk, ...],
        compressed_context: str,
        reranker_input: tuple[RagScoredChunk, ...] = (),
        reranked: tuple[RagScoredChunk, ...] = (),
    ) -> dict[str, Any]:
        """构建低敏检索摘要。"""

        query_digest = "sha256:" + hashlib.sha256(query.question.encode("utf-8")).hexdigest()
        retrieved_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        query_terms = tokenize_for_rag(normalize_rag_retrieval_question(query.question))
        query_summary = {
            "kind": "RAG_QUERY",
            "queryLength": len(query.question),
            "tokenCount": len(query_terms),
            "retrievalMode": str(query.retrieval_mode).lower(),
            "sourceTypes": tuple(str(value).lower() for value in (query.source_types or ())),
        }
        evidence_records = tuple(
            {
                "evidenceId": "rag-evidence:" + hashlib.sha256(
                    f"{query_digest}|{query.tenant_id}|{query.project_id}|{query.workspace_key}|{item.chunk.chunk_id}".encode("utf-8")
                ).hexdigest(),
                "citationId": f"C{index}",
                "documentId": item.chunk.document_id,
                "chunkId": item.chunk.chunk_id,
                "sourceType": item.chunk.source_type.value,
                # sourceRef 是跨诊断、RAG 和 Java 控制面的统一来源字段；sourceUri 继续保留给旧调用方。
                "sourceRef": item.chunk.source_uri,
                "sourceUri": item.chunk.source_uri,
                "retrievedAt": retrieved_at,
                "queryDigest": query_digest,
                "querySummary": query_summary,
                "finalScore": round(item.final_score, 6),
                "confidence": _rag_evidence_confidence(item),
                "confidenceBasis": "HYBRID_RETRIEVAL_SCORE",
                # 来源事实描述文档本身何时生效、由什么依据确认；confidence 描述本次查询与文档的
                # 检索相关性。二者不能混成一个分数，否则高相关的过期文档会被误认为高可信。
                "sourceStatus": _rag_source_status(item),
                "sourceEffectiveAt": _rag_source_effective_at(item),
                "sourceConfidence": _rag_source_confidence(item),
                "sourceConfidenceBasis": _rag_source_confidence_basis(item),
            }
            for index, item in enumerate(selected, start=1)
        )
        source_type_counts: dict[str, int] = {}
        for record in evidence_records:
            source_type = str(record["sourceType"])
            source_type_counts[source_type] = source_type_counts.get(source_type, 0) + 1
        evidence_digest = "sha256:" + hashlib.sha256(
            "|".join(str(record["evidenceId"]) for record in evidence_records).encode("utf-8")
        ).hexdigest()
        stage_metrics = _vector_stage_metrics(
            retrieved=retrieved,
            reranker_input=reranker_input,
            reranked=reranked,
            gated=gated,
            selected=selected,
        )
        return {
            "candidateCount": len(retrieved),
            "evidenceAcceptedCount": len(gated),
            "weakEvidenceRejectedCount": max(len(retrieved) - len(gated), 0),
            "selectedCount": len(selected),
            "topK": query.top_k,
            "candidateLimit": query.candidate_limit,
            "compressedContextChars": len(compressed_context),
            "maxContextChars": query.max_context_chars,
            # 向量通道是否参与不能用“分数大于 0”代替。余弦相似度允许负值，测试阈值也可能显式放行
            # 负分候选；只要候选携带了非零向量分，就说明该通道确实参与了融合。
            "hasVectorSignal": any(item.vector_score != 0 for item in retrieved),
            "hasLexicalSignal": any(item.lexical_score > 0 for item in retrieved),
            "citationRequired": True,
            "queryDigest": query_digest,
            "querySummary": query_summary,
            "retrievedAt": retrieved_at,
            "evidenceRecords": evidence_records,
            "sourceTypeCounts": dict(sorted(source_type_counts.items())),
            "evidenceDigest": evidence_digest,
            "evidenceCount": len(evidence_records),
            "evidenceSourceTypes": tuple(sorted(source_type_counts)),
            "vectorStageMetrics": stage_metrics,
            "scope": {
                "tenantId": query.tenant_id,
                "projectId": query.project_id,
                "workspaceKey": query.workspace_key,
            },
            "payloadPolicy": "LOW_SENSITIVE_RAG_RETRIEVAL_SUMMARY_ONLY",
        }


def _vector_stage_metrics(
    *,
    retrieved: tuple[RagScoredChunk, ...],
    reranker_input: tuple[RagScoredChunk, ...],
    reranked: tuple[RagScoredChunk, ...],
    gated: tuple[RagScoredChunk, ...],
    selected: tuple[RagScoredChunk, ...],
) -> dict[str, int | float]:
    """计算向量证据在各阶段的存活情况。

    仅调 Embedding 的 ``top-k`` 或阈值无法解释“向量已经找到但引用没有出现”。这里把每个阶段
    都按 chunk ID 做集合比较：``retrieved`` 表示召回器事实，``rerankerInput`` 表示真正发送给
    Reranker 的窗口，``reranked`` 表示供应商返回的完整候选，``gated`` 表示通过回答前证据门禁，
    ``selected`` 表示最终进入 MMR/引用的候选。vector-only 定义为携带向量分且没有词法分的候选，
    正好对应“Embedding 独有命中”这个排障目标。指标只保存数量和比例，不保存正文、问题或凭据。
    """

    def vector_ids(items: tuple[RagScoredChunk, ...]) -> set[str]:
        return {
            item.chunk.chunk_id
            for item in items
            if float(item.vector_score) != 0.0
        }

    def vector_only_ids(items: tuple[RagScoredChunk, ...]) -> set[str]:
        return {
            item.chunk.chunk_id
            for item in items
            if float(item.vector_score) != 0.0 and float(item.lexical_score) <= 0.0
        }

    retrieved_vector = vector_ids(retrieved)
    retrieved_vector_only = vector_only_ids(retrieved)
    stage_values = {
        "retrieved": retrieved_vector,
        "rerankerInput": vector_ids(reranker_input),
        "reranked": vector_ids(reranked),
        "gated": vector_ids(gated),
        "selected": vector_ids(selected),
    }
    vector_only_values = {
        "retrieved": retrieved_vector_only,
        "rerankerInput": vector_only_ids(reranker_input),
        "reranked": vector_only_ids(reranked),
        "gated": vector_only_ids(gated),
        "selected": vector_only_ids(selected),
    }

    def ratio(numerator: int, denominator: int) -> float:
        # 没有向量候选时返回 0，而不是把“没有观测对象”伪装成 100% 覆盖；这样聚合评测不会被
        # lexical-only 用例抬高 vector survival 指标。
        return round(numerator / denominator, 6) if denominator else 0.0

    return {
        "vectorRetrievedCount": len(retrieved_vector),
        "vectorOnlyRetrievedCount": len(retrieved_vector_only),
        "vectorInRerankerCount": len(stage_values["rerankerInput"]),
        "vectorOnlyInRerankerCount": len(vector_only_values["rerankerInput"]),
        "vectorRerankedCount": len(stage_values["reranked"]),
        "vectorOnlyRerankedCount": len(vector_only_values["reranked"]),
        "vectorAcceptedCount": len(stage_values["gated"]),
        "vectorOnlyAcceptedCount": len(vector_only_values["gated"]),
        "vectorSelectedCount": len(stage_values["selected"]),
        "vectorOnlySelectedCount": len(vector_only_values["selected"]),
        "vectorRerankerWindowCoverage": ratio(
            len(stage_values["rerankerInput"]), len(retrieved_vector)
        ),
        "vectorOnlyRerankerWindowCoverage": ratio(
            len(vector_only_values["rerankerInput"]), len(retrieved_vector_only)
        ),
        "vectorAcceptanceRate": ratio(
            len(stage_values["gated"]), len(stage_values["reranked"])
        ),
        "vectorSelectionRate": ratio(
            len(stage_values["selected"]), len(stage_values["gated"])
        ),
    }


def _rag_evidence_confidence(item: RagScoredChunk) -> float:
    """把多路召回信号压缩为可审计的 0 到 1 证据可信度。

    ``finalScore`` 的量纲会随召回器配置变化，不能直接当作百分比。因此分别
    归一化向量、重排、词法和融合信号后再加权。这个值只描述引用与当前查询
    的匹配可信度，不代表文档内容本身绝对正确。
    """

    def bounded(value: float) -> float:
        return max(0.0, min(1.0, float(value)))

    lexical_raw = max(0.0, float(item.lexical_score))
    lexical_signal = bounded(lexical_raw / (1.0 + lexical_raw))
    confidence = (
        (0.35 * bounded(item.vector_score))
        + (0.25 * bounded(item.rerank_score))
        + (0.20 * lexical_signal)
        + (0.20 * bounded(item.fused_score))
    )
    return round(confidence, 6)


def _rag_source_status(item: RagScoredChunk) -> str:
    """读取文档自身的证据状态，并规范化为稳定大写值。"""

    metadata = item.chunk.metadata or {}
    value = metadata.get("sourceStatus") or metadata.get("evidenceStatus") or "UNSPECIFIED"
    return str(value).strip().upper()[:64] or "UNSPECIFIED"


def _rag_source_effective_at(item: RagScoredChunk) -> str | None:
    """返回文档来源声明的生效时间，不用本次检索时间冒充。"""

    metadata = item.chunk.metadata or {}
    value = metadata.get("effectiveAt") or metadata.get("updatedAt")
    normalized = str(value).strip() if value is not None else ""
    return normalized[:64] or None


def _rag_source_confidence(item: RagScoredChunk) -> float | None:
    """读取并限制文档来源可信度；非法值返回 None。"""

    value = (item.chunk.metadata or {}).get("sourceConfidence")
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(parsed):
        return None
    return round(max(0.0, min(1.0, parsed)), 6)


def _rag_source_confidence_basis(item: RagScoredChunk) -> str | None:
    """返回来源可信度的审计依据。"""

    value = (item.chunk.metadata or {}).get("sourceConfidenceBasis")
    normalized = str(value).strip() if value is not None else ""
    return normalized[:128] or None


def _rag_messages(
    query: RagQuery,
    compressed_context: str,
    citations: tuple[RagCitation, ...],
) -> tuple[ModelMessage, ...]:
    """构造 RAG 生成消息。"""

    citation_ids = ", ".join(citation.citation_id for citation in citations)
    return (
        ModelMessage(
            role="system",
            content=(
                "你是 DataSmart Govern 的数据治理 RAG 问答节点。"
                "只能基于给定证据回答；如果证据不足，必须说明不足；"
                "回答中需要使用 [C1]、[C2] 这样的引用编号，不要编造未出现的系统能力。"
            ),
        ),
        ModelMessage(
            role="user",
            content=(
                f"问题：{query.question}\n\n"
                f"可用引用编号：{citation_ids or '无'}\n\n"
                f"证据上下文：\n{compressed_context}\n\n"
                "请给出简洁、可执行、带引用的回答。"
            ),
        ),
    )


def _evidence_only_answer(citations: tuple[RagCitation, ...]) -> str:
    """未调用模型时返回证据摘要。"""

    if not citations:
        return "当前没有可用证据，无法生成可靠回答。"
    lines = ["已召回以下证据，可作为回答依据："]
    lines.extend(f"- [{citation.citation_id}] {citation.title}: {citation.snippet}" for citation in citations)
    return "\n".join(lines)


def _has_sufficient_evidence(
    candidate: RagScoredChunk,
    settings: RagPipelineSettings,
    query: RagQuery | None = None,
) -> bool:
    """判断候选 chunk 是否足以进入 RAG 生成上下文。

    这个函数解决的是 RAG 中很常见、但 demo 代码经常忽略的问题：检索系统只负责“找候选”，不天然保证
    “候选足够可靠”。例如用户问“火星仓库调度策略”，质量文档里出现了“审批策略”，词项检索就可能命中
    “策略”这个泛词。如果不做门控，模型会拿着弱证据编出看似合理的答案。

    当前采用三条可解释通过路径：
    1. 词项路径：lexical 分达到阈值，且命中 token 数达到阈值，说明不是单个泛词误召回；
    2. 向量路径：vector 分达到阈值，说明 embedding 语义相似度足够强。
    3. 精确路径：用户明确给出的资料码、检索锚点或受治理替代关系命中。

    生产接入专用 reranker 后，可以把 reranker 分数也纳入这里，但不建议直接移除 lexical/vector 门槛，
    因为门槛是治理问答 fail-closed 的安全边界。
    """

    # ``exact_score`` 也可能来自普通字段名、错误码或正文交叉引用，因此不能天然代表可靠证据。
    # 只有用户明确要求按资料码精确读取，或知识库已经沿受治理 ``supersededBy`` 关系解析出当前
    # 替代资料时，才允许确定性通道越过普通词法/向量阈值。
    if query is not None and _is_governed_exact_candidate(candidate, query):
        return True
    lexical_passed = (
        candidate.lexical_score >= settings.minimum_lexical_score
        and len(candidate.match_terms) >= settings.minimum_match_terms
    )
    # 多证据查询会把问题拆成多个独立 facet。某份接口说明只回答“接口标识”这一面时，
    # 它不一定同时包含整句里的所有陌生实体；因此要记住 facet 门禁的结果，不能在下面
    # 的整句陌生词检查中把它再次误杀。普通单问题仍然只走原有 lexical/vector 门禁。
    multi_evidence_facet_passed = False
    if query is not None and not lexical_passed and rag_query_requests_multiple_evidence(query.question):
        # 整句门禁默认要求两个词项，适合阻止“规则/步骤”等单个泛词误召回；但多证据问题中的
        # “认证、并发、超时、DLT”等短 facet 本来就可能只有一个稳定业务词。这里仅在问题已经被
        # 识别为多证据请求时，按每个实质 facet 独立检查强词项分。普通单问题、纯提问骨架和没有
        # 独特业务词的片段仍不会走这条放宽路径。
        multi_evidence_facet_passed = _has_sufficient_multi_evidence_facet(
            candidate,
            settings,
            query,
        )
        lexical_passed = multi_evidence_facet_passed
    vector_passed = candidate.vector_score >= settings.minimum_vector_score
    # 中文自然问法可能没有复用资料原文中的稳定短语，例如用户说“回执接错任务”，资料写的是
    # correlationId/taskId 关联规则。只要受治理职责先验明确匹配，并且候选仍有最低正文词法信号或
    # 正常向量信号，就允许它作为单一证据通过。职责先验本身不能放行，因此知识库外的“月球计费”类
    # 问题仍会被 distinctive/entity 门禁拒绝。
    responsibility_backed = False
    if query is not None:
        responsibility_score = rag_query_document_intent_score(query.question, candidate.chunk)
        # 结构化 CSV/JSON/SQL 资料经常把答案写在列名、键名或短值中，中文自然问法可能完全不复用
        # 正文 token。它们不能因为 category 标签就直接成为引用；但如果资料已经先经过范围过滤、
        # 真实 Reranker 窗口和排序，并且在一个明确的多证据问题中被模型保留了正相关分数，那么这
        # 是“职责先验 + 模型确认”的独立证据路径。该路径只在具体 category 上生效，宽泛的 incident、
        # runbook 等类别仍必须有正文或向量信号，避免 Manifest 元数据单独放行。
        structured_reranker_confirmed = _structured_responsibility_reranker_confirmed(
            candidate,
            query,
            responsibility_score,
        )
        responsibility_backed = (
            responsibility_score >= _MULTI_EVIDENCE_RESPONSIBILITY_INTENT_THRESHOLD
            and (
                candidate.lexical_score >= settings.minimum_lexical_score * 0.70
                or bool(candidate.match_terms)
                or candidate.vector_score >= settings.minimum_vector_score
            )
        )
        # 语义改写经常完全不复用资料原词，例如“自动处理后走向哪个收尾环节”。如果 Manifest
        # 已经声明了明确职责，且候选同时具备达到普通向量下限的语义信号，就不应再要求它命中整句
        # n-gram。这个放行只适用于高职责分候选，仍然受范围过滤、Provider 外发批准和向量阈值约束，
        # 不会把任意“看起来像”的向量邻居送进生成上下文。
        responsibility_backed = responsibility_backed or (
            responsibility_score >= max(
                _MULTI_EVIDENCE_RESPONSIBILITY_INTENT_THRESHOLD,
                float(settings.multi_evidence_responsibility_intent_threshold),
            )
            and candidate.vector_score >= settings.minimum_vector_score
        )
        responsibility_backed = responsibility_backed or structured_reranker_confirmed
    if query is not None:
        # 查询含有“火星冷链/海岛传感器”这类知识库外实体时，候选即使命中“规则/字段”等泛词，
        # 也不能凭普通向量近邻直接通过。只有较高的语义相似度，或候选确实覆盖了独特词，才允许进入
        # 生成上下文。这个额外门槛不会影响有明确词法证据的候选，也不会禁止 exact 通道。
        distinctive_terms = set(distinctive_rag_query_terms(query.question))
        distinctive_matches = distinctive_terms.intersection(
            str(term).casefold() for term in candidate.match_terms
        )
        if distinctive_terms and not distinctive_matches:
            lexical_passed = False
            vector_passed = candidate.vector_score >= settings.minimum_unanchored_vector_score
    # 当候选只覆盖某一个 facet 时，整句 distinctive token 可能没有命中是正常现象；
    # facet 门禁已经同时检查了独特词、职责先验和正文信号，此处允许它作为独立通过路径。
    return lexical_passed or vector_passed or multi_evidence_facet_passed or responsibility_backed


def _structured_responsibility_reranker_confirmed(
    candidate: RagScoredChunk,
    query: RagQuery,
    responsibility_score: float,
) -> bool:
    """判断结构化职责候选是否获得了足够的二阶段模型确认。

    这条路径专门处理“接收方承受不住”“保存的台账如何串起来”这类自然表达：候选资料的
    ``category``、标题和标签已经说明它负责连接器清单或恢复台账，但正文切块可能只有英文字段名，
    因而 lexical/vector 任一路都没有达到普通 gate 的最低分。候选仍必须同时满足以下条件：

    * 查询明确需要多个互补证据面，单证据问题继续保持 fail-closed；
    *职责分达到多证据门槛；
    * category 是具体职责类别，而不是宽泛的大类；
    * 候选已进入 Reranker，且保留了正的原始分与最终排序分。

    最后一项是关键边界：category 只负责解释“该资料应该回答哪一面”，真正的“这次查询确实
    相关”仍由二阶段模型确认。若 Provider 返回零分、协议错误或候选根本没有进入窗口，就不能
    通过这条路径生成引用。
    """

    if not rag_query_requests_multiple_evidence(query.question):
        return False
    if float(responsibility_score) < _MULTI_EVIDENCE_RESPONSIBILITY_INTENT_THRESHOLD:
        return False
    category = str((candidate.chunk.metadata or {}).get("category") or "").strip().casefold()
    if not category or category in {
        "architecture",
        "document",
        "governance",
        "incident",
        "rule",
        "runbook",
    }:
        return False
    return (
        float(candidate.rerank_score) > 0.0
        and float(candidate.final_score) > 0.0
    )


def _has_sufficient_multi_evidence_facet(
    candidate: RagScoredChunk,
    settings: RagPipelineSettings,
    query: RagQuery,
) -> bool:
    """判断候选是否对某一个实质子问题提供了足够强的词法证据。

    多证据查询的整句可能包含多个互不重复的名词，例如“认证、双主体审批和修复审计”。认证接口只需
    解释“认证”这一面，不应该被要求同时包含“审批”和“审计”。本方法逐个检查已经受控拆分的 facet：

    - facet 必须通过实质主题判断，避免“给出步骤/满足边界”放宽门禁；
    - 词法分仍必须达到与整句相同的最低阈值；
    - 两字术语或一个稳定 ASCII 标识可以只命中一个独特词，较长 facet 仍至少命中两个；
    - 候选命中词必须与该 facet 的独特词（含受控同义扩展）相交；如果资料 category 对该 facet
      有强职责匹配，则允许使用较低的词法下限，但仍至少要有一个正文/标题词法信号。

    这只是生成前证据门禁，不负责选择最终引用；后续仍要经过相对裁剪、多证据覆盖和 MMR。
    """

    variants = split_rag_query_variants(query.question)
    for variant in variants[1:]:
        facet_question = normalize_rag_query_facet(variant)
        if not facet_question or not rag_query_variant_has_substantive_signal(facet_question):
            continue
        if _score_multi_evidence_facet(
            candidate,
            facet_question,
            settings,
            query_context=query.question,
        ) is not None:
            return True
    return False


def _facet_has_strong_match(
    distinctive_terms: set[str],
    matched_terms: set[str],
) -> bool:
    """防止一个两字泛词伪装成较长业务 facet 的完整覆盖。

    中文 n-gram 会让“连接器容量”产生“容量”这样的两字片段。若只统计一个命中，任何包含“容量”的
    PostgreSQL 或资源手册都可能被误认为回答了“连接器容量”。长度至少为三个字符的中文术语、ASCII
    字段名或错误码能提供更可靠的锚点；真正只有一个两字业务词（批量、并发、认证、超时）允许单独
    精确命中，两个及以上独立两字词（例如“授权 + 决策 + 最小”）也构成可靠的联合锚点。该规则只用于
    多证据 facet，不改变普通词法排序。
    """

    if not matched_terms:
        return False
    if any(
        len(term) >= 3 or any(char.isascii() and char.isalnum() for char in term)
        for term in matched_terms
    ):
        return True
    return len(matched_terms) >= 2 or len(distinctive_terms) <= 1


def _score_multi_evidence_facet(
    candidate: RagScoredChunk,
    facet_question: str,
    settings: RagPipelineSettings,
    *,
    query_context: str | None = None,
) -> _MultiEvidenceFacetSignal | None:
    """计算候选对一个 facet 的支持，并执行 facet 级证据门禁。

    这里有意把“整句相关”与“子问题有证据”分开。一个字段画像可能恰好出现 ``region_code``，但它
    没有解释“非空失败的根因、允许的修复和日志验证”；如果只看整句词法分，它会挤掉恢复手册和
    Worker 日志。只有满足以下任一路径才会通过：

    1. 词法路径命中足够多的 facet 独特词；
    2. category/sourceType 明确声明了该职责，同时正文仍有最低词法信号。

    职责先验路径使用比普通词法更低的正文阈值，是为了容纳 DOCX/XLSX 提取后的同义表达；它仍然
    不能脱离正文命中单独放行。``query_context`` 只供职责内部区分“事件追踪型 replay”与“案例配置型
    replay”，不会把整句其他主题当成当前 facet 的词法证据。质量分优先使用职责匹配，再使用有界词法
    分，避免长日志仅凭重复词频压过短而准确的接口或 Runbook。
    """

    distinctive_terms = set(distinctive_rag_query_terms(facet_question))
    if not distinctive_terms:
        return None
    lexical = lexical_score_for_query(facet_question, candidate.chunk)
    matched_terms = distinctive_terms.intersection(
        str(term).casefold() for term in lexical.match_terms
    )
    facet_is_short = len(distinctive_terms) <= 2 or len(facet_question.strip()) <= 8
    required_matches = min(
        1 if facet_is_short else max(1, int(settings.minimum_match_terms)),
        len(distinctive_terms),
    )
    intent_score = rag_query_document_intent_score(
        facet_question,
        candidate.chunk,
        context_text=query_context,
    )
    # 某些结构化运维手册不会复述用户的自然动作词。例如 Kafka Runbook 记录的是“DLT/积压/处置
    # 步骤”，而用户 facet 可能只写“失败处置”；该资料已经凭整句“消息处理堵塞 + 消费端现象”被
    # 识别为 Kafka 运维职责，却没有可供局部 facet 复用的字面词。这里仅对明确的 Kafka 运维手册
    # category 开启整句职责借用，并且要求当前 facet 是“失败处置/堵塞/消费端现象”类主题、候选仍
    # 有最低正文词法信号。它不会让 category 单独生成证据，也不会影响其他资料职责。
    category = str((candidate.chunk.metadata or {}).get("category") or "").strip().casefold()
    kafka_operational_facet = any(
        term in facet_question.casefold()
        for term in ("失败处置", "消费端现象", "堵塞", "积压", "dlt", "死信")
    )
    # 整句同时包含“消费端现象”和“失败处置”时，职责收敛器会优先把整句判给日志 category，
    # 这对日志 facet 是正确的，却会让 Runbook facet 无法借用整句分。对当前 Runbook facet 只
    # 构造最小的 Kafka 运维上下文，保留它自己的动作词和“消息处理堵塞”主题，避免另一 facet 的
    # category 优先级把本资料误判为不相关。
    role_context = query_context
    if category == "kafka_operations_manual" and query_context:
        role_context = f"{facet_question} 消息处理堵塞 Kafka"
    context_intent_score = (
        rag_query_document_intent_score(role_context, candidate.chunk)
        if role_context
        else 0.0
    )
    kafka_runbook_context_passed = (
        category == "kafka_operations_manual"
        and kafka_operational_facet
        and context_intent_score >= _MULTI_EVIDENCE_RESPONSIBILITY_INTENT_THRESHOLD
        and (
            # Runbook 正文往往使用“DLT/积压/消费者组”等规范术语，而自然 facet 只写“失败处置”。
            # 因此用候选对整句的既有词法/向量信号证明它确实属于本问题，再允许整句职责补足
            # 局部动作词；不能要求局部 facet 与 Runbook 逐字重合。
            candidate.lexical_score >= float(settings.minimum_lexical_score) * 0.30
            or candidate.vector_score >= float(settings.minimum_vector_score) * 0.70
        )
    )
    if kafka_runbook_context_passed:
        intent_score = max(float(intent_score), float(context_intent_score))
    responsibility_threshold = max(
        0.0,
        min(
            2.5,
            float(settings.multi_evidence_responsibility_intent_threshold),
        ),
    )
    # 普通排障问题中的字段名不是“只读这一份资料”的指令，但它仍然可能是某个 facet 的唯一稳定
    # 锚点。例如 ``region_code`` 本身不会出现在“非空失败的根因”这类自然语言近问中，字段映射案例
    # 仍应凭字段名和 category 为该 facet 提供一份互补证据。这里允许“高职责匹配 + exact 字段命中”
    # 作为 facet 证据，却不把候选放入受保护种子，因此字段画像等低职责资料不会冻结整个选择。
    facet_exact_identifiers = set(extract_rag_exact_identifiers(facet_question))
    candidate_exact_identifiers = {
        str(identifier).removeprefix("replacement:").casefold()
        for identifier in candidate.exact_match_identifiers
    }
    exact_facet_matches = facet_exact_identifiers.intersection(candidate_exact_identifiers)
    exact_facet_passed = bool(
        exact_facet_matches
        and candidate.exact_score >= 0.5
        and intent_score >= responsibility_threshold
    )
    # 两字业务词（例如“批量”“并发”“超时”）在短文档中的词法分通常天然低于整句问题：
    # 文档没有重复一整句话，但它确实给出了该参数。这里仅对“短 facet + 强业务词命中”
    # 有界降低词法下限，仍然要求命中数和强词规则，避免“规则”“步骤”等泛词借机放行。
    facet_lexical_floor = float(settings.minimum_lexical_score) * (0.60 if facet_is_short else 1.0)
    strong_facet_match = _facet_has_strong_match(distinctive_terms, matched_terms)
    # 对只有一个明确两字业务词的短 facet，词法分和命中数已经足够表达证据；例如“批量”会被
    # 分词器识别为一个稳定术语，而不是“配置版本、批量、并发”整句中的泛化片段。必须要求
    # 命中词就是 facet 本身，避免任意两字 n-gram 获得同样的放宽。
    if not strong_facet_match and facet_is_short and len(matched_terms) == 1:
        only_matched_term = next(iter(matched_terms))
        strong_facet_match = only_matched_term == facet_question.strip()
    lexical_facet_passed = (
        lexical.score >= facet_lexical_floor
        and len(matched_terms) >= required_matches
        and strong_facet_match
    )

    # 结构化资料职责可以补足中文短问句中缺失的同义词。例如“接口标识追踪”在综合 API 文档中
    # 可能只留下一个“接口”词，但 category=api_contract_snapshot 已明确表明该候选负责接口合同。
    # 这里采用 0.30 倍正文阈值，而不是让 category 单独通过；这对 DOCX/XLSX 的分散表格内容更
    # 稳定，也能让字段恢复手册在“根因”facet 中保留一条可引用证据。
    intent_facet_passed = (
        intent_score >= responsibility_threshold
        and (
            (
                lexical.score >= settings.minimum_lexical_score * 0.30
                and bool(lexical.match_terms)
            )
            or kafka_runbook_context_passed
        )
    )
    # 对结构化职责资料，正文可能只有字段名/短值，当前 facet 与 chunk 没有可复用的中文 token。
    # 如果整句确实是多证据问题，并且该候选已经得到 Reranker 的正相关确认，则允许它覆盖自己的
    # facet。这里复用与整句 gate 相同的具体 category 白名单边界；没有正的 rerank/final 分数时，
    # category 仍然不能单独宣告 facet 已覆盖。
    structured_facet_reranker_confirmed = (
        query_context is not None
        and rag_query_requests_multiple_evidence(query_context)
        and _structured_responsibility_reranker_confirmed(
            candidate,
            RagQuery(
                tenant_id=candidate.chunk.tenant_id,
                project_id=candidate.chunk.project_id,
                actor_id="rag-facet-gate",
                question=query_context,
                application_id=candidate.chunk.application_id,
                workspace_key=candidate.chunk.workspace_key,
                generate_answer=False,
                retrieval_mode="lexical",
            ),
            intent_score,
        )
    )
    intent_facet_passed = intent_facet_passed or structured_facet_reranker_confirmed

    # 长 facet 中只命中一个字段名或一个两字泛词，不能算作完整支持。短 facet（例如“批量”“超时”）
    # 仍允许一个稳定 ASCII 字段或一个明确的两字业务术语通过；这样不会破坏已有的参数/认证回归。
    if lexical_facet_passed and intent_score < responsibility_threshold:
        if not facet_is_short and len(matched_terms) < 2:
            lexical_facet_passed = False

    if not (lexical_facet_passed or intent_facet_passed or exact_facet_passed):
        return None

    minimum_lexical = max(float(settings.minimum_lexical_score), 0.01)
    bounded_lexical = min(1.0, max(0.0, float(lexical.score)) / minimum_lexical)
    normalized_intent = min(1.0, max(0.0, float(intent_score)) / 1.0)
    if exact_facet_passed:
        # exact 字段命中只代表“这份资料涉及该字段”，职责分仍需占主要权重，避免字段画像或
        # 其他交叉引用资料凭一个字段名压过真正的映射案例。
        quality = (0.68 * normalized_intent) + (0.32 * min(1.0, float(candidate.exact_score)))
    elif intent_facet_passed:
        # category 精确匹配是高价值信号，但保留一部分词法分，防止只凭 Manifest 标签选错资料。
        quality = (0.68 * normalized_intent) + (0.32 * min(1.0, bounded_lexical))
    else:
        quality = 0.82 * min(1.0, bounded_lexical)
    return _MultiEvidenceFacetSignal(
        quality=round(max(0.0, min(1.0, quality)), 6),
        intent_score=float(intent_score),
        lexical_score=float(lexical.score),
        matched_terms=tuple(sorted(set(matched_terms).union(exact_facet_matches))),
    )


def _prune_redundant_reranked_evidence(
    candidates: tuple[RagScoredChunk, ...],
    settings: RagPipelineSettings,
    *,
    query: RagQuery | None = None,
) -> tuple[RagScoredChunk, ...]:
    """在最终 topK 之前按文档去重，并裁掉与最佳候选分差明显的证据。

    ``topK`` 是调用方允许的证据上限，不是必须凑满的数量。专用 Reranker 已经联合阅读了“问题 + 候选
    正文”，如果第一名得分很高而第二、三名明显偏低，继续返回它们只会降低引用精确率并增加生成模型
    混淆。相反，多跳问题的几份互补资料往往会得到接近的高分，因此相对阈值仍会保留它们。

    本方法先按 ``document_id`` 只保留得分最高的 chunk，防止长文档的相邻切块占满证据窗口；再以最佳
    文档分数乘 ``minimum_relative_rerank_score`` 得到动态下限。相对阈值比固定绝对分更适合同时兼容
    本地规则重排和不同供应商的 Cross-Encoder 分数尺度。``minimum_absolute_rerank_score`` 只应使用同一
    模型、同一语料版本的拒答样本校准；默认 0 表示本地规则重排不套用模型阈值。即使启用了该值，完整
    answerability 仍需要独立验收集，不能把单一分数当成普适拒答分类器。
    """

    if not candidates:
        return ()
    absolute_floor = max(0.0, min(1.0, float(settings.minimum_absolute_rerank_score)))
    eligible = tuple(
        candidate
        for candidate in candidates
        if float(candidate.final_score) >= absolute_floor
        or (
            query is not None
            and _is_governed_exact_candidate(candidate, query)
        )
    )
    if not eligible:
        return ()
    if query is not None:
        # 必须在按文档去重和多 facet 补充之前收敛候选。若只过滤 document_candidates，后面的
        # coverage-aware 补充仍会从原始 eligible 集合把通用邻居加回来，等于绕过职责门禁。
        eligible = _restrict_to_declared_responsibility(
            eligible,
            query,
            settings,
        )
    best_by_document: dict[str, RagScoredChunk] = {}
    for candidate in sorted(eligible, key=lambda item: item.final_score, reverse=True):
        best_by_document.setdefault(candidate.chunk.document_id, candidate)
    document_candidates = tuple(best_by_document.values())
    best_score = max(0.0, float(document_candidates[0].final_score))
    relative_floor = max(0.0, min(1.0, float(settings.minimum_relative_rerank_score)))
    if query is not None and rag_query_requests_multiple_evidence(query.question):
        # 多跳问题的支持资料可能只回答其中一个子问题，分数天然低于覆盖整句的主资料。降低相对裁剪
        # 只对明确的多证据请求生效；普通单文档问答继续使用更严格的默认阈值。
        relative_floor = min(
            relative_floor,
            max(0.0, min(1.0, float(settings.multi_evidence_relative_rerank_score))),
        )
    if relative_floor <= 0.0 or best_score <= 0.0:
        return document_candidates
    minimum_score = best_score * relative_floor
    pruned = tuple(
        candidate
        for candidate in document_candidates
        if float(candidate.final_score) >= minimum_score
    )
    if query is not None and rag_query_requests_multiple_evidence(query.question):
        # 多证据问题不能只按整句总分裁剪。整句往往更偏向“事故/结果”那一份长资料，另一份回答
        # “执行条件/接口契约”的资料即使已经通过证据门禁，也可能略低于相对阈值。对每个有限子问题
        # 保留一个最佳覆盖候选，等价于一个轻量的 coverage-aware fan-out；它不会扩大数据库范围，也
        # 不会把未通过上游门禁的候选重新放回来。
        pruned = _reserve_multi_evidence_coverage(
            pruned or document_candidates[:1],
            eligible,
            query,
            settings,
        )
    # 单问题也可能由 Embedding 找到一份不复用原问题词汇、但职责明确的资料。只按远端分数的相对
    # 阈值会把这类“互补语义证据”全部删掉，造成召回阶段和最终引用之间的断层。候选必须已经通过
    # evidence gate，并同时满足高向量分或明确职责先验；每个文档最多保留一份，最多补回一份，避免
    # 为了保护向量信号而牺牲引用精确率和上下文预算。
    if query is not None:
        pruned = _reserve_vector_semantic_evidence(
            pruned or document_candidates[:1],
            document_candidates,
            query,
            settings,
        )
    # 第一名已经通过上游证据门禁；浮点边界异常时仍保留它，避免裁剪层制造无证据假象。
    return pruned or document_candidates[:1]


def _reserve_vector_semantic_evidence(
    selected: tuple[RagScoredChunk, ...],
    candidates: tuple[RagScoredChunk, ...],
    query: RagQuery,
    settings: RagPipelineSettings,
) -> tuple[RagScoredChunk, ...]:
    """在重排相对裁剪后有限保留高价值 vector-only 证据。

    ``vector_score`` 不是答案可信度，不能单独绕过证据门禁；本函数只处理已经进入 ``candidates`` 的
    候选，并要求它是词法独有、达到正常向量门槛且满足以下任一条件：高于“无锚点”门槛，或具备
    明确的资料职责先验。职责先验仍需与正文词法/向量信号共同存在，避免 category 标签单独放行。
    这样可以修复 Reranker 分数尺度与 Embedding 信号不一致导致的丢证据，同时把新增引用数锁为一条。
    """

    if not candidates:
        return selected
    selected_ids = {item.chunk.document_id for item in selected}
    vector_candidates = [
        candidate
        for candidate in candidates
        if candidate.chunk.document_id not in selected_ids
        and float(candidate.vector_score) >= float(settings.minimum_vector_score)
        and float(candidate.lexical_score) <= 0.0
    ]
    if not vector_candidates:
        return selected
    threshold = max(
        float(settings.minimum_vector_score),
        float(settings.minimum_unanchored_vector_score),
    )
    protected = [
        candidate
        for candidate in vector_candidates
        if float(candidate.vector_score) >= threshold
        or rag_query_document_intent_score(query.question, candidate.chunk)
        >= max(
            _MULTI_EVIDENCE_RESPONSIBILITY_INTENT_THRESHOLD,
            float(settings.multi_evidence_responsibility_intent_threshold),
        )
    ]
    if not protected:
        return selected
    best = max(
        protected,
        key=lambda item: (float(item.vector_score), float(item.final_score)),
    )
    return tuple((*selected, best))


def _restrict_to_declared_responsibility(
    candidates: tuple[RagScoredChunk, ...],
    query: RagQuery,
    settings: RagPipelineSettings,
) -> tuple[RagScoredChunk, ...]:
    """在单一来源边界内优先保留职责明确的资料。

    ``sourceTypes`` 只有一个值时，调用方已经声明本次只需要同一类证据，例如只查 ``runbook``。
    这时 Cross-Encoder 容易因为正文更长，把“错误码目录”排在用户明确询问的“可观测性手册”旁边；
    如果两者都进入生成上下文，虽然召回率不受影响，引用精确率和答案聚焦度会明显下降。

    本方法只有在至少一份候选通过 Manifest category 的职责门槛时才收敛候选，并保留所有同样达到
    门槛的职责资料。没有 category、职责分不足、调用方要求多个来源类型，或用户明确指定资料码时，
    都保持原候选集合。这样 category 只负责缩小一个已经授权且已经通过证据门禁的集合，不会扩大
    范围、替代 Reranker，也不会把精确资料误删。
    """

    requested_source_types = {
        str(value).strip().casefold()
        for value in (query.source_types or ())
        if str(value).strip()
    }
    if (
        not candidates
        or len(requested_source_types) != 1
        or query.retrieval_mode == "exact_search"
        or rag_query_requests_explicit_exact(query.question)
    ):
        return candidates

    responsibility_threshold = max(
        0.0,
        min(
            2.5,
            float(settings.multi_evidence_responsibility_intent_threshold),
        ),
    )
    responsibility_backed_ids = {
        candidate.chunk.document_id
        for candidate in candidates
        if rag_query_document_intent_score(query.question, candidate.chunk)
        >= responsibility_threshold
    }
    if not responsibility_backed_ids:
        return candidates

    # 单证据查询只需要一个最贴合当前职责的类别。多个 category 都可能因为共享“恢复/日志/手册”
    # 等词进入 gate，但继续把相邻职责陪引到最终上下文会降低 citation precision，例如恢复台账
    # 与持久化快照、Kafka lag 日志与 Kafka 事故复盘。多证据问题必须保留互补类别，仍交给 facet
    # 集合覆盖；这里只有明确的单一 sourceType 且没有多 facet 合同才做职责收敛。
    if not rag_query_requests_multiple_evidence(query.question):
        best_responsibility = max(
            rag_query_document_intent_score(query.question, candidate.chunk)
            for candidate in candidates
            if candidate.chunk.document_id in responsibility_backed_ids
        )
        responsibility_backed_ids = {
            candidate.chunk.document_id
            for candidate in candidates
            if candidate.chunk.document_id in responsibility_backed_ids
            and rag_query_document_intent_score(query.question, candidate.chunk)
            >= best_responsibility * 0.92
        }

    protected_ids = responsibility_backed_ids.union(
        candidate.chunk.document_id
        for candidate in candidates
        if candidate.exact_score > 0
    )
    restricted = tuple(
        candidate
        for candidate in candidates
        if candidate.chunk.document_id in protected_ids
    )
    return restricted or candidates


def _reserve_multi_evidence_coverage(
    currently_selected: tuple[RagScoredChunk, ...],
    candidates: tuple[RagScoredChunk, ...],
    query: RagQuery,
    settings: RagPipelineSettings,
) -> tuple[RagScoredChunk, ...]:
    """用有界贪心集合覆盖为多证据问题选择互补候选。

    每个实质子问题先独立计算词法分。某候选只有达到该子问题最佳分数的配置比例，同时继续满足绝对
    词法门槛和独特词命中，才算覆盖该 facet。如果某个 facet 存在达到职责门槛的 category 候选，则先
    只在这些职责候选中计算覆盖；通用资料即使在正文里复述了整句，也不能用词法重合把专用职责挤掉。
    只有完全没有职责候选时，才退回词法覆盖。随后按“新增覆盖 facet 数、整句资料职责匹配、归一化
    覆盖强度、整句词法分、原始重排分”依次选择候选。一份 Runbook 能同时可靠回答两个 facet 时会优先
    于两份重复资料；覆盖数相同则优先选择与 Kafka、Schema、接口、事故等整句职责更一致的资料，独立
    事故记录仍会按缺失 facet 补回。

    算法最多选择 ``topK`` 份文档，只处理已经通过范围过滤、Reranker 和证据门禁的候选，不会扩大授权
    范围或把弱候选重新召回。只有 ``exact_search`` 或用户明确要求精确资料时，exact 命中才会作为
    受保护种子保留；普通字段名和错误上下文中的标识符仍可参与排序与召回，但不能冻结互补证据选择。
    """

    # exact_search 或用户明确说“精确码/只依据锚点”时，语义是沿稳定资料码找到主资料。即使问题
    # 句式里出现“以及、同时”等连接词，也不能追加相似事故或手册，否则精确引用会被互补阶段污染。
    # 普通混合查询中的字段名（例如 region_code）只是一种业务证据，不得触发这条保护。
    exact_protection_requested = (
        query.retrieval_mode == "exact_search"
        or rag_query_requests_explicit_exact(query.question)
    )
    if exact_protection_requested:
        exact_selected = tuple(
            item for item in currently_selected if item.exact_score > 0
        )
        return exact_selected or currently_selected

    variants = tuple(dict.fromkeys(
        normalized
        for variant in split_rag_query_variants(query.question)[1:]
        if rag_query_variant_has_substantive_signal(variant)
        if (normalized := normalize_rag_query_facet(variant))
    ))
    if not variants or not candidates:
        return currently_selected

    relative_floor = max(
        0.0,
        min(1.0, float(settings.multi_evidence_facet_relative_score)),
    )
    responsibility_threshold = max(
        0.0,
        min(
            2.5,
            float(settings.multi_evidence_responsibility_intent_threshold),
        ),
    )
    # documentId -> facetIndex -> 相对该 facet 最佳候选归一化后的覆盖强度。
    coverage_by_document: dict[str, dict[int, float]] = {}
    # 与覆盖强度并列保存职责分和词法分。选择时优先使用 facet 自身的职责，而不是整句意图，
    # 这样“Kafka 积压”不会把通用成功任务参数误当成 Kafka 证据。
    facet_intent_by_document: dict[str, dict[int, float]] = {}
    facet_lexical_by_document: dict[str, dict[int, float]] = {}
    # 一份 DOCX/XLSX/日志通常会产生很多 chunk。最终引用仍然只能保留一个代表 chunk，但 facet
    # 覆盖必须查看该文档的全部合格 chunk；否则“参数在工作表 A、验证结果在工作表 B”的文档会被
    # 错判为只覆盖一个主题。代表 chunk 始终选择 finalScore 最高者，保证引用片段仍与主排序一致。
    candidate_by_document: dict[str, RagScoredChunk] = {}
    for candidate in candidates:
        document_id = candidate.chunk.document_id
        existing = candidate_by_document.get(document_id)
        if existing is None or candidate.final_score > existing.final_score:
            candidate_by_document[document_id] = candidate
    for facet_index, variant in enumerate(variants):
        scored_by_document: dict[str, _MultiEvidenceFacetSignal] = {}
        for candidate in candidates:
            signal = _score_multi_evidence_facet(
                candidate,
                variant,
                settings,
                query_context=query.question,
            )
            if signal is None:
                continue
            document_id = candidate.chunk.document_id
            previous = scored_by_document.get(document_id)
            # 同一 DOCX/XLSX/日志的多个 chunk 可能分别包含字段、参数和验证结果；对每个 facet 保留
            # 最强 chunk 的信号，避免一个代表 chunk 的位置决定整份文档是否有证据。
            if previous is None or (
                signal.quality,
                signal.intent_score,
                signal.lexical_score,
            ) > (
                previous.quality,
                previous.intent_score,
                previous.lexical_score,
            ):
                scored_by_document[document_id] = signal
        if not scored_by_document:
            continue
        # category 是资料职责声明，不是正文关键词。只要当前 facet 存在一个达到职责门槛的候选，
        # 就把低于门槛的“碰巧提到同一词”的资料排除出本 facet 的覆盖集合；否则通用资料会因为
        # 一次整句复述把专用接口、任务案例或事故记录全部吞掉。这里仍保留这些候选在上游召回和
        # Reranker 结果中，便于审计“它曾被看到但没有承担该职责”，不会改变候选范围。
        responsibility_backed_documents = {
            document_id
            for document_id, signal in scored_by_document.items()
            if signal.intent_score >= responsibility_threshold
        }
        eligible_signals = (
            {
                document_id: signal
                for document_id, signal in scored_by_document.items()
                if document_id in responsibility_backed_documents
            }
            if responsibility_backed_documents
            else scored_by_document
        )
        best_facet_score = max(signal.quality for signal in eligible_signals.values())
        minimum_facet_score = best_facet_score * relative_floor
        for document_id, signal in eligible_signals.items():
            if signal.quality < minimum_facet_score:
                continue
            coverage_by_document.setdefault(document_id, {})[facet_index] = (
                signal.quality / best_facet_score if best_facet_score > 0 else 0.0
            )
            facet_intent_by_document.setdefault(document_id, {})[facet_index] = signal.intent_score
            facet_lexical_by_document.setdefault(document_id, {})[facet_index] = signal.lexical_score
    if not coverage_by_document:
        return currently_selected

    # 普通多证据查询不能把全局 Reranker 第一名永久锁成主资料：它可能只覆盖一个 facet，
    # 而另一份稍低分的资料能同时覆盖三个参数面。先清空非 exact 种子，再用有限集合覆盖重新
    # 选择，才能真正优化“覆盖面 + 职责匹配”，而不是把 topK 退化成排序结果的复制品。
    # 当前已裁剪集合仍作为无 facet 或异常情况下的安全回退；用户明确要求 exact 时，精确资料
    # 继续作为唯一受保护种子，不能被互补阶段替换。
    selected: dict[str, RagScoredChunk] = {}
    if exact_protection_requested:
        selected = {
            item.chunk.document_id: item
            for item in currently_selected
            if item.exact_score > 0
        }
    covered_facets = {
        facet_index
        for document_id in selected
        for facet_index in coverage_by_document.get(document_id, {})
    }
    all_facets = {
        facet_index
        for coverage in coverage_by_document.values()
        for facet_index in coverage
    }
    selection_limit = max(1, min(int(query.top_k), 20))
    requested_source_types = {
        str(value).strip().casefold()
        for value in (query.source_types or ())
        if str(value).strip()
    }
    # source_types 在普通查询里通常只是“允许检索哪些来源”的过滤条件；只有当问题明确要求
    # 多份互补证据且给出了至少两个来源类型时，才把它作为软多样性目标。这样可以让“手册 +
    # 案例 + 日志”各保留一份代表资料，同时不会把一个普通 source_types 过滤条件误当成必须
    # 凑齐的引用数量。
    source_diversity_requested = len(requested_source_types) >= 2
    selected_source_types = {
        str(item.chunk.source_type.value).strip().casefold()
        for item in selected.values()
    }
    whole_query_lexical: dict[str, float] = {}
    whole_query_intent: dict[str, float] = {}
    for candidate in candidates:
        document_id = candidate.chunk.document_id
        lexical_score_value = float(lexical_score_for_query(query.question, candidate.chunk).score)
        whole_query_lexical[document_id] = max(
            whole_query_lexical.get(document_id, 0.0),
            lexical_score_value,
        )
        whole_query_intent[document_id] = max(
            whole_query_intent.get(document_id, 0.0),
            rag_query_document_intent_score(query.question, candidate.chunk),
        )
    # 第一阶段只解决“还有哪些实质 facet 没有证据”的问题。排序必须先看当前 facet 的原始
    # 职责分、覆盖质量和局部词法，再把整句意图作为最后的领域平局裁决。例如“字段映射案例”
    # 与“Schema 事故”都可能覆盖“字段故障” facet，但前者的 category 职责分更高；如果先把
    # facet 意图封顶为 1.0，再比较长句整句分，长篇事故正文就会反过来压过真正的字段案例。
    while len(selected) < selection_limit:
        uncovered_facets = all_facets - covered_facets
        uncovered_source_types = (
            requested_source_types - selected_source_types
            if source_diversity_requested
            else set()
        )
        if not uncovered_facets and not uncovered_source_types:
            break
        best_document_id: str | None = None
        best_key: tuple[int, int, float, float, float, float, float, float] | None = None
        for document_id, facet_scores in coverage_by_document.items():
            if document_id in selected:
                continue
            newly_covered = uncovered_facets.intersection(facet_scores)
            candidate = candidate_by_document[document_id]
            candidate_source_type = str(candidate.chunk.source_type.value).strip().casefold()
            adds_source_type = candidate_source_type in uncovered_source_types
            if not newly_covered and not adds_source_type:
                continue
            key = (
                len(newly_covered),
                int(adds_source_type),
                sum(
                    min(
                        2.5,
                        max(
                            0.0,
                            facet_intent_by_document.get(document_id, {}).get(index, 0.0),
                        ),
                    )
                    / 2.5
                    for index in newly_covered
                ),
                sum(facet_scores[index] for index in newly_covered),
                sum(
                    min(1.0, max(0.0, facet_lexical_by_document.get(document_id, {}).get(index, 0.0)))
                    for index in newly_covered
                ),
                min(1.0, max(0.0, whole_query_intent.get(document_id, 0.0))),
                whole_query_lexical.get(document_id, 0.0),
                float(candidate.final_score),
            )
            if best_key is None or key > best_key:
                best_document_id = document_id
                best_key = key
        if best_document_id is None:
            break
        selected[best_document_id] = candidate_by_document[best_document_id]
        covered_facets.update(coverage_by_document[best_document_id])
        selected_source_types.add(
            str(candidate_by_document[best_document_id].chunk.source_type.value).strip().casefold()
        )

    # 第二阶段只在“所有可识别 facet 已覆盖”或“没有候选能再覆盖 facet”之后运行。候选仍然来自
    # 上游已经通过范围过滤、Reranker、绝对分数和证据门禁的集合，不能在这里重新访问知识库或扩大
    # 授权范围。补充资料必须对某个 facet 有自己的证据信号；整句意图不再足以触发补充。这样可以
    # 保留“综合资料 + 专门事件字典”的互补组合，同时阻止通用成功案例、模型手册和 Kafka 运维资料
    # 因为共享几个治理词就污染最终引用。
    uncovered_source_types = (
        requested_source_types - selected_source_types
        if source_diversity_requested
        else set()
    )
    coverage_complete = not (all_facets - covered_facets) and not uncovered_source_types
    # topK 是上限，不是“必须填满”的数量。多证据查询已经覆盖了全部 facet 和调用方声明的来源
    # 类型后，继续追加同职责或同来源的邻近资料只会降低引用精确率；需要更多证据时应由调用方
    # 提高 topK 或提出新的 facet，而不是由检索器自动凑数。
    # 调用方一旦显式给出 source_types，就已经表达了本次证据边界。facet 已全部覆盖后不再追加
    # 同一边界内的邻近类别；这样“incident”过滤下的 Checkpoint 问题不会凭空多出一份 Recovery
    # 事件。没有 source_types 的普通查询仍保留 companion 阶段，兼容综合资料 + 专门资料的治理问答。
    if len(selected) < selection_limit and not (bool(requested_source_types) and coverage_complete):
        selected_responsibilities = {
            _rag_responsibility_key(item)
            for item in selected.values()
        }
        selected_facet_intent: dict[int, float] = {}
        for document_id in selected:
            for facet_index, quality in coverage_by_document.get(document_id, {}).items():
                selected_facet_intent[facet_index] = max(
                    selected_facet_intent.get(facet_index, 0.0),
                    facet_intent_by_document.get(document_id, {}).get(facet_index, 0.0),
                )
        supplementary: list[tuple[tuple[float, ...], str, RagScoredChunk]] = []
        for document_id, candidate in candidate_by_document.items():
            if document_id in selected:
                continue
            facet_scores = coverage_by_document.get(document_id, {})
            # 没有 facet 级证据的资料，即使整句 finalScore 很高，也只是“邻近资料”，不应因为 topK
            # 尚有空位而进入最终引用。
            if not facet_scores:
                continue
            responsibility_key = _rag_responsibility_key(candidate)
            if responsibility_key in selected_responsibilities:
                # category 是治理资料的职责键；同一 category 的第二份资料默认视为重复职责。
                # 没有 category 的临时测试/旧资料使用文档自身作为退化键，不把 sourceType 粗暴当成
                # 职责，否则所有 document 或 incident 都会互相误去重。
                continue

            # 已覆盖 facet 只有在候选提供了明显更强的专门职责证据时才允许再次补充。比较的是
            # facet intent，而不是整句 intent：配置版本资料不能因为同属 task_case 就挤掉已经覆盖
            # “最近成功任务参数”的成功案例；Recovery 事件字典仍可补充一个只由综合资料覆盖的事件面。
            uncovered_facets = all_facets - covered_facets
            newly_covered = set(facet_scores).intersection(uncovered_facets)
            improved_facets = {
                facet_index
                for facet_index, intent_score in facet_intent_by_document.get(document_id, {}).items()
                if intent_score >= responsibility_threshold
                and (
                    selected_facet_intent.get(facet_index, 0.0) <= 0.0
                    or intent_score >= selected_facet_intent.get(facet_index, 0.0) + 0.12
                )
            }
            relevant_facets = newly_covered or improved_facets.intersection(facet_scores)
            if not relevant_facets:
                # 一个综合资料可能已经覆盖了某个 facet，但它不一定承担了该 facet 的最佳职责。
                # 例如字段映射案例能够提供字段名和参数，恢复手册仍然负责解释根因、前置条件和
                # 回滚；如果只按“facet 已覆盖”去重，后者会被错误丢弃。这里仅允许同时满足：
                # 1) category/sourceType 已通过高职责先验；2) 仍有至少 30% 的词法证据；3) 资料
                # category 尚未被选中；4) 来源类型与已选资料存在职责差异。最后一条专门阻止两个
                # 都是 task_case 的“Kafka 案例 + 泛成功参数”互相污染。这样不会因为 topK 有空位
                # 就补入同职责的泛化资料。
                selected_source_types = {
                    str(item.chunk.source_type.value).casefold()
                    for item in selected.values()
                }
                responsibility_complement_facets = {
                    facet_index
                    for facet_index, intent_score in facet_intent_by_document.get(document_id, {}).items()
                    if intent_score >= responsibility_threshold
                    and facet_lexical_by_document.get(document_id, {}).get(facet_index, 0.0)
                    >= float(settings.minimum_lexical_score) * 0.30
                    and str(candidate.chunk.source_type.value).casefold() not in selected_source_types
                }
                relevant_facets = responsibility_complement_facets.intersection(facet_scores)
            if not relevant_facets:
                continue
            quality = sum(facet_scores[index] for index in relevant_facets)
            explicit_intent = sum(
                min(1.0, max(0.0, facet_intent_by_document.get(document_id, {}).get(index, 0.0)))
                for index in relevant_facets
            )
            facet_lexical = sum(
                min(1.0, max(0.0, facet_lexical_by_document.get(document_id, {}).get(index, 0.0)))
                for index in relevant_facets
            )
            bounded_final = max(0.0, min(1.0, float(candidate.final_score)))
            # 这是“互补资料”排序分，不是新的证据门禁。facet 已经通过上游门禁，因此这里优先看
            # 新增 facet 数和明确职责，再看词法与 Reranker 分数；不会为了凑满 topK 引用弱邻居。
            supplement_score = (
                (0.40 * min(1.0, len(relevant_facets) / max(1, len(variants))))
                + (0.25 * min(1.0, explicit_intent / max(1, len(relevant_facets))))
                + (0.15 * min(1.0, quality / max(1, len(relevant_facets))))
                + (0.05 * min(1.0, facet_lexical / max(1, len(relevant_facets))))
                + (0.12 * min(1.0, max(0.0, whole_query_intent.get(document_id, 0.0))))
                + (0.03 * bounded_final)
            )
            if supplement_score < 0.28:
                continue
            supplementary.append(
                (
                    (
                        supplement_score,
                        float(len(relevant_facets)),
                        explicit_intent,
                        quality,
                        facet_lexical,
                        bounded_final,
                    ),
                    responsibility_key,
                    candidate,
                )
            )

        while len(selected) < selection_limit and supplementary:
            _, responsibility_key, candidate = max(
                supplementary,
                key=lambda item: item[0],
            )
            supplementary = [
                item
                for item in supplementary
                if item[1] != responsibility_key
            ]
            selected[candidate.chunk.document_id] = candidate
            selected_responsibilities.add(responsibility_key)
            selected_source_types.add(str(candidate.chunk.source_type.value).strip().casefold())
            candidate_facets = coverage_by_document.get(candidate.chunk.document_id, {})
            covered_facets.update(candidate_facets)
            for facet_index in candidate_facets:
                selected_facet_intent[facet_index] = max(
                    selected_facet_intent.get(facet_index, 0.0),
                    facet_intent_by_document.get(candidate.chunk.document_id, {}).get(facet_index, 0.0),
                )
            if source_diversity_requested:
                remaining_source_types = requested_source_types - selected_source_types
                if not (all_facets - covered_facets) and not remaining_source_types:
                    break

    if not selected:
        return currently_selected
    # ``selected`` 的插入顺序就是有限集合覆盖的决策顺序：先填补缺失 facet，再按职责互补补充。
    # 不能在这里重新按整句 finalScore 排序，否则下游即使跳过 MMR，也会再次丢掉低分但唯一覆盖
    # 某个子问题的资料。每个 document 已经由字典去重，返回值仍然严格受 topK 上限约束。
    return tuple(selected.values())


def _rag_responsibility_key(candidate: RagScoredChunk) -> str:
    """返回资料的职责去重键。

    生成资料的 Manifest 会用 ``category`` 表达“接口合同、事故复盘、字段画像、恢复决策”等职责。
    多证据补充阶段必须按这个字段去重，而不能只看 ``sourceType``：同样是 ``document`` 的资料可能
    分别负责 API、WebSocket 和 Recovery 事件。历史资料如果还没有 category，则退化到文档 ID，保证
    兼容旧数据的同时不把所有同类型资料错误合并。
    """

    metadata = candidate.chunk.metadata or {}
    category = str(metadata.get("category") or "").strip().casefold()
    if category:
        return f"category:{category}"
    return f"document:{candidate.chunk.document_id}"


def _prioritize_exact_matches(
    candidates: tuple[RagScoredChunk, ...],
    query: RagQuery,
) -> tuple[RagScoredChunk, ...]:
    """在重排后恢复用户明确指定的精确资料优先级。

    远端 Cross-Encoder 可能把一份包含很多通用术语的手册排在 exact 命中前面。只要候选已有 exact
    信号，这里就把它作为第一排序键；没有 exact 标识符时完全保持 Reranker 原有顺序。替代关系候选
    使用较低的 exactScore，因此直接命中现行资料仍然优先。
    """

    identifiers = extract_rag_exact_identifiers(query.question)
    if not identifiers or not any(item.exact_score > 0 for item in candidates):
        return candidates
    return tuple(
        sorted(
            candidates,
            key=lambda item: (
                item.exact_score > 0,
                item.exact_score,
                item.final_score,
            ),
            reverse=True,
        )
    )


def _protect_governed_exact_evidence(
    candidates: tuple[RagScoredChunk, ...],
    query: RagQuery,
) -> tuple[RagScoredChunk, ...]:
    """保护用户明确指定的资料所有者和 ``supersededBy`` 现行替代资料。

    exact 标识符有两种治理语义：用户明确说“请依据资料码/原始文件”时，应该只保留最强拥有者；
    用户在历史冲突问题中给出旧资料码时，知识库会把它转换成 ``replacement:<id>``，现行替代也必须
    免受远端分数尺度和相对裁剪影响。普通问题里出现字段名、错误码或日志 ID 仍不会触发本保护，
    这样字段映射、事故和恢复手册仍能组成真正的互补证据。
    """

    if not candidates:
        return ()
    explicit_exact = (
        query.retrieval_mode == "exact_search"
        or rag_query_requests_explicit_exact(query.question)
    )
    replacement = tuple(
        candidate
        for candidate in candidates
        if any(
            str(identifier).casefold().startswith("replacement:")
            for identifier in candidate.exact_match_identifiers
        )
    )
    if replacement:
        return _keep_strongest_exact_matches(replacement)
    if not explicit_exact:
        return candidates
    exact = tuple(candidate for candidate in candidates if candidate.exact_score > 0)
    return _keep_strongest_exact_matches(exact) if exact else candidates


def _is_governed_exact_candidate(candidate: RagScoredChunk, query: RagQuery) -> bool:
    """判断候选是否属于允许越过模型绝对分下限的确定性定位证据。

    只有两类候选满足条件：沿 ``supersededBy`` 找到的现行替代，或用户明确要求按资料码/锚点读取的
    exact 命中。普通字段名、错误码和日志 ID 不会因为有 ``exact_score`` 就跳过远端证据下限。
    """

    replacement = any(
        str(identifier).casefold().startswith("replacement:")
        for identifier in candidate.exact_match_identifiers
    )
    explicit_exact = (
        query.retrieval_mode == "exact_search"
        or rag_query_requests_explicit_exact(query.question)
    )
    return replacement or (explicit_exact and candidate.exact_score > 0)


def _keep_strongest_exact_matches(
    candidates: tuple[RagScoredChunk, ...],
) -> tuple[RagScoredChunk, ...]:
    """在当前已授权候选中保留最强 exact 拥有者，兼容多个分块和同码副本。

    这个阈值只比较 exact 标识符命中强度，不比较远端模型分数。完整命中元数据的资料通常为 1.0，
    仅在正文交叉引用的邻居通常为 0.65；保留最佳值 95% 以内可以保留同一资料的多个有效分块，
    同时排除只顺带提及资料码的旁证文档。
    """

    if not candidates:
        return ()
    best_score = max(float(candidate.exact_score) for candidate in candidates)
    minimum_score = best_score * 0.95
    return tuple(
        candidate
        for candidate in candidates
        if float(candidate.exact_score) >= minimum_score
    )


def _prepare_reranker_input(
    reranker: RagReranker,
    query: RagQuery,
    candidates: tuple[RagScoredChunk, ...],
) -> tuple[RagScoredChunk, ...]:
    """读取 Reranker 的真实外发窗口，兼容旧的单参数本地测试替身。

    远端 Provider 需要查询文本来做有界 facet 路由；本地规则 Reranker 通常没有外发窗口，也不需要
    这个参数。先检查方法签名再调用，避免把 Provider 内部真正的 ``TypeError`` 隐藏成兼容性回退。
    """

    prepare = getattr(reranker, "prepare_candidates", None)
    if callable(prepare):
        try:
            prepared = tuple(prepare(candidates, query=query))
        except TypeError as exc:
            # 旧扩展实现可能仍只有 ``prepare_candidates(candidates)``。只有错误明确来自
            # 不接受 query 关键字时才回退；Provider 自身的类型错误必须继续抛出，避免静默改变窗口。
            message = str(exc)
            if "query" not in message or "unexpected keyword" not in message:
                raise
            prepared = tuple(prepare(candidates))
        if len(prepared) > len(candidates):
            raise ValueError("RAG Reranker 候选准备协议不能扩大召回窗口。")
        return prepared
    return candidates


def _apply_query_intent_prior(
    query: RagQuery,
    candidates: tuple[RagScoredChunk, ...],
    *,
    boost: float,
) -> tuple[RagScoredChunk, ...]:
    """把资料职责先验作为受控的二阶段排序修正。

    这里不重新计算词法、向量或供应商原始 Reranker 分数，而是在 ``final_score`` 上增加一个有界的小
    修正。不同 Cross-Encoder 的分数尺度差异很大：BGE-Reranker 在高密度长文档上的有效分可能只有
    ``0.001~0.02``，如果直接加固定 ``0.08``，资料职责先验反而会盖过模型排序。因此先用本轮最高
    ``rerank_score`` 作为量纲，再施加职责加分。先验最终只负责打破同尺度近似平分，不能把低相关资料
    抬到模型明确判定的高相关资料之前。
    """

    if not candidates:
        return ()
    bounded_boost = max(0.0, min(1.0, float(boost)))
    if bounded_boost <= 0.0:
        return candidates

    rerank_scale = max(
        (max(0.0, float(candidate.rerank_score)) for candidate in candidates),
        default=0.0,
    )
    if rerank_scale <= 0.0:
        rerank_scale = max(
            (max(0.0, float(candidate.final_score)) for candidate in candidates),
            default=0.0,
        )
    rerank_scale = max(0.0, min(1.0, rerank_scale))

    adjusted: list[RagScoredChunk] = []
    for candidate in candidates:
        intent_score = rag_query_document_intent_score(query.question, candidate.chunk)
        adjusted.append(
            RagScoredChunk(
                chunk=candidate.chunk,
                lexical_score=candidate.lexical_score,
                vector_score=candidate.vector_score,
                fused_score=candidate.fused_score,
                rerank_score=candidate.rerank_score,
                diversity_penalty=candidate.diversity_penalty,
                final_score=(
                    candidate.final_score
                    + bounded_boost * rerank_scale * intent_score
                ),
                match_terms=candidate.match_terms,
                exact_score=candidate.exact_score,
                exact_match_identifiers=candidate.exact_match_identifiers,
            )
        )
    return tuple(sorted(adjusted, key=lambda item: item.final_score, reverse=True))


def _validate_query(query: RagQuery) -> RagQuery:
    """规范化 RAG 查询参数。"""

    question = str(query.question or "").strip()
    if not question:
        raise ValueError("RAG question 不能为空。")
    return RagQuery(
        tenant_id=str(query.tenant_id or "*").strip() or "*",
        application_id=str(query.application_id or "*").strip() or "*",
        project_id=str(query.project_id or "*").strip() or "*",
        actor_id=str(query.actor_id or "anonymous").strip() or "anonymous",
        question=question[:4000],
        workspace_key=str(query.workspace_key or "*").strip() or "*",
        top_k=max(1, min(int(query.top_k), 20)),
        candidate_limit=max(5, min(int(query.candidate_limit), 200)),
        max_context_chars=max(500, min(int(query.max_context_chars), 12000)),
        generate_answer=bool(query.generate_answer),
        trace_id=query.trace_id,
        session_id=query.session_id,
        sensitivity_level=_query_sensitivity_level(query.sensitivity_level),
        retrieval_mode=_retrieval_mode(query.retrieval_mode),
        source_types=tuple(
            sorted({str(value).strip().lower() for value in (query.source_types or ()) if str(value).strip()})
        ),
        graph_max_hops=max(1, min(int(query.graph_max_hops), 3)),
        graph_start_entity=(str(query.graph_start_entity).strip() or None)
        if query.graph_start_entity is not None
        else None,
        graph_relation=(str(query.graph_relation).strip() or None)
        if query.graph_relation is not None
        else None,
        graph_hops=int(query.graph_hops) if query.graph_hops is not None else None,
        graph_as_of=str(query.graph_as_of).strip() or None
        if query.graph_as_of is not None
        else None,
    )


def _query_sensitivity_level(value: object) -> str:
    """规范化查询正文分级，空值保持保守的 ``internal`` 默认值。

    该值不会自行决定能否外发；真正的允许列表仍由 Embedding/Reranker Provider 独立校验。这里仅保证
    API、Kafka worker 与本地调用使用稳定的小写标识，避免 ``RESTRICTED`` 与 ``restricted`` 形成两套
    缓存键或审计结论。
    """

    normalized = str(value or "internal").strip().lower().replace("_", "-")
    return normalized or "internal"


def _has_explicit_scope_reference_conflict(query: RagQuery) -> bool:
    """判断问题是否明确点名了当前授权范围之外的租户或项目。

    RAG 存储层已有“先范围过滤、再排序”的硬隔离，但它无法理解问题正文里点名的目标范围。
    例如当前授权为租户 10 / 项目 101，用户却询问项目 102；过滤器会正确排除项目 102 的
    私有文档，却仍可能召回主题相同的全局文档。此时继续回答会让用户误以为全局资料就是
    项目 102 的事实。

    因此本方法只处理可确定判断的中文范围表达：
    - 明确写出的租户、项目都等于查询授权范围时放行；
    - 任意一组明确范围与授权范围不一致时拒绝；
    - 查询范围为 ``*`` 时只允许问题保持全局语义，不能借全局查询读取点名的私有范围；
    - 没有明确范围表达时不做猜测，继续交给存储层硬隔离。

    该检查不是权限中心的替代品，而是检索前的一层语义防护。真正的文档可见性仍由
    ``tenant_id/project_id/workspace_key`` 过滤和上游授权事实共同决定。
    """

    authorized_scope = (query.tenant_id.casefold(), query.project_id.casefold())
    explicit_scopes = (
        (match.group(1).casefold(), match.group(2).casefold())
        for match in _EXPLICIT_SCOPE_REFERENCE_PATTERN.finditer(query.question)
    )
    return any(explicit_scope != authorized_scope for explicit_scope in explicit_scopes)


def _retrieval_mode(value: Any) -> str:
    normalized = str(value or "hybrid").strip().lower()
    return normalized if normalized in {
        "auto",
        "hybrid",
        "hybrid_graph",
        "lexical",
        "vector",
        "exact_search",
        "graph",
    } else "hybrid"


def _graph_citation_records(path: tuple[GraphRagPathStep, ...]) -> tuple[dict[str, Any], ...]:
    """把 GraphRAG 每一跳的来源边转换成统一引用记录。

    一条关系边不是普通文档 chunk，因此不能伪造 embedding 分数；这里用边自身的 confidence 作为
    关系证据可信度，并同时保留来源文档、URI、chunk、断言时间、生效时间和失效时间。多跳关系会得到
    多个引用编号，调用方可以沿 `graphPath -> graphCitations` 完整回溯到每一跳的原始材料。
    """

    records: list[dict[str, Any]] = []
    for index, step in enumerate(path, start=1):
        records.append(
            {
                "citationId": f"G{index}",
                "sourceDocumentId": step.source_document_id,
                "sourceChunkId": step.source_chunk_id,
                "sourceUri": step.source_uri,
                "title": f"{step.source_entity_name} -> {step.target_entity_name}",
                "snippet": (
                    f"{step.source_entity_name}（{step.source_entity_id}）"
                    f"通过 {step.relation} 指向 {step.target_entity_name}（{step.target_entity_id}）。"
                ),
                "confidence": round(float(step.confidence), 6),
                "assertedAt": _graph_timestamp_text(step.asserted_at),
                "effectiveAt": _graph_timestamp_text(step.effective_at),
                "expiresAt": _graph_timestamp_text(step.expires_at),
                "status": step.status,
                "hop": step.hop,
            }
        )
    return tuple(records)


def _graph_timestamp_text(value: Any) -> str | None:
    """序列化图边时间，兼容 datetime、date 和 ISO 字符串。"""

    if value is None:
        return None
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def _graph_context(path: tuple[GraphRagPathStep, ...]) -> str:
    """生成仅包含实体和关系摘要的 GraphRAG 上下文，不复制原始文档正文。"""

    return "\n".join(
        f"[G{step.hop}] {step.source_entity_name} -{step.relation}-> {step.target_entity_name}"
        for step in path
    )


def _combine_hybrid_context(
    document_context: str,
    graph_context: str,
    *,
    max_chars: int,
) -> str:
    """在上下文预算内同时保留关系路径和文档依据。

    联合检索最容易出现的工程问题是：普通文档正文把上下文预算占满，导致图路径被截掉；或者图路径
    过短，模型看不到解释关系边的原文。这里给图证据保留一个优先区，再把剩余预算给文档证据，
    并保留明确的小标题，方便模型区分 ``G`` 与 ``C`` 两类引用。
    """

    limit = max(500, int(max_chars))
    graph_part = f"关系证据：\n{graph_context}" if graph_context else ""
    document_part = f"文档证据：\n{document_context}" if document_context else ""
    full = "\n\n".join(part for part in (graph_part, document_part) if part)
    if len(full) <= limit:
        return full
    if not graph_part:
        return document_part[:limit]
    if not document_part:
        return graph_part[:limit]

    # 先保留完整关系段的尽可能多内容，文档段使用剩余预算；如果关系路径本身很长，仍然只裁剪
    # 上下文文本，graphPath/graphCitations 中的结构化完整证据不会被删除。
    graph_budget = min(len(graph_part), max(160, int(limit * 0.42)))
    document_budget = max(80, limit - graph_budget - 2)
    return f"{graph_part[:graph_budget]}\n\n{document_part[:document_budget]}"[:limit]


__all__ = [
    "RagContextCompressor",
    "RagHeuristicReranker",
    "RagPipeline",
    "RagPipelineSettings",
]
