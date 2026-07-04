"""受控多 Agent turn runner 的低敏模型。

本文件只保存 DTO、StateGraph 状态和统一副作用边界，真正的 LangGraph 节点编排放在
`controlled_turn_runner.py`。这样拆分有两个目的：
- 模型合同可以被 API、runtime event、测试和未来 Java projection 文档共同引用；
- 编排文件不会因为字段说明和响应结构过多而再次突破 500 行。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, TypedDict


TURN_RUNNER_SCHEMA_VERSION = "datasmart.multi-agent.turn-runner.v1"


class MultiAgentTurnRunnerState(TypedDict, total=False):
    """LangGraph turn runner 图中的共享状态。

    状态字段只保存低敏控制面信息。尤其要注意：这里不保存 request objective、ToolPlan.arguments、
    SQL、样本值、模型输出、token 或内部 endpoint。真实执行需要的 payload 必须通过 Java 控制面中的
    payloadReference、graphId/contractId、approvalConfirmationId、worker receipt 等受控事实补齐。
    """

    requestId: str
    runId: str
    sessionId: str
    trace: tuple[str, ...]
    sessionStatus: str
    durablePhase: str
    durableResumeAction: str | None
    workItems: tuple[Mapping[str, Any], ...]
    commandTemplateCount: int
    javaProposalRoutes: tuple[str, ...]
    maxTurnDepth: int
    maxConcurrentAgentTurns: int
    currentTurnDepth: int
    runnerRoute: str
    runnerStatus: str
    loopDecision: str
    turnAttempts: tuple["ControlledAgentTurnAttempt", ...]
    managerAsTools: tuple[dict[str, Any], ...]
    knowledgeAgentCapabilities: tuple[dict[str, Any], ...]
    runnerPolicy: dict[str, Any]
    runStatus: str
    nextActions: tuple[str, ...]


@dataclass(frozen=True)
class ControlledAgentTurnAttempt:
    """单个 Agent work item 在下一轮 turn 中的推进尝试。

    字段说明：
    - `turn_id`：低敏稳定 ID，只由 turn 深度、workItemId 和角色生成，不携带租户、用户或业务主键；
    - `turn_status`：本 attempt 当前可推进状态，例如等待审批、等待控制面反馈、可交 Java proposal；
    - `manager_tool_name`：manager-as-tools 视角下的虚拟工具名，用于描述“主控如何调度子 Agent”，不是
      已注册可执行工具，也不会在 Python 中调用；
    - `required_evidence_codes`：真正执行前必须补齐的 host fact 编码；
    - `control_plane_route_hints`：低敏路由提示，帮助调用方知道应该进入 Java 哪类控制面接口；
    - `side_effect_boundary`：再次声明本 runner 不产生副作用。
    """

    turn_id: str
    work_item_id: str
    agent_role: str
    delivery_tier: str
    turn_status: str
    resume_action: str
    execution_lane: str
    manager_tool_name: str
    required_evidence_codes: tuple[str, ...]
    waiting_reason_codes: tuple[str, ...]
    blocked_by: tuple[str, ...]
    planned_tool_count: int
    visible_skill_count: int
    memory_dependency_count: int
    control_plane_route_hints: tuple[str, ...]
    payload_policy: str = "LOW_SENSITIVE_AGENT_TURN_ATTEMPT_ONLY"

    def to_summary(self) -> dict[str, Any]:
        """输出 API/runtime event 可安全展示的 turn attempt。"""

        return {
            "turnId": self.turn_id,
            "workItemId": self.work_item_id,
            "agentRole": self.agent_role,
            "deliveryTier": self.delivery_tier,
            "turnStatus": self.turn_status,
            "resumeAction": self.resume_action,
            "executionLane": self.execution_lane,
            "managerToolName": self.manager_tool_name,
            "requiredEvidenceCodes": self.required_evidence_codes,
            "waitingReasonCodes": self.waiting_reason_codes,
            "blockedBy": self.blocked_by,
            "plannedToolCount": self.planned_tool_count,
            "visibleSkillCount": self.visible_skill_count,
            "memoryDependencyCount": self.memory_dependency_count,
            "controlPlaneRouteHints": self.control_plane_route_hints,
            "sideEffectBoundary": side_effect_boundary(),
            "payloadPolicy": self.payload_policy,
        }


@dataclass(frozen=True)
class ControlledMultiAgentTurnRunnerDiagnostics:
    """受控多 Agent turn runner 的低敏诊断与推进合同。"""

    engine: str
    status: str
    enabled: bool
    dependency_available: bool
    compiled: bool
    executed: bool
    fallback_used: bool
    fallback_reason: str | None
    graph_nodes: tuple[str, ...]
    graph_edges: tuple[str, ...]
    node_trace: tuple[str, ...]
    run_status: str
    runner_route: str
    runner_status: str
    loop_decision: str
    session_status: str
    durable_phase: str
    current_turn_depth: int
    max_turn_depth: int
    max_concurrent_agent_turns: int
    turn_attempts: tuple[ControlledAgentTurnAttempt, ...]
    manager_as_tools: tuple[dict[str, Any], ...]
    knowledge_agent_capabilities: tuple[dict[str, Any], ...]
    runner_policy: dict[str, Any]
    next_actions: tuple[str, ...]
    runtime_graph_contract: Mapping[str, Any] | None = None

    def to_summary(self) -> dict[str, Any]:
        """转换为 `/agent/plans` 顶层字段和 runtime event 可复用的低敏摘要。"""

        attempts = tuple(item.to_summary() for item in self.turn_attempts)
        return {
            "schemaVersion": TURN_RUNNER_SCHEMA_VERSION,
            "engine": self.engine,
            "status": self.status,
            "enabled": self.enabled,
            "dependencyAvailable": self.dependency_available,
            "compiled": self.compiled,
            "executed": self.executed,
            "fallbackUsed": self.fallback_used,
            "fallbackReason": self.fallback_reason,
            "graphNodes": self.graph_nodes,
            "graphEdges": self.graph_edges,
            "runtimeGraphContract": dict(self.runtime_graph_contract or {}),
            "nodeTrace": self.node_trace,
            "capabilities": {
                "durableTurnStatePlanning": self.executed and bool(attempts),
                "managerAsToolsPlanning": self.executed and bool(self.manager_as_tools),
                "controlPlaneHandoffPlanning": self.executed and any(
                    attempt["controlPlaneRouteHints"] for attempt in attempts
                ),
                "knowledgeRagPlanning": self.executed and bool(self.knowledge_agent_capabilities),
                "sideEffectGuardrails": bool(self.runner_policy.get("javaControlPlaneRequiredForSideEffects")),
            },
            "runStatus": self.run_status,
            "runnerRoute": self.runner_route,
            "runnerStatus": self.runner_status,
            "loopDecision": self.loop_decision,
            "sessionStatus": self.session_status,
            "durablePhase": self.durable_phase,
            "currentTurnDepth": self.current_turn_depth,
            "maxTurnDepth": self.max_turn_depth,
            "maxConcurrentAgentTurns": self.max_concurrent_agent_turns,
            "turnAttemptCount": len(attempts),
            "waitingAttemptCount": sum(1 for item in attempts if str(item["turnStatus"]).startswith("WAITING")),
            "blockedAttemptCount": sum(1 for item in attempts if str(item["turnStatus"]).startswith("BLOCKED")),
            "controlPlaneHandoffCount": sum(
                1 for item in attempts if "JAVA_COMMAND_PROPOSAL_OR_OUTBOX_REQUIRED" in item["requiredEvidenceCodes"]
            ),
            "knowledgeAgentCapabilityCount": len(self.knowledge_agent_capabilities),
            "turnAttempts": attempts,
            "managerAsTools": self.manager_as_tools,
            "knowledgeAgentCapabilities": self.knowledge_agent_capabilities,
            "runnerPolicy": self.runner_policy,
            "nextActions": self.next_actions,
            "sideEffectBoundary": side_effect_boundary(),
            "executionBoundary": "CONTROLLED_MULTI_AGENT_TURN_RUNNER_NO_SIDE_EFFECTS",
            "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_TURN_RUNNER_ONLY",
        }


def side_effect_boundary() -> dict[str, bool | str]:
    """统一副作用边界声明。"""

    return {
        "toolExecutedByPython": False,
        "modelCalledByTurnRunner": False,
        "outboxWrittenByPython": False,
        "approvalCreatedByPython": False,
        "workerDispatchedByPython": False,
        "checkpointMutatedByTurnRunner": False,
        "javaControlPlaneRequiredForSideEffects": True,
        "workerReceiptRequiredForSideEffects": True,
        "payloadPolicy": "LOW_SENSITIVE_CONTROL_PLANE_FACTS_ONLY",
    }
