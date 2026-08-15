"""RAG 知识库存储配置与 PostgreSQL/pgvector 适配器。

RAG 管线只依赖精简的 ``RagKnowledgeBase`` 协议，不直接依赖具体数据库客户端。本模块是该协议的
装配入口，负责以下存储选择：

* ``in-memory`` 只能作为显式选择的学习或测试实现；
* ``postgresql`` 提供持久化、先范围过滤的词法检索；
* ``pgvector`` 在此基础上增加持久向量和数据库侧近邻候选查询；
* 生产存储未配置或不可用时返回 fail-closed 知识库，绝不静默降级到进程内存实现。

SQL 合同以常量形式暴露给迁移和集成验证使用。Runtime 启动时不会隐式创建或修改生产 schema。
"""

from __future__ import annotations

import json
import math
import os
import re
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from threading import RLock
from typing import Any, Callable, Iterable, Mapping
from urllib import parse

from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    AgentMemoryEmbeddingProvider,
    DeterministicHashEmbeddingProvider,
    build_memory_embedding_provider,
    memory_embedding_provider_settings_from_env,
    validate_embedding_vector,
)
from datasmart_ai_runtime.services.memory.memory_sql_connection import (
    build_postgresql_connection,
    mask_postgresql_dsn,
)
from datasmart_ai_runtime.services.rag.knowledge_base import (
    RagKnowledgeBase,
    RagKnowledgeCandidateSet,
)
from datasmart_ai_runtime.services.rag.models import (
    RagChunk,
    RagChunkSourceType,
    RagDocument,
    RagQuery,
    rag_query_explicitly_requests_history,
)
from datasmart_ai_runtime.services.rag.text import chunk_document


RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_VERSION = "datasmart.rag.postgresql-knowledge.v1"
RAG_POSTGRESQL_KNOWLEDGE_TABLE = "rag_knowledge_chunk"
RAG_KNOWLEDGE_DIAGNOSTICS_PAYLOAD_POLICY = "RAG_KNOWLEDGE_DIAGNOSTICS_NO_DOCUMENT_BODY"

# 这是可重复执行的迁移合同，不表示运行时可以在客户数据库中随意变更结构。表按 chunk 有意做了适度
# 反范式设计，使一次查询可以先执行全部范围谓词，再直接取得引用字段，无需先把文档目录加载进内存。
# pgvector 扩展固定安装在 public；显式限定类型可兼容只开放 ai_memory 的受限 search_path。
RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL = """
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE SCHEMA IF NOT EXISTS ai_memory;

CREATE TABLE IF NOT EXISTS ai_memory.rag_knowledge_chunk (
    chunk_id VARCHAR(256) PRIMARY KEY,
    document_id VARCHAR(256) NOT NULL,
    chunk_index INTEGER NOT NULL,
    title TEXT NOT NULL,
    chunk_text TEXT NOT NULL,
    source_uri TEXT NOT NULL,
    tenant_id VARCHAR(128) NOT NULL DEFAULT '*',
    project_id VARCHAR(128) NOT NULL DEFAULT '*',
    workspace_key VARCHAR(255) NOT NULL DEFAULT '*',
    source_type VARCHAR(32) NOT NULL DEFAULT 'document',
    tags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    sensitivity_level VARCHAR(32) NOT NULL DEFAULT 'internal',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    embedding_model VARCHAR(128),
    embedding_dimension INTEGER,
    embedding public.vector,
    content_fingerprint VARCHAR(128),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    content_search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple', COALESCE(title, '') || ' ' || COALESCE(chunk_text, ''))
    ) STORED,
    CONSTRAINT ck_rag_knowledge_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_rag_knowledge_chunk_embedding_dimension CHECK (
        embedding_dimension IS NULL OR embedding_dimension > 0
    )
);

CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_scope
    ON ai_memory.rag_knowledge_chunk (tenant_id, project_id, workspace_key, enabled, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_document
    ON ai_memory.rag_knowledge_chunk (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_scoped_document
    ON ai_memory.rag_knowledge_chunk (
        tenant_id, project_id, workspace_key, document_id, chunk_index
    );
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_search
    ON ai_memory.rag_knowledge_chunk USING GIN (content_search_vector);
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_embedding_model
    ON ai_memory.rag_knowledge_chunk (embedding_model, embedding_dimension, enabled);
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_bge_m3_hnsw
    ON ai_memory.rag_knowledge_chunk USING hnsw (
        (embedding::public.vector(1024)) public.vector_cosine_ops
    )
    WHERE enabled = TRUE
      AND embedding_model = 'BAAI/bge-m3'
      AND embedding_dimension = 1024;
""".strip()


_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,62}$")
_IN_MEMORY_ALLOWED_MODES = frozenset({"learning", "test"})
_IN_MEMORY_STORES = frozenset({"in-memory", "memory", "test-memory"})
_POSTGRES_STORES = frozenset({"postgres", "postgresql", "sql"})
_PGVECTOR_STORES = frozenset({"pgvector", "postgresql-pgvector", "postgres-pgvector"})

RagPostgresConnectionFactory = Callable[["RagKnowledgeBaseSettings"], Any]


class RagPersistenceConfigurationError(ValueError):
    """显式指定的 RAG 存储合同无效时抛出的配置异常。"""


@dataclass(frozen=True)
class RagKnowledgeBaseSettings:
    """RAG 知识库的运行时选择和边界参数。

    ``store_type`` 默认值刻意设为 ``unconfigured``，防止 API 装配入口误把进程内演示数据宣传为持久化
    生产知识库。学习和测试调用方必须显式选择 ``in-memory``。
    """

    runtime_mode: str = "production"
    store_type: str = "unconfigured"
    postgresql_dsn: str = ""
    schema_name: str = "ai_memory"
    table_name: str = RAG_POSTGRESQL_KNOWLEDGE_TABLE
    connect_timeout_seconds: int = 3
    fail_fast: bool = False
    schema_check_on_startup: bool = False
    candidate_limit: int = 200
    chunk_max_chars: int = 700
    chunk_overlap_chars: int = 120
    ingest_document_limit: int = 500
    ingest_chunk_limit: int = 2000
    embedding_model: str = ""
    embedding_dimensions: int | None = None

    @property
    def production_mode(self) -> bool:
        """判断当前运行模式是否必须拒绝进程内知识状态。"""

        # 未知模式按生产类环境处理。部署配置即使拼写错误，也不能意外开放进程内存储。
        return not self.in_memory_allowed

    @property
    def in_memory_allowed(self) -> bool:
        """判断当前模式是否允许显式使用内存知识库。"""

        return _normalize_runtime_mode(self.runtime_mode) in _IN_MEMORY_ALLOWED_MODES

    @property
    def vector_enabled(self) -> bool:
        """判断所选存储是否要求启用 pgvector 查询合同。"""

        return _normalize_store_type(self.store_type) == "pgvector"


