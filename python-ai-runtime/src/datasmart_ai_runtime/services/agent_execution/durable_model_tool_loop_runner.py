"""Run model-selected tool batches through the existing Java durable control plane.

This module deliberately does not execute business tools in Python.  Each model
batch becomes a new Java run in the same Agent session, receives real tool
feedback, and only then may enter another model turn.  Approval or asynchronous
work pauses the loop naturally and is resumed by the existing event/checkpoint
path rather than by an in-process recursive shortcut.
"""

from __future__ import annotations

import hashlib
import logging
import time
from dataclasses import dataclass, replace
from typing import Any, Callable

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import AgentLoopControlState


LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class AgentLoopTurnSummary:
    """Low-sensitive summary of one additional tool run and feedback turn."""

    turn_index: int
    request_id: str
    session_id: str | None
    run_id: str | None
    submitted_tool_names: tuple[str, ...]
    ingestion_succeeded: bool
    feedback_status_counts: dict[str, int]
    loop_action: str | None
    model_executed: bool
    next_tool_names: tuple[str, ...]
    stop_reason: str | None = None

    def to_summary(self) -> dict[str, Any]:
        return {
            "turnIndex": self.turn_index,
            "requestId": self.request_id,
            "sessionId": self.session_id,
            "runId": self.run_id,
            "submittedToolNames": self.submitted_tool_names,
            "ingestionSucceeded": self.ingestion_succeeded,
            "feedbackStatusCounts": dict(self.feedback_status_counts),
            "loopAction": self.loop_action,
            "modelExecuted": self.model_executed,
            "nextToolNames": self.next_tool_names,
            "stopReason": self.stop_reason,
        }


@dataclass(frozen=True)
class AgentDurableModelToolLoopResult:
    """Result returned to plan assembly after bounded durable continuation."""

    turns: tuple[AgentLoopTurnSummary, ...]
    latest_plan: AgentPlan
    latest_feedback: Any | None
    latest_loop_decision: Any | None
    latest_model_turn: Any | None
    stopped_reason: str

    def to_summary(self) -> dict[str, Any]:
        return {
            "turnCount": len(self.turns),
            "turns": tuple(turn.to_summary() for turn in self.turns),
            "stoppedReason": self.stopped_reason,
            "continuesAfterResponse": self.stopped_reason in {
                "WAITING_CONTROL_PLANE",
                "WAITING_APPROVAL",
                "HUMAN_TAKEOVER_REQUIRED",
            },
            "payloadPolicy": "LOW_SENSITIVE_DURABLE_LOOP_SUMMARY_ONLY",
        }


