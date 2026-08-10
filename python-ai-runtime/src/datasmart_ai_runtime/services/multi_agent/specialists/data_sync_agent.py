"""真实 DATA_SYNC_AGENT：把同步需求规划为可审核的配置草案。

本模块是多 Agent 渐进式拆分中的同步规划专业 Agent。它拥有独立模型调用边界，但没有任务写入权限：
模型负责理解同步意图并提出草案，Python 侧确定性规则负责验证模式、映射、元数据和副作用。保存草稿、
发布任务、启动执行仍必须由主 Agent 委派 Java 控制面中的受控工具完成。
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
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


@dataclass(frozen=True)
class SyncPlanningModelInput:
    """传递给同步规划模型的最小、低敏输入。

    主 Agent 已经完成身份与数据范围委派，因此这里不包含密码、连接串、Token 或数据库样例数据。
    ``allowed_tool_names`` 仅告诉模型哪些只读工具在本次委派中可见，不代表专业 Agent 会自行执行工具。
    """

    objective: str
    context: Mapping[str, Any]
    allowed_tool_names: tuple[str, ...]
    max_output_tokens: int
    tenant_id: str = ""
    project_id: str | None = None
    actor_id: str = ""
    session_id: str = ""
    run_id: str = ""
    trace_id: str | None = None

    def __post_init__(self) -> None:
        """冻结上下文，避免模型适配器意外修改主 Agent 提供的会话事实。"""

        object.__setattr__(self, "context", MappingProxyType(dict(self.context)))


@dataclass(frozen=True)
class SyncPlanningModelOutput:
    """模型适配器返回的同步规划建议。

    ``configuration`` 只是模型建议，不是可信任务定义。``requested_tool_names`` 也只是建议读取更多事实；
    DataSyncSpecialistAgent 会再次检查专业角色白名单和本次 delegation 白名单，并且绝不执行这些工具。
    """

    configuration: Mapping[str, Any]
    public_summary: str = ""
    invocation_summary: Mapping[str, Any] = field(default_factory=dict)
    requested_tool_names: tuple[str, ...] = ()
    requested_actions: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        """把模型返回值转换为不可变快照，防止校验过程中发生并发修改。"""

        object.__setattr__(self, "configuration", MappingProxyType(dict(self.configuration)))
        object.__setattr__(self, "invocation_summary", MappingProxyType(dict(self.invocation_summary)))
        object.__setattr__(self, "requested_tool_names", _unique_text(self.requested_tool_names))
        object.__setattr__(self, "requested_actions", _unique_text(self.requested_actions))


class SyncPlanningModel(Protocol):
    """DATA_SYNC_AGENT 使用的独立模型规划协议。

    该协议刻意不依赖某一家 Provider。OpenAI-compatible、vLLM、本地模型或测试替身只要实现一次
    ``plan`` 调用即可接入；模型路由、缓存和 Token 统计可通过 ``invocation_summary`` 回传。
    """

    def plan(self, request: SyncPlanningModelInput) -> SyncPlanningModelOutput:
        """依据低敏目标与上下文生成同步配置建议，不得在方法内部写入或执行业务任务。"""


class SyncMetadataDiscoveryError(RuntimeError):
    """元数据只读工具返回的稳定、低敏错误。

    适配器可能遇到 HTTP 401、403、超时、范围回显不一致或响应 DTO 损坏等不同故障。
    这些故障不能把下游响应正文带回模型或前端，因此统一只保留可审计的机器码；
    DATA_SYNC_AGENT 会把它转换为本轮专业 Agent 的稳定失败码。
    """

    def __init__(self, code: str, *, status_code: int | None = None) -> None:
        """创建只包含稳定错误码的异常，避免异常字符串泄露 URL、Header 或响应正文。"""

        normalized = re.sub(r"[^A-Za-z0-9_.:-]", "_", str(code or "").strip())
        self.code = normalized[:120] or "SYNC_METADATA_DISCOVERY_FAILED"
        self.status_code = status_code if isinstance(status_code, int) else None
        super().__init__(self.code)


@dataclass(frozen=True)
class SyncMetadataDiscoveryRequest:
    """一次只读元数据发现所需的受控请求。

    这个请求和自然语言目标完全分离：``datasource_id`` 必须来自当前 turn 上下文或
    DATASOURCE_AGENT 已解析的授权结果，不能由 HTTP 适配器或模型根据“MySQL”等文本猜测。
    ``authorized_project_id`` 与 ``project_id`` 必须一致，适配器会把它们收敛为 PROJECT
    数据范围 Header，确保下游不会把一次项目内读取降级成租户级读取。
    """

    tenant_id: str
    project_id: str
    actor_id: str
    delegation_id: str
    session_id: str
    run_id: str
    trace_id: str
    datasource_id: int
    side: str
    connector_type: str | None = None
    authorized_project_id: str | None = None
    scope_level: str = "PROJECT"
    include_columns: bool = True
    max_tables: int = 100
    max_columns: int = 200
    # 当用户已经明确说出表名时，使用精确对象查询而不是先扫描前 N 张表。
    # 这组名称只来自用户审核过的结构化映射或受限的显式标识符提取，不能把“MySQL”
    # 这类连接器名称当成数据源或表名。适配器会为每个名称发出一次只读请求并合并结果，
    # 从而避免数据源表很多时因为 max_tables 截断而把真实存在的表误报为不存在。
    table_names: tuple[str, ...] = ()
    schema_pattern: str | None = None
    table_name_pattern: str | None = None
    filter_mode: str = "ALL"

    def __post_init__(self) -> None:
        """在发出 HTTP 请求前冻结审计主体、数据源 ID 和扫描规模。"""

        required = {
            "tenant_id": self.tenant_id,
            "project_id": self.project_id,
            "actor_id": self.actor_id,
            "delegation_id": self.delegation_id,
            "session_id": self.session_id,
            "run_id": self.run_id,
            "trace_id": self.trace_id,
        }
        missing = tuple(name for name, value in required.items() if not str(value or "").strip())
        if missing:
            raise ValueError(f"元数据发现请求缺少审计字段：{', '.join(missing)}")
        for name, value in required.items():
            object.__setattr__(self, name, str(value).strip())

        if isinstance(self.datasource_id, bool):
            raise ValueError("元数据发现请求的 datasource_id 无效")
        try:
            datasource_id = int(str(self.datasource_id).strip())
        except (TypeError, ValueError) as exc:
            raise ValueError("元数据发现请求的 datasource_id 无效") from exc
        if datasource_id <= 0:
            raise ValueError("元数据发现请求的 datasource_id 必须为正数")
        object.__setattr__(self, "datasource_id", datasource_id)

        side = str(self.side or "").strip().upper()
        if side not in {"SOURCE", "TARGET"}:
            raise ValueError("元数据发现请求的 side 只能是 SOURCE 或 TARGET")
        object.__setattr__(self, "side", side)

        scope_level = str(self.scope_level or "").strip().upper()
        if scope_level != "PROJECT":
            raise ValueError("元数据发现请求必须使用 PROJECT 数据范围")
        object.__setattr__(self, "scope_level", scope_level)

        authorized_project_id = str(self.authorized_project_id or self.project_id).strip()
        if authorized_project_id != self.project_id:
            raise ValueError("authorized_project_id 必须与 project_id 一致")
        object.__setattr__(self, "authorized_project_id", authorized_project_id)

        connector_type = str(self.connector_type or "").strip().upper() or None
        if connector_type is not None and not re.fullmatch(r"[A-Z0-9][A-Z0-9_.:-]{0,63}", connector_type):
            raise ValueError("元数据发现请求的 connector_type 无效")
        object.__setattr__(self, "connector_type", connector_type)

        for name, value, minimum, maximum in (
            ("max_tables", self.max_tables, 1, 200),
            ("max_columns", self.max_columns, 1, 500),
        ):
            if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
                raise ValueError(f"{name} 必须在 {minimum} 到 {maximum} 之间")

        normalized_table_names = tuple(
            dict.fromkeys(
                str(table_name).strip()
                for table_name in self.table_names
                if str(table_name or "").strip()
            )
        )
        if len(normalized_table_names) > 100:
            raise ValueError("table_names 不能超过 100 个")
        for table_name in normalized_table_names:
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$]{0,127}", table_name):
                raise ValueError("table_names 中包含无效的表名")
        object.__setattr__(self, "table_names", normalized_table_names)

        for name in ("schema_pattern", "table_name_pattern"):
            value = str(getattr(self, name) or "").strip() or None
            if value is not None and not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$%]{0,127}", value):
                raise ValueError(f"{name} 包含无效标识符")
            object.__setattr__(self, name, value)

        filter_mode = str(self.filter_mode or "ALL").strip().upper().replace("-", "_")
        if filter_mode not in {"ALL", "TABLE", "SCHEMA", "SCHEMA_AND_TABLE", "CATALOG"}:
            raise ValueError("filter_mode 不是受支持的元数据筛选模式")
        object.__setattr__(self, "filter_mode", filter_mode)


@dataclass(frozen=True)
class SyncMetadataDiscoveryResult:
    """只读元数据工具的低敏返回合同。

    ``metadata`` 只允许包含 schema、table、field、类型、可空性和主键摘要，不能携带样本行、
    连接串、账号密码、SQL 或原始 HTTP 响应。适配器负责把真实 DTO 裁剪到该合同，Agent 在
    使用前还会进行一次递归脱敏，防止测试替身或未来适配器误带敏感字段。
    """

    datasource_id: int
    side: str
    connector_type: str | None
    metadata: Mapping[str, Any]
    object_count: int = 0
    field_count: int = 0
    warnings: tuple[str, ...] = ()
    evidence_reference: str | None = None

    def __post_init__(self) -> None:
        """冻结结果并校验最小身份字段，避免跨数据源元数据被静默混用。"""

        if isinstance(self.datasource_id, bool) or int(self.datasource_id) <= 0:
            raise ValueError("元数据发现结果的 datasource_id 无效")
        object.__setattr__(self, "datasource_id", int(self.datasource_id))
        side = str(self.side or "").strip().upper()
        if side not in {"SOURCE", "TARGET"}:
            raise ValueError("元数据发现结果的 side 无效")
        object.__setattr__(self, "side", side)
        connector_type = str(self.connector_type or "").strip().upper() or None
        object.__setattr__(self, "connector_type", connector_type)
        if not isinstance(self.metadata, Mapping):
            raise TypeError("元数据发现结果必须包含 Mapping metadata")
        object.__setattr__(self, "metadata", MappingProxyType(dict(self.metadata)))
        object.__setattr__(self, "object_count", max(0, int(self.object_count)))
        object.__setattr__(self, "field_count", max(0, int(self.field_count)))
        object.__setattr__(
            self,
            "warnings",
            tuple(str(item).strip()[:300] for item in self.warnings if str(item or "").strip())[:20],
        )
        object.__setattr__(
            self,
            "evidence_reference",
            str(self.evidence_reference).strip()[:256] if self.evidence_reference else None,
        )


class SyncMetadataDiscoveryTool(Protocol):
    """DATA_SYNC_AGENT 使用的只读元数据发现工具协议。

    协议只暴露 ``discover``，没有创建数据源、修改表结构或写入数据的方法。生产实现可以
    通过 data-sync HTTP 控制面、未来的受控 MCP Server 或测试替身接入，但所有实现都必须
    使用本请求携带的用户、租户、项目、会话和 delegation 范围再次授权。
    """

    def discover(self, request: SyncMetadataDiscoveryRequest) -> SyncMetadataDiscoveryResult:
        """在当前 PROJECT 授权范围内读取一个数据源的低敏对象和字段元数据。"""


@dataclass(frozen=True)
class _ValidationResult:
    """确定性校验的内部结果，不跨越专业 Agent 合同边界。"""

    configuration: Mapping[str, Any]
    missing_fields: tuple[str, ...]
    issue_codes: tuple[str, ...]


@dataclass(frozen=True)
class _ReviewedBaselineMerge:
    """用户审核基线与模型建议合并后的内部快照。

    ``configuration`` 是交给确定性校验器的唯一配置来源：用户已经在页面中确认的值
    会优先写入，模型只能为基线没有覆盖的字段补充建议。``conflict_fields`` 只保存
    字段路径，不保存冲突前后的具体值，既方便前端解释“模型的哪一项被拦截”，又避免
    把 SQL、WHERE 表达式或其他任务正文重复写入 Agent 事件和审计记录。
    """

    configuration: Mapping[str, Any]
    applied: bool = False
    conflict_fields: tuple[str, ...] = ()


@dataclass(frozen=True)
class _ModelRequestGovernance:
    """模型工具/动作建议经过专业角色边界后的治理结果。

    模型返回的 ``requestedToolNames`` 与 ``requestedActions`` 只是建议字段，本身不会触发工具。
    因此，越过 DATA_SYNC_AGENT 白名单的建议应被隔离并记入 issue，而不应该把一份已经能通过
    元数据校验的同步配置整体判失败。真正危险的是模型把 ``publish``、``taskId`` 或
    ``toolCalls`` 等副作用字段混入 configuration 正文：该正文稍后会进入 ToolPlan bridge，
    所以这种污染仍采用 fail-closed，拒绝整个专业 turn。

    ``accepted_read_tools`` 只包含“角色白名单 AND 本轮 delegation”的只读建议；它用于公开
    草案说明，不会在这里再次执行元数据工具。``quarantined_*`` 只保留稳定名称以便前端解释
    治理结果，不携带参数、模型原文或隐藏推理。
    """

    accepted_read_tools: tuple[str, ...] = ()
    quarantined_tool_names: tuple[str, ...] = ()
    quarantined_actions: tuple[str, ...] = ()
    quarantined_configuration_field_count: int = 0
    fatal_error_code: str | None = None


@dataclass(frozen=True)
class _TrustedDatasourceFacts:
    """从结构化上下文提取的源端/目标端数据源事实。

    这是 DATA_SYNC_AGENT 防止“模型根据 MySQL 文本猜数据源 ID”的核心边界。模型可以根据
    ``objective`` 规划同步模式和表名，但数据源 ID、连接器类型只能来自主 Agent 已经授权的
    context_summary 或 DATASOURCE_AGENT.structuredOutput；缺少这些事实时必须停下来补参。
    """

    source_datasource_id: int | None = None
    target_datasource_id: int | None = None
    source_connector_type: str | None = None
    target_connector_type: str | None = None


class DataSyncSpecialistAgent:
    """只负责同步配置规划的真实 DATA_SYNC_AGENT。

    工作流分为五个清晰阶段：

    1. 缩减主 Agent 上下文并检查本轮只读工具委派；
    2. 从主 Agent/DATASOURCE_AGENT 事实中确认双方数据源，并按需调用只读元数据工具；
    3. 通过注入的 ``SyncPlanningModel`` 完成一次独立模型规划；
    4. 拒绝模型伪造的保存、发布、执行等副作用；
    5. 以真实元数据为事实源校验对象与字段映射，返回 COMPLETED 或 WAITING_FOR_INPUT。

    该类没有业务写工具、数据库客户端或 Java 写接口；唯一注入的元数据工具是只读协议，
    这是结构上的最小权限保证，而不只是 prompt 约束。
    """

    _ROLE = AgentSessionRole.DATA_SYNC_AGENT
    _AGENT_TOOL_ALLOWLIST = frozenset(
        {
            "datasource.source.metadata.read",
            "datasource.target.metadata.read",
            "sync.cdc.readiness.check",
        }
    )
    _SCHEDULED_MODES = frozenset({"SCHEDULED_BATCH", "SCHEDULED_FULL"})
    _SYNC_MODES = frozenset(
        {"FULL", "SCHEDULED_BATCH", "SCHEDULED_FULL", "CUSTOM_SQL_QUERY", "CDC_STREAMING"}
    )
    _FORBIDDEN_OUTPUT_KEYS = frozenset(
        {
            "action",
            "actions",
            "execute",
            "executed",
            "execution",
            "executionid",
            "publish",
            "published",
            "publishresult",
            "persist",
            "persisted",
            "run",
            "runresult",
            "save",
            "saved",
            "sideeffect",
            "sideeffects",
            "taskid",
            "toolcall",
            "toolcalls",
        }
    )
    _SAFE_INVOCATION_SUMMARY_KEYS = frozenset(
        {
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
    # 模型适配器可能附带这些低敏失败分类。它们只说明失败发生在
    # Provider 传输、Provider 返回、JSON 解析或适配器读取阶段，不包含 endpoint、
    # prompt、响应正文、工具参数或认证信息。白名单保证失败事实可以帮助运维排查，
    # 同时不会把第三方 Provider 的原始异常泄露给用户。
    _MODEL_FAILURE_REASON_CODES = frozenset(
        {
            "MODEL_TIMEOUT",
            "MODEL_PROVIDER_ERROR",
            "MODEL_PROVIDER_NETWORK_ERROR",
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
    _SENSITIVE_CONTEXT_KEY_PARTS = (
        "prompt",
        "reasoning",
        "thought",
        "chainofthought",
        "rawresponse",
        "modeloutput",
        "rawlog",
        "logbody",
        "stacktrace",
    )
    # 这些是同步向导允许用户审核的业务字段。它们与 ``plan_response`` 的 handoff
    # 白名单保持一致，但数据源 ID 有意排除在外：ID 仍然只能来自结构化授权事实，
    # 即使用户页面或模型返回了一个数字，也不能绕过 DATASOURCE_AGENT/主 Agent 的事实边界。
    _REVIEWED_BASELINE_SCALAR_ALIASES = (
        ("taskName", ("taskName", "task_name")),
        ("syncMode", ("syncMode", "sync_mode")),
        ("writeStrategy", ("writeStrategy", "write_strategy", "writeMode", "write_mode")),
        ("scheduleConfig", ("scheduleConfig", "schedule_config")),
        ("customSqlText", ("customSqlText", "custom_sql_text")),
    )
    _REVIEWED_MAPPING_ALIASES = (
        ("objectKey", ("objectKey", "object_key")),
        ("sourceSchemaName", ("sourceSchemaName", "source_schema_name")),
        ("sourceObjectName", ("sourceObjectName", "sourceTableName", "source_object_name")),
        ("targetSchemaName", ("targetSchemaName", "target_schema_name")),
        ("targetObjectName", ("targetObjectName", "targetTableName", "target_object_name")),
        # ``where`` 是旧手工向导仍可能提交的别名，进入专业 Agent 后统一成 whereCondition。
        ("whereCondition", ("whereCondition", "where", "where_condition")),
    )
    _REVIEWED_FIELD_ALIASES = (
        ("sourceField", ("sourceField", "source_field")),
        ("sourceType", ("sourceType", "source_type")),
        ("targetField", ("targetField", "target_field")),
        ("targetType", ("targetType", "target_type")),
        ("nullable", ("nullable",)),
        ("primaryKey", ("primaryKey", "primary_key")),
        ("syncEnabled", ("syncEnabled", "sync_enabled")),
        ("typeCompatible", ("typeCompatible", "type_compatible")),
        ("transform", ("transform",)),
    )

    def __init__(
        self,
        model: SyncPlanningModel,
        *,
        metadata_discovery_tool: SyncMetadataDiscoveryTool | None = None,
        agent_id: str = "data-sync-specialist-v1",
    ) -> None:
        """创建同步规划专业 Agent。

        Args:
            model: 独立执行同步规划的模型适配器。该对象只能返回建议，不能获得业务写客户端。
            metadata_discovery_tool: 只读元数据发现工具。上下文已经包含双方元数据时可以不注入；
                生产装配应注入真实 data-sync HTTP 适配器，使缺少元数据时能够自动补齐事实。
            agent_id: 写入审计结果的稳定专业 Agent 实例标识。

        Raises:
            ValueError: 模型或 Agent 标识缺失时拒绝启动，避免产生无法审计的匿名调用。
        """

        if model is None:
            raise ValueError("DATA_SYNC_AGENT 必须注入 SyncPlanningModel")
        if not str(agent_id or "").strip():
            raise ValueError("DATA_SYNC_AGENT 必须提供非空 agent_id")
        self._model = model
        self._metadata_discovery_tool = metadata_discovery_tool
        self._agent_id = str(agent_id).strip()

    @property
    def role(self) -> AgentSessionRole:
        """返回注册表用于路由的固定角色，实例不能在运行时伪装成其他 Agent。"""

        return self._ROLE

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None = None,
    ) -> SpecialistTurnResult:
        """独立规划并确定性验证一份同步任务配置草案。

        ``COMPLETED`` 只表示“配置草案已经具备交给用户审核或预检查的必要信息”，不表示任务已经保存、
        发布或开始执行。正常缺项使用 ``WAITING_FOR_INPUT``；模型异常、越权工具建议或伪造副作用才使用
        ``FAILED``，从而让主 Agent 能区分“需要补参”和“专业 Agent 本轮失败”。
        """

        started_at = time.perf_counter()
        if request.role != self.role:
            # 直接调用 specialist 时也必须和 Coordinator 一样返回可审计的 FAILED，不能
            # 把角色路由错误暴露成未捕获异常，导致旁路调用者绕过统一失败隔离。
            return self._failed_result(
                request,
                None,
                started_at,
                error_code="DATA_SYNC_AGENT_ROLE_MISMATCH",
                summary="同步规划专业 Agent 拒绝了不匹配的角色委派。",
            )

        # 同步规划后续会读取项目内数据源元数据。项目缺失时不能依赖模型或下游客户端猜测
        # 范围，更不能降级到租户级元数据查询。
        if not self._has_project_scope(request.scope.project_id):
            return self._failed_result(
                request,
                None,
                started_at,
                error_code="DATA_SYNC_PROJECT_SCOPE_REQUIRED",
                summary="同步规划缺少明确项目范围，已停止读取数据源元数据。",
            )

        delegated_read_tools = tuple(
            sorted(self._AGENT_TOOL_ALLOWLIST.intersection(request.scope.allowed_tool_names))
        )
        tool_activities: list[SpecialistToolActivity] = []
        evidence_references: list[str] = list(request.evidence_references)
        trusted_datasources = self._trusted_datasource_facts(request.context_summary)
        planning_context = self._low_sensitive_context(request.context_summary)
        if not isinstance(planning_context, dict):
            # 当前实现的脱敏函数始终返回 dict；这里保留显式保护，避免未来改动后把不可变标量
            # 误当成上下文继续传给模型或校验器。
            planning_context = {}
        planning_context["trustedDatasourceFacts"] = self._trusted_facts_summary(trusted_datasources)
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_STARTED",
            status="RUNNING",
            summary="同步规划专业 Agent 已开始分析低敏配置上下文。",
        )
        self._emit(
            event_sink,
            request,
            action="TOOL_ALLOWLIST_CHECKED",
            status="SUCCEEDED",
            summary=f"已完成只读工具白名单检查，本轮可见 {len(delegated_read_tools)} 个工具。",
            attributes={"visibleToolCount": len(delegated_read_tools)},
        )

        metadata_error = self._discover_missing_metadata(
            request=request,
            context=planning_context,
            trusted_datasources=trusted_datasources,
            delegated_read_tools=delegated_read_tools,
            event_sink=event_sink,
            tool_activities=tool_activities,
            evidence_references=evidence_references,
        )
        if metadata_error is not None:
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code=metadata_error,
                summary="读取同步两端元数据失败，已停止本轮规划，未生成或执行任何任务。",
                tool_activities=tuple(tool_activities),
                evidence_references=tuple(evidence_references),
            )

        model_input = SyncPlanningModelInput(
            objective=self._bounded_text(request.objective, 4_000),
            context=planning_context,
            allowed_tool_names=delegated_read_tools,
            max_output_tokens=request.budget.max_output_tokens,
            # 专业模型和主模型共用统一网关治理。身份字段只参与预算、隔离、审计和追踪，
            # 不会被拼进用户可见配置，也不能替代 Java/Gateway 的业务权限校验。
            tenant_id=request.scope.tenant_id,
            project_id=request.scope.project_id,
            actor_id=request.scope.actor_id,
            session_id=request.session_id,
            run_id=request.run_id,
            trace_id=self._trace_id(request.context_summary, request),
        )
        self._emit(
            event_sink,
            request,
            action="MODEL_PLANNING_STARTED",
            status="RUNNING",
            summary="已向独立同步规划模型提交低敏目标和结构化事实。",
        )

        try:
            raw_output = self._model.plan(model_input)
            model_output = self._coerce_model_output(raw_output)
        except Exception as exc:
            # Provider 的原始异常经常包含 URL、请求片段或认证信息，因此专业 Agent 只回传稳定错误码。
            failure_details = self._model_failure_details(exc)
            failure_summary = self._model_failure_summary(failure_details)
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code="DATA_SYNC_SPECIALIST_MODEL_FAILED",
                summary=failure_summary,
                structured_output=failure_details,
                tool_activities=tuple(tool_activities),
                evidence_references=tuple(evidence_references),
            )

        invocation_summary = self._safe_invocation_summary(model_output)
        self._emit(
            event_sink,
            request,
            action="MODEL_PLANNING_COMPLETED",
            status="SUCCEEDED",
            summary="独立同步规划模型已返回配置建议，正在执行确定性校验。",
            attributes={
                "modelName": invocation_summary.get("modelName"),
                "latencyMs": invocation_summary.get("latencyMs", 0),
            },
        )

        request_governance = self._govern_model_requests(model_output, delegated_read_tools)
        if request_governance.fatal_error_code is not None:
            return self._failed_result(
                request,
                event_sink,
                started_at,
                error_code=request_governance.fatal_error_code,
                summary=(
                    "模型建议包含 DATA_SYNC_AGENT 无权执行的工具或副作用，已在保存、发布和执行之前拦截。"
                ),
                model_invocation_summary=invocation_summary,
                tool_activities=tuple(tool_activities),
                evidence_references=tuple(evidence_references),
            )

        # 先合并用户在高级配置页已经审核的基线，再进入确定性校验。顺序不能反过来：
        # 如果先校验模型建议，模型删掉用户已填的表映射或 WHERE 后，校验器只能把它
        # 当成“用户尚未配置”，从而产生错误的补参提示，甚至把错误建议交给后续流程。
        baseline_merge = self._merge_reviewed_baseline(
            model_output.configuration,
            model_input.context,
        )
        if baseline_merge.applied:
            self._emit(
                event_sink,
                request,
                action="USER_REVIEWED_BASELINE_APPLIED",
                status="SUCCEEDED",
                summary=(
                    "已合并用户审核过的同步配置；模型只能补充用户未填写的项目，不能覆盖或删除已确认内容。"
                ),
                attributes={
                    "conflictCount": len(baseline_merge.conflict_fields),
                    "baselineProtected": True,
                },
            )

        validation = self._validate_configuration(
            self._complete_explicit_object_mappings(
                baseline_merge.configuration,
                objective=model_input.objective,
                context=model_input.context,
            ),
            model_input.context,
            trusted_datasources=trusted_datasources,
        )
        duration_ms = self._duration_ms(started_at)
        validation_issue_codes = list(validation.issue_codes)
        model_governance_issue_codes: list[str] = []
        if request_governance.quarantined_tool_names:
            self._append_unique(
                model_governance_issue_codes,
                "MODEL_UNAVAILABLE_TOOL_SUGGESTIONS_QUARANTINED",
            )
        if request_governance.quarantined_actions:
            self._append_unique(
                model_governance_issue_codes,
                "MODEL_SIDE_EFFECT_SUGGESTIONS_QUARANTINED",
            )
        if request_governance.quarantined_configuration_field_count:
            # A model sometimes mirrors the boundary as ``persisted: false`` or
            # ``executed: false`` inside its draft.  Those values grant no capability and
            # the deterministic validator never copies them into the normalized task.
            # Keep a count for governance diagnostics, but do not turn a valid draft into
            # a failed turn merely because the model explicitly said that it did nothing.
            self._append_unique(
                model_governance_issue_codes,
                "MODEL_INACTIVE_SIDE_EFFECT_FIELDS_QUARANTINED",
            )
        if baseline_merge.conflict_fields:
            # The deterministic merge has already restored every user-reviewed value. The disagreement is
            # therefore useful audit evidence, not an unresolved task-configuration defect. Keeping it out
            # of validationIssueCodes lets the Bridge proceed with the protected baseline while the UI can
            # still explain which model suggestions were ignored.
            self._append_unique(
                model_governance_issue_codes,
                "MODEL_CONFIGURATION_CONFLICT_WITH_USER_BASELINE",
            )
        structured_output = {
            **dict(validation.configuration),
            "draftOnly": True,
            "persisted": False,
            "published": False,
            "executed": False,
            "validationIssueCodes": tuple(validation_issue_codes),
            # Bridge 会把 validationIssueCodes 全部视为业务阻断项；模型治理诊断已经被
            # DATA_SYNC_AGENT 隔离且不会进入 ToolPlan，因此必须单独表达为非阻断审计信息。
            "modelGovernanceIssueCodes": tuple(model_governance_issue_codes),
            "requestedReadTools": request_governance.accepted_read_tools,
            "quarantinedToolSuggestionCount": len(request_governance.quarantined_tool_names),
            "quarantinedActionSuggestionCount": len(request_governance.quarantined_actions),
            "quarantinedConfigurationFieldCount": (
                request_governance.quarantined_configuration_field_count
            ),
            "metadataDiscovery": self._metadata_discovery_summary(tool_activities),
            "userReviewedBaselineApplied": baseline_merge.applied,
            "userReviewedBaselineConflictFields": baseline_merge.conflict_fields,
        }
        if validation.missing_fields:
            self._emit(
                event_sink,
                request,
                action="CONFIGURATION_WAITING_FOR_INPUT",
                status=SpecialistTurnStatus.WAITING_FOR_INPUT.value,
                summary=f"同步配置草案仍缺少 {len(validation.missing_fields)} 项必要事实。",
                attributes={"requiredInputCount": len(validation.missing_fields)},
            )
            return SpecialistTurnResult(
                agent_id=self._agent_id,
                role=self.role,
                turn_id=request.turn_id,
                status=SpecialistTurnStatus.WAITING_FOR_INPUT,
                public_summary="同步配置草案尚未完整，请补充列出的配置或真实元数据后继续规划。",
                structured_output=structured_output,
                evidence_references=tuple(evidence_references),
                tool_activities=tuple(tool_activities),
                model_invocation_summary=invocation_summary,
                required_input_fields=validation.missing_fields,
                duration_ms=duration_ms,
            )

        mapping_count = len(structured_output.get("objectMappings") or ())
        self._emit(
            event_sink,
            request,
            action="CONFIGURATION_DRAFT_COMPLETED",
            status=SpecialistTurnStatus.COMPLETED.value,
            summary=f"同步配置草案已通过确定性完整性校验，共包含 {mapping_count} 条对象映射。",
            attributes={
                "syncMode": structured_output.get("syncMode"),
                "objectMappingCount": mapping_count,
                "durationMs": duration_ms,
            },
        )
        return SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.COMPLETED,
            public_summary="同步配置草案已生成并通过完整性校验，等待主 Agent 提交用户审核。",
            structured_output=structured_output,
            evidence_references=tuple(evidence_references),
            tool_activities=tuple(tool_activities),
            model_invocation_summary=invocation_summary,
            duration_ms=duration_ms,
        )

    @classmethod
    def _complete_explicit_object_mappings(
        cls,
        configuration: Mapping[str, Any],
        *,
        objective: str,
        context: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        """Complete an omitted mapping from explicit names verified by metadata.

        The planning model may return an incomplete draft when the request is
        ambiguous. It must not erase table names that the user stated clearly
        and that both metadata tools have already verified. This method is
        deliberately narrow: it runs only when the model returned no mapping,
        matches names against both metadata snapshots, and refuses ambiguous
        target tables that exist in multiple schemas. It never turns a
        connector name such as MySQL or PostgreSQL into a datasource fact.

        Field mappings and the default ``NO_FILTER`` state remain the job of
        ``_validate_object_mapping``. The returned value is still a draft;
        Java owns authorization, precheck, persistence, and execution.
        """

        if not isinstance(configuration, Mapping):
            return configuration
        raw_mappings = configuration.get("objectMappings") or configuration.get("object_mappings")
        complete_model_mappings = [
            dict(item)
            for item in raw_mappings
            if isinstance(item, Mapping)
            and cls._metadata_object_name(
                {
                    "objectName": item.get("sourceObjectName")
                    or item.get("sourceTableName")
                    or item.get("source_object_name")
                }
            )
            and cls._metadata_object_name(
                {
                    "objectName": item.get("targetObjectName")
                    or item.get("targetTableName")
                    or item.get("target_object_name")
                }
            )
        ] if isinstance(raw_mappings, (list, tuple)) else []

        source_objects = cls._metadata_objects(cls._metadata_from_context(context, "source"))
        target_objects = cls._metadata_objects(cls._metadata_from_context(context, "target"))
        user_text = str(objective or "").strip()
        if not source_objects or not target_objects or not user_text:
            return configuration

        target_schema = cls._explicit_schema_from_objective(user_text)
        mappings: list[dict[str, Any]] = []
        for source in source_objects:
            source_name = cls._metadata_object_name(source)
            if not source_name or not cls._identifier_mentioned(user_text, source_name):
                continue
            target_candidates = [
                target
                for target in target_objects
                if (cls._metadata_object_name(target) or "").casefold() == source_name.casefold()
            ]
            if target_schema:
                target_candidates = [
                    target
                    for target in target_candidates
                    if (cls._metadata_schema_name(target) or "").casefold() == target_schema.casefold()
                ]
            if len(target_candidates) != 1:
                # A same-name table in multiple target schemas is ambiguous.
                continue
            target = target_candidates[0]
            mappings.append(
                {
                    "objectKey": f"agent-explicit-metadata-{len(mappings) + 1}",
                    "sourceSchemaName": cls._metadata_schema_name(source),
                    "sourceObjectName": source_name,
                    "targetSchemaName": cls._metadata_schema_name(target),
                    "targetObjectName": cls._metadata_object_name(target),
                    "whereCondition": "",
                }
            )

        if not mappings:
            return configuration
        mapped_source_names = {
            str(
                item.get("sourceObjectName")
                or item.get("sourceTableName")
                or item.get("source_object_name")
                or ""
            ).strip().casefold()
            for item in complete_model_mappings
        }
        completed_mappings = [
            *complete_model_mappings,
            *[
                item
                for item in mappings
                if str(item.get("sourceObjectName") or "").strip().casefold()
                not in mapped_source_names
            ],
        ]
        if completed_mappings == complete_model_mappings and len(complete_model_mappings) == len(
            raw_mappings or ()
        ):
            return configuration
        completed = dict(configuration)
        completed["objectMappings"] = completed_mappings
        completed["objectMappingsSource"] = "USER_EXPLICIT_TABLES_VERIFIED_BY_METADATA"
        return completed

    @classmethod
    def _explicit_schema_from_objective(cls, objective: str) -> str | None:
        """Read an explicitly named schema without interpreting connector text."""

        patterns = (
            r"(?<![A-Za-z0-9_$])(?P<schema>[A-Za-z_][A-Za-z0-9_$]{0,127})\s+schema(?![A-Za-z0-9_$])",
            r"\bschema\s*(?:is|=|:|为|是|叫做|中的)?\s*(?P<schema>[A-Za-z_][A-Za-z0-9_$]{0,127})",
        )
        for pattern in patterns:
            matches = tuple(re.finditer(pattern, objective, re.IGNORECASE))
            if not matches:
                continue
            candidate = cls._text(matches[-1].group("schema"))
            if candidate and candidate.casefold() not in {"source", "target", "same", "name"}:
                return candidate
        return None

    @classmethod
    def _metadata_object_name(cls, metadata_object: Mapping[str, Any]) -> str | None:
        """Return the canonical object name from a low-sensitive snapshot."""

        if not isinstance(metadata_object, Mapping):
            return None
        return cls._text(
            metadata_object.get("tableName")
            or metadata_object.get("objectName")
            or metadata_object.get("name")
        )

    @classmethod
    def _metadata_schema_name(cls, metadata_object: Mapping[str, Any]) -> str | None:
        """Return a schema name while preserving schema-less connectors as None."""

        if not isinstance(metadata_object, Mapping):
            return None
        return cls._text(metadata_object.get("schemaName") or metadata_object.get("schema"))

    @staticmethod
    def _identifier_mentioned(text: str, identifier: str) -> bool:
        """Match an object identifier as a token, not as a substring."""

        return re.search(
            rf"(?<![A-Za-z0-9_$]){re.escape(identifier)}(?![A-Za-z0-9_$])",
            text,
            re.IGNORECASE,
        ) is not None

    def _discover_missing_metadata(
        self,
        *,
        request: SpecialistTurnRequest,
        context: dict[str, Any],
        trusted_datasources: _TrustedDatasourceFacts,
        delegated_read_tools: tuple[str, ...],
        event_sink: SpecialistEventSink | None,
        tool_activities: list[SpecialistToolActivity],
        evidence_references: list[str],
    ) -> str | None:
        """在规划模型前补齐缺失的源端和目标端真实元数据。

        这里采用“每一侧独立处理”的方式：源端元数据成功而目标端失败时，工具活动仍然会
        记录源端成功和目标端失败，但本轮整体返回 FAILED，绝不拿半份事实继续生成可执行草案。
        只有当数据源 ID 已由结构化授权上下文提供、且对应只读工具位于本轮 delegation 白名单时，
        才允许发起 HTTP 发现；缺 ID、缺工具或未装配适配器都只会让后续确定性校验返回补参，
        不会根据自然语言尝试猜测数据源。
        """

        for side, datasource_id, connector_type, tool_name in (
            (
                "source",
                trusted_datasources.source_datasource_id,
                trusted_datasources.source_connector_type,
                "datasource.source.metadata.read",
            ),
            (
                "target",
                trusted_datasources.target_datasource_id,
                trusted_datasources.target_connector_type,
                "datasource.target.metadata.read",
            ),
        ):
            existing_metadata = self._metadata_from_context(context, side)
            if self._metadata_context_present(existing_metadata):
                existing_datasource_id = self._metadata_datasource_id(existing_metadata)
                if (
                    datasource_id is not None
                    and existing_datasource_id is not None
                    and existing_datasource_id != datasource_id
                ):
                    return "DATA_SYNC_METADATA_CONTEXT_SCOPE_MISMATCH"
                continue
            if datasource_id is None or tool_name not in delegated_read_tools:
                continue
            # 测试和离线规划可以只注入预加载元数据；生产 app 装配必须注入真实适配器。
            # 未注入时不能伪造“发现成功”，但也不把基础设施缺失误报成下游 HTTP 失败，
            # 让用户看到 sourceTableMetadata/targetTableMetadata 这样的明确补参项。
            if self._metadata_discovery_tool is None:
                continue

            tool_started_at = time.perf_counter()
            self._emit(
                event_sink,
                request,
                action="METADATA_DISCOVERY_STARTED",
                status="RUNNING",
                summary=f"正在读取{self._side_label(side)}授权的数据源元数据（schema、表、字段和主键摘要）。",
                attributes={"side": side, "includeColumns": True},
            )
            try:
                metadata_query = self._metadata_query_hints(
                    request=request,
                    context=context,
                    side=side,
                    connector_type=connector_type,
                )
                discovery_request = SyncMetadataDiscoveryRequest(
                    tenant_id=request.scope.tenant_id,
                    project_id=request.scope.project_id or "",
                    actor_id=request.scope.actor_id,
                    delegation_id=request.scope.delegation_id,
                    session_id=request.session_id,
                    run_id=request.run_id,
                    trace_id=self._trace_id(request.context_summary, request),
                    datasource_id=datasource_id,
                    side=side,
                    connector_type=connector_type,
                    authorized_project_id=request.scope.project_id,
                    table_names=metadata_query["table_names"],
                    schema_pattern=metadata_query["schema_pattern"],
                    filter_mode=metadata_query["filter_mode"],
                )
                discovered = self._metadata_discovery_tool.discover(discovery_request)
                if not isinstance(discovered, SyncMetadataDiscoveryResult):
                    raise SyncMetadataDiscoveryError("SYNC_METADATA_RESULT_TYPE_INVALID")
                if discovered.datasource_id != datasource_id or discovered.side != side.upper():
                    raise SyncMetadataDiscoveryError("SYNC_METADATA_SCOPE_MISMATCH")
                if (
                    connector_type
                    and discovered.connector_type
                    and connector_type != discovered.connector_type
                ):
                    raise SyncMetadataDiscoveryError("SYNC_METADATA_CONNECTOR_MISMATCH")
            except SyncMetadataDiscoveryError as exc:
                error_code = self._metadata_error_code(exc.code)
                duration_ms = self._duration_ms(tool_started_at)
                tool_activities.append(
                    SpecialistToolActivity(
                        tool_name=tool_name,
                        status="FAILED",
                        public_summary=f"读取{self._side_label(side)}元数据失败，错误码：{error_code}。",
                        duration_ms=duration_ms,
                    )
                )
                self._emit(
                    event_sink,
                    request,
                    action="METADATA_DISCOVERY_COMPLETED",
                    status="FAILED",
                    summary=f"读取{self._side_label(side)}元数据失败，已停止后续规划。",
                    attributes={"side": side, "errorCode": error_code, "durationMs": duration_ms},
                )
                return error_code
            except ValueError:
                error_code = "DATA_SYNC_METADATA_SCOPE_REQUIRED"
                duration_ms = self._duration_ms(tool_started_at)
                tool_activities.append(
                    SpecialistToolActivity(
                        tool_name=tool_name,
                        status="FAILED",
                        public_summary=f"读取{self._side_label(side)}元数据缺少有效项目授权范围。",
                        duration_ms=duration_ms,
                    )
                )
                self._emit(
                    event_sink,
                    request,
                    action="METADATA_DISCOVERY_COMPLETED",
                    status="FAILED",
                    summary=f"读取{self._side_label(side)}元数据缺少有效项目授权范围，已停止后续规划。",
                    attributes={"side": side, "errorCode": error_code, "durationMs": duration_ms},
                )
                return error_code
            except Exception:
                error_code = "DATA_SYNC_METADATA_DISCOVERY_FAILED"
                duration_ms = self._duration_ms(tool_started_at)
                tool_activities.append(
                    SpecialistToolActivity(
                        tool_name=tool_name,
                        status="FAILED",
                        public_summary=f"读取{self._side_label(side)}元数据失败，已安全停止。",
                        duration_ms=duration_ms,
                    )
                )
                self._emit(
                    event_sink,
                    request,
                    action="METADATA_DISCOVERY_COMPLETED",
                    status="FAILED",
                    summary=f"读取{self._side_label(side)}元数据失败，已停止后续规划。",
                    attributes={"side": side, "errorCode": error_code, "durationMs": duration_ms},
                )
                return error_code

            safe_metadata = self._low_sensitive_context(discovered.metadata)
            context[f"{side}Metadata"] = safe_metadata
            object_count, field_count = self._metadata_counts(safe_metadata)
            if discovered.evidence_reference and discovered.evidence_reference not in evidence_references:
                evidence_references.append(discovered.evidence_reference)
            duration_ms = self._duration_ms(tool_started_at)
            tool_activities.append(
                SpecialistToolActivity(
                    tool_name=tool_name,
                    status="SUCCEEDED",
                    public_summary=(
                        f"已读取{self._side_label(side)}元数据：{object_count} 张表、{field_count} 个字段摘要。"
                    ),
                    evidence_reference=discovered.evidence_reference,
                    duration_ms=duration_ms,
                )
            )
            self._emit(
                event_sink,
                request,
                action="METADATA_DISCOVERY_COMPLETED",
                status="SUCCEEDED",
                summary=f"已完成{self._side_label(side)}元数据读取，可用于对象和字段映射校验。",
                attributes={
                    "side": side,
                    "objectCount": object_count,
                    "fieldCount": field_count,
                    "durationMs": duration_ms,
                },
            )
        return None

    @classmethod
    def _metadata_query_hints(
        cls,
        *,
        request: SpecialistTurnRequest,
        context: Mapping[str, Any],
        side: str,
        connector_type: str | None,
    ) -> dict[str, Any]:
        """根据已确认事实构造元数据查询提示。

        普通的 ``ALL + maxTables`` 查询适合打开对象选择器，但不适合 Agent 校验用户已经
        明确说出的表名：表数量一多，目标表可能排在前 100 张之外，后续校验就会把“未被本次
        快照返回”错误解释成“数据库中不存在”。这里先从用户审核过的对象映射读取精确名称；
        没有结构化映射时，只提取带下划线的显式标识符或反引号/引号中的标识符，避免把
        ``MySQL``、``PostgreSQL``、``FULL`` 等自然语言实体误当成表名。

        返回值只用于只读发现请求。它不授予任何数据源权限，也不改变 Java 端最终的权限、
        元数据和任务预检查结果。
        """

        side = str(side or "").strip().lower()
        name_keys = (
            f"{side}TableNames",
            f"{side}ObjectNames",
            f"{side}_table_names",
            f"{side}_object_names",
        )
        names: list[str] = []

        def append_name(value: Any) -> None:
            """把一个或一组安全标识符去重加入精确元数据查询列表。"""

            if isinstance(value, str):
                candidates = (value,)
            elif isinstance(value, (list, tuple, set)):
                candidates = tuple(value)
            else:
                return
            for candidate in candidates:
                normalized = cls._text(candidate)
                if normalized and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$]{0,127}", normalized):
                    if normalized.casefold() not in {item.casefold() for item in names}:
                        names.append(normalized)

        # 这些字段是页面已经确认的结构化任务基线，优先级高于模型返回值和自然语言猜测。
        containers: list[Mapping[str, Any]] = [context]
        raw_request = context.get("dataSyncRequest") or context.get("data_sync_request")
        if isinstance(raw_request, Mapping):
            containers.append(raw_request)
        for container in containers:
            for key in name_keys:
                append_name(container.get(key))
            raw_mappings = container.get("objectMappings") or container.get("object_mappings")
            if isinstance(raw_mappings, (list, tuple)):
                object_key = "sourceObjectName" if side == "source" else "targetObjectName"
                alias_key = "sourceTableName" if side == "source" else "targetTableName"
                for mapping in raw_mappings:
                    if isinstance(mapping, Mapping):
                        append_name(mapping.get(object_key) or mapping.get(alias_key))

        if not names:
            # 只把非常明确的表标识符拿去精确查询；没有命中时退回普通元数据扫描，交给模型
            # 和后续消歧流程处理，而不是基于自然语言做危险的“最相近匹配”。
            objective = str(request.objective or "")
            quoted = re.findall(r"[`\"']([A-Za-z_][A-Za-z0-9_$]{0,127})[`\"']", objective)
            underscored = re.findall(
                r"(?<![A-Za-z0-9_$])([A-Za-z_][A-Za-z0-9_$]*_[A-Za-z0-9_$]*)(?![A-Za-z0-9_$])",
                objective,
            )
            excluded = {
                "mysql", "mariadb", "postgres", "postgresql", "pgsql", "public",
                "source", "target", "full", "insert", "update", "merge", "table", "schema",
            }
            for candidate in (*quoted, *underscored):
                if str(candidate).casefold() not in excluded:
                    append_name(candidate)

        schema_pattern = None
        if side == "target":
            schema_pattern = cls._explicit_schema_from_objective(str(request.objective or ""))
        for container in containers:
            raw_mappings = container.get("objectMappings") or container.get("object_mappings")
            if not isinstance(raw_mappings, (list, tuple)):
                continue
            schema_key = "sourceSchemaName" if side == "source" else "targetSchemaName"
            for mapping in raw_mappings:
                if not isinstance(mapping, Mapping):
                    continue
                candidate_schema = cls._text(mapping.get(schema_key))
                if candidate_schema:
                    schema_pattern = candidate_schema
                    break

        is_mysql = str(connector_type or "").upper() in {"MYSQL", "MARIADB"}
        if is_mysql:
            # MySQL 的 database 是 catalog，不能把历史残留 schema 作为 schemaPattern 传下游。
            schema_pattern = None
        filter_mode = "SCHEMA_AND_TABLE" if schema_pattern and not is_mysql else "TABLE"
        return {
            "table_names": tuple(names[:100]),
            "schema_pattern": schema_pattern,
            "filter_mode": filter_mode if names else "ALL",
        }

    @classmethod
    def _trusted_datasource_facts(cls, context: Mapping[str, Any]) -> _TrustedDatasourceFacts:
        """只从显式结构化字段读取双方数据源 ID 和连接器类型。

        DATASOURCE_AGENT 的结果通常形如 ``structuredOutput.sourceDatasourceId`` 加上
        ``resolutions.source``。这里兼容主 Agent 直接回填的 camelCase/snake_case 字段，
        但不遍历任意文本、不读取 ``objective``，也不把候选数组中与已选 ID 不匹配的连接器
        当作事实，避免“名称相近”被误解析成具体数据源。
        """

        if not isinstance(context, Mapping):
            return _TrustedDatasourceFacts()
        sources: list[Mapping[str, Any]] = [context]
        dependency_output = cls._dependency_datasource_output(context)
        if dependency_output is not None:
            sources.append(dependency_output)

        values: dict[str, Any] = {}
        for side in ("source", "target"):
            datasource_id: int | None = None
            connector_type: str | None = None
            for source in sources:
                candidate_id, candidate_connector = cls._extract_side_facts(source, side)
                if datasource_id is None and candidate_id is not None:
                    datasource_id = candidate_id
                if connector_type is None and candidate_connector is not None:
                    connector_type = candidate_connector
            values[f"{side}_datasource_id"] = datasource_id
            values[f"{side}_connector_type"] = connector_type
        return _TrustedDatasourceFacts(**values)

    @classmethod
    def _extract_side_facts(cls, container: Mapping[str, Any], side: str) -> tuple[int | None, str | None]:
        """从一个可信结构化容器提取某一侧的 datasourceId/connectorType。"""

        # 项目对外结构化字段使用 sourceDatasourceId/targetDatasourceId；同时兼容少数
        # Java 风格首字母大写包装，不能只支持其中一种命名而误判“没有选择数据源”。
        prefix = side
        title_prefix = side[0].upper() + side[1:]
        datasource_id = None
        connector_type = None
        for key in (
            f"{prefix}DatasourceId",
            f"{prefix}_datasource_id",
            f"{prefix}DataSourceId",
            f"{title_prefix}DatasourceId",
            f"{title_prefix}DataSourceId",
        ):
            datasource_id = cls._positive_id(container.get(key))
            if datasource_id is not None:
                break
        for key in (
            f"{prefix}ConnectorType",
            f"{prefix}_connector_type",
            f"{prefix}DatabaseType",
            f"{title_prefix}ConnectorType",
            f"{title_prefix}DatabaseType",
        ):
            connector_type = cls._safe_connector_type(container.get(key))
            if connector_type is not None:
                break

        nested_candidates = (
            container.get(side),
            container.get(f"{prefix}Datasource"),
            container.get(f"{prefix}DataSource"),
            container.get(f"{title_prefix}Datasource"),
            container.get(f"{title_prefix}DataSource"),
            container.get(f"{side}_datasource"),
        )
        for nested in nested_candidates:
            if not isinstance(nested, Mapping):
                continue
            if datasource_id is None:
                for key in ("datasourceId", "datasource_id", "selectedDatasourceId", "selected_datasource_id"):
                    datasource_id = cls._positive_id(nested.get(key))
                    if datasource_id is not None:
                        break
            if connector_type is None:
                for key in ("connectorType", "connector_type", "databaseType"):
                    connector_type = cls._safe_connector_type(nested.get(key))
                    if connector_type is not None:
                        break

        resolutions = container.get("resolutions")
        resolution = resolutions.get(side) if isinstance(resolutions, Mapping) else None
        if isinstance(resolution, Mapping):
            if datasource_id is None:
                datasource_id = cls._positive_id(
                    resolution.get("selectedDatasourceId") or resolution.get("selected_datasource_id")
                )
            requested = resolution.get("requested")
            if connector_type is None and datasource_id is not None:
                for candidate in resolution.get("candidates") or ():
                    if not isinstance(candidate, Mapping):
                        continue
                    candidate_id = cls._positive_id(
                        candidate.get("datasourceId") or candidate.get("datasource_id")
                    )
                    if candidate_id == datasource_id:
                        connector_type = cls._safe_connector_type(
                            candidate.get("connectorType") or candidate.get("connector_type")
                        )
                        break
            # requested.connectorType 可能只是用户输入的筛选条件；只有没有已选候选的真实
            # connectorType 时才使用它作为低敏提示，避免把名称消歧前的文本冒充事实。
            if connector_type is None and isinstance(requested, Mapping):
                connector_type = cls._safe_connector_type(
                    requested.get("connectorType") or requested.get("connector_type")
                )
        return datasource_id, connector_type

    @classmethod
    def _dependency_datasource_output(cls, context: Mapping[str, Any]) -> Mapping[str, Any] | None:
        """读取 DATASOURCE_AGENT 已完成结果的 structuredOutput，不消费其模型原文。"""

        dependencies = context.get("dependencyResults") or context.get("dependency_results")
        if not isinstance(dependencies, Mapping):
            return None
        candidate = None
        for key, value in dependencies.items():
            if cls._normalized_key(key) == "datasourceagent":
                candidate = value
                break
        if not isinstance(candidate, Mapping):
            return None
        structured = candidate.get("structuredOutput") or candidate.get("structured_output")
        return structured if isinstance(structured, Mapping) else None

    @classmethod
    def _trusted_facts_summary(cls, facts: _TrustedDatasourceFacts) -> dict[str, Any]:
        """生成可给同步规划模型使用的低敏数据源事实摘要。"""

        return {
            "source": {
                "datasourceId": facts.source_datasource_id,
                "connectorType": facts.source_connector_type,
            },
            "target": {
                "datasourceId": facts.target_datasource_id,
                "connectorType": facts.target_connector_type,
            },
        }

    @staticmethod
    def _trace_id(context: Mapping[str, Any], request: SpecialistTurnRequest) -> str:
        """优先使用可信上下文 traceId，否则使用当前 turn ID，确保工具 Header 永不为空。"""

        candidate = str(context.get("traceId") or context.get("trace_id") or "").strip()
        if candidate and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", candidate):
            return candidate
        return request.turn_id

    @staticmethod
    def _side_label(side: str) -> str:
        """把内部 source/target 标识转换为用户可读的中文方向。"""

        return "源端" if side == "source" else "目标端"

    @staticmethod
    def _metadata_error_code(code: str) -> str:
        """把工具错误收敛为 DATA_SYNC_AGENT 可稳定判断的前缀码。"""

        normalized = re.sub(r"[^A-Za-z0-9_.:-]", "_", str(code or "").strip())[:100]
        if not normalized:
            normalized = "DISCOVERY_FAILED"
        return f"DATA_SYNC_METADATA_{normalized}"

    @classmethod
    def _metadata_counts(cls, metadata: Any) -> tuple[int, int]:
        """仅按低敏对象/字段集合统计工具活动，不把元数据正文写入事件。"""

        objects = cls._metadata_objects(metadata)
        field_count = sum(len(cls._metadata_fields(item)) for item in objects)
        return len(objects), field_count

    @classmethod
    def _metadata_datasource_id(cls, metadata: Any) -> int | None:
        """读取元数据摘要中的可选 datasourceId，用于防止上下文残留跨数据源串用。"""

        if not isinstance(metadata, Mapping):
            return None
        return cls._positive_id(metadata.get("datasourceId") or metadata.get("datasource_id"))

    @classmethod
    def _metadata_context_present(cls, metadata: Any) -> bool:
        """区分“真实空表结果”和空字典占位，避免残缺上下文阻止真实发现。"""

        if isinstance(metadata, Mapping):
            if any(key in metadata for key in ("objects", "tables", "summary")):
                return True
            return bool(cls._metadata_objects(metadata))
        if isinstance(metadata, (list, tuple)):
            return bool(metadata)
        return False

    @staticmethod
    def _metadata_discovery_summary(tool_activities: list[SpecialistToolActivity]) -> dict[str, Any]:
        """生成不含表名和字段名的元数据发现摘要，供主 Agent 和前端展示。"""

        return {
            "source": next(
                (activity.status for activity in tool_activities if activity.tool_name == "datasource.source.metadata.read"),
                "NOT_REQUESTED",
            ),
            "target": next(
                (activity.status for activity in tool_activities if activity.tool_name == "datasource.target.metadata.read"),
                "NOT_REQUESTED",
            ),
        }

    @classmethod
    def _reviewed_sync_baseline(cls, context: Mapping[str, Any]) -> dict[str, Any]:
        """从专业上下文提取已经通过页面审核的同步配置基线。

        这里再次做一次小范围重建，而不是直接把 ``dataSyncRequest`` 原对象交给合并器。
        ``plan_response`` 已经做过一次白名单化，但 DATA_SYNC_AGENT 也可能被单元测试、
        Durable 恢复流程或其他内部入口直接调用；在领域边界再次收敛可以防止未来某个入口
        忘记复用上游过滤器时把连接凭据、控制字段或未知 JSON 带入专业 Agent。

        数据源 ID 特别不在本基线中。用户选择的数据源是否可信由结构化上下文和
        DATASOURCE_AGENT 依赖结果决定，配置基线只负责承载任务名称、模式、映射等业务正文。
        """

        if not isinstance(context, Mapping):
            return {}
        raw_request = context.get("dataSyncRequest") or context.get("data_sync_request")
        if not isinstance(raw_request, Mapping):
            return {}

        baseline: dict[str, Any] = {}
        for canonical_name, aliases in cls._REVIEWED_BASELINE_SCALAR_ALIASES:
            key = cls._first_present_key(raw_request, aliases)
            if key is None:
                continue
            value = raw_request.get(key)
            # 空字符串通常表示前端尚未填写，允许模型在此处提出补充；WHERE 则在映射层
            # 单独处理，因为空 WHERE 是用户明确确认“不过滤”的有效语义。
            if cls._has_reviewed_baseline_value(canonical_name, value):
                baseline[canonical_name] = value

        mapping_key = cls._first_present_key(raw_request, ("objectMappings", "object_mappings"))
        raw_mappings = raw_request.get(mapping_key) if mapping_key is not None else None
        if isinstance(raw_mappings, (list, tuple)) and raw_mappings:
            mappings = [
                normalized
                for raw_mapping in raw_mappings[:500]
                if isinstance(raw_mapping, Mapping)
                for normalized in (cls._normalize_reviewed_mapping(raw_mapping),)
                if normalized
            ]
            if mappings:
                baseline["objectMappings"] = mappings
        return baseline

    @classmethod
    def _normalize_reviewed_mapping(cls, raw_mapping: Mapping[str, Any]) -> dict[str, Any]:
        """把一条用户审核对象映射裁剪为可合并的业务字段。

        只复制手工向导已经定义的对象、WHERE 和字段映射字段；不存在的字段保持不存在，
        这样模型仍可以给“用户只填了源表、还没有填目标表”的映射补上缺项。WHERE 的空字符串
        则故意保留，表示用户已经确认该对象不添加过滤条件。
        """

        result: dict[str, Any] = {}
        for canonical_name, aliases in cls._REVIEWED_MAPPING_ALIASES:
            key = cls._first_present_key(raw_mapping, aliases)
            if key is None:
                continue
            value = raw_mapping.get(key)
            if canonical_name == "whereCondition":
                if isinstance(value, (str, int, float, bool)):
                    result[canonical_name] = value
            elif isinstance(value, (str, int, float, bool)):
                # 对象名称只保留标量，并在最终校验器中统一转成受限文本。
                result[canonical_name] = value

        field_key = cls._first_present_key(raw_mapping, ("fieldMappings", "field_mappings"))
        raw_fields = raw_mapping.get(field_key) if field_key is not None else None
        if isinstance(raw_fields, (list, tuple)) and raw_fields:
            fields = [
                normalized
                for raw_field in raw_fields[:2_000]
                if isinstance(raw_field, Mapping)
                for normalized in (cls._normalize_reviewed_field(raw_field),)
                if normalized
            ]
            if fields:
                result["fieldMappings"] = fields
        return result

    @classmethod
    def _normalize_reviewed_field(cls, raw_field: Mapping[str, Any]) -> dict[str, Any]:
        """保留手工字段映射的可审计白名单字段，不复制未知转换脚本或嵌套对象。"""

        return {
            canonical_name: raw_field[key]
            for canonical_name, aliases in cls._REVIEWED_FIELD_ALIASES
            for key in (cls._first_present_key(raw_field, aliases),)
            if key is not None
            and isinstance(raw_field.get(key), (str, int, float, bool))
        }

    @classmethod
    def _merge_reviewed_baseline(
        cls,
        model_configuration: Mapping[str, Any],
        context: Mapping[str, Any],
    ) -> _ReviewedBaselineMerge:
        """把用户审核基线与模型建议做“基线优先、模型补缺”的确定性合并。

        合并规则刻意不依赖模型是否声称“已确认”：

        * 用户已填的标量字段优先，模型遗漏或改写都被拦截；
        * 用户已填的对象映射按对象身份合并，基线字段优先，模型只能补充基线没有的字段；
        * 用户已填的字段映射按源字段/目标字段匹配，模型不能换目标字段、关闭同步或删除字段；
        * 模型新增的对象或字段可以保留，随后仍要经过真实元数据和模式校验；
        * ``sourceDatasourceId``/``targetDatasourceId`` 永远不从基线读取。

        这使模型成为“理解和补充配置”的参与者，而不是可以重新解释用户审核结果的最终权威。
        """

        if not isinstance(model_configuration, Mapping):
            return _ReviewedBaselineMerge(configuration={}, applied=False)
        baseline = cls._reviewed_sync_baseline(context)
        if not baseline:
            return _ReviewedBaselineMerge(configuration=dict(model_configuration), applied=False)

        model_payload = cls._configuration_payload(model_configuration)
        merged = dict(model_payload)
        conflicts: list[str] = []

        for canonical_name, aliases in cls._REVIEWED_BASELINE_SCALAR_ALIASES:
            if canonical_name not in baseline:
                continue
            model_key = cls._first_present_key(model_payload, aliases)
            if model_key is None or not cls._same_reviewed_value(
                canonical_name,
                baseline[canonical_name],
                model_payload.get(model_key),
            ):
                cls._append_unique(conflicts, f"dataSyncRequest.{canonical_name}")
            merged[canonical_name] = baseline[canonical_name]
            for alias in aliases:
                if alias != canonical_name:
                    merged.pop(alias, None)

        if "objectMappings" in baseline:
            model_mapping_key = cls._first_present_key(
                model_payload,
                ("objectMappings", "object_mappings"),
            )
            raw_model_mappings = (
                model_payload.get(model_mapping_key) if model_mapping_key is not None else None
            )
            model_mappings = (
                [
                    normalized
                    for raw_mapping in raw_model_mappings
                    if isinstance(raw_mapping, Mapping)
                    for normalized in (cls._normalize_model_mapping(raw_mapping),)
                    if normalized
                ]
                if isinstance(raw_model_mappings, (list, tuple))
                else []
            )
            merged["objectMappings"] = cls._merge_object_mappings(
                baseline["objectMappings"],
                model_mappings,
                conflicts,
            )
            if model_mapping_key == "object_mappings":
                merged.pop("object_mappings", None)

        # 如果模型使用了 dataSyncRequest 包装，保持包装形态；校验器之后会统一解包，
        # 但保留外层可以让副作用递归检查继续覆盖整个模型返回树。
        if isinstance(model_configuration.get("dataSyncRequest"), Mapping):
            wrapped = dict(model_configuration)
            wrapped["dataSyncRequest"] = merged
            return _ReviewedBaselineMerge(
                configuration=wrapped,
                applied=True,
                conflict_fields=tuple(conflicts),
            )
        return _ReviewedBaselineMerge(
            configuration=merged,
            applied=True,
            conflict_fields=tuple(conflicts),
        )

    @classmethod
    def _merge_object_mappings(
        cls,
        baseline_mappings: list[Mapping[str, Any]],
        model_mappings: list[Mapping[str, Any]],
        conflicts: list[str],
    ) -> list[dict[str, Any]]:
        """逐条合并对象映射，并将模型遗漏/覆盖记录为字段路径。"""

        merged: list[dict[str, Any]] = []
        used_model_indexes: set[int] = set()
        for index, baseline_mapping in enumerate(baseline_mappings):
            model_index = cls._match_object_mapping(
                baseline_mapping,
                model_mappings,
                used_model_indexes,
                fallback_index=index,
            )
            model_mapping = model_mappings[model_index] if model_index is not None else {}
            if model_index is None:
                cls._append_unique(conflicts, f"dataSyncRequest.objectMappings[{index}]")
            else:
                used_model_indexes.add(model_index)
            merged.append(
                cls._merge_one_object_mapping(
                    baseline_mapping,
                    model_mapping,
                    path=f"dataSyncRequest.objectMappings[{index}]",
                    conflicts=conflicts,
                )
            )

        # 模型新增对象不覆盖基线对象，保留在尾部；确定性元数据校验会再判断它是否真实存在。
        merged.extend(
            dict(model_mapping)
            for index, model_mapping in enumerate(model_mappings)
            if index not in used_model_indexes
        )
        return merged

    @classmethod
    def _merge_one_object_mapping(
        cls,
        baseline_mapping: Mapping[str, Any],
        model_mapping: Mapping[str, Any],
        *,
        path: str,
        conflicts: list[str],
    ) -> dict[str, Any]:
        """合并单个对象的表名、WHERE 和字段映射，基线字段始终优先。"""

        merged = dict(model_mapping)
        for key, baseline_value in baseline_mapping.items():
            if key == "fieldMappings":
                continue
            if key in model_mapping and not cls._same_reviewed_value(key, baseline_value, model_mapping[key]):
                cls._append_unique(conflicts, f"{path}.{key}")
            elif key not in model_mapping:
                cls._append_unique(conflicts, f"{path}.{key}")
            merged[key] = baseline_value

        if "fieldMappings" in baseline_mapping:
            model_fields = model_mapping.get("fieldMappings")
            normalized_model_fields = (
                [
                    normalized
                    for raw_field in model_fields
                    if isinstance(raw_field, Mapping)
                    for normalized in (cls._normalize_model_field(raw_field),)
                    if normalized
                ]
                if isinstance(model_fields, (list, tuple))
                else []
            )
            merged["fieldMappings"] = cls._merge_field_mappings(
                baseline_mapping["fieldMappings"],
                normalized_model_fields,
                path=f"{path}.fieldMappings",
                conflicts=conflicts,
            )
        return merged

    @classmethod
    def _merge_field_mappings(
        cls,
        baseline_fields: list[Mapping[str, Any]],
        model_fields: list[Mapping[str, Any]],
        *,
        path: str,
        conflicts: list[str],
    ) -> list[dict[str, Any]]:
        """按字段身份合并字段映射，防止模型把同名字段换成另一目标字段。"""

        merged: list[dict[str, Any]] = []
        used_model_indexes: set[int] = set()
        for index, baseline_field in enumerate(baseline_fields):
            model_index = cls._match_field_mapping(baseline_field, model_fields, used_model_indexes)
            model_field = model_fields[model_index] if model_index is not None else {}
            if model_index is None:
                cls._append_unique(conflicts, f"{path}[{index}]")
            else:
                used_model_indexes.add(model_index)
            merged_field = dict(model_field)
            for key, baseline_value in baseline_field.items():
                if key in model_field and not cls._same_reviewed_value(key, baseline_value, model_field[key]):
                    cls._append_unique(conflicts, f"{path}[{index}].{key}")
                elif key not in model_field:
                    cls._append_unique(conflicts, f"{path}[{index}].{key}")
                merged_field[key] = baseline_value
            merged.append(merged_field)
        merged.extend(
            dict(model_field)
            for index, model_field in enumerate(model_fields)
            if index not in used_model_indexes
        )
        return merged

    @classmethod
    def _normalize_model_mapping(cls, raw_mapping: Mapping[str, Any]) -> dict[str, Any]:
        """把模型对象映射转换成与基线相同的有限字段形态后再参与合并。"""

        result: dict[str, Any] = {}
        for canonical_name, aliases in cls._REVIEWED_MAPPING_ALIASES:
            key = cls._first_present_key(raw_mapping, aliases)
            if key is not None and isinstance(raw_mapping.get(key), (str, int, float, bool)):
                result[canonical_name] = raw_mapping[key]
        field_key = cls._first_present_key(raw_mapping, ("fieldMappings", "field_mappings"))
        raw_fields = raw_mapping.get(field_key) if field_key is not None else None
        if isinstance(raw_fields, (list, tuple)):
            result["fieldMappings"] = [
                normalized
                for raw_field in raw_fields
                if isinstance(raw_field, Mapping)
                for normalized in (cls._normalize_model_field(raw_field),)
                if normalized
            ]
        return result

    @classmethod
    def _normalize_model_field(cls, raw_field: Mapping[str, Any]) -> dict[str, Any]:
        """裁剪模型字段建议，避免合并器接受未知嵌套对象或控制字段。"""

        return {
            canonical_name: raw_field[key]
            for canonical_name, aliases in cls._REVIEWED_FIELD_ALIASES
            for key in (cls._first_present_key(raw_field, aliases),)
            if key is not None
            and isinstance(raw_field.get(key), (str, int, float, bool))
        }

    @classmethod
    def _match_object_mapping(
        cls,
        baseline_mapping: Mapping[str, Any],
        model_mappings: list[Mapping[str, Any]],
        used_indexes: set[int],
        *,
        fallback_index: int,
    ) -> int | None:
        """按 objectKey、源对象、目标对象和最终索引依次匹配对象映射。"""

        baseline_key = cls._mapping_identity(baseline_mapping)
        if baseline_key:
            for index, candidate in enumerate(model_mappings):
                if index not in used_indexes and cls._mapping_identity(candidate) == baseline_key:
                    return index
        if fallback_index < len(model_mappings) and fallback_index not in used_indexes:
            return fallback_index
        return None

    @classmethod
    def _match_field_mapping(
        cls,
        baseline_field: Mapping[str, Any],
        model_fields: list[Mapping[str, Any]],
        used_indexes: set[int],
    ) -> int | None:
        """优先按 sourceField 匹配字段，避免模型用另一个目标字段替换用户选择。"""

        source_name = cls._text(baseline_field.get("sourceField"))
        target_name = cls._text(baseline_field.get("targetField"))
        for preferred_name in (source_name, target_name):
            if not preferred_name:
                continue
            for index, candidate in enumerate(model_fields):
                candidate_name = cls._text(
                    candidate.get("sourceField" if preferred_name == source_name else "targetField")
                )
                if index not in used_indexes and candidate_name and candidate_name.casefold() == preferred_name.casefold():
                    return index
        return None

    @classmethod
    def _mapping_identity(cls, mapping: Mapping[str, Any]) -> tuple[str, ...]:
        """生成不含敏感值的对象身份，用于基线和模型映射的稳定配对。"""

        object_key = cls._text(mapping.get("objectKey"))
        if object_key:
            return ("key", object_key.casefold())
        values = tuple(
            (cls._text(mapping.get(name)) or "").casefold()
            for name in (
                "sourceSchemaName",
                "sourceObjectName",
                "targetSchemaName",
                "targetObjectName",
            )
        )
        return ("object", *values) if any(values) else ()

    @classmethod
    def _same_reviewed_value(cls, field_name: str, left: Any, right: Any) -> bool:
        """比较模型和基线的语义值，允许模式别名相同但不允许业务含义被替换。"""

        if field_name == "syncMode":
            left_mode = cls._normalize_sync_mode(left)
            right_mode = cls._normalize_sync_mode(right)
            return left_mode is not None and left_mode == right_mode
        if field_name in {"writeStrategy", "writeMode"}:
            left_strategy = str(left or "").strip().upper()
            right_strategy = str(right or "").strip().upper()
            return left_strategy == right_strategy or (
                left_strategy in {"UPDATE", "MERGE", "UPSERT"}
                and right_strategy in {"UPDATE", "MERGE", "UPSERT"}
            )
        return left == right

    @classmethod
    def _has_reviewed_baseline_value(cls, field_name: str, value: Any) -> bool:
        """判断标量是否代表用户真正填写的审核值。"""

        if value is None:
            return False
        if isinstance(value, str):
            return bool(value.strip())
        if isinstance(value, Mapping):
            return bool(value)
        if isinstance(value, (list, tuple)):
            return bool(value)
        return True

    @staticmethod
    def _first_present_key(container: Mapping[str, Any], aliases: tuple[str, ...]) -> str | None:
        """返回容器中第一个实际存在的别名，区分“未提供”和“提供空值”。"""

        for alias in aliases:
            if alias in container:
                return alias
        return None

    def _validate_configuration(
        self,
        raw_configuration: Mapping[str, Any],
        context: Mapping[str, Any],
        *,
        trusted_datasources: _TrustedDatasourceFacts | None = None,
    ) -> _ValidationResult:
        """把模型建议收敛为稳定任务草案，并返回所有可补充缺项。

        校验器采用“尽量保留正确部分、一次返回全部缺项”的策略，避免用户每补一个字段才发现下一个字段。
        对象和字段存在性只相信上下文中的真实元数据；模型即使声称已经验证，也不能绕过这里的比对。
        """

        source = self._configuration_payload(raw_configuration)
        missing: list[str] = []
        issues: list[str] = []
        trusted = trusted_datasources or self._trusted_datasource_facts(context)
        model_source_datasource_id = self._positive_id(
            source.get("sourceDatasourceId") or source.get("source_datasource_id")
        )
        model_target_datasource_id = self._positive_id(
            source.get("targetDatasourceId") or source.get("target_datasource_id")
        )
        result: dict[str, Any] = {
            "taskName": self._text(source.get("taskName") or source.get("task_name")),
            "objectMappingsSource": self._text(
                source.get("objectMappingsSource") or source.get("object_mappings_source")
            ),
            # 数据源 ID 只能来自可信结构化事实，不能使用模型根据自然语言猜出的数字。
            "sourceDatasourceId": trusted.source_datasource_id,
            "targetDatasourceId": trusted.target_datasource_id,
            "sourceConnectorType": trusted.source_connector_type,
            "targetConnectorType": trusted.target_connector_type,
        }
        self._require(result["taskName"], "taskName", missing)
        self._require(result["sourceDatasourceId"], "sourceDatasourceId", missing)
        self._require(result["targetDatasourceId"], "targetDatasourceId", missing)
        if (
            trusted.source_datasource_id is not None
            and model_source_datasource_id is not None
            and trusted.source_datasource_id != model_source_datasource_id
        ):
            self._append_unique(missing, "sourceDatasourceId")
            self._append_unique(issues, "SOURCE_DATASOURCE_ID_CONFLICT")
        if (
            trusted.target_datasource_id is not None
            and model_target_datasource_id is not None
            and trusted.target_datasource_id != model_target_datasource_id
        ):
            self._append_unique(missing, "targetDatasourceId")
            self._append_unique(issues, "TARGET_DATASOURCE_ID_CONFLICT")

        sync_mode = self._normalize_sync_mode(source.get("syncMode") or source.get("sync_mode"))
        result["syncMode"] = sync_mode
        if sync_mode is None:
            self._append_unique(missing, "syncMode")
            self._append_unique(issues, "SYNC_MODE_MISSING_OR_UNSUPPORTED")

        write_strategy, write_mode, write_valid = self._normalize_write_strategy(
            source.get("writeStrategy") or source.get("writeMode") or source.get("write_strategy"),
            sync_mode,
        )
        result["writeStrategy"] = write_strategy
        result["writeMode"] = write_mode
        if not write_valid:
            self._append_unique(missing, "writeStrategy")
            self._append_unique(issues, "WRITE_STRATEGY_INCOMPATIBLE_WITH_SYNC_MODE")

        schedule = source.get("scheduleConfig") or source.get("schedule_config")
        if sync_mode in self._SCHEDULED_MODES:
            result["scheduleConfig"] = schedule
            if not self._has_value(schedule):
                self._append_unique(missing, "scheduleConfig")
                self._append_unique(issues, "SCHEDULE_CONFIG_REQUIRED")
        else:
            # 非定期模式明确输出 None，避免模型夹带调度配置改变任务生命周期。
            result["scheduleConfig"] = None

        sql_text = self._text(source.get("customSqlText") or source.get("custom_sql_text"))
        result["customSqlText"] = sql_text if sync_mode == "CUSTOM_SQL_QUERY" else None
        if sync_mode == "CUSTOM_SQL_QUERY":
            if not sql_text:
                self._append_unique(missing, "customSqlText")
                self._append_unique(issues, "CUSTOM_SQL_TEXT_REQUIRED")
            elif not self._is_read_only_sql(sql_text):
                self._append_unique(missing, "customSqlText")
                self._append_unique(issues, "CUSTOM_SQL_MUST_BE_READ_ONLY_QUERY")

        raw_mappings = source.get("objectMappings") or source.get("object_mappings")
        if not isinstance(raw_mappings, (list, tuple)) or not raw_mappings:
            result["objectMappings"] = []
            self._append_unique(missing, "objectMappings")
            self._append_unique(issues, "OBJECT_MAPPING_REQUIRED")
            return _ValidationResult(result, tuple(missing), tuple(issues))

        source_metadata = self._metadata_from_context(context, "source")
        target_metadata = self._metadata_from_context(context, "target")
        normalized_mappings: list[dict[str, Any]] = []
        for index, raw_mapping in enumerate(raw_mappings):
            path = f"objectMappings[{index}]"
            if not isinstance(raw_mapping, Mapping):
                self._append_unique(missing, path)
                self._append_unique(issues, "OBJECT_MAPPING_INVALID")
                continue
            normalized_mappings.append(
                self._validate_object_mapping(
                    raw_mapping,
                    path=path,
                    sync_mode=sync_mode,
                    source_metadata=source_metadata,
                    target_metadata=target_metadata,
                    missing=missing,
                    issues=issues,
                )
            )
        result["objectMappings"] = normalized_mappings
        return _ValidationResult(result, tuple(missing), tuple(issues))

    def _validate_object_mapping(
        self,
        raw_mapping: Mapping[str, Any],
        *,
        path: str,
        sync_mode: str | None,
        source_metadata: Any,
        target_metadata: Any,
        missing: list[str],
        issues: list[str],
    ) -> dict[str, Any]:
        """校验一条严格的“源对象到目标对象”映射，并处理字段默认推断。

        同名字段默认映射只能由本方法在两端真实字段集合的交集上生成。模型直接给出的字段映射仍需逐项
        验证，缺少任意一侧表元数据时，本轮必须等待输入，绝不能把猜测标记为成功。
        """

        mapping = {
            "sourceSchemaName": self._text(
                raw_mapping.get("sourceSchemaName") or raw_mapping.get("source_schema_name")
            ),
            "sourceObjectName": self._text(
                raw_mapping.get("sourceObjectName")
                or raw_mapping.get("sourceTableName")
                or raw_mapping.get("source_object_name")
            ),
            "targetSchemaName": self._text(
                raw_mapping.get("targetSchemaName") or raw_mapping.get("target_schema_name")
            ),
            "targetObjectName": self._text(
                raw_mapping.get("targetObjectName")
                or raw_mapping.get("targetTableName")
                or raw_mapping.get("target_object_name")
            ),
        }
        # ``objectKey`` 是手工向导用来稳定标识一条源到目标映射的低敏业务字段。
        # 它不是数据源 ID，也不具备执行权限，因此可以在基线合并后原样保留，方便前端
        # 在多表/多 schema 场景下把本次校验结果准确回填到对应行。
        object_key = self._text(raw_mapping.get("objectKey") or raw_mapping.get("object_key"))
        if object_key:
            mapping["objectKey"] = object_key
        if sync_mode != "CUSTOM_SQL_QUERY" and not mapping["sourceObjectName"]:
            self._append_unique(missing, f"{path}.sourceObjectName")
            self._append_unique(issues, "SOURCE_OBJECT_REQUIRED")
        if not mapping["targetObjectName"]:
            self._append_unique(missing, f"{path}.targetObjectName")
            self._append_unique(issues, "TARGET_OBJECT_REQUIRED")

        where_condition = self._text(
            raw_mapping.get("whereCondition") or raw_mapping.get("where") or raw_mapping.get("where_condition")
        )
        mapping["whereCondition"] = where_condition
        mapping["whereMode"] = "EXPRESSION" if where_condition else "NO_FILTER"

        source_object = self._find_metadata_object(
            source_metadata,
            mapping["sourceSchemaName"],
            mapping["sourceObjectName"],
            allow_single_object=sync_mode == "CUSTOM_SQL_QUERY" and not mapping["sourceObjectName"],
        )
        target_object = self._find_metadata_object(
            target_metadata,
            mapping["targetSchemaName"],
            mapping["targetObjectName"],
        )
        if source_object is None:
            self._append_unique(missing, "sourceTableMetadata")
            self._append_unique(issues, "SOURCE_TABLE_METADATA_REQUIRED")
        if target_object is None:
            self._append_unique(missing, "targetTableMetadata")
            self._append_unique(issues, "TARGET_TABLE_METADATA_REQUIRED")

        raw_fields = raw_mapping.get("fieldMappings") or raw_mapping.get("field_mappings")
        source_fields = self._metadata_fields(source_object)
        target_fields = self._metadata_fields(target_object)
        if not isinstance(raw_fields, (list, tuple)) or not raw_fields:
            inferred_fields = self._infer_same_name_fields(source_fields, target_fields)
            mapping["fieldMappings"] = inferred_fields
            mapping["fieldMappingMode"] = "DEFAULT_SAME_NAME_INFERENCE"
            if not inferred_fields:
                self._append_unique(missing, f"{path}.fieldMappings")
                self._append_unique(issues, "FIELD_MAPPING_REQUIRED")
            return mapping

        normalized_fields: list[dict[str, Any]] = []
        for field_index, raw_field in enumerate(raw_fields):
            field_path = f"{path}.fieldMappings[{field_index}]"
            if not isinstance(raw_field, Mapping):
                self._append_unique(missing, field_path)
                self._append_unique(issues, "FIELD_MAPPING_INVALID")
                continue
            source_field = self._text(raw_field.get("sourceField") or raw_field.get("source_field"))
            target_field = self._text(raw_field.get("targetField") or raw_field.get("target_field"))
            sync_enabled = raw_field.get("syncEnabled") is not False
            normalized_field = {
                "sourceField": source_field,
                "targetField": target_field,
                "syncEnabled": sync_enabled,
                "inferred": False,
                "inferenceSource": None,
            }
            # 这些字段属于 handoff 白名单，可能是用户在高级字段映射页已经审核的类型、
            # 主键、可空性或转换提示。旧实现只保留 source/target 两个名字，虽未改变
            # 实际列选择，却会让用户看到“自己确认的配置被模型删掉”。只复制标量，避免
            # 任意嵌套转换脚本穿过专业 Agent 边界。
            for optional_name in (
                "sourceType",
                "targetType",
                "nullable",
                "primaryKey",
                "typeCompatible",
                "transform",
            ):
                if optional_name in raw_field and isinstance(
                    raw_field.get(optional_name), (str, int, float, bool)
                ):
                    normalized_field[optional_name] = raw_field[optional_name]
            normalized_fields.append(normalized_field)
            if not source_field:
                self._append_unique(missing, f"{field_path}.sourceField")
            elif source_object is None or source_field.casefold() not in source_fields:
                self._append_unique(missing, f"{field_path}.sourceField")
                self._append_unique(issues, "SOURCE_FIELD_NOT_IN_METADATA")
            if not target_field:
                self._append_unique(missing, f"{field_path}.targetField")
            elif target_object is None or target_field.casefold() not in target_fields:
                self._append_unique(missing, f"{field_path}.targetField")
                self._append_unique(issues, "TARGET_FIELD_NOT_IN_METADATA")
        mapping["fieldMappings"] = normalized_fields
        mapping["fieldMappingMode"] = "EXPLICIT_MODEL_PROPOSAL"
        if not any(item["syncEnabled"] for item in normalized_fields):
            self._append_unique(missing, f"{path}.fieldMappings")
            self._append_unique(issues, "ENABLED_FIELD_MAPPING_REQUIRED")
        return mapping

    def _govern_model_requests(
        self,
        output: SyncPlanningModelOutput,
        delegated_read_tools: tuple[str, ...],
    ) -> _ModelRequestGovernance:
        """隔离非绑定越权建议，并拒绝污染配置正文的副作用字段。

        即便主 Agent 错误地把 ``sync.task.publish`` 放进 delegation，专业角色自身白名单仍会再次
        隔离它，形成“委派范围 AND 角色能力”的双重最小权限边界。隔离不会执行工具，也不会把
        建议透传给 Java；它只允许确定性的配置校验继续进行。这样模型自然地表达“任务最终需要
        创建并运行”时不会误伤配置草案，而模型尝试把副作用伪装成配置字段时仍会被拒绝。
        """

        delegated = set(delegated_read_tools)
        accepted_read_tools = tuple(
            tool_name
            for tool_name in output.requested_tool_names
            if tool_name in self._AGENT_TOOL_ALLOWLIST and tool_name in delegated
        )
        quarantined_tool_names = tuple(
            tool_name
            for tool_name in output.requested_tool_names
            if tool_name not in accepted_read_tools
        )
        # 不能只检查顶层键：模型可能把 `publish`、`taskId` 或 `toolCalls` 藏进
        # `dataSyncRequest`、对象映射或任意自定义嵌套对象中。扫描会区分“声称已经产生
        # 副作用”和“明确声明没有产生副作用”：前者拒绝整个 turn，后者只隔离并由后续
        # 确定性白名单重建丢弃。这样既不放宽执行权限，也避免模型返回
        # ``persisted: false`` 时把一份完整草案误判为越权。
        forbidden_field_count, active_side_effect_claim = self._scan_forbidden_output_fields(
            output.configuration
        )
        if active_side_effect_claim:
            return _ModelRequestGovernance(
                accepted_read_tools=accepted_read_tools,
                quarantined_tool_names=quarantined_tool_names,
                quarantined_actions=output.requested_actions,
                quarantined_configuration_field_count=forbidden_field_count,
                fatal_error_code="DATA_SYNC_SPECIALIST_SIDE_EFFECT_REJECTED",
            )
        return _ModelRequestGovernance(
            accepted_read_tools=accepted_read_tools,
            quarantined_tool_names=quarantined_tool_names,
            quarantined_actions=output.requested_actions,
            quarantined_configuration_field_count=forbidden_field_count,
        )

    @classmethod
    def _scan_forbidden_output_fields(
        cls,
        value: Any,
        *,
        depth: int = 0,
    ) -> tuple[int, bool]:
        """扫描模型配置中的控制字段，并判断它是否声称产生了真实副作用。

        Returns:
            二元组第一项是命中的静态控制字段数量，只用于低敏治理统计；第二项表示是否存在
            `publish: true`、非空 `toolCalls`、正数 `taskId` 等主动副作用声明。`false`、`null`、
            空集合及 `NOT_EXECUTED` 一类显式否定值只会被隔离，因为确定性配置重建不会复制
            这些字段。超过八层的未知结构仍直接标记为主动风险，保持原有 fail-closed 边界。
        """

        if depth > 8:
            return 0, True
        forbidden_count = 0
        active_claim = False
        if isinstance(value, Mapping):
            for key, child in value.items():
                if cls._normalized_key(key) in cls._FORBIDDEN_OUTPUT_KEYS:
                    forbidden_count += 1
                    active_claim = active_claim or cls._is_active_side_effect_claim(child)
                    # The complete value belongs to a forbidden control field.  Do not
                    # traverse it again and accidentally double count nested DTO members.
                    continue
                child_count, child_active = cls._scan_forbidden_output_fields(
                    child,
                    depth=depth + 1,
                )
                forbidden_count += child_count
                active_claim = active_claim or child_active
            return forbidden_count, active_claim
        if isinstance(value, (list, tuple)):
            for item in value:
                child_count, child_active = cls._scan_forbidden_output_fields(
                    item,
                    depth=depth + 1,
                )
                forbidden_count += child_count
                active_claim = active_claim or child_active
            return forbidden_count, active_claim
        return 0, False

    @staticmethod
    def _is_active_side_effect_claim(value: Any) -> bool:
        """判断控制字段值是否表达“已经或应当执行”而不是明确否定。

        该方法只负责分类，不会信任或执行模型值。即使结果为 ``False``，对应字段也会在
        `_validate_configuration` 的业务白名单重建中被完全丢弃；结果为 ``True`` 时则终止
        Specialist turn，防止任何任务 ID、执行回执或命令对象进入后续桥接层。
        """

        if value is None or value is False:
            return False
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return value != 0
        if isinstance(value, str):
            normalized = re.sub(r"[\s-]+", "_", value.strip()).upper()
            return normalized not in {
                "",
                "0",
                "FALSE",
                "NO",
                "NONE",
                "NULL",
                "NOT_REQUESTED",
                "NOT_EXECUTED",
                "NOT_PERSISTED",
                "NOT_PUBLISHED",
                "NOT_SAVED",
                "DRAFT_ONLY",
            }
        if isinstance(value, Mapping):
            return bool(value)
        if isinstance(value, (list, tuple, set)):
            return bool(value)
        # Unknown object types are not valid JSON configuration values. Treat them as
        # active risk rather than relying on truthiness that a custom object can alter.
        return True

    def _safe_invocation_summary(self, output: SyncPlanningModelOutput) -> dict[str, Any]:
        """生成可审计但不泄露 prompt、模型正文、工具参数或隐藏思维链的调用摘要。"""

        summary = {
            key: value
            for key, value in output.invocation_summary.items()
            if key in self._SAFE_INVOCATION_SUMMARY_KEYS and self._is_scalar(value)
        }
        summary.update(
            {
                "specialistModelInvoked": True,
                "independentInvocation": True,
                "requestedToolCount": len(output.requested_tool_names),
                "publicResponseSummary": self._bounded_text(output.public_summary, 300),
            }
        )
        return summary

    def _low_sensitive_context(self, context: Mapping[str, Any]) -> dict[str, Any]:
        """递归移除凭据类字段并限制集合体积，构造模型最小上下文。

        表名、字段名和数据源 ID 是完成同步规划必需的业务元数据，因此允许保留；密码、Token、连接串、
        私钥和样例行永远不应进入专业 Agent handoff。容量限制同时避免大元数据快照耗尽模型上下文。
        """

        def sanitize(value: Any, *, key: str = "", depth: int = 0) -> Any:
            """递归裁剪模型上下文，隐藏凭据、样例行并限制嵌套深度和集合大小。"""

            if depth > 6:
                return "[TRUNCATED]"
            normalized_key = self._normalized_key(key)
            if any(part in normalized_key for part in self._SECRET_KEY_PARTS):
                return "[REDACTED]"
            if any(part in normalized_key for part in self._SENSITIVE_CONTEXT_KEY_PARTS):
                return "[OMITTED_SENSITIVE_CONTEXT]"
            if normalized_key in {"rows", "samplerows", "sampledata", "records"}:
                return "[OMITTED_DATA_ROWS]"
            if isinstance(value, Mapping):
                return {
                    str(child_key): sanitize(child_value, key=str(child_key), depth=depth + 1)
                    for child_key, child_value in list(value.items())[:100]
                }
            if isinstance(value, (list, tuple)):
                return [sanitize(item, key=key, depth=depth + 1) for item in value[:100]]
            if isinstance(value, str):
                return self._bounded_text(value, 4_000)
            if self._is_scalar(value):
                return value
            return str(value)[:500]

        return sanitize(context)

    @classmethod
    def _metadata_from_context(cls, context: Mapping[str, Any], side: str) -> Any:
        """兼容常见 camelCase/snake_case 元数据摘要键，避免模型自行发明数据库事实。"""

        candidates = (
            f"{side}Metadata",
            f"{side}_metadata",
            f"{side}MetadataSummary",
            f"{side}_metadata_summary",
        )
        for key in candidates:
            if key in context:
                return context[key]
        nested = context.get("metadata")
        if isinstance(nested, Mapping):
            found = nested.get(side) or nested.get(f"{side}Metadata")
            if found is not None:
                return found
        dependency_output = cls._dependency_datasource_output(context)
        if dependency_output is not None:
            for key in candidates:
                if key in dependency_output:
                    return dependency_output[key]
            nested_dependency = dependency_output.get("metadata")
            if isinstance(nested_dependency, Mapping):
                return nested_dependency.get(side) or nested_dependency.get(f"{side}Metadata")
        return None

    @classmethod
    def _find_metadata_object(
        cls,
        metadata: Any,
        schema_name: str | None,
        object_name: str | None,
        *,
        allow_single_object: bool = False,
    ) -> Mapping[str, Any] | None:
        """按 schema + 表名从真实元数据中定位对象，比较时忽略大小写。"""

        objects = cls._metadata_objects(metadata)
        if allow_single_object and not object_name and len(objects) == 1:
            return objects[0]
        if not object_name:
            return None
        expected_object = object_name.casefold()
        expected_schema = schema_name.casefold() if schema_name else None
        for item in objects:
            actual_object = cls._text(
                item.get("tableName") or item.get("objectName") or item.get("name")
            )
            actual_schema = cls._text(item.get("schemaName") or item.get("schema"))
            if not actual_object or actual_object.casefold() != expected_object:
                continue
            if expected_schema is not None and (actual_schema or "").casefold() != expected_schema:
                continue
            return item
        return None

    @classmethod
    def _metadata_objects(cls, metadata: Any) -> list[Mapping[str, Any]]:
        """把单个摘要、多个摘要和 ``summary`` 包装统一展开为对象列表。"""

        if metadata is None:
            return []
        if isinstance(metadata, (list, tuple)):
            result: list[Mapping[str, Any]] = []
            for item in metadata:
                result.extend(cls._metadata_objects(item))
            return result
        if not isinstance(metadata, Mapping):
            return []
        summary = metadata.get("summary")
        if isinstance(summary, (Mapping, list, tuple)):
            return cls._metadata_objects(summary)
        raw_objects = metadata.get("objects") or metadata.get("tables")
        if isinstance(raw_objects, (list, tuple)):
            return [item for item in raw_objects if isinstance(item, Mapping)]
        if metadata.get("tableName") or metadata.get("objectName"):
            return [metadata]
        return []

    @classmethod
    def _metadata_fields(cls, metadata_object: Mapping[str, Any] | None) -> dict[str, str]:
        """返回 ``casefold 字段名 -> 原始字段名``，既便于校验又保留数据库真实大小写。"""

        if not isinstance(metadata_object, Mapping):
            return {}
        raw_columns = metadata_object.get("columns") or metadata_object.get("fields") or ()
        result: dict[str, str] = {}
        for raw_column in raw_columns if isinstance(raw_columns, (list, tuple)) else ():
            if not isinstance(raw_column, Mapping):
                continue
            name = cls._text(
                raw_column.get("columnName") or raw_column.get("fieldName") or raw_column.get("name")
            )
            if name:
                result.setdefault(name.casefold(), name)
        return result

    @staticmethod
    def _infer_same_name_fields(
        source_fields: Mapping[str, str],
        target_fields: Mapping[str, str],
    ) -> list[dict[str, Any]]:
        """仅基于两端真实元数据交集生成默认字段映射，并显式标记推断来源。"""

        return [
            {
                "sourceField": source_name,
                "targetField": target_fields[normalized_name],
                "syncEnabled": True,
                "inferred": True,
                "inferenceSource": "SAME_NAME_METADATA_DEFAULT",
            }
            for normalized_name, source_name in source_fields.items()
            if normalized_name in target_fields
        ]

    @classmethod
    def _coerce_model_output(cls, raw_output: Any) -> SyncPlanningModelOutput:
        """兼容协议对象和简单 Mapping 测试替身，同时保持统一治理入口。"""

        if isinstance(raw_output, SyncPlanningModelOutput):
            return raw_output
        if not isinstance(raw_output, Mapping):
            raise TypeError("SyncPlanningModel 必须返回 SyncPlanningModelOutput 或 Mapping")
        configuration = raw_output.get("configuration") or raw_output.get("draft")
        if not isinstance(configuration, Mapping):
            # 简单模型适配器可以直接返回任务配置；治理元字段会在副作用检查阶段被识别。
            configuration = raw_output
        return SyncPlanningModelOutput(
            configuration=configuration,
            public_summary=cls._text(raw_output.get("publicSummary") or raw_output.get("summary")) or "",
            invocation_summary=raw_output.get("invocationSummary")
            if isinstance(raw_output.get("invocationSummary"), Mapping)
            else {},
            requested_tool_names=tuple(
                str(item)
                for item in (raw_output.get("requestedToolNames") or ())
                if str(item or "").strip()
            ),
            requested_actions=tuple(
                str(item)
                for item in (raw_output.get("requestedActions") or raw_output.get("actions") or ())
                if str(item or "").strip()
            ),
        )

    @staticmethod
    def _configuration_payload(configuration: Mapping[str, Any]) -> Mapping[str, Any]:
        """解开模型可能使用的 ``dataSyncRequest`` 包装，但不接受任意深层对象。"""

        nested = configuration.get("dataSyncRequest")
        return nested if isinstance(nested, Mapping) else configuration

    @classmethod
    def _normalize_sync_mode(cls, value: Any) -> str | None:
        """归一化明确别名；未知模式不静默回退 FULL，防止任务类型被错误改变。"""

        normalized = cls._text(value)
        if not normalized:
            return None
        normalized = normalized.upper().replace("-", "_").replace(" ", "_")
        aliases = {
            "FULL_SYNC": "FULL",
            "SCHEDULED": "SCHEDULED_BATCH",
            "PERIODIC": "SCHEDULED_BATCH",
            "REALTIME": "CDC_STREAMING",
            "REAL_TIME": "CDC_STREAMING",
            "CDC": "CDC_STREAMING",
            "SQL": "CUSTOM_SQL_QUERY",
            "CUSTOM_SQL": "CUSTOM_SQL_QUERY",
        }
        normalized = aliases.get(normalized, normalized)
        return normalized if normalized in cls._SYNC_MODES else None

    @classmethod
    def _normalize_write_strategy(
        cls,
        value: Any,
        sync_mode: str | None,
    ) -> tuple[str | None, str | None, bool]:
        """同时输出后端兼容策略和用户语义，实时缺省统一采用 UPDATE/merge。"""

        normalized = (cls._text(value) or "").upper()
        if sync_mode == "CDC_STREAMING":
            if normalized and normalized not in {"UPDATE", "MERGE", "UPSERT"}:
                return None, None, False
            return "UPDATE", "MERGE", True
        if not normalized:
            return "INSERT", "INSERT", True
        if normalized in {"MERGE", "UPSERT", "UPDATE"}:
            return "UPDATE", "MERGE", True
        if normalized == "INSERT":
            return "INSERT", "INSERT", True
        return None, None, False

    @staticmethod
    def _is_read_only_sql(sql_text: str) -> bool:
        """执行轻量只读门禁；真正 SQL 解析和数据库方言检查仍由后续预检查完成。"""

        stripped = re.sub(r"^\s*(?:--[^\n]*\n|/\*.*?\*/\s*)*", "", sql_text, flags=re.DOTALL)
        if not re.match(r"^(SELECT|WITH)\b", stripped, flags=re.IGNORECASE):
            return False
        return re.search(
            r"\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|MERGE|CALL)\b",
            stripped,
            flags=re.IGNORECASE,
        ) is None

    def _failed_result(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        started_at: float,
        *,
        error_code: str,
        summary: str,
        model_invocation_summary: Mapping[str, Any] | None = None,
        structured_output: Mapping[str, Any] | None = None,
        tool_activities: tuple[SpecialistToolActivity, ...] = (),
        evidence_references: tuple[str, ...] | None = None,
    ) -> SpecialistTurnResult:
        """集中构造低敏失败结果，保证错误路径同样具备事件和耗时审计。"""

        duration_ms = self._duration_ms(started_at)
        self._emit(
            event_sink,
            request,
            action="SPECIALIST_FAILED",
            status=SpecialistTurnStatus.FAILED.value,
            summary=summary,
            attributes={
                "errorCode": error_code,
                "durationMs": duration_ms,
                **dict(structured_output or {}),
            },
        )

        return SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.FAILED,
            public_summary=summary,
            structured_output={
                **dict(structured_output or {}),
                "draftOnly": True,
                "persisted": False,
                "published": False,
                "executed": False,
            },
            evidence_references=evidence_references or request.evidence_references,
            tool_activities=tool_activities,
            model_invocation_summary=model_invocation_summary or {},
            error_code=error_code,
            duration_ms=duration_ms,
        )

    @staticmethod
    def _model_failure_summary(details: Mapping[str, str]) -> str:
        """把稳定模型失败码转换为用户能理解的摘要，不回显 Provider 原始错误。"""

        messages = {
            "MODEL_TIMEOUT": "模型响应超时",
            "MODEL_PROVIDER_ERROR": "模型服务返回错误",
            "MODEL_PROVIDER_NETWORK_ERROR": "模型服务网络连接失败",
            "MODEL_RESPONSE_INVALID_JSON": "模型返回内容不是可解析的 JSON",
            "MODEL_RESPONSE_CONTRACT_VIOLATION": "模型返回内容不符合同步规划契约",
            "MODEL_RESULT_UNAVAILABLE": "模型结果不可用",
            "MODEL_ADAPTER_ERROR": "模型适配器处理失败",
        }
        reason_code = str(details.get("modelFailureReasonCode") or "MODEL_ADAPTER_ERROR")
        human_message = messages.get(reason_code, "模型规划失败")
        return f"同步规划模型调用失败（{human_message}，错误码：{reason_code}），未生成或执行任何任务。请稍后重试。"

    @classmethod
    def _model_failure_details(cls, exc: Exception) -> Mapping[str, str]:
        """把规划模型异常收敛为可审计、可操作但不泄露原文的失败事实。

        `GovernedSpecialistJsonModel` 会在异常对象上附加 `reason_code` 和 `reason_source`。
        Data Sync 不能直接把这个对象或 `str(exc)` 放进 Specialist 结果，因为其中可能包含
        Provider URL、HTTP 响应片段甚至认证诊断。对于旧适配器或测试替身，则按 Python 异常
        类型退化到稳定的超时/适配器错误码，保证不同模型 Provider 的失败表现一致。
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
            "modelFailureReasonCode": str(reason_code),
            "modelFailureSource": str(reason_source),
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
        """发布前端可展示的低敏专业 Agent 动作；观察端故障不得中断业务规划。"""

        if event_sink is None:
            return
        event = {
            "eventType": "SPECIALIST_AGENT_ACTION",
            "agentId": self._agent_id,
            "agentRole": self.role.value,
            "turnId": request.turn_id,
            "action": action,
            "status": status,
            "publicSummary": summary,
            "attributes": {
                key: self._bounded_text(value, 600) if isinstance(value, str) else value
                for key, value in (attributes or {}).items()
                if self._is_scalar(value)
            },
            "payloadPolicy": "LOW_SENSITIVE_SPECIALIST_EVENT_ONLY",
        }
        try:
            event_sink(event)
        except Exception:
            # event_sink 是旁路可观测能力，浏览器断连或事件消费者异常不能改变规划结果。
            return

    @staticmethod
    def _has_project_scope(project_id: object) -> bool:
        """判断同步规划是否绑定具体项目，拒绝租户级通配范围。"""

        normalized = str(project_id or "").strip()
        return bool(normalized) and normalized.casefold() not in {"*", "all", "tenant", "tenant_scope"}

    @staticmethod
    def _positive_id(value: Any) -> int | None:
        """接受整数或数字字符串的数据源 ID，拒绝 bool、零值、负数和任意文本。"""

        if isinstance(value, bool):
            return None
        try:
            parsed = int(str(value).strip())
        except (TypeError, ValueError):
            return None
        return parsed if parsed > 0 else None

    @staticmethod
    def _safe_connector_type(value: Any) -> str | None:
        """只接受结构化连接器标识，拒绝把数据源名称或自然语言当成连接器类型。"""

        normalized = str(value or "").strip().upper()
        if not normalized or not re.fullmatch(r"[A-Z0-9][A-Z0-9_.:-]{0,63}", normalized):
            return None
        return normalized

    @staticmethod
    def _require(value: Any, field_name: str, missing: list[str]) -> None:
        """把缺失字段去重加入用户补参列表。"""

        if value is None or value == "":
            DataSyncSpecialistAgent._append_unique(missing, field_name)

    @staticmethod
    def _append_unique(items: list[str], value: str) -> None:
        """保持缺项和问题码的首次发现顺序，便于前端稳定展示。"""

        if value not in items:
            items.append(value)

    @staticmethod
    def _has_value(value: Any) -> bool:
        """判断调度等复合配置是否包含有效内容。"""

        if value is None:
            return False
        if isinstance(value, str):
            return bool(value.strip())
        if isinstance(value, Mapping):
            return bool(value)
        if isinstance(value, (list, tuple)):
            return bool(value)
        return True

    @staticmethod
    def _text(value: Any) -> str | None:
        """把标量规范为去除首尾空格的文本；空值统一返回 None。"""

        if value is None:
            return None
        normalized = str(value).strip()
        return normalized or None

    @staticmethod
    def _bounded_text(value: Any, limit: int) -> str:
        """限制公开文本长度，避免模型或事件输出无限放大上下文。"""

        normalized = str(value or "").strip()
        return normalized[:limit]

    @staticmethod
    def _normalized_key(value: Any) -> str:
        """移除键名分隔符并转小写，用于跨命名风格执行安全检查。"""

        return re.sub(r"[^a-z0-9]", "", str(value or "").lower())

    @staticmethod
    def _is_scalar(value: Any) -> bool:
        """事件和调用摘要只允许低风险标量，拒绝嵌套原始响应。"""

        return value is None or isinstance(value, (str, int, float, bool))

    @staticmethod
    def _duration_ms(started_at: float) -> int:
        """以单调时钟计算耗时，避免系统时间调整导致负数。"""

        return max(0, int((time.perf_counter() - started_at) * 1_000))


def _unique_text(values: tuple[str, ...]) -> tuple[str, ...]:
    """规范化协议中的文本元组，并保持原始顺序去重。"""

    return tuple(dict.fromkeys(str(value).strip() for value in values if str(value or "").strip()))


__all__ = [
    "DataSyncSpecialistAgent",
    "SyncMetadataDiscoveryError",
    "SyncMetadataDiscoveryRequest",
    "SyncMetadataDiscoveryResult",
    "SyncMetadataDiscoveryTool",
    "SyncPlanningModel",
    "SyncPlanningModelInput",
    "SyncPlanningModelOutput",
]
