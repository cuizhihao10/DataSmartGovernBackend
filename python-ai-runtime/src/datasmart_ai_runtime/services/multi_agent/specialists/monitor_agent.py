"""真实 MONITOR_AGENT：只读任务监控、阈值诊断与低敏摘要。

本文件刻意把「事实采集」和「语言总结」拆成两个可注入边界：

* :class:`TaskMonitoringClient` 是唯一可以提供任务状态的对象。它应当由
  Java 控制面、只读查询服务或测试替身实现；Agent 不会从 prompt 推测进度。
* :class:`MonitoringSummaryModel` 只能接收已经脱敏的事实并返回文字摘要和
  建议。模型返回的 status、progress、health 或 anomaly 字段永远不会写回
  结构化监控结果。

因此，一个 Durable 调度器可以安全地每隔一段时间调用一次
``MonitorSpecialistAgent.execute``：每一轮读取一个确定性快照，计算下一次
轮询时间，并在定期任务或 CDC 任务仍然存续时继续轮询，而不是把一次运行的
失败误判成整个长期任务「未完成」。
"""

from __future__ import annotations

import math
import re
import time
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from types import MappingProxyType
from typing import Any, Protocol

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistAuditScope,
    SpecialistEventSink,
    SpecialistToolActivity,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)


# 这是 MONITOR_AGENT 唯一允许调用的只读能力。即使主 Agent 把别的工具名称
# 放进 delegation，下面的执行路径也不会把它们传给客户端或模型。
MONITOR_TOOL_CODE = "task.monitor.read"
TASK_MONITORING_TOOL_CODE = MONITOR_TOOL_CODE


class TaskKind(str, Enum):
    """任务的生命周期语义，而不是某一次轮询的运行状态。

    ``LONG_RUNNING`` 代表有明确终点的长任务；``PERIODIC`` 代表调度器会
    不断创建最近一次运行；``CDC_REALTIME`` 代表长期存在的实时链路。把
    kind 单独建模很重要，因为同一个 ``FAILED`` 状态在三类任务中的
    ``terminal`` 含义不同。
    """

    LONG_RUNNING = "LONG_RUNNING"
    PERIODIC = "PERIODIC"
    CDC_REALTIME = "CDC_REALTIME"


class TaskLifecycleStatus(str, Enum):
    """控制面提供的六种任务生命周期状态。

    Agent 只接受这六个归一化状态；未知状态不会被猜测成 RUNNING 或
    SUCCEEDED，而是让本轮监控 fail-closed。
    """

    RUNNING = "RUNNING"
    QUEUED = "QUEUED"
    SCHEDULED = "SCHEDULED"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class MonitorHealth(str, Enum):
    """由事实和结构化阈值计算出的低敏健康状态。"""

    HEALTHY = "HEALTHY"
    DEGRADED = "DEGRADED"
    UNHEALTHY = "UNHEALTHY"
    STOPPED = "STOPPED"
    UNKNOWN = "UNKNOWN"


# 兼容调用方可能采用的描述性名称，同时保持一个权威枚举类型。
TaskMonitoringStatus = TaskLifecycleStatus
TaskMonitoringKind = TaskKind


@dataclass(frozen=True)
class TaskMonitoringQuery:
    """传给只读监控客户端的最小查询范围。

    查询中重复携带租户、项目、用户和 delegation，是为了让客户端可以在
    数据库查询前再次做行级授权，而不是只相信 Python Runtime 的上游检查。
    ``task_kind`` 可以为空，表示由控制面快照补充；它绝不会由模型猜测。
    """

    tenant_id: str
    project_id: str
    actor_id: str
    delegation_id: str
    task_id: str
    task_kind: TaskKind | None = None
    application_id: str | None = None
    run_id: str | None = None

    def __post_init__(self) -> None:
        """在离开 Agent 前校验查询的权限主体和资源标识。"""

        required = {
            "tenant_id": self.tenant_id,
            "project_id": self.project_id,
            "actor_id": self.actor_id,
            "delegation_id": self.delegation_id,
            "task_id": self.task_id,
        }
        missing = tuple(name for name, value in required.items() if not _text(value))
        if missing:
            raise ValueError(f"监控查询缺少必要字段: {', '.join(missing)}")


@dataclass(frozen=True)
class TaskMonitoringSnapshot:
    """任务监控客户端返回的确定性事实快照。

    这些字段只表达「控制面观察到了什么」，不表达 Agent 的推断。字段可为
    ``None``，因为例如 CDC 没有总行数、定期任务没有一个永久进度百分比；
    ``None`` 会在输出中保留为未知，而不是被补成 0 或 100。
    """

    task_id: str
    status: TaskLifecycleStatus | str
    task_kind: TaskKind | str = TaskKind.LONG_RUNNING
    phase: str | None = None
    rows_total: int | None = None
    rows_processed: int | None = None
    success_count: int | None = None
    failure_count: int | None = None
    throughput_rows_per_second: float | None = None
    baseline_throughput_rows_per_second: float | None = None
    latency_ms: float | None = None
    heartbeat_age_seconds: float | None = None
    heartbeat_present: bool | None = None
    heartbeat_at: str | None = None
    queue_wait_seconds: float | None = None
    cdc_lag_seconds: float | None = None
    checkpoint: Mapping[str, Any] | None = None
    schedule: Mapping[str, Any] | None = None
    exception: Mapping[str, Any] | None = None
    captured_at: str | None = None
    last_run_status: TaskLifecycleStatus | str | None = None
    last_run_at: str | None = None
    next_run_at: str | None = None
    schedule_missed: bool | None = None
    missed_schedule_count: int | None = None
    last_success_at: str | None = None
    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    delegation_id: str | None = None


@dataclass(frozen=True)
class MonitoringThresholds:
    """本轮监控使用的结构化阈值和轮询间隔。

    阈值来自控制面或 Agent 默认配置，不能由模型修改。所有值在构造时做
    范围校验；错误配置直接失败，而不是静默使用一个可能不安全的默认值。
    """

    queue_timeout_seconds: float = 300.0
    heartbeat_timeout_seconds: float = 120.0
    throughput_drop_ratio: float = 0.50
    failure_rate_threshold: float = 0.05
    cdc_lag_threshold_seconds: float = 60.0
    schedule_miss_grace_seconds: float = 0.0
    queued_poll_seconds: int = 15
    running_poll_seconds: int = 30
    scheduled_poll_seconds: int = 60
    periodic_poll_seconds: int = 60
    realtime_poll_seconds: int = 15

    def __post_init__(self) -> None:
        """拒绝负阈值、无效比例和非正轮询间隔，避免告警策略失真。"""

        non_negative = {
            "queue_timeout_seconds": self.queue_timeout_seconds,
            "heartbeat_timeout_seconds": self.heartbeat_timeout_seconds,
            "cdc_lag_threshold_seconds": self.cdc_lag_threshold_seconds,
            "schedule_miss_grace_seconds": self.schedule_miss_grace_seconds,
        }
        for name, value in non_negative.items():
            parsed = _finite_number(value)
            if parsed is None or parsed < 0:
                raise ValueError(f"监控阈值 {name} 必须是非负有限数字")
        throughput_drop_ratio = _finite_number(self.throughput_drop_ratio)
        if throughput_drop_ratio is None or not 0 <= throughput_drop_ratio <= 1:
            raise ValueError("监控阈值 throughput_drop_ratio 必须位于 0 到 1 之间")
        failure_rate_threshold = _finite_number(self.failure_rate_threshold)
        if failure_rate_threshold is None or not 0 <= failure_rate_threshold <= 1:
            raise ValueError("监控阈值 failure_rate_threshold 必须位于 0 到 1 之间")
        for name in (
            "queued_poll_seconds",
            "running_poll_seconds",
            "scheduled_poll_seconds",
            "periodic_poll_seconds",
            "realtime_poll_seconds",
        ):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise ValueError(f"监控轮询间隔 {name} 必须是正整数")

    @classmethod
    def from_mapping(cls, raw: Mapping[str, Any] | None) -> "MonitoringThresholds":
        """从 camelCase/snake_case 低敏配置创建阈值对象。

        只读取白名单字段；未知字段不会被转发给客户端或模型。这样既兼容
        Java 控制面 JSON，又避免把任意用户配置误当成执行参数。
        """

        if raw is None:
            return cls()
        if not isinstance(raw, Mapping):
            raise ValueError("monitoring thresholds 必须是结构化对象")

        def number(name: str, default: float) -> float:
            """读取有限数值阈值；未配置时使用经过验证的产品默认值。"""

            value = _first(raw, name, _camel(name))
            if value is None:
                return default
            parsed = _finite_number(value)
            if parsed is None:
                raise ValueError(f"监控阈值 {name} 不是有限数字")
            return parsed

        def positive_int(name: str, default: int) -> int:
            """读取严格正整数轮询间隔，拒绝布尔值、负数和非整数字符串。"""

            value = _first(raw, name, _camel(name))
            if value is None:
                return default
            if isinstance(value, bool):
                raise ValueError(f"监控轮询间隔 {name} 不是正整数")
            try:
                parsed = int(value)
            except (TypeError, ValueError) as exc:
                raise ValueError(f"监控轮询间隔 {name} 不是正整数") from exc
            if parsed <= 0 or str(value).strip() not in {str(parsed), f"{parsed}.0"}:
                raise ValueError(f"监控轮询间隔 {name} 不是正整数")
            return parsed

        return cls(
            queue_timeout_seconds=number("queue_timeout_seconds", cls.queue_timeout_seconds),
            heartbeat_timeout_seconds=number("heartbeat_timeout_seconds", cls.heartbeat_timeout_seconds),
            throughput_drop_ratio=number("throughput_drop_ratio", cls.throughput_drop_ratio),
            failure_rate_threshold=number("failure_rate_threshold", cls.failure_rate_threshold),
            cdc_lag_threshold_seconds=number("cdc_lag_threshold_seconds", cls.cdc_lag_threshold_seconds),
            schedule_miss_grace_seconds=number("schedule_miss_grace_seconds", cls.schedule_miss_grace_seconds),
            queued_poll_seconds=positive_int("queued_poll_seconds", cls.queued_poll_seconds),
            running_poll_seconds=positive_int("running_poll_seconds", cls.running_poll_seconds),
            scheduled_poll_seconds=positive_int("scheduled_poll_seconds", cls.scheduled_poll_seconds),
            periodic_poll_seconds=positive_int("periodic_poll_seconds", cls.periodic_poll_seconds),
            realtime_poll_seconds=positive_int("realtime_poll_seconds", cls.realtime_poll_seconds),
        )


