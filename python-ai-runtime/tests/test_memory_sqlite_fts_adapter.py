import os
import sqlite3
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryRecord,
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
    AgentMemoryType,
)
from datasmart_ai_runtime.services.memory.memory_secondary_index import (
    AgentMemorySecondaryIndexKind,
    AgentMemorySecondaryIndexQuery,
)
from datasmart_ai_runtime.services.memory.memory_sqlite_fts_adapter import (
    SQLiteFtsAgentMemorySecondaryIndex,
    sqlite_fts5_available,
    sqlite_fts_memory_index_diagnostics,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStoreEntry, InMemoryAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_store_retriever import StoreBackedAgentMemoryRetriever


class SQLiteFtsAgentMemorySecondaryIndexTest(unittest.TestCase):
    """SQLite FTS 长期记忆索引测试。

    这组测试关注的是闭环能力而不是搜索算法炫技：
    - FTS5 schema 能在本地初始化；
    - 索引命中必须受 tenant/project/session/memoryNamespace 保护；
    - 过期记忆不能被召回；
    - 索引层 attributes 只能返回低敏计数和状态，不能返回记忆正文；
    - `StoreBackedAgentMemoryRetriever` 可以把 KEYWORD 通道替换为真实 FTS adapter。
    """

    def test_fts5_capability_diagnostics_are_low_sensitive(self) -> None:
        """FTS5 诊断只能说明能力是否可用，不能暴露数据库路径或记忆内容。"""

        connection = sqlite3.connect(":memory:")
        diagnostics = sqlite_fts_memory_index_diagnostics(connection)

        self.assertTrue(sqlite_fts5_available(connection))
        self.assertTrue(diagnostics["fts5Available"])
        self.assertEqual("LOW_SENSITIVE_RUNTIME_CAPABILITY_ONLY", diagnostics["payloadPolicy"])
        self.assertNotIn("phone quality", str(diagnostics).lower())
        self.assertNotIn(":memory:", str(diagnostics).lower())

    def test_search_uses_fts_and_respects_workspace_namespace(self) -> None:
        """FTS 命中后仍必须按 workspace namespace 隔离，不能跨工作空间召回相同关键词。"""

        store = InMemoryAgentMemoryStore()
        workspace_a = self._entry(
            memory_id="memory-a",
            source_candidate_id="candidate-a",
            workspace_suffix="workspace-a",
            content="phone quality regex guardrail for customer profile repair",
        )
        workspace_b = self._entry(
            memory_id="memory-b",
            source_candidate_id="candidate-b",
            workspace_suffix="workspace-b",
            content="phone quality regex guardrail from another workspace",
        )
        unrelated = self._entry(
            memory_id="memory-c",
            source_candidate_id="candidate-c",
            workspace_suffix="workspace-a",
            content="invoice classification glossary and tax code mapping",
        )
        for entry in (workspace_a, workspace_b, unrelated):
            store.save_if_absent(entry)

        index = SQLiteFtsAgentMemorySecondaryIndex(connection=sqlite3.connect(":memory:"), memory_store=store)
        for entry in (workspace_a, workspace_b, unrelated):
            index.upsert_entry(entry)

        result = index.search(
            self._query(
                memory_namespace="memory:tenant:tenant-a:project:project-a:workspace:workspace-a",
                objective="please reuse phone quality guardrail",
                query_hint="phone regex",
            )
        )

        self.assertEqual(("candidate-a",), tuple(entry.source_candidate_id for entry in result.entries))
        self.assertEqual("SQLiteFtsAgentMemorySecondaryIndex", result.attributes["implementation"])
        self.assertEqual(1, result.attributes["candidateCount"])
        self.assertFalse(result.attributes["memoryBodyReturnedInAttributes"])
        self.assertNotIn("phone quality regex guardrail", str(result.attributes).lower())

    def test_expired_memory_is_removed_instead_of_indexed(self) -> None:
        """过期正式记忆写入 FTS 时应清理旧索引，避免历史残留被误召回。"""

        store = InMemoryAgentMemoryStore()
        expired = self._entry(
            memory_id="memory-expired",
            source_candidate_id="candidate-expired",
            workspace_suffix="workspace-a",
            content="expired phone quality incident",
            expires_at=datetime.now(timezone.utc) - timedelta(minutes=1),
        )
        index = SQLiteFtsAgentMemorySecondaryIndex(connection=sqlite3.connect(":memory:"), memory_store=store)

        upsert_result = index.upsert_entry(expired)
        result = index.search(
            self._query(
                memory_namespace="memory:tenant:tenant-a:project:project-a:workspace:workspace-a",
                objective="phone quality",
                query_hint="phone",
            )
        )

        self.assertFalse(upsert_result.indexed)
        self.assertTrue(upsert_result.removed_expired)
        self.assertEqual((), result.entries)
        self.assertEqual(0, index.diagnostics()["indexedMemoryCount"])

    def test_store_backed_retriever_can_use_sqlite_fts_keyword_channel(self) -> None:
        """正式检索器可以把 KEYWORD 二级索引替换为 SQLite FTS adapter。"""

        store = InMemoryAgentMemoryStore()
        matching = self._entry(
            memory_id="memory-d",
            source_candidate_id="candidate-d",
            workspace_suffix="default",
            content="sync retry backlog incident and phone quality remediation playbook",
        )
        non_matching = self._entry(
            memory_id="memory-e",
            source_candidate_id="candidate-e",
            workspace_suffix="default",
            content="metadata glossary lineage mapping only",
        )
        for entry in (matching, non_matching):
            store.save_if_absent(entry)
        index = SQLiteFtsAgentMemorySecondaryIndex(connection=sqlite3.connect(":memory:"), memory_store=store)
        index.upsert_entry(matching)
        index.upsert_entry(non_matching)

        report = StoreBackedAgentMemoryRetriever(
            store,
            secondary_indexes={AgentMemorySecondaryIndexKind.KEYWORD: index},
        ).retrieve(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="analyst-a",
                objective="find sync retry phone quality remediation experience",
            ),
            AgentMemoryPlan(
                retrieval_targets=(
                    AgentMemoryRetrievalTarget(
                        memory_type=AgentMemoryType.EPISODIC,
                        scope=AgentMemoryScope.PROJECT,
                        query_hint="sync retry phone remediation",
                        reason="验证 SQLite FTS 可以融入正式记忆检索器。",
                        max_items=3,
                    ),
                )
            ),
        )

        self.assertEqual(1, report.total_retrieved)
        self.assertEqual("candidate-d", report.results[0].memories[0].attributes["sourceCandidateId"])
        self.assertEqual("keyword", report.results[0].attributes["secondaryIndexKind"])
        self.assertEqual("SQLiteFtsAgentMemorySecondaryIndex", report.results[0].attributes["implementation"])
        self.assertIn("keyword", report.attributes["availableSecondaryIndexes"])

    @staticmethod
    def _query(*, memory_namespace: str, objective: str, query_hint: str) -> AgentMemorySecondaryIndexQuery:
        return AgentMemorySecondaryIndexQuery(
            target=AgentMemoryRetrievalTarget(
                memory_type=AgentMemoryType.EPISODIC,
                scope=AgentMemoryScope.PROJECT,
                query_hint=query_hint,
                reason="验证 SQLite FTS 长期记忆索引。",
                max_items=5,
            ),
            tenant_id="tenant-a",
            project_id="project-a",
            session_id=None,
            memory_namespace=memory_namespace,
            objective=objective,
            index_kind=AgentMemorySecondaryIndexKind.KEYWORD,
            candidate_limit=20,
        )

    @staticmethod
    def _entry(
        *,
        memory_id: str,
        source_candidate_id: str,
        workspace_suffix: str,
        content: str,
        expires_at: datetime | None = None,
    ) -> AgentMemoryStoreEntry:
        now = datetime.now(timezone.utc)
        workspace_key = (
            "tenant:tenant-a:project:project-a"
            if workspace_suffix == "default"
            else f"tenant:tenant-a:project:project-a:workspace:{workspace_suffix}"
        )
        return AgentMemoryStoreEntry(
            memory=AgentMemoryRecord(
                memory_id=memory_id,
                memory_type=AgentMemoryType.EPISODIC,
                scope=AgentMemoryScope.PROJECT,
                tenant_id="tenant-a",
                project_id="project-a",
                title=f"memory title {source_candidate_id}",
                content=content,
                source="unit-test",
                importance_score=0.8,
                tags=("memory", "incident"),
                created_at=now,
                attributes={"sourceCandidateId": source_candidate_id},
            ),
            workspace_key=workspace_key,
            memory_namespace=f"memory:{workspace_key}",
            namespace=("memory-namespace", f"memory:{workspace_key}", "type", "episodic"),
            idempotency_key=f"tenant-a|project-a|{source_candidate_id}",
            source_candidate_id=source_candidate_id,
            expires_at=expires_at or now + timedelta(days=30),
            materialized_at=now,
        )


if __name__ == "__main__":
    unittest.main()
