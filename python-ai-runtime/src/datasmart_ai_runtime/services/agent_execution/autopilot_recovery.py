"""受治理的 Data Sync Autopilot Recovery 编排服务。

该服务承接 Java agent-runtime 已经验证过的失败触发事实，并把一次自动恢复规划拆成可审计的
LangGraph checkpoint：先让 RECOVERY_AGENT 读取结构化诊断，再尊重模型的 SEARCH/SKIP 决策；
只有模型选择搜索时才调用 RAG，取得引用后再执行第二个 Recovery turn。Python 始终只返回建议，
真正的授权、风险、幂等和副作用执行仍由 Java 与 data-sync 双重策略层完成。
"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from datasmart_ai_runtime.services.agent_execution.langgraph_durable_checkpointer import (
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    LangGraphDurableCheckpointerService,
)
from datasmart_ai_runtime.services.agent_execution.autopilot_recovery_investigation import (
    AutopilotRecoveryInvestigationCollaborator,
)
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnBudget,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_registry import SpecialistAgentRegistry
from datasmart_ai_runtime.services.multi_agent.specialists.recovery_agent import (
    FAILURE_DIAGNOSTIC_TOOL_CODE,
)
from datasmart_ai_runtime.services.rag.models import RagPipelineResult, RagQuery
from datasmart_ai_runtime.services.rag.pipeline import RagPipeline


_SHA_256 = re.compile(r"^(?:sha256:)?[0-9a-fA-F]{64}$")
_HEX_64 = re.compile(r"^[0-9a-fA-F]{64}$")
_SAFE_CODE = re.compile(r"^[A-Z0-9_.:\-]{1,96}$")
_AUTOMATIC_ACTION_ALIASES = {
    # 这是 Java 和 data-sync 实际执行的规范动作。它仍受下方
    # _has_explicit_transient_retry_facts 保护；仅将动作命名为
    # RETRY_EXECUTION 永远不足以授予无人值守执行权限。
    "RETRY_EXECUTION": "RETRY_EXECUTION",
}
# 面向模型的名称有意与自动动作表分开维护。只有通过确定性的瞬态失败事实门禁后，
# 它才是别名，因此不能因自然语言中的动作相似性而将 schema 错误转换为无人值守重试。
_RETRY_ACTION_ALIASES = {"RETRY_FAILED_OBJECTS": "RETRY_EXECUTION"}
# 这些名称描述的是证据收集，而非 data-sync 副作用。它们保留在受治理目录中，
# 以便一致地分类模型响应，随后在候选选择前移除。因此平台会记录稳定的关注结果，
# 而不会把无害诊断报告成未知写动作。两种 READ_ONLY 拼写用于兼容旧 Agent 输入。
_SEARCH_ACTIONS = frozenset({"SEARCH_RECOVERY_KNOWLEDGE", "DIAGNOSE", "READ_LOGS"})
_READ_ONLY_ACTION_ALIASES = frozenset({"READ_ONLY_DIAGNOSIS", "READ_ONLY_DIAGNOSTIC"})
_NON_EXECUTING_ACTIONS = frozenset({*_SEARCH_ACTIONS, *_READ_ONLY_ACTION_ALIASES})
# APPLY_QUARANTINE 有意不属于 ``_AUTOMATIC_ACTION_ALIASES``：该表在任何
# receipt 专属校验前都必须安全。这个独立集合明确了更严格的规则：仅在
# ``_validated_quarantine_preview`` 接受当前执行的真实 Java preview receipt 后，apply 动作才可自动执行。
_RECEIPT_BOUND_AUTOMATIC_ACTIONS = frozenset({"APPLY_QUARANTINE"})
_KNOWN_GOVERNED_ACTIONS = frozenset(
    {
        *_AUTOMATIC_ACTION_ALIASES,
        *_RETRY_ACTION_ALIASES,
        "RECONNECT_DATASOURCE",
        "REFRESH_METADATA",
        # APPLY_QUARANTINE 被有意列入目录，但不自动执行。Python 只能返回
        # 绑定 receipt 的候选；Java 始终是唯一写入方，并在之后启动有界重试。
        "APPLY_QUARANTINE",
        "PREVIEW_SCHEMA_REPAIR",
        "PREVIEW_CREATE_TARGET_TABLE",
        "REPLAY_DIRTY_RECORDS",
        "REPLAY_FROM_CHECKPOINT",
        "REPLAY_FAILED_SHARDS",
        "RESUME_FROM_CHECKPOINT",
        "ALTER_TARGET_SCHEMA",
        "CREATE_TARGET_TABLE",
        "RENAME_TASK",
        "CHANGE_SCHEMA",
        "CHANGE_CREDENTIAL",
        "DELETE_DATA",
        "OVERWRITE_TARGET",
        "EXPAND_DATA_SCOPE",
        *_NON_EXECUTING_ACTIONS,
    }
)
_NON_AUTOMATIC_GOVERNED_ACTIONS = frozenset(
    {
        "RECONNECT_DATASOURCE",
        "REFRESH_METADATA",
        "PREVIEW_SCHEMA_REPAIR",
        "PREVIEW_CREATE_TARGET_TABLE",
        "REPLAY_DIRTY_RECORDS",
        "REPLAY_FROM_CHECKPOINT",
        "REPLAY_FAILED_SHARDS",
        "RESUME_FROM_CHECKPOINT",
        "ALTER_TARGET_SCHEMA",
        "CREATE_TARGET_TABLE",
        "RENAME_TASK",
        "CHANGE_SCHEMA",
        "CHANGE_CREDENTIAL",
        "DELETE_DATA",
        "OVERWRITE_TARGET",
        "EXPAND_DATA_SCOPE",
    }
)
# 这些代码只描述本轮已经完成的证据收集或诊断，不是等待 Java 执行的恢复副作用。
# 判断只能基于平台固定代码，不能采信模型提供的 classification/requiresApproval 字段；否则模型可能把
# CHANGE_SCHEMA 等真实写动作标成只读，从而错误地绕过人工审批。
# 多候选输出不能通常由规则层替模型决定先后顺序。唯一例外是“全部候选都是平台注册的调查动作”：
# 此时平台可以依据固定风险目录选出一个最小、只读、幂等且无需审批的下一步，让真实工具回执成为下一轮证据。
# 当前 Java auto-execute-sync 只满足 PREVIEW_QUARANTINE；schema/create preview 虽然只读，但注册风险或幂等属性
# 不满足无人值守执行门槛，因此不能加入本表，也不能通过模型自报 readOnly/idempotent 绕过目录。
_AUTONOMOUS_INVESTIGATION_PRIORITY = {
    "PREVIEW_QUARANTINE": 10,
}
_PLATFORM_INVESTIGATION_ACTIONS = frozenset(
    {
        "PREVIEW_QUARANTINE",
        "PREVIEW_SCHEMA_REPAIR",
        "PREVIEW_CREATE_TARGET_TABLE",
    }
)
_QUARANTINE_PREVIEW_TOOL = "sync.dirty-record.quarantine.preview"
_QUARANTINE_PREVIEW_OUTPUT_FIELDS = (
    "taskId",
    "executionId",
    "selectedCount",
    "eligibleCount",
    "confirmationDigest",
    "selectedSampleIds",
    "issueCodes",
    "auditId",
    "runId",
    "outputRef",
)

AUTOPILOT_RECOVERY_SPECIALIST_FACT_NOT_DURABLE = (
    "AUTOPILOT_RECOVERY_SPECIALIST_FACT_NOT_DURABLE"
)


class AutopilotRecoveryDurableFactError(RuntimeError):
    """表示自治 Recovery 候选缺少可证明的 Java durable specialist fact。

    这是一个技术错误而不是业务上的 ``ATTENTION_REQUIRED``。Java Kafka consumer
    只有在 Python HTTP 正常返回后才会继续写入 data-sync 的终态回执；如果这里抛出异常，
    HTTP 调用失败，Kafka offset 不能被提交，事件会进入既有的有界重试/DLT 流程。异常只
    暴露固定机器码，避免把内部 HTTP、响应正文或凭据带出 Recovery 边界。
    """

    def __init__(self, code: str = AUTOPILOT_RECOVERY_SPECIALIST_FACT_NOT_DURABLE) -> None:
        """用稳定错误码创建可重试的技术异常，不接收底层异常文本。"""

        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class AutopilotRecoveryRequest:
    """Java 控制面传给 Python 的低敏、不可扩权恢复请求。

    字段全部来自 Kafka 触发事件和 Java 重新加载的 session/run。调用方不能通过自然语言目标覆盖
    tenant、project、delegation、循环预算或错误指纹。``workspace_key`` 由 Java 会话补充，用于 RAG
    的项目内二级隔离；它不从模型输出或失败日志中推断。
    """

    event_id: str
    root_session_id: str
    root_run_id: str
    tenant_id: str
    application_id: str
    project_id: str
    user_id: str
    actor_id: str
    agent_id: str
    delegation_id: str
    workspace_key: str
    sync_task_id: str
    root_execution_id: str
    current_execution_id: str
    cycle: int
    max_recovery_cycles: int
    deadline_at: str
    error_fingerprint: str
    repeated_error_count: int
    previous_repair_fingerprint: str | None = None
    issue_codes: tuple[str, ...] = ()
    triggered_at: str | None = None

    def __post_init__(self) -> None:
        """在调用任何模型、诊断 API 或 RAG 前完成格式与预算校验。

        这里的校验不是 Java 授权校验的替代，而是第二道边界：即使内部 HTTP 请求被错误拼装，Python
        也不会把空项目、过期 deadline、非法数据库 ID 或任意日志文本带进 Specialist 工具。
        """

        required_text = {
            "event_id": self.event_id,
            "root_session_id": self.root_session_id,
            "root_run_id": self.root_run_id,
            "tenant_id": self.tenant_id,
            "application_id": self.application_id,
            "project_id": self.project_id,
            "user_id": self.user_id,
            "actor_id": self.actor_id,
            "agent_id": self.agent_id,
            "delegation_id": self.delegation_id,
            "workspace_key": self.workspace_key,
            "sync_task_id": self.sync_task_id,
            "root_execution_id": self.root_execution_id,
            "current_execution_id": self.current_execution_id,
            "deadline_at": self.deadline_at,
        }
        missing = tuple(name for name, value in required_text.items() if not str(value or "").strip())
        if missing:
            raise ValueError(f"Autopilot recovery request 缺少字段：{', '.join(missing)}")
        for name, value in required_text.items():
            object.__setattr__(self, name, str(value).strip())

        for name in ("tenant_id", "application_id", "project_id", "sync_task_id", "root_execution_id", "current_execution_id"):
            value = getattr(self, name)
            if not value.isdecimal() or int(value) <= 0:
                raise ValueError(f"{name} 必须是正整数标识")
        if self.cycle < 1 or self.max_recovery_cycles < 1 or self.cycle > self.max_recovery_cycles:
            raise ValueError("Autopilot recovery cycle 超出授权预算")
        if self.repeated_error_count < 0:
            raise ValueError("repeated_error_count 不能为负数")
        if not _SHA_256.fullmatch(self.error_fingerprint):
            raise ValueError("error_fingerprint 必须是 SHA-256")
        object.__setattr__(self, "error_fingerprint", _fingerprint_hex(self.error_fingerprint))
        if self.previous_repair_fingerprint:
            if not _SHA_256.fullmatch(self.previous_repair_fingerprint):
                raise ValueError("previous_repair_fingerprint 必须是 SHA-256")
            object.__setattr__(
                self,
                "previous_repair_fingerprint",
                _fingerprint_hex(self.previous_repair_fingerprint),
            )

        deadline = _parse_time(self.deadline_at, "deadline_at")
        if deadline <= datetime.now(timezone.utc):
            raise ValueError("Autopilot recovery deadline 已经过期")
        if self.triggered_at:
            _parse_time(self.triggered_at, "triggered_at")
        normalized_codes = tuple(
            dict.fromkeys(
                str(value).strip().upper()
                for value in self.issue_codes
                if _SAFE_CODE.fullmatch(str(value).strip().upper())
            )
        )[:20]
        object.__setattr__(self, "issue_codes", normalized_codes)

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "AutopilotRecoveryRequest":
        """把 Java JSON 字段映射为领域请求，并拒绝隐式默认的安全字段。

        循环次数、作用域、指纹和 deadline 不设置宽松默认值，因为缺失时继续运行会把传输错误误当成
        授权。只有 ``issueCodes``、上一轮指纹和触发时间属于可选诊断元数据。
        """

        if not isinstance(payload, Mapping):
            raise ValueError("Autopilot recovery payload 必须是 JSON object")
        return cls(
            event_id=str(payload.get("eventId") or ""),
            root_session_id=str(payload.get("rootSessionId") or ""),
            root_run_id=str(payload.get("rootRunId") or ""),
            tenant_id=str(payload.get("tenantId") or ""),
            application_id=str(payload.get("applicationId") or ""),
            project_id=str(payload.get("projectId") or ""),
            user_id=str(payload.get("userId") or ""),
            actor_id=str(payload.get("actorId") or ""),
            agent_id=str(payload.get("agentId") or ""),
            delegation_id=str(payload.get("delegationId") or ""),
            workspace_key=str(payload.get("workspaceKey") or ""),
            sync_task_id=str(payload.get("syncTaskId") or ""),
            root_execution_id=str(payload.get("rootExecutionId") or ""),
            current_execution_id=str(payload.get("currentExecutionId") or ""),
            cycle=int(payload.get("cycle") or 0),
            max_recovery_cycles=int(payload.get("maxRecoveryCycles") or 0),
            deadline_at=str(payload.get("deadlineAt") or ""),
            error_fingerprint=str(payload.get("errorFingerprint") or ""),
            repeated_error_count=int(payload.get("repeatedErrorCount") or 0),
            previous_repair_fingerprint=(
                str(payload.get("previousRepairFingerprint"))
                if payload.get("previousRepairFingerprint") is not None
                else None
            ),
            issue_codes=tuple(payload.get("issueCodes") or ()),
            triggered_at=str(payload.get("triggeredAt")) if payload.get("triggeredAt") else None,
        )


@dataclass(frozen=True)
class AutopilotRecoveryResult:
    """返回 Java 策略层的低敏恢复候选或明确阻断结果。"""

    event_id: str
    status: str
    reason_code: str
    action: str | None = None
    risk_level: str | None = None
    idempotent: bool = False
    repair_fingerprint: str | None = None
    error_fingerprint: str | None = None
    confidence: float = 0.0
    evidence_available: bool = False
    evidence_audit: Mapping[str, Any] = field(default_factory=dict)
    evidence_scope: Mapping[str, Any] = field(default_factory=dict)
    # 只有 APPLY_QUARANTINE 候选会携带这个绑定 receipt 的映射。将其与
    # evidenceAudit 分离，可避免将 Java 工具输出事实误视为模型生成的证据。
    quarantine_preview: Mapping[str, Any] = field(default_factory=dict)
    # 解释 RETRY_EXECUTION 为何符合条件的确定性事实。Java 会在创建恢复案例前
    # 重新校验这份精简投影。
    autopilot_recovery_facts: Mapping[str, Any] = field(default_factory=dict)
    retrieval_decision: str = "SKIP"
    retrieval_strategy: str = "STRUCTURED_DIAGNOSTIC"
    retrieval_audit: Mapping[str, Any] = field(default_factory=dict)
    strategy_changed: bool = False
    checkpoint_thread_id: str | None = None

    def to_summary(self) -> dict[str, Any]:
        """生成 Java 可反序列化的稳定响应，不返回 RAG 正文、日志或模型原始输出。

        中文说明：``quarantinePreview`` 是 APPLY_QUARANTINE 唯一允许携带的 Java preview receipt 投影；
        它不含 ``operationState``、message、原始样本内容或任意模型参数。Java 必须用其中的 audit/run/output
        引用重新读取并验证 preview，再执行 apply 和其后的自动 retry，Python 在本方法前后都不会写 data-sync。

        English: the summary exposes a narrow, receipt-bound ``quarantinePreview`` only for an apply
        candidate.  It is evidence for Java to verify, never an execution command or a substitute for
        Java/data-sync authorization, idempotency, and bounded-retry policy.
        """

        return {
            "schemaVersion": "datasmart.autopilot.recovery-candidate.v1",
            "eventId": self.event_id,
            "status": self.status,
            "reasonCode": self.reason_code,
            "action": self.action,
            "riskLevel": self.risk_level,
            "idempotent": self.idempotent,
            "repairFingerprint": self.repair_fingerprint,
            "errorFingerprint": self.error_fingerprint,
            "confidence": round(max(0.0, min(1.0, self.confidence)), 6),
            "evidenceAvailable": self.evidence_available,
            "evidenceAudit": dict(self.evidence_audit),
            "evidenceScope": dict(self.evidence_scope),
            "quarantinePreview": _quarantine_preview_summary(self.quarantine_preview),
            "autopilotRecoveryFacts": _autopilot_recovery_facts_summary(self.autopilot_recovery_facts),
            "retrievalDecision": self.retrieval_decision,
            "retrievalStrategy": self.retrieval_strategy,
            "retrievalAudit": dict(self.retrieval_audit),
            "strategyChanged": self.strategy_changed,
            "checkpointThreadId": self.checkpoint_thread_id,
            "payloadPolicy": "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY",
        }

    @classmethod
    def from_summary(cls, summary: Mapping[str, Any]) -> "AutopilotRecoveryResult":
        """从 durable checkpoint 中保存的公开响应合同重建终态结果。

        中文说明：checkpoint 只保存 ``to_summary`` 已经允许返回给 Java 的低敏字段，因此重放时不需要
        重新访问模型、RAG 或 Java preview。这里仍逐字段收窄类型并重新应用 quarantine preview 白名单，
        防止数据库中的旧数据或人工修复数据把任意嵌套对象扩成新的响应通道。

        该解析器有意比通用的 ``**mapping`` 构造方式更严格：它只接受带版本的公开合同，并重建不可变映射，
        从而让重放结果保持确定且有界。
        """

        if not isinstance(summary, Mapping):
            raise ValueError("Autopilot recovery terminalResult must be a mapping")
        if str(summary.get("schemaVersion") or "") != "datasmart.autopilot.recovery-candidate.v1":
            raise ValueError("Autopilot recovery terminalResult schema is unsupported")
        event_id = str(summary.get("eventId") or "").strip()
        status = _code(summary.get("status"), "")
        reason_code = _code(summary.get("reasonCode"), "")
        if not event_id or not status or not reason_code:
            raise ValueError("Autopilot recovery terminalResult is incomplete")
        action = _optional_code(summary.get("action"))
        risk_level = _optional_code(summary.get("riskLevel"))
        repair_fingerprint = _optional_fingerprint(summary.get("repairFingerprint"))
        error_fingerprint = _optional_fingerprint(summary.get("errorFingerprint"))
        return cls(
            event_id=event_id,
            status=status,
            reason_code=reason_code,
            action=action,
            risk_level=risk_level,
            idempotent=summary.get("idempotent") is True,
            repair_fingerprint=repair_fingerprint,
            error_fingerprint=error_fingerprint,
            confidence=_confidence(summary.get("confidence")),
            evidence_available=summary.get("evidenceAvailable") is True,
            evidence_audit=_mapping_copy(summary.get("evidenceAudit")),
            evidence_scope=_mapping_copy(summary.get("evidenceScope")),
            quarantine_preview=_quarantine_preview_summary(summary.get("quarantinePreview")),
            autopilot_recovery_facts=_autopilot_recovery_facts_summary(summary.get("autopilotRecoveryFacts")),
            retrieval_decision=_code(summary.get("retrievalDecision"), "SKIP"),
            retrieval_strategy=_code(summary.get("retrievalStrategy"), "STRUCTURED_DIAGNOSTIC"),
            retrieval_audit=_mapping_copy(summary.get("retrievalAudit")),
            strategy_changed=summary.get("strategyChanged") is True,
            checkpoint_thread_id=_optional_text(summary.get("checkpointThreadId"), 512),
        )


class AutopilotRecoveryCoordinator:
    """协调 Recovery Specialist、RAG 工具和 durable checkpoint 的两轮恢复决策。

    该类没有 data-sync 写客户端，因此无法自行 retry、replay 或修改 schema。即使模型返回了可执行动作，
    协调器也只把规范化候选交给 Java；Java 必须重新验证 session/run/authorization、证据、风险和
    data-sync 本地策略后，才能触发任何副作用。
    """

    def __init__(
        self,
        *,
        specialist_registry: SpecialistAgentRegistry,
        rag_pipeline: RagPipeline,
        checkpointer: LangGraphDurableCheckpointerService,
        result_sink: Callable[[SpecialistTurnRequest, SpecialistTurnResult], Any] | None = None,
        investigation_collaborator: AutopilotRecoveryInvestigationCollaborator | None = None,
    ) -> None:
        """保存启动期装配的共享依赖，避免每个请求创建独立 Agent 或知识库实例。"""

        self._specialist_registry = specialist_registry
        self._rag_pipeline = rag_pipeline
        self._checkpointer = checkpointer
        self._result_sink = result_sink
        self._investigation_collaborator = investigation_collaborator

    def plan(self, request: AutopilotRecoveryRequest) -> AutopilotRecoveryResult:
        """执行一次受治理、证据驱动且有界的恢复规划。

        中文说明：第一轮始终使用结构化诊断；模型自主选择 ``SKIP`` 时评估当前候选，选择 ``SEARCH`` 时
        最多执行一次 RAG 并把引用交给后续 Recovery turn。模型提出 ``PREVIEW_QUARANTINE`` 时，Python 只经
        Java 控制面安排一次只读 preview；后续模型只有看到真实 receipt 才可能提出 ``APPLY_QUARANTINE``。
        apply 仍只是返回 Java 的候选，绝不从这里调用 data-sync 写接口。

        English: SEARCH and preview are independent one-shot evidence expansions.  A repeated request for either
        expansion stops with a durable attention result, so the loop cannot become an unbounded model/RAG/tool
        cycle.  Every transition is checkpointed for replay-safe audit across runtime restarts.
        """

        if not isinstance(request, AutopilotRecoveryRequest):
            raise TypeError("request 必须是 AutopilotRecoveryRequest")
        thread_id = f"autopilot-recovery:{request.event_id}"
        replayed = self._replay_terminal_result(request, thread_id)
        if replayed is not None:
            return replayed
        # 终态关注结果可在不再次联系 Java 的情况下重放，但每次新规划尝试在调用 Specialist 前
        # 都必须具备 durable-fact sink。缺少此保护时，完全有效的模型响应可能被误认为可审计的自治决策，
        # Kafka consumer 还可能确认其 Specialist 证据仅存在于 Python 内存中的事件。
        if self._result_sink is None:
            raise AutopilotRecoveryDurableFactError()
        self._record_checkpoint(
            request,
            thread_id=thread_id,
            node_name="autopilot_recovery_started",
            status=LangGraphCheckpointStatus.RUNNING,
            next_nodes=("recovery_specialist_diagnose",),
            state={"cycle": request.cycle, "repeatedErrorCount": request.repeated_error_count},
            summary="Autopilot recovery trigger accepted for governed diagnosis.",
        )

        # 将每个登记 receipt 限定在本次规划尝试中。共享 Specialist coordinator 对普通规划
        # 有意采取 fail-open 策略，因此 Recovery 必须在此边界显式实施更严格的确认规则，
        # 而不能从 Specialist 业务结果推断持久性。
        fact_receipts: list[tuple[str, Any]] = []
        current = self._run_recovery_turn(
            request,
            phase="diagnose",
            knowledge_summary=None,
            investigation_summary=None,
            investigation_references=(),
            fact_receipts=fact_receipts,
        )
        searched = False
        investigated = False
        retrieval_audit: dict[str, Any] = {}
        retrieval_strategy = "STRUCTURED_DIAGNOSTIC"
        knowledge_summary: Mapping[str, Any] | None = None
        investigation_summary: Mapping[str, Any] | None = None
        investigation_references: tuple[str, ...] = ()
        phase = "diagnose"

        # 单次规划器调用有两种彼此独立且有界的证据扩展：至多一次知识检索和至多一次受 Java 治理的调查预览。
        # 模型选择二者的顺序。结合外层 maxRecoveryCycles/deadline，这允许自治收集证据而不会形成无界内循环。
        while True:
            output = dict(current.structured_output)
            self._record_specialist_checkpoint(request, thread_id, current, output, phase=phase)
            if current.status != SpecialistTurnStatus.COMPLETED:
                return self._blocked_result(request, current, thread_id, retrieval_audit=retrieval_audit)

            requested_retrieval = _code(
                output.get("retrievalDecision") or output.get("ragDecision"),
                "SKIP",
            )
            requested_strategy = _code(output.get("retrievalStrategy"), retrieval_strategy)
            if requested_retrieval == "SEARCH":
                if searched:
                    result = self._attention(
                        request,
                        thread_id,
                        "RECOVERY_RETRIEVAL_LOOP_LIMIT_REACHED",
                        output=output,
                        retrieval_decision="SEARCH",
                        retrieval_strategy=requested_strategy,
                        retrieval_audit=retrieval_audit,
                    )
                    self._record_terminal_checkpoint(request, thread_id, result)
                    return result
                if requested_strategy == "STRUCTURED_DIAGNOSTIC":
                    result = self._attention(
                        request,
                        thread_id,
                        "STRUCTURED_DIAGNOSTIC_ALREADY_SATISFIED",
                        output=output,
                        retrieval_decision="SEARCH",
                        retrieval_strategy=requested_strategy,
                        retrieval_audit=retrieval_audit,
                    )
                    self._record_terminal_checkpoint(request, thread_id, result)
                    return result

                rag_result = self._search(request, output, requested_strategy)
                retrieval_audit = dict(rag_result.retrieval_summary)
                retrieval_strategy = requested_strategy
                searched = True
                self._record_checkpoint(
                    request,
                    thread_id=thread_id,
                    node_name="autopilot_recovery_search_completed",
                    status=LangGraphCheckpointStatus.RUNNING,
                    next_nodes=("recovery_specialist_decide",),
                    state={
                        "retrievalStrategy": retrieval_strategy,
                        "evidenceCount": int(retrieval_audit.get("evidenceCount") or 0),
                        "evidenceDigest": retrieval_audit.get("evidenceDigest"),
                    },
                    summary="Model-selected governed recovery search completed.",
                )
                if not rag_result.citations:
                    result = self._attention(
                        request,
                        thread_id,
                        "RECOVERY_SEARCH_EVIDENCE_NOT_FOUND",
                        output=output,
                        retrieval_decision="SEARCH",
                        retrieval_strategy=retrieval_strategy,
                        retrieval_audit=retrieval_audit,
                    )
                    self._record_terminal_checkpoint(request, thread_id, result)
                    return result
                knowledge_summary = self._knowledge_summary(rag_result)
                phase = "decide_after_search"
                current = self._run_recovery_turn(
                    request,
                    phase=phase,
                    knowledge_summary=knowledge_summary,
                    investigation_summary=investigation_summary,
                    investigation_references=investigation_references,
                    fact_receipts=fact_receipts,
                )
                continue

            governed_actions = self._governed_actions(output)
            governed_actions = self._select_single_investigation_action(governed_actions)
            if len(governed_actions) != 1:
                reason = (
                    self._no_governed_action_reason(output)
                    if not governed_actions
                    else "MULTIPLE_RECOVERY_ACTIONS_REQUIRE_REVIEW"
                )
                result = self._attention(
                    request,
                    thread_id,
                    reason,
                    output=output,
                    retrieval_decision="SEARCH" if searched else "SKIP",
                    retrieval_strategy=retrieval_strategy if searched else requested_strategy,
                    retrieval_audit=retrieval_audit,
                )
                self._record_terminal_checkpoint(request, thread_id, result)
                return result

            selected_action = _code(governed_actions[0].get("actionType"), "")
            if selected_action in _AUTONOMOUS_INVESTIGATION_PRIORITY:
                if investigated:
                    result = self._attention(
                        request,
                        thread_id,
                        "RECOVERY_INVESTIGATION_LOOP_LIMIT_REACHED",
                        output=output,
                        retrieval_decision="SEARCH" if searched else "SKIP",
                        retrieval_strategy=retrieval_strategy if searched else requested_strategy,
                        retrieval_audit=retrieval_audit,
                    )
                    self._record_terminal_checkpoint(request, thread_id, result)
                    return result
                if self._investigation_collaborator is None:
                    result = self._attention(
                        request,
                        thread_id,
                        "RECOVERY_INVESTIGATION_CONTROL_PLANE_UNAVAILABLE",
                        output=output,
                        retrieval_decision="SEARCH" if searched else "SKIP",
                        retrieval_strategy=retrieval_strategy if searched else requested_strategy,
                        retrieval_audit=retrieval_audit,
                    )
                    self._record_terminal_checkpoint(request, thread_id, result)
                    return result
                investigation = self._investigation_collaborator.investigate(
                    request=request,
                    specialist_result=current,
                    action_type=selected_action,
                )
                if not investigation.completed:
                    result = self._attention(
                        request,
                        thread_id,
                        investigation.reason_code,
                        output=output,
                        retrieval_decision="SEARCH" if searched else "SKIP",
                        retrieval_strategy=retrieval_strategy if searched else requested_strategy,
                        retrieval_audit=retrieval_audit,
                    )
                    self._record_terminal_checkpoint(request, thread_id, result)
                    return result
                investigated = True
                investigation_summary = dict(investigation.evidence_summary)
                investigation_references = tuple(investigation.evidence_references)
                self._record_checkpoint(
                    request,
                    thread_id=thread_id,
                    node_name="autopilot_recovery_investigation_completed",
                    status=LangGraphCheckpointStatus.RUNNING,
                    next_nodes=("recovery_specialist_decide",),
                    state={
                        "actionType": selected_action,
                        "evidenceReferenceCount": len(investigation_references),
                        "payloadPolicy": investigation_summary.get("payloadPolicy"),
                    },
                    summary="Java-governed autonomous recovery investigation completed.",
                )
                phase = "decide_after_investigation"
                current = self._run_recovery_turn(
                    request,
                    phase=phase,
                    knowledge_summary=knowledge_summary,
                    investigation_summary=investigation_summary,
                    investigation_references=investigation_references,
                    fact_receipts=fact_receipts,
                )
                continue

            result = self._candidate_result(
                request,
                output=output,
                thread_id=thread_id,
                retrieval_decision="SEARCH" if searched else "SKIP",
                retrieval_strategy=retrieval_strategy if searched else requested_strategy,
                retrieval_audit=retrieval_audit,
                investigation_summary=investigation_summary,
                fact_receipts=fact_receipts,
            )
            self._record_terminal_checkpoint(request, thread_id, result)
            return result

    def _replay_terminal_result(
        self,
        request: AutopilotRecoveryRequest,
        thread_id: str,
    ) -> AutopilotRecoveryResult | None:
        """在任何模型或工具调用前复用同一事件已经完成的 durable 规划结果。

        Kafka 与内部 HTTP 都是至少一次投递：Python 可能已经完成规划、Java 也可能已经提交恢复副作用，
        但最终 HTTP 响应在网络上丢失。此时再次询问模型会产生不同候选，并与 data-sync 的固定 receipt 冲突。
        因此只有 ``autopilot_recovery_finished`` 且携带完整 ``terminalResult`` 的 checkpoint 才可直接重放；
        旧版本终态没有完整结果时继续走原规划流程，保持向后兼容。

        重放前会校验 checkpoint 顶层隔离字段和 ``requestBinding``。任何 event、tenant、project、workspace、
        task、execution 或错误指纹变化都视为事件 ID 冲突并 fail-closed，且不会追加新的 checkpoint/event。
        """

        latest = self._checkpointer.latest_for_thread(thread_id)
        if latest is None or latest.node_name != "autopilot_recovery_finished":
            return None
        terminal_summary = latest.state.get("terminalResult")
        if not isinstance(terminal_summary, Mapping):
            return None
        self._validate_replay_binding(request, latest)
        result = AutopilotRecoveryResult.from_summary(terminal_summary)
        if result.event_id != request.event_id or result.checkpoint_thread_id != thread_id:
            raise ValueError("Autopilot recovery replay binding mismatch: terminal result locator")
        # 较旧 checkpoint 早于 durable-fact 门禁。它们仍可能是有用的历史记录，但在没有证明每一轮
        # 均已被 Java 接受（或属于合法幂等重复）前，历史 CANDIDATE_READY 不得作为新的 Kafka 确认重放。
        # 返回 None 会有意重新进入规划，在产生新的成功结果前检查当前 sink 合同。
        if result.status == "CANDIDATE_READY" and latest.state.get("specialistFactsDurable") is not True:
            return None
        return result

    @staticmethod
    def _validate_replay_binding(
        request: AutopilotRecoveryRequest,
        checkpoint: LangGraphDurableCheckpoint,
    ) -> None:
        """校验 durable 终态确实属于当前可信 Java 触发事实。

        顶层 checkpoint 字段提供第一层租户/项目/会话隔离；``requestBinding`` 保存执行级幂等所需的固定
        标识和指纹。比较采用规范化后的字符串，不读取模型输出、日志或 RAG 正文。
        """

        expected_top_level = {
            "tenantId": request.tenant_id,
            "projectId": request.project_id,
            "workspaceKey": request.workspace_key,
            "runId": request.root_run_id,
            "sessionId": request.root_session_id,
        }
        actual_top_level = {
            "tenantId": checkpoint.tenant_id,
            "projectId": checkpoint.project_id,
            "workspaceKey": checkpoint.workspace_key,
            "runId": checkpoint.run_id,
            "sessionId": checkpoint.session_id,
        }
        mismatched_top_level = tuple(
            name
            for name, expected in expected_top_level.items()
            if str(actual_top_level.get(name) or "") != expected
        )
        binding = checkpoint.state.get("requestBinding")
        if not isinstance(binding, Mapping):
            raise ValueError("Autopilot recovery replay binding is missing")
        expected_binding = _terminal_request_binding(request)
        mismatched_binding = tuple(
            name
            for name, expected in expected_binding.items()
            if not _binding_values_equal(binding.get(name), expected)
        )
        if mismatched_top_level or mismatched_binding:
            changed = ",".join((*mismatched_top_level, *mismatched_binding))[:240]
            raise ValueError(f"Autopilot recovery replay binding mismatch: {changed}")

    def _run_recovery_turn(
        self,
        request: AutopilotRecoveryRequest,
        *,
        phase: str,
        knowledge_summary: Mapping[str, Any] | None,
        investigation_summary: Mapping[str, Any] | None,
        investigation_references: tuple[str, ...],
        fact_receipts: list[tuple[str, Any]],
    ) -> SpecialistTurnResult:
        """构造不可扩权的 Recovery turn，并通过统一 Specialist 注册表执行。

        ``trustedAutopilotRecovery`` 中的重复计数和上一轮指纹来自 Java 验证后的事件；Recovery Agent
        只允许读取这三个固定字段。模型无法从 objective 或 RAG 文档伪造循环状态。

        本方法还承担 Recovery 专属的事实登记边界。共享 ``SpecialistAgentCoordinator`` 为普通只读规划保留
        fail-open 兼容行为，因此这里不能只“调用 sink 后继续”：必须保存 sink 的原始低敏 receipt。缺少 sink
        会保存为无 receipt，sink 抛出的网络/传输异常则转换成固定技术错误；最终候选生成器会据此阻止 Kafka
        ACK。这样 Specialist 的 ``COMPLETED`` 只表示模型完成了 turn，不会被误当成 Java 已持久化事实。
        """

        turn_id = f"{request.event_id}:{phase}"
        context: dict[str, Any] = {
            "taskId": request.sync_task_id,
            "executionId": request.current_execution_id,
            "failureCode": request.issue_codes[0] if request.issue_codes else "EXECUTION_FAILED",
            # coordinator 拥有这一阶段快照。RAG turn 成功后，模型必须根据证据选择一个
            # 受治理动作，且不能再次消耗一次性检索预算。
            "recoveryDecisionControl": {
                "phase": phase.upper(),
                "knowledgeSearchCompleted": bool(knowledge_summary),
                "retrievalAlreadyPerformed": bool(knowledge_summary),
                "remainingKnowledgeSearches": 0 if knowledge_summary else 1,
                "mustChooseSingleGovernedAction": bool(knowledge_summary),
            },
            "trustedAutopilotRecovery": {
                "errorFingerprint": request.error_fingerprint,
                "repeatedErrorCount": request.repeated_error_count,
                "previousRepairFingerprint": request.previous_repair_fingerprint,
            },
        }
        evidence_references: tuple[str, ...] = ()
        if knowledge_summary:
            context["knowledgeSummary"] = dict(knowledge_summary)
            evidence_references = tuple(
                str(item.get("citationId"))
                for item in knowledge_summary.get("citations") or ()
                if isinstance(item, Mapping) and item.get("citationId")
            )
        if investigation_summary:
            # 成功的 preview receipt 是案例证据，而非知识文本。Recovery 仅接收 Java 过滤后的摘要
            # 和不透明输出引用；原始样本和日志绝不会进入此上下文。
            context["caseEvidence"] = {
                "investigationReceipt": dict(investigation_summary),
                "matchedCaseCount": 1,
            }
            context["recoveryInvestigation"] = dict(investigation_summary)
            evidence_references = tuple(dict.fromkeys((*evidence_references, *investigation_references)))
        specialist_request = SpecialistTurnRequest(
            turn_id=turn_id,
            session_id=request.root_session_id,
            run_id=request.root_run_id,
            role=AgentSessionRole.RECOVERY_AGENT,
            objective="诊断当前数据同步失败，并在受治理授权范围内提出下一步恢复动作。",
            scope=SpecialistDelegationScope(
                tenant_id=request.tenant_id,
                application_id=request.application_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                delegation_id=self._child_delegation_id(request, phase),
                allowed_tool_names=(FAILURE_DIAGNOSTIC_TOOL_CODE,),
            ),
            budget=SpecialistTurnBudget(
                timeout_ms=60_000,
                max_tool_calls=2,
                max_model_invocations=2,
                max_output_tokens=1_500,
            ),
            context_summary=context,
            evidence_references=evidence_references,
        )
        result = self._specialist_registry.execute(specialist_request)
        try:
            receipt = self._result_sink(specialist_request, result)
        except Exception as exc:  # noqa: BLE001 - do not expose transport details across the Recovery boundary
            raise AutopilotRecoveryDurableFactError() from None
            # 在产生 turn 的边界校验每份 receipt。这可避免早期事实登记被跳过或拒绝后，后续模型 turn
            # 仍继续执行，并使失败可在同一 Kafka 事件上重试，而不是返回误导性的候选。
        self._require_durable_specialist_receipt(receipt)
        fact_receipts.append((specialist_request.turn_id, receipt))
        return result

    def _search(
        self,
        request: AutopilotRecoveryRequest,
        specialist_output: Mapping[str, Any],
        strategy: str,
    ) -> RagPipelineResult:
        """把模型选择的高层检索策略映射到现有 pgvector/FTS RAG 合同。

        查询文本只由稳定错误码和资源类型生成，不包含原始日志、SQL、样本行或凭据。EXACT_SEARCH
        使用 lexical；RAG 使用 hybrid；Wiki 与 Git history 仍走同一持久化知识库，只增加来源过滤。
        """

        retrieval_mode, source_types = _retrieval_contract(strategy)
        failure = specialist_output.get("failure")
        failure_code = (
            str(failure.get("failureCode") or "").strip().upper()
            if isinstance(failure, Mapping)
            else ""
        )
        codes = tuple(dict.fromkeys((failure_code, *request.issue_codes)))
        safe_codes = tuple(code for code in codes if _SAFE_CODE.fullmatch(code))
        question = (
            "Data synchronization recovery guidance for error codes: "
            + ", ".join(safe_codes or ("EXECUTION_FAILED",))
            + ". Find similar resolved incidents, product runbooks, and safe retry conditions."
        )
        return self._rag_pipeline.answer(
            RagQuery(
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                workspace_key=request.workspace_key,
                question=question,
                top_k=5,
                candidate_limit=32,
                max_context_chars=4_000,
                generate_answer=True,
                trace_id=f"{request.event_id}:rag",
                session_id=request.root_session_id,
                retrieval_mode=retrieval_mode,
                source_types=source_types,
            )
        )

    def _candidate_result(
        self,
        request: AutopilotRecoveryRequest,
        *,
        output: Mapping[str, Any],
        thread_id: str,
        retrieval_decision: str,
        retrieval_strategy: str,
        retrieval_audit: Mapping[str, Any],
        investigation_summary: Mapping[str, Any] | None,
        fact_receipts: list[tuple[str, Any]],
    ) -> AutopilotRecoveryResult:
        """从 Specialist 公开动作中提取单一候选，并为 quarantine apply 执行 receipt 门禁。

        中文说明：普通 Recovery 动作仍以模型的单一建议为输入、以 Java 的授权和 data-sync 白名单为最终
        执行边界。``APPLY_QUARANTINE`` 是额外收紧的例外：它必须绑定本次已完成的真实 Java preview receipt，
        且 Python 会用可信 trigger 与 receipt 重算 repair fingerprint，完全忽略模型自报指纹。这样 Python
        只能把候选交给 Java，无法绕过 Java 的 apply、审计与自动 retry 流程。

        English: multiple side effects remain fail-closed.  For apply quarantine, this method validates the narrow
        receipt shape and returns ``ATTENTION_REQUIRED`` on every missing, stale, malformed, or scope-mismatched
        preview fact.  The final result carries only the approved low-sensitive receipt projection, never the raw
        preview payload, logs, or model arguments.

        Before constructing ``CANDIDATE_READY`` this method also requires a durable receipt for every Recovery turn.
        A receipt is valid only when ``registered`` or ``duplicate`` is the literal boolean ``True``; disabled
        (``skipped``), unattempted, missing, rejected, and fail-open network receipts are all technical failures.
        The check is intentionally local to Autopilot Recovery so ordinary read-only specialist planning keeps its
        existing fail-open contract.
        """

        governed_actions = self._governed_actions(output)
        governed_actions = self._select_single_investigation_action(governed_actions)
        if len(governed_actions) != 1:
            reason = (
                self._no_governed_action_reason(output)
                if not governed_actions
                else "MULTIPLE_RECOVERY_ACTIONS_REQUIRE_REVIEW"
            )
            return self._attention(
                request,
                thread_id,
                reason,
                output=output,
                retrieval_decision=retrieval_decision,
                retrieval_strategy=retrieval_strategy,
                retrieval_audit=retrieval_audit,
            )
        raw_action = _code(governed_actions[0].get("actionType"), "")
        if raw_action not in _KNOWN_GOVERNED_ACTIONS:
            return self._attention(
                request,
                thread_id,
                "RECOVERY_ACTION_NOT_IN_PLATFORM_CATALOG",
                output=output,
                retrieval_decision=retrieval_decision,
                retrieval_strategy=retrieval_strategy,
                retrieval_audit=retrieval_audit,
            )
        action = _AUTOMATIC_ACTION_ALIASES.get(raw_action, raw_action)
        automatic = raw_action in _AUTOMATIC_ACTION_ALIASES
        autopilot_recovery_facts = _autopilot_recovery_facts_summary(
            output.get("autopilotRecoveryFacts")
        )
        if raw_action in _RETRY_ACTION_ALIASES or raw_action == "RETRY_EXECUTION":
            if not bool(autopilot_recovery_facts.get("eligibleForAutomaticRetry")):
                return self._attention(
                    request,
                    thread_id,
                    "RECOVERY_AUTOMATIC_RETRY_FACTS_REQUIRED",
                    output=output,
                    retrieval_decision=retrieval_decision,
                    retrieval_strategy=retrieval_strategy,
                    retrieval_audit=retrieval_audit,
                )
            action = "RETRY_EXECUTION"
            automatic = True
        elif raw_action in _NON_AUTOMATIC_GOVERNED_ACTIONS:
            return self._attention(
                request,
                thread_id,
                "RECOVERY_ACTION_REQUIRES_APPROVAL",
                output=output,
                retrieval_decision=retrieval_decision,
                retrieval_strategy=retrieval_strategy,
                retrieval_audit=retrieval_audit,
            )
        quarantine_preview: Mapping[str, Any] = {}
        if raw_action == "APPLY_QUARANTINE":
            quarantine_preview, preview_issue = self._validated_quarantine_preview(
                request,
                investigation_summary,
            )
            if preview_issue is not None:
                return self._attention(
                    request,
                    thread_id,
                    preview_issue,
                    output=output,
                    retrieval_decision=retrieval_decision,
                    retrieval_strategy=retrieval_strategy,
                    retrieval_audit=retrieval_audit,
                )
            repair_fingerprint = self._apply_quarantine_fingerprint(request, quarantine_preview)
            # 只有每个 preview fact 通过上述严格校验后，才有意赋予绑定 receipt 的自治资格。模型在没有
            # 真实 receipt 时命名 APPLY_QUARANTINE 会通过关注分支退出，且永远不会从本方法获得 LOW/idempotent 标志。
            automatic = raw_action in _RECEIPT_BOUND_AUTOMATIC_ACTIONS
        else:
            repair_fingerprint = output.get("actionFingerprint")
            if not isinstance(repair_fingerprint, str) or not _SHA_256.fullmatch(repair_fingerprint):
                return self._attention(
                    request,
                    thread_id,
                    "RECOVERY_REPAIR_FINGERPRINT_INVALID",
                    output=output,
                    retrieval_decision=retrieval_decision,
                    retrieval_strategy=retrieval_strategy,
                    retrieval_audit=retrieval_audit,
                )
        evidence_audit = output.get("evidenceAudit")
        evidence_gate = output.get("diagnosticEvidenceGate")
        evidence_available = (
            isinstance(evidence_audit, Mapping)
            and int(evidence_audit.get("evidenceCount") or 0) > 0
            and isinstance(evidence_gate, Mapping)
            and evidence_gate.get("satisfied") is True
        )
        confidence = _confidence(output.get("modelConfidence"))
        self._require_durable_specialist_facts(fact_receipts)
        return AutopilotRecoveryResult(
            event_id=request.event_id,
            status="CANDIDATE_READY",
            reason_code="RECOVERY_CANDIDATE_READY",
            action=action,
            risk_level="LOW" if automatic else "HIGH",
            idempotent=automatic,
            repair_fingerprint=_fingerprint_hex(repair_fingerprint),
            error_fingerprint=request.error_fingerprint,
            confidence=confidence,
            evidence_available=evidence_available,
            evidence_audit=dict(evidence_audit) if isinstance(evidence_audit, Mapping) else {},
            evidence_scope={
                "tenantId": request.tenant_id,
                "projectId": request.project_id,
                "workspaceKey": request.workspace_key,
                "taskId": request.sync_task_id,
                "executionId": request.current_execution_id,
            },
            quarantine_preview=quarantine_preview,
            autopilot_recovery_facts=autopilot_recovery_facts,
            retrieval_decision=retrieval_decision,
            retrieval_strategy=retrieval_strategy,
            retrieval_audit=dict(retrieval_audit),
            # ``strategyChanged`` 是审计提示而非授权。对于重复错误，如果候选仅在已完成的受治理 SEARCH
            # 且检索审计非空后产生，coordinator 可自行证明策略扩展。即使第二个 Recovery 模型遗漏该布尔值，
            # 返回说明仍保持准确；Java 在允许再次无人值守重试前，仍会独立要求新的 repair fingerprint 并重新校验
            # 检索结果的摘要、作用域与时间信息。
            strategy_changed=bool(output.get("strategyChanged"))
            or (
                request.repeated_error_count > 0
                and retrieval_decision == "SEARCH"
                and bool(retrieval_audit)
            ),
            checkpoint_thread_id=thread_id,
        )

    @staticmethod
    def _require_durable_specialist_facts(
        fact_receipts: list[tuple[str, Any]],
    ) -> None:
        """Require Java acknowledgement for every Specialist turn before reporting success.

        The Specialist result and its durable fact are separate contracts: a model can finish with ``COMPLETED``
        while the Java registration client is disabled, fail-open, rejected, or unable to reach the control plane.
        Treating any of those states as a successful Recovery plan would let the Java Kafka consumer commit an ACK
        without the audit fact that explains the autonomous decision.  This helper therefore uses a deliberately
        small, transport-independent receipt protocol and accepts exactly two success cases:

        * ``registered is True`` means Java accepted this fact now;
        * ``duplicate is True`` means Java already accepted the same idempotent fact.

        ``skipped`` and ``attempted`` are checked explicitly so a malformed receipt cannot pass merely because a
        truthy value appeared in an unrelated field.  The exception contains only the stable machine code; callers
        can retry the HTTP/Kafka delivery without exposing network details, response bodies, or credentials.
        """

        if not fact_receipts:
            raise AutopilotRecoveryDurableFactError()
        for _turn_id, receipt in fact_receipts:
            AutopilotRecoveryCoordinator._require_durable_specialist_receipt(receipt)

    @staticmethod
    def _require_durable_specialist_receipt(receipt: Any) -> None:
        """Validate one Java fact receipt without trusting permissive truthiness.

        The Python client returns a dataclass in production, while tests and future transports may use a mapping.
        Both forms are accepted, but only literal booleans are valid.  ``registered`` means the control plane
        accepted the fact now and ``duplicate`` means the same idempotency key was already accepted.  Every other
        state is a technical dependency failure: the caller must retry the Kafka event rather than acknowledge an
        autonomous recovery decision that cannot be audited durably.
        """

        if receipt is None:
            raise AutopilotRecoveryDurableFactError()
        registered = _receipt_bool(receipt, "registered")
        skipped = _receipt_bool(receipt, "skipped")
        duplicate = _receipt_bool(receipt, "duplicate")
        attempted = _receipt_bool(receipt, "attempted")
        if (
            skipped is True
            or attempted is not True
            or (registered is not True and duplicate is not True)
        ):
            raise AutopilotRecoveryDurableFactError()

    @staticmethod
    def _validated_quarantine_preview(
        request: AutopilotRecoveryRequest,
        investigation_summary: Mapping[str, Any] | None,
    ) -> tuple[Mapping[str, Any], str | None]:
        """验证 Java preview receipt，并生成 apply 候选唯一允许携带的低敏投影。

        中文说明：此处不信任 Recovery 模型，也不把协作者返回的任意 Mapping 视为真实 receipt。候选必须来自
        ``PREVIEW_QUARANTINE`` 的 Java tool receipt，绑定当前 task/execution，且 preview 状态、计数、digest、
        样本 ID、issue 列表及 audit/run/output 引用全部满足固定合同。``operationState`` 仅用于本地验收，不能
        进入最终 ``quarantinePreview``，以免响应膨胀为通用工具输出通道。

        English: this is a fail-closed boundary between a model suggestion and a Java-executable candidate.  It
        normalizes IDs and digest only after validation, sorts IDs numerically for deterministic hashing, and returns
        a fixed reason code instead of remote payload details.  It performs no I/O and never calls data-sync.
        """

        if not isinstance(investigation_summary, Mapping):
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_RECEIPT_REQUIRED"
        if (
            str(investigation_summary.get("source") or "")
            != "JAVA_AGENT_RUNTIME_TOOL_RECEIPT"
            or _code(investigation_summary.get("actionType"), "") != "PREVIEW_QUARANTINE"
            or str(investigation_summary.get("toolName") or "") != _QUARANTINE_PREVIEW_TOOL
        ):
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID"

        result = investigation_summary.get("result")
        if not isinstance(result, Mapping):
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID"
        if _code(result.get("operationState"), "") != "PREVIEWED":
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID"

        task_id = _positive_integer(result.get("taskId"))
        execution_id = _positive_integer(result.get("executionId"))
        selected_count = _positive_integer(result.get("selectedCount"))
        eligible_count = _positive_integer(result.get("eligibleCount"))
        if (
            task_id != _positive_integer(request.sync_task_id)
            or execution_id != _positive_integer(request.current_execution_id)
            or selected_count is None
            or eligible_count != selected_count
        ):
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID"

        raw_sample_ids = result.get("selectedSampleIds")
        if not isinstance(raw_sample_ids, (list, tuple)) or not raw_sample_ids or len(raw_sample_ids) > 500:
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID"
        sample_ids = tuple(_positive_integer(value) for value in raw_sample_ids)
        if (
            any(value is None for value in sample_ids)
            or len(set(sample_ids)) != len(sample_ids)
            or len(sample_ids) != selected_count
        ):
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID"
        selected_sample_ids = tuple(sorted(int(value) for value in sample_ids if value is not None))

        issue_codes = result.get("issueCodes")
        if not isinstance(issue_codes, (list, tuple)) or issue_codes:
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID"
        digest = str(result.get("confirmationDigest") or "").strip().lower()
        audit_id = _bounded_receipt_text(investigation_summary.get("auditId"), 200)
        run_id = _bounded_receipt_text(investigation_summary.get("runId"), 200)
        output_ref = _bounded_receipt_text(investigation_summary.get("outputRef"), 1_200)
        if (
            not _HEX_64.fullmatch(digest)
            or audit_id is None
            or run_id is None
            or output_ref is None
            or not output_ref.startswith("agent-runtime://")
        ):
            return {}, "RECOVERY_APPLY_QUARANTINE_PREVIEW_INVALID"

        # 此顺序和字段集构成跨语言合同。不得在此添加 operationState、message、原始输出或模型数据：
        # Java 会在执行 quarantine 前独立解析不透明 receipt。
        return {
            "taskId": task_id,
            "executionId": execution_id,
            "selectedCount": selected_count,
            "eligibleCount": eligible_count,
            "confirmationDigest": digest,
            "selectedSampleIds": selected_sample_ids,
            "issueCodes": (),
            "auditId": audit_id,
            "runId": run_id,
            "outputRef": output_ref,
        }, None

    @staticmethod
    def _apply_quarantine_fingerprint(
        request: AutopilotRecoveryRequest,
        quarantine_preview: Mapping[str, Any],
    ) -> str:
        """重算 APPLY_QUARANTINE 的跨语言 repair fingerprint，不采信模型输出。

        中文说明：canonical material 严格为 ``eventId|errorFingerprint|currentExecutionId|APPLY_QUARANTINE|``
        ``confirmationDigest|sortedSampleIds``。错误指纹和确认摘要均为无前缀小写 64 位 hex，样本 ID 按数值
        升序以逗号连接。该值把本次恢复事件、失败事实和 Java preview 的精确选择绑定在一起，Java 可用相同
        算法独立复算；方法不写状态、不调工具。

        English: this deterministic SHA-256 binds the apply candidate to the exact Java preview selection.  Sorting
        numeric IDs prevents representation order from changing the candidate fingerprint, while excluding all model
        fields prevents a model-supplied action fingerprint from becoming authority.
        """

        sample_ids = tuple(sorted(int(value) for value in quarantine_preview["selectedSampleIds"]))
        material = "|".join(
            (
                request.event_id,
                request.error_fingerprint.lower(),
                request.current_execution_id,
                "APPLY_QUARANTINE",
                str(quarantine_preview["confirmationDigest"]).lower(),
                ",".join(str(value) for value in sample_ids),
            )
        )
        return hashlib.sha256(material.encode("utf-8")).hexdigest()

    @staticmethod
    def _governed_actions(output: Mapping[str, Any]) -> tuple[Mapping[str, Any], ...]:
        """移除已经完成的诊断/检索记录，仅返回等待平台处理的动作建议。"""

        raw_actions = output.get("repairActions") or ()
        return tuple(
            item
            for item in raw_actions
            if isinstance(item, Mapping)
            and _code(item.get("actionType"), "") not in _NON_EXECUTING_ACTIONS
        )

    @staticmethod
    def _no_governed_action_reason(output: Mapping[str, Any]) -> str:
        """当一轮不含副作用候选时，返回确定性的关注码。

        Recovery 模型可以以诊断或检索建议结束。这些记录是有用证据，但不是可执行的恢复命令。
        将这种正常弃权与空的或格式错误的动作列表区分开，可让运维行为易于理解，并避免调用方
        将只读结果视为动作目录故障。
        """

        raw_actions = output.get("repairActions") or ()
        if isinstance(raw_actions, (list, tuple)) and raw_actions:
            codes = tuple(
                _code(item.get("actionType"), "")
                for item in raw_actions
                if isinstance(item, Mapping)
            )
            if codes and all(code in _NON_EXECUTING_ACTIONS for code in codes):
                return "RECOVERY_READ_ONLY_EVIDENCE_ONLY"
        return "RECOVERY_ACTION_MISSING"

    @staticmethod
    def _select_single_investigation_action(
        governed_actions: tuple[Mapping[str, Any], ...],
    ) -> tuple[Mapping[str, Any], ...]:
        """把“多个纯调查候选”收敛为一个当前可无人值守执行的最小动作。

        模型协议已经要求一轮最多一个动作，但模型输出仍属于不可信输入，生产代码不能假设它永远守约。
        对普通多动作组合，本方法原样返回，让调用方进入 ``MULTIPLE_RECOVERY_ACTIONS_REQUIRE_REVIEW``；尤其是
        preview 与 retry/apply/replay/schema 写动作混合时，绝不能为了自动化而丢弃高风险意图。

        只有所有动作都属于平台固定的 investigation 目录时，才允许进一步按平台目录筛选。筛选不读取模型自报
        的 risk、readOnly、idempotent 或 requiresApproval 字段，只使用代码内与 Java 注册表一致的优先级。
        当前仅 ``PREVIEW_QUARANTINE`` 同时满足 LOW、只读、幂等、无需审批，因此可作为本轮唯一下一步；其余
        preview 仍保留阻断语义，等待专用执行器或真实审批闭环支持。重复的同一安全 preview 也会收敛为一次，
        避免模型重复项消耗额外数据库预算。

        @param governed_actions 已移除诊断/检索记录后的平台动作建议
        @return 零个、一个或原始多个动作；一个动作表示可继续候选评估
        """

        if len(governed_actions) <= 1:
            return governed_actions
        action_codes = tuple(_code(item.get("actionType"), "") for item in governed_actions)
        if not action_codes or any(code not in _PLATFORM_INVESTIGATION_ACTIONS for code in action_codes):
            return governed_actions
        autonomous = tuple(
            item
            for item in governed_actions
            if _code(item.get("actionType"), "") in _AUTONOMOUS_INVESTIGATION_PRIORITY
        )
        if not autonomous:
            return governed_actions
        selected = min(
            autonomous,
            key=lambda item: _AUTONOMOUS_INVESTIGATION_PRIORITY[_code(item.get("actionType"), "")],
        )
        return (selected,)

    def _blocked_result(
        self,
        request: AutopilotRecoveryRequest,
        result: SpecialistTurnResult,
        thread_id: str,
        *,
        retrieval_audit: Mapping[str, Any] | None = None,
    ) -> AutopilotRecoveryResult:
        """把 Specialist 的等待或失败转换成 Java 可持久化的稳定阻断原因。"""

        status = "FAILED" if result.status == SpecialistTurnStatus.FAILED else "ATTENTION_REQUIRED"
        reason = result.error_code or (
            "RECOVERY_SPECIALIST_WAITING_FOR_INPUT"
            if result.status == SpecialistTurnStatus.WAITING_FOR_INPUT
            else "RECOVERY_SPECIALIST_NOT_COMPLETED"
        )
        blocked = AutopilotRecoveryResult(
            event_id=request.event_id,
            status=status,
            reason_code=reason,
            error_fingerprint=request.error_fingerprint,
            retrieval_audit=dict(retrieval_audit or {}),
            evidence_scope={
                "tenantId": request.tenant_id,
                "projectId": request.project_id,
                "workspaceKey": request.workspace_key,
                "taskId": request.sync_task_id,
                "executionId": request.current_execution_id,
            },
            checkpoint_thread_id=thread_id,
        )
        self._record_terminal_checkpoint(request, thread_id, blocked)
        return blocked

    def _attention(
        self,
        request: AutopilotRecoveryRequest,
        thread_id: str,
        reason_code: str,
        *,
        output: Mapping[str, Any],
        retrieval_decision: str,
        retrieval_strategy: str,
        retrieval_audit: Mapping[str, Any] | None = None,
    ) -> AutopilotRecoveryResult:
        """创建不执行副作用的人工关注结果，同时保留可复核证据摘要。"""

        evidence_audit = output.get("evidenceAudit")
        return AutopilotRecoveryResult(
            event_id=request.event_id,
            status="ATTENTION_REQUIRED",
            reason_code=reason_code,
            error_fingerprint=request.error_fingerprint,
            confidence=_confidence(output.get("modelConfidence")),
            evidence_available=isinstance(evidence_audit, Mapping)
            and int(evidence_audit.get("evidenceCount") or 0) > 0,
            evidence_audit=dict(evidence_audit) if isinstance(evidence_audit, Mapping) else {},
            evidence_scope={
                "tenantId": request.tenant_id,
                "projectId": request.project_id,
                "workspaceKey": request.workspace_key,
                "taskId": request.sync_task_id,
                "executionId": request.current_execution_id,
            },
            retrieval_decision=retrieval_decision,
            retrieval_strategy=retrieval_strategy,
            retrieval_audit=dict(retrieval_audit or {}),
            strategy_changed=bool(output.get("strategyChanged")),
            checkpoint_thread_id=thread_id,
        )

    @staticmethod
    def _knowledge_summary(result: RagPipelineResult) -> dict[str, Any]:
        """把 RAG 结果压缩成 Recovery 第二轮允许消费的引用摘要。

        ``compressed_context`` 和选中 chunk 正文不会进入第二轮 handoff；Recovery 只看到模型生成的
        低敏答案、citation 元数据和 retrieval audit。这样能使用知识证据，又不会把文档全文复制进
        Specialist checkpoint 或 Java 响应。
        """

        return {
            "grounded": bool(result.citations),
            "answerAvailable": bool(result.answer and result.citations),
            "answer": result.answer[:1_200],
            "citations": tuple(citation.to_summary() for citation in result.citations),
            "retrievalSummary": dict(result.retrieval_summary),
            "payloadPolicy": "LOW_SENSITIVE_AUTOPILOT_RAG_SUMMARY_ONLY",
        }

    def _record_specialist_checkpoint(
        self,
        request: AutopilotRecoveryRequest,
        thread_id: str,
        result: SpecialistTurnResult,
        output: Mapping[str, Any],
        *,
        phase: str,
    ) -> None:
        """记录 Specialist turn 的低敏状态，不保存模型正文或动作参数值。

        ``repairActionCodes`` 是排查自治策略时需要的最小观测面：它只保留每个动作的 ``actionType``，
        统一为平台大写代码、去重并最多保存十项。参数、理由、日志、知识正文和模型生成文本都不会进入
        checkpoint，因此运维人员可以判断“模型选择了哪些动作”，却无法从该表反向读到敏感内容。
        """

        repair_actions = output.get("repairActions") or ()

        self._record_checkpoint(
            request,
            thread_id=thread_id,
            node_name=f"autopilot_recovery_{phase}_completed",
            status=(
                LangGraphCheckpointStatus.RUNNING
                if result.status == SpecialistTurnStatus.COMPLETED
                else LangGraphCheckpointStatus.FAILED
            ),
            next_nodes=("autopilot_recovery_route",) if result.status == SpecialistTurnStatus.COMPLETED else (),
            state={
                "specialistStatus": result.status.value,
                "specialistErrorCode": result.error_code,
                "retrievalDecision": output.get("retrievalDecision") or output.get("ragDecision"),
                "retrievalStrategy": output.get("retrievalStrategy"),
                "repairActionCount": len(repair_actions),
                "repairActionCodes": _low_sensitive_repair_action_codes(repair_actions),
                "evidenceCount": (
                    int(output.get("evidenceAudit", {}).get("evidenceCount") or 0)
                    if isinstance(output.get("evidenceAudit"), Mapping)
                    else 0
                ),
            },
            summary=f"Recovery specialist {phase} turn finished with {result.status.value}.",
        )

    def _record_terminal_checkpoint(
        self,
        request: AutopilotRecoveryRequest,
        thread_id: str,
        result: AutopilotRecoveryResult,
    ) -> None:
        """记录候选、人工关注或失败终态，并保存可确定性重放的公开响应合同。

        ``terminalResult`` 与返回给 Java 的 ``to_summary`` 完全同源，不包含模型原文、RAG 正文、日志、SQL、
        凭据或工具参数。``requestBinding`` 则把 eventId 绑定到当前授权范围、任务、执行和错误事实，使 Kafka
        重投可以安全复用结果，又不能把同一 eventId 借给另一个执行。
        """

        status = (
            LangGraphCheckpointStatus.COMPLETED
            if result.status == "CANDIDATE_READY"
            else LangGraphCheckpointStatus.FAILED
        )
        self._record_checkpoint(
            request,
            thread_id=thread_id,
            node_name="autopilot_recovery_finished",
            status=status,
            next_nodes=(),
            state={
                "resultStatus": result.status,
                "reasonCode": result.reason_code,
                "action": result.action,
                "repairFingerprint": result.repair_fingerprint,
                "errorFingerprint": result.error_fingerprint,
                "evidenceAvailable": result.evidence_available,
                "quarantinePreviewAvailable": bool(result.quarantine_preview),
                "retrievalDecision": result.retrieval_decision,
                "retrievalStrategy": result.retrieval_strategy,
                "strategyChanged": result.strategy_changed,
        # 仅在 _candidate_result 证明每个已执行 turn 均有已登记或合法重复的 fact 后才写入此标志。
        # 重放将其作为持久性证明，而非配置提示。
                "specialistFactsDurable": result.status == "CANDIDATE_READY",
                "requestBinding": _terminal_request_binding(request),
                "terminalResult": result.to_summary(),
            },
            summary=f"Autopilot recovery planning finished with {result.status}.",
        )

    def _record_checkpoint(
        self,
        request: AutopilotRecoveryRequest,
        *,
        thread_id: str,
        node_name: str,
        status: LangGraphCheckpointStatus,
        next_nodes: tuple[str, ...],
        state: Mapping[str, Any],
        summary: str,
    ) -> None:
        """以递增版本写入一个低敏 checkpoint，并建立父子链路。

        checkpoint ID 由 event、版本和节点摘要生成，同一请求重放不会把其它 event 的状态串入本线程。
        PostgreSQL store 会持久化这些版本；内存 store 则服务本地单测和无数据库学习环境。
        """

        latest = self._checkpointer.latest_for_thread(thread_id)
        version = 1 if latest is None else latest.checkpoint_version + 1
        material = f"{request.event_id}|{version}|{node_name}"
        checkpoint_id = "lgcp:autopilot:" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:32]
        self._checkpointer.record_checkpoint(
            LangGraphDurableCheckpoint(
                checkpoint_id=checkpoint_id,
                thread_id=thread_id,
                parent_checkpoint_id=latest.checkpoint_id if latest else None,
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                workspace_key=request.workspace_key,
                run_id=request.root_run_id,
                session_id=request.root_session_id,
                graph_name="datasmart-autopilot-recovery",
                graph_version="v1",
                node_name=node_name,
                status=status,
                checkpoint_version=version,
                state=dict(state),
                next_nodes=next_nodes,
                low_sensitive_summary=summary,
            ),
            event_type=node_name,
        )

    @staticmethod
    def _child_delegation_id(request: AutopilotRecoveryRequest, phase: str) -> str:
        """从父委派和当前 event/phase 派生稳定的最小子委派标识。"""

        material = "|".join(
            (
                request.tenant_id,
                request.project_id,
                request.actor_id,
                request.root_session_id,
                request.root_run_id,
                request.delegation_id,
                request.event_id,
                phase,
                AgentSessionRole.RECOVERY_AGENT.value,
            )
        )
        return "delegation-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]


def _retrieval_contract(strategy: str) -> tuple[str, tuple[str, ...]]:
    """把模型允许的检索策略转换为 RAG storage adapter 参数。"""

    normalized = _code(strategy, "RAG")
    if normalized == "EXACT_SEARCH":
        return "lexical", ()
    if normalized == "WIKI":
        return "hybrid", ("wiki",)
    if normalized == "GIT_HISTORY":
        return "hybrid", ("git_history",)
    return "hybrid", ()


def _terminal_request_binding(request: AutopilotRecoveryRequest) -> dict[str, Any]:
    """生成终态重放所需的固定、低敏请求绑定。

    绑定包含身份隔离、授权预算、任务执行定位和错误指纹，不包含 objective、日志、样本、凭据或模型输入。
    ``issueCodes`` 经过请求构造阶段的白名单和数量限制，可以作为诊断事实的一部分确定性比较。
    """

    return {
        "eventId": request.event_id,
        "rootSessionId": request.root_session_id,
        "rootRunId": request.root_run_id,
        "tenantId": request.tenant_id,
        "applicationId": request.application_id,
        "projectId": request.project_id,
        "userId": request.user_id,
        "actorId": request.actor_id,
        "agentId": request.agent_id,
        "delegationId": request.delegation_id,
        "workspaceKey": request.workspace_key,
        "syncTaskId": request.sync_task_id,
        "rootExecutionId": request.root_execution_id,
        "currentExecutionId": request.current_execution_id,
        "cycle": request.cycle,
        "maxRecoveryCycles": request.max_recovery_cycles,
        "deadlineAt": request.deadline_at,
        "errorFingerprint": request.error_fingerprint,
        "repeatedErrorCount": request.repeated_error_count,
        "previousRepairFingerprint": request.previous_repair_fingerprint or "",
        "issueCodes": tuple(request.issue_codes),
    }


def _binding_values_equal(actual: Any, expected: Any) -> bool:
    """比较 PostgreSQL JSONB 恢复值与内存领域值，兼容 tuple 被 JSON 解码成 list。"""

    if isinstance(expected, (list, tuple)):
        return isinstance(actual, (list, tuple)) and tuple(str(item) for item in actual) == tuple(
            str(item) for item in expected
        )
    if isinstance(expected, bool):
        return actual is expected
    if isinstance(expected, int):
        return isinstance(actual, int) and not isinstance(actual, bool) and actual == expected
    return str(actual or "") == str(expected or "")


def _receipt_bool(receipt: Any, field_name: str) -> bool | None:
    """从 sink receipt 中读取一个布尔值，不强制转换字符串或任意真值。

    共享事实客户端返回 dataclass，而小型适配器和测试可能返回映射。两种形式都受支持，但有意不接受
    ``"true"`` 或 ``1`` 之类的值：持久确认是有类型的合同，不能通过宽松的真值转换伪造。
    """

    if isinstance(receipt, Mapping):
        value = receipt.get(field_name)
    else:
        value = getattr(receipt, field_name, None)
    return value if isinstance(value, bool) else None


def _mapping_copy(value: Any) -> dict[str, Any]:
    """仅在值是 Mapping 时复制，用于恢复已通过公开合同白名单的低敏嵌套对象。"""

    return dict(value) if isinstance(value, Mapping) else {}


def _optional_code(value: Any) -> str | None:
    """读取可选平台枚举；空值保持 ``None``，避免重放时制造虚假的动作或风险。"""

    if value is None or not str(value).strip():
        return None
    normalized = _code(value, "")
    return normalized or None


def _optional_fingerprint(value: Any) -> str | None:
    """读取可选 SHA-256，并拒绝 checkpoint 中损坏或人工注入的非指纹文本。"""

    if value is None or not str(value).strip():
        return None
    normalized = str(value).strip()
    if not _SHA_256.fullmatch(normalized):
        raise ValueError("Autopilot recovery terminalResult fingerprint is invalid")
    return _fingerprint_hex(normalized)


def _optional_text(value: Any, max_length: int) -> str | None:
    """读取有界可选文本；超长值 fail-closed，而不是在重放时静默截断 locator。"""

    if value is None:
        return None
    normalized = str(value).strip()
    if not normalized:
        return None
    if len(normalized) > max_length:
        raise ValueError("Autopilot recovery terminalResult text exceeds replay limit")
    return normalized


def _fingerprint_hex(value: str) -> str:
    """移除可选 ``sha256:`` 前缀，统一 Java/data-sync 使用的 64 位十六进制格式。"""

    normalized = str(value).strip()
    return normalized[7:].lower() if normalized.lower().startswith("sha256:") else normalized.lower()


def _parse_time(value: str, field_name: str) -> datetime:
    """解析 ISO-8601 时间并统一到 UTC；无时区时间不会被静默接受。"""

    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"{field_name} 不是有效 ISO-8601 时间") from exc
    if parsed.tzinfo is None:
        raise ValueError(f"{field_name} 必须包含时区")
    return parsed.astimezone(timezone.utc)


def _code(value: Any, fallback: str) -> str:
    """把外部枚举值规范为大写下划线编码，避免大小写差异改变分支。"""

    normalized = re.sub(r"[^A-Z0-9]+", "_", str(value or "").upper()).strip("_")
    return normalized or fallback


def _low_sensitive_repair_action_codes(raw_actions: Any) -> tuple[str, ...]:
    """从模型动作列表中提取可持久化的有界动作代码摘要。

    该方法故意不复制整个动作对象，也不读取 ``parameters``、``arguments``、``reason`` 或模型提供的
    风险分类。每项只有在它是对象、含有效 ``actionType`` 且标准化代码符合平台短代码格式时才会保留。
    使用有序去重可以让重复建议不会放大审计噪声；十项上限则避免异常模型输出撑大 durable state。
    """

    if not isinstance(raw_actions, (list, tuple)):
        return ()
    codes: list[str] = []
    for item in raw_actions:
        if not isinstance(item, Mapping):
            continue
        code = _code(item.get("actionType"), "")
        if not code or not _SAFE_CODE.fullmatch(code) or code in codes:
            continue
        codes.append(code)
        if len(codes) == 10:
            break
    return tuple(codes)


def _confidence(value: Any) -> float:
    """把模型置信度限制在 0 到 1；缺失值保持 0，由 Java 策略转人工关注。"""

    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return 0.0


def _positive_integer(value: Any) -> int | None:
    """解析一个 JSON 数字或十进制字符串为正整数，拒绝布尔、浮点和非数字文本。

    中文说明：preview receipt 的 task、execution、计数和样本 ID 都参与 Java/Python 的范围判断及指纹；
    因此不能用宽松的 ``int(value)`` 接受 ``True``、``1.0`` 或带符号文本。返回 ``None`` 表示调用方必须
    fail-closed，而不是尝试猜测或补默认值。

    English: this deliberately strict parser keeps a JSON receipt's numeric identity stable across services.  It has
    no side effects and provides a single invalid marker so callers can return a low-sensitive attention reason.
    """

    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value > 0 else None
    if isinstance(value, str):
        normalized = value.strip()
        if normalized.isdecimal():
            parsed = int(normalized)
            return parsed if parsed > 0 else None
    return None


def _bounded_receipt_text(value: Any, max_length: int) -> str | None:
    """规范化 receipt 引用文本并限制长度，避免异常回执扩张最终候选。

    中文说明：auditId、runId 和 outputRef 都来自 Java receipt，而非模型；仍需在进入最终 API 合同前做
    非空和长度边界校验。该函数不解释 URI、不查询控制面，URI 前缀及字段间语义由调用方继续验证。

    English: receipt locators are opaque identifiers.  This helper only enforces a bounded transport shape and never
    turns a text value into proof of execution by itself.
    """

    normalized = str(value or "").strip()
    return normalized if normalized and len(normalized) <= max_length else None


def _quarantine_preview_summary(value: Mapping[str, Any] | Any) -> dict[str, Any]:
    """将最终响应中的 quarantine preview 再投影为固定十字段，阻止未来调用方夹带扩展数据。

    中文说明：coordinator 在创建 APPLY 候选时已经验证并构造完整 preview；这里保留第二道输出边界，使任何
    直接构造 ``AutopilotRecoveryResult`` 的未来代码也无法通过 ``to_summary`` 泄露 ``operationState``、message、
    原始坏行或其它 Java 工具输出。字段缺失时不猜测默认值，直接省略，Java 的独立合同校验会拒绝不完整候选。

    English: this is presentation allow-listing, not receipt validation.  The coordinator owns semantic validation;
    the final serializer merely guarantees that the public contract cannot grow through an arbitrary Mapping attached
    to the frozen result object.  It performs no I/O, authorization, or data-sync execution.
    """

    if not isinstance(value, Mapping):
        return {}
    return {
        name: value[name]
        for name in _QUARANTINE_PREVIEW_OUTPUT_FIELDS
        if name in value
    }


def _autopilot_recovery_facts_summary(value: Mapping[str, Any] | Any) -> dict[str, Any]:
    """使重试资格投影保持有界且确定。

    此映射由 Recovery Specialist 根据控制面诊断生成，但到达此 coordinator 时仍是不可信的传输数据。
    只有下列固定字段可跨越 Python checkpoint/Java 响应边界；缺失或格式错误的值保持为 false，
    从而使自动重试 fail-closed。
    """

    if not isinstance(value, Mapping):
        return {}
    failure_class = _code(value.get("failureClass"), "")
    retryable = value.get("retryable") is True
    eligible = value.get("eligibleForAutomaticRetry") is True
    failed_object_count = _positive_integer(value.get("failedObjectCount")) or 0
    raw_root_causes = value.get("rootCauseCodes")
    root_causes = tuple(
        code
        for code in (
            _code(item, "")
            for item in (raw_root_causes if isinstance(raw_root_causes, (list, tuple)) else ())
        )
        if code and _SAFE_CODE.fullmatch(code)
    )[:12]
    return {
        "failureClass": failure_class,
        "retryable": retryable,
        "eligibleForAutomaticRetry": (
            eligible
            and failure_class == "TRANSIENT_CONNECTOR_OR_WORKER"
            and retryable
            and failed_object_count > 0
        ),
        "failedObjectCount": failed_object_count,
        "rootCauseCodes": root_causes,
    }


__all__ = [
    "AutopilotRecoveryCoordinator",
    "AutopilotRecoveryRequest",
    "AutopilotRecoveryResult",
]
