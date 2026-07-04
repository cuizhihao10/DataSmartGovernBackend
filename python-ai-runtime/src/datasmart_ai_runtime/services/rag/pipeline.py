"""可解释 RAG 管线。

本模块把 RAG 拆成一组明确步骤，而不是只调用框架 API：

1. `retrieve`：从知识库按硬隔离边界召回候选；
2. `rerank`：用轻量规则模拟 reranker 的“更精细相关性判断”；
3. `compress`：按上下文预算压缩证据，避免把整篇文档塞给模型；
4. `generate`：通过统一 ModelQueryEngine 调用治理问答模型；
5. `cite`：把答案和证据引用绑定，降低幻觉并提升可审计性。

生产环境可以把第 2 步替换为 Qwen/BGE/Jina reranker，把第 1 步替换为 pgvector/Neo4j/MinIO 检索，但
这些替换不应改变 API 的主合同。
"""

from __future__ import annotations

from dataclasses import dataclass
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
from datasmart_ai_runtime.services.rag.knowledge_base import RagHybridRetriever
from datasmart_ai_runtime.services.rag.models import RagCitation, RagPipelineResult, RagQuery, RagScoredChunk
from datasmart_ai_runtime.services.rag.text import compress_chunk_text, lexical_score, tokenize_for_rag


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
    minimum_vector_score: float = 0.65


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

        query_terms = tokenize_for_rag(query.question)
        reranked: list[RagScoredChunk] = []
        for candidate in candidates:
            lexical = lexical_score(query_terms, candidate.chunk)
            title_boost = 0.08 if any(term in candidate.chunk.title.lower() for term in query_terms) else 0.0
            governance_boost = 0.04 if candidate.chunk.source_type.value in {"rule", "runbook"} else 0.0
            rerank_score = candidate.fused_score + lexical.score * 0.12 + title_boost + governance_boost
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
                )
            )
        return tuple(sorted(reranked, key=lambda item: item.final_score, reverse=True))


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
        query_terms = tokenize_for_rag(query.question)
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
    """DataSmart Governance RAG 管线。"""

    def __init__(
        self,
        *,
        retriever: RagHybridRetriever,
        model_routes: ModelRouteRegistry,
        model_gateway: ModelGatewayGovernanceService,
        model_providers: ModelProviderRegistry,
        reranker: RagHeuristicReranker | None = None,
        compressor: RagContextCompressor | None = None,
        query_engine: ModelQueryEngine | None = None,
        settings: RagPipelineSettings | None = None,
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

    def answer(self, query: RagQuery) -> RagPipelineResult:
        """执行完整 RAG 问答。

        如果没有证据，默认不会让模型直接裸答，因为治理场景下无依据回答容易造成规则误导。调用方可以通过
        settings 调整，但推荐生产保持 fail-closed。
        """

        validated_query = _validate_query(query)
        retrieved = self._retriever.retrieve(validated_query)
        reranked = self._reranker.rerank(validated_query, retrieved)
        gated = tuple(item for item in reranked if _has_sufficient_evidence(item, self._settings))
        selected = gated[: max(1, min(validated_query.top_k, 20))]
        compressed_context, citations = self._compressor.compress(
            validated_query,
            selected,
            snippet_chars=self._settings.citation_snippet_chars,
        )
        retrieval_summary = self._retrieval_summary(
            query=validated_query,
            retrieved=retrieved,
            gated=gated,
            selected=selected,
            compressed_context=compressed_context,
        )
        if not selected and self._settings.fallback_when_no_evidence:
            return RagPipelineResult(
                answer="当前知识库没有召回到足够证据，已拒绝无依据生成。请补充项目文档、规则库或扩大检索范围。",
                citations=(),
                selected_chunks=(),
                compressed_context="",
                retrieval_summary=retrieval_summary,
                model_summary={"skipped": True, "reason": "no_evidence"},
                generated=False,
            )
        if not validated_query.generate_answer:
            return RagPipelineResult(
                answer=_evidence_only_answer(citations),
                citations=citations,
                selected_chunks=selected,
                compressed_context=compressed_context,
                retrieval_summary=retrieval_summary,
                model_summary={"skipped": True, "reason": "generate_answer_false"},
                generated=False,
            )
        answer, model_summary = self._generate_answer(validated_query, compressed_context, citations)
        return RagPipelineResult(
            answer=answer,
            citations=citations,
            selected_chunks=selected,
            compressed_context=compressed_context,
            retrieval_summary=retrieval_summary,
            model_summary=model_summary,
            generated=not bool(model_summary.get("errorCode")),
        )

    def diagnostics(self) -> dict[str, Any]:
        """返回低敏 RAG 运行诊断。"""

        return {
            "component": "datasmart-governance-rag-pipeline",
            "retriever": self._retriever.diagnostics(),
            "settings": {
                "temperature": self._settings.temperature,
                "maxOutputTokens": self._settings.max_output_tokens,
                "citationSnippetChars": self._settings.citation_snippet_chars,
                "fallbackWhenNoEvidence": self._settings.fallback_when_no_evidence,
                "minimumLexicalScore": self._settings.minimum_lexical_score,
                "minimumMatchTerms": self._settings.minimum_match_terms,
                "minimumVectorScore": self._settings.minimum_vector_score,
            },
            "algorithmStages": (
                "scope_filter",
                "chunking",
                "lexical_score",
                "optional_vector_score",
                "rrf_fusion",
                "mmr_diversity",
                "heuristic_rerank",
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
    ) -> dict[str, Any]:
        """构建低敏检索摘要。"""

        return {
            "candidateCount": len(retrieved),
            "evidenceAcceptedCount": len(gated),
            "weakEvidenceRejectedCount": max(len(retrieved) - len(gated), 0),
            "selectedCount": len(selected),
            "topK": query.top_k,
            "candidateLimit": query.candidate_limit,
            "compressedContextChars": len(compressed_context),
            "maxContextChars": query.max_context_chars,
            "hasVectorSignal": any(item.vector_score > 0 for item in retrieved),
            "hasLexicalSignal": any(item.lexical_score > 0 for item in retrieved),
            "citationRequired": True,
            "payloadPolicy": "LOW_SENSITIVE_RAG_RETRIEVAL_SUMMARY_ONLY",
        }


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


def _has_sufficient_evidence(candidate: RagScoredChunk, settings: RagPipelineSettings) -> bool:
    """判断候选 chunk 是否足以进入 RAG 生成上下文。

    这个函数解决的是 RAG 中很常见、但 demo 代码经常忽略的问题：检索系统只负责“找候选”，不天然保证
    “候选足够可靠”。例如用户问“火星仓库调度策略”，质量文档里出现了“审批策略”，词项检索就可能命中
    “策略”这个泛词。如果不做门控，模型会拿着弱证据编出看似合理的答案。

    当前采用两条可解释通过路径：
    1. 词项路径：lexical 分达到阈值，且命中 token 数达到阈值，说明不是单个泛词误召回；
    2. 向量路径：vector 分达到阈值，说明 embedding 语义相似度足够强。

    生产接入专用 reranker 后，可以把 reranker 分数也纳入这里，但不建议直接移除 lexical/vector 门槛，
    因为门槛是治理问答 fail-closed 的安全边界。
    """

    lexical_passed = (
        candidate.lexical_score >= settings.minimum_lexical_score
        and len(candidate.match_terms) >= settings.minimum_match_terms
    )
    vector_passed = candidate.vector_score >= settings.minimum_vector_score
    return lexical_passed or vector_passed


def _validate_query(query: RagQuery) -> RagQuery:
    """规范化 RAG 查询参数。"""

    question = str(query.question or "").strip()
    if not question:
        raise ValueError("RAG question 不能为空。")
    return RagQuery(
        tenant_id=str(query.tenant_id or "*").strip() or "*",
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
    )


__all__ = [
    "RagContextCompressor",
    "RagHeuristicReranker",
    "RagPipeline",
    "RagPipelineSettings",
]