@dataclass(frozen=True)
class RagKnowledgeBaseRuntime:
    """封装选中的知识库实例和可对外诊断的低敏启动事实。"""

    knowledge_base: RagKnowledgeBase
    settings: RagKnowledgeBaseSettings
    available: bool
    persistent: bool
    embedding_provider: AgentMemoryEmbeddingProvider | None = None
    reason_code: str | None = None


class UnavailableRagKnowledgeBase:
    """持久 RAG 不可用时使用的 fail-closed 知识库。

    返回空 chunk 可以保持现有管线合同不变：证据门禁会拒绝生成并返回标准无证据结果；诊断字段则负责
    区分“存储不可用”和“存储健康但没有匹配文档”。
    """

    def __init__(self, settings: RagKnowledgeBaseSettings, *, reason_code: str) -> None:
        self._settings = settings
        self._reason_code = reason_code

    def chunks_for_query(self, query: RagQuery) -> tuple[RagChunk, ...]:
        """配置存储不可用时永远不返回证据。"""

        return ()

    def diagnostics(self) -> dict[str, object]:
        """返回可操作且不含正文的不可用诊断。"""

        return {
            "implementation": type(self).__name__,
            "available": False,
            "persistent": False,
            "configured": self._settings.store_type != "unconfigured",
            "configuredType": _normalize_store_type(self._settings.store_type),
            "runtimeMode": _normalize_runtime_mode(self._settings.runtime_mode),
            "reasonCode": self._reason_code,
            "failClosed": True,
            "postgresqlDsn": mask_postgresql_dsn(self._settings.postgresql_dsn),
            "schema": self._settings.schema_name,
            "table": self._settings.table_name,
            "payloadPolicy": RAG_KNOWLEDGE_DIAGNOSTICS_PAYLOAD_POLICY,
            "notes": "RAG queries return no evidence until the configured durable knowledge store is available.",
        }


