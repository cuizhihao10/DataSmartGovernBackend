"""真实专业 Agent turn 的统一输入输出合同。

现有多 Agent 协作图主要描述“哪些角色应当参与”，本模块进一步定义“一个专业 Agent 真正运行时能看见
什么、必须返回什么”。合同刻意不承载完整 prompt、数据库凭据、SQL 正文或样本数据；专业 Agent 只能
在主编排器显式委派的用户/租户/项目范围和工具白名单内工作。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Any, Callable, Mapping, Protocol

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole


class SpecialistTurnStatus(str, Enum):
    """专业 Agent 单次 turn 的终态。

    `WAITING_FOR_INPUT` 表示专业 Agent 已经完成当前可完成的分析，但缺少用户选择或业务参数；它不是
    技术失败。`FAILED` 才表示模型、工具、超时或合同校验导致本次委派无法给出有效结果。
    """

    COMPLETED = "COMPLETED"
    WAITING_FOR_INPUT = "WAITING_FOR_INPUT"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


@dataclass(frozen=True)
class SpecialistAuditScope:
    """一次专业模型调用使用的不可变低敏审计范围。

    这个类型故意放在 specialist 合同层，而不是放在某个模型适配器里。原因是模型输入必须由
    当前 ``SpecialistTurnRequest`` 现场构造，不能依赖跨请求单例中的可变上下文，也不能让适配器
    根据 ``objective`` 猜测调用人或项目。五个字段会被适配器映射到 ``ModelGatewayRequestContext``，
    但不会进入模型的 ``public_payload``，因此它既能参与租户/项目隔离、缓存和审计，又不会把身份
    元数据暴露给模型内容层。

    ``trace_id`` 使用当前 turn 的唯一标识生成。这样同一会话的不同 turn 仍然拥有不同的模型网关
    链路标识，跨租户连续调用时也不会因为复用旧的 provider context 而串到上一个请求。
    """

    # 模型预算、缓存隔离和审计归属的第一层边界。
    tenant_id: str
    # 数据治理资源的最小项目边界；模型调用不允许降级成“没有项目”的范围。
    project_id: str
    # 代表当前用户或受控服务身份的业务主体，不能从自然语言目标推断。
    actor_id: str
    # 绑定当前持久会话，避免不同会话复用临时模型范围。
    session_id: str
    # 绑定当前 turn 的链路标识，用于模型网关和运行审计关联。
    trace_id: str

    def __post_init__(self) -> None:
        """在模型输入创建前拒绝任何缺失或只有空白的审计字段。"""

        required = {
            "tenant_id": self.tenant_id,
            "project_id": self.project_id,
            "actor_id": self.actor_id,
            "session_id": self.session_id,
            "trace_id": self.trace_id,
        }
        missing = tuple(name for name, value in required.items() if not str(value or "").strip())
        if missing:
            raise ValueError(f"专业模型审计范围缺少字段：{', '.join(missing)}")
        for name, value in required.items():
            object.__setattr__(self, name, str(value).strip())


@dataclass(frozen=True)
class SpecialistDelegationScope:
    """主 Agent 授予专业 Agent 的最小权限范围。

    该对象表达双主体审计中的委派事实：业务资源仍归当前用户身份所有，`delegation_id` 仅证明主 Agent
    在本次会话、本次运行中允许某个专业 Agent 使用指定工具。它绝不能替代 Gateway 或下游服务的
    RBAC/数据范围校验。
    """

    tenant_id: str
    application_id: str | None
    project_id: str | None
    actor_id: str
    delegation_id: str
    allowed_tool_names: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        """拒绝缺失关键审计主体的委派，避免匿名专业 Agent 进入执行链路。"""

        required_values = {
            "tenant_id": self.tenant_id,
            "actor_id": self.actor_id,
            "delegation_id": self.delegation_id,
        }
        missing = tuple(name for name, value in required_values.items() if not str(value or "").strip())
        if missing:
            raise ValueError(f"专业 Agent 委派范围缺少必填字段：{', '.join(missing)}")

        normalized_tools = tuple(
            dict.fromkeys(str(tool_name).strip() for tool_name in self.allowed_tool_names if str(tool_name).strip())
        )
        object.__setattr__(self, "allowed_tool_names", normalized_tools)


@dataclass(frozen=True)
class SpecialistTurnBudget:
    """限制单个专业 Agent turn 的资源消耗和循环上限。"""

    timeout_ms: int = 30_000
    max_tool_calls: int = 4
    max_model_invocations: int = 2
    max_output_tokens: int = 1_500

    def __post_init__(self) -> None:
        """在进入模型或工具层之前完成预算校验，使错误可预测、可审计。"""

        if self.timeout_ms < 1_000:
            raise ValueError("专业 Agent timeout_ms 不能小于 1000")
        if not 0 <= self.max_tool_calls <= 32:
            raise ValueError("专业 Agent max_tool_calls 必须位于 0 到 32 之间")
        if not 1 <= self.max_model_invocations <= 8:
            raise ValueError("专业 Agent max_model_invocations 必须位于 1 到 8 之间")
        if not 128 <= self.max_output_tokens <= 32_768:
            raise ValueError("专业 Agent max_output_tokens 必须位于 128 到 32768 之间")


@dataclass(frozen=True)
class SpecialistTurnRequest:
    """主 Agent 发送给专业 Agent 的受控工作包。

    `objective` 是本次专业职责的目标，不应直接复制整段系统 prompt；`context_summary` 只能包含已经过
    主 Agent 或 Java 控制面低敏处理的结构化事实。需要读取正文时，专业 Agent 必须使用被授权工具，
    不能把正文塞进 handoff 合同绕过审计。
    """

    turn_id: str
    session_id: str
    run_id: str
    role: AgentSessionRole
    objective: str
    scope: SpecialistDelegationScope
    budget: SpecialistTurnBudget = field(default_factory=SpecialistTurnBudget)
    context_summary: Mapping[str, Any] = field(default_factory=dict)
    evidence_references: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        """规范化不可变输入，防止调用方在专业 Agent 运行中修改上下文。"""

        required_values = {
            "turn_id": self.turn_id,
            "session_id": self.session_id,
            "run_id": self.run_id,
            "objective": self.objective,
        }
        missing = tuple(name for name, value in required_values.items() if not str(value or "").strip())
        if missing:
            raise ValueError(f"专业 Agent turn 缺少必填字段：{', '.join(missing)}")
        if self.role == AgentSessionRole.MASTER_ORCHESTRATOR:
            raise ValueError("主编排 Agent 不能通过专业 Agent turn 合同委派给自身")

        object.__setattr__(self, "context_summary", MappingProxyType(dict(self.context_summary)))
        object.__setattr__(
            self,
            "evidence_references",
            tuple(dict.fromkeys(str(item).strip() for item in self.evidence_references if str(item).strip())),
        )

    @property
    def audit_scope(self) -> SpecialistAuditScope:
        """根据当前 turn 即时生成模型调用范围，不读取全局或跨请求上下文。

        ``SpecialistDelegationScope`` 对通用专业工具允许 ``project_id`` 为空，但三个真实模型解释器
        必须拥有项目边界。因此这里会把缺失项目转换为明确异常，由调用方或模型适配器 fail-closed，
        而不是把请求放入默认项目或租户范围。``turn_id`` 作为 traceId，使范围和当前执行轮次一一对应。
        """

        return SpecialistAuditScope(
            tenant_id=self.scope.tenant_id,
            project_id=self.scope.project_id,
            actor_id=self.scope.actor_id,
            session_id=self.session_id,
            trace_id=self.turn_id,
        )


@dataclass(frozen=True)
class SpecialistToolActivity:
    """可向主 Agent 和前端公开的单次工具活动摘要。"""

    tool_name: str
    status: str
    public_summary: str
    evidence_reference: str | None = None
    duration_ms: int = 0

    def to_summary(self) -> dict[str, Any]:
        """生成稳定的低敏 API 视图，不包含工具参数和工具原始输出。"""

        return {
            "toolName": self.tool_name,
            "status": self.status,
            "publicSummary": self.public_summary,
            "evidenceReference": self.evidence_reference,
            "durationMs": max(0, self.duration_ms),
        }


@dataclass(frozen=True)
class SpecialistTurnResult:
    """专业 Agent 回交给主 Agent 的结果信封。"""

    agent_id: str
    role: AgentSessionRole
    turn_id: str
    status: SpecialistTurnStatus
    public_summary: str
    structured_output: Mapping[str, Any] = field(default_factory=dict)
    evidence_references: tuple[str, ...] = ()
    tool_activities: tuple[SpecialistToolActivity, ...] = ()
    model_invocation_summary: Mapping[str, Any] = field(default_factory=dict)
    required_input_fields: tuple[str, ...] = ()
    error_code: str | None = None
    duration_ms: int = 0
    # 该绑定由 SpecialistAgentCoordinator 在专业 Agent 返回后附加，而不是由模型写入
    # structured_output。Bridge 用它区分“专业建议来自哪个 session/run/delegation”和
    # “Java 工具反馈属于哪个控制面 Run”，避免把业务 executionId 或工具 Run 误当成
    # 专业 Agent 的审批身份。该字段只在进程内治理边界使用，不进入公开 to_summary。
    delegated_scope_binding: Mapping[str, Any] = field(default_factory=dict, repr=False)
    # 某些专业 Agent 会先通过受保护的 Java 只读接口取得事实，再把建议交给主 Agent Bridge。
    # 这份绑定只保存控制面来源、身份范围和稳定资源定位，不保存响应正文；它与
    # ``delegated_scope_binding`` 一样不进入公开摘要。Bridge 可以用它创建一个真正具有
    # agent-runtime auditId/runId 的前置只读 ToolPlan，但绝不能把它当作写操作审批事实。
    control_plane_fact_binding: Mapping[str, Any] = field(default_factory=dict, repr=False)

    def __post_init__(self) -> None:
        """冻结结果并校验角色/状态，避免把不完整结果误标为成功。"""

        if not str(self.agent_id or "").strip() or not str(self.turn_id or "").strip():
            raise ValueError("专业 Agent 结果必须包含 agent_id 和 turn_id")
        if self.role == AgentSessionRole.MASTER_ORCHESTRATOR:
            raise ValueError("专业 Agent 结果不能声明为 MASTER_ORCHESTRATOR")
        if self.status == SpecialistTurnStatus.FAILED and not str(self.error_code or "").strip():
            raise ValueError("失败的专业 Agent 结果必须提供低敏 error_code")

        object.__setattr__(self, "structured_output", MappingProxyType(dict(self.structured_output)))
        object.__setattr__(self, "model_invocation_summary", MappingProxyType(dict(self.model_invocation_summary)))
        object.__setattr__(
            self,
            "delegated_scope_binding",
            MappingProxyType(dict(self.delegated_scope_binding)),
        )
        object.__setattr__(
            self,
            "control_plane_fact_binding",
            MappingProxyType(dict(self.control_plane_fact_binding)),
        )
        object.__setattr__(
            self,
            "evidence_references",
            tuple(dict.fromkeys(str(item).strip() for item in self.evidence_references if str(item).strip())),
        )
        object.__setattr__(
            self,
            "required_input_fields",
            tuple(dict.fromkeys(str(item).strip() for item in self.required_input_fields if str(item).strip())),
        )

    def to_summary(self) -> dict[str, Any]:
        """输出供主 Agent 仲裁、运行事件和前端过程展示共同消费的低敏摘要。"""

        return {
            "agentId": self.agent_id,
            "agentRole": self.role.value,
            "turnId": self.turn_id,
            "status": self.status.value,
            "publicSummary": self.public_summary,
            "structuredOutput": dict(self.structured_output),
            "evidenceReferences": self.evidence_references,
            "toolActivities": tuple(activity.to_summary() for activity in self.tool_activities),
            "modelInvocationSummary": dict(self.model_invocation_summary),
            "requiredInputFields": self.required_input_fields,
            "errorCode": self.error_code,
            "durationMs": max(0, self.duration_ms),
            "payloadPolicy": "LOW_SENSITIVE_SPECIALIST_RESULT_ONLY",
        }


SpecialistEventSink = Callable[[Mapping[str, Any]], None]


class SpecialistAgent(Protocol):
    """所有真实专业 Agent 必须实现的最小运行协议。"""

    @property
    def role(self) -> AgentSessionRole:
        """返回该实例唯一负责的专业角色。"""

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None = None,
    ) -> SpecialistTurnResult:
        """在受控委派范围内执行一次专业 turn，并返回低敏结构化结果。"""
