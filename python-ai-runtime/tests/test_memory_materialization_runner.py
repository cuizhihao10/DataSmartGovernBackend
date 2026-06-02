import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

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
    AgentMemoryMaterializationReceiptStatus,
    InMemoryAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    InMemoryAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_runner import (
    AgentMemoryMaterializationRunner,
    AgentMemoryMaterializationRunnerItemStatus,
)
from datasmart_ai_runtime.services.memory.memory_store import InMemoryAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_write_candidate_store import InMemoryAgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory.memory_write_materializer import (
    AgentApprovedMemoryWriteMaterializer,
    AgentMemoryMaterializationOutcome,
)


class AgentMemoryMaterializationRunnerTest(unittest.TestCase):
    """长期记忆落成 Runner 的批次执行测试。

    materializer 的单条能力已经由 `test_memory_write_materializer.py` 覆盖。本测试关注 Runner 自己的产品语义：
    - 是否只扫描 APPROVED 候选；
    - 是否把单条失败隔离为失败 item；
    - 是否继续处理同批其他候选；
    - 是否生成适合管理后台、worker 日志和 runtime event 使用的低敏批次报告。

    这些语义对商业化 agent 很重要。真实客户环境中，长期记忆候选可能来自不同工具、不同审批人和不同历史版本。
    如果一条历史坏数据阻塞整批 worker，系统会表现为“审批通过但经验没有沉淀”，并且很难被运维快速定位。
    """

    def test_run_once_materializes_approved_candidates_and_returns_summary(self) -> None:
        """Runner 应能把 APPROVED 候选落成正式记忆，并返回低敏批次摘要。"""

        candidate_store, memory_store, receipt_store, runner = self._runtime()
        candidate_store.save(self._candidate("candidate-a"))

        report = runner.run_once(limit=10)
        summary = report.to_summary()
        receipt = receipt_store.get_by_candidate_id("candidate-a")

        self.assertEqual(10, report.requested_limit)
        self.assertEqual(1, report.scanned_count)
        self.assertEqual(1, report.succeeded_count)
        self.assertEqual(0, report.failed_count)
        self.assertEqual("unit-test-memory-runner", report.worker_id)
        self.assertEqual(AgentMemoryMaterializationRunnerItemStatus.SUCCEEDED, report.items[0].status)
        self.assertEqual(AgentMemoryMaterializationOutcome.MATERIALIZED.value, report.items[0].outcome)
        self.assertEqual("candidate-a", summary["items"][0]["candidateId"])
        self.assertEqual("success", summary["items"][0]["attributes"]["runnerResult"])
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.SUCCEEDED, receipt.status)
        self.assertIsNotNone(memory_store.get_by_candidate_id("candidate-a"))

    def test_run_once_continues_after_single_candidate_failure(self) -> None:
        """一条候选失败时，Runner 不能中断同批其他候选。

        这里用 `retention_days=0` 模拟审批后的历史坏数据。materializer 会在 receipt begin 之后失败，
        因而 receipt store 应该记录 failed；Runner 则继续处理另一条合法候选。
        """

        candidate_store, memory_store, receipt_store, runner = self._runtime()
        candidate_store.save(self._candidate("candidate-valid", created_offset_seconds=-2))
        candidate_store.save(self._candidate("candidate-invalid", retention_days=0, created_offset_seconds=-1))

        report = runner.run_once(limit=10)
        items_by_candidate_id = {item.candidate_id: item for item in report.items}
        failed_receipt = receipt_store.get_by_candidate_id("candidate-invalid")

        self.assertEqual(2, report.scanned_count)
        self.assertEqual(1, report.succeeded_count)
        self.assertEqual(1, report.failed_count)
        self.assertEqual(AgentMemoryMaterializationRunnerItemStatus.SUCCEEDED, items_by_candidate_id["candidate-valid"].status)
        self.assertEqual(AgentMemoryMaterializationRunnerItemStatus.FAILED, items_by_candidate_id["candidate-invalid"].status)
        self.assertIn("retentionDays", items_by_candidate_id["candidate-invalid"].error_message)
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.FAILED, failed_receipt.status)
        self.assertIsNotNone(memory_store.get_by_candidate_id("candidate-valid"))
        self.assertIsNone(memory_store.get_by_candidate_id("candidate-invalid"))

    def test_run_once_clamps_unsafe_limit_to_one(self) -> None:
        """Runner 会把小于 1 的 limit 裁剪为 1，避免调用方误传 0 导致语义不清。"""

        candidate_store, _memory_store, _receipt_store, runner = self._runtime()
        candidate_store.save(self._candidate("candidate-old", created_offset_seconds=-2))
        candidate_store.save(self._candidate("candidate-new", created_offset_seconds=-1))

        report = runner.run_once(limit=0)

        self.assertEqual(0, report.requested_limit)
        self.assertEqual(1, report.scanned_count)
        self.assertEqual(1, report.attributes["safeLimit"])
        self.assertEqual("candidate-new", report.items[0].candidate_id)

    def test_run_once_only_scans_approved_candidates(self) -> None:
        """Runner 只扫描 APPROVED 候选，不能把 DRAFT 或 REJECTED 历史候选误写入正式记忆。"""

        candidate_store, memory_store, _receipt_store, runner = self._runtime()
        candidate_store.save(self._candidate("candidate-approved"))
        candidate_store.save(
            self._candidate(
                "candidate-draft",
                status=AgentMemoryWriteCandidateStatus.DRAFT,
                created_offset_seconds=1,
            )
        )

        report = runner.run_once(limit=10)

        self.assertEqual(1, report.scanned_count)
        self.assertEqual("candidate-approved", report.items[0].candidate_id)
        self.assertIsNotNone(memory_store.get_by_candidate_id("candidate-approved"))
        self.assertIsNone(memory_store.get_by_candidate_id("candidate-draft"))

    def test_run_once_does_not_repeat_successfully_materialized_candidate(self) -> None:
        """lease 成功终态应阻止 Runner 每轮重复落成同一候选。"""

        candidate_store, _memory_store, receipt_store, runner = self._runtime()
        candidate_store.save(self._candidate("candidate-a"))

        first = runner.run_once(limit=10)
        second = runner.run_once(limit=10)
        receipt = receipt_store.get_by_candidate_id("candidate-a")

        self.assertEqual(1, first.succeeded_count)
        self.assertEqual(0, second.succeeded_count)
        self.assertEqual(1, second.skipped_count)
        self.assertEqual((), second.items)
        self.assertEqual(1, receipt.attempt_count)

    def test_run_once_scans_past_candidate_leased_by_other_worker(self) -> None:
        """窗口前部候选被其他实例占用时，Runner 应继续向后扫描，减少可执行候选饥饿。"""

        lease_store = InMemoryAgentMemoryMaterializationLeaseStore()
        candidate_store, memory_store, _receipt_store, runner = self._runtime(lease_store=lease_store)
        candidate_store.save(self._candidate("candidate-free", created_offset_seconds=-2))
        candidate_store.save(self._candidate("candidate-leased", created_offset_seconds=-1))
        lease_store.try_acquire(
            candidate_id="candidate-leased",
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            worker_id="other-worker",
            lease_seconds=60,
        )

        report = runner.run_once(limit=1)

        self.assertEqual(2, report.scanned_count)
        self.assertEqual(1, report.skipped_count)
        self.assertEqual(1, report.succeeded_count)
        self.assertEqual("candidate-free", report.items[0].candidate_id)
        self.assertIsNotNone(memory_store.get_by_candidate_id("candidate-free"))

    @staticmethod
    def _runtime(*, lease_store=None):
        """构造 Runner 测试运行时。

        测试使用内存 store 是为了聚焦 Runner 语义，不代表生产环境会使用内存。生产环境应继续使用 SQL
        candidate store、SQL formal memory store、SQL receipt store 和 SQL lease store，并在下一阶段增加退避、DLQ 与指标。
        """

        candidate_store = InMemoryAgentMemoryWriteCandidateStore()
        memory_store = InMemoryAgentMemoryStore()
        receipt_store = InMemoryAgentMemoryMaterializationReceiptStore()
        materializer = AgentApprovedMemoryWriteMaterializer(
            candidate_store=candidate_store,
            memory_store=memory_store,
            receipt_store=receipt_store,
            worker_id="unit-test-materializer",
        )
        runner = AgentMemoryMaterializationRunner(
            candidate_store=candidate_store,
            materializer=materializer,
            lease_store=lease_store,
            worker_id="unit-test-memory-runner",
        )
        return candidate_store, memory_store, receipt_store, runner

    @staticmethod
    def _candidate(
        candidate_id: str,
        *,
        status: AgentMemoryWriteCandidateStatus = AgentMemoryWriteCandidateStatus.APPROVED,
        retention_days: int = 30,
        created_offset_seconds: int = 0,
    ) -> AgentMemoryWriteCandidate:
        """构造一条长期记忆候选。

        `idempotency_key` 携带 candidate_id，确保每条候选在正式 store 中形成独立幂等键。
        `created_offset_seconds` 用于测试扫描顺序：内存候选 store 当前按 `created_at` 倒序返回。
        """

        created_at = datetime.now(timezone.utc) + timedelta(seconds=created_offset_seconds)
        return AgentMemoryWriteCandidate(
            candidate_id=candidate_id,
            memory_type=AgentMemoryType.EPISODIC,
            scope=AgentMemoryScope.PROJECT,
            status=status,
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="auditor-a",
            title=f"质量治理经验 {candidate_id}",
            content_summary=f"{candidate_id} 记录了一次手机号质量异常治理经验，可复用为空值兜底与格式校验策略。",
            source="agent-runtime-tool-feedback",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            source_tool_name="quality.rule.suggest",
            source_status="succeeded",
            source_audit_id=f"audit-{candidate_id}",
            source_run_id=f"run-{candidate_id}",
            output_ref="minio://quality/private-result.json",
            retention_days=retention_days,
            sensitivity_level="internal",
            idempotency_key=f"tenant-a|project-a|quality.rule.suggest|{candidate_id}",
            created_at=created_at,
        )


if __name__ == "__main__":
    unittest.main()
