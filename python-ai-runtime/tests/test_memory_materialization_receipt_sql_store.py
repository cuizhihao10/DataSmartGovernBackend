import os
import sqlite3
import sys
import tempfile
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.memory import (
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_components import (
    AgentMemoryMaterializationReceiptStoreSettings,
    build_memory_materialization_receipt_store_runtime,
    memory_materialization_receipt_store_diagnostics,
    memory_materialization_receipt_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_sql_store import (
    SqlAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_store import (
    AgentMemoryMaterializationReceiptStatus,
)
from datasmart_ai_runtime.services.memory.memory_sql_store import SqlAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_write_candidate_store import InMemoryAgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory.memory_write_materializer import (
    AgentApprovedMemoryWriteMaterializer,
    AgentMemoryMaterializationOutcome,
)


class SqlAgentMemoryMaterializationReceiptStoreTest(unittest.TestCase):
    """长期记忆落成 receipt SQL store 测试。

    receipt 不是用户可见正文，而是后台执行证据。测试重点放在状态流转、attempt_count、持久化恢复和
    诊断脱敏，避免后续 outbox worker 在没有可靠执行证据的情况下上线。
    """

    def test_begin_succeed_fail_and_repeated_begin_are_persistent(self) -> None:
        """receipt 应支持开始、成功、再次开始、失败，并能跨 store 实例恢复。"""

        with tempfile.TemporaryDirectory() as temp_dir:
            sqlite_path = os.path.join(temp_dir, "receipts.sqlite3")
            connection = self._connection(sqlite_path)
            try:
                store = SqlAgentMemoryMaterializationReceiptStore(connection)
                first = store.begin(
                    candidate_id="candidate-a",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="tenant:tenant-a:project:project-a",
                    memory_namespace="memory:tenant:tenant-a:project:project-a",
                    worker_id="worker-a",
                )
                succeeded = store.succeed(
                    receipt_id=first.receipt_id,
                    memory_id="memory-a",
                    namespace=("memory-namespace", "memory:tenant:tenant-a:project:project-a"),
                    outcome="materialized",
                    message="已写入正式记忆。",
                )
                repeated = store.begin(
                    candidate_id="candidate-a",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="tenant:tenant-a:project:project-a",
                    memory_namespace="memory:tenant:tenant-a:project:project-a",
                    worker_id="worker-b",
                )
                failed = store.fail(receipt_id=first.receipt_id, error_message="RuntimeError: 模拟失败")
            finally:
                connection.close()

            second_connection = self._connection(sqlite_path)
            try:
                reloaded = SqlAgentMemoryMaterializationReceiptStore(second_connection).get_by_candidate_id(
                    "candidate-a"
                )
            finally:
                second_connection.close()

        self.assertEqual(AgentMemoryMaterializationReceiptStatus.STARTED, first.status)
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.SUCCEEDED, succeeded.status)
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.STARTED, repeated.status)
        self.assertEqual(2, repeated.attempt_count)
        self.assertEqual("worker-b", repeated.worker_id)
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.FAILED, failed.status)
        self.assertIsNotNone(reloaded)
        self.assertEqual(2, reloaded.attempt_count)
        self.assertEqual("RuntimeError: 模拟失败", reloaded.error_message)

    def test_materializer_can_record_receipt_in_sql_store(self) -> None:
        """materializer 使用 SQL receipt store 时，应把成功结果写入数据库。"""

        connection = self._connection(":memory:")
        self._formal_memory_schema(connection)
        candidate_store = InMemoryAgentMemoryWriteCandidateStore()
        candidate = self._candidate()
        candidate_store.save(candidate)
        receipt_store = SqlAgentMemoryMaterializationReceiptStore(connection)
        materializer = AgentApprovedMemoryWriteMaterializer(
            candidate_store=candidate_store,
            memory_store=SqlAgentMemoryStore(connection),
            receipt_store=receipt_store,
        )

        result = materializer.materialize("candidate-a")
        receipt = receipt_store.get_by_candidate_id("candidate-a")
        connection.close()

        self.assertEqual(AgentMemoryMaterializationOutcome.MATERIALIZED, result.outcome)
        self.assertIsNotNone(receipt)
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.SUCCEEDED, receipt.status)
        self.assertEqual(result.memory_id, receipt.memory_id)
        self.assertEqual(result.outcome.value, receipt.outcome)

    def test_runtime_builder_supports_default_sqlite_and_mysql_failure_modes(self) -> None:
        """receipt runtime builder 应支持默认内存、SQLite、MySQL fail-open 和 fail-fast。"""

        default_settings = memory_materialization_receipt_store_settings_from_env({})
        default_runtime = build_memory_materialization_receipt_store_runtime(default_settings)
        sqlite_settings = AgentMemoryMaterializationReceiptStoreSettings(store_type="sqlite")
        sqlite_runtime = build_memory_materialization_receipt_store_runtime(
            sqlite_settings,
            connection_factory=lambda _: self._connection(":memory:"),
        )
        mysql_fail_open = build_memory_materialization_receipt_store_runtime(
            AgentMemoryMaterializationReceiptStoreSettings(
                store_type="mysql",
                mysql_dsn="mysql://root:secret@localhost:3306/datasmart",
                fail_open=True,
            ),
            connection_factory=lambda _: (_ for _ in ()).throw(RuntimeError("模拟 receipt MySQL 不可用")),
        )
        diagnostics = memory_materialization_receipt_store_diagnostics(mysql_fail_open)

        self.assertFalse(default_runtime.persistent)
        self.assertTrue(sqlite_runtime.persistent)
        self.assertFalse(mysql_fail_open.persistent)
        self.assertTrue(diagnostics["fallback"])
        self.assertNotIn("secret", diagnostics["mysql"]["dsn"])
        with self.assertRaises(RuntimeError):
            build_memory_materialization_receipt_store_runtime(
                AgentMemoryMaterializationReceiptStoreSettings(
                    store_type="mysql",
                    mysql_dsn="host=localhost;user=root;password=secret;database=datasmart",
                    fail_open=False,
                ),
                connection_factory=lambda _: (_ for _ in ()).throw(RuntimeError("模拟生产 receipt 连接失败")),
            )

    @staticmethod
    def _connection(path: str) -> sqlite3.Connection:
        connection = sqlite3.connect(path)
        connection.row_factory = sqlite3.Row
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS agent_memory_materialization_receipt (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                receipt_id TEXT NOT NULL UNIQUE,
                candidate_id TEXT NOT NULL UNIQUE,
                tenant_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                workspace_key TEXT NOT NULL,
                memory_namespace TEXT NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 1,
                worker_id TEXT,
                memory_id TEXT,
                namespace_json TEXT,
                outcome TEXT,
                message TEXT,
                error_message TEXT,
                started_at TEXT,
                finished_at TEXT,
                create_time TEXT,
                update_time TEXT
            );
            """
        )
        return connection

    @staticmethod
    def _formal_memory_schema(connection: sqlite3.Connection) -> None:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS agent_memory_store_entry (
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

    @staticmethod
    def _candidate() -> AgentMemoryWriteCandidate:
        return AgentMemoryWriteCandidate(
            candidate_id="candidate-a",
            memory_type=AgentMemoryType.EPISODIC,
            scope=AgentMemoryScope.PROJECT,
            status=AgentMemoryWriteCandidateStatus.APPROVED,
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            title="手机号质量异常处理经验",
            content_summary="历史质量检测发现手机号格式异常，建议复用正则校验和空值兜底规则。",
            source="agent-runtime-tool-feedback",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            source_tool_name="quality.rule.suggest",
            source_status="succeeded",
            source_audit_id="audit-a",
            source_run_id="run-a",
            output_ref="minio://quality/private-result.json",
            retention_days=30,
            sensitivity_level="internal",
            idempotency_key="tenant-a|project-a|audit-a",
        )


if __name__ == "__main__":
    unittest.main()