@dataclass(frozen=True)
class MonitoringModelInput:
    """传给总结模型的最小、已脱敏输入。

    模型看得到事实摘要和异常摘要，但没有 SQL、凭据、行数据或工具实参。
    ``allowed_tool_names`` 只是告知模型本轮能力，模型不能据此执行工具。
    """

    objective: str
    # 监控摘要模型必须沿用本轮委派范围，不能因复用 Monitor 单例而继承上一租户的上下文。
    audit_scope: SpecialistAuditScope
    task_id: str
    task_kind: TaskKind
    facts: Mapping[str, Any]
    anomalies: tuple[Mapping[str, Any], ...]
    allowed_tool_names: tuple[str, ...]
    max_output_tokens: int

    def __post_init__(self) -> None:
        """冻结一层输入容器，防止模型适配器修改事实快照。"""

        if not isinstance(self.audit_scope, SpecialistAuditScope):
            raise TypeError("MONITOR 模型输入必须携带 SpecialistAuditScope")
        object.__setattr__(self, "facts", MappingProxyType(dict(self.facts)))
        object.__setattr__(self, "anomalies", tuple(dict(item) for item in self.anomalies))
        object.__setattr__(self, "allowed_tool_names", tuple(self.allowed_tool_names))


@dataclass(frozen=True)
class MonitoringModelOutput:
    """总结模型允许返回的窄合同。

    没有 ``status``、``progress`` 或 ``health`` 字段，故模型在类型层面就不
    能成为事实来源。建议仍然只是文字建议，Agent 不会把它们转成工具调用。
    """

    public_summary: str = ""
    recommended_actions: tuple[str, ...] = ()
    invocation_summary: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        """冻结模型调用统计，避免原始响应对象泄漏到结果。"""

        object.__setattr__(self, "invocation_summary", MappingProxyType(dict(self.invocation_summary)))


class TaskMonitoringClient(Protocol):
    """确定性、只读的任务监控客户端协议。

    生产实现应在 ``get_snapshot`` 内部使用 tenant/project/actor/delegation
    做二次授权，并只返回聚合指标和低敏异常事实。它不得执行停止、重试、
    补数或重放；测试替身可以按查询返回预先构造的快照。
    """

    def get_snapshot(self, query: TaskMonitoringQuery) -> TaskMonitoringSnapshot | Mapping[str, Any]:
        """按受控范围读取一个任务快照，不产生业务副作用。"""


class MonitoringSummaryModel(Protocol):
    """监控摘要模型协议，明确限制为一次总结调用。"""

    def summarize(self, request: MonitoringModelInput) -> MonitoringModelOutput | Mapping[str, Any]:
        """根据事实摘要生成公开说明和建议，不得修改任务状态。"""


@dataclass(frozen=True)
class MonitoringAnomaly:
    """由结构化阈值或明确失败事实产生的异常记录。"""

    code: str
    severity: str
    public_summary: str
    actual: Any = None
    threshold: Any = None
    recommended_action: str = ""

    def to_summary(self) -> dict[str, Any]:
        """返回不包含原始异常堆栈、SQL 或行数据的低敏异常。"""

        return {
            "code": self.code,
            "severity": self.severity,
            "publicSummary": self.public_summary,
            "actual": self.actual,
            "threshold": self.threshold,
            "recommendedAction": self.recommended_action,
        }


@dataclass(frozen=True)
class _ValidatedMonitorRequest:
    """内部保存已完成权限、预算和输入校验的本轮参数。"""

    query: TaskMonitoringQuery
    thresholds: MonitoringThresholds


class _DeterministicSummaryModel:
    """未注入模型时的保守替身，仅生成固定格式的事实摘要。

    生产装配应传入真实的 ``MonitoringSummaryModel``。保留这个替身是为了让
    只读监控在没有 LLM Provider 的本地诊断和单元测试中仍然可运行；它不推断
    任何额外状态，也不计入模型调用次数。
    """

    def summarize(self, request: MonitoringModelInput) -> MonitoringModelOutput:
        """用已计算事实生成不带新事实的短说明。"""

        status = _text(request.facts.get("status")) or "UNKNOWN"
        return MonitoringModelOutput(public_summary=f"监控事实显示任务当前状态为 {status}。")


