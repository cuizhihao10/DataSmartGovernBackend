"""RAG 持久化和组件装配合同的聚焦回归测试。"""

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
    RagHybridRetriever,
    RagKnowledgeBaseSettings,
    RagQuery,
    UnavailableRagKnowledgeBase,
    build_default_governance_rag_pipeline,
    build_rag_knowledge_base_runtime,
    rag_embedding_provider_from_env,
    rag_knowledge_base_settings_from_env,
)


class RagPersistenceTest(unittest.TestCase):
    """保证生产存储选择、fail-closed 行为和 SQL 形状保持稳定。"""

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
            runtime_mode="test",
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
        self.assertRegex(chunks[0].chunk_id, r"^rag-[0-9a-f]{64}#chunk-1$")
        self.assertEqual("quality-doc", chunks[0].document_id)
        self.assertEqual(RagChunkSourceType.RULE, chunks[0].source_type)
        self.assertEqual({"version": 1}, chunks[0].metadata)

        select_statements = [
            sql for sql, _ in connection.statements if sql.lstrip().upper().startswith("SELECT")
        ]
        self.assertTrue(any("websearch_to_tsquery('simple', %s)" in sql for sql in select_statements))
        self.assertTrue(any("content_search_vector @@ query.tsq" in sql for sql in select_statements))
        self.assertTrue(
            any(
                "OPERATOR(public.<=>) query_vector.value" in sql
                for sql in select_statements
            )
        )
        self.assertTrue(
            any(
                "CROSS JOIN (SELECT CAST(%s AS public.vector) AS value) query_vector" in sql
                for sql in select_statements
            )
        )
        insert_statements = [
            sql for sql, _ in connection.statements if sql.lstrip().upper().startswith("INSERT")
        ]
        self.assertTrue(any("CAST(%s AS public.vector)" in sql for sql in insert_statements))
        for select_sql in select_statements:
            self.assertIn("tenant_id IN ('*', %s)", select_sql)
            self.assertIn("project_id IN ('*', %s)", select_sql)
            self.assertIn("workspace_key IN ('*', %s)", select_sql)
            self.assertIn("metadata_json->>'sourceStatus'", select_sql)
            self.assertIn("metadata_json->>'evidenceStatus'", select_sql)

        diagnostics = knowledge_base.diagnostics()
        self.assertTrue(diagnostics["persistent"])
        self.assertTrue(diagnostics["embedding"]["enabled"])
        self.assertNotIn("do-not-leak", json.dumps(diagnostics, ensure_ascii=False))

    def test_production_pgvector_rejects_deterministic_embedding_provider(self) -> None:
        """确定性哈希向量只适合测试，生产 pgvector 不能把它误报为语义检索。"""

        runtime = build_rag_knowledge_base_runtime(
            settings=RagKnowledgeBaseSettings(
                runtime_mode="production",
                store_type="pgvector",
                postgresql_dsn="host=postgres password=must-not-leak",
                embedding_model="deterministic-test-model",
                embedding_dimensions=4,
            ),
            embedding_provider=DeterministicHashEmbeddingProvider(dimensions=4),
            connection_factory=lambda resolved: self.fail("禁止配置不应连接数据库"),
        )

        self.assertFalse(runtime.available)
        self.assertEqual(
            "RAG_PGVECTOR_NON_SEMANTIC_PROVIDER_FORBIDDEN",
            runtime.reason_code,
        )

    def test_pgvector_ingestion_batches_embeddings_for_all_document_chunks(self) -> None:
        """多切块语料必须批量生成向量，不能按 chunk 放大远程 HTTP 调用。"""

        connection = _FakePostgresConnection()
        provider = _RecordingBatchEmbeddingProvider(dimensions=4)
        knowledge_base = PostgresRagKnowledgeBase(
            connection,
            settings=RagKnowledgeBaseSettings(
                runtime_mode="production",
                store_type="pgvector",
                embedding_model="BAAI/bge-m3",
                embedding_dimensions=4,
                chunk_max_chars=200,
                chunk_overlap_chars=20,
            ),
            embedding_provider=provider,
        )

        written = knowledge_base.upsert_documents(
            (
                RagDocument(
                    document_id="batch-embedding-doc",
                    title="批量向量写入测试",
                    content="字段映射与恢复策略用于验证批量向量写入。" * 80,
                    source_uri="test://batch-embedding-doc",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="workspace-a",
                    source_type=RagChunkSourceType.RUNBOOK,
                ),
            )
        )

        self.assertGreater(written, 1)
        self.assertEqual([], provider.single_calls)
        self.assertEqual(1, len(provider.batch_calls))
        self.assertEqual(written, len(provider.batch_calls[0]))

    def test_pgvector_ingestion_rejects_unbounded_document_or_chunk_batches(self) -> None:
        """单次摄取必须在调用远程模型和数据库前执行文档数、chunk 数硬上限。"""

        for documents, document_limit, chunk_limit, expected_error in (
            (
                (
                    _rag_document("doc-a", "短文档"),
                    _rag_document("doc-b", "短文档"),
                ),
                1,
                20,
                "文档数量",
            ),
            (
                (_rag_document("doc-many-chunks", "字段映射恢复步骤。" * 200),),
                5,
                1,
                "chunk 数量",
            ),
        ):
            with self.subTest(expected_error=expected_error):
                connection = _FakePostgresConnection()
                provider = _RecordingBatchEmbeddingProvider(dimensions=4)
                knowledge_base = PostgresRagKnowledgeBase(
                    connection,
                    settings=RagKnowledgeBaseSettings(
                        runtime_mode="production",
                        store_type="pgvector",
                        embedding_model="BAAI/bge-m3",
                        embedding_dimensions=4,
                        chunk_max_chars=200,
                        ingest_document_limit=document_limit,
                        ingest_chunk_limit=chunk_limit,
                    ),
                    embedding_provider=provider,
                )

                with self.assertRaisesRegex(ValueError, expected_error):
                    knowledge_base.upsert_documents(documents)

                self.assertEqual([], provider.batch_calls)
                self.assertEqual([], connection.statements)

    def test_pgvector_same_document_id_is_isolated_by_full_scope(self) -> None:
        """不同租户可使用相同 documentId，摄取和删除都不能覆盖另一租户的 chunk。"""

        connection = _FakePostgresConnection()
        knowledge_base = PostgresRagKnowledgeBase(
            connection,
            settings=RagKnowledgeBaseSettings(
                runtime_mode="production",
                store_type="pgvector",
                embedding_model="BAAI/bge-m3",
                embedding_dimensions=4,
            ),
            embedding_provider=DeterministicHashEmbeddingProvider(dimensions=4),
        )
        documents = tuple(
            RagDocument(
                document_id="shared-runbook",
                title=f"{tenant_id} 的操作手册",
                content=f"仅属于 {tenant_id} 的恢复步骤。",
                source_uri=f"test://{tenant_id}/shared-runbook",
                tenant_id=tenant_id,
                project_id="project-a",
                workspace_key="workspace-a",
                source_type=RagChunkSourceType.RUNBOOK,
            )
            for tenant_id in ("tenant-a", "tenant-b")
        )

        self.assertEqual(2, knowledge_base.upsert_documents(documents))
        self.assertEqual(2, len(connection.rows))
        self.assertEqual(2, len({row["chunk_id"] for row in connection.rows}))

        deleted = knowledge_base.delete_document(
            "shared-runbook",
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="workspace-a",
        )

        self.assertEqual(1, deleted)
        self.assertEqual(["tenant-b"], [row["tenant_id"] for row in connection.rows])

    def test_pgvector_retriever_reuses_database_vector_scores(self) -> None:
        """数据库已完成向量近邻计算后，检索器不能再次外发查询和全部候选正文做 Embedding。"""

        connection = _FakePostgresConnection()
        provider = _RecordingBatchEmbeddingProvider(dimensions=4)
        knowledge_base = PostgresRagKnowledgeBase(
            connection,
            settings=RagKnowledgeBaseSettings(
                runtime_mode="production",
                store_type="pgvector",
                embedding_model="BAAI/bge-m3",
                embedding_dimensions=4,
            ),
            embedding_provider=provider,
        )
        knowledge_base.upsert_documents(
            (
                RagDocument(
                    document_id="persistent-vector-doc",
                    title="持久向量复用",
                    content="字段映射错误应先核对来源字段和目标字段类型。",
                    source_uri="test://persistent-vector-doc",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="workspace-a",
                    source_type=RagChunkSourceType.RUNBOOK,
                ),
            )
        )
        provider.single_calls.clear()
        provider.batch_calls.clear()

        retriever = RagHybridRetriever(knowledge_base, embedding_provider=provider)
        retriever.retrieve(
            RagQuery(
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="workspace-a",
                actor_id="actor-a",
                question="如何修复字段映射错误",
            )
        )

        self.assertEqual(1, len(provider.single_calls))
        self.assertEqual([], provider.batch_calls)

    def test_environment_contract_treats_dedicated_rag_dsn_as_explicit_store(self) -> None:
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
        self.assertIn("embedding public.vector", RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL)
        self.assertIn("USING hnsw", RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL)
        self.assertIn("public.vector_cosine_ops", RAG_POSTGRESQL_KNOWLEDGE_SCHEMA_SQL)

    def test_repository_migration_installs_the_runtime_schema_contract(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        migration = (
            repository_root / "docker" / "postgresql" / "init" / "11-rag-knowledge-schema.sql"
        ).read_text(encoding="utf-8")

        self.assertIn("CREATE TABLE IF NOT EXISTS ai_memory.rag_knowledge_chunk", migration)
        self.assertIn("tenant_id VARCHAR(128) NOT NULL DEFAULT '*'", migration)
        self.assertIn("workspace_key VARCHAR(255) NOT NULL DEFAULT '*'", migration)
        self.assertIn("embedding public.vector", migration)
        self.assertIn("content_search_vector TSVECTOR GENERATED ALWAYS AS", migration)
        self.assertIn("idx_rag_knowledge_chunk_scoped_document", migration)
        self.assertIn("idx_rag_knowledge_chunk_bge_m3_hnsw", migration)

    def test_enterprise_corpus_source_types_are_explicit(self) -> None:
        """事故、任务案例和数据集必须保留可分层评测的来源类型。"""

        self.assertEqual("incident", RagChunkSourceType.INCIDENT.value)
        self.assertEqual("task_case", RagChunkSourceType.TASK_CASE.value)
        self.assertEqual("dataset", RagChunkSourceType.DATASET.value)

    def test_application_compose_enables_migration_managed_postgresql_rag(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        compose = (repository_root / "docker-compose.application.yml").read_text(encoding="utf-8")

        self.assertIn(
            "DATASMART_RAG_KNOWLEDGE_BASE: ${DATASMART_RAG_KNOWLEDGE_BASE:-postgresql}",
            compose,
        )
        self.assertIn(
            "DATASMART_RAG_INGEST_DOCUMENT_LIMIT: ${DATASMART_RAG_INGEST_DOCUMENT_LIMIT:-500}",
            compose,
        )
        self.assertIn(
            "DATASMART_RAG_INGEST_CHUNK_LIMIT: ${DATASMART_RAG_INGEST_CHUNK_LIMIT:-2000}",
            compose,
        )
        self.assertIn("DATASMART_RAG_POSTGRESQL_DSN:", compose)
        self.assertIn('DATASMART_RAG_SCHEMA_CHECK_ON_STARTUP: "true"', compose)
        self.assertIn(
            "DATASMART_RAG_EMBEDDING_ENDPOINT: ${DATASMART_RAG_EMBEDDING_ENDPOINT:-https://api.siliconflow.cn/v1/embeddings}",
            compose,
        )
        self.assertIn(
            "DATASMART_RAG_EMBEDDING_MODEL: ${DATASMART_RAG_EMBEDDING_MODEL:-BAAI/bge-m3}",
            compose,
        )
        self.assertIn(
            "DATASMART_RAG_RERANK_ENDPOINT: ${DATASMART_RAG_RERANK_ENDPOINT:-https://api.siliconflow.cn/v1/rerank}",
            compose,
        )
        self.assertIn(
            "DATASMART_RAG_RERANK_MODEL: ${DATASMART_RAG_RERANK_MODEL:-BAAI/bge-reranker-v2-m3}",
            compose,
        )
        self.assertIn("SILICONFLOW_API_KEY: ${SILICONFLOW_API_KEY:-}", compose)

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

    def test_rag_embedding_can_read_shared_siliconflow_secret_without_enabling_it_implicitly(self) -> None:
        provider = rag_embedding_provider_from_env(
            {
                "DATASMART_RAG_EMBEDDING_PROVIDER": "openai-compatible",
                "DATASMART_RAG_EMBEDDING_ENDPOINT": "https://api.siliconflow.cn/v1/embeddings",
                "DATASMART_RAG_EMBEDDING_MODEL": "BAAI/bge-m3",
                "DATASMART_RAG_EMBEDDING_DIMENSIONS": "1024",
                "SILICONFLOW_API_KEY": "unit-test-placeholder",
            }
        )

        self.assertIsNotNone(provider)
        assert provider is not None
        settings = getattr(provider, "_settings")
        self.assertEqual("unit-test-placeholder", settings.api_key)

        disabled = rag_embedding_provider_from_env(
            {
                "SILICONFLOW_API_KEY": "unit-test-placeholder",
            }
        )
        self.assertIsNone(disabled)

        with self.assertRaisesRegex(ValueError, "SILICONFLOW_API_KEY"):
            rag_embedding_provider_from_env(
                {
                    "DATASMART_RAG_EMBEDDING_PROVIDER": "openai-compatible",
                    "DATASMART_RAG_EMBEDDING_ENDPOINT": "https://untrusted.example/v1/embeddings",
                    "DATASMART_RAG_EMBEDDING_MODEL": "BAAI/bge-m3",
                    "DATASMART_RAG_EMBEDDING_DIMENSIONS": "1024",
                    "SILICONFLOW_API_KEY": "unit-test-placeholder",
                }
            )


class _FakePostgresConnection:
    """用于验证 SQL 装配和行映射合同的轻量 DB-API 测试替身。"""

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


def _rag_document(document_id: str, content: str) -> RagDocument:
    """构造摄取边界测试使用的同范围文档。"""

    return RagDocument(
        document_id=document_id,
        title=document_id,
        content=content,
        source_uri=f"test://{document_id}",
        tenant_id="tenant-a",
        project_id="project-a",
        workspace_key="workspace-a",
        source_type=RagChunkSourceType.RUNBOOK,
    )


class _RecordingBatchEmbeddingProvider:
    """记录调用方式的测试 Provider，用于区分单条调用与批量调用。"""

    def __init__(self, *, dimensions: int) -> None:
        self._dimensions = dimensions
        self.single_calls: list[str] = []
        self.batch_calls: list[tuple[str, ...]] = []

    def embed_text(self, text: str) -> tuple[float, ...]:
        """记录不期望发生的单条调用，便于回归测试给出明确失败原因。"""

        self.single_calls.append(text)
        return tuple(0.25 for _ in range(self._dimensions))

    def embed_texts(self, texts: tuple[str, ...]) -> tuple[tuple[float, ...], ...]:
        """一次返回全部输入对应的固定维度测试向量。"""

        self.batch_calls.append(texts)
        return tuple(
            tuple((index + 1) / 10 for _ in range(self._dimensions))
            for index, _ in enumerate(texts)
        )


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
            document_id, tenant_id, project_id, workspace_key = values[:4]
            before = len(self._connection.rows)
            self._connection.rows = [
                row
                for row in self._connection.rows
                if not (
                    row["document_id"] == document_id
                    and row["tenant_id"] == tenant_id
                    and row["project_id"] == project_id
                    and row["workspace_key"] == workspace_key
                )
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
            scope_offset = 1 if "WEBSEARCH_TO_TSQUERY" in normalized or "VECTOR_SCORE" in normalized else 0
            tenant_id, project_id, workspace_key = values[scope_offset : scope_offset + 3]
            matching_rows = [
                dict(row)
                for row in self._connection.rows
                if row["tenant_id"] in {"*", tenant_id}
                and row["project_id"] in {"*", project_id}
                and row["workspace_key"] in {"*", workspace_key}
            ]
            if "VECTOR_SCORE" in normalized:
                for row in matching_rows:
                    row["vector_score"] = 0.9
            self._rows = matching_rows
            return
        self._rows = []

    def fetchall(self):
        return tuple(self._rows)


if __name__ == "__main__":
    unittest.main()
