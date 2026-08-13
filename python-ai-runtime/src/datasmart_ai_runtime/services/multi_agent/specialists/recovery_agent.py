"""真实 RECOVERY_AGENT：基于受控事实生成、审批并交接恢复方案。

本模块实现的是一个可以独立运行的故障恢复专业 Agent，而不是一个拥有数据库账号的
“自动修复脚本”。它严格遵循 :mod:`specialist_contracts` 的 turn 合同，并把恢复链路拆成
四个可审计阶段：

1. 通过注入的 ``FailureDiagnosticClient`` 读取由控制面裁剪过的运行日志和失败事实；
2. 只消费主编排器传入的案例证据以及 KNOWLEDGE_AGENT 的知识摘要；
3. 通过注入的 ``RecoveryPlanningModel`` 生成建议，再由 Python 侧确定性分类和校验；
4. 高风险动作只作为不可信建议返回给主 Agent，由 bridge 映射到平台注册工具，
   再由 Java agent-runtime 负责审批、outbox、worker receipt 和最终执行。

模型没有数据库连接、任务服务客户端、审批创建权限或执行权限。这个边界是通过对象依赖和
确定性校验共同实现的，不依赖提示词自觉。输出和事件也只保留低敏摘要：不回传凭据、原始
SQL、样本行、原始日志或隐藏思维链。
"""

from __future__ import annotations

import hashlib
import json
import re
import time
from collections.abc import Iterable, Mapping
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


# 诊断工具是只读事实入口。Recovery 不再拥有通用执行器入口；恢复动作会在主 Agent bridge
# 中按平台注册表映射为具体 ToolPlan，避免 Python 侧出现第二套执行权限边界。
FAILURE_DIAGNOSTIC_TOOL_CODE = "recovery.failure.diagnose"
# 这是由 Java 控制、受审批约束的 recovery handoff 的稳定合同名称。Python 仅提出
# 恢复动作建议；控制面仍负责授权、执行和 receipt 持久化。
CONTROLLED_RECOVERY_TOOL_CODE = "recovery.controlled.execute"
RECOVERY_DIAGNOSTIC_TOOL_CODE = FAILURE_DIAGNOSTIC_TOOL_CODE


class RecoveryActionClass(str, Enum):
    """恢复动作的确定性风险分层。

    ``READ_ONLY_DIAGNOSTIC`` 只查看事实，``LOW_RISK_DRAFT`` 只产生不落库的草案，
    ``HIGH_RISK_SIDE_EFFECT`` 可能改变数据库、脏数据、任务状态或触发重跑。模型可以提出
    高风险建议，但不能把建议伪装成已执行结果。
    """

    READ_ONLY_DIAGNOSTIC = "READ_ONLY_DIAGNOSTIC"
    LOW_RISK_DRAFT = "LOW_RISK_DRAFT"
    HIGH_RISK_SIDE_EFFECT = "HIGH_RISK_SIDE_EFFECT"


# 这些别名让外部组合根可以用“风险”或“分类”理解同一个稳定枚举，而不会复制第三套状态。
RecoveryActionRisk = RecoveryActionClass
RecoveryActionCategory = RecoveryActionClass


@dataclass(frozen=True)
class FailureDiagnosticRequest:
    """传给失败诊断客户端的最小、低敏事实查询合同。

    诊断客户端可以把 ``run_id`` 映射到受控日志索引、worker receipt 或失败反馈，但不能把
    数据库凭据、完整日志正文和 SQL 直接塞回专业 Agent 的事件链路。请求中的上下文已经由
    Agent 做过字段裁剪，便于测试替身和真实控制面使用同一份合同。
    """

    turn_id: str
    session_id: str
    run_id: str
    delegation_id: str
    tenant_id: str
    project_id: str | None
    actor_id: str
    objective: str
    context_summary: Mapping[str, Any] = field(default_factory=dict)
    evidence_references: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        """冻结诊断查询上下文，防止客户端改变主编排器传入的审计事实。"""

        object.__setattr__(self, "context_summary", MappingProxyType(dict(self.context_summary)))
        object.__setattr__(self, "evidence_references", _unique_text(self.evidence_references))


@dataclass(frozen=True)
class FailureDiagnosticResult:
    """失败诊断客户端返回的受控事实快照。

    ``facts`` 只应包含控制面允许恢复 Agent 消费的结构化事实，例如错误码、任务名摘要、失败
    阶段和重试次数。``log_references`` 是日志定位标识，不是日志正文；``log_summary`` 只能
    表达计数或状态。即使测试替身误返了原始日志，Agent 也不会把它放进公开结果。
    """

    failure_code: str | None = None
    failure_reason: str = ""
    facts: Mapping[str, Any] = field(default_factory=dict)
    log_references: tuple[str, ...] = ()
    evidence_references: tuple[str, ...] = ()
    log_summary: Mapping[str, Any] = field(default_factory=dict)
    public_summary: str = ""
    evidence_records: tuple[Mapping[str, Any], ...] = ()

    def __post_init__(self) -> None:
        """把客户端返回的映射和引用转换成稳定的不可变快照。"""

        object.__setattr__(self, "facts", MappingProxyType(dict(self.facts)))
        object.__setattr__(self, "log_summary", MappingProxyType(dict(self.log_summary)))
        object.__setattr__(self, "log_references", _unique_text(self.log_references))
        object.__setattr__(self, "evidence_references", _unique_text(self.evidence_references))
        object.__setattr__(
            self,
            "evidence_records",
            tuple(_sanitize_mapping(item) for item in self.evidence_records if isinstance(item, Mapping)),
        )

    @property
    def failure_facts(self) -> Mapping[str, Any]:
        """提供语义更明确的别名，方便模型适配器读取失败事实。"""

        return self.facts


class FailureDiagnosticClient(Protocol):
    """由 Runtime 注入的确定性失败事实读取器。

    该协议只负责查询受控日志索引和失败事实，不负责生成修复计划，也不执行任何写操作。
    生产实现通常由 Java 控制面或受保护的 runtime client 提供；单元测试可以用固定结果替身。
    """

    def diagnose(self, request: FailureDiagnosticRequest) -> FailureDiagnosticResult:
        """按 run/session 范围读取裁剪后的失败事实，禁止返回凭据、原始 SQL 和样本行。"""


@dataclass(frozen=True)
class RecoveryPlanningModelInput:
    """传给恢复规划模型的低敏数据合同。

    ``diagnostic_facts``、``case_evidence`` 和 ``knowledge_summary`` 都来自外部事实源；模型不能
    通过 objective 自己补造案例。输入只包含摘要和引用，原始日志、文档正文、SQL、样本行和认证
    信息会在进入模型前被裁剪。
    """

    objective: str
    # 只允许使用当前 turn 生成的范围；模型适配器不会从 objective 或静态 provider 取身份。
    audit_scope: SpecialistAuditScope
    diagnostic_facts: Mapping[str, Any]
    case_evidence: Mapping[str, Any]
    knowledge_summary: Mapping[str, Any]
    evidence_references: tuple[str, ...]
    allowed_tool_names: tuple[str, ...]
    max_output_tokens: int
    failure_code: str | None = None
    failure_reason: str = ""
    # 此摘要仅来自已完成的 MONITOR_AGENT 依赖。对于独立单元/领域调用它是可选的，
    # 但已调度 MONITOR_AGENT 的 recovery wave 必须消费它。
    monitoring_summary: Mapping[str, Any] = field(default_factory=dict)
    evidence_audit: Mapping[str, Any] = field(default_factory=dict)
    # 这些由 coordinator 拥有的事实告知模型当前 turn 是初始检索决策，还是检索后的有界后续决策。
    # 它们不携带执行授权。
    decision_phase: str = "DIAGNOSE"
    knowledge_search_completed: bool = False
    retrieval_already_performed: bool = False
    remaining_knowledge_searches: int = 1
    must_choose_single_governed_action: bool = False

    def __post_init__(self) -> None:
        """冻结模型输入，避免 Provider 适配器在规划过程中篡改事实快照。"""

        if not isinstance(self.audit_scope, SpecialistAuditScope):
            raise TypeError("RECOVERY 模型输入必须携带 SpecialistAuditScope")
        object.__setattr__(self, "diagnostic_facts", MappingProxyType(dict(self.diagnostic_facts)))
        object.__setattr__(self, "case_evidence", MappingProxyType(dict(self.case_evidence)))
        object.__setattr__(self, "knowledge_summary", MappingProxyType(dict(self.knowledge_summary)))
        object.__setattr__(self, "monitoring_summary", MappingProxyType(dict(self.monitoring_summary)))
        object.__setattr__(self, "evidence_audit", MappingProxyType(dict(self.evidence_audit)))
        object.__setattr__(self, "evidence_references", _unique_text(self.evidence_references))
        object.__setattr__(self, "allowed_tool_names", _unique_text(self.allowed_tool_names))
        object.__setattr__(self, "failure_code", _bounded_text(self.failure_code, 160).strip() or None)
        object.__setattr__(self, "failure_reason", _bounded_text(self.failure_reason, 1_000))
        phase = _bounded_text(self.decision_phase, 48).strip().upper() or "DIAGNOSE"
        if phase not in {"DIAGNOSE", "DECIDE_AFTER_SEARCH", "DECIDE_AFTER_INVESTIGATION"}:
            raise ValueError("decision_phase must be a supported Recovery phase")
        object.__setattr__(self, "decision_phase", phase)
        for field_name in ("knowledge_search_completed", "retrieval_already_performed", "must_choose_single_governed_action"):
            if not isinstance(getattr(self, field_name), bool):
                raise TypeError(f"{field_name} must be boolean")
        if isinstance(self.remaining_knowledge_searches, bool):
            raise TypeError("remaining_knowledge_searches must be an integer")
        remaining = int(self.remaining_knowledge_searches)
        if not 0 <= remaining <= 1:
            raise ValueError("remaining_knowledge_searches must be 0 or 1")
        object.__setattr__(self, "remaining_knowledge_searches", remaining)

    @property
    def failure_facts(self) -> Mapping[str, Any]:
        """返回 ``diagnostic_facts`` 的兼容别名，避免适配器误解数据来源。"""

        return self.diagnostic_facts


@dataclass(frozen=True)
class RecoveryPlanningModelOutput:
    """恢复模型返回的建议合同，而不是可执行命令合同。

    ``actions`` 中的每一项只是待审核建议。模型可以写出 ``RENAME_TASK``、``RERUN_TASK`` 或
    ``ALTER_TABLE`` 这类高风险意图，但 Agent 会把它们转为等待审批状态；模型返回 ``execute``、
    ``approval``、``sql`` 等越权字段则会被确定性拒绝。
    """

    actions: tuple[Any, ...] = ()
    public_summary: str = ""
    failure_reason: str = ""
    next_step: str = ""
    invocation_summary: Mapping[str, Any] = field(default_factory=dict)
    requested_tool_names: tuple[str, ...] = ()
    requested_actions: tuple[str, ...] = ()
    rag_decision: str = "AUTO"
    rag_reason: str = ""
    confidence: float | None = None
    retrieval_decision: str | None = None
    retrieval_strategy: str = "AUTO"

    def __post_init__(self) -> None:
        """冻结模型返回的建议和调用统计，不保留可变的 Provider 对象。"""

        object.__setattr__(self, "actions", _as_tuple(self.actions))
        object.__setattr__(self, "invocation_summary", MappingProxyType(dict(self.invocation_summary)))
        object.__setattr__(self, "requested_tool_names", _unique_text(self.requested_tool_names))
        object.__setattr__(self, "requested_actions", _unique_text(self.requested_actions))
        decision = _bounded_text(self.retrieval_decision or self.rag_decision, 24).strip().upper() or "AUTO"
        if decision not in {"AUTO", "SEARCH", "SKIP"}:
            raise ValueError("retrieval_decision must be AUTO, SEARCH or SKIP")
        object.__setattr__(self, "rag_decision", decision)
        object.__setattr__(self, "retrieval_decision", decision)
        strategy = _bounded_text(self.retrieval_strategy, 48).strip().upper() or "AUTO"
        if strategy not in {"AUTO", "STRUCTURED_DIAGNOSTIC", "RAG", "EXACT_SEARCH", "WIKI", "GIT_HISTORY"}:
            strategy = "AUTO"
        object.__setattr__(self, "retrieval_strategy", strategy)
        object.__setattr__(self, "rag_reason", _sanitize_text(self.rag_reason))
        if self.confidence is not None:
            confidence = float(self.confidence)
            if not 0.0 <= confidence <= 1.0:
                raise ValueError("confidence must be between 0 and 1")
            object.__setattr__(self, "confidence", confidence)

    @property
    def repair_actions(self) -> tuple[Any, ...]:
        """返回更面向业务的动作别名，保持模型适配器命名自由。"""

        return self.actions


