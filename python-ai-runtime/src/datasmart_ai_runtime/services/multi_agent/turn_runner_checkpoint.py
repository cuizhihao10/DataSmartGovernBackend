"""多 Agent turn runner 到 LangGraph durable checkpoint 的适配层。

`agentTurnRunner` 已经能描述本轮多 Agent 如何进入下一步：哪些 Agent 可以作为 manager-as-tools 被调度、
哪些 evidence 必须由 Java 控制面补齐、是否需要审批、是否等待 worker receipt。此前这些信息只存在于
HTTP 响应、runtime event 和 Prometheus 指标中，重启后无法作为 LangGraph 线程现场恢复。

本模块负责把 `agentTurnRunner` 的低敏摘要投影为 `LangGraphDurableCheckpoint`：
- 不重新运行 turn runner；
- 不执行工具、不调用模型、不写 Java outbox；
- 不保存用户目标、prompt、ToolPlan.arguments、SQL、样本数据、模型输出或 endpoint；
- 只保存恢复所需的状态码、计数、Agent role/status、required evidence code、能力 code 和下一步动作。

这样做的价值是让多 Agent 执行层真正具备“暂停、恢复、分支、循环、跨实例恢复”的状态机底座，同时仍然
遵守项目当前“副作用必须由 Java 控制面和 worker receipt 承接”的生产边界。
"""

from __future__ import annotations

from typing import Any, Mapping
from uuid import uuid4

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.agent_execution import (
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    LangGraphDurableCheckpointerService,
)


LANGGRAPH_MULTI_AGENT_TURN_RUNNER_GRAPH_NAME = "datasmart.agent.multi-agent-turn-runner"
LANGGRAPH_MULTI_AGENT_TURN_RUNNER_GRAPH_VERSION = "v1"


def record_multi_agent_turn_runner_checkpoint(
    service: LangGraphDurableCheckpointerService,
    *,
    request: AgentRequest,
    plan: AgentPlan,
    agent_turn_runner: Mapping[str, Any],
) -> dict[str, Any]:
    """把 `agentTurnRunner` 低敏摘要写入 LangGraph durable checkpoint。

    参数说明：
    - `service`：启动期装配好的 checkpointer service，生产可落 PostgreSQL，单测可用内存实现；
    - `request`：只读取租户、项目、操作者和 variables 中的低敏 session/workspace/run 标识；
    - `plan`：只读取 `request_id` 作为 run/thread 兜底标识，不读取用户目标或模型输出；
    - `agent_turn_runner`：已由 `ControlledMultiAgentTurnRunnerDiagnostics.to_summary()` 低敏化的摘要。

    返回值直接进入 `/agent/plans.agentTurnRunnerCheckpoint`，只包含 checkpoint summary 与恢复摘要，不返回
    checkpoint state 正文。这样前端/网关能知道 checkpoint 已记录，但不能看到 prompt、工具参数或模型输出。
    """

    checkpoint = _build_checkpoint(request=request, plan=plan, agent_turn_runner=agent_turn_runner)
    saved = service.record_checkpoint(checkpoint, event_type="multi_agent_turn_runner_recorded")
    recovered = service.recover_multi_agent_state(saved.thread_id)
    return {
        "threadId": saved.thread_id,
        "checkpoint": saved.to_summary(),
        "multiAgentRecovery": recovered.to_summary(),
        "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_TURN_RUNNER_CHECKPOINT_SUMMARY_ONLY",
    }


def _build_checkpoint(
    *,
    request: AgentRequest,
    plan: AgentPlan,
    agent_turn_runner: Mapping[str, Any],
) -> LangGraphDurableCheckpoint:
    """构造 checkpoint 领域对象。

    这里集中完成三件事：
    1. 根据 runStatus 映射 checkpoint status；
    2. 白名单压缩 turn runner state；
    3. 生成恢复要求和下一节点建议。
    """

    thread_id = _thread_id(request, plan)
    run_status = _text(agent_turn_runner.get("runStatus")) or "UNKNOWN"
    status = _checkpoint_status(run_status)
    return LangGraphDurableCheckpoint(
        checkpoint_id=_checkpoint_id(thread_id, "turn-runner"),
        thread_id=thread_id,
        tenant_id=_text_or_none(request.tenant_id),
        project_id=_text_or_none(request.project_id),
        actor_id=_text_or_none(request.actor_id),
        workspace_key=_workspace_key(request),
        run_id=_run_id(request, plan),
        session_id=_session_id(request),
        graph_name=LANGGRAPH_MULTI_AGENT_TURN_RUNNER_GRAPH_NAME,
        graph_version=LANGGRAPH_MULTI_AGENT_TURN_RUNNER_GRAPH_VERSION,
        node_name=_node_name(run_status),
        status=status,
        state=_checkpoint_state(agent_turn_runner),
        next_nodes=_next_nodes(run_status),
        resume_requirements=_resume_requirements(agent_turn_runner),
        low_sensitive_summary=_summary(run_status, status),
    )


