import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.memory.memory_materialization_metrics import AgentMemoryMaterializationMetrics
from datasmart_ai_runtime.services.memory.memory_materialization_runner import (
    AgentMemoryMaterializationRunnerItem,
    AgentMemoryMaterializationRunnerItemStatus,
    AgentMemoryMaterializationRunnerReport,
)
from datasmart_ai_runtime.services.memory.memory_materialization_worker import (
    AgentMemoryMaterializationWorker,
    AgentMemoryMaterializationWorkerSettings,
    memory_materialization_worker_settings_from_env,
)


class AgentMemoryMaterializationWorkerTest(unittest.TestCase):
    """长期记忆物化后台 worker 测试。

    测试重点不是验证 materializer 写入正式记忆，这些已经由 runner/materializer 测试覆盖；这里关注后台 worker 的
    生产控制语义：单轮 limit、事件投递、指标记录、旁路失败 fail-open、连续异常熔断和环境变量配置。
    """

    def test_run_once_records_runtime_event_and_metrics(self) -> None:
        """单轮执行应调用 runner，并把批次报告转为 Runtime Event 和 Prometheus 指标。"""

        runner = _FakeRunner()
        event_store = _RecordingEventStore()
        publisher = _RecordingEventPublisher()
        metrics = AgentMemoryMaterializationMetrics()
        worker = AgentMemoryMaterializationWorker(
            runner=runner,
            settings=AgentMemoryMaterializationWorkerSettings(batch_limit=12),
            event_store=event_store,
            event_publisher=publisher,
            metrics_recorder=metrics,
        )

        result = worker.run_once()
        diagnostics = worker.diagnostics()
        metric_text = metrics.render_prometheus()

        self.assertTrue(result.completed)
        self.assertEqual([12], runner.limits)
        self.assertEqual(AgentRuntimeEventType.MEMORY_MATERIALIZATION_RUN_COMPLETED, event_store.events[0].event_type)
        self.assertEqual([1], publisher.batch_sizes)
        self.assertTrue(result.metric_delivery["recorded"])
        self.assertIn('datasmart_ai_memory_materialization_runs_total{result="succeeded",severity="info"} 1', metric_text)
        self.assertEqual(1, diagnostics["runCount"])
        self.assertEqual(1, diagnostics["completedRunCount"])
        self.assertEqual(0, diagnostics["failedRunCount"])

    def test_event_delivery_failure_does_not_fail_completed_runner_round(self) -> None:
        """event store 或 publisher 故障属于旁路失败，不应把已完成的 Runner 批次改判失败。"""

        worker = AgentMemoryMaterializationWorker(
            runner=_FakeRunner(),
            event_store=_FailingEventStore(),
            event_publisher=_FailingEventPublisher(),
            metrics_recorder=AgentMemoryMaterializationMetrics(),
        )

        result = worker.run_once()

        self.assertTrue(result.completed)
        self.assertFalse(result.event_delivery["stored"])
        self.assertEqual(2, len(result.event_delivery["errors"]))
        self.assertTrue(result.metric_delivery["recorded"])

    def test_runner_exceptions_open_fuse_after_consecutive_failures(self) -> None:
        """连续 Runner 异常达到阈值后，worker 应打开熔断标记并停止后台循环。"""

        worker = AgentMemoryMaterializationWorker(
            runner=_FailingRunner(),
            settings=AgentMemoryMaterializationWorkerSettings(max_consecutive_errors=2),
        )

        first = worker.run_once()
        second = worker.run_once()
        diagnostics = worker.diagnostics()

        self.assertFalse(first.completed)
        self.assertFalse(second.completed)
        self.assertTrue(diagnostics["fuseOpen"])
        self.assertEqual(2, diagnostics["runCount"])
        self.assertEqual(2, diagnostics["failedRunCount"])
        self.assertEqual(2, diagnostics["consecutiveErrorCount"])

    def test_settings_from_env_clamps_and_parses_values(self) -> None:
        """环境变量应能控制 worker 启停、间隔、批次大小和熔断阈值。"""

        settings = memory_materialization_worker_settings_from_env(
            {
                "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_ENABLED": "true",
                "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_INTERVAL_SECONDS": "0",
                "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_BATCH_LIMIT": "1000",
                "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_RUN_ON_STARTUP": "false",
                "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_MAX_CONSECUTIVE_ERRORS": "0",
                "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_STOP_TIMEOUT_SECONDS": "0",
            }
        )

        self.assertTrue(settings.enabled)
        self.assertEqual(0.1, settings.interval_seconds)
        self.assertEqual(100, settings.batch_limit)
        self.assertFalse(settings.run_once_on_startup)
        self.assertEqual(1, settings.max_consecutive_errors)
        self.assertEqual(0.1, settings.stop_timeout_seconds)

    def test_start_is_noop_when_worker_disabled(self) -> None:
        """默认关闭时 start() 不应创建后台线程。"""

        worker = AgentMemoryMaterializationWorker(
            runner=_FakeRunner(),
            settings=AgentMemoryMaterializationWorkerSettings(enabled=False),
        )

        started = worker.start()

        self.assertFalse(started)
        self.assertFalse(worker.diagnostics()["running"])


class _FakeRunner:
    """返回固定低敏 Runner 报告的测试 runner。"""

    def __init__(self) -> None:
        self.limits: list[int] = []

    def run_once(self, *, limit: int):
        self.limits.append(limit)
        return _runner_report()


class _FailingRunner:
    """稳定抛出异常的测试 runner，用于验证熔断。"""

    def run_once(self, *, limit: int):
        raise RuntimeError("模拟 Runner 故障")


class _RecordingEventStore:
    """记录写入事件的测试 store。"""

    def __init__(self) -> None:
        self.events = []

    def append_many(self, events) -> None:
        self.events.extend(events)


class _RecordingEventPublisher:
    """记录发布批次大小的测试 publisher。"""

    def __init__(self) -> None:
        self.batch_sizes: list[int] = []

    def publish(self, events) -> int:
        batch = tuple(events)
        self.batch_sizes.append(len(batch))
        return len(batch)


class _FailingEventStore:
    """模拟 replay store 故障。"""

    def append_many(self, events) -> None:
        raise RuntimeError("模拟 event store 故障")


class _FailingEventPublisher:
    """模拟异步总线故障。"""

    def publish(self, events) -> int:
        raise RuntimeError("模拟 event publisher 故障")


def _runner_report() -> AgentMemoryMaterializationRunnerReport:
    """构造一个成功批次报告。"""

    started_at = datetime(2026, 6, 3, 6, 0, tzinfo=timezone.utc)
    return AgentMemoryMaterializationRunnerReport(
        requested_limit=12,
        scanned_count=1,
        succeeded_count=1,
        failed_count=0,
        skipped_count=0,
        worker_id="unit-test-worker",
        started_at=started_at,
        finished_at=started_at + timedelta(milliseconds=20),
        items=(
            AgentMemoryMaterializationRunnerItem(
                candidate_id="candidate-a",
                status=AgentMemoryMaterializationRunnerItemStatus.SUCCEEDED,
                outcome="materialized",
                memory_id="memory-a",
                message="测试成功",
            ),
        ),
        attributes={"claimedCount": 1, "deadLetterCount": 0, "skippedReasons": {}},
    )


if __name__ == "__main__":
    unittest.main()
