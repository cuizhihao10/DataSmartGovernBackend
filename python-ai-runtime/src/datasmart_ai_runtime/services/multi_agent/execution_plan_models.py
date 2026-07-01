"""多智能体执行前计划的数据模型。

本模块只定义低敏 DTO / dataclass，不包含 LangGraph 编译逻辑，也不包含具体业务规则。拆分它的原因是：
- `langgraph_execution_plan.py` 应专注于图节点和流程编排；
- 工作项、协作边、诊断摘要属于稳定响应合同，后续 Java projection、gateway 或文档都可能复用；
- 单文件保持在 500 行以内，避免执行计划能力继续膨胀成难维护的巨型模块。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, TypedDict


class MultiAgentExecutionPlanState(TypedDict, total=False):
    """LangGraph 执行计划图中的低敏共享状态。

    字段说明：
    - `agentViews`：来自 `agentSessionScheduling.participatingAgents` 的裁剪视图；
    - `workItems`：每个 Agent 的执行前工作项，不包含工具参数或业务正文；
    - `collaborationEdges`：Agent 之间的委派、守门、上下文支持和汇报关系；
    - `executionPolicy`：统一副作用边界，明确 Python 图不执行工具、不写 outbox；
    - `globalState`：图内聚合出的全局状态摘要，可被前端或 Java projection 安全展示。
    """

    trace: tuple[str, ...]
    schedulingStatus: str
    primaryAgentRole: str
    handoffRequired: bool
    plannedToolNames: tuple[str, ...]
    visibleSkillCodes: tuple[str, ...]
    memoryDependencies: tuple[str, ...]
    workflowStatus: str
    agentViews: tuple[dict[str, Any], ...]
    workItems: tuple["MultiAgentExecutionWorkItem", ...]
    collaborationEdges: tuple["MultiAgentExecutionEdge", ...]
    handoffContracts: tuple["MultiAgentHandoffContract", ...]
    executionPolicy: dict[str, Any]
    globalState: dict[str, Any]
    planStatus: str
    nextActions: tuple[str, ...]


@dataclass(frozen=True)
class MultiAgentExecutionWorkItem:
    """单个 Agent 的执行前工作项。

    这个对象是“多 Agent 能力从角色列表走向可执行计划”的关键契约。它仍然不执行任务，但会说明：
    - 该 Agent 承担什么责任；
    - 需要哪些上游 Agent 的低敏结果；
    - 能看到哪些 Skill / 工具名 / 记忆类型；
    - 当前是否因为审批、权限、预算或上下文不足而停在执行前。
    """

    agent_role: str
    participation_mode: str
    status: str
    responsibility: str
    execution_lane: str
    depends_on_roles: tuple[str, ...]
    planned_tool_names: tuple[str, ...]
    visible_skill_codes: tuple[str, ...]
    memory_dependencies: tuple[str, ...]
    handoff_required: bool
    blocked_by: tuple[str, ...]
    next_action: str
    payload_policy: str = "LOW_SENSITIVE_AGENT_WORK_ITEM_ONLY"

    def to_summary(self) -> dict[str, Any]:
        """输出低敏工作项摘要，显式白名单字段，避免未来误暴露内部诊断。"""

        return {
            "agentRole": self.agent_role,
            "participationMode": self.participation_mode,
            "status": self.status,
            "responsibility": self.responsibility,
            "executionLane": self.execution_lane,
            "dependsOnRoles": self.depends_on_roles,
            "plannedToolNames": self.planned_tool_names,
            "visibleSkillCodes": self.visible_skill_codes,
            "memoryDependencies": self.memory_dependencies,
            "handoffRequired": self.handoff_required,
            "blockedBy": self.blocked_by,
            "nextAction": self.next_action,
            "payloadPolicy": self.payload_policy,
        }


@dataclass(frozen=True)
class MultiAgentExecutionEdge:
    """Agent 之间的低敏协作边。

    边只表达“控制面关系”，不表达真实数据流。比如 `DATASOURCE_AGENT -> DATA_QUALITY_AGENT` 表示
    质量 Agent 需要元数据上下文，但并不意味着这里传输了表结构正文或样本数据。
    """

    from_role: str
    to_role: str
    edge_type: str
    reason_code: str
    payload_policy: str = "LOW_SENSITIVE_AGENT_EDGE_ONLY"

    def to_summary(self) -> dict[str, Any]:
        """输出低敏协作边摘要。"""

        return {
            "fromRole": self.from_role,
            "toRole": self.to_role,
            "edgeType": self.edge_type,
            "reasonCode": self.reason_code,
            "payloadPolicy": self.payload_policy,
        }


@dataclass(frozen=True)
class MultiAgentHandoffContract:
    """多 Agent specialist handoff 的低敏控制面合同。

    为什么需要单独的 handoff contract：
    - work item 说明“某个 Agent 现在要做什么”；
    - collaboration edge 说明“Agent 之间有什么协作关系”；
    - handoff contract 则说明“当某个 Agent 需要把控制权交给权限、人审、运维恢复或 Java 控制面时，
      下游应该等待哪些 host fact，下一步动作是什么，Python Runtime 不允许做哪些副作用”。

    这个对象是给前端、gateway、Java projection 和审计系统看的合同摘要，不是执行命令：
    它不包含 prompt、SQL、工具参数、样本数据、模型输出、真实审批 ID、checkpointId 或 commandId。
    """

    contract_id: str
    source_agent_role: str
    target_agent_role: str
    handoff_type: str
    status: str
    reason_codes: tuple[str, ...]
    required_host_fact_types: tuple[str, ...]
    missing_evidence_codes: tuple[str, ...]
    next_action: str
    source_work_item_status: str
    side_effect_boundary: dict[str, Any]
    payload_policy: str = "LOW_SENSITIVE_MULTI_AGENT_HANDOFF_CONTRACT_ONLY"

    def to_summary(self) -> dict[str, Any]:
        """输出可安全返回给 `/agent/plans` 的 handoff 合同摘要。"""

        return {
            "contractId": self.contract_id,
            "sourceAgentRole": self.source_agent_role,
            "targetAgentRole": self.target_agent_role,
            "handoffType": self.handoff_type,
            "status": self.status,
            "reasonCodes": self.reason_codes,
            "requiredHostFactTypes": self.required_host_fact_types,
            "missingEvidenceCodes": self.missing_evidence_codes,
            "nextAction": self.next_action,
            "sourceWorkItemStatus": self.source_work_item_status,
            "sideEffectBoundary": self.side_effect_boundary,
            "payloadPolicy": self.payload_policy,
        }


@dataclass(frozen=True)
class MultiAgentExecutionPlanDiagnostics:
    """LangGraph 多智能体执行前计划诊断结果。

    诊断对象同时服务两类调用方：
    - 前端 / gateway：需要知道多 Agent 图是否执行、当前停在哪个门禁；
    - Java 控制面 / projection：需要稳定字段观察工作项、协作边和副作用边界。
    """

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
    plan_status: str
    work_items: tuple[MultiAgentExecutionWorkItem, ...]
    collaboration_edges: tuple[MultiAgentExecutionEdge, ...]
    handoff_contracts: tuple[MultiAgentHandoffContract, ...]
    execution_policy: dict[str, Any]
    global_state: dict[str, Any]
    next_actions: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """转换为 `/agent/plans` 可返回的低敏执行计划摘要。"""

        return {
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
            "nodeTrace": self.node_trace,
            "capabilities": {
                "agentWorkItemPlanning": self.executed and bool(self.work_items),
                "collaborationEdgePlanning": self.executed and bool(self.collaboration_edges),
                "specialistHandoffContractPlanning": self.executed and bool(self.handoff_contracts),
                "guardrailAwareExecutionBoundary": self.executed and bool(self.execution_policy),
                "globalStateManagement": self.executed and bool(self.global_state),
            },
            "planStatus": self.plan_status,
            "workItemCount": len(self.work_items),
            "collaborationEdgeCount": len(self.collaboration_edges),
            "handoffContractCount": len(self.handoff_contracts),
            "workItems": tuple(item.to_summary() for item in self.work_items),
            "collaborationEdges": tuple(edge.to_summary() for edge in self.collaboration_edges),
            "handoffContracts": tuple(contract.to_summary() for contract in self.handoff_contracts),
            "executionPolicy": self.execution_policy,
            "globalState": self.global_state,
            "nextActions": self.next_actions,
            "executionBoundary": "PRE_EXECUTION_MULTI_AGENT_PLAN_ONLY",
            "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_EXECUTION_PLAN_ONLY",
        }
