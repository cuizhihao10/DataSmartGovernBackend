"""LangGraph execution gate 的低基数 Prometheus 指标桥接器。

本模块把 `agent_execution_gate_recorded` Runtime Event 转换为少量聚合指标，用于回答生产运维最关心的
几个问题：
- 当前 Agent 工具执行前门禁主要卡在哪个分支，例如等待审批、等待澄清、等待 Java 控制面恢复预检；
- LangGraph 图是否频繁降级到 fallback，例如依赖缺失、被关闭、执行失败；
- readiness 中各类决策的数量分布是否异常升高，例如 blocked、throttled、approval_required；
- resume preflight 需要的服务端事实数量是否持续堆积。

为什么不直接把完整 event 原样输出到 Prometheus：
- Prometheus 适合低基数时间序列，不适合存放每个 tenant、project、request、checkpoint 或 command 的明细；
- 明细定位应该继续走 runtime event、replay、审计日志和 Java projection；
- 指标标签必须是固定枚举，否则真实客户环境中会很快制造海量时序，拖垮监控系统，也扩大敏感信息泄露面。

因此这里严格只使用 gate_route、gate_status、result、fallback、severity、decision、fact_state 这些有界标签。
"""

from __future__ import annotations

from collections import defaultdict
from threading import RLock
from typing import Any, Iterable, Mapping

from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType


