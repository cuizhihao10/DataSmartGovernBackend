"""真实 PRECHECK_AGENT：调用控制面预检查并解释结构化结果。

预检查是同步任务从“配置草案”走向“可以执行”之前的事实闸门。这个专业 Agent 不自己读取
数据库、不猜测表结构，也不保存、发布或执行任务；它只把一次受控的
``sync.task.precheck`` 调用交给注入的 Java/data-sync 控制面，再让独立模型解释已经返回的
低敏检查摘要。这样模型可以帮助用户理解问题，但不能把“模型认为表存在”伪装成后端事实。
"""

from __future__ import annotations

import re
import time
from collections.abc import Mapping as MappingABC
from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Any, Mapping, Protocol

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistAuditScope,
    SpecialistEventSink,
    SpecialistToolActivity,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)


PRECHECK_TOOL_CODE = "sync.task.precheck"
# 描述性别名便于工具注册表和接入适配器保持与其他 specialist 的命名风格一致。
SYNC_TASK_PRECHECK_TOOL_CODE = PRECHECK_TOOL_CODE
PRECHECK_CONTROL_PLANE_TOOL_CODE = PRECHECK_TOOL_CODE


class PrecheckCheckStatus(str, Enum):
    """控制面检查项允许的状态集合。

    状态是后端返回的事实，而不是模型可以生成的字段。``WARNING`` 表示当前检查没有形成硬阻断，
    但用户仍应确认提示；``FAILED`` 和 ``BLOCKED`` 都会把专业 Agent turn 置为
    ``WAITING_FOR_INPUT``，并关闭后续保存、发布和执行闸门。
    """

    PASSED = "PASSED"
    FAILED = "FAILED"
    WARNING = "WARNING"
    BLOCKED = "BLOCKED"


@dataclass(frozen=True)
class PrecheckControlPlaneRequest:
    """调用真实预检查控制面的最小请求合同。

    ``configuration`` 可能包含后端完成预检查所需的完整任务配置，因此它只会进入受控客户端，
    不会进入模型输入、事件、工具活动或最终公开结果。客户端仍必须把 tenant/project/actor 和
    delegation 传递给下游服务完成第二次权限校验；Python Agent 的工具白名单不能替代下游 RBAC。
    """

    tenant_id: str
    application_id: str | None
    project_id: str | None
    actor_id: str
    delegation_id: str
    turn_id: str
    run_id: str
    task_id: str | None = None
    configuration: Mapping[str, Any] | None = None
    timeout_ms: int = 20_000
    task_config: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        """冻结控制面请求中的配置快照，防止客户端调用期间被调用方并发改写。"""

        selected_configuration = self.configuration if self.configuration is not None else self.task_config
        if selected_configuration is not None and not isinstance(selected_configuration, MappingABC):
            raise TypeError("PrecheckControlPlaneRequest configuration 必须是 Mapping")
        if selected_configuration is not None:
            selected_configuration = MappingProxyType(dict(selected_configuration))
        object.__setattr__(self, "configuration", selected_configuration)
        object.__setattr__(self, "task_config", selected_configuration)
        object.__setattr__(self, "task_id", _text(self.task_id))

    @property
    def config(self) -> Mapping[str, Any] | None:
        """返回配置别名，方便不同控制面适配器渐进接入而不复制敏感配置。"""

        return self.configuration


@dataclass(frozen=True)
class PrecheckCheckItem:
    """控制面返回的一项结构化预检查结果。

    该类型只描述允许跨越 Agent 边界的字段：问题码、状态、用户可读问题、建议、配置步骤和详情
    引用。它没有 SQL、连接串、凭据、样本行或任意原始 HTTP 响应字段。
    """

    code: str = ""
    status: str = ""
    problem: str = ""
    suggestion: str = ""
    configuration_steps: tuple[str, ...] = ()
    details_reference: str | None = None
    issue_code: str | None = None
    message: str = ""

    def __post_init__(self) -> None:
        """规范化检查项的文本集合，避免公开结果携带无界字符串或重复步骤。"""

        object.__setattr__(self, "code", _text(self.code) or _text(self.issue_code) or "")
        object.__setattr__(self, "status", _text(self.status) or "")
        object.__setattr__(self, "problem", _text(self.problem) or _text(self.message) or "")
        object.__setattr__(self, "suggestion", _text(self.suggestion) or "")
        object.__setattr__(self, "configuration_steps", _unique_text(self.configuration_steps))
        object.__setattr__(self, "details_reference", _text(self.details_reference))


@dataclass(frozen=True)
class PrecheckControlPlaneResult:
    """真实预检查客户端可以返回的低敏结构化快照。

    生产适配器通常直接把 Java ``SyncTaskExecutionPrecheckResponse`` 映射成此对象；测试替身也可以
    返回同字段的 Mapping。``can_start_execution`` 使用 ``None`` 表示“后端未提供该字段”，此时
    Agent 只根据状态和检查项推导安全默认值，不会把缺失字段当成无条件允许执行。
    """

    status: str = ""
    checks: tuple[PrecheckCheckItem | Mapping[str, Any], ...] = ()
    task_id: str | None = None
    can_start_execution: bool | None = None
    issue_codes: tuple[str, ...] = ()
    recommended_actions: tuple[str, ...] = ()
    configuration_steps: tuple[str, ...] = ()
    details_references: tuple[str, ...] = ()
    evidence_references: tuple[str, ...] = ()
    required_input_fields: tuple[str, ...] = ()
    invocation_summary: Mapping[str, Any] = field(default_factory=dict)
    precheck_status: str | None = None

    def __post_init__(self) -> None:
        """冻结控制面返回的顶层快照，但不把未知原始字段带入 Agent 合同。"""

        normalized_status = _text(self.precheck_status) or _text(self.status) or ""
        object.__setattr__(self, "status", normalized_status)
        object.__setattr__(self, "precheck_status", normalized_status)
        object.__setattr__(self, "task_id", _text(self.task_id))
        object.__setattr__(self, "checks", tuple(self.checks or ()))
        object.__setattr__(self, "issue_codes", _unique_text(self.issue_codes))
        object.__setattr__(self, "recommended_actions", _unique_text(self.recommended_actions))
        object.__setattr__(self, "configuration_steps", _unique_text(self.configuration_steps))
        object.__setattr__(self, "details_references", _unique_text(self.details_references))
        object.__setattr__(self, "evidence_references", _unique_text(self.evidence_references))
        object.__setattr__(self, "required_input_fields", _unique_text(self.required_input_fields))
        object.__setattr__(self, "invocation_summary", MappingProxyType(dict(self.invocation_summary or {})))


class PrecheckControlPlaneClient(Protocol):
    """真实、确定性的同步任务预检查客户端协议。

    该协议没有 ``save``、``publish`` 或 ``run`` 方法。生产实现可以通过 HTTP、gRPC 或内部
    service adapter 调用 data-sync，但必须只返回后端已经计算的低敏事实，不能把数据库原文直接
    透传给专业 Agent。
    """

    def precheck(self, request: PrecheckControlPlaneRequest) -> PrecheckControlPlaneResult:
        """按当前委派范围调用后端预检查并返回结构化结果。"""


@dataclass(frozen=True)
class PrecheckExplanationModelInput:
    """交给模型解释器的低敏输入。

    模型只看 ``checks``、问题码、控制面状态和执行闸门摘要；任务配置、SQL、凭据、样本数据以及
    控制面原始响应都不会进入此对象。模型没有字段可以重写检查状态或生成业务副作用。
    """

    objective: str
    # 审计范围由当前 SpecialistTurnRequest 现场生成，只会进入模型网关上下文，不会进入 public_payload。
    audit_scope: SpecialistAuditScope
    task_id: str | None
    precheck_status: str
    can_start_execution: bool
    checks: tuple[Mapping[str, Any], ...]
    issue_codes: tuple[str, ...]
    max_output_tokens: int

    def __post_init__(self) -> None:
        """冻结检查摘要，避免模型适配器修改后续结果所依赖的事实。"""

        object.__setattr__(self, "objective", _bounded_text(self.objective, 2_000))
        if not isinstance(self.audit_scope, SpecialistAuditScope):
            raise TypeError("PRECHECK 模型输入必须携带 SpecialistAuditScope")
        object.__setattr__(self, "task_id", _text(self.task_id))
        object.__setattr__(self, "precheck_status", _text(self.precheck_status) or "BLOCKED")
        object.__setattr__(self, "checks", tuple(MappingProxyType(dict(item)) for item in self.checks))
        object.__setattr__(self, "issue_codes", _unique_text(self.issue_codes))

    @property
    def check_results(self) -> tuple[Mapping[str, Any], ...]:
        """返回语义更明确的检查结果别名，保持模型适配器接口易读。"""

        return self.checks


