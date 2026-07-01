"""LangGraph-backed 多智能体执行前协作计划。

本模块把 `agentSessionScheduling` 与上一层 `agentCollaborationWorkflow` 转换为可观察的执行前合同：
每个 Agent 的工作项、协作边、守门策略、全局状态和下一步动作。它仍然不执行工具、不调用模型、
不写 outbox、不创建审批单；真实副作用继续由 Java 控制面、permission-admin 和 worker receipt 承接。
"""

from __future__ import annotations

from typing import Any, Mapping, Protocol

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.multi_agent.execution_plan_models import (
    MultiAgentExecutionEdge,
    MultiAgentExecutionPlanDiagnostics,
    MultiAgentExecutionPlanState,
    MultiAgentExecutionWorkItem,
)
from datasmart_ai_runtime.services.multi_agent.execution_plan_rules import (
    append_edge,
    blocked_by,
    depends_on_roles,
    execution_lane,
    next_action_for_work_item,
    next_actions_for_plan_status,
    responsibility_for_role,
    sanitize_agent_view,
    string_tuple,
    string_value,
    truthy_env,
    work_item_status,
)


class _CompiledGraph(Protocol):
    """LangGraph compile 后对象的最小协议。"""

    def invoke(self, input: dict[str, Any]) -> dict[str, Any]:
        """执行图并返回最终低敏状态。"""


class _StateGraph(Protocol):
    """StateGraph 最小协议，方便单测注入 fake graph。"""

    def add_node(self, node: str, action: Any) -> None:
        """注册节点函数。"""

    def add_edge(self, start_key: str, end_key: str) -> None:
        """注册固定边。"""

    def compile(self) -> _CompiledGraph:
        """编译为可执行图。"""