class RecoveryPlanningModel(Protocol):
    """由 Runtime 注入的恢复方案规划模型。

    模型只接收低敏事实并返回建议；它不能创建审批、调用数据库、修改任务、发布任务或触发
    执行。所有风险分类、审批绑定和工具交接都由 ``RecoverySpecialistAgent`` 确定性完成。
    """

    def plan(self, request: RecoveryPlanningModelInput) -> RecoveryPlanningModelOutput:
        """根据真实诊断事实和外部证据生成恢复建议，不得在方法内部执行副作用。"""


@dataclass(frozen=True)
class RecoveryAction:
    """经过确定性规范化后的恢复动作。

    ``arguments`` 只留在 Agent 内部交给受控执行器，公开结果只返回参数字段名。``original_values``
    和 ``proposed_values`` 是面向用户确认的低敏变更对照，特别用于同名任务恢复时说明原值、建议值
    和原因。``classification`` 不采信模型声明，而由 Agent 根据动作类型重新计算。
    """

    action_type: str
    tool_name: str | None = None
    arguments: Mapping[str, Any] = field(default_factory=dict)
    reason: str = ""
    original_values: Mapping[str, Any] = field(default_factory=dict)
    proposed_values: Mapping[str, Any] = field(default_factory=dict)
    action_id: str = ""
    evidence_references: tuple[str, ...] = ()
    classification: RecoveryActionClass = RecoveryActionClass.READ_ONLY_DIAGNOSTIC

    def __post_init__(self) -> None:
        """冻结动作参数和变更对照，确保审批指纹计算期间动作不会漂移。"""

        normalized_type = _bounded_text(self.action_type, 160).strip() or "UNKNOWN_RECOVERY_ACTION"
        object.__setattr__(self, "action_type", normalized_type)
        object.__setattr__(self, "tool_name", _bounded_text(self.tool_name, 160).strip() or None)
        object.__setattr__(self, "arguments", MappingProxyType(dict(self.arguments)))
        object.__setattr__(self, "original_values", MappingProxyType(dict(self.original_values)))
        object.__setattr__(self, "proposed_values", MappingProxyType(dict(self.proposed_values)))
        object.__setattr__(self, "action_id", _bounded_text(self.action_id, 120).strip())
        object.__setattr__(self, "reason", _sanitize_text(self.reason))
        object.__setattr__(self, "evidence_references", _unique_text(self.evidence_references))


class _RecoveryModelOverreach(ValueError):
    """模型输出包含直接副作用、审批或敏感正文时使用的内部异常。"""


