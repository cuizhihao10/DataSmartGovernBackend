"""LangGraph-backed 多智能体协作工作流。

上一阶段的 `LangGraphAgentPlanningWorkflow` 只把 LangGraph 接入到 `/agent/plans` 的规划入口，
用于证明“真实框架可编译、可调用、可诊断”。本模块继续向前推进一步：让 LangGraph 消费已经存在的
`agentSessionScheduling` 策略视图，把多智能体协作、全局状态同步和 handoff 判断纳入图执行结果。

为什么不直接让每个 Agent 并发执行：
- 当前项目正在收敛闭环，真实工具执行、审批、outbox、worker receipt 仍应由 Java 控制面守护；
- 如果 Python Runtime 直接让多个 Agent 产生副作用，会绕过 permission-admin、agent-runtime 和审计链路；
- 因此本工作流先做“协作控制图”：它不执行工具、不调用模型、不读取业务数据，只把已有低敏调度事实
  通过 LangGraph 节点流转成可观测的协作状态。

安全边界：
- 输入只允许来自 `agentSessionScheduling`、ToolPlan 数量、记忆依赖数量等低敏控制面字段；
- 输出不包含用户 objective、prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint；
- 规划中的 8 个产品 Agent 覆盖情况会被明确展示，避免“现有运行治理 Agent 已经等于全部专项 Agent”
  这种误判。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Mapping, Protocol, TypedDict

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.multi_agent.product_agent_catalog import (
    PLANNED_PRODUCT_AGENTS,
    ProductAgentDefinition,
    planned_route_aliases,
)


class MultiAgentCollaborationState(TypedDict, total=False):
    """LangGraph 多智能体协作图中的低敏共享状态。

    字段说明：
    - `participatingAgentRoles`：来自 `agentSessionScheduling` 的参与角色，只保存角色编码；
    - `implementedProductAgentRoles`：规划中 8 个产品 Agent 已经有当前运行角色覆盖的部分；
    - `missingProductAgentRoles`：规划中仍未被当前运行角色覆盖的专项 Agent；
    - `globalState`：图内聚合出的全局协作状态摘要，只包含状态、计数和策略标志；
    - `trace`：LangGraph 节点执行轨迹，用于学习、诊断和后续迁移真实业务节点。
    """

    trace: tuple[str, ...]
    schedulingStatus: str
    primaryAgentRole: str
    participatingAgentRoles: tuple[str, ...]
    participationModeCounts: dict[str, int]
    handoffRequired: bool
    plannedToolCount: int
    memoryDependencyCount: int
    implementedProductAgentRoles: tuple[str, ...]
    missingProductAgentRoles: tuple[str, ...]
    runtimeGovernanceAgentRoles: tuple[str, ...]
    globalState: dict[str, Any]
    handoffRoute: str
    collaborationStatus: str


class _CompiledGraph(Protocol):
    """LangGraph compile 后对象的最小协议。"""

    def invoke(self, input: dict[str, Any]) -> dict[str, Any]:
        """执行图并返回最终低敏状态。"""


class _StateGraph(Protocol):
    """StateGraph 的最小协议，用于测试替身和真实 LangGraph 兼容。"""

    def add_node(self, node: str, action: Any) -> None:
        """注册节点函数。"""

    def add_edge(self, start_key: str, end_key: str) -> None:
        """注册固定边。"""

    def compile(self) -> _CompiledGraph:
        """编译为可执行图。"""


@dataclass(frozen=True)
class MultiAgentCollaborationWorkflowDiagnostics:
    """LangGraph 多智能体协作图诊断结果。

    这个对象回答用户当前最关心的三个问题：
    - LangGraph 是否已经参与复杂流程编排；
    - LangGraph 是否已经参与全局状态管理；
    - LangGraph 是否已经参与多智能体协作，以及技术路线规划的 Agent 覆盖了多少。
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
    complex_flow_orchestration: bool
    global_state_management: bool
    multi_agent_collaboration: bool
    planned_product_agents: tuple[ProductAgentDefinition, ...]
    participating_agent_roles: tuple[str, ...]
    implemented_product_agent_roles: tuple[str, ...]
    missing_product_agent_roles: tuple[str, ...]
    runtime_governance_agent_roles: tuple[str, ...]
    global_state: dict[str, Any]
    handoff_route: str
    next_migration_targets: tuple[str, ...] = (
        "langgraph.plan_tools_node",
        "langgraph.retrieve_memory_node",
        "langgraph.tool_execution_readiness_node",
        "langgraph.human_resume_gate_node",
        "langgraph.specialist_agent_handoff_nodes",
    )

    def to_summary(self) -> dict[str, Any]:
        """转换为 `/agent/plans` 可安全返回的低敏摘要。"""

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
                "complexFlowOrchestration": self.complex_flow_orchestration,
                "globalStateManagement": self.global_state_management,
                "multiAgentCollaboration": self.multi_agent_collaboration,
            },
            "plannedProductAgentCount": len(self.planned_product_agents),
            "plannedProductAgents": tuple(agent.to_summary() for agent in self.planned_product_agents),
            "participatingAgentRoles": self.participating_agent_roles,
            "implementedProductAgentRoles": self.implemented_product_agent_roles,
            "missingProductAgentRoles": self.missing_product_agent_roles,
            "runtimeGovernanceAgentRoles": self.runtime_governance_agent_roles,
            "globalState": self.global_state,
            "handoffRoute": self.handoff_route,
            "nextMigrationTargets": self.next_migration_targets,
            "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_CONTROL_STATE_ONLY",
        }