class LangGraphMultiAgentExecutionPlanWorkflow:
    """使用 LangGraph 生成多智能体执行前协作计划。

    图节点职责：
    - `load_collaboration_context`：读取会话调度与协作诊断的低敏状态；
    - `assign_agent_work_items`：把参与 Agent 转换成可解释工作项；
    - `build_collaboration_edges`：构建委派、汇报、守门和上下文支持关系；
    - `enforce_execution_boundaries`：声明副作用边界并计算计划状态；
    - `finalize_execution_plan`：汇总全局状态和下一步动作。
    """

    GRAPH_NODES = (
        "load_collaboration_context",
        "assign_agent_work_items",
        "build_collaboration_edges",
        "enforce_execution_boundaries",
        "finalize_execution_plan",
    )
    GRAPH_EDGES = (
        "START->load_collaboration_context",
        "load_collaboration_context->assign_agent_work_items",
        "assign_agent_work_items->build_collaboration_edges",
        "build_collaboration_edges->enforce_execution_boundaries",
        "enforce_execution_boundaries->finalize_execution_plan",
        "finalize_execution_plan->END",
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
    def from_env(cls) -> "LangGraphMultiAgentExecutionPlanWorkflow":
        """从环境变量构建默认执行计划图。

        环境变量说明：
        - `DATASMART_AI_LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_ENABLED`：默认 true；
        - `DATASMART_AI_LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_FAIL_CLOSED`：默认 false。
        """

        return cls(
            enabled=truthy_env("DATASMART_AI_LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_ENABLED", default=True),
            fail_closed=truthy_env("DATASMART_AI_LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_FAIL_CLOSED", default=False),
        )

    def run(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        scheduling: Mapping[str, Any],
        collaboration: Mapping[str, Any],
    ) -> MultiAgentExecutionPlanDiagnostics:
        """运行执行前计划图。

        `request` 只用于保持接口与其他 workflow 一致；本图不会读取 `request.objective` 或敏感变量。
        """

        if not self._enabled:
            return self._diagnostics(
                status="DISABLED",
                dependency_available=False,
                compiled=False,
                executed=False,
                state={},
                fallback_used=True,
                fallback_reason="LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_DISABLED",
            )

        api = self._langgraph_api or self._import_langgraph_api()
        if api is None:
            if self._fail_closed:
                raise RuntimeError("LangGraph dependency is required for multi-agent execution plan workflow.")
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
                self._initial_state(request=request, plan=plan, scheduling=scheduling, collaboration=collaboration)
            )
        except Exception as exc:
            if self._fail_closed:
                raise RuntimeError("LangGraph multi-agent execution plan workflow failed.") from exc
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
            status="LANGGRAPH_MULTI_AGENT_EXECUTION_PLAN_BUILT",
            dependency_available=True,
            compiled=True,
            executed=True,
            state=result,
            fallback_used=False,
            fallback_reason=None,
        )

    def _import_langgraph_api(self) -> LangGraphApi | None:
        """延迟导入 LangGraph，避免离线单测强依赖可选框架包。"""

        try:
            from langgraph.graph import END, START, StateGraph
        except ImportError:
            return None
        return LangGraphApi(state_graph=StateGraph, start=START, end=END)

    def _compile_graph(self, api: LangGraphApi) -> _CompiledGraph:
        """构建并编译执行前协作计划 StateGraph。"""

        builder: _StateGraph = api.state_graph(MultiAgentExecutionPlanState)
        builder.add_node("load_collaboration_context", self._load_collaboration_context)
        builder.add_node("assign_agent_work_items", self._assign_agent_work_items)
        builder.add_node("build_collaboration_edges", self._build_collaboration_edges)
        builder.add_node("enforce_execution_boundaries", self._enforce_execution_boundaries)
        builder.add_node("finalize_execution_plan", self._finalize_execution_plan)
        builder.add_edge(api.start, "load_collaboration_context")
        builder.add_edge("load_collaboration_context", "assign_agent_work_items")
        builder.add_edge("assign_agent_work_items", "build_collaboration_edges")
        builder.add_edge("build_collaboration_edges", "enforce_execution_boundaries")
        builder.add_edge("enforce_execution_boundaries", "finalize_execution_plan")
        builder.add_edge("finalize_execution_plan", api.end)
        return builder.compile()

    def _initial_state(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        scheduling: Mapping[str, Any],
        collaboration: Mapping[str, Any],
    ) -> MultiAgentExecutionPlanState:
        """从计划响应上下文提取低敏初始状态。"""

        _ = request  # 明确不读取 objective/variables，避免执行计划误带用户原文。
        policy_axes = scheduling.get("policyAxes") if isinstance(scheduling.get("policyAxes"), Mapping) else {}
        agent_views = tuple(
            sanitize_agent_view(item)
            for item in scheduling.get("participatingAgents", ())
            if isinstance(item, Mapping)
        )
        return {
            "trace": (),
            "schedulingStatus": string_value(scheduling.get("status")) or "UNKNOWN",
            "primaryAgentRole": string_value(scheduling.get("primaryAgentRole")) or "MASTER_ORCHESTRATOR",
            "handoffRequired": bool(scheduling.get("handoffRequired")),
            "plannedToolNames": string_tuple(policy_axes.get("plannedToolNames"))
            or tuple(tool.tool_name for tool in plan.tool_plans),
            "visibleSkillCodes": string_tuple(policy_axes.get("visibleSkillCodes")),
            "memoryDependencies": string_tuple(policy_axes.get("memoryDependencies")),
            "workflowStatus": string_value(collaboration.get("status")) or "UNKNOWN",
            "agentViews": agent_views,
        }

    def _load_collaboration_context(self, state: MultiAgentExecutionPlanState) -> MultiAgentExecutionPlanState:
        """读取协作上下文并追加 LangGraph trace。"""

        return self._append_trace(state, "langgraph.multi_agent_execution.load_collaboration_context")

    def _assign_agent_work_items(self, state: MultiAgentExecutionPlanState) -> MultiAgentExecutionPlanState:
        """把参与 Agent 转换为执行前工作项。"""

        updated = self._append_trace(state, "langgraph.multi_agent_execution.assign_agent_work_items")
        agent_views = tuple(state.get("agentViews") or ())
        if not agent_views:
            agent_views = ({"role": state.get("primaryAgentRole") or "MASTER_ORCHESTRATOR", "participationMode": "PRIMARY"},)
        roles = tuple(string_value(item.get("role")) for item in agent_views if string_value(item.get("role")))
        updated["workItems"] = tuple(
            self._work_item_for_agent(agent, all_roles=roles, state=state)
            for agent in agent_views
            if string_value(agent.get("role"))
        )
        return updated

    def _build_collaboration_edges(self, state: MultiAgentExecutionPlanState) -> MultiAgentExecutionPlanState:
        """根据工作项构建低敏协作边。"""

        updated = self._append_trace(state, "langgraph.multi_agent_execution.build_collaboration_edges")
        roles = {item.agent_role for item in tuple(state.get("workItems") or ())}
        edges: list[MultiAgentExecutionEdge] = []
        for role in sorted(roles - {"MASTER_ORCHESTRATOR"}):
            if "MASTER_ORCHESTRATOR" in roles:
                append_edge(edges, "MASTER_ORCHESTRATOR", role, "delegates_to", "PRIMARY_COORDINATES_AGENT")
                append_edge(edges, role, "MASTER_ORCHESTRATOR", "reports_to", "AGENT_REPORTS_LOW_SENSITIVE_RESULT")
        if {"DATASOURCE_AGENT", "DATA_QUALITY_AGENT"}.issubset(roles):
            append_edge(edges, "DATASOURCE_AGENT", "DATA_QUALITY_AGENT", "provides_context_to", "QUALITY_NEEDS_METADATA_CONTEXT")
        if {"DATASOURCE_AGENT", "DATA_SYNC_AGENT"}.issubset(roles):
            append_edge(edges, "DATASOURCE_AGENT", "DATA_SYNC_AGENT", "provides_context_to", "SYNC_NEEDS_SOURCE_METADATA")
        if {"DATA_QUALITY_AGENT", "DATA_SYNC_AGENT"}.issubset(roles):
            append_edge(edges, "DATA_QUALITY_AGENT", "DATA_SYNC_AGENT", "validates_before", "SYNC_SHOULD_CONSIDER_QUALITY_GATES")
        if "PERMISSION_AGENT" in roles:
            for role in sorted(roles - {"PERMISSION_AGENT"}):
                append_edge(edges, role, "PERMISSION_AGENT", "guarded_by", "SIDE_EFFECT_BOUNDARY_GUARDED")
        if "MEMORY_AGENT" in roles:
            for role in sorted(roles - {"MEMORY_AGENT"}):
                append_edge(edges, "MEMORY_AGENT", role, "supports_context", "MEMORY_CONTEXT_AVAILABLE_AS_SUMMARY")
        if "OPS_AGENT" in roles:
            for role in sorted(roles - {"OPS_AGENT"}):
                append_edge(edges, role, "OPS_AGENT", "observed_by", "RUNTIME_DEGRADATION_OBSERVED")
        updated["collaborationEdges"] = tuple(edges[:40])
        return updated

    def _enforce_execution_boundaries(self, state: MultiAgentExecutionPlanState) -> MultiAgentExecutionPlanState:
        """声明副作用边界并计算计划状态。"""

        updated = self._append_trace(state, "langgraph.multi_agent_execution.enforce_execution_boundaries")
        work_items = tuple(state.get("workItems") or ())
        scheduling_status = state.get("schedulingStatus") or "UNKNOWN"
        if not work_items:
            plan_status = "NO_AGENT_WORK_ITEMS"
        elif any(item.status.startswith("BLOCKED") for item in work_items) or scheduling_status == "BLOCKED":
            plan_status = "BLOCKED_BEFORE_EXECUTION"
        elif bool(state.get("handoffRequired")) or any("WAITING" in item.status for item in work_items):
            plan_status = "WAITING_HUMAN_OR_PERMISSION_HANDOFF"
        elif scheduling_status == "DEGRADED":
            plan_status = "DEGRADED_CAN_PREPARE_DRAFT"
        else:
            plan_status = "READY_FOR_CONTROL_PLANE_HANDOFF"
        updated["planStatus"] = plan_status
        updated["executionPolicy"] = {
            "boundary": "PRE_EXECUTION_MULTI_AGENT_PLAN_ONLY",
            "toolExecuted": False,
            "outboxWritten": False,
            "approvalCreated": False,
            "modelCalledByThisGraph": False,
            "javaControlPlaneRequiredForSideEffects": True,
            "workerReceiptRequiredForSideEffects": True,
        }
        return updated

    def _finalize_execution_plan(self, state: MultiAgentExecutionPlanState) -> MultiAgentExecutionPlanState:
        """汇总全局状态和下一步建议。"""

        updated = self._append_trace(state, "langgraph.multi_agent_execution.finalize_execution_plan")
        work_items = tuple(state.get("workItems") or ())
        edges = tuple(state.get("collaborationEdges") or ())
        updated["globalState"] = {
            "stateSchemaVersion": "datasmart.multi-agent.execution-plan-state.v1",
            "stateSource": "agentSessionScheduling+agentCollaborationWorkflow+AgentPlan",
            "schedulingStatus": state.get("schedulingStatus") or "UNKNOWN",
            "workflowStatus": state.get("workflowStatus") or "UNKNOWN",
            "planStatus": state.get("planStatus") or "UNKNOWN",
            "workItemCount": len(work_items),
            "collaborationEdgeCount": len(edges),
            "handoffRequired": bool(state.get("handoffRequired")),
            "plannedToolCount": len(tuple(state.get("plannedToolNames") or ())),
            "checkpointPolicy": "SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT",
        }
        updated["nextActions"] = next_actions_for_plan_status(updated.get("planStatus") or "UNKNOWN")
        return updated

    def _work_item_for_agent(
        self,
        agent: Mapping[str, Any],
        *,
        all_roles: tuple[str, ...],
        state: MultiAgentExecutionPlanState,
    ) -> MultiAgentExecutionWorkItem:
        """根据会话调度中的单个 Agent 摘要生成执行前工作项。"""

        role = string_value(agent.get("role")) or "UNKNOWN"
        mode = string_value(agent.get("participationMode")) or "UNKNOWN"
        agent_status = string_value(agent.get("status")) or state.get("schedulingStatus") or "UNKNOWN"
        handoff_required = bool(agent.get("requiresHandoff")) or bool(state.get("handoffRequired"))
        status = work_item_status(state.get("schedulingStatus") or "UNKNOWN", agent_status, handoff_required)
        return MultiAgentExecutionWorkItem(
            agent_role=role,
            participation_mode=mode,
            status=status,
            responsibility=responsibility_for_role(role),
            execution_lane=execution_lane(role, mode),
            depends_on_roles=depends_on_roles(role, all_roles),
            planned_tool_names=string_tuple(agent.get("plannedToolNames")) or tuple(state.get("plannedToolNames") or ()),
            visible_skill_codes=string_tuple(agent.get("visibleSkillCodes")) or tuple(state.get("visibleSkillCodes") or ()),
            memory_dependencies=string_tuple(agent.get("memoryDependencies")) or tuple(state.get("memoryDependencies") or ()),
            handoff_required=handoff_required,
            blocked_by=blocked_by(state.get("schedulingStatus") or "UNKNOWN", agent_status, handoff_required),
            next_action=next_action_for_work_item(role, mode, status),
        )

    @staticmethod
    def _append_trace(state: MultiAgentExecutionPlanState, node_name: str) -> MultiAgentExecutionPlanState:
        """返回追加 trace 后的新状态，避免原地修改 LangGraph 共享对象。"""

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
    ) -> MultiAgentExecutionPlanDiagnostics:
        """构造统一诊断对象。"""

        return MultiAgentExecutionPlanDiagnostics(
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
            plan_status=str(state.get("planStatus") or "UNKNOWN"),
            work_items=tuple(state.get("workItems") or ()),
            collaboration_edges=tuple(state.get("collaborationEdges") or ()),
            execution_policy=dict(state.get("executionPolicy") or {}),
            global_state=dict(state.get("globalState") or {}),
            next_actions=tuple(state.get("nextActions") or ()),
        )