class RecoverySpecialistAgent:
    """真实 RECOVERY_AGENT 的受控实现。

    类职责是“诊断事实 + 可选外部证据 -> 可审核恢复建议”。是否需要 RAG 由模型显式输出
    ``ragDecision``；当模型选择 SEARCH 时，本 Agent 只生成 KNOWLEDGE_AGENT 的只读检索建议，
    不把任何修复动作混入同一批。审批持久化和动作执行仍属于主 Agent bridge 与 Java 控制面。

    重要的业务状态语义如下：

    * ``COMPLETED``：诊断或草案已经完成，高风险动作已形成待治理 ToolPlan 建议；
    * ``WAITING_FOR_INPUT``：需要案例证据、用户许可、绑定批准事实或控制面工具授权；
    * ``FAILED``：诊断客户端、模型或受控工具发生技术故障，或者模型越过了合同边界。
    """

    _ROLE = AgentSessionRole.RECOVERY_AGENT
    AGENT_ID = "recovery-specialist-v1"
    _EVENT_PAYLOAD_POLICY = "LOW_SENSITIVE_RECOVERY_SPECIALIST_EVENT_ONLY"

    _DIAGNOSTIC_TOOL_NAMES = frozenset(
        {
            FAILURE_DIAGNOSTIC_TOOL_CODE,
            "recovery.failure.diagnostic",
            "recovery.diagnostic.read",
            "recovery.runtime.logs.read",
            "runtime.logs.read",
        }
    )
    _SAFE_INVOCATION_KEYS = frozenset(
        {
            "cachedPromptTokens",
            "completionTokens",
            "deterministicPreviewFallbackCount",
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
    _MODEL_FAILURE_REASON_CODES = frozenset(
        {
            "MODEL_TIMEOUT",
            "MODEL_PROVIDER_ERROR",
            "MODEL_RESPONSE_INVALID_JSON",
            "MODEL_RESPONSE_CONTRACT_VIOLATION",
            "MODEL_RESULT_UNAVAILABLE",
            "MODEL_ADAPTER_ERROR",
        }
    )
    _MODEL_FAILURE_SOURCES = frozenset(
        {
            "MODEL_PROVIDER_TRANSPORT",
            "MODEL_PROVIDER_RESPONSE",
            "MODEL_RESPONSE_PARSER",
            "MODEL_RESPONSE_CONTRACT",
            "MODEL_RESULT_READER",
            "SPECIALIST_MODEL_ADAPTER",
        }
    )
    _FORBIDDEN_MODEL_KEYS = frozenset(
        {
            "approval",
            "approvaldecision",
            "approvalfact",
            "approvalrequest",
            "approved",
            "authorizationdecision",
            "credential",
            "credentials",
            "delete",
            "executed",
            "execute",
            "execution",
            "executionresult",
            "outbox",
            "persist",
            "publish",
            "query",
            "run",
            "sample",
            "samplerows",
            "secret",
            "sql",
            "statement",
            "token",
            "toolcall",
            "toolcalls",
            "toolresult",
        }
    )
    _SECRET_KEY_PARTS = (
        "apikey",
        "authorization",
        "accesskey",
        "connectionstring",
        "credential",
        "jdbc",
        "password",
        "privatekey",
        "refreshtoken",
        "secret",
        "token",
    )
    _SENSITIVE_CONTENT_KEY_PARTS = (
        "sql",
        "query",
        "statement",
        "logbody",
        "logentry",
        "rawlog",
        "samplerow",
        "stacktrace",
        "thought",
        "reasoning",
        "chain",
        "prompt",
        "chainofthought",
        "reasoningtrace",
    )
    _HIGH_RISK_ACTION_PARTS = (
        "ALTER",
        "BACKFILL",
        "CLEAN",
        "DELETE",
        "DROP",
        "EXECUTE",
        "INSERT",
        "MODIFY",
        "PUBLISH",
        "PURGE",
        "REPAIR_DATABASE",
        "RETRY",
        "RERUN",
        "RESUME",
        "RESTART",
        "TRUNCATE",
        "UPDATE",
        "WRITE",
    )
    _READ_ONLY_ACTION_PARTS = (
        "CHECK",
        "DIAGNOSE",
        "INSPECT",
        "KNOWLEDGE",
        "LOG",
        "READ",
        "SEARCH",
        "TRACE",
        "VERIFY",
    )
    _DRAFT_ACTION_PARTS = ("DRAFT", "PLAN", "PREVIEW", "PROPOSE", "RECOMMEND")

    def __init__(
        self,
        diagnostic_client: FailureDiagnosticClient | None = None,
        model: RecoveryPlanningModel | None = None,
        *,
        failure_diagnostic_client: FailureDiagnosticClient | None = None,
        planning_model: RecoveryPlanningModel | None = None,
        recovery_planning_model: RecoveryPlanningModel | None = None,
        agent_id: str = AGENT_ID,
    ) -> None:
        """创建恢复专业 Agent，并保存由 Runtime 组合根注入的只读依赖。

        这里故意只接收诊断客户端和规划模型。过去的通用受控执行器参数会让调用方误以为
        Python 可以承担审批后的业务执行；
        现在恢复动作统一由 bridge 生成 ToolPlan，再交给 Java ingestion/outbox，因此这些参数
        不再存在，错误的装配会在启动时直接暴露，而不是静默降级到一条旁路执行链。
        """

        if diagnostic_client is not None and failure_diagnostic_client is not None:
            raise ValueError("RECOVERY_AGENT 诊断客户端不能重复注入")
        if model is not None and planning_model is not None:
            raise ValueError("RECOVERY_AGENT 规划模型不能重复注入")
        if model is not None and recovery_planning_model is not None:
            raise ValueError("RECOVERY_AGENT 规划模型不能重复注入")
        if planning_model is not None and recovery_planning_model is not None:
            raise ValueError("RECOVERY_AGENT 规划模型不能重复注入")
        self._diagnostic_client = diagnostic_client or failure_diagnostic_client
        self._model = model or planning_model or recovery_planning_model
        self._agent_id = _bounded_text(agent_id, 120).strip()
        if self._diagnostic_client is None:
            raise ValueError("RECOVERY_AGENT 必须注入 FailureDiagnosticClient")
        if self._model is None:
            raise ValueError("RECOVERY_AGENT 必须注入 RecoveryPlanningModel")
        if not self._agent_id:
            raise ValueError("RECOVERY_AGENT 必须提供非空 agent_id")

    @property
    def role(self) -> AgentSessionRole:
        """返回注册表用于路由的固定角色，实例不能在运行时伪装成别的 Agent。"""

        return self._ROLE

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None = None,
    ) -> SpecialistTurnResult:
        """执行一轮受控恢复分析，并把必要的外部输入明确交还主编排器。

        顺序不能随意交换：先验证角色和只读授权，再读取诊断事实；没有外部案例证据时不调用
        模型；模型输出必须先经过越权字段检查和确定性风险分类；高风险动作最后才检查外部批准
        事实并交给受控执行器。任何异常都转换为稳定错误码，避免把原始异常泄漏到 handoff。
        """

        started_at = time.perf_counter()
        empty_activities: tuple[SpecialistToolActivity, ...] = ()
        if request.role != self.role:
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_AGENT_ROLE_MISMATCH",
                public_summary="故障恢复专业 Agent 拒绝了不匹配的角色委派。",
                next_step="请由主编排器把请求路由到 RECOVERY_AGENT。",
                tool_activities=empty_activities,
            )

        # 故障诊断同样属于项目级数据访问，不能因为它是“只读”就允许空项目或通配项目。
        # 该校验必须位于事件、诊断客户端和模型调用之前：否则 Recovery 虽然最终会在
        # SpecialistAuditScope 构造时失败，期间却已经可能读取到不属于当前项目的运行事实。
        if not _has_project_scope(request.scope.project_id):
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_PROJECT_SCOPE_REQUIRED",
                public_summary="故障恢复缺少明确项目范围，已停止读取运行日志和失败事实。",
                next_step="请在当前用户已授权的具体项目中重新发起恢复分析。",
                tool_activities=empty_activities,
            )

        self._emit(
            event_sink,
            request,
            action="RECOVERY_TURN_STARTED",
            status="RUNNING",
            public_summary="故障恢复专业 Agent 已开始读取受控失败事实。",
        )

        diagnostic_tool = self._diagnostic_tool_name(request.scope.allowed_tool_names)
        if diagnostic_tool is None or request.budget.max_tool_calls < 1:
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_DIAGNOSTIC_TOOL_NOT_AUTHORIZED",
                public_summary="当前委派没有授权受控失败诊断读取，恢复分析已阻断。",
                next_step="请由 Gateway/Java 控制面授予只读诊断工具后重新发起本轮。",
                tool_activities=(
                    SpecialistToolActivity(
                        tool_name=FAILURE_DIAGNOSTIC_TOOL_CODE,
                        status="DENIED",
                        public_summary="受控失败诊断工具未获本轮委派授权。",
                    ),
                ),
            )

        self._emit(
            event_sink,
            request,
            action="RECOVERY_DIAGNOSTIC_STARTED",
            status="RUNNING",
            public_summary="正在读取本次 run 的受控日志摘要和失败事实。",
            attributes={"toolName": diagnostic_tool},
        )
        diagnostic_started_at = time.perf_counter()
        try:
            diagnostic_raw = self._run_diagnostic(self._build_diagnostic_request(request))
            diagnostic = self._coerce_diagnostic_result(diagnostic_raw)
            diagnostic = self._apply_trusted_autopilot_facts(diagnostic, request.context_summary)
        except Exception:
            # 诊断客户端异常可能带 endpoint、日志正文或认证信息，不能把异常字符串交给上层。
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_DIAGNOSTIC_FAILED",
                public_summary="受控运行日志或失败事实读取失败，暂未生成修复方案。",
                next_step="请检查运行事实服务状态，确认后重试恢复分析。",
                tool_activities=(
                    SpecialistToolActivity(
                        tool_name=FAILURE_DIAGNOSTIC_TOOL_CODE,
                        status="FAILED",
                        public_summary="受控失败诊断读取未完成。",
                        duration_ms=self._elapsed_ms(diagnostic_started_at),
                    ),
                ),
            )

        diagnostic_activity = SpecialistToolActivity(
            tool_name=FAILURE_DIAGNOSTIC_TOOL_CODE,
            status="SUCCEEDED",
            public_summary="已读取受控失败事实，未公开原始日志正文。",
            evidence_reference=diagnostic.log_references[0] if diagnostic.log_references else None,
            duration_ms=self._elapsed_ms(diagnostic_started_at),
        )
        self._emit(
            event_sink,
            request,
            action="RECOVERY_DIAGNOSTIC_COMPLETED",
            status="COMPLETED",
            public_summary="受控失败事实读取完成，正在检查案例和知识证据。",
            attributes={
                "logReferenceCount": len(diagnostic.log_references),
                "factCount": len(diagnostic.facts),
            },
        )

        case_evidence, knowledge_summary, monitoring_summary, evidence_references = self._evidence_context(
            request=request,
            diagnostic=diagnostic,
        )
        evidence_audit = self._build_evidence_audit(
            request=request,
            diagnostic=diagnostic,
            case_evidence=case_evidence,
            knowledge_summary=knowledge_summary,
            monitoring_summary=monitoring_summary,
        )
        diagnostic_evidence_gate = self._diagnostic_evidence_gate(diagnostic, evidence_audit)
        if not diagnostic_evidence_gate["satisfied"]:
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_DIAGNOSTIC_EVIDENCE_INSUFFICIENT",
                public_summary="恢复缺少可审计的结构化失败事实或受控日志证据，已停止模型决策。",
                next_step="请先完成同步执行诊断，再由模型自主选择精确检索或知识检索。",
                diagnostic=diagnostic,
                evidence_references=evidence_references,
                tool_activities=(diagnostic_activity,),
                structured_output={
                    "diagnosticEvidenceGate": diagnostic_evidence_gate,
                    "evidenceAudit": evidence_audit,
                },
            )
        monitoring_dependency_required = self._monitoring_dependency_required(request.context_summary)
        required_inputs: list[str] = []
        if monitoring_dependency_required and not monitoring_summary:
            required_inputs.append("monitoringSummary")
        if required_inputs:
            self._emit(
                event_sink,
                request,
                action="RECOVERY_EVIDENCE_REQUIRED",
                status="WAITING_FOR_INPUT",
                public_summary="缺少恢复所需的可信运行监控摘要，未让模型猜测运行状态。",
                attributes={"requiredEvidence": tuple(required_inputs)},
            )
            return self._waiting_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                public_summary="当前缺少已调度的运行监控摘要，无法安全生成恢复方案。",
                next_step="请等待 MONITOR_AGENT 返回同一 task/execution 的确定性状态后再生成恢复建议。",
                required_input_fields=tuple(required_inputs),
                diagnostic=diagnostic,
                evidence_references=evidence_references,
                tool_activities=(diagnostic_activity,),
            )

        try:
            # 恢复模型可能提出高风险动作，所以它比普通摘要模型更不能依赖隐式上下文；
            # 范围必须来自本次失败恢复 turn 的 request，而不是故障会话的旧缓存。
            audit_scope = request.audit_scope
            decision_control = _lookup(
                request.context_summary,
                "recoveryDecisionControl",
                "recovery_decision_control",
            )
            if not isinstance(decision_control, Mapping):
                decision_control = {}
            decision_phase = _bounded_text(_lookup(decision_control, "phase"), 48).strip().upper() or "DIAGNOSE"
            knowledge_search_completed = _strict_bool(
                _lookup(decision_control, "knowledgeSearchCompleted", "knowledge_search_completed"),
                default=False,
            )
            retrieval_already_performed = _strict_bool(
                _lookup(decision_control, "retrievalAlreadyPerformed", "retrieval_already_performed"),
                default=False,
            )
            remaining_knowledge_searches = _lookup(
                decision_control,
                "remainingKnowledgeSearches",
                "remaining_knowledge_searches",
            )
            remaining_knowledge_searches = (
                0 if remaining_knowledge_searches is None else int(remaining_knowledge_searches)
            )
            must_choose_single_governed_action = _strict_bool(
                _lookup(decision_control, "mustChooseSingleGovernedAction", "must_choose_single_governed_action"),
                default=False,
            )
            model_input = RecoveryPlanningModelInput(
                objective=_bounded_text(request.objective, 4_000),
                audit_scope=audit_scope,
                diagnostic_facts=_sanitize_mapping(diagnostic.facts),
                case_evidence=case_evidence,
                knowledge_summary=knowledge_summary,
                monitoring_summary=monitoring_summary,
                evidence_references=evidence_references,
                allowed_tool_names=tuple(
                    sorted(
                        name
                        for name in request.scope.allowed_tool_names
                        if self._is_read_only_tool_name(name)
                    )
                ),
                max_output_tokens=request.budget.max_output_tokens,
                failure_code=diagnostic.failure_code,
                failure_reason=_sanitize_text(diagnostic.failure_reason),
                evidence_audit=evidence_audit,
                decision_phase=decision_phase,
                knowledge_search_completed=knowledge_search_completed,
                retrieval_already_performed=retrieval_already_performed,
                remaining_knowledge_searches=remaining_knowledge_searches,
                must_choose_single_governed_action=must_choose_single_governed_action,
            )
        except (TypeError, ValueError):
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_MODEL_AUDIT_SCOPE_INVALID",
                public_summary="恢复规划模型缺少当前租户、项目、用户、会话或 turn 审计范围，已停止模型调用。",
                next_step="请由主编排器使用当前 turn 的委派范围重新发起恢复分析。",
                diagnostic=diagnostic,
                evidence_references=evidence_references,
                tool_activities=(diagnostic_activity,),
            )
        self._emit(
            event_sink,
            request,
            action="RECOVERY_MODEL_PLANNING_STARTED",
            status="RUNNING",
            public_summary="正在依据真实失败事实和外部证据生成恢复建议。",
            attributes={"evidenceCount": len(evidence_references)},
        )
        model_started_at = time.perf_counter()
        try:
            raw_model_output = self._model.plan(model_input)
            self._reject_model_overreach(raw_model_output)
            model_output = self._coerce_model_output(raw_model_output)
            self._reject_model_overreach(model_output.actions)
        except _RecoveryModelOverreach:
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_MODEL_OUTPUT_REJECTED",
                public_summary="恢复模型返回了越权执行或敏感正文，已拒绝该建议。",
                next_step="请修正模型适配器，只返回低敏恢复建议，不要返回审批、工具调用或 SQL。",
                diagnostic=diagnostic,
                evidence_references=evidence_references,
                tool_activities=(diagnostic_activity,),
            )
        except Exception as exc:
            model_failure = self._model_failure_details(exc)
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_PLANNING_MODEL_FAILED",
                public_summary="恢复规划模型调用失败，未生成或执行任何修复动作。",
                next_step="请稍后重试，或检查恢复规划模型 Provider 状态。",
                diagnostic=diagnostic,
                evidence_references=evidence_references,
                tool_activities=(diagnostic_activity,),
                structured_output=model_failure,
            )

        model_summary = self._safe_invocation_summary(model_output.invocation_summary)
        self._emit(
            event_sink,
            request,
            action="RECOVERY_MODEL_PLANNING_COMPLETED",
            status="COMPLETED",
            public_summary="恢复模型已返回建议，正在执行副作用和敏感输出校验。",
            attributes={
                "actionCount": len(model_output.actions),
                "latencyMs": model_summary.get("latencyMs", self._elapsed_ms(model_started_at)),
            },
        )

        grounded_knowledge = self._has_grounded_knowledge(knowledge_summary)
        rag_decision = model_output.retrieval_decision or model_output.rag_decision
        retrieval_strategy = model_output.retrieval_strategy
        if rag_decision == "AUTO":
            # 兼容旧 Provider：已有可信知识证据时不重复查，否则先进入只读检索。
            # 明确返回 SEARCH/SKIP 的新 Provider 始终保留自主选择。
            rag_decision = "SKIP" if grounded_knowledge else "SEARCH"
        raw_actions: tuple[Any, ...] = model_output.actions
        if rag_decision == "SEARCH":
            # 检索与修复分成两个 durable turn。即使模型同批返回了写动作，这里也只保留只读检索，
            # 防止“决定搜索”和“假设搜索结果已支持修复”在同一步发生。已有知识证据时再次
            # SEARCH 代表模型要扩大来源，而不是被规则层静默改成 SKIP。
            raw_actions = ({
                "actionId": f"recovery-rag-{request.turn_id}",
                "actionType": "SEARCH_RECOVERY_KNOWLEDGE",
                "arguments": {
                    "retrievalStrategy": retrieval_strategy if retrieval_strategy != "AUTO" else "RAG",
                },
                "reason": model_output.rag_reason or "恢复模型判断当前诊断仍需扩大受控证据来源。",
            },)

        try:
            actions = self._normalize_actions(
                raw_actions=raw_actions,
                diagnostic=diagnostic,
                evidence_references=evidence_references,
            )
        except _RecoveryModelOverreach:
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_MODEL_OUTPUT_REJECTED",
                public_summary="恢复模型的动作建议包含不允许的副作用字段或敏感内容，已拒绝。",
                next_step="请让模型返回结构化动作建议，由控制面负责审批和执行。",
                diagnostic=diagnostic,
                evidence_references=evidence_references,
                tool_activities=(diagnostic_activity,),
                model_invocation_summary=model_summary,
            )
        except (TypeError, ValueError):
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                error_code="RECOVERY_MODEL_OUTPUT_INVALID",
                public_summary="恢复模型没有返回可审核的结构化动作建议。",
                next_step="请修正模型输出契约后重新进行恢复分析。",
                diagnostic=diagnostic,
                evidence_references=evidence_references,
                tool_activities=(diagnostic_activity,),
                model_invocation_summary=model_summary,
            )

        strategy_changed = False
        previous_repair_fingerprint = _lookup(
            diagnostic.facts,
            "previousRepairFingerprint",
            "previous_repair_fingerprint",
            "lastRepairFingerprint",
            "last_repair_fingerprint",
        )
        repeated_error_count = _positive_int(
            _lookup(diagnostic.facts, "repeatedErrorCount", "repeated_error_count")
        )
        if repeated_error_count > 0 and previous_repair_fingerprint:
            current_fingerprint = compute_action_fingerprint(actions)
            if str(previous_repair_fingerprint).strip() == current_fingerprint:
                strategy_changed = True
                rag_decision = "SEARCH"
                retrieval_strategy = "RAG"
                actions = self._alternate_strategy_action(
                    request=request,
                    evidence_references=evidence_references,
                    previous_repair_fingerprint=current_fingerprint,
                    repeated_error_count=repeated_error_count,
                )
        action_fingerprint = compute_action_fingerprint(actions)
        public_actions = tuple(self._public_action(action) for action in actions)
        high_risk_actions = tuple(
            action
            for action in actions
            if action.classification == RecoveryActionClass.HIGH_RISK_SIDE_EFFECT
        )
        base_output = {
            "failure": self._public_failure(diagnostic),
            # 这是控制面诊断的确定性投影，而非模型声明。Java executor 使用同一事实判断
            # 重试是否可以保持在用户的 Autopilot 范围内。
            "autopilotRecoveryFacts": self._autopilot_recovery_facts(diagnostic),
            "repairActions": public_actions,
            "actionFingerprint": action_fingerprint,
            "evidenceReferences": evidence_references,
            "evidenceCount": len(evidence_references),
            "caseEvidenceAvailable": bool(case_evidence),
            "knowledgeSummaryAvailable": bool(knowledge_summary),
            "monitoringSummaryAvailable": bool(monitoring_summary),
            "ragDecision": rag_decision,
            "retrievalDecision": rag_decision,
            "retrievalStrategy": retrieval_strategy,
            "strategyChanged": strategy_changed,
            "diagnosticEvidenceGate": diagnostic_evidence_gate,
            "evidenceAudit": evidence_audit,
            "ragReason": _bounded_text(model_output.rag_reason, 600),
            "modelConfidence": model_output.confidence,
            "executed": False,
            "readOnly": not high_risk_actions,
            "payloadPolicy": "LOW_SENSITIVE_RECOVERY_RESULT_ONLY",
        }

        if not high_risk_actions:
            public_summary = self._model_public_summary(
                model_output,
                default="恢复 Agent 已完成受控诊断并生成不含业务副作用的方案草案。",
            )
            next_step = _bounded_text(
                model_output.next_step,
                600,
            ).strip() or "请审核恢复草案；如需改动任务或数据，必须由控制面发起审批。"
            base_output.update(
                {
                    "planAvailable": bool(actions),
                    "draftOnly": any(
                        action.classification == RecoveryActionClass.LOW_RISK_DRAFT for action in actions
                    ),
                    "nextStep": next_step,
                }
            )
            if rag_decision == "SEARCH" and not grounded_knowledge:
                base_output.update({
                    "javaToolPlanPending": True,
                    "nextStep": "先执行受治理的只读 RAG 检索；取得项目内引用后进入下一轮恢复决策。",
                })
            result = self._completed_result(
                request=request,
                event_sink=event_sink,
                started_at=started_at,
                public_summary=public_summary,
                structured_output=base_output,
                diagnostic=diagnostic,
                evidence_references=evidence_references,
                tool_activities=(diagnostic_activity,),
                model_invocation_summary=model_summary,
            )
            self._emit(
                event_sink,
                request,
                action="RECOVERY_PLAN_READY",
                status=result.status.value,
                public_summary=public_summary,
                attributes={"actionCount": len(actions), "requiresApproval": False},
            )
            return result

        # 高风险动作到这里仍然只是模型建议。Recovery 不读取 approvalFact，不检查通用执行器，
        # 也不在 Python 中发起任何业务调用；主 Agent bridge 会依据 actionType 选择平台注册工具，
        # FollowUpToolPlanner 再完成可见性、schema、预算、重复和真实反馈状态校验。
        base_output.update(
            {
                "planAvailable": bool(actions),
                "draftOnly": False,
                "requiresApproval": True,
                "executionStatus": "PROPOSED_FOR_GOVERNED_TOOLPLAN",
                "executed": False,
                "nextStep": (
                    "恢复建议将交给主 Agent bridge 映射为已注册工具；高风险 ToolPlan 由 Java 控制面"
                    "自动创建审批和 outbox，收到审批/worker receipt 后再继续。"
                ),
                "approvalRequest": self._approval_request(
                    request=request,
                    action_fingerprint=action_fingerprint,
                    status="JAVA_TOOLPLAN_APPROVAL_REQUIRED",
                    reason="高风险恢复建议尚未执行，必须由 Java 控制面生成受治理 ToolPlan、审批和 outbox。",
                ),
                "javaToolPlanPending": True,
            }
        )
        self._emit(
            event_sink,
            request,
            action="RECOVERY_TOOLPLAN_PROPOSAL_READY",
            status="COMPLETED",
            public_summary="恢复建议已生成，等待主 Agent bridge 映射为受治理 ToolPlan。",
            attributes={"highRiskActionCount": len(high_risk_actions)},
        )
        return self._completed_result(
            request=request,
            event_sink=event_sink,
            started_at=started_at,
            public_summary="恢复 Agent 已生成待治理恢复建议，Python 不直接执行动作。",
            structured_output=base_output,
            diagnostic=diagnostic,
            evidence_references=evidence_references,
            tool_activities=(diagnostic_activity,),
            model_invocation_summary=model_summary,
        )

    def _build_diagnostic_request(self, request: SpecialistTurnRequest) -> FailureDiagnosticRequest:
        """从专业 turn 构造诊断查询，并排除审批、原始正文和敏感字段。"""

        return FailureDiagnosticRequest(
            turn_id=request.turn_id,
            session_id=request.session_id,
            run_id=request.run_id,
            delegation_id=request.scope.delegation_id,
            tenant_id=request.scope.tenant_id,
            project_id=request.scope.project_id,
            actor_id=request.scope.actor_id,
            objective=_bounded_text(request.objective, 4_000),
            context_summary=self._diagnostic_context(request.context_summary),
            evidence_references=request.evidence_references,
        )

    def _run_diagnostic(self, request: FailureDiagnosticRequest) -> Any:
        """调用受控诊断适配器，兼容 ``diagnose`` 和历史 ``get_failure_facts`` 命名。"""

        diagnose = getattr(self._diagnostic_client, "diagnose", None)
        if diagnose is None:
            diagnose = getattr(self._diagnostic_client, "get_failure_facts", None)
        if diagnose is None:
            raise TypeError("failure diagnostic client has no supported read method")
        return diagnose(request)

    def _diagnostic_context(self, context: Mapping[str, Any]) -> Mapping[str, Any]:
        """只保留诊断所需的低敏索引字段，避免把批准事实或正文传给日志客户端。"""

        allowed_names = {
            "failureReference",
            "failureCode",
            "runId",
            "sessionId",
            "traceId",
            "caseReference",
            "taskReference",
            # taskId/executionId 是调用 data-sync 失败诊断接口所需的低敏定位符，不是日志正文。
            # 如果不显式保留，HTTP 适配器只能拿到 Agent 自己的 runId，无法定位真实同步 execution。
            "taskId",
            "executionId",
        }
        return {
            str(key): _sanitize_value(value, public=True)
            for key, value in context.items()
            if str(key) in allowed_names
        }

    def _apply_trusted_autopilot_facts(
        self,
        diagnostic: FailureDiagnosticResult,
        context: Mapping[str, Any],
    ) -> FailureDiagnosticResult:
        """把 Java 已验证的循环事实叠加到结构化诊断结果中。

        普通 Recovery turn 不携带 ``trustedAutopilotRecovery``，因此保持原诊断不变。Autopilot
        内部入口只允许叠加当前错误指纹、重复次数和上一轮修复指纹三个固定字段；这些值用于判断
        同一错误是否重复采用了同一方案。方法不会读取 objective、模型输出、RAG 文档或任意顶层字段，
        从而防止不可信文本伪造循环次数并强迫 Agent 改变策略。

        ``FailureDiagnosticResult`` 是冻结 dataclass，所以这里创建一份新快照而不是修改原对象；原始
        日志引用、证据引用、公开摘要和 evidence records 都原样保留。
        """

        trusted = _lookup(context, "trustedAutopilotRecovery", "trusted_autopilot_recovery")
        if not isinstance(trusted, Mapping):
            return diagnostic

        repeated_error_count = _lookup(trusted, "repeatedErrorCount", "repeated_error_count")
        if isinstance(repeated_error_count, bool):
            raise ValueError("trusted repeatedErrorCount 不能是布尔值")
        try:
            normalized_repeated_count = int(repeated_error_count or 0)
        except (TypeError, ValueError) as exc:
            raise ValueError("trusted repeatedErrorCount 必须是整数") from exc
        if not 0 <= normalized_repeated_count <= 100:
            raise ValueError("trusted repeatedErrorCount 超出安全范围")

        error_fingerprint = _bounded_text(
            _lookup(trusted, "errorFingerprint", "error_fingerprint"),
            80,
        ).strip()
        previous_repair_fingerprint = _bounded_text(
            _lookup(trusted, "previousRepairFingerprint", "previous_repair_fingerprint"),
            80,
        ).strip()
        sha256_pattern = re.compile(r"^(?:sha256:)?[0-9a-fA-F]{64}$")
        if not sha256_pattern.fullmatch(error_fingerprint):
            raise ValueError("trusted errorFingerprint 必须是 SHA-256")
        if previous_repair_fingerprint and not sha256_pattern.fullmatch(previous_repair_fingerprint):
            raise ValueError("trusted previousRepairFingerprint 必须是 SHA-256")

        facts = dict(diagnostic.facts)
        facts["errorFingerprint"] = error_fingerprint.removeprefix("sha256:").lower()
        facts["repeatedErrorCount"] = normalized_repeated_count
        if previous_repair_fingerprint:
            # Recovery 的动作指纹函数历史上返回 ``sha256:`` 前缀。统一成该格式后，重复策略比较
            # 不会因 Java/data-sync 使用纯 64 位十六进制而产生假阴性。
            previous_hex = previous_repair_fingerprint.removeprefix("sha256:").lower()
            facts["previousRepairFingerprint"] = f"sha256:{previous_hex}"
        return FailureDiagnosticResult(
            failure_code=diagnostic.failure_code,
            failure_reason=diagnostic.failure_reason,
            facts=facts,
            log_references=diagnostic.log_references,
            evidence_references=diagnostic.evidence_references,
            log_summary=diagnostic.log_summary,
            public_summary=diagnostic.public_summary,
            evidence_records=diagnostic.evidence_records,
        )

    def _evidence_context(
        self,
        *,
        request: SpecialistTurnRequest,
        diagnostic: FailureDiagnosticResult,
    ) -> tuple[Mapping[str, Any], Mapping[str, Any], Mapping[str, Any], tuple[str, ...]]:
        """提取主编排器传入的案例证据和 KNOWLEDGE_AGENT 摘要。

        顶层 ``evidence_references`` 只能作为定位标识，不能单独证明有案例事实；必须有摘要内容、
        引用列表或 KNOWLEDGE_AGENT 的 grounded 结果。这样可以防止 Agent 看到一个 URI 就自行
        猜测对应的事故。
        """

        context = request.context_summary
        case_raw = _lookup(context, "caseEvidence", "case_evidence", "recoveryCaseEvidence")
        knowledge_raw = _lookup(
            context,
            "knowledgeSummary",
            "knowledge_summary",
            "knowledgeEvidence",
            "knowledge_evidence",
        )
        monitoring_raw: Any = None

        # Specialist coordinator 将已完成的上游输出放入 ``dependencyResults``。仅读取其低敏
        # 结构化摘要；两个分支都不会重新运行 RAG 或读取日志。
        dependency_results = _lookup(context, "dependencyResults", "dependency_results")
        if isinstance(dependency_results, Mapping):
            if knowledge_raw is None:
                knowledge_result = _lookup(
                    dependency_results,
                    AgentSessionRole.KNOWLEDGE_AGENT.value,
                    "KNOWLEDGE_AGENT",
                )
                if isinstance(knowledge_result, Mapping):
                    knowledge_raw = _lookup(knowledge_result, "structuredOutput", "structured_output")
                    if knowledge_raw is None:
                        knowledge_raw = knowledge_result

            # Monitor 独立于知识查询。将此提取保留在 ``knowledge_raw is None`` 分支外，
            # 以免正常的 grounded RAG 结果意外抑制 recovery wave 所需的已完成 runtime 依赖。
            monitor_result = _lookup(
                dependency_results,
                AgentSessionRole.MONITOR_AGENT.value,
                "MONITOR_AGENT",
            )
            if isinstance(monitor_result, Mapping):
                monitoring_raw = _lookup(monitor_result, "structuredOutput", "structured_output")
                if monitoring_raw is None:
                    monitoring_raw = monitor_result

        case_evidence = self._normalize_evidence_mapping(case_raw)
        knowledge_summary = self._normalize_evidence_mapping(knowledge_raw)
        monitoring_summary = self._normalize_monitoring_summary(monitoring_raw)
        if not self._has_evidence(knowledge_summary):
            knowledge_summary = {}
        if not self._has_evidence(case_evidence):
            case_evidence = {}

        references: list[str] = list(request.evidence_references)
        references.extend(diagnostic.evidence_references)
        references.extend(diagnostic.log_references)
        references.extend(self._references_from_value(case_raw))
        references.extend(self._references_from_value(knowledge_raw))
        references.extend(self._rag_references_from_value(knowledge_raw))
        return (
            _sanitize_mapping(case_evidence),
            _sanitize_mapping(knowledge_summary),
            monitoring_summary,
            _unique_text(references),
        )

    def _build_evidence_audit(
        self,
        *,
        request: SpecialistTurnRequest,
        diagnostic: FailureDiagnosticResult,
        case_evidence: Mapping[str, Any],
        knowledge_summary: Mapping[str, Any],
        monitoring_summary: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        """为当前 turn 构建低敏的来源、时间、查询和证据元数据。"""

        retrieved_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        query_material = "|".join((
            request.scope.tenant_id,
            str(request.scope.project_id or ""),
            request.session_id,
            request.run_id,
            FAILURE_DIAGNOSTIC_TOOL_CODE,
        ))
        query_digest = "sha256:" + hashlib.sha256(query_material.encode("utf-8")).hexdigest()
        query_summary = {
            "kind": "RECOVERY_DIAGNOSTIC",
            "scope": "TASK_EXECUTION",
            "fieldCount": len(diagnostic.facts),
            "referenceCount": len(diagnostic.log_references) + len(diagnostic.evidence_references),
        }
        records: list[dict[str, Any]] = []

        def add(source_type: str, source_ref: Any, *, evidence_id: Any = None, source_time: Any = None) -> None:
            reference = _bounded_text(source_ref, 220).strip()
            if not reference:
                return
            normalized_type = _bounded_text(source_type, 48).strip().upper() or "STRUCTURED_API"
            stable_id = _bounded_text(evidence_id, 220).strip() or (
                "diagnostic-evidence:"
                + hashlib.sha256(f"{normalized_type}|{reference}|{query_digest}".encode("utf-8")).hexdigest()
            )
            records.append({
                "evidenceId": stable_id,
                "sourceType": normalized_type,
                "sourceRef": reference,
                "retrievedAt": _bounded_text(source_time, 64).strip() or retrieved_at,
                "queryDigest": query_digest,
                "querySummary": query_summary,
            })

        for record in diagnostic.evidence_records:
            add(
                _lookup(record, "sourceType", "source_type") or "STRUCTURED_API",
                _lookup(record, "sourceRef", "source_ref", "reference", "sourceUri", "source_uri"),
                evidence_id=_lookup(record, "evidenceId", "evidence_id"),
                source_time=_lookup(record, "retrievedAt", "retrieved_at"),
            )
        for reference in diagnostic.log_references:
            add("EXECUTION_LOG", reference)
        for reference in diagnostic.evidence_references:
            add("STRUCTURED_API", reference)
        task_id = _lookup(diagnostic.facts, "taskId", "task_id")
        execution_id = _lookup(diagnostic.facts, "executionId", "execution_id")
        if task_id is not None and execution_id is not None:
            add("STRUCTURED_API", f"sync-execution:{task_id}:{execution_id}")
        for reference in self._references_from_value(case_evidence):
            add("CASE_HISTORY", reference)
        for reference in self._rag_references_from_value(knowledge_summary):
            add("RAG", reference)
        if monitoring_summary:
            add("MONITORING_API", "sync-monitoring:summary")

        deduplicated: dict[str, dict[str, Any]] = {}
        for record in records:
            deduplicated.setdefault(str(record["evidenceId"]), record)
        final_records = tuple(deduplicated.values())
        digest_material = json.dumps(final_records, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return {
            "queryDigest": query_digest,
            "querySummary": query_summary,
            "retrievedAt": retrieved_at,
            "evidenceCount": len(final_records),
            "sourceTypes": tuple(sorted({str(item["sourceType"]) for item in final_records})),
            "evidenceRecords": final_records,
            "evidenceDigest": "sha256:" + hashlib.sha256(digest_material.encode("utf-8")).hexdigest(),
            "payloadPolicy": "LOW_SENSITIVE_RECOVERY_EVIDENCE_AUDIT_NO_RAW_LOG_OR_DOCUMENT_BODY",
        }

    @staticmethod
    def _diagnostic_evidence_gate(
        diagnostic: FailureDiagnosticResult,
        audit: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        """要求权威诊断，但不要求调用 RAG。"""

        code = _bounded_text(diagnostic.failure_code, 160).strip().upper()
        failure_signal = (bool(code) and code != "UNKNOWN") or bool(diagnostic.facts)
        source_types = {str(value) for value in (audit.get("sourceTypes") or ())}
        authoritative_source = bool(source_types & {"EXECUTION_LOG", "STRUCTURED_API", "MONITORING_API"})
        evidence_count = int(audit.get("evidenceCount") or 0)
        return {
            "satisfied": failure_signal and authoritative_source and evidence_count > 0,
            "failureSignal": failure_signal,
            "authoritativeSource": authoritative_source,
            "evidenceCount": evidence_count,
            "required": ("failureSignal", "authoritativeSource", "evidenceCount"),
            "ragRequired": False,
        }

    @classmethod
    def _autopilot_recovery_facts(cls, diagnostic: FailureDiagnosticResult) -> Mapping[str, Any]:
        """投影提出无人值守重试前所需的精简事实。

        只有对于明确的瞬态 connector/worker 故障，且受控诊断同时表明受影响工作可重试时，重试才是安全的。
        本方法有意忽略模型标签、动作名称和自由文本错误描述。因此即使模型请求
        ``RETRY_FAILED_OBJECTS``，schema、凭据、权限、范围和数据合同故障仍留在受治理的审核路径中。

        返回映射包含布尔值、有界故障类别和计数。它绝不携带原始错误消息、SQL、对象名或样本行，
        因而适用于 specialist 结果和 checkpoint。
        """

        facts = diagnostic.facts if isinstance(diagnostic.facts, Mapping) else {}
        root_causes = tuple(
            _action_key(value)
            for value in (_lookup(facts, "rootCauseCodes", "root_cause_codes") or ())
            if _action_key(value)
        )
        errors = _lookup(facts, "errors") or ()
        error_codes: list[str] = []
        retryable_error = False
        if isinstance(errors, (list, tuple)):
            for item in errors[:32]:
                if not isinstance(item, Mapping):
                    continue
                error_codes.extend(
                    [
                        code
                        for code in (_action_key(_lookup(item, "errorType", "error_type")),)
                        if code
                    ],
                )
                error_codes.extend(
                    [
                        code
                        for code in (_action_key(_lookup(item, "errorCode", "error_code")),)
                        if code
                    ],
                )
                if _lookup(item, "retryable") is True and _positive_int(_lookup(item, "count")) > 0:
                    retryable_error = True

        transient_markers = (
            "CONNECTOR",
            "CONNECTION",
            "NETWORK",
            "TIMEOUT",
            "UNAVAILABLE",
            "COMMUNICATION",
            "WORKER",
            "KAFKA",
            "BROKER",
            "TRANSIENT",
        )
        transient_source = any(
            any(marker in code for marker in transient_markers)
            for code in (*root_causes, *error_codes)
        )
        explicit_retryable = _lookup(facts, "retryable") is True or retryable_error
        failure_class = (
            "TRANSIENT_CONNECTOR_OR_WORKER"
            if transient_source and explicit_retryable
            else "NON_TRANSIENT_OR_UNCLASSIFIED"
        )
        failed_object_count = _positive_int(
            _lookup(facts, "failedObjectCount", "failed_object_count")
        )
        return {
            "failureClass": failure_class,
            "retryable": explicit_retryable,
            "eligibleForAutomaticRetry": (
                failure_class == "TRANSIENT_CONNECTOR_OR_WORKER"
                and explicit_retryable
                and failed_object_count > 0
            ),
            "failedObjectCount": failed_object_count,
            "rootCauseCodes": tuple(root_causes[:12]),
        }

    def _alternate_strategy_action(
        self,
        *,
        request: SpecialistTurnRequest,
        evidence_references: tuple[str, ...],
        previous_repair_fingerprint: str,
        repeated_error_count: int,
    ) -> tuple[RecoveryAction, ...]:
        """用只读证据扩展替换重复的修复动作。"""

        return (
            RecoveryAction(
                action_type="SEARCH_RECOVERY_KNOWLEDGE",
                tool_name="sync.execution.rag.lookup",
                arguments={
                    "retrievalStrategy": "RAG",
                    "reasonCode": "REPEATED_ERROR_REQUIRES_DIFFERENT_STRATEGY",
                    "previousRepairFingerprint": previous_repair_fingerprint,
                    "repeatedErrorCount": repeated_error_count,
                },
                reason="同一错误再次出现且修复指纹重复，必须先扩大受控知识证据并更换方案。",
                action_id=f"recovery-rag-repeat-{request.turn_id}",
                evidence_references=evidence_references,
                classification=RecoveryActionClass.READ_ONLY_DIAGNOSTIC,
            ),
        )

    def _has_grounded_knowledge(self, value: Mapping[str, Any]) -> bool:
        """在模型恢复规划前要求实际落地的 KNOWLEDGE_AGENT 结果。

        Recovery 可以将案例摘要作为辅助证据，但不能用它替代 RAG 落地合同。必须同时具备布尔值和至少一条
        citation/reference，从而防止调用方在没有证据时声称 ``grounded=true``。
        """

        if not self._has_evidence(value) or _lookup(value, "grounded") is not True:
            return False
        references = _lookup(value, "citations", "evidenceReferences", "evidence_references", "references")
        return bool(references)

    @staticmethod
    def _monitoring_dependency_required(context: Mapping[str, Any]) -> bool:
        """检测 coordinator 是否为当前 turn 提供了已完成的 monitor 依赖。"""

        dependencies = _lookup(context, "dependencyResults", "dependency_results")
        return isinstance(dependencies, Mapping) and isinstance(
            _lookup(dependencies, AgentSessionRole.MONITOR_AGENT.value, "MONITOR_AGENT"),
            Mapping,
        )

    @staticmethod
    def _normalize_monitoring_summary(value: Any) -> Mapping[str, Any]:
        """仅保留 recovery 规划模型可使用的低敏 monitor 事实。

        monitor 结果可包含说明文本和调度细节，但 recovery 仅需要确定性的生命周期/健康度计数器。重建此
        精简对象可避免日志、凭据、模型文本或任意依赖字段进入第二次模型调用。
        """

        if not isinstance(value, Mapping):
            return {}
        allowed = (
            "taskId",
            "executionId",
            "status",
            "health",
            "terminal",
            "anomalyCodes",
            "anomalyCount",
            "nextPollAfterSeconds",
            "recordsRead",
            "recordsWritten",
            "recordsFailed",
        )
        return {
            name: _sanitize_value(value.get(name), public=True)
            for name in allowed
            if value.get(name) is not None
        }

    @classmethod
    def _model_failure_details(cls, exc: Exception) -> Mapping[str, str]:
        """将模型故障分类为稳定、低敏的结果字段。

        适配器可以附加 ``reason_code`` 和 ``reason_source`` 属性，但仅接受固定允许列表中的值。未知异常会
        回退到通用适配器代码；这在保留可操作运维类别的同时，有意避免返回 endpoint URL、provider 消息、
        prompt、输出文本或堆栈跟踪。
        """

        reason_code = getattr(exc, "reason_code", None)
        reason_source = getattr(exc, "reason_source", None)
        if reason_code not in cls._MODEL_FAILURE_REASON_CODES:
            if isinstance(exc, TimeoutError) or "timeout" in exc.__class__.__name__.lower():
                reason_code = "MODEL_TIMEOUT"
                reason_source = "MODEL_PROVIDER_TRANSPORT"
            else:
                reason_code = "MODEL_ADAPTER_ERROR"
                reason_source = "SPECIALIST_MODEL_ADAPTER"
        if reason_source not in cls._MODEL_FAILURE_SOURCES:
            reason_source = "SPECIALIST_MODEL_ADAPTER"
        return {
            "modelFailureReasonCode": reason_code,
            "modelFailureSource": reason_source,
        }

    def _normalize_evidence_mapping(self, value: Any) -> Mapping[str, Any]:
        """把案例或知识摘要统一成映射，并在边界处去掉原始正文。"""

        if isinstance(value, Mapping):
            return _sanitize_mapping(value)
        if isinstance(value, (list, tuple)) and value:
            return {"items": _sanitize_value(value, public=True)}
        if isinstance(value, str) and value.strip():
            return {"summary": _bounded_text(value, 1_200)}
        return {}

    def _has_evidence(self, value: Mapping[str, Any]) -> bool:
        """判断摘要是否真的携带案例事实，而不是只有 false 标志或空引用。"""

        if not value:
            return False
        answer_available = _lookup(value, "answerAvailable", "answer_available")
        grounded = _lookup(value, "grounded")
        citations = _lookup(value, "citations", "evidenceReferences", "evidence_references", "references")
        if answer_available is False and not citations:
            return False
        if grounded is False and not citations:
            return False
        meaningful_keys = {
            _normalized_key(key)
            for key, item in value.items()
            if item not in (None, "", (), [], {})
            and _normalized_key(key) not in {"answeravailable", "grounded", "payloadpolicy"}
        }
        return bool(meaningful_keys or citations)

    def _references_from_value(self, value: Any) -> tuple[str, ...]:
        """从摘要中提取引用标识，不把引用附近的正文带入结果。"""

        if not isinstance(value, Mapping):
            return ()
        references = _lookup(value, "evidenceReferences", "evidence_references", "references", "referenceIds")
        return _unique_text(references if isinstance(references, (list, tuple, set)) else (references,))

    def _rag_references_from_value(self, value: Any) -> tuple[str, ...]:
        """把 KNOWLEDGE_AGENT citation 元数据转为稳定 RAG 引用，不保留 snippet。"""

        if not isinstance(value, Mapping):
            return ()
        citations = _lookup(value, "citations")
        if not isinstance(citations, (list, tuple)):
            return ()
        references: list[str] = []
        for citation in citations:
            if not isinstance(citation, Mapping):
                continue
            document_id = _lookup(citation, "documentId", "document_id")
            chunk_id = _lookup(citation, "chunkId", "chunk_id")
            if document_id and chunk_id:
                references.append(f"rag:{_bounded_text(document_id, 160)}:{_bounded_text(chunk_id, 160)}")
        return _unique_text(references)

    def _normalize_actions(
        self,
        *,
        raw_actions: Iterable[Any],
        diagnostic: FailureDiagnosticResult,
        evidence_references: tuple[str, ...],
    ) -> tuple[RecoveryAction, ...]:
        """把模型动作转成统一结构，并按动作语义重新计算风险等级。"""

        actions: list[RecoveryAction] = []
        for index, raw_action in enumerate(raw_actions, start=1):
            if isinstance(raw_action, str):
                action_mapping: Mapping[str, Any] = {"actionType": raw_action}
            elif isinstance(raw_action, Mapping):
                action_mapping = raw_action
            else:
                raise ValueError("recovery action must be a mapping or text")
            self._reject_model_overreach(action_mapping)
            action_type = _bounded_text(
                _lookup(action_mapping, "actionType", "action_type", "kind", "type", "name"),
                160,
            ).strip()
            if not action_type:
                raise ValueError("recovery action type is required")
            arguments = _lookup(action_mapping, "arguments", "parameters", "change")
            if arguments is None:
                arguments = {}
            if not isinstance(arguments, Mapping):
                raise ValueError("recovery action arguments must be a mapping")
            original_values = _lookup(
                action_mapping,
                "originalValues",
                "original_values",
                "originalValue",
                "original_value",
            )
            proposed_values = _lookup(
                action_mapping,
                "proposedValues",
                "proposed_values",
                "proposedValue",
                "proposed_value",
                "suggestedValues",
            )
            original_values = original_values if isinstance(original_values, Mapping) else {}
            proposed_values = proposed_values if isinstance(proposed_values, Mapping) else {}
            if not original_values:
                original_values = self._values_from_arguments(arguments, "original")
            if not proposed_values:
                proposed_values = self._values_from_arguments(arguments, "proposed")
            action = RecoveryAction(
                action_type=action_type,
                tool_name=_lookup(action_mapping, "toolName", "tool_name", "executorTool"),
                arguments=_sanitize_mapping(arguments),
                reason=_bounded_text(_lookup(action_mapping, "reason", "why", "explanation"), 1_000),
                original_values=_sanitize_mapping(original_values),
                proposed_values=_sanitize_mapping(proposed_values),
                action_id=(
                    _bounded_text(_lookup(action_mapping, "actionId", "action_id"), 120).strip()
                    or f"recovery-action-{index}"
                ),
                evidence_references=_unique_text(
                    evidence_references
                    + self._references_from_value(action_mapping)
                ),
                classification=self._classify_action(action_type),
            )
            self._reject_action_sensitive_content(action)
            actions.append(action)

        if self._is_duplicate_failure(diagnostic):
            actions = self._ensure_duplicate_task_plan(actions, diagnostic, evidence_references)
        return tuple(actions)

    def _values_from_arguments(self, arguments: Mapping[str, Any], prefix: str) -> Mapping[str, Any]:
        """从结构化参数兼容提取原值或建议值，避免要求模型重复字段。"""

        values = _lookup(arguments, f"{prefix}Values", f"{prefix}_values")
        if isinstance(values, Mapping):
            return values
        value = _lookup(arguments, f"{prefix}Value", f"{prefix}_value")
        if value is not None:
            return {"value": value}
        return {}

    def _ensure_duplicate_task_plan(
        self,
        actions: list[RecoveryAction],
        diagnostic: FailureDiagnosticResult,
        evidence_references: tuple[str, ...],
    ) -> list[RecoveryAction]:
        """为同名任务错误补齐可审核的重命名建议，但绝不直接改名或重跑任务。

        如果模型已经给出 ``RENAME_TASK``/``MODIFY_TASK`` 动作，只补齐缺失的原值、建议值和原因；
        如果模型没有给出动作，则只有在受控失败事实明确提供原任务名时，才生成一个确定性的
        ``<原名>-recovery`` 建议。没有原名时宁可等待用户补参，也不猜测任务对象。
        """

        duplicate_index = next(
            (
                index
                for index, action in enumerate(actions)
                if self._is_task_mutation(action.action_type)
            ),
            None,
        )
        original_name = self._find_fact_value(
            diagnostic.facts,
            "originalTaskName",
            "taskName",
            "currentTaskName",
        )
        proposed_name = self._find_fact_value(
            diagnostic.facts,
            "proposedTaskName",
            "suggestedTaskName",
            "recoveryTaskName",
        )
        if duplicate_index is None:
            if not original_name:
                return actions
            proposed_name = proposed_name or f"{original_name}-recovery"
            actions.append(
                RecoveryAction(
                    action_type="RENAME_TASK",
                    tool_name="task.recovery.rename",
                    reason="运行事实表明项目内存在同名任务，建议先改用唯一任务名再继续后续生命周期。",
                    original_values={"taskName": original_name},
                    proposed_values={"taskName": proposed_name},
                    action_id="recovery-action-duplicate-task-name",
                    evidence_references=evidence_references,
                    classification=RecoveryActionClass.HIGH_RISK_SIDE_EFFECT,
                )
            )
            return actions

        action = actions[duplicate_index]
        original_values = dict(action.original_values)
        proposed_values = dict(action.proposed_values)
        if not original_values and original_name:
            original_values = {"taskName": original_name}
        if not proposed_values:
            proposed_name = proposed_name or original_values.get("taskName")
            if proposed_name:
                proposed_values = {"taskName": f"{proposed_name}-recovery"}
        reason = action.reason or "同名任务错误需要先取得用户许可，再使用唯一任务名重新提交。"
        actions[duplicate_index] = RecoveryAction(
            action_type=action.action_type,
            tool_name=action.tool_name or "task.recovery.rename",
            arguments=action.arguments,
            reason=reason,
            original_values=original_values,
            proposed_values=proposed_values,
            action_id=action.action_id,
            evidence_references=_unique_text(action.evidence_references + evidence_references),
            classification=RecoveryActionClass.HIGH_RISK_SIDE_EFFECT,
        )
        return actions

    def _find_fact_value(self, facts: Mapping[str, Any], *names: str) -> str | None:
        """在受控失败事实的浅层和常见嵌套层中读取一个低敏文本值。"""

        value = _lookup(facts, *names)
        if value is None:
            for nested_name in ("task", "taskDetails", "task_details", "resource"):
                nested = _lookup(facts, nested_name)
                if isinstance(nested, Mapping):
                    value = _lookup(nested, *names)
                    if value is not None:
                        break
        if isinstance(value, (str, int, float)) and not isinstance(value, bool):
            return _bounded_text(value, 240).strip() or None
        return None

    def _classify_action(self, action_type: str) -> RecoveryActionClass:
        """只依据动作类型做风险分类，拒绝模型用一个 low-risk 标签覆盖真实副作用。"""

        normalized = _action_key(action_type)
        if any(part in normalized for part in self._HIGH_RISK_ACTION_PARTS) or self._is_task_mutation(normalized):
            return RecoveryActionClass.HIGH_RISK_SIDE_EFFECT
        if any(part in normalized for part in self._DRAFT_ACTION_PARTS):
            return RecoveryActionClass.LOW_RISK_DRAFT
        if any(part in normalized for part in self._READ_ONLY_ACTION_PARTS):
            return RecoveryActionClass.READ_ONLY_DIAGNOSTIC
        # 未知动作默认按高风险处理，保证新增动作不会意外绕过审批。
        return RecoveryActionClass.HIGH_RISK_SIDE_EFFECT

    def _is_task_mutation(self, action_type: str) -> bool:
        """判断动作是否可能修改、发布、重跑或重试任务生命周期。"""

        normalized = _action_key(action_type)
        return any(
            marker in normalized
            for marker in (
                "TASK",
                "RENAME",
                "PUBLISH",
                "RERUN",
                "RETRY",
                "RESUME",
                "RESTART",
            )
        ) and not any(part in normalized for part in self._READ_ONLY_ACTION_PARTS)

    def _is_duplicate_failure(self, diagnostic: FailureDiagnosticResult) -> bool:
        """根据受控错误码、原因和结构化事实识别可恢复的同名任务错误。"""

        material = " ".join(
            (
                _bounded_text(diagnostic.failure_code, 240),
                _bounded_text(diagnostic.failure_reason, 800),
                _bounded_text(_lookup(diagnostic.facts, "errorCode", "reason", "category"), 800),
            )
        ).lower()
        return any(
            marker in material
            for marker in (
                "duplicate_task_name",
                "duplicate operation",
                "duplicate task",
                "same-name",
                "same name",
                "同名任务",
                "任务同名",
            )
        )

    def _reject_action_sensitive_content(self, action: RecoveryAction) -> None:
        """拒绝动作参数中的 SQL、样本行、凭据和直接执行字段，阻止敏感内容进入输出或工具。"""

        for mapping in (action.arguments, action.original_values, action.proposed_values):
            if self._contains_sensitive_key(mapping):
                raise _RecoveryModelOverreach("sensitive recovery action payload")
        if _looks_like_sql_text(action.reason):
            raise _RecoveryModelOverreach("sensitive recovery action reason")

    def _reject_model_overreach(self, value: Any) -> None:
        """递归检查模型输出，确保模型只能提出建议而不能伪造审批、执行结果或敏感正文。"""

        if isinstance(value, Mapping):
            for key, item in value.items():
                normalized = _normalized_key(key)
                if normalized in self._FORBIDDEN_MODEL_KEYS or self._is_sensitive_content_key(normalized):
                    raise _RecoveryModelOverreach(f"forbidden model field: {normalized}")
                self._reject_model_overreach(item)
            return
        if isinstance(value, (list, tuple, set)):
            for item in value:
                self._reject_model_overreach(item)
            return
        if isinstance(value, str) and _looks_like_sql_text(value):
            raise _RecoveryModelOverreach("raw SQL in model output")

    def _contains_sensitive_key(self, value: Any) -> bool:
        """递归检查内部动作值的字段名，避免凭据、SQL 和样本行被交给执行器。"""

        if isinstance(value, Mapping):
            for key, item in value.items():
                normalized = _normalized_key(key)
                if self._is_sensitive_content_key(normalized) or any(
                    part in normalized for part in self._SECRET_KEY_PARTS
                ):
                    return True
                if self._contains_sensitive_key(item):
                    return True
        elif isinstance(value, (list, tuple, set)):
            return any(self._contains_sensitive_key(item) for item in value)
        return False

    def _is_sensitive_content_key(self, normalized_key: str) -> bool:
        """判断字段名是否表示 SQL、正文、样本或隐藏思维链，而不是普通业务值。"""

        return any(part in normalized_key for part in self._SENSITIVE_CONTENT_KEY_PARTS)

    def _public_action(self, action: RecoveryAction) -> Mapping[str, Any]:
        """生成不包含参数值的动作公开摘要，同时保留同名任务的原值/建议值对照。"""

        return {
            "actionId": action.action_id,
            "actionType": action.action_type,
            "classification": action.classification.value,
            "toolName": action.tool_name,
            "requiresApproval": action.classification == RecoveryActionClass.HIGH_RISK_SIDE_EFFECT,
            "argumentFieldNames": tuple(
                sorted(
                    _bounded_text(key, 120)
                    for key in action.arguments
                    if not self._is_sensitive_content_key(_normalized_key(key))
                )
            ),
            "originalValues": _sanitize_value(action.original_values, public=True),
            "proposedValues": _sanitize_value(action.proposed_values, public=True),
            "reason": _sanitize_text(action.reason),
            "evidenceReferences": action.evidence_references,
        }

    def _public_failure(self, diagnostic: FailureDiagnosticResult) -> Mapping[str, Any]:
        """返回用户可读但不含日志正文的失败原因和事实计数。"""

        return {
            "failureCode": _sanitize_text(diagnostic.failure_code),
            "failureReason": _sanitize_text(diagnostic.failure_reason) or "运行事实报告了未分类失败。",
            "logReferenceCount": len(diagnostic.log_references),
            "factCount": len(diagnostic.facts),
        }

    @staticmethod
    def _diagnostic_fact_binding(
        request: SpecialistTurnRequest,
        diagnostic: FailureDiagnosticResult,
    ) -> Mapping[str, Any]:
        """生成只供主 Agent Bridge 使用的 Java 诊断事实绑定。

        ``FailureDiagnosticResult`` 已由受保护的 ``HttpFailureDiagnosticClient`` 完成响应范围校验；
        这里仍只复制 task/execution 定位和本次不可变的双主体范围，不复制错误正文、日志、样本或
        模型输出。Bridge 后续必须逐项对照 ``delegated_scope_binding``，并且只能先创建一个只读
        ``sync.execution.diagnose`` ToolPlan 来换取正式 auditId/runId，不能把本字段直接当作
        ``diagnosisRef``，更不能据此批准 retry、apply、replay、alter 或 create。
        """

        facts = diagnostic.facts if isinstance(diagnostic.facts, Mapping) else {}
        task_id = _positive_decimal_reference(_lookup(facts, "taskId", "task_id"))
        execution_id = _positive_decimal_reference(_lookup(facts, "executionId", "execution_id"))
        if task_id is None or execution_id is None:
            return {}
        return {
            "source": "data-sync-control-plane",
            "factType": "SYNC_EXECUTION_DIAGNOSIS",
            "tenantId": request.scope.tenant_id,
            "applicationId": request.scope.application_id,
            "projectId": request.scope.project_id,
            "actorId": request.scope.actor_id,
            "sessionId": request.session_id,
            "runId": request.run_id,
            "delegationId": request.scope.delegation_id,
            "taskId": task_id,
            "executionId": execution_id,
        }

    def _approval_request(
        self,
        *,
        request: SpecialistTurnRequest,
        action_fingerprint: str,
        status: str,
        reason: str,
    ) -> Mapping[str, Any]:
        """生成显式审批请求，告诉用户批准必须绑定哪些不可替代的审计字段。"""

        return {
            "required": True,
            "type": "RECOVERY_ACTION_APPROVAL",
            "status": status,
            "reason": _sanitize_text(reason),
            "delegationId": request.scope.delegation_id,
            "runId": request.run_id,
            "actionFingerprint": action_fingerprint,
            "requiredBindings": ("delegationId", "runId", "actionFingerprint"),
            "approvalCreatedByPython": False,
            "executionBoundary": "JAVA_GATEWAY_RBAC_CONTROLLED_TOOL_ONLY",
        }

    def _coerce_diagnostic_result(self, value: Any) -> FailureDiagnosticResult:
        """兼容确定性测试替身的映射返回，同时丢弃原始日志字段。"""

        if isinstance(value, FailureDiagnosticResult):
            return value
        if not isinstance(value, Mapping):
            raise TypeError("diagnostic result must be a mapping")
        facts = _lookup(value, "facts", "failureFacts", "failure_facts", "diagnosticFacts")
        if not isinstance(facts, Mapping):
            facts = {}
        return FailureDiagnosticResult(
            failure_code=str(_lookup(value, "failureCode", "failure_code", "errorCode", "error_code") or "")
            or None,
            failure_reason=str(_lookup(value, "failureReason", "failure_reason", "reason") or ""),
            facts=facts,
            log_references=_unique_text(
                _lookup(value, "logReferences", "log_references", "logIds", "log_ids") or ()
            ),
            evidence_references=_unique_text(
                _lookup(value, "evidenceReferences", "evidence_references") or ()
            ),
            log_summary=_lookup(value, "logSummary", "log_summary")
            if isinstance(_lookup(value, "logSummary", "log_summary"), Mapping)
            else {},
            public_summary=str(_lookup(value, "publicSummary", "public_summary") or ""),
            evidence_records=tuple(
                item
                for item in (_lookup(value, "evidenceRecords", "evidence_records") or ())
                if isinstance(item, Mapping)
            ),
        )

    def _coerce_model_output(self, value: Any) -> RecoveryPlanningModelOutput:
        """把模型适配器的映射返回转成统一建议合同。"""

        if isinstance(value, RecoveryPlanningModelOutput):
            return value
        if not isinstance(value, Mapping):
            raise TypeError("recovery model output must be a mapping")
        actions = _lookup(value, "actions", "repairActions", "repair_actions", "plans") or ()
        return RecoveryPlanningModelOutput(
            actions=_as_tuple(actions),
            public_summary=str(_lookup(value, "publicSummary", "public_summary", "summary") or ""),
            failure_reason=str(_lookup(value, "failureReason", "failure_reason") or ""),
            next_step=str(_lookup(value, "nextStep", "next_step") or ""),
            invocation_summary=(
                _lookup(value, "invocationSummary", "invocation_summary")
                if isinstance(_lookup(value, "invocationSummary", "invocation_summary"), Mapping)
                else {}
            ),
            requested_tool_names=_unique_text(
                _lookup(value, "requestedToolNames", "requested_tool_names") or ()
            ),
            requested_actions=_unique_text(
                _lookup(value, "requestedActions", "requested_actions") or ()
            ),
            rag_decision=str(_lookup(value, "ragDecision", "rag_decision") or "AUTO"),
            rag_reason=str(_lookup(value, "ragReason", "rag_reason") or ""),
            confidence=_lookup(value, "confidence", "modelConfidence", "model_confidence"),
            retrieval_decision=(
                str(_lookup(value, "retrievalDecision", "retrieval_decision"))
                if _lookup(value, "retrievalDecision", "retrieval_decision") is not None
                else None
            ),
            retrieval_strategy=str(
                _lookup(value, "retrievalStrategy", "retrieval_strategy") or "AUTO"
            ),
        )

    def _safe_invocation_summary(self, summary: Mapping[str, Any]) -> Mapping[str, Any]:
        """只保留模型 Provider 的低敏统计，禁止 prompt、响应正文和 endpoint 出现在结果中。"""

        safe: dict[str, Any] = {"rawModelOutputStored": False}
        for key, value in summary.items():
            if str(key) in self._SAFE_INVOCATION_KEYS:
                safe[str(key)] = _sanitize_value(value, public=True)
        safe.setdefault("invoked", True)
        return safe

    def _model_public_summary(self, output: RecoveryPlanningModelOutput, *, default: str) -> str:
        """选取模型低敏摘要；空摘要时使用确定性的用户说明。"""

        return _sanitize_text(output.public_summary).strip() or default

    def _diagnostic_tool_name(self, allowed_tool_names: Iterable[str]) -> str | None:
        """从委派白名单中找出只读失败诊断工具，兼容少量历史命名。"""

        allowed = tuple(str(name).strip() for name in allowed_tool_names if str(name).strip())
        for name in allowed:
            if name in self._DIAGNOSTIC_TOOL_NAMES:
                return name
        for name in allowed:
            normalized = name.lower()
            if "recovery" in normalized and any(token in normalized for token in ("diagnos", "log", "read")):
                return name
        return None

    def _is_read_only_tool_name(self, tool_name: str) -> bool:
        """判断工具名是否可以向规划模型展示为只读能力，而不是执行入口。"""

        normalized = str(tool_name or "").lower()
        return tool_name in self._DIAGNOSTIC_TOOL_NAMES or any(
            token in normalized for token in ("diagnos", "log.read", "metadata.read", "status.read")
        )

    def _completed_result(
        self,
        *,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        started_at: float,
        public_summary: str,
        structured_output: Mapping[str, Any],
        diagnostic: FailureDiagnosticResult,
        evidence_references: tuple[str, ...],
        tool_activities: tuple[SpecialistToolActivity, ...],
        model_invocation_summary: Mapping[str, Any],
    ) -> SpecialistTurnResult:
        """构造完成结果，并把失败原因、下一步、工具活动和证据放进统一合同。"""

        result = SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.COMPLETED,
            public_summary=_sanitize_text(public_summary),
            structured_output={
                **dict(structured_output),
                "failure": self._public_failure(diagnostic),
                "evidenceReferences": evidence_references,
            },
            evidence_references=evidence_references,
            tool_activities=tool_activities,
            model_invocation_summary=model_invocation_summary,
            duration_ms=self._elapsed_ms(started_at),
            control_plane_fact_binding=self._diagnostic_fact_binding(request, diagnostic),
        )
        self._emit(
            event_sink,
            request,
            action="RECOVERY_TURN_COMPLETED",
            status=result.status.value,
            public_summary=result.public_summary,
            attributes={"evidenceCount": len(evidence_references)},
        )
        return result

    def _waiting_result(
        self,
        *,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        started_at: float,
        public_summary: str,
        next_step: str,
        required_input_fields: tuple[str, ...],
        diagnostic: FailureDiagnosticResult,
        evidence_references: tuple[str, ...],
        tool_activities: tuple[SpecialistToolActivity, ...],
        model_invocation_summary: Mapping[str, Any] | None = None,
        structured_output: Mapping[str, Any] | None = None,
    ) -> SpecialistTurnResult:
        """构造等待结果，明确缺什么输入以及 Agent 尚未产生任何副作用。"""

        output = {
            **dict(structured_output or {}),
            "failure": self._public_failure(diagnostic),
            "nextStep": _sanitize_text(next_step),
            "executed": False,
            "evidenceReferences": evidence_references,
            "payloadPolicy": "LOW_SENSITIVE_RECOVERY_RESULT_ONLY",
        }
        result = SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.WAITING_FOR_INPUT,
            public_summary=_sanitize_text(public_summary),
            structured_output=output,
            evidence_references=evidence_references,
            tool_activities=tool_activities,
            model_invocation_summary=dict(model_invocation_summary or {}),
            required_input_fields=required_input_fields,
            duration_ms=self._elapsed_ms(started_at),
        )
        self._emit(
            event_sink,
            request,
            action="RECOVERY_TURN_WAITING",
            status=result.status.value,
            public_summary=result.public_summary,
            attributes={"requiredInputCount": len(required_input_fields)},
        )
        return result

    def _failed_result(
        self,
        *,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        started_at: float,
        error_code: str,
        public_summary: str,
        next_step: str,
        tool_activities: tuple[SpecialistToolActivity, ...],
        diagnostic: FailureDiagnosticResult | None = None,
        evidence_references: tuple[str, ...] = (),
        model_invocation_summary: Mapping[str, Any] | None = None,
        structured_output: Mapping[str, Any] | None = None,
    ) -> SpecialistTurnResult:
        """构造低敏失败结果，稳定区分技术失败、用户等待和审批阻断。"""

        output = {
            **dict(structured_output or {}),
            "failureReason": _sanitize_text(public_summary),
            "nextStep": _sanitize_text(next_step),
            "executed": False,
            "evidenceReferences": evidence_references,
            "payloadPolicy": "LOW_SENSITIVE_RECOVERY_RESULT_ONLY",
        }
        if diagnostic is not None:
            output["failure"] = self._public_failure(diagnostic)
        result = SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.FAILED,
            public_summary=_sanitize_text(public_summary),
            structured_output=output,
            evidence_references=evidence_references,
            tool_activities=tool_activities,
            model_invocation_summary=dict(model_invocation_summary or {}),
            error_code=error_code,
            duration_ms=self._elapsed_ms(started_at),
        )
        self._emit(
            event_sink,
            request,
            action="RECOVERY_TURN_FAILED",
            status=result.status.value,
            public_summary=result.public_summary,
            attributes={
                "errorCode": error_code,
                **{
                    key: output[key]
                    for key in ("modelFailureReasonCode", "modelFailureSource")
                    if key in output
                },
            },
        )
        return result

    def _emit(
        self,
        event_sink: SpecialistEventSink | None,
        request: SpecialistTurnRequest,
        *,
        action: str,
        status: str,
        public_summary: str,
        attributes: Mapping[str, Any] | None = None,
    ) -> None:
        """发布低敏恢复进度事件，并隔离前端或日志旁路异常。

        事件只包含稳定动作、状态、计数和错误码，不包含 objective、模型输入、动作参数、日志
        正文、SQL 或审批原文。sink 断线不能改变已经完成的诊断或执行结果。
        """

        if event_sink is None:
            return
        safe_attributes = {
            str(key): _sanitize_value(value, public=True)
            for key, value in (attributes or {}).items()
            if _normalized_key(key)
            not in {
                "objective",
                "prompt",
                "sql",
                "statement",
                "arguments",
                "parameters",
                "approvalfact",
            }
        }
        event = {
            "eventType": "SPECIALIST_ACTION",
            "agentId": self._agent_id,
            "agentRole": self.role.value,
            "turnId": request.turn_id,
            "runId": request.run_id,
            "action": action,
            "status": status,
            "publicSummary": _sanitize_text(public_summary),
            "attributes": safe_attributes,
            "payloadPolicy": self._EVENT_PAYLOAD_POLICY,
        }
        try:
            event_sink(event)
        except Exception:
            return

    @staticmethod
    def _elapsed_ms(started_at: float) -> int:
        """使用单调时钟计算非负耗时，避免系统时钟回拨污染工具活动。"""

        return max(0, int((time.perf_counter() - started_at) * 1_000))