class MonitorSpecialistAgent:
    """真实 MONITOR_AGENT specialist。

    一次 ``execute`` 只处理一个只读快照，适合被 Durable scheduler 周期调用。
    执行顺序固定为：校验委派 -> 读取客户端快照 -> 归一化事实 -> 计算阈值
    异常 -> 调用一次总结模型 -> 组装低敏结果。任何权限、预算、客户端或
    模型异常都会返回 ``FAILED``，而不是用猜测值继续执行。

    该类没有停止、重试、补数、重放等方法，也不会接收写工具。即使输出中有
    ``recommendedActions``，它们也只是给主 Agent/控制面的建议。
    """

    AGENT_ID = "monitor-specialist-v1"
    _ROLE = AgentSessionRole.MONITOR_AGENT
    _AGENT_TOOL_ALLOWLIST = frozenset({MONITOR_TOOL_CODE})
    _SUPPORTED_STATUSES = frozenset(item.value for item in TaskLifecycleStatus)
    _TERMINAL_STATUSES = frozenset(
        {
            TaskLifecycleStatus.SUCCEEDED.value,
            TaskLifecycleStatus.FAILED.value,
            TaskLifecycleStatus.CANCELLED.value,
        }
    )
    _SAFE_MODEL_SUMMARY_KEYS = frozenset(
        {
            "actualModelName",
            "cachedPromptTokens",
            "completionTokens",
            "errorCode",
            "latencyMs",
            "modelName",
            "promptTokens",
            "providerInvoked",
            "providerName",
            "providerSucceeded",
            "responseSource",
            "totalTokens",
        }
    )
    _SECRET_KEY_PARTS = (
        "apikey",
        "authorization",
        "credential",
        "jdbc",
        "password",
        "privatekey",
        "refreshtoken",
        "secret",
        "token",
    )
    _FORBIDDEN_OUTPUT_KEYS = frozenset(
        {
            "prompt",
            "messages",
            "chainofthought",
            "thoughts",
            "reasoningtrace",
            "sql",
            "query",
            "rowsdata",
            "sampledata",
            "credentials",
        }
    )

    def __init__(
        self,
        monitoring_client: TaskMonitoringClient,
        model: MonitoringSummaryModel | None = None,
        *,
        agent_id: str = AGENT_ID,
        default_thresholds: MonitoringThresholds | None = None,
    ) -> None:
        """创建监控 Agent。

        Args:
            monitoring_client: 注入的确定性只读事实源，不能为空。
            model: 可选的摘要模型；缺省时使用固定格式替身，不影响事实计算。
            agent_id: 审计和事件中的稳定实例标识。
            default_thresholds: 没有随请求传入阈值时使用的结构化默认值。
        """

        if monitoring_client is None:
            raise ValueError("MONITOR_AGENT 必须注入 TaskMonitoringClient")
        if not _text(agent_id):
            raise ValueError("MONITOR_AGENT 必须提供非空 agent_id")
        if default_thresholds is not None and not isinstance(default_thresholds, MonitoringThresholds):
            raise ValueError("default_thresholds 必须是 MonitoringThresholds")
        self._monitoring_client = monitoring_client
        self._model = model or _DeterministicSummaryModel()
        self._model_injected = model is not None
        self._agent_id = str(agent_id).strip()
        self._default_thresholds = default_thresholds or MonitoringThresholds()

    @property
    def role(self) -> AgentSessionRole:
        """返回固定的 MONITOR_AGENT 角色，防止实例被伪装成其他角色。"""

        return self._ROLE

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None = None,
    ) -> SpecialistTurnResult:
        """执行一轮只读监控并返回 Durable 可保存的低敏结果。

        成功读取到 ``FAILED`` 或 ``CANCELLED`` 的任务仍然是一次成功的监控
        turn；只有 Agent 自身的权限、客户端、数据合同或模型错误才使用
        ``SpecialistTurnStatus.FAILED``。这样定期/CDC 的一次失败不会被误标为
        「等待输入」，也不会阻止后续轮询。
        """

        started_at = time.perf_counter()
        tool_activities: list[SpecialistToolActivity] = []
        try:
            validated = self._validate_request(request)
        except _MonitorFailure as failure:
            denied_activity = self._denied_tool_activity(failure.error_code)
            if denied_activity is not None:
                self._emit(
                    event_sink,
                    request,
                    action="SPECIALIST_TOOL_DENIED",
                    status="DENIED",
                    public_summary="本轮监控工具未通过委派或预算校验，未执行读取。",
                    attributes={"errorCode": failure.error_code},
                )
            return self._failed_result(
                request,
                started_at,
                event_sink,
                error_code=failure.error_code,
                public_summary=failure.public_summary,
                tool_activities=[denied_activity] if denied_activity is not None else None,
            )

        self._emit(
            event_sink,
            request,
            action="SPECIALIST_STARTED",
            status="RUNNING",
            public_summary="任务监控专业 Agent 已开始读取本轮低敏运行事实。",
        )
        self._emit(
            event_sink,
            request,
            action="TOOL_ALLOWLIST_CHECKED",
            status="SUCCEEDED",
            public_summary="本轮仅允许调用任务监控只读工具。",
            attributes={"visibleToolCount": 1, "maxToolCalls": request.budget.max_tool_calls},
        )

        tool_started_at = time.perf_counter()
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TOOL_STARTED",
            status="RUNNING",
            public_summary="正在读取任务状态、进度、心跳、checkpoint 和调度事实。",
        )
        try:
            raw_snapshot = self._read_snapshot(validated.query)
            snapshot = self._coerce_snapshot(raw_snapshot, validated.query)
            self._ensure_scope_matches(validated.query, snapshot)
        except Exception:
            duration_ms = self._duration_ms(tool_started_at)
            tool_activities.append(
                SpecialistToolActivity(
                    tool_name=MONITOR_TOOL_CODE,
                    status="FAILED",
                    public_summary="任务监控只读工具未能返回可验证的低敏快照。",
                    duration_ms=duration_ms,
                )
            )
            self._emit(
                event_sink,
                request,
                action="SPECIALIST_TOOL_COMPLETED",
                status="FAILED",
                public_summary="任务监控只读工具失败或返回了不完整事实。",
            )
            return self._failed_result(
                request,
                started_at,
                event_sink,
                error_code="MONITOR_TASK_CLIENT_FAILED",
                public_summary="任务监控事实读取失败，本轮未生成任何推测性状态。",
                tool_activities=tool_activities,
            )

        snapshot_duration_ms = self._duration_ms(tool_started_at)
        evidence_reference = self._evidence_reference(snapshot)
        tool_activities.append(
            SpecialistToolActivity(
                tool_name=MONITOR_TOOL_CODE,
                status="SUCCEEDED",
                public_summary="任务监控只读工具已返回可验证快照。",
                evidence_reference=evidence_reference,
                duration_ms=snapshot_duration_ms,
            )
        )
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TOOL_COMPLETED",
            status="SUCCEEDED",
            public_summary="任务监控只读工具已返回本轮低敏事实。",
            attributes={
                "taskStatus": snapshot.status.value,
                "taskKind": snapshot.task_kind.value,
                "evidenceAvailable": True,
            },
        )

        anomalies = self._detect_anomalies(snapshot, validated.thresholds)
        facts = self._build_fact_summary(snapshot, anomalies)
        try:
            # 监控 Agent 通常会被 Durable scheduler 反复调用，正是最容易发生“上一次租户范围
            # 泄漏到下一次轮询”的场景。因此每一轮都从 request 重新生成不可变 audit_scope。
            model_input = MonitoringModelInput(
                objective=self._safe_public_text(getattr(request, "objective", ""), 1_000),
                audit_scope=request.audit_scope,
                task_id=snapshot.task_id,
                task_kind=snapshot.task_kind,
                facts=facts,
                anomalies=tuple(anomaly.to_summary() for anomaly in anomalies),
                allowed_tool_names=(MONITOR_TOOL_CODE,),
                max_output_tokens=request.budget.max_output_tokens,
            )
        except (TypeError, ValueError):
            self._emit(
                event_sink,
                request,
                action="SPECIALIST_MODEL_COMPLETED",
                status="FAILED",
                public_summary="监控模型缺少当前 turn 的完整审计范围，已停止模型调用。",
                attributes={"errorCode": "MONITOR_MODEL_AUDIT_SCOPE_INVALID"},
            )
            return self._failed_result(
                request,
                started_at,
                event_sink,
                error_code="MONITOR_MODEL_AUDIT_SCOPE_INVALID",
                public_summary="监控总结模型缺少当前租户、项目、用户、会话或 turn 审计范围。",
                tool_activities=tool_activities,
            )

        self._emit(
            event_sink,
            request,
            action="SPECIALIST_MODEL_STARTED",
            status="RUNNING",
            public_summary="正在将已验证监控事实交给总结模型，不允许模型改变状态。",
            attributes={"maxOutputTokens": request.budget.max_output_tokens},
        )
        model_degraded = False
        try:
            model_output = self._summarize(model_input)
        except Exception:
            # The monitoring client has already returned a scope-checked deterministic snapshot.  A provider
            # outage must not erase that evidence or turn a healthy read-only observer into a failed business
            # step.  Fall back to the same fixed formatter used when no model is configured, while preserving
            # bounded invocation metadata so operators can see that the language summary degraded.  Raw provider
            # errors, prompts and responses are intentionally discarded.
            deterministic_output = _DeterministicSummaryModel().summarize(model_input)
            model_output = MonitoringModelOutput(
                public_summary=deterministic_output.public_summary,
                recommended_actions=deterministic_output.recommended_actions,
                invocation_summary={
                    "providerInvoked": self._model_injected,
                    "providerSucceeded": False,
                    "errorCode": "MONITOR_SUMMARY_MODEL_FAILED",
                    "responseSource": "deterministic_fallback",
                },
            )
            model_degraded = True

        model_summary = self._safe_model_invocation_summary(model_output)
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_MODEL_COMPLETED",
            status="DEGRADED" if model_degraded else "SUCCEEDED",
            public_summary=(
                "总结模型已完成说明；结构化状态仍以监控客户端事实为准。"
                if model_summary.get("providerSucceeded") is not False
                else "模型摘要已降级为确定性模板；结构化状态继续以监控客户端事实为准。"
            ),
            attributes={
                "modelInvoked": self._model_injected,
                "modelName": model_summary.get("modelName") or model_summary.get("actualModelName"),
            },
        )

        structured_output = self._build_structured_output(
            snapshot=snapshot,
            anomalies=anomalies,
            facts=facts,
            thresholds=validated.thresholds,
            model_output=model_output,
            evidence_reference=evidence_reference,
        )
        terminal = bool(structured_output["terminal"])
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TURN_COMPLETED",
            status=SpecialistTurnStatus.COMPLETED.value,
            public_summary="本轮任务监控已完成，后续轮询由 nextPollAfterSeconds 决定。",
            attributes={
                "taskStatus": snapshot.status.value,
                "health": structured_output["health"],
                "terminal": terminal,
                "anomalyCount": len(anomalies),
                "nextPollAfterSeconds": structured_output["nextPollAfterSeconds"],
            },
        )
        return SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=self._request_turn_id(request),
            status=SpecialistTurnStatus.COMPLETED,
            public_summary=self._public_summary(snapshot, anomalies),
            structured_output=structured_output,
            evidence_references=tuple(
                dict.fromkeys(
                    tuple(getattr(request, "evidence_references", ()) or ()) + (evidence_reference,)
                )
            ),
            tool_activities=tuple(tool_activities),
            model_invocation_summary=model_summary,
            duration_ms=self._duration_ms(started_at),
        )

    def _validate_request(self, request: SpecialistTurnRequest) -> _ValidatedMonitorRequest:
        """校验角色、租户/项目/用户委派、工具白名单和本轮预算。

        校验必须早于任何客户端或模型调用。特别是 project_id 不能像共享
        合同那样可选，因为监控查询若没有项目边界就可能跨项目泄漏状态。
        """

        if request is None or not isinstance(request, SpecialistTurnRequest):
            raise _MonitorFailure("MONITOR_REQUEST_INVALID", "监控 Agent 收到的 turn 请求不符合 specialist 合同。")
        if request.role != self.role:
            raise _MonitorFailure("MONITOR_AGENT_ROLE_MISMATCH", "MONITOR_AGENT 不能处理其他 Agent 角色的请求。")
        scope = request.scope
        required_scope = {
            "tenantId": getattr(scope, "tenant_id", None),
            "projectId": getattr(scope, "project_id", None),
            "actorId": getattr(scope, "actor_id", None),
            "delegationId": getattr(scope, "delegation_id", None),
        }
        missing_scope = tuple(name for name, value in required_scope.items() if not _text(value))
        if missing_scope:
            raise _MonitorFailure(
                "MONITOR_SCOPE_INVALID",
                f"监控 Agent 委派范围缺少必要主体: {', '.join(missing_scope)}。",
            )

        context = request.context_summary
        if not isinstance(context, Mapping):
            raise _MonitorFailure("MONITOR_CONTEXT_INVALID", "监控 Agent 只接受结构化低敏上下文。")
        for context_keys, scope_name in (
            (("tenantId", "tenant_id"), "tenant_id"),
            (("projectId", "project_id"), "project_id"),
            (("actorId", "actor_id"), "actor_id"),
            (("delegationId", "delegation_id"), "delegation_id"),
        ):
            context_value = _first(context, *context_keys)
            if context_value is not None and _text(context_value) != _text(getattr(scope, scope_name)):
                raise _MonitorFailure("MONITOR_SCOPE_MISMATCH", "监控上下文与委派主体不一致，已拒绝查询。")

        task_id = self._task_id_from_context(context)
        if not task_id:
            raise _MonitorFailure("MONITOR_TASK_ID_REQUIRED", "监控 Agent 必须收到明确的 taskId。")
        task_kind = self._task_kind_from_context(context)

        allowed_tools = tuple(getattr(scope, "allowed_tool_names", ()) or ())
        if MONITOR_TOOL_CODE not in allowed_tools or MONITOR_TOOL_CODE not in self._AGENT_TOOL_ALLOWLIST:
            raise _MonitorFailure(
                "MONITOR_TOOL_NOT_AUTHORIZED",
                "当前 delegation 未授权 MONITOR_AGENT 的任务监控只读工具。",
            )
        budget = request.budget
        if budget.max_tool_calls < 1:
            raise _MonitorFailure("MONITOR_TOOL_BUDGET_EXHAUSTED", "本轮没有可用的监控工具调用预算。")
        if budget.max_model_invocations < 1:
            raise _MonitorFailure("MONITOR_MODEL_BUDGET_EXHAUSTED", "本轮没有可用的总结模型调用预算。")
        if budget.max_output_tokens < 128:
            raise _MonitorFailure("MONITOR_OUTPUT_BUDGET_INVALID", "本轮模型输出预算不足以生成受控摘要。")

        raw_thresholds = _first(context, "thresholds", "monitoringThresholds", "monitoring_thresholds")
        try:
            thresholds = (
                self._default_thresholds
                if raw_thresholds is None
                else MonitoringThresholds.from_mapping(raw_thresholds)
            )
        except ValueError as exc:
            raise _MonitorFailure("MONITOR_THRESHOLDS_INVALID", "监控阈值配置无效，已拒绝本轮执行。") from exc

        query = TaskMonitoringQuery(
            tenant_id=str(scope.tenant_id).strip(),
            project_id=str(scope.project_id).strip(),
            actor_id=str(scope.actor_id).strip(),
            delegation_id=str(scope.delegation_id).strip(),
            task_id=task_id,
            task_kind=task_kind,
            application_id=_text(getattr(scope, "application_id", None)),
            run_id=_text(_first(context, "runId", "run_id")) or _text(request.run_id),
        )
        return _ValidatedMonitorRequest(query=query, thresholds=thresholds)

    def _read_snapshot(self, query: TaskMonitoringQuery) -> Any:
        """调用注入的只读客户端，并兼容常见的等价方法名。

        正式协议是 ``get_snapshot``。其余方法名只为迁移旧的轻量测试替身，
        不会改变「一次 turn 只允许一次只读工具调用」的规则。
        """

        for method_name in ("get_snapshot", "get_task_snapshot", "read_snapshot", "read"):
            method = getattr(self._monitoring_client, method_name, None)
            if callable(method):
                return method(query)
        raise TypeError("TaskMonitoringClient 缺少 get_snapshot 只读方法")

    @classmethod
    def _coerce_snapshot(cls, raw: Any, query: TaskMonitoringQuery) -> TaskMonitoringSnapshot:
        """把客户端对象或 Mapping 归一化为严格的六状态事实快照。

        这里是防止「模型发明进度」的关键边界：所有数字都来自客户端，所有
        进度百分比都由这里根据真实行数计算，未知字段保持 None。
        """

        if isinstance(raw, TaskMonitoringSnapshot):
            source = cls._snapshot_to_mapping(raw)
        elif isinstance(raw, Mapping):
            source = raw
        else:
            raise TypeError("监控客户端必须返回 TaskMonitoringSnapshot 或 Mapping")

        task_id = _text(_first(source, "taskId", "task_id", "id")) or query.task_id
        if task_id != query.task_id:
            raise ValueError("监控快照 taskId 与查询 taskId 不一致")
        raw_status = _first(source, "status", "lifecycleStatus", "lifecycle_status", "state")
        status = cls._normalize_status(raw_status)
        if status is None:
            raise ValueError("监控快照缺少受支持的任务状态")
        raw_kind = _first(source, "taskType", "taskKind", "task_kind", "monitoringType", "mode", "syncMode")
        kind = cls._normalize_task_kind(raw_kind)
        if kind is None:
            kind = query.task_kind
        if kind is None and status == TaskLifecycleStatus.SCHEDULED:
            kind = TaskKind.PERIODIC
        if kind is None:
            kind = TaskKind.LONG_RUNNING

        progress = _first_mapping(source, "progress", "metrics", "statistics")
        rows_total = cls._optional_count(
            cls._first_from(source, progress, "rowsTotal", "rows_total", "totalRows", "total")
        )
        rows_processed = cls._optional_count(
            cls._first_from(source, progress, "rowsProcessed", "rows_processed", "processedRows", "completedRows")
        )
        success_count = cls._optional_count(
            cls._first_from(source, progress, "successCount", "success_count", "successfulRows", "rowsSucceeded")
        )
        failure_count = cls._optional_count(
            cls._first_from(source, progress, "failureCount", "failure_count", "failedCount", "rowsFailed")
        )
        if rows_total is not None and rows_processed is not None and rows_processed > rows_total:
            raise ValueError("监控快照 rowsProcessed 不能大于 rowsTotal")

        schedule = _first_mapping(source, "schedule", "scheduleFacts", "schedule_facts")
        exception = _exception_mapping(_first(source, "exception", "lastException", "error"))
        last_run_status = cls._normalize_status(
            cls._first_from(source, schedule, "lastRunStatus", "last_run_status")
        )
        raw_checkpoint = _first(source, "checkpoint", "checkpointFacts", "checkpoint_facts")
        if isinstance(raw_checkpoint, Mapping):
            checkpoint = raw_checkpoint
        elif raw_checkpoint is not None and _safe_scalar(raw_checkpoint):
            checkpoint = {"checkpointId": raw_checkpoint}
        else:
            checkpoint_id = _first(source, "checkpointId", "checkpoint_id")
            checkpoint = {"checkpointId": checkpoint_id} if checkpoint_id is not None else None

        return TaskMonitoringSnapshot(
            task_id=task_id,
            status=status,
            task_kind=kind,
            phase=cls._safe_public_text(_first(source, "phase", "stage", "currentPhase"), 120) or None,
            rows_total=rows_total,
            rows_processed=rows_processed,
            success_count=success_count,
            failure_count=failure_count,
            throughput_rows_per_second=cls._optional_number(
                cls._first_from(
                    source,
                    progress,
                    "throughputRowsPerSecond",
                    "throughput_rows_per_second",
                    "throughput",
                    "rowsPerSecond",
                )
            ),
            baseline_throughput_rows_per_second=cls._optional_number(
                cls._first_from(
                    source,
                    progress,
                    "baselineThroughputRowsPerSecond",
                    "baseline_throughput_rows_per_second",
                    "baselineThroughput",
                )
            ),
            latency_ms=cls._optional_number(
                cls._first_from(source, progress, "latencyMs", "latency_ms", "averageLatencyMs", "p95LatencyMs")
            ),
            heartbeat_age_seconds=cls._optional_number(
                _first(source, "heartbeatAgeSeconds", "heartbeat_age_seconds", "lastHeartbeatAgeSeconds", "last_heartbeat_age_seconds")
            ),
            heartbeat_present=cls._optional_bool(_first(source, "heartbeatPresent", "heartbeat_present")),
            heartbeat_at=cls._safe_public_text(
                _first(source, "heartbeatAt", "heartbeat_at", "lastHeartbeatAt", "last_heartbeat_at"),
                120,
            )
            or None,
            queue_wait_seconds=cls._optional_number(
                _first(source, "queueWaitSeconds", "queue_wait_seconds", "queuedForSeconds", "queued_for_seconds")
            ),
            cdc_lag_seconds=cls._optional_number(
                _first(source, "cdcLagSeconds", "cdc_lag_seconds", "lagSeconds", "sourceLagSeconds")
            ),
            checkpoint=checkpoint,
            schedule=schedule or None,
            exception=exception,
            captured_at=cls._safe_public_text(
                _first(source, "capturedAt", "captured_at", "observedAt", "observed_at"),
                120,
            )
            or None,
            last_run_status=last_run_status,
            last_run_at=cls._safe_public_text(cls._first_from(source, schedule, "lastRunAt", "last_run_at"), 120)
            or None,
            next_run_at=cls._safe_public_text(cls._first_from(source, schedule, "nextRunAt", "next_run_at"), 120)
            or None,
            schedule_missed=cls._optional_bool(
                cls._first_from(
                    source,
                    schedule,
                    "scheduleMissed",
                    "schedule_missed",
                    "missedSchedule",
                    "missed_schedule",
                    "missed",
                )
            ),
            missed_schedule_count=cls._optional_count(
                cls._first_from(source, schedule, "missedScheduleCount", "missed_schedule_count", "missedCount")
            ),
            last_success_at=cls._safe_public_text(
                cls._first_from(source, schedule, "lastSuccessAt", "last_success_at"),
                120,
            )
            or None,
            tenant_id=_text(_first(source, "tenantId", "tenant_id")),
            project_id=_text(_first(source, "projectId", "project_id")),
            actor_id=_text(_first(source, "actorId", "actor_id")),
            delegation_id=_text(_first(source, "delegationId", "delegation_id")),
        )

    @staticmethod
    def _snapshot_to_mapping(snapshot: TaskMonitoringSnapshot) -> dict[str, Any]:
        """把公开 dataclass 转成归一化器使用的 camelCase 映射。"""

        return {
            "taskId": snapshot.task_id,
            "status": snapshot.status,
            "taskKind": snapshot.task_kind,
            "phase": snapshot.phase,
            "rowsTotal": snapshot.rows_total,
            "rowsProcessed": snapshot.rows_processed,
            "successCount": snapshot.success_count,
            "failureCount": snapshot.failure_count,
            "throughputRowsPerSecond": snapshot.throughput_rows_per_second,
            "baselineThroughputRowsPerSecond": snapshot.baseline_throughput_rows_per_second,
            "latencyMs": snapshot.latency_ms,
            "heartbeatAgeSeconds": snapshot.heartbeat_age_seconds,
            "heartbeatPresent": snapshot.heartbeat_present,
            "heartbeatAt": snapshot.heartbeat_at,
            "queueWaitSeconds": snapshot.queue_wait_seconds,
            "cdcLagSeconds": snapshot.cdc_lag_seconds,
            "checkpoint": snapshot.checkpoint,
            "schedule": snapshot.schedule,
            "exception": snapshot.exception,
            "capturedAt": snapshot.captured_at,
            "lastRunStatus": snapshot.last_run_status,
            "lastRunAt": snapshot.last_run_at,
            "nextRunAt": snapshot.next_run_at,
            "scheduleMissed": snapshot.schedule_missed,
            "missedScheduleCount": snapshot.missed_schedule_count,
            "lastSuccessAt": snapshot.last_success_at,
            "tenantId": snapshot.tenant_id,
            "projectId": snapshot.project_id,
            "actorId": snapshot.actor_id,
            "delegationId": snapshot.delegation_id,
        }

    @staticmethod
    def _first_from(primary: Mapping[str, Any], secondary: Mapping[str, Any], *keys: str) -> Any:
        """先查顶层事实，再查 progress/schedule 子对象，避免混淆键和值。"""

        value = _first(primary, *keys)
        return value if value is not None else _first(secondary, *keys)

    @classmethod
    def _ensure_scope_matches(cls, query: TaskMonitoringQuery, snapshot: TaskMonitoringSnapshot) -> None:
        """验证客户端没有把其他租户、项目或用户的事实带回本轮。"""

        for expected, actual in (
            (query.tenant_id, snapshot.tenant_id),
            (query.project_id, snapshot.project_id),
            (query.actor_id, snapshot.actor_id),
            (query.delegation_id, snapshot.delegation_id),
        ):
            if actual is not None and _text(actual) != expected:
                raise ValueError("监控快照返回了超出委派范围的事实")

    @classmethod
    def _detect_anomalies(
        cls,
        snapshot: TaskMonitoringSnapshot,
        thresholds: MonitoringThresholds,
    ) -> tuple[MonitoringAnomaly, ...]:
        """只用确定性事实和阈值发现六类运维异常。

        这里不触发任何控制面动作。每条记录都包含建议文字，供主 Agent 或
        人工运维决定是否另行发起受控的停止、重试、补数或重放流程。
        """

        anomalies: list[MonitoringAnomaly] = []
        status = snapshot.status.value
        if status == TaskLifecycleStatus.QUEUED.value and snapshot.queue_wait_seconds is not None:
            if snapshot.queue_wait_seconds > thresholds.queue_timeout_seconds:
                anomalies.append(
                    MonitoringAnomaly(
                        code="QUEUE_TIMEOUT",
                        severity="ERROR",
                        public_summary="任务在队列中的等待时间超过结构化阈值。",
                        actual=snapshot.queue_wait_seconds,
                        threshold=thresholds.queue_timeout_seconds,
                        recommended_action="建议检查调度队列、worker 容量和任务优先级。",
                    )
                )

        if snapshot.heartbeat_present is False or (
            snapshot.heartbeat_age_seconds is not None
            and snapshot.heartbeat_age_seconds > thresholds.heartbeat_timeout_seconds
        ):
            anomalies.append(
                MonitoringAnomaly(
                    code="HEARTBEAT_LOST",
                    severity="ERROR",
                    public_summary="任务心跳缺失或超过心跳超时阈值。",
                    actual=snapshot.heartbeat_age_seconds,
                    threshold=thresholds.heartbeat_timeout_seconds,
                    recommended_action="建议检查 worker 健康、网络连通性和心跳消费链路。",
                )
            )

        if (
            snapshot.throughput_rows_per_second is not None
            and snapshot.baseline_throughput_rows_per_second is not None
            and snapshot.baseline_throughput_rows_per_second > 0
            and snapshot.throughput_rows_per_second
            < snapshot.baseline_throughput_rows_per_second * (1 - thresholds.throughput_drop_ratio)
        ):
            anomalies.append(
                MonitoringAnomaly(
                    code="THROUGHPUT_DROP",
                    severity="WARNING",
                    public_summary="当前吞吐低于基线允许比例。",
                    actual=snapshot.throughput_rows_per_second,
                    threshold=round(
                        snapshot.baseline_throughput_rows_per_second * (1 - thresholds.throughput_drop_ratio),
                        6,
                    ),
                    recommended_action="建议检查源端、目标端、队列背压和 worker 资源使用。",
                )
            )

        total_outcomes = (snapshot.success_count or 0) + (snapshot.failure_count or 0)
        failure_rate = (
            snapshot.failure_count / total_outcomes
            if snapshot.failure_count is not None and total_outcomes > 0
            else None
        )
        if failure_rate is not None and failure_rate > thresholds.failure_rate_threshold:
            anomalies.append(
                MonitoringAnomaly(
                    code="FAILURE_RATE_HIGH",
                    severity="ERROR",
                    public_summary="任务失败率超过结构化阈值。",
                    actual=round(failure_rate, 6),
                    threshold=thresholds.failure_rate_threshold,
                    recommended_action="建议检查异常分类和失败阶段，再由控制面评估后续处置。",
                )
            )

        if snapshot.task_kind == TaskKind.CDC_REALTIME and snapshot.cdc_lag_seconds is not None:
            if snapshot.cdc_lag_seconds > thresholds.cdc_lag_threshold_seconds:
                anomalies.append(
                    MonitoringAnomaly(
                        code="CDC_LAG_HIGH",
                        severity="ERROR",
                        public_summary="CDC 延迟超过实时链路阈值。",
                        actual=snapshot.cdc_lag_seconds,
                        threshold=thresholds.cdc_lag_threshold_seconds,
                        recommended_action="建议检查 source connector、消息积压、sink 吞吐和 checkpoint 推进。",
                    )
                )

        schedule_missed = snapshot.schedule_missed is True or bool(snapshot.missed_schedule_count)
        missed_by_time = cls._schedule_missed_by_time(snapshot, thresholds.schedule_miss_grace_seconds)
        if missed_by_time is not None:
            schedule_missed = schedule_missed or missed_by_time > 0
        if snapshot.task_kind == TaskKind.PERIODIC and schedule_missed:
            anomalies.append(
                MonitoringAnomaly(
                    code="SCHEDULE_MISSED",
                    severity="ERROR",
                    public_summary="定期任务存在调度错过事实。",
                    actual=(
                        snapshot.missed_schedule_count
                        if snapshot.missed_schedule_count is not None
                        else (missed_by_time if missed_by_time is not None else True)
                    ),
                    threshold=thresholds.schedule_miss_grace_seconds,
                    recommended_action="建议检查调度器时钟、触发器、队列容量和任务日历。",
                )
            )

        if status == TaskLifecycleStatus.FAILED.value:
            anomalies.append(
                MonitoringAnomaly(
                    code="TASK_FAILED",
                    severity="ERROR",
                    public_summary="监控快照记录任务失败状态。",
                    actual=_safe_exception_code(snapshot.exception),
                    recommended_action="建议查看低敏异常事实并由控制面决定是否需要人工处置。",
                )
            )
        elif snapshot.task_kind == TaskKind.PERIODIC and snapshot.last_run_status == TaskLifecycleStatus.FAILED:
            anomalies.append(
                MonitoringAnomaly(
                    code="RECENT_RUN_FAILED",
                    severity="ERROR",
                    public_summary="定期任务最近一次运行失败，但调度生命周期仍可继续。",
                    actual=_safe_exception_code(snapshot.exception),
                    recommended_action="建议查看最近一次运行异常，并确认下一次调度是否按计划到达。",
                )
            )
        return tuple(anomalies)

    @staticmethod
    def _schedule_missed_by_time(
        snapshot: TaskMonitoringSnapshot,
        grace_seconds: float,
    ) -> float | None:
        """用快照自身的观测时间判断 nextRunAt 是否已超过宽限期。

        只有客户端同时提供可解析的 ``capturedAt`` 和 ``nextRunAt`` 才进行
        计算；缺少任一时间就保持未知，避免 Agent 使用本机时钟制造调度事实。
        """

        captured = _timestamp_seconds(snapshot.captured_at)
        next_run = _timestamp_seconds(snapshot.next_run_at)
        if captured is None or next_run is None:
            return None
        return max(0.0, captured - next_run - float(grace_seconds))

    @classmethod
    def _build_fact_summary(
        cls,
        snapshot: TaskMonitoringSnapshot,
        anomalies: tuple[MonitoringAnomaly, ...],
    ) -> dict[str, Any]:
        """构造模型和持久化结果共用的低敏事实摘要。"""

        return {
            "taskId": snapshot.task_id,
            "taskKind": snapshot.task_kind.value,
            "status": snapshot.status.value,
            "phase": snapshot.phase,
            "rows": {
                "total": snapshot.rows_total,
                "processed": snapshot.rows_processed,
                "success": snapshot.success_count,
                "failure": snapshot.failure_count,
            },
            "throughputRowsPerSecond": snapshot.throughput_rows_per_second,
            "baselineThroughputRowsPerSecond": snapshot.baseline_throughput_rows_per_second,
            "latencyMs": snapshot.latency_ms,
            "heartbeat": {
                "present": snapshot.heartbeat_present,
                "ageSeconds": snapshot.heartbeat_age_seconds,
                "at": snapshot.heartbeat_at,
            },
            "queueWaitSeconds": snapshot.queue_wait_seconds,
            "cdcLagSeconds": snapshot.cdc_lag_seconds,
            "checkpoint": cls._safe_checkpoint(snapshot.checkpoint),
            "schedule": cls._safe_schedule(snapshot),
            "exception": cls._safe_exception(snapshot.exception),
            "capturedAt": snapshot.captured_at,
            "anomalyCodes": tuple(item.code for item in anomalies),
        }

    @classmethod
    def _build_structured_output(
        cls,
        *,
        snapshot: TaskMonitoringSnapshot,
        anomalies: tuple[MonitoringAnomaly, ...],
        facts: Mapping[str, Any],
        thresholds: MonitoringThresholds,
        model_output: MonitoringModelOutput,
        evidence_reference: str,
    ) -> dict[str, Any]:
        """组装主 Agent 使用的事实结果，模型字段只作为附加说明。

        ``status``、``progress``、``health``、``terminal``、``anomalies`` 和
        ``nextPollAfterSeconds`` 均在本方法内从快照计算；即便模型返回同名
        字段，也不会被合并进来。
        """

        terminal = cls._terminal_for(snapshot)
        health = cls._health_for(snapshot, anomalies)
        progress_percent = cls._progress_percent(snapshot)
        progress = {
            "phase": snapshot.phase,
            "percent": progress_percent,
            "rowsTotal": snapshot.rows_total,
            "rowsProcessed": snapshot.rows_processed,
            "successCount": snapshot.success_count,
            "failureCount": snapshot.failure_count,
            "throughputRowsPerSecond": snapshot.throughput_rows_per_second,
            "latencyMs": snapshot.latency_ms,
            "checkpoint": cls._safe_checkpoint(snapshot.checkpoint),
        }
        schedule = cls._safe_schedule(snapshot)
        output: dict[str, Any] = {
            "schemaVersion": "monitor.specialist.v1",
            "taskId": snapshot.task_id,
            "taskType": snapshot.task_kind.value,
            "status": snapshot.status.value,
            "taskStatus": snapshot.status.value,
            "phase": snapshot.phase,
            "nextPollAfterSeconds": cls._next_poll_after_seconds(snapshot, thresholds, terminal),
            "terminal": terminal,
            "health": health.value,
            "progressPercent": progress_percent,
            "progress": progress,
            "anomalies": tuple(item.to_summary() for item in anomalies),
            "evidence": cls._evidence(snapshot, evidence_reference),
            "evidenceReference": evidence_reference,
            "schedule": schedule,
            "recentRun": cls._recent_run(snapshot),
            "nextRun": {"scheduledAt": snapshot.next_run_at} if snapshot.next_run_at else None,
            "longTermHealth": cls._long_term_health(snapshot, health),
            "exception": cls._safe_exception(snapshot.exception),
            "recommendedActions": cls._recommended_actions(anomalies, model_output.recommended_actions),
            "modelSummary": cls._safe_public_text(model_output.public_summary, 600) or None,
            "facts": dict(facts),
            "readOnly": True,
            "sideEffectsPerformed": False,
            "payloadPolicy": "LOW_SENSITIVE_MONITOR_RESULT_ONLY",
        }
        return output

    @staticmethod
    def _terminal_for(snapshot: TaskMonitoringSnapshot) -> bool:
        """按任务 kind 计算终止语义，避免长期任务的单次失败被结束。"""

        if snapshot.status == TaskLifecycleStatus.CANCELLED:
            return True
        if snapshot.task_kind == TaskKind.LONG_RUNNING:
            return snapshot.status.value in {
                TaskLifecycleStatus.SUCCEEDED.value,
                TaskLifecycleStatus.FAILED.value,
            }
        return False

    @staticmethod
    def _health_for(
        snapshot: TaskMonitoringSnapshot,
        anomalies: tuple[MonitoringAnomaly, ...],
    ) -> MonitorHealth:
        """从任务状态和异常严重级别计算健康度，而不是让模型选择颜色。"""

        if snapshot.status == TaskLifecycleStatus.CANCELLED:
            return MonitorHealth.STOPPED
        if snapshot.task_kind == TaskKind.LONG_RUNNING and snapshot.status == TaskLifecycleStatus.FAILED:
            return MonitorHealth.UNHEALTHY
        if snapshot.task_kind in {TaskKind.PERIODIC, TaskKind.CDC_REALTIME} and snapshot.status == TaskLifecycleStatus.FAILED:
            return MonitorHealth.DEGRADED
        if any(item.severity == "ERROR" for item in anomalies):
            return MonitorHealth.DEGRADED
        if snapshot.status in {TaskLifecycleStatus.RUNNING, TaskLifecycleStatus.QUEUED, TaskLifecycleStatus.SCHEDULED}:
            return MonitorHealth.HEALTHY
        if snapshot.status == TaskLifecycleStatus.SUCCEEDED:
            return MonitorHealth.HEALTHY
        return MonitorHealth.UNKNOWN

    @staticmethod
    def _progress_percent(snapshot: TaskMonitoringSnapshot) -> float | None:
        """只用总行数和已处理行数计算百分比，无法计算时保留 None。"""

        if snapshot.rows_total is None or snapshot.rows_processed is None:
            return None
        if snapshot.rows_total == 0:
            return 100.0 if snapshot.status == TaskLifecycleStatus.SUCCEEDED else None
        return round(snapshot.rows_processed / snapshot.rows_total * 100, 2)

    @staticmethod
    def _next_poll_after_seconds(
        snapshot: TaskMonitoringSnapshot,
        thresholds: MonitoringThresholds,
        terminal: bool,
    ) -> int:
        """为 Durable 调度器选择下一次轮询间隔，终止任务返回 0。"""

        if terminal:
            return 0
        if snapshot.task_kind == TaskKind.CDC_REALTIME:
            return thresholds.realtime_poll_seconds
        if snapshot.task_kind == TaskKind.PERIODIC:
            return thresholds.periodic_poll_seconds
        if snapshot.status == TaskLifecycleStatus.QUEUED:
            return thresholds.queued_poll_seconds
        if snapshot.status == TaskLifecycleStatus.SCHEDULED:
            return thresholds.scheduled_poll_seconds
        return thresholds.running_poll_seconds

    @classmethod
    def _safe_schedule(cls, snapshot: TaskMonitoringSnapshot) -> dict[str, Any]:
        """只保留调度可视化需要的字段，过滤任意嵌套凭据或执行参数。"""

        source = snapshot.schedule or {}
        allowed = (
            "enabled",
            "intervalSeconds",
            "interval_seconds",
            "cronDescription",
            "lastRunAt",
            "last_run_at",
            "lastRunStatus",
            "last_run_status",
            "nextRunAt",
            "next_run_at",
            "missed",
            "missedCount",
            "lastSuccessAt",
        )
        result: dict[str, Any] = {}
        for key in allowed:
            if key in source and _safe_scalar(source[key]):
                value = source[key]
                result[_camel(key)] = cls._safe_public_text(value, 180) if isinstance(value, str) else value
        if snapshot.last_run_at:
            result["lastRunAt"] = snapshot.last_run_at
        if snapshot.last_run_status:
            result["lastRunStatus"] = snapshot.last_run_status.value
        if snapshot.next_run_at:
            result["nextRunAt"] = snapshot.next_run_at
        if snapshot.schedule_missed is not None:
            result["missed"] = snapshot.schedule_missed
        if snapshot.missed_schedule_count is not None:
            result["missedCount"] = snapshot.missed_schedule_count
        if snapshot.last_success_at:
            result["lastSuccessAt"] = snapshot.last_success_at
        return result

    @classmethod
    def _recent_run(cls, snapshot: TaskMonitoringSnapshot) -> dict[str, Any] | None:
        """表达定期任务最近一次运行，不把失败转成等待输入。"""

        status = snapshot.last_run_status
        if status is None and snapshot.task_kind == TaskKind.PERIODIC and snapshot.status in {
            TaskLifecycleStatus.RUNNING,
            TaskLifecycleStatus.SUCCEEDED,
            TaskLifecycleStatus.FAILED,
            TaskLifecycleStatus.CANCELLED,
        }:
            status = snapshot.status
        if status is None and not snapshot.last_run_at:
            return None
        return {
            "status": status.value if isinstance(status, TaskLifecycleStatus) else status,
            "runAt": snapshot.last_run_at,
            "lastSuccessAt": snapshot.last_success_at,
        }

    @classmethod
    def _long_term_health(
        cls,
        snapshot: TaskMonitoringSnapshot,
        health: MonitorHealth,
    ) -> dict[str, Any] | None:
        """为 CDC 和定期任务保留长期健康视图。"""

        if snapshot.task_kind not in {TaskKind.PERIODIC, TaskKind.CDC_REALTIME}:
            return None
        return {
            "health": health.value,
            "lastHeartbeatAgeSeconds": snapshot.heartbeat_age_seconds,
            "cdcLagSeconds": snapshot.cdc_lag_seconds,
            "lastCheckpoint": cls._safe_checkpoint(snapshot.checkpoint),
            "lastSuccessAt": snapshot.last_success_at,
        }

    @classmethod
    def _evidence(cls, snapshot: TaskMonitoringSnapshot, reference: str) -> tuple[dict[str, Any], ...]:
        """生成低敏证据清单，只包含聚合值和引用，不包含行数据。"""

        return (
            {
                "type": "TASK_STATUS",
                "reference": reference,
                "status": snapshot.status.value,
                "phase": snapshot.phase,
                "capturedAt": snapshot.captured_at,
            },
            {
                "type": "TASK_METRICS",
                "reference": reference,
                "rowsTotal": snapshot.rows_total,
                "rowsProcessed": snapshot.rows_processed,
                "successCount": snapshot.success_count,
                "failureCount": snapshot.failure_count,
                "throughputRowsPerSecond": snapshot.throughput_rows_per_second,
                "latencyMs": snapshot.latency_ms,
            },
            {
                "type": "TASK_HEARTBEAT_CHECKPOINT",
                "reference": reference,
                "heartbeatAgeSeconds": snapshot.heartbeat_age_seconds,
                "checkpoint": cls._safe_checkpoint(snapshot.checkpoint),
            },
        )

    @classmethod
    def _recommended_actions(
        cls,
        anomalies: tuple[MonitoringAnomaly, ...],
        model_actions: tuple[str, ...],
    ) -> tuple[str, ...]:
        """合并建议而不执行动作，并过滤模型可能返回的复杂对象。"""

        actions: list[str] = [item.recommended_action for item in anomalies if item.recommended_action]
        for action in model_actions:
            safe_action = cls._safe_public_text(action, 300)
            if safe_action and safe_action not in actions:
                actions.append(safe_action)
        return tuple(actions[:12])

    def _summarize(self, model_input: MonitoringModelInput) -> MonitoringModelOutput:
        """调用一次注入模型，并把响应收敛到窄的总结合同。"""

        method = getattr(self._model, "summarize", None)
        if not callable(method):
            if callable(self._model):
                raw_output = self._model(model_input)
            else:
                raise TypeError("MonitoringSummaryModel 缺少 summarize 方法")
        else:
            raw_output = method(model_input)
        return self._coerce_model_output(raw_output)

    @classmethod
    def _coerce_model_output(cls, raw_output: Any) -> MonitoringModelOutput:
        """只接受摘要、建议和调用统计，丢弃模型的状态性或隐藏字段。"""

        if isinstance(raw_output, MonitoringModelOutput):
            return MonitoringModelOutput(
                public_summary=cls._safe_public_text(raw_output.public_summary, 600),
                recommended_actions=cls._safe_actions(raw_output.recommended_actions),
                invocation_summary=raw_output.invocation_summary,
            )
        if isinstance(raw_output, str):
            return MonitoringModelOutput(public_summary=cls._safe_public_text(raw_output, 600))
        if not isinstance(raw_output, Mapping):
            raise TypeError("MonitoringSummaryModel 必须返回 MonitoringModelOutput 或 Mapping")
        actions = _first(raw_output, "recommendedActions", "recommended_actions", "suggestions")
        if not isinstance(actions, (list, tuple)):
            actions = ()
        invocation = _first(raw_output, "invocationSummary", "invocation_summary", "modelInvocationSummary")
        return MonitoringModelOutput(
            public_summary=cls._safe_public_text(
                _first(raw_output, "publicSummary", "public_summary", "summary"),
                600,
            ),
            recommended_actions=cls._safe_actions(actions),
            invocation_summary=invocation if isinstance(invocation, Mapping) else {},
        )

    def _safe_model_invocation_summary(self, output: MonitoringModelOutput) -> dict[str, Any]:
        """输出模型调用元数据，不输出 prompt、原始响应或隐藏思维链。"""

        result: dict[str, Any] = {
            "invoked": self._model_injected,
            "invocationCount": 1 if self._model_injected else 0,
            "rawModelOutputStored": False,
        }
        for key, value in output.invocation_summary.items():
            if key in self._SAFE_MODEL_SUMMARY_KEYS and _safe_scalar(value):
                result[key] = value
        if not self._model_summary_has_provider_fields(result):
            result["responseSource"] = "injected_summary_model" if self._model_injected else "deterministic_fallback"
        return result

    @staticmethod
    def _model_summary_has_provider_fields(summary: Mapping[str, Any]) -> bool:
        """判断模型统计中是否已经包含一个可公开的来源描述。"""

        return any(key in summary for key in ("modelName", "actualModelName", "providerName", "responseSource"))

    @classmethod
    def _safe_checkpoint(cls, checkpoint: Mapping[str, Any] | None) -> dict[str, Any] | None:
        """仅保留 checkpoint 的低敏定位和聚合字段。"""

        if not isinstance(checkpoint, Mapping):
            return None
        allowed_names = {
            "checkpointid",
            "checkpointversion",
            "partitioncount",
            "offset",
            "watermark",
            "updatedat",
            "position",
            "available",
        }
        result: dict[str, Any] = {}
        for key, value in checkpoint.items():
            normalized = cls._normalized_key(key)
            if normalized in allowed_names and _safe_scalar(value):
                result[_camel(str(key))] = cls._safe_public_text(value, 180) if isinstance(value, str) else value
        return result

    @classmethod
    def _safe_exception(cls, exception: Mapping[str, Any] | None) -> dict[str, Any] | None:
        """保留异常分类和计数，拒绝堆栈、凭据、SQL 和原始行内容。"""

        if not isinstance(exception, Mapping):
            return None
        result: dict[str, Any] = {}
        for source_key, target_key in (
            ("code", "code"),
            ("errorCode", "code"),
            ("category", "category"),
            ("phase", "phase"),
            ("count", "count"),
            ("retryable", "retryable"),
            ("observedAt", "observedAt"),
            ("lastObservedAt", "observedAt"),
            ("message", "messageSummary"),
            ("summary", "messageSummary"),
        ):
            if source_key not in exception or target_key in result:
                continue
            value = exception[source_key]
            if target_key == "messageSummary":
                value = cls._safe_public_text(value, 240)
            elif target_key == "count":
                try:
                    value = cls._optional_count(value)
                except ValueError:
                    continue
            elif isinstance(value, str):
                value = cls._safe_public_text(value, 180)
            elif not _safe_scalar(value):
                continue
            if value is not None and value != "":
                result[target_key] = value
        return result or None

    @classmethod
    def _public_summary(
        cls,
        snapshot: TaskMonitoringSnapshot,
        anomalies: tuple[MonitoringAnomaly, ...],
    ) -> str:
        """生成不依赖模型判断的公开 turn 摘要。"""

        suffix = f"，发现 {len(anomalies)} 项异常" if anomalies else "，未发现阈值异常"
        return (
            f"{snapshot.task_kind.value} 任务当前状态为 {snapshot.status.value}"
            f"，阶段为 {snapshot.phase or '未提供'}{suffix}；结构化进度和健康度均以只读监控事实为准。"
        )

    def _failed_result(
        self,
        request: Any,
        started_at: float,
        event_sink: SpecialistEventSink | None,
        *,
        error_code: str,
        public_summary: str,
        tool_activities: list[SpecialistToolActivity] | None = None,
        model_invocation_summary: Mapping[str, Any] | None = None,
    ) -> SpecialistTurnResult:
        """集中构造低敏失败结果，并保证失败分支不泄漏原始异常。"""

        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TURN_FAILED",
            status=SpecialistTurnStatus.FAILED.value,
            public_summary=public_summary,
            attributes={"errorCode": error_code},
        )
        return SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=self._request_turn_id(request),
            status=SpecialistTurnStatus.FAILED,
            public_summary=public_summary,
            structured_output={
                "schemaVersion": "monitor.specialist.v1",
                "nextPollAfterSeconds": 0,
                "terminal": False,
                "health": MonitorHealth.UNKNOWN.value,
                "progress": {},
                "anomalies": (),
                "evidence": (),
                "readOnly": True,
                "sideEffectsPerformed": False,
                "payloadPolicy": "LOW_SENSITIVE_MONITOR_RESULT_ONLY",
            },
            evidence_references=tuple(getattr(request, "evidence_references", ()) or ()),
            tool_activities=tuple(tool_activities or ()),
            model_invocation_summary=dict(model_invocation_summary or {"invoked": False, "rawModelOutputStored": False}),
            error_code=error_code,
            duration_ms=self._duration_ms(started_at),
        )

    @staticmethod
    def _denied_tool_activity(error_code: str) -> SpecialistToolActivity | None:
        """为工具白名单/预算拒绝创建低敏 DENIED 活动记录。"""

        if error_code not in {"MONITOR_TOOL_NOT_AUTHORIZED", "MONITOR_TOOL_BUDGET_EXHAUSTED"}:
            return None
        return SpecialistToolActivity(
            tool_name=MONITOR_TOOL_CODE,
            status="DENIED",
            public_summary="任务监控只读工具未获本轮授权或没有剩余预算。",
        )

    def _emit(
        self,
        event_sink: SpecialistEventSink | None,
        request: Any,
        *,
        action: str,
        status: str,
        public_summary: str,
        attributes: Mapping[str, Any] | None = None,
    ) -> None:
        """发出低敏实时事件；观察链路失败不改变监控业务结果。"""

        if event_sink is None:
            return
        safe_attributes = {
            str(key): value
            for key, value in (attributes or {}).items()
            if self._safe_event_value(key, value)
        }
        event = {
            "eventType": "SPECIALIST_AGENT_ACTION",
            "agentId": self._agent_id,
            "agentRole": self.role.value,
            "turnId": self._request_turn_id(request),
            "runId": _text(getattr(request, "run_id", None)),
            "action": action,
            "status": status,
            "publicSummary": self._safe_public_text(public_summary, 300),
            "attributes": safe_attributes,
            "payloadPolicy": "LOW_SENSITIVE_SPECIALIST_EVENT_ONLY",
        }
        try:
            event_sink(event)
        except Exception:
            return

    @classmethod
    def _safe_event_value(cls, key: Any, value: Any) -> bool:
        """判断事件属性是否为安全标量且不是敏感键。"""

        normalized = cls._normalized_key(key)
        return normalized not in cls._FORBIDDEN_OUTPUT_KEYS and not any(
            part in normalized for part in cls._SECRET_KEY_PARTS
        ) and _safe_scalar(value)

    @staticmethod
    def _request_turn_id(request: Any) -> str:
        """在正常和坏请求分支都生成稳定的审计 turnId。"""

        return _text(getattr(request, "turn_id", None)) or "invalid-monitor-turn"

    @staticmethod
    def _task_id_from_context(context: Mapping[str, Any]) -> str | None:
        """只接受上下文明确给出的任务标识，不从目标文本猜测。"""

        nested = _first_mapping(context, "task", "taskSummary", "task_summary")
        return _text(
            _first(
                context,
                "taskId",
                "task_id",
                "monitorTaskId",
                "monitor_task_id",
                nested.get("taskId"),
                nested.get("task_id"),
            )
        )

    @classmethod
    def _task_kind_from_context(cls, context: Mapping[str, Any]) -> TaskKind | None:
        """从结构化模式字段解析任务 kind，未知显式值直接拒绝。"""

        nested = _first_mapping(context, "task", "taskSummary", "task_summary")
        raw = _first(
            context,
            "taskType",
            "taskKind",
            "task_kind",
            "monitoringType",
            "monitoring_type",
            "syncMode",
            "mode",
            nested.get("taskType"),
            nested.get("taskKind"),
            nested.get("syncMode"),
        )
        if raw is None:
            return None
        normalized = cls._normalize_task_kind(raw)
        if normalized is None:
            raise _MonitorFailure("MONITOR_TASK_KIND_INVALID", "监控任务类型不受支持。")
        return normalized

    @staticmethod
    def _normalize_status(value: Any) -> TaskLifecycleStatus | None:
        """把控制面别名收敛为六种公开状态，未知值返回 None。"""

        if isinstance(value, TaskLifecycleStatus):
            return value
        normalized = _text(value)
        if not normalized:
            return None
        normalized = normalized.upper().replace("-", "_").replace(" ", "_")
        aliases = {
            "SUCCESS": "SUCCEEDED",
            "COMPLETED": "SUCCEEDED",
            "CANCELED": "CANCELLED",
            "WAITING": "QUEUED",
            "PENDING": "QUEUED",
        }
        normalized = aliases.get(normalized, normalized)
        try:
            return TaskLifecycleStatus(normalized)
        except ValueError:
            return None

    @staticmethod
    def _normalize_task_kind(value: Any) -> TaskKind | None:
        """把 FULL、SCHEDULED、CDC 等业务模式映射到三种监控语义。"""

        if isinstance(value, TaskKind):
            return value
        normalized = _text(value)
        if not normalized:
            return None
        normalized = normalized.upper().replace("-", "_").replace(" ", "_")
        if normalized in {
            "PERIODIC",
            "SCHEDULED",
            "SCHEDULED_BATCH",
            "SCHEDULED_FULL",
            "CRON",
            "RECURRING",
        }:
            return TaskKind.PERIODIC
        if normalized in {
            "CDC",
            "REALTIME",
            "REAL_TIME",
            "CDC_STREAMING",
            "CDC_REALTIME",
            "STREAMING",
        }:
            return TaskKind.CDC_REALTIME
        if normalized in {
            "LONG_RUNNING",
            "LONG_RUNNING_TASK",
            "LONG_TASK",
            "ONE_TIME",
            "ONE_TIME_MIGRATION",
            "FULL",
            "BATCH",
            "BACKFILL",
            "REPLAY",
            "OFFLINE_IMPORT",
            "OFFLINE_EXPORT",
        }:
            return TaskKind.LONG_RUNNING
        try:
            return TaskKind(normalized)
        except ValueError:
            return None

    @staticmethod
    def _optional_count(value: Any) -> int | None:
        """解析非负整数计数，拒绝 bool、小数和负数。"""

        if value is None:
            return None
        if isinstance(value, bool):
            raise ValueError("监控计数不能是 bool")
        try:
            parsed = int(value)
            numeric = float(value)
        except (TypeError, ValueError, OverflowError) as exc:
            raise ValueError("监控计数不是整数") from exc
        if not math.isfinite(numeric) or numeric != parsed or parsed < 0:
            raise ValueError("监控计数必须是非负整数")
        return parsed

    @staticmethod
    def _optional_number(value: Any) -> float | None:
        """解析非负有限监控数值，避免异常值污染阈值计算。"""

        if value is None:
            return None
        parsed = _finite_number(value)
        if parsed is None or parsed < 0:
            raise ValueError("监控数值必须是非负有限数字")
        return parsed

    @staticmethod
    def _optional_bool(value: Any) -> bool | None:
        """只接受明确的布尔心跳/调度事实，不把任意字符串当真。"""

        if value is None:
            return None
        if isinstance(value, bool):
            return value
        if isinstance(value, str) and value.strip().lower() in {"true", "false"}:
            return value.strip().lower() == "true"
        raise ValueError("监控布尔事实不是明确的 true/false")

    @classmethod
    def _safe_actions(cls, actions: Any) -> tuple[str, ...]:
        """限制模型建议的长度和类型，不把建议转换成控制面命令。"""

        if not isinstance(actions, (list, tuple)):
            return ()
        result: list[str] = []
        for action in actions:
            safe_action = cls._safe_public_text(action, 300)
            if safe_action and safe_action not in result:
                result.append(safe_action)
        return tuple(result[:8])

    @classmethod
    def _safe_public_text(cls, value: Any, limit: int) -> str:
        """脱敏并截断公开文字，尽量阻断凭据、SQL 和原始异常泄漏。"""

        text = _text(value)
        if not text:
            return ""
        text = re.sub(
            r"(?i)(?:api[_-]?key|authorization|bearer|credential|password|secret|token|jdbc:[^\s,;]+|(?:postgresql|mysql|redis)://[^\s,;]+)",
            "[REDACTED]",
            text,
        )
        # 只有出现 SQL 结构关键词时才整体隐藏，避免把普通中文摘要误删。
        if re.search(r"(?is)\b(select|insert|update|delete|drop|alter|truncate|create|merge)\b.{0,120}\b(from|into|table|where|set)\b", text):
            text = "[REDACTED_SENSITIVE_TEXT]"
        return text[:limit]

    @classmethod
    def _evidence_reference(cls, snapshot: TaskMonitoringSnapshot) -> str:
        """生成可追踪但不包含快照正文的稳定证据引用。"""

        captured = cls._safe_public_text(snapshot.captured_at, 80) or "current"
        return f"monitor:{snapshot.task_id}:snapshot:{captured}"

    @staticmethod
    def _duration_ms(started_at: float) -> int:
        """把 monotonic 计时转换为非负毫秒，供活动审计使用。"""

        return max(0, int((time.perf_counter() - started_at) * 1_000))

    @staticmethod
    def _normalized_key(value: Any) -> str:
        """规范化键名，便于安全白名单同时兼容 camelCase 和 snake_case。"""

        return re.sub(r"[^a-z0-9]", "", str(value or "").lower())


