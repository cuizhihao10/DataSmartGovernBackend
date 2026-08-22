"""RAG 知识库与混合召回。

本模块实现一个可替换的内存知识库和混合召回器。生产环境最终会把知识库替换成 PostgreSQL/pgvector、
Neo4j、MinIO 对象索引或企业搜索服务；但内存实现仍然很有价值：

- 单元测试不依赖外部中间件；
- 可以清楚展示 RAG 的基础算法；
- 可以作为 API smoke 与本地学习入口；
- 后续真实适配器必须遵守同一范围隔离和低敏摘要契约。
"""

from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass
from typing import Mapping, Protocol

from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    AgentMemoryEmbeddingProvider,
    validate_embedding_vector,
)
from datasmart_ai_runtime.services.rag.models import (
    RagChunk,
    RagDocument,
    RagQuery,
    RagScoredChunk,
    rag_query_explicitly_requests_history,
)
from datasmart_ai_runtime.services.rag.text import (
    build_lexical_chunk_profile,
    chunk_document,
    cosine_similarity,
    exact_identifier_match,
    extract_rag_exact_identifiers,
    jaccard_similarity,
    lexical_score,
    lexical_score_for_query,
    normalize_rag_query_facet,
    normalize_rag_retrieval_question,
    prepare_lexical_query_variants,
    rag_query_document_intent_score,
    rag_query_requests_multiple_evidence,
    rag_query_variant_has_substantive_signal,
    split_rag_query_variants,
    tokenize_for_rag,
)


class RagKnowledgeBase(Protocol):
    """RAG 知识库协议。"""

    def chunks_for_query(self, query: RagQuery) -> tuple[RagChunk, ...]:
        """按租户、项目和 workspace 返回允许进入召回排序的 chunk。"""

    def diagnostics(self) -> dict[str, object]:
        """返回低敏诊断。"""


@dataclass(frozen=True)
class RagKnowledgeCandidateSet:
    """知识库一次查询返回的候选正文和可复用向量分数。

    内存知识库只返回 chunk，由检索器本地计算向量；pgvector 已在数据库中完成近邻计算，因此同时返回
    ``vector_scores``。显式的数据结构让检索器知道这些分数已经计算完毕，即使结果为空也不会再次把
    查询和候选正文发送给远程 Embedding Provider。
    """

    chunks: tuple[RagChunk, ...]
    vector_scores: Mapping[str, float]


class InMemoryRagKnowledgeBase:
    """内存版 RAG 知识库。

    注意：内存知识库不是生产持久化方案。它的价值是把文档切块、范围过滤和候选窗口固定下来，后续
    PostgreSQL/pgvector 适配器只需要实现 `chunks_for_query(...)` 或更强的 search 协议即可。
    """

    def __init__(
        self,
        documents: tuple[RagDocument, ...],
        *,
        chunk_max_chars: int = 700,
        chunk_overlap_chars: int = 120,
    ) -> None:
        self._documents = tuple(document for document in documents if document.enabled)
        chunks: list[RagChunk] = []
        for document in self._documents:
            chunks.extend(
                chunk_document(
                    document,
                    max_chars=chunk_max_chars,
                    overlap_chars=chunk_overlap_chars,
                )
            )
        self._chunks = tuple(chunks)

    def replacement_chunks_for_query(
        self,
        query: RagQuery,
        query_identifiers: tuple[str, ...],
    ) -> tuple[RagChunk, ...]:
        """根据过期资料的 ``supersededBy`` 关系返回同范围现行替代 chunk。

        普通查询不能读取已过期资料正文，但用户在事故复盘时经常会直接给出旧资料码，例如
        ``HIS-RAG-001``。如果只做“过滤过期资料”，系统只能得到一堆泛化相似资料，无法把问题导向
        当前 Runbook。这里仅使用过期资料的元数据建立关系，不返回过期正文；最终返回的每个 chunk
        都重新经过当前查询的硬范围过滤，并且必须是现行证据。
        """

        if not query_identifiers:
            return ()
        requested = {str(value).casefold() for value in query_identifiers}
        replacement_keys: set[str] = set()
        for historical_chunk in self._chunks:
            # 历史关系本身也属于租户数据。必须在读取 ``supersededBy`` 前完成与 PostgreSQL
            # ``_scope_predicates`` 相同的范围和来源过滤，不能只在最终替代文档上检查范围。
            if not _chunk_matches_scope_and_source(historical_chunk, query):
                continue
            metadata = historical_chunk.metadata or {}
            status = str(metadata.get("sourceStatus") or metadata.get("evidenceStatus") or "").strip().upper()
            if status != "SUPERSEDED":
                continue
            historical_identifiers = extract_rag_exact_identifiers(
                " ".join(
                    str(metadata.get(key) or "")
                    for key in ("artifactCode", "retrievalAnchor", "logicalDocumentKey")
                )
                + " "
                + historical_chunk.document_id
            )
            if not requested.intersection(historical_identifiers):
                continue
            replacement = str(metadata.get("supersededBy") or "").strip().casefold()
            if replacement:
                replacement_keys.add(replacement)
        if not replacement_keys:
            return ()

        selected: list[RagChunk] = []
        seen_chunks: set[str] = set()
        for chunk in self._chunks:
            if not _chunk_visible(chunk, query):
                continue
            metadata = chunk.metadata or {}
            status = str(metadata.get("sourceStatus") or metadata.get("evidenceStatus") or "").strip().upper()
            if status == "SUPERSEDED":
                continue
            candidates = {
                str(metadata.get("retrievalAnchor") or "").casefold().split(":")[-1],
                str(metadata.get("logicalDocumentKey") or "").casefold(),
                str(chunk.document_id or "").casefold(),
            }
            if any(
                replacement == candidate
                or candidate.endswith("-" + replacement)
                or candidate.endswith(":" + replacement)
                for replacement in replacement_keys
                for candidate in candidates
                if candidate
            ) and chunk.chunk_id not in seen_chunks:
                seen_chunks.add(chunk.chunk_id)
                selected.append(chunk)
        return tuple(selected)

    def chunks_for_query(self, query: RagQuery) -> tuple[RagChunk, ...]:
        """返回当前查询可见的 chunk。

        过滤规则刻意先于任何排序执行：
        - `*` 表示全局公共知识；
        - 非 `*` 的 tenant/project/workspace 必须与查询完全一致；
        - 这样即使后续接向量召回，也不能先全局相似度搜索再过滤。
        """

        return tuple(chunk for chunk in self._chunks if _chunk_visible(chunk, query))

    def diagnostics(self) -> dict[str, object]:
        """返回低敏知识库诊断，不返回正文。"""

        type_counts: dict[str, int] = {}
        for chunk in self._chunks:
            type_counts[chunk.source_type.value] = type_counts.get(chunk.source_type.value, 0) + 1
        return {
            "implementation": type(self).__name__,
            "documentCount": len(self._documents),
            "chunkCount": len(self._chunks),
            "chunkSourceTypeCounts": dict(sorted(type_counts.items())),
            "persistent": False,
            "payloadPolicy": "RAG_KNOWLEDGE_DIAGNOSTICS_NO_DOCUMENT_BODY",
        }


@dataclass(frozen=True)
class RagHybridRetrieverSettings:
    """混合召回参数。

    - `lexical_weight/vector_weight`：词项召回和向量召回的融合权重；
    - `rrf_k`：Reciprocal Rank Fusion 平滑参数，值越大排名差异影响越小；
    - `mmr_lambda`：MMR 相关性与多样性的平衡，越接近 1 越偏相关性，越低越强去冗余；
    - `minimum_vector_score`：向量通道最低相似度阈值。

    这里专门保留 `minimum_vector_score`，是因为真实向量数据库通常会“尽力返回最近邻”：
    即使问题和知识库完全无关，也可能返回一个数学上最近、但业务上没有证据价值的 chunk。
    RAG 在治理场景中不能把“最近”误当成“可引用证据”，所以需要在向量通道增加阈值，
    再叠加 lexical、reranker 和 citation 约束，避免无证据问题触发模型裸答。
    """

    lexical_weight: float = 0.55
    vector_weight: float = 0.45
    rrf_k: int = 60
    mmr_lambda: float = 0.72
    # BGE-M3 在中文长文档上的余弦相似度通常集中在 0.45~0.60。0.65 会在进入 Reranker 前误删
    # 大量真实语义候选；0.45 只负责扩大候选召回，最终是否可引用仍由证据门禁和 Reranker 决定。
    minimum_vector_score: float = 0.45
    hierarchical_vector_minimum_chunks: int = 5000
    vector_routing_group_size: int = 24
    vector_routing_candidate_limit: int = 24
    vector_routing_groups_per_document: int = 2
    vector_routing_chunks_per_group: int = 2
    hybrid_vector_candidate_ratio: float = 0.5
    max_candidate_chunks_per_document: int = 2
    exact_match_weight: float = 4.0
    query_intent_rank_weight: float = 0.16


