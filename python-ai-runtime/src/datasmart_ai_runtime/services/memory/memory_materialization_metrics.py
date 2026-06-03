"""长期记忆物化 Prometheus 指标桥接器。

本模块把 5.12 中已经稳定下来的长期记忆物化 Runtime Event 转换为低基数 Prometheus 指标。

为什么不是直接在 Runner、AdminService 或 lease store 里写指标：
- Runner 负责执行候选，不应该知道 Prometheus 文本格式；
- AdminService 负责补偿状态机，不应该承担监控系统细节；
- lease store 负责并发控制事实，不应该因为监控需求改变持久化协议；
- Runtime Event 已经是低敏事实汇总，把指标建立在事件之上，可以让 API、CLI、未来常驻 worker 复用同一套观测语义。

为什么不直接依赖 `prometheus_client`：
- 当前 `python-ai-runtime` 默认依赖为空，保证本地学习、离线单测和轻量调试无需额外安装包；
- 本阶段只需要少量 Counter/Summary 文本输出，手写标准 exposition text 足够稳定；
- 后续如果需要 Histogram、multiprocess、ASGI middleware 或默认进程指标，再把本类替换为官方 client 适配器即可。
"""

from __future__ import annotations

from collections import defaultdict
from threading import RLock
from typing import Any, Iterable

from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType


