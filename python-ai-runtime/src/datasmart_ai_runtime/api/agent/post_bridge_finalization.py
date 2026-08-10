"""专业 Agent 结果进入 Durable 控制面后的最终化服务。

主 ``/agent/plans`` 流程会先生成执行前视图，再运行专业 Agent。DATA_SYNC_AGENT 或
RECOVERY_AGENT 的结果若随后被 bridge 转成新的 ToolPlan，原先计算的 readiness、闭环状态、
协作图和 checkpoint 就已经过期。本模块把“bridge 后最终化”集中在一个边界内，避免响应一边
声称任务已创建，一边仍展示桥接前的缺参状态。

本模块不创建业务任务，也不执行恢复动作。它只消费 Java 控制面已经返回的低敏 taskId、
executionId 和 worker 状态，按需再次运行只读 PRECHECK_AGENT/MONITOR_AGENT，然后重建视图。
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from typing import Any

from datasmart_ai_runtime.api.agent.plan_readiness_views import (
    build_command_proposal_context,
    build_tool_execution_readiness_response,
)
from datasmart_ai_runtime.api.agent.plan_response_events import (
    attach_agent_execution_gate_event,
    attach_agent_execution_session_event,
    attach_agent_turn_runner_event,
    attach_tool_execution_readiness_event,
    record_agent_execution_gate_metrics,
)
from datasmart_ai_runtime.api.gateway.intelligent_gateway import (
    build_intelligent_gateway_governance_response,
)
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.agent_execution import AgentExecutionClosureService
from datasmart_ai_runtime.services.agent_execution.post_resource_specialist_verification import (
    control_plane_resource_fingerprint,
    run_post_bridge_verification_wave,
)
from datasmart_ai_runtime.services.langgraph_multi_agent_collaboration import (
    LangGraphMultiAgentCollaborationWorkflow,
)
from datasmart_ai_runtime.services.multi_agent import (
    LangGraphMultiAgentTurnRunnerWorkflow,
    MultiAgentExecutionSessionService,
    record_multi_agent_turn_runner_checkpoint,
)
from datasmart_ai_runtime.services.multi_agent.langgraph_execution_plan import (
    LangGraphMultiAgentExecutionPlanWorkflow,
)
from datasmart_ai_runtime.services.tools import (
    LangGraphExecutionGateWorkflow,
    ToolActionIntakeSource,
    ToolExecutionReadinessService,
    build_tool_action_command_proposal_templates,
)


# Re-export the execution-layer functions for callers that still import this API
# assembly module.  The single source of truth below retains the complete trusted
# Java receipt policy, including audit/run/output-reference binding and idempotent
# resource fingerprints, so response assembly cannot accidentally drift from the
# post-confirm continuation path.


def recompute_post_bridge_views(
    *,
    request: AgentRequest,
    plan: AgentPlan,
    readiness_policy_snapshot: Any,
    control_plane_ingestion: Any | None,
    control_plane_feedback: Any | None,
    runtime_event_feedback: Any | None,
    loop_control_decision: Any | None,
    second_turn_result: Any | None,
    memory_write_proposal: Any | None,
    durable_agent_loop_service: Any | None,
    multi_agent_execution_session_metrics: Any | None,
    multi_agent_turn_runner_workflow: Any | None,
    multi_agent_turn_runner_metrics: Any | None,
    langgraph_checkpointer_service: Any | None,
    langgraph_execution_gate_metrics: Any | None,
    workspace_context: Any,
    skill_manifest_diagnostics: Mapping[str, Any] | None,
    plan_runtime_event_sink: Callable[[Any], None] | None,
) -> dict[str, Any]:
    """使用 bridge 后最新 ToolPlan 和控制面反馈重建全部最终视图。

    该方法复用首轮相同的 readiness、LangGraph gate、closure、协作图、执行会话与 turn runner
    服务，避免实现两套“新增”和“专业 Agent 自动创建”逻辑。只新增低敏状态事件；不会重新 ingest
    ToolPlan、创建业务任务、执行 worker 或调用模型。
    """

    original_event_count = len(plan.runtime_events)
    readiness = ToolExecutionReadinessService().evaluate(
        plan.tool_plans,
        policy=readiness_policy_snapshot.policy,
        policy_metadata=readiness_policy_snapshot.to_low_sensitive_summary(),
    )
    final_plan = attach_tool_execution_readiness_event(
        plan,
        request=request,
        tool_execution_readiness=readiness,
    )
    readiness_response = build_tool_execution_readiness_response(readiness)

    execution_gate = LangGraphExecutionGateWorkflow.from_env().run(readiness)
    execution_gate_summary = execution_gate.to_summary()
    final_plan = attach_agent_execution_gate_event(
        final_plan,
        request=request,
        execution_gate_summary=execution_gate_summary,
    )
    record_agent_execution_gate_metrics(final_plan, metrics_recorder=langgraph_execution_gate_metrics)

    command_templates = build_tool_action_command_proposal_templates(
        source=ToolActionIntakeSource.MODEL_TOOL_CALL,
        protocol_family="AGENT_PLAN",
        readiness_summary=readiness_response,
        command_context=build_command_proposal_context(request, final_plan, readiness_policy_snapshot),
    )
    closure_summary = AgentExecutionClosureService().build(
        plan=final_plan,
        readiness=readiness,
        control_plane_ingestion=control_plane_ingestion,
        control_plane_feedback=control_plane_feedback,
        runtime_event_feedback=runtime_event_feedback,
        loop_control_decision=loop_control_decision,
        second_turn_result=second_turn_result,
        memory_write_proposal=memory_write_proposal,
        command_proposal_templates=command_templates,
    ).to_summary()
    gateway_governance = build_intelligent_gateway_governance_response(
        final_plan,
        workspace_context,
        request,
        skill_manifest_diagnostics=skill_manifest_diagnostics,
        agent_execution_closure=closure_summary,
    )
    collaboration = LangGraphMultiAgentCollaborationWorkflow.from_env().run(
        request=request,
        plan=final_plan,
        scheduling=gateway_governance.get("agentSessionScheduling", {}),
    )
    collaboration_plan = LangGraphMultiAgentExecutionPlanWorkflow.from_env().run(
        request=request,
        plan=final_plan,
        scheduling=gateway_governance.get("agentSessionScheduling", {}),
        collaboration=collaboration.to_summary(),
    )
    collaboration_plan_summary = collaboration_plan.to_summary()

    durable_checkpoint = None
    if durable_agent_loop_service is not None:
        durable_checkpoint = durable_agent_loop_service.record(
            request=request,
            plan=final_plan,
            control_plane_feedback=control_plane_feedback,
            loop_control_decision=loop_control_decision,
            second_turn_result=second_turn_result,
        )

    execution_session = MultiAgentExecutionSessionService().build(
        request=request,
        plan=final_plan,
        scheduling=gateway_governance.get("agentSessionScheduling", {}),
        collaboration_execution_plan=collaboration_plan_summary,
        durable_loop=durable_checkpoint.to_summary() if durable_checkpoint is not None else None,
    )
    execution_session_summary = execution_session.to_summary()
    if multi_agent_execution_session_metrics is not None:
        multi_agent_execution_session_metrics.record_summary(execution_session_summary)
    final_plan = attach_agent_execution_session_event(
        final_plan,
        request=request,
        agent_execution_session=execution_session_summary,
    )

    runner_workflow = multi_agent_turn_runner_workflow or LangGraphMultiAgentTurnRunnerWorkflow.from_env()
    turn_runner = runner_workflow.run(
        request=request,
        plan=final_plan,
        execution_session=execution_session_summary,
        command_proposal_templates=command_templates,
        durable_loop=durable_checkpoint.to_summary() if durable_checkpoint is not None else None,
    )
    turn_runner_summary = turn_runner.to_summary()
    if multi_agent_turn_runner_metrics is not None:
        multi_agent_turn_runner_metrics.record_summary(turn_runner_summary)

    turn_checkpoint = None
    if langgraph_checkpointer_service is not None:
        turn_checkpoint = record_multi_agent_turn_runner_checkpoint(
            langgraph_checkpointer_service,
            request=request,
            plan=final_plan,
            agent_turn_runner=turn_runner_summary,
        )
    final_plan = attach_agent_turn_runner_event(
        final_plan,
        request=request,
        agent_turn_runner=turn_runner_summary,
        agent_turn_runner_checkpoint=turn_checkpoint,
    )

    if plan_runtime_event_sink is not None:
        for event in final_plan.runtime_events[original_event_count:]:
            plan_runtime_event_sink(event)

    return {
        "plan": final_plan,
        "tool_execution_readiness": readiness,
        "tool_execution_readiness_response": readiness_response,
        "agent_execution_gate_summary": execution_gate_summary,
        "command_proposal_templates": command_templates,
        "agent_execution_closure_summary": closure_summary,
        "intelligent_gateway_governance": gateway_governance,
        "agent_collaboration_workflow": collaboration,
        "agent_collaboration_execution_plan_summary": collaboration_plan_summary,
        "agent_execution_session": execution_session,
        "agent_turn_runner_summary": turn_runner_summary,
        "durable_loop_checkpoint": durable_checkpoint,
        "agent_turn_runner_checkpoint": turn_checkpoint,
    }
