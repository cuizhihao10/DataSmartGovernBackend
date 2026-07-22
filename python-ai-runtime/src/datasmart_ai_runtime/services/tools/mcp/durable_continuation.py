"""Resume the governed Agent loop after a durable MCP worker receipt.

The MCP worker is asynchronous, so the HTTP request that produced the original
tool call may no longer exist when the result arrives.  This coordinator rebuilds
only the low-sensitive planning envelope required for the next model turn, feeds
the real tool result back to the shared second-turn orchestrator, and submits any
new model-selected tools through the existing Java durable control plane.

It never calls an internal or MCP business tool directly.  Model output remains
an untrusted proposal and still crosses schema validation, budget/repeat guards,
permission, approval, outbox, lease and receipt validation.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ToolExecutionMode,
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
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import AgentFollowUpToolPlanner
from datasmart_ai_runtime.services.agent_loop_control_policy import (
    AgentLoopControlPolicyEvaluator,
    AgentLoopControlState,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnOrchestrator
from datasmart_ai_runtime.services.intent_analyzer import RuleBasedIntentAnalyzer
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedback
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


MCP_DURABLE_CONTINUATION_SCHEMA_VERSION = "datasmart.mcp-durable-continuation.v1"


@dataclass(frozen=True)
class McpDurableContinuationResult:
    """Low-sensitive result of one event-driven MCP continuation."""

    model_turn: Any
    durable_loop: Any | None
    request_id: str
    session_id: str | None
    run_id: str | None

    def to_summary(self) -> dict[str, Any]:
        return {
            "schemaVersion": MCP_DURABLE_CONTINUATION_SCHEMA_VERSION,
            "requestId": self.request_id,
            "sessionId": self.session_id,
            "runId": self.run_id,
            "modelSecondTurn": self.model_turn.to_summary(),
            "durableLoop": self.durable_loop.to_summary() if self.durable_loop is not None else None,
            "payloadPolicy": "LOW_SENSITIVE_MODEL_AND_DURABLE_LOOP_SUMMARY_ONLY",
        }


class McpDurableContinuationCoordinator:
    """Convert one MCP receipt into a bounded, same-session Agent continuation."""

    def __init__(
        self,
        *,
        model_routes: ModelRouteRegistry,
        tool_planner: ToolPlanner,
        follow_up_tool_planner: AgentFollowUpToolPlanner,
        second_turn_orchestrator: AgentSecondTurnOrchestrator,
        loop_control_evaluator: AgentLoopControlPolicyEvaluator,
        durable_loop_runner: AgentDurableModelToolLoopRunner,
        intent_analyzer: RuleBasedIntentAnalyzer | None = None,
        max_runtime_visible_mcp_tools: int = 16,
    ) -> None:
        self._model_routes = model_routes
        self._tool_planner = tool_planner
        self._follow_up_tool_planner = follow_up_tool_planner
        self._second_turn_orchestrator = second_turn_orchestrator
        self._loop_control_evaluator = loop_control_evaluator
        self._durable_loop_runner = durable_loop_runner
        self._intent_analyzer = intent_analyzer or RuleBasedIntentAnalyzer()
        self._max_runtime_visible_mcp_tools = max(1, max_runtime_visible_mcp_tools)

    def continue_after_mcp(
        self,
        *,
        feedback: ToolExecutionFeedback,
        control_facts: Mapping[str, Any],
        trace_id: str | None = None,
        workspace_key: str | None = None,
    ) -> McpDurableContinuationResult:
        """Run the post-MCP model turn and submit its governed follow-up batch."""

        session_id = _text(control_facts.get("sessionId") or control_facts.get("session_id"))
        run_id = _text(control_facts.get("runId") or control_facts.get("run_id") or feedback.run_id)
        request_id = _text(trace_id) or run_id or feedback.tool_call_id
        objective = _text(control_facts.get("objectiveSummary") or control_facts.get("objective_summary"))
        if objective is None:
            objective = "基于当前 MCP 工具的真实执行结果完成用户目标；如信息不足则明确说明需要补充什么。"

        request = AgentRequest(
            tenant_id=_text(control_facts.get("tenantId") or control_facts.get("tenant_id")) or "unknown-tenant",
            project_id=_text(control_facts.get("projectId") or control_facts.get("project_id")) or "unknown-project",
            actor_id=_text(control_facts.get("actorId") or control_facts.get("actor_id")) or "unknown-actor",
            objective=objective,
            variables={
                "agentRuntimeSessionId": session_id,
                "traceId": request_id,
                "workspaceKey": workspace_key,
                "mcpDurableContinuation": True,
            },
            preferred_workload=WorkloadType.AGENT_REASONING,
            request_id=request_id,
        )
        intent = self._intent_analyzer.analyze(request, ())
        resource_reference: dict[str, str] = {"toolCode": feedback.tool_name}
        if feedback.audit_id:
            resource_reference["auditId"] = feedback.audit_id
        if run_id:
            resource_reference["runId"] = run_id
        if feedback.output_ref:
            resource_reference["outputRef"] = feedback.output_ref

        runtime_visible_names = self._runtime_visible_mcp_tool_names(feedback.tool_name)
        current_tool = self._registered_tool(feedback.tool_name)
        current_plan = ToolPlan(
            tool_name=feedback.tool_name,
            reason="MCP durable worker 已返回真实结果，准备进入同一会话的受控模型后续轮。",
            arguments={},
            risk_level=current_tool.risk_level if current_tool is not None else ToolRiskLevel.HIGH,
            execution_mode=(
                current_tool.execution_mode if current_tool is not None else ToolExecutionMode.ASYNC_TASK
            ),
            requires_human_approval=(
                current_tool.requires_approval if current_tool is not None else True
            ),
            governance_hints={
                "modelToolCallId": feedback.tool_call_id,
                "agentRuntimeSessionId": session_id,
                "agentRuntimeRunId": run_id,
                "workspaceKey": workspace_key,
                "agentLoopResourceRefs": {feedback.tool_name: resource_reference},
                "runtimeContinuationVisibleToolNames": runtime_visible_names,
                "mcpDurableContinuation": True,
            },
        )
        plan = AgentPlan(
            request_id=request_id,
            selected_route=self._model_routes.route_for(WorkloadType.AGENT_REASONING),
            state_trace=("mcp_worker_receipt", "resume_model_tool_loop"),
            tool_plans=(current_plan,),
            requires_human_approval=False,
            response_summary="MCP 工具已完成，正在基于真实结果决定结束回答或继续选择受控工具。",
            next_actions=("基于工具结果形成公开回答，或提交下一批受治理工具。",),
            intent_analysis=intent,
        )
        snapshot = self._feedback_snapshot(feedback)
        decision = self._loop_control_evaluator.evaluate(
            snapshot,
            AgentLoopControlState(
                tool_step_index=1,
                completed_second_turns=0,
                consumed_tokens=0,
                estimated_next_turn_tokens=1024,
                elapsed_seconds=0,
            ),
        )
        model_turn = self._second_turn_orchestrator.run(
            request=request,
            plan=plan,
            control_plane_feedback=snapshot,
            loop_control_decision=decision,
        )
        durable_loop = None
        if model_turn.follow_up_tool_plans:
            durable_loop = self._durable_loop_runner.run(
                request=request,
                plan=plan,
                first_model_turn=model_turn,
            )
        return McpDurableContinuationResult(
            model_turn=model_turn,
            durable_loop=durable_loop,
            request_id=request_id,
            session_id=session_id,
            run_id=run_id,
        )

    def _runtime_visible_mcp_tool_names(self, current_tool_name: str) -> tuple[str, ...]:
        """Expose only the current MCP server's startup-catalog tools."""

        parts = current_tool_name.split(".")
        prefix = ".".join(parts[:2]) + "." if len(parts) >= 3 and parts[0] == "mcp" else None
        if prefix is None:
            return ()
        names = sorted(
            tool.name
            for tool in self._tool_planner.registered_tools()
            if tool.name.startswith(prefix)
        )
        return tuple(names[: self._max_runtime_visible_mcp_tools])

    def _registered_tool(self, tool_name: str) -> Any | None:
        return next(
            (tool for tool in self._tool_planner.registered_tools() if tool.name == tool_name),
            None,
        )

    @staticmethod
    def _feedback_snapshot(feedback: ToolExecutionFeedback) -> AgentControlPlaneFeedbackSnapshot:
        item = AgentControlPlaneFeedbackItem(
            model_tool_call_id=feedback.tool_call_id,
            tool_name=feedback.tool_name,
            status=feedback.status,
            summary=feedback.summary,
            result=dict(feedback.result),
            audit_id=feedback.audit_id,
            run_id=feedback.run_id,
            output_ref=feedback.output_ref,
            output_workspace_key=feedback.output_workspace_key,
            output_context_policy=feedback.output_context_policy,
            error_code=feedback.error_code,
            error_message=feedback.error_message,
            sensitive_fields=feedback.sensitive_fields,
            model_context_include_paths=feedback.model_context_include_paths,
            model_context_exclude_paths=feedback.model_context_exclude_paths,
            sensitive_result_paths=feedback.sensitive_result_paths,
        )
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=1,
            feedback_items=(item,),
            missing_tool_call_ids=(),
            status_counts={feedback.status.value: 1},
            second_turn_eligible=True,
            recommended_actions=("MCP 工具结果已完整回填，可进入受控模型后续轮。",),
        )


def _text(value: Any) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


__all__ = [
    "MCP_DURABLE_CONTINUATION_SCHEMA_VERSION",
    "McpDurableContinuationCoordinator",
    "McpDurableContinuationResult",
]