class AgentMemoryMaterializationMetrics:
    """长期记忆物化低基数指标注册器。

    指标标签设计原则：
    - 只允许有限枚举，例如 `result`、`reason`、`severity`、`dry_run`；
    - 不把 `tenantId/projectId/candidateId/leaseId/requestId/runId/sessionId/workspaceKey` 放入标签；
    - 单条问题定位继续走 runtime event、lease/receipt 查询和审计日志；
    - Prometheus 指标只用于回答“这一类问题是否在升高、是否需要告警”。

    这种拆分是生产监控中很关键的边界：Prometheus 擅长低基数时间序列，不适合承载每条业务对象的明细。
    如果把 candidateId 或 traceId 当标签，真实客户环境中会快速制造大量时序，最终拖垮监控系统本身。
    """

    _RUN_RESULTS = {"succeeded", "partial_failed", "dead_lettered", "finalize_error"}
    _SEVERITIES = {"info", "warning", "error", "audit"}
    _CANDIDATE_RESULTS = {"scanned", "claimed", "succeeded", "failed", "skipped", "dead_lettered"}
    _SKIP_REASONS = {"retry_cooldown", "active_lease", "dead_letter", "already_succeeded", "other"}
    _REQUEUE_ACTIONS = {"dry_run_requeue", "scheduled_retry", "other"}
    _REQUEUE_STATUSES = {"leased", "succeeded", "failed", "dead_letter", "other"}
    _BOOL_LABELS = {"true", "false"}

    def __init__(self) -> None:
        self._lock = RLock()
        self._counters: dict[tuple[str, tuple[tuple[str, str], ...]], float] = defaultdict(float)

    def record_runtime_events(self, events: Iterable[AgentRuntimeEvent]) -> int:
        """批量记录 Runtime Event 并返回真正被本指标器识别的事件数量。

        参数：
        - `events`：任意 Runtime Event 序列。调用方可以直接把一批事件交给指标器，不需要先筛选事件类型。

        返回：
        - 被识别并写入指标的事件数量。未识别事件会被安全忽略，避免后续新增其他事件类型时破坏指标链路。
        """

        recorded = 0
        for event in events:
            if self.record_runtime_event(event):
                recorded += 1
        return recorded

    def record_runtime_event(self, event: AgentRuntimeEvent) -> bool:
        """记录单条 Runtime Event。

        当前只消费长期记忆物化相关事件：
        - `memory_materialization_run_completed`：转换为批次、候选、跳过原因和耗时指标；
        - `memory_materialization_requeue_recorded`：转换为管理员补偿重排计数。

        其他事件类型返回 `False`。这让指标器可以被挂在统一 event pipeline 后面，而不需要上游知道所有细节。
        """

        if event.event_type == AgentRuntimeEventType.MEMORY_MATERIALIZATION_RUN_COMPLETED:
            self._record_runner_event(event)
            return True
        if event.event_type == AgentRuntimeEventType.MEMORY_MATERIALIZATION_REQUEUE_RECORDED:
            self._record_requeue_event(event)
            return True
        return False

    def snapshot(self) -> dict[str, Any]:
        """返回低敏诊断快照。

        该方法用于单元测试和未来 `/agent/memory/diagnostics` 扩展。它不会返回任何业务对象 ID，只返回当前指标
        名称、标签和值，帮助确认指标链路是否被调用。
        """

        with self._lock:
            return {
                "metricCount": len(self._counters),
                "series": tuple(
                    {
                        "name": name,
                        "labels": dict(labels),
                        "value": value,
                    }
                    for (name, labels), value in sorted(self._counters.items())
                ),
            }

    def render_prometheus(self) -> str:
        """渲染 Prometheus exposition text。

        返回内容可以直接作为 FastAPI `Response(media_type="text/plain; version=0.0.4")` 的 body，也可以被单元测试
        断言。即使当前没有任何事件，仍输出 HELP/TYPE，便于 Prometheus 和运维人员发现端点存在。
        """

        with self._lock:
            counters = dict(self._counters)
        lines: list[str] = []
        for name, help_text in _METRIC_HELP.items():
            lines.append(f"# HELP {name} {help_text}")
            lines.append(f"# TYPE {name} counter")
            for (metric_name, labels), value in sorted(counters.items()):
                if metric_name != name:
                    continue
                lines.append(f"{name}{_label_text(labels)} {_format_number(value)}")
        lines.append("")
        return "\n".join(lines)

    def _record_runner_event(self, event: AgentRuntimeEvent) -> None:
        """把 Runner 汇总事件拆成低基数指标。

        `result` 是从事件属性推导出的聚合结果，而不是某条候选 ID：
        - `succeeded`：本轮没有失败、没有 DLQ、没有 fencing finalize error；
        - `partial_failed`：存在普通失败；
        - `dead_lettered`：本轮产生或携带 DLQ 事实；
        - `finalize_error`：存在 lease token fencing/finalize 回写失败，这是更高优先级的错误。
        """

        attrs = event.attributes
        result = self._runner_result(attrs)
        severity = _safe_label(_event_value(event.severity), self._SEVERITIES)
        with self._lock:
            self._inc(
                "datasmart_ai_memory_materialization_runs_total",
                {"result": result, "severity": severity},
            )
            self._inc_candidate_result("scanned", _int_attr(attrs, "scannedCount"))
            self._inc_candidate_result("claimed", _int_attr(attrs, "claimedCount"))
            self._inc_candidate_result("succeeded", _int_attr(attrs, "succeededCount"))
            self._inc_candidate_result("failed", _int_attr(attrs, "failedCount"))
            self._inc_candidate_result("skipped", _int_attr(attrs, "skippedCount"))
            self._inc_candidate_result("dead_lettered", _int_attr(attrs, "deadLetterCount"))
            self._inc_skip_reason("retry_cooldown", _int_attr(attrs, "retryCooldownSkippedCount"))
            self._inc_skip_reason("active_lease", _int_attr(attrs, "activeLeaseSkippedCount"))
            self._inc_skip_reason("dead_letter", _int_attr(attrs, "deadLetterSkippedCount"))
            self._inc_skip_reason("already_succeeded", _int_attr(attrs, "alreadySucceededSkippedCount"))
            self._inc(
                "datasmart_ai_memory_materialization_finalize_errors_total",
                {},
                _int_attr(attrs, "leaseFinalizeErrorCount"),
            )
            self._inc(
                "datasmart_ai_memory_materialization_duration_milliseconds_count",
                {"result": result},
            )
            self._inc(
                "datasmart_ai_memory_materialization_duration_milliseconds_sum",
                {"result": result},
                _int_attr(attrs, "durationMillis"),
            )

    def _record_requeue_event(self, event: AgentRuntimeEvent) -> None:
        """把管理员补偿事件转换为低基数重排计数。"""

        attrs = event.attributes
        action = _safe_label(str(attrs.get("action") or "other"), self._REQUEUE_ACTIONS)
        dry_run = _safe_label(str(bool(attrs.get("dryRun"))).lower(), self._BOOL_LABELS)
        after_status = _safe_label(str(attrs.get("afterStatus") or "other"), self._REQUEUE_STATUSES)
        with self._lock:
            self._inc(
                "datasmart_ai_memory_materialization_requeues_total",
                {
                    "action": action,
                    "dry_run": dry_run,
                    "after_status": after_status,
                },
            )

    def _runner_result(self, attrs: dict[str, Any]) -> str:
        """按运维优先级推导 Runner 批次结果标签。"""

        if _int_attr(attrs, "leaseFinalizeErrorCount") > 0:
            return "finalize_error"
        if _int_attr(attrs, "deadLetterCount") > 0:
            return "dead_lettered"
        if _int_attr(attrs, "failedCount") > 0:
            return "partial_failed"
        return "succeeded"

    def _inc_candidate_result(self, result: str, value: int) -> None:
        """记录候选级结果计数。"""

        self._inc("datasmart_ai_memory_materialization_candidates_total", {"result": result}, value)

    def _inc_skip_reason(self, reason: str, value: int) -> None:
        """记录 Runner 跳过原因计数。"""

        self._inc("datasmart_ai_memory_materialization_skips_total", {"reason": reason}, value)

    def _inc(self, name: str, labels: dict[str, str], value: float = 1) -> None:
        """递增一个指标序列。

        `value <= 0` 时不产生新序列，避免还没有数据的 label 被输出成噪声。Prometheus 可以通过缺失序列判断
        该类事件尚未发生；如果未来产品希望每个枚举都预初始化为 0，可以在诊断或启动阶段显式增加。
        """

        if value <= 0:
            return
        key = (name, tuple(sorted(labels.items())))
        self._counters[key] += float(value)


