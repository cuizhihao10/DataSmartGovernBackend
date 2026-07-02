"""受控多 Agent 执行会话。

本模块承接 `agentCollaborationExecutionPlan` 生成的执行前工作项，把“计划里有哪些 Agent 要参与”
进一步整理成“本轮会话中每个 Agent 当前停在哪个可恢复阶段”。它是从静态执行合同走向真实多 Agent
runtime 的中间层，但仍然刻意保持只读、低敏、无副作用：

- 不执行工具；
- 不调用模型；
- 不写 outbox；
- 不创建审批单；
- 不派发 worker；
- 不修改 Durable Agent Loop checkpoint。

为什么需要这一层：
1. `agentSessionScheduling` 回答“哪些 Agent 应该参与”；
2. `agentCollaborationExecutionPlan` 回答“每个 Agent 的执行前工作项、依赖和 handoff 合同是什么”；
3. 本模块回答“这些工作项进入一次可恢复会话后，各自处于什么状态、下一步恢复动作是什么、哪些角色
   active/standby/deferred”。

这样的设计可以让前端、gateway、Java 控制面和审计系统先看到接近 Codex/Claude Code 风格的多 Agent
执行会话视图，又不会在 Python Runtime 里绕过企业级权限、审批、审计和 worker receipt 边界。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.multi_agent.execution_plan_rules import (
    execution_lane,
    responsibility_for_role,
    string_tuple,
    string_value,
)
from datasmart_ai_runtime.services.multi_agent.product_agent_catalog import runtime_agent_delivery_tiers


SESSION_SCHEMA_VERSION = "datasmart.multi-agent.execution-session.v1"


@dataclass(frozen=True)
class ControlledMultiAgentExecutionWorkItem:
    """一次受控执行会话中的单个 Agent 工作项。

    字段说明：
    - `work_item_id`：低敏、稳定的会话内工作项编号，只由顺序和角色生成，不携带租户、用户或业务 ID；
    - `agent_role`：运行时 Agent 角色，例如 `MASTER_ORCHESTRATOR`、`DATA_QUALITY_AGENT`；
    - `delivery_tier`：当前收敛路线中的交付层级，帮助区分必做、受控范围和轻量化占位角色；
    - `session_status`：会话层状态，融合执行前计划状态与 Durable Loop phase；
    - `resume_action`：下一步恢复动作编码，供 Java 控制面、gateway 或未来 durable runner 读取；
    - `planned_tool_count/visible_skill_count/memory_dependency_count`：只暴露数量，不把工具参数、记忆正文、
      prompt 或模型输出写进会话状态；
    - `side_effect_boundary`：再次声明该工作项不能在 Python 会话层直接产生副作用。
    """

    work_item_id: str
    agent_role: str
    delivery_tier: str
    participation_mode: str
    session_status: str
    resume_action: str
    responsibility: str
    execution_lane: str
    depends_on_roles: tuple[str, ...]
    handoff_required: bool
    planned_tool_count: int
    visible_skill_count: int
    memory_dependency_count: int
    waiting_reason_codes: tuple[str, ...]
    blocked_by: tuple[str, ...]
    durable_phase: str
    source_status: str
    payload_policy: str = "LOW_SENSITIVE_MULTI_AGENT_SESSION_WORK_ITEM_ONLY"

    def to_summary(self) -> dict[str, Any]:
        """输出 API 可安全返回的工作项摘要。"""

        return {
            "workItemId": self.work_item_id,
            "agentRole": self.agent_role,
            "deliveryTier": self.delivery_tier,
            "participationMode": self.participation_mode,
            "sessionStatus": self.session_status,
            "resumeAction": self.resume_action,
            "responsibility": self.responsibility,
            "executionLane": self.execution_lane,
            "dependsOnRoles": self.depends_on_roles,
            "handoffRequired": self.handoff_required,
            "plannedToolCount": self.planned_tool_count,
            "visibleSkillCount": self.visible_skill_count,
            "memoryDependencyCount": self.memory_dependency_count,
            "waitingReasonCodes": self.waiting_reason_codes,
            "blockedBy": self.blocked_by,
            "durablePhase": self.durable_phase,
            "sourceStatus": self.source_status,
            "sideEffectBoundary": _side_effect_boundary(),
            "payloadPolicy": self.payload_policy,
        }


@dataclass(frozen=True)
class ControlledMultiAgentExecutionSession:
    """一次 `/agent/plans` 对应的受控多 Agent 会话摘要。

    会话对象不是线程、进程或远程 Agent 实例；它是稳定的控制面视图。后续如果接入真正的 Agent host，
    可以把这里的 `sessionId/workItems/resumeAction/rosterCoverage` 写入 Java projection 或 Redis/MySQL
    session store，真实执行器再按这些状态申请审批、领取 outbox、续租 worker lease 和回写 receipt。
    """

    session_id: str
    request_id: str
    run_id: str | None
    status: str
    durable_phase: str
    durable_resume_action: str | None
    execution_plan_status: str
    source: str
    work_items: tuple[ControlledMultiAgentExecutionWorkItem, ...]
    roster_coverage: dict[str, Any]
    collaboration_edge_count: int
    handoff_contract_count: int
    next_actions: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """输出低敏会话摘要。

        这里显式列字段而不是 `asdict`，是为了保证未来内部字段不会被自动暴露给 API 调用方。
        """

        return {
            "schemaVersion": SESSION_SCHEMA_VERSION,
            "sessionId": self.session_id,
            "requestId": self.request_id,
            "runId": self.run_id,
            "status": self.status,
            "durablePhase": self.durable_phase,
            "durableResumeAction": self.durable_resume_action,
            "executionPlanStatus": self.execution_plan_status,
            "source": self.source,
            "workItemCount": len(self.work_items),
            "activeRoles": tuple(item.agent_role for item in self.work_items),
            "workItems": tuple(item.to_summary() for item in self.work_items),
            "rosterCoverage": self.roster_coverage,
            "collaborationEdgeCount": self.collaboration_edge_count,
            "handoffContractCount": self.handoff_contract_count,
            "nextActions": self.next_actions,
            "sideEffectBoundary": _side_effect_boundary(),
            "executionBoundary": "CONTROLLED_MULTI_AGENT_SESSION_NO_SIDE_EFFECTS",
            "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_EXECUTION_SESSION_ONLY",
        }


class MultiAgentExecutionSessionService:
    """把多 Agent 执行前计划转换为受控执行会话。

    服务是纯计算对象，不持有连接池、不访问文件系统、不访问数据库。这样它可以安全运行在同步
    `/agent/plans` 响应组装阶段，也可以在单元测试、命令行诊断或未来 Java 回调适配器中复用。
    """

    def build(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        scheduling: Mapping[str, Any],
        collaboration_execution_plan: Mapping[str, Any],
        durable_loop: Mapping[str, Any] | None = None,
    ) -> ControlledMultiAgentExecutionSession:
        """构建受控多 Agent 执行会话。

        输入来源：
        - `scheduling`：智能网关会话调度摘要，作为执行计划缺失或禁用时的 fallback；
        - `collaboration_execution_plan`：LangGraph 执行前计划，优先作为 work item 来源；
        - `durable_loop`：Durable Agent Loop checkpoint 摘要，用于把每个 Agent 工作项对齐到可恢复阶段。
        """

        durable_summary = dict(durable_loop or {})
        raw_items, source = _raw_work_items(collaboration_execution_plan, scheduling)
        if not raw_items:
            raw_items = (_fallback_master_work_item(),)
            source = "fallback_master_only"

        tiers = runtime_agent_delivery_tiers()
        work_items = tuple(
            self._work_item(
                index=index,
                raw_item=raw_item,
                tiers=tiers,
                durable_loop=durable_summary,
                scheduling_status=string_value(scheduling.get("status")) or "UNKNOWN",
            )
            for index, raw_item in enumerate(raw_items)
        )
        roster_coverage = _roster_coverage(work_items, tiers)
        status = _session_status(work_items, collaboration_execution_plan, durable_summary)
        return ControlledMultiAgentExecutionSession(
            session_id=_session_id(plan, durable_summary),
            request_id=plan.request_id,
            run_id=string_value(durable_summary.get("runId")),
            status=status,
            durable_phase=string_value(durable_summary.get("phase")) or "not_recorded",
            durable_resume_action=string_value(durable_summary.get("resumeAction")),
            execution_plan_status=string_value(collaboration_execution_plan.get("planStatus")) or "UNKNOWN",
            source=source,
            work_items=work_items,
            roster_coverage=roster_coverage,
            collaboration_edge_count=_non_negative_int(collaboration_execution_plan.get("collaborationEdgeCount")),
            handoff_contract_count=_non_negative_int(collaboration_execution_plan.get("handoffContractCount")),
            next_actions=_next_actions(status, roster_coverage),
        )

    def _work_item(
        self,
        *,
        index: int,
        raw_item: Mapping[str, Any],
        tiers: Mapping[str, str],
        durable_loop: Mapping[str, Any],
        scheduling_status: str,
    ) -> ControlledMultiAgentExecutionWorkItem:
        """把执行计划或调度视图中的单个 Agent 记录转换成会话工作项。"""

        role = string_value(raw_item.get("agentRole") or raw_item.get("role")) or "UNKNOWN_AGENT"
        mode = string_value(raw_item.get("participationMode")) or "UNKNOWN"
        source_status = string_value(raw_item.get("status")) or scheduling_status
        handoff_required = bool(raw_item.get("handoffRequired") or raw_item.get("requiresHandoff"))
        durable_phase = string_value(durable_loop.get("phase")) or "not_recorded"
        session_status = _work_item_session_status(
            source_status=source_status,
            durable_phase=durable_phase,
            handoff_required=handoff_required,
        )
        return ControlledMultiAgentExecutionWorkItem(
            work_item_id=f"workitem-{index + 1}-{_role_fragment(role)}",
            agent_role=role,
            delivery_tier=tiers.get(role, "runtime_governance"),
            participation_mode=mode,
            session_status=session_status,
            resume_action=_resume_action(session_status, role, mode),
            responsibility=string_value(raw_item.get("responsibility")) or responsibility_for_role(role),
            execution_lane=string_value(raw_item.get("executionLane")) or execution_lane(role, mode),
            depends_on_roles=string_tuple(raw_item.get("dependsOnRoles")),
            handoff_required=handoff_required,
            planned_tool_count=len(string_tuple(raw_item.get("plannedToolNames"))),
            visible_skill_count=len(string_tuple(raw_item.get("visibleSkillCodes"))),
            memory_dependency_count=len(string_tuple(raw_item.get("memoryDependencies"))),
            waiting_reason_codes=_waiting_reason_codes(raw_item, durable_loop, session_status),
            blocked_by=string_tuple(raw_item.get("blockedBy") or raw_item.get("degradationReasons")),
            durable_phase=durable_phase,
            source_status=source_status,
        )


def _raw_work_items(
    collaboration_execution_plan: Mapping[str, Any],
    scheduling: Mapping[str, Any],
) -> tuple[Mapping[str, Any], ...]:
    """优先读取 LangGraph 执行前工作项，缺失时回退到会话调度参与 Agent。"""

    work_items = tuple(
        item
        for item in collaboration_execution_plan.get("workItems", ())
        if isinstance(item, Mapping)
    )
    if work_items:
        return work_items, "langgraph_multi_agent_execution_plan"
    scheduled_agents = tuple(
        item
        for item in scheduling.get("participatingAgents", ())
        if isinstance(item, Mapping)
    )
    if scheduled_agents:
        return scheduled_agents, "agent_session_scheduling_fallback"
    return (), "no_agent_source_available"


def _work_item_session_status(*, source_status: str, durable_phase: str, handoff_required: bool) -> str:
    """融合执行前计划状态与 Durable Loop phase，得到会话层状态。"""

    normalized = source_status.upper()
    phase = durable_phase.lower()
    if normalized.startswith("BLOCKED"):
        return "BLOCKED_WAITING_RECOVERY"
    if handoff_required or "APPROVAL" in normalized or phase == "waiting_approval":
        return "WAITING_APPROVAL_OR_HANDOFF"
    if phase == "waiting_control_plane":
        return "WAITING_CONTROL_PLANE_FEEDBACK"
    if phase == "manual_takeover_required":
        return "WAITING_HUMAN_TAKEOVER"
    if phase == "ready_for_second_turn":
        return "READY_FOR_AGENT_TURN"
    if phase in {"second_turn_completed", "stopped_by_policy"}:
        return "COMPLETED_OR_SUMMARIZED"
    if normalized.startswith("DEGRADED"):
        return "DEGRADED_DRAFT_ONLY"
    if normalized in {"PLANNED_READY", "READY"}:
        return "READY_FOR_CONTROL_PLANE_HANDOFF"
    return "PLANNED_NOT_STARTED"


def _resume_action(session_status: str, role: str, mode: str) -> str:
    """为工作项生成下一步恢复动作编码。"""

    if session_status == "BLOCKED_WAITING_RECOVERY":
        return "WAIT_FOR_RUNTIME_RECOVERY_FACT"
    if session_status == "WAITING_APPROVAL_OR_HANDOFF":
        return "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT"
    if session_status == "WAITING_CONTROL_PLANE_FEEDBACK":
        return "REPLAY_CONTROL_PLANE_EVENTS"
    if session_status == "WAITING_HUMAN_TAKEOVER":
        return "HAND_OFF_TO_HUMAN_OPERATOR"
    if session_status == "READY_FOR_AGENT_TURN":
        return "COORDINATE_SPECIALIST_NEXT_TURN" if role == "MASTER_ORCHESTRATOR" or mode == "PRIMARY" else "PREPARE_SPECIALIST_NEXT_TURN"
    if session_status == "DEGRADED_DRAFT_ONLY":
        return "PREPARE_LOW_SENSITIVE_DRAFT_ONLY"
    if session_status == "READY_FOR_CONTROL_PLANE_HANDOFF":
        return "HANDOFF_TO_JAVA_CONTROL_PLANE"
    return "WAIT_FOR_SESSION_ORCHESTRATOR_REVIEW"


def _waiting_reason_codes(
    raw_item: Mapping[str, Any],
    durable_loop: Mapping[str, Any],
    session_status: str,
) -> tuple[str, ...]:
    """汇总工作项等待原因，只保留稳定 reason code。"""

    reasons = list(string_tuple(raw_item.get("blockedBy") or raw_item.get("degradationReasons")))
    reasons.extend(string_tuple(durable_loop.get("waitingReasonCodes")))
    if not reasons and session_status.startswith("WAITING"):
        reasons.append(session_status)
    return tuple(dict.fromkeys(reasons))


def _session_status(
    work_items: tuple[ControlledMultiAgentExecutionWorkItem, ...],
    collaboration_execution_plan: Mapping[str, Any],
    durable_loop: Mapping[str, Any],
) -> str:
    """计算整轮多 Agent 会话状态。"""

    if not work_items:
        return "SESSION_NOT_STARTED"
    statuses = {item.session_status for item in work_items}
    if "BLOCKED_WAITING_RECOVERY" in statuses:
        return "BLOCKED_WAITING_RECOVERY"
    if "WAITING_APPROVAL_OR_HANDOFF" in statuses:
        return "WAITING_APPROVAL_OR_HANDOFF"
    if "WAITING_HUMAN_TAKEOVER" in statuses:
        return "WAITING_HUMAN_TAKEOVER"
    if "WAITING_CONTROL_PLANE_FEEDBACK" in statuses:
        return "WAITING_CONTROL_PLANE_FEEDBACK"
    if "READY_FOR_AGENT_TURN" in statuses:
        return "READY_FOR_AGENT_TURNS"
    if "DEGRADED_DRAFT_ONLY" in statuses:
        return "DEGRADED_DRAFT_ONLY"
    if statuses == {"READY_FOR_CONTROL_PLANE_HANDOFF"}:
        return "READY_FOR_CONTROL_PLANE_HANDOFF"
    plan_status = string_value(collaboration_execution_plan.get("planStatus")) or ""
    if plan_status == "READY_FOR_CONTROL_PLANE_HANDOFF":
        return "READY_FOR_CONTROL_PLANE_HANDOFF"
    if string_value(durable_loop.get("phase")) in {"plan_created", "stopped_by_policy", "second_turn_completed"}:
        return "COMPLETED_OR_SUMMARIZED"
    return "PLANNED_NOT_STARTED"


def _roster_coverage(
    work_items: tuple[ControlledMultiAgentExecutionWorkItem, ...],
    tiers: Mapping[str, str],
) -> dict[str, Any]:
    """生成 active/standby/deferred 角色覆盖视图。"""

    active_roles = {item.agent_role for item in work_items}
    must_do = tuple(role for role, tier in tiers.items() if tier == "must_do")
    controlled_scope = tuple(role for role, tier in tiers.items() if tier == "controlled_scope")
    lightweight = tuple(role for role, tier in tiers.items() if tier == "lightweight")
    return {
        "activeMustDoRoles": tuple(role for role in must_do if role in active_roles),
        "standbyMustDoRoles": tuple(role for role in must_do if role not in active_roles),
        "activeControlledScopeRoles": tuple(role for role in controlled_scope if role in active_roles),
        "standbyControlledScopeRoles": tuple(role for role in controlled_scope if role not in active_roles),
        "deferredLightweightRoles": lightweight,
        "activeRoleCount": len(active_roles),
        "mustDoRoleCount": len(must_do),
        "activeMustDoRoleCount": sum(1 for role in must_do if role in active_roles),
        "coveragePolicy": "ACTIVATE_BY_INTENT_KEEP_NON_MATCHED_AGENTS_STANDBY",
        "payloadPolicy": "LOW_SENSITIVE_AGENT_ROSTER_COVERAGE_ONLY",
    }


def _next_actions(status: str, roster_coverage: Mapping[str, Any]) -> tuple[str, ...]:
    """根据会话状态生成下一步动作建议。"""

    actions: list[str] = []
    if status == "BLOCKED_WAITING_RECOVERY":
        actions.append("WAIT_FOR_OPS_OR_CONTROL_PLANE_RECOVERY_FACT")
    elif status == "WAITING_APPROVAL_OR_HANDOFF":
        actions.append("WAIT_FOR_PERMISSION_OR_HUMAN_HANDOFF_FACT")
    elif status == "WAITING_CONTROL_PLANE_FEEDBACK":
        actions.append("REPLAY_CONTROL_PLANE_EVENTS_AND_WORKER_RECEIPTS")
    elif status == "READY_FOR_AGENT_TURNS":
        actions.append("RUN_NEXT_CONTROLLED_AGENT_TURN_AFTER_HOST_FACTS")
    elif status == "READY_FOR_CONTROL_PLANE_HANDOFF":
        actions.append("MATERIALIZE_AGENT_WORK_ITEMS_IN_JAVA_CONTROL_PLANE")
    else:
        actions.append("KEEP_SESSION_SUMMARY_AND_WAIT_FOR_NEXT_USER_OR_HOST_EVENT")
    if roster_coverage.get("standbyMustDoRoles"):
        actions.append("ACTIVATE_STANDBY_MUST_DO_AGENTS_ONLY_WHEN_INTENT_OR_HOST_FACT_REQUIRES")
    actions.append("KEEP_PYTHON_RUNTIME_SIDE_EFFECT_FREE")
    return tuple(actions)


def _session_id(plan: AgentPlan, durable_loop: Mapping[str, Any]) -> str:
    """生成稳定会话 ID，优先绑定 durable runId。"""

    base = string_value(durable_loop.get("runId")) or plan.request_id or "unknown"
    return f"multi-agent-session-{_role_fragment(base)[:48]}"


def _fallback_master_work_item() -> dict[str, Any]:
    """异常情况下保底生成主控工作项，避免响应缺少会话骨架。"""

    return {
        "agentRole": "MASTER_ORCHESTRATOR",
        "participationMode": "PRIMARY",
        "status": "PLANNED_NOT_STARTED",
        "handoffRequired": False,
    }


def _side_effect_boundary() -> dict[str, bool | str]:
    """统一副作用边界声明，防止受控会话被误解为执行器。"""

    return {
        "toolExecutedByPython": False,
        "modelCalledByExecutionSession": False,
        "outboxWrittenByPython": False,
        "approvalCreatedByPython": False,
        "workerDispatchedByPython": False,
        "checkpointMutatedByExecutionSession": False,
        "javaControlPlaneRequiredForSideEffects": True,
        "workerReceiptRequiredForSideEffects": True,
        "payloadPolicy": "LOW_SENSITIVE_CONTROL_PLANE_FACTS_ONLY",
    }


def _non_negative_int(value: object | None) -> int:
    """把数量字段转换为非负整数。"""

    if isinstance(value, int):
        return max(0, value)
    if value is None:
        return 0
    try:
        return max(0, int(str(value)))
    except ValueError:
        return 0


def _role_fragment(value: str) -> str:
    """把角色或 runId 片段转换为适合出现在 session/workItem ID 中的字符串。"""

    fragment = "".join(char.lower() if char.isalnum() else "-" for char in value)
    return "-".join(part for part in fragment.split("-") if part) or "unknown"
