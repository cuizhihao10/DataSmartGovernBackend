"""Durable Agent Loop 的可恢复状态模型与内存仓储。

DataSmart 的 Agent 目标是接近 Codex/Claude Code 这类“持续规划、调用工具、读取反馈、继续推理”的体验。
但在企业 ETL/数据同步产品里，Agent loop 不能只是 Python 函数递归：工具执行、审批、outbox、worker
receipt、审计和失败恢复都必须能被外部控制面观察和恢复。因此本模块先落一个轻量但真实的 Durable Loop
基座：

- 记录每次 Agent run 的低敏 checkpoint；
- 根据控制面反馈和 loop policy 判断当前可恢复阶段；
- 暴露下一步 resume action，供 gateway、Java 控制面或未来 LangGraph durable runner 消费；
- 当前只使用内存仓储，生产环境后续可替换 Redis/MySQL，而不改变 `/agent/plans` 响应契约。

重要边界：本模块不执行工具、不创建审批、不写 outbox、不调用模型、不读取 artifact 正文。它只记录状态。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from threading import RLock
from typing import Any, Protocol

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackSnapshot
from datasmart_ai_runtime.services.agent_loop_control_policy import (
    AgentLoopControlAction,
    AgentLoopControlDecision,
)


class DurableAgentLoopPhase(str, Enum):
    """Durable Agent Loop 当前阶段。

    - `PLAN_CREATED`：只生成了计划，还没有 Java 控制面反馈；
    - `WAITING_CONTROL_PLANE`：已提交或等待工具反馈，暂不能继续二轮；
    - `WAITING_APPROVAL`：至少一个工具等待审批，必须停在人工/权限边界；
    - `READY_FOR_SECOND_TURN`：反馈完整且策略允许，可以由受控二轮节点继续；
    - `SECOND_TURN_COMPLETED`：受控二轮已经完成，本轮 loop 暂时闭合；
    - `STOPPED_BY_POLICY`：策略因预算、步数、超时、取消或无工作而停止；
    - `MANUAL_TAKEOVER_REQUIRED`：需要人工接管，不能自动推进。
    """

    PLAN_CREATED = "plan_created"
    WAITING_CONTROL_PLANE = "waiting_control_plane"
    WAITING_APPROVAL = "waiting_approval"
    READY_FOR_SECOND_TURN = "ready_for_second_turn"
    SECOND_TURN_COMPLETED = "second_turn_completed"
    STOPPED_BY_POLICY = "stopped_by_policy"
    MANUAL_TAKEOVER_REQUIRED = "manual_takeover_required"


class DurableAgentLoopResumeAction(str, Enum):
    """下一步恢复动作。

    该动作不是立即执行命令，而是告诉外部 orchestrator 该如何恢复：
    - `WAIT_EVENT_REPLAY`：等待事件或从 replay source 刷新控制面反馈；
    - `WAIT_APPROVAL`：等待审批；
    - `RUN_SECOND_TURN`：可以进入受控二轮模型推理；
    - `STOP_AND_SUMMARIZE`：停止自动推进，保留当前摘要；
    - `HAND_OFF_TO_HUMAN`：交给人类或运维处理。
    """

    WAIT_EVENT_REPLAY = "wait_event_replay"
    WAIT_APPROVAL = "wait_approval"
    RUN_SECOND_TURN = "run_second_turn"
    STOP_AND_SUMMARIZE = "stop_and_summarize"
    HAND_OFF_TO_HUMAN = "hand_off_to_human"


@dataclass(frozen=True)
class DurableAgentLoopCheckpoint:
    """一次 Agent run 的可恢复 checkpoint。

    字段说明：
    - `request_id/run_id/session_id`：与 runtime events 对齐的恢复定位字段；
    - `phase/resume_action`：当前阶段和下一步恢复动作；
    - `tool_plan_count/expected_feedback_count/received_feedback_count`：低敏进度计数；
    - `loop_action`：来自 loop policy 的稳定动作编码；
    - `waiting_reason_codes`：等待或停止原因编码，不包含 prompt、SQL、工具参数或模型输出；
    - `checkpoint_version`：未来迁移 Redis/MySQL 时用于兼容历史 checkpoint；
    - `updated_at`：每次 `/agent/plans` 或后续 replay 刷新时更新时间。
    """

    request_id: str
    run_id: str | None
    session_id: str | None
    tenant_id: str
    project_id: str
    actor_id: str
    phase: DurableAgentLoopPhase
    resume_action: DurableAgentLoopResumeAction
    tool_plan_count: int
    expected_feedback_count: int
    received_feedback_count: int
    loop_action: str | None = None
    waiting_reason_codes: tuple[str, ...] = ()
    second_turn_executed: bool = False
    checkpoint_version: int = 1
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    attributes: dict[str, Any] = field(default_factory=dict)

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 响应摘要。

        该摘要可以安全返回给前端和 gateway，因为它只包含状态、计数和原因编码，不包含工具参数或结果正文。
        """

        return {
            "requestId": self.request_id,
            "runId": self.run_id,
            "sessionId": self.session_id,
            "phase": self.phase.value,
            "resumeAction": self.resume_action.value,
            "toolPlanCount": self.tool_plan_count,
            "expectedFeedbackCount": self.expected_feedback_count,
            "receivedFeedbackCount": self.received_feedback_count,
            "loopAction": self.loop_action,
            "waitingReasonCodes": self.waiting_reason_codes,
            "secondTurnExecuted": self.second_turn_executed,
            "checkpointVersion": self.checkpoint_version,
            "createdAt": self.created_at.isoformat(),
            "updatedAt": self.updated_at.isoformat(),
            "attributes": dict(self.attributes),
            "payloadPolicy": "LOW_SENSITIVE_LOOP_STATE_ONLY",
        }


