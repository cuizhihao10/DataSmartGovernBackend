"""LangGraph-backed 长期记忆检索可观测工作流。

本模块把 `AgentOrchestrator.plan()` 内部的 `retrieve_memory` 步骤推进为一个最小 LangGraph 可观测节点。
它不重写召回算法，不重复访问 Chroma/MySQL/SQLite FTS，也不返回长期记忆正文；真实召回仍由已有
`AgentMemoryRetriever` 完成。本工作流只消费 `AgentMemoryPlan`、`AgentMemoryRetrievalReport`、
workspace 上下文和多 Agent 调度摘要，把它们压缩为可观察、可审计、可回放的低敏图状态。

生产边界：
- 不输出 prompt、objective、queryHint、reason、SQL、工具参数、样本数据、模型输出、token 或记忆正文；
- 不执行工具、不写 outbox、不创建审批、不调用模型、不写入或物化长期记忆；
- MEMORY_AGENT 在这里是上下文治理角色，不是副作用执行器。
"""

from __future__ import annotations

import os
from typing import Any, Mapping, Protocol

from datasmart_ai_runtime.domain.memory import AgentMemoryPlan, AgentMemoryRetrievalReport
from datasmart_ai_runtime.services.agent_workspace import AgentWorkspaceContext
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.memory.langgraph_memory_retrieval_models import (
    LangGraphMemoryRetrievalWorkflowDiagnostics,
    MemoryRetrievalWorkflowState,
)


class _CompiledGraph(Protocol):
    """LangGraph `compile()` 后对象的最小协议，方便单测注入 fake graph。"""

    def invoke(self, input: dict[str, Any]) -> dict[str, Any]:
        """执行已编译图并返回最终低敏状态。"""


class _StateGraph(Protocol):
    """StateGraph 的最小协议。"""

    def add_node(self, node: str, action: Any) -> None:
        """注册节点函数。"""

    def add_edge(self, start_key: str, end_key: str) -> None:
        """注册固定有向边。"""

    def compile(self) -> _CompiledGraph:
        """编译为可执行图。"""