class PostgresRagKnowledgeBase:
    """支持可选 pgvector 检索路径的 PostgreSQL 知识库。

    该适配器只负责文档/chunk 持久化和数据库侧候选选择。每条查询 SQL 都包含范围谓词，并在向量排序前
    完成过滤；``RagHybridRetriever`` 继续在有界候选窗口上执行可解释的词法/向量融合和 MMR 选择。
    """

    _SELECT_COLUMNS = (
        "chunk_id, document_id, chunk_index, title, chunk_text, source_uri, "
        "tenant_id, project_id, workspace_key, source_type, tags_json, "
        "sensitivity_level, metadata_json"
    )

    def __init__(
        self,
        connection: Any,
        *,
        settings: RagKnowledgeBaseSettings,
        embedding_provider: AgentMemoryEmbeddingProvider | None = None,
    ) -> None:
        if connection is None:
            raise RagPersistenceConfigurationError("RAG PostgreSQL connection cannot be None.")
        self._connection = connection
        self._settings = _normalized_settings(settings)
        self._embedding_provider = embedding_provider
        self._table = _qualified_table(self._settings.schema_name, self._settings.table_name)
        self._lock = RLock()
        self._last_error_code: str | None = None
        self._last_error_type: str | None = None
        self._last_query_row_count = 0
        self._upserted_chunk_count = 0

    def chunks_for_query(self, query: RagQuery) -> tuple[RagChunk, ...]:
        """返回经过硬范围过滤的有界候选集。

        问题正文只作为本次请求的 Embedding 输入，不写入数据库或诊断状态。
        """

        return self.candidate_set_for_query(query).chunks

    def candidate_set_for_query(self, query: RagQuery) -> RagKnowledgeCandidateSet:
        """返回候选 chunk，并携带数据库已经计算完成的向量相似度。

        ``RagHybridRetriever`` 通过这个增强合同复用 pgvector 分数，避免查询向量和候选正文被第二次发送
        给 Embedding Provider。纯词法 PostgreSQL 也返回空的 ``vector_scores``，明确表示本次数据库查询
        已经决定不启用向量通道，而不是让外层猜测是否应重新计算。
        """

        limit = max(5, min(int(query.candidate_limit), self._settings.candidate_limit, 200))
        try:
            with self._lock:
                retrieval_mode = str(query.retrieval_mode or "hybrid").strip().lower()
                lexical_rows = () if retrieval_mode == "vector" else self._query_lexical_rows(query, limit)
                vector_rows = ()
                if retrieval_mode in {"hybrid", "vector"} and self._settings.vector_enabled:
                    if self._embedding_provider is None:
                        self._record_error("RAG_PGVECTOR_PROVIDER_UNAVAILABLE", RuntimeError)
                    else:
                        query_embedding = validate_embedding_vector(
                            self._embedding_provider.embed_text(str(query.question or "")[:4000])
                        )
                        vector_rows = self._query_vector_rows(query, query_embedding, limit)
                vector_scores = _vector_scores_for_rows(vector_rows)
                rows = _merge_rows(lexical_rows, vector_rows, limit)
                if not rows and retrieval_mode != "vector":
                    # 某些 PostgreSQL 文本配置不能正确切分本地语言，因此保留有界的同范围候选回退。
                    # 外层检索器仍会执行精确 token 评分，最终是否可引用仍由证据门禁决定。
                    rows = self._query_scope_rows(query, limit)
                chunks = tuple(self._row_to_chunk(row) for row in rows)
                self._last_query_row_count = len(chunks)
                self._clear_error()
                return RagKnowledgeCandidateSet(
                    chunks=chunks,
                    vector_scores={
                        chunk.chunk_id: vector_scores[chunk.chunk_id]
                        for chunk in chunks
                        if chunk.chunk_id in vector_scores
                    },
                )
        except Exception as exc:
            # RAG 基础设施故障不能变成内存回退。空候选让证据门禁收口请求，诊断字段仅暴露故障类别。
            with self._lock:
                self._rollback_safely()
                self._record_error(_query_error_code(self._settings), exc)
            return RagKnowledgeCandidateSet(chunks=(), vector_scores={})

    def upsert_documents(self, documents: Iterable[RagDocument]) -> int:
        """在有界批次内按文档原子替换持久化 chunk，并批量生成向量。

        摄取流程刻意分成三个清晰阶段：

        1. 逐份校验范围和稳定文档 ID，达到文档/chunk 上限立即拒绝，不消费无限 iterable；
        2. 在数据库锁外批量调用 Embedding Provider，在线查询不会被远程模型延迟阻塞；
        3. 在同一数据库事务中删除旧版本、写入新版本，任一步失败都会整体回滚。

        调用方只需要提供 `RagDocument`，不需要了解 SQL 标识符、JSONB 编码或 pgvector 字面量。
        """

        try:
            prepared_documents, all_chunks = self._prepare_documents_for_upsert(documents)
            # Provider 自己按 max_batch_size 切 HTTP 批次；单次摄取的总 chunk 数已由本地硬上限约束。
            embedding_records = self._embeddings_for_chunks(all_chunks)
        except RagPersistenceConfigurationError as exc:
            with self._lock:
                self._record_error("RAG_INGEST_PREPARATION_REJECTED", exc)
            raise
        except Exception as exc:
            with self._lock:
                self._record_error("RAG_EMBEDDING_BATCH_FAILED", exc)
            raise RagPersistenceConfigurationError(
                "RAG 文档切块或 Embedding 生成失败，尚未开始数据库事务。"
            ) from exc

        with self._lock:
            try:
                embedding_index = 0
                total_chunks = 0
                for document_id, tenant_id, project_id, workspace_key, chunks in prepared_documents:
                    self._execute(
                        f"DELETE FROM {self._table} "
                        "WHERE document_id = %s AND tenant_id = %s AND project_id = %s "
                        "AND workspace_key = %s",
                        (document_id, tenant_id, project_id, workspace_key),
                    )
                    for chunk in chunks:
                        embedding, embedding_model = embedding_records[embedding_index]
                        embedding_index += 1
                        now = datetime.now(timezone.utc)
                        self._execute(
                            f"""
                            INSERT INTO {self._table} (
                                chunk_id, document_id, chunk_index, title, chunk_text, source_uri,
                                tenant_id, project_id, workspace_key, source_type, tags_json,
                                sensitivity_level, metadata_json, enabled, embedding_model,
                                embedding_dimension, embedding, content_fingerprint, updated_at
                            ) VALUES (
                                %s, %s, %s, %s, %s, %s,
                                %s, %s, %s, %s, CAST(%s AS jsonb),
                                %s, CAST(%s AS jsonb), TRUE, %s,
                                %s, {"CAST(%s AS public.vector)" if self._settings.vector_enabled else "%s"}, %s, %s
                            )
                            ON CONFLICT (chunk_id) DO UPDATE SET
                                document_id = EXCLUDED.document_id,
                                chunk_index = EXCLUDED.chunk_index,
                                title = EXCLUDED.title,
                                chunk_text = EXCLUDED.chunk_text,
                                source_uri = EXCLUDED.source_uri,
                                tenant_id = EXCLUDED.tenant_id,
                                project_id = EXCLUDED.project_id,
                                workspace_key = EXCLUDED.workspace_key,
                                source_type = EXCLUDED.source_type,
                                tags_json = EXCLUDED.tags_json,
                                sensitivity_level = EXCLUDED.sensitivity_level,
                                metadata_json = EXCLUDED.metadata_json,
                                enabled = TRUE,
                                embedding_model = EXCLUDED.embedding_model,
                                embedding_dimension = EXCLUDED.embedding_dimension,
                                embedding = EXCLUDED.embedding,
                                content_fingerprint = EXCLUDED.content_fingerprint,
                                updated_at = EXCLUDED.updated_at
                            """,
                            (
                                chunk.chunk_id,
                                chunk.document_id,
                                chunk.chunk_index,
                                chunk.title,
                                chunk.text,
                                chunk.source_uri,
                                chunk.tenant_id,
                                chunk.project_id,
                                chunk.workspace_key,
                                chunk.source_type.value,
                                json.dumps(chunk.tags, ensure_ascii=False),
                                chunk.sensitivity_level,
                                json.dumps(chunk.metadata, ensure_ascii=False, sort_keys=True, default=str),
                                embedding_model,
                                len(embedding) if embedding else None,
                                _vector_literal(embedding) if embedding else None,
                                _content_fingerprint(chunk),
                                now,
                            ),
                        )
                        total_chunks += 1
                self._commit()
                self._upserted_chunk_count += total_chunks
                self._clear_error()
                return total_chunks
            except Exception as exc:
                self._rollback_safely()
                self._record_error("RAG_POSTGRESQL_WRITE_FAILED", exc)
                raise RagPersistenceConfigurationError(
                    "RAG 知识文档持久化失败，数据库事务已回滚。"
                ) from exc

    def _prepare_documents_for_upsert(
        self,
        documents: Iterable[RagDocument],
    ) -> tuple[
        tuple[tuple[str, str, str, str, tuple[RagChunk, ...]], ...],
        tuple[RagChunk, ...],
    ]:
        """逐份准备摄取数据，并在越过本地资源边界前停止消费输入。

        文档上限防止无限生成器或超大请求被整体物化；chunk 上限同时限制待生成向量、事务 SQL 数量和
        Python 浮点对象占用。超过边界应由调用方拆成多个显式批次，每个批次仍保持自己的事务原子性。
        """

        prepared_documents: list[tuple[str, str, str, str, tuple[RagChunk, ...]]] = []
        seen_document_scopes: set[tuple[str, str, str, str]] = set()
        all_chunks: list[RagChunk] = []
        for document in documents:
            if len(prepared_documents) >= self._settings.ingest_document_limit:
                raise RagPersistenceConfigurationError(
                    f"单次 RAG 摄取文档数量超过上限 {self._settings.ingest_document_limit}。"
                )
            document_id = _required_text(document.document_id, "document_id")
            tenant_id = _required_text(document.tenant_id, "tenant_id")
            project_id = _required_text(document.project_id, "project_id")
            workspace_key = _required_text(document.workspace_key, "workspace_key")
            scoped_identity = (tenant_id, project_id, workspace_key, document_id)
            if scoped_identity in seen_document_scopes:
                raise RagPersistenceConfigurationError(
                    "同一次 RAG 摄取不能重复提交相同范围和 documentId。"
                )
            seen_document_scopes.add(scoped_identity)
            chunks = (
                chunk_document(
                    document,
                    max_chars=self._settings.chunk_max_chars,
                    overlap_chars=self._settings.chunk_overlap_chars,
                )
                if document.enabled
                else ()
            )
            if len(all_chunks) + len(chunks) > self._settings.ingest_chunk_limit:
                raise RagPersistenceConfigurationError(
                    f"单次 RAG 摄取 chunk 数量超过上限 {self._settings.ingest_chunk_limit}。"
                )
            prepared_documents.append(
                (document_id, tenant_id, project_id, workspace_key, chunks)
            )
            all_chunks.extend(chunks)
        return tuple(prepared_documents), tuple(all_chunks)

    def delete_document(
        self,
        document_id: str,
        *,
        tenant_id: str,
        project_id: str,
        workspace_key: str,
    ) -> int:
        """按完整治理范围和文档 ID 删除 chunk，并以单个事务原子提交。

        ``document_id`` 只保证项目内部稳定，不能单独标识共享知识表中的一份文档。三个范围参数因此是
        必填关键字参数，调用方无法无意间使用旧的一参数形式跨租户删除同名知识。
        """

        normalized_id = _required_text(document_id, "document_id")
        normalized_tenant_id = _required_text(tenant_id, "tenant_id")
        normalized_project_id = _required_text(project_id, "project_id")
        normalized_workspace_key = _required_text(workspace_key, "workspace_key")
        with self._lock:
            try:
                cursor = self._execute(
                    f"DELETE FROM {self._table} "
                    "WHERE document_id = %s AND tenant_id = %s AND project_id = %s "
                    "AND workspace_key = %s",
                    (
                        normalized_id,
                        normalized_tenant_id,
                        normalized_project_id,
                        normalized_workspace_key,
                    ),
                )
                self._commit()
                self._clear_error()
                return max(0, int(getattr(cursor, "rowcount", 0) or 0))
            except Exception as exc:
                self._rollback_safely()
                self._record_error("RAG_POSTGRESQL_DELETE_FAILED", exc)
                raise RagPersistenceConfigurationError(
                    "RAG knowledge document deletion failed; the transaction was rolled back."
                ) from exc

    def validate_schema(self) -> bool:
        """探测目标表是否可用，不创建或修改 schema。"""

        with self._lock:
            try:
                self._execute(f"SELECT 1 FROM {self._table} LIMIT 0")
                self._clear_error()
                return True
            except Exception as exc:
                self._rollback_safely()
                self._record_error("RAG_POSTGRESQL_SCHEMA_UNAVAILABLE", exc)
                return False

    def diagnostics(self) -> dict[str, object]:
        """返回不含文档正文和查询正文的低敏存储事实。"""

        return {
            "implementation": type(self).__name__,
            "available": self._last_error_code is None,
            "persistent": True,
            "configured": True,
            "configuredType": _normalize_store_type(self._settings.store_type),
            "runtimeMode": _normalize_runtime_mode(self._settings.runtime_mode),
            "schemaVersion": RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_VERSION,
            "schema": self._settings.schema_name,
            "table": self._settings.table_name,
            "postgresqlDsn": mask_postgresql_dsn(self._settings.postgresql_dsn),
            "schemaCheckOnStartup": self._settings.schema_check_on_startup,
            "schemaContract": "migration-managed; runtime does not auto-create schema",
            "candidateLimit": self._settings.candidate_limit,
            "chunkMaxChars": self._settings.chunk_max_chars,
            "ingestDocumentLimit": self._settings.ingest_document_limit,
            "ingestChunkLimit": self._settings.ingest_chunk_limit,
            "embedding": {
                "enabled": self._settings.vector_enabled,
                "model": self._settings.embedding_model or None,
                "declaredDimensions": self._settings.embedding_dimensions,
                "providerConfigured": self._embedding_provider is not None,
                "minimumSimilarity": None,
            },
            "lastQueryRowCount": self._last_query_row_count,
            "upsertedChunkCount": self._upserted_chunk_count,
            "lastErrorCode": self._last_error_code,
            "lastErrorType": self._last_error_type,
            "payloadPolicy": RAG_KNOWLEDGE_DIAGNOSTICS_PAYLOAD_POLICY,
        }

    def close(self) -> None:
        """在宿主生命周期允许时关闭本实例持有的 DB-API 连接。"""

        with self._lock:
            close = getattr(self._connection, "close", None)
            if callable(close):
                close()

    def _query_scope_rows(self, query: RagQuery, limit: int) -> tuple[Any, ...]:
        predicates, params = _scope_predicates(query)
        cursor = self._execute(
            f"SELECT {self._SELECT_COLUMNS} FROM {self._table} "
            f"WHERE enabled = TRUE AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) "
            f"AND {' AND '.join(predicates)} "
            "ORDER BY updated_at DESC, chunk_index ASC, chunk_id ASC LIMIT %s",
            tuple(params + [limit]),
        )
        return tuple(cursor.fetchall())

    def _query_lexical_rows(self, query: RagQuery, limit: int) -> tuple[Any, ...]:
        """在 Python 重排前使用 PostgreSQL 全文检索取得候选。

        生成列 ``content_search_vector`` 的 GIN 索引负责持久化精确检索错误码、标识符和 Runbook 术语。
        范围谓词与全文条件位于同一条 SQL 中，词法命中不会在排序前扩大租户或项目可见范围。
        """

        predicates, params = _scope_predicates(query)
        params = [str(query.question or "")[:4000], *params, limit]
        cursor = self._execute(
            f"SELECT {self._SELECT_COLUMNS} "
            f"FROM {self._table} "
            "CROSS JOIN LATERAL ("
            "SELECT websearch_to_tsquery('simple', %s) AS tsq"
            ") query "
            f"WHERE enabled = TRUE AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) "
            f"AND {' AND '.join(predicates)} "
            "AND content_search_vector @@ query.tsq "
            "ORDER BY ts_rank_cd(content_search_vector, query.tsq) DESC, updated_at DESC, chunk_index ASC, chunk_id ASC "
            "LIMIT %s",
            tuple(params),
        )
        return tuple(cursor.fetchall())

    def _query_vector_rows(
        self,
        query: RagQuery,
        embedding: tuple[float, ...],
        limit: int,
    ) -> tuple[Any, ...]:
        """在硬范围和向量版本约束内查询最相近的持久化 chunk。

        DataSmart 的数据库连接会把 ``search_path`` 收紧到 ``ai_memory``，防止应用误访问其他 schema；
        pgvector 的类型和距离运算符则由扩展安装在 ``public``。PostgreSQL 对类型和运算符分别做名称解析，
        所以只写 ``public.vector`` 仍不足以找到 ``<=>``。这里同时使用 ``public.vector`` 和
        ``OPERATOR(public.<=>)``，在不放宽连接权限边界的前提下完成余弦距离排序。
        """

        predicates, scope_params = _scope_predicates(query)
        predicates.extend(["embedding IS NOT NULL"])
        if self._settings.embedding_model:
            predicates.append("embedding_model = %s")
            scope_params.append(self._settings.embedding_model)
        if self._settings.embedding_dimensions is not None:
            predicates.append("embedding_dimension = %s")
            scope_params.append(self._settings.embedding_dimensions)
        vector_literal = _vector_literal(embedding)
        bge_m3_indexed = (
            self._settings.embedding_model == "BAAI/bge-m3"
            and self._settings.embedding_dimensions == 1024
        )
        stored_vector = (
            "embedding::public.vector(1024)" if bge_m3_indexed else "embedding"
        )
        query_vector_type = "public.vector(1024)" if bge_m3_indexed else "public.vector"
        distance_expression = (
            f"({stored_vector}) OPERATOR(public.<=>) query_vector.value"
        )
        params = [vector_literal, *scope_params, limit]
        cursor = self._execute(
            f"SELECT {self._SELECT_COLUMNS}, 1 - ({distance_expression}) AS vector_score "
            f"FROM {self._table} "
            f"CROSS JOIN (SELECT CAST(%s AS {query_vector_type}) AS value) query_vector "
            f"WHERE enabled = TRUE AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) "
            f"AND {' AND '.join(predicates)} "
            f"ORDER BY {distance_expression}, chunk_id ASC LIMIT %s",
            tuple(params),
        )
        return tuple(cursor.fetchall())

    def _embeddings_for_chunks(
        self,
        chunks: tuple[RagChunk, ...],
    ) -> tuple[tuple[tuple[float, ...], str | None], ...]:
        """为一组 chunk 生成顺序稳定、维度一致的向量记录。

        新 Provider 应实现 `embed_texts` 以利用服务端数组输入。这里仍兼容只实现 `embed_text` 的旧测试
        替身或内部适配器，但生产配置不会因为兼容逻辑而静默使用伪向量。响应数量、有限浮点值和声明维度
        都在进入 SQL 参数前校验，因此不完整或错位的批量响应会使整次摄取回滚。
        """

        if not chunks:
            return ()
        if not self._settings.vector_enabled:
            return tuple(((), None) for _ in chunks)
        if self._embedding_provider is None:
            raise RagPersistenceConfigurationError("pgvector RAG requires an embedding provider.")

        texts = tuple(
            f"{chunk.title}\n{chunk.text}\n{' '.join(chunk.tags)}"[:4000]
            for chunk in chunks
        )
        embed_texts = getattr(self._embedding_provider, "embed_texts", None)
        raw_embeddings = (
            tuple(embed_texts(texts))
            if callable(embed_texts)
            else tuple(self._embedding_provider.embed_text(text) for text in texts)
        )
        if len(raw_embeddings) != len(chunks):
            raise RagPersistenceConfigurationError(
                "RAG embedding provider returned a different vector count than the chunk count."
            )

        embeddings = tuple(validate_embedding_vector(value) for value in raw_embeddings)
        declared = self._settings.embedding_dimensions
        if declared is not None and any(len(embedding) != declared for embedding in embeddings):
            raise RagPersistenceConfigurationError(
                "RAG embedding dimension does not match DATASMART_RAG_EMBEDDING_DIMENSIONS."
            )
        dimensions = {len(embedding) for embedding in embeddings}
        if len(dimensions) != 1:
            raise RagPersistenceConfigurationError(
                "RAG embedding provider returned vectors with inconsistent dimensions."
            )
        embedding_model = self._settings.embedding_model or "rag-embedding"
        return tuple((embedding, embedding_model) for embedding in embeddings)

    def _row_to_chunk(self, row: Any) -> RagChunk:
        source_type_raw = _row_value(row, "source_type", 9)
        try:
            source_type = source_type_raw if isinstance(source_type_raw, RagChunkSourceType) else RagChunkSourceType(str(source_type_raw))
        except ValueError as exc:
            raise ValueError("RAG row contains an unsupported source_type.") from exc
        tags = _json_sequence(_row_value(row, "tags_json", 10))
        metadata = _json_mapping(_row_value(row, "metadata_json", 12))
        return RagChunk(
            chunk_id=_required_text(_row_value(row, "chunk_id", 0), "chunk_id"),
            document_id=_required_text(_row_value(row, "document_id", 1), "document_id"),
            chunk_index=int(_row_value(row, "chunk_index", 2)),
            title=_required_text(_row_value(row, "title", 3), "title"),
            text=_required_text(_row_value(row, "chunk_text", 4), "chunk_text"),
            source_uri=_required_text(_row_value(row, "source_uri", 5), "source_uri"),
            tenant_id=_required_text(_row_value(row, "tenant_id", 6), "tenant_id"),
            project_id=_required_text(_row_value(row, "project_id", 7), "project_id"),
            workspace_key=_required_text(_row_value(row, "workspace_key", 8), "workspace_key"),
            source_type=source_type,
            tags=tuple(item for item in tags if item),
            sensitivity_level=str(_row_value(row, "sensitivity_level", 11) or "internal"),
            metadata=metadata,
        )

    def _execute(self, sql: str, params: tuple[Any, ...] = ()) -> Any:
        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        commit = getattr(self._connection, "commit", None)
        if callable(commit):
            commit()

    def _rollback_safely(self) -> None:
        rollback = getattr(self._connection, "rollback", None)
        if callable(rollback):
            try:
                rollback()
            except Exception:
                pass

    def _record_error(self, code: str, error: BaseException | type[BaseException]) -> None:
        self._last_error_code = code
        self._last_error_type = error.__name__ if isinstance(error, type) else type(error).__name__

    def _clear_error(self) -> None:
        self._last_error_code = None
        self._last_error_type = None