class AgentDurableModelToolLoopRunner:
    """Submit and evaluate a bounded series of model-selected tool batches."""

    def __init__(
        self,
        *,
        plan_ingestion_client: Any,
        feedback_collector: Any,
        loop_control_evaluator: Any,
        second_turn_orchestrator: Any,
        max_model_turns: int = 4,
        estimated_next_turn_tokens: int = 8192,
    ) -> None:
        self._plan_ingestion_client = plan_ingestion_client
        self._feedback_collector = feedback_collector
        self._loop_control_evaluator = loop_control_evaluator
        self._second_turn_orchestrator = second_turn_orchestrator
        self._max_model_turns = max(1, max_model_turns)
        # The second-turn prompt contains visible tool schemas plus accumulated
        # control-plane evidence.  A 1K estimate materially understated the
        # observed 7K-10K prompts and made the 16K policy stop immediately after
        # metadata discovery.  Keep the estimate configurable and conservative
        # so the policy reserves enough budget before starting another provider
        # request instead of discovering the overrun afterwards.
        self._estimated_next_turn_tokens = max(1, estimated_next_turn_tokens)

    def run(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        first_model_turn: Any,
        initial_feedback: AgentControlPlaneFeedbackSnapshot | None = None,
        progress_event_sink: Callable[[Any], None] | None = None,
    ) -> AgentDurableModelToolLoopResult:
        """Continue until the model finishes or a durable gate requires pausing."""

        current_plan = plan
        current_model_turn = first_model_turn
        latest_feedback = None
        latest_decision = None
        turns: list[AgentLoopTurnSummary] = []
        consumed_tokens = self._tokens(first_model_turn)
        started_at = time.perf_counter()
        accumulated_plan = plan if initial_feedback is not None else replace(plan, tool_plans=())
        accumulated_feedback = initial_feedback

        # The first model turn has already consumed one model budget slot.  Every
        # iteration below submits its proposed tools, collects real feedback, then
        # optionally consumes one more model slot.
        for turn_index in range(1, self._max_model_turns):
            next_tools = tuple(getattr(current_model_turn, "follow_up_tool_plans", ()) or ())
            if not next_tools:
                return self._result(
                    turns, current_plan, accumulated_feedback or latest_feedback, latest_decision,
                    current_model_turn, "MODEL_COMPLETED_WITHOUT_MORE_TOOLS",
                )

            continuation_request, continuation_plan = self._continuation(
                request=request,
                parent_plan=current_plan,
                tool_plans=next_tools,
                turn_index=turn_index,
            )
            try:
                ingestion = self._plan_ingestion_client.ingest(
                    continuation_request,
                    continuation_plan,
                    trace_id=continuation_plan.request_id,
                )
                continuation_plan = ingestion.attach_to_plan(continuation_plan)
            except Exception as exc:
                LOGGER.warning(
                    "Agent durable follow-up plan ingestion failed: request_id=%s turn_index=%s error=%s",
                    continuation_plan.request_id,
                    turn_index,
                    str(exc),
                )
                turns.append(
                    AgentLoopTurnSummary(
                        turn_index=turn_index,
                        request_id=continuation_plan.request_id,
                        session_id=self._session_id(current_plan),
                        run_id=None,
                        submitted_tool_names=tuple(item.tool_name for item in next_tools),
                        ingestion_succeeded=False,
                        feedback_status_counts={},
                        loop_action=None,
                        model_executed=False,
                        next_tool_names=(),
                        stop_reason=f"CONTROL_PLANE_INGESTION_FAILED:{type(exc).__name__}",
                    )
                )
                return self._result(
                    turns, current_plan, latest_feedback, latest_decision,
                    current_model_turn, "CONTROL_PLANE_INGESTION_FAILED",
                )

            continuation_plan = self._record_progress(
                request=request,
                plan=continuation_plan,
                turn_index=turn_index,
                step_index=1,
                event_type=AgentRuntimeEventType.TOOL_PLANNED,
                stage="submit_follow_up_tool_batch",
                message="模型选择的下一批工具已提交 Java/MCP Durable 控制面。",
                attributes={
                    "turnIndex": turn_index,
                    "toolNames": tuple(item.tool_name for item in next_tools),
                    "toolCount": len(next_tools),
                    "sessionId": self._session_id(continuation_plan),
                    "runId": self._run_id(continuation_plan),
                },
                sink=progress_event_sink,
            )

            latest_feedback = self._feedback_collector.collect(continuation_plan)
            # Java keeps each batch as an independent run for retry and audit,
            # while the model needs all previously verified evidence in context.
            # This synthetic plan/snapshot is read-only and is never ingested or
            # executed; it only prevents the model from forgetting catalog,
            # connection and per-table metadata results between turns.
            accumulated_plan = self._merge_evidence_plans(
                accumulated_plan,
                continuation_plan,
            )
            accumulated_feedback = self._merge_feedback_snapshots(
                accumulated_plan,
                accumulated_feedback,
                latest_feedback,
            )
            continuation_plan = self._record_progress(
                request=request,
                plan=continuation_plan,
                turn_index=turn_index,
                step_index=2,
                event_type=AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,
                stage="collect_follow_up_tool_feedback",
                message="已收到本轮真实工具执行状态，准备进行循环门禁判断。",
                attributes={
                    "turnIndex": turn_index,
                    "statusCounts": dict(getattr(latest_feedback, "status_counts", {}) or {}),
                    "feedbackCount": len(getattr(latest_feedback, "feedback_items", ()) or ()),
                    "sessionId": self._session_id(continuation_plan),
                    "runId": self._run_id(continuation_plan),
                },
                sink=progress_event_sink,
            )
            latest_decision = self._loop_control_evaluator.evaluate(
                latest_feedback,
                AgentLoopControlState(
                    tool_step_index=turn_index,
                    completed_second_turns=turn_index,
                    consumed_tokens=consumed_tokens,
                    estimated_next_turn_tokens=self._estimated_next_turn_tokens,
                    elapsed_seconds=int(time.perf_counter() - started_at),
                ),
            )
            if not latest_decision.allowed:
                turns.append(
                    self._turn_summary(
                        turn_index,
                        continuation_plan,
                        next_tools,
                        latest_feedback,
                        latest_decision,
                        None,
                        latest_decision.action.value.upper(),
                    )
                )
                return self._result(
                    turns, continuation_plan, accumulated_feedback, latest_decision,
                    None, self._stop_reason(latest_decision.action.value),
                )

            next_model_turn = self._second_turn_orchestrator.run(
                request=continuation_request,
                plan=accumulated_plan,
                control_plane_feedback=accumulated_feedback,
                loop_control_decision=latest_decision,
            )
            if next_model_turn.runtime_events:
                continuation_plan = replace(
                    continuation_plan,
                    runtime_events=continuation_plan.runtime_events + next_model_turn.runtime_events,
                )
                if progress_event_sink is not None:
                    for event in next_model_turn.runtime_events:
                        progress_event_sink(event)
            consumed_tokens += self._tokens(next_model_turn)
            turns.append(
                self._turn_summary(
                    turn_index,
                    continuation_plan,
                    next_tools,
                    latest_feedback,
                    latest_decision,
                    next_model_turn,
                    None,
                )
            )
            current_plan = continuation_plan
            current_model_turn = next_model_turn

        final_tools = tuple(getattr(current_model_turn, "follow_up_tool_plans", ()) or ())
        final_reason = (
            "MODEL_TURN_LIMIT_REACHED"
            if final_tools
            else "MODEL_COMPLETED_WITHOUT_MORE_TOOLS"
        )
        return self._result(
            turns,
            current_plan,
            accumulated_feedback or latest_feedback,
            latest_decision,
            current_model_turn,
            final_reason,
        )

    @classmethod
    def _continuation(
        cls,
        *,
        request: AgentRequest,
        parent_plan: AgentPlan,
        tool_plans: tuple[ToolPlan, ...],
        turn_index: int,
    ) -> tuple[AgentRequest, AgentPlan]:
        parent_session_id = cls._session_id(parent_plan)
        digest = hashlib.sha256(
            f"{parent_plan.request_id}|{turn_index}|{','.join(item.tool_name for item in tool_plans)}".encode("utf-8")
        ).hexdigest()[:24]
        request_id = f"loop-{digest}"
        variables = dict(request.variables)
        if parent_session_id:
            variables["agentRuntimeSessionId"] = parent_session_id
        variables["idempotencyKey"] = f"agent-loop:{digest}"
        variables["agentLoopTurnIndex"] = turn_index
        continuation_request = replace(request, variables=variables, request_id=request_id)
        inherited_hints = cls._inherited_hints(parent_plan)
        normalized_plans = tuple(
            replace(
                item,
                governance_hints={
                    **inherited_hints,
                    **item.governance_hints,
                    "agentLoopTurnIndex": turn_index,
                },
            )
            for item in tool_plans
        )
        continuation_plan = AgentPlan(
            request_id=request_id,
            selected_route=parent_plan.selected_route,
            state_trace=parent_plan.state_trace + ("submit_follow_up_tool_batch",),
            tool_plans=normalized_plans,
            requires_human_approval=any(item.requires_human_approval for item in normalized_plans),
            response_summary="模型基于真实工具结果提出了下一批受治理工具，正在提交同一 Agent 会话继续执行。",
            next_actions=("等待 Java/MCP 控制面执行并回填真实结果。",),
            model_intent_summary=parent_plan.model_intent_summary,
            model_decision_summary=parent_plan.model_decision_summary,
            model_invocation_summary=parent_plan.model_invocation_summary,
            model_interaction_summary=parent_plan.model_interaction_summary,
            context_blocks=parent_plan.context_blocks,
            intent_analysis=parent_plan.intent_analysis,
            model_gateway_decision=parent_plan.model_gateway_decision,
            skill_plan=parent_plan.skill_plan,
            memory_plan=parent_plan.memory_plan,
            memory_retrieval_report=parent_plan.memory_retrieval_report,
            user_profile_context=parent_plan.user_profile_context,
            runtime_events=parent_plan.runtime_events,
            workflow_diagnostics=parent_plan.workflow_diagnostics,
        )
        return continuation_request, continuation_plan

    @classmethod
    def _merge_evidence_plans(
        cls,
        previous: AgentPlan,
        current: AgentPlan,
    ) -> AgentPlan:
        """Build model-only history without changing the current durable run.

        Tool call IDs are the authoritative correlation keys.  A fallback digest
        covers deterministic test plans or legacy plans that predate model call
        IDs.  When the same call appears twice, the latest ToolPlan wins because
        it carries the newest Java audit/run references.
        """

        ordered_keys: list[str] = []
        by_key: dict[str, ToolPlan] = {}
        for item in previous.tool_plans + current.tool_plans:
            call_id = cls._model_tool_call_id(item)
            key = call_id or (
                "legacy:"
                + hashlib.sha256(
                    f"{item.tool_name}|{repr(sorted(item.arguments.items()))}".encode("utf-8")
                ).hexdigest()
            )
            if key not in by_key:
                ordered_keys.append(key)
            by_key[key] = item
        return replace(
            current,
            tool_plans=tuple(by_key[key] for key in ordered_keys),
            requires_human_approval=any(
                by_key[key].requires_human_approval for key in ordered_keys
            ),
        )

    @classmethod
    def _merge_feedback_snapshots(
        cls,
        plan: AgentPlan,
        previous: AgentControlPlaneFeedbackSnapshot | None,
        current: AgentControlPlaneFeedbackSnapshot,
    ) -> AgentControlPlaneFeedbackSnapshot:
        """Merge per-run feedback into a complete model evidence snapshot."""

        item_by_call_id = {
            item.model_tool_call_id: item
            for snapshot in (previous, current)
            if snapshot is not None
            for item in snapshot.feedback_items
        }
        expected_ids = tuple(
            call_id
            for item in plan.tool_plans
            if (call_id := cls._model_tool_call_id(item))
        )
        missing_ids = tuple(call_id for call_id in expected_ids if call_id not in item_by_call_id)
        ordered_items = tuple(
            item_by_call_id[call_id]
            for call_id in expected_ids
            if call_id in item_by_call_id
        )
        status_counts: dict[str, int] = {}
        for item in ordered_items:
            status = item.status.value
            status_counts[status] = status_counts.get(status, 0) + 1

        recommended_actions = tuple(
            dict.fromkeys(
                action
                for snapshot in (previous, current)
                if snapshot is not None
                for action in snapshot.recommended_actions
            )
        )
        second_turn_eligible = (
            bool(expected_ids)
            and not missing_ids
            and status_counts.get("waiting_approval", 0) == 0
            and status_counts.get("pending", 0) == 0
        )
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=len(expected_ids),
            feedback_items=ordered_items,
            missing_tool_call_ids=missing_ids,
            status_counts=status_counts,
            second_turn_eligible=second_turn_eligible,
            recommended_actions=recommended_actions,
            auto_execution_summary=(
                current.auto_execution_summary
                if current.auto_execution_summary is not None
                else (previous.auto_execution_summary if previous is not None else None)
            ),
        )

    @staticmethod
    def _model_tool_call_id(tool_plan: ToolPlan) -> str | None:
        value = tool_plan.governance_hints.get("modelToolCallId")
        return str(value).strip() if value is not None and str(value).strip() else None

    @staticmethod
    def _inherited_hints(plan: AgentPlan) -> dict[str, Any]:
        allowed = {
            "workspaceKey",
            "tenantScoped",
            "projectScoped",
            "agentLoopResourceRefs",
            "agentLoopResourceRefGroups",
            "agentLoopToolFingerprints",
        }
        for item in plan.tool_plans:
            hints = {key: value for key, value in item.governance_hints.items() if key in allowed}
            if hints:
                return hints
        return {}

    @staticmethod
    def _session_id(plan: AgentPlan) -> str | None:
        for item in plan.tool_plans:
            value = item.governance_hints.get("agentRuntimeSessionId")
            if value:
                return str(value)
        return None

    @staticmethod
    def _run_id(plan: AgentPlan) -> str | None:
        for item in plan.tool_plans:
            value = item.governance_hints.get("agentRuntimeRunId")
            if value:
                return str(value)
        return None

    @staticmethod
    def _record_progress(
        *,
        request: AgentRequest,
        plan: AgentPlan,
        turn_index: int,
        step_index: int,
        event_type: AgentRuntimeEventType,
        stage: str,
        message: str,
        attributes: dict[str, Any],
        sink: Callable[[Any], None] | None,
    ) -> AgentPlan:
        event = AgentRuntimeEvent(
            event_type=event_type,
            stage=stage,
            message=message,
            severity=AgentRuntimeEventSeverity.INFO,
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            request_id=plan.request_id,
            run_id=AgentDurableModelToolLoopRunner._run_id(plan),
            session_id=AgentDurableModelToolLoopRunner._session_id(plan),
            sequence=10_000 + turn_index * 10 + step_index,
            attributes=attributes,
        )
        if sink is not None:
            sink(event)
        return replace(plan, runtime_events=plan.runtime_events + (event,))

    @staticmethod
    def _tokens(model_turn: Any | None) -> int:
        if model_turn is None:
            return 0
        return int(getattr(model_turn, "prompt_tokens", 0) or 0) + int(
            getattr(model_turn, "completion_tokens", 0) or 0
        )

    @classmethod
    def _turn_summary(
        cls,
        turn_index: int,
        plan: AgentPlan,
        submitted_tools: tuple[ToolPlan, ...],
        feedback: Any,
        decision: Any,
        model_turn: Any | None,
        stop_reason: str | None,
    ) -> AgentLoopTurnSummary:
        return AgentLoopTurnSummary(
            turn_index=turn_index,
            request_id=plan.request_id,
            session_id=cls._session_id(plan),
            run_id=next(
                (
                    str(item.governance_hints.get("agentRuntimeRunId"))
                    for item in plan.tool_plans
                    if item.governance_hints.get("agentRuntimeRunId")
                ),
                None,
            ),
            submitted_tool_names=tuple(item.tool_name for item in submitted_tools),
            ingestion_succeeded=True,
            feedback_status_counts=dict(getattr(feedback, "status_counts", {}) or {}),
            loop_action=getattr(getattr(decision, "action", None), "value", None),
            model_executed=bool(getattr(model_turn, "executed", False)),
            next_tool_names=tuple(
                item.tool_name
                for item in (getattr(model_turn, "follow_up_tool_plans", ()) or ())
            ),
            stop_reason=stop_reason,
        )

    @staticmethod
    def _stop_reason(action: str) -> str:
        mapping = {
            "wait_for_control_plane": "WAITING_CONTROL_PLANE",
            "wait_for_approval": "WAITING_APPROVAL",
            "require_human_takeover": "HUMAN_TAKEOVER_REQUIRED",
        }
        return mapping.get(action, action.upper())

    @staticmethod
    def _result(
        turns: list[AgentLoopTurnSummary],
        plan: AgentPlan,
        feedback: Any | None,
        decision: Any | None,
        model_turn: Any | None,
        reason: str,
    ) -> AgentDurableModelToolLoopResult:
        return AgentDurableModelToolLoopResult(
            turns=tuple(turns),
            latest_plan=plan,
            latest_feedback=feedback,
            latest_loop_decision=decision,
            latest_model_turn=model_turn,
            stopped_reason=reason,
        )
