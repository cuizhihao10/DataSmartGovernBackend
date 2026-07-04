"""MCP durable worker 到 LangGraph checkpoint 的适配层。

`mcp_worker.py` 的职责是 HTTP payload 解析、worker 调用和低敏响应组装；本文件专门负责把 MCP worker
执行事实转换成 LangGraph durable checkpoint。这样可以避免一个路由文件同时承担协议解析、状态机建模、
多 Agent 恢复状态构造等多种职责。

设计边界：
- 输入只来自已经完成 admission/worker receipt/modelFeedback 适配的低敏对象；
- checkpoint state 只保存恢复执行需要的摘要字段；
- 不保存 MCP arguments、工具结果正文、二轮 prompt/messages、Java lease fencing token 或 Provider 原始响应；
- 当前图名固定为 `datasmart.agent.mcp-feedback-loop`，后续真实多 Agent runner 可以复用同一套节点命名规则。
"""

from __future__ import annotations

from typing import Any, Mapping
from uuid import uuid4

from datasmart_ai_runtime.services.agent_execution import (
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    LangGraphDurableCheckpointerService,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedback
from datasmart_ai_runtime.services.tools.mcp import McpDurableWorkerRunRequest


LANGGRAPH_MCP_FEEDBACK_GRAPH_NAME = "datasmart.agent.mcp-feedback-loop"
LANGGRAPH_MCP_FEEDBACK_GRAPH_VERSION = "v1"


def record_mcp_model_feedback_checkpoint(
    service: LangGraphDurableCheckpointerService,
    *,
    request: McpDurableWorkerRunRequest,
    payload: Mapping[str, Any],
    worker_result: Any,
    feedback: ToolExecutionFeedback,
    feedback_summary: Mapping[str, Any],
) -> LangGraphDurableCheckpoint:
    """把 MCP worker 结果转换为 LangGraph 图的第一个可恢复节点。

    该节点代表“工具执行结果已经被转成安全 modelFeedback，但模型尚未进行二轮阅读”。它是后续暂停、
    恢复、分支和多 Agent handoff 的根状态，因此只保存恢复调度需要的低敏字段：
    - 业务定位：tenant/project/actor/workspace/run/session；
    - 工具定位：toolName/toolCallId/auditId/outputRef；
    - 安全判断：inlineResultAllowed/inlineDecisionReason；
    - 多 Agent 现场：总控 Agent 与被工具命中的专项 Agent 的状态；
    - 禁止保存：MCP arguments、工具返回正文、二轮 messages、Java receipt token。
    """

    control_facts = request.control_facts
    thread_id = _langgraph_thread_id(control_facts, payload, feedback)
    specialist_role = _agent_role_for_tool(feedback.tool_name)
    worker_status = _enum_or_text(getattr(worker_result.execution_result, "status", None))
    checkpoint = LangGraphDurableCheckpoint(
        checkpoint_id=_langgraph_checkpoint_id(thread_id, "mcp-feedback"),
        thread_id=thread_id,
        tenant_id=_control_text(control_facts, "tenantId", "tenant_id"),
        project_id=_control_text(control_facts, "projectId", "project_id"),
        actor_id=_control_text(control_facts, "actorId", "actor_id"),
        workspace_key=_optional_text(payload.get("workspaceKey") or payload.get("workspace_key"))
        or _control_text(control_facts, "workspaceKey", "workspace_key"),
        run_id=feedback.run_id or _control_text(control_facts, "runId", "run_id"),
        session_id=_control_text(control_facts, "sessionId", "session_id")
        or _optional_text(payload.get("sessionId") or payload.get("session_id")),
        graph_name=LANGGRAPH_MCP_FEEDBACK_GRAPH_NAME,
        graph_version=LANGGRAPH_MCP_FEEDBACK_GRAPH_VERSION,
        node_name="mcp_model_feedback",
        status=LangGraphCheckpointStatus.RUNNING,
        state={
            "source": "mcp_durable_worker_api",
            "currentAgent": "MASTER_ORCHESTRATOR",
            "toolName": feedback.tool_name,
            "toolCallId": feedback.tool_call_id,
            "auditIdPresent": bool(feedback.audit_id),
            "outputRefPresent": bool(feedback.output_ref),
            "workerStatus": worker_status,
            "feedbackStatus": feedback.status.value,
            "feedbackPolicy": _feedback_policy_summary(feedback_summary),
            "multiAgentState": _mcp_multi_agent_state(
                specialist_role=specialist_role,
                specialist_status="TOOL_RESULT_FEEDBACK_READY",
                master_status="WAITING_MODEL_SECOND_TURN",
            ),
            "collaborationEdges": (
                {
                    "fromRole": "MASTER_ORCHESTRATOR",
                    "toRole": specialist_role,
                    "edgeType": "tool_result_feedback",
                },
            ),
            "securityPolicies": (
                "MCP_ARGUMENTS_NEVER_STORED_IN_LANGGRAPH_STATE",
                "TOOL_RESULT_BODY_NOT_STORED_IN_LANGGRAPH_STATE",
                "MODEL_MESSAGES_NOT_STORED_IN_LANGGRAPH_STATE",
            ),
        },
        next_nodes=("mcp_model_second_turn",),
        low_sensitive_summary="MCP 工具结果已转换为安全 modelFeedback，等待二轮模型节点读取。",
    )
    return service.record_checkpoint(checkpoint, event_type="mcp_model_feedback_prepared")


def record_mcp_model_second_turn_loop(
    service: LangGraphDurableCheckpointerService,
    *,
    initial_checkpoint: LangGraphDurableCheckpoint,
    feedback: ToolExecutionFeedback,
) -> LangGraphDurableCheckpoint:
    """记录从 `modelFeedback` 到 `modelSecondTurn` 的显式 LangGraph 边。

    这里使用 `record_loop_iteration` 并不是说 MCP 二轮一定会无限循环，而是借用“同 thread 版本推进”的
    语义表达：同一执行现场经过一条可观测边进入下一节点。后续如果二轮模型要求继续调用工具，可以继续
    在同一个 thread 中追加新的循环版本，而不是覆盖原始现场。
    """

    specialist_role = _agent_role_for_tool(feedback.tool_name)
    return service.record_loop_iteration(
        thread_id=initial_checkpoint.thread_id,
        node_name="mcp_model_second_turn",
        edge_name="model_feedback_to_second_turn",
        state_patch={
            "currentAgent": "MASTER_ORCHESTRATOR",
            "toolCallId": feedback.tool_call_id,
            "feedbackStatus": feedback.status.value,
            "secondTurnAttempted": True,
            "multiAgentState": _mcp_multi_agent_state(
                specialist_role=specialist_role,
                specialist_status="TOOL_RESULT_DELIVERED_TO_MODEL",
                master_status="RUNNING_MODEL_SECOND_TURN",
            ),
        },
    )


def record_mcp_model_second_turn_final(
    service: LangGraphDurableCheckpointerService,
    *,
    parent_checkpoint: LangGraphDurableCheckpoint,
    feedback: ToolExecutionFeedback,
    model_second_turn_summary: Mapping[str, Any],
) -> LangGraphDurableCheckpoint:
    """记录二轮模型节点结束状态。

    结束状态不等于一定成功：
    - Provider 返回错误码时标记 `failed`，保留可重试/分支机会；
    - 等待审批或人工确认时标记 `waiting_human`；
    - 策略性跳过或成功总结都标记 `completed`，表示该 MCP feedback 节点已经收口。
    """

    status = _checkpoint_status_for_second_turn(feedback, model_second_turn_summary)
    specialist_role = _agent_role_for_tool(feedback.tool_name)
    state = dict(parent_checkpoint.state)
    state.update(
        {
            "currentAgent": "MASTER_ORCHESTRATOR",
            "secondTurnExecuted": bool(model_second_turn_summary.get("executed")),
            "secondTurnSkipped": bool(model_second_turn_summary.get("skipped")),
            "secondTurnReason": _optional_text(model_second_turn_summary.get("reason")),
            "providerName": _optional_text(model_second_turn_summary.get("providerName")),
            "modelName": _optional_text(model_second_turn_summary.get("modelName")),
            "errorCode": _optional_text(model_second_turn_summary.get("errorCode")),
            "multiAgentState": _mcp_multi_agent_state(
                specialist_role=specialist_role,
                specialist_status="SECOND_TURN_FEEDBACK_CONSUMED",
                master_status=_master_status_for_second_turn(status),
            ),
            "collaborationEdges": (
                {
                    "fromRole": specialist_role,
                    "toRole": "MASTER_ORCHESTRATOR",
                    "edgeType": "model_feedback_consumed",
                },
            ),
        }
    )
    checkpoint = LangGraphDurableCheckpoint(
        checkpoint_id=_langgraph_checkpoint_id(parent_checkpoint.thread_id, "mcp-second-turn-final"),
        thread_id=parent_checkpoint.thread_id,
        parent_checkpoint_id=parent_checkpoint.checkpoint_id,
        tenant_id=parent_checkpoint.tenant_id,
        project_id=parent_checkpoint.project_id,
        actor_id=parent_checkpoint.actor_id,
        workspace_key=parent_checkpoint.workspace_key,
        run_id=parent_checkpoint.run_id,
        session_id=parent_checkpoint.session_id,
        graph_name=parent_checkpoint.graph_name,
        graph_version=parent_checkpoint.graph_version,
        node_name="mcp_model_second_turn_completed",
        status=status,
        checkpoint_version=parent_checkpoint.checkpoint_version + 1,
        state=state,
        next_nodes=() if status == LangGraphCheckpointStatus.COMPLETED else ("retry_or_human_review",),
        resume_requirements=_resume_requirements_for_second_turn(status),
        low_sensitive_summary=f"MCP 二轮模型节点结束：{_optional_text(model_second_turn_summary.get('reason')) or status.value}",
    )
    return service.record_checkpoint(checkpoint, event_type="mcp_model_second_turn_completed")


def _langgraph_thread_id(
    control_facts: Mapping[str, Any],
    payload: Mapping[str, Any],
    feedback: ToolExecutionFeedback,
) -> str:
    """解析 LangGraph threadId，优先使用 Java 控制面传入的显式 ID。"""

    explicit = (
        _control_text(control_facts, "langGraphThreadId", "langgraphThreadId", "lang_graph_thread_id")
        or _optional_text(payload.get("langGraphThreadId") or payload.get("lang_graph_thread_id"))
    )
    if explicit:
        return explicit
    run_id = feedback.run_id or _control_text(control_facts, "runId", "run_id") or "unknown-run"
    call_id = feedback.tool_call_id or _control_text(control_facts, "callId", "call_id") or "unknown-call"
    return f"mcp-feedback:{run_id}:{call_id}"


def _langgraph_checkpoint_id(thread_id: str, marker: str) -> str:
    """生成可读但不暴露敏感正文的 checkpoint id。"""

    return f"lgcp:mcp:{_safe_key_part(thread_id)}:{_safe_key_part(marker)}:{uuid4().hex[:12]}"


def _feedback_policy_summary(summary: Mapping[str, Any]) -> dict[str, Any]:
    """压缩 feedback adapter 的准入摘要，避免把工具结果正文带入 durable state。"""

    return {
        "inlineResultAllowed": bool(summary.get("inlineResultAllowed")),
        "inlineDecisionReason": _optional_text(summary.get("inlineDecisionReason")),
        "runtimeResultPresent": bool(summary.get("runtimeResultPresent")),
        "resultDigestPresent": bool(summary.get("resultDigest")),
        "artifactReferencePresent": bool(summary.get("artifactReference")),
    }


def _mcp_multi_agent_state(
    *,
    specialist_role: str,
    specialist_status: str,
    master_status: str,
) -> dict[str, dict[str, str]]:
    """构造 MCP feedback 节点涉及的多 Agent 状态。

    当前 MCP worker 节点主要由总控 Agent 驱动，并把结果交还给某个专项 Agent 或 Task Agent。为了让多
    Agent 恢复 API 能直接看见完整角色集合，这里列出核心角色；未参与本次边的角色标记为 `NOT_SCHEDULED`，
    表示“本轮没有调度”，而不是“能力不存在”。
    """

    roles = {
        "MASTER_ORCHESTRATOR": {"status": master_status},
        "DATASOURCE_AGENT": {"status": "NOT_SCHEDULED"},
        "DATA_QUALITY_AGENT": {"status": "NOT_SCHEDULED"},
        "DATA_SYNC_AGENT": {"status": "NOT_SCHEDULED"},
        "TASK_AGENT": {"status": "NOT_SCHEDULED"},
        "PERMISSION_AGENT": {"status": "OBSERVING_POLICY"},
        "MEMORY_AGENT": {"status": "AVAILABLE_FOR_RETRIEVAL"},
        "OPS_AGENT": {"status": "OBSERVING_RUNTIME"},
    }
    roles[specialist_role] = {"status": specialist_status}
    return roles


def _agent_role_for_tool(tool_name: str) -> str:
    """根据工具名推断最相关的专项 Agent 角色。"""

    normalized = str(tool_name or "").lower()
    if "quality" in normalized or "rule" in normalized or "clean" in normalized:
        return "DATA_QUALITY_AGENT"
    if "datasource" in normalized or "metadata" in normalized or "connector" in normalized:
        return "DATASOURCE_AGENT"
    if "sync" in normalized or "etl" in normalized or "pipeline" in normalized:
        return "DATA_SYNC_AGENT"
    if "permission" in normalized or "auth" in normalized or "rbac" in normalized:
        return "PERMISSION_AGENT"
    if "task" in normalized or "schedule" in normalized:
        return "TASK_AGENT"
    return "TASK_AGENT"


def _checkpoint_status_for_second_turn(
    feedback: ToolExecutionFeedback,
    summary: Mapping[str, Any],
) -> LangGraphCheckpointStatus:
    """根据二轮调用结果选择 LangGraph checkpoint 终态。"""

    if feedback.status.value == "waiting_approval":
        return LangGraphCheckpointStatus.WAITING_HUMAN
    if _optional_text(summary.get("errorCode")):
        return LangGraphCheckpointStatus.FAILED
    if summary.get("executed") is False and summary.get("skipped") is False:
        return LangGraphCheckpointStatus.FAILED
    return LangGraphCheckpointStatus.COMPLETED


def _master_status_for_second_turn(status: LangGraphCheckpointStatus) -> str:
    """把 checkpoint 状态转成多 Agent 视图中的总控状态。"""

    if status == LangGraphCheckpointStatus.COMPLETED:
        return "COMPLETED_SECOND_TURN"
    if status == LangGraphCheckpointStatus.WAITING_HUMAN:
        return "WAITING_HUMAN_REVIEW"
    if status == LangGraphCheckpointStatus.FAILED:
        return "FAILED_NEEDS_RETRY_OR_BRANCH"
    return status.value.upper()


def _resume_requirements_for_second_turn(status: LangGraphCheckpointStatus) -> dict[str, Any]:
    """为失败或等待状态生成低敏恢复条件。"""

    if status == LangGraphCheckpointStatus.WAITING_HUMAN:
        return {"humanApproval": "required"}
    if status == LangGraphCheckpointStatus.FAILED:
        return {"retryPolicy": "required", "operatorReview": "recommended"}
    return {}


def _control_text(mapping: Mapping[str, Any], *keys: str) -> str | None:
    """按多个 Java/Python 兼容字段名读取控制面文本。"""

    return _optional_text(_first(mapping, *keys))


def _enum_or_text(value: Any) -> str:
    """把 Enum 或普通值转换为低敏短文本。"""

    return _optional_text(getattr(value, "value", value)) or "unknown"


def _optional_text(value: Any) -> str | None:
    """读取可选非空文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _safe_key_part(value: Any) -> str:
    """把外部 ID 压成 checkpoint id 可用片段。"""

    text = _optional_text(value) or "unknown"
    return "".join(ch if ch.isalnum() or ch in "_.:-" else "_" for ch in text)[:96] or "unknown"


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """按多个兼容字段名读取第一个存在的值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


__all__ = [
    "record_mcp_model_feedback_checkpoint",
    "record_mcp_model_second_turn_final",
    "record_mcp_model_second_turn_loop",
]
