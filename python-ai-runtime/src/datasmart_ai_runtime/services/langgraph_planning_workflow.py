"""LangGraph-backed Agent 规划工作流外壳。

这个模块是 DataSmart Python Runtime 接入 LangGraph 的第一步，目标不是立刻重写现有
`AgentOrchestrator` 的全部业务逻辑，而是先把“可编译、可调用、可诊断、可替换”的 LangGraph
运行时边界接进主计划链路。

为什么先做工作流外壳：
- 当前 `AgentOrchestrator` 已经承载模型路由、上下文、Skill、工具计划、记忆检索和事件记录；
- 如果一次性把所有步骤迁入 LangGraph，风险会集中在主路径，容易破坏已经通过的 smoke；
- 先用 LangGraph 承载 receive -> governance_gate -> existing_orchestrator_handoff -> finalize 这条低敏
  控制流，可以让项目马上具备真实框架接入点，后续再逐步把 plan_tools、retrieve_memory、readiness
  和 resume gate 迁成真正业务节点。

安全边界：
- 本工作流不执行工具、不写 outbox、不创建审批、不访问源端数据、不调用模型；
- 节点状态只保存低敏字段，例如租户/项目 ID、objective 长度、节点 trace 和治理策略；
- 如果本地未安装 LangGraph，返回明确诊断并回退现有手写编排，不能让学习环境或 CI 因可选依赖缺失失败。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Protocol, TypedDict

from datasmart_ai_runtime.domain.contracts import AgentRequest


class AgentPlanningWorkflowState(TypedDict, total=False):
    """LangGraph 节点之间传递的共享状态。

    字段设计原则：
    - 只放低敏控制面字段，不放 prompt、工具参数、SQL、样本数据、模型输出或 token；
    - `trace` 使用 tuple，避免节点之间共享可变 list 后出现难以复盘的原地修改；
    - `controlPolicy` 明确声明该图当前是 preview-only，后续真实执行图必须另建 outbox/worker 节点。
    """

    tenantId: str
    projectId: str
    actorIdPresent: bool
    objectiveLength: int
    trace: tuple[str, ...]
    gateStatus: str
    handoffTarget: str
    controlPolicy: str


class _CompiledGraph(Protocol):
    """LangGraph compile 后对象的最小协议。

    官方 LangGraph Graph API 在 compile 后提供 `invoke()` 等执行方法。这里用协议而不是直接类型引用，
    是为了让单元测试可以注入 fake graph，同时生产环境仍然使用真实 LangGraph。
    """

    def invoke(self, input: dict[str, Any]) -> dict[str, Any]:
        """执行已编译图并返回最终状态。"""


class _StateGraph(Protocol):
    """StateGraph 的最小协议。"""

    def add_node(self, node: str, action: Any) -> None:
        """注册节点函数。"""

    def add_edge(self, start_key: str, end_key: str) -> None:
        """注册固定边。"""

    def compile(self) -> _CompiledGraph:
        """编译为可执行图。"""


@dataclass(frozen=True)
class LangGraphApi:
    """LangGraph Graph API 依赖包。

    真实运行时由 `_import_langgraph_api()` 从 `langgraph.graph` 读取；测试可以直接注入 fake API。
    这样既能证明项目确实使用 LangGraph，也不会让核心测试强依赖第三方包。
    """

    state_graph: Any
    start: str
    end: str


@dataclass(frozen=True)
class AgentPlanningWorkflowDiagnostics:
    """Agent 规划工作流低敏诊断结果。

    这个对象会进入 `AgentPlan.workflow_diagnostics` 和 HTTP 顶层 `agentWorkflowDiagnostics`。
    它用于回答：
    - 当前是否启用了 LangGraph；
    - LangGraph 依赖是否存在；
    - 图是否成功 compile/invoke；
    - 节点 trace 是什么；
    - 失败时采用了什么 fallback。

    不允许放入 prompt、SQL、工具参数、模型输出、token、内部 endpoint 或完整异常堆栈。
    """

    engine: str
    status: str
    enabled: bool
    dependency_available: bool
    compiled: bool
    executed: bool
    graph_nodes: tuple[str, ...]
    graph_edges: tuple[str, ...]
    node_trace: tuple[str, ...]
    control_policy: str
    fallback_used: bool
    fallback_reason: str | None = None
    next_migration_targets: tuple[str, ...] = (
        "plan_tools",
        "retrieve_memory",
        "tool_execution_readiness",
        "resume_gate",
    )

    def to_summary(self) -> dict[str, Any]:
        """转换为 HTTP/事件可安全返回的低敏摘要。"""

        return {
            "engine": self.engine,
            "status": self.status,
            "enabled": self.enabled,
            "dependencyAvailable": self.dependency_available,
            "compiled": self.compiled,
            "executed": self.executed,
            "graphNodes": self.graph_nodes,
            "graphEdges": self.graph_edges,
            "nodeTrace": self.node_trace,
            "controlPolicy": self.control_policy,
            "fallbackUsed": self.fallback_used,
            "fallbackReason": self.fallback_reason,
            "nextMigrationTargets": self.next_migration_targets,
        }


class LangGraphAgentPlanningWorkflow:
    """使用 LangGraph 承载 Agent 规划控制流的可替换工作流。

    当前版本是 preview shell，不替代业务编排器。它的价值在于：
    1. 把 LangGraph 编译/调用点接进真实 `/agent/plans` 主路径；
    2. 给响应、诊断和后续 E2E 提供可观察的 workflow engine 事实；
    3. 为后续把工具计划、记忆检索、readiness、HITL resume 迁成节点提供稳定接口。
    """

    GRAPH_NODES = ("receive_goal", "governance_gate", "existing_orchestrator_handoff", "finalize")
    GRAPH_EDGES = (
        "START->receive_goal",
        "receive_goal->governance_gate",
        "governance_gate->existing_orchestrator_handoff",
        "existing_orchestrator_handoff->finalize",
        "finalize->END",
    )
    CONTROL_POLICY = "PREVIEW_ONLY_NO_TOOL_EXECUTION_NO_OUTBOX_NO_MODEL_CALL"

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
    def from_env(cls) -> "LangGraphAgentPlanningWorkflow":
        """从环境变量构建默认工作流。

        环境变量说明：
        - `DATASMART_AI_LANGGRAPH_WORKFLOW_ENABLED`：默认 true，表示主计划链路尝试启用 LangGraph 外壳；
        - `DATASMART_AI_LANGGRAPH_WORKFLOW_FAIL_CLOSED`：默认 false，表示 LangGraph 缺失或失败时回退现有编排。

        当前保持 fail-open 是为了闭环收敛阶段不中断已有功能。生产若要求“没有 LangGraph 不允许运行”，
        可以把 fail-closed 打开，但必须先确保部署镜像安装 `python-ai-runtime[api]`。
        """

        return cls(
            enabled=_truthy_env("DATASMART_AI_LANGGRAPH_WORKFLOW_ENABLED", default=True),
            fail_closed=_truthy_env("DATASMART_AI_LANGGRAPH_WORKFLOW_FAIL_CLOSED", default=False),
        )

    def run(self, request: AgentRequest) -> AgentPlanningWorkflowDiagnostics:
        """运行 LangGraph 工作流外壳并返回低敏诊断。

        该方法只做图编译和低敏状态流转，不改变 `AgentOrchestrator` 后续业务计划结果。
        """

        if not self._enabled:
            return self._diagnostics(
                status="DISABLED",
                dependency_available=False,
                compiled=False,
                executed=False,
                node_trace=(),
                fallback_used=True,
                fallback_reason="LANGGRAPH_WORKFLOW_DISABLED",
            )

        api = self._langgraph_api or self._import_langgraph_api()
        if api is None:
            if self._fail_closed:
                raise RuntimeError("LangGraph dependency is required but not installed.")
            return self._diagnostics(
                status="DEPENDENCY_MISSING",
                dependency_available=False,
                compiled=False,
                executed=False,
                node_trace=(),
                fallback_used=True,
                fallback_reason="INSTALL_python_ai_runtime_api_OR_langgraph",
            )

        try:
            graph = self._compile_graph(api)
            result = graph.invoke(self._initial_state(request))
        except Exception as exc:
            if self._fail_closed:
                raise RuntimeError("LangGraph workflow failed.") from exc
            return self._diagnostics(
                status="EXECUTION_FAILED",
                dependency_available=True,
                compiled=False,
                executed=False,
                node_trace=(),
                fallback_used=True,
                fallback_reason=exc.__class__.__name__,
            )

        return self._diagnostics(
            status="LANGGRAPH_EXECUTED",
            dependency_available=True,
            compiled=True,
            executed=True,
            node_trace=tuple(result.get("trace") or ()),
            fallback_used=False,
            fallback_reason=None,
        )

    def _import_langgraph_api(self) -> LangGraphApi | None:
        """延迟导入 LangGraph。

        延迟导入的原因：
        - 核心领域单测不应因为未安装可选 API 依赖而失败；
        - API 运行时通过 `python-ai-runtime[api]` 安装后会自然启用；
        - 当前模块仍然可以被离线学习脚本导入。
        """

        try:
            from langgraph.graph import END, START, StateGraph
        except ImportError:
            return None
        return LangGraphApi(state_graph=StateGraph, start=START, end=END)

    def _compile_graph(self, api: LangGraphApi) -> _CompiledGraph:
        """构建并编译 LangGraph StateGraph。"""

        builder: _StateGraph = api.state_graph(AgentPlanningWorkflowState)
        builder.add_node("receive_goal", self._receive_goal)
        builder.add_node("governance_gate", self._governance_gate)
        builder.add_node("existing_orchestrator_handoff", self._existing_orchestrator_handoff)
        builder.add_node("finalize", self._finalize)
        builder.add_edge(api.start, "receive_goal")
        builder.add_edge("receive_goal", "governance_gate")
        builder.add_edge("governance_gate", "existing_orchestrator_handoff")
        builder.add_edge("existing_orchestrator_handoff", "finalize")
        builder.add_edge("finalize", api.end)
        return builder.compile()

    def _initial_state(self, request: AgentRequest) -> AgentPlanningWorkflowState:
        """构造进入 LangGraph 的低敏初始状态。"""

        return {
            "tenantId": str(request.tenant_id),
            "projectId": str(request.project_id),
            "actorIdPresent": bool(request.actor_id),
            "objectiveLength": len(request.objective or ""),
            "trace": (),
            "controlPolicy": self.CONTROL_POLICY,
        }

    def _receive_goal(self, state: AgentPlanningWorkflowState) -> AgentPlanningWorkflowState:
        """接收治理目标节点。"""

        return self._append_trace(state, "langgraph.receive_goal")

    def _governance_gate(self, state: AgentPlanningWorkflowState) -> AgentPlanningWorkflowState:
        """执行工作流级安全门禁节点。"""

        updated = self._append_trace(state, "langgraph.governance_gate")
        updated["gateStatus"] = "ALLOW_PREVIEW_ONLY"
        updated["controlPolicy"] = self.CONTROL_POLICY
        return updated

    def _existing_orchestrator_handoff(self, state: AgentPlanningWorkflowState) -> AgentPlanningWorkflowState:
        """把执行权交还现有业务编排器。"""

        updated = self._append_trace(state, "langgraph.existing_orchestrator_handoff")
        updated["handoffTarget"] = "AgentOrchestrator.plan"
        return updated

    def _finalize(self, state: AgentPlanningWorkflowState) -> AgentPlanningWorkflowState:
        """完成工作流 preview 节点。"""

        return self._append_trace(state, "langgraph.finalize")

    def _append_trace(
        self,
        state: AgentPlanningWorkflowState,
        node_name: str,
    ) -> AgentPlanningWorkflowState:
        """返回追加节点 trace 后的新状态。"""

        trace = tuple(state.get("trace") or ())
        return {
            **state,
            "trace": trace + (node_name,),
        }

    def _diagnostics(
        self,
        *,
        status: str,
        dependency_available: bool,
        compiled: bool,
        executed: bool,
        node_trace: tuple[str, ...],
        fallback_used: bool,
        fallback_reason: str | None,
    ) -> AgentPlanningWorkflowDiagnostics:
        """构造统一诊断对象。"""

        return AgentPlanningWorkflowDiagnostics(
            engine="langgraph",
            status=status,
            enabled=self._enabled,
            dependency_available=dependency_available,
            compiled=compiled,
            executed=executed,
            graph_nodes=self.GRAPH_NODES,
            graph_edges=self.GRAPH_EDGES,
            node_trace=node_trace,
            control_policy=self.CONTROL_POLICY,
            fallback_used=fallback_used,
            fallback_reason=fallback_reason,
        )


def _truthy_env(name: str, *, default: bool) -> bool:
    """读取布尔环境变量。"""

    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}