# 显式别名便于调用方表达目标索引技术，同时让纯词法 PostgreSQL 和 pgvector 复用同一适配器。
PostgresPgvectorRagKnowledgeBase = PostgresRagKnowledgeBase
PgvectorRagKnowledgeBase = PostgresRagKnowledgeBase


def rag_knowledge_base_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> RagKnowledgeBaseSettings:
    """从环境变量读取 RAG 存储设置。

    主配置为 ``DATASMART_RAG_KNOWLEDGE_BASE`` 和 ``DATASMART_RAG_POSTGRESQL_DSN``。RAG 专用 DSN 或
    ``DATASMART_RAG_PGVECTOR_ENABLED`` 本身视为显式启用 pgvector；项目级
    ``DATASMART_AI_MEMORY_POSTGRESQL_DSN`` 只表示可共享连接，单独存在时不会隐式开启 RAG 存储。
    """

    source = environ if environ is not None else os.environ
    dedicated_dsn = _first_text(
        source,
        "DATASMART_RAG_PGVECTOR_POSTGRESQL_DSN",
        "DATASMART_RAG_POSTGRESQL_DSN",
        "DATASMART_AI_RAG_POSTGRESQL_DSN",
    )
    explicit_store = _first_text(
        source,
        "DATASMART_RAG_KNOWLEDGE_BASE",
        "DATASMART_RAG_KNOWLEDGE_BASE_STORE",
        "DATASMART_RAG_STORE",
        "DATASMART_RAG_STORAGE_BACKEND",
    )
    if explicit_store:
        store_type = _normalize_store_type(explicit_store)
    elif _truthy(_first_text(source, "DATASMART_RAG_PGVECTOR_ENABLED"), default=False) or dedicated_dsn:
        store_type = "pgvector"
    else:
        store_type = "unconfigured"

    dsn = dedicated_dsn
    if store_type in {"postgresql", "pgvector"} and not dsn:
        dsn = _first_text(source, "DATASMART_AI_MEMORY_POSTGRESQL_DSN") or ""

    return _normalized_settings(
        RagKnowledgeBaseSettings(
            runtime_mode=_first_text(
                source,
                "DATASMART_AI_RUNTIME_MODE",
                "DATASMART_RAG_RUNTIME_MODE",
                "DATASMART_ENVIRONMENT",
                "DATASMART_ENV",
                "APP_ENV",
                "ENVIRONMENT",
            )
            or "production",
            store_type=store_type,
            postgresql_dsn=dsn,
            schema_name=_first_text(
                source,
                "DATASMART_RAG_PGVECTOR_SCHEMA",
                "DATASMART_RAG_SCHEMA",
            )
            or "ai_memory",
            table_name=_first_text(source, "DATASMART_RAG_KNOWLEDGE_TABLE") or RAG_POSTGRESQL_KNOWLEDGE_TABLE,
            connect_timeout_seconds=_positive_int(
                _first_text(source, "DATASMART_RAG_POSTGRESQL_CONNECT_TIMEOUT_SECONDS"),
                3,
            ),
            fail_fast=_truthy(_first_text(source, "DATASMART_RAG_PERSISTENCE_FAIL_FAST"), default=False),
            schema_check_on_startup=_truthy(
                _first_text(source, "DATASMART_RAG_SCHEMA_CHECK_ON_STARTUP"),
                default=False,
            ),
            candidate_limit=_positive_int(_first_text(source, "DATASMART_RAG_CANDIDATE_LIMIT"), 200),
            chunk_max_chars=_positive_int(_first_text(source, "DATASMART_RAG_CHUNK_MAX_CHARS"), 700),
            chunk_overlap_chars=_nonnegative_int(_first_text(source, "DATASMART_RAG_CHUNK_OVERLAP_CHARS"), 120),
            ingest_document_limit=_positive_int(
                _first_text(source, "DATASMART_RAG_INGEST_DOCUMENT_LIMIT"),
                500,
            ),
            ingest_chunk_limit=_positive_int(
                _first_text(source, "DATASMART_RAG_INGEST_CHUNK_LIMIT"),
                2000,
            ),
            embedding_model=_first_text(
                source,
                "DATASMART_RAG_EMBEDDING_MODEL",
                "DATASMART_AI_RAG_EMBEDDING_MODEL",
                "DATASMART_AI_MEMORY_EMBEDDING_MODEL",
            )
            or "",
            embedding_dimensions=_optional_positive_int(
                _first_text(
                    source,
                    "DATASMART_RAG_EMBEDDING_DIMENSIONS",
                    "DATASMART_AI_RAG_EMBEDDING_DIMENSIONS",
                    "DATASMART_AI_MEMORY_EMBEDDING_DIMENSIONS",
                )
            ),
        )
    )


