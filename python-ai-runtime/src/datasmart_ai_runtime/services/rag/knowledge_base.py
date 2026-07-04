"""RAG 知识库与混合召回。

本模块实现一个可替换的内存知识库和混合召回器。生产环境最终会把知识库替换成 PostgreSQL/pgvector、
Neo4j、MinIO 对象索引或企业搜索服务；但内存实现仍然很有价值：

- 单元测试不依赖外部中间件；
- 可以清楚展示 RAG 的基础算法；
- 可以作为 API smoke 与本地学习入口；
- 后续真实适配器必须遵守同一范围隔离和低敏摘要契约。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Protocol

from datasmart_ai_runtime.services.memory.memory_embedding_provider import AgentMemoryEmbeddingProvider
from datasmart_ai_runtime.services.rag.models import RagChunk, RagDocument, RagQuery, RagScoredChunk
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
        """执行一次混合召回并返回 topK 候选。"""

        visible_chunks = self._knowledge_base.chunks_for_query(query)
        query_terms = tokenize_for_rag(query.question)
        lexical_ranked = self._lexical_rank(visible_chunks, query_terms)
        vector_ranked = self._vector_rank(visible_chunks, query)
        fused = self._fuse(lexical_ranked, vector_ranked)
        candidate_window = fused[: max(5, min(query.candidate_limit, 200))]
        return self._select_with_mmr(candidate_window, top_k=max(1, min(query.top_k, 20)))

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

    def _vector_rank(self, chunks: tuple[RagChunk, ...], query: RagQuery) -> tuple[RagScoredChunk, ...]:
        """计算向量召回排序。

        如果没有 embedding provider，直接返回空结果。这样本地默认仍能靠 lexical RAG 工作；生产配置
        embedding 后，向量通道会自然参与融合。

        需要特别注意：向量检索不是“相关性证明”，它更像“在向量空间里找最近的候选”。
        对真实 pgvector、Milvus、OpenSearch Vector 这类后端来说，如果不加阈值，完全不相关的问题
        也可能拿到最近邻。这里在进入 RRF 融合前就做最小分数过滤，让“无证据”可以安全地
        fail-closed，而不是因为向量通道返回了一个低质量近邻就继续生成答案。
        """

        if self._embedding_provider is None:
            return ()
        query_embedding = self._embedding_provider.embed_text(query.question[:4000])
        scored: list[RagScoredChunk] = []
        for chunk in chunks:
            chunk_embedding = self._chunk_embedding(chunk)
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
        embedding = self._embedding_provider.embed_text(text)
        self._chunk_embedding_cache[chunk.chunk_id] = embedding
        return embedding

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
                mmr_score = self._settings.mmr_lambda * candidate.fused_score - (1 - self._settings.mmr_lambda) * penalty
                if mmr_score > best_score:
                    best_index = index
                    best_score = mmr_score
            chosen = remaining.pop(best_index)
            selected.append(
                RagScoredChunk(
                    chunk=chosen.chunk,
                    lexical_score=chosen.lexical_score,
                    vector_score=chosen.vector_score,
                    fused_score=chosen.fused_score,
                    rerank_score=chosen.rerank_score,
                    diversity_penalty=_max_similarity_to_selected(chosen, selected[:-1]),
                    final_score=best_score,
                    match_terms=chosen.match_terms,
                )
            )
        return tuple(selected)


def _chunk_visible(chunk: RagChunk, query: RagQuery) -> bool:
    """判断 chunk 是否对当前查询可见。"""

    tenant_visible = chunk.tenant_id in {"*", query.tenant_id}
    project_visible = chunk.project_id in {"*", query.project_id}
    workspace_visible = chunk.workspace_key in {"*", query.workspace_key}
    return tenant_visible and project_visible and workspace_visible


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
