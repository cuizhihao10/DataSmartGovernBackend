"""RAG 服务包。

该包承载 DataSmart 的检索增强生成能力。当前 V1 重点不是追求复杂框架功能，而是把 RAG 的核心机制
清楚、可测、可替换地落地：切块、范围过滤、混合召回、RRF 融合、MMR 去冗余、重排、压缩、引用和生成。
"""

from datasmart_ai_runtime.services.rag.components import (
    build_default_governance_rag_pipeline,
    default_governance_rag_documents,
)
from datasmart_ai_runtime.services.rag.knowledge_base import (
    InMemoryRagKnowledgeBase,
    RagHybridRetriever,
    RagHybridRetrieverSettings,
    RagKnowledgeBase,
)
from datasmart_ai_runtime.services.rag.langgraph_checkpoint import (
    LANGGRAPH_RAG_GRAPH_NAME,
    LANGGRAPH_RAG_GRAPH_VERSION,
    record_rag_pipeline_checkpoints,
)
from datasmart_ai_runtime.services.rag.models import (
    RAG_PIPELINE_SCHEMA_VERSION,
    RagChunk,
    RagChunkSourceType,
    RagCitation,
    RagDocument,
    RagPipelineResult,
    RagQuery,
    RagScoredChunk,
)
from datasmart_ai_runtime.services.rag.pipeline import (
    RagContextCompressor,
    RagHeuristicReranker,
    RagPipeline,
    RagPipelineSettings,
)
from datasmart_ai_runtime.services.rag.text import (
    chunk_document,
    compress_chunk_text,
    cosine_similarity,
    jaccard_similarity,
    lexical_score,
    tokenize_for_rag,
)

__all__ = [
    "RAG_PIPELINE_SCHEMA_VERSION",
    "InMemoryRagKnowledgeBase",
    "LANGGRAPH_RAG_GRAPH_NAME",
    "LANGGRAPH_RAG_GRAPH_VERSION",
    "RagChunk",
    "RagChunkSourceType",
    "RagCitation",
    "RagContextCompressor",
    "RagDocument",
    "RagHeuristicReranker",
    "RagHybridRetriever",
    "RagHybridRetrieverSettings",
    "RagKnowledgeBase",
    "RagPipeline",
    "RagPipelineResult",
    "RagPipelineSettings",
    "RagQuery",
    "RagScoredChunk",
    "build_default_governance_rag_pipeline",
    "chunk_document",
    "compress_chunk_text",
    "cosine_similarity",
    "default_governance_rag_documents",
    "jaccard_similarity",
    "lexical_score",
    "record_rag_pipeline_checkpoints",
    "tokenize_for_rag",
]
