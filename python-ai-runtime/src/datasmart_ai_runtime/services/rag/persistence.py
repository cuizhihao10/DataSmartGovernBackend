"""RAG knowledge-store configuration and PostgreSQL/pgvector adapter.

The RAG pipeline deliberately depends on the small ``RagKnowledgeBase``
protocol rather than on a database client.  This module owns the composition
root for that protocol:

* ``in-memory`` is an explicit learning/test choice;
* ``postgresql`` provides durable, scope-filtered lexical retrieval;
* ``pgvector`` adds durable embedding storage and database-side nearest-neighbour
  candidate selection;
* an unconfigured or broken production store becomes an unavailable knowledge
  base instead of silently changing to ``InMemoryRagKnowledgeBase``.

The SQL contract is exposed as a string for migrations and integration smoke
tests.  Runtime startup does not create or alter production schema implicitly.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from threading import RLock
from typing import Any, Callable, Iterable, Mapping

from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    AgentMemoryEmbeddingProvider,
    build_memory_embedding_provider,
    memory_embedding_provider_settings_from_env,
    validate_embedding_vector,
)
from datasmart_ai_runtime.services.memory.memory_sql_connection import (
    build_postgresql_connection,
    mask_postgresql_dsn,
)
from datasmart_ai_runtime.services.rag.knowledge_base import RagKnowledgeBase
from datasmart_ai_runtime.services.rag.models import (
    RagChunk,
    RagChunkSourceType,
    RagDocument,
    RagQuery,
)
from datasmart_ai_runtime.services.rag.text import chunk_document


RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_VERSION = "datasmart.rag.postgresql-knowledge.v1"
RAG_POSTGRESQL_KNOWLEDGE_TABLE = "rag_knowledge_chunk"
RAG_KNOWLEDGE_DIAGNOSTICS_PAYLOAD_POLICY = "RAG_KNOWLEDGE_DIAGNOSTICS_NO_DOCUMENT_BODY"

# This is a migration contract, not an instruction for the Runtime to mutate
# a customer database during startup.  The table is intentionally denormalised
# at chunk level: a query can enforce all scope predicates and retrieve the
# citation fields without first loading a document catalog into process memory.
RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL = """
CREATE EXTENSION IF NOT EXISTS vector;
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
    embedding VECTOR,
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
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_search
    ON ai_memory.rag_knowledge_chunk USING GIN (content_search_vector);
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_embedding_model
    ON ai_memory.rag_knowledge_chunk (embedding_model, embedding_dimension, enabled);
""".strip()


_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,62}$")
_IN_MEMORY_ALLOWED_MODES = frozenset({"learning", "test"})
_IN_MEMORY_STORES = frozenset({"in-memory", "memory", "test-memory"})
_POSTGRES_STORES = frozenset({"postgres", "postgresql", "sql"})
_PGVECTOR_STORES = frozenset({"pgvector", "postgresql-pgvector", "postgres-pgvector"})

RagPostgresConnectionFactory = Callable[["RagKnowledgeBaseSettings"], Any]


class RagPersistenceConfigurationError(ValueError):
    """Raised when an explicitly requested RAG storage contract is invalid."""


@dataclass(frozen=True)
class RagKnowledgeBaseSettings:
    """Runtime selection for the RAG knowledge base.

    ``store_type`` is intentionally ``unconfigured`` by default.  The API
    composition root therefore cannot accidentally make a process-local demo
    store look like a durable production knowledge base.  Learning and test
    callers must select ``in-memory`` explicitly.
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
    embedding_model: str = ""
    embedding_dimensions: int | None = None

    @property
    def production_mode(self) -> bool:
        """Whether the runtime must reject process-local knowledge state."""

        # Unknown modes are treated as production-like.  A typo in deployment
        # configuration must not accidentally enable a process-local store.
        return not self.in_memory_allowed

    @property
    def in_memory_allowed(self) -> bool:
        """Whether an explicit in-memory store is permitted for this mode."""

        return _normalize_runtime_mode(self.runtime_mode) in _IN_MEMORY_ALLOWED_MODES

    @property
    def vector_enabled(self) -> bool:
        """Whether the selected store requires the pgvector query contract."""

        return _normalize_store_type(self.store_type) == "pgvector"