class LangGraphMultiAgentCollaborationWorkflow:
    """使用 LangGraph 承载多智能体协作控制图。

    当前版本不是“多 Agent 并发执行器”，而是“协作控制图”：它把会话调度结果放进 LangGraph，
    让主控、专家、权限、记忆、运维等角色的参与事实经过节点化处理。这样后续迁移到真实复杂编排时，
    可以沿着这些节点逐步替换，而不是再次推翻 `/agent/plans` 主链路。
    """

    GRAPH_NODES = (
        "ingest_session_scheduling",
        "map_agent_roster",
        "synchronize_global_state",
        "evaluate_handoff_policy",
        "finalize_collaboration",
    )
    GRAPH_EDGES = (
        "START->ingest_session_scheduling",
        "ingest_session_scheduling->map_agent_roster",
        "map_agent_roster->synchronize_global_state",
        "synchronize_global_state->evaluate_handoff_policy",
        "evaluate_handoff_policy->finalize_collaboration",
        "finalize_collaboration->END",
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
    def from_env(cls) -> "LangGraphMultiAgentCollaborationWorkflow":
        """从环境变量构建默认协作工作流。

        环境变量说明：
        - `DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED`：默认 true；
        - `DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_FAIL_CLOSED`：默认 false。

        当前保持 fail-open，是为了不让可选框架依赖阻断已有计划响应；生产环境如果要求 LangGraph
        多智能体图必须可用，可以在镜像依赖固定后开启 fail-closed。
        """

        return cls(
            enabled=_truthy_env("DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_ENABLED", default=True),
            fail_closed=_truthy_env("DATASMART_AI_LANGGRAPH_MULTI_AGENT_WORKFLOW_FAIL_CLOSED", default=False),
        )

    def run(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        scheduling: Mapping[str, Any],
    ) -> MultiAgentCollaborationWorkflowDiagnostics:
        """运行多智能体协作图并返回低敏诊断。"""

        if not self._enabled:
            return self._diagnostics(
                status="DISABLED",
                dependency_available=False,
                compiled=False,
                executed=False,
                state={},
                fallback_used=True,
                fallback_reason="LANGGRAPH_MULTI_AGENT_WORKFLOW_DISABLED",
            )

        api = self._langgraph_api or self._import_langgraph_api()
        if api is None:
            if self._fail_closed:
                raise RuntimeError("LangGraph dependency is required for multi-agent collaboration workflow.")
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
            result = graph.invoke(self._initial_state(request=request, plan=plan, scheduling=scheduling))
        except Exception as exc:
            if self._fail_closed:
                raise RuntimeError("LangGraph multi-agent collaboration workflow failed.") from exc
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
            status="LANGGRAPH_COLLABORATION_EXECUTED",
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
        """构建并编译多智能体协作 StateGraph。"""

        builder: _StateGraph = api.state_graph(MultiAgentCollaborationState)
        builder.add_node("ingest_session_scheduling", self._ingest_session_scheduling)
        builder.add_node("map_agent_roster", self._map_agent_roster)
        builder.add_node("synchronize_global_state", self._synchronize_global_state)
        builder.add_node("evaluate_handoff_policy", self._evaluate_handoff_policy)
        builder.add_node("finalize_collaboration", self._finalize_collaboration)
        builder.add_edge(api.start, "ingest_session_scheduling")
        builder.add_edge("ingest_session_scheduling", "map_agent_roster")
        builder.add_edge("map_agent_roster", "synchronize_global_state")
        builder.add_edge("synchronize_global_state", "evaluate_handoff_policy")
        builder.add_edge("evaluate_handoff_policy", "finalize_collaboration")
        builder.add_edge("finalize_collaboration", api.end)
        return builder.compile()

    def _initial_state(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        scheduling: Mapping[str, Any],
    ) -> MultiAgentCollaborationState:
        """从计划响应上下文中提取低敏协作初始状态。"""

        agents = tuple(item for item in scheduling.get("participatingAgents", ()) if isinstance(item, Mapping))
        roles = tuple(_string_field(agent, "role") for agent in agents if _string_field(agent, "role"))
        policy_axes = scheduling.get("policyAxes") if isinstance(scheduling.get("policyAxes"), Mapping) else {}
        memory_dependencies = _string_tuple(policy_axes.get("memoryDependencies"))
        return {
            "trace": (),
            "schedulingStatus": _string_value(scheduling.get("status")) or "UNKNOWN",
            "primaryAgentRole": _string_value(scheduling.get("primaryAgentRole")) or "UNKNOWN",
            "participatingAgentRoles": roles,
            "participationModeCounts": _count_agent_field(agents, "participationMode"),
            "handoffRequired": bool(scheduling.get("handoffRequired")),
            "plannedToolCount": len(plan.tool_plans),
            "memoryDependencyCount": len(memory_dependencies),
        }

    def _ingest_session_scheduling(
        self,
        state: MultiAgentCollaborationState,
    ) -> MultiAgentCollaborationState:
        """接收智能网关已生成的会话调度事实。"""

        return self._append_trace(state, "langgraph.multi_agent.ingest_session_scheduling")

    def _map_agent_roster(self, state: MultiAgentCollaborationState) -> MultiAgentCollaborationState:
        """映射产品规划 Agent 与当前运行角色覆盖情况。"""

        roles = set(state.get("participatingAgentRoles") or ())
        implemented = tuple(
            agent.role
            for agent in PLANNED_PRODUCT_AGENTS
            if roles.intersection(agent.route_role_aliases)
        )
        missing = tuple(agent.role for agent in PLANNED_PRODUCT_AGENTS if agent.role not in implemented)
        runtime_governance_roles = tuple(
            role for role in sorted(roles) if role not in planned_route_aliases()
        )
        updated = self._append_trace(state, "langgraph.multi_agent.map_agent_roster")
        updated["implementedProductAgentRoles"] = implemented
        updated["missingProductAgentRoles"] = missing
        updated["runtimeGovernanceAgentRoles"] = runtime_governance_roles
        return updated

    def _synchronize_global_state(self, state: MultiAgentCollaborationState) -> MultiAgentCollaborationState:
        """汇总 LangGraph 图内的全局协作状态。

        这一步对应用户关心的“全局状态管理”：当前不是持久化 checkpoint，而是把本轮会话的角色覆盖、
        handoff、工具数量、记忆依赖和状态聚合为一个低敏全局状态快照。后续可以把这个节点替换为
        Redis/Java session store checkpoint。
        """

        updated = self._append_trace(state, "langgraph.multi_agent.synchronize_global_state")
        updated["globalState"] = {
            "stateSchemaVersion": "datasmart.multi-agent.global-state.v1",
            "stateSource": "agentSessionScheduling+AgentPlan",
            "schedulingStatus": state.get("schedulingStatus") or "UNKNOWN",
            "primaryAgentRole": state.get("primaryAgentRole") or "UNKNOWN",
            "participatingAgentCount": len(tuple(state.get("participatingAgentRoles") or ())),
            "handoffRequired": bool(state.get("handoffRequired")),
            "plannedToolCount": int(state.get("plannedToolCount") or 0),
            "memoryDependencyCount": int(state.get("memoryDependencyCount") or 0),
            "implementedProductAgentCount": len(tuple(state.get("implementedProductAgentRoles") or ())),
            "missingProductAgentCount": len(tuple(state.get("missingProductAgentRoles") or ())),
            "participationModeCounts": dict(state.get("participationModeCounts") or {}),
            "checkpointPolicy": "SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT",
        }
        return updated

    def _evaluate_handoff_policy(self, state: MultiAgentCollaborationState) -> MultiAgentCollaborationState:
        """评估本轮协作图是否需要 handoff 或人工接管。"""

        updated = self._append_trace(state, "langgraph.multi_agent.evaluate_handoff_policy")
        if state.get("schedulingStatus") == "BLOCKED":
            updated["handoffRoute"] = "OPS_AGENT_MANUAL_RECOVERY"
        elif bool(state.get("handoffRequired")):
            updated["handoffRoute"] = "PERMISSION_OR_HUMAN_APPROVAL"
        elif state.get("schedulingStatus") == "DEGRADED":
            updated["handoffRoute"] = "MASTER_ORCHESTRATOR_DEGRADED_CONTINUE"
        else:
            updated["handoffRoute"] = "MASTER_ORCHESTRATOR_CONTINUE"
        return updated

    def _finalize_collaboration(self, state: MultiAgentCollaborationState) -> MultiAgentCollaborationState:
        """完成协作图并给出最终协作状态。"""

        updated = self._append_trace(state, "langgraph.multi_agent.finalize_collaboration")
        updated["collaborationStatus"] = (
            "PARTIAL_PRODUCT_AGENT_COVERAGE"
            if tuple(state.get("missingProductAgentRoles") or ())
            else "FULL_PRODUCT_AGENT_COVERAGE"
        )
        return updated

    @staticmethod
    def _append_trace(
        state: MultiAgentCollaborationState,
        node_name: str,
    ) -> MultiAgentCollaborationState:
        """返回追加节点 trace 后的新状态，避免原地修改共享对象。"""

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
    ) -> MultiAgentCollaborationWorkflowDiagnostics:
        """构造统一诊断对象。"""

        return MultiAgentCollaborationWorkflowDiagnostics(
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
            complex_flow_orchestration=executed and len(self.GRAPH_NODES) >= 5,
            global_state_management=executed and bool(state.get("globalState")),
            multi_agent_collaboration=executed and len(tuple(state.get("participatingAgentRoles") or ())) >= 2,
            planned_product_agents=PLANNED_PRODUCT_AGENTS,
            participating_agent_roles=tuple(state.get("participatingAgentRoles") or ()),
            implemented_product_agent_roles=tuple(state.get("implementedProductAgentRoles") or ()),
            missing_product_agent_roles=tuple(state.get("missingProductAgentRoles") or (agent.role for agent in PLANNED_PRODUCT_AGENTS)),
            runtime_governance_agent_roles=tuple(state.get("runtimeGovernanceAgentRoles") or ()),
            global_state=dict(state.get("globalState") or {}),
            handoff_route=str(state.get("handoffRoute") or "UNKNOWN"),
        )


def _count_agent_field(agents: tuple[Mapping[str, Any], ...], field_name: str) -> dict[str, int]:
    """统计参与 Agent 中某个字段的分布。"""

    counts: dict[str, int] = {}
    for agent in agents:
        value = _string_field(agent, field_name)
        if not value:
            continue
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def _string_tuple(value: object | None) -> tuple[str, ...]:
    """把调度策略轴中的列表字段转换为字符串元组。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return (value,) if value else ()
    if isinstance(value, (tuple, list, set, frozenset)):
        return tuple(str(item).strip() for item in value if str(item).strip())
    text = str(value).strip()
    return (text,) if text else ()


def _string_field(mapping: Mapping[str, Any], field_name: str) -> str | None:
    """读取映射中的非空字符串字段。"""

    return _string_value(mapping.get(field_name))


def _string_value(value: object | None) -> str | None:
    """把对象转成非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _truthy_env(name: str, *, default: bool) -> bool:
    """读取布尔环境变量。"""

    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}
