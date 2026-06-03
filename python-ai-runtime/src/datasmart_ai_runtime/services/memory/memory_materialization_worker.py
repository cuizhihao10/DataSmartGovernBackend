"""长期记忆物化受控后台 worker。

长期记忆物化已经具备候选审批、正式记忆 store、receipt、lease fencing、失败退避、DLQ、管理员补偿、
Runtime Event 和低基数 Prometheus 指标。本模块把这些能力串成一个“可选、可控、可诊断”的后台循环。

为什么 worker 默认关闭：
- 当前 Python Runtime 既用于本地学习，也用于未来生产部署；默认启动后台副作用会让学习环境难以理解数据变化；
- 多实例生产部署需要先确认 SQL lease store、Prometheus 抓取、审计策略和资源配额都已配置；
- 真实客户环境中，长期记忆写入会影响未来模型上下文，应由部署方显式开启。

为什么当前只做单线程循环：
- `AgentMemoryMaterializationRunner` 已经支持有界批次和 lease token fencing，先用单线程能把调度语义稳定下来；
- 多线程并发会引入租户配额、数据库连接池、下游向量库容量、错误风暴熔断等问题，应在指标稳定后分阶段引入；
- 单线程仍可多实例水平扩展，前提是生产使用 SQL lease store，而不是进程内内存 store。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import datetime, timezone
from threading import Event, RLock, Thread
from typing import Any

from datasmart_ai_runtime.domain.events import AgentRuntimeEvent
from datasmart_ai_runtime.services.memory.memory_materialization_events import (
    memory_materialization_runner_event,
)
from datasmart_ai_runtime.services.memory.memory_materialization_runner import (
    AgentMemoryMaterializationRunner,
    AgentMemoryMaterializationRunnerReport,
)


@dataclass(frozen=True)
class AgentMemoryMaterializationWorkerSettings:
    """长期记忆物化后台 worker 配置。

    字段说明：
    - `enabled`：是否启动后台循环。默认关闭，避免本地启动 API 时产生隐式副作用；
    - `interval_seconds`：两轮之间的等待秒数。真实生产应根据候选产生速度、数据库压力和下游存储容量调优；
    - `batch_limit`：每轮交给 Runner 的最大候选数。Runner 内部还会裁剪到自身安全上限；
    - `run_once_on_startup`：启动后是否立即跑一轮。开启后能降低候选延迟，但也会让服务启动瞬间产生写入压力；
    - `max_consecutive_errors`：连续多少轮异常后自动熔断停止，避免坏配置导致无限错误日志和下游风暴；
    - `stop_timeout_seconds`：服务关闭时等待 worker 线程退出的最长秒数。超过后不强杀线程，但诊断会保留状态。
    """

    enabled: bool = False
    interval_seconds: float = 30.0
    batch_limit: int = 50
    run_once_on_startup: bool = True
    max_consecutive_errors: int = 5
    stop_timeout_seconds: float = 5.0


@dataclass(frozen=True)
class AgentMemoryMaterializationWorkerRunResult:
    """worker 单轮执行结果。

    该结果只包含控制面事实，不包含候选正文、正式记忆正文、工具输出或异常堆栈全文。
    """

    completed: bool
    report: AgentMemoryMaterializationRunnerReport | None
    runtime_event: AgentRuntimeEvent | None
    event_delivery: dict[str, Any]
    metric_delivery: dict[str, Any]
    error: dict[str, str] | None = None

    def to_summary(self) -> dict[str, Any]:
        """转换为诊断接口可返回的低敏摘要。"""

        return {
            "completed": self.completed,
            "report": self.report.to_summary() if self.report else None,
            "runtimeEvent": _runtime_event_payload(self.runtime_event) if self.runtime_event else None,
            "eventDelivery": self.event_delivery,
            "metricDelivery": self.metric_delivery,
            "error": self.error,
        }


class AgentMemoryMaterializationWorker:
    """受控后台循环，周期性调用长期记忆物化 Runner。

    责任边界：
    - 本类负责“什么时候跑一轮、如何优雅停止、如何把报告转成事件和指标”；
    - Runner 负责“本轮处理哪些候选、如何领取 lease、如何把单条失败隔离”；
    - metrics recorder 负责“事件如何聚合成低基数指标”；
    - event store/publisher 负责“事件如何 replay 或发布到异步总线”。

    这种拆分让 worker 不直接依赖数据库表结构，也不直接理解 Prometheus 文本格式，后续迁移到外部调度器时更容易。
    """

    def __init__(
        self,
        *,
        runner: AgentMemoryMaterializationRunner,
        settings: AgentMemoryMaterializationWorkerSettings | None = None,
        event_store: Any | None = None,
        event_publisher: Any | None = None,
        metrics_recorder: Any | None = None,
        worker_name: str = "python-ai-runtime-memory-materialization-worker",
    ) -> None:
        self.settings = settings or AgentMemoryMaterializationWorkerSettings()
        self._runner = runner
        self._event_store = event_store
        self._event_publisher = event_publisher
        self._metrics_recorder = metrics_recorder
        self._worker_name = worker_name
        self._lock = RLock()
        self._stop_event = Event()
        self._thread: Thread | None = None
        self._started_at: datetime | None = None
        self._stopped_at: datetime | None = None
        self._last_run_at: datetime | None = None
        self._last_result: AgentMemoryMaterializationWorkerRunResult | None = None
        self._run_count = 0
        self._completed_run_count = 0
        self._failed_run_count = 0
        self._consecutive_error_count = 0
        self._fuse_open = False

    def start(self) -> bool:
        """启动后台循环。

        返回：
        - `True`：本次调用确实启动了线程；
        - `False`：配置未启用，或线程已经在运行。

        `start()` 是幂等的，FastAPI startup 事件重复触发时不会创建多个线程。
        """

        if not self.settings.enabled:
            return False
        with self._lock:
            if self._thread and self._thread.is_alive():
                return False
            self._stop_event.clear()
            self._fuse_open = False
            self._started_at = _utc_now()
            self._stopped_at = None
            self._thread = Thread(target=self._loop, name=self._worker_name, daemon=True)
            self._thread.start()
            return True

    def stop(self) -> bool:
        """请求后台线程停止并等待有限时间。

        返回 `True` 表示 stop 请求已发送且线程不存在或已退出；返回 `False` 表示等待超时后线程仍存活。
        """

        with self._lock:
            thread = self._thread
            self._stop_event.set()
        if thread is not None:
            thread.join(timeout=max(0.1, self.settings.stop_timeout_seconds))
        with self._lock:
            stopped = thread is None or not thread.is_alive()
            if stopped:
                self._stopped_at = _utc_now()
            return stopped

    def run_once(self) -> AgentMemoryMaterializationWorkerRunResult:
        """执行一轮物化，并把 Runner 报告同步写入事件和指标旁路。

        该方法可被后台线程调用，也可以被测试、CLI 或未来管理接口显式调用。即使后台 worker 默认关闭，
        手动调用 `run_once()` 仍然可用，便于本地验证和补偿场景复用。
        """

        self._last_run_at = _utc_now()
        try:
            report = self._runner.run_once(limit=self.settings.batch_limit)
            runtime_event = memory_materialization_runner_event(report)
            event_delivery = self._record_runtime_event(runtime_event)
            metric_delivery = self._record_metrics(runtime_event)
            result = AgentMemoryMaterializationWorkerRunResult(
                completed=True,
                report=report,
                runtime_event=runtime_event,
                event_delivery=event_delivery,
                metric_delivery=metric_delivery,
            )
            with self._lock:
                self._run_count += 1
                self._completed_run_count += 1
                self._consecutive_error_count = 0
                self._last_result = result
            return result
        except Exception as exc:
            result = AgentMemoryMaterializationWorkerRunResult(
                completed=False,
                report=None,
                runtime_event=None,
                event_delivery={"storeEnabled": self._event_store is not None, "publisherEnabled": self._event_publisher is not None, "stored": False, "publishedCount": 0, "errors": ()},
                metric_delivery={"enabled": self._metrics_recorder is not None, "recorded": False, "errors": ()},
                error=_delivery_error("memory_materialization_worker", exc),
            )
            with self._lock:
                self._run_count += 1
                self._failed_run_count += 1
                self._consecutive_error_count += 1
                self._last_result = result
                if self._consecutive_error_count >= self.settings.max_consecutive_errors:
                    self._fuse_open = True
                    self._stop_event.set()
            return result

    def diagnostics(self) -> dict[str, Any]:
        """返回 worker 低敏诊断快照。"""

        with self._lock:
            thread_alive = bool(self._thread and self._thread.is_alive())
            return {
                "implementation": "AgentMemoryMaterializationWorker",
                "enabled": self.settings.enabled,
                "running": thread_alive,
                "fuseOpen": self._fuse_open,
                "settings": {
                    "intervalSeconds": self.settings.interval_seconds,
                    "batchLimit": self.settings.batch_limit,
                    "runOnceOnStartup": self.settings.run_once_on_startup,
                    "maxConsecutiveErrors": self.settings.max_consecutive_errors,
                    "stopTimeoutSeconds": self.settings.stop_timeout_seconds,
                },
                "startedAt": _iso_or_none(self._started_at),
                "stoppedAt": _iso_or_none(self._stopped_at),
                "lastRunAt": _iso_or_none(self._last_run_at),
                "runCount": self._run_count,
                "completedRunCount": self._completed_run_count,
                "failedRunCount": self._failed_run_count,
                "consecutiveErrorCount": self._consecutive_error_count,
                "lastResult": self._last_result.to_summary() if self._last_result else None,
                "notes": (
                    "后台 worker 默认关闭，需显式配置 DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_ENABLED=true。",
                    "生产多实例部署应使用 SQL lease store；内存 lease store 只适合本地学习和单进程测试。",
                ),
            }

    def _loop(self) -> None:
        """后台线程主循环。"""

        if self.settings.run_once_on_startup and not self._stop_event.is_set():
            self.run_once()
        while not self._stop_event.wait(max(0.1, self.settings.interval_seconds)):
            self.run_once()

    def _record_runtime_event(self, event: AgentRuntimeEvent) -> dict[str, Any]:
        """写入 runtime event store 并尝试发布到异步总线。"""

        errors: list[dict[str, str]] = []
        stored = False
        published_count = 0
        if self._event_store is not None:
            try:
                self._event_store.append_many((event,))
                stored = True
            except Exception as exc:  # pragma: no cover - 依赖真实外部存储故障时触发
                errors.append(_delivery_error("event_store", exc))
        if self._event_publisher is not None:
            try:
                published_count = int(self._event_publisher.publish((event,)))
            except Exception as exc:  # pragma: no cover - 依赖真实消息总线故障时触发
                errors.append(_delivery_error("event_publisher", exc))
        return {
            "storeEnabled": self._event_store is not None,
            "publisherEnabled": self._event_publisher is not None,
            "stored": stored,
            "publishedCount": published_count,
            "errors": tuple(errors),
        }

    def _record_metrics(self, event: AgentRuntimeEvent) -> dict[str, Any]:
        """把 worker 事件写入低基数指标旁路。"""

        if self._metrics_recorder is None:
            return {"enabled": False, "recorded": False, "errors": ()}
        try:
            return {
                "enabled": True,
                "recorded": bool(self._metrics_recorder.record_runtime_event(event)),
                "errors": (),
            }
        except Exception as exc:  # pragma: no cover - 依赖真实指标实现故障时触发
            return {
                "enabled": True,
                "recorded": False,
                "errors": (_delivery_error("memory_materialization_metrics", exc),),
            }


def memory_materialization_worker_settings_from_env(environ: dict[str, str] | None = None) -> AgentMemoryMaterializationWorkerSettings:
    """从环境变量读取 worker 配置。"""

    source = environ or os.environ
    return AgentMemoryMaterializationWorkerSettings(
        enabled=_truthy(source.get("DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_ENABLED")),
        interval_seconds=_float_env(source, "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_INTERVAL_SECONDS", 30.0, minimum=0.1),
        batch_limit=_int_env(source, "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_BATCH_LIMIT", 50, minimum=1, maximum=100),
        run_once_on_startup=_truthy(source.get("DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_RUN_ON_STARTUP", "true")),
        max_consecutive_errors=_int_env(source, "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_MAX_CONSECUTIVE_ERRORS", 5, minimum=1, maximum=100),
        stop_timeout_seconds=_float_env(source, "DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_STOP_TIMEOUT_SECONDS", 5.0, minimum=0.1),
    )


def _runtime_event_payload(event: AgentRuntimeEvent | None) -> dict[str, Any] | None:
    """把 runtime event 转成 API/诊断友好的低敏结构。"""

    if event is None:
        return None
    return {
        "eventType": _event_value(event.event_type),
        "stage": event.stage,
        "message": event.message,
        "severity": _event_value(event.severity),
        "runId": event.run_id,
        "sequence": event.sequence,
        "attributes": dict(event.attributes),
        "createdAt": event.created_at.isoformat(),
    }


def _delivery_error(component: str, exc: Exception) -> dict[str, str]:
    """构造低敏错误摘要。"""

    return {
        "component": component,
        "errorType": type(exc).__name__,
        "message": str(exc)[:300],
    }


def _truthy(value: str | None) -> bool:
    """解析布尔环境变量。"""

    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def _int_env(
    source: dict[str, str],
    key: str,
    default: int,
    *,
    minimum: int,
    maximum: int,
) -> int:
    """读取有上下限的整数环境变量。"""

    try:
        value = int(source.get(key, default))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(value, maximum))


def _float_env(
    source: dict[str, str],
    key: str,
    default: float,
    *,
    minimum: float,
) -> float:
    """读取有下限的浮点环境变量。"""

    try:
        value = float(source.get(key, default))
    except (TypeError, ValueError):
        value = default
    return max(minimum, value)


def _utc_now() -> datetime:
    """返回 UTC aware 当前时间。"""

    return datetime.now(timezone.utc)


def _iso_or_none(value: datetime | None) -> str | None:
    """把可选时间转换为 ISO 字符串。"""

    return value.isoformat() if value else None


def _event_value(value: Any) -> Any:
    """把 Enum 转换为 JSON 友好值。"""

    return getattr(value, "value", value)
