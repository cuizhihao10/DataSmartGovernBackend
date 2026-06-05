import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.memory import (
    AgentMemoryRecord,
    AgentMemoryScope,
    AgentMemoryType,
)
from datasmart_ai_runtime.services.memory.memory_chroma_adapter import (
    ChromaSemanticMemorySyncAdapter,
    DeterministicHashEmbeddingProvider,
)
from datasmart_ai_runtime.services.memory.memory_secondary_index import AgentMemorySecondaryIndexKind
from datasmart_ai_runtime.services.memory.memory_secondary_index_sync import (
    AgentMemorySecondaryIndexSyncAction,
    AgentMemorySecondaryIndexSyncScheduler,
    AgentMemorySecondaryIndexSyncStatus,
    AgentMemorySecondaryIndexSyncTask,
    AgentMemorySecondaryIndexSyncWorker,
    InMemoryAgentMemorySecondaryIndexSyncTaskStore,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStoreEntry, InMemoryAgentMemoryStore


class ChromaSemanticMemorySyncAdapterTest(unittest.TestCase):
    """Chroma-compatible semantic memory adapter 测试。"""

    def test_adapter_upserts_semantic_memory_with_required_metadata(self) -> None:
        """semantic/vector 任务应写入 Chroma-compatible collection，并携带 namespace metadata。"""

        memory_store = InMemoryAgentMemoryStore()
        entry = self._entry(AgentMemoryType.SEMANTIC)
        memory_store.save_if_absent(entry)
        collection = FakeChromaCollection()
        adapter = ChromaSemanticMemorySyncAdapter(
            memory_store=memory_store,
            collection=collection,
            embedding_provider=DeterministicHashEmbeddingProvider(dimensions=8),
        )

        result = adapter.sync(self._task(entry, AgentMemorySecondaryIndexKind.VECTOR))

        self.assertTrue(result.synced)
        self.assertEqual(("memory-semantic",), collection.ids)
        self.assertEqual(8, len(collection.embeddings[0]))
        self.assertIn("客户主数据字段含义", collection.documents[0])
        metadata = collection.metadatas[0]
        self.assertEqual("memory:tenant:tenant-a:project:project-a", metadata["memoryNamespace"])
        self.assertEqual("tenant:tenant-a:project:project-a", metadata["workspaceKey"])
        self.assertEqual("SUMMARY_ONLY_NO_RAW_TOOL_RESULT_NO_SQL_NO_SAMPLE_DATA", metadata["payloadPolicy"])

    def test_adapter_rejects_non_semantic_vector_task(self) -> None:
        """Chroma semantic adapter 不应误处理 episodic/procedural/resource 任务。"""

        memory_store = InMemoryAgentMemoryStore()
        entry = self._entry(AgentMemoryType.EPISODIC)
        memory_store.save_if_absent(entry)
        adapter = ChromaSemanticMemorySyncAdapter(
            memory_store=memory_store,
            collection=FakeChromaCollection(),
            embedding_provider=DeterministicHashEmbeddingProvider(),
        )

        result = adapter.sync(self._task(entry, AgentMemorySecondaryIndexKind.VECTOR))

        self.assertFalse(result.synced)
        self.assertIn("只处理 semantic memory", result.message)

    def test_worker_can_use_chroma_adapter_for_vector_task(self) -> None:
        """二级索引 worker 可以按 indexKind 使用 Chroma adapter。"""

        memory_store = InMemoryAgentMemoryStore()
        entry = self._entry(AgentMemoryType.SEMANTIC)
        memory_store.save_if_absent(entry)
        task_store = InMemoryAgentMemorySecondaryIndexSyncTaskStore()
        scheduler = AgentMemorySecondaryIndexSyncScheduler(store=task_store)
        scheduler.schedule_for_entry(entry)
        collection = FakeChromaCollection()
        worker = AgentMemorySecondaryIndexSyncWorker(
            store=task_store,
            adapters={
                AgentMemorySecondaryIndexKind.VECTOR: ChromaSemanticMemorySyncAdapter(
                    memory_store=memory_store,
                    collection=collection,
                    embedding_provider=DeterministicHashEmbeddingProvider(dimensions=8),
                )
            },
        )

        report = worker.run_once(limit=10)

        self.assertEqual(2, report.scanned_count)
        self.assertEqual(2, report.succeeded_count)
        self.assertEqual(("memory-semantic",), collection.ids)
        self.assertTrue(all(item["status"] == AgentMemorySecondaryIndexSyncStatus.SYNCED.value for item in report.items))

    @staticmethod
    def _entry(memory_type: AgentMemoryType) -> AgentMemoryStoreEntry:
        now = datetime.now(timezone.utc)
        return AgentMemoryStoreEntry(
            memory=AgentMemoryRecord(
                memory_id=f"memory-{memory_type.value}",
                memory_type=memory_type,
                scope=AgentMemoryScope.PROJECT,
                tenant_id="tenant-a",
                project_id="project-a",
                title="客户主数据字段含义",
                content="客户主数据字段用于描述客户编号、手机号和客户状态，适合进入语义记忆。",
                source="unit-test",
                sensitivity_level="internal",
                attributes={"payloadPolicy": "SUMMARY_ONLY_NO_RAW_TOOL_RESULT_NO_SQL_NO_SAMPLE_DATA"},
                created_at=now,
            ),
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            namespace=("memory-namespace", "memory:tenant:tenant-a:project:project-a", "type", memory_type.value),
            idempotency_key=f"tenant-a|project-a|{memory_type.value}",
            source_candidate_id=f"candidate-{memory_type.value}",
            expires_at=now + timedelta(days=30),
            materialized_at=now,
        )

    @staticmethod
    def _task(
        entry: AgentMemoryStoreEntry,
        index_kind: AgentMemorySecondaryIndexKind,
    ) -> AgentMemorySecondaryIndexSyncTask:
        return AgentMemorySecondaryIndexSyncTask(
            task_id=f"task-{entry.memory.memory_type.value}-{index_kind.value}",
            memory_id=entry.memory.memory_id,
            source_candidate_id=entry.source_candidate_id,
            memory_type=entry.memory.memory_type,
            index_kind=index_kind,
            action=AgentMemorySecondaryIndexSyncAction.UPSERT,
            tenant_id=entry.memory.tenant_id,
            project_id=entry.memory.project_id,
            session_id=entry.memory.session_id,
            workspace_key=entry.workspace_key,
            memory_namespace=entry.memory_namespace,
        )


class FakeChromaCollection:
    """测试用 Chroma collection 端口。"""

    def __init__(self) -> None:
        self.ids = ()
        self.embeddings = ()
        self.documents = ()
        self.metadatas = ()

    def upsert(self, *, ids, embeddings, documents, metadatas):
        self.ids = tuple(ids)
        self.embeddings = tuple(tuple(item) for item in embeddings)
        self.documents = tuple(documents)
        self.metadatas = tuple(dict(item) for item in metadatas)


if __name__ == "__main__":
    unittest.main()
