"""只读数据源专业 Agent。

本模块实现第一批可以独立执行 turn 的真实 ``DATASOURCE_AGENT``。它只承担“在当前用户已经有权使用的
数据源候选中完成源端/目标端消歧”这一项职责，不创建、不编辑、不删除数据源，也不读取连接串、密码等
敏感配置。真正的数据范围判断仍由注入的 Java 数据源发现工具完成，模型只能在工具返回的低敏候选 ID
集合内选择，因此即使模型幻觉出一个 ID，也不会被主 Agent 当作有效业务事实。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from time import perf_counter
from types import MappingProxyType
from typing import Any, Mapping, Protocol

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistEventSink,
    SpecialistToolActivity,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)


class DatasourceDirection(str, Enum):
    """数据源在同步任务中的业务方向。

    方向不是连接器本身的属性：同一个数据库连接有可能同时作为源端和目标端使用。该枚举用于把用户的
    “源 MySQL、目标 PostgreSQL”描述拆成两个独立检索问题，避免把最后一个候选错误地同时填到两端。
    """

    SOURCE = "SOURCE"
    TARGET = "TARGET"


@dataclass(frozen=True)
class DatasourceCandidate:
    """数据源发现工具允许交给模型和主 Agent 的低敏候选视图。

    该类型故意没有 ``host``、``port``、``jdbcUrl``、``username``、``password`` 或任意自由扩展
    ``metadata`` 字段。这样敏感连接配置不会因为工具适配器多返回了字段而穿透到模型上下文或前端事件。

    Attributes:
        datasource_id: Java 数据源管理服务分配的稳定 ID，主 Agent 最终只能使用这里出现过的 ID。
        name: 用户在项目内可见的数据源名称，用于人工确认和名称消歧。
        connector_type: 规范化的连接器类型，例如 ``MYSQL``、``POSTGRESQL``。
        supported_directions: 当前授权和连接器能力允许该数据源承担的同步方向。
        display_status: 可选的低敏可用性提示，例如“可用”或“连接待复核”，不得承载异常堆栈。
    """

    datasource_id: str
    name: str
    connector_type: str
    supported_directions: tuple[DatasourceDirection, ...] = (
        DatasourceDirection.SOURCE,
        DatasourceDirection.TARGET,
    )
    display_status: str | None = None

    def __post_init__(self) -> None:
        """规范化候选基础字段，并拒绝缺失 ID、名称或连接器类型的工具输出。"""

        normalized_id = _normalize_identifier(self.datasource_id, 128, "datasource_id")
        normalized_name = _bounded_text(self.name, 256)
        normalized_connector = _bounded_text(self.connector_type, 64).upper()
        if not normalized_id or not normalized_name or not normalized_connector:
            raise ValueError("数据源候选必须包含 datasource_id、name 和 connector_type")

        normalized_directions = tuple(
            dict.fromkeys(
                direction
                if isinstance(direction, DatasourceDirection)
                else DatasourceDirection(str(direction).strip().upper())
                for direction in self.supported_directions
            )
        )
        if not normalized_directions:
            raise ValueError("数据源候选至少支持一个源端或目标端方向")

        object.__setattr__(self, "datasource_id", normalized_id)
        object.__setattr__(self, "name", normalized_name)
        object.__setattr__(self, "connector_type", normalized_connector)
        object.__setattr__(self, "supported_directions", normalized_directions)
        object.__setattr__(
            self,
            "display_status",
            _bounded_text(self.display_status, 160) if self.display_status is not None else None,
        )

    def to_public_summary(self) -> dict[str, Any]:
        """生成可交给模型、主 Agent 和前端展示的字段白名单视图。"""

        return {
            "datasourceId": self.datasource_id,
            "name": self.name,
            "connectorType": self.connector_type,
            "supportedDirections": tuple(direction.value for direction in self.supported_directions),
            "displayStatus": self.display_status,
        }


@dataclass(frozen=True)
class DatasourceDiscoveryRequest:
    """调用只读数据源发现工具时使用的结构化查询。

    ``tenant_id/project_id/actor_id`` 来自主 Agent 的不可变委派范围。工具适配器必须继续把它们传给
    Gateway/数据源服务做真实授权，不能因为 Python Agent 已经检查过白名单就省略下游 RBAC。
    """

    tenant_id: str
    application_id: str | None
    project_id: str | None
    actor_id: str
    delegation_id: str
    turn_id: str
    run_id: str
    direction: DatasourceDirection
    connector_type: str | None = None
    name: str | None = None
    datasource_id: str | None = None


@dataclass(frozen=True)
class DatasourceDiscoveryResult:
    """只读发现工具的返回合同。

    工具只能返回已经按当前用户、租户和项目过滤过的候选。``evidence_reference`` 是控制面审计记录的
    低敏引用，而不是原始 HTTP 响应或数据库查询结果。
    """

    candidates: tuple[DatasourceCandidate, ...] = ()
    evidence_reference: str | None = None

    def __post_init__(self) -> None:
        """冻结候选集合，并尽早发现工具适配器返回了错误的数据类型。"""

        normalized_candidates = tuple(self.candidates)
        if any(not isinstance(candidate, DatasourceCandidate) for candidate in normalized_candidates):
            raise TypeError("数据源发现工具只能返回 DatasourceCandidate")
        object.__setattr__(self, "candidates", normalized_candidates)
        object.__setattr__(
            self,
            "evidence_reference",
            _bounded_text(self.evidence_reference, 512) if self.evidence_reference else None,
        )


class DatasourceDiscoveryTool(Protocol):
    """可注入的只读数据源检索工具协议。

    生产实现通常会调用 datasource-management 的授权检索接口。协议没有写操作方法，专业 Agent 也不会
    接收创建、修改或删除数据源的工具，从类型边界和运行时白名单两层保证其只读职责。
    """

    def discover(self, request: DatasourceDiscoveryRequest) -> DatasourceDiscoveryResult:
        """在指定用户的 tenant/application/project 范围内检索授权候选。"""


@dataclass(frozen=True)
class DatasourceDisambiguationRequest:
    """交给独立模型判别器的最小输入。

    模型只看到当前方向、用户给出的名称/连接器提示以及字段白名单化后的候选摘要。它不会看到原始工具
    响应、数据源凭据、连接串，也不能通过此合同调用数据源写操作。
    """

    direction: DatasourceDirection
    requested_connector_type: str | None
    requested_name: str | None
    candidate_summaries: tuple[Mapping[str, Any], ...]
    max_output_tokens: int
    tenant_id: str = ""
    project_id: str | None = None
    actor_id: str = ""
    session_id: str = ""
    run_id: str = ""
    trace_id: str | None = None

    def __post_init__(self) -> None:
        """冻结候选摘要，防止模型适配器在判别期间改写 Agent 的候选事实。"""

        object.__setattr__(
            self,
            "candidate_summaries",
            tuple(MappingProxyType(dict(summary)) for summary in self.candidate_summaries),
        )


@dataclass(frozen=True)
class DatasourceDisambiguationDecision:
    """独立模型对多候选问题给出的结构化判别。

    ``clear=False`` 表示模型认为证据不足，此时即使带了一个 ID，Agent 也必须停在人工选择阶段。
    ``selected_datasource_id`` 还会再次与工具候选集合求交，模型无法凭空注入业务 ID。
    """

    clear: bool
    selected_datasource_id: str | None = None
    public_reason: str = ""
    model_name: str | None = None
    model_invocation_id: str | None = None

    def __post_init__(self) -> None:
        """规范化模型的低敏判别结果，不保留模型原始响应或隐藏思维链。"""

        object.__setattr__(
            self,
            "selected_datasource_id",
            _bounded_text(self.selected_datasource_id, 128) if self.selected_datasource_id else None,
        )
        object.__setattr__(self, "public_reason", _bounded_text(self.public_reason, 600))
        object.__setattr__(
            self,
            "model_name",
            _bounded_text(self.model_name, 160) if self.model_name else None,
        )
        object.__setattr__(
            self,
            "model_invocation_id",
            _bounded_text(self.model_invocation_id, 160) if self.model_invocation_id else None,
        )


class DatasourceDisambiguationModel(Protocol):
    """可注入的数据源候选模型判别协议。"""

    def disambiguate(self, request: DatasourceDisambiguationRequest) -> DatasourceDisambiguationDecision:
        """只在给定候选摘要内选择；证据不足时必须返回 ``clear=False``。"""


@dataclass(frozen=True)
class _DatasourceCriteria:
    """从主 Agent 低敏上下文中提取的单方向检索条件。"""

    direction: DatasourceDirection
    connector_type: str | None = None
    name: str | None = None
    datasource_id: str | None = None

    def to_public_summary(self) -> dict[str, Any]:
        """输出检索条件摘要；不包含租户、用户等审计主体。"""

        return {
            "direction": self.direction.value,
            "connectorType": self.connector_type,
            "name": self.name,
            "datasourceId": self.datasource_id,
        }


@dataclass(frozen=True)
class _DirectionResolution:
    """一个源端或目标端的内部解析结果。"""

    direction: DatasourceDirection
    selected_datasource_id: str | None
    selection_method: str | None
    candidates: tuple[DatasourceCandidate, ...]
    message: str
    model_status: str = "NOT_INVOKED"

    @property
    def resolved(self) -> bool:
        """判断该方向是否已经得到来自授权候选集合的确定 ID。"""

        return self.selected_datasource_id is not None

    def to_public_summary(self, criteria: _DatasourceCriteria) -> dict[str, Any]:
        """输出主 Agent 可直接消费的单方向解析结果。"""

        return {
            "status": "RESOLVED" if self.resolved else SpecialistTurnStatus.WAITING_FOR_INPUT.value,
            "selectedDatasourceId": self.selected_datasource_id,
            "selectionMethod": self.selection_method,
            "requested": criteria.to_public_summary(),
            "candidates": tuple(candidate.to_public_summary() for candidate in self.candidates),
            "message": _bounded_text(self.message, 600),
            "modelStatus": self.model_status,
        }


@dataclass
class _ExecutionJournal:
    """收集一次 turn 内允许公开的工具、证据和模型调用摘要。"""

    tool_activities: list[SpecialistToolActivity] = field(default_factory=list)
    evidence_references: list[str] = field(default_factory=list)
    model_selections: list[dict[str, Any]] = field(default_factory=list)


class DatasourceSpecialistAgent:
    """只读数据源消歧专业 Agent。

    执行顺序为：复核角色与工具白名单 -> 按方向调用授权检索工具 -> 唯一候选直接确定 -> 多候选才调用
    独立模型 -> 校验模型 ID 必须属于候选集合 -> 返回主 Agent 可填充的源/目标数据源 ID。任何不确定性
    都会转为 ``WAITING_FOR_INPUT``，不会随机选择候选。
    """

    AGENT_ID = "datasource-specialist-agent-v1"
    DISCOVERY_TOOL_NAME = "datasource.discovery.read"

    def __init__(
        self,
        discovery_tool: DatasourceDiscoveryTool,
        disambiguation_model: DatasourceDisambiguationModel | None = None,
    ) -> None:
        """注入只读发现工具和可选的独立模型判别器。

        不提供默认网络客户端是有意设计：生产启动代码必须显式注入带用户身份透传和下游 RBAC 的工具
        适配器，测试则可注入内存替身。缺少模型不会导致随机降级，多候选会直接等待用户选择。
        """

        if discovery_tool is None:
            raise ValueError("DATASOURCE_AGENT 必须注入 DatasourceDiscoveryTool")
        self._discovery_tool = discovery_tool
        self._disambiguation_model = disambiguation_model

    @property
    def role(self) -> AgentSessionRole:
        """返回该实现唯一允许承担的专业角色。"""

        return AgentSessionRole.DATASOURCE_AGENT

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None = None,
    ) -> SpecialistTurnResult:
        """执行一次只读数据源检索与消歧 turn。

        Args:
            request: 主 Agent 下发的不可变委派请求，其中包含用户范围、工具白名单和预算。
            event_sink: 可选的流式事件接收器，用于展示低敏步骤；接收器异常不会改变业务结果。

        Returns:
            ``COMPLETED`` 表示所有请求方向都已有确定候选；``WAITING_FOR_INPUT`` 表示需要用户选择；
            ``FAILED`` 仅用于角色、权限、预算、工具或模型等技术/治理错误。
        """

        started_at = perf_counter()
        journal = _ExecutionJournal()
        self._emit(
            event_sink,
            request,
            "SPECIALIST_TURN_STARTED",
            "RUNNING",
            "数据源 Agent 开始在当前用户授权范围内检索候选。",
        )

        if request.role != self.role:
            return self._failed_result(
                request,
                started_at,
                journal,
                event_sink,
                "SPECIALIST_ROLE_MISMATCH",
                "数据源 Agent 收到的委派角色不一致，已拒绝执行。",
            )

        # 数据源名称和连接器类型只是筛选条件，真正的资源边界是项目。
        # 缺少 project_id 时，即使下游工具本身也有 RBAC，Python 侧仍不能发起目录访问。
        if not _has_project_scope(request.scope.project_id):
            return self._failed_result(
                request,
                started_at,
                journal,
                event_sink,
                "DATASOURCE_PROJECT_SCOPE_REQUIRED",
                "数据源检索缺少明确项目范围，已停止访问数据源目录。",
            )

        # 白名单检查必须发生在任何工具或模型调用之前；主 Agent 没有委派该能力时应 fail-closed。
        if self.DISCOVERY_TOOL_NAME not in request.scope.allowed_tool_names:
            denied_activity = SpecialistToolActivity(
                tool_name=self.DISCOVERY_TOOL_NAME,
                status="DENIED",
                public_summary="当前委派未授权只读数据源检索工具，未发起任何数据源请求。",
            )
            journal.tool_activities.append(denied_activity)
            self._emit(
                event_sink,
                request,
                "SPECIALIST_TOOL_DENIED",
                "FAILED",
                denied_activity.public_summary,
                {"toolName": self.DISCOVERY_TOOL_NAME},
            )
            return self._failed_result(
                request,
                started_at,
                journal,
                event_sink,
                "SPECIALIST_TOOL_NOT_ALLOWED",
                "当前委派没有数据源只读检索权限，请由主 Agent 重新申请工具授权。",
            )

        criteria_by_direction = self._extract_criteria(request.context_summary)
        if request.budget.max_tool_calls < len(criteria_by_direction):
            return self._failed_result(
                request,
                started_at,
                journal,
                event_sink,
                "SPECIALIST_TOOL_BUDGET_EXCEEDED",
                "本次委派的工具调用预算不足，无法完成全部源端/目标端检索。",
            )

        resolutions: dict[DatasourceDirection, _DirectionResolution] = {}
        model_invocations = 0
        for direction, criteria in criteria_by_direction.items():
            discovery = self._discover_candidates(request, criteria, journal, event_sink)
            if isinstance(discovery, SpecialistTurnResult):
                return discovery

            candidates = tuple(
                candidate for candidate in discovery.candidates if direction in candidate.supported_directions
            )
            resolution, model_invocations, model_error = self._resolve_direction(
                request=request,
                criteria=criteria,
                candidates=candidates,
                model_invocations=model_invocations,
                journal=journal,
                event_sink=event_sink,
            )
            if model_error is not None:
                return self._failed_result(
                    request,
                    started_at,
                    journal,
                    event_sink,
                    model_error[0],
                    model_error[1],
                )
            resolutions[direction] = resolution

        waiting_directions = tuple(direction for direction, item in resolutions.items() if not item.resolved)
        structured_output = self._build_structured_output(criteria_by_direction, resolutions)
        model_summary = {
            "invoked": bool(model_invocations),
            "invocationCount": model_invocations,
            "selections": tuple(journal.model_selections),
            "rawModelOutputStored": False,
        }

        if waiting_directions:
            required_fields = tuple(self._output_id_field(direction) for direction in waiting_directions)
            direction_names = "、".join(self._direction_label(direction) for direction in waiting_directions)
            public_summary = f"{direction_names}数据源仍有歧义，请从授权候选中明确选择后继续。"
            self._emit(
                event_sink,
                request,
                "SPECIALIST_WAITING_FOR_INPUT",
                "WAITING_FOR_INPUT",
                public_summary,
                {"requiredInputFields": required_fields},
            )
            return SpecialistTurnResult(
                agent_id=self.AGENT_ID,
                role=self.role,
                turn_id=request.turn_id,
                status=SpecialistTurnStatus.WAITING_FOR_INPUT,
                public_summary=public_summary,
                structured_output=structured_output,
                evidence_references=tuple(journal.evidence_references),
                tool_activities=tuple(journal.tool_activities),
                model_invocation_summary=model_summary,
                required_input_fields=required_fields,
                duration_ms=self._elapsed_ms(started_at),
            )

        public_summary = "已在当前用户授权范围内确定所需的源端和/或目标端数据源。"
        self._emit(
            event_sink,
            request,
            "SPECIALIST_TURN_COMPLETED",
            "COMPLETED",
            public_summary,
            {
                "sourceDatasourceResolved": bool(structured_output.get("sourceDatasourceId")),
                "targetDatasourceResolved": bool(structured_output.get("targetDatasourceId")),
            },
        )
        return SpecialistTurnResult(
            agent_id=self.AGENT_ID,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.COMPLETED,
            public_summary=public_summary,
            structured_output=structured_output,
            evidence_references=tuple(journal.evidence_references),
            tool_activities=tuple(journal.tool_activities),
            model_invocation_summary=model_summary,
            duration_ms=self._elapsed_ms(started_at),
        )

    def _discover_candidates(
        self,
        request: SpecialistTurnRequest,
        criteria: _DatasourceCriteria,
        journal: _ExecutionJournal,
        event_sink: SpecialistEventSink | None,
    ) -> DatasourceDiscoveryResult | SpecialistTurnResult:
        """调用一次授权发现工具，并把原始结果收敛为低敏候选和活动摘要。"""

        direction_label = self._direction_label(criteria.direction)
        self._emit(
            event_sink,
            request,
            "SPECIALIST_TOOL_STARTED",
            "RUNNING",
            f"正在检索当前用户可用的{direction_label}数据源。",
            {"toolName": self.DISCOVERY_TOOL_NAME, "direction": criteria.direction.value},
        )
        tool_started_at = perf_counter()
        try:
            discovery = self._discovery_tool.discover(
                DatasourceDiscoveryRequest(
                    tenant_id=request.scope.tenant_id,
                    application_id=request.scope.application_id,
                    project_id=request.scope.project_id,
                    actor_id=request.scope.actor_id,
                    delegation_id=request.scope.delegation_id,
                    turn_id=request.turn_id,
                    run_id=request.run_id,
                    direction=criteria.direction,
                    connector_type=criteria.connector_type,
                    name=criteria.name,
                    datasource_id=criteria.datasource_id,
                )
            )
            if not isinstance(discovery, DatasourceDiscoveryResult):
                raise TypeError("发现工具返回类型不符合 DatasourceDiscoveryResult 合同")
        except Exception:
            # 不能把下游 URL、SQL、令牌或异常堆栈写进 Agent 结果；详细异常由工具侧审计系统保存。
            failed_activity = SpecialistToolActivity(
                tool_name=self.DISCOVERY_TOOL_NAME,
                status="FAILED",
                public_summary=f"{direction_label}数据源检索失败，请检查数据源服务和当前项目授权。",
                duration_ms=self._elapsed_ms(tool_started_at),
            )
            journal.tool_activities.append(failed_activity)
            return self._failed_result(
                request,
                tool_started_at,
                journal,
                event_sink,
                "DATASOURCE_DISCOVERY_FAILED",
                failed_activity.public_summary,
            )

        activity = SpecialistToolActivity(
            tool_name=self.DISCOVERY_TOOL_NAME,
            status="SUCCEEDED",
            public_summary=f"已检索到 {len(discovery.candidates)} 个授权{direction_label}候选。",
            evidence_reference=discovery.evidence_reference,
            duration_ms=self._elapsed_ms(tool_started_at),
        )
        journal.tool_activities.append(activity)
        if discovery.evidence_reference and discovery.evidence_reference not in journal.evidence_references:
            journal.evidence_references.append(discovery.evidence_reference)
        self._emit(
            event_sink,
            request,
            "SPECIALIST_TOOL_COMPLETED",
            "SUCCEEDED",
            activity.public_summary,
            {
                "toolName": self.DISCOVERY_TOOL_NAME,
                "direction": criteria.direction.value,
                "candidateCount": len(discovery.candidates),
                "evidenceReference": discovery.evidence_reference,
            },
        )
        return discovery

    def _resolve_direction(
        self,
        request: SpecialistTurnRequest,
        criteria: _DatasourceCriteria,
        candidates: tuple[DatasourceCandidate, ...],
        model_invocations: int,
        journal: _ExecutionJournal,
        event_sink: SpecialistEventSink | None,
    ) -> tuple[_DirectionResolution, int, tuple[str, str] | None]:
        """根据显式 ID、唯一候选或模型判别依次确定单方向数据源。

        返回值中的第三项是低敏技术错误；业务歧义不会放入该字段，而会形成未解析结果，由 ``execute``
        统一转换为 ``WAITING_FOR_INPUT``。
        """

        direction_label = self._direction_label(criteria.direction)
        if criteria.datasource_id:
            explicit_candidate = next(
                (candidate for candidate in candidates if candidate.datasource_id == criteria.datasource_id),
                None,
            )
            if explicit_candidate is not None:
                return (
                    _DirectionResolution(
                        direction=criteria.direction,
                        selected_datasource_id=explicit_candidate.datasource_id,
                        selection_method="EXPLICIT_AUTHORIZED_ID",
                        candidates=candidates,
                        message=f"已验证用户明确提供的{direction_label}数据源 ID 在授权候选中。",
                    ),
                    model_invocations,
                    None,
                )
            return (
                _DirectionResolution(
                    direction=criteria.direction,
                    selected_datasource_id=None,
                    selection_method=None,
                    candidates=candidates,
                    message=f"用户提供的{direction_label}数据源 ID 不在当前授权候选中，请重新选择。",
                ),
                model_invocations,
                None,
            )

        if len(candidates) == 1:
            return (
                _DirectionResolution(
                    direction=criteria.direction,
                    selected_datasource_id=candidates[0].datasource_id,
                    selection_method="UNIQUE_AUTHORIZED_CANDIDATE",
                    candidates=candidates,
                    message=f"仅找到一个符合条件且已授权的{direction_label}数据源，已自动确定。",
                ),
                model_invocations,
                None,
            )

        if not candidates:
            return (
                _DirectionResolution(
                    direction=criteria.direction,
                    selected_datasource_id=None,
                    selection_method=None,
                    candidates=(),
                    message=f"未找到符合条件且当前用户有权使用的{direction_label}数据源。",
                ),
                model_invocations,
                None,
            )

        if self._disambiguation_model is None:
            return (
                _DirectionResolution(
                    direction=criteria.direction,
                    selected_datasource_id=None,
                    selection_method=None,
                    candidates=candidates,
                    message=f"找到多个{direction_label}数据源候选，缺少明确依据，请用户选择。",
                ),
                model_invocations,
                None,
            )

        if model_invocations >= request.budget.max_model_invocations:
            return (
                _DirectionResolution(
                    direction=criteria.direction,
                    selected_datasource_id=None,
                    selection_method=None,
                    candidates=candidates,
                    message=f"找到多个{direction_label}候选，但本次模型调用预算已用尽。",
                    model_status="BUDGET_EXHAUSTED",
                ),
                model_invocations,
                None,
            )

        self._emit(
            event_sink,
            request,
            "SPECIALIST_MODEL_STARTED",
            "RUNNING",
            f"模型正在根据低敏候选摘要判别{direction_label}数据源。",
            {"direction": criteria.direction.value, "candidateCount": len(candidates)},
        )
        try:
            decision = self._disambiguation_model.disambiguate(
                DatasourceDisambiguationRequest(
                    direction=criteria.direction,
                    requested_connector_type=criteria.connector_type,
                    requested_name=criteria.name,
                    candidate_summaries=tuple(candidate.to_public_summary() for candidate in candidates),
                    max_output_tokens=request.budget.max_output_tokens,
                    # 模型网关治理必须继续携带原始用户与项目边界。这里传递的只是审计标识，
                    # 不包含数据源凭据、连接地址或候选工具的原始响应。
                    tenant_id=request.scope.tenant_id,
                    project_id=request.scope.project_id,
                    actor_id=request.scope.actor_id,
                    session_id=request.session_id,
                    run_id=request.run_id,
                    trace_id=str(request.context_summary.get("traceId") or "").strip() or None,
                )
            )
            if not isinstance(decision, DatasourceDisambiguationDecision):
                raise TypeError("模型返回类型不符合 DatasourceDisambiguationDecision 合同")
        except Exception:
            return (
                _DirectionResolution(
                    direction=criteria.direction,
                    selected_datasource_id=None,
                    selection_method=None,
                    candidates=candidates,
                    message=f"{direction_label}候选模型判别失败。",
                    model_status="FAILED",
                ),
                model_invocations + 1,
                ("DATASOURCE_MODEL_DISAMBIGUATION_FAILED", "数据源候选模型判别失败，请稍后重试或人工选择。"),
            )

        model_invocations += 1
        candidate_ids = {candidate.datasource_id for candidate in candidates}
        valid_selection = bool(
            decision.clear
            and decision.selected_datasource_id
            and decision.selected_datasource_id in candidate_ids
        )
        model_status = "ACCEPTED" if valid_selection else "REJECTED_OR_UNCLEAR"
        journal.model_selections.append(
            {
                "direction": criteria.direction.value,
                "status": model_status,
                "modelName": decision.model_name,
                "modelInvocationId": decision.model_invocation_id,
                "selectedDatasourceId": decision.selected_datasource_id if valid_selection else None,
                "publicReason": _bounded_text(decision.public_reason, 600),
            }
        )
        self._emit(
            event_sink,
            request,
            "SPECIALIST_MODEL_COMPLETED",
            "SUCCEEDED" if valid_selection else "WAITING_FOR_INPUT",
            (
                f"模型已在授权候选中明确选定{direction_label}数据源。"
                if valid_selection
                else f"模型未能在授权候选中明确选定{direction_label}数据源，需要用户确认。"
            ),
            {
                "direction": criteria.direction.value,
                "decisionStatus": model_status,
                "modelName": decision.model_name,
                "modelInvocationId": decision.model_invocation_id,
            },
        )

        if valid_selection:
            return (
                _DirectionResolution(
                    direction=criteria.direction,
                    selected_datasource_id=decision.selected_datasource_id,
                    selection_method="MODEL_CONFIRMED_AUTHORIZED_CANDIDATE",
                    candidates=candidates,
                    message=decision.public_reason or f"模型已根据候选摘要明确选定{direction_label}数据源。",
                    model_status=model_status,
                ),
                model_invocations,
                None,
            )

        invalid_id_message = (
            "模型返回的 ID 不属于授权候选集合，已拒绝采用；"
            if decision.selected_datasource_id and decision.selected_datasource_id not in candidate_ids
            else "模型没有给出明确且可验证的候选；"
        )
        return (
            _DirectionResolution(
                direction=criteria.direction,
                selected_datasource_id=None,
                selection_method=None,
                candidates=candidates,
                message=f"{invalid_id_message}请用户从候选列表中选择{direction_label}数据源。",
                model_status=model_status,
            ),
            model_invocations,
            None,
        )

    @classmethod
    def _extract_criteria(
        cls,
        context_summary: Mapping[str, Any],
    ) -> dict[DatasourceDirection, _DatasourceCriteria]:
        """从兼容的新旧低敏上下文键中提取源端/目标端结构化条件。

        推荐主 Agent 使用 ``source``/``target`` 子对象；同时兼容 ``sourceDatasourceName`` 等扁平键，
        便于现有单 Agent 链路渐进接入。若没有任何方向提示，则默认同时解析源端和目标端。
        """

        requested_directions = cls._requested_directions(context_summary)
        result: dict[DatasourceDirection, _DatasourceCriteria] = {}
        for direction in requested_directions:
            prefix = "source" if direction == DatasourceDirection.SOURCE else "target"
            nested_value = context_summary.get(prefix)
            if not isinstance(nested_value, Mapping):
                nested_value = context_summary.get(f"{prefix}Datasource")
            nested = nested_value if isinstance(nested_value, Mapping) else {}

            connector_type = cls._first_text(
                nested.get("connectorType"),
                nested.get("databaseType"),
                nested.get("type"),
                context_summary.get(f"{prefix}ConnectorType"),
                context_summary.get(f"{prefix}DatabaseType"),
            )
            name = cls._first_text(
                nested.get("datasourceName"),
                nested.get("name"),
                context_summary.get(f"{prefix}DatasourceName"),
            )
            datasource_id = cls._first_text(
                nested.get("datasourceId"),
                nested.get("id"),
                context_summary.get(f"{prefix}DatasourceId"),
            )
            result[direction] = _DatasourceCriteria(
                direction=direction,
                connector_type=connector_type.upper() if connector_type else None,
                name=name,
                datasource_id=datasource_id,
            )
        return result

    @classmethod
    def _requested_directions(cls, context_summary: Mapping[str, Any]) -> tuple[DatasourceDirection, ...]:
        """确定本 turn 需要解析哪些方向，避免不必要的工具和模型调用。"""

        explicit = context_summary.get("requestedDirections")
        if isinstance(explicit, (list, tuple, set)):
            normalized: list[DatasourceDirection] = []
            for item in explicit:
                try:
                    direction = item if isinstance(item, DatasourceDirection) else DatasourceDirection(str(item).upper())
                except ValueError:
                    continue
                if direction not in normalized:
                    normalized.append(direction)
            if normalized:
                return tuple(normalized)

        source_present = cls._has_direction_hint(context_summary, "source")
        target_present = cls._has_direction_hint(context_summary, "target")
        if source_present or target_present:
            directions: list[DatasourceDirection] = []
            if source_present:
                directions.append(DatasourceDirection.SOURCE)
            if target_present:
                directions.append(DatasourceDirection.TARGET)
            return tuple(directions)
        return (DatasourceDirection.SOURCE, DatasourceDirection.TARGET)

    @staticmethod
    def _has_direction_hint(context_summary: Mapping[str, Any], prefix: str) -> bool:
        """判断上下文是否显式包含某一方向的结构化提示。"""

        return any(
            key in context_summary
            for key in (
                prefix,
                f"{prefix}Datasource",
                f"{prefix}ConnectorType",
                f"{prefix}DatabaseType",
                f"{prefix}DatasourceName",
                f"{prefix}DatasourceId",
            )
        )

    @staticmethod
    def _first_text(*values: Any) -> str | None:
        """从多个兼容字段中选取第一个非空文本，并统一去除首尾空白。"""

        for value in values:
            normalized = str(value).strip() if value is not None else ""
            if normalized:
                return normalized
        return None

    @classmethod
    def _build_structured_output(
        cls,
        criteria_by_direction: Mapping[DatasourceDirection, _DatasourceCriteria],
        resolutions: Mapping[DatasourceDirection, _DirectionResolution],
    ) -> dict[str, Any]:
        """组装主 Agent 可直接回填同步任务草案的数据源解析结果。"""

        source = resolutions.get(DatasourceDirection.SOURCE)
        target = resolutions.get(DatasourceDirection.TARGET)
        return {
            "sourceDatasourceId": source.selected_datasource_id if source else None,
            "targetDatasourceId": target.selected_datasource_id if target else None,
            "resolutions": {
                direction.value.lower(): resolution.to_public_summary(criteria_by_direction[direction])
                for direction, resolution in resolutions.items()
            },
            "readOnly": True,
            "writeOperationsPerformed": False,
        }

    def _failed_result(
        self,
        request: SpecialistTurnRequest,
        started_at: float,
        journal: _ExecutionJournal,
        event_sink: SpecialistEventSink | None,
        error_code: str,
        public_summary: str,
    ) -> SpecialistTurnResult:
        """生成统一低敏失败结果，禁止把捕获到的原始异常内容回传给主 Agent。"""

        self._emit(
            event_sink,
            request,
            "SPECIALIST_TURN_FAILED",
            "FAILED",
            public_summary,
            {"errorCode": error_code},
        )
        return SpecialistTurnResult(
            agent_id=self.AGENT_ID,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.FAILED,
            public_summary=public_summary,
            evidence_references=tuple(journal.evidence_references),
            tool_activities=tuple(journal.tool_activities),
            model_invocation_summary={
                "invoked": bool(journal.model_selections),
                "invocationCount": len(journal.model_selections),
                "selections": tuple(journal.model_selections),
                "rawModelOutputStored": False,
            },
            error_code=error_code,
            duration_ms=self._elapsed_ms(started_at),
        )

    @staticmethod
    def _emit(
        event_sink: SpecialistEventSink | None,
        request: SpecialistTurnRequest,
        event_type: str,
        status: str,
        public_summary: str,
        attributes: Mapping[str, Any] | None = None,
    ) -> None:
        """向流式界面发送低敏步骤事件。

        事件接收器通常连接 WebSocket/SSE，客户端断线不应使专业 Agent 的业务结果失败，因此这里采用
        best-effort 投递。事件只包含公开摘要和字段白名单属性，不包含上下文正文或工具原始参数。
        """

        if event_sink is None:
            return
        event = {
            "eventType": event_type,
            "status": status,
            "agentRole": AgentSessionRole.DATASOURCE_AGENT.value,
            "agentId": DatasourceSpecialistAgent.AGENT_ID,
            "turnId": request.turn_id,
            "runId": request.run_id,
            "publicSummary": _bounded_text(public_summary, 600),
            "attributes": _safe_event_attributes(attributes),
            "payloadPolicy": "LOW_SENSITIVE_SPECIALIST_EVENT_ONLY",
        }
        try:
            event_sink(event)
        except Exception:
            return

    @staticmethod
    def _elapsed_ms(started_at: float) -> int:
        """把 ``perf_counter`` 的单调时间差转换为非负毫秒数。"""

        return max(0, int((perf_counter() - started_at) * 1_000))

    @staticmethod
    def _direction_label(direction: DatasourceDirection) -> str:
        """返回面向用户的方向名称。"""

        return "源端" if direction == DatasourceDirection.SOURCE else "目标端"

    @staticmethod
    def _output_id_field(direction: DatasourceDirection) -> str:
        """返回主 Agent 草案中对应方向的数据源 ID 字段名。"""

        return "sourceDatasourceId" if direction == DatasourceDirection.SOURCE else "targetDatasourceId"


def _bounded_text(value: Any, limit: int) -> str:
    """把外部组件提供的公开文本裁剪为有限、无 NUL 字符的摘要。"""

    return str(value or "").replace("\x00", "").strip()[: max(1, limit)]


def _normalize_identifier(value: Any, limit: int, field_name: str) -> str:
    """规范化资源标识，并拒绝超长标识而不是静默截断造成 ID 碰撞。"""

    normalized = _bounded_text(value, limit + 1)
    if len(normalized) > limit:
        raise ValueError(f"{field_name} 超过允许长度")
    return normalized


def _has_project_scope(project_id: object) -> bool:
    """只接受具体项目，不接受空值或租户通配范围。"""

    normalized = str(project_id or "").strip()
    return bool(normalized) and normalized.casefold() not in {"*", "all", "tenant", "tenant_scope"}


def _safe_event_attributes(attributes: Mapping[str, Any] | None) -> dict[str, Any]:
    """把数据源事件属性收敛为低敏标量或小型文本序列。

    事件会被 SSE/WebSocket 和审计投影共同消费，不能原样携带模型理由、任意嵌套对象或超长
    引用。数组只保留少量字段名，mapping 直接丢弃，确保事件不是第二条原始响应通道。
    """

    safe: dict[str, Any] = {}
    for raw_key, value in (attributes or {}).items():
        key = _bounded_text(raw_key, 80)
        normalized_key = "".join(character for character in key.lower() if character.isalnum())
        if normalized_key in {"prompt", "reasoning", "rawmodeloutput", "modelresponse", "arguments"}:
            continue
        if isinstance(value, bool) or value is None or isinstance(value, (int, float)):
            safe[key] = value
        elif isinstance(value, str):
            safe[key] = _bounded_text(value, 600)
        elif isinstance(value, (tuple, list)):
            safe[key] = tuple(_bounded_text(item, 160) for item in value[:16] if str(item or "").strip())
    return safe
