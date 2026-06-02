import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.memory.memory_materialization_admin import (
    AgentMemoryMaterializationAdminService,
    AgentMemoryMaterializationLeaseQuery,
    AgentMemoryMaterializationRequeueRequest,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLeaseStatus,
    InMemoryAgentMemoryMaterializationLeaseStore,
)


class AgentMemoryMaterializationAdminServiceTest(unittest.TestCase):
    """长期记忆物化管理员补偿服务测试。

    这些测试关注产品级补偿语义，而不是正式记忆正文：
    - 管理员只能看 failed/dead_letter 等真正需要处理的候选；
    - dry-run 必须只预览，不改变 store；
    - 真实 requeue 只能把失败/DLQ 候选重新安排到 nextRetryAt；
    - 成功或仍在执行的候选不能被补偿入口误重排。
    """

    def test_list_leases_defaults_to_failed_and_dead_letter(self) -> None:
        """补偿列表默认只返回失败与 DLQ，不把成功终态混进待处理列表。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        failed = self._failed_lease(store, "candidate-failed")
        dead_letter = self._dead_letter_lease(store, "candidate-dlq")
        succeeded = self._succeeded_lease(store, "candidate-succeeded")
        service = AgentMemoryMaterializationAdminService(store)

        leases = service.list_leases(AgentMemoryMaterializationLeaseQuery(limit=10))

        self.assertEqual({"candidate-failed", "candidate-dlq"}, {lease.candidate_id for lease in leases})
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.FAILED, failed.status)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.DEAD_LETTER, dead_letter.status)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.SUCCEEDED, succeeded.status)

    def test_dry_run_requeue_does_not_mutate_store(self) -> None:
        """dry-run 应返回 before/after 预览，但不能解除 DLQ 或写入 nextRetryAt。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        dead_letter = self._dead_letter_lease(store, "candidate-dlq")
        service = AgentMemoryMaterializationAdminService(store)

        result = service.requeue(
            AgentMemoryMaterializationRequeueRequest(
                candidate_id="candidate-dlq",
                operator_id="admin-a",
                reason="下游 MySQL 连接恢复后准备重试",
                dry_run=True,
                delay_seconds=0,
            ),
            now=datetime(2026, 6, 3, 2, 0, tzinfo=timezone.utc),
        )
        stored = store.get_by_candidate_id("candidate-dlq")

        self.assertTrue(result.dry_run)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.DEAD_LETTER, stored.status)
        self.assertIsNone(stored.next_retry_at)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.FAILED, result.after.status)
        self.assertEqual(dead_letter.attempt_count, result.after.attempt_count)

    def test_requeue_dead_letter_schedules_retry_without_resetting_attempts(self) -> None:
        """真实 requeue 应保留 attemptCount，并让 Runner 能在 nextRetryAt 到达后重新领取。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        self._dead_letter_lease(store, "candidate-dlq")
        service = AgentMemoryMaterializationAdminService(store)
        now = datetime(2026, 6, 3, 2, 0, tzinfo=timezone.utc)

        result = service.requeue(
            AgentMemoryMaterializationRequeueRequest(
                candidate_id="candidate-dlq",
                operator_id="admin-a",
                reason="修复历史 schema 后重新物化",
                dry_run=False,
                delay_seconds=30,
            ),
            now=now,
        )
        claim_before_window = self._acquire(store, "candidate-dlq", "worker-b", now=now + timedelta(seconds=10))
        claim_after_window = self._acquire(store, "candidate-dlq", "worker-b", now=now + timedelta(seconds=31))

        self.assertFalse(result.dry_run)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.FAILED, result.after.status)
        self.assertEqual(1, result.after.attempt_count)
        self.assertEqual((now + timedelta(seconds=30)).isoformat(), result.after.next_retry_at.isoformat())
        self.assertIsNone(claim_before_window)
        self.assertEqual(AgentMemoryMaterializationLeaseStatus.LEASED, claim_after_window.status)
        self.assertEqual(2, claim_after_window.attempt_count)

    def test_requeue_rejects_succeeded_or_missing_audit_fields(self) -> None:
        """补偿入口必须拒绝成功终态，也必须要求操作者和原因。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        self._succeeded_lease(store, "candidate-succeeded")
        service = AgentMemoryMaterializationAdminService(store)

        with self.assertRaisesRegex(ValueError, "failed/dead_letter"):
            service.requeue(
                AgentMemoryMaterializationRequeueRequest(
                    candidate_id="candidate-succeeded",
                    operator_id="admin-a",
                    reason="不应重排成功终态",
                    dry_run=False,
                )
            )
        with self.assertRaisesRegex(ValueError, "operatorId"):
            service.requeue(
                AgentMemoryMaterializationRequeueRequest(
                    candidate_id="candidate-succeeded",
                    operator_id="",
                    reason="缺少操作者",
                )
            )

    @staticmethod
    def _acquire(
        store: InMemoryAgentMemoryMaterializationLeaseStore,
        candidate_id: str,
        worker_id: str,
        *,
        now: datetime | None = None,
    ):
        """使用固定治理范围领取一条测试候选。"""

        return store.try_acquire(
            candidate_id=candidate_id,
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            worker_id=worker_id,
            lease_seconds=60,
            now=now,
        )

    def _failed_lease(
        self,
        store: InMemoryAgentMemoryMaterializationLeaseStore,
        candidate_id: str,
    ):
        """创建 failed lease。"""

        lease = self._acquire(store, candidate_id, "worker-a")
        return store.fail(
            candidate_id=candidate_id,
            lease_token=lease.lease_token,
            error_message="RuntimeError: 模拟短暂失败",
            max_attempts=5,
            retry_base_seconds=30,
        )

    def _dead_letter_lease(
        self,
        store: InMemoryAgentMemoryMaterializationLeaseStore,
        candidate_id: str,
    ):
        """创建 dead_letter lease。"""

        lease = self._acquire(store, candidate_id, "worker-a")
        return store.fail(
            candidate_id=candidate_id,
            lease_token=lease.lease_token,
            error_message="RuntimeError: 模拟持续失败",
            max_attempts=1,
        )

    def _succeeded_lease(
        self,
        store: InMemoryAgentMemoryMaterializationLeaseStore,
        candidate_id: str,
    ):
        """创建 succeeded lease。"""

        lease = self._acquire(store, candidate_id, "worker-a")
        return store.succeed(
            candidate_id=candidate_id,
            lease_token=lease.lease_token,
            memory_id=f"memory-{candidate_id}",
            outcome="materialized",
            message="测试成功终态",
        )


if __name__ == "__main__":
    unittest.main()