_METRIC_HELP = {
    "datasmart_ai_memory_materialization_runs_total": (
        "Total memory materialization runner batches grouped by low-cardinality result and severity."
    ),
    "datasmart_ai_memory_materialization_candidates_total": (
        "Total memory materialization candidate counts grouped by scanned, claimed, succeeded, failed, skipped or dead_lettered."
    ),
    "datasmart_ai_memory_materialization_skips_total": (
        "Total skipped memory materialization candidates grouped by bounded skip reason."
    ),
    "datasmart_ai_memory_materialization_finalize_errors_total": (
        "Total lease fencing or finalize errors observed during memory materialization."
    ),
    "datasmart_ai_memory_materialization_duration_milliseconds_count": (
        "Count of observed memory materialization runner batch durations."
    ),
    "datasmart_ai_memory_materialization_duration_milliseconds_sum": (
        "Sum of observed memory materialization runner batch durations in milliseconds."
    ),
    "datasmart_ai_memory_materialization_requeues_total": (
        "Total admin memory materialization requeue operations grouped by action, dry_run and after_status."
    ),
}


def _int_attr(attrs: dict[str, Any], key: str) -> int:
    """从 event attributes 中读取非负整数。"""

    try:
        return max(0, int(attrs.get(key) or 0))
    except (TypeError, ValueError):
        return 0


def _event_value(value: Any) -> str:
    """把 Enum 或普通值转换为字符串。"""

    return str(getattr(value, "value", value))


def _safe_label(value: str, allowed: set[str], default: str = "other") -> str:
    """把动态值收敛到允许枚举，防止指标标签基数失控。"""

    normalized = str(value or default).strip().lower()
    return normalized if normalized in allowed else default


def _label_text(labels: tuple[tuple[str, str], ...]) -> str:
    """渲染 Prometheus 标签文本。"""

    if not labels:
        return ""
    joined = ",".join(f'{key}="{_escape_label_value(value)}"' for key, value in labels)
    return "{" + joined + "}"


def _escape_label_value(value: str) -> str:
    """转义 Prometheus label value。"""

    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _format_number(value: float) -> str:
    """把浮点计数渲染为更易读的 Prometheus 数字。"""

    return str(int(value)) if value.is_integer() else str(value)