def build_rag_knowledge_base_runtime(
    *,
    settings: RagKnowledgeBaseSettings | None = None,
    embedding_provider: AgentMemoryEmbeddingProvider | None = None,
    connection_factory: RagPostgresConnectionFactory | None = None,
) -> RagKnowledgeBaseRuntime:
    """选择并构造配置指定的 RAG 知识库。

    任何分支都不会把请求的持久存储静默降级为内存存储。``fail_fast`` 控制无效持久化配置是否在启动时
    直接抛错；默认返回不可用的 fail-closed 知识库，使无关 Runtime 控制面路由仍可启动并暴露低敏诊断。
    """

    resolved = _normalized_settings(settings or rag_knowledge_base_settings_from_env())
    if resolved.store_type == "in-memory":
        if resolved.production_mode:
            raise RagPersistenceConfigurationError(
                "RAG in-memory storage is only allowed in learning or test runtime modes."
            )
        from datasmart_ai_runtime.services.rag.knowledge_base import InMemoryRagKnowledgeBase

        return RagKnowledgeBaseRuntime(
            knowledge_base=InMemoryRagKnowledgeBase(
                default_documents_for_runtime(),
                chunk_max_chars=resolved.chunk_max_chars,
                chunk_overlap_chars=resolved.chunk_overlap_chars,
            ),
            settings=resolved,
            available=True,
            persistent=False,
            embedding_provider=embedding_provider,
        )

    if resolved.store_type == "unconfigured":
        return _unavailable_runtime(resolved, "RAG_PERSISTENCE_NOT_CONFIGURED")

    if not resolved.postgresql_dsn:
        return _persistent_failure_runtime(
            resolved,
            "RAG_POSTGRESQL_DSN_NOT_CONFIGURED",
            "RAG PostgreSQL/pgvector storage was selected without a DSN.",
        )

    provider = embedding_provider
    if resolved.vector_enabled and provider is None:
        provider = _build_embedding_provider_from_env()
    if resolved.vector_enabled and provider is None:
        return _persistent_failure_runtime(
            resolved,
            "RAG_PGVECTOR_PROVIDER_NOT_CONFIGURED",
            "RAG pgvector storage requires an embedding provider.",
        )
    if resolved.vector_enabled and not resolved.embedding_model:
        return _persistent_failure_runtime(
            resolved,
            "RAG_PGVECTOR_MODEL_NOT_CONFIGURED",
            "RAG pgvector storage requires an embedding model name.",
        )
    if (
        resolved.vector_enabled
        and resolved.production_mode
        and isinstance(provider, DeterministicHashEmbeddingProvider)
    ):
        return _persistent_failure_runtime(
            resolved,
            "RAG_PGVECTOR_NON_SEMANTIC_PROVIDER_FORBIDDEN",
            "生产 pgvector 禁止使用不具备语义能力的确定性测试 Embedding Provider。",
        )

    try:
        connection = connection_factory(resolved) if connection_factory else build_postgresql_connection(
            resolved.postgresql_dsn,
            resolved.connect_timeout_seconds,
        )
        knowledge_base = PostgresRagKnowledgeBase(
            connection,
            settings=resolved,
            embedding_provider=provider,
        )
        if resolved.schema_check_on_startup and not knowledge_base.validate_schema():
            raise RagPersistenceConfigurationError("RAG PostgreSQL knowledge schema is unavailable.")
        return RagKnowledgeBaseRuntime(
            knowledge_base=knowledge_base,
            settings=resolved,
            available=True,
            persistent=True,
            embedding_provider=provider,
        )
    except Exception as exc:
        if resolved.fail_fast:
            if isinstance(exc, RagPersistenceConfigurationError):
                raise
            raise RagPersistenceConfigurationError(
                "RAG PostgreSQL/pgvector storage could not be initialized."
            ) from exc
        return _unavailable_runtime(resolved, _persistent_error_code(exc))