class LangGraphExecutionGateMetrics:
    """LangGraph execution gate 的低基数指标注册器。

    设计边界：
    - 只消费低敏 Runtime Event 或已经裁剪过的 workflow summary；
    - 不读取 ToolPlan.arguments、用户 objective、模型输出、SQL、样本数据、凭证或内部 endpoint；
    - 不把 tenantId、projectId、actorId、requestId、runId、sessionId、checkpointId、commandId 放入 label；
    - 使用线程锁保护计数器，适配 FastAPI 多请求并发场景下的简单进程内指标聚合。
    """

    _GATE_ROUTES = {
        "no_tool_plan",
        "blocked",
        "human_input",
        "human_approval",
        "capacity_wait",
        "draft_review",
        "resume_preflight",
        "unavailable",
        "unrouted",
        "other",
    }
    _GATE_STATUSES = {
        "not_evaluated",
        "unavailable",
        "no_tool_plan",
        "blocked_before_execution",
        "waiting_human_input",
        "waiting_approval_fact",
        "waiting_capacity",
        "waiting_draft_review",
        "ready_for_java_control_plane_preflight",
        "other",
    }
    _RESULTS = {"evaluated", "disabled", "dependency_missing", "execution_failed", "fallback", "other"}
    _SEVERITIES = {"info", "warning", "error", "audit"}
    _BOOL_LABELS = {"true", "false"}
    _READINESS_COUNT_KEYS = {
        "totalCount": "total",
        "executableCount": "executable",
        "approvalRequiredCount": "approval_required",
        "clarificationRequiredCount": "clarification_required",
        "draftOnlyCount": "draft_only",
        "queuedAsyncCount": "queued_async",
        "throttledCount": "throttled",
        "blockedCount": "blocked",
    }
    _FACT_STATES = {"required"}

    def __init__(self) -> None:
        self._lock = RLock()
        self._counters: dict[tuple[str, tuple[tuple[str, str], ...]], float] = defaultdict(float)

    def record_runtime_events(self, events: Iterable[AgentRuntimeEvent]) -> int:
        """批量记录 Runtime Event，并返回真正被本指标器识别的事件数量。

        调用方可以直接把 `plan.runtime_events` 全量传进来，本方法会自行过滤非 execution gate 事件。这样后续
        API 层、worker、事件回放或 Kafka consumer 都能复用同一个指标入口，而不需要在上游散落事件类型判断。
        """

        recorded = 0
        for event in events:
            if self.record_runtime_event(event):
                recorded += 1
        return recorded

    def record_runtime_event(self, event: AgentRuntimeEvent) -> bool:
        """记录单条 `agent_execution_gate_recorded` 事件。

        返回值语义：
        - `True`：事件属于 LangGraph execution gate，已经转为指标；
        - `False`：事件与本指标器无关，已安全忽略。

        指标转化只读取 event attributes 中的低敏字段。即使上游错误地塞入了 checkpointId、token、SQL 等字段，
        本方法也不会把这些字段渲染为 label 或 metric value。
        """

        if event.event_type != AgentRuntimeEventType.AGENT_EXECUTION_GATE_RECORDED:
            return False
        self.record_summary(event.attributes, severity=_event_value(event.severity))
        return True

    def record_summary(self, summary: Mapping[str, Any], *, severity: str | None = None) -> bool:
        """记录已经低敏化的 execution gate summary。

        该方法主要用于测试、离线诊断或未来直接从 workflow summary 采集指标的场景。生产主链路优先使用
        `record_runtime_event(...)`，因为 Runtime Event 已经携带 request/run/session sequence 等回放语义。
        这里仍然不把这些明细维度放入 Prometheus label，只按固定枚举聚合。
        """

        route = _safe_label(str(summary.get("gateRoute") or "unavailable"), self._GATE_ROUTES, "other")
        status = _safe_label(str(summary.get("gateStatus") or "unavailable"), self._GATE_STATUSES, "other")
        fallback = _safe_label(str(bool(summary.get("fallbackUsed"))).lower(), self._BOOL_LABELS, "false")
        result = _result_label(summary.get("status"), fallback == "true")
        severity_label = _safe_label(str(severity or _severity_from_route(route)), self._SEVERITIES, "info")
        readiness_counts = _mapping(summary.get("readinessCounts"))
        resume_gate = _mapping(summary.get("resumeGate"))
        required_facts = _sequence(summary.get("resumeRequiredFactTypes")) or _sequence(
            resume_gate.get("requiredFactTypes")
        )

        with self._lock:
            self._inc(
                "datasmart_ai_langgraph_execution_gate_events_total",
                {
                    "gate_route": route,
                    "gate_status": status,
                    "result": result,
                    "fallback": fallback,
                    "severity": severity_label,
                },
            )
            for count_key, decision in self._READINESS_COUNT_KEYS.items():
                self._inc(
                    "datasmart_ai_langgraph_execution_gate_readiness_items_total",
                    {"decision": decision, "gate_route": route},
                    _int_attr(readiness_counts, count_key),
                )
            self._inc(
                "datasmart_ai_langgraph_execution_gate_resume_facts_total",
                {"fact_state": "required", "gate_route": route},
                len(required_facts),
            )
        return True

    def snapshot(self) -> dict[str, Any]:
        """返回低敏指标快照，供单元测试和未来诊断接口复用。"""

        with self._lock:
            return {
                "metricCount": len(self._counters),
                "series": tuple(
                    {"name": name, "labels": dict(labels), "value": value}
                    for (name, labels), value in sorted(self._counters.items())
                ),
            }

    def render_prometheus(self) -> str:
        """渲染 Prometheus exposition text。

        即使当前还没有记录任何 gate 事件，也会输出固定 HELP/TYPE 声明，方便 Prometheus 抓取端确认指标端点
        已经挂载。真实业务值只在事件发生后才生成，避免无意义的零值时序膨胀。
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

    def _inc(self, name: str, labels: dict[str, str], value: float = 1) -> None:
        """递增一个指标序列，`value <= 0` 时不创建噪声序列。"""

        if value <= 0:
            return
        key = (name, tuple(sorted(labels.items())))
        self._counters[key] += float(value)


_METRIC_HELP = {
    "datasmart_ai_langgraph_execution_gate_events_total": (
        "Total LangGraph execution gate runtime events grouped by bounded route, status, result, fallback and severity."
    ),
    "datasmart_ai_langgraph_execution_gate_readiness_items_total": (
        "Total readiness item counts observed by LangGraph execution gate grouped by bounded decision and route."
    ),
    "datasmart_ai_langgraph_execution_gate_resume_facts_total": (
        "Total resume fact types required by LangGraph execution gate grouped by bounded fact state and route."
    ),
}


def _result_label(status: Any, fallback_used: bool) -> str:
    """把 workflow status 收敛为有限 result 标签。"""

    normalized = _normalize_label_value(status)
    if not fallback_used:
        return "evaluated"
    if "disabled" in normalized:
        return "disabled"
    if "dependency_missing" in normalized:
        return "dependency_missing"
    if "execution_failed" in normalized:
        return "execution_failed"
    return "fallback"


def _severity_from_route(route: str) -> str:
    """在没有 Runtime Event severity 时，根据 dominant route 推导粗粒度严重级别。"""

    if route == "blocked":
        return "error"
    if route in {"human_input", "human_approval", "capacity_wait"}:
        return "audit"
    if route == "draft_review":
        return "warning"
    return "info"


def _mapping(value: Any) -> Mapping[str, Any]:
    """安全读取字典字段。"""

    return value if isinstance(value, Mapping) else {}


def _sequence(value: Any) -> tuple[Any, ...]:
    """安全读取列表/元组字段，字符串按单个元素处理。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return (value,) if value.strip() else ()
    return tuple(value) if isinstance(value, (list, tuple, set, frozenset)) else ()


def _int_attr(attrs: Mapping[str, Any], key: str) -> int:
    """从 mapping 中读取非负整数。"""

    try:
        return max(0, int(attrs.get(key) or 0))
    except (TypeError, ValueError):
        return 0


def _event_value(value: Any) -> str:
    """把 Enum 或普通值转换为字符串。"""

    return str(getattr(value, "value", value))


def _safe_label(value: str, allowed: set[str], default: str = "other") -> str:
    """把动态值收敛到允许枚举，防止指标标签基数失控。"""

    normalized = _normalize_label_value(value or default)
    return normalized if normalized in allowed else default


def _normalize_label_value(value: Any) -> str:
    """归一化 Prometheus label 候选值。"""

    return str(value or "").strip().lower().replace("-", "_")


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
