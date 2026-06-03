import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api_memory_materialization_admin import (
    _record_audit_outbox,
    _record_runtime_event,
    _runtime_event_payload,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventSeverity, AgentRuntimeEventType
from datasmart_ai_runtime.services.memory.memory_materialization_admin import (
    AgentMemoryMaterializationAdminService,
    AgentMemoryMaterializationRequeueRequest,
)
from datasmart_ai_runtime.services.memory.memory_materialization_events import (
    AgentMemoryMaterializationEventContext,
    memory_materialization_requeue_event,
    memory_materialization_runner_event,
)
from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox import (
    AgentMemoryMaterializationAuditOutboxError,
    AgentMemoryMaterializationAuditOutboxRecorder,
    InMemoryAgentMemoryMaterializationAuditOutboxStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    InMemoryAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_runner import (
    AgentMemoryMaterializationRunnerItem,
    AgentMemoryMaterializationRunnerItemStatus,
    AgentMemoryMaterializationRunnerReport,
)


class AgentMemoryMaterializationEventTest(unittest.TestCase):
    """长期记忆物化 Runtime Event 契约测试。

    这些测试刻意不启动 FastAPI，也不依赖 Redis/Kafka。原因是本批能力的核心不是 HTTP 框架本身，
    而是“长期记忆物化事实”能否被稳定转换为低敏、可 replay、可发布、可审计的统一事件。

    商业化产品里，长期记忆物化不是单纯后台小任务，而是类 Codex/Claude Code agent 逐步积累长期经验的
    关键链路。如果它失败、进入 DLQ、被管理员重排或被 retry cooldown 跳过，运营和研发必须能在同一条
    Runtime Event 时间线上看见这些事实，否则问题会退化成“用户觉得 agent 记不住，但平台看不出哪里断了”。
    """

    def test_requeue_event_records_low_sensitive_audit_fact(self) -> None:
        """真实重排应生成 AUDIT 事件，并且只携带控制面低敏字段。"""

        service = AgentMemoryMaterializationAdminService(self._failed_store("candidate-a"))
        result = service.requeue(
            AgentMemoryMaterializationRequeueRequest(
                candidate_id="candidate-a",
                operator_id="admin-a",
                reason="下游向量库恢复后重新安排物化",
                dry_run=False,
                delay_seconds=30,
            ),
            now=datetime(2026, 6, 3, 3, 0, tzinfo=timezone.utc),
        )

        event = memory_materialization_requeue_event(
            result,
            AgentMemoryMaterializationEventContext(request_id="req-a", session_id="session-a"),
        )

        self.assertEqual(AgentRuntimeEventType.MEMORY_MATERIALIZATION_REQUEUE_RECORDED, event.event_type)
        self.assertEqual(AgentRuntimeEventSeverity.AUDIT, event.severity)
        self.assertEqual("tenant-a", event.tenant_id)
        self.assertEqual("project-a", event.project_id)
        self.assertEqual("admin-a", event.actor_id)
        self.assertEqual("req-a", event.request_id)
        self.assertEqual("session-a", event.session_id)
        self.assertEqual("scheduled_retry", event.attributes["action"])
        self.assertFalse(event.attributes["dryRun"])
        self.assertEqual("memory_materialization_compensation_audit", event.attributes["eventPurpose"])
        self.assertNotIn("leaseToken", event.attributes)
        self.assertNotIn("content", event.attributes)
        self.assertIsNotNone(event.attributes["afterNextRetryAt"])

    def test_dry_run_requeue_event_uses_info_severity(self) -> None:
        """dry-run 是预览动作，不代表审计落库事实，因此严重级别保持 INFO。"""

        service = AgentMemoryMaterializationAdminService(self._failed_store("candidate-dry-run"))
        result = service.requeue(
            AgentMemoryMaterializationRequeueRequest(
                candidate_id="candidate-dry-run",
                operator_id="admin-a",
                reason="预览是否可以从失败状态重新调度",
                dry_run=True,
            )
        )

        event = memory_materialization_requeue_event(result)

        self.assertEqual(AgentRuntimeEventSeverity.INFO, event.severity)
        self.assertEqual("dry_run_requeue", event.attributes["action"])
        self.assertTrue(event.attributes["dryRun"])

    def test_runner_event_summarizes_batch_observability_counters(self) -> None:
        """Runner 批次事件应聚合 DLQ、cooldown 跳过和 fencing finalize 失败等运维指标。"""

        started_at = datetime(2026, 6, 3, 4, 0, tzinfo=timezone.utc)
        report = AgentMemoryMaterializationRunnerReport(
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
                    candidate_id="candidate-failed",
                    status=AgentMemoryMaterializationRunnerItemStatus.FAILED,
                    error_message="RuntimeError: 模拟失败",
                    attributes={"leaseStatus": "dead_letter"},
                ),
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
                "executionPolicy": "BOUNDED_AT_LEAST_ONCE_WITH_LEASE_TOKEN_FENCING",
                "maxAttempts": 5,
                "retryBaseSeconds": 30,
                "retryMaxSeconds": 3600,
            },
        )

        event = memory_materialization_runner_event(report)

        self.assertEqual(AgentRuntimeEventType.MEMORY_MATERIALIZATION_RUN_COMPLETED, event.event_type)
        self.assertEqual(AgentRuntimeEventSeverity.ERROR, event.severity)
        self.assertEqual(12, event.attributes["scannedCount"])
        self.assertEqual(7, event.attributes["succeededCount"])
        self.assertEqual(1, event.attributes["deadLetterCount"])
        self.assertEqual(2, event.attributes["retryCooldownSkippedCount"])
        self.assertEqual(1, event.attributes["activeLeaseSkippedCount"])
        self.assertEqual(1, event.attributes["deadLetterSkippedCount"])
        self.assertEqual(1, event.attributes["leaseFinalizeErrorCount"])
        self.assertEqual(1250, event.attributes["durationMillis"])
        self.assertEqual("memory_materialization_batch_observability", event.attributes["eventPurpose"])

    def test_record_runtime_event_is_fail_open_for_side_channel_delivery(self) -> None:
        """事件旁路投递失败应返回诊断摘要，不能抛异常破坏补偿主流程。"""

        event = memory_materialization_runner_event(
            AgentMemoryMaterializationRunnerReport(
                requested_limit=1,
                scanned_count=0,
                succeeded_count=0,
                failed_count=0,
                skipped_count=0,
                items=(),
                worker_id="worker-a",
                started_at=datetime(2026, 6, 3, 4, 0, tzinfo=timezone.utc),
                finished_at=datetime(2026, 6, 3, 4, 0, tzinfo=timezone.utc),
            )
        )
        store = _RecordingEventStore()
        publisher = _FailingEventPublisher()

        delivery = _record_runtime_event(event, event_store=store, event_publisher=publisher)
        payload = _runtime_event_payload(event)

        self.assertTrue(delivery["storeEnabled"])
        self.assertTrue(delivery["publisherEnabled"])
        self.assertTrue(delivery["stored"])
        self.assertEqual(0, delivery["publishedCount"])
        self.assertEqual(1, len(delivery["errors"]))
        self.assertEqual("event_publisher", delivery["errors"][0]["component"])
        self.assertEqual((event,), tuple(store.events))
        self.assertEqual("memory_materialization_run_completed", payload["eventType"])
        self.assertEqual("info", payload["severity"])

    def test_record_audit_outbox_can_be_required_for_compensation_events(self) -> None:
        """补偿事件可以写入审计 outbox；required 模式下写入失败应抛错。"""

        event = memory_materialization_requeue_event(
            AgentMemoryMaterializationAdminService(self._failed_store("candidate-a")).requeue(
                AgentMemoryMaterializationRequeueRequest(
                    candidate_id="candidate-a",
                    operator_id="admin-a",
                    reason="补偿审计测试",
                    dry_run=False,
                )
            )
        )
        audit_store = InMemoryAgentMemoryMaterializationAuditOutboxStore()
        delivery = _record_audit_outbox(
            event,
            audit_outbox_recorder=AgentMemoryMaterializationAuditOutboxRecorder(
                store=audit_store,
                enabled=True,
                required=True,
            ),
        )

        self.assertTrue(delivery["stored"])
        self.assertEqual(1, len(audit_store.list_recent()))
        with self.assertRaises(AgentMemoryMaterializationAuditOutboxError):
            _record_audit_outbox(
                event,
                audit_outbox_recorder=AgentMemoryMaterializationAuditOutboxRecorder(
                    store=_FailingAuditOutboxStore(),
                    enabled=True,
                    required=True,
                ),
            )

    @staticmethod
    def _failed_store(candidate_id: str) -> InMemoryAgentMemoryMaterializationLeaseStore:
        """构造带一条 failed lease 的内存 store。

        测试只关心事件契约，因此这里直接通过 lease store 建立失败事实，不引入完整 candidate/materializer。
        真实生产链路里，这个 failed 状态会由 Runner 在物化异常后写入。
        """

        store = InMemoryAgentMemoryMaterializationLeaseStore()
        lease = store.try_acquire(
            candidate_id=candidate_id,
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            worker_id="worker-a",
            lease_seconds=60,
        )
        store.fail(
            candidate_id=candidate_id,
            lease_token=lease.lease_token,
            error_message="RuntimeError: 模拟物化失败",
            max_attempts=5,
            retry_base_seconds=30,
        )
        return store


class _RecordingEventStore:
    """测试用 replay store，记录收到的事件但不接入 Redis。"""

    def __init__(self) -> None:
        self.events: list[object] = []

    def append_many(self, events) -> None:
        """模拟 RuntimeEventStore.append_many。"""

        self.events.extend(events)


class _FailingEventPublisher:
    """测试用 publisher，模拟 Kafka 或异步总线短暂故障。"""

    def publish(self, events) -> int:
        """模拟 RuntimeEventPublisher.publish，并稳定抛出异常便于验证 fail-open 行为。"""

        raise RuntimeError("模拟事件总线不可用")


class _FailingAuditOutboxStore:
    """测试用审计 outbox store，稳定抛错以验证 required 行为。"""

    def append(self, record):
        raise RuntimeError("模拟审计 outbox 不可用")

    def list_recent(self, *, limit: int = 100):
        return ()


if __name__ == "__main__":
    unittest.main()