def _checkpoint_state(agent_turn_runner: Mapping[str, Any]) -> dict[str, Any]:
    """把 turn runner summary 压缩成 durable state。

    注意这里不使用 `dict(agent_turn_runner)` 整体复制，而是逐字段白名单抽取。原因是未来 summary 可能新增
    面向调试的字段，如果无审查地整体写入 checkpoint，可能把 prompt、工具参数或模型输出带进持久化层。
    """

    attempts = tuple(item for item in _sequence(agent_turn_runner.get("turnAttempts")) if isinstance(item, Mapping))
    manager_tools = tuple(item for item in _sequence(agent_turn_runner.get("managerAsTools")) if isinstance(item, Mapping))
    knowledge_capabilities = tuple(
        item for item in _sequence(agent_turn_runner.get("knowledgeAgentCapabilities")) if isinstance(item, Mapping)
    )
    return {
        "source": "agent_plan_response",
        "currentAgent": "MASTER_ORCHESTRATOR",
        "turnRunner": {
            "runStatus": _text(agent_turn_runner.get("runStatus")),
            "runnerRoute": _text(agent_turn_runner.get("runnerRoute")),
            "runnerStatus": _text(agent_turn_runner.get("runnerStatus")),
            "loopDecision": _text(agent_turn_runner.get("loopDecision")),
            "sessionStatus": _text(agent_turn_runner.get("sessionStatus")),
            "durablePhase": _text(agent_turn_runner.get("durablePhase")),
            "currentTurnDepth": _non_negative_int(agent_turn_runner.get("currentTurnDepth")),
            "maxTurnDepth": _non_negative_int(agent_turn_runner.get("maxTurnDepth")),
            "maxConcurrentAgentTurns": _non_negative_int(agent_turn_runner.get("maxConcurrentAgentTurns")),
            "turnAttemptCount": _non_negative_int(agent_turn_runner.get("turnAttemptCount")),
            "waitingAttemptCount": _non_negative_int(agent_turn_runner.get("waitingAttemptCount")),
            "blockedAttemptCount": _non_negative_int(agent_turn_runner.get("blockedAttemptCount")),
            "controlPlaneHandoffCount": _non_negative_int(agent_turn_runner.get("controlPlaneHandoffCount")),
            "knowledgeAgentCapabilityCount": _non_negative_int(agent_turn_runner.get("knowledgeAgentCapabilityCount")),
            "payloadPolicy": "LOW_SENSITIVE_TURN_RUNNER_STATE_ONLY",
        },
        "multiAgentState": _multi_agent_state(attempts),
        "managerAsTools": {
            "count": len(manager_tools),
            "agentRoles": tuple(_text(item.get("agentRole")) for item in manager_tools if _text(item.get("agentRole"))),
            "payloadPolicy": "MANAGER_AS_TOOLS_SUMMARY_ONLY_NO_ARGUMENTS",
        },
        "knowledgeAgentCapabilities": {
            "count": len(knowledge_capabilities),
            "capabilityCodes": tuple(
                _text(item.get("capabilityCode")) for item in knowledge_capabilities if _text(item.get("capabilityCode"))
            ),
            "payloadPolicy": "KNOWLEDGE_AGENT_CAPABILITY_CODES_ONLY",
        },
        "collaborationEdges": _collaboration_edges(attempts, knowledge_capabilities),
        "handoffRequired": _handoff_required(attempts, agent_turn_runner),
        "nextActions": _limited_text_tuple(agent_turn_runner.get("nextActions"), limit=16),
        "securityPolicies": (
            "TURN_RUNNER_CHECKPOINT_NO_PROMPT",
            "TURN_RUNNER_CHECKPOINT_NO_TOOL_ARGUMENTS",
            "TURN_RUNNER_CHECKPOINT_NO_MODEL_OUTPUT",
            "TURN_RUNNER_CHECKPOINT_NO_SQL_OR_SAMPLE_DATA",
            "TURN_RUNNER_CHECKPOINT_NO_INTERNAL_ENDPOINT",
        ),
    }