@dataclass(frozen=True)
class PrecheckExplanationModelOutput:
    """模型生成的解释性文本，不承载任何新的检查事实。

    ``requested_tool_names``、``requested_actions`` 和 ``claims`` 不是正常输出字段，而是显式的
    治理探针：模型一旦返回它们，Agent 会拒绝整次解释，防止“解释模型”偷偷变成工具规划器。
    """

    public_summary: str = ""
    problems: tuple[str, ...] = ()
    suggestions: tuple[str, ...] = ()
    configuration_steps: tuple[str, ...] = ()
    details_references: tuple[str, ...] = ()
    invocation_summary: Mapping[str, Any] = field(default_factory=dict)
    requested_tool_names: tuple[str, ...] = ()
    requested_actions: tuple[str, ...] = ()
    claims: tuple[str, ...] = ()
    summary: str = ""
    recommendations: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        """把模型适配器输出固定为有限文本集合，后续只允许做内容安全检查。"""

        object.__setattr__(self, "public_summary", _text(self.public_summary) or _text(self.summary) or "")
        object.__setattr__(self, "problems", _unique_text(self.problems))
        object.__setattr__(self, "suggestions", _unique_text(self.suggestions))
        object.__setattr__(self, "configuration_steps", _unique_text(self.configuration_steps))
        object.__setattr__(self, "details_references", _unique_text(self.details_references))
        object.__setattr__(self, "requested_tool_names", _unique_text(self.requested_tool_names))
        object.__setattr__(self, "requested_actions", _unique_text(self.requested_actions))
        object.__setattr__(self, "claims", _unique_text(self.claims))
        object.__setattr__(self, "recommendations", _unique_text(self.recommendations))
        object.__setattr__(self, "invocation_summary", MappingProxyType(dict(self.invocation_summary or {})))


class PrecheckExplanationModel(Protocol):
    """可替换的预检查解释模型协议。

    模型提供方可以是 OpenAI-compatible、vLLM、本地模型或测试替身；它只能实现一次 ``explain``，
    不会获得控制面写客户端，也不能通过返回值改变后端检查项。
    """

    def explain(self, request: PrecheckExplanationModelInput) -> PrecheckExplanationModelOutput:
        """根据已验证的结构化检查摘要生成用户可读解释。"""


# 这些别名让上层接入方可以使用更短的协议名，同时保留完整名称帮助学习者理解边界。
PrecheckModelInput = PrecheckExplanationModelInput
PrecheckModelOutput = PrecheckExplanationModelOutput
PrecheckModel = PrecheckExplanationModel


@dataclass(frozen=True)
class _NormalizedCheck:
    """内部的低敏检查项快照，状态只来自控制面。"""

    code: str
    status: PrecheckCheckStatus
    problem: str | None
    suggestion: str | None
    configuration_steps: tuple[str, ...]
    details_reference: str | None

    def to_public_summary(self) -> dict[str, Any]:
        """生成不携带原始控制面字段的用户/主 Agent 视图。"""

        return {
            "code": self.code,
            "status": self.status.value,
            "problem": self.problem,
            "suggestion": self.suggestion,
            "configurationSteps": self.configuration_steps,
            "detailsReference": self.details_reference,
        }

    def to_model_summary(self) -> dict[str, Any]:
        """生成模型可见的同一份低敏事实摘要，禁止模型接触配置正文。"""

        return self.to_public_summary()


@dataclass(frozen=True)
class _NormalizedPrecheck:
    """控制面结果经过状态、文本和引用门控后的内部快照。"""

    status: PrecheckCheckStatus
    checks: tuple[_NormalizedCheck, ...]
    can_start_execution: bool
    explicit_can_start_execution: bool
    task_id: str | None
    issue_codes: tuple[str, ...]
    recommended_actions: tuple[str, ...]
    configuration_steps: tuple[str, ...]
    details_references: tuple[str, ...]
    evidence_references: tuple[str, ...]
    required_input_fields: tuple[str, ...]
    control_plane_invocation_summary: Mapping[str, Any]


class _ControlPlaneResponseError(ValueError):
    """内部异常：控制面返回了无法安全解释的结构。"""


class _ModelGovernanceError(ValueError):
    """内部异常：模型输出包含未授权事实或副作用建议。"""

    def __init__(self, code: str) -> None:
        """保存确定性治理错误码，调用方据此生成低敏失败结果而不回显模型正文。"""

        super().__init__(code)
        self.code = code


