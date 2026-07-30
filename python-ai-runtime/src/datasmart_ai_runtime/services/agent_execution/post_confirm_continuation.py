"""Resume the model/tool loop after a Java-confirmed synchronous tool batch.

The original planning HTTP request has already returned when a user confirms a
Java Run.  This coordinator rebuilds the minimum governed planning envelope from
the durable Java tool results, feeds those results back to the model, and reuses
the existing bounded durable runner for every subsequent tool batch.

Only Java remains allowed to execute business tools.  Read-only/idempotent tools
may be auto-executed by the existing Java policy endpoint; state-changing tools
are ingested as a new approval-gated Run and stop the loop for one user
confirmation.
"""

from __future__ import annotations

import hashlib
from collections import Counter
from dataclasses import dataclass, replace
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ToolExecutionMode,
    ToolParameterValidationResult,
    ToolPlan,
    ToolRiskLevel,
    WorkloadType,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_execution.durable_model_tool_loop_runner import (
    AgentDurableModelToolLoopRunner,
)
from datasmart_ai_runtime.services.agent_execution.duplicate_task_name_recovery import (
    DuplicateTaskNameRecoveryPlan,
    DuplicateTaskNameRecoveryPlanner,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import (
    AgentLoopControlPolicyEvaluator,
    AgentLoopControlState,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnOrchestrator
from datasmart_ai_runtime.services.intent_analyzer import RuleBasedIntentAnalyzer
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


POST_CONFIRM_CONTINUATION_SCHEMA_VERSION = "datasmart.post-confirm-continuation.v1"


@dataclass(frozen=True)
class AgentPostConfirmContinuationResult:
    """Low-sensitive result returned to Java after one event-driven resume."""

    request_id: str
    session_id: str
    source_run_id: str
    model_turn: Any
    durable_loop: Any | None
    repair_plan: DuplicateTaskNameRecoveryPlan | None = None

    def to_summary(self) -> dict[str, Any]:
        latest_plan = self.durable_loop.latest_plan if self.durable_loop is not None else None
        next_run_id = _plan_hint(latest_plan, "agentRuntimeRunId")
        stopped_reason = (
            self.durable_loop.stopped_reason
            if self.durable_loop is not None
            else "MODEL_COMPLETED_WITHOUT_MORE_TOOLS"
        )
        requires_confirmation = stopped_reason in {
            "WAITING_APPROVAL",
            "HUMAN_TAKEOVER_REQUIRED",
        }
        return {
            "schemaVersion": POST_CONFIRM_CONTINUATION_SCHEMA_VERSION,
            "status": "WAITING_CONFIRMATION" if requires_confirmation else "CONTINUED",
            "continued": True,
            "requestId": self.request_id,
            "sessionId": self.session_id,
            "sourceRunId": self.source_run_id,
            "nextRunId": next_run_id,
            "requiresConfirmation": requires_confirmation,
            "stoppedReason": stopped_reason,
            "assistantReply": self.model_turn.summary,
            "modelSecondTurn": self.model_turn.to_summary(),
            "durableLoop": self.durable_loop.to_summary() if self.durable_loop is not None else None,
            "repairProposal": (
                self.repair_plan.proposal.to_summary()
                if self.repair_plan is not None
                else None
            ),
            "payloadPolicy": "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
        }


class AgentPostConfirmContinuationCoordinator:
    """Turn confirmed Java tool outputs into the next governed Agent frontier."""

    def __init__(
        self,
        *,
        model_routes: ModelRouteRegistry,
        second_turn_orchestrator: AgentSecondTurnOrchestrator,
        loop_control_evaluator: AgentLoopControlPolicyEvaluator,
        durable_loop_runner: AgentDurableModelToolLoopRunner,
        tool_planner: ToolPlanner | None = None,
        intent_analyzer: RuleBasedIntentAnalyzer | None = None,
    ) -> None:
        self._model_routes = model_routes
        self._second_turn_orchestrator = second_turn_orchestrator
        self._loop_control_evaluator = loop_control_evaluator
        self._durable_loop_runner = durable_loop_runner
        self._duplicate_name_recovery = (
            DuplicateTaskNameRecoveryPlanner(tool_planner)
            if tool_planner is not None
            else None
        )
        self._intent_analyzer = intent_analyzer or RuleBasedIntentAnalyzer()

    def continue_after_confirmed_tools(
        self,
        payload: Mapping[str, Any],
    ) -> AgentPostConfirmContinuationResult:
        """Resume one same-session model loop from complete Java tool results."""

        session_id = _required_text(payload, "sessionId")
        source_run_id = _required_text(payload, "runId")
        tenant_id = _required_text(payload, "tenantId")
        project_id = _required_text(payload, "projectId")
        actor_id = _required_text(payload, "actorId")
        objective = _required_text(payload, "objective")
        workspace_key = _text(payload.get("workspaceKey")) or f"tenant:{tenant_id}:project:{project_id}"
        request_id = _text(payload.get("traceId")) or _continuation_request_id(session_id, source_run_id)

        request = AgentRequest(
            tenant_id=tenant_id,
            project_id=project_id,
            actor_id=actor_id,
            objective=objective,
            variables={
                "agentRuntimeSessionId": session_id,
                "agentRuntimeSourceRunId": source_run_id,
                "traceId": request_id,
                "workspaceKey": workspace_key,
                "postConfirmContinuation": True,
            },
            preferred_workload=WorkloadType.AGENT_REASONING,
            request_id=request_id,
        )
        tool_plans, feedback_items = self._confirmed_batch(
            payload.get("toolResults"),
            session_id=session_id,
            source_run_id=source_run_id,
            workspace_key=workspace_key,
        )
        failed_tool_names = tuple(
            item.tool_name
            for item in feedback_items
            if item.status is ToolExecutionFeedbackStatus.FAILED
        )
        if failed_tool_names:
            request.variables["failureRecoveryContinuation"] = True
            request.variables["failedToolNames"] = failed_tool_names
        repair_plan = (
            self._duplicate_name_recovery.build(
                source_run_id=source_run_id,
                tool_plans=tool_plans,
                feedback_items=feedback_items,
            )
            if self._duplicate_name_recovery is not None
            else None
        )
        if repair_plan is not None:
            request.variables["failureRecoveryKind"] = "DUPLICATE_TASK_NAME"
            request.variables["repairProposal"] = repair_plan.proposal.to_summary()
        snapshot = _feedback_snapshot(feedback_items)
        intent = self._intent_analyzer.analyze(request, ())
        plan = AgentPlan(
            request_id=request_id,
            selected_route=self._model_routes.route_for(WorkloadType.AGENT_REASONING),
            state_trace=("java_tools_confirmed", "resume_model_tool_loop"),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary=(
                "已收到真实工具失败事实，正在分析根因并选择最少的安全诊断动作。"
                if failed_tool_names
                else "用户确认的工具批次已成功，正在基于真实结果继续完成原始目标。"
            ),
            next_actions=((
                "先解释具体失败原因并执行安全只读诊断；任何修复写操作重新展示并等待用户确认。"
            ) if failed_tool_names else (
                "继续自动收集安全只读证据，最终写操作统一等待用户确认。"
            ),),
            intent_analysis=intent,
        )
        decision = self._loop_control_evaluator.evaluate(
            snapshot,
            AgentLoopControlState(
                tool_step_index=1,
                completed_second_turns=0,
                consumed_tokens=0,
                estimated_next_turn_tokens=8192,
                elapsed_seconds=0,
            ),
        )
        model_turn = self._second_turn_orchestrator.run(
            request=request,
            plan=plan,
            control_plane_feedback=snapshot,
            loop_control_decision=decision,
        )
        if repair_plan is not None:
            public_summary = repair_plan.proposal.to_summary()["summary"]
            model_turn = replace(
                model_turn,
                action="await_repair_confirmation",
                summary=str(public_summary),
                recommended_actions=(
                    "核对原任务名称和建议名称；确认后才会重新保存并提交任务。",
                ),
                follow_up_tool_plans=repair_plan.tool_plans,
            )
        durable_loop = None
        if model_turn.follow_up_tool_plans:
            # Supplying the initial feedback is essential: source and target
            # catalog results often share one Run and must remain in the resource
            # ledger when later metadata reads use separate Runs.
            durable_loop = self._durable_loop_runner.run(
                request=request,
                plan=plan,
                first_model_turn=model_turn,
                initial_feedback=snapshot,
            )
        return AgentPostConfirmContinuationResult(
            request_id=request_id,
            session_id=session_id,
            source_run_id=source_run_id,
            model_turn=model_turn,
            durable_loop=durable_loop,
            repair_plan=repair_plan,
        )

    @staticmethod
    def _confirmed_batch(
        raw_results: Any,
        *,
        session_id: str,
        source_run_id: str,
        workspace_key: str,
    ) -> tuple[tuple[ToolPlan, ...], tuple[AgentControlPlaneFeedbackItem, ...]]:
        if not isinstance(raw_results, list) or not raw_results:
            raise ValueError("Post-confirm continuation requires at least one tool result.")
        plans: list[ToolPlan] = []
        feedback: list[AgentControlPlaneFeedbackItem] = []
        for index, raw_result in enumerate(raw_results, start=1):
            if not isinstance(raw_result, Mapping):
                raise ValueError(f"toolResults[{index}] must be an object.")
            audit = raw_result.get("audit")
            if not isinstance(audit, Mapping):
                raise ValueError(f"toolResults[{index}].audit must be an object.")
            state = (_text(audit.get("state")) or "").upper()
            feedback_status = _terminal_feedback_status(state)
            if feedback_status is None:
                raise ValueError(
                    f"toolResults[{index}] must be a terminal Java tool result, got {state or 'EMPTY'}."
                )
            tool_name = _required_text(audit, "toolCode")
            audit_id = _required_text(audit, "auditId")
            run_id = _text(audit.get("runId")) or source_run_id
            hints = dict(audit.get("governanceHints") or {})
            call_id = _text(hints.get("modelToolCallId")) or f"confirmed-{audit_id}"
            result = raw_result.get("output")
            safe_result = dict(result) if isinstance(result, Mapping) else {}
            output_ref = f"agent-runtime://sessions/{session_id}/runs/{run_id}/tool-executions/{audit_id}/result"
            output_context_policy = (
                _text(hints.get("outputContextPolicy")) or "model_summary_allowed"
            )
            common_hints = {
                **hints,
                "modelToolCallId": call_id,
                "agentRuntimeSessionId": session_id,
                "agentRuntimeRunId": run_id,
                "agentRuntimeAuditId": audit_id,
                "workspaceKey": workspace_key,
                "postConfirmContinuation": True,
            }
            execution_succeeded = feedback_status is ToolExecutionFeedbackStatus.SUCCEEDED
            plans.append(
                ToolPlan(
                    tool_name=tool_name,
                    reason=(
                        _text(audit.get("planReason"))
                        or ("Java 控制面已确认并成功执行该工具。" if execution_succeeded
                            else "Java 控制面已确认执行该工具，但工具返回了终态失败事实。")
                    ),
                    arguments=dict(audit.get("planArguments") or {}),
                    risk_level=_risk_level(audit.get("riskLevel")),
                    execution_mode=_execution_mode(audit.get("executionMode")),
                    requires_human_approval=False,
                    parameter_validation=ToolParameterValidationResult(can_execute=True),
                    governance_hints=common_hints,
                )
            )
            feedback.append(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id=call_id,
                    tool_name=tool_name,
                    status=feedback_status,
                    summary=(
                        _text(audit.get("message"))
                        or _text(audit.get("outputSummary"))
                        or (f"`{tool_name}` 已成功执行。" if execution_succeeded
                            else f"`{tool_name}` 执行失败。")
                    ),
                    result=safe_result,
                    error_code=None if execution_succeeded else _text(audit.get("errorCode")),
                    error_message=None if execution_succeeded else _text(audit.get("message")),
                    audit_id=audit_id,
                    run_id=run_id,
                    output_ref=output_ref,
                    output_workspace_key=_text(hints.get("outputWorkspaceKey")) or workspace_key,
                    output_context_policy=output_context_policy,
                    sensitive_fields=_string_tuple(hints.get("sensitiveFields")),
                    model_context_include_paths=_string_tuple(hints.get("modelContextIncludePaths")),
                    model_context_exclude_paths=_string_tuple(hints.get("modelContextExcludePaths")),
                    sensitive_result_paths=_string_tuple(hints.get("sensitiveResultPaths")),
                )
            )
        return tuple(plans), tuple(feedback)


def _feedback_snapshot(
    items: tuple[AgentControlPlaneFeedbackItem, ...],
) -> AgentControlPlaneFeedbackSnapshot:
    status_counts = Counter(item.status.value for item in items)
    failed_count = status_counts.get(ToolExecutionFeedbackStatus.FAILED.value, 0)
    return AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=len(items),
        feedback_items=items,
        missing_tool_call_ids=(),
        status_counts=dict(status_counts),
        second_turn_eligible=bool(items),
        recommended_actions=((
            f"已收到 {failed_count} 个真实失败结果；先分析根因和只读证据，禁止原样重试失败写操作。"
        ) if failed_count else (
            "已收到完整的 Java 成功结果，可继续受控模型与工具循环。"
        ),),
    )