def _multi_agent_state(attempts: tuple[Mapping[str, Any], ...]) -> dict[str, dict[str, Any]]:
    """抽取 Agent 角色状态，供 `recover_multi_agent_state` 恢复。

    每个 Agent 只保留 role/status/resumeAction 和计数类信息。`managerToolName`、工具参数、prompt、
    work item 原始 payload 均不进入 checkpoint。
    """

    result: dict[str, dict[str, Any]] = {
        "MASTER_ORCHESTRATOR": {"status": "OBSERVING_TURN_RUNNER"},
    }
    for attempt in attempts[:32]:
        role = _text(attempt.get("agentRole"))
        if not role:
            continue
        result[role] = {
            "status": _text(attempt.get("turnStatus")) or "UNKNOWN",
            "deliveryTier": _text(attempt.get("deliveryTier")),
            "resumeAction": _text(attempt.get("resumeAction")),
            "plannedToolCount": _non_negative_int(attempt.get("plannedToolCount")),
            "visibleSkillCount": _non_negative_int(attempt.get("visibleSkillCount")),
            "memoryDependencyCount": _non_negative_int(attempt.get("memoryDependencyCount")),
            "requiredEvidenceCodes": _limited_text_tuple(attempt.get("requiredEvidenceCodes"), limit=12),
        }
    return result


def _collaboration_edges(
    attempts: tuple[Mapping[str, Any], ...],
    knowledge_capabilities: tuple[Mapping[str, Any], ...],
) -> tuple[dict[str, str], ...]:
    """构造低敏协作边。

    协作边用于表达“主控可以调度哪些 specialist Agent”以及“知识 Agent 提供 RAG 证据支撑”。这些边只记录
    role 与边类型，不记录 tool arguments 或模型输出。
    """

    edges: list[dict[str, str]] = []
    for attempt in attempts[:32]:
        role = _text(attempt.get("agentRole"))
        if role and role != "MASTER_ORCHESTRATOR":
            edges.append(
                {
                    "fromRole": "MASTER_ORCHESTRATOR",
                    "toRole": role,
                    "edgeType": "manager_as_tools_turn_candidate",
                }
            )
    if knowledge_capabilities:
        edges.append(
            {
                "fromRole": "KNOWLEDGE_AGENT",
                "toRole": "MASTER_ORCHESTRATOR",
                "edgeType": "rag_evidence_capability_available",
            }
        )
    return tuple(edges[:64])


def _resume_requirements(agent_turn_runner: Mapping[str, Any]) -> dict[str, Any]:
    """把 requiredEvidenceCodes 聚合为 checkpoint 恢复要求。"""

    codes: dict[str, str] = {}
    for attempt in _sequence(agent_turn_runner.get("turnAttempts")):
        if not isinstance(attempt, Mapping):
            continue
        for code in _limited_text_tuple(attempt.get("requiredEvidenceCodes"), limit=24):
            codes[code] = "required"
    return {
        "requiredEvidenceCodes": tuple(sorted(codes)),
        "javaControlPlaneRequired": True,
        "workerReceiptRequired": "WORKER_RECEIPT_REQUIRED" in codes,
        "payloadPolicy": "LOW_SENSITIVE_RESUME_REQUIREMENTS_CODES_ONLY",
    }


def _checkpoint_status(run_status: str) -> LangGraphCheckpointStatus:
    """把 turn runner runStatus 映射为 LangGraph checkpoint status。"""

    normalized = run_status.upper()
    if normalized.startswith("BLOCKED"):
        return LangGraphCheckpointStatus.FAILED
    if "APPROVAL" in normalized or "HUMAN" in normalized or "DRAFT_ONLY" in normalized:
        return LangGraphCheckpointStatus.WAITING_HUMAN
    if "CONTROL_PLANE" in normalized or "SPECIALIST" in normalized or "WORKER" in normalized:
        return LangGraphCheckpointStatus.WAITING_TOOL
    if normalized in {"NO_AGENT_TURN_CANDIDATES", "TURN_RUNNER_OBSERVED_ONLY"}:
        return LangGraphCheckpointStatus.COMPLETED
    return LangGraphCheckpointStatus.RUNNING


def _node_name(run_status: str) -> str:
    """根据 runStatus 选择当前 checkpoint 节点名。"""

    normalized = run_status.upper()
    if "APPROVAL" in normalized or "HUMAN" in normalized:
        return "multi_agent_turn_wait_human"
    if "CONTROL_PLANE" in normalized:
        return "multi_agent_turn_wait_control_plane"
    if "SPECIALIST" in normalized:
        return "multi_agent_turn_wait_specialist"
    if normalized.startswith("BLOCKED"):
        return "multi_agent_turn_blocked"
    if normalized in {"NO_AGENT_TURN_CANDIDATES", "TURN_RUNNER_OBSERVED_ONLY"}:
        return "multi_agent_turn_observed"
    return "multi_agent_turn_checkpointed"