class _MonitorFailure(Exception):
    """内部可预期失败，保存稳定错误码而不保存原始异常文字。"""

    def __init__(self, error_code: str, public_summary: str) -> None:
        """保存稳定错误码和可公开摘要，避免原始异常正文进入 Specialist 事实。"""

        super().__init__(error_code)
        self.error_code = error_code
        self.public_summary = public_summary


def _text(value: Any) -> str | None:
    """把标量规范化为去首尾空格的文本，空值统一为 None。"""

    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


def _first(mapping: Mapping[str, Any], *keys: Any) -> Any:
    """按顺序返回第一个存在且非 None 的字段，兼容旧新命名。"""

    for key in keys:
        if key is not None and key in mapping and mapping[key] is not None:
            return mapping[key]
    return None


def _first_mapping(mapping: Mapping[str, Any], *keys: Any) -> dict[str, Any]:
    """返回第一个结构化子对象的浅拷贝，拒绝把列表/正文当成指标。"""

    for key in keys:
        value = mapping.get(key)
        if isinstance(value, Mapping):
            return dict(value)
    return {}


def _exception_mapping(value: Any) -> dict[str, Any] | None:
    """将异常对象收敛为单个映射，避免原始堆栈进入 Agent 结果。"""

    if isinstance(value, Mapping):
        return dict(value)
    if isinstance(value, str) and value.strip():
        return {"message": value}
    if isinstance(value, (list, tuple)):
        for item in reversed(value):
            if isinstance(item, Mapping):
                return dict(item)
            if isinstance(item, str) and item.strip():
                return {"message": item}
    return None


