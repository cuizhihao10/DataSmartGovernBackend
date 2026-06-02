import os
import sqlite3
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.memory.memory_materialization_lease_components import (
    AgentMemoryMaterializationLeaseStoreSettings,
    build_memory_materialization_lease_store_runtime,
    memory_materialization_lease_store_diagnostics,
    memory_materialization_lease_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_sql_store import (
    SqlAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLeaseStatus,
    InMemoryAgentMemoryMaterializationLeaseStore,
)


class AgentMemoryMaterializationLeaseStoreTest(unittest.TestCase):
    """长期记忆落成租约 store 测试。

    测试关注并发控制语义，而不是正式记忆正文：
    - 有效租约期间其他 worker 不能重复领取；
    - 租约过期后其他 worker 可以接管；
    - 旧 token 不能覆盖新 worker 的结果；
    - 成功终态不再被重复领取；
    - SQL 实现可以跨 store 实例恢复。
    """

    def test_in_memory_store_rejects_active_competitor_and_fences_expired_worker(self) -> None:
        """有效租约应互斥；过期接管后旧 worker 必须被 fencing 拒绝。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        started_at = datetime(2026, 6, 3, 1, 0, tzinfo=timezone.utc)

        first = self._acquire(store, worker_id="worker-a", now=started_at, lease_seconds=30)
        blocked = self._acquire(store, worker_id="worker-b", now=started_at + timedelta(seconds=10))
        second = self._acquire(store, worker_id="worker-b", now=started_at + timedelta(seconds=31))

        self.assertIsNotNone(first)
        self.assertIsNone(blocked)
        self.assertIsNotNone(second)
        self.assertNotEqual(first.lease_token, second.lease_token)
        self.assertEqual(2, second.attempt_count)
        with self.assertRaisesRegex(RuntimeError, "fencing"):
            store.succeed(
                candidate_id="candidate-a",
                lease_token=first.lease_token,
                memory_id="memory-a",
                outcome="materialized",
                message="旧 worker 不应覆盖新 worker。",
            )

    def test_in_memory_store_success_is_terminal(self) -> None:
        """成功终态应阻止后续 Runner 重复领取同一候选。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        lease = self._acquire(store, worker_id="worker-a")
        stored = store.succeed(
            candidate_id="candidate-a",
            lease_token=lease.lease_token,
            memory_id="memory-a",
            outcome="materialized",
            message="已写入正式长期记忆。",
        )
        repeated = self._acquire(store, worker_id="worker-b", now=datetime.now(timezone.utc) + timedelta(days=1))

        self.assertEqual(AgentMemoryMaterializationLeaseStatus.SUCCEEDED, stored.status)
        self.assertIsNone(repeated)
        self.assertNotIn("leaseToken", stored.to_summary())
        self.assertTrue(stored.to_summary()["leaseTokenPresent"])

    def test_in_memory_store_failed_lease_can_be_retried(self) -> None:
        """失败租约应先进入退避窗口，到达 nextRetryAt 后才能重新领取。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        started_at = datetime(2026, 6, 3, 1, 0, tzinfo=timezone.utc)
        first = self._acquire(store, worker_id="worker-a", now=started_at)
        failed = store.fail(
            candidate_id="candidate-a",
            lease_token=first.lease_token,
            error_message="RuntimeError: 模拟外部存储抖动",
            max_attempts=3,
            retry_base_seconds=30,
            retry_max_seconds=300,
            now=started_at + timedelta(seconds=1),
        )
        blocked = self._acquire(store, worker_id="worker-b", now=started_at + timedelta(seconds=10))
        second = self._acquire(store, worker_id="worker-b", now=started_at + timedelta(seconds=31))

        self.assertEqual(AgentMemoryMaterializationLeaseStatus.FAILED, failed.status)
        self.assertEqual((started_at + timedelta(seconds=31)).isoformat(), failed.next_retry_at.isoformat())
        self.assertIsNone(blocked)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.LEASED, second.status)
        self.assertEqual(2, second.attempt_count)

    def test_in_memory_store_moves_to_dead_letter_after_max_attempts(self) -> None:
        """达到最大尝试次数后应进入 DLQ，后续自动 Runner 不再领取。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        lease = self._acquire(store, worker_id="worker-a")
        dead_letter = store.fail(
            candidate_id="candidate-a",
            lease_token=lease.lease_token,
            error_message="RuntimeError: 模拟持续失败候选",
            max_attempts=1,
        )
        repeated = self._acquire(store, worker_id="worker-b", now=datetime.now(timezone.utc) + timedelta(days=1))

        self.assertEqual(AgentMemoryMaterializationLeaseStatus.DEAD_LETTER, dead_letter.status)
        self.assertIsNone(dead_letter.next_retry_at)
        self.assertIsNone(repeated)

    def test_sql_store_persists_lease_and_fencing_semantics(self) -> None:
        """SQL lease store 应跨实例恢复，并执行相同 token fencing 规则。"""

        with tempfile.TemporaryDirectory() as temp_dir:
            sqlite_path = os.path.join(temp_dir, "leases.sqlite3")
            first_connection = self._connection(sqlite_path)
            first_store = SqlAgentMemoryMaterializationLeaseStore(first_connection)
            started_at = datetime(2026, 6, 3, 1, 0, tzinfo=timezone.utc)
            first = self._acquire(first_store, worker_id="worker-a", now=started_at, lease_seconds=30)
            first_connection.close()

            second_connection = self._connection(sqlite_path)
            second_store = SqlAgentMemoryMaterializationLeaseStore(second_connection)
            blocked = self._acquire(second_store, worker_id="worker-b", now=started_at + timedelta(seconds=10))
            second = self._acquire(second_store, worker_id="worker-b", now=started_at + timedelta(seconds=31))
            with self.assertRaisesRegex(RuntimeError, "fencing"):
                second_store.fail(
                    candidate_id="candidate-a",
                    lease_token=first.lease_token,
                    error_message="旧 worker 失败回写不应覆盖新 worker。",
                )
            succeeded = second_store.succeed(
                candidate_id="candidate-a",
                lease_token=second.lease_token,
                memory_id="memory-a",
                outcome="materialized",
                message="已写入正式长期记忆。",
            )
            second_connection.close()

            third_connection = self._connection(sqlite_path)
            reloaded = SqlAgentMemoryMaterializationLeaseStore(third_connection).get_by_candidate_id("candidate-a")
            third_connection.close()

        self.assertIsNone(blocked)
        self.assertEqual(2, second.attempt_count)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.SUCCEEDED, succeeded.status)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.SUCCEEDED, reloaded.status)
        self.assertEqual("memory-a", reloaded.memory_id)

    def test_sql_store_respects_retry_cooldown_and_dead_letter(self) -> None:
        """SQL lease store 应持久化 next_retry_at，并在达到上限后进入 DLQ。"""

        with tempfile.TemporaryDirectory() as temp_dir:
            sqlite_path = os.path.join(temp_dir, "leases.sqlite3")
            connection = self._connection(sqlite_path)
            store = SqlAgentMemoryMaterializationLeaseStore(connection)
            started_at = datetime(2026, 6, 3, 1, 0, tzinfo=timezone.utc)
            first = self._acquire(store, worker_id="worker-a", now=started_at)
            failed = store.fail(
                candidate_id="candidate-a",
                lease_token=first.lease_token,
                error_message="RuntimeError: 模拟外部存储抖动",
                max_attempts=3,
                retry_base_seconds=30,
                retry_max_seconds=300,
                now=started_at + timedelta(seconds=1),
            )
            blocked = self._acquire(store, worker_id="worker-b", now=started_at + timedelta(seconds=10))
            second = self._acquire(store, worker_id="worker-b", now=started_at + timedelta(seconds=31))
            dead_letter = store.fail(
                candidate_id="candidate-a",
                lease_token=second.lease_token,
                error_message="RuntimeError: 模拟第二次即进入 DLQ",
                max_attempts=2,
                retry_base_seconds=30,
                retry_max_seconds=300,
                now=started_at + timedelta(seconds=32),
            )
            repeated = self._acquire(store, worker_id="worker-c", now=started_at + timedelta(days=1))
            connection.close()

        self.assertEqual(AgentMemoryMaterializationLeaseStatus.FAILED, failed.status)
        self.assertIsNotNone(failed.next_retry_at)
        self.assertIsNone(blocked)
        self.assertEqual(2, second.attempt_count)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.DEAD_LETTER, dead_letter.status)
        self.assertIsNone(dead_letter.next_retry_at)
        self.assertIsNone(repeated)

    def test_sql_store_lists_and_requeues_compensation_leases(self) -> None:
        """SQL lease store 应支持管理员补偿列表和条件重排。"""

        with tempfile.TemporaryDirectory() as temp_dir:
            sqlite_path = os.path.join(temp_dir, "leases.sqlite3")
            connection = self._connection(sqlite_path)
            store = SqlAgentMemoryMaterializationLeaseStore(connection)
            started_at = datetime(2026, 6, 3, 2, 0, tzinfo=timezone.utc)
            failed_lease = self._acquire_candidate(store, "candidate-failed", "worker-a", now=started_at)
            store.fail(
                candidate_id="candidate-failed",
                lease_token=failed_lease.lease_token,
                error_message="RuntimeError: 模拟短暂失败",
                max_attempts=5,
                retry_base_seconds=30,
                now=started_at + timedelta(seconds=1),
            )
            dead_letter_lease = self._acquire_candidate(store, "candidate-dlq", "worker-a", now=started_at)
            store.fail(
                candidate_id="candidate-dlq",
                lease_token=dead_letter_lease.lease_token,
                error_message="RuntimeError: 模拟持续失败",
                max_attempts=1,
                now=started_at + timedelta(seconds=2),
            )
            succeeded_lease = self._acquire_candidate(store, "candidate-succeeded", "worker-a", now=started_at)
            store.succeed(
                candidate_id="candidate-succeeded",
                lease_token=succeeded_lease.lease_token,
                memory_id="memory-succeeded",
                outcome="materialized",
                message="测试成功终态",
                now=started_at + timedelta(seconds=3),
            )

            leases = store.list_for_compensation(project_id="project-a", limit=10)
            scheduled = store.schedule_retry(
                candidate_id="candidate-dlq",
                operator_id="admin-a",
                reason="修复历史 schema 后重新物化",
                next_retry_at=started_at + timedelta(seconds=60),
                now=started_at + timedelta(seconds=4),
            )
            blocked = self._acquire_candidate(
                store,
                "candidate-dlq",
                "worker-b",
                now=started_at + timedelta(seconds=30),
            )
            claimed = self._acquire_candidate(
                store,
                "candidate-dlq",
                "worker-b",
                now=started_at + timedelta(seconds=61),
            )
            connection.close()

        self.assertEqual({"candidate-failed", "candidate-dlq"}, {lease.candidate_id for lease in leases})
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.FAILED, scheduled.status)
        self.assertEqual((started_at + timedelta(seconds=60)).isoformat(), scheduled.next_retry_at.isoformat())
        self.assertIsNone(blocked)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.LEASED, claimed.status)
        self.assertEqual(2, claimed.attempt_count)

    def test_runtime_builder_supports_default_sqlite_and_mysql_failure_modes(self) -> None:
        """lease runtime builder 应支持默认内存、SQLite、MySQL fail-open 和 fail-fast。"""

        default_settings = memory_materialization_lease_store_settings_from_env({})
        default_runtime = build_memory_materialization_lease_store_runtime(default_settings)
        sqlite_runtime = build_memory_materialization_lease_store_runtime(
            AgentMemoryMaterializationLeaseStoreSettings(store_type="sqlite"),
            connection_factory=lambda _: self._connection(":memory:"),
        )
        mysql_fail_open = build_memory_materialization_lease_store_runtime(
            AgentMemoryMaterializationLeaseStoreSettings(
                store_type="mysql",
                mysql_dsn="mysql://root:secret@localhost:3306/datasmart",
                fail_open=True,
            ),
            connection_factory=lambda _: (_ for _ in ()).throw(RuntimeError("模拟 lease MySQL 不可用")),
        )
        diagnostics = memory_materialization_lease_store_diagnostics(mysql_fail_open)

        self.assertEqual(60, default_settings.default_lease_seconds)
        self.assertEqual(5, default_settings.max_attempts)
        self.assertEqual(30, default_settings.retry_base_seconds)
        self.assertEqual(3600, default_settings.retry_max_seconds)
        self.assertFalse(default_runtime.persistent)
        self.assertTrue(sqlite_runtime.persistent)
        self.assertFalse(mysql_fail_open.persistent)
        self.assertTrue(diagnostics["fallback"])
        self.assertNotIn("secret", diagnostics["mysql"]["dsn"])
        with self.assertRaises(RuntimeError):
            build_memory_materialization_lease_store_runtime(
                AgentMemoryMaterializationLeaseStoreSettings(
                    store_type="mysql",
                    mysql_dsn="host=localhost;user=root;password=secret;database=datasmart",
                    fail_open=False,
                ),
                connection_factory=lambda _: (_ for _ in ()).throw(RuntimeError("模拟生产 lease 连接失败")),
            )

    @staticmethod
    def _acquire(store, *, worker_id: str, now: datetime | None = None, lease_seconds: int = 60):
        """使用固定候选范围尝试领取。"""

        return store.try_acquire(
            candidate_id="candidate-a",
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            worker_id=worker_id,
            lease_seconds=lease_seconds,
            now=now,
        )

    @staticmethod
    def _acquire_candidate(
        store,
        candidate_id: str,
        worker_id: str,
        *,
        now: datetime | None = None,
        lease_seconds: int = 60,
    ):
        """使用指定候选 ID 领取测试 lease，便于同一用例构造多种状态。"""

        return store.try_acquire(
            candidate_id=candidate_id,
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            worker_id=worker_id,
            lease_seconds=lease_seconds,
            now=now,
        )

    @staticmethod
    def _connection(path: str) -> sqlite3.Connection:
        """创建带 lease schema 的 SQLite 测试连接。"""

        connection = sqlite3.connect(path)
        connection.row_factory = sqlite3.Row
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS agent_memory_materialization_lease (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lease_id TEXT NOT NULL UNIQUE,
                candidate_id TEXT NOT NULL UNIQUE,
                tenant_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                workspace_key TEXT NOT NULL,
                memory_namespace TEXT NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 1,
                worker_id TEXT NOT NULL,
                lease_token TEXT NOT NULL,
                leased_until TEXT NOT NULL,
                next_retry_at TEXT,
                memory_id TEXT,
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


if __name__ == "__main__":
    unittest.main()
