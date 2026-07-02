"""受控多 Agent 执行会话的低基数 Prometheus 指标桥接器。

`agentExecutionSession` 让 `/agent/plans` 能看到一次多 Agent 会话中的 active/standby/deferred 角色、
每个工作项的 sessionStatus、resumeAction 和 Durable Loop phase。但同步响应适合单次排障，不适合运维
持续观察趋势。因此本模块把会话摘要压缩成少量 Prometheus counter，用于回答：

- 多 Agent 会话主要停在哪些状态，是等待审批、等待控制面反馈，还是已经可交给 Java 控制面；
- 必做 Agent、受控范围 Agent、轻量化 Agent 的 active/standby/deferred 覆盖是否符合预期；
- 工作项下一步恢复动作是否长期堆积在 approval、control-plane replay 或 recovery；
- LangGraph 执行前计划和会话调度 fallback 的比例是否异常。

指标设计原则：
- 只消费已经低敏化的 `agentExecutionSession` summary；
- 只使用固定枚举标签：session_status、source、durable_phase、delivery_tier、resume_action、role_group、
  role_state；
- 不把 tenantId、projectId、actorId、requestId、runId、sessionId、toolName、skillCode、memory namespace、
  prompt、SQL、工具参数、样本数据或模型输出放进标签或数值。

这也是商业化 Agent 平台的典型分层：单次细节走 runtime event / replay / Java projection，趋势告警走
Prometheus 低基数指标，二者互补而不是互相替代。
"""

from __future__ import annotations

from collections import defaultdict
from threading import RLock
from typing import Any, Mapping