class LangGraphMemoryRetrievalWorkflow:
    """使用 LangGraph 表达长期记忆检索的观察型节点链路。

    图节点职责：
    - `load_memory_retrieval_context`：接收已完成的记忆计划与召回报告；
    - `evaluate_retrieval_scope`：统计记忆类型、scope、可写类型和 workspace 边界；
    - `summarize_retrieval_report`：统计召回数量、空召回数量和跳过数量；
    - `bind_memory_agent_context`：把 MEMORY_AGENT 与其他 Agent 的上下文支持关系显式化；
    - `finalize_memory_retrieval`：汇总全局状态、能力状态和下一步建议。
    """

    GRAPH_NODES = (
        "load_memory_retrieval_context",
        "evaluate_retrieval_scope",
        "summarize_retrieval_report",
        "bind_memory_agent_context",
        "finalize_memory_retrieval",
    )
    GRAPH_EDGES = (
        "START->load_memory_retrieval_context",
        "load_memory_retrieval_context->evaluate_retrieval_scope",
        "evaluate_retrieval_scope->summarize_retrieval_report",
        "summarize_retrieval_report->bind_memory_agent_context",
        "bind_memory_agent_context->finalize_memory_retrieval",
        "finalize_memory_retrieval->END",
    )

    def __init__(
        self,
        *,
        enabled: bool = True,
        fail_closed: bool = False,
        langgraph_api: LangGraphApi | None = None,
    ) -> None:
        self._enabled = enabled
        self._fail_closed = fail_closed
        self._langgraph_api = langgraph_api

    @classmethod
    def from_env(cls) -> "LangGraphMemoryRetrievalWorkflow":
        """从环境变量构建默认工作流。

        - `DATASMART_AI_LANGGRAPH_MEMORY_RETRIEVAL_ENABLED`：默认 true，控制是否输出记忆图诊断；
        - `DATASMART_AI_LANGGRAPH_MEMORY_RETRIEVAL_FAIL_CLOSED`：默认 false，收敛阶段保持 fail-open，避免可选
          LangGraph 依赖缺失时阻断已有 Agent plan 能力。
        """

        return cls(
            enabled=_truthy_env("DATASMART_AI_LANGGRAPH_MEMORY_RETRIEVAL_ENABLED", default=True),
            fail_closed=_truthy_env("DATASMART_AI_LANGGRAPH_MEMORY_RETRIEVAL_FAIL_CLOSED", default=False),
        )

    def run(
        self,
        *,
        memory_plan: AgentMemoryPlan,
        retrieval_report: AgentMemoryRetrievalReport,
        workspace_context: AgentWorkspaceContext,
        scheduling: Mapping[str, Any],
        collaboration_execution_plan: Mapping[str, Any],
    ) -> LangGraphMemoryRetrievalWorkflowDiagnostics:
        """运行长期记忆检索观察图。

        本方法只读取已存在的低敏计划事实。这样可以避免同一次请求被重复召回、重复计数或引入额外外部存储
        IO。后续如果要把“真实召回执行”也迁入 LangGraph durable runner，应先设计 checkpoint、重试、
        跨实例恢复和 Java host fact 对齐，而不是在当前收敛阶段直接替换主链路。
        """

        if not self._enabled:
            return self._diagnostics(
                status="DISABLED",
                dependency_available=False,
                compiled=False,
                executed=False,
                state={},
                fallback_used=True,
                fallback_reason="LANGGRAPH_MEMORY_RETRIEVAL_DISABLED",
            )

        api = self._langgraph_api or self._import_langgraph_api()
        if api is None:
            if self._fail_closed:
                raise RuntimeError("LangGraph dependency is required for memory retrieval workflow.")
            return self._diagnostics(
                status="DEPENDENCY_MISSING",
                dependency_available=False,
                compiled=False,
                executed=False,
                state={},
                fallback_used=True,
                fallback_reason="INSTALL_python_ai_runtime_api_OR_langgraph",
            )

        try:
            graph = self._compile_graph(api)
            result = graph.invoke(
                self._initial_state(
                    memory_plan=memory_plan,
                    retrieval_report=retrieval_report,
                    workspace_context=workspace_context,
                    scheduling=scheduling,
                    collaboration_execution_plan=collaboration_execution_plan,
                )
            )
        except Exception as exc:
            if self._fail_closed:
                raise RuntimeError("LangGraph memory retrieval workflow failed.") from exc
            return self._diagnostics(
                status="EXECUTION_FAILED",
                dependency_available=True,
                compiled=False,
                executed=False,
                state={},
                fallback_used=True,
                fallback_reason=exc.__class__.__name__,
            )

        return self._diagnostics(
            status="LANGGRAPH_MEMORY_RETRIEVAL_OBSERVED",
            dependency_available=True,
            compiled=True,
            executed=True,
            state=result,
            fallback_used=False,
            fallback_reason=None,
        )

    def _import_langgraph_api(self) -> LangGraphApi | None:
        """延迟导入 LangGraph，保证核心离线测试不强依赖可选 API 包。"""

        try:
            from langgraph.graph import END, START, StateGraph
        except ImportError:
            return None
        return LangGraphApi(state_graph=StateGraph, start=START, end=END)

    def _compile_graph(self, api: LangGraphApi) -> _CompiledGraph:
        """构建并编译长期记忆检索观察图。"""

        builder: _StateGraph = api.state_graph(MemoryRetrievalWorkflowState)
        builder.add_node("load_memory_retrieval_context", self._load_memory_retrieval_context)
        builder.add_node("evaluate_retrieval_scope", self._evaluate_retrieval_scope)
        builder.add_node("summarize_retrieval_report", self._summarize_retrieval_report)
        builder.add_node("bind_memory_agent_context", self._bind_memory_agent_context)
        builder.add_node("finalize_memory_retrieval", self._finalize_memory_retrieval)
        builder.add_edge(api.start, "load_memory_retrieval_context")
        builder.add_edge("load_memory_retrieval_context", "evaluate_retrieval_scope")
        builder.add_edge("evaluate_retrieval_scope", "summarize_retrieval_report")
        builder.add_edge("summarize_retrieval_report", "bind_memory_agent_context")
        builder.add_edge("bind_memory_agent_context", "finalize_memory_retrieval")
        builder.add_edge("finalize_memory_retrieval", api.end)
        return builder.compile()

    def _initial_state(
        self,
        *,
        memory_plan: AgentMemoryPlan,
        retrieval_report: AgentMemoryRetrievalReport,
        workspace_context: AgentWorkspaceContext,
        scheduling: Mapping[str, Any],
        collaboration_execution_plan: Mapping[str, Any],
    ) -> MemoryRetrievalWorkflowState:
        """从领域对象提取低敏初始状态。"""

        targets = tuple(memory_plan.retrieval_targets)
        results = tuple(retrieval_report.results)
        policy_axes = scheduling.get("policyAxes") if isinstance(scheduling.get("policyAxes"), Mapping) else {}
        return {
            "trace": (),
            "targetMemoryTypes": tuple(target.memory_type.value for target in targets),
            "targetMemoryScopes": tuple(target.scope.value for target in targets),
            "targetMaxItems": tuple(target.max_items for target in targets),
            "writableMemoryTypes": tuple(memory_type.value for memory_type in memory_plan.writable_memory_types),
            "defaultScope": memory_plan.default_scope.value,
            "retentionDays": int(memory_plan.retention_days),
            "approvalRequiredForWrite": bool(memory_plan.approval_required_for_write),
            "auditRequired": bool(memory_plan.audit_required),
            "retrievalResultCount": len(results),
            "retrievedCount": int(retrieval_report.total_retrieved),
            "skippedResultCount": sum(1 for result in results if result.skipped_reason),
            "emptyResultCount": sum(1 for result in results if not result.memories),
            "retrieverName": _safe_label(retrieval_report.attributes.get("retriever") or "custom"),
            "workspaceIsolationLevel": workspace_context.isolation_level.value,
            "workspaceMemoryNamespaceAvailable": bool(workspace_context.memory_namespace),
            "schedulingStatus": _string_value(scheduling.get("status")) or "UNKNOWN",
            "participatingAgentRoles": _participating_roles(scheduling),
            "memoryDependencies": _string_tuple(policy_axes.get("memoryDependencies")),
            "executionPlanStatus": _string_value(collaboration_execution_plan.get("planStatus")) or "UNKNOWN",
        }

    def _load_memory_retrieval_context(
        self,
        state: MemoryRetrievalWorkflowState,
    ) -> MemoryRetrievalWorkflowState:
        """接收已经完成的记忆计划与召回报告。"""

        return self._append_trace(state, "langgraph.memory_retrieval.load_memory_retrieval_context")

    def _evaluate_retrieval_scope(
        self,
        state: MemoryRetrievalWorkflowState,
    ) -> MemoryRetrievalWorkflowState:
        """统计本轮允许观察的记忆类型、scope 和 workspace 边界。"""

        updated = self._append_trace(state, "langgraph.memory_retrieval.evaluate_retrieval_scope")
        target_types = tuple(state.get("targetMemoryTypes") or ())
        target_scopes = tuple(state.get("targetMemoryScopes") or ())
        updated["retrievalScope"] = {
            "targetCount": len(target_types),
            "memoryTypeCounts": _count_values(target_types),
            "memoryScopeCounts": _count_values(target_scopes),
            "targetDescriptors": _target_descriptors(
                target_types,
                target_scopes,
                tuple(state.get("targetMaxItems") or ()),
            ),
            "defaultScope": state.get("defaultScope") or "project",
            "writableMemoryTypes": tuple(state.get("writableMemoryTypes") or ()),
            "writableMemoryTypeCount": len(tuple(state.get("writableMemoryTypes") or ())),
            "retentionDays": int(state.get("retentionDays") or 0),
            "approvalRequiredForWrite": bool(state.get("approvalRequiredForWrite")),
            "auditRequired": bool(state.get("auditRequired")),
            "workspaceIsolationLevel": state.get("workspaceIsolationLevel") or "UNKNOWN",
            "workspaceMemoryNamespaceAvailable": bool(state.get("workspaceMemoryNamespaceAvailable")),
        }
        return updated

    def _summarize_retrieval_report(
        self,
        state: MemoryRetrievalWorkflowState,
    ) -> MemoryRetrievalWorkflowState:
        """把真实召回报告压缩成不含正文的数量与状态摘要。"""

        updated = self._append_trace(state, "langgraph.memory_retrieval.summarize_retrieval_report")
        target_count = len(tuple(state.get("targetMemoryTypes") or ()))
        retrieved_count = int(state.get("retrievedCount") or 0)
        if target_count == 0:
            retrieval_status = "NO_RETRIEVAL_TARGETS"
        elif retrieved_count > 0:
            retrieval_status = "RETRIEVAL_AVAILABLE"
        elif int(state.get("skippedResultCount") or 0) > 0:
            retrieval_status = "RETRIEVAL_SKIPPED_OR_EMPTY"
        else:
            retrieval_status = "RETRIEVAL_EMPTY"
        updated["retrievalStatus"] = retrieval_status
        updated["retrievalReport"] = {
            "retrievalStatus": retrieval_status,
            "retrievalResultCount": int(state.get("retrievalResultCount") or 0),
            "retrievedCount": retrieved_count,
            "emptyResultCount": int(state.get("emptyResultCount") or 0),
            "skippedResultCount": int(state.get("skippedResultCount") or 0),
            "retriever": state.get("retrieverName") or "custom",
            "memoryContentReturned": False,
            "retrievalNotesReturned": False,
        }
        return updated

    def _bind_memory_agent_context(
        self,
        state: MemoryRetrievalWorkflowState,
    ) -> MemoryRetrievalWorkflowState:
        """把 MEMORY_AGENT 与其他 Agent 的上下文支持关系显式化。"""

        updated = self._append_trace(state, "langgraph.memory_retrieval.bind_memory_agent_context")
        roles = tuple(state.get("participatingAgentRoles") or ()) or ("MASTER_ORCHESTRATOR",)
        consumer_roles = tuple(role for role in roles if role != "MEMORY_AGENT")
        target_count = len(tuple(state.get("targetMemoryTypes") or ()))
        memory_dependencies = tuple(state.get("memoryDependencies") or ()) or tuple(
            sorted(set(state.get("targetMemoryTypes") or ()))
        )
        updated["multiAgentMemoryContext"] = {
            "memoryAgentRole": "MEMORY_AGENT",
            "memoryAgentScheduled": "MEMORY_AGENT" in roles,
            "memoryAgentRequired": target_count > 0 or bool(memory_dependencies),
            "consumerAgentRoles": consumer_roles,
            "contextEdgeType": "supports_context",
            "memoryDependencies": memory_dependencies,
            "schedulingStatus": state.get("schedulingStatus") or "UNKNOWN",
            "executionPlanStatus": state.get("executionPlanStatus") or "UNKNOWN",
            "handoffRequired": False,
            "handoffReasonCode": "READ_ONLY_MEMORY_RETRIEVAL_OBSERVATION",
            "payloadPolicy": "LOW_SENSITIVE_MEMORY_AGENT_CONTEXT_ONLY",
        }
        return updated

    def _finalize_memory_retrieval(
        self,
        state: MemoryRetrievalWorkflowState,
    ) -> MemoryRetrievalWorkflowState:
        """汇总长期记忆检索观察图的最终状态与下一步建议。"""

        updated = self._append_trace(state, "langgraph.memory_retrieval.finalize_memory_retrieval")
        retrieval_status = str(updated.get("retrievalStatus") or "UNKNOWN")
        updated["globalState"] = {
            "stateSchemaVersion": "datasmart.memory-retrieval.workflow-state.v1",
            "stateSource": "AgentMemoryPlan+AgentMemoryRetrievalReport+AgentSessionScheduling",
            "retrievalStatus": retrieval_status,
            "retrievalTargetCount": int(updated.get("retrievalScope", {}).get("targetCount") or 0),
            "retrievedCount": int(updated.get("retrievalReport", {}).get("retrievedCount") or 0),
            "workspaceMemoryNamespaceAvailable": bool(updated.get("workspaceMemoryNamespaceAvailable")),
            "memoryAgentScheduled": bool(updated.get("multiAgentMemoryContext", {}).get("memoryAgentScheduled")),
            "memoryAgentRequired": bool(updated.get("multiAgentMemoryContext", {}).get("memoryAgentRequired")),
            "checkpointPolicy": "SUMMARY_ONLY_NO_MEMORY_CONTENT_NO_QUERY_HINT_NO_PROMPT",
        }
        updated["nextActions"] = _next_actions_for_retrieval_status(retrieval_status)
        return updated

    @staticmethod
    def _append_trace(
        state: MemoryRetrievalWorkflowState,
        node_name: str,
    ) -> MemoryRetrievalWorkflowState:
        """返回追加节点 trace 后的新状态，避免原地修改共享状态。"""

        trace = tuple(state.get("trace") or ())
        return {**state, "trace": trace + (node_name,)}

    def _diagnostics(
        self,
        *,
        status: str,
        dependency_available: bool,
        compiled: bool,
        executed: bool,
        state: Mapping[str, Any],
        fallback_used: bool,
        fallback_reason: str | None,
    ) -> LangGraphMemoryRetrievalWorkflowDiagnostics:
        """构造统一诊断对象。"""

        return LangGraphMemoryRetrievalWorkflowDiagnostics(
            engine="langgraph",
            status=status,
            enabled=self._enabled,
            dependency_available=dependency_available,
            compiled=compiled,
            executed=executed,
            fallback_used=fallback_used,
            fallback_reason=fallback_reason,
            graph_nodes=self.GRAPH_NODES,
            graph_edges=self.GRAPH_EDGES,
            node_trace=tuple(state.get("trace") or ()),
            retrieval_status=str(state.get("retrievalStatus") or "NOT_EXECUTED"),
            retrieval_scope=dict(state.get("retrievalScope") or {}),
            retrieval_report=dict(state.get("retrievalReport") or {}),
            multi_agent_memory_context=dict(state.get("multiAgentMemoryContext") or {}),
            global_state=dict(state.get("globalState") or {}),
            next_actions=tuple(state.get("nextActions") or ()),
        )