@dataclass(frozen=True)
class _RagVectorRoutingGroup:
    """大语料父子检索中的轻量路由单元。

    一个路由单元只负责判断“一组相邻 chunk 是否值得进入精排”，不会作为最终引用。最终引用仍指向原始
    ``RagChunk`` 和原始 ``sourceUri``，因此父级路由不会制造无法追溯的合成来源。
    """

    cache_key: str
    embedding_text: str
    sensitivity_level: str
    chunks: tuple[RagChunk, ...]


class RagHybridRetriever:
    """RAG 混合召回器。

    召回流程：
    1. 知识库先执行租户/项目/workspace 过滤；
    2. 对候选 chunk 计算词项分；
    3. 如配置 embedding provider，则计算 query/chunk 余弦相似度；
    4. 使用 RRF + 加权分融合 lexical/vector 两路排序；
    5. 用 MMR 做去冗余选择，避免 topK 全是同一文档的重复段落。
    """

    def __init__(
        self,
        knowledge_base: RagKnowledgeBase,
        *,
        embedding_provider: AgentMemoryEmbeddingProvider | None = None,
        settings: RagHybridRetrieverSettings | None = None,
    ) -> None:
        self._knowledge_base = knowledge_base
        self._embedding_provider = embedding_provider
        self._settings = settings or RagHybridRetrieverSettings()
        self._chunk_embedding_cache: dict[str, tuple[float, ...]] = {}
        self._routing_embedding_cache: dict[str, tuple[float, ...]] = {}

    def retrieve(self, query: RagQuery) -> tuple[RagScoredChunk, ...]:
        """执行混合召回并返回供 reranker 使用的有界候选窗口。

        ``top_k`` 表示最终证据数量，不应在专用 reranker 之前截断。这里按 ``candidate_limit`` 返回融合
        候选；RAG 管线完成重排和证据门禁后，再用 MMR 选择最终 ``top_k``，避免在大窗口上重复计算。
        """

        candidate_loader = getattr(self._knowledge_base, "candidate_set_for_query", None)
        if callable(candidate_loader):
            candidate_set = candidate_loader(query)
            visible_chunks = tuple(candidate_set.chunks)
            persistent_vector_scores: Mapping[str, float] | None = dict(
                candidate_set.vector_scores
            )
        else:
            visible_chunks = self._knowledge_base.chunks_for_query(query)
            persistent_vector_scores = None
        visible_chunks = _prefer_most_specific_scope_chunks(visible_chunks, query)
        retrieval_question = normalize_rag_retrieval_question(query.question)
        retrieval_mode = str(query.retrieval_mode or "hybrid").strip().lower()
        query_terms = tokenize_for_rag(retrieval_question)
        exact_identifiers = extract_rag_exact_identifiers(retrieval_question)
        replacement_loader = getattr(self._knowledge_base, "replacement_chunks_for_query", None)
        replacement_chunks = (
            tuple(replacement_loader(query, exact_identifiers))
            if callable(replacement_loader) and exact_identifiers
            else ()
        )
        # 替代资料通过独立关系查询进入候选，不能绕过上面对普通可见 chunk 执行的覆盖规则。同一个
        # supersededBy 在项目和全局都存在时，只保留项目现行版本，避免把默认基线和项目事实同时引用。
        replacement_chunks = _prefer_most_specific_scope_chunks(replacement_chunks, query)
        exact_ranked = self._exact_rank(
            visible_chunks,
            exact_identifiers,
            query=query,
            replacement_chunks=replacement_chunks,
        )
        lexical_ranked = (
            ()
            if retrieval_mode in {"vector", "exact_search"} and exact_ranked
            else self._lexical_rank(visible_chunks, retrieval_question, query_terms)
        )
        if retrieval_mode == "exact_search" and exact_ranked:
            # 精确搜索的语义是“先找指定资料”，不能让通用词法候选把目标挤出窗口。替代关系候选也
            # 允许进入，但它们只携带现行资料正文，不会把历史 chunk 重新暴露给后续 Reranker。
            exact_ranked = _strongest_exact_matches(exact_ranked)
            lexical_ranked = ()
            vector_ranked = ()
            fused = self._fuse((), (), exact_ranked)
            return self._bounded_candidate_window(
                fused,
                lexical_ranked=(),
                vector_ranked=(),
                exact_ranked=exact_ranked,
                query=query,
                candidate_limit=max(5, min(query.candidate_limit, 200)),
            )
        vector_ranked = self._vector_rank(
            visible_chunks,
            query,
            retrieval_question=retrieval_question,
            lexical_ranked=lexical_ranked,
            persistent_vector_scores=persistent_vector_scores,
        )
        fused = self._fuse(
            lexical_ranked,
            vector_ranked,
            exact_ranked,
            query=query,
        )
        return self._bounded_candidate_window(
            fused,
            lexical_ranked=lexical_ranked,
            vector_ranked=vector_ranked,
            exact_ranked=exact_ranked,
            query=query,
            candidate_limit=max(5, min(query.candidate_limit, 200)),
        )

    def select_diverse(
        self,
        candidates: tuple[RagScoredChunk, ...],
        *,
        top_k: int,
    ) -> tuple[RagScoredChunk, ...]:
        """在重排和证据门禁之后，用 MMR 选择少量相关且不重复的最终证据。

        该方法不重新访问知识库或 Embedding Provider。它只在最多 ``top_k`` 轮中比较当前候选，既保留
        专用 reranker 的排序纠正能力，也避免多份近重复文档占满最终引用。
        """

        return self._select_with_mmr(candidates, top_k=max(1, min(int(top_k), 20)))

    def diagnostics(self) -> dict[str, object]:
        """返回低敏召回器诊断。"""

        base = self._knowledge_base.diagnostics()
        base["retriever"] = {
            "implementation": type(self).__name__,
            "embeddingEnabled": self._embedding_provider is not None,
            "embeddingCacheSize": len(self._chunk_embedding_cache),
            "routingEmbeddingCacheSize": len(self._routing_embedding_cache),
            "lexicalWeight": self._settings.lexical_weight,
            "vectorWeight": self._settings.vector_weight,
            "rrfK": self._settings.rrf_k,
            "mmrLambda": self._settings.mmr_lambda,
            "minimumVectorScore": self._settings.minimum_vector_score,
            "hierarchicalVectorMinimumChunks": self._settings.hierarchical_vector_minimum_chunks,
            "vectorRoutingGroupSize": self._settings.vector_routing_group_size,
            "vectorRoutingCandidateLimit": self._settings.vector_routing_candidate_limit,
            "vectorRoutingGroupsPerDocument": self._settings.vector_routing_groups_per_document,
            "vectorRoutingChunksPerGroup": self._settings.vector_routing_chunks_per_group,
            "hybridVectorCandidateRatio": self._settings.hybrid_vector_candidate_ratio,
            "maxCandidateChunksPerDocument": self._settings.max_candidate_chunks_per_document,
            "exactMatchWeight": self._settings.exact_match_weight,
            "queryIntentRankWeight": self._settings.query_intent_rank_weight,
            "rrfUnit": "DOCUMENT_THEN_BEST_CHUNK",
            "scopeOverlayPolicy": "MOST_SPECIFIC_LOGICAL_DOCUMENT_WINS",
            "payloadPolicy": "RAG_RETRIEVER_DIAGNOSTICS_NO_QUERY_OR_DOCUMENT_BODY",
        }
        return base

    def _lexical_rank(
        self,
        chunks: tuple[RagChunk, ...],
        retrieval_question: str,
        query_terms: tuple[str, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """计算词项召回排序。"""

        scored: list[RagScoredChunk] = []
        prepared_variants = prepare_lexical_query_variants(retrieval_question)
        for chunk in chunks:
            # 子问题评分负责覆盖多跳查询；query_terms 参数仍作为兼容性输入保留，避免调用方
            # 误以为词法通道已经改成纯语义搜索。
            score = lexical_score_for_query(
                retrieval_question,
                chunk,
                prepared_variants=prepared_variants,
            )
            if not score.match_terms and query_terms:
                # 极短或只包含范围词的查询仍走原始 token 评分，保持旧调用的可解释行为。
                score = lexical_score(query_terms, chunk)
            intent_score = rag_query_document_intent_score(retrieval_question, chunk)
            # 自然语言问题经常只表达业务职责，不复用资料正文中的规范术语，例如“接收方承受不住”
            # 对应连接器清单中的 max_batch_size/rate_limit_rps。若这里坚持“必须先有正文词法”，
            # 这类资料永远进不了 fused，后面的 facet fan-out 和 SiliconFlow Reranker 没有机会判断
            # 它是否是真正证据。高职责候选只能作为召回保留项进入后续窗口；它仍必须经过真实
            # Reranker、正文/向量 evidence gate 和范围隔离，category 本身不能直接产生引用。
            intent_backed = intent_score >= 0.85
            if score.score > 0 or intent_backed:
                scored.append(
                    RagScoredChunk(
                        chunk=chunk,
                        lexical_score=score.score,
                        match_terms=score.match_terms,
                    )
                )
        # 先按正文词法或高职责保留条件筛出候选，再用资料职责先验做次级排序。category 不会把
        # 完全没有词法证据直接变成答案；高职责保留项只是在进入 Reranker 前扩大候选窗口，最终
        # 仍由证据门禁决定是否可引用。
        intent_weight = max(0.0, min(1.0, float(self._settings.query_intent_rank_weight)))
        scored.sort(
            key=lambda item: (
                item.lexical_score
                + intent_weight * rag_query_document_intent_score(retrieval_question, item.chunk),
                item.lexical_score,
            ),
            reverse=True,
        )
        return tuple(scored)

    def _facet_lexical_representatives(
        self,
        lexical_ranked: tuple[RagScoredChunk, ...],
        query: RagQuery | None,
    ) -> tuple[RagScoredChunk, ...]:
        """为长文档保留能回答新子问题的有限补充分块。

        RRF 的主排名以“文档”为单位，避免几千行 Excel/JSONL 用重复分块挤掉其他资料；但只保留整句
        最高分块又会产生另一个问题：同一文档中真正回答第二、第三个 facet 的分块在进入 Reranker 前
        就消失了。这里为明确的多证据问题做一次有界补充：

        1. 每份文档仍先保留整句排名最高的主块；
        2. 只从主块尚未覆盖的 facet 中寻找最佳补充块；
        3. 补充数量受 ``max_candidate_chunks_per_document`` 限制；
        4. 这里只扩大同一份已授权文档的候选视野，不绕过范围、来源状态或后续证据门禁。

        返回值只供 RRF/Reranker 使用，最终引用仍会按 documentId 去重为一个代表分块。
        """

        per_document_limit = max(
            1,
            min(int(self._settings.max_candidate_chunks_per_document), 20),
        )
        if query is None or per_document_limit <= 1 or not lexical_ranked:
            return ()
        variants = tuple(dict.fromkeys(
            normalized
            for variant in split_rag_query_variants(query.question)[1:]
            if rag_query_variant_has_substantive_signal(variant)
            if (normalized := normalize_rag_query_facet(variant))
        ))
        if len(variants) < 2:
            return ()

        # 只有原本有机会进入 candidateLimit 的文档才值得寻找第二分块。为了不漏掉正文较短、但
        # category 明确匹配问题的结构化资料，再额外保留少量高职责候选。这个池只控制 CPU 开销，
        # 不改变知识库范围，也不会把池外文档标记为“不相关”。
        document_representatives: list[RagScoredChunk] = []
        seen_documents: set[str] = set()
        for candidate in lexical_ranked:
            document_id = candidate.chunk.document_id or candidate.chunk.source_uri
            if document_id in seen_documents:
                continue
            seen_documents.add(document_id)
            document_representatives.append(candidate)
        document_pool_limit = max(16, min(200, int(query.candidate_limit)))
        pooled_document_ids = {
            item.chunk.document_id or item.chunk.source_uri
            for item in document_representatives[:document_pool_limit]
        }
        high_intent_representatives = sorted(
            (
                item
                for item in document_representatives[document_pool_limit:]
                if rag_query_document_intent_score(query.question, item.chunk) >= 0.85
            ),
            key=lambda item: (
                rag_query_document_intent_score(query.question, item.chunk),
                item.lexical_score,
            ),
            reverse=True,
        )[:16]
        pooled_document_ids.update(
            item.chunk.document_id or item.chunk.source_uri
            for item in high_intent_representatives
        )

        candidates_by_document: dict[str, list[RagScoredChunk]] = {}
        for candidate in lexical_ranked:
            document_id = candidate.chunk.document_id or candidate.chunk.source_uri
            if document_id not in pooled_document_ids:
                continue
            candidates_by_document.setdefault(document_id, []).append(candidate)

        # 同一个 chunk 的 facet 分数会在“主块覆盖判断、补充块排序、覆盖合并”三个步骤复用。
        # 缓存可以避免对长 JSONL/DOCX 重复分词和 n-gram 计算。
        prepared_facet_variants = tuple(
            prepare_lexical_query_variants(variant)
            for variant in variants
        )
        facet_scores_by_chunk: dict[str, tuple[float, ...]] = {}
        for document_candidates in candidates_by_document.values():
            for candidate in document_candidates:
                # 同一 chunk 只分词一次，再用多个已预分词 facet 评分。原实现会为每个 facet 重建
                # Counter，在长 DOCX/JSONL 中形成“候选块数 x facet 数”的重复 CPU 开销。
                profile = build_lexical_chunk_profile(candidate.chunk)
                facet_scores_by_chunk[candidate.chunk.chunk_id] = tuple(
                    lexical_score_for_query(
                        variant,
                        candidate.chunk,
                        profile=profile,
                        prepared_variants=prepared,
                    ).score
                    for variant, prepared in zip(variants, prepared_facet_variants)
                )

        selected: list[RagScoredChunk] = []
        maximum_extras = per_document_limit - 1
        for document_candidates in candidates_by_document.values():
            if len(document_candidates) < 2:
                continue
            primary = document_candidates[0]
            primary_scores = facet_scores_by_chunk[primary.chunk.chunk_id]
            uncovered_facets = {
                index
                for index, score in enumerate(primary_scores)
                if score <= 0.0
            }
            if not uncovered_facets:
                continue

            # 一个补充块可能同时回答多个缺失 facet，例如 Recovery 事件同时包含 replay 与最终验证。
            # 先按新增覆盖数排序，再按这些 facet 的词法强度排序，避免选择另一个只重复主块的片段。
            ranked_extras: list[tuple[tuple[int, float, float, float], RagScoredChunk]] = []
            for candidate in document_candidates[1:]:
                facet_scores = facet_scores_by_chunk[candidate.chunk.chunk_id]
                newly_covered = {
                    index
                    for index in uncovered_facets
                    if facet_scores[index] > 0.0
                }
                if not newly_covered:
                    continue
                ranked_extras.append(
                    (
                        (
                            len(newly_covered),
                            sum(facet_scores[index] for index in newly_covered),
                            max(facet_scores[index] for index in newly_covered),
                            float(candidate.lexical_score),
                        ),
                        candidate,
                    )
                )
            ranked_extras.sort(key=lambda item: item[0], reverse=True)

            covered_by_extras: set[int] = set()
            selected_for_document = 0
            for _, candidate in ranked_extras:
                if len(covered_by_extras) >= len(uncovered_facets):
                    break
                candidate_coverage = {
                    index
                    for index in uncovered_facets
                    if facet_scores_by_chunk[candidate.chunk.chunk_id][index] > 0.0
                }
                if not (candidate_coverage - covered_by_extras):
                    continue
                selected.append(candidate)
                covered_by_extras.update(candidate_coverage)
                selected_for_document += 1
                if selected_for_document >= maximum_extras:
                    break
        return tuple(selected)

    def _exact_rank(
        self,
        chunks: tuple[RagChunk, ...],
        query_identifiers: tuple[str, ...],
        *,
        query: RagQuery,
        replacement_chunks: tuple[RagChunk, ...] = (),
    ) -> tuple[RagScoredChunk, ...]:
        """计算精确标识符和现行替代资料的优先排序。"""

        if not query_identifiers and not replacement_chunks:
            return ()
        scored: dict[str, RagScoredChunk] = {}
        for chunk in chunks:
            match = exact_identifier_match(query_identifiers, chunk)
            if match.score <= 0:
                continue
            scored[chunk.chunk_id] = RagScoredChunk(
                chunk=chunk,
                exact_score=match.score,
                exact_match_identifiers=match.identifiers,
                fused_score=match.score,
                rerank_score=match.score,
                final_score=match.score,
                match_terms=match.identifiers,
            )
        # 旧资料码没有出现在现行正文中时，用关系映射给替代资料一个低于直接命中的分数；这样直接
        # 命中的现行资料永远优先，且替代资料仍能在没有正文词法重叠时进入候选窗口。
        for chunk in replacement_chunks:
            if chunk.chunk_id in scored:
                continue
            scored[chunk.chunk_id] = RagScoredChunk(
                chunk=chunk,
                exact_score=0.82,
                exact_match_identifiers=tuple(
                    f"replacement:{identifier}" for identifier in query_identifiers
                ),
                fused_score=0.82,
                rerank_score=0.82,
                final_score=0.82,
            )
        return tuple(
            sorted(
                scored.values(),
                key=lambda item: (
                    item.exact_score,
                    _scope_specificity(item.chunk, query),
                    item.chunk.document_id,
                ),
                reverse=True,
            )
        )

    def _vector_rank(
        self,
        chunks: tuple[RagChunk, ...],
        query: RagQuery,
        *,
        retrieval_question: str,
        lexical_ranked: tuple[RagScoredChunk, ...],
        persistent_vector_scores: Mapping[str, float] | None,
    ) -> tuple[RagScoredChunk, ...]:
        """计算向量召回排序。

        如果没有 embedding provider，直接返回空结果。这样本地默认仍能靠 lexical RAG 工作；生产配置
        embedding 后，向量通道会自然参与融合。

        需要特别注意：向量检索不是“相关性证明”，它更像“在向量空间里找最近的候选”。
        对真实 pgvector、Milvus、OpenSearch Vector 这类后端来说，如果不加阈值，完全不相关的问题
        也可能拿到最近邻。这里在进入 RRF 融合前就做最小分数过滤，让“无证据”可以安全地
        fail-closed，而不是因为向量通道返回了一个低质量近邻就继续生成答案。
        """

        if str(query.retrieval_mode).lower() == "lexical":
            return ()
        if persistent_vector_scores is not None:
            scored = tuple(
                RagScoredChunk(chunk=chunk, vector_score=float(persistent_vector_scores[chunk.chunk_id]))
                for chunk in chunks
                if chunk.chunk_id in persistent_vector_scores
                and math.isfinite(float(persistent_vector_scores[chunk.chunk_id]))
                and float(persistent_vector_scores[chunk.chunk_id])
                >= self._settings.minimum_vector_score
            )
            return tuple(sorted(scored, key=lambda item: item.vector_score, reverse=True))
        if self._embedding_provider is None:
            return ()
        query_embedding = validate_embedding_vector(
            self._embedding_provider.embed_text(
                retrieval_question[:4000],
                sensitivity_level=query.sensitivity_level,
            )
        )
        if len(chunks) >= max(1, int(self._settings.hierarchical_vector_minimum_chunks)):
            return self._hierarchical_vector_rank(
                chunks,
                query_embedding=query_embedding,
                lexical_ranked=lexical_ranked,
            )
        self._prime_chunk_embedding_cache(chunks)
        scored: list[RagScoredChunk] = []
        for chunk in chunks:
            chunk_embedding = self._chunk_embedding(chunk)
            if len(chunk_embedding) != len(query_embedding):
                raise ValueError("RAG query 与 chunk 的 Embedding 维度不一致。")
            vector_score = cosine_similarity(query_embedding, chunk_embedding)
            if vector_score >= self._settings.minimum_vector_score:
                scored.append(RagScoredChunk(chunk=chunk, vector_score=vector_score))
        scored.sort(key=lambda item: item.vector_score, reverse=True)
        return tuple(scored)

    def _hierarchical_vector_rank(
        self,
        chunks: tuple[RagChunk, ...],
        *,
        query_embedding: tuple[float, ...],
        lexical_ranked: tuple[RagScoredChunk, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """对高密度语料执行父级路由，再把少量真实 chunk 送入融合与 Reranker。

        14.9 万个 chunk 如果在每次内存评测启动时全部调用远程 Embedding，不仅费用和耗时失控，也与
        生产 pgvector 的 ANN 查询方式不一致。本方法按“同一文档内相邻 chunk”构建路由摘要：摘要保留
        标题、标签、稳定资料码和每个子 chunk 的短片段，BGE-M3 先从这些摘要中选出少量相关分组；随后
        每组只选择词法最相关的子 chunk，若没有词项命中则选择该组首块，再交给真实 Reranker 阅读正文。

        这是一种可解释的 parent-child retrieval。路由摘要只用于候选发现，不进入引用、生成上下文、
        审计来源或权限判断；范围过滤与项目覆盖已经在它之前完成。持久化 pgvector 已经能直接返回 chunk
        近邻分数，因此不会进入本分支。
        """

        groups = self._build_vector_routing_groups(chunks)
        self._prime_routing_embedding_cache(groups)
        ranked_groups: list[tuple[float, _RagVectorRoutingGroup]] = []
        for group in groups:
            embedding = self._routing_embedding_cache[group.cache_key]
            if len(embedding) != len(query_embedding):
                raise ValueError("RAG 路由摘要与 query 的 Embedding 维度不一致。")
            score = cosine_similarity(query_embedding, embedding)
            if score >= self._settings.minimum_vector_score:
                ranked_groups.append((score, group))
        ranked_groups.sort(key=lambda item: item[0], reverse=True)
        route_limit = max(1, min(int(self._settings.vector_routing_candidate_limit), 200))
        groups_per_document = max(
            1,
            min(int(self._settings.vector_routing_groups_per_document), 8),
        )
        chunks_per_group = max(1, min(int(self._settings.vector_routing_chunks_per_group), 8))
        lexical_by_chunk = {item.chunk.chunk_id: item for item in lexical_ranked}
        selected: dict[str, RagScoredChunk] = {}
        selected_group_count_by_document: dict[str, int] = {}
        selected_group_count = 0
        for vector_score, group in ranked_groups:
            document_id = group.chunks[0].document_id
            document_group_count = selected_group_count_by_document.get(document_id, 0)
            if document_group_count >= groups_per_document:
                continue
            selected_group_count_by_document[document_id] = document_group_count + 1
            selected_group_count += 1
            ordered_chunks = sorted(
                group.chunks,
                key=lambda chunk: (
                    lexical_by_chunk.get(chunk.chunk_id, RagScoredChunk(chunk=chunk)).lexical_score,
                    -chunk.chunk_index,
                ),
                reverse=True,
            )
            for chunk in ordered_chunks[:chunks_per_group]:
                existing = selected.get(chunk.chunk_id)
                if existing is None or vector_score > existing.vector_score:
                    selected[chunk.chunk_id] = RagScoredChunk(
                        chunk=chunk,
                        vector_score=vector_score,
                    )
            if selected_group_count >= route_limit:
                break
        return tuple(
            sorted(selected.values(), key=lambda item: item.vector_score, reverse=True)
        )

    def _build_vector_routing_groups(
        self,
        chunks: tuple[RagChunk, ...],
    ) -> tuple[_RagVectorRoutingGroup, ...]:
        """按文档边界构建路由分组，绝不把不同来源拼成一个父级摘要。"""

        chunks_by_document: dict[str, list[RagChunk]] = {}
        for chunk in chunks:
            chunks_by_document.setdefault(chunk.document_id, []).append(chunk)
        group_size = max(2, min(int(self._settings.vector_routing_group_size), 64))
        groups: list[_RagVectorRoutingGroup] = []
        for document_chunks in chunks_by_document.values():
            ordered = tuple(sorted(document_chunks, key=lambda item: item.chunk_index))
            for start in range(0, len(ordered), group_size):
                child_chunks = ordered[start : start + group_size]
                embedding_text = _routing_group_embedding_text(child_chunks)
                sensitivity_level = _most_restrictive_sensitivity_level(child_chunks)
                groups.append(
                    _RagVectorRoutingGroup(
                        cache_key=_classified_embedding_cache_key(
                            embedding_text,
                            sensitivity_level,
                        ),
                        embedding_text=embedding_text,
                        sensitivity_level=sensitivity_level,
                        chunks=child_chunks,
                    )
                )
        return tuple(groups)

    def _prime_routing_embedding_cache(
        self,
        groups: tuple[_RagVectorRoutingGroup, ...],
    ) -> None:
        """批量生成尚未缓存的父级路由向量，并复用不同治理范围的同内容摘要。"""

        if self._embedding_provider is None:
            return
        missing = {
            group.cache_key: group
            for group in groups
            if group.cache_key not in self._routing_embedding_cache
        }
        if not missing:
            return
        keys = tuple(missing)
        texts = tuple(missing[key].embedding_text for key in keys)
        sensitivity_levels = tuple(missing[key].sensitivity_level for key in keys)
        embed_texts = getattr(self._embedding_provider, "embed_texts", None)
        raw_embeddings = (
            tuple(embed_texts(texts, sensitivity_levels=sensitivity_levels))
            if callable(embed_texts)
            else tuple(
                self._embedding_provider.embed_text(
                    text,
                    sensitivity_level=sensitivity_level,
                )
                for text, sensitivity_level in zip(texts, sensitivity_levels)
            )
        )
        if len(raw_embeddings) != len(keys):
            raise ValueError("RAG 路由批量 Embedding 响应数量与摘要数量不一致。")
        embeddings = tuple(validate_embedding_vector(value) for value in raw_embeddings)
        if len({len(embedding) for embedding in embeddings}) != 1:
            raise ValueError("RAG 路由批量 Embedding 响应维度不一致。")
        for cache_key, embedding in zip(keys, embeddings):
            self._routing_embedding_cache[cache_key] = embedding

    def _chunk_embedding(self, chunk: RagChunk) -> tuple[float, ...]:
        """按规范化语义文本读取或生成 chunk embedding。

        chunkId 包含治理范围，适合持久化幂等主键，却不适合作为模型费用缓存键。同一份全局模板复制到
        多个项目后，范围已经由数据库谓词保证，正文中的范围标签不应让 Embedding 重算。因此缓存键改为
        “标题 + 正文 + 标签”删除范围套话后的 SHA-256；向量仍只会附着到当前已授权 chunk，不会跨范围
        返回文档。
        """

        cache_key = _chunk_embedding_cache_key(chunk)
        cached = self._chunk_embedding_cache.get(cache_key)
        if cached is not None:
            return cached
        if self._embedding_provider is None:
            return ()
        text = _chunk_embedding_text(chunk)
        embedding = validate_embedding_vector(
            self._embedding_provider.embed_text(
                text,
                sensitivity_level=chunk.sensitivity_level,
            )
        )
        self._chunk_embedding_cache[cache_key] = embedding
        return embedding

    def _prime_chunk_embedding_cache(self, chunks: tuple[RagChunk, ...]) -> None:
        """批量生成当前候选窗口中尚未缓存的 chunk 向量。

        黄金评测和本地内存检索会在第一次查询时扫描同一范围内的多份文档。如果逐 chunk 请求远端模型，
        网络往返、限流风险和费用都会被候选数量放大。新 Provider 使用 `embed_texts` 的数组输入；只实现
        单条接口的旧测试替身仍可兼容。响应数量和向量维度在写入缓存前统一校验，避免错位缓存污染后续用例。
        """

        if self._embedding_provider is None:
            return
        missing_by_key: dict[str, RagChunk] = {}
        for chunk in chunks:
            cache_key = _chunk_embedding_cache_key(chunk)
            if cache_key not in self._chunk_embedding_cache:
                # 同一批次内也可能出现多个范围副本。只向 Provider 发送一份规范化文本，响应后所有副本
                # 都会通过同一个摘要键命中缓存。
                missing_by_key.setdefault(cache_key, chunk)
        missing = tuple(missing_by_key.items())
        if not missing:
            return
        texts = tuple(_chunk_embedding_text(chunk) for _, chunk in missing)
        sensitivity_levels = tuple(chunk.sensitivity_level for _, chunk in missing)
        embed_texts = getattr(self._embedding_provider, "embed_texts", None)
        raw_embeddings = (
            tuple(embed_texts(texts, sensitivity_levels=sensitivity_levels))
            if callable(embed_texts)
            else tuple(
                self._embedding_provider.embed_text(
                    text,
                    sensitivity_level=sensitivity_level,
                )
                for text, sensitivity_level in zip(texts, sensitivity_levels)
            )
        )
        if len(raw_embeddings) != len(missing):
            raise ValueError("RAG 批量 Embedding 响应数量与 chunk 数量不一致。")
        embeddings = tuple(validate_embedding_vector(value) for value in raw_embeddings)
        if len({len(embedding) for embedding in embeddings}) != 1:
            raise ValueError("RAG 批量 Embedding 响应维度不一致。")
        for (cache_key, _), embedding in zip(missing, embeddings):
            self._chunk_embedding_cache[cache_key] = embedding

    def _fuse(
        self,
        lexical_ranked: tuple[RagScoredChunk, ...],
        vector_ranked: tuple[RagScoredChunk, ...],
        exact_ranked: tuple[RagScoredChunk, ...] = (),
        *,
        query: RagQuery | None = None,
    ) -> tuple[RagScoredChunk, ...]:
        """融合 lexical/vector 两路候选。

        这里用简化 RRF：排名越靠前，`1 / (k + rank)` 越大。RRF 的好处是不同通道分数尺度不一致时仍能
        稳定融合，比如词项分和余弦相似度不在同一个数值范围。
        """

        # RRF 的排名单位必须和候选多样性目标一致。若直接按 chunk 排名，一份 3000 行的 Excel
        # 会在另一份文档出现之前占据 3000 个位置，导致“文档是否相关”被“文档有多长”替代。
        # 每一路只保留每份文档最靠前的代表块后再计算 RRF；词法代表块和向量代表块可以不同，
        # 因而不会丢失某一路发现的正文证据。
        lexical_for_fusion = _collapse_ranked_by_document(lexical_ranked)
        lexical_facet_representatives = self._facet_lexical_representatives(
            lexical_ranked,
            query,
        )
        lexical_candidates_by_chunk = {
            item.chunk.chunk_id: item
            for item in (*lexical_for_fusion, *lexical_facet_representatives)
        }
        lexical_candidates = tuple(lexical_candidates_by_chunk.values())
        vector_for_fusion = _collapse_ranked_by_document(vector_ranked)
        exact_for_fusion = _collapse_ranked_by_document(exact_ranked)
        by_chunk: dict[str, RagScoredChunk] = {}
        lexical_document_rank = {
            (item.chunk.document_id or item.chunk.source_uri): index + 1
            for index, item in enumerate(lexical_for_fusion)
        }
        # facet 补充块继承所属文档的 RRF 名次。这样它可以进入 Reranker，但不会把一份长文档的
        # 多个块伪装成多个独立文档、进而挤压其他来源的排名。
        lexical_rank = {
            item.chunk.chunk_id: lexical_document_rank.get(
                item.chunk.document_id or item.chunk.source_uri,
                len(lexical_document_rank) + 1,
            )
            for item in lexical_candidates
        }
        vector_rank = {
            item.chunk.chunk_id: index + 1
            for index, item in enumerate(vector_for_fusion)
        }
        exact_rank = {
            item.chunk.chunk_id: index + 1
            for index, item in enumerate(exact_for_fusion)
        }
        lexical_by_chunk = {item.chunk.chunk_id: item for item in lexical_candidates}
        vector_by_chunk = {item.chunk.chunk_id: item for item in vector_for_fusion}
        exact_by_chunk = {item.chunk.chunk_id: item for item in exact_for_fusion}
        for item in lexical_candidates + vector_for_fusion + exact_for_fusion:
            existing = by_chunk.get(item.chunk.chunk_id)
            if existing is None:
                existing = RagScoredChunk(
                    chunk=item.chunk,
                    match_terms=item.match_terms,
                    exact_score=item.exact_score,
                    exact_match_identifiers=item.exact_match_identifiers,
                )
            lexical_item = lexical_by_chunk.get(item.chunk.chunk_id)
            vector_item = vector_by_chunk.get(item.chunk.chunk_id)
            exact_item = exact_by_chunk.get(item.chunk.chunk_id)
            lexical_score = lexical_item.lexical_score if lexical_item is not None else 0.0
            # 余弦相似度允许负值。不能和默认值 0 做 max，否则“向量通道参与但结果为负”的事实会在
            # 融合摘要里消失，调试阈值时也无法区分未计算向量与确实不相似。
            vector_score = vector_item.vector_score if vector_item is not None else 0.0
            exact_score = exact_item.exact_score if exact_item is not None else existing.exact_score
            exact_identifiers = (
                exact_item.exact_match_identifiers
                if exact_item is not None
                else existing.exact_match_identifiers
            )
            match_terms = existing.match_terms or item.match_terms
            l_rank = lexical_rank.get(item.chunk.chunk_id)
            v_rank = vector_rank.get(item.chunk.chunk_id)
            fused_score = 0.0
            if l_rank is not None:
                fused_score += self._settings.lexical_weight / (self._settings.rrf_k + l_rank)
            if v_rank is not None:
                fused_score += self._settings.vector_weight / (self._settings.rrf_k + v_rank)
            e_rank = exact_rank.get(item.chunk.chunk_id)
            if e_rank is not None:
                fused_score += self._settings.exact_match_weight / (self._settings.rrf_k + e_rank)
            by_chunk[item.chunk.chunk_id] = RagScoredChunk(
                chunk=item.chunk,
                lexical_score=lexical_score,
                vector_score=vector_score,
                fused_score=fused_score,
                rerank_score=fused_score,
                final_score=fused_score,
                match_terms=match_terms,
                exact_score=exact_score,
                exact_match_identifiers=exact_identifiers,
            )
        return tuple(sorted(by_chunk.values(), key=lambda item: item.fused_score, reverse=True))

    def _bounded_candidate_window(
        self,
        fused: tuple[RagScoredChunk, ...],
        *,
        lexical_ranked: tuple[RagScoredChunk, ...],
        vector_ranked: tuple[RagScoredChunk, ...],
        exact_ranked: tuple[RagScoredChunk, ...] = (),
        query: RagQuery | None = None,
        candidate_limit: int,
    ) -> tuple[RagScoredChunk, ...]:
        """为 hybrid 窗口保留两路候选配额，避免某一路在 Reranker 前被截断。

        RRF 解决的是不同分数尺度的排序融合，不自动保证候选覆盖。词法通道在高密度中文资料中常命中大量
        通用词，哪怕真正的语义文档在向量路由中排第 5，也可能被 32 个词法 chunk 挤出最终窗口。这里先
        从 lexical/vector 各取一份有界配额，再用 fused 排名填满剩余位置。

        候选窗口还会限制同一文档可进入 Reranker 的 chunk 数。长手册、日志和 Excel 提取文本往往拥有
        数千个相似分块；如果只按 chunk 排名，重排模型看到的可能只是同一文档的重复内容，而不是多个可
        比较的证据来源。限额只作用于 Reranker 前的候选多样性，不删除知识库内容，也不改变原始引用。
        当可见文档不足时，窗口可以小于 ``candidateLimit``，不会用重复分块人为凑满候选数。

        整个过程不会扩大 ``candidateLimit``，也不会绕过范围、时效和来源过滤；作用只是确保专用
        Reranker 真的有机会比较两路证据。
        """

        limit = max(1, min(int(candidate_limit), 200))
        per_document_limit = max(
            1,
            min(int(self._settings.max_candidate_chunks_per_document), 20),
        )
        fused_by_chunk = {item.chunk.chunk_id: item for item in fused}
        selected_ids: dict[str, None] = {}
        selected_count_by_document: dict[str, int] = {}

        def add_candidates(
            ranked: tuple[RagScoredChunk, ...],
            *,
            quota: int,
        ) -> None:
            """从一路排名中按文档限额选取候选，跳过重复项后继续向后扫描。"""

            added = 0
            for item in ranked:
                if added >= quota or len(selected_ids) >= limit:
                    break
                chunk_id = item.chunk.chunk_id
                if chunk_id in selected_ids or chunk_id not in fused_by_chunk:
                    continue
                document_id = item.chunk.document_id or item.chunk.source_uri or chunk_id
                document_count = selected_count_by_document.get(document_id, 0)
                if document_count >= per_document_limit:
                    continue
                selected_ids[chunk_id] = None
                selected_count_by_document[document_id] = document_count + 1
                added += 1

        # 精确标识符是用户明确给出的资料定位意图，必须先为它保留窗口名额；否则大量通用词法结果
        # 仍可能在进入 Reranker 前把真正目标截掉。没有精确命中时该配额为零，不影响普通语义查询。
        if exact_ranked:
            add_candidates(exact_ranked, quota=min(limit, max(1, len(exact_ranked))))

        # 自然语言问题通常不会直接写出资料标题，例如用户问“某类错误如何处理”，而不是说“请查错误码目录”。
        # 这类问题仍可能已经被词法或向量通道召回，但会因为长文档重复分块而在窗口边界被截掉。
        # 在这里为职责分明确的候选保留一个 chunk，既不重新扫描知识库，也不凭 category 生成证据，
        # 只是确保后续 Embedding/Reranker 有机会真正比较这份已经通过范围过滤的资料。
        if query is not None and len(selected_ids) < limit:
            responsibility_candidates = _responsibility_routing_reserves(fused, query)
            add_candidates(
                responsibility_candidates,
                quota=min(limit - len(selected_ids), len(responsibility_candidates)),
            )

        # 多证据问题需要有限的运行时 fan-out：每个实质 facet 至少获得一个候选机会，再由后续
        # Reranker、证据门禁和 MMR 决定是否真的引用。仅按 fused 总分取前 N 条时，长手册、成功案例
        # 或泛化配置资料很容易把第三个互补职责（例如 recovery_events、connector_inventory）挤到
        # Reranker 的外发窗口之外。这里不扫描知识库、不开新权限，只在已经通过范围和召回通道进入
        # ``fused`` 的候选中按 category/sourceType/格式职责做配额，因此仍是有界、可审计的动态 fan-out。
        if query is not None and rag_query_requests_multiple_evidence(query.question):
            facet_candidates = _facet_routing_reserves(fused, query)
            if facet_candidates:
                add_candidates(
                    facet_candidates,
                    quota=min(limit - len(selected_ids), len(facet_candidates)),
                )

        if lexical_ranked and vector_ranked and limit > 1:
            vector_ratio = max(
                0.1,
                min(0.9, float(self._settings.hybrid_vector_candidate_ratio)),
            )
            vector_quota = max(1, min(limit - 1, round(limit * vector_ratio)))
            lexical_quota = limit - vector_quota
            add_candidates(lexical_ranked, quota=lexical_quota)
            add_candidates(vector_ranked, quota=vector_quota)

        # 单通道查询会直接从 fused 补位；混合查询也通过同一路径补足尚未占用的窗口。
        add_candidates(fused, quota=limit - len(selected_ids))
        selected = tuple(
            fused_by_chunk[chunk_id]
            for chunk_id in selected_ids
            if chunk_id in fused_by_chunk
        )
        return tuple(sorted(selected, key=lambda item: item.fused_score, reverse=True))

    def _select_with_mmr(self, candidates: tuple[RagScoredChunk, ...], *, top_k: int) -> tuple[RagScoredChunk, ...]:
        """用 MMR 从候选中选择相关且多样的证据。"""

        selected: list[RagScoredChunk] = []
        remaining = list(candidates)
        while remaining and len(selected) < top_k:
            best_index = 0
            best_score = -10**9
            for index, candidate in enumerate(remaining):
                penalty = _max_similarity_to_selected(candidate, selected)
                mmr_score = self._settings.mmr_lambda * candidate.final_score - (1 - self._settings.mmr_lambda) * penalty
                if mmr_score > best_score:
                    best_index = index
                    best_score = mmr_score
            chosen = remaining.pop(best_index)
            diversity_penalty = _max_similarity_to_selected(chosen, selected)
            selected.append(
                RagScoredChunk(
                    chunk=chosen.chunk,
                    lexical_score=chosen.lexical_score,
                    vector_score=chosen.vector_score,
                    fused_score=chosen.fused_score,
                    rerank_score=chosen.rerank_score,
                    diversity_penalty=diversity_penalty,
                    # final_score 继续表示 reranker/融合相关性；MMR 只影响选择顺序，不能把不可比较的
                    # 多样性合成分冒充模型可信度写入引用和审计记录。
                    final_score=chosen.final_score,
                    match_terms=chosen.match_terms,
                    exact_score=chosen.exact_score,
                    exact_match_identifiers=chosen.exact_match_identifiers,
                )
            )
        return tuple(selected)


def _collapse_ranked_by_document(
    ranked: tuple[RagScoredChunk, ...],
) -> tuple[RagScoredChunk, ...]:
    """把一路 chunk 排名压成文档排名，同时保留每份文档的最佳原始分块。"""

    seen_documents: set[str] = set()
    collapsed: list[RagScoredChunk] = []
    for item in ranked:
        document_id = item.chunk.document_id or item.chunk.source_uri or item.chunk.chunk_id
        if document_id in seen_documents:
            continue
        seen_documents.add(document_id)
        collapsed.append(item)
    return tuple(collapsed)


def _strongest_exact_matches(
    ranked: tuple[RagScoredChunk, ...],
) -> tuple[RagScoredChunk, ...]:
    """精确搜索只保留与最佳稳定标识符匹配强度接近的文档。

    综合接口手册可能在示例中引用其他资料码；这种正文交叉引用应参与普通混合检索，却不能在
    ``exact_search`` 中与拥有该 ``artifactCode/retrievalAnchor`` 的主资料并列。保留最佳分数 95% 以内
    的候选，可以兼容同一稳定码确实对应多个分片的场景，同时裁掉仅在正文中顺带提及的资料。
    """

    if not ranked:
        return ()
    best_score = max(float(item.exact_score) for item in ranked)
    minimum_score = best_score * 0.95
    return tuple(item for item in ranked if float(item.exact_score) >= minimum_score)


def _facet_routing_reserves(
    candidates: tuple[RagScoredChunk, ...],
    query: RagQuery,
) -> tuple[RagScoredChunk, ...]:
    """为多证据查询生成有界的 facet 候选保留集合。

    这是召回窗口层的轻量 fan-out，不是最终证据选择器。它把整句问题拆出的每个实质子问题分别与
    已召回候选的职责元数据、facet 词法分和已有向量/融合分比较，并优先保留一个职责最匹配的候选。
    例如“接口追踪、失败分片 replay、最终验证”可以分别保留 API、Recovery 事件和状态快照资料，
    即使第三份资料的整句 RRF 名次较低。候选仍必须已经经过知识库范围、来源状态和检索通道过滤；本
    函数绝不从知识库重新搜索，也不把 category 当作单独的证据证明。

    为避免一份长文档占满窗口，每个 facet 首选尚未保留的 document；如果同一文档确实覆盖多个 facet，
    则允许复用它。最多保留八个 facet，和文本层的有界变体数量保持一致。
    """

    if not candidates:
        return ()
    variants = tuple(
        normalized
        for variant in split_rag_query_variants(query.question)[1:]
        if rag_query_variant_has_substantive_signal(variant)
        if (normalized := normalize_rag_query_facet(variant))
    )
    if not variants:
        return ()

    responsibility_threshold = 0.85
    per_facet: list[tuple[RagScoredChunk, ...]] = []
    for variant in variants[:8]:
        scored: list[tuple[tuple[float, ...], RagScoredChunk]] = []
        for candidate in candidates:
            lexical = lexical_score_for_query(variant, candidate.chunk)
            intent = rag_query_document_intent_score(
                variant,
                candidate.chunk,
                context_text=query.question,
            )
            # category 先验必须伴随正文词法或现有向量/融合信号。这样“只因为 category=incident”
            # 不会把无关事故记录塞进窗口，但自然语言 facet 仍能找到职责明确的结构化资料。
            if (
                intent < responsibility_threshold
                and lexical.score < 0.30
                and candidate.vector_score < 0.65
            ):
                continue
            scored.append(
                (
                    (
                        float(intent >= responsibility_threshold),
                        min(2.5, max(0.0, float(intent))),
                        min(1.0, max(0.0, float(lexical.score))),
                        min(1.0, max(0.0, float(candidate.vector_score))),
                        max(0.0, float(candidate.fused_score)),
                    ),
                    candidate,
                )
            )
        scored.sort(key=lambda item: item[0], reverse=True)
        per_facet.append(tuple(item[1] for item in scored[:8]))

    selected: list[RagScoredChunk] = []
    selected_chunk_ids: set[str] = set()
    selected_document_ids: set[str] = set()
    for facet_candidates in per_facet:
        if not facet_candidates:
            continue
        chosen = next(
            (
                candidate
                for candidate in facet_candidates
                if candidate.chunk.document_id not in selected_document_ids
            ),
            facet_candidates[0],
        )
        if chosen.chunk.chunk_id in selected_chunk_ids:
            continue
        selected.append(chosen)
        selected_chunk_ids.add(chosen.chunk.chunk_id)
        selected_document_ids.add(chosen.chunk.document_id)

    return tuple(selected[:8])


def _responsibility_routing_reserves(
    candidates: tuple[RagScoredChunk, ...],
    query: RagQuery,
    *,
    max_documents: int = 8,
    responsibility_threshold: float = 0.85,
) -> tuple[RagScoredChunk, ...]:
    """为职责高度匹配的自然语言候选保留一个文档代表。

    多证据查询已经有 ``_facet_routing_reserves``，但单一职责问题同样需要保护。例如问题只问“这个
    错误码怎么处理”，目标错误码目录可能排在第 12 名，而前 11 名是同一份长运维手册的重复分块。
    如果远端 Reranker 只接收前 16 个 chunk，它根本没有机会判断目标资料是否最相关。

    本方法只处理已经通过租户、项目、逻辑空间、来源状态和召回通道过滤的候选，并且要求候选保留
    至少一个真实检索信号。职责分数只用于排序和保留，不会凭 category 把知识库之外的资料加入结果。
    每份文档最多保留一个代表，最终数量有界，避免长文档再次占满外发窗口。
    """

    if not candidates or query.retrieval_mode == "exact_search":
        return ()

    # ``fused`` 在内存评测中可能包含十几万 chunk；职责保护是候选窗口优化，不能为了挑保留名额又
    # 把整库逐条扫描一遍。融合结果已经按分数降序排列，因此扫描“候选上限的有界倍数”即可覆盖
    # 远端窗口附近和刚被长文档重复块挤压的目标。精确标识符仍由上层 exact 通道单独保护。
    scan_limit = max(64, max(1, int(query.candidate_limit)) * 4)
    bounded_candidates = candidates[: min(len(candidates), scan_limit)]

    # 同一文档的多个 chunk 只挑最适合当前问题的一个，保证保护名额代表不同的证据来源。
    best_by_document: dict[str, tuple[tuple[float, ...], RagScoredChunk]] = {}
    threshold = max(0.0, min(2.5, float(responsibility_threshold)))
    for candidate in bounded_candidates:
        intent_score = rag_query_document_intent_score(
            query.question,
            candidate.chunk,
            context_text=query.question,
        )
        # category 先验必须与候选原本的召回事实同时存在。fused_score 对已经进入融合结果的候选
        # 通常大于零；其它分数条件兼容数据库适配器返回的仅单通道候选。
        has_retrieval_signal = (
            candidate.exact_score > 0.0
            or candidate.lexical_score > 0.0
            or candidate.vector_score >= 0.45
            or candidate.fused_score > 0.0
        )
        if not has_retrieval_signal or intent_score < threshold:
            continue
        document_id = candidate.chunk.document_id or candidate.chunk.source_uri or candidate.chunk.chunk_id
        ranking = (
            min(2.5, max(0.0, float(intent_score))),
            min(1.0, max(0.0, float(candidate.lexical_score))),
            min(1.0, max(0.0, float(candidate.vector_score))),
            max(0.0, float(candidate.fused_score)),
            max(0.0, float(candidate.final_score)),
        )
        previous = best_by_document.get(document_id)
        if previous is None or ranking > previous[0]:
            best_by_document[document_id] = (ranking, candidate)

    ranked = sorted(best_by_document.values(), key=lambda item: item[0], reverse=True)
    return tuple(candidate for _, candidate in ranked[: max(1, min(int(max_documents), 16))])


def _chunk_visible(chunk: RagChunk, query: RagQuery) -> bool:
    """判断 chunk 是否同时满足授权范围、来源类型和证据时效要求。

    ``SUPERSEDED`` 文档不会参与普通问答，也不会被发送给远程 reranker；只有来源类型明确且唯一为
    ``git_history`` 的审计追溯才放行。未知状态保持兼容可见，摄取治理可以另行要求生产文档必须声明状态。
    """

    scope_and_source_visible = _chunk_matches_scope_and_source(chunk, query)
    evidence_status = str((chunk.metadata or {}).get("evidenceStatus") or "").strip().lower()
    source_status = str((chunk.metadata or {}).get("sourceStatus") or "").strip().upper()
    superseded = evidence_status == "superseded" or source_status == "SUPERSEDED"
    evidence_visible = not superseded or rag_query_explicitly_requests_history(query)
    return (
        scope_and_source_visible
        and evidence_visible
    )


def _chunk_matches_scope_and_source(chunk: RagChunk, query: RagQuery) -> bool:
    """检查不会读取证据正文的硬范围与来源类型边界。

    该判断既用于普通候选，也用于只读取元数据的历史替代关系扫描。单独抽出它是因为历史 chunk
    按设计处于 ``SUPERSEDED`` 状态，不能直接复用会排除过期资料的 ``_chunk_visible``；但租户、
    项目、逻辑空间和调用方声明的来源类型仍必须与持久化知识库保持一致。
    """

    source_types = {
        str(value).strip().lower()
        for value in (query.source_types or ())
        if str(value).strip()
    }
    return (
        chunk.tenant_id in {"*", query.tenant_id}
        and chunk.project_id in {"*", query.project_id}
        and chunk.workspace_key in {"*", query.workspace_key}
        and (not source_types or chunk.source_type.value in source_types)
    )


def _prefer_most_specific_scope_chunks(
    chunks: tuple[RagChunk, ...],
    query: RagQuery,
) -> tuple[RagChunk, ...]:
    """让项目资料覆盖同一逻辑资料的全局基线。

    DataSmart 的全局知识用于提供产品默认规则和通用 Runbook；项目可以发布自己的现行版本。两份资料都
    通过硬范围过滤并不代表它们应该同时进入排序：当项目版和全局版描述同一个 ``artifactCode`` 时，
    全局版只是回退值。如果继续把两份正文发送给 Reranker，既浪费外部模型预算，也可能把默认参数混入
    项目事实，黄金评测中的禁止文档合同也会失败。

    覆盖键优先使用显式的 ``logicalDocumentKey``，兼容当前评测与摄取合同中的 ``artifactCode``。没有
    声明覆盖键的普通文档互不替代，仍按原逻辑全部参与召回。对于同一覆盖键，本方法比较 tenant、project
    和 workspace 三个维度的具体程度，只保留与当前查询匹配且具体程度最高的版本。因此：

    - 项目版存在时，项目版覆盖全局版；
    - 只有全局版时，全局版仍可正常回退；
    - 将来增加“租户级、项目通配”的中间层资料时，也会自然优先于全局版、低于项目精确版。

    这里仍不是权限判断。所有输入 chunk 必须已经通过 ``_chunk_visible`` 或持久化知识库的范围谓词；本
    方法只在已授权候选内部解决配置继承与证据优先级。
    """

    if not chunks:
        return ()
    maximum_specificity: dict[str, int] = {}
    for chunk in chunks:
        overlay_key = _logical_document_overlay_key(chunk)
        if overlay_key is None:
            continue
        specificity = _scope_specificity(chunk, query)
        maximum_specificity[overlay_key] = max(
            specificity,
            maximum_specificity.get(overlay_key, -1),
        )
    if not maximum_specificity:
        return chunks
    return tuple(
        chunk
        for chunk in chunks
        if (
            (overlay_key := _logical_document_overlay_key(chunk)) is None
            or _scope_specificity(chunk, query) == maximum_specificity[overlay_key]
        )
    )


def _logical_document_overlay_key(chunk: RagChunk) -> str | None:
    """读取显式逻辑文档键；不根据标题猜测，避免误覆盖名称相似但职责不同的资料。"""

    metadata = chunk.metadata or {}
    raw_key = metadata.get("logicalDocumentKey") or metadata.get("artifactCode")
    normalized = str(raw_key or "").strip()
    if not normalized:
        return None
    namespace = str(metadata.get("overlayNamespace") or "default").strip() or "default"
    return f"{namespace}:{normalized}"


def _scope_specificity(chunk: RagChunk, query: RagQuery) -> int:
    """计算一个已授权 chunk 相对当前查询的范围具体程度。"""

    return sum(
        1
        for chunk_value, query_value in (
            (chunk.tenant_id, query.tenant_id),
            (chunk.project_id, query.project_id),
            (chunk.workspace_key, query.workspace_key),
        )
        if chunk_value != "*" and chunk_value == query_value
    )


def _chunk_embedding_text(chunk: RagChunk) -> str:
    """构造不重复携带授权范围套话的有界 Embedding 文本。"""

    raw_text = f"{chunk.title}\n{chunk.text}\n{' '.join(chunk.tags)}"
    return normalize_rag_retrieval_question(raw_text)[:4000]


def _chunk_embedding_cache_key(chunk: RagChunk) -> str:
    """用“正文 + 分级”生成缓存键，既复用范围副本，也阻止跨分级复用。

    同一正文在 ``internal`` 与 ``restricted`` 下可能采用不同外发策略。若缓存键只包含正文，先以低分级
    生成的向量会让后续高分级请求完全绕过 Provider 门禁。把分级放进摘要输入后，缓存仍不保存正文，且
    同级别的租户/项目范围副本仍可正常复用。
    """

    normalized_text = _chunk_embedding_text(chunk)
    return _classified_embedding_cache_key(normalized_text, chunk.sensitivity_level)


def _classified_embedding_cache_key(text: str, sensitivity_level: str) -> str:
    """为模型输入和稳定分级生成不含原文的 SHA-256 缓存键。"""

    normalized_level = _normalized_embedding_sensitivity_level(sensitivity_level)
    return hashlib.sha256(f"{normalized_level}\0{text}".encode("utf-8")).hexdigest()


def _normalized_embedding_sensitivity_level(value: object) -> str:
    """把缓存与 Provider 使用的分级键统一为小写短横线形式。"""

    normalized = str(value or "internal").strip().lower().replace("_", "-")
    return normalized or "internal"


def _most_restrictive_sensitivity_level(chunks: tuple[RagChunk, ...]) -> str:
    """返回父级路由摘要中最严格的子块分级。

    当前切块器会让同一文档的所有 chunk 继承相同分级，但这里仍按最严格值计算，防止未来合并型摄取器
    引入混合分级后错误地按首块降级。未知级别保留原值并排在已知级别之后，外部 Provider 若未显式批准
    就会 fail-closed，而不是被映射成较低级别。
    """

    if not chunks:
        return "internal"
    ranks = {
        "public": 0,
        "internal": 1,
        "confidential": 2,
        "restricted": 3,
    }
    levels = tuple(
        _normalized_embedding_sensitivity_level(chunk.sensitivity_level)
        for chunk in chunks
    )
    return max(levels, key=lambda level: (ranks.get(level, 4), level))


def _routing_group_embedding_text(chunks: tuple[RagChunk, ...]) -> str:
    """把同一文档的一组子 chunk 压缩成可用于父级向量路由的文本。

    每个子块只贡献有界片段，因此一个超长接口手册或事故台账不会因为前几页很长而遮住后续记录。标题、
    标签和稳定资料码提供文档级语义，逐块片段提供记录级语义；最终再次删除范围套话，使同内容的范围副本
    可以复用向量。
    """

    if not chunks:
        return ""
    first = chunks[0]
    metadata = first.metadata or {}
    header = (
        f"标题：{first.title}\n"
        f"标签：{' '.join(first.tags)}\n"
        f"资料码：{metadata.get('artifactCode') or metadata.get('logicalDocumentKey') or ''}\n"
        f"来源类型：{first.source_type.value}"
    )
    available_chars = max(800, 4000 - len(header))
    per_chunk_chars = max(80, min(180, available_chars // max(len(chunks), 1)))
    child_summaries = "\n".join(
        f"片段 {offset + 1}：{chunk.text[:per_chunk_chars]}"
        for offset, chunk in enumerate(chunks)
    )
    return normalize_rag_retrieval_question(f"{header}\n{child_summaries}")[:4000]


def _max_similarity_to_selected(candidate: RagScoredChunk, selected: list[RagScoredChunk]) -> float:
    """计算候选与已选证据的最大 token 相似度。"""

    if not selected:
        return 0.0
    candidate_tokens = tokenize_for_rag(candidate.chunk.text)
    return max(jaccard_similarity(candidate_tokens, tokenize_for_rag(item.chunk.text)) for item in selected)


__all__ = [
    "InMemoryRagKnowledgeBase",
    "RagHybridRetriever",
    "RagHybridRetrieverSettings",
    "RagKnowledgeBase",
]
