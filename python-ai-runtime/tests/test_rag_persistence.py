"""Focused RAG persistence and composition-contract regressions."""

from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.services.memory import DeterministicHashEmbeddingProvider
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService, ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag import (
    RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL,
    InMemoryRagKnowledgeBase,
    PostgresRagKnowledgeBase,
    RagChunkSourceType,
    RagDocument,
    RagKnowledgeBaseSettings,
    RagQuery,
    UnavailableRagKnowledgeBase,
    build_default_governance_rag_pipeline,
    build_rag_knowledge_base_runtime,
    rag_embedding_provider_from_env,
    rag_knowledge_base_settings_from_env,
)


class RagPersistenceTest(unittest.TestCase):
    """Keep production selection, fail-closed behavior, and SQL shape stable."""

    def test_production_without_persistent_store_is_unavailable_and_fail_closed(self) -> None:
        runtime = build_rag_knowledge_base_runtime(
            settings=RagKnowledgeBaseSettings(
                runtime_mode="production",
                store_type="unconfigured",
            )
        )

        self.assertFalse(runtime.available)
        self.assertFalse(runtime.persistent)
        self.assertIsInstance(runtime.knowledge_base, UnavailableRagKnowledgeBase)
        diagnostics = runtime.knowledge_base.diagnostics()
        self.assertEqual("RAG_PERSISTENCE_NOT_CONFIGURED", diagnostics["reasonCode"])
        self.assertTrue(diagnostics["failClosed"])
        self.assertFalse(diagnostics["persistent"])

        routes = ModelRouteRegistry(default_model_routes())
        pipeline = build_default_governance_rag_pipeline(
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            knowledge_base_settings=RagKnowledgeBaseSettings(
                runtime_mode="production",
                store_type="unconfigured",
            ),
        )
        result = pipeline.answer(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="actor-a",
                question="which governance rule applies?",
                generate_answer=True,
            )
        )
        self.assertFalse(result.generated)
        self.assertEqual((), result.citations)

    def test_learning_mode_can_explicitly_select_in_memory(self) -> None:
        runtime = build_rag_knowledge_base_runtime(
            settings=RagKnowledgeBaseSettings(
                runtime_mode="test",
                store_type="in-memory",
            )
        )

        self.assertTrue(runtime.available)
        self.assertFalse(runtime.persistent)
        self.assertIsInstance(runtime.knowledge_base, InMemoryRagKnowledgeBase)
        self.assertEqual("in-memory", runtime.knowledge_base.diagnostics()["implementation"].replace("InMemoryRagKnowledgeBase", "in-memory"))

    def test_production_explicit_in_memory_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            build_rag_knowledge_base_runtime(
                settings=RagKnowledgeBaseSettings(
                    runtime_mode="production",
                    store_type="in-memory",
                )
            )

    def test_unknown_runtime_mode_cannot_enable_in_memory(self) -> None:
        with self.assertRaises(ValueError):
            build_rag_knowledge_base_runtime(
                settings=RagKnowledgeBaseSettings(
                    runtime_mode="typoed-environment",
                    store_type="in-memory",
                )
            )

    def test_pgvector_assembly_and_chunk_contract_use_injected_connection(self) -> None:
        connection = _FakePostgresConnection()
        provider = DeterministicHashEmbeddingProvider(dimensions=4)
        settings = RagKnowledgeBaseSettings(
            runtime_mode="production",
            store_type="pgvector",
            postgresql_dsn="host=postgres password=do-not-leak",
            embedding_model="test-embedding-v1",
            embedding_dimensions=4,
        )

        runtime = build_rag_knowledge_base_runtime(
            settings=settings,
            embedding_provider=provider,
            connection_factory=lambda resolved: connection,
        )

        self.assertTrue(runtime.available)
        self.assertTrue(runtime.persistent)
        self.assertIsInstance(runtime.knowledge_base, PostgresRagKnowledgeBase)

        knowledge_base = runtime.knowledge_base
        assert isinstance(knowledge_base, PostgresRagKnowledgeBase)
        written = knowledge_base.upsert_documents(
            (
                RagDocument(
                    document_id="quality-doc",
                    title="Quality rules",
                    content="Completeness and uniqueness rules require metadata.",
                    source_uri="test://quality-doc",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="workspace-a",
                    source_type=RagChunkSourceType.RULE,
                    metadata={"version": 1},
                ),
            )
        )
        self.assertEqual(1, written)
        self.assertGreaterEqual(connection.commit_count, 1)

        chunks = knowledge_base.chunks_for_query(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="workspace-a",
                actor_id="actor-a",
                question="quality rules",
            )
        )
        self.assertEqual(1, len(chunks))
        self.assertEqual("quality-doc#chunk-1", chunks[0].chunk_id)
        self.assertEqual(RagChunkSourceType.RULE, chunks[0].source_type)
        self.assertEqual({"version": 1}, chunks[0].metadata)

        select_statements = [
            sql for sql, _ in connection.statements if sql.lstrip().upper().startswith("SELECT")
        ]
        self.assertTrue(any("websearch_to_tsquery('simple', %s)" in sql for sql in select_statements))
        self.assertTrue(any("content_search_vector @@ query.tsq" in sql for sql in select_statements))
        self.assertTrue(any("<=> CAST(%s AS vector)" in sql for sql in select_statements))
        for select_sql in select_statements:
            self.assertIn("tenant_id IN ('*', %s)", select_sql)
            self.assertIn("project_id IN ('*', %s)", select_sql)
            self.assertIn("workspace_key IN ('*', %s)", select_sql)

        diagnostics = knowledge_base.diagnostics()
        self.assertTrue(diagnostics["persistent"])
        self.assertTrue(diagnostics["embedding"]["enabled"])
        self.assertNotIn("do-not-leak", json.dumps(diagnostics, ensure_ascii=False))

    def test_environment_contract_prefers_dedicated_dsn_and_requires_explicit_store(self) -> None:
        parsed = rag_knowledge_base_settings_from_env(
            {
                "DATASMART_AI_RUNTIME_MODE": "test",
                "DATASMART_RAG_PGVECTOR_POSTGRESQL_DSN": "host=dedicated",
                "DATASMART_AI_MEMORY_POSTGRESQL_DSN": "host=shared",
                "DATASMART_RAG_EMBEDDING_MODEL": "rag-model",
            }
        )
        self.assertEqual("test", parsed.runtime_mode)
        self.assertEqual("pgvector", parsed.store_type)
        self.assertEqual("host=dedicated", parsed.postgresql_dsn)
        self.assertEqual("rag-model", parsed.embedding_model)

        no_rag_config = rag_knowledge_base_settings_from_env(
            {
                "DATASMART_AI_RUNTIME_MODE": "production",
                "DATASMART_AI_MEMORY_POSTGRESQL_DSN": "host=shared-only",
            }
        )
        self.assertEqual("unconfigured", no_rag_config.store_type)

    def test_schema_contract_contains_scope_and_vector_columns(self) -> None:
        self.assertIn("rag_knowledge_chunk", RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL)
        self.assertIn("tenant_id", RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL)
        self.assertIn("workspace_key", RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL)
        self.assertIn("embedding VECTOR", RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL)

    def test_repository_migration_installs_the_runtime_schema_contract(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        migration = (
            repository_root / "docker" / "postgresql" / "init" / "11-rag-knowledge-schema.sql"
        ).read_text(encoding="utf-8")

        self.assertIn("CREATE TABLE IF NOT EXISTS ai_memory.rag_knowledge_chunk", migration)
        self.assertIn("tenant_id VARCHAR(128) NOT NULL DEFAULT '*'", migration)
        self.assertIn("workspace_key VARCHAR(255) NOT NULL DEFAULT '*'", migration)
        self.assertIn("embedding VECTOR", migration)
        self.assertIn("content_search_vector TSVECTOR GENERATED ALWAYS AS", migration)

    def test_application_compose_enables_migration_managed_postgresql_rag(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        compose = (repository_root / "docker-compose.application.yml").read_text(encoding="utf-8")

        self.assertIn(
            "DATASMART_RAG_KNOWLEDGE_BASE: ${DATASMART_RAG_KNOWLEDGE_BASE:-postgresql}",
            compose,
        )
        self.assertIn("DATASMART_RAG_POSTGRESQL_DSN:", compose)
        self.assertIn('DATASMART_RAG_SCHEMA_CHECK_ON_STARTUP: "true"', compose)

    def test_application_compose_persists_langgraph_checkpoints_fail_closed(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        compose = (repository_root / "docker-compose.application.yml").read_text(encoding="utf-8")

        self.assertIn("DATASMART_LANGGRAPH_CHECKPOINT_STORE: postgresql", compose)
        self.assertIn("DATASMART_LANGGRAPH_CHECKPOINT_POSTGRESQL_DSN:", compose)
        self.assertIn('DATASMART_LANGGRAPH_CHECKPOINT_CONNECT_TIMEOUT_SECONDS: "3"', compose)
        self.assertIn('DATASMART_LANGGRAPH_CHECKPOINT_FAIL_OPEN: "false"', compose)

    def test_rag_embedding_environment_can_be_dedicated_from_memory_environment(self) -> None:
        provider = rag_embedding_provider_from_env(
            {
                "DATASMART_RAG_EMBEDDING_PROVIDER": "deterministic",
                "DATASMART_RAG_EMBEDDING_DIMENSIONS": "4",
                "DATASMART_RAG_EMBEDDING_MODEL": "rag-test-model",
            }
        )
        self.assertIsNotNone(provider)
        assert provider is not None
        self.assertEqual(4, len(provider.embed_text("contract")))


class _FakePostgresConnection:
    """Small DB-API double for SQL assembly and row-mapping contracts."""

    def __init__(self) -> None:
        self.rows = []
        self.statements = []
        self.commit_count = 0

    def cursor(self):
        return _FakePostgresCursor(self)

    def commit(self) -> None:
        self.commit_count += 1

    def rollback(self) -> None:
        return None


class _FakePostgresCursor:
    def __init__(self, connection: _FakePostgresConnection) -> None:
        self._connection = connection
        self._rows = []
        self.rowcount = 1

    def execute(self, sql, params=()) -> None:
        normalized = sql.lstrip().upper()
        values = tuple(params)
        self._connection.statements.append((sql, values))
        self.rowcount = 0
        if normalized.startswith("DELETE FROM"):
            document_id = values[0]
            before = len(self._connection.rows)
            self._connection.rows = [
                row for row in self._connection.rows if row["document_id"] != document_id
            ]
            self.rowcount = before - len(self._connection.rows)
            self._rows = []
            return
        if normalized.startswith("INSERT INTO"):
            row = {
                "chunk_id": values[0],
                "document_id": values[1],
                "chunk_index": values[2],
                "title": values[3],
                "chunk_text": values[4],
                "source_uri": values[5],
                "tenant_id": values[6],
                "project_id": values[7],
                "workspace_key": values[8],
                "source_type": values[9],
                "tags_json": values[10],
                "sensitivity_level": values[11],
                "metadata_json": values[12],
            }
            self._connection.rows = [
                existing
                for existing in self._connection.rows
                if existing["chunk_id"] != row["chunk_id"]
            ]
            self._connection.rows.append(row)
            self.rowcount = 1
            self._rows = []
            return
        if normalized.startswith("SELECT"):
            if normalized.startswith("SELECT 1"):
                self._rows = []
                return
            tenant_id, project_id, workspace_key = values[:3]
            self._rows = [
                row
                for row in self._connection.rows
                if row["tenant_id"] in {"*", tenant_id}
                and row["project_id"] in {"*", project_id}
                and row["workspace_key"] in {"*", workspace_key}
            ]
            return
        self._rows = []

    def fetchall(self):
        return tuple(self._rows)


if __name__ == "__main__":
    unittest.main()
