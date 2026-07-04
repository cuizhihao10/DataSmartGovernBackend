"""RAG 领域模型。

本文件只定义 Retrieval-Augmented Generation 的稳定数据契约，不绑定 LangChain、LlamaIndex、Chroma、
pgvector 或某个模型 SDK。这样做有两个好处：

1. 面试或架构评审时可以清楚解释每个阶段的数据如何流动，而不是只说“调用某框架的 retriever”；
2. 后续把内存知识库替换为 PostgreSQL/pgvector、Neo4j GraphRAG 或企业搜索服务时，上层 API 与测试
   可以保持不变。

RAG 和 Agent Memory 的边界：
- Agent Memory 偏用户画像、任务历史、操作经验和会话上下文；
- RAG Knowledge 偏企业文档、产品说明、数据治理规则、字段口径、运维手册和可引用证据。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


RAG_PIPELINE_SCHEMA_VERSION = "datasmart.rag-pipeline.v1"


class RagChunkSourceType(str, Enum):
    """RAG 知识来源类型。

    类型不会直接决定权限，但会影响后续索引策略和解释方式：
    - `DOCUMENT`：普通说明文档、PRD、运维手册；
    - `RULE`：质量规则、权限策略、合规条款；
    - `METADATA`：表结构、字段口径、血缘或资产说明；
    - `RUNBOOK`：故障演练、恢复手册、部署指南；
    - `MEMORY_EXPORT`：从 Agent Memory 受控导出的低敏知识快照。
    """

    DOCUMENT = "document"
    RULE = "rule"
    METADATA = "metadata"
    RUNBOOK = "runbook"
    MEMORY_EXPORT = "memory_export"


@dataclass(frozen=True)
class RagDocument:
    """进入 RAG 知识库的原始文档。

    字段说明：
    - `document_id`：稳定文档 ID，后续用于增量更新、删除、审计和引用；
    - `title/content/source_uri`：标题、正文和来源，`source_uri` 可以是文档路径、MinIO 对象、数据库记录或 URL；
    - `tenant_id/project_id/workspace_key`：范围隔离字段，检索时必须先过滤再排序；
    - `source_type/tags`：用于过滤、召回加权和诊断；
    - `sensitivity_level`：当前只作为低敏提示，生产环境应与权限中心/数据分级分类联动。
    """

    document_id: str
    title: str
    content: str
    source_uri: str
    tenant_id: str = "*"
    project_id: str = "*"
    workspace_key: str = "*"
    source_type: RagChunkSourceType = RagChunkSourceType.DOCUMENT
    tags: tuple[str, ...] = ()
    sensitivity_level: str = "internal"
    metadata: dict[str, Any] = field(default_factory=dict)
    enabled: bool = True


@dataclass(frozen=True)
class RagChunk:
    """文档切块后的最小召回单元。

    RAG 一般不会直接把整篇文档放入模型，因为长文会带来三类问题：
    - 召回粒度太粗，相关片段被大量无关文本稀释；
    - prompt 过长，推理成本和延迟上升；
    - 引用证据不清楚，回答难以追溯。

    因此本项目把文档切成 chunk，再以 chunk 为单位做召回、重排、压缩和引用。
    """

    chunk_id: str
    document_id: str
    chunk_index: int
    title: str
    text: str
    source_uri: str
    tenant_id: str
    project_id: str
    workspace_key: str
    source_type: RagChunkSourceType
    tags: tuple[str, ...] = ()
    sensitivity_level: str = "internal"
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class RagQuery:
    """一次 RAG 查询请求。

    字段说明：
    - `question`：用户问题或 Agent 子问题；
    - `tenant_id/project_id/workspace_key`：硬隔离边界；
    - `top_k/candidate_limit`：最终证据数量与候选窗口大小；
    - `max_context_chars`：压缩后允许进入模型的证据上下文字符预算；
    - `generate_answer`：是否调用模型生成答案。检索评测、压测或只看证据时可以关闭；
    - `trace_id/session_id`：链路追踪和缓存隔离字段，不写入知识库。
    """

    tenant_id: str
    project_id: str
    actor_id: str
    question: str
    workspace_key: str = "*"
    top_k: int = 5
    candidate_limit: int = 32
    max_context_chars: int = 4000
    generate_answer: bool = True
    trace_id: str | None = None
    session_id: str | None = None


@dataclass(frozen=True)
class RagScoredChunk:
    """带各阶段分数的候选 chunk。

    面试深挖 RAG 时常会问“你怎么知道召回结果为什么排在前面”。这里不只保留一个总分，而是保存：
    - `lexical_score`：关键词/BM25 风格分数；
    - `vector_score`：embedding 余弦相似度分数；
    - `fused_score`：多路召回融合分数；
    - `rerank_score`：重排后的最终相关性；
    - `diversity_penalty`：MMR 去冗余时的相似惩罚。
    """

    chunk: RagChunk
    lexical_score: float = 0.0
    vector_score: float = 0.0
    fused_score: float = 0.0
    rerank_score: float = 0.0
    diversity_penalty: float = 0.0
    final_score: float = 0.0
    match_terms: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """输出低敏候选摘要，不返回完整 chunk 文本。"""

        return {
            "chunkId": self.chunk.chunk_id,
            "documentId": self.chunk.document_id,
            "title": self.chunk.title,
            "sourceUri": self.chunk.source_uri,
            "sourceType": self.chunk.source_type.value,
            "lexicalScore": round(self.lexical_score, 6),
            "vectorScore": round(self.vector_score, 6),
            "fusedScore": round(self.fused_score, 6),
            "rerankScore": round(self.rerank_score, 6),
            "diversityPenalty": round(self.diversity_penalty, 6),
            "finalScore": round(self.final_score, 6),
            "matchTerms": self.match_terms,
        }


@dataclass(frozen=True)
class RagCitation:
    """进入答案的证据引用。"""

    citation_id: str
    document_id: str
    chunk_id: str
    title: str
    source_uri: str
    snippet: str
    final_score: float

    def to_summary(self) -> dict[str, Any]:
        """输出可给 API 调用方展示的引用。"""

        return {
            "citationId": self.citation_id,
            "documentId": self.document_id,
            "chunkId": self.chunk_id,
            "title": self.title,
            "sourceUri": self.source_uri,
            "snippet": self.snippet,
            "finalScore": round(self.final_score, 6),
        }


@dataclass(frozen=True)
class RagPipelineResult:
    """RAG 管线最终结果。"""

    answer: str
    citations: tuple[RagCitation, ...]
    selected_chunks: tuple[RagScoredChunk, ...]
    compressed_context: str
    retrieval_summary: dict[str, Any]
    model_summary: dict[str, Any] = field(default_factory=dict)
    generated: bool = False

    def to_summary(self) -> dict[str, Any]:
        """输出 API 响应结构。

        这里会返回 `compressedContext`，因为它是已经经过 RAG compressor 控制的低敏证据上下文，便于学习、
        调试和面试讲解；生产中如需进一步收紧，可通过 gateway/权限策略隐藏该字段，仅保留 citations。
        """

        return {
            "schemaVersion": RAG_PIPELINE_SCHEMA_VERSION,
            "answer": self.answer,
            "generated": self.generated,
            "citations": tuple(citation.to_summary() for citation in self.citations),
            "selectedChunks": tuple(chunk.to_summary() for chunk in self.selected_chunks),
            "compressedContext": self.compressed_context,
            "retrievalSummary": dict(self.retrieval_summary),
            "modelSummary": dict(self.model_summary),
            "payloadPolicy": "RAG_COMPRESSED_EVIDENCE_WITH_CITATIONS_NO_RAW_FULL_DOCUMENT",
        }


__all__ = [
    "RAG_PIPELINE_SCHEMA_VERSION",
    "RagChunk",
    "RagChunkSourceType",
    "RagCitation",
    "RagDocument",
    "RagPipelineResult",
    "RagQuery",
    "RagScoredChunk",
]
