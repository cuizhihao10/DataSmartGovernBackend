"""A2A Task 控制面合同的 Python 领域模型。

本文件的目标不是实现完整 A2A Server，而是把 Java Agent Runtime 5.28-5.30 已经沉淀出的只读
控制面合同转换成 Python Runtime 可以稳定消费的领域对象。这样 Python 侧后续做 Master Agent
会话编排、远程 Agent 委派、任务恢复或工具预检时，不需要直接依赖 Java DTO 字段名，也不会把
外部协议的细节散落在各个 service 中。

安全边界说明：
- 这些模型只保存 task id、context id、状态、内部阶段、序号、原因码和 artifact 引用；
- 不保存用户消息正文、prompt、模型输出、工具参数、SQL、样本数据、artifact 正文、凭证或内部端点；
- `to_summary()` 显式列字段，避免未来新增内部字段时被 `asdict` 意外暴露到 API、事件或日志中。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class A2aTaskState(str, Enum):
    """A2A Task 对外状态。

    Java 控制面当前使用 `TASK_STATE_*` 形式表达状态，外部 A2A 文档也常用 submitted、working、
    input-required、completed 等语义。这里统一收敛为一个枚举，避免 Python 编排器到处写字符串比较。

    `TASK_STATE_UNSPECIFIED` 被刻意保留为诊断态：真实商业化系统遇到未知状态时不能“猜测继续执行”，
    应该 fail-closed，让控制面或运维先确认协议版本是否漂移。
    """

    TASK_STATE_UNSPECIFIED = "TASK_STATE_UNSPECIFIED"
    TASK_STATE_SUBMITTED = "TASK_STATE_SUBMITTED"
    TASK_STATE_WORKING = "TASK_STATE_WORKING"
    TASK_STATE_INPUT_REQUIRED = "TASK_STATE_INPUT_REQUIRED"
    TASK_STATE_AUTH_REQUIRED = "TASK_STATE_AUTH_REQUIRED"
    TASK_STATE_COMPLETED = "TASK_STATE_COMPLETED"
    TASK_STATE_FAILED = "TASK_STATE_FAILED"
    TASK_STATE_CANCELED = "TASK_STATE_CANCELED"
    TASK_STATE_REJECTED = "TASK_STATE_REJECTED"

    @classmethod
    def from_value(cls, value: object) -> "A2aTaskState":
        """把 Java DTO、A2A 文本状态或缺省值归一化为枚举。

        支持多种输入形态的原因是：Java preview 返回 `TASK_STATE_INPUT_REQUIRED`，而真实 A2A SDK 或
        网关适配层未来可能传入 `input-required`、`input_required` 或 `INPUT_REQUIRED`。把归一化逻辑
        放在领域模型里，可以让 service 层专注业务映射。
        """

        if value is None:
            return cls.TASK_STATE_UNSPECIFIED
        normalized = str(value).strip().upper().replace("-", "_")
        if not normalized:
            return cls.TASK_STATE_UNSPECIFIED
        if not normalized.startswith("TASK_STATE_"):
            normalized = f"TASK_STATE_{normalized}"
        return cls.__members__.get(normalized, cls.TASK_STATE_UNSPECIFIED)

    @property
    def terminal(self) -> bool:
        """当前状态是否为终态。

        终态任务不能被 Python Runtime 重新推进到工具执行。需要重跑时应创建新 task、生成补偿工单，
        或由 Java 控制面明确发起管理员补偿。
        """

        return self in {
            A2aTaskState.TASK_STATE_COMPLETED,
            A2aTaskState.TASK_STATE_FAILED,
            A2aTaskState.TASK_STATE_CANCELED,
            A2aTaskState.TASK_STATE_REJECTED,
        }

    @property
    def waiting_for_external_input(self) -> bool:
        """当前状态是否需要用户、权限系统或外部控制面继续提供输入。"""

        return self in {
            A2aTaskState.TASK_STATE_INPUT_REQUIRED,
            A2aTaskState.TASK_STATE_AUTH_REQUIRED,
        }


class AgentTaskPlanningMode(str, Enum):
    """Python Runtime 对 A2A task 的规划模式。

    该枚举回答“Master Agent 下一步应该怎么对待这个 task”：
    - `PRECHECK_REQUIRED`：只允许做权限、幂等、配额、租户边界等执行前检查；
    - `WORKER_PLANNING_ALLOWED`：可以规划 worker pre-check 或查询执行进度，但仍不直接执行工具；
    - `WAIT_FOR_USER_INPUT`：必须等待用户补充信息，模型不能脑补参数；
    - `WAIT_FOR_AUTHORIZATION`：必须等待授权、审批或权限包更新，凭证不能放进 A2A 消息正文；
    - `TERMINAL_NO_EXECUTION`：任务已终结，只能展示结果/失败/取消事实，不允许继续推进；
    - `REJECTED_OR_DIAGNOSTIC`：未知或不可信状态，按 fail-closed 处理。
    """

    PRECHECK_REQUIRED = "PRECHECK_REQUIRED"
    WORKER_PLANNING_ALLOWED = "WORKER_PLANNING_ALLOWED"
    WAIT_FOR_USER_INPUT = "WAIT_FOR_USER_INPUT"
    WAIT_FOR_AUTHORIZATION = "WAIT_FOR_AUTHORIZATION"
    TERMINAL_NO_EXECUTION = "TERMINAL_NO_EXECUTION"
    REJECTED_OR_DIAGNOSTIC = "REJECTED_OR_DIAGNOSTIC"


class AgentTaskPlanningStatus(str, Enum):
    """规划决策的产品状态。

    `mode` 更偏执行语义，`status` 更偏前端和控制面展示语义。拆开后，未来多个 mode 可以共享一个
    展示状态，而不会影响编排器的安全判断。
    """

    READY_FOR_PRECHECK = "READY_FOR_PRECHECK"
    READY_FOR_WORKER_PLANNING = "READY_FOR_WORKER_PLANNING"
    WAITING_FOR_USER = "WAITING_FOR_USER"
    WAITING_FOR_CONTROL_PLANE = "WAITING_FOR_CONTROL_PLANE"
    TERMINAL = "TERMINAL"
    BLOCKED = "BLOCKED"


class AgentTaskSuggestedAction(str, Enum):
    """Python Runtime 给上层编排或 UI 的下一步动作建议。"""

    REQUEST_PERMISSION_PRECHECK = "REQUEST_PERMISSION_PRECHECK"
    VALIDATE_IDEMPOTENCY_KEY = "VALIDATE_IDEMPOTENCY_KEY"
    PLAN_WORKER_PRECHECK = "PLAN_WORKER_PRECHECK"
    QUERY_TASK_HISTORY = "QUERY_TASK_HISTORY"
    ASK_USER_FOR_INPUT = "ASK_USER_FOR_INPUT"
    REQUEST_AUTHORIZATION = "REQUEST_AUTHORIZATION"
    SURFACE_ARTIFACT_REFERENCE = "SURFACE_ARTIFACT_REFERENCE"
    STOP_AUTOMATIC_EXECUTION = "STOP_AUTOMATIC_EXECUTION"
    OPEN_DIAGNOSTIC_REVIEW = "OPEN_DIAGNOSTIC_REVIEW"


@dataclass(frozen=True)
class A2aTaskHistoryEvent:
    """A2A task 历史事件的低敏事实。

    事件只保留排序、状态、阶段、事件类型和 artifact 引用。`lowSensitiveSummary` 这类自由文本即使
    在 Java preview 中存在，Python 适配层也默认不保留，因为自由文本最容易在后续真实接入时混入
    用户消息、工具结果或模型输出。
    """

    sequence: int
    event_type: str
    a2a_state: A2aTaskState = A2aTaskState.TASK_STATE_UNSPECIFIED
    internal_phase: str = ""
    stream_event_kind: str = ""
    terminal: bool = False
    reason_code: str = ""
    artifact_ref: str = ""

    def to_summary(self) -> dict[str, Any]:
        """输出可进入 API 或事件 attributes 的低敏摘要。"""

        return {
            "sequence": self.sequence,
            "eventType": self.event_type,
            "a2aState": self.a2a_state.value,
            "internalPhase": self.internal_phase,
            "streamEventKind": self.stream_event_kind,
            "terminal": self.terminal,
            "reasonCode": self.reason_code,
            "artifactRef": self.artifact_ref,
        }


@dataclass(frozen=True)
class A2aTaskArtifactReference:
    """A2A artifact 的 metadata-only 引用。

    Artifact 可能代表质量报告、同步诊断、脱敏结果或模型生成的治理建议。正文必须由受控 artifact
    服务二次鉴权后读取；task planning 只能看到引用和类型，不能缓存正文。
    """

    artifact_ref: str
    artifact_type: str = ""
    available: bool = False
    metadata_only: bool = True
    linked_event_sequences: tuple[int, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """输出 artifact 引用摘要，不包含正文、路径或下载地址。"""

        return {
            "artifactRef": self.artifact_ref,
            "artifactType": self.artifact_type,
            "available": self.available,
            "metadataOnly": self.metadata_only,
            "linkedEventSequences": self.linked_event_sequences,
        }


@dataclass(frozen=True)
class A2aTaskControlPlaneSnapshot:
    """Java A2A task 控制面合同在 Python 内部的低敏快照。

    这个对象是 adapter 的输入归一化结果：它把 Java preview 或未来真实查询返回的多层字段收敛成
    Python Runtime 能理解的一组事实。后续如果 Java DTO 改名，只需要修改 adapter，不需要修改
    Master Agent、调度器或测试中的业务判断。
    """

    task_public_id: str = ""
    context_public_id: str = ""
    state: A2aTaskState = A2aTaskState.TASK_STATE_UNSPECIFIED
    internal_phase: str = ""
    sequence: int = 0
    terminal: bool = False
    interrupted: bool = False
    reason_code: str = ""
    allowed_client_operations: tuple[str, ...] = ()
    governance_summary: tuple[str, ...] = ()
    history_events: tuple[A2aTaskHistoryEvent, ...] = ()
    artifact_references: tuple[A2aTaskArtifactReference, ...] = ()
    source_schema_version: str = ""
    source_scenario: str = ""
    preview_only: bool = True
    task_endpoint_enabled: bool = False
    sensitive_field_ignored_count: int = 0
    payload_policy: str = "SUMMARY_ONLY_LOW_SENSITIVE_CONTROL_PLANE_FIELDS"

    def to_summary(self) -> dict[str, Any]:
        """输出低敏快照摘要。

        注意这里不输出被忽略的敏感字段名称。字段名本身虽然通常不是秘密，但如果事件或 API 中不断出现
        具体敏感字段名，会让消费者误以为这些字段可被协议支持，也会增加审计误读成本。
        """

        return {
            "taskPublicId": self.task_public_id,
            "contextPublicId": self.context_public_id,
            "a2aState": self.state.value,
            "internalPhase": self.internal_phase,
            "sequence": self.sequence,
            "terminal": self.terminal,
            "interrupted": self.interrupted,
            "reasonCode": self.reason_code,
            "allowedClientOperations": self.allowed_client_operations,
            "governanceSummary": self.governance_summary,
            "historyEventCount": len(self.history_events),
            "artifactReferenceCount": len(self.artifact_references),
            "artifactReferences": tuple(ref.to_summary() for ref in self.artifact_references),
            "sourceSchemaVersion": self.source_schema_version,
            "sourceScenario": self.source_scenario,
            "previewOnly": self.preview_only,
            "taskEndpointEnabled": self.task_endpoint_enabled,
            "sensitiveFieldIgnoredCount": self.sensitive_field_ignored_count,
            "payloadPolicy": self.payload_policy,
        }


@dataclass(frozen=True)
class AgentTaskPlanningDecision:
    """Python Runtime 基于 A2A task 快照生成的规划决策。

    该决策服务“下一步要不要让 Agent 继续推进”。它不是执行命令，也不是审批结果。真正执行工具、
    创建任务、写 outbox、取消任务或读取 artifact 正文，仍必须回到 Java 控制面和受控 worker。
    """

    mode: AgentTaskPlanningMode
    status: AgentTaskPlanningStatus
    executable: bool = False
    should_start_worker: bool = False
    should_wait_for_human: bool = False
    snapshot: A2aTaskControlPlaneSnapshot = field(default_factory=A2aTaskControlPlaneSnapshot)
    suggested_actions: tuple[AgentTaskSuggestedAction, ...] = ()
    guardrails: tuple[str, ...] = ()
    decision_reason: str = ""

    def to_summary(self) -> dict[str, Any]:
        """输出可展示、可测试、可后续事件化的低敏决策摘要。"""

        return {
            "mode": self.mode.value,
            "status": self.status.value,
            "executable": self.executable,
            "shouldStartWorker": self.should_start_worker,
            "shouldWaitForHuman": self.should_wait_for_human,
            "decisionReason": self.decision_reason,
            "suggestedActions": tuple(action.value for action in self.suggested_actions),
            "guardrails": self.guardrails,
            "snapshot": self.snapshot.to_summary(),
        }