def _truthy_env(name: str, *, default: bool) -> bool:
    """读取布尔环境变量。"""

    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def _participating_roles(scheduling: Mapping[str, Any]) -> tuple[str, ...]:
    """从 agentSessionScheduling 中读取参与 Agent 角色，只保留角色编码。"""

    roles: list[str] = []
    for item in scheduling.get("participatingAgents", ()):
        if not isinstance(item, Mapping):
            continue
        role = _string_value(item.get("role"))
        if role:
            roles.append(role)
    return tuple(dict.fromkeys(roles))


def _string_tuple(value: object | None) -> tuple[str, ...]:
    """把列表、集合或单值转换为字符串元组，并去除空值。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return (value,) if value else ()
    if isinstance(value, (tuple, list, set, frozenset)):
        return tuple(str(item).strip() for item in value if str(item).strip())
    text = str(value).strip()
    return (text,) if text else ()


def _string_value(value: object | None) -> str | None:
    """读取非空字符串值。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _safe_label(value: object) -> str:
    """把外部扩展字段压缩成安全标签，避免 endpoint、路径或长文本进入响应。"""

    text = str(value).strip().lower().replace(" ", "_")
    allowed = "".join(char for char in text if char.isalnum() or char in {"_", "-", "."})
    return allowed[:64] or "custom"


