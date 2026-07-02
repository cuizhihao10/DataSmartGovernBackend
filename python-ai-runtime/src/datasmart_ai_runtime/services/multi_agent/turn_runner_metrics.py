"""受控多 Agent Turn Runner 的低基数 Prometheus 指标桥接器。

`agentTurnRunner` 已经把多 Agent 能力从“会话状态视图”推进到“下一轮 turn 可恢复合同”：它能说明
哪些 specialist Agent 可以被主控以 manager-as-tools 方式调度、哪些 turn attempt 等待审批或控制面反馈、
以及真实执行前还缺哪些 Java host fact。同步响应和 Java projection 适合单次排障，Prometheus 指标则负责
持续观察生产趋势，例如：

- Turn Runner 是否长期卡在等待审批、等待控制面反馈或恢复阻断；
- manager-as-tools 描述数量是否异常升高，提示主控拆分过细或循环策略不收敛；
- requiredEvidence 是否长期缺 worker receipt、outbox confirmation 或 approval decision；
- Python Runtime 是否仍保持“无副作用 runner”边界，没有误执行工具、写 outbox 或创建审批。

指标设计原则：
- 只消费已经低敏化的 `agentTurnRunner` summary；
- 只使用固定枚举标签：run_status、session_status、durable_phase、turn_status、delivery_tier、resume_action、
  evidence_code、boundary；
- 不把 tenantId、projectId、actorId、requestId、runId、sessionId、turnId、workItemId、managerToolName、
  prompt、SQL、工具参数、样本数据、模型输出、token、checkpointId 或 commandId 放进标签或指标文本。
"""

from __future__ import annotations

from collections import defaultdict
from threading import RLock
from typing import Any, Mapping


