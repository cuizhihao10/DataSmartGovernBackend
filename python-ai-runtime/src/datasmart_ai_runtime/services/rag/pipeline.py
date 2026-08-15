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
from datasmart_ai_runtime.services.rag.reranker_provider import RagReranker
from datasmart_ai_runtime.services.rag.text import compress_chunk_text, lexical_score, tokenize_for_rag


# 只识别用户明确写出的“租户 + 项目”范围，不猜测自然语言里的公司名、部门名或数字。
# 这种保守策略既能拦住黄金集中的跨范围请求，也不会因为普通业务描述恰好包含数字而误拒绝。
_EXPLICIT_SCOPE_REFERENCE_PATTERN = re.compile(
    r"租户\s*[:：]?\s*([A-Za-z0-9_.-]+)\s*(?:的)?\s*项目\s*[:：]?\s*([A-Za-z0-9_.-]+)",
    re.IGNORECASE,
)


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
        retrieved = self._retriever.retrieve(validated_query)
        reranker_input = retrieved
        reranked = self._reranker.rerank(validated_query, reranker_input)
        gated = tuple(item for item in reranked if _has_sufficient_evidence(item, self._settings))
        selected = self._retriever.select_diverse(
            gated,
            top_k=max(1, min(validated_query.top_k, 20)),
        )
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
                retrieved_chunks=retrieved,
                reranker_input_chunks=reranker_input,
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
                retrieved_chunks=retrieved,
                reranker_input_chunks=reranker_input,
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
            retrieved_chunks=retrieved,
            reranker_input_chunks=reranker_input,
        )

    def diagnostics(self) -> dict[str, Any]:
        """返回低敏 RAG 运行诊断。"""

        return {
            "component": "datasmart-governance-rag-pipeline",
            "retriever": self._retriever.diagnostics(),
            "reranker": self._reranker.diagnostics(),
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
    ) -> dict[str, Any]:
        """构建低敏检索摘要。"""

        query_digest = "sha256:" + hashlib.sha256(query.question.encode("utf-8")).hexdigest()
        retrieved_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        query_terms = tokenize_for_rag(query.question)
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
            "queryDigest": query_digest,
            "querySummary": query_summary,
            "retrievedAt": retrieved_at,
            "evidenceRecords": evidence_records,
            "sourceTypeCounts": dict(sorted(source_type_counts.items())),
            "evidenceDigest": evidence_digest,
            "evidenceCount": len(evidence_records),
            "evidenceSourceTypes": tuple(sorted(source_type_counts)),
            "scope": {
                "tenantId": query.tenant_id,
                "projectId": query.project_id,
                "workspaceKey": query.workspace_key,
            },
            "payloadPolicy": "LOW_SENSITIVE_RAG_RETRIEVAL_SUMMARY_ONLY",
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
        retrieval_mode=_retrieval_mode(query.retrieval_mode),
        source_types=tuple(
            sorted({str(value).strip().lower() for value in (query.source_types or ()) if str(value).strip()})
        ),
    )


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
    return normalized if normalized in {"hybrid", "lexical", "vector"} else "hybrid"


__all__ = [
    "RagContextCompressor",
    "RagHeuristicReranker",
    "RagPipeline",
    "RagPipelineSettings",
]
