"""工具动作 checkpoint 低基数 Prometheus 指标桥接器。

本模块把 checkpoint query/resume-preview Runtime Event 转换成少量低基数指标。设计重点是：
- 指标只按 operation、result、severity、fact_state 等固定枚举聚合；
- 不把 tenantId、projectId、actorId、checkpointId、threadId、requestId、runId 或 sessionId 放进 label；
- 单次请求排障继续通过 runtime event 和审计链路完成，Prometheus 只回答“某类问题是否在升高”。

这延续了长期记忆物化指标的实现风格：当前不引入 `prometheus_client` 依赖，先手写标准 exposition text。
后续如果需要多进程 collector、Histogram 或 OpenTelemetry exporter，可以替换这一层而不影响 checkpoint API。
"""

from __future__ import annotations

from collections import defaultdict
from threading import RLock
from typing import Any, Iterable

from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType


class ToolActionCheckpointMetrics:
    """工具动作 checkpoint 低基数指标注册器。"""

    _OPERATIONS = {"query", "resume_preview"}
    _RESULTS = {"found", "not_found", "missing_locator", "scope_mismatch", "ready", "waiting", "provider_error"}
    _SEVERITIES = {"info", "warning", "error", "audit"}
    _FACT_STATES = {"accepted", "missing", "rejected", "required"}

    def __init__(self) -> None:
        self._lock = RLock()
        self._counters: dict[tuple[str, tuple[tuple[str, str], ...]], float] = defaultdict(float)

    def record_runtime_events(self, events: Iterable[AgentRuntimeEvent]) -> int:
        """批量记录 checkpoint Runtime Event。"""

        recorded = 0
        for event in events:
            if self.record_runtime_event(event):
                recorded += 1
        return recorded

    def record_runtime_event(self, event: AgentRuntimeEvent) -> bool:
        """记录单条 checkpoint Runtime Event。

        未识别事件会被忽略并返回 False，便于未来把该指标器挂到统一 event pipeline 后面。
        """

        if event.event_type not in {
            AgentRuntimeEventType.TOOL_ACTION_CHECKPOINT_QUERIED,
            AgentRuntimeEventType.TOOL_ACTION_CHECKPOINT_RESUME_PREVIEWED,
        }:
            return False
        attrs = event.attributes
        operation = _safe_label(str(attrs.get("operation") or "query"), self._OPERATIONS, "query")
        result = _safe_label(str(attrs.get("metricResult") or "not_found"), self._RESULTS, "not_found")
        severity = _safe_label(_event_value(event.severity), self._SEVERITIES, "audit")
        with self._lock:
            self._inc(
                "datasmart_ai_tool_action_checkpoint_events_total",
                {"operation": operation, "result": result, "severity": severity},
            )
            self._inc(
                "datasmart_ai_tool_action_checkpoint_access_issues_total",
                {"operation": operation, "result": result},
                _int_attr(attrs, "accessIssueCount"),
            )
            if operation == "query":
                self._inc(
                    "datasmart_ai_tool_action_checkpoint_returned_total",
                    {"result": result},
                    _int_attr(attrs, "checkpointCount"),
                )
            else:
                self._record_fact_state("accepted", _int_attr(attrs, "acceptedFactTypeCount"))
                self._record_fact_state("missing", len(_tuple_attr(attrs, "missingFactTypes")))
                self._record_fact_state("rejected", len(_tuple_attr(attrs, "rejectedFactTypes")))
                self._record_fact_state("required", len(_tuple_attr(attrs, "requiredFactTypes")))
        return True

    def snapshot(self) -> dict[str, Any]:
        """返回低敏指标快照，供单元测试和诊断接口复用。"""

        with self._lock:
            return {
                "metricCount": len(self._counters),
                "series": tuple(
                    {"name": name, "labels": dict(labels), "value": value}
                    for (name, labels), value in sorted(self._counters.items())
                ),
            }

    def render_prometheus(self) -> str:
        """渲染 Prometheus exposition text。"""

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

    def _record_fact_state(self, state: str, value: int) -> None:
        """记录 resume-preview 事实类型状态计数。"""

        self._inc(
            "datasmart_ai_tool_action_checkpoint_resume_facts_total",
            {"fact_state": _safe_label(state, self._FACT_STATES)},
            value,
        )

    def _inc(self, name: str, labels: dict[str, str], value: float = 1) -> None:
        """递增一个指标序列，value<=0 时不创建噪声序列。"""

        if value <= 0:
            return
        key = (name, tuple(sorted(labels.items())))
        self._counters[key] += float(value)


_METRIC_HELP = {
    "datasmart_ai_tool_action_checkpoint_events_total": (
        "Total tool action checkpoint query and resume-preview audit events grouped by operation, result and severity."
    ),
    "datasmart_ai_tool_action_checkpoint_access_issues_total": (
        "Total checkpoint access issues grouped by operation and bounded result."
    ),
    "datasmart_ai_tool_action_checkpoint_returned_total": (
        "Total low-sensitive checkpoints returned by query operations grouped by bounded result."
    ),
    "datasmart_ai_tool_action_checkpoint_resume_facts_total": (
        "Total resume fact types observed by checkpoint resume-preview grouped by bounded fact state."
    ),
}


def _int_attr(attrs: dict[str, Any], key: str) -> int:
    """从 event attributes 中读取非负整数。"""

    try:
        return max(0, int(attrs.get(key) or 0))
    except (TypeError, ValueError):
        return 0


def _tuple_attr(attrs: dict[str, Any], key: str) -> tuple[Any, ...]:
    """读取 tuple/list 属性，非序列返回空。"""

    value = attrs.get(key)
    return tuple(value) if isinstance(value, (list, tuple)) else ()


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
