"""A2A Task planning decision 到会话级 Agent 调度的防腐层。

Java Agent Runtime 5.28-5.30 已经把 A2A task 的状态机、事件契约和查询预览沉淀为低敏控制面合同；
Python Runtime 5.31-5.32 又把这些合同转换成 `planningDecision`。但如果 `/agent/plans` 的
`agentSessionScheduling` 仍然不知道这份 decision，那么 A2A 委派任务只能停留在旁路预览接口中，
不能真正影响 Master Agent、任务 Agent、权限 Agent 和运维 Agent 的会话编排。

本模块只做一件事：把已经低敏化的 A2A planning decision 转换成“会话调度上下文”。
它不会读取原始 A2A message、prompt、工具参数、SQL、artifact 正文或内部 endpoint，也不会创建、
取消或执行 task。这样既能把 A2A 状态纳入 Agent Host 主链路，又不会把协议适配层变成新的执行入口。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_gateway.session_models import (
    AgentParticipationMode,
    AgentSchedulingStatus,
    AgentSessionRole,
    ScheduledAgentView,
)


_MAX_SAFE_CODES = 16


@dataclass(frozen=True)
class A2aTaskSchedulingContext:
    """A2A task 在会话调度层可消费的低敏上下文。

    字段说明：
    - `available`：请求中是否真的携带 A2A planning decision；没有携带时不影响现有会话调度。
    - `source`：decision 来源。生产更推荐 `TRUSTED_CONTROL_PLANE`，迁移/单测可使用兼容变量。
    - `mode/status`：来自 5.31 适配器的规划语义，用来决定是否等待用户、等待授权、进入预检或诊断。
    - `a2a_state/internal_phase`：低敏状态摘要，帮助前端和 runtime event 解释为什么调度被降级或阻断。
    - `*_count`：只记录 history、artifact、敏感字段丢弃数量，不保存正文或字段名，避免事件成为二次泄漏面。
    """

    available: bool = False
    source: str = "ABSENT"
    mode: str = ""
    status: str = ""
    a2a_state: str = ""
    internal_phase: str = ""
    sequence: int = 0
    terminal: bool = False
    interrupted: bool = False
    executable: bool = False
    should_start_worker: bool = False
    should_wait_for_human: bool = False
    suggested_actions: tuple[str, ...] = ()
    guardrail_codes: tuple[str, ...] = ()
    history_event_count: int = 0
    artifact_reference_count: int = 0
    sensitive_field_ignored_count: int = 0
    payload_policy: str = ""
    task_public_id_present: bool = False
    context_public_id_present: bool = False

    @property
    def scheduling_status(self) -> AgentSchedulingStatus:
        """把 A2A planning mode 映射为会话调度状态。

        这里采用商业化 Agent Host 的保守策略：
        - `WAIT_FOR_AUTHORIZATION` 进入 `APPROVAL_REQUIRED`，因为权限/审批事实尚未满足；
        - `WAIT_FOR_USER_INPUT` 与 `PRECHECK_REQUIRED` 进入 `DEGRADED`，表示可以继续解释或预检，但不能直接执行；
        - `REJECTED_OR_DIAGNOSTIC` 进入 `BLOCKED`，未知或不可信协议状态必须 fail-closed；
        - `WORKER_PLANNING_ALLOWED` 和 `TERMINAL_NO_EXECUTION` 不额外阻断会话，但仍会通过策略轴说明边界。
        """

        if not self.available:
            return AgentSchedulingStatus.READY
        if self.mode == "REJECTED_OR_DIAGNOSTIC":
            return AgentSchedulingStatus.BLOCKED
        if self.mode == "WAIT_FOR_AUTHORIZATION":
            return AgentSchedulingStatus.APPROVAL_REQUIRED
        if self.mode in {"PRECHECK_REQUIRED", "WAIT_FOR_USER_INPUT"}:
            return AgentSchedulingStatus.DEGRADED
        return AgentSchedulingStatus.READY

    @property
    def requires_handoff(self) -> bool:
        """判断当前 A2A task 是否需要用户、权限控制面或运维接管。"""

        return self.mode in {
            "WAIT_FOR_USER_INPUT",
            "WAIT_FOR_AUTHORIZATION",
            "REJECTED_OR_DIAGNOSTIC",
        }

    @property
    def degradation_reasons(self) -> tuple[str, ...]:
        """输出稳定原因码，供 Agent 卡片和推荐动作复用。"""

        if not self.available:
            return ()
        reason_by_mode = {
            "PRECHECK_REQUIRED": "A2A_TASK_PRECHECK_REQUIRED",
            "WAIT_FOR_USER_INPUT": "A2A_TASK_WAITING_FOR_USER_INPUT",
            "WAIT_FOR_AUTHORIZATION": "A2A_TASK_AUTHORIZATION_REQUIRED",
            "REJECTED_OR_DIAGNOSTIC": "A2A_TASK_DIAGNOSTIC_BLOCKED",
        }
        reason = reason_by_mode.get(self.mode)
        return (reason,) if reason else ()

    def participating_agents(self) -> tuple[ScheduledAgentView, ...]:
        """生成 A2A task 需要额外激活的 Agent 视图。

        这里不新增单独的 `A2A_AGENT`，是为了保持当前角色体系稳定：A2A task 本质上仍是任务治理、
        权限治理和运行治理的组合场景。未来如果协议协作变得足够复杂，再新增专门角色会更稳。
        """

        if not self.available:
            return ()
        agents = [
            ScheduledAgentView(
                role=AgentSessionRole.TASK_AGENT,
                display_name="A2A 任务编排 Agent",
                participation_mode=AgentParticipationMode.SPECIALIST,
                activation_reasons=(
                    "本轮会话携带 A2A task planning decision，需要任务 Agent 解释外部委派任务的状态与下一步边界。",
                ),
                governed_domains=("task_management",),
                status=self.scheduling_status,
                degradation_reasons=self.degradation_reasons,
                requires_handoff=self.requires_handoff,
            )
        ]
        if self.mode in {"PRECHECK_REQUIRED", "WAIT_FOR_AUTHORIZATION"}:
            agents.append(
                ScheduledAgentView(
                    role=AgentSessionRole.PERMISSION_AGENT,
                    display_name="A2A 权限与审批 Agent",
                    participation_mode=AgentParticipationMode.GUARDRAIL,
                    activation_reasons=(
                        "A2A task 需要权限预检、审批或授权事实更新，必须由权限治理 Agent 守住控制面边界。",
                    ),
                    governed_domains=("permission_admin",),
                    status=self.scheduling_status,
                    degradation_reasons=self.degradation_reasons,
                    requires_handoff=self.mode == "WAIT_FOR_AUTHORIZATION",
                )
            )
        if self.mode == "REJECTED_OR_DIAGNOSTIC":
            agents.append(
                ScheduledAgentView(
                    role=AgentSessionRole.OPS_AGENT,
                    display_name="A2A 协议诊断 Agent",
                    participation_mode=AgentParticipationMode.OBSERVER,
                    activation_reasons=(
                        "A2A task 状态缺失、未知或不可信，当前必须 fail-closed 并交给运行治理侧诊断。",
                    ),
                    status=AgentSchedulingStatus.BLOCKED,
                    degradation_reasons=self.degradation_reasons,
                    requires_handoff=True,
                )
            )
        return tuple(agents)

    def to_policy_axis(self) -> dict[str, Any]:
        """输出可进入 `agentSessionScheduling.policyAxes` 的低敏策略轴。"""

        return {
            "available": self.available,
            "source": self.source,
            "mode": self.mode,
            "status": self.status,
            "a2aState": self.a2a_state,
            "internalPhase": self.internal_phase,
            "sequence": self.sequence,
            "terminal": self.terminal,
            "interrupted": self.interrupted,
            "executable": self.executable,
            "shouldStartWorker": self.should_start_worker,
            "shouldWaitForHuman": self.should_wait_for_human,
            "suggestedActions": self.suggested_actions,
            "guardrailCodes": self.guardrail_codes,
            "historyEventCount": self.history_event_count,
            "artifactReferenceCount": self.artifact_reference_count,
            "sensitiveFieldIgnoredCount": self.sensitive_field_ignored_count,
            "payloadPolicy": self.payload_policy,
            "taskPublicIdPresent": self.task_public_id_present,
            "contextPublicIdPresent": self.context_public_id_present,
        }

    def recommended_actions(self) -> tuple[str, ...]:
        """根据 A2A task mode 生成会话级下一步建议。"""

        if not self.available:
            return ()
        if self.mode == "PRECHECK_REQUIRED":
            return ("先让 permission-admin、幂等键和租户配额完成 A2A task 执行前预检，再考虑 worker pre-check。",)
        if self.mode == "WORKER_PLANNING_ALLOWED":
            return ("可以规划 worker pre-check 和 task history 查询，但仍不能绕过 outbox、容量保护和 worker receipt。",)
        if self.mode == "WAIT_FOR_USER_INPUT":
            return ("让 Master Agent 生成低敏追问，等待用户补充信息；不要让模型自行猜测数据源、SQL 或导出路径。",)
        if self.mode == "WAIT_FOR_AUTHORIZATION":
            return ("等待 permission-admin、审批单或租户能力包返回新的授权事实，凭证不能进入 A2A 消息正文。",)
        if self.mode == "TERMINAL_NO_EXECUTION":
            return ("A2A task 已是终态；只能展示低敏历史或 artifact 引用，如需重跑应创建新 task 或补偿工单。",)
        return ("A2A task 状态未知或不可信，当前已 fail-closed；请先检查协议版本、Java task fact 或 gateway adapter。",)


def build_a2a_task_scheduling_context(request: AgentRequest) -> A2aTaskSchedulingContext:
    """从 `AgentRequest` 中提取 A2A planning decision，并转换为调度上下文。

    来源优先级：
    1. `variables["trustedControlPlane"]["a2aTaskPlanningDecision"]`：推荐生产形态，应由 gateway/Java 控制面注入；
    2. `variables["a2aTaskPlanningDecision"]`：兼容本地联调和单元测试，但不能被真实执行链路当作授权事实；
    3. `variables["a2aTaskPlanning"]["planningDecision"]`：兼容 5.32 API 响应被整体嵌入的情况。
    """

    decision, source = _extract_decision(request.variables)
    if not isinstance(decision, Mapping):
        return A2aTaskSchedulingContext()
    snapshot = decision.get("snapshot") if isinstance(decision.get("snapshot"), Mapping) else {}
    return A2aTaskSchedulingContext(
        available=True,
        source=source,
        mode=_code(decision.get("mode")),
        status=_code(decision.get("status")),
        a2a_state=_code(snapshot.get("a2aState")),
        internal_phase=_code(snapshot.get("internalPhase")),
        sequence=_non_negative_int(snapshot.get("sequence")),
        terminal=bool(snapshot.get("terminal")),
        interrupted=bool(snapshot.get("interrupted")),
        executable=bool(decision.get("executable")),
        should_start_worker=bool(decision.get("shouldStartWorker")),
        should_wait_for_human=bool(decision.get("shouldWaitForHuman")),
        suggested_actions=_safe_code_tuple(decision.get("suggestedActions")),
        guardrail_codes=_safe_code_tuple(decision.get("guardrails")),
        history_event_count=_non_negative_int(snapshot.get("historyEventCount")),
        artifact_reference_count=_non_negative_int(snapshot.get("artifactReferenceCount")),
        sensitive_field_ignored_count=_non_negative_int(snapshot.get("sensitiveFieldIgnoredCount")),
        payload_policy=_code(snapshot.get("payloadPolicy")),
        task_public_id_present=bool(_text(snapshot.get("taskPublicId"))),
        context_public_id_present=bool(_text(snapshot.get("contextPublicId"))),
    )


def most_restrictive_status(
    left: AgentSchedulingStatus,
    right: AgentSchedulingStatus,
) -> AgentSchedulingStatus:
    """在两个调度状态中选择更保守的一个。"""

    rank = {
        AgentSchedulingStatus.READY: 0,
        AgentSchedulingStatus.DEGRADED: 1,
        AgentSchedulingStatus.APPROVAL_REQUIRED: 2,
        AgentSchedulingStatus.BLOCKED: 3,
    }
    return left if rank[left] >= rank[right] else right


def apply_a2a_task_scheduling_context(
    agents: tuple[ScheduledAgentView, ...],
    context: A2aTaskSchedulingContext,
) -> tuple[ScheduledAgentView, ...]:
    """把 A2A task 调度上下文合并到现有 Agent 列表。

    该函数放在 A2A context 模块中，而不是继续写进 `session_scheduler.py`，是为了避免会话调度器重新变成
    一个承担所有协议细节的大文件。调度器只需要调用一次本函数；A2A 角色去重、状态叠加和原因码追加都在
    这里完成，后续如果接 MCP task、OpenClaw handoff 或其他协议，也可以按同样模式拆独立上下文模块。
    """

    if not context.available:
        return agents
    merged = _merge_agent_views(agents, context.participating_agents())
    return _apply_context_to_relevant_agents(merged, context)


def _merge_agent_views(
    primary_agents: tuple[ScheduledAgentView, ...],
    extra_agents: tuple[ScheduledAgentView, ...],
) -> tuple[ScheduledAgentView, ...]:
    """合并 Agent 列表，并按角色去重。"""

    merged = list(primary_agents)
    existing_roles = {agent.role for agent in merged}
    for agent in extra_agents:
        if agent.role in existing_roles:
            continue
        merged.append(agent)
        existing_roles.add(agent.role)
    return tuple(merged)


def _apply_context_to_relevant_agents(
    agents: tuple[ScheduledAgentView, ...],
    context: A2aTaskSchedulingContext,
) -> tuple[ScheduledAgentView, ...]:
    """把 A2A 状态叠加到 Master/Task/Permission/Ops 等相关 Agent 上。"""

    affected_roles = {AgentSessionRole.MASTER_ORCHESTRATOR, AgentSessionRole.TASK_AGENT}
    if context.mode in {"PRECHECK_REQUIRED", "WAIT_FOR_AUTHORIZATION"}:
        affected_roles.add(AgentSessionRole.PERMISSION_AGENT)
    if context.mode == "REJECTED_OR_DIAGNOSTIC":
        affected_roles.add(AgentSessionRole.OPS_AGENT)
    updated: list[ScheduledAgentView] = []
    for agent in agents:
        if agent.role not in affected_roles:
            updated.append(agent)
            continue
        degradation_reasons = tuple(dict.fromkeys(agent.degradation_reasons + context.degradation_reasons))
        updated.append(
            replace(
                agent,
                status=most_restrictive_status(agent.status, context.scheduling_status),
                degradation_reasons=degradation_reasons,
                requires_handoff=agent.requires_handoff or (context.requires_handoff and agent.role in affected_roles),
            )
        )
    return tuple(updated)


def _extract_decision(variables: Mapping[str, Any]) -> tuple[Mapping[str, Any] | None, str]:
    """按可信优先级从请求变量中提取 planning decision。"""

    trusted = variables.get("trustedControlPlane")
    if isinstance(trusted, Mapping):
        decision = trusted.get("a2aTaskPlanningDecision")
        if isinstance(decision, Mapping):
            return _unwrap_decision(decision), "TRUSTED_CONTROL_PLANE"
        wrapped = trusted.get("a2aTaskPlanning")
        if isinstance(wrapped, Mapping):
            return _unwrap_decision(wrapped), "TRUSTED_CONTROL_PLANE"
    direct = variables.get("a2aTaskPlanningDecision")
    if isinstance(direct, Mapping):
        return _unwrap_decision(direct), "REQUEST_VARIABLES_COMPATIBILITY_PREVIEW"
    wrapped = variables.get("a2aTaskPlanning")
    if isinstance(wrapped, Mapping):
        return _unwrap_decision(wrapped), "REQUEST_VARIABLES_COMPATIBILITY_PREVIEW"
    return None, "ABSENT"


def _unwrap_decision(value: Mapping[str, Any]) -> Mapping[str, Any]:
    """兼容直接 decision 与 5.32 API 响应整体嵌入两种形态。"""

    decision = value.get("planningDecision")
    return decision if isinstance(decision, Mapping) else value


def _safe_code_tuple(value: object | None) -> tuple[str, ...]:
    """读取动作/guardrail code，并限制数量与字符形态。"""

    if value is None:
        return ()
    if isinstance(value, str):
        raw_items: Sequence[object] = (value,)
    elif isinstance(value, (list, tuple, set, frozenset)):
        raw_items = tuple(value)
    else:
        raw_items = (value,)
    codes: list[str] = []
    for item in raw_items:
        code = _code(item)
        if code and code not in codes:
            codes.append(code)
        if len(codes) >= _MAX_SAFE_CODES:
            break
    return tuple(codes)


def _code(value: object | None) -> str:
    """把外部输入归一化为适合事件和策略轴的枚举式字符串。"""

    text = _text(value)
    if not text:
        return ""
    return text.upper().replace("-", "_").replace(" ", "_")[:96]


def _text(value: object | None) -> str:
    """读取非空文本，避免 None 或空白污染响应字段。"""

    if value is None:
        return ""
    return str(value).strip()


def _non_negative_int(value: object | None) -> int:
    """把计数字段转换为非负整数。"""

    if isinstance(value, int):
        return max(0, value)
    if isinstance(value, float):
        return max(0, int(value))
    try:
        return max(0, int(str(value).strip()))
    except (TypeError, ValueError):
        return 0