class MultiAgentTurnRunnerMetrics:
    """受控多 Agent Turn Runner 的进程内低基数指标记录器。

    调用方只需要在 `/agent/plans` 生成 `agentTurnRunner` 后调用 `record_summary(...)`，在 `/agent/metrics`
    中拼接 `render_prometheus()`。本类不访问数据库、网络或文件，也不会执行任何 Agent turn。

    线程安全说明：
    FastAPI 单进程可能并发处理多个 Agent plan 请求，因此内部 counter 使用 `RLock` 保护。当前项目的其他
    Python 指标类也采用“轻量 in-process counter + Prometheus scrape”的模式，后续如果进入多实例生产环境，
    汇总与持久化应交给 Prometheus/remote_write，而不是在 Python Runtime 内部保存全局状态。
    """

    _STATUSES = {
        "langgraph_multi_agent_turn_runner_built",
        "disabled",
        "dependency_missing",
        "execution_failed",
        "other",
    }
    _RUN_STATUSES = {
        "no_agent_turn_candidates",
        "blocked_turn_depth_exceeded",
        "blocked_waiting_recovery",
        "waiting_approval_or_handoff_fact",
        "waiting_control_plane_feedback",
        "waiting_human_takeover",
        "ready_for_java_control_plane_handoff",
        "ready_for_specialist_agent_turns",
        "ready_for_draft_only_turns",
        "turn_runner_observed_only",
        "other",
    }
    _SESSION_STATUSES = {
        "blocked_waiting_recovery",
        "waiting_approval_or_handoff",
        "waiting_human_takeover",
        "waiting_control_plane_feedback",
        "ready_for_agent_turn",
        "ready_for_agent_turns",
        "ready_for_control_plane_handoff",
        "degraded_draft_only",
        "completed_or_summarized",
        "planned_not_started",
        "session_not_started",
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
    _TURN_STATUSES = {
        "blocked_turn_depth_exceeded",
        "blocked_waiting_recovery",
        "waiting_approval_or_handoff_fact",
        "waiting_control_plane_feedback",
        "waiting_human_takeover",
        "ready_for_specialist_turn",
        "ready_for_java_control_plane_handoff",
        "ready_for_draft_only_turn",
        "completed_or_summarized",
        "waiting_session_orchestrator_review",
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
    _EVIDENCE_CODES = {
        "turn_checkpoint_required",
        "runtime_recovery_fact_required",
        "approval_decision_fact_required",
        "control_plane_feedback_event_required",
        "human_takeover_fact_required",
        "java_command_proposal_or_outbox_required",
        "worker_receipt_required",
        "draft_review_fact_required",
        "approval_or_handoff_fact_required",
        "other",
    }
    _BOUNDARIES = {
        "tool_executed_by_python",
        "model_called_by_turn_runner",
        "outbox_written_by_python",
        "approval_created_by_python",
        "worker_dispatched_by_python",
        "java_control_plane_required_for_side_effects",
        "worker_receipt_required_for_side_effects",
    }
    _BOUNDARY_STATES = {"clean", "violation", "required", "missing"}

    def __init__(self) -> None:
        self._lock = RLock()
        self._counters: dict[tuple[str, tuple[tuple[str, str], ...]], float] = defaultdict(float)

    def record_summary(self, summary: Mapping[str, Any] | None) -> bool:
        """记录一次 `agentTurnRunner` 摘要。

        返回值：
        - `True`：summary 形状可识别，已经转换为指标；
        - `False`：summary 缺失或不是字典，已安全忽略。

        安全边界：
        本方法只读取状态枚举、turnAttempts 中的状态/层级/恢复动作/证据编码，以及少量 count 字段。
        即使调用方把敏感字段误塞入 summary 或 attempt，本方法也不会把它们放进 label。
        """

        if not isinstance(summary, Mapping):
            return False
        status = _safe_label(summary.get("status"), self._STATUSES)
        run_status = _safe_label(summary.get("runStatus"), self._RUN_STATUSES)
        session_status = _safe_label(summary.get("sessionStatus"), self._SESSION_STATUSES)
        durable_phase = _durable_phase_label(summary.get("durablePhase"), self._DURABLE_PHASES)
        attempts = tuple(item for item in _sequence(summary.get("turnAttempts")) if isinstance(item, Mapping))

        with self._lock:
            self._inc(
                "datasmart_ai_multi_agent_turn_runner_runs_total",
                {
                    "status": status,
                    "run_status": run_status,
                    "session_status": session_status,
                    "durable_phase": durable_phase,
                },
            )
            self._inc(
                "datasmart_ai_multi_agent_turn_runner_manager_tools_total",
                {"run_status": run_status},
                _non_negative_int(summary.get("managerAsToolsCount")),
            )
            self._record_attempts(attempts)
            self._record_boundaries(summary)
        return True

    def snapshot(self) -> dict[str, Any]:
        """返回当前指标快照，供测试和诊断复用。"""

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

    def _record_attempts(self, attempts: tuple[Mapping[str, Any], ...]) -> None:
        """记录 turn attempt 状态、层级、恢复动作和证据缺口分布。"""

        for attempt in attempts:
            turn_status = _safe_label(attempt.get("turnStatus"), self._TURN_STATUSES)
            delivery_tier = _safe_label(attempt.get("deliveryTier"), self._DELIVERY_TIERS)
            resume_action = _safe_label(attempt.get("resumeAction"), self._RESUME_ACTIONS)
            self._inc(
                "datasmart_ai_multi_agent_turn_runner_attempts_total",
                {
                    "turn_status": turn_status,
                    "delivery_tier": delivery_tier,
                    "resume_action": resume_action,
                },
            )
            for code in _sequence(attempt.get("requiredEvidenceCodes")):
                self._inc(
                    "datasmart_ai_multi_agent_turn_runner_required_evidence_total",
                    {"evidence_code": _safe_label(code, self._EVIDENCE_CODES)},
                )

    def _record_boundaries(self, summary: Mapping[str, Any]) -> None:
        """记录副作用边界状态。

        前五个字段正常应为 false；只要为 true 就记为 violation。后两个字段正常应为 true，表示 Java 控制面和
        worker receipt 仍然是副作用的必需边界；如果为 false，则记为 missing，方便告警侧捕捉合同退化。
        """

        for key, boundary in (
            ("toolExecutedByPython", "tool_executed_by_python"),
            ("modelCalledByTurnRunner", "model_called_by_turn_runner"),
            ("outboxWrittenByPython", "outbox_written_by_python"),
            ("approvalCreatedByPython", "approval_created_by_python"),
            ("workerDispatchedByPython", "worker_dispatched_by_python"),
        ):
            self._inc(
                "datasmart_ai_multi_agent_turn_runner_safety_boundary_total",
                {
                    "boundary": _safe_label(boundary, self._BOUNDARIES),
                    "state": "violation" if _truthy(summary.get(key)) else "clean",
                },
            )
        for key, boundary in (
            ("javaControlPlaneRequiredForSideEffects", "java_control_plane_required_for_side_effects"),
            ("workerReceiptRequiredForSideEffects", "worker_receipt_required_for_side_effects"),
        ):
            self._inc(
                "datasmart_ai_multi_agent_turn_runner_safety_boundary_total",
                {
                    "boundary": _safe_label(boundary, self._BOUNDARIES),
                    "state": "required" if _truthy(summary.get(key)) else "missing",
                },
            )

    def _inc(self, name: str, labels: dict[str, str], value: float = 1) -> None:
        """递增一个指标序列；`value <= 0` 时不创建噪声序列。"""

        if value <= 0:
            return
        key = (name, tuple(sorted(labels.items())))
        self._counters[key] += float(value)


_METRIC_HELP = {
    "datasmart_ai_multi_agent_turn_runner_runs_total": (
        "Total controlled multi-agent turn runner summaries grouped by bounded status, run status and session status."
    ),
    "datasmart_ai_multi_agent_turn_runner_attempts_total": (
        "Total controlled multi-agent turn attempts grouped by bounded turn status, delivery tier and resume action."
    ),
    "datasmart_ai_multi_agent_turn_runner_required_evidence_total": (
        "Total required host facts for controlled multi-agent turn attempts grouped by bounded evidence code."
    ),
    "datasmart_ai_multi_agent_turn_runner_manager_tools_total": (
        "Total low-sensitive manager-as-tools descriptors grouped by bounded turn runner run status."
    ),
    "datasmart_ai_multi_agent_turn_runner_safety_boundary_total": (
        "Total controlled turn runner side-effect boundary observations grouped by bounded boundary and state."
    ),
}


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


def _durable_phase_label(value: Any, allowed: set[str]) -> str:
    """Durable phase 缺失时单独标记为 not_recorded，而不是和未知值混为 other。"""

    if value is None or str(value).strip() == "":
        return "not_recorded"
    return _safe_label(value, allowed)


def _normalize_label_value(value: Any) -> str:
    """归一化 Prometheus label 候选值。"""

    text = str(value or "").strip()
    normalized = "".join(char.lower() if char.isalnum() else "_" for char in text)
    while "__" in normalized:
        normalized = normalized.replace("__", "_")
    return normalized.strip("_")


def _truthy(value: Any) -> bool:
    """宽松解析布尔字段，兼容 Python bool、字符串和数字。"""

    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _non_negative_int(value: Any) -> int:
    """把数量字段转换为非负整数。"""

    if isinstance(value, int):
        return max(0, value)
    try:
        return max(0, int(str(value).strip()))
    except (TypeError, ValueError):
        return 0


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