def default_documents_for_runtime() -> tuple[RagDocument, ...]:
    """延迟导入内置学习文档，避免模块循环依赖。"""

    from datasmart_ai_runtime.services.rag.components import default_governance_rag_documents

    return default_governance_rag_documents()


def _unavailable_runtime(settings: RagKnowledgeBaseSettings, reason_code: str) -> RagKnowledgeBaseRuntime:
    return RagKnowledgeBaseRuntime(
        knowledge_base=UnavailableRagKnowledgeBase(settings, reason_code=reason_code),
        settings=settings,
        available=False,
        persistent=False,
        reason_code=reason_code,
    )


def _persistent_failure_runtime(
    settings: RagKnowledgeBaseSettings,
    reason_code: str,
    message: str,
) -> RagKnowledgeBaseRuntime:
    if settings.fail_fast:
        raise RagPersistenceConfigurationError(message)
    return _unavailable_runtime(settings, reason_code)


def _normalized_settings(settings: RagKnowledgeBaseSettings) -> RagKnowledgeBaseSettings:
    normalized = replace(
        settings,
        runtime_mode=_normalize_runtime_mode(settings.runtime_mode),
        store_type=_normalize_store_type(settings.store_type),
        postgresql_dsn=str(settings.postgresql_dsn or "").strip(),
        schema_name=str(settings.schema_name or "ai_memory").strip(),
        table_name=str(settings.table_name or RAG_POSTGRESQL_KNOWLEDGE_TABLE).strip(),
        connect_timeout_seconds=max(1, int(settings.connect_timeout_seconds)),
        candidate_limit=max(5, min(int(settings.candidate_limit), 2000)),
        chunk_max_chars=max(200, min(int(settings.chunk_max_chars), 4000)),
        chunk_overlap_chars=max(0, int(settings.chunk_overlap_chars)),
        ingest_document_limit=max(1, min(int(settings.ingest_document_limit), 10000)),
        ingest_chunk_limit=max(1, min(int(settings.ingest_chunk_limit), 50000)),
        embedding_model=str(settings.embedding_model or "").strip(),
    )
    if normalized.chunk_overlap_chars > normalized.chunk_max_chars // 2:
        normalized = replace(normalized, chunk_overlap_chars=normalized.chunk_max_chars // 2)
    if normalized.embedding_dimensions is not None:
        normalized = replace(normalized, embedding_dimensions=max(1, int(normalized.embedding_dimensions)))
    _validate_identifier(normalized.schema_name, "schema_name")
    _validate_identifier(normalized.table_name, "table_name")
    return normalized


def _scope_predicates(query: RagQuery) -> tuple[list[str], list[Any]]:
    predicates = [
            "tenant_id IN ('*', %s)",
            "project_id IN ('*', %s)",
            "workspace_key IN ('*', %s)",
        ]
    params: list[Any] = [
        str(query.tenant_id or "*"),
        str(query.project_id or "*"),
        str(query.workspace_key or "*"),
    ]
    source_types = tuple(sorted({str(value).strip().lower() for value in (query.source_types or ()) if str(value).strip()}))
    if source_types:
        predicates.append("source_type IN (" + ", ".join("%s" for _ in source_types) + ")")
        params.extend(source_types)
    if not rag_query_explicitly_requests_history(query):
        # 过期证据应在数据库候选阶段被排除，不能等到 reranker 或答案压缩后再丢弃；否则正文已经可能
        # 被发送给外部模型。JSONB 同时兼容旧 evidenceStatus 和新的 sourceStatus 字段。
        predicates.extend(
            (
                "UPPER(COALESCE(metadata_json->>'sourceStatus', '')) <> 'SUPERSEDED'",
                "LOWER(COALESCE(metadata_json->>'evidenceStatus', '')) <> 'superseded'",
            )
        )
    return predicates, params


def _merge_rows(left: tuple[Any, ...], right: tuple[Any, ...], limit: int) -> tuple[Any, ...]:
    """合并词法和向量候选窗口，并按 chunk ID 去重。"""

    merged: list[Any] = []
    seen: set[str] = set()
    for row in (*left, *right):
        chunk_id = str(_row_value(row, "chunk_id", 0))
        if chunk_id in seen:
            continue
        seen.add(chunk_id)
        merged.append(row)
        if len(merged) >= limit:
            break
    return tuple(merged)


def _vector_scores_for_rows(rows: tuple[Any, ...]) -> dict[str, float]:
    """从数据库向量候选中提取有限相似度，并与 chunk 身份稳定关联。

    SQL 返回的是 ``1 - cosine_distance``。这里不把分数写入文档 metadata，避免内部排序事实混入最终
    引用；它只在本次 ``RagKnowledgeCandidateSet`` 中传递给检索器。非法或非有限分数会使整次查询
    fail-closed，由上层返回无证据结果。
    """

    scores: dict[str, float] = {}
    for row in rows:
        chunk_id = _required_text(_row_value(row, "chunk_id", 0), "chunk_id")
        try:
            score = float(_row_value(row, "vector_score", 13))
        except (TypeError, ValueError) as exc:
            raise RagPersistenceConfigurationError(
                "RAG pgvector 返回了非法相似度分数。"
            ) from exc
        if not math.isfinite(score):
            raise RagPersistenceConfigurationError("RAG pgvector 返回了非有限相似度分数。")
        scores[chunk_id] = score
    return scores


def _row_value(row: Any, key: str, index: int) -> Any:
    if isinstance(row, Mapping):
        return row.get(key)
    return row[index]


def _json_sequence(value: Any) -> tuple[str, ...]:
    decoded = _decode_json(value, default=[])
    if not isinstance(decoded, (list, tuple)):
        return ()
    return tuple(str(item).strip() for item in decoded if str(item).strip())


def _json_mapping(value: Any) -> dict[str, Any]:
    decoded = _decode_json(value, default={})
    return dict(decoded) if isinstance(decoded, Mapping) else {}


def _decode_json(value: Any, *, default: Any) -> Any:
    if value is None:
        return default
    if isinstance(value, (dict, list, tuple)):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except (TypeError, ValueError):
            return default
    return default


def _content_fingerprint(chunk: RagChunk) -> str:
    import hashlib

    payload = f"{chunk.title}\n{chunk.text}\n{chunk.source_uri}".encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _vector_literal(embedding: tuple[float, ...]) -> str:
    return "[" + ",".join(format(value, ".12g") for value in embedding) + "]"


def _qualified_table(schema_name: str, table_name: str) -> str:
    _validate_identifier(schema_name, "schema_name")
    _validate_identifier(table_name, "table_name")
    return f'"{schema_name}"."{table_name}"'


def _validate_identifier(value: str, field_name: str) -> None:
    if not _IDENTIFIER_PATTERN.fullmatch(value):
        raise RagPersistenceConfigurationError(
            f"{field_name} must contain only letters, digits, and underscores."
        )


def _required_text(value: Any, field_name: str) -> str:
    normalized = str(value or "").strip()
    if not normalized:
        raise ValueError(f"RAG {field_name} cannot be empty.")
    return normalized


def _normalize_runtime_mode(value: str | None) -> str:
    normalized = str(value or "production").strip().lower().replace("_", "-")
    aliases = {
        "prod": "production",
        "stage": "staging",
        "pre-production": "preprod",
        "dev": "learning",
        "development": "learning",
        "local": "learning",
        "unit-test": "test",
        "unittest": "test",
    }
    return aliases.get(normalized, normalized or "production")


def _normalize_store_type(value: str | None) -> str:
    normalized = str(value or "unconfigured").strip().lower().replace("_", "-")
    if normalized in _IN_MEMORY_STORES:
        return "in-memory"
    if normalized in _POSTGRES_STORES:
        return "postgresql"
    if normalized in _PGVECTOR_STORES:
        return "pgvector"
    if normalized in {"", "auto", "none", "disabled", "unconfigured", "unavailable"}:
        return "unconfigured"
    raise RagPersistenceConfigurationError(
        "DATASMART_RAG_KNOWLEDGE_BASE must be in-memory, postgresql, pgvector, or unconfigured."
    )


def _first_text(source: Mapping[str, str], *keys: str) -> str | None:
    for key in keys:
        value = source.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None


def _truthy(value: str | None, *, default: bool) -> bool:
    if value is None or not str(value).strip():
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _positive_int(value: str | None, default: int) -> int:
    if value is None or not str(value).strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


def _nonnegative_int(value: str | None, default: int) -> int:
    if value is None or not str(value).strip():
        return default
    parsed = int(value)
    return parsed if parsed >= 0 else default


def _optional_positive_int(value: str | None) -> int | None:
    if value is None or not str(value).strip():
        return None
    parsed = int(value)
    if parsed <= 0:
        raise ValueError("Embedding dimensions must be positive.")
    return parsed


def rag_embedding_provider_from_env(
    environ: Mapping[str, str] | None = None,
) -> AgentMemoryEmbeddingProvider | None:
    """按需构建共享或 RAG 专用 Embedding Provider。

    RAG 专用配置始终优先。若部署统一使用硅基流动，可只通过 Secret 注入
    ``SILICONFLOW_API_KEY``；该兼容变量只补充密钥，不会隐式开启 Provider 或改变模型名称。
    """

    source = dict(environ if environ is not None else os.environ)
    # 复用现有 Memory Provider 实现，同时允许 RAG 在部署需要时独立管理 Endpoint 和模型。
    aliases = {
        "PROVIDER": ("DATASMART_RAG_EMBEDDING_PROVIDER", "DATASMART_AI_RAG_EMBEDDING_PROVIDER"),
        "ENDPOINT": ("DATASMART_RAG_EMBEDDING_ENDPOINT", "DATASMART_AI_RAG_EMBEDDING_ENDPOINT"),
        "API_KEY": ("DATASMART_RAG_EMBEDDING_API_KEY", "DATASMART_AI_RAG_EMBEDDING_API_KEY"),
        "MODEL": ("DATASMART_RAG_EMBEDDING_MODEL", "DATASMART_AI_RAG_EMBEDDING_MODEL"),
        "DIMENSIONS": ("DATASMART_RAG_EMBEDDING_DIMENSIONS", "DATASMART_AI_RAG_EMBEDDING_DIMENSIONS"),
        "TIMEOUT_SECONDS": (
            "DATASMART_RAG_EMBEDDING_TIMEOUT_SECONDS",
            "DATASMART_AI_RAG_EMBEDDING_TIMEOUT_SECONDS",
        ),
        "ORGANIZATION": (
            "DATASMART_RAG_EMBEDDING_ORGANIZATION",
            "DATASMART_AI_RAG_EMBEDDING_ORGANIZATION",
        ),
        "MAX_INPUT_CHARS": (
            "DATASMART_RAG_EMBEDDING_MAX_INPUT_CHARS",
            "DATASMART_AI_RAG_EMBEDDING_MAX_INPUT_CHARS",
        ),
        "MAX_BATCH_SIZE": (
            "DATASMART_RAG_EMBEDDING_MAX_BATCH_SIZE",
            "DATASMART_AI_RAG_EMBEDDING_MAX_BATCH_SIZE",
        ),
    }
    for suffix, keys in aliases.items():
        value = _first_text(source, *keys)
        if value is not None:
            source[f"DATASMART_AI_MEMORY_EMBEDDING_{suffix}"] = value
    if not _first_text(source, "DATASMART_AI_MEMORY_EMBEDDING_API_KEY"):
        siliconflow_api_key = _first_text(source, "SILICONFLOW_API_KEY")
        if siliconflow_api_key is not None:
            provider_type = str(
                source.get("DATASMART_AI_MEMORY_EMBEDDING_PROVIDER") or "disabled"
            ).strip().lower().replace("_", "-")
            if provider_type in {"openai", "openai-compatible"}:
                endpoint = _first_text(source, "DATASMART_AI_MEMORY_EMBEDDING_ENDPOINT") or ""
                if (parse.urlsplit(endpoint).hostname or "").lower() != "api.siliconflow.cn":
                    raise ValueError(
                        "共享 SILICONFLOW_API_KEY 只能用于 api.siliconflow.cn；"
                        "自定义 Endpoint 必须配置独立 Embedding API Key。"
                    )
            source["DATASMART_AI_MEMORY_EMBEDDING_API_KEY"] = siliconflow_api_key
    return build_memory_embedding_provider(memory_embedding_provider_settings_from_env(source))


def _build_embedding_provider_from_env() -> AgentMemoryEmbeddingProvider | None:
    """供持久化装配器调用的内部兼容入口。"""

    return rag_embedding_provider_from_env()


def _persistent_error_code(error: BaseException) -> str:
    if isinstance(error, RagPersistenceConfigurationError):
        return "RAG_PERSISTENCE_CONFIGURATION_INVALID"
    if "vector" in str(error).lower():
        return "RAG_PGVECTOR_INITIALIZATION_FAILED"
    return "RAG_POSTGRESQL_INITIALIZATION_FAILED"


def _query_error_code(settings: RagKnowledgeBaseSettings) -> str:
    return "RAG_PGVECTOR_QUERY_FAILED" if settings.vector_enabled else "RAG_POSTGRESQL_QUERY_FAILED"


__all__ = [
    "PgvectorRagKnowledgeBase",
    "PostgresPgvectorRagKnowledgeBase",
    "PostgresRagKnowledgeBase",
    "RAG_KNOWLEDGE_DIAGNOSTICS_PAYLOAD_POLICY",
    "RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL",
    "RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_VERSION",
    "RAG_POSTGRESQL_KNOWLEDGE_TABLE",
    "RagKnowledgeBaseRuntime",
    "RagKnowledgeBaseSettings",
    "RagPersistenceConfigurationError",
    "RagPostgresConnectionFactory",
    "UnavailableRagKnowledgeBase",
    "build_rag_knowledge_base_runtime",
    "rag_embedding_provider_from_env",
    "rag_knowledge_base_settings_from_env",
]
