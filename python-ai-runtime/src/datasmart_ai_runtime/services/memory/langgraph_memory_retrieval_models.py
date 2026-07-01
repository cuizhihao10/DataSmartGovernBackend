"""长期记忆检索 LangGraph 工作流的低敏模型。

本文件只承载状态 DTO 与诊断 DTO，不包含 LangGraph 编排逻辑。拆分的目的有两个：
- 让 `langgraph_memory_retrieval_workflow.py` 专注于节点流转，避免单文件超过 500 行；
- 让 HTTP 响应、测试、未来 Java projection 或指标模块可以复用稳定的低敏摘要结构。

这些模型刻意不保存长期记忆正文、queryHint、用户目标、工具参数或模型输出。长期记忆未来会连接向量库、
全文索引、图谱和审计表，如果诊断模型过宽，很容易把原本只应在上下文层短暂存在的信息扩散到日志和事件
回放系统中。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, TypedDict


class MemoryRetrievalWorkflowState(TypedDict, total=False):
    """LangGraph 记忆检索图中的低敏共享状态。

    字段设计原则：
    - 只保存“控制面事实”，例如记忆类型、scope、目标数量、召回数量和 Agent 角色；
    - 不保存 prompt、objective、queryHint、reason、SQL、工具参数、样本数据、模型输出或记忆正文；
    - workspace 只暴露“是否具备 memory namespace”和隔离等级，不复制真实 namespace；
    - 多 Agent 相关字段只使用角色编码，表达 MEMORY_AGENT 为其他 Agent 提供上下文支持。
    """

    trace: tuple[str, ...]
    targetMemoryTypes: tuple[str, ...]
    targetMemoryScopes: tuple[str, ...]
    targetMaxItems: tuple[int, ...]
    writableMemoryTypes: tuple[str, ...]
    defaultScope: str
    retentionDays: int
    approvalRequiredForWrite: bool
    auditRequired: bool
    retrievalResultCount: int
    retrievedCount: int
    skippedResultCount: int
    emptyResultCount: int
    retrieverName: str
    workspaceIsolationLevel: str
    workspaceMemoryNamespaceAvailable: bool
    schedulingStatus: str
    participatingAgentRoles: tuple[str, ...]
    memoryDependencies: tuple[str, ...]
    executionPlanStatus: str
    retrievalScope: dict[str, Any]
    retrievalReport: dict[str, Any]
    multiAgentMemoryContext: dict[str, Any]
    globalState: dict[str, Any]
    retrievalStatus: str
    nextActions: tuple[str, ...]


@dataclass(frozen=True)
class LangGraphMemoryRetrievalWorkflowDiagnostics:
    """长期记忆检索 LangGraph 图的低敏诊断结果。

    诊断对象进入 `/agent/plans.agentMemoryRetrievalWorkflow`。它回答三类问题：
    - LangGraph 记忆节点是否启用、编译、执行以及是否 fallback；
    - 本轮长期记忆计划和召回报告的低敏统计是什么；
    - MEMORY_AGENT 如何为其他专项 Agent 提供上下文支持，以及 Python Runtime 是否保持无副作用边界。
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
    retrieval_status: str
    retrieval_scope: dict[str, Any]
    retrieval_report: dict[str, Any]
    multi_agent_memory_context: dict[str, Any]
    global_state: dict[str, Any]
    next_actions: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """转换为可安全返回给 HTTP 调用方的摘要。

        这里显式白名单输出字段，而不是直接 `asdict()`，避免未来在诊断对象里新增内部排障字段后自动暴露到
        API 响应中。长期记忆链路尤其要谨慎，因为它未来会连接向量库、全文索引、图谱和审计表。
        """

        target_count = int(self.retrieval_scope.get("targetCount") or 0)
        retrieved_count = int(self.retrieval_report.get("retrievedCount") or 0)
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
                "langGraphMemoryRetrievalNode": self.executed,
                "retrievalPlanObserved": self.executed and target_count > 0,
                "retrievalReportObserved": self.executed,
                "workspaceMemoryBoundaryObserved": bool(self.global_state.get("workspaceMemoryNamespaceAvailable")),
                "multiAgentMemoryContextVisible": bool(
                    self.multi_agent_memory_context.get("consumerAgentRoles")
                ),
            },
            "retrievalStatus": self.retrieval_status,
            "retrievalTargetCount": target_count,
            "retrievedCount": retrieved_count,
            "retrievalScope": self.retrieval_scope,
            "retrievalReport": self.retrieval_report,
            "multiAgentMemoryContext": self.multi_agent_memory_context,
            "globalState": self.global_state,
            "sideEffectBoundary": {
                "memoryContentReturnedByThisGraph": False,
                "memoryWrittenByThisGraph": False,
                "memoryMaterializedByThisGraph": False,
                "toolExecuted": False,
                "outboxWritten": False,
                "approvalCreated": False,
                "modelCalledByThisGraph": False,
                "javaControlPlaneRequiredForMemoryWrite": True,
            },
            "nextActions": self.next_actions,
            "executionBoundary": "OBSERVE_RETRIEVE_MEMORY_ONLY",
            "payloadPolicy": "LOW_SENSITIVE_MEMORY_RETRIEVAL_GRAPH_ONLY",
        }