class PrecheckSpecialistAgent:
    """执行真实后端预检查的 PRECHECK_AGENT 专业实现。

    这类 Agent 的职责边界可以用一句话概括：**控制面判断，模型解释，主 Agent 决定下一步**。
    执行流程固定为：

    1. 校验 PRECHECK_AGENT 角色、预算和 ``sync.task.precheck`` 委派白名单；
    2. 从低敏 handoff 中解析 task/config，缺失时等待用户补充而不猜测；
    3. 通过注入的 ``PrecheckControlPlaneClient`` 调用真实后端预检查；
    4. 只把控制面返回的状态、问题码、建议和引用交给解释模型；
    5. 由确定性代码重新裁决最终状态和执行闸门，拒绝模型伪造的表、字段、主键或副作用结论。

    本类没有保存、发布、运行方法，也没有数据库客户端。即使模型或上层请求带入写工具名称，
    它们也不会进入控制面调用，从结构上保证“检查不通过不得保存/发布/执行”。
    """

    _ROLE = AgentSessionRole.PRECHECK_AGENT
    AGENT_ID = "precheck-specialist-v1"
    _TOOL_ALLOWLIST = frozenset({PRECHECK_TOOL_CODE})
    _EVENT_PAYLOAD_POLICY = "LOW_SENSITIVE_PRECHECK_SPECIALIST_EVENT_ONLY"
    _RESULT_PAYLOAD_POLICY = "LOW_SENSITIVE_PRECHECK_RESULT_ONLY"
    _SAFE_INVOCATION_SUMMARY_KEYS = frozenset(
        {
            "cachedPromptTokens",
            "completionTokens",
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
    _MODEL_FORBIDDEN_KEYS = frozenset(
        {
            "action",
            "actions",
            "canstartexecution",
            "check",
            "checks",
            "checkitems",
            "configuration",
            "config",
            "execute",
            "execution",
            "fieldmappingdeclared",
            "fieldpassed",
            "fields",
            "passed",
            "persist",
            "publish",
            "primarykeypassed",
            "precheckpassed",
            "precheckstatus",
            "run",
            "save",
            "status",
            "tablepassed",
            "targettablepassed",
            "taskid",
            "toolcall",
            "toolcalls",
        }
    )
    _SECRET_KEY_PARTS = (
        "apikey",
        "credential",
        "jdbcurl",
        "password",
        "privatekey",
        "refreshtoken",
        "secret",
        "token",
    )
    _SENSITIVE_TEXT_PATTERN = re.compile(
        r"(?is)(?:password|passwd|token|secret|api[_ -]?key|credential)\s*[:=]\s*[^,;\s]+"
    )
    _SQL_TEXT_PATTERN = re.compile(
        r"(?is)\b(?:select|with|insert|update|delete|drop|alter|truncate|create|merge)\b.*?\b(?:from|into|where|set|table)\b"
    )
    _SAMPLE_DATA_PATTERN = re.compile(r"(?is)(?:sample\s*rows?|row\s*data|样本行|示例行)\s*[:=].*")
    _UNTRUSTED_CLAIM_PATTERN = re.compile(
        r"(?is)(?:表|字段|列|主键|目标表|源表|table|field|column|primary\s*key|target\s*table)"
        r".{0,32}(?<!未)(?<!不)(?<!没)(?:通过|校验通过|已通过|正常|存在|可用|匹配成功|passed|verified|valid|exists|ready)"
    )
    _UNAUTHORIZED_ACTION_PATTERN = re.compile(
        r"(?is)(?:可以|允许|请|直接|现在|should|may|please|can)\s*.{0,18}"
        r"(?:保存|发布|执行|运行|写入|创建草稿|提交任务|save|publish|execute|run)"
    )

    def __init__(
        self,
        control_plane_client: PrecheckControlPlaneClient | None = None,
        explanation_model: PrecheckExplanationModel | None = None,
        *,
        model: PrecheckExplanationModel | None = None,
        precheck_client: PrecheckControlPlaneClient | None = None,
        client: PrecheckControlPlaneClient | None = None,
        agent_id: str = AGENT_ID,
    ) -> None:
        """创建一个只拥有预检查能力的专业 Agent。

        Args:
            control_plane_client: 调用 data-sync 真实预检查的确定性客户端。
            explanation_model: 只解释结构化检查结果的模型适配器；不允许接收任务配置正文。
            model: ``explanation_model`` 的兼容别名，便于与其他 specialist 的构造风格一致。
            precheck_client: ``control_plane_client`` 的兼容别名。
            client: 更短的控制面客户端别名。
            agent_id: 写入 turn 结果和低敏事件的稳定实例标识。

        Raises:
            ValueError: 缺少客户端、模型或 Agent 标识时拒绝启动，避免产生不可审计的匿名执行。
        """

        resolved_client = (
            control_plane_client
            if control_plane_client is not None
            else precheck_client if precheck_client is not None else client
        )
        resolved_model = explanation_model if explanation_model is not None else model
        if resolved_client is None:
            raise ValueError("PRECHECK_AGENT 必须注入 PrecheckControlPlaneClient")
        if resolved_model is None:
            raise ValueError("PRECHECK_AGENT 必须注入 PrecheckExplanationModel")
        normalized_agent_id = _text(agent_id)
        if not normalized_agent_id:
            raise ValueError("PRECHECK_AGENT 必须提供非空 agent_id")
        self._control_plane_client = resolved_client
        self._explanation_model = resolved_model
        self._agent_id = normalized_agent_id

    @property
    def role(self) -> AgentSessionRole:
        """返回该实例唯一负责的 PRECHECK_AGENT 角色。"""

        return self._ROLE

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None = None,
    ) -> SpecialistTurnResult:
        """在受控委派范围内完成一次真实预检查解释。

        后端已经发现失败项时返回 ``WAITING_FOR_INPUT``，因为用户需要根据问题和配置步骤修复草案；
        这不是 Python/模型技术故障。只有权限、预算、控制面、模型或响应合同异常才返回 ``FAILED``。
        无论哪种状态，结果中的 ``persisted``、``published`` 和 ``executed`` 都固定为 ``False``。
        """

        if request is None:
            raise ValueError("PRECHECK_AGENT execute request 不能为空")

        started_at = time.perf_counter()
        if request.role != self.role:
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code="PRECHECK_AGENT_ROLE_MISMATCH",
                summary="预检查专业 Agent 拒绝了不匹配的角色委派。",
            )

        # 预检查会把 task/config 交给项目级控制面执行。没有明确项目时，
        # 即使只是“只读检查”也可能读取其它项目的表、主键和目标表状态，因此必须在
        # 预算、白名单和控制面调用之前 fail-closed。
        if not _has_project_scope(request.scope.project_id):
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code="PRECHECK_PROJECT_SCOPE_REQUIRED",
                summary="预检查缺少明确项目范围，已停止访问同步任务控制面。",
            )

        budget_error = self._validate_budget(request)
        if budget_error is not None:
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code=budget_error,
                summary="预检查专业 Agent 的本轮预算无效，已停止调用工具和模型。",
            )

        if PRECHECK_TOOL_CODE not in self._authorized_tools(request):
            denied_activity = SpecialistToolActivity(
                tool_name=PRECHECK_TOOL_CODE,
                status="DENIED",
                public_summary="当前委派未授权真实同步任务预检查，已按 fail-closed 处理。",
            )
            self._emit(
                event_sink,
                request,
                action="SPECIALIST_TOOL_DENIED",
                status="DENIED",
                summary="本轮预检查工具未通过委派白名单校验，未访问后端控制面。",
                attributes={"errorCode": "PRECHECK_TOOL_NOT_AUTHORIZED"},
            )
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code="PRECHECK_TOOL_NOT_AUTHORIZED",
                summary="当前委派没有同步任务预检查权限，未访问后端控制面。",
                tool_activities=(denied_activity,),
            )

        self._emit(
            event_sink,
            request,
            action="SPECIALIST_STARTED",
            status="RUNNING",
            summary="预检查专业 Agent 已开始执行受控的只读预检查。",
        )
        self._emit(
            event_sink,
            request,
            action="TOOL_ALLOWLIST_CHECKED",
            status="SUCCEEDED",
            summary="本轮仅允许调用同步任务预检查只读工具。",
            attributes={"visibleToolCount": 1, "maxToolCalls": request.budget.max_tool_calls},
        )
        task_id, configuration = self._extract_task_and_configuration(request.context_summary)
        if task_id is None and configuration is None:
            return self._waiting_for_task_or_configuration(request, event_sink, started_at)

        control_request = PrecheckControlPlaneRequest(
            tenant_id=request.scope.tenant_id,
            application_id=request.scope.application_id,
            project_id=request.scope.project_id,
            actor_id=request.scope.actor_id,
            delegation_id=request.scope.delegation_id,
            turn_id=request.turn_id,
            run_id=request.run_id,
            task_id=task_id,
            configuration=configuration,
            timeout_ms=request.budget.timeout_ms,
        )
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TOOL_STARTED",
            status="RUNNING",
            summary="正在调用后端同步任务预检查控制面。",
        )
        tool_started_at = time.perf_counter()
        try:
            raw_control_plane_result = self._invoke_control_plane(control_request)
            normalized = self._normalize_control_plane_result(raw_control_plane_result, request)
        except _ControlPlaneResponseError:
            failed_activity = self._tool_activity(
                status="FAILED",
                summary="后端预检查返回的结构无法安全解析，已停止后续流程。",
                duration_ms=self._elapsed_ms(tool_started_at),
            )
            self._emit(
                event_sink,
                request,
                action="SPECIALIST_TOOL_COMPLETED",
                status="FAILED",
                summary="后端预检查返回的结构无法安全解析。",
            )
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code="PRECHECK_CONTROL_PLANE_INVALID_RESPONSE",
                summary="后端预检查响应不符合低敏结构化合同，未生成可执行结论。",
                tool_activities=(failed_activity,),
            )
        except Exception:
            # 真实异常可能包含 URL、SQL、凭据或服务端堆栈；这里只返回稳定错误码。
            failed_activity = self._tool_activity(
                status="FAILED",
                summary="后端同步任务预检查调用失败，未生成可执行结论。",
                duration_ms=self._elapsed_ms(tool_started_at),
            )
            self._emit(
                event_sink,
                request,
                action="SPECIALIST_TOOL_COMPLETED",
                status="FAILED",
                summary="后端同步任务预检查调用失败。",
            )
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code="PRECHECK_CONTROL_PLANE_FAILED",
                summary="后端同步任务预检查调用失败，请稍后重试或检查控制面状态。",
                tool_activities=(failed_activity,),
            )

        tool_activity = self._tool_activity(
            status="SUCCEEDED",
            summary=f"后端预检查已返回 {len(normalized.checks)} 项结构化检查结果。",
            evidence_reference=normalized.details_references[0] if normalized.details_references else None,
            duration_ms=self._elapsed_ms(tool_started_at),
        )
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TOOL_COMPLETED",
            status="SUCCEEDED",
            summary="后端预检查事实已返回，正在进入解释和最终闸门裁决。",
            attributes={
                "checkCount": len(normalized.checks),
                "passedCount": self._count_status(normalized.checks, PrecheckCheckStatus.PASSED),
                "warningCount": self._count_status(normalized.checks, PrecheckCheckStatus.WARNING),
                "failedCount": self._count_status(normalized.checks, PrecheckCheckStatus.FAILED),
                "blockedCount": self._count_status(normalized.checks, PrecheckCheckStatus.BLOCKED),
            },
        )

        try:
            # 这里必须从当前 request 生成新对象。不能从 Agent 单例、线程局部变量或上一次 turn
            # 的缓存读取范围，否则连续切换租户/项目时模型调用可能审计到错误的主体。
            audit_scope = request.audit_scope
            model_input = PrecheckExplanationModelInput(
                objective=self._safe_public_text(request.objective, 2_000),
                audit_scope=audit_scope,
                task_id=normalized.task_id,
                precheck_status=normalized.status.value,
                can_start_execution=normalized.can_start_execution,
                checks=tuple(item.to_model_summary() for item in normalized.checks),
                issue_codes=normalized.issue_codes,
                max_output_tokens=request.budget.max_output_tokens,
            )
        except (TypeError, ValueError):
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code="PRECHECK_MODEL_AUDIT_SCOPE_INVALID",
                summary="预检查解释模型缺少当前租户、项目、用户、会话或 turn 审计范围，已停止模型调用。",
                tool_activities=(tool_activity,),
            )
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_MODEL_STARTED",
            status="RUNNING",
            summary="正在让独立模型解释后端已返回的结构化检查结果。",
            attributes={"checkCount": len(model_input.checks)},
        )
        model_explanation_issue_codes: tuple[str, ...] = ()
        try:
            raw_model_output = self._invoke_explanation_model(model_input)
            model_output = self._coerce_model_output(raw_model_output)
            self._validate_model_output(model_output)
        except _ModelGovernanceError as error:
            self._emit(
                event_sink,
                request,
                action="SPECIALIST_MODEL_COMPLETED",
                status="WARNING",
                summary="模型解释越过事实或副作用边界，已丢弃解释并保留后端预检查结论。",
                attributes={"errorCode": error.code},
            )
            # The model never owns precheck facts. Once its output is quarantined, the already normalized
            # Java checks remain sufficient to produce the deterministic gate. Returning FAILED here would
            # make an optional explanation able to overturn a successful control-plane decision.
            model_output = PrecheckExplanationModelOutput()
            model_summary = self._failed_model_summary("governance_rejected")
            model_explanation_issue_codes = (error.code,)
        except Exception:
            self._emit(
                event_sink,
                request,
                action="SPECIALIST_MODEL_COMPLETED",
                status="WARNING",
                summary="预检查解释模型调用失败，已回退到后端确定性检查摘要。",
            )
            model_output = PrecheckExplanationModelOutput()
            model_summary = self._failed_model_summary("model_failed")
            model_explanation_issue_codes = ("PRECHECK_MODEL_EXPLANATION_FAILED",)
        else:
            model_summary = self._safe_model_invocation_summary(model_output)
            self._emit(
                event_sink,
                request,
                action="SPECIALIST_MODEL_COMPLETED",
                status="SUCCEEDED",
                summary="模型已完成低敏检查结果解释，最终状态仍以控制面事实为准。",
                attributes={
                    "modelName": model_summary.get("modelName"),
                    "requestedToolCount": 0,
                },
            )
        result = self._build_result(
            request=request,
            event_sink=event_sink,
            started_at=started_at,
            normalized=normalized,
            model_output=model_output,
            model_summary=model_summary,
            model_explanation_issue_codes=model_explanation_issue_codes,
            tool_activity=tool_activity,
        )
        return result

    def _build_result(
        self,
        *,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        started_at: float,
        normalized: _NormalizedPrecheck,
        model_output: PrecheckExplanationModelOutput,
        model_summary: Mapping[str, Any],
        model_explanation_issue_codes: tuple[str, ...],
        tool_activity: SpecialistToolActivity,
    ) -> SpecialistTurnResult:
        """用控制面事实构建最终结果，并把模型文本限制在解释性字段内。

        这里有意不读取模型的 ``status``、``canStartExecution`` 或任何模型生成的检查项；这些字段
        即使被恶意适配器返回，也没有进入 ``PrecheckExplanationModelOutput``。最终的失败/阻断计数、
        执行闸门和每项状态全部来自 ``normalized``。
        """

        failed_count = self._count_status(normalized.checks, PrecheckCheckStatus.FAILED)
        blocked_count = self._count_status(normalized.checks, PrecheckCheckStatus.BLOCKED)
        warning_count = self._count_status(normalized.checks, PrecheckCheckStatus.WARNING)
        passed_count = self._count_status(normalized.checks, PrecheckCheckStatus.PASSED)
        has_blocking_issue = failed_count > 0 or blocked_count > 0 or normalized.status in {
            PrecheckCheckStatus.FAILED,
            PrecheckCheckStatus.BLOCKED,
        }
        can_start_execution = bool(normalized.can_start_execution and not has_blocking_issue)
        turn_status = SpecialistTurnStatus.WAITING_FOR_INPUT if has_blocking_issue else SpecialistTurnStatus.COMPLETED

        problems = list(
            self._collect_check_text(normalized.checks, "problem", statuses={
                PrecheckCheckStatus.FAILED,
                PrecheckCheckStatus.BLOCKED,
                PrecheckCheckStatus.WARNING,
            })
        )
        suggestions = list(
            self._collect_check_text(normalized.checks, "suggestion", statuses={
                PrecheckCheckStatus.FAILED,
                PrecheckCheckStatus.BLOCKED,
                PrecheckCheckStatus.WARNING,
            })
        )
        suggestions.extend(normalized.recommended_actions)
        configuration_steps = list(normalized.configuration_steps)
        for item in normalized.checks:
            configuration_steps.extend(item.configuration_steps)

        # 模型只能补充解释性语言；它的文本已通过 claims/action 门禁，不会改变事实集合。
        if model_output.public_summary:
            model_explanation = self._safe_public_text(model_output.public_summary, 600)
        else:
            model_explanation = None
        problems.extend(self._safe_model_texts(model_output.problems))
        suggestions.extend(self._safe_model_texts(model_output.suggestions))
        suggestions.extend(self._safe_model_texts(model_output.recommendations))
        configuration_steps.extend(self._safe_model_texts(model_output.configuration_steps))

        if has_blocking_issue:
            if not configuration_steps:
                configuration_steps.append("返回同步任务配置步骤，修正未通过的检查项后重新执行预检查。")
            public_summary = f"后端预检查发现 {failed_count + blocked_count} 项阻断问题，未允许保存、发布或执行。"
        elif warning_count:
            public_summary = f"后端预检查已完成，存在 {warning_count} 项警告，请确认配置后再继续。"
        else:
            public_summary = "后端预检查已完成，返回的检查项均已通过。"
        if model_explanation:
            public_summary = f"{public_summary} {model_explanation}"

        check_summaries = tuple(item.to_public_summary() for item in normalized.checks)
        details_references = tuple(
            dict.fromkeys(
                reference
                for reference in (*normalized.details_references, *model_output.details_references)
                if reference in normalized.details_references
            )
        )
        structured_output = {
            "taskId": normalized.task_id,
            "precheckStatus": normalized.status.value,
            "controlPlaneStatus": normalized.status.value,
            "precheckPassed": normalized.status == PrecheckCheckStatus.PASSED and not has_blocking_issue,
            "canStartExecution": can_start_execution,
            "controlPlaneCanStartExecution": normalized.can_start_execution,
            "checks": check_summaries,
            "checkItems": check_summaries,
            "passedCount": passed_count,
            "warningCount": warning_count,
            "failedCount": failed_count,
            "blockedCount": blocked_count,
            "issueCodes": normalized.issue_codes,
            "problems": tuple(dict.fromkeys(problems)),
            "suggestions": tuple(dict.fromkeys(suggestions)),
            "recommendedActions": tuple(dict.fromkeys(suggestions)),
            "configurationSteps": tuple(dict.fromkeys(configuration_steps)),
            "detailsReferences": details_references,
            "requiredInputFields": normalized.required_input_fields,
            "modelExplanation": model_explanation,
            "modelExplanationStatus": (
                "FALLBACK_TO_CONTROL_PLANE_FACTS"
                if model_explanation_issue_codes
                else "ACCEPTED"
            ),
            "modelExplanationIssueCodes": model_explanation_issue_codes,
            "persisted": False,
            "published": False,
            "executed": False,
            "readOnly": True,
            "sideEffectsAllowed": False,
            "payloadPolicy": self._RESULT_PAYLOAD_POLICY,
        }
        duration_ms = self._elapsed_ms(started_at)
        result = SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=turn_status,
            public_summary=public_summary,
            structured_output=structured_output,
            evidence_references=tuple(
                dict.fromkeys((*request.evidence_references, *normalized.evidence_references, *details_references))
            ),
            tool_activities=(tool_activity,),
            model_invocation_summary=model_summary,
            required_input_fields=normalized.required_input_fields if has_blocking_issue else (),
            duration_ms=duration_ms,
        )
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TURN_COMPLETED",
            status=turn_status.value,
            summary=(
                "预检查存在未通过项，已关闭保存、发布和执行路径。"
                if has_blocking_issue
                else "预检查专业 Agent 已完成低敏结果解释。"
            ),
            attributes={
                "precheckStatus": normalized.status.value,
                "canStartExecution": can_start_execution,
                "failedCount": failed_count,
                "blockedCount": blocked_count,
                "warningCount": warning_count,
                "modelExplanationIssueCount": len(model_explanation_issue_codes),
            },
        )
        return result

    def _waiting_for_task_or_configuration(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        started_at: float,
    ) -> SpecialistTurnResult:
        """在没有可定位任务或配置时返回补参结果，避免向控制面发送空请求。"""

        activity = self._tool_activity(
            status="SKIPPED",
            summary="缺少任务引用或任务配置，未调用后端预检查。",
        )
        missing_fields = ("taskId", "taskConfig")
        summary = "缺少同步任务引用或配置，请返回配置步骤补充后再执行预检查。"
        structured_output = self._base_structured_output(
            task_id=None,
            precheck_status="BLOCKED",
            can_start_execution=False,
            checks=(),
            problems=(summary,),
            suggestions=("返回同步任务配置步骤，补充 taskId 或完整 taskConfig。",),
            configuration_steps=("补充 taskId 或完整 taskConfig 后重新执行预检查。",),
            details_references=request.evidence_references,
        )
        result = SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.WAITING_FOR_INPUT,
            public_summary=summary,
            structured_output=structured_output,
            evidence_references=request.evidence_references,
            tool_activities=(activity,),
            model_invocation_summary={"specialistModelInvoked": False, "rawModelOutputStored": False},
            required_input_fields=missing_fields,
            duration_ms=self._elapsed_ms(started_at),
        )
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TURN_WAITING",
            status=SpecialistTurnStatus.WAITING_FOR_INPUT.value,
            summary=summary,
            attributes={"requiredInputCount": len(missing_fields)},
        )
        return result

    def _normalize_control_plane_result(
        self,
        raw_result: PrecheckControlPlaneResult | Mapping[str, Any],
        request: SpecialistTurnRequest,
    ) -> _NormalizedPrecheck:
        """把控制面响应收敛为可信检查项，并拒绝未知状态或危险原始字段。

        这里允许兼容 Java DTO 的 camelCase、Python 测试替身的 snake_case 和少量 ``result`` 包装，
        但只读取显式白名单字段。任何未知检查状态都会失败关闭，而不是默默当成 PASSED。
        """

        result = self._coerce_control_plane_result(raw_result)
        top_status = self._normalize_status(result.status, allow_empty=True)
        raw_checks = result.checks
        normalized_checks: list[_NormalizedCheck] = []
        for index, raw_check in enumerate(raw_checks):
            item = self._coerce_check_item(raw_check)
            item_status = self._normalize_status(item.status or (top_status.value if top_status else ""))
            code = self._normalize_code(item.code or f"CHECK_{index + 1}")
            normalized_checks.append(
                _NormalizedCheck(
                    code=code,
                    status=item_status,
                    problem=self._safe_optional_text(item.problem, 600),
                    suggestion=self._safe_optional_text(item.suggestion, 600),
                    configuration_steps=self._safe_texts(item.configuration_steps, 400),
                    details_reference=self._safe_reference(item.details_reference),
                )
            )

        if not normalized_checks and result.issue_codes:
            fallback_status = top_status or PrecheckCheckStatus.FAILED
            if fallback_status == PrecheckCheckStatus.PASSED:
                fallback_status = PrecheckCheckStatus.WARNING
            normalized_checks = [
                _NormalizedCheck(
                    code=self._normalize_code(code),
                    status=fallback_status,
                    problem="后端返回了需要关注的问题码。",
                    suggestion=None,
                    configuration_steps=(),
                    details_reference=None,
                )
                for code in result.issue_codes
            ]

        if top_status is None:
            top_status = self._derive_status(normalized_checks, result.can_start_execution)
        # 兼容只返回顶层状态的旧 DTO：已知顶层状态时，空 checks 不能被误判为 BLOCKED。
        derived_status = (
            self._derive_status(normalized_checks, result.can_start_execution)
            if normalized_checks
            else top_status
        )
        # 子项是更细的事实；即使顶层 status 被错误标成 PASSED，也不能覆盖 FAILED/BLOCKED。
        effective_status = self._more_restrictive_status(top_status, derived_status)
        explicit_can_start = result.can_start_execution is not None
        if (
            explicit_can_start
            and result.can_start_execution is False
            and effective_status == PrecheckCheckStatus.PASSED
        ):
            # 控制面明确关闭执行闸门时，不能把“所有已返回子项通过”解释为可执行成功。
            effective_status = PrecheckCheckStatus.BLOCKED
            normalized_checks.append(
                _NormalizedCheck(
                    code="EXECUTION_NOT_ALLOWED_BY_CONTROL_PLANE",
                    status=PrecheckCheckStatus.BLOCKED,
                    problem="控制面明确未开放当前任务的执行闸门。",
                    suggestion="返回配置步骤确认审批、运行时能力或其他控制面条件。",
                    configuration_steps=(),
                    details_reference=None,
                )
            )
        elif top_status in {
            PrecheckCheckStatus.WARNING,
            PrecheckCheckStatus.FAILED,
            PrecheckCheckStatus.BLOCKED,
        } and not any(item.status == top_status for item in normalized_checks):
            # 某些旧 DTO 只有顶层状态和 issueCodes，没有 checks 数组；补一项低敏状态事实，
            # 让用户看到“为什么等待”而不是得到空的成功列表。
            normalized_checks.append(
                _NormalizedCheck(
                    code="PRECHECK_STATUS_" + top_status.value,
                    status=top_status,
                    problem=(
                        "后端预检查存在需要关注的问题。"
                        if top_status == PrecheckCheckStatus.WARNING
                        else "后端预检查未返回可继续执行的通过结果。"
                    ),
                    suggestion="返回配置步骤检查后端预检查问题码，并在修复后重试。",
                    configuration_steps=(),
                    details_reference=None,
                )
            )
        if not normalized_checks and effective_status in {
            PrecheckCheckStatus.FAILED,
            PrecheckCheckStatus.BLOCKED,
        }:
            normalized_checks.append(
                _NormalizedCheck(
                    code="PRECHECK_STATUS_BLOCKED",
                    status=effective_status,
                    problem="后端预检查未返回可继续执行的通过结果。",
                    suggestion="返回配置步骤检查后端预检查问题码，并在修复后重试。",
                    configuration_steps=(),
                    details_reference=None,
                )
            )

        if explicit_can_start:
            can_start = bool(result.can_start_execution)
        else:
            can_start = effective_status in {PrecheckCheckStatus.PASSED, PrecheckCheckStatus.WARNING}
        if effective_status in {PrecheckCheckStatus.FAILED, PrecheckCheckStatus.BLOCKED}:
            can_start = False

        check_codes = tuple(item.code for item in normalized_checks if item.status != PrecheckCheckStatus.PASSED)
        issue_codes = tuple(
            dict.fromkeys(
                self._normalize_code(code)
                for code in (*result.issue_codes, *check_codes)
                if _text(code)
            )
        )
        recommended_actions = self._safe_texts(result.recommended_actions, 600)
        configuration_steps = self._safe_texts(result.configuration_steps, 500)
        details_references = tuple(
            dict.fromkeys(
                reference
                for reference in (
                    *(self._safe_references(result.details_references)),
                    *(item.details_reference for item in normalized_checks),
                )
                if reference
            )
        )
        evidence_references = tuple(
            dict.fromkeys(
                reference
                for reference in (*self._safe_references(result.evidence_references), *details_references)
                if reference
            )
        )
        required_input_fields = tuple(
            dict.fromkeys(field_name for field_name in self._safe_texts(result.required_input_fields, 160))
        )
        return _NormalizedPrecheck(
            status=effective_status,
            checks=tuple(normalized_checks),
            can_start_execution=can_start,
            explicit_can_start_execution=explicit_can_start,
            task_id=result.task_id or self._extract_task_and_configuration(request.context_summary)[0],
            issue_codes=issue_codes,
            recommended_actions=recommended_actions,
            configuration_steps=configuration_steps,
            details_references=details_references,
            evidence_references=evidence_references,
            required_input_fields=required_input_fields,
            control_plane_invocation_summary=self._safe_control_plane_summary(result.invocation_summary),
        )

    @classmethod
    def _coerce_control_plane_result(
        cls,
        raw_result: PrecheckControlPlaneResult | Mapping[str, Any],
    ) -> PrecheckControlPlaneResult:
        """兼容协议对象与受限 Mapping，同时不接受原始响应作为公开结果。"""

        if isinstance(raw_result, PrecheckControlPlaneResult):
            return raw_result
        if not isinstance(raw_result, MappingABC):
            raise _ControlPlaneResponseError("control plane result must be a mapping")
        payload: Mapping[str, Any] = raw_result
        for nested_key in ("result", "data", "precheck"):
            nested = payload.get(nested_key)
            if isinstance(nested, MappingABC) and any(
                key in nested for key in ("status", "precheckStatus", "checks", "checkItems", "issueCodes")
            ):
                payload = nested
                break
        raw_checks = payload.get("checks") or payload.get("checkItems") or payload.get("items") or ()
        if isinstance(raw_checks, MappingABC):
            raw_checks = (raw_checks,)
        if not isinstance(raw_checks, (list, tuple)):
            raise _ControlPlaneResponseError("checks must be a sequence")
        can_start = cls._optional_bool(
            payload.get("canStartExecution")
            if "canStartExecution" in payload
            else payload.get("can_start_execution")
        )
        return PrecheckControlPlaneResult(
            status=_text(
                payload.get("precheckStatus")
                or payload.get("precheck_status")
                or payload.get("status")
                or payload.get("decision")
                or ""
            )
            or "",
            checks=tuple(raw_checks),
            task_id=_text(payload.get("taskId") or payload.get("task_id")),
            can_start_execution=can_start,
            issue_codes=cls._text_values(payload.get("issueCodes") or payload.get("issue_codes")),
            recommended_actions=cls._text_values(
                payload.get("recommendedActions") or payload.get("recommended_actions")
            ),
            configuration_steps=cls._text_values(
                payload.get("configurationSteps") or payload.get("configuration_steps")
            ),
            details_references=cls._text_values(
                payload.get("detailsReferences")
                or payload.get("details_references")
                or payload.get("detailsRefs")
            ),
            evidence_references=cls._text_values(
                payload.get("evidenceReferences") or payload.get("evidence_references")
            ),
            required_input_fields=cls._text_values(
                payload.get("requiredInputFields") or payload.get("required_input_fields")
            ),
            invocation_summary=(
                payload.get("invocationSummary")
                if isinstance(payload.get("invocationSummary"), MappingABC)
                else {}
            ),
        )

    @classmethod
    def _coerce_check_item(cls, raw_check: PrecheckCheckItem | Mapping[str, Any]) -> PrecheckCheckItem:
        """读取检查项字段白名单，明确丢弃 SQL、样本和原始异常等控制面扩展字段。"""

        if isinstance(raw_check, PrecheckCheckItem):
            return raw_check
        if not isinstance(raw_check, MappingABC):
            raise _ControlPlaneResponseError("check item must be a mapping")
        raw_steps = (
            raw_check.get("configurationSteps")
            or raw_check.get("configuration_steps")
            or raw_check.get("nextSteps")
            or ()
        )
        if isinstance(raw_steps, str):
            raw_steps = (raw_steps,)
        if not isinstance(raw_steps, (list, tuple)):
            raise _ControlPlaneResponseError("configuration steps must be a sequence")
        return PrecheckCheckItem(
            code=_text(
                raw_check.get("code")
                or raw_check.get("issueCode")
                or raw_check.get("checkCode")
                or raw_check.get("name")
            )
            or "",
            status=_text(raw_check.get("status") or raw_check.get("checkStatus") or raw_check.get("level")) or "",
            problem=_text(
                raw_check.get("problem")
                or raw_check.get("message")
                or raw_check.get("description")
                or raw_check.get("reason")
            )
            or "",
            suggestion=_text(
                raw_check.get("suggestion")
                or raw_check.get("recommendedAction")
                or raw_check.get("recommendation")
                or raw_check.get("action")
            )
            or "",
            configuration_steps=tuple(str(item) for item in raw_steps if _text(item)),
            details_reference=_text(
                raw_check.get("detailsReference")
                or raw_check.get("detailsRef")
                or raw_check.get("evidenceReference")
                or raw_check.get("reference")
            ),
        )

    def _invoke_control_plane(self, client_request: PrecheckControlPlaneRequest) -> Any:
        """从注入实例调用客户端的只读 ``precheck`` 方法，不提供任何写操作 fallback。"""

        # 生产协议名是 precheck；check 是为了兼容已存在的极简测试/适配器，不会改变权限边界。
        client = self._control_plane_client
        method = getattr(client, "precheck", None) or getattr(client, "check", None)
        if not callable(method) and callable(client):
            method = client
        if not callable(method):
            raise _ControlPlaneResponseError("PrecheckControlPlaneClient has no precheck method")
        return method(client_request)

    def _invoke_explanation_model(self, model_request: PrecheckExplanationModelInput) -> Any:
        """调用只读解释模型；模型没有工具执行器或控制面客户端引用。"""

        method = (
            getattr(self._explanation_model, "explain", None)
            or getattr(self._explanation_model, "interpret", None)
            or getattr(self._explanation_model, "summarize", None)
        )
        if not callable(method):
            raise TypeError("PrecheckExplanationModel has no explain method")
        return method(model_request)

    @classmethod
    def _normalize_status(cls, value: Any, *, allow_empty: bool = False) -> PrecheckCheckStatus | None:
        """把后端常见别名规范为四种检查状态，未知值始终拒绝。"""

        normalized = _text(value)
        if not normalized:
            if allow_empty:
                return None
            raise _ControlPlaneResponseError("precheck status is missing")
        normalized = normalized.upper().replace("-", "_").replace(" ", "_")
        aliases = {
            "PASS": PrecheckCheckStatus.PASSED,
            "SUCCESS": PrecheckCheckStatus.PASSED,
            "SUCCEEDED": PrecheckCheckStatus.PASSED,
            "READY": PrecheckCheckStatus.PASSED,
            "READY_TO_EXECUTE": PrecheckCheckStatus.PASSED,
            "COMPLETED": PrecheckCheckStatus.PASSED,
            "FAIL": PrecheckCheckStatus.FAILED,
            "ERROR": PrecheckCheckStatus.FAILED,
            "REJECTED": PrecheckCheckStatus.FAILED,
            "REQUIRES_APPROVAL": PrecheckCheckStatus.WARNING,
            "WARN": PrecheckCheckStatus.WARNING,
            "DEGRADED": PrecheckCheckStatus.WARNING,
            "NOT_SUPPORTED_BY_CURRENT_RUNNER": PrecheckCheckStatus.BLOCKED,
            "BLOCK": PrecheckCheckStatus.BLOCKED,
        }
        try:
            aliased = aliases.get(normalized)
            return aliased if aliased is not None else PrecheckCheckStatus(normalized)
        except ValueError as error:
            raise _ControlPlaneResponseError("unknown precheck status") from error

    @staticmethod
    def _derive_status(
        checks: list[_NormalizedCheck] | tuple[_NormalizedCheck, ...],
        can_start_execution: bool | None,
    ) -> PrecheckCheckStatus:
        """按最严格子项推导缺失的顶层状态，防止空值默认成通过。"""

        statuses = {item.status for item in checks}
        if PrecheckCheckStatus.BLOCKED in statuses:
            return PrecheckCheckStatus.BLOCKED
        if PrecheckCheckStatus.FAILED in statuses:
            return PrecheckCheckStatus.FAILED
        if PrecheckCheckStatus.WARNING in statuses:
            return PrecheckCheckStatus.WARNING
        if checks:
            return PrecheckCheckStatus.PASSED
        return PrecheckCheckStatus.PASSED if can_start_execution is True else PrecheckCheckStatus.BLOCKED

    @staticmethod
    def _more_restrictive_status(
        first: PrecheckCheckStatus,
        second: PrecheckCheckStatus,
    ) -> PrecheckCheckStatus:
        """合并顶层和子项状态，优先保留 FAILED/BLOCKED 等更严格结论。"""

        rank = {
            PrecheckCheckStatus.PASSED: 0,
            PrecheckCheckStatus.WARNING: 1,
            PrecheckCheckStatus.FAILED: 2,
            PrecheckCheckStatus.BLOCKED: 3,
        }
        return first if rank[first] >= rank[second] else second

    @classmethod
    def _coerce_model_output(cls, raw_output: Any) -> PrecheckExplanationModelOutput:
        """只接收解释字段，拒绝模型返回检查状态、配置或副作用字段。"""

        if isinstance(raw_output, PrecheckExplanationModelOutput):
            return raw_output
        if isinstance(raw_output, str):
            return PrecheckExplanationModelOutput(public_summary=raw_output)
        if not isinstance(raw_output, MappingABC):
            raise TypeError("PrecheckExplanationModel 必须返回 PrecheckExplanationModelOutput 或 Mapping")
        forbidden = {
            cls._normalized_key(key)
            for key in raw_output
            if cls._normalized_key(key) in cls._MODEL_FORBIDDEN_KEYS
        }
        if forbidden:
            raise _ModelGovernanceError("PRECHECK_MODEL_UNTRUSTED_FACT_CLAIM")
        requested_tools = cls._text_values(raw_output.get("requestedToolNames") or raw_output.get("toolNames"))
        requested_actions = cls._text_values(
            raw_output.get("requestedActions") or raw_output.get("actions")
        )
        claims = cls._text_values(raw_output.get("claims"))
        problems = cls._text_values(raw_output.get("problems") or raw_output.get("issues"))
        suggestions = cls._text_values(raw_output.get("suggestions"))
        recommendations = cls._text_values(
            raw_output.get("recommendations") or raw_output.get("recommendedActions")
        )
        steps = cls._text_values(raw_output.get("configurationSteps") or raw_output.get("configuration_steps"))
        details = cls._text_values(raw_output.get("detailsReferences") or raw_output.get("details_refs"))
        invocation_summary = (
            raw_output.get("invocationSummary")
            if isinstance(raw_output.get("invocationSummary"), MappingABC)
            else {}
        )
        return PrecheckExplanationModelOutput(
            public_summary=_text(
                raw_output.get("publicSummary") or raw_output.get("summary") or raw_output.get("explanation")
            )
            or "",
            problems=problems,
            suggestions=suggestions,
            configuration_steps=steps,
            details_references=details,
            invocation_summary=invocation_summary,
            requested_tool_names=requested_tools,
            requested_actions=requested_actions,
            claims=claims,
            recommendations=recommendations,
        )

    @classmethod
    def _validate_model_output(cls, output: PrecheckExplanationModelOutput) -> None:
        """检查模型解释是否越过事实和副作用边界，越界时拒绝整次解释。"""

        if output.requested_tool_names or output.requested_actions:
            raise _ModelGovernanceError("PRECHECK_MODEL_UNAUTHORIZED_ACTION")
        if output.claims:
            raise _ModelGovernanceError("PRECHECK_MODEL_UNTRUSTED_FACT_CLAIM")
        texts = (
            output.public_summary,
            *output.problems,
            *output.suggestions,
            *output.recommendations,
            *output.configuration_steps,
        )
        if any(cls._contains_untrusted_claim(text) for text in texts):
            raise _ModelGovernanceError("PRECHECK_MODEL_UNTRUSTED_FACT_CLAIM")
        if any(cls._contains_unauthorized_action(text) for text in texts):
            raise _ModelGovernanceError("PRECHECK_MODEL_UNAUTHORIZED_ACTION")

    @classmethod
    def _safe_model_invocation_summary(cls, output: PrecheckExplanationModelOutput) -> dict[str, Any]:
        """只保留模型调用计量和路由摘要，不暴露 prompt、原文响应或隐藏思维链。"""

        summary = {
            key: value
            for key, value in output.invocation_summary.items()
            if key in cls._SAFE_INVOCATION_SUMMARY_KEYS and cls._is_scalar(value)
        }
        summary.update(
            {
                "specialistModelInvoked": True,
                "independentInvocation": True,
                "invocationCount": 1,
                "requestedToolCount": 0,
                "rawModelOutputStored": False,
                "reasoningStored": False,
                "publicResponseSummary": cls._safe_public_text(output.public_summary, 300)
                if output.public_summary
                else None,
            }
        )
        return summary

    @classmethod
    def _safe_control_plane_summary(cls, summary: Mapping[str, Any]) -> dict[str, Any]:
        """过滤控制面调用摘要，只留下低敏计量字段，避免把 HTTP/SQL 原文带回结果。"""

        return {
            key: value
            for key, value in summary.items()
            if key in cls._SAFE_INVOCATION_SUMMARY_KEYS and cls._is_scalar(value)
        }

    @classmethod
    def _validate_budget(cls, request: SpecialistTurnRequest) -> str | None:
        """在任何外部调用前校验 timeout、工具调用、模型调用和输出 token 预算。"""

        try:
            timeout_ms = request.budget.timeout_ms
            max_tool_calls = request.budget.max_tool_calls
            max_model_invocations = request.budget.max_model_invocations
            max_output_tokens = request.budget.max_output_tokens
        except Exception:
            return "PRECHECK_BUDGET_INVALID"
        if isinstance(timeout_ms, bool) or not isinstance(timeout_ms, int) or not 1_000 <= timeout_ms <= 300_000:
            return "PRECHECK_BUDGET_INVALID"
        if isinstance(max_tool_calls, bool) or not isinstance(max_tool_calls, int) or not 1 <= max_tool_calls <= 32:
            return "PRECHECK_BUDGET_INVALID"
        if (
            isinstance(max_model_invocations, bool)
            or not isinstance(max_model_invocations, int)
            or not 1 <= max_model_invocations <= 8
        ):
            return "PRECHECK_BUDGET_INVALID"
        if (
            isinstance(max_output_tokens, bool)
            or not isinstance(max_output_tokens, int)
            or not 128 <= max_output_tokens <= 32_768
        ):
            return "PRECHECK_BUDGET_INVALID"
        return None

    @classmethod
    def _authorized_tools(cls, request: SpecialistTurnRequest) -> tuple[str, ...]:
        """求角色白名单与本轮 delegation 白名单交集，拒绝把其他工具传给模型或客户端。"""

        return tuple(sorted(cls._TOOL_ALLOWLIST.intersection(request.scope.allowed_tool_names)))

    @classmethod
    def _extract_task_and_configuration(
        cls,
        context: Mapping[str, Any],
    ) -> tuple[str | None, Mapping[str, Any] | None]:
        """从 handoff 兼容键中提取任务定位和配置，但不把任意文本误认为完整配置。"""

        context = context if isinstance(context, MappingABC) else {}
        task_id = cls._first_identifier(
            context.get("taskId"),
            context.get("task_id"),
            context.get("taskRef"),
            context.get("task_ref"),
            context.get("draftRef"),
            context.get("draft_ref"),
        )
        task_value = context.get("task")
        if isinstance(task_value, MappingABC):
            task_id = task_id or cls._first_identifier(task_value.get("taskId"), task_value.get("id"))
        elif task_value is not None:
            task_id = task_id or cls._first_identifier(task_value)

        configuration: Mapping[str, Any] | None = None
        for key in (
            "taskConfig",
            "task_config",
            "configuration",
            "config",
            "draftConfiguration",
            "draft_configuration",
        ):
            candidate = context.get(key)
            if isinstance(candidate, MappingABC) and candidate:
                configuration = candidate
                break
        if configuration is None and isinstance(task_value, MappingABC):
            for key in ("taskConfig", "task_config", "configuration", "config"):
                candidate = task_value.get(key)
                if isinstance(candidate, MappingABC) and candidate:
                    configuration = candidate
                    break

        # PRECHECK_AGENT 在协作图中依赖 DATA_SYNC_AGENT。协调器把上游专业结果放在
        # dependencyResults 中，因此这里必须读取已经完成确定性完整性校验的 structuredOutput；否则
        # 用户明明已经由同步规划 Agent 形成完整映射，预检查仍会错误提示“缺少 taskConfig”。该配置只
        # 存活于当前进程 turn，并直接交给控制面客户端，不进入模型解释、事件或 Durable 事实表。
        if configuration is None:
            dependency_results = context.get("dependencyResults") or context.get("dependency_results")
            if isinstance(dependency_results, MappingABC):
                sync_result = dependency_results.get(AgentSessionRole.DATA_SYNC_AGENT.value)
                if isinstance(sync_result, MappingABC):
                    candidate = sync_result.get("structuredOutput") or sync_result.get("structured_output")
                    if isinstance(candidate, MappingABC) and candidate:
                        configuration = candidate
                        task_id = task_id or cls._first_identifier(
                            candidate.get("taskId"),
                            candidate.get("task_id"),
                        )
        if task_id is None and configuration is not None:
            task_id = cls._first_identifier(configuration.get("taskId"), configuration.get("task_id"))
        return task_id, configuration

    def _failed_result(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        started_at: float,
        *,
        error_code: str,
        summary: str,
        tool_activities: tuple[SpecialistToolActivity, ...] = (),
        model_invocation_summary: Mapping[str, Any] | None = None,
    ) -> SpecialistTurnResult:
        """统一构造技术失败结果，并确保失败路径也留下低敏结束事件。"""

        duration_ms = self._elapsed_ms(started_at)
        result = SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.FAILED,
            public_summary=summary,
            structured_output=self._base_structured_output(
                task_id=None,
                precheck_status="BLOCKED",
                can_start_execution=False,
                checks=(),
                problems=(summary,),
                suggestions=("检查权限、预算或控制面状态后重试。",),
                configuration_steps=(),
                details_references=request.evidence_references,
            ),
            evidence_references=request.evidence_references,
            tool_activities=tool_activities,
            model_invocation_summary=dict(model_invocation_summary or {}),
            error_code=error_code,
            duration_ms=duration_ms,
        )
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_TURN_FAILED",
            status=SpecialistTurnStatus.FAILED.value,
            summary=summary,
            attributes={"errorCode": error_code, "durationMs": duration_ms},
        )
        return result

    @classmethod
    def _base_structured_output(
        cls,
        *,
        task_id: str | None,
        precheck_status: str,
        can_start_execution: bool,
        checks: tuple[Mapping[str, Any], ...],
        problems: tuple[str, ...],
        suggestions: tuple[str, ...],
        configuration_steps: tuple[str, ...],
        details_references: tuple[str, ...],
    ) -> dict[str, Any]:
        """生成所有等待/失败路径共享的只读结构，避免错误分支遗漏执行闸门。"""

        return {
            "taskId": task_id,
            "precheckStatus": precheck_status,
            "controlPlaneStatus": precheck_status,
            "precheckPassed": False,
            "canStartExecution": can_start_execution,
            "controlPlaneCanStartExecution": False,
            "checks": checks,
            "checkItems": checks,
            "passedCount": 0,
            "warningCount": 0,
            "failedCount": 0,
            "blockedCount": 1,
            "issueCodes": (),
            "problems": problems,
            "suggestions": suggestions,
            "recommendedActions": suggestions,
            "configurationSteps": configuration_steps,
            "detailsReferences": details_references,
            "requiredInputFields": (),
            "modelExplanation": None,
            "persisted": False,
            "published": False,
            "executed": False,
            "readOnly": True,
            "sideEffectsAllowed": False,
            "payloadPolicy": cls._RESULT_PAYLOAD_POLICY,
        }

    def _tool_activity(
        self,
        *,
        status: str,
        summary: str,
        evidence_reference: str | None = None,
        duration_ms: int = 0,
    ) -> SpecialistToolActivity:
        """创建只描述工具名称、状态、耗时和引用的低敏工具活动。"""

        return SpecialistToolActivity(
            tool_name=PRECHECK_TOOL_CODE,
            status=status,
            public_summary=summary,
            evidence_reference=evidence_reference,
            duration_ms=duration_ms,
        )

    @classmethod
    def _safe_model_texts(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        """清理模型补充文本；最终真值仍不来自这些文本。"""

        return tuple(
            value
            for value in (cls._safe_public_text(item, 600) for item in values)
            if value
        )

    @classmethod
    def _collect_check_text(
        cls,
        checks: tuple[_NormalizedCheck, ...],
        field_name: str,
        *,
        statuses: set[PrecheckCheckStatus],
    ) -> tuple[str, ...]:
        """只收集非通过检查项的问题/建议，避免把通过事实改写成模型结论。"""

        return tuple(
            dict.fromkeys(
                value
                for item in checks
                if item.status in statuses
                for value in (getattr(item, field_name),)
                if value
            )
        )

    @classmethod
    def _safe_texts(cls, values: Any, limit: int) -> tuple[str, ...]:
        """把外部字符串列表限制为有界低敏文本，忽略嵌套原始对象。"""

        if isinstance(values, str):
            values = (values,)
        if not isinstance(values, (list, tuple, set)):
            return ()
        return tuple(
            dict.fromkeys(
                value
                for value in (cls._safe_public_text(item, limit) for item in values if isinstance(item, str))
                if value
            )
        )

    @classmethod
    def _safe_references(cls, values: Any) -> tuple[str, ...]:
        """保留可追溯详情引用，拒绝把 URL、Token 或 SQL 文本误当成引用。"""

        if isinstance(values, str):
            values = (values,)
        if not isinstance(values, (list, tuple, set)):
            return ()
        return tuple(dict.fromkeys(reference for reference in (cls._safe_reference(item) for item in values) if reference))

    @classmethod
    def _safe_reference(cls, value: Any) -> str | None:
        """限制详情引用长度和字符风险，引用本身不展开为详情正文。"""

        reference = _text(value)
        if not reference or len(reference) > 256:
            return None
        if cls._SENSITIVE_TEXT_PATTERN.search(reference) or cls._SQL_TEXT_PATTERN.search(reference):
            return None
        return reference

    @classmethod
    def _safe_public_text(cls, value: Any, limit: int) -> str:
        """清理用户可见文字，至少移除凭据、连接串、SQL 原文和无界长度。"""

        text = _bounded_text(value, limit)
        if not text:
            return ""
        if cls._SQL_TEXT_PATTERN.search(text):
            return "[OMITTED_SQL]"
        if cls._SAMPLE_DATA_PATTERN.search(text):
            return "[OMITTED_DATA_ROWS]"
        text = cls._SENSITIVE_TEXT_PATTERN.sub("[REDACTED_SECRET]", text)
        text = re.sub(r"(?is)\bjdbc:[^\s,;]+", "[REDACTED_CONNECTION]", text)
        return text[:limit]

    @classmethod
    def _safe_optional_text(cls, value: Any, limit: int) -> str | None:
        """把空字符串统一为空引用，便于公开 JSON 稳定表示。"""

        text = cls._safe_public_text(value, limit)
        return text or None

    @classmethod
    def _contains_untrusted_claim(cls, value: Any) -> bool:
        """识别模型对表、字段、主键或目标表状态的未经控制面授权断言。"""

        return bool(value and cls._UNTRUSTED_CLAIM_PATTERN.search(str(value)))

    @classmethod
    def _contains_unauthorized_action(cls, value: Any) -> bool:
        """识别模型试图把解释变成保存、发布、运行或执行建议的文本。"""

        return bool(value and cls._UNAUTHORIZED_ACTION_PATTERN.search(str(value)))

    @staticmethod
    def _first_identifier(*values: Any) -> str | None:
        """从 ID 或低敏引用对象提取稳定定位符，不接受任意嵌套配置作为任务 ID。"""

        for value in values:
            if isinstance(value, MappingABC):
                value = value.get("taskId") or value.get("task_id") or value.get("id") or value.get("ref")
            if isinstance(value, bool):
                continue
            normalized = _text(value)
            if normalized:
                return normalized
        return None

    @staticmethod
    def _text_values(value: Any) -> tuple[str, ...]:
        """读取外部文本数组但不展开对象，供后续统一安全清洗。"""

        if isinstance(value, str):
            return (value,)
        if not isinstance(value, (list, tuple, set)):
            return ()
        return tuple(str(item) for item in value if isinstance(item, str) and _text(item))

    @staticmethod
    def _optional_bool(value: Any) -> bool | None:
        """解析控制面布尔字段；未知文本不静默当成 True。"""

        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized in {"true", "1", "yes"}:
                return True
            if normalized in {"false", "0", "no"}:
                return False
        if isinstance(value, int) and value in {0, 1}:
            return bool(value)
        return None

    @classmethod
    def _normalize_code(cls, value: Any) -> str:
        """规范化问题码并限制字符，避免把异常正文放进 issueCodes。"""

        code = _text(value)
        if not code:
            raise _ControlPlaneResponseError("check code is missing")
        normalized = re.sub(r"[^A-Za-z0-9_.:-]", "_", code.upper())[:120]
        if not normalized:
            raise _ControlPlaneResponseError("check code is invalid")
        return normalized

    @staticmethod
    def _count_status(checks: tuple[_NormalizedCheck, ...], status: PrecheckCheckStatus) -> int:
        """计算公开统计数，统计只来源于已经校验的检查项。"""

        return sum(1 for item in checks if item.status == status)

    @classmethod
    def _failed_model_summary(cls, reason: str) -> dict[str, Any]:
        """生成模型异常时的低敏摘要，不携带异常正文或原始响应。"""

        return {
            "specialistModelInvoked": True,
            "independentInvocation": True,
            "invocationCount": 1,
            "requestedToolCount": 0,
            "rawModelOutputStored": False,
            "reasoningStored": False,
            "failureReason": reason,
        }

    def _emit(
        self,
        event_sink: SpecialistEventSink | None,
        request: SpecialistTurnRequest,
        *,
        action: str,
        status: str,
        summary: str,
        attributes: Mapping[str, Any] | None = None,
    ) -> None:
        """发布前端/审计可消费的低敏事件，旁路事件异常不会改变业务结果。"""

        if event_sink is None:
            return
        event = {
            "eventType": "SPECIALIST_AGENT_ACTION",
            "agentId": self._agent_id,
            "agentRole": self.role.value,
            "turnId": request.turn_id,
            "runId": request.run_id,
            "action": action,
            "status": status,
            "publicSummary": self._safe_public_text(summary, 600),
            "attributes": {
                key: value
                for key, value in (attributes or {}).items()
                if self._is_scalar(value) and (not isinstance(value, str) or len(value) <= 256)
            },
            "payloadPolicy": self._EVENT_PAYLOAD_POLICY,
        }
        try:
            event_sink(event)
        except Exception:
            return

    @staticmethod
    def _elapsed_ms(started_at: float) -> int:
        """以单调时钟计算耗时，避免系统时间调整造成负数审计字段。"""

        return max(0, int((time.perf_counter() - started_at) * 1_000))

    @staticmethod
    def _is_scalar(value: Any) -> bool:
        """事件和调用摘要只允许低风险标量，不接受嵌套工具响应。"""

        return value is None or isinstance(value, (str, int, float, bool))

    @staticmethod
    def _normalized_key(value: Any) -> str:
        """移除分隔符并转小写，用于跨 camelCase/snake_case 执行字段门控。"""

        return re.sub(r"[^a-z0-9]", "", str(value or "").lower())


def _text(value: Any) -> str | None:
    """把标量转换成去空格文本，统一空值表示。"""

    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


def _bounded_text(value: Any, limit: int) -> str:
    """限制跨边界文本长度，避免异常或模型输出放大公开载荷。"""

    return (str(value).strip() if value is not None else "")[:limit]


def _has_project_scope(project_id: object) -> bool:
    """只接受具体项目范围，拒绝空值和租户通配值。"""

    normalized = str(project_id or "").strip()
    return bool(normalized) and normalized.casefold() not in {"*", "all", "tenant", "tenant_scope"}


def _unique_text(values: Any) -> tuple[str, ...]:
    """规范化文本序列并保持首次出现顺序。"""

    if isinstance(values, str):
        values = (values,)
    if not isinstance(values, (list, tuple, set)):
        return ()
    return tuple(dict.fromkeys(str(value).strip() for value in values if _text(value)))


# 保留简短别名，方便注册表或学习示例按 PRECHECK_AGENT / PrecheckAgent 直观理解实现。
PrecheckAgent = PrecheckSpecialistAgent


__all__ = [
    "PRECHECK_TOOL_CODE",
    "PRECHECK_CONTROL_PLANE_TOOL_CODE",
    "SYNC_TASK_PRECHECK_TOOL_CODE",
    "PrecheckCheckItem",
    "PrecheckCheckStatus",
    "PrecheckControlPlaneClient",
    "PrecheckControlPlaneRequest",
    "PrecheckControlPlaneResult",
    "PrecheckExplanationModel",
    "PrecheckExplanationModelInput",
    "PrecheckExplanationModelOutput",
    "PrecheckModel",
    "PrecheckModelInput",
    "PrecheckModelOutput",
    "PrecheckAgent",
    "PrecheckSpecialistAgent",
]