@dataclass(frozen=True)
class RagKnowledgeBaseRuntime:
    """The selected knowledge base plus low-sensitivity startup facts."""

    knowledge_base: RagKnowledgeBase
    settings: RagKnowledgeBaseSettings
    available: bool
    persistent: bool
    embedding_provider: AgentMemoryEmbeddingProvider | None = None
    reason_code: str | None = None


class UnavailableRagKnowledgeBase:
    """Fail-closed knowledge base used when durable RAG is unavailable.

    Returning no chunks keeps the existing pipeline contract intact: its
    evidence gate refuses generation and returns the normal no-evidence result.
    The diagnostic distinguishes this state from an empty but healthy store.
    """

    def __init__(self, settings: RagKnowledgeBaseSettings, *, reason_code: str) -> None:
        self._settings = settings
        self._reason_code = reason_code

    def chunks_for_query(self, query: RagQuery) -> tuple[RagChunk, ...]:
        """Never return evidence while the configured store is unavailable."""

        return ()

    def diagnostics(self) -> dict[str, object]:
        """Return an actionable, body-free unavailable diagnostic."""

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
    """PostgreSQL knowledge store with an optional pgvector retrieval path.

    The adapter owns only document/chunk persistence and database-side
    candidate selection.  Scope filtering is part of every SQL query and is
    performed before vector ordering.  The existing ``RagHybridRetriever``
    still performs its explainable lexical/vector fusion and MMR selection on
    the bounded candidate window.
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
        """Return a bounded, hard-scope-filtered candidate set.

        The question is used only as an in-flight embedding input.  It is
        never written to the database or diagnostic state.
        """

        limit = max(5, min(int(query.candidate_limit), self._settings.candidate_limit, 200))
        try:
            with self._lock:
                if self._settings.vector_enabled:
                    if self._embedding_provider is None:
                        self._record_error("RAG_PGVECTOR_PROVIDER_UNAVAILABLE", RuntimeError)
                        return ()
                    query_embedding = validate_embedding_vector(
                        self._embedding_provider.embed_text(str(query.question or "")[:4000])
                    )
                    rows = self._query_vector_rows(query, query_embedding, limit)
                    # A newly indexed document may not have a vector yet.  A
                    # lexical window preserves safe, explainable retrieval for
                    # those rows without weakening the SQL scope boundary.
                    if not rows:
                        rows = self._query_scope_rows(query, limit)
                else:
                    rows = self._query_scope_rows(query, limit)
                chunks = tuple(self._row_to_chunk(row) for row in rows)
                self._last_query_row_count = len(chunks)
                self._clear_error()
                return chunks
        except Exception as exc:
            # RAG infrastructure failures must not turn into an in-memory
            # fallback.  Empty candidates let the pipeline's evidence gate
            # close the request while diagnostics expose the failure class.
            with self._lock:
                self._rollback_safely()
                self._record_error(_query_error_code(self._settings), exc)
            return ()

    def upsert_documents(self, documents: Iterable[RagDocument]) -> int:
        """Atomically replace the persisted chunks for each supplied document.

        This is the minimal ingestion contract for P1.  A future MinIO/parser
        worker can call it after authorization and chunking; it does not need
        to know SQL identifiers, JSON encoding, or pgvector literals.
        """

        normalized_documents = tuple(documents)
        with self._lock:
            try:
                total_chunks = 0
                for document in normalized_documents:
                    document_id = _required_text(document.document_id, "document_id")
                    self._execute(
                        f"DELETE FROM {self._table} WHERE document_id = %s",
                        (document_id,),
                    )
                    if not document.enabled:
                        continue
                    chunks = chunk_document(
                        document,
                        max_chars=self._settings.chunk_max_chars,
                        overlap_chars=self._settings.chunk_overlap_chars,
                    )
                    for chunk in chunks:
                        embedding, embedding_model = self._embedding_for_chunk(chunk)
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
                                %s, {"CAST(%s AS vector)" if self._settings.vector_enabled else "%s"}, %s, %s
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
                    "RAG knowledge document persistence failed; the transaction was rolled back."
                ) from exc

    def delete_document(self, document_id: str) -> int:
        """Delete all chunks for one stable document ID and commit atomically."""

        normalized_id = _required_text(document_id, "document_id")
        with self._lock:
            try:
                cursor = self._execute(
                    f"DELETE FROM {self._table} WHERE document_id = %s",
                    (normalized_id,),
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
        """Probe the configured table without creating or altering schema."""

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
        """Return low-sensitivity storage facts without document or query text."""

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
        """Close the owned DB-API connection when the host lifecycle permits it."""

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

    def _query_vector_rows(
        self,
        query: RagQuery,
        embedding: tuple[float, ...],
        limit: int,
    ) -> tuple[Any, ...]:
        predicates, params = _scope_predicates(query)
        predicates.extend(["embedding IS NOT NULL"])
        if self._settings.embedding_model:
            predicates.append("embedding_model = %s")
            params.append(self._settings.embedding_model)
        if self._settings.embedding_dimensions is not None:
            predicates.append("embedding_dimension = %s")
            params.append(self._settings.embedding_dimensions)
        vector_literal = _vector_literal(embedding)
        params.extend([vector_literal, limit])
        cursor = self._execute(
            f"SELECT {self._SELECT_COLUMNS} FROM {self._table} "
            f"WHERE enabled = TRUE AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) "
            f"AND {' AND '.join(predicates)} "
            "ORDER BY embedding <=> CAST(%s AS vector), chunk_id ASC LIMIT %s",
            tuple(params),
        )
        return tuple(cursor.fetchall())

    def _embedding_for_chunk(self, chunk: RagChunk) -> tuple[tuple[float, ...], str | None]:
        if not self._settings.vector_enabled:
            return (), None
        if self._embedding_provider is None:
            raise RagPersistenceConfigurationError("pgvector RAG requires an embedding provider.")
        text = f"{chunk.title}\n{chunk.text}\n{' '.join(chunk.tags)}"[:4000]
        embedding = validate_embedding_vector(self._embedding_provider.embed_text(text))
        declared = self._settings.embedding_dimensions
        if declared is not None and len(embedding) != declared:
            raise RagPersistenceConfigurationError(
                "RAG embedding dimension does not match DATASMART_RAG_EMBEDDING_DIMENSIONS."
            )
        return embedding, self._settings.embedding_model or "rag-embedding"

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


# The explicit name is useful to callers that want to communicate the target
# index technology while retaining one adapter for lexical-only PostgreSQL.
PostgresPgvectorRagKnowledgeBase = PostgresRagKnowledgeBase
PgvectorRagKnowledgeBase = PostgresRagKnowledgeBase


def rag_knowledge_base_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> RagKnowledgeBaseSettings:
    """Read RAG storage settings from environment variables.

    Primary variables are ``DATASMART_RAG_KNOWLEDGE_BASE`` and
    ``DATASMART_RAG_POSTGRESQL_DSN``.  Dedicated pgvector aliases and the
    project-wide ``DATASMART_AI_MEMORY_POSTGRESQL_DSN`` are accepted so a
    deployment can share the target database while still choosing RAG
    explicitly.
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
    """Select and construct the configured RAG knowledge base.

    No branch silently falls back from a requested persistent store to an
    in-memory store.  ``fail_fast`` controls whether an invalid persistent
    configuration raises at startup; the default is an unavailable,
    fail-closed knowledge base so unrelated Runtime control-plane routes can
    still start and expose a useful diagnostic.
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
    """Import built-in learning documents lazily to avoid a module cycle."""

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
    return (
        [
            "tenant_id IN ('*', %s)",
            "project_id IN ('*', %s)",
            "workspace_key IN ('*', %s)",
        ],
        [str(query.tenant_id or "*"), str(query.project_id or "*"), str(query.workspace_key or "*")],
    )


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
    """Build a shared or RAG-dedicated embedding provider on demand."""

    source = dict(environ if environ is not None else os.environ)
    # Keep the existing Memory provider implementation while allowing RAG to
    # use an independently managed endpoint/model when a deployment needs it.
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
    }
    for suffix, keys in aliases.items():
        value = _first_text(source, *keys)
        if value is not None:
            source[f"DATASMART_AI_MEMORY_EMBEDDING_{suffix}"] = value
    return build_memory_embedding_provider(memory_embedding_provider_settings_from_env(source))


def _build_embedding_provider_from_env() -> AgentMemoryEmbeddingProvider | None:
    """Internal compatibility wrapper used by the persistence builder."""

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