def _count_values(values: tuple[str, ...]) -> dict[str, int]:
    """统计字符串分布，并按 key 排序保证响应稳定。"""

    counts: dict[str, int] = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def _target_descriptors(
    memory_types: tuple[str, ...],
    scopes: tuple[str, ...],
    max_items: tuple[int, ...],
) -> tuple[dict[str, Any], ...]:
    """生成不含 queryHint/reason 的记忆目标描述符。"""

    descriptors: list[dict[str, Any]] = []
    for index, memory_type in enumerate(memory_types):
        descriptors.append(
            {
                "memoryType": memory_type,
                "scope": scopes[index] if index < len(scopes) else "unknown",
                "maxItems": max_items[index] if index < len(max_items) else 0,
            }
        )
    return tuple(descriptors)


def _next_actions_for_retrieval_status(retrieval_status: str) -> tuple[str, ...]:
    """根据召回状态生成下一步建议码。"""

    mapping = {
        "NO_RETRIEVAL_TARGETS": (
            "CONTINUE_WITHOUT_LONG_TERM_MEMORY",
            "REVIEW_SKILL_OR_INTENT_MEMORY_DEPENDENCIES_IF_CONTEXT_IS_EXPECTED",
        ),
        "RETRIEVAL_AVAILABLE": (
            "ALLOW_MEMORY_SUMMARY_TO_SUPPORT_SPECIALIST_AGENTS",
            "KEEP_MEMORY_CONTENT_IN_CONTEXT_LAYER_NOT_DIAGNOSTIC_RESPONSE",
        ),
        "RETRIEVAL_SKIPPED_OR_EMPTY": (
            "CHECK_MEMORY_NAMESPACE_SCOPE_AND_SESSION_FACTS",
            "ALLOW_SPECIALIST_AGENTS_TO_PREPARE_DRAFT_WITHOUT_MEMORY_CONTENT",
        ),
        "RETRIEVAL_EMPTY": (
            "CHECK_MEMORY_MATERIALIZATION_AND_SECONDARY_INDEX_SYNC",
            "ALLOW_SPECIALIST_AGENTS_TO_PREPARE_DRAFT_WITHOUT_MEMORY_CONTENT",
        ),
    }
    return mapping.get(retrieval_status, ("REVIEW_MEMORY_RETRIEVAL_WORKFLOW_STATUS",))
