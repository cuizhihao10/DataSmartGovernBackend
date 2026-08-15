"""RAG 知识库与混合召回。

本模块实现一个可替换的内存知识库和混合召回器。生产环境最终会把知识库替换成 PostgreSQL/pgvector、
Neo4j、MinIO 对象索引或企业搜索服务；但内存实现仍然很有价值：

- 单元测试不依赖外部中间件；
- 可以清楚展示 RAG 的基础算法；
- 可以作为 API smoke 与本地学习入口；
- 后续真实适配器必须遵守同一范围隔离和低敏摘要契约。
"""

from __future__ import annotations

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
    chunk_document,
    cosine_similarity,
    jaccard_similarity,
    lexical_score,
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
    minimum_vector_score: float = 0.65


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
        query_terms = tokenize_for_rag(query.question)
        lexical_ranked = self._lexical_rank(visible_chunks, query_terms)
        vector_ranked = self._vector_rank(
            visible_chunks,
            query,
            persistent_vector_scores=persistent_vector_scores,
        )
        fused = self._fuse(lexical_ranked, vector_ranked)
        return fused[: max(5, min(query.candidate_limit, 200))]

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
            "lexicalWeight": self._settings.lexical_weight,
            "vectorWeight": self._settings.vector_weight,
            "rrfK": self._settings.rrf_k,
            "mmrLambda": self._settings.mmr_lambda,
            "minimumVectorScore": self._settings.minimum_vector_score,
            "payloadPolicy": "RAG_RETRIEVER_DIAGNOSTICS_NO_QUERY_OR_DOCUMENT_BODY",
        }
        return base

    def _lexical_rank(
        self,
        chunks: tuple[RagChunk, ...],
        query_terms: tuple[str, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """计算词项召回排序。"""

        scored: list[RagScoredChunk] = []
        for chunk in chunks:
            score = lexical_score(query_terms, chunk)
            if score.score > 0:
                scored.append(
                    RagScoredChunk(
                        chunk=chunk,
                        lexical_score=score.score,
                        match_terms=score.match_terms,
                    )
                )
        scored.sort(key=lambda item: item.lexical_score, reverse=True)
        return tuple(scored)

    def _vector_rank(
        self,
        chunks: tuple[RagChunk, ...],
        query: RagQuery,
        *,
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
            self._embedding_provider.embed_text(query.question[:4000])
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

    def _chunk_embedding(self, chunk: RagChunk) -> tuple[float, ...]:
        """读取或生成 chunk embedding。"""

        cached = self._chunk_embedding_cache.get(chunk.chunk_id)
        if cached is not None:
            return cached
        if self._embedding_provider is None:
            return ()
        text = f"{chunk.title}\n{chunk.text}\n{' '.join(chunk.tags)}"[:4000]
        embedding = validate_embedding_vector(self._embedding_provider.embed_text(text))
        self._chunk_embedding_cache[chunk.chunk_id] = embedding
        return embedding

    def _prime_chunk_embedding_cache(self, chunks: tuple[RagChunk, ...]) -> None:
        """批量生成当前候选窗口中尚未缓存的 chunk 向量。

        黄金评测和本地内存检索会在第一次查询时扫描同一范围内的多份文档。如果逐 chunk 请求远端模型，
        网络往返、限流风险和费用都会被候选数量放大。新 Provider 使用 `embed_texts` 的数组输入；只实现
        单条接口的旧测试替身仍可兼容。响应数量和向量维度在写入缓存前统一校验，避免错位缓存污染后续用例。
        """

        if self._embedding_provider is None:
            return
        missing_by_id = {
            chunk.chunk_id: chunk
            for chunk in chunks
            if chunk.chunk_id not in self._chunk_embedding_cache
        }
        missing = tuple(missing_by_id.values())
        if not missing:
            return
        texts = tuple(
            f"{chunk.title}\n{chunk.text}\n{' '.join(chunk.tags)}"[:4000]
            for chunk in missing
        )
        embed_texts = getattr(self._embedding_provider, "embed_texts", None)
        raw_embeddings = (
            tuple(embed_texts(texts))
            if callable(embed_texts)
            else tuple(self._embedding_provider.embed_text(text) for text in texts)
        )
        if len(raw_embeddings) != len(missing):
            raise ValueError("RAG 批量 Embedding 响应数量与 chunk 数量不一致。")
        embeddings = tuple(validate_embedding_vector(value) for value in raw_embeddings)
        if len({len(embedding) for embedding in embeddings}) != 1:
            raise ValueError("RAG 批量 Embedding 响应维度不一致。")
        for chunk, embedding in zip(missing, embeddings):
            self._chunk_embedding_cache[chunk.chunk_id] = embedding

    def _fuse(
        self,
        lexical_ranked: tuple[RagScoredChunk, ...],
        vector_ranked: tuple[RagScoredChunk, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """融合 lexical/vector 两路候选。

        这里用简化 RRF：排名越靠前，`1 / (k + rank)` 越大。RRF 的好处是不同通道分数尺度不一致时仍能
        稳定融合，比如词项分和余弦相似度不在同一个数值范围。
        """

        by_chunk: dict[str, RagScoredChunk] = {}
        lexical_rank = {item.chunk.chunk_id: index + 1 for index, item in enumerate(lexical_ranked)}
        vector_rank = {item.chunk.chunk_id: index + 1 for index, item in enumerate(vector_ranked)}
        for item in lexical_ranked + vector_ranked:
            existing = by_chunk.get(item.chunk.chunk_id)
            if existing is None:
                existing = RagScoredChunk(chunk=item.chunk, match_terms=item.match_terms)
            lexical_score = max(existing.lexical_score, item.lexical_score)
            vector_score = max(existing.vector_score, item.vector_score)
            match_terms = existing.match_terms or item.match_terms
            l_rank = lexical_rank.get(item.chunk.chunk_id)
            v_rank = vector_rank.get(item.chunk.chunk_id)
            fused_score = 0.0
            if l_rank is not None:
                fused_score += self._settings.lexical_weight / (self._settings.rrf_k + l_rank)
            if v_rank is not None:
                fused_score += self._settings.vector_weight / (self._settings.rrf_k + v_rank)
            by_chunk[item.chunk.chunk_id] = RagScoredChunk(
                chunk=item.chunk,
                lexical_score=lexical_score,
                vector_score=vector_score,
                fused_score=fused_score,
                rerank_score=fused_score,
                final_score=fused_score,
                match_terms=match_terms,
            )
        return tuple(sorted(by_chunk.values(), key=lambda item: item.fused_score, reverse=True))

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
                )
            )
        return tuple(selected)


def _chunk_visible(chunk: RagChunk, query: RagQuery) -> bool:
    """判断 chunk 是否同时满足授权范围、来源类型和证据时效要求。

    ``SUPERSEDED`` 文档不会参与普通问答，也不会被发送给远程 reranker；只有来源类型明确且唯一为
    ``git_history`` 的审计追溯才放行。未知状态保持兼容可见，摄取治理可以另行要求生产文档必须声明状态。
    """

    tenant_visible = chunk.tenant_id in {"*", query.tenant_id}
    project_visible = chunk.project_id in {"*", query.project_id}
    workspace_visible = chunk.workspace_key in {"*", query.workspace_key}
    source_types = {
        str(value).strip().lower()
        for value in (query.source_types or ())
        if str(value).strip()
    }
    source_visible = not source_types or chunk.source_type.value in source_types
    evidence_status = str((chunk.metadata or {}).get("evidenceStatus") or "").strip().lower()
    source_status = str((chunk.metadata or {}).get("sourceStatus") or "").strip().upper()
    superseded = evidence_status == "superseded" or source_status == "SUPERSEDED"
    evidence_visible = not superseded or rag_query_explicitly_requests_history(query)
    return (
        tenant_visible
        and project_visible
        and workspace_visible
        and source_visible
        and evidence_visible
    )


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