class DurableAgentLoopStore(Protocol):
    """Durable Agent Loop checkpoint 仓储协议。"""

    def save(self, checkpoint: DurableAgentLoopCheckpoint) -> DurableAgentLoopCheckpoint:
        """保存或覆盖当前 run 的最新 checkpoint。"""

    def get(self, run_id: str) -> DurableAgentLoopCheckpoint | None:
        """按 runId 查询 checkpoint。"""

    def diagnostics(self) -> dict[str, Any]:
        """返回仓储低敏诊断。"""


class InMemoryDurableAgentLoopStore:
    """线程安全内存版 Durable Loop store。

    该实现服务于本地闭环和测试。虽然名字里有 Durable，但真正生产耐久性要靠后续 Redis/MySQL 实现；
    当前模块先把“哪些状态必须被持久化”定成稳定合同。
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self._checkpoints_by_run_id: dict[str, DurableAgentLoopCheckpoint] = {}

    def save(self, checkpoint: DurableAgentLoopCheckpoint) -> DurableAgentLoopCheckpoint:
        """保存 checkpoint。

        runId 缺失时使用 requestId 作为降级键。这样同步 HTTP preview 也能被查询，但生产真实 loop 应尽量
        提供 runId，避免同一次请求的多个恢复阶段无法稳定关联。
        """

        key = checkpoint.run_id or checkpoint.request_id
        with self._lock:
            existing = self._checkpoints_by_run_id.get(key)
            if existing:
                checkpoint = DurableAgentLoopCheckpoint(
                    **{
                        **checkpoint.__dict__,
                        "created_at": existing.created_at,
                        "updated_at": datetime.now(timezone.utc),
                    }
                )
            self._checkpoints_by_run_id[key] = checkpoint
            return checkpoint

    def get(self, run_id: str) -> DurableAgentLoopCheckpoint | None:
        """按 runId 查询 checkpoint。"""

        with self._lock:
            return self._checkpoints_by_run_id.get(run_id)

    def diagnostics(self) -> dict[str, Any]:
        """返回低敏诊断。"""

        with self._lock:
            phase_counts: dict[str, int] = {}
            for checkpoint in self._checkpoints_by_run_id.values():
                phase_counts[checkpoint.phase.value] = phase_counts.get(checkpoint.phase.value, 0) + 1
            return {
                "storeType": "in_memory",
                "checkpointCount": len(self._checkpoints_by_run_id),
                "phaseCounts": dict(sorted(phase_counts.items())),
                "payloadPolicy": "LOW_SENSITIVE_LOOP_STATE_ONLY",
            }


class DurableAgentLoopService:
    """Durable Agent Loop checkpoint 服务。

    服务调用时机通常位于 `/agent/plans` 响应组装后半段：此时已经有 AgentPlan、可选 Java 控制面反馈、
    可选 loop policy 决策和可选二轮结果。服务把这些事实压缩成一个可恢复 checkpoint。
    """

    def __init__(self, store: DurableAgentLoopStore | None = None) -> None:
        self._store = store or InMemoryDurableAgentLoopStore()

    def record(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None = None,
        loop_control_decision: AgentLoopControlDecision | None = None,
        second_turn_result: Any | None = None,
    ) -> DurableAgentLoopCheckpoint:
        """根据当前响应事实记录 Durable Loop checkpoint。

        该方法不要求 Java 控制面已经接入：如果没有 feedback/decision，也会记录 `PLAN_CREATED` 或
        `WAITING_CONTROL_PLANE`，让前端和后续恢复器知道当前链路停在“等待外部事实”的阶段。
        """

        phase, resume_action, reason_codes = self._decide_phase(
            plan=plan,
            control_plane_feedback=control_plane_feedback,
            loop_control_decision=loop_control_decision,
            second_turn_result=second_turn_result,
        )
        checkpoint = DurableAgentLoopCheckpoint(
            request_id=plan.request_id,
            run_id=_run_id_from_plan(plan),
            session_id=_session_id_from_plan(plan),
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            phase=phase,
            resume_action=resume_action,
            tool_plan_count=len(plan.tool_plans),
            expected_feedback_count=_expected_feedback_count(plan, control_plane_feedback),
            received_feedback_count=len(control_plane_feedback.feedback_items) if control_plane_feedback else 0,
            loop_action=loop_control_decision.action.value if loop_control_decision else None,
            waiting_reason_codes=reason_codes,
            second_turn_executed=bool(getattr(second_turn_result, "executed", False)),
            attributes={
                "requiresHumanApproval": plan.requires_human_approval,
                "hasControlPlaneFeedback": control_plane_feedback is not None,
                "hasLoopDecision": loop_control_decision is not None,
            },
        )
        return self._store.save(checkpoint)

    def get(self, run_id: str) -> dict[str, Any]:
        """查询 checkpoint 摘要。"""

        checkpoint = self._store.get(run_id)
        return checkpoint.to_summary() if checkpoint else {"found": False, "runId": run_id}

    def diagnostics(self) -> dict[str, Any]:
        """返回 Durable Loop 诊断。"""

        return {
            "durableLoopContractVersion": "datasmart.agent-loop.checkpoint.v1",
            "store": self._store.diagnostics(),
            "sideEffectBoundary": {
                "toolExecuted": False,
                "approvalCreated": False,
                "outboxWritten": False,
                "modelCalled": False,
            },
        }

    def _decide_phase(
        self,
        *,
        plan: AgentPlan,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
        loop_control_decision: AgentLoopControlDecision | None,
        second_turn_result: Any | None,
    ) -> tuple[DurableAgentLoopPhase, DurableAgentLoopResumeAction, tuple[str, ...]]:
        """把计划、反馈和策略决策映射为可恢复阶段。"""

        if bool(getattr(second_turn_result, "executed", False)):
            return (
                DurableAgentLoopPhase.SECOND_TURN_COMPLETED,
                DurableAgentLoopResumeAction.STOP_AND_SUMMARIZE,
                ("SECOND_TURN_EXECUTED",),
            )
        if loop_control_decision is not None:
            return self._phase_from_loop_decision(loop_control_decision)
        if control_plane_feedback is None:
            if _model_tool_call_count(plan.tool_plans) > 0:
                return (
                    DurableAgentLoopPhase.WAITING_CONTROL_PLANE,
                    DurableAgentLoopResumeAction.WAIT_EVENT_REPLAY,
                    ("CONTROL_PLANE_FEEDBACK_NOT_COLLECTED",),
                )
            return (
                DurableAgentLoopPhase.PLAN_CREATED,
                DurableAgentLoopResumeAction.STOP_AND_SUMMARIZE,
                ("NO_MODEL_TOOL_CALLS",),
            )
        if control_plane_feedback.missing_tool_call_ids:
            return (
                DurableAgentLoopPhase.WAITING_CONTROL_PLANE,
                DurableAgentLoopResumeAction.WAIT_EVENT_REPLAY,
                ("MISSING_TOOL_FEEDBACK",),
            )
        return (
            DurableAgentLoopPhase.PLAN_CREATED,
            DurableAgentLoopResumeAction.STOP_AND_SUMMARIZE,
            ("LOOP_DECISION_NOT_EVALUATED",),
        )

    @staticmethod
    def _phase_from_loop_decision(
        decision: AgentLoopControlDecision,
    ) -> tuple[DurableAgentLoopPhase, DurableAgentLoopResumeAction, tuple[str, ...]]:
        """根据 loop policy 决策选择恢复阶段。"""

        if decision.action == AgentLoopControlAction.ALLOW_SECOND_TURN and decision.allowed:
            return (
                DurableAgentLoopPhase.READY_FOR_SECOND_TURN,
                DurableAgentLoopResumeAction.RUN_SECOND_TURN,
                ("LOOP_POLICY_ALLOWED_SECOND_TURN",),
            )
        if decision.action == AgentLoopControlAction.WAIT_FOR_APPROVAL:
            return (
                DurableAgentLoopPhase.WAITING_APPROVAL,
                DurableAgentLoopResumeAction.WAIT_APPROVAL,
                ("WAITING_APPROVAL",),
            )
        if decision.action == AgentLoopControlAction.WAIT_FOR_CONTROL_PLANE:
            return (
                DurableAgentLoopPhase.WAITING_CONTROL_PLANE,
                DurableAgentLoopResumeAction.WAIT_EVENT_REPLAY,
                ("WAITING_CONTROL_PLANE",),
            )
        if decision.action == AgentLoopControlAction.REQUIRE_HUMAN_TAKEOVER:
            return (
                DurableAgentLoopPhase.MANUAL_TAKEOVER_REQUIRED,
                DurableAgentLoopResumeAction.HAND_OFF_TO_HUMAN,
                ("HUMAN_TAKEOVER_REQUIRED",),
            )
        return (
            DurableAgentLoopPhase.STOPPED_BY_POLICY,
            DurableAgentLoopResumeAction.STOP_AND_SUMMARIZE,
            (decision.action.value.upper(),),
        )


def _run_id_from_plan(plan: AgentPlan) -> str | None:
    """从 runtime events 中解析 runId。"""

    return next((event.run_id for event in plan.runtime_events if event.run_id), None)


def _session_id_from_plan(plan: AgentPlan) -> str | None:
    """从 runtime events 中解析 sessionId。"""

    return next((event.session_id for event in plan.runtime_events if event.session_id), None)


def _expected_feedback_count(
    plan: AgentPlan,
    feedback: AgentControlPlaneFeedbackSnapshot | None,
) -> int:
    """计算理论需要等待多少条工具反馈。"""

    if feedback is not None:
        return feedback.expected_tool_call_count
    return _model_tool_call_count(plan.tool_plans)


def _model_tool_call_count(tool_plans: tuple[ToolPlan, ...]) -> int:
    """统计携带模型 tool_call_id 的工具计划数量。"""

    return sum(1 for item in tool_plans if item.governance_hints.get("modelToolCallId"))
