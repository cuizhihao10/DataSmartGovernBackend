"""专业 Agent 结果到主 Agent ``ToolPlan`` 的受治理桥接层。

本模块解决一个很容易被忽略的信任边界问题：专业 Agent 能够读取元数据、调用
独立模型并生成结构化建议，但它的输出仍然属于“不可信模型建议”，不能因为输出已经
长得像同步任务配置，就直接送入 Java 执行链路。

桥接层因此只做四件事：

1. 检查专业 Agent 结果是否完整、是否仍然只是草案，以及是否夹带了保存/发布/执行
   等越权声明；
2. 要求真实控制面提供源端和目标端元数据成功事实，并把这些事实交给现有的
   ``AgentFollowUpToolPlanner`` 做工具可见性、schema、权限、预算、重复和元数据状态
   校验；
3. 把通过治理的 ``sync.task.draft.save`` 交给现有平台生命周期扩展，生成预检查、发布、
   运行和状态观察等后续节点；
4. 只返回可交给 ``AgentDurableModelToolLoopRunner`` 的“下一批 ToolPlan”，绝不在这里
   调用 HTTP、数据库、MCP、worker 或任何业务执行器。

RECOVERY_AGENT 的高风险分支还提供一个独立的 Java 交接合同。该合同表达“等待用户审批”
或“等待 Java 根据受控事实重新水合 ToolPlan”，不会把 ``controlledToolExecutor`` 变成
用户补充字段，也不会让 Python 因为收到了批准字段就直接执行恢复动作。
"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, replace
from enum import Enum
from types import MappingProxyType
from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ModelToolCall,
    ToolDefinition,
    ToolPlan,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import (
    AgentFollowUpToolPlanner,
    AgentFollowUpToolPlanningResult,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnResult
from datasmart_ai_runtime.services.agent_execution.duplicate_task_name_recovery import (
    DuplicateTaskNameRecoveryPlanner,
)
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


SPECIALIST_TOOLPLAN_BRIDGE_SCHEMA_VERSION = "datasmart.specialist-toolplan-bridge.v1"
RECOVERY_JAVA_HANDOFF_SCHEMA_VERSION = "datasmart.recovery-java-toolplan-handoff.v1"

# Java feedback client generates this URI after it has queried a durable tool
# result.  The bridge only reads the session locator; it never dereferences the
# URI itself and never accepts a model-authored URL as an identity source.
_JAVA_OUTPUT_SESSION_PATTERN = re.compile(
    r"^agent-runtime://sessions/(?P<session>ags_[A-Za-z0-9_-]{8,120})/"
    r"runs/(?P<run>agr_[A-Za-z0-9_-]{8,120})/tool-executions/"
    r"(?P<audit>atea_[A-Za-z0-9_-]{8,160})/(?:result|event-replay)$"
)


# RecoveryAction 是模型输出，不能直接把它的 ``toolName`` 当作执行权限。
# 下面的映射表只允许少量已经在平台注册、并且与业务语义一一对应的工具；未知动作没有
# “通用 recovery.execute” 兜底，防止新增模型动作意外获得写权限。
RECOVERY_ACTION_TOOL_MAP: dict[str, str] = {
    "RETRY_FAILED_OBJECTS": "sync.execution.failed-objects.retry",
    "RETRY_FAILED_OBJECT": "sync.execution.failed-objects.retry",
    "RERUN_FAILED_OBJECTS": "sync.execution.failed-objects.retry",
    # Quarantine 的 apply 是高风险写动作。actionType 必须显式绑定到唯一的
    # 受治理工具，不能因为模型返回了一个看似相近的动作名就落入通用执行入口。
    "APPLY_QUARANTINE": "sync.dirty-record.quarantine.apply",
    "SYNC_DIRTY_RECORD_QUARANTINE_APPLY": "sync.dirty-record.quarantine.apply",
    "PREVIEW_QUARANTINE": "sync.dirty-record.quarantine.preview",
    "QUARANTINE_PREVIEW": "sync.dirty-record.quarantine.preview",
    "DIRTY_RECORD_QUARANTINE_PREVIEW": "sync.dirty-record.quarantine.preview",
    "SYNC_DIRTY_RECORD_QUARANTINE_PREVIEW": "sync.dirty-record.quarantine.preview",
    "REPLAY_DIRTY_RECORDS": "sync.dirty-record.replay",
    "DIRTY_RECORD_REPLAY": "sync.dirty-record.replay",
    "REPLAY_FAILED_RECORDS": "sync.dirty-record.replay",
    # 注册表中的 schema repair 写动作允许的真实 action 是
    # ALTER_TARGET_SCHEMA；不能臆造 APPLY_SCHEMA_REPAIR 作为权限语义。
    "ALTER_TARGET_SCHEMA": "datasource.schema.repair.apply",
    "DATASOURCE_SCHEMA_REPAIR_APPLY": "datasource.schema.repair.apply",
    "PREVIEW_SCHEMA_REPAIR": "datasource.schema.repair.preview",
    "SCHEMA_REPAIR_PREVIEW": "datasource.schema.repair.preview",
    "REPAIR_SCHEMA_PREVIEW": "datasource.schema.repair.preview",
    "DATASOURCE_SCHEMA_REPAIR_PREVIEW": "datasource.schema.repair.preview",
    "PREVIEW_CREATE_TARGET_TABLE": "datasource.target-table.create.preview",
    "TARGET_TABLE_CREATE_PREVIEW": "datasource.target-table.create.preview",
    "CREATE_TARGET_TABLE": "datasource.target-table.create.apply",
    "TARGET_TABLE_CREATE_APPLY": "datasource.target-table.create.apply",
}

RECOVERY_ACTION_TOOL_NAMES = frozenset(RECOVERY_ACTION_TOOL_MAP.values())

# Recovery Specialist 的建议不能把完整注册表重新暴露给主 Agent。只有这两个工具同时满足
# “固定恢复动作映射 + 注册表只读 + 无需审批 + 本轮 repairActions 明确提出”时，bridge 才能
# 以子委派的形式把它们补入当前 turn。这里故意不包含 retry、replay、apply 或建表 apply；
# 它们即使在注册表中存在，也只能沿用主 Agent 已计划的可见性和 Java 审批链路。
RECOVERY_MINIMAL_READ_ONLY_DELEGATION_TOOL_NAMES = frozenset({
    "sync.dirty-record.quarantine.preview",
    "datasource.schema.repair.preview",
})

# 工具名存在并不等于动作语义正确。这个二次映射把 Recovery 的动作类型和平台
# 注册表中的 allowed_actions 绑定起来，防止错误配置的同名工具成为权限绕过入口。
RECOVERY_TOOL_REQUIRED_ACTION: dict[str, str] = {
    "sync.execution.failed-objects.retry": "RETRY_FAILED_OBJECTS",
    "sync.dirty-record.quarantine.apply": "APPLY_QUARANTINE",
    "sync.dirty-record.quarantine.preview": "PREVIEW_QUARANTINE",
    "sync.dirty-record.replay": "REPLAY_DIRTY_RECORDS",
    "datasource.schema.repair.apply": "ALTER_TARGET_SCHEMA",
    "datasource.schema.repair.preview": "PREVIEW_SCHEMA_REPAIR",
    "datasource.target-table.create.preview": "PREVIEW_CREATE_TARGET_TABLE",
    "datasource.target-table.create.apply": "CREATE_TARGET_TABLE",
}


class SpecialistBridgeStatus(str, Enum):
    """桥接结果的公开状态。

    这些状态故意不复用专业 Agent 的 ``SpecialistTurnStatus``。专业 Agent 的状态描述
    “专业工作是否完成”，而桥接状态描述“结果是否已经具备进入主 Agent Durable
    ToolPlan 链路的条件”，两者处于不同层次，混用会让前端把“等待控制面证据”误显示为
    “用户参数缺失”。
    """

    ACCEPTED = "ACCEPTED"
    WAITING_FOR_SPECIALIST_INPUT = "WAITING_FOR_SPECIALIST_INPUT"
    WAITING_FOR_CONTROL_PLANE_EVIDENCE = "WAITING_FOR_CONTROL_PLANE_EVIDENCE"
    WAITING_FOR_APPROVAL = "WAITING_FOR_APPROVAL"
    WAITING_FOR_JAVA_HANDOFF = "WAITING_FOR_JAVA_HANDOFF"
    REJECTED = "REJECTED"


@dataclass(frozen=True)
class SpecialistBridgeIssue:
    """桥接阶段发现的稳定、低敏治理问题。"""

    code: str
    message: str

    def to_summary(self) -> dict[str, str]:
        """返回可以写入 runtime event 或 API 的问题摘要。"""

        return {"code": self.code, "message": self.message}


@dataclass(frozen=True)
class RecoveryToolPlanBlueprint:
    """恢复动作交给 Java 前的最小 ToolPlan 蓝图。

    当前 RecoverySpecialistAgent 的公开结果只暴露动作名称、参数字段名和审批指纹，
    不暴露恢复参数值。这是有意的低敏策略。因此 ``arguments`` 只有在受控 Java
    事实已经提供了正式 ToolPlan 参数时才会填充；普通 Python 运行结果会保持为空，
    让 Java 控制面根据 durable 事实重新水合，而不是让 Python 猜测参数。
    """

    tool_name: str
    action_id: str
    action_type: str
    risk_level: str = "HIGH"
    argument_field_names: tuple[str, ...] = ()
    arguments: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        """规范化蓝图标识，并冻结参数映射避免桥接后被并发代码修改。"""

        for field_name in ("tool_name", "action_id", "action_type"):
            if not str(getattr(self, field_name) or "").strip():
                raise ValueError(f"Recovery ToolPlan 蓝图缺少 {field_name}")
        object.__setattr__(
            self,
            "argument_field_names",
            tuple(dict.fromkeys(
                str(name).strip()
                for name in self.argument_field_names
                if str(name).strip()
            )),
        )
        object.__setattr__(self, "arguments", MappingProxyType(dict(self.arguments)))

    @property
    def arguments_available(self) -> bool:
        """判断当前蓝图是否含有可交给 Java schema 校验的参数值。"""

        return bool(self.arguments)

    def to_summary(self) -> dict[str, Any]:
        """返回不包含参数值的公开蓝图摘要。"""

        return {
            "toolName": self.tool_name,
            "actionId": self.action_id,
            "actionType": self.action_type,
            "riskLevel": self.risk_level,
            "argumentFieldNames": self.argument_field_names,
            "argumentsAvailable": self.arguments_available,
        }


@dataclass(frozen=True)
class RecoveryJavaToolPlanHandoff:
    """RECOVERY_AGENT 到 Java agent-runtime 的审批/ToolPlan 交接合同。

    合同的执行边界固定为 Java ``ingestion -> approval/outbox -> worker receipt``。即使
    ``approval_fact_accepted`` 为真，Python 也不能解释这个字段为“现在执行”，而只能把
    绑定到本次 delegation/run/action fingerprint 的事实交给 Java。这样可以避免恢复
    任务绕过统一 RBAC、幂等和审计链路。
    """

    tenant_id: str
    application_id: str | None
    project_id: str
    actor_id: str
    user_id: str
    session_id: str
    run_id: str
    delegation_id: str
    action_fingerprint: str
    approval_status: str
    approval_fact_accepted: bool
    blueprints: tuple[RecoveryToolPlanBlueprint, ...] = ()
    approval_request: Mapping[str, Any] = field(default_factory=dict)
    execution_boundary: str = "JAVA_AGENT_RUNTIME_INGESTION_OUTBOX"
    direct_execution: bool = False

    def __post_init__(self) -> None:
        """校验恢复合同不可缺少的三元绑定键，并冻结审批摘要。"""

        required = {
            "tenant_id": self.tenant_id,
            "project_id": self.project_id,
            "actor_id": self.actor_id,
            "user_id": self.user_id,
            "session_id": self.session_id,
            "run_id": self.run_id,
            "delegation_id": self.delegation_id,
            "action_fingerprint": self.action_fingerprint,
        }
        missing = tuple(name for name, value in required.items() if not str(value or "").strip())
        if missing:
            raise ValueError(f"Recovery Java handoff 缺少绑定字段：{', '.join(missing)}")
        if self.direct_execution:
            raise ValueError("Recovery Java handoff 禁止标记为 Python 直接执行")
        object.__setattr__(self, "approval_request", MappingProxyType(dict(self.approval_request)))

    @property
    def requires_java_rehydration(self) -> bool:
        """判断 Java 是否还需要根据 durable 事实补齐 ToolPlan 参数。"""

        return bool(self.blueprints) and not all(item.arguments_available for item in self.blueprints)

    def to_summary(self) -> dict[str, Any]:
        """返回审批页面和 Agent 过程面板可以安全展示的摘要。"""

        return {
            "schemaVersion": RECOVERY_JAVA_HANDOFF_SCHEMA_VERSION,
            "tenantId": self.tenant_id,
            "applicationId": self.application_id,
            "projectId": self.project_id,
            "actorId": self.actor_id,
            "userId": self.user_id,
            "sessionId": self.session_id,
            "runId": self.run_id,
            "delegationId": self.delegation_id,
            "actionFingerprint": self.action_fingerprint,
            "approvalStatus": self.approval_status,
            "approvalFactAccepted": self.approval_fact_accepted,
            "blueprints": tuple(item.to_summary() for item in self.blueprints),
            "blueprintCount": len(self.blueprints),
            "requiresJavaRehydration": self.requires_java_rehydration,
            "executionBoundary": self.execution_boundary,
            "directExecution": False,
            "requiredApprovalBindings": (
                "tenantId",
                "applicationId",
                "projectId",
                "userId",
                "delegationId",
                "runId",
                "actionFingerprint",
            ),
            "payloadPolicy": "LOW_SENSITIVE_RECOVERY_JAVA_HANDOFF_ONLY",
        }


@dataclass(frozen=True)
class SpecialistToolPlanBridgeResult:
    """一次专业 Agent 结果桥接的内部结果和 Durable Loop 入口。

    ``plan`` 与 ``model_turn`` 组合后可以直接传给
    ``AgentDurableModelToolLoopRunner.run(request=..., plan=plan, first_model_turn=model_turn)``。
    这并不表示桥接层已经执行了任何工具；它只构造下一批已经过治理的 ToolPlan，真正
    ingestion、审批、outbox、worker 和 feedback 仍由现有控制面完成。
    """

    status: SpecialistBridgeStatus
    specialist_role: AgentSessionRole
    specialist_turn_id: str
    public_summary: str
    accepted_tool_plans: tuple[ToolPlan, ...] = ()
    plan: AgentPlan | None = None
    model_turn: AgentSecondTurnResult | None = None
    recovery_handoff: RecoveryJavaToolPlanHandoff | None = None
    visible_tool_names: tuple[str, ...] = ()
    issues: tuple[SpecialistBridgeIssue, ...] = ()
    specialist_result_fingerprint: str | None = None
    scope_binding: Mapping[str, Any] = field(default_factory=dict)

    @property
    def can_submit_durable_loop(self) -> bool:
        """只有同时具备计划和非空下一批工具时才允许提交 Durable Loop。"""

        return self.status is SpecialistBridgeStatus.ACCEPTED and bool(
            self.plan is not None and self.model_turn is not None and self.accepted_tool_plans
        )

    def to_summary(self) -> dict[str, Any]:
        """返回低敏桥接摘要，隐藏 ToolPlan 参数值和恢复参数正文。"""

        return {
            "schemaVersion": SPECIALIST_TOOLPLAN_BRIDGE_SCHEMA_VERSION,
            "status": self.status.value,
            "specialistRole": self.specialist_role.value,
            "specialistTurnId": self.specialist_turn_id,
            "publicSummary": self.public_summary,
            "acceptedToolPlanCount": len(self.accepted_tool_plans),
            "acceptedToolNames": tuple(item.tool_name for item in self.accepted_tool_plans),
            "visibleToolNames": self.visible_tool_names,
            "canSubmitDurableLoop": self.can_submit_durable_loop,
            "toolArgumentNameSets": tuple(
                tuple(sorted(item.arguments.keys())) for item in self.accepted_tool_plans
            ),
            "issues": tuple(issue.to_summary() for issue in self.issues),
            "specialistResultFingerprint": self.specialist_result_fingerprint,
            "scopeBinding": dict(self.scope_binding),
            "recoveryHandoff": (
                self.recovery_handoff.to_summary()
                if self.recovery_handoff is not None
                else None
            ),
            "payloadPolicy": "LOW_SENSITIVE_SPECIALIST_TOOLPLAN_BRIDGE_ONLY",
        }


class SpecialistToolPlanBridge:
    """把专业 Agent 结果安全转换为主 Agent 可继续提交的 ToolPlan 前沿。

    该类是纯编排服务，不持有请求级可变状态，也不依赖 FastAPI、LangGraph 或具体
    Provider。这样它可以被初始 Agent、Durable continuation、审批回调和离线测试复用，
    同时避免把桥接逻辑塞进 ``app.py`` 或主模型响应组装器。
    """

    def __init__(
        self,
        *,
        tool_planner: ToolPlanner,
        follow_up_tool_planner: AgentFollowUpToolPlanner,
    ) -> None:
        """注入平台工具注册表和既有 follow-up 治理器。

        两个依赖必须来自同一份启动时工具注册表。若桥接层自己维护一份工具目录，
        就可能出现“前端显示的工具”和“Java 实际允许的工具”不一致，因此这里不提供
        默认目录，也不允许调用方只传工具名称绕过平台注册。
        """

        if not isinstance(tool_planner, ToolPlanner):
            raise TypeError("tool_planner 必须是 ToolPlanner")
        if not isinstance(follow_up_tool_planner, AgentFollowUpToolPlanner):
            raise TypeError("follow_up_tool_planner 必须是 AgentFollowUpToolPlanner")
        self._tool_planner = tool_planner
        self._follow_up = follow_up_tool_planner

    def bridge(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        specialist_result: SpecialistTurnResult,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None = None,
    ) -> SpecialistToolPlanBridgeResult:
        """按专业 Agent 角色分派到对应的桥接分支。

        DATA_SYNC_AGENT 会尝试生成可提交的同步任务 ToolPlan；RECOVERY_AGENT 只会生成
        审批/Java handoff 合同。其它角色不能直接生成业务 ToolPlan，防止未来新增专业
        Agent 时意外获得主 Agent 的写入能力。
        """

        if specialist_result.role is AgentSessionRole.DATA_SYNC_AGENT:
            return self.bridge_data_sync(
                request=request,
                plan=plan,
                specialist_result=specialist_result,
                control_plane_feedback=control_plane_feedback,
            )
        if specialist_result.role is AgentSessionRole.RECOVERY_AGENT:
            return self.bridge_recovery(
                request=request,
                plan=plan,
                specialist_result=specialist_result,
                control_plane_feedback=control_plane_feedback,
            )
        return self._rejected(
            specialist_result,
            code="SPECIALIST_ROLE_CANNOT_CREATE_TOOLPLAN",
            message="当前专业 Agent 只允许提供事实或解释，不能直接生成主 Agent ToolPlan。",
        )

    def bridge_data_sync(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        specialist_result: SpecialistTurnResult,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> SpecialistToolPlanBridgeResult:
        """将 DATA_SYNC_AGENT 草案转为 ``sync.task.draft.save`` 和生命周期。

        这里最重要的顺序是“先验证控制面证据，再调用 follow-up planner”：如果先把
        专业 Agent 的元数据摘要伪造成反馈，表名、字段名和数据源 ID 就会被错误地当成
        Java 事实，进而失去真实元数据状态校验的意义。
        """

        base_issues = self._validate_data_sync_result(specialist_result)
        if base_issues:
            return self._bridge_without_plans(
                specialist_result,
                status=(
                    SpecialistBridgeStatus.WAITING_FOR_SPECIALIST_INPUT
                    if specialist_result.status is SpecialistTurnStatus.WAITING_FOR_INPUT
                    else SpecialistBridgeStatus.REJECTED
                ),
                issues=base_issues,
                summary="同步专业 Agent 的草案尚未具备进入主 Agent ToolPlan 的完整条件。",
            )

        scope_issues = self._validate_scope_binding(
            request=request,
            plan=plan,
            control_plane_feedback=control_plane_feedback,
            specialist_result=specialist_result,
        )
        if scope_issues:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=scope_issues,
                summary="同步草案的租户、应用、项目或用户委派绑定与当前请求不一致，已拒绝提交。",
            )
        scope_binding = self._scope_binding(
            request=request,
            plan=plan,
            specialist_result=specialist_result,
            control_plane_feedback=control_plane_feedback,
        )

        metadata_issues = self._validate_metadata_feedback(control_plane_feedback)
        if metadata_issues:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.WAITING_FOR_CONTROL_PLANE_EVIDENCE,
                issues=metadata_issues,
                summary="同步草案已经生成，但还没有收到两端真实元数据的控制面证据。",
            )

        configuration = specialist_result.structured_output
        datasource_issues = self._validate_datasource_scope(
            configuration,
            control_plane_feedback,
        )
        if datasource_issues:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=datasource_issues,
                summary="同步草案中的数据源身份与控制面元数据事实不一致，已拒绝提交。",
            )

        try:
            arguments = self._draft_arguments(configuration)
        except ValueError as exc:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=(SpecialistBridgeIssue("SPECIALIST_DRAFT_ARGUMENTS_INVALID", str(exc)),),
                summary="同步专业 Agent 返回的草案参数不符合平台 JSON 合同。",
            )

        visible_tools = self._follow_up.visible_tools(request, plan)
        visible_names = tuple(tool.name for tool in visible_tools)
        if "sync.task.draft.save" not in visible_names:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                visible_tool_names=visible_names,
                issues=(SpecialistBridgeIssue(
                    "SPECIALIST_DRAFT_TOOL_NOT_VISIBLE",
                    "当前主 Agent 委派范围没有暴露同步草稿工具，不能因为专业 Agent 完成就扩大工具权限。",
                ),),
                summary="同步草案未获得当前主 Agent 委派范围允许的工具可见性。",
            )

        call = ModelToolCall(
            call_id=self._specialist_call_id(specialist_result),
            type="specialist_result",
            name="sync.task.draft.save",
            arguments=json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
            raw_call={
                "source": "specialist_result_bridge",
                "specialistRole": specialist_result.role.value,
                "specialistTurnId": specialist_result.turn_id,
            },
        )
        governed = self._follow_up.govern(
            request=request,
            plan=plan,
            tool_calls=(call,),
            visible_tools=visible_tools,
            control_plane_feedback=control_plane_feedback,
        )
        accepted = self._mark_specialist_origin(
            governed,
            specialist_result,
            scope_binding=scope_binding,
        )
        if not accepted:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                visible_tool_names=visible_names,
                issues=self._governance_issues(governed),
                summary="同步草案未通过主 Agent 的工具、参数或真实状态治理。",
            )

        bridge_plan = replace(
            plan,
            state_trace=plan.state_trace + ("specialist_result_to_toolplan",),
            tool_plans=accepted,
            requires_human_approval=any(item.requires_human_approval for item in accepted),
            response_summary=(
                "DATA_SYNC_AGENT 草案已经通过主 Agent 治理，等待 Java 控制面接收同步生命周期。"
            ),
            next_actions=("提交 Java agent-runtime ingestion，等待预检查/审批/执行反馈。",),
        )
        model_turn = AgentSecondTurnResult(
            executed=False,
            allowed=True,
            action="continue_with_tools",
            summary="专业同步草案已转为受治理 ToolPlan，准备进入 Java Durable 执行链路。",
            visible_tool_names=visible_names,
            model_tool_call_count=0,
            follow_up_tool_plans=accepted,
            governance_issue_codes=governed.state_guard_issue_codes,
            governance_issue_messages=governed.state_guard_issue_messages,
            budget_issue_codes=governed.budget_issue_codes,
        )
        return SpecialistToolPlanBridgeResult(
            status=SpecialistBridgeStatus.ACCEPTED,
            specialist_role=specialist_result.role,
            specialist_turn_id=specialist_result.turn_id,
            public_summary="同步草案已通过主 Agent 治理，下一步由 Java Durable 控制面接收并执行。",
            accepted_tool_plans=accepted,
            plan=bridge_plan,
            model_turn=model_turn,
            visible_tool_names=visible_names,
            specialist_result_fingerprint=self._result_fingerprint(specialist_result),
            scope_binding=scope_binding,
        )

    def _map_recovery_action(self, action: Mapping[str, Any]) -> str | None:
        """把不可信 RecoveryAction 映射到固定的平台注册工具。

        ``toolName`` 只能作为一致性提示，真正的选择依据是受限的 actionType 映射表。
        这样模型即使把一个高风险工具名写进另一个动作，也不能借字段名直接扩大能力；
        duplicate task name 则由专门 planner 处理，不在这里伪造一个 rename 工具。

        映射表本身不是工具注册表。这里还必须回查启动时注入的实际注册工具，并确认
        工具带有服务端目标、租户/项目范围和动作声明；否则“映射表里有名字”不能证明
        Java 控制面真的认识并治理该工具。
        """

        action_type = self._recovery_action_key(action.get("actionType"))
        tool_name = self._text(action.get("toolName"))
        mapped = RECOVERY_ACTION_TOOL_MAP.get(action_type)
        if mapped is None or mapped not in RECOVERY_ACTION_TOOL_NAMES:
            return None
        if tool_name and tool_name != mapped:
            return None
        registered = next(
            (tool for tool in self._tool_planner.registered_tools() if tool.name == mapped),
            None,
        )
        if registered is None:
            return None
        if not registered.target_service or not registered.target_endpoint:
            return None
        if not registered.tenant_scoped or not registered.project_scoped:
            return None
        required_action = RECOVERY_TOOL_REQUIRED_ACTION.get(mapped)
        if required_action is None or required_action not in registered.allowed_actions:
            return None
        return mapped

    def _recovery_minimal_delegated_visible_tools(
        self,
        *,
        base_visible_tools: tuple[ToolDefinition, ...],
        action_mappings: tuple[Mapping[str, Any], ...],
    ) -> tuple[ToolDefinition, ...]:
        """Return the Recovery turn's fail-closed minimum delegated tool frontier.

        The main Agent's normal frontier remains authoritative for every ordinary tool.  Recovery is a
        deliberately narrow exception because the Specialist may have produced a registered *read-only*
        diagnostic preview after the parent plan was created.  Rejecting that preview merely because the
        earlier parent frontier did not predict the exact failure blocks safe diagnosis, while exposing the
        full recovery catalog would turn the exception into a privilege-escalation path.

        A tool is appended only after all of these independent checks pass:

        * the current ``repairActions`` maps through the fixed ``actionType -> tool`` table;
        * the mapped name belongs to the two-item minimal preview allowlist, never a mutation tool;
        * the immutable startup registry still declares the tool as read-only and not approval-required;
        * the Registry's action contract was already validated by ``_map_recovery_action``.

        The method does not infer a tool from a failure code, raw model text, an endpoint, or an arbitrary
        ``toolName``.  Consequently an unsupported, unregistered, write, or merely unplanned tool remains
        invisible and reaches the existing ``RECOVERY_TOOL_NOT_VISIBLE``/unsupported rejection path.
        """

        visible_tools = list(base_visible_tools)
        visible_names = {tool.name for tool in visible_tools}
        registered_by_name = {
            tool.name: tool
            for tool in self._tool_planner.registered_tools()
        }

        for action in action_mappings:
            tool_name = self._map_recovery_action(action)
            if tool_name is None or tool_name in visible_names:
                continue
            if tool_name not in RECOVERY_MINIMAL_READ_ONLY_DELEGATION_TOOL_NAMES:
                continue
            tool = registered_by_name.get(tool_name)
            if tool is None or not tool.read_only or tool.requires_approval:
                continue
            visible_tools.append(tool)
            visible_names.add(tool_name)

        return tuple(visible_tools)

    def _recovery_model_optional_arguments(
        self,
        tool_name: str,
        action: Mapping[str, Any],
    ) -> dict[str, Any]:
        """提取工具 schema 允许的可选建议参数，忽略所有 derived/system 字段。

        diagnosisRef、taskId 和 executionId 等定位信息必须来自 Java 成功反馈并由
        ``AgentFollowUpToolPlanner`` 注入；模型只能提供平台注册表标记为 ``model_optional``
        的有限参数。这里还限制了容器大小和 JSON 类型，避免 Recovery 输出绕过统一参数校验。
        """

        tool = next((item for item in self._tool_planner.registered_tools() if item.name == tool_name), None)
        if tool is None:
            return {}
        candidates: dict[str, Any] = {}
        for source_name in ("originalValues", "proposedValues"):
            values = action.get(source_name)
            if isinstance(values, Mapping):
                candidates.update(values)
        arguments: dict[str, Any] = {}
        for name, value in candidates.items():
            definition = tool.input_schema.get(str(name))
            if not isinstance(definition, Mapping):
                continue
            if str(definition.get("resolution") or "").strip().lower() != "model_optional":
                continue
            arguments[str(name)] = self._json_safe(value, path=f"recovery.{tool_name}.{name}")
        if (
            tool_name == "sync.dirty-record.quarantine.preview"
            and not self._has_argument_value(arguments.get("errorSampleIds"))
        ):
            # Dirty-row IDs are intentionally absent from the model contract. A preview with neither
            # IDs nor this selector is invalid at the data-sync boundary, while selecting all retryable
            # samples remains read-only and is capped by that service. The exact selected IDs and digest
            # are returned by Java and any later apply still requires explicit user approval.
            arguments["quarantineAllRetryableInExecution"] = True
        return arguments

    def _recovery_missing_model_arguments(
        self,
        tool_name: str,
        arguments: Mapping[str, Any],
    ) -> tuple[str, ...]:
        """Return required model/user fields that the recovery proposal did not ground.

        Derived fields such as ``diagnosisRef`` are excluded because the follow-up planner must inject
        them from successful Java feedback. Required ``model_optional``/``user_required`` fields are
        different: submitting an empty schema-repair preview would merely create a PLANNED audit that can
        never execute. The bridge therefore keeps the omission visible as a low-sensitive issue and lets
        other complete, read-only actions continue instead of poisoning the whole recovery batch.
        """

        tool = next((item for item in self._tool_planner.registered_tools() if item.name == tool_name), None)
        if tool is None:
            return ()
        missing: list[str] = []
        for name, definition in tool.input_schema.items():
            if not isinstance(definition, Mapping) or definition.get("required") is not True:
                continue
            resolution = str(definition.get("resolution") or "").strip().lower()
            if resolution not in {"model_optional", "model_required", "user_required"}:
                continue
            if not self._has_argument_value(arguments.get(str(name))):
                missing.append(str(name))
        return tuple(missing)

    @staticmethod
    def _has_argument_value(value: Any) -> bool:
        """Distinguish an intentional boolean/number from an absent or empty recovery argument."""

        if value is None:
            return False
        if isinstance(value, str):
            return bool(value.strip())
        if isinstance(value, (Mapping, Sequence)) and not isinstance(value, (str, bytes)):
            return bool(value)
        return True

    def _recovery_diagnosis_evidence_issue(
        self,
        feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> SpecialistBridgeIssue | None:
        """要求真实诊断成功事实，防止 bridge 猜造 derived ``diagnosisRef``。"""

        if feedback is None:
            return SpecialistBridgeIssue(
                "RECOVERY_DIAGNOSIS_EVIDENCE_REQUIRED",
                "恢复工具需要真实的 sync.execution.diagnose 成功反馈，当前还没有可绑定的诊断事实。",
            )
        item = next(
            (item for item in reversed(feedback.feedback_items) if item.tool_name == "sync.execution.diagnose"),
            None,
        )
        if item is None or getattr(getattr(item, "status", None), "value", "") != "succeeded":
            return SpecialistBridgeIssue(
                "RECOVERY_DIAGNOSIS_EVIDENCE_REQUIRED",
                "恢复工具需要先完成 sync.execution.diagnose；没有成功诊断结果就不能提交恢复动作。",
            )
        if not getattr(item, "audit_id", None) or not getattr(item, "run_id", None):
            return SpecialistBridgeIssue(
                "RECOVERY_DIAGNOSIS_EVIDENCE_UNBOUND",
                "失败诊断反馈缺少 auditId/runId，不能生成可审计的 diagnosisRef。",
            )
        return None

    def _build_recovery_diagnosis_bootstrap(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        specialist_result: SpecialistTurnResult,
        scope_binding: Mapping[str, Any],
        visible_tools: Sequence[Any],
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> SpecialistToolPlanBridgeResult | None:
        """把 Specialist 已验证的只读诊断定位重新提交为 Java Durable ToolPlan。

        Recovery Specialist 为了给模型提供根因，会先通过受保护的 data-sync 只读接口读取诊断；
        该调用本身不属于主 Agent 的 ``agent_tool_execution_audit``，所以不能直接充当后续工具所需的
        ``diagnosisRef``。当主循环尚无成功诊断 audit 时，本方法只从结果的非公开
        ``control_plane_fact_binding`` 读取 task/execution 定位，并逐项核对 tenant/application/
        project/actor/session/run/delegation。核对通过后仅创建一个幂等、只读的
        ``sync.execution.diagnose`` ToolPlan；真正 auditId/runId 仍由 Java 创建，下一次 Bridge 才能
        用正式 feedback 生成预览、重试或修复计划。

        返回 ``None`` 表示绑定缺失或不一致。调用方必须继续停在控制面证据等待态，不能从公开
        ``structured_output``、objective 或模型动作中猜 taskId/executionId。
        """

        binding = specialist_result.control_plane_fact_binding
        if not isinstance(binding, Mapping):
            return None
        if self._text(binding.get("source")) != "data-sync-control-plane":
            return None
        if self._text(binding.get("factType")) != "SYNC_EXECUTION_DIAGNOSIS":
            return None
        identity_pairs = (
            ("tenantId", "tenantId"),
            ("applicationId", "applicationId"),
            ("projectId", "projectId"),
            ("actorId", "actorId"),
            ("sessionId", "sessionId"),
            ("runId", "runId"),
            ("delegationId", "delegationId"),
        )
        for fact_name, scope_name in identity_pairs:
            expected = self._text(scope_binding.get(scope_name))
            actual = self._text(binding.get(fact_name))
            # applicationId 在部分旧租户中可以同时为空；其它身份字段已经由 scope 校验保证非空。
            if actual != expected:
                return None
        task_id = self._positive_decimal_identifier(binding.get("taskId"))
        execution_id = self._positive_decimal_identifier(binding.get("executionId"))
        if task_id is None or execution_id is None:
            return None

        visible_names = tuple(tool.name for tool in visible_tools)
        diagnose_tool = "sync.execution.diagnose"
        if diagnose_tool not in visible_names:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                visible_tool_names=visible_names,
                issues=(SpecialistBridgeIssue(
                    "RECOVERY_DIAGNOSIS_TOOL_NOT_VISIBLE",
                    "只读失败诊断工具不在本次主 Agent 委派范围内，系统不会扩大权限创建诊断审计。",
                ),),
                summary="Recovery 已有低敏诊断定位，但当前委派不允许创建 Java 诊断 ToolPlan。",
            )

        call = ModelToolCall(
            call_id=self._recovery_call_id(specialist_result, {"actionId": "diagnosis-bootstrap"}, 0),
            type="specialist_control_plane_fact",
            name=diagnose_tool,
            arguments=json.dumps(
                {"taskId": int(task_id), "executionId": int(execution_id)},
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
            raw_call={
                "source": "specialist_control_plane_fact_binding",
                "specialistRole": specialist_result.role.value,
                "specialistTurnId": specialist_result.turn_id,
            },
        )
        governed = self._follow_up.govern(
            request=request,
            plan=plan,
            tool_calls=(call,),
            visible_tools=visible_tools,
            control_plane_feedback=control_plane_feedback,
        )
        accepted = self._mark_specialist_origin(
            governed,
            specialist_result,
            scope_binding=scope_binding,
        )
        if not accepted and governed.repeated_count:
            # 主 Agent 可能已经从用户目标中计划了同一个只读诊断，只是尚未取得可供后续
            # preview 引用的 Java audit。重复保护不应再创建第二个诊断节点，但 Recovery
            # 仍需沿用原节点进入 Durable runner，才能生成正式 diagnosisRef。
            accepted = self._reuse_safe_recovery_plans(
                plan=plan,
                recovery_calls=(call,),
                governed=governed,
                specialist_result=specialist_result,
                scope_binding=scope_binding,
            )
        if not accepted or governed.rejected_count or governed.state_guard_issue_codes or governed.budget_issue_codes:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                visible_tool_names=visible_names,
                issues=self._governance_issues(governed),
                summary="Java 诊断 bootstrap 未通过统一工具治理，已停止后续恢复动作。",
            )

        bootstrap_plan = replace(
            plan,
            state_trace=plan.state_trace + ("recovery_diagnosis_bootstrap",),
            tool_plans=accepted,
            requires_human_approval=False,
            response_summary="正在通过 Java Durable 控制面登记只读失败诊断，随后继续恢复动作桥接。",
            next_actions=("等待 sync.execution.diagnose 成功 audit，再解析只读预览或高风险审批计划。",),
        )
        model_turn = AgentSecondTurnResult(
            executed=False,
            allowed=True,
            action="continue_with_tools",
            summary="已依据可信 Specialist 诊断范围创建 Java 只读诊断 ToolPlan。",
            visible_tool_names=visible_names,
            model_tool_call_count=0,
            follow_up_tool_plans=accepted,
            governance_issue_codes=governed.state_guard_issue_codes,
            governance_issue_messages=governed.state_guard_issue_messages,
            budget_issue_codes=governed.budget_issue_codes,
        )
        return SpecialistToolPlanBridgeResult(
            status=SpecialistBridgeStatus.ACCEPTED,
            specialist_role=specialist_result.role,
            specialist_turn_id=specialist_result.turn_id,
            public_summary="Recovery 的只读诊断正在进入 Java audit；取得正式 diagnosisRef 后继续处理恢复建议。",
            accepted_tool_plans=accepted,
            plan=bootstrap_plan,
            model_turn=model_turn,
            visible_tool_names=visible_names,
            specialist_result_fingerprint=self._result_fingerprint(specialist_result),
            scope_binding=scope_binding,
        )

    @staticmethod
    def _positive_decimal_identifier(value: Any) -> str | None:
        """只接受正十进制资源 ID，防止内部绑定携带自由文本或通配符。"""

        text = str(value or "").strip()
        if not text.isdigit():
            return None
        try:
            return text if int(text) > 0 else None
        except ValueError:
            return None

    def _build_duplicate_name_recovery(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        specialist_result: SpecialistTurnResult,
        action_mappings: tuple[Mapping[str, Any], ...],
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
        scope_binding: Mapping[str, Any],
    ) -> SpecialistToolPlanBridgeResult | None:
        """复用既有 duplicate planner 处理同名任务，不重复实现重命名生命周期。

        只有 Recovery 建议明确涉及任务重命名且控制面反馈中存在同名草稿失败时才进入该分支。
        planner 会重新绑定成功元数据引用、保留原任务配置并生成唯一名称；如果本计划已经包含
        同一恢复生命周期，则直接返回拒绝结果，保证重复 specialist 回放不会重复创建任务。
        """

        duplicate_action = any(
            self._recovery_action_key(item.get("actionType")) in {"RENAME_TASK", "DUPLICATE_TASK_NAME"}
            or self._text(item.get("toolName")) == "task.recovery.rename"
            for item in action_mappings
        )
        if not duplicate_action or control_plane_feedback is None:
            return None
        failed_item = next(
            (
                item
                for item in control_plane_feedback.feedback_items
                if item.tool_name == "sync.task.draft.save"
                and getattr(getattr(item, "status", None), "value", "") == "failed"
            ),
            None,
        )
        if failed_item is None:
            return None
        source_run_id = str(getattr(failed_item, "run_id", None) or request.request_id or "").strip()
        if not source_run_id:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=(SpecialistBridgeIssue(
                    "RECOVERY_DUPLICATE_SOURCE_RUN_MISSING",
                    "同名任务恢复缺少原始失败 Run 绑定，未生成重命名计划。",
                ),),
                summary="同名任务恢复缺少可信失败 Run，已停止。",
            )
        repair = DuplicateTaskNameRecoveryPlanner(self._tool_planner).build(
            source_run_id=source_run_id,
            tool_plans=plan.tool_plans,
            feedback_items=control_plane_feedback.feedback_items,
        )
        if repair is None:
            return None
        proposed_name = repair.proposal.proposed_task_name
        if any(
            item.tool_name == "sync.task.draft.save"
            and str(item.arguments.get("taskName") or "").strip() == proposed_name
            for item in plan.tool_plans
        ):
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=(SpecialistBridgeIssue(
                    "RECOVERY_DUPLICATE_REPAIR_ALREADY_PLANNED",
                    "同名任务的重命名恢复计划已经存在，未重复创建任务或重新提交生命周期。",
                ),),
                summary="同名任务恢复计划已存在，已按幂等规则跳过重复提交。",
            )

        visible_names = tuple(item.name for item in self._follow_up.visible_tools(request, plan))
        if "sync.task.draft.save" not in visible_names:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                visible_tool_names=visible_names,
                issues=(SpecialistBridgeIssue(
                    "RECOVERY_DUPLICATE_DRAFT_TOOL_NOT_VISIBLE",
                    "当前委派没有同步草稿工具可见性，不能扩大权限执行重命名恢复。",
                ),),
                summary="同名任务恢复未获得同步草稿工具权限。",
            )

        accepted = tuple(
            replace(
                item,
                governance_hints={
                    **item.governance_hints,
                    "specialistBridgeSource": "specialist_result_bridge",
                    "specialistAgentRole": specialist_result.role.value,
                    "specialistTurnId": specialist_result.turn_id,
                    "specialistResultFingerprint": self._result_fingerprint(specialist_result),
                    "agentScopeBinding": dict(scope_binding),
                },
            )
            for item in repair.tool_plans
        )
        bridge_plan = replace(
            plan,
            state_trace=plan.state_trace + ("recovery_duplicate_task_name_recovery",),
            tool_plans=accepted,
            requires_human_approval=True,
            response_summary="同名任务已按既有恢复规划器生成重命名生命周期，等待 Java 审批。",
            next_actions=("先确认任务名称变更，再由 Java 控制面提交保存/预检/发布/运行生命周期。",),
        )
        model_turn = AgentSecondTurnResult(
            executed=False,
            allowed=True,
            action="continue_with_tools",
            summary="已复用 duplicate_task_name_recovery 生成重命名恢复 ToolPlan。",
            visible_tool_names=visible_names,
            model_tool_call_count=0,
            follow_up_tool_plans=accepted,
        )
        return SpecialistToolPlanBridgeResult(
            status=SpecialistBridgeStatus.ACCEPTED,
            specialist_role=specialist_result.role,
            specialist_turn_id=specialist_result.turn_id,
            public_summary="同名任务恢复已复用平台既有重命名规划器并交给 Java 控制面。",
            accepted_tool_plans=accepted,
            plan=bridge_plan,
            model_turn=model_turn,
            visible_tool_names=visible_names,
            specialist_result_fingerprint=self._result_fingerprint(specialist_result),
            scope_binding=scope_binding,
        )

    def _build_recovery_handoff(
        self,
        *,
        specialist_result: SpecialistTurnResult,
        action_fingerprint: str,
        mapped_actions: list[tuple[Mapping[str, Any], str, dict[str, Any]]],
        scope_binding: Mapping[str, Any],
    ) -> RecoveryJavaToolPlanHandoff | None:
        """在有可信会话绑定时附加低敏 Java handoff 摘要，实际 ToolPlan 仍是主结果。

        绑定值全部来自主请求和已存在的运行时事实，不能从 Recovery 模型输出的
        ``approvalRequest`` 或 ``javaToolPlan`` 反向推断。这样即使模型伪造批准字段，
        handoff 也只能携带当前用户、租户、应用、项目和委派范围。
        """

        session_id = self._text(scope_binding.get("sessionId"))
        delegation_id = self._text(scope_binding.get("delegationId"))
        run_id = self._text(scope_binding.get("runId"))
        if not session_id or not delegation_id or not run_id:
            return None
        blueprints = tuple(
            RecoveryToolPlanBlueprint(
                tool_name=tool_name,
                action_id=self._text(action.get("actionId")) or f"recovery-action-{index}",
                action_type=self._text(action.get("actionType")) or "RECOVERY_ACTION",
                risk_level="HIGH" if tool_name != "sync.dirty-record.quarantine.preview" else "LOW",
                argument_field_names=tuple(sorted(arguments)),
                arguments=arguments,
            )
            for index, (action, tool_name, arguments) in enumerate(mapped_actions, start=1)
        )
        requires_approval = any(item.risk_level == "HIGH" for item in blueprints)
        return RecoveryJavaToolPlanHandoff(
            tenant_id=self._text(scope_binding.get("tenantId")) or "",
            application_id=self._text(scope_binding.get("applicationId")),
            project_id=self._text(scope_binding.get("projectId")) or "",
            actor_id=self._text(scope_binding.get("actorId")) or "",
            user_id=self._text(scope_binding.get("userId")) or "",
            session_id=session_id,
            run_id=run_id,
            delegation_id=delegation_id,
            action_fingerprint=action_fingerprint,
            approval_status=(
                "JAVA_TOOLPLAN_APPROVAL_OUTBOX_PENDING"
                if requires_approval
                else "JAVA_TOOLPLAN_HANDOFF_PENDING"
            ),
            approval_fact_accepted=False,
            blueprints=blueprints,
            approval_request={
                "required": requires_approval,
                "source": "JAVA_CONTROL_PLANE_TOOLPLAN_GOVERNANCE",
                "scopeBinding": dict(scope_binding),
            },
        )

    @staticmethod
    def _recovery_action_key(value: Any) -> str:
        """把动作名规范化为只含大写字母、数字的比较键。"""

        text = str(value or "").strip().upper()
        return "_".join(part for part in text.replace("-", "_").replace(".", "_").split("_") if part)

    @classmethod
    def _recovery_action_diagnostic_label(cls, value: Any) -> str:
        """返回可公开的动作码；自由文本只返回指纹，不回显模型正文。

        合法动作码必须是短 ASCII 标识。若模型把自然语言、SQL 片段或其它正文放进 actionType，
        这里不会把正文带进 E2E、前端或日志，而是返回稳定短指纹供服务端关联排查。
        """

        raw = str(value or "").strip()
        if re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]{0,79}", raw):
            return cls._recovery_action_key(raw)
        digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12]
        return f"UNRECOGNIZED_{digest}"

    @staticmethod
    def _recovery_call_id(
        result: SpecialistTurnResult,
        action: Mapping[str, Any],
        index: int,
    ) -> str:
        """生成稳定的 bridge tool call id，供 Durable loop 幂等和审计关联使用。"""

        action_id = str(action.get("actionId") or f"recovery-action-{index}").strip()
        digest = hashlib.sha256(f"{result.turn_id}|{action_id}|{index}".encode("utf-8")).hexdigest()[:20]
        return f"recovery-{digest}"

    def bridge_recovery(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        specialist_result: SpecialistTurnResult,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None = None,
    ) -> SpecialistToolPlanBridgeResult:
        """把 RecoveryAction 转换成已注册的受治理 ToolPlan。

        Recovery 输出只是一组不可信建议。这里不解析批准字段，也不接收任何用户侧执行器；
        bridge 先按动作类型做固定白名单映射，再复用 ``AgentFollowUpToolPlanner.govern``。
        因此最终是否可提交由统一的工具可见性、参数 schema、权限、预算、重复和控制面反馈
        状态共同决定，高风险工具的审批/outbox 由 Java agent-runtime 生成。
        """

        if specialist_result.role is not AgentSessionRole.RECOVERY_AGENT:
            return self._rejected(
                specialist_result,
                code="SPECIALIST_RESULT_ROLE_MISMATCH",
                message="恢复桥接只接受 RECOVERY_AGENT 的结果，不能把其他专业 Agent 输出当成恢复动作。",
            )
        output = specialist_result.structured_output
        if specialist_result.status is SpecialistTurnStatus.FAILED:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=(SpecialistBridgeIssue(
                    specialist_result.error_code or "RECOVERY_SPECIALIST_FAILED",
                    "故障恢复专业 Agent 本轮失败，没有生成可交接的恢复建议。",
                ),),
                summary="恢复专业 Agent 本轮失败，已停止工具交接。",
            )
        if specialist_result.status is not SpecialistTurnStatus.COMPLETED:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.WAITING_FOR_SPECIALIST_INPUT,
                issues=(SpecialistBridgeIssue(
                    "RECOVERY_SPECIALIST_INPUT_REQUIRED",
                    "恢复专业 Agent 还没有完成诊断建议，不能提前创建恢复 ToolPlan。",
                ),),
                summary="恢复建议尚未完成，等待专业 Agent 补齐诊断输入。",
            )
        if not isinstance(output, Mapping):
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=(SpecialistBridgeIssue(
                    "RECOVERY_RESULT_OUTPUT_INVALID",
                    "恢复专业 Agent 没有返回结构化建议对象，已拒绝交接。",
                ),),
                summary="恢复结果不是受支持的结构化建议。",
            )
        if output.get("executed") is True or output.get("approvalFactAccepted") is True:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=(SpecialistBridgeIssue(
                    "RECOVERY_RESULT_CLAIMS_SIDE_EFFECT",
                    "恢复专业 Agent 只能提出建议，不能声称已经执行或已经取得批准事实。",
                ),),
                summary="恢复结果包含不可信的执行/批准声明，已停止工具交接。",
            )

        action_fingerprint = self._text(output.get("actionFingerprint"))
        actions = output.get("repairActions")
        action_mappings = tuple(
            item
            for item in actions
            if isinstance(item, Mapping)
        ) if isinstance(actions, Sequence) and not isinstance(actions, (str, bytes)) else ()
        if not action_fingerprint or not action_mappings:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=(SpecialistBridgeIssue(
                    "RECOVERY_ACTION_PROPOSAL_INCOMPLETE",
                    "恢复结果缺少动作列表或稳定动作指纹，不能创建受治理 ToolPlan。",
                ),),
                summary="恢复结果还没有形成完整的结构化动作建议。",
            )

        scope_issues = self._validate_scope_binding(
            request=request,
            plan=plan,
            control_plane_feedback=control_plane_feedback,
            specialist_result=specialist_result,
        )
        if scope_issues:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                issues=scope_issues,
                summary="恢复建议的租户、应用、项目或用户委派绑定与当前请求不一致，已拒绝提交。",
            )
        scope_binding = self._scope_binding(
            request=request,
            plan=plan,
            specialist_result=specialist_result,
            control_plane_feedback=control_plane_feedback,
        )

        base_visible_tools = self._follow_up.visible_tools(request, plan)
        visible_tools = self._recovery_minimal_delegated_visible_tools(
            base_visible_tools=base_visible_tools,
            action_mappings=action_mappings,
        )
        visible_names = tuple(tool.name for tool in visible_tools)

        # 同名任务是一次“草稿保存失败”恢复，不另造 rename 工具。复用平台已有 planner，
        # 它会保留原始已审核配置，重建唯一名称，并重新展开正常的保存/预检/发布/运行生命周期。
        duplicate_plan = self._build_duplicate_name_recovery(
            request=request,
            plan=plan,
            specialist_result=specialist_result,
            action_mappings=action_mappings,
            control_plane_feedback=control_plane_feedback,
            scope_binding=scope_binding,
        )
        if duplicate_plan is not None:
            return duplicate_plan

        recovery_calls: list[ModelToolCall] = []
        mapped_actions: list[tuple[Mapping[str, Any], str, dict[str, Any]]] = []
        fatal_issues: list[SpecialistBridgeIssue] = []
        incomplete_input_issues: list[SpecialistBridgeIssue] = []
        for index, action in enumerate(action_mappings, start=1):
            tool_name = self._map_recovery_action(action)
            if tool_name is None:
                action_label = self._recovery_action_diagnostic_label(action.get("actionType"))
                fatal_issues.append(SpecialistBridgeIssue(
                    "RECOVERY_ACTION_UNSUPPORTED",
                    f"恢复建议动作 {action_label} 尚未映射到受治理工具；系统不会使用通用执行入口猜测如何修改任务或数据。",
                ))
                continue
            if tool_name not in visible_names:
                fatal_issues.append(SpecialistBridgeIssue(
                    "RECOVERY_TOOL_NOT_VISIBLE",
                    f"恢复工具 {tool_name} 不在本次主 Agent 委派的可见工具范围内，已拒绝扩大权限。",
                ))
                continue
            arguments = self._recovery_model_optional_arguments(tool_name, action)
            missing_arguments = self._recovery_missing_model_arguments(tool_name, arguments)
            if missing_arguments:
                incomplete_input_issues.append(SpecialistBridgeIssue(
                    "RECOVERY_ACTION_INPUT_INCOMPLETE",
                    f"恢复建议 {tool_name} 缺少可验证配置：{', '.join(missing_arguments)}；"
                    "该动作未提交，其他完整的只读预览仍可继续。",
                ))
                continue
            call_id = self._recovery_call_id(specialist_result, action, index)
            recovery_calls.append(ModelToolCall(
                call_id=call_id,
                type="specialist_result",
                name=tool_name,
                arguments=json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                raw_call={
                    "source": "specialist_result_bridge",
                    "specialistRole": specialist_result.role.value,
                    "specialistTurnId": specialist_result.turn_id,
                    "recoveryActionId": self._text(action.get("actionId")) or f"recovery-action-{index}",
                },
            ))
            mapped_actions.append((action, tool_name, arguments))

        if fatal_issues:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                visible_tool_names=visible_names,
                issues=tuple(fatal_issues),
                summary="恢复建议没有全部通过确定性工具白名单和权限可见性校验，整批未提交。",
            )
        if not recovery_calls:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.WAITING_FOR_SPECIALIST_INPUT,
                visible_tool_names=visible_names,
                issues=tuple(incomplete_input_issues),
                summary="恢复建议仍缺少结构化配置，尚未创建不可执行的 Java ToolPlan。",
            )

        evidence_issue = self._recovery_diagnosis_evidence_issue(control_plane_feedback)
        if evidence_issue is not None:
            bootstrap = self._build_recovery_diagnosis_bootstrap(
                request=request,
                plan=plan,
                specialist_result=specialist_result,
                scope_binding=scope_binding,
                visible_tools=visible_tools,
                control_plane_feedback=control_plane_feedback,
            )
            if bootstrap is not None:
                return bootstrap
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.WAITING_FOR_CONTROL_PLANE_EVIDENCE,
                visible_tool_names=visible_names,
                issues=(evidence_issue,),
                summary="恢复建议已映射，但还没有真实失败诊断反馈，暂不伪造 diagnosisRef。",
            )

        governed = self._follow_up.govern(
            request=request,
            plan=plan,
            tool_calls=tuple(recovery_calls),
            visible_tools=visible_tools,
            control_plane_feedback=control_plane_feedback,
        )
        accepted = self._mark_specialist_origin(
            governed,
            specialist_result,
            scope_binding=scope_binding,
        )
        if not accepted and governed.repeated_count:
            # 两阶段 Recovery 会把第一阶段的只读诊断反馈带入第二阶段。
            # 如果同一只读预览已经被主 Agent 计划过，重复保护会有意不再生成一份新的
            # ToolPlan；这里复用计划中的同一节点即可继续使用 Java 的原有审计/幂等链路。
            # 只读且无需审批是这个复用分支的硬门槛，重试、重放、清理和改表等动作即使名称相同，
            # 也必须继续停在审批边界，不能因为“看起来已经计划过”而自动放行。
            accepted = self._reuse_safe_recovery_plans(
                plan=plan,
                recovery_calls=tuple(recovery_calls),
                governed=governed,
                specialist_result=specialist_result,
                scope_binding=scope_binding,
            )
        if not accepted or governed.rejected_count or governed.state_guard_issue_codes or governed.budget_issue_codes:
            return self._bridge_without_plans(
                specialist_result,
                status=SpecialistBridgeStatus.REJECTED,
                visible_tool_names=visible_names,
                issues=self._governance_issues(governed),
                summary="恢复建议未通过主 Agent 工具治理，整批未提交。",
            )

        bridge_plan = replace(
            plan,
            state_trace=plan.state_trace + ("recovery_result_to_toolplan",),
            tool_plans=accepted,
            requires_human_approval=any(item.requires_human_approval for item in accepted),
            response_summary="恢复建议已转为受治理 ToolPlan，等待 Java 审批/outbox/worker receipt。",
            next_actions=("等待 Java 控制面返回审批和 worker receipt，再由 MONITOR_AGENT 验证恢复结果。",),
        )
        model_turn = AgentSecondTurnResult(
            executed=False,
            allowed=True,
            action="continue_with_tools",
            summary="RecoveryAction 已按注册工具白名单转换为受治理 ToolPlan。",
            visible_tool_names=visible_names,
            model_tool_call_count=len(recovery_calls),
            follow_up_tool_plans=accepted,
            governance_issue_codes=governed.state_guard_issue_codes,
            governance_issue_messages=governed.state_guard_issue_messages,
            budget_issue_codes=governed.budget_issue_codes,
        )
        handoff = self._build_recovery_handoff(
            specialist_result=specialist_result,
            action_fingerprint=action_fingerprint,
            mapped_actions=mapped_actions,
            scope_binding=scope_binding,
        )
        return SpecialistToolPlanBridgeResult(
            status=SpecialistBridgeStatus.ACCEPTED,
            specialist_role=specialist_result.role,
            specialist_turn_id=specialist_result.turn_id,
            public_summary="恢复建议已通过统一工具治理，交给 Java Durable 控制面处理。",
            accepted_tool_plans=accepted,
            plan=bridge_plan,
            model_turn=model_turn,
            recovery_handoff=handoff,
            visible_tool_names=visible_names,
            issues=tuple(incomplete_input_issues),
            specialist_result_fingerprint=self._result_fingerprint(specialist_result),
            scope_binding=scope_binding,
        )

    def _validate_data_sync_result(
        self,
        result: SpecialistTurnResult,
    ) -> tuple[SpecialistBridgeIssue, ...]:
        """校验同步 specialist 的完成语义和副作用声明。

        ``SpecialistTurnResult`` 来自模型编排层，不能仅因为状态是 COMPLETED 就当成
        可写入的任务配置。这里明确要求它仍然是 draft-only 结果，并拒绝模型声称已经
        保存、发布或执行的字段；真正的副作用只能由后续 Java ToolPlan 生命周期产生。
        """

        if result.role is not AgentSessionRole.DATA_SYNC_AGENT:
            return (SpecialistBridgeIssue(
                "SPECIALIST_RESULT_ROLE_MISMATCH",
                "同步桥接只接受 DATA_SYNC_AGENT 的结果，不能把其他专业 Agent 的输出当成同步配置。",
            ),)
        if result.status is not SpecialistTurnStatus.COMPLETED:
            required = ", ".join(result.required_input_fields) or "专业 Agent 完成状态"
            return (SpecialistBridgeIssue(
                "SPECIALIST_RESULT_NOT_COMPLETED",
                f"同步专业 Agent 尚未完成草案，仍需要：{required}。",
            ),)
        output = result.structured_output
        if not isinstance(output, Mapping):
            return (SpecialistBridgeIssue(
                "SPECIALIST_RESULT_OUTPUT_INVALID",
                "同步专业 Agent 没有返回结构化配置对象。",
            ),)
        if output.get("persisted") is True or output.get("published") is True or output.get("executed") is True:
            return (SpecialistBridgeIssue(
                "SPECIALIST_RESULT_CLAIMS_SIDE_EFFECT",
                "专业 Agent 只能生成草案，不能声称已经保存、发布或执行任务。",
            ),)
        if output.get("draftOnly") is not True:
            return (SpecialistBridgeIssue(
                "SPECIALIST_RESULT_NOT_DRAFT_ONLY",
                "同步专业 Agent 的结果没有明确标记为仅草案，不能进入业务写入链路。",
            ),)
        issue_codes = tuple(
            str(code).strip()
            for code in output.get("validationIssueCodes", ())
            if str(code).strip()
        ) if isinstance(output.get("validationIssueCodes"), (list, tuple, set)) else ()
        if issue_codes:
            return (SpecialistBridgeIssue(
                "SPECIALIST_RESULT_VALIDATION_INCOMPLETE",
                f"同步草案仍有未解决的确定性校验问题：{'、'.join(issue_codes)}。",
            ),)
        if not isinstance(output.get("objectMappings"), list) or not output.get("objectMappings"):
            return (SpecialistBridgeIssue(
                "SPECIALIST_RESULT_OBJECT_MAPPING_MISSING",
                "同步草案没有源表到目标表的对象映射，不能进入任务创建链路。",
            ),)
        return ()

    def _validate_metadata_feedback(
        self,
        feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> tuple[SpecialistBridgeIssue, ...]:
        """要求两端元数据来自带审计身份的成功控制面反馈。"""

        if feedback is None:
            return (SpecialistBridgeIssue(
                "SPECIALIST_METADATA_EVIDENCE_REQUIRED",
                "还没有源端和目标端元数据的 Java 控制面成功事实，不能把专业 Agent 摘要当成真实元数据。",
            ),)
        issues: list[SpecialistBridgeIssue] = []
        for tool_name, label in (
            ("datasource.source.metadata.read", "源端"),
            ("datasource.target.metadata.read", "目标端"),
        ):
            item = self._latest_feedback_item(feedback, tool_name)
            if item is None or self._feedback_status(item) != "succeeded":
                issues.append(SpecialistBridgeIssue(
                    "SPECIALIST_METADATA_EVIDENCE_MISSING",
                    f"缺少{label}元数据读取成功事实，不能提交同步草案。",
                ))
                continue
            if not item.audit_id or not item.run_id:
                issues.append(SpecialistBridgeIssue(
                    "SPECIALIST_METADATA_EVIDENCE_UNBOUND",
                    f"{label}元数据反馈缺少 auditId/runId 绑定，不能进入 Durable ToolPlan。",
                ))
                continue
            summary = item.result.get("summary") if isinstance(item.result, Mapping) else None
            if not isinstance(summary, Mapping) or not isinstance(summary.get("objects"), list):
                issues.append(SpecialistBridgeIssue(
                    "SPECIALIST_METADATA_SUMMARY_MISSING",
                    f"{label}元数据成功反馈没有可供确定性校验的 objects 摘要。",
                ))
        return tuple(issues)

    def _validate_datasource_scope(
        self,
        configuration: Mapping[str, Any],
        feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> tuple[SpecialistBridgeIssue, ...]:
        """校验草案数据源 ID 与控制面回显的真实数据源身份一致。"""

        source_id = self._positive_id(configuration.get("sourceDatasourceId"))
        target_id = self._positive_id(configuration.get("targetDatasourceId"))
        issues: list[SpecialistBridgeIssue] = []
        if source_id is None:
            issues.append(SpecialistBridgeIssue(
                "SPECIALIST_SOURCE_DATASOURCE_ID_MISSING",
                "同步草案缺少有效的源端数据源 ID。",
            ))
        if target_id is None:
            issues.append(SpecialistBridgeIssue(
                "SPECIALIST_TARGET_DATASOURCE_ID_MISSING",
                "同步草案缺少有效的目标端数据源 ID。",
            ))
        if feedback is None:
            return tuple(issues)
        for tool_name, expected, label in (
            ("datasource.source.metadata.read", source_id, "源端"),
            ("datasource.target.metadata.read", target_id, "目标端"),
        ):
            item = self._latest_feedback_item(feedback, tool_name)
            echoed = self._metadata_datasource_id(item.result if item is not None else None)
            if expected is not None and echoed is not None and expected != echoed:
                issues.append(SpecialistBridgeIssue(
                    "SPECIALIST_DATASOURCE_SCOPE_MISMATCH",
                    f"{label}草案数据源 ID 与真实元数据回显不一致，已拒绝防止跨数据源提交。",
                ))
            if expected is not None and echoed is None:
                issues.append(SpecialistBridgeIssue(
                    "SPECIALIST_DATASOURCE_EVIDENCE_ID_MISSING",
                    f"{label}元数据反馈没有回显数据源 ID，不能确认草案选择的真实资源。",
                ))
        return tuple(issues)

    def _draft_arguments(self, output: Mapping[str, Any]) -> dict[str, Any]:
        """只提取同步草案工具注册表允许的根字段，并验证可 JSON 序列化。"""

        tool = next(
            (tool for tool in self._tool_planner.registered_tools() if tool.name == "sync.task.draft.save"),
            None,
        )
        if tool is None:
            raise ValueError("工具注册表缺少 sync.task.draft.save")
        arguments: dict[str, Any] = {}
        for key in tool.input_schema:
            if key not in output or output[key] is None:
                continue
            arguments[key] = self._json_safe(output[key], path=key)
        required_roots = ("taskName", "syncMode", "objectMappings")
        missing = tuple(name for name in required_roots if not arguments.get(name))
        if missing:
            raise ValueError(f"同步草案缺少必填字段：{', '.join(missing)}")
        return arguments

    @classmethod
    def _json_safe(cls, value: Any, *, path: str, depth: int = 0) -> Any:
        """把 specialist 结构化结果复制成有限深度的普通 JSON 值。"""

        if depth > 12:
            raise ValueError(f"字段 {path} 嵌套过深")
        if value is None or isinstance(value, (str, int, float, bool)):
            return value
        if isinstance(value, Mapping):
            if len(value) > 512:
                raise ValueError(f"字段 {path} 的对象成员过多")
            return {
                str(key): cls._json_safe(item, path=f"{path}.{key}", depth=depth + 1)
                for key, item in value.items()
            }
        if isinstance(value, (list, tuple)):
            if len(value) > 512:
                raise ValueError(f"字段 {path} 的数组元素过多")
            return [cls._json_safe(item, path=f"{path}[{index}]", depth=depth + 1) for index, item in enumerate(value)]
        raise ValueError(f"字段 {path} 包含非 JSON 类型")

    @staticmethod
    def _mark_specialist_origin(
        governed: AgentFollowUpToolPlanningResult,
        specialist_result: SpecialistTurnResult,
        scope_binding: Mapping[str, Any] | None = None,
    ) -> tuple[ToolPlan, ...]:
        """给已经通过 follow-up 治理的节点附加来源指纹，不改变治理结论。

        ``scope_binding`` 是主请求的治理上下文快照，不是模型参数。把它复制到每个
        生命周期节点，可以让 Java ingestion、审批、worker receipt 和审计回放在节点
        被拆到不同 Run 后仍然验证同一组租户/应用/项目/用户/委派边界。
        """

        return SpecialistToolPlanBridge._attach_specialist_origin(
            governed.accepted_tool_plans,
            specialist_result,
            scope_binding,
        )

    @staticmethod
    def _attach_specialist_origin(
        tool_plans: Sequence[ToolPlan],
        specialist_result: SpecialistTurnResult,
        scope_binding: Mapping[str, Any] | None = None,
    ) -> tuple[ToolPlan, ...]:
        """给新计划或幂等复用计划补齐 Specialist 来源与作用域快照。

        正常路径的计划来自 ``govern``，Recovery 的两阶段路径则可能复用父计划中已经
        通过治理的只读预览。两种来源都必须带上同样的审计绑定，否则 Java 控制面无法
        判断这个 ToolPlan 属于哪一次专业 Agent 委派，也无法在后续回执中完成范围复核。
        """

        fingerprint = SpecialistToolPlanBridge._result_fingerprint(specialist_result)
        # agentScopeBinding.sessionId identifies the Python Specialist turn and its approval origin;
        # it is not proof that Java agent-runtime already owns a session with the same identifier.
        # Only a session recovered from a trusted Java feedback URI may be sent as
        # ``agentRuntimeSessionId``. On the first Specialist bridge this value is intentionally absent,
        # so Java creates the controlled session and returns the authoritative locator. Reusing the
        # Python-only session here would turn a valid first handoff into a 404 "session not found".
        runtime_session_id = SpecialistToolPlanBridge._text(
            (scope_binding or {}).get("controlPlaneSessionId")
        )
        return tuple(
            replace(
                item,
                governance_hints={
                    **item.governance_hints,
                    # follow-up planner 对同步生命周期扩展已经写入了
                    # platform_sync_lifecycle_expansion 等来源。来源字段是治理事实，不能
                    # 被桥接层覆盖；新增专用字段即可同时保留两种来源。
                    "specialistBridgeSource": "specialist_result_bridge",
                    "specialistAgentRole": specialist_result.role.value,
                    "specialistTurnId": specialist_result.turn_id,
                    "specialistResultFingerprint": fingerprint,
                    **({
                        "agentScopeBinding": dict(scope_binding),
                    } if scope_binding else {}),
                    # The Java output resolver intentionally permits an explicit audit reference only
                    # inside the same Java Agent session. Once feedback supplies that trusted locator,
                    # copy it as a first-class ingestion hint while deliberately omitting the old runId:
                    # lifecycle nodes need a fresh Run but keep reading metadata by sessionId + auditId.
                    **({
                        "agentRuntimeSessionId": runtime_session_id,
                    } if runtime_session_id else {}),
                },
            )
            for item in tool_plans
        )

    def _reuse_safe_recovery_plans(
        self,
        *,
        plan: AgentPlan,
        recovery_calls: tuple[ModelToolCall, ...],
        governed: AgentFollowUpToolPlanningResult,
        specialist_result: SpecialistTurnResult,
        scope_binding: Mapping[str, Any],
    ) -> tuple[ToolPlan, ...]:
        """从父计划复用已提交且无需审批的只读恢复预览节点。

        ``AgentFollowUpToolPlanner`` 的重复保护以整个 Agent 请求为范围，能够阻止模型
        在同一轮无限重复调用工具，但它无法区分“真正的重复副作用”和“第二阶段只是重新
        引用第一阶段已提交的只读预览”。本方法只接受精确 fingerprint 匹配，且再次回查
        注册表的 ``read_only`` 和 ``requires_approval`` 属性。这里复用的是同一个
        ToolPlan 及其 Java 幂等标识，而不是重新创建一次预览；所以即使某类预览会签发
        新 previewRef、注册表没有声明业务幂等，也不会触发第二次执行。任何写操作或审批
        动作都返回空集合，让上层继续 fail-closed。
        """

        repeated = set(governed.repeated_fingerprints)
        if not repeated:
            return ()
        requested_tools = {
            self._text(call.name)
            for call in recovery_calls
            if self._text(call.name)
        }
        registered_by_name = {
            tool.name: tool
            for tool in self._tool_planner.registered_tools()
        }
        reusable: list[ToolPlan] = []
        for item in plan.tool_plans:
            if item.tool_name not in requested_tools:
                continue
            if self._follow_up.fingerprint(item.tool_name, item.arguments) not in repeated:
                continue
            registered = registered_by_name.get(item.tool_name)
            if registered is None or not registered.read_only or registered.requires_approval:
                continue
            reusable.append(item)
        return self._attach_specialist_origin(
            tuple(reusable),
            specialist_result,
            scope_binding,
        )

    @staticmethod
    def _governance_issues(
        governed: AgentFollowUpToolPlanningResult,
    ) -> tuple[SpecialistBridgeIssue, ...]:
        """把 follow-up planner 的低敏治理结论转换为可行动的桥接问题。

        工具参数值不会进入这里。桥接响应只保留稳定错误码和预先编写的人话说明，
        既能让 E2E、前端和运维判断失败发生在哪一层，也避免把 SQL、表名或模型原文
        从 Python 信任边界带到公共响应。
        """

        issues: list[SpecialistBridgeIssue] = []
        for code in governed.budget_issue_codes:
            issues.append(SpecialistBridgeIssue(code, "同步草案超过当前 Agent 工具预算，未提交执行。"))
        intake_messages = {
            "TOOL_ACTION_INTAKE_REPORT_MISSING": "工具入口没有生成治理报告，系统按失败关闭原则停止提交。",
            "MODEL_TOOL_CALL_UNKNOWN_TOOL": "专业 Agent 提出的工具未注册，系统不会猜测或调用未知能力。",
            "MODEL_TOOL_CALL_NOT_EXPOSED": "专业 Agent 提出的工具不在本轮最小委派范围内，系统已拒绝扩大权限。",
            "MODEL_TOOL_CALL_ARGUMENTS_INVALID_JSON": "专业 Agent 提交的工具参数不是合法 JSON，需要重新生成结构化参数。",
            "MODEL_TOOL_CALL_ARGUMENTS_NOT_OBJECT": "专业 Agent 提交的工具参数必须是 JSON 对象，不能使用数组或纯文本。",
            "MODEL_TOOL_CALL_CRITICAL_RISK": "专业 Agent 提出了平台禁止直接受理的关键风险工具，必须改走受控审批能力。",
        }
        for code in governed.intake_issue_codes:
            issues.append(SpecialistBridgeIssue(
                code,
                intake_messages.get(code, "工具调用在进入执行计划前未通过注册、可见性或参数格式校验。"),
            ))
        for code, message in zip(
            governed.state_guard_issue_codes,
            governed.state_guard_issue_messages,
        ):
            issues.append(SpecialistBridgeIssue(code, message))
        if governed.repeated_count:
            repeated_names = "、".join(governed.repeated_tool_names) or "已计划工具"
            issues.append(SpecialistBridgeIssue(
                "SPECIALIST_TOOLPLAN_REPEATED",
                f"{repeated_names} 的相同参数已在本次 Agent 运行中提交过；系统阻止重复执行，"
                "请等待已有回执或基于新反馈重新规划。",
            ))
        if not issues:
            issues.append(SpecialistBridgeIssue(
                "SPECIALIST_TOOLPLAN_GOVERNANCE_REJECTED",
                "同步草案未通过工具 schema、权限、重复或真实状态校验。",
            ))
        return tuple(issues)

    def _recovery_blueprint(self, action: Mapping[str, Any]) -> RecoveryToolPlanBlueprint:
        """从 Recovery 的低敏公开动作构造不执行的 Java 蓝图。"""

        tool_name = self._text(action.get("toolName")) or "recovery.action.apply"
        action_id = self._text(action.get("actionId")) or "recovery-action"
        action_type = self._text(action.get("actionType")) or "RECOVERY_ACTION"
        raw_fields = action.get("argumentFieldNames")
        field_names = tuple(raw_fields) if isinstance(raw_fields, (list, tuple, set)) else ()
        java_plan = action.get("javaToolPlan")
        arguments: Mapping[str, Any] = {}
        if isinstance(java_plan, Mapping) and isinstance(java_plan.get("arguments"), Mapping):
            # 只有显式标记为 Java 生成的 ToolPlan 才能携带参数；Recovery 普通模型输出
            # 不会落入该分支，因此不会把模型原始参数直接当成执行合同。
            if java_plan.get("source") == "JAVA_CONTROL_PLANE":
                arguments = dict(java_plan["arguments"])
        return RecoveryToolPlanBlueprint(
            tool_name=tool_name,
            action_id=action_id,
            action_type=action_type,
            risk_level="HIGH",
            argument_field_names=field_names,
            arguments=arguments,
        )

    def _scope_binding(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        specialist_result: SpecialistTurnResult,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> dict[str, Any]:
        """从可信请求和控制面引用生成一次不可变的 Agent 作用域快照。

        这里刻意不读取专业模型的 ``tenantId``、``projectId`` 或 ``actorId``。模型只能
        解释当前请求，不能决定请求属于谁；租户、项目和用户始终来自主 Agent 请求，应用
        标识只从 gateway 重建的 ``trustedControlPlane`` 读取。session/run/delegation
        则优先使用 Java 已回写的计划提示，没有时按协调器同样的稳定规则生成 delegation。
        """

        trusted = request.variables.get("trustedControlPlane")
        trusted_map = trusted if isinstance(trusted, Mapping) else {}
        trusted_request_context = trusted_map.get("requestContext")
        request_context = (
            trusted_request_context
            if isinstance(trusted_request_context, Mapping)
            else {}
        )
        application_id = self._text(
            trusted_map.get("applicationId")
            or trusted_map.get("application_id")
            or request_context.get("applicationId")
            or request_context.get("application_id")
        )

        inherited_binding = self._existing_scope_binding(plan)
        delegated_binding = (
            specialist_result.delegated_scope_binding
            if isinstance(specialist_result.delegated_scope_binding, Mapping)
            else {}
        )
        # A Java feedback URI is the strongest session evidence. An explicitly propagated
        # ``agentRuntimeSessionId`` is the backward-compatible second source used by an already
        # ingested continuation. The generic ``sessionId`` and delegated Specialist session are not
        # accepted here because they may identify only the Python conversation/analysis boundary.
        control_plane_session_id = self._trusted_feedback_session_id(control_plane_feedback) or self._text(
            request.variables.get("agentRuntimeSessionId")
        )
        control_plane_run_id = None
        if control_plane_feedback is not None:
            control_plane_run_id = next(
                (
                    self._text(item.run_id)
                    for item in reversed(control_plane_feedback.feedback_items)
                    if self._text(item.run_id)
                ),
                None,
            )
        # 当前请求显式携带的运行时标识优先于上一批计划的 scope 快照；否则把一个旧会话
        # 传给新请求时，旧快照会反过来覆盖当前身份，破坏幂等和会话隔离。
        session_id = self._text(delegated_binding.get("sessionId")) or self._plan_hint(
            plan,
            "agentRuntimeSessionId",
            "sessionId",
            "session_id",
        ) or self._text(request.variables.get("agentRuntimeSessionId") or request.variables.get("sessionId")) or self._text(inherited_binding.get("sessionId"))
        if not session_id:
            session_id = control_plane_session_id
        run_id = self._text(delegated_binding.get("runId")) or self._plan_hint(
            plan,
            "agentRuntimeRunId",
            "runId",
            "run_id",
        ) or self._text(request.variables.get("agentRuntimeRunId"))
        if not run_id:
            run_id = self._text(inherited_binding.get("runId"))
        if not run_id:
            run_id = control_plane_run_id
        if not run_id:
            run_id = self._text(request.request_id)

        delegation_id = self._text(delegated_binding.get("delegationId")) or self._plan_hint(plan, "delegationId", "delegation_id") or self._text(
            trusted_map.get("delegationId") or trusted_map.get("delegation_id")
        )
        if not delegation_id and not session_id and not run_id:
            delegation_id = self._text(inherited_binding.get("delegationId"))
        if not delegation_id and session_id and run_id:
            delegation_id = self._derived_delegation_id(
                request=request,
                session_id=session_id,
                run_id=run_id,
                specialist_result=specialist_result,
            )

        return {
            "tenantId": self._text(request.tenant_id),
            "applicationId": application_id,
            "projectId": self._text(request.project_id),
            "actorId": self._text(request.actor_id),
            # Java 的 specialist fact 合同使用 userId；这里明确保留同一业务主体的两个
            # 语义名，避免后续跨服务转换时只剩 agent/actor 而丢失用户归属。
            "userId": self._text(request.actor_id),
            "sessionId": session_id,
            "runId": run_id,
            "delegationId": delegation_id,
            # 这两个字段只定位 Java feedback/outputRef，不参与用户审批来源的 run 绑定。
            # 保存为独立命名可以让 ToolPlan ingestion 使用正确 Java session，同时避免
            # Recovery approvalRequest 因为 Java 新建了另一个 Run 而被误判为跨范围复用。
            "controlPlaneSessionId": control_plane_session_id,
            "controlPlaneRunId": control_plane_run_id,
        }

    @staticmethod
    def _trusted_feedback_session_id(
        feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> str | None:
        """Recover one Java session locator from durable feedback references.

        ``AgentControlPlaneFeedbackItem`` intentionally exposes auditId/runId and
        an opaque output reference rather than duplicating sessionId as a mutable
        field.  DATA_SYNC bridging nevertheless has to keep the lifecycle Run in
        the metadata Run's session because Java resolves explicit outputs by
        ``sessionId + auditId``.  This method therefore accepts only the exact URI
        shape emitted by ``JavaAgentRuntimeToolFeedbackProvider`` and only when all
        matching feedback items agree on one session.  Missing, legacy or mixed
        references return ``None`` and can never broaden the caller's scope.
        """

        if feedback is None:
            return None
        session_ids: set[str] = set()
        for item in feedback.feedback_items:
            output_ref = SpecialistToolPlanBridge._text(item.output_ref)
            if not output_ref:
                continue
            match = _JAVA_OUTPUT_SESSION_PATTERN.fullmatch(output_ref)
            if match is not None:
                session_ids.add(match.group("session"))
        if len(session_ids) != 1:
            return None
        return next(iter(session_ids))

    def _validate_scope_binding(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
        specialist_result: SpecialistTurnResult | None = None,
    ) -> tuple[SpecialistBridgeIssue, ...]:
        """拒绝已存在的控制面绑定与当前请求不一致的桥接。

        ToolPlan 可以跨多个 Java Run 继续传递，因此桥接不能只检查当前请求的字段，
        还要检查计划中已经写入的 scope 快照以及控制面反馈中可能回显的 scope。任何
        已存在的非空绑定只要和当前可信请求不同，就 fail-closed，防止跨租户、跨项目
        或跨用户复用上一轮计划。
        """

        expected = self._scope_binding(
            request=request,
            plan=plan,
            specialist_result=specialist_result or self._scope_probe_result(),
            control_plane_feedback=control_plane_feedback,
        )
        issues: list[SpecialistBridgeIssue] = []
        for name in ("tenantId", "projectId", "actorId", "userId"):
            if not self._text(expected.get(name)):
                issues.append(SpecialistBridgeIssue(
                    "SPECIALIST_SCOPE_BINDING_INCOMPLETE",
                    f"当前请求缺少可信 {name} 绑定，不能创建专业 Agent ToolPlan。",
                ))

        # 不同候选属于不同 ID 域：计划和专业结果继承的是 Agent 委派范围，可以比较完整
        # session/run/delegation；Java feedback 的嵌入结果只比较业务主体范围，它自己的 Run
        # 已在 controlPlaneRunId 中单独记录，不能与专业 Agent Run 直接比较。
        candidates: list[tuple[Mapping[str, Any], tuple[str, ...]]] = []
        identity_names = (
            "tenantId",
            "applicationId",
            "projectId",
            "actorId",
            "userId",
            "sessionId",
            "runId",
            "delegationId",
        )
        principal_names = (
            "tenantId",
            "applicationId",
            "projectId",
            "actorId",
            "userId",
        )
        for item in plan.tool_plans:
            candidate = item.governance_hints.get("agentScopeBinding")
            if isinstance(candidate, Mapping):
                candidates.append((candidate, identity_names))
        if control_plane_feedback is not None:
            for item in control_plane_feedback.feedback_items:
                candidates.extend(
                    (candidate, principal_names)
                    for candidate in self._embedded_scope_bindings(item.result)
                )
        if specialist_result is not None:
            if specialist_result.delegated_scope_binding:
                candidates.append((specialist_result.delegated_scope_binding, identity_names))
            candidates.extend(
                (candidate, identity_names)
                for candidate in self._embedded_scope_bindings(specialist_result.structured_output)
            )

        for candidate, comparable_names in candidates:
            for name in comparable_names:
                actual = self._text(candidate.get(name))
                expected_value = self._text(expected.get(name))
                if actual and actual != expected_value:
                    issues.append(SpecialistBridgeIssue(
                        "SPECIALIST_SCOPE_BINDING_MISMATCH",
                        f"控制面已有 {name} 绑定与当前请求不一致，已拒绝跨范围复用专业结果。",
                    ))
                    break
        return tuple(dict.fromkeys(issues))

    @staticmethod
    def _scope_probe_result() -> SpecialistTurnResult:
        """构造只供绑定计算使用的最小结果，避免校验阶段伪造业务输出。"""

        return SpecialistTurnResult(
            agent_id="scope-validation",
            role=AgentSessionRole.DATA_SYNC_AGENT,
            turn_id="scope-validation",
            status=SpecialistTurnStatus.COMPLETED,
        )

    @staticmethod
    def _embedded_scope_bindings(value: Any) -> tuple[Mapping[str, Any], ...]:
        """从控制面结果中读取明确命名的 scope 容器，不遍历业务数据猜测身份字段。"""

        if not isinstance(value, Mapping):
            return ()
        bindings: list[Mapping[str, Any]] = []
        for key in ("agentScopeBinding", "scopeBinding", "scope", "requestContext", "approvalRequest"):
            candidate = value.get(key)
            if isinstance(candidate, Mapping):
                bindings.append(candidate)
        if any(
            key in value
            for key in (
                "tenantId",
                "applicationId",
                "projectId",
                "actorId",
                "userId",
                "sessionId",
                "runId",
                "delegationId",
            )
        ):
            bindings.append(value)
        return tuple(bindings)

    @staticmethod
    def _existing_scope_binding(plan: AgentPlan) -> Mapping[str, Any]:
        """返回计划中最早的服务端 scope 快照，供继续运行时保持绑定稳定。"""

        for item in plan.tool_plans:
            candidate = item.governance_hints.get("agentScopeBinding")
            if isinstance(candidate, Mapping):
                return candidate
        return {}

    @staticmethod
    def _plan_hint(plan: AgentPlan, *names: str) -> str | None:
        """按计划顺序读取 Java 回写的运行时标识，不读取模型参数作为身份来源。"""

        for item in plan.tool_plans:
            for name in names:
                value = SpecialistToolPlanBridge._text(item.governance_hints.get(name))
                if value:
                    return value
        return None

    @staticmethod
    def _derived_delegation_id(
        *,
        request: AgentRequest,
        session_id: str,
        run_id: str,
        specialist_result: SpecialistTurnResult,
    ) -> str:
        """复用协调器的稳定委派 ID 算法，保证重试同一 turn 不生成新委派。"""

        material = "|".join((
            str(request.tenant_id),
            str(request.project_id),
            str(request.actor_id),
            session_id,
            run_id,
            specialist_result.turn_id,
            specialist_result.role.value,
        ))
        return f"delegation-{hashlib.sha256(material.encode('utf-8')).hexdigest()[:24]}"

    @staticmethod
    def _latest_feedback_item(
        feedback: AgentControlPlaneFeedbackSnapshot,
        tool_name: str,
    ) -> Any | None:
        """按控制面事件顺序读取某工具最新事实，避免旧失败覆盖新成功结果。"""

        return next(
            (item for item in reversed(feedback.feedback_items) if item.tool_name == tool_name),
            None,
        )

    @staticmethod
    def _feedback_status(item: Any) -> str:
        """兼容枚举和边界适配器字符串，统一读取反馈状态。"""

        return str(getattr(getattr(item, "status", None), "value", getattr(item, "status", "")) or "").strip().lower()

    def _bridge_without_plans(
        self,
        result: SpecialistTurnResult,
        *,
        status: SpecialistBridgeStatus,
        issues: tuple[SpecialistBridgeIssue, ...],
        summary: str,
        visible_tool_names: tuple[str, ...] = (),
    ) -> SpecialistToolPlanBridgeResult:
        """构造没有 Durable 前沿的失败/等待结果。"""

        return SpecialistToolPlanBridgeResult(
            status=status,
            specialist_role=result.role,
            specialist_turn_id=result.turn_id,
            public_summary=summary,
            visible_tool_names=visible_tool_names,
            issues=issues,
            specialist_result_fingerprint=self._result_fingerprint(result),
        )

    @staticmethod
    def _rejected(
        result: SpecialistTurnResult,
        *,
        code: str,
        message: str,
    ) -> SpecialistToolPlanBridgeResult:
        """构造角色不被允许生成 ToolPlan 的统一拒绝结果。"""

        return SpecialistToolPlanBridgeResult(
            status=SpecialistBridgeStatus.REJECTED,
            specialist_role=result.role,
            specialist_turn_id=result.turn_id,
            public_summary=message,
            issues=(SpecialistBridgeIssue(code, message),),
            specialist_result_fingerprint=SpecialistToolPlanBridge._result_fingerprint(result),
        )

    @staticmethod
    def _metadata_datasource_id(value: Any) -> int | None:
        """从控制面元数据结果中读取数据源 ID，兼容 root/summary 两种 DTO。"""

        if not isinstance(value, Mapping):
            return None
        candidates: list[Any] = [value.get("datasourceId"), value.get("datasource_id")]
        summary = value.get("summary")
        if isinstance(summary, Mapping):
            candidates.extend((summary.get("datasourceId"), summary.get("datasource_id")))
        for candidate in candidates:
            parsed = SpecialistToolPlanBridge._positive_id(candidate)
            if parsed is not None:
                return parsed
        return None

    @staticmethod
    def _positive_id(value: Any) -> int | None:
        """规范化正整数数据源 ID，拒绝 bool、零、负数和自然语言。"""

        if isinstance(value, bool):
            return None
        try:
            parsed = int(str(value).strip())
        except (TypeError, ValueError):
            return None
        return parsed if parsed > 0 else None

    @staticmethod
    def _specialist_call_id(result: SpecialistTurnResult) -> str:
        """生成稳定的桥接调用 ID，保证 Durable feedback 能回填到本次 turn。"""

        digest = hashlib.sha256(
            f"{result.role.value}|{result.turn_id}".encode("utf-8")
        ).hexdigest()[:24]
        return f"specialist-{digest}"

    @staticmethod
    def _result_fingerprint(result: SpecialistTurnResult) -> str:
        """为专业结果生成去内容化指纹，用于幂等和审计关联。"""

        material = {
            "role": result.role.value,
            "turnId": result.turn_id,
            "status": result.status.value,
            "structuredOutput": result.structured_output,
        }
        encoded = json.dumps(material, ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":"))
        return f"sha256:{hashlib.sha256(encoded.encode('utf-8')).hexdigest()}"

    @staticmethod
    def _text(value: Any) -> str | None:
        """读取非空标量文本，避免把嵌套对象 repr 当成标识符。"""

        if value is None or isinstance(value, (Mapping, list, tuple, set)):
            return None
        text = str(value).strip()
        return text or None


__all__ = [
    "RECOVERY_JAVA_HANDOFF_SCHEMA_VERSION",
    "SPECIALIST_TOOLPLAN_BRIDGE_SCHEMA_VERSION",
    "RecoveryJavaToolPlanHandoff",
    "RecoveryToolPlanBlueprint",
    "SpecialistBridgeIssue",
    "SpecialistBridgeStatus",
    "SpecialistToolPlanBridge",
    "SpecialistToolPlanBridgeResult",
]
