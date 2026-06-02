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
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_store import (
    InMemoryAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_sql_store import SqlAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_store_retriever import StoreBackedAgentMemoryRetriever
from datasmart_ai_runtime.services.memory.memory_write_candidate_store import InMemoryAgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory.memory_write_materializer import (
    AgentApprovedMemoryWriteMaterializer,
    AgentMemoryMaterializationOutcome,
)


class SqlAgentMemoryStoreTest(unittest.TestCase):
    """正式长期记忆 SQL store 测试。

    这里用 sqlite3 验证 DB-API 语义，不代表生产选择 SQLite。生产部署应按 MySQL migration 建表，
    并通过 MySQL 驱动或未来 Java memory-service 连接同一张正式记忆控制面表。
    """

    def test_materialized_memory_survives_store_recreation_and_can_be_retrieved(self) -> None:
        """已落成记忆应跨 store 实例恢复，并可被同 workspace 后续请求召回。"""

        connection = self._connection()
        candidate_store = InMemoryAgentMemoryWriteCandidateStore()
        candidate_store.save(self._candidate())
        materializer = AgentApprovedMemoryWriteMaterializer(
            candidate_store=candidate_store,
            memory_store=SqlAgentMemoryStore(connection),
            receipt_store=InMemoryAgentMemoryMaterializationReceiptStore(),
        )

        result = materializer.materialize("candidate-a")
        reloaded_store = SqlAgentMemoryStore(connection)
        loaded = reloaded_store.get_by_candidate_id("candidate-a")
        report = StoreBackedAgentMemoryRetriever(reloaded_store).retrieve(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="analyst-b",
                objective="请参考手机号质量异常治理经验",
            ),
            self._memory_plan(),
        )

        self.assertEqual(AgentMemoryMaterializationOutcome.MATERIALIZED, result.outcome)
        self.assertIsNotNone(loaded)
        self.assertEqual(result.memory_id, loaded.memory.memory_id)
        self.assertEqual(1, report.total_retrieved)
        self.assertEqual(result.memory_id, report.results[0].memories[0].memory_id)

    def test_repeated_save_uses_idempotency_key_and_source_candidate_id(self) -> None:
        """重复 materialize 同一候选时应返回已有正式记忆，而不是插入重复记录。"""

        connection = self._connection()
        candidate_store = InMemoryAgentMemoryWriteCandidateStore()
        candidate_store.save(self._candidate())
        memory_store = SqlAgentMemoryStore(connection)
        materializer = AgentApprovedMemoryWriteMaterializer(
            candidate_store=candidate_store,
            memory_store=memory_store,
            receipt_store=InMemoryAgentMemoryMaterializationReceiptStore(),
        )

        first = materializer.materialize("candidate-a")
        second = materializer.materialize("candidate-a")

        self.assertEqual(first.memory_id, second.memory_id)
        self.assertEqual(AgentMemoryMaterializationOutcome.ALREADY_MATERIALIZED, second.outcome)
        row_count = connection.execute("SELECT COUNT(*) FROM agent_memory_store_entry").fetchone()[0]
        self.assertEqual(1, row_count)

    def test_datetime_values_are_stored_in_mysql_datetime3_friendly_format(self) -> None:
        """写入数据库的时间应贴近 MySQL DATETIME(3)，同时读取时恢复为 UTC aware datetime。"""

        connection = self._connection()
        candidate_store = InMemoryAgentMemoryWriteCandidateStore()
        candidate_store.save(self._candidate())
        memory_store = SqlAgentMemoryStore(connection)
        materializer = AgentApprovedMemoryWriteMaterializer(
            candidate_store=candidate_store,
            memory_store=memory_store,
            receipt_store=InMemoryAgentMemoryMaterializationReceiptStore(),
        )

        materializer.materialize("candidate-a")
        raw = connection.execute(
            "SELECT created_at, expires_at, materialized_at FROM agent_memory_store_entry"
        ).fetchone()
        loaded = memory_store.get_by_candidate_id("candidate-a")

        # MySQL DATETIME(3) 更适合 `YYYY-MM-DD HH:mm:ss.SSS`，不带 `T`、`Z` 或 `+00:00` 时区后缀。
        for value in raw:
            self.assertRegex(value, r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$")
        self.assertIsNotNone(loaded)
        self.assertEqual(timezone.utc, loaded.expires_at.tzinfo)

    def test_search_respects_workspace_namespace_and_expiry(self) -> None:
        """SQL 检索必须执行 workspace namespace 隔离并排除过期记忆。"""

        connection = self._connection()
        candidate_store = InMemoryAgentMemoryWriteCandidateStore()
        candidate_store.save(
            self._candidate(
                candidate_id="candidate-a",
                workspace_key="tenant:tenant-a:project:project-a:workspace:workspace-a",
                memory_namespace="memory:tenant:tenant-a:project:project-a:workspace:workspace-a",
            )
        )
        candidate_store.save(
            self._candidate(
                candidate_id="candidate-b",
                workspace_key="tenant:tenant-a:project:project-a:workspace:workspace-b",
                memory_namespace="memory:tenant:tenant-a:project:project-a:workspace:workspace-b",
                idempotency_key="tenant-a|project-a|audit-b",
                source_audit_id="audit-b",
            )
        )
        memory_store = SqlAgentMemoryStore(connection)
        materializer = AgentApprovedMemoryWriteMaterializer(
            candidate_store=candidate_store,
            memory_store=memory_store,
            receipt_store=InMemoryAgentMemoryMaterializationReceiptStore(),
        )
        materializer.materialize("candidate-a")
        materializer.materialize("candidate-b")
        connection.execute(
            "UPDATE agent_memory_store_entry SET expires_at = ? WHERE source_candidate_id = ?",
            ((datetime.now(timezone.utc) - timedelta(days=1)).isoformat(), "candidate-b"),
        )
        connection.commit()

        workspace_a = memory_store.search(
            memory_type=AgentMemoryType.EPISODIC,
            scope=AgentMemoryScope.PROJECT,
            tenant_id="tenant-a",
            project_id="project-a",
            session_id=None,
            memory_namespace="memory:tenant:tenant-a:project:project-a:workspace:workspace-a",
        )
        workspace_b = memory_store.search(
            memory_type=AgentMemoryType.EPISODIC,
            scope=AgentMemoryScope.PROJECT,
            tenant_id="tenant-a",
            project_id="project-a",
            session_id=None,
            memory_namespace="memory:tenant:tenant-a:project:project-a:workspace:workspace-b",
        )

        self.assertEqual(("candidate-a",), tuple(entry.source_candidate_id for entry in workspace_a))
        self.assertEqual((), workspace_b)

    @staticmethod
    def _connection() -> sqlite3.Connection:
        connection = sqlite3.connect(":memory:")
        connection.row_factory = sqlite3.Row
        connection.executescript(
            """
            CREATE TABLE agent_memory_store_entry (
                memory_id TEXT PRIMARY KEY,
                tenant_id TEXT,
                project_id TEXT,
                session_id TEXT,
                memory_type TEXT NOT NULL,
                scope TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT,
                importance_score REAL NOT NULL,
                sensitivity_level TEXT NOT NULL,
                tags_json TEXT,
                created_at TEXT,
                attributes_json TEXT,
                workspace_key TEXT NOT NULL,
                memory_namespace TEXT NOT NULL,
                namespace_json TEXT,
                idempotency_key TEXT NOT NULL UNIQUE,
                source_candidate_id TEXT NOT NULL UNIQUE,
                expires_at TEXT NOT NULL,
                materialized_at TEXT NOT NULL
            );
            """
        )
        return connection

    @staticmethod
    def _memory_plan() -> AgentMemoryPlan:
        return AgentMemoryPlan(
            retrieval_targets=(
                AgentMemoryRetrievalTarget(
                    memory_type=AgentMemoryType.EPISODIC,
                    scope=AgentMemoryScope.PROJECT,
                    query_hint="手机号 质量异常 治理经验",
                    reason="验证 SQL 正式记忆 store 可以被后续 Agent 请求召回。",
                ),
            )
        )

    @staticmethod
    def _candidate(
        *,
        candidate_id: str = "candidate-a",
        workspace_key: str = "tenant:tenant-a:project:project-a",
        memory_namespace: str = "memory:tenant:tenant-a:project:project-a",
        idempotency_key: str = "tenant-a|project-a|audit-a",
        source_audit_id: str = "audit-a",
    ) -> AgentMemoryWriteCandidate:
        return AgentMemoryWriteCandidate(
            candidate_id=candidate_id,
            memory_type=AgentMemoryType.EPISODIC,
            scope=AgentMemoryScope.PROJECT,
            status=AgentMemoryWriteCandidateStatus.APPROVED,
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            title="手机号质量异常处理经验",
            content_summary="历史质量检测发现手机号格式异常，建议复用正则校验和空值兜底规则。",
            source="agent-runtime-tool-feedback",
            workspace_key=workspace_key,
            memory_namespace=memory_namespace,
            source_tool_name="quality.rule.suggest",
            source_status="succeeded",
            source_audit_id=source_audit_id,
            source_run_id="run-a",
            output_ref="minio://quality/private-result.json",
            retention_days=30,
            sensitivity_level="internal",
            idempotency_key=idempotency_key,
        )


if __name__ == "__main__":
    unittest.main()
