"""Agent 请求级执行闭环报告。

本模块不是新的工具执行器，也不是新的工作流引擎。它的职责更像 Codex/Claude Code 类 Agent Host
里的“运行状态面板”：把一次请求已经走过的计划、模型网关、工具计划、readiness、Java 控制面接入、
反馈回放、loop 决策和记忆写入候选等事实汇总成一个低敏闭环视图。

为什么需要这个闭环视图：
- 现有 `/agent/plans` 已经返回很多分散字段，例如 `toolExecutionReadiness`、`controlPlaneIngestion`、
  `controlPlaneFeedback`、`agentLoopControl`、`agentSecondTurn`、`memoryWriteProposal`；
- 如果调用方需要自己拼这些字段，就容易把“READY 工具”误认为“工具已执行”，或把“已写入 Java 审计引用”
  误认为“worker 已完成副作用”；
- 商业化 Agent 平台必须明确每次请求停在什么门禁：等待用户澄清、等待审批、等待预算、等待 Java
  控制面、等待 worker receipt，还是已经可以进入下一轮模型推理。

安全边界：
- 本报告只读取结构化状态、计数和布尔事实；
- 不读取 `ToolPlan.arguments` 的真实值，不返回用户 objective、prompt、SQL、样本数据、模型输出、
  工具结果、凭据、内部 endpoint 或 Java 远端异常正文；
- 所有输出字段都是低敏治理元数据，适合进入 HTTP 响应、runtime event 投影或项目进度诊断。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan
from datasmart_ai_runtime.services.agent_execution.control_plane_handoff import build_control_plane_handoff_summary
from datasmart_ai_runtime.services.tools import ToolExecutionReadinessReport


class AgentExecutionClosurePhase(str, Enum):
    """一次 Agent 请求当前所处的闭环阶段。

    这些阶段不是工具最终状态，而是“请求级执行链路状态”。它帮助我们回答一个产品问题：
    这次 Agent 请求现在应该继续往哪里走？
    """

    TEXT_RESPONSE_OR_TOOL_DISCOVERY = "text_response_or_tool_discovery"
    INTERRUPTED_BEFORE_EXECUTION = "interrupted_before_execution"
    WAITING_HUMAN_OR_DRAFT_REVIEW = "waiting_human_or_draft_review"
    READY_FOR_CONTROL_PLANE_INGESTION = "ready_for_control_plane_ingestion"
    WAITING_CONTROL_PLANE_FEEDBACK = "waiting_control_plane_feedback"
    WAITING_LOOP_POLICY = "waiting_loop_policy"
    AGENT_LOOP_EVALUATED = "agent_loop_evaluated"


class AgentExecutionClosedLoopLevel(str, Enum):
    """闭环成熟度等级。

    等级越高，说明本次请求离“可恢复、可审计、可继续推进”的真实 Agent Host 越近。
    它不是项目整体成熟度，而是单次 `/agent/plans` 响应在运行链路上的证据完整度。
    """

    PRE_EXECUTION_ONLY = "pre_execution_only"
    CONTROL_PLANE_REFERENCED = "control_plane_referenced"
    CONTROL_PLANE_OBSERVED = "control_plane_observed"
    AGENT_LOOP_EVALUATED = "agent_loop_evaluated"


@dataclass(frozen=True)
class AgentExecutionClosureReport:
    """请求级闭环报告。

    字段说明：
    - `closure_phase`：当前请求停留的主阶段；
    - `closed_loop_level`：当前已经拿到的可恢复执行证据等级；
    - `completed_stages`：已完成的稳定阶段名，便于前端/Java projection 渲染时间线；
    - `blocking_gates`：仍在阻断或等待的门禁，全部是机器可读 code；
    - `missing_runtime_evidence`：要进入真实可恢复执行还缺哪些运行时证据；
    - `next_actions`：面向编排器或控制面的下一步动作建议；
    - `counts`：低敏计数，用来快速判断本轮工具计划规模和阻断类型；
    - `side_effect_boundary`：明确 Python 同步响应没有产生真实工具副作用，避免调用方误解。
    """

    closure_phase: AgentExecutionClosurePhase
    closed_loop_level: AgentExecutionClosedLoopLevel
    completed_stages: tuple[str, ...]
    blocking_gates: tuple[str, ...]
    missing_runtime_evidence: tuple[str, ...]
    next_actions: tuple[str, ...]
    counts: Mapping[str, int]
    side_effect_boundary: Mapping[str, Any]
    control_plane_handoff: Mapping[str, Any]

    def to_summary(self) -> dict[str, Any]:
        """转换为 `/agent/plans` 可直接返回的低敏字典。

        这里显式白名单输出字段，而不是 `asdict(self)`，是为了给后续维护留一层保险：即使报告对象
        将来增加了内部诊断字段，也不会自动暴露到 API 响应里。
        """

        return {
            "schemaVersion": "datasmart.python-ai-runtime.agent-execution-closure.v1",
            "snapshotType": "AGENT_EXECUTION_CLOSURE",
            "payloadPolicy": "LOW_SENSITIVE_EXECUTION_METADATA_ONLY",
            "closurePhase": self.closure_phase.value,
            "closedLoopLevel": self.closed_loop_level.value,
            "completedStages": self.completed_stages,
            "blockingGates": self.blocking_gates,
            "missingRuntimeEvidence": self.missing_runtime_evidence,
            "nextActions": self.next_actions,
            "counts": dict(self.counts),
            "sideEffectBoundary": dict(self.side_effect_boundary),
            "controlPlaneHandoff": dict(self.control_plane_handoff),
        }


class AgentExecutionClosureService:
    """构建请求级 Agent 执行闭环报告。

    服务输入 deliberately 使用已经存在的 plan/readiness/控制面摘要对象，而不重新调用任何下游系统。
    这样它可以安全地挂在同步响应组装末尾，也可以被未来 Java projection、离线诊断或测试复用。
    """

    def build(
        self,
        *,
        plan: AgentPlan,
        readiness: ToolExecutionReadinessReport,
        control_plane_ingestion: Any | None = None,
        control_plane_feedback: Any | None = None,
        runtime_event_feedback: Any | None = None,
        loop_control_decision: Any | None = None,
        second_turn_result: Any | None = None,
        memory_write_proposal: Any | None = None,
        command_proposal_templates: Mapping[str, Any] | None = None,
    ) -> AgentExecutionClosureReport:
        """汇总一次 Agent 请求的闭环状态。

        参数说明：
        - `plan`：AgentOrchestrator 输出的计划对象，只读取 state_trace、tool_plans 和模型网关决策存在性；
        - `readiness`：工具执行前准备度报告，决定本轮是否可进入控制面、审批、澄清或预算等待；
        - `control_plane_ingestion`：Java agent-runtime 是否已接收计划并返回审计引用；
        - `control_plane_feedback`：Java 控制面是否已有工具状态反馈快照；
        - `runtime_event_feedback`：是否通过 runtime-event replay 增强了反馈；
        - `loop_control_decision`：是否已有受控 Agent loop 决策；
        - `second_turn_result`：是否执行过受控二轮推理；
        - `memory_write_proposal`：是否生成长期记忆写入候选。
        - `command_proposal_templates`：面向 Java command proposal 的低敏请求模板集合。闭环报告只会读取
          模板 ID、工具名、状态、缺失证据和目标路由等治理字段，不会读取 requestBodyTemplate 里的受控引用，
          更不会读取工具参数值。

        返回值不会携带上述对象的正文，只保留是否存在、计数和下一步 code。
        """

        completed_stages = self._completed_stages(
            plan=plan,
            readiness=readiness,
            control_plane_ingestion=control_plane_ingestion,
            control_plane_feedback=control_plane_feedback,
            runtime_event_feedback=runtime_event_feedback,
            loop_control_decision=loop_control_decision,
            second_turn_result=second_turn_result,
            memory_write_proposal=memory_write_proposal,
        )
        blocking_gates = self._blocking_gates(readiness, control_plane_ingestion, control_plane_feedback, loop_control_decision)
        missing_runtime_evidence = self._missing_runtime_evidence(
            readiness=readiness,
            control_plane_ingestion=control_plane_ingestion,
            control_plane_feedback=control_plane_feedback,
            loop_control_decision=loop_control_decision,
        )
        closure_phase = self._closure_phase(
            readiness=readiness,
            control_plane_ingestion=control_plane_ingestion,
            control_plane_feedback=control_plane_feedback,
            loop_control_decision=loop_control_decision,
        )
        return AgentExecutionClosureReport(
            closure_phase=closure_phase,
            closed_loop_level=self._closed_loop_level(control_plane_ingestion, control_plane_feedback, loop_control_decision),
            completed_stages=completed_stages,
            blocking_gates=blocking_gates,
            missing_runtime_evidence=missing_runtime_evidence,
            next_actions=self._next_actions(readiness, blocking_gates, missing_runtime_evidence),
            counts=self._counts(plan, readiness),
            side_effect_boundary=self._side_effect_boundary(
                control_plane_ingestion=control_plane_ingestion,
                control_plane_feedback=control_plane_feedback,
                runtime_event_feedback=runtime_event_feedback,
            ),
            control_plane_handoff=build_control_plane_handoff_summary(command_proposal_templates),
        )

    @staticmethod
    def _completed_stages(
        *,
        plan: AgentPlan,
        readiness: ToolExecutionReadinessReport,
        control_plane_ingestion: Any | None,
        control_plane_feedback: Any | None,
        runtime_event_feedback: Any | None,
        loop_control_decision: Any | None,
        second_turn_result: Any | None,
        memory_write_proposal: Any | None,
    ) -> tuple[str, ...]:
        """生成已完成阶段列表。

        `state_trace` 来自编排器内部节点，这里把它压缩为更稳定的产品阶段名。这样前端和 Java 投影
        不需要绑定 Python 内部每个节点名称，也能看懂当前请求走到了哪里。
        """

        stages = ["REQUEST_ACCEPTED", "CONTEXT_BUILT"]
        if plan.model_gateway_decision is not None:
            stages.append("MODEL_GATEWAY_DECIDED")
        if "analyze_intent" in plan.state_trace:
            stages.append("INTENT_ANALYZED")
        if plan.skill_plan.available_skill_count or plan.skill_plan.selected_skills or plan.skill_plan.rejected_skills:
            stages.append("SKILL_ADMISSION_EVALUATED")
        if plan.tool_plans:
            stages.append("TOOL_PLAN_CREATED")
        stages.append("TOOL_READINESS_EVALUATED")
        if readiness.total_count == 0:
            stages.append("NO_TOOL_PLAN_CONFIRMED")
        if control_plane_ingestion is not None:
            stages.append("JAVA_CONTROL_PLANE_INGESTED")
        if control_plane_feedback is not None:
            stages.append("CONTROL_PLANE_FEEDBACK_COLLECTED")
        if runtime_event_feedback is not None:
            stages.append("RUNTIME_EVENT_FEEDBACK_REPLAYED")
        if loop_control_decision is not None:
            stages.append("AGENT_LOOP_CONTROL_EVALUATED")
        if second_turn_result is not None:
            stages.append("SECOND_TURN_EVALUATED")
        if memory_write_proposal is not None:
            stages.append("MEMORY_WRITE_PROPOSED")
        return tuple(stages)

    @staticmethod
    def _blocking_gates(
        readiness: ToolExecutionReadinessReport,
        control_plane_ingestion: Any | None,
        control_plane_feedback: Any | None,
        loop_control_decision: Any | None,
    ) -> tuple[str, ...]:
        """根据 readiness 和控制面证据生成等待/阻断门禁。"""

        gates: list[str] = []
        if readiness.total_count == 0:
            gates.append("NO_TOOL_PLAN_OR_TOOL_DISCOVERY_REQUIRED")
        if readiness.blocked_count:
            gates.append("BLOCKED_BEFORE_EXECUTION")
        if readiness.clarification_required_count:
            gates.append("WAITING_USER_CLARIFICATION")
        if readiness.approval_required_count:
            gates.append("WAITING_HUMAN_APPROVAL")
        if readiness.draft_only_count:
            gates.append("WAITING_DRAFT_REVIEW")
        if readiness.throttled_count:
            gates.append("WAITING_TOOL_BUDGET")
        if AgentExecutionClosureService._has_control_plane_candidates(readiness) and control_plane_ingestion is None:
            gates.append("WAITING_JAVA_CONTROL_PLANE_INGESTION")
        if control_plane_ingestion is not None and control_plane_feedback is None:
            gates.append("WAITING_CONTROL_PLANE_FEEDBACK")
        if control_plane_feedback is not None and loop_control_decision is None:
            gates.append("WAITING_AGENT_LOOP_POLICY")
        return tuple(gates)

    @staticmethod
    def _missing_runtime_evidence(
        *,
        readiness: ToolExecutionReadinessReport,
        control_plane_ingestion: Any | None,
        control_plane_feedback: Any | None,
        loop_control_decision: Any | None,
    ) -> tuple[str, ...]:
        """列出进入真实可恢复执行还缺的证据类型。

        这里的“证据”是控制面事实，不是业务 payload。比如 Java plan ingestion、工具反馈快照、
        loop policy 决策都可以作为恢复点；而 prompt、SQL、工具参数值绝不属于本报告需要的证据。
        """

        evidence: list[str] = []
        if AgentExecutionClosureService._has_control_plane_candidates(readiness) and control_plane_ingestion is None:
            evidence.append("JAVA_AGENT_PLAN_INGESTION")
        if control_plane_ingestion is not None and control_plane_feedback is None:
            evidence.append("CONTROL_PLANE_TOOL_FEEDBACK")
        if control_plane_feedback is not None and loop_control_decision is None:
            evidence.append("AGENT_LOOP_CONTROL_DECISION")
        if readiness.queued_async_count:
            evidence.append("WORKER_RECEIPT_AFTER_ASYNC_DISPATCH")
        if readiness.approval_required_count:
            evidence.append("APPROVAL_CONFIRMATION_FACT")
        if readiness.clarification_required_count:
            evidence.append("USER_CLARIFICATION_FACT")
        return tuple(evidence)

    @staticmethod
    def _closure_phase(
        *,
        readiness: ToolExecutionReadinessReport,
        control_plane_ingestion: Any | None,
        control_plane_feedback: Any | None,
        loop_control_decision: Any | None,
    ) -> AgentExecutionClosurePhase:
        """选择最能代表当前请求的闭环阶段。"""

        if readiness.total_count == 0:
            return AgentExecutionClosurePhase.TEXT_RESPONSE_OR_TOOL_DISCOVERY
        if readiness.blocked_count or readiness.clarification_required_count or readiness.throttled_count:
            return AgentExecutionClosurePhase.INTERRUPTED_BEFORE_EXECUTION
        if readiness.approval_required_count or readiness.draft_only_count:
            return AgentExecutionClosurePhase.WAITING_HUMAN_OR_DRAFT_REVIEW
        if AgentExecutionClosureService._has_control_plane_candidates(readiness) and control_plane_ingestion is None:
            return AgentExecutionClosurePhase.READY_FOR_CONTROL_PLANE_INGESTION
        if control_plane_ingestion is not None and control_plane_feedback is None:
            return AgentExecutionClosurePhase.WAITING_CONTROL_PLANE_FEEDBACK
        if control_plane_feedback is not None and loop_control_decision is None:
            return AgentExecutionClosurePhase.WAITING_LOOP_POLICY
        return AgentExecutionClosurePhase.AGENT_LOOP_EVALUATED

    @staticmethod
    def _closed_loop_level(
        control_plane_ingestion: Any | None,
        control_plane_feedback: Any | None,
        loop_control_decision: Any | None,
    ) -> AgentExecutionClosedLoopLevel:
        """根据控制面证据完整度生成闭环等级。"""

        if loop_control_decision is not None:
            return AgentExecutionClosedLoopLevel.AGENT_LOOP_EVALUATED
        if control_plane_feedback is not None:
            return AgentExecutionClosedLoopLevel.CONTROL_PLANE_OBSERVED
        if control_plane_ingestion is not None:
            return AgentExecutionClosedLoopLevel.CONTROL_PLANE_REFERENCED
        return AgentExecutionClosedLoopLevel.PRE_EXECUTION_ONLY

    @staticmethod
    def _next_actions(
        readiness: ToolExecutionReadinessReport,
        blocking_gates: tuple[str, ...],
        missing_runtime_evidence: tuple[str, ...],
    ) -> tuple[str, ...]:
        """把门禁和证据缺口转换成稳定下一步动作。"""

        actions: list[str] = []
        gate_to_action = {
            "NO_TOOL_PLAN_OR_TOOL_DISCOVERY_REQUIRED": "CONTINUE_TEXT_RESPONSE_OR_EXPAND_TOOL_REGISTRY",
            "BLOCKED_BEFORE_EXECUTION": "ESCALATE_TO_OPERATOR_WITH_LOW_SENSITIVE_REASON_CODES",
            "WAITING_USER_CLARIFICATION": "REQUEST_USER_CLARIFICATION",
            "WAITING_HUMAN_APPROVAL": "CREATE_OR_WAIT_APPROVAL",
            "WAITING_DRAFT_REVIEW": "SHOW_DRAFT_FOR_REVIEW",
            "WAITING_TOOL_BUDGET": "WAIT_FOR_TOOL_BUDGET",
            "WAITING_JAVA_CONTROL_PLANE_INGESTION": "SUBMIT_PLAN_TO_JAVA_CONTROL_PLANE",
            "WAITING_CONTROL_PLANE_FEEDBACK": "COLLECT_CONTROL_PLANE_FEEDBACK",
            "WAITING_AGENT_LOOP_POLICY": "EVALUATE_AGENT_LOOP_CONTROL",
        }
        for gate in blocking_gates:
            action = gate_to_action.get(gate)
            if action and action not in actions:
                actions.append(action)
        if readiness.executable_count and "JAVA_AGENT_PLAN_INGESTION" not in missing_runtime_evidence:
            actions.append("REPLAY_RUNTIME_EVENTS_FOR_TOOL_STATUS")
        if not actions:
            actions.append("KEEP_AGENT_RUN_OBSERVABLE_AND_WAIT_FOR_NEXT_EVENT")
        return tuple(actions)

    @staticmethod
    def _counts(plan: AgentPlan, readiness: ToolExecutionReadinessReport) -> Mapping[str, int]:
        """生成低敏计数快照。"""

        return {
            "stateTraceCount": len(plan.state_trace),
            "runtimeEventCount": len(plan.runtime_events),
            "toolPlanCount": readiness.total_count,
            "executableToolCount": readiness.executable_count,
            "approvalRequiredToolCount": readiness.approval_required_count,
            "clarificationRequiredToolCount": readiness.clarification_required_count,
            "draftOnlyToolCount": readiness.draft_only_count,
            "queuedAsyncToolCount": readiness.queued_async_count,
            "throttledToolCount": readiness.throttled_count,
            "blockedToolCount": readiness.blocked_count,
        }

    @staticmethod
    def _side_effect_boundary(
        *,
        control_plane_ingestion: Any | None,
        control_plane_feedback: Any | None,
        runtime_event_feedback: Any | None,
    ) -> Mapping[str, Any]:
        """说明本报告和 Python 同步响应产生了哪些副作用、没产生哪些副作用。"""

        return {
            "pythonRuntimeExecutedTool": False,
            "pythonRuntimeWroteOutbox": False,
            "pythonRuntimeCreatedApproval": False,
            "javaControlPlaneIngested": control_plane_ingestion is not None,
            "controlPlaneFeedbackObserved": control_plane_feedback is not None,
            "runtimeEventReplayUsed": runtime_event_feedback is not None,
            "workerReceiptRequiredForSideEffects": True,
            "meaning": "本快照只说明执行闭环证据是否齐备，不代表工具、outbox、审批或 worker 副作用已经发生。",
        }

    @staticmethod
    def _has_control_plane_candidates(readiness: ToolExecutionReadinessReport) -> bool:
        """判断本轮是否存在应提交 Java 控制面的候选工具计划。"""

        return readiness.executable_count > 0 or readiness.queued_async_count > 0
