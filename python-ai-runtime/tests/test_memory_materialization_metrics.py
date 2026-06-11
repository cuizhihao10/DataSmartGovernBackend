import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.memory.materialization_admin import _record_memory_materialization_metrics
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.memory.memory_materialization_admin import (
    AgentMemoryMaterializationAdminService,
    AgentMemoryMaterializationRequeueRequest,
)
from datasmart_ai_runtime.services.memory.memory_materialization_events import (
    memory_materialization_requeue_event,
    memory_materialization_runner_event,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    InMemoryAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_metrics import (
    AgentMemoryMaterializationMetrics,
)
from datasmart_ai_runtime.services.memory.memory_materialization_runner import (
    AgentMemoryMaterializationRunnerItem,
    AgentMemoryMaterializationRunnerItemStatus,
    AgentMemoryMaterializationRunnerReport,
)


class AgentMemoryMaterializationMetricsTest(unittest.TestCase):
    """长期记忆物化低基数指标测试。

    本测试关注“哪些事实可以进入 Prometheus”，也关注“哪些事实绝不能进入标签或指标文本”。
    Runtime Event 可以保留 candidateId/leaseId 作为排障线索，但 Prometheus 指标要面向聚合告警，因此只能使用
    result、reason、severity、dry_run 等有限枚举。
    """

    def test_runner_event_updates_low_cardinality_prometheus_metrics(self) -> None:
        """Runner 事件应被拆分为批次、候选、跳过原因、finalize error 和耗时指标。"""

        metrics = AgentMemoryMaterializationMetrics()
        event = memory_materialization_runner_event(self._runner_report())

        recorded = metrics.record_runtime_event(event)
        text = metrics.render_prometheus()

        self.assertTrue(recorded)
        self.assertIn('datasmart_ai_memory_materialization_runs_total{result="finalize_error",severity="error"} 1', text)
        self.assertIn('datasmart_ai_memory_materialization_candidates_total{result="scanned"} 12', text)
        self.assertIn('datasmart_ai_memory_materialization_candidates_total{result="claimed"} 8', text)
        self.assertIn('datasmart_ai_memory_materialization_candidates_total{result="succeeded"} 7', text)
        self.assertIn('datasmart_ai_memory_materialization_skips_total{reason="retry_cooldown"} 2', text)
        self.assertIn('datasmart_ai_memory_materialization_finalize_errors_total 1', text)
        self.assertIn('datasmart_ai_memory_materialization_duration_milliseconds_sum{result="finalize_error"} 1250', text)
        self.assertNotIn("candidate-fencing", text)
        self.assertNotIn("tenant-a", text)
        self.assertNotIn("trace", text)

    def test_requeue_event_updates_admin_compensation_metrics(self) -> None:
        """管理员补偿事件应记录 action/dry_run/after_status，不记录候选和 lease 明细。"""

        metrics = AgentMemoryMaterializationMetrics()
        event = memory_materialization_requeue_event(self._requeue_result())

        delivery = _record_memory_materialization_metrics(event, metrics_recorder=metrics)
        text = metrics.render_prometheus()

        self.assertTrue(delivery["enabled"])
        self.assertTrue(delivery["recorded"])
        self.assertEqual((), delivery["errors"])
        self.assertIn(
            'datasmart_ai_memory_materialization_requeues_total{action="scheduled_retry",after_status="failed",dry_run="false"} 1',
            text,
        )
        self.assertNotIn("candidate-requeue", text)
        self.assertNotIn("lease-", text)

    def test_unknown_runtime_event_is_ignored(self) -> None:
        """指标器可以挂在统一事件流水线后面，未知事件应安全忽略。"""

        metrics = AgentMemoryMaterializationMetrics()
        event = AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.TOOL_PLANNED,
            stage="plan_tools",
            message="非长期记忆事件",
        )

        recorded = metrics.record_runtime_event(event)
        snapshot = metrics.snapshot()
        text = metrics.render_prometheus()

        self.assertFalse(recorded)
        self.assertEqual(0, snapshot["metricCount"])
        self.assertIn("# HELP datasmart_ai_memory_materialization_runs_total", text)
        self.assertNotIn("plan_tools", text)

    @staticmethod
    def _runner_report() -> AgentMemoryMaterializationRunnerReport:
        """构造一个带失败、DLQ、跳过和 finalize error 的 Runner 报告。"""

        started_at = datetime(2026, 6, 3, 5, 0, tzinfo=timezone.utc)
        return AgentMemoryMaterializationRunnerReport(
            requested_limit=50,
            scanned_count=12,
            succeeded_count=7,
            failed_count=1,
            skipped_count=4,
            worker_id="worker-a",
            started_at=started_at,
            finished_at=started_at + timedelta(milliseconds=1250),
            items=(
                AgentMemoryMaterializationRunnerItem(
                    candidate_id="candidate-fencing",
                    status=AgentMemoryMaterializationRunnerItemStatus.FAILED,
                    error_message="RuntimeError: 模拟失败",
                    attributes={"leaseFinalizeError": "RuntimeError: token mismatch"},
                ),
            ),
            attributes={
                "claimedCount": 8,
                "deadLetterCount": 1,
                "skippedReasons": {
                    "retry_cooldown": 2,
                    "active_lease": 1,
                    "dead_letter": 1,
                },
            },
        )

    @staticmethod
    def _requeue_result():
        """构造一次真实补偿重排结果。"""

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        lease = store.try_acquire(
            candidate_id="candidate-requeue",
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            worker_id="worker-a",
            lease_seconds=60,
        )
        store.fail(
            candidate_id="candidate-requeue",
            lease_token=lease.lease_token,
            error_message="RuntimeError: 模拟物化失败",
            max_attempts=5,
            retry_base_seconds=30,
        )
        service = AgentMemoryMaterializationAdminService(store)
        return service.requeue(
            AgentMemoryMaterializationRequeueRequest(
                candidate_id="candidate-requeue",
                operator_id="admin-a",
                reason="验证指标记录",
                dry_run=False,
                delay_seconds=30,
            )
        )


if __name__ == "__main__":
    unittest.main()