def compute_action_fingerprint(actions: Iterable[RecoveryAction]) -> str:
    """为一组规范化动作生成稳定 SHA-256 指纹。

    指纹包含动作类型、工具名、参数字段和值、原值/建议值和证据引用；因此批准事实不能只
    绑定“恢复 Agent”或“任务 ID”，而必须绑定用户实际看到并批准的完整动作集合。
    """

    material = []
    for action in actions:
        if isinstance(action, RecoveryAction):
            material.append(
                {
                    "actionId": action.action_id,
                    "actionType": action.action_type,
                    "toolName": action.tool_name,
                    "arguments": _canonicalize(action.arguments),
                    "reason": _canonicalize(action.reason),
                    "originalValues": _canonicalize(action.original_values),
                    "proposedValues": _canonicalize(action.proposed_values),
                    "evidenceReferences": tuple(action.evidence_references),
                    "classification": action.classification.value,
                }
            )
        elif isinstance(action, Mapping):
            material.append(_canonicalize(action))
        else:
            material.append(_canonicalize(str(action)))
    encoded = json.dumps(material, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return f"sha256:{hashlib.sha256(encoded.encode('utf-8')).hexdigest()}"


build_action_fingerprint = compute_action_fingerprint


def _lookup(mapping: Mapping[str, Any] | None, *names: str) -> Any:
    """按精确名和大小写/下划线不敏感名读取映射字段，兼容 Python 与 Java 命名。"""

    if not isinstance(mapping, Mapping):
        return None
    for name in names:
        if name in mapping:
            return mapping[name]
    normalized_names = {_normalized_key(name) for name in names}
    for key, value in mapping.items():
        if _normalized_key(key) in normalized_names:
            return value
    return None


def _strict_bool(value: Any, *, default: bool) -> bool:
    """解析 coordinator 拥有的控制标志，不接受任意真值文本。"""

    if value is None:
        return default
    if not isinstance(value, bool):
        raise ValueError("Recovery decision control boolean must be true or false")
    return value


def _positive_decimal_reference(value: Any) -> str | None:
    """把 Java 资源 ID 收敛为正十进制字符串，拒绝模型文本、UUID 和通配符。

    该值只用于内部诊断事实绑定；返回字符串可以避免 Python/Java 数字宽度差异，同时严格的
    ``isdigit`` 与正数检查保证 Bridge 不会把自由文本资源定位带进 ToolPlan。
    """

    text = str(value or "").strip()
    if not text.isdigit():
        return None
    try:
        return text if int(text) > 0 else None
    except ValueError:
        return None


def _positive_int(value: Any) -> int:
    """返回非负控制面计数器，不抛出异常。"""

    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def _normalized_key(value: Any) -> str:
    """将字段名归一为只含小写字母和数字的比较键。"""

    return re.sub(r"[^a-z0-9]", "", str(value or "").lower())


def _action_key(value: Any) -> str:
    """将动作类型转换为大写下划线语义，便于确定性风险匹配。"""

    return re.sub(r"[^A-Z0-9]+", "_", str(value or "").upper()).strip("_")


def _has_project_scope(value: Any) -> bool:
    """只接受具体项目标识，拒绝空值和可扩大到租户范围的通配符。

    ``SpecialistDelegationScope`` 为兼容少数租户级只读能力仍允许 ``project_id`` 为空，
    但同步故障、运行日志、脏数据和表结构修复全部属于项目资源。Recovery 必须在任何
    控制面读取前执行这道更严格的领域校验。
    """

    normalized = str(value or "").strip()
    return bool(normalized) and normalized not in {"*", "0"}


def _bounded_text(value: Any, limit: int) -> str:
    """把外部文本限制在低敏摘要允许的长度，并对常见凭据赋值做最小遮罩。"""

    if value is None:
        return ""
    text = str(value)
    text = re.sub(
        r"(?i)(password|api[_-]?key|secret|token|authorization)\s*[:=]\s*[^\s,;]+",
        r"\1=[REDACTED]",
        text,
    )
    return text[: max(0, int(limit))]


def _sanitize_text(value: Any) -> str:
    """生成用户可读的低敏短文本，不传播明显的原始 SQL 或凭据值。"""

    text = _bounded_text(value, 1_200).strip()
    if _looks_like_sql_text(text):
        return "[REDACTED SQL]"
    return text


def _looks_like_sql_text(value: Any) -> bool:
    """识别明显的 SQL 正文，而不误伤“删除脏数据”等普通动作说明。"""

    text = str(value or "").strip()
    return bool(
        re.search(r"(?i)\bsql\b", text)
        or re.match(r"(?is)^select\b.{0,200}\bfrom\b", text)
        or re.match(r"(?is)^insert\b.{0,200}\binto\b", text)
        or re.match(r"(?is)^update\b.{0,200}\bset\b", text)
        or re.match(r"(?is)^delete\b.{0,200}\bfrom\b", text)
        or re.match(r"(?is)^alter\s+table\b", text)
        or re.match(r"(?is)^drop\s+table\b", text)
        or re.match(r"(?is)^truncate\s+table\b", text)
    )


def _sanitize_value(value: Any, *, public: bool = False, depth: int = 0) -> Any:
    """递归裁剪映射、列表和文本，统一保护凭据、SQL、样本行和过深对象。"""

    if depth > 5:
        return "[TRUNCATED]"
    if isinstance(value, Mapping):
        sanitized: dict[str, Any] = {}
        for index, (key, item) in enumerate(value.items()):
            if index >= 48:
                break
            key_text = _bounded_text(key, 120)
            normalized = _normalized_key(key)
            if any(part in normalized for part in RecoverySpecialistAgent._SECRET_KEY_PARTS):
                sanitized[key_text] = "[REDACTED]"
            elif any(part in normalized for part in RecoverySpecialistAgent._SENSITIVE_CONTENT_KEY_PARTS):
                sanitized[key_text] = "[REDACTED]"
            else:
                sanitized[key_text] = _sanitize_value(item, public=public, depth=depth + 1)
        return sanitized
    if isinstance(value, (list, tuple, set)):
        return tuple(_sanitize_value(item, public=public, depth=depth + 1) for item in list(value)[:32])
    if isinstance(value, str):
        return _sanitize_text(value)
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return _sanitize_text(value)


def _sanitize_mapping(value: Mapping[str, Any] | None) -> Mapping[str, Any]:
    """返回普通字典形式的低敏映射，便于 dataclass 和 JSON 序列化。"""

    if not isinstance(value, Mapping):
        return {}
    sanitized = _sanitize_value(value, public=False)
    return sanitized if isinstance(sanitized, Mapping) else {}


def _canonicalize(value: Any) -> Any:
    """把动作值转换成 JSON 可编码、键顺序稳定的指纹材料。"""

    if isinstance(value, Mapping):
        return {
            str(key): _canonicalize(item)
            for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
        }
    if isinstance(value, (list, tuple, set)):
        return tuple(_canonicalize(item) for item in value)
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return _bounded_text(value, 500)


def _unique_text(values: Iterable[Any] | Any) -> tuple[str, ...]:
    """将引用或工具名规范为有限、去重、非空的文本元组。"""

    if values is None:
        return ()
    if isinstance(values, str):
        values = (values,)
    try:
        candidates = values
    except TypeError:
        candidates = (values,)
    result: list[str] = []
    for value in candidates:
        text = _bounded_text(value, 240).strip()
        if text and text not in result:
            result.append(text)
        if len(result) >= 64:
            break
    return tuple(result)


def _as_tuple(value: Any) -> tuple[Any, ...]:
    """把单个动作、动作映射或动作序列转换成统一元组。"""

    if value is None:
        return ()
    if isinstance(value, Mapping) or isinstance(value, str):
        return (value,)
    if isinstance(value, (list, tuple, set)):
        return tuple(value)
    return (value,)


__all__ = [
    "CONTROLLED_RECOVERY_TOOL_CODE",
    "FAILURE_DIAGNOSTIC_TOOL_CODE",
    "FailureDiagnosticClient",
    "FailureDiagnosticRequest",
    "FailureDiagnosticResult",
    "RecoveryAction",
    "RecoveryActionCategory",
    "RecoveryActionClass",
    "RecoveryActionRisk",
    "RecoveryPlanningModel",
    "RecoveryPlanningModelInput",
    "RecoveryPlanningModelOutput",
    "RecoverySpecialistAgent",
    "build_action_fingerprint",
    "compute_action_fingerprint",
]
