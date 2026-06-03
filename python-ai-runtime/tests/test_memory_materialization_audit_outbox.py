import os
import sqlite3
import sys
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox import (
    AgentMemoryMaterializationAuditOutboxError,
    AgentMemoryMaterializationAuditOutboxRecorder,
    InMemoryAgentMemoryMaterializationAuditOutboxStore,
    audit_record_from_runtime_event,
)
from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox_components import (
    AgentMemoryMaterializationAuditOutboxSettings,
    build_memory_materialization_audit_outbox_runtime,
    memory_materialization_audit_outbox_diagnostics,
    memory_materialization_audit_outbox_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox_sql_store import (
    SqlAgentMemoryMaterializationAuditOutboxStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_events import (
    memory_materialization_requeue_event,
    memory_materialization_runner_event,
)
from datasmart_ai_runtime.services.memory.memory_materialization_runner import (
    AgentMemoryMaterializationRunnerItem,
    AgentMemoryMaterializationRunnerItemStatus,
    AgentMemoryMaterializationRunnerReport,
)
from datasmart_ai_runtime.services.memory.memory_materialization_admin import (
    AgentMemoryMaterializationRequeueResult,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLease,
    AgentMemoryMaterializationLeaseStatus,
)


class AgentMemoryMaterializationAuditOutboxTest(unittest.TestCase):
    """长期记忆物化审计 outbox 契约测试。

    这组测试关注“审计事实如何形成、如何持久化、失败时如何按策略处理”。
    它不测试真正的 Java 审计派发，因为 dispatcher 还不是本阶段能力；当前只先把 outbox 写入边界固定住。
    """

    def test_record_from_requeue_event_uses_candidate_as_aggregate_id(self) -> None:
        """补偿审计记录应围绕 candidateId 聚合，并且不保存敏感正文。"""

        event = memory_materialization_requeue_event(self._requeue_result(dry_run=False))
        record = audit_record_from_runtime_event(event)

        self.assertEqual("memory_materialization_requeue_recorded", record.event_type)
        self.assertEqual("memory_materialization_compensation_audit", record.event_purpose)
        self.assertEqual("candidate-a", record.aggregate_id)
        self.assertEqual("scheduled_retry", record.action)
        self.assertFalse(record.dry_run)
        self.assertEqual("audit", record.severity)
        self.assertIn("attributes", record.payload)
        self.assertNotIn("leaseToken", str(record.payload))
        self.assertNotIn("原始工具输出", str(record.payload))

    def test_recorder_disabled_does_not_write_store(self) -> None:
        """默认关闭时 recorder 应返回稳定摘要，不产生隐藏写入副作用。"""

        store = InMemoryAgentMemoryMaterializationAuditOutboxStore()
        recorder = AgentMemoryMaterializationAuditOutboxRecorder(store=store, enabled=False)

        delivery = recorder.record_runtime_event(memory_materialization_runner_event(self._runner_report()))

        self.assertFalse(delivery["enabled"])
        self.assertFalse(delivery["stored"])
        self.assertEqual(0, len(store.list_recent()))

    def test_required_recorder_raises_when_store_fails(self) -> None:
        """required=True 时，审计写入失败必须显式抛错，不能静默降级。"""

        recorder = AgentMemoryMaterializationAuditOutboxRecorder(
            store=_FailingAuditOutboxStore(),
            enabled=True,
            required=True,
        )

        with self.assertRaises(AgentMemoryMaterializationAuditOutboxError) as context:
            recorder.record_runtime_event(memory_materialization_runner_event(self._runner_report()))

        self.assertTrue(context.exception.delivery["enabled"])
        self.assertTrue(context.exception.delivery["required"])
        self.assertFalse(context.exception.delivery["stored"])

    def test_sql_store_appends_and_lists_recent_records(self) -> None:
        """SQL outbox 应能保存并按时间倒序读回审计记录。"""

        connection = self._sqlite_connection_with_schema()
        store = SqlAgentMemoryMaterializationAuditOutboxStore(connection)
        first = audit_record_from_runtime_event(memory_materialization_runner_event(self._runner_report("worker-a")))
        second = replace(
            audit_record_from_runtime_event(memory_materialization_runner_event(self._runner_report("worker-b"))),
            created_at=first.created_at + timedelta(seconds=1),
            updated_at=first.updated_at + timedelta(seconds=1),
        )

        store.append(first)
        store.append(second)
        recent = store.list_recent(limit=2)

        self.assertEqual((second.outbox_id, first.outbox_id), tuple(record.outbox_id for record in recent))
        self.assertEqual("worker-b", recent[0].aggregate_id)
        self.assertEqual("pending", recent[0].delivery_status.value)
        self.assertEqual("batch_completed", recent[0].action)

    def test_settings_and_diagnostics_explain_fail_closed_boundary(self) -> None:
        """环境变量应能启用 outbox、required 和 SQL store 选择，并输出低敏诊断。"""

        settings = memory_materialization_audit_outbox_settings_from_env(
            {
                "DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_ENABLED": "true",
                "DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_REQUIRED": "true",
                "DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE": "sqlite",
                "DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_SQLITE_PATH": ":memory:",
                "DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE_FAIL_OPEN": "false",
            }
        )
        runtime = build_memory_materialization_audit_outbox_runtime(
            settings,
            connection_factory=lambda _: self._sqlite_connection_with_schema(),
        )
        diagnostics = memory_materialization_audit_outbox_diagnostics(runtime)

        self.assertTrue(settings.enabled)
        self.assertTrue(settings.required)
        self.assertEqual("sqlite", settings.store_type)
        self.assertTrue(diagnostics["enabled"])
        self.assertTrue(diagnostics["required"])
        self.assertTrue(diagnostics["persistent"])
        self.assertFalse(diagnostics["storeFailOpen"])

    @staticmethod
    def _sqlite_connection_with_schema() -> sqlite3.Connection:
        """创建 SQLite 测试表。

        SQLite 没有 MySQL 的 JSON/TINYINT 类型语义，这里使用 TEXT/INTEGER 近似即可；SQL store 只依赖 DB-API
        参数化写入和字段名读取，不依赖 MySQL 方言特性。
        """

        connection = sqlite3.connect(":memory:")
        connection.row_factory = sqlite3.Row
        connection.executescript(
            """
            CREATE TABLE agent_memory_materialization_audit_outbox (
                outbox_id TEXT PRIMARY KEY,
                event_type TEXT NOT NULL,
                event_purpose TEXT NOT NULL,
                aggregate_id TEXT NOT NULL,
                tenant_id TEXT,
                project_id TEXT,
                actor_id TEXT,
                request_id TEXT,
                run_id TEXT,
                session_id TEXT,
                severity TEXT NOT NULL,
                action TEXT,
                dry_run INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                delivery_status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL,
                next_delivery_attempt_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            """
        )
        return connection

    @staticmethod
    def _runner_report(worker_id: str = "worker-a") -> AgentMemoryMaterializationRunnerReport:
        """构造低敏 runner 批次报告。"""

        started_at = datetime(2026, 6, 3, 8, 0, tzinfo=timezone.utc)
        return AgentMemoryMaterializationRunnerReport(
            requested_limit=10,
            scanned_count=1,
            succeeded_count=1,
            failed_count=0,
            skipped_count=0,
            worker_id=worker_id,
            started_at=started_at,
            finished_at=started_at + timedelta(milliseconds=10),
            items=(
                AgentMemoryMaterializationRunnerItem(
                    candidate_id="candidate-a",
                    status=AgentMemoryMaterializationRunnerItemStatus.SUCCEEDED,
                    outcome="materialized",
                    memory_id="memory-a",
                ),
            ),
            attributes={"claimedCount": 1, "deadLetterCount": 0, "skippedReasons": {}},
        )

    @staticmethod
    def _requeue_result(*, dry_run: bool) -> AgentMemoryMaterializationRequeueResult:
        """构造补偿结果。"""

        now = datetime(2026, 6, 3, 9, 0, tzinfo=timezone.utc)
        before = AgentMemoryMaterializationLease(
            lease_id="lease-a",
            candidate_id="candidate-a",
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            status=AgentMemoryMaterializationLeaseStatus.DEAD_LETTER,
            attempt_count=3,
            worker_id="worker-a",
            lease_token="should-not-leak",
            leased_until=now,
            error_message="RuntimeError: 模拟失败",
            updated_at=now,
        )
        after = AgentMemoryMaterializationLease(
            **{
                **before.__dict__,
                "status": AgentMemoryMaterializationLeaseStatus.FAILED,
                "next_retry_at": now + timedelta(seconds=30),
                "updated_at": now + timedelta(seconds=1),
            }
        )
        return AgentMemoryMaterializationRequeueResult(
            candidate_id="candidate-a",
            dry_run=dry_run,
            action="dry_run_requeue" if dry_run else "scheduled_retry",
            operator_id="admin-a",
            before=before,
            after=after,
            notes=("下游恢复后重排",),
        )


class _FailingAuditOutboxStore:
    """稳定失败的测试 store。"""

    def append(self, record):
        raise RuntimeError("模拟审计 outbox 不可用")

    def list_recent(self, *, limit: int = 100):
        return ()


if __name__ == "__main__":
    unittest.main()