def _safe_exception_code(exception: Mapping[str, Any] | None) -> str | None:
    """只返回异常编码，不返回异常正文。"""

    if not isinstance(exception, Mapping):
        return None
    return _text(_first(exception, "code", "errorCode", "category"))


def _safe_scalar(value: Any) -> bool:
    """判断值是否适合进入低敏事件或摘要。"""

    return value is None or isinstance(value, (str, int, float, bool))


def _finite_number(value: Any) -> float | None:
    """解析有限数字，拒绝 bool、NaN 和无穷大。"""

    if isinstance(value, bool):
        return None
    try:
        parsed = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return parsed if math.isfinite(parsed) else None


def _timestamp_seconds(value: Any) -> float | None:
    """解析 ISO-8601 或 Unix 秒时间戳，供同一快照内的时间比较使用。"""

    if value is None:
        return None
    numeric = _finite_number(value)
    if numeric is not None and (not isinstance(value, str) or re.fullmatch(r"[+-]?\d+(?:\.\d+)?", value.strip())):
        return numeric
    text = _text(value)
    if not text:
        return None
    try:
        normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
        parsed = datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.timestamp()
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def _camel(value: str) -> str:
    """将少量 snake_case 配置键转换成输出使用的 camelCase。"""

    parts = value.split("_")
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])


# 允许渐进接入的调用方使用简短类名，但实例仍然只有一个真实实现。
MonitorAgent = MonitorSpecialistAgent
MonitoringTaskType = TaskKind


__all__ = [
    "MONITOR_TOOL_CODE",
    "TASK_MONITORING_TOOL_CODE",
    "MonitorHealth",
    "MonitorAgent",
    "MonitorSpecialistAgent",
    "MonitoringAnomaly",
    "MonitoringModelInput",
    "MonitoringModelOutput",
    "MonitoringSummaryModel",
    "MonitoringThresholds",
    "TaskKind",
    "TaskLifecycleStatus",
    "TaskMonitoringClient",
    "TaskMonitoringKind",
    "TaskMonitoringQuery",
    "TaskMonitoringSnapshot",
    "TaskMonitoringStatus",
    "MonitoringTaskType",
]