class MultiAgentExecutionSessionMetrics:
    """受控多 Agent 执行会话的进程内低基数指标记录器。

    该类没有外部依赖，也不主动读取会话、数据库或网络。调用方只需要在 `/agent/plans` 生成
    `agentExecutionSession` 后调用 `record_summary(...)`，在 `/agent/metrics` 中调用
    `render_prometheus()` 即可。

    线程安全说明：
    FastAPI 同一进程内可能同时处理多个计划请求，因此计数器用 `RLock` 保护。这里没有引入全局 registry
    或第三方 Prometheus client，是为了和项目当前已有 memory/tool gate 指标保持一致，也方便单元测试直接
    读取 snapshot。
    """

    _SESSION_STATUSES = {
        "blocked_waiting_recovery",
        "waiting_approval_or_handoff",
        "waiting_human_takeover",
        "waiting_control_plane_feedback",
        "ready_for_agent_turn",
        "ready_for_agent_turns",
        "degraded_draft_only",
        "ready_for_control_plane_handoff",
        "completed_or_summarized",
        "planned_not_started",
        "session_not_started",
        "other",
    }
    _SOURCES = {
        "langgraph_multi_agent_execution_plan",
        "agent_session_scheduling_fallback",
        "fallback_master_only",
        "no_agent_source_available",
        "other",
    }
    _DURABLE_PHASES = {
        "not_recorded",
        "plan_created",
        "waiting_control_plane",
        "waiting_approval",
        "ready_for_second_turn",
        "second_turn_completed",
        "stopped_by_policy",
        "manual_takeover_required",
        "other",
    }
    _DELIVERY_TIERS = {"must_do", "controlled_scope", "lightweight", "runtime_governance", "other"}
    _RESUME_ACTIONS = {
        "wait_for_runtime_recovery_fact",
        "wait_for_approval_or_handoff_fact",
        "replay_control_plane_events",
        "hand_off_to_human_operator",
        "coordinate_specialist_next_turn",
        "prepare_specialist_next_turn",
        "prepare_low_sensitive_draft_only",
        "handoff_to_java_control_plane",
        "wait_for_session_orchestrator_review",
        "other",
    }
    _ROLE_GROUPS = {"must_do", "controlled_scope", "lightweight", "other"}
    _ROLE_STATES = {"active", "standby", "deferred"}

    def __init__(self) -> None:
        self._lock = RLock()
        self._counters: dict[tuple[str, tuple[tuple[str, str], ...]], float] = defaultdict(float)

    def record_summary(self, summary: Mapping[str, Any] | None) -> bool:
        """记录一次 `agentExecutionSession` 摘要。

        返回值：
        - `True`：summary 形状可识别，已经转换为指标；
        - `False`：summary 缺失或不是字典，已安全忽略。

        安全边界：
        本方法只读取 summary 顶层的状态枚举、workItems 中的状态/层级/恢复动作，以及 rosterCoverage 中的
        角色数量。即使调用方把敏感字段误塞进 summary，也不会被本方法读取为 label。
        """

        if not isinstance(summary, Mapping):
            return False
        session_status = _safe_label(summary.get("status"), self._SESSION_STATUSES)
        source = _safe_label(summary.get("source"), self._SOURCES)
        # `durablePhase` 需要区分两种情况：
        # - 字段缺失/为空：说明当前链路确实没有接 Durable Agent Loop，记录为 `not_recorded`；
        # - 字段存在但不是白名单枚举：说明上游出现了新值或脏值，必须归入 `other`，不能误装成未记录。
        # 这能让告警侧区分“功能未开启”和“合同漂移/异常状态”，避免生产排障被指标噪声误导。
        raw_durable_phase = summary.get("durablePhase")
        durable_phase = (
            "not_recorded"
            if raw_durable_phase is None or str(raw_durable_phase).strip() == ""
            else _safe_label(raw_durable_phase, self._DURABLE_PHASES)
        )
        work_items = tuple(item for item in _sequence(summary.get("workItems")) if isinstance(item, Mapping))
        roster_coverage = _mapping(summary.get("rosterCoverage"))

        with self._lock:
            self._inc(
                "datasmart_ai_multi_agent_execution_sessions_total",
                {
                    "session_status": session_status,
                    "source": source,
                    "durable_phase": durable_phase,
                },
            )
            self._record_work_items(work_items, source)
            self._record_roster_coverage(roster_coverage)
        return True

    def snapshot(self) -> dict[str, Any]:
        """返回当前指标快照，供测试或诊断接口复用。"""

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

    def _record_work_items(self, work_items: tuple[Mapping[str, Any], ...], source: str) -> None:
        """记录 Agent 工作项状态分布。"""

        for item in work_items:
            self._inc(
                "datasmart_ai_multi_agent_execution_work_items_total",
                {
                    "session_status": _safe_label(item.get("sessionStatus"), self._SESSION_STATUSES),
                    "delivery_tier": _safe_label(item.get("deliveryTier"), self._DELIVERY_TIERS),
                    "resume_action": _safe_label(item.get("resumeAction"), self._RESUME_ACTIONS),
                    "source": source,
                },
            )

    def _record_roster_coverage(self, coverage: Mapping[str, Any]) -> None:
        """记录 active/standby/deferred 角色覆盖数量。

        这里按角色组和状态计数，不按具体 role 输出标签。这样可以观察“必做 Agent 是否经常 standby”，但不会
        为每个运行时角色、租户或请求制造独立时序。
        """

        for key, role_group, role_state in (
            ("activeMustDoRoles", "must_do", "active"),
            ("standbyMustDoRoles", "must_do", "standby"),
            ("activeControlledScopeRoles", "controlled_scope", "active"),
            ("standbyControlledScopeRoles", "controlled_scope", "standby"),
            ("deferredLightweightRoles", "lightweight", "deferred"),
        ):
            self._inc(
                "datasmart_ai_multi_agent_execution_roster_roles_total",
                {
                    "role_group": _safe_label(role_group, self._ROLE_GROUPS),
                    "role_state": _safe_label(role_state, self._ROLE_STATES),
                },
                len(_sequence(coverage.get(key))),
            )

    def _inc(self, name: str, labels: dict[str, str], value: float = 1) -> None:
        """递增一个指标序列；`value <= 0` 时不创建噪声序列。"""

        if value <= 0:
            return
        key = (name, tuple(sorted(labels.items())))
        self._counters[key] += float(value)


_METRIC_HELP = {
    "datasmart_ai_multi_agent_execution_sessions_total": (
        "Total controlled multi-agent execution sessions grouped by bounded session status, source and durable phase."
    ),
    "datasmart_ai_multi_agent_execution_work_items_total": (
        "Total controlled multi-agent work items grouped by bounded status, delivery tier, resume action and source."
    ),
    "datasmart_ai_multi_agent_execution_roster_roles_total": (
        "Total controlled multi-agent roster roles grouped by bounded role group and active/standby/deferred state."
    ),
}


def _mapping(value: Any) -> Mapping[str, Any]:
    """安全读取字典字段。"""

    return value if isinstance(value, Mapping) else {}


def _sequence(value: Any) -> tuple[Any, ...]:
    """安全读取序列字段；字符串按单个值处理，避免被拆成字符。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return (value,) if value.strip() else ()
    return tuple(value) if isinstance(value, (list, tuple, set, frozenset)) else ()


def _safe_label(value: Any, allowed: set[str], default: str = "other") -> str:
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