def _terminal_feedback_status(state: str) -> ToolExecutionFeedbackStatus | None:
    """Map only terminal Java audit states into model tool-result feedback."""

    return {
        "SUCCEEDED": ToolExecutionFeedbackStatus.SUCCEEDED,
        "FAILED": ToolExecutionFeedbackStatus.FAILED,
        "REJECTED": ToolExecutionFeedbackStatus.REJECTED,
        "SKIPPED": ToolExecutionFeedbackStatus.SKIPPED,
    }.get(state)


def _continuation_request_id(session_id: str, run_id: str) -> str:
    digest = hashlib.sha256(f"{session_id}|{run_id}|post-confirm".encode("utf-8")).hexdigest()[:24]
    return f"post-confirm-{digest}"


def _plan_hint(plan: AgentPlan | None, name: str) -> str | None:
    if plan is None:
        return None
    for tool_plan in reversed(plan.tool_plans):
        value = _text(tool_plan.governance_hints.get(name))
        if value:
            return value
    return None


def _required_text(payload: Mapping[str, Any], name: str) -> str:
    value = _text(payload.get(name))
    if value is None:
        raise ValueError(f"{name} is required.")
    return value


def _text(value: Any) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


def _string_tuple(value: Any) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple, set)):
        return ()
    return tuple(str(item).strip() for item in value if str(item).strip())


def _risk_level(value: Any) -> ToolRiskLevel:
    try:
        return ToolRiskLevel(str(value or "LOW").strip().lower())
    except ValueError:
        return ToolRiskLevel.LOW


def _execution_mode(value: Any) -> ToolExecutionMode:
    try:
        return ToolExecutionMode(str(value or "SYNC").strip().lower())
    except ValueError:
        return ToolExecutionMode.SYNC


__all__ = (
    "POST_CONFIRM_CONTINUATION_SCHEMA_VERSION",
    "AgentPostConfirmContinuationCoordinator",
    "AgentPostConfirmContinuationResult",
)