def _next_nodes(run_status: str) -> tuple[str, ...]:
    """为恢复/分支提供下一节点候选。"""

    normalized = run_status.upper()
    if "APPROVAL" in normalized or "HUMAN" in normalized:
        return ("wait_approval_fact", "resume_after_human_decision")
    if "CONTROL_PLANE" in normalized:
        return ("prepare_control_plane_handoff", "wait_worker_receipt")
    if "SPECIALIST" in normalized:
        return ("prepare_specialist_turn", "collect_specialist_summary")
    if normalized.startswith("BLOCKED"):
        return ("recover_multi_agent_state", "human_review")
    return ()


def _handoff_required(
    attempts: tuple[Mapping[str, Any], ...],
    agent_turn_runner: Mapping[str, Any],
) -> bool:
    """判断是否存在需要 Java 控制面/人审/worker receipt 的 handoff。"""

    if _non_negative_int(agent_turn_runner.get("controlPlaneHandoffCount")) > 0:
        return True
    for attempt in attempts:
        codes = set(_limited_text_tuple(attempt.get("requiredEvidenceCodes"), limit=24))
        if codes & {"APPROVAL_DECISION_FACT_REQUIRED", "WORKER_RECEIPT_REQUIRED", "JAVA_COMMAND_PROPOSAL_OR_OUTBOX_REQUIRED"}:
            return True
    return False


def _summary(run_status: str, status: LangGraphCheckpointStatus) -> str:
    """生成低敏 checkpoint 摘要。"""

    return f"多 Agent turn runner 已记录 checkpoint，runStatus={run_status or 'UNKNOWN'}，checkpointStatus={status.value}。"


def _thread_id(request: AgentRequest, plan: AgentPlan) -> str:
    """生成 turn runner threadId。

    优先使用 sessionId/runId/requestId 这类控制面标识，不使用 `request.objective`，避免把用户自然语言目标写入
    checkpoint key 或 PostgreSQL 索引。
    """

    stable = _session_id(request) or _run_id(request, plan) or plan.request_id
    return f"turn-runner:{_safe_key_part(stable)}"


def _checkpoint_id(thread_id: str, marker: str) -> str:
    """生成 checkpoint id。"""

    return f"lgcp:turn:{_safe_key_part(thread_id)}:{_safe_key_part(marker)}:{uuid4().hex[:12]}"


def _workspace_key(request: AgentRequest) -> str | None:
    """读取 workspace key。"""

    return _text_or_none(
        request.variables.get("workspaceKey")
        or request.variables.get("workspace_key")
        or request.variables.get("workspaceId")
    )


def _session_id(request: AgentRequest) -> str | None:
    """读取 sessionId。"""

    return _text_or_none(request.variables.get("sessionId") or request.variables.get("session_id"))


def _run_id(request: AgentRequest, plan: AgentPlan) -> str:
    """读取 runId，缺失时使用 plan.request_id 兜底。"""

    return _text_or_none(request.variables.get("runId") or request.variables.get("run_id")) or plan.request_id


def _sequence(value: Any) -> tuple[Any, ...]:
    """把列表/元组统一为 tuple。"""

    if isinstance(value, tuple):
        return value
    if isinstance(value, list):
        return tuple(value)
    return ()


def _limited_text_tuple(value: Any, *, limit: int) -> tuple[str, ...]:
    """读取低敏短文本 tuple。"""

    result: list[str] = []
    for item in _sequence(value):
        text = _text(item)
        if text:
            result.append(text)
        if len(result) >= limit:
            break
    return tuple(result)


def _non_negative_int(value: Any) -> int:
    """读取非负整数。"""

    if isinstance(value, int):
        return max(0, value)
    try:
        return max(0, int(str(value).strip()))
    except (TypeError, ValueError):
        return 0


def _text_or_none(value: Any) -> str | None:
    """读取可选文本。"""

    text = _text(value)
    return text or None


def _text(value: Any) -> str:
    """读取低敏短文本。"""

    if value is None:
        return ""
    return str(value).strip()[:256]


def _safe_key_part(value: Any) -> str:
    """把外部 ID 压成 checkpoint key 可用片段。"""

    text = _text(value) or "unknown"
    return "".join(char if char.isalnum() or char in "_.:-" else "_" for char in text)[:96] or "unknown"


__all__ = [
    "LANGGRAPH_MULTI_AGENT_TURN_RUNNER_GRAPH_NAME",
    "LANGGRAPH_MULTI_AGENT_TURN_RUNNER_GRAPH_VERSION",
    "record_multi_agent_turn_runner_checkpoint",
]
