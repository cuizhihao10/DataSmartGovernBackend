import os
import sys
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
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_store import (
    InMemoryAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_secondary_index import AgentMemorySecondaryIndexKind
from datasmart_ai_runtime.services.memory.memory_secondary_index_sync import (
    AgentMemorySecondaryIndexSyncAdapterResult,
    AgentMemorySecondaryIndexSyncScheduler,
    AgentMemorySecondaryIndexSyncStatus,
    AgentMemorySecondaryIndexSyncWorker,
    InMemoryAgentMemorySecondaryIndexSyncTaskStore,
    secondary_index_sync_diagnostics,
)
from datasmart_ai_runtime.services.memory.memory_store import InMemoryAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_write_candidate_store import InMemoryAgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory.memory_write_materializer import AgentApprovedMemoryWriteMaterializer


class AgentMemorySecondaryIndexSyncTest(unittest.TestCase):
    """长期记忆二级索引同步任务测试。"""

    def test_materializer_schedules_secondary_index_tasks_after_success(self) -> None:
        """正式记忆落成成功后，应为该记忆类型声明的索引通道创建同步任务。"""

        runtime = self._runtime()
        runtime["candidate_store"].save(self._candidate(memory_type=AgentMemoryType.SEMANTIC))

        result = runtime["materializer"].materialize("candidate-a")
        ready = runtime["sync_store"].list_ready()

        self.assertEqual(2, result.attributes["secondaryIndexTaskCount"])
        self.assertEqual(2, result.attributes["secondaryIndexTaskCreatedCount"])
        self.assertEqual({"vector", "keyword"}, {task.index_kind.value for task in ready})
        self.assertTrue(all(task.memory_id == result.memory_id for task in ready))

    def test_repeated_materialization_does_not_duplicate_secondary_index_tasks(self) -> None:
        """同一候选重复落成时，同步任务应按 taskId 幂等复用。"""

        runtime = self._runtime()
        runtime["candidate_store"].save(self._candidate(memory_type=AgentMemoryType.EPISODIC))

        first = runtime["materializer"].materialize("candidate-a")
        second = runtime["materializer"].materialize("candidate-a")
        ready = runtime["sync_store"].list_ready()

        self.assertEqual(2, first.attributes["secondaryIndexTaskCreatedCount"])
        self.assertEqual(0, second.attributes["secondaryIndexTaskCreatedCount"])
        self.assertEqual(2, len(ready))
        self.assertEqual({"keyword", "vector"}, {task.index_kind.value for task in ready})

    def test_worker_marks_ready_tasks_synced_with_noop_adapter(self) -> None:
        """默认 no-op adapter 应能验证任务状态机，把 ready 任务推进到 synced。"""

        runtime = self._runtime()
        runtime["candidate_store"].save(self._candidate(memory_type=AgentMemoryType.PROCEDURAL))
        runtime["materializer"].materialize("candidate-a")

        report = AgentMemorySecondaryIndexSyncWorker(store=runtime["sync_store"]).run_once(limit=10)

        self.assertEqual(2, report.scanned_count)
        self.assertEqual(2, report.succeeded_count)
        self.assertEqual(0, report.failed_count)
        self.assertTrue(all(item["status"] == "synced" for item in report.items))

    def test_worker_failure_uses_backoff_and_dead_letter_policy(self) -> None:
        """真实 adapter 失败时，任务应进入失败退避，并在达到最大尝试次数后进入 DLQ。"""

        runtime = self._runtime()
        runtime["candidate_store"].save(self._candidate(memory_type=AgentMemoryType.SHORT_TERM))
        runtime["materializer"].materialize("candidate-a")
        worker = AgentMemorySecondaryIndexSyncWorker(
            store=runtime["sync_store"],
            adapters={AgentMemorySecondaryIndexKind.KEYWORD: FailingSyncAdapter()},
            max_attempts=1,
        )

        report = worker.run_once(limit=10)

        self.assertEqual(1, report.failed_count)
        self.assertEqual("dead_letter", report.items[0]["status"])
        self.assertIn("模拟索引服务不可用", report.items[0]["lastError"])

    def test_diagnostics_summarizes_ready_tasks_without_memory_content(self) -> None:
        """同步诊断只暴露任务数量和索引类型，不暴露记忆正文。"""

        runtime = self._runtime()
        runtime["candidate_store"].save(self._candidate(memory_type=AgentMemoryType.RESOURCE))
        runtime["materializer"].materialize("candidate-a")

        diagnostics = secondary_index_sync_diagnostics(runtime["sync_store"])

        self.assertEqual("memory-secondary-index-sync", diagnostics["component"])
        self.assertEqual(2, diagnostics["readyTaskCount"])
        self.assertEqual(1, diagnostics["readyByIndexKind"]["resource"])
        self.assertNotIn("content", str(diagnostics).lower())

    @staticmethod
    def _runtime() -> dict[str, object]:
        candidate_store = InMemoryAgentMemoryWriteCandidateStore()
        memory_store = InMemoryAgentMemoryStore()
        receipt_store = InMemoryAgentMemoryMaterializationReceiptStore()
        sync_store = InMemoryAgentMemorySecondaryIndexSyncTaskStore()
        scheduler = AgentMemorySecondaryIndexSyncScheduler(store=sync_store)
        materializer = AgentApprovedMemoryWriteMaterializer(
            candidate_store=candidate_store,
            memory_store=memory_store,
            receipt_store=receipt_store,
            secondary_index_sync_scheduler=scheduler,
        )
        return {
            "candidate_store": candidate_store,
            "memory_store": memory_store,
            "receipt_store": receipt_store,
            "sync_store": sync_store,
            "materializer": materializer,
        }

    @staticmethod
    def _candidate(memory_type: AgentMemoryType) -> AgentMemoryWriteCandidate:
        return AgentMemoryWriteCandidate(
            candidate_id="candidate-a",
            memory_type=memory_type,
            scope=AgentMemoryScope.PROJECT,
            status=AgentMemoryWriteCandidateStatus.APPROVED,
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            title="长期记忆二级索引测试",
            content_summary="这是进入正式记忆 store 的低敏摘要，不包含原始工具结果。",
            source="unit-test",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            source_tool_name="quality.rule.suggest",
            source_status="succeeded",
            source_audit_id="audit-a",
            retention_days=30,
            sensitivity_level="internal",
            idempotency_key=f"tenant-a|project-a|{memory_type.value}",
        )


class FailingSyncAdapter:
    """用于测试失败路径的 adapter。"""

    def sync(self, task):
        return AgentMemorySecondaryIndexSyncAdapterResult(False, "模拟索引服务不可用")


if __name__ == "__main__":
    unittest.main()
