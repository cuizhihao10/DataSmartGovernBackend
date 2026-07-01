"""LangGraph-backed 工具执行门禁工作流。

本模块把已经低敏化的 `ToolExecutionReadinessReport` 迁入真实 LangGraph 条件图。它解决的是项目
收敛阶段一个很具体的问题：以前 `/agent/plans` 已经能返回 readiness summary 和 readiness graph，
但这些结果仍然是普通 Python if/else 生成的诊断视图；现在我们增加一层 LangGraph 条件门禁，让
“readiness -> 审批/澄清/预算/草案/resume gate”这条执行前路线进入可编译、可观测、可替换的图。

重要边界：
- 本工作流只消费 readiness 的低敏字段，不读取 ToolPlan.arguments、prompt、SQL、样本数据或模型输出；
- 本工作流不执行工具、不写 outbox、不创建审批单、不修改 checkpoint、不派发 worker；
- `resume_gate` 在 `/agent/plans` 场景下只表达“后续真实副作用必须经 Java checkpoint/host facts/worker
  receipt 继续预检”，不会在 Python 默认计划响应里直接恢复执行图。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Mapping, Protocol, TypedDict

from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.tools.tool_execution_readiness import ToolExecutionReadinessReport
from datasmart_ai_runtime.services.tools.tool_execution_readiness_graph import ToolExecutionReadinessGraphBuilder


class ExecutionGateWorkflowState(TypedDict, total=False):
    """LangGraph 执行门禁图的低敏共享状态。

    状态字段只保存控制面摘要：
    - readiness 计数、分支、nextActions 等可公开诊断字段；
    - dominant route，即本轮最应该优先处理的门禁；
    - resumeGate 摘要，用来提醒后续 durable runner 需要哪些 Java host facts。

    这里不保存工具参数值、用户原文、模型输出、异常堆栈或内部 endpoint。这样即使 LangGraph 后续接入
    checkpoint 或事件回放，也不会把高敏正文扩散到执行图状态里。
    """

    trace: tuple[str, ...]
    readinessCounts: dict[str, int]
    readinessBranchCounts: dict[str, int]
    readinessNextActions: tuple[str, ...]
    hasBlockingDecision: bool
    gateRoute: str
    gateStatus: str
    gateReasonCodes: tuple[str, ...]
    resumeGate: dict[str, Any]
    sideEffectBoundary: dict[str, Any]
    controlPolicy: str
    nextActions: tuple[str, ...]


class _CompiledGraph(Protocol):
    """LangGraph compile 后对象的最小协议。"""

    def invoke(self, input: dict[str, Any]) -> dict[str, Any]:
        """执行图并返回最终状态。"""


class _StateGraph(Protocol):
    """StateGraph 最小协议。

    这里显式包含 `add_conditional_edges`，因为本阶段的目标不是再增加一个顺序外壳，而是让 readiness
    结果真正决定后续门禁节点。测试会注入 fake graph，生产环境则使用真实 LangGraph。
    """

    def add_node(self, node: str, action: Any) -> None:
        """注册节点函数。"""

    def add_edge(self, start_key: str, end_key: str) -> None:
        """注册固定边。"""

    def add_conditional_edges(self, source: str, path: Any, path_map: Mapping[str, str]) -> None:
        """注册条件边，`path` 根据当前 state 返回 path_map 中的分支 key。"""

    def compile(self) -> _CompiledGraph:
        """编译为可执行图。"""


@dataclass(frozen=True)
class LangGraphExecutionGateDiagnostics:
    """工具执行门禁 LangGraph 诊断结果。

    该对象会进入 `/agent/plans.agentExecutionGateWorkflow`。它面向前端、gateway、Java projection 和
    学习排障场景，回答：
    - readiness/resume gate 是否已经进入 LangGraph 条件图；
    - 本轮 dominant gate route 是什么；
    - 是否仍需 Java 控制面、checkpoint、审批事实或 worker receipt；
    - 该图是否有任何真实副作用。
    """

    engine: str
    status: str
    enabled: bool
    dependency_available: bool
    compiled: bool
    executed: bool
    graph_nodes: tuple[str, ...]
    graph_edges: tuple[str, ...]
    conditional_routes: Mapping[str, str]
    node_trace: tuple[str, ...]
    gate_route: str
    gate_status: str
    readiness_counts: Mapping[str, int]
    readiness_branch_counts: Mapping[str, int]
    readiness_next_actions: tuple[str, ...]
    resume_gate: Mapping[str, Any]
    side_effect_boundary: Mapping[str, Any]
    control_policy: str
    fallback_used: bool
    fallback_reason: str | None = None

    def to_summary(self) -> dict[str, Any]:
        """转换为 HTTP 响应可返回的低敏摘要。"""

        return {
            "engine": self.engine,
            "status": self.status,
            "enabled": self.enabled,
            "dependencyAvailable": self.dependency_available,
            "compiled": self.compiled,
            "executed": self.executed,
            "graphNodes": self.graph_nodes,
            "graphEdges": self.graph_edges,
            "conditionalRoutes": dict(self.conditional_routes),
            "nodeTrace": self.node_trace,
            "gateRoute": self.gate_route,
            "gateStatus": self.gate_status,
            "readinessCounts": dict(self.readiness_counts),
            "readinessBranchCounts": dict(self.readiness_branch_counts),
            "readinessNextActions": self.readiness_next_actions,
            "resumeGate": dict(self.resume_gate),
            "sideEffectBoundary": dict(self.side_effect_boundary),
            "controlPolicy": self.control_policy,
            "fallbackUsed": self.fallback_used,
            "fallbackReason": self.fallback_reason,
        }


class LangGraphExecutionGateWorkflow:
    """使用 LangGraph 承载工具执行前门禁条件图。

    图节点说明：
    - `load_readiness_context`：把 readiness report 压缩成低敏图状态；
    - `route_execution_gate`：根据 readiness 计数选择本轮 dominant gate；
    - 条件节点：进入无工具、阻断、澄清、审批、预算等待、草案 review 或 resume preflight；
    - `finalize_execution_gate`：统一声明副作用边界和下一步动作。
    """

    GRAPH_NODES = (
        "load_readiness_context",
        "route_execution_gate",
        "no_tool_plan_gate",
        "blocked_gate",
        "human_input_gate",
        "human_approval_gate",
        "capacity_wait_gate",
        "draft_review_gate",
        "resume_gate_preflight",
        "finalize_execution_gate",
    )
    CONDITIONAL_ROUTES = {
        "NO_TOOL_PLAN": "no_tool_plan_gate",
        "BLOCKED": "blocked_gate",
        "HUMAN_INPUT": "human_input_gate",
        "HUMAN_APPROVAL": "human_approval_gate",
        "CAPACITY_WAIT": "capacity_wait_gate",
        "DRAFT_REVIEW": "draft_review_gate",
        "RESUME_PREFLIGHT": "resume_gate_preflight",
    }
    GRAPH_EDGES = (
        "START->load_readiness_context",
        "load_readiness_context->route_execution_gate",
        "route_execution_gate--NO_TOOL_PLAN-->no_tool_plan_gate",
        "route_execution_gate--BLOCKED-->blocked_gate",
        "route_execution_gate--HUMAN_INPUT-->human_input_gate",
        "route_execution_gate--HUMAN_APPROVAL-->human_approval_gate",
        "route_execution_gate--CAPACITY_WAIT-->capacity_wait_gate",
        "route_execution_gate--DRAFT_REVIEW-->draft_review_gate",
        "route_execution_gate--RESUME_PREFLIGHT-->resume_gate_preflight",
        "*_gate->finalize_execution_gate",
        "finalize_execution_gate->END",
    )
    CONTROL_POLICY = "PRE_EXECUTION_LANGGRAPH_GATE_NO_TOOL_EXECUTION_NO_OUTBOX_NO_CHECKPOINT_MUTATION"

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
    def from_env(cls) -> "LangGraphExecutionGateWorkflow":
        """从环境变量构建默认执行门禁工作流。

        环境变量说明：
        - `DATASMART_AI_LANGGRAPH_EXECUTION_GATE_ENABLED`：默认 true，表示启用 execution gate 图；
        - `DATASMART_AI_LANGGRAPH_EXECUTION_GATE_FAIL_CLOSED`：默认 false，避免本地未安装 LangGraph 时中断计划响应。
        """

        return cls(
            enabled=_truthy_env("DATASMART_AI_LANGGRAPH_EXECUTION_GATE_ENABLED", default=True),
            fail_closed=_truthy_env("DATASMART_AI_LANGGRAPH_EXECUTION_GATE_FAIL_CLOSED", default=False),
        )

    def run(self, readiness: ToolExecutionReadinessReport) -> LangGraphExecutionGateDiagnostics:
        """运行 LangGraph 执行门禁图。

        输入是已经低敏化的 readiness report，而不是原始 ToolPlan。这样可以从接口层面保证本 workflow
        只能做门禁诊断，不能拿到工具真实入参去执行副作用。
        """

        if not self._enabled:
            return self._diagnostics(
                status="DISABLED",
                dependency_available=False,
                compiled=False,
                executed=False,
                state={},
                fallback_used=True,
                fallback_reason="LANGGRAPH_EXECUTION_GATE_DISABLED",
            )

        api = self._langgraph_api or self._import_langgraph_api()
        if api is None:
            if self._fail_closed:
                raise RuntimeError("LangGraph dependency is required for execution gate workflow.")
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
            result = graph.invoke(self._initial_state(readiness))
        except Exception as exc:
            if self._fail_closed:
                raise RuntimeError("LangGraph execution gate workflow failed.") from exc
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
            status="LANGGRAPH_EXECUTION_GATE_EVALUATED",
            dependency_available=True,
            compiled=True,
            executed=True,
            state=result,
            fallback_used=False,
            fallback_reason=None,
        )

    def _import_langgraph_api(self) -> LangGraphApi | None:
        """延迟导入 LangGraph，保持单测和轻量脚本的可选依赖边界。"""

        try:
            from langgraph.graph import END, START, StateGraph
        except ImportError:
            return None
        return LangGraphApi(state_graph=StateGraph, start=START, end=END)

    def _compile_graph(self, api: LangGraphApi) -> _CompiledGraph:
        """构建带条件边的 LangGraph StateGraph。"""

        builder: _StateGraph = api.state_graph(ExecutionGateWorkflowState)
        builder.add_node("load_readiness_context", self._load_readiness_context)
        builder.add_node("route_execution_gate", self._route_execution_gate)
        builder.add_node("no_tool_plan_gate", self._no_tool_plan_gate)
        builder.add_node("blocked_gate", self._blocked_gate)
        builder.add_node("human_input_gate", self._human_input_gate)
        builder.add_node("human_approval_gate", self._human_approval_gate)
        builder.add_node("capacity_wait_gate", self._capacity_wait_gate)
        builder.add_node("draft_review_gate", self._draft_review_gate)
        builder.add_node("resume_gate_preflight", self._resume_gate_preflight)
        builder.add_node("finalize_execution_gate", self._finalize_execution_gate)
        builder.add_edge(api.start, "load_readiness_context")
        builder.add_edge("load_readiness_context", "route_execution_gate")
        builder.add_conditional_edges("route_execution_gate", self._select_gate_route, self.CONDITIONAL_ROUTES)
        for branch_node in self.CONDITIONAL_ROUTES.values():
            builder.add_edge(branch_node, "finalize_execution_gate")
        builder.add_edge("finalize_execution_gate", api.end)
        return builder.compile()

    def _initial_state(self, readiness: ToolExecutionReadinessReport) -> ExecutionGateWorkflowState:
        """把 readiness report 转换为 LangGraph 初始状态。"""

        readiness_graph = ToolExecutionReadinessGraphBuilder().build(readiness).to_response()
        return {
            "trace": (),
            "readinessCounts": {
                "totalCount": readiness.total_count,
                "executableCount": readiness.executable_count,
                "approvalRequiredCount": readiness.approval_required_count,
                "clarificationRequiredCount": readiness.clarification_required_count,
                "draftOnlyCount": readiness.draft_only_count,
                "queuedAsyncCount": readiness.queued_async_count,
                "throttledCount": readiness.throttled_count,
                "blockedCount": readiness.blocked_count,
            },
            "readinessBranchCounts": dict(readiness_graph.get("branchCounts") or {}),
            "readinessNextActions": readiness.next_actions,
            "hasBlockingDecision": readiness.has_blocking_decision,
            "gateRoute": "UNROUTED",
            "gateStatus": "NOT_EVALUATED",
            "gateReasonCodes": (),
            "resumeGate": _default_resume_gate(),
            "sideEffectBoundary": _side_effect_boundary(),
            "controlPolicy": self.CONTROL_POLICY,
            "nextActions": (),
        }

    def _load_readiness_context(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """读取 readiness 低敏上下文节点。"""

        return self._append_trace(state, "langgraph.execution_gate.load_readiness_context")

    def _route_execution_gate(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """根据 readiness 计数选择 dominant gate。

        dominant gate 是本轮最保守、最应该优先处理的门禁。完整分支仍保留在 readiness graph 中；这里
        只为 LangGraph 的条件边选择一个主路线，避免一次响应同时声称“可执行”和“应等待审批”。
        """

        route, reason_codes = _dominant_route(state.get("readinessCounts") or {})
        updated = self._append_trace(state, "langgraph.execution_gate.route_execution_gate")
        updated["gateRoute"] = route
        updated["gateReasonCodes"] = reason_codes
        return updated

    def _select_gate_route(self, state: ExecutionGateWorkflowState) -> str:
        """供 LangGraph conditional edge 使用的路由选择器。"""

        route = str(state.get("gateRoute") or "BLOCKED")
        return route if route in self.CONDITIONAL_ROUTES else "BLOCKED"

    def _no_tool_plan_gate(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """无工具计划分支：本轮不需要进入工具执行控制面。"""

        updated = self._gate_state(state, "langgraph.execution_gate.no_tool_plan_gate", "NO_TOOL_PLAN")
        updated["nextActions"] = ("CONTINUE_TEXT_RESPONSE_OR_REQUEST_MORE_CONTEXT",)
        return updated

    def _blocked_gate(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """策略阻断分支：不能进入执行、审批或恢复。"""

        updated = self._gate_state(state, "langgraph.execution_gate.blocked_gate", "BLOCKED_BEFORE_EXECUTION")
        updated["nextActions"] = ("ESCALATE_TO_OPERATOR", "REVIEW_TOOL_POLICY_OR_RISK_LEVEL")
        return updated

    def _human_input_gate(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """澄清分支：需要用户或管理员补充缺失上下文。"""

        updated = self._gate_state(state, "langgraph.execution_gate.human_input_gate", "WAITING_HUMAN_INPUT")
        updated["nextActions"] = ("REQUEST_USER_CLARIFICATION", "RETRY_READINESS_AFTER_CONTEXT_COMPLETION")
        return updated

    def _human_approval_gate(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """审批分支：需要 permission-admin 或企业审批台产生服务端事实。"""

        updated = self._gate_state(state, "langgraph.execution_gate.human_approval_gate", "WAITING_APPROVAL_FACT")
        updated["resumeGate"] = _resume_gate_waiting_for("APPROVAL_CONFIRMATION_FACT")
        updated["nextActions"] = ("CREATE_OR_WAIT_APPROVAL", "RETRY_RESUME_PREFLIGHT_AFTER_APPROVAL")
        return updated

    def _capacity_wait_gate(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """预算/容量分支：需要等待工具预算或 worker 容量恢复。"""

        updated = self._gate_state(state, "langgraph.execution_gate.capacity_wait_gate", "WAITING_CAPACITY")
        updated["resumeGate"] = _resume_gate_waiting_for("TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY")
        updated["nextActions"] = ("WAIT_FOR_TOOL_BUDGET", "RETRY_AFTER_WORKER_CAPACITY_RECOVERY")
        return updated

    def _draft_review_gate(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """草案 review 分支：可以展示草案，但不能产生真实副作用。"""

        updated = self._gate_state(state, "langgraph.execution_gate.draft_review_gate", "WAITING_DRAFT_REVIEW")
        updated["nextActions"] = ("SHOW_DRAFT_FOR_REVIEW", "CONFIRM_DRAFT_BEFORE_CONTROL_PLANE_HANDOFF")
        return updated

    def _resume_gate_preflight(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """resume gate 预检分支。

        该分支通常由 READY/QUEUED 工具触发。它不代表 Python 可以直接执行，而是声明后续必须由 Java
        控制面创建 proposal/checkpoint，并在 durable runner 侧结合 host facts 与 worker receipt 恢复。
        """

        updated = self._gate_state(
            state,
            "langgraph.execution_gate.resume_gate_preflight",
            "READY_FOR_JAVA_CONTROL_PLANE_PREFLIGHT",
        )
        updated["resumeGate"] = {
            "status": "PENDING_JAVA_CHECKPOINT_AND_HOST_FACTS",
            "resumePreviewReady": False,
            "requiredFactTypes": (
                "GRAPH_OR_CONTRACT_EVIDENCE",
                "PAYLOAD_REFERENCE",
                "POLICY_VERSION",
                "OUTBOX_WRITE_CONFIRMATION",
                "WORKER_RECEIPT_FACT",
            ),
            "checkpointMutationAllowed": False,
            "meaning": "本轮只生成执行前门禁路线；真实 resume 必须等待 Java checkpoint、outbox 与 worker receipt 事实。",
        }
        updated["nextActions"] = (
            "HANDOFF_TO_JAVA_AGENT_RUNTIME",
            "CREATE_LOW_SENSITIVE_CHECKPOINT_OR_PROPOSAL",
            "WAIT_FOR_WORKER_RECEIPT_BEFORE_SIDE_EFFECT_CONFIRMATION",
        )
        return updated

    def _finalize_execution_gate(self, state: ExecutionGateWorkflowState) -> ExecutionGateWorkflowState:
        """完成执行门禁图并再次声明副作用边界。"""

        updated = self._append_trace(state, "langgraph.execution_gate.finalize_execution_gate")
        updated["sideEffectBoundary"] = _side_effect_boundary()
        updated["controlPolicy"] = self.CONTROL_POLICY
        return updated

    def _gate_state(
        self,
        state: ExecutionGateWorkflowState,
        trace_node: str,
        status: str,
    ) -> ExecutionGateWorkflowState:
        """生成进入某个门禁分支后的状态。"""

        updated = self._append_trace(state, trace_node)
        updated["gateStatus"] = status
        return updated

    @staticmethod
    def _append_trace(state: ExecutionGateWorkflowState, node_name: str) -> ExecutionGateWorkflowState:
        """追加节点 trace 并返回新状态。"""

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
        state: Mapping[str, Any],
        fallback_used: bool,
        fallback_reason: str | None,
    ) -> LangGraphExecutionGateDiagnostics:
        """构造统一诊断对象。"""

        return LangGraphExecutionGateDiagnostics(
            engine="langgraph",
            status=status,
            enabled=self._enabled,
            dependency_available=dependency_available,
            compiled=compiled,
            executed=executed,
            graph_nodes=self.GRAPH_NODES,
            graph_edges=self.GRAPH_EDGES,
            conditional_routes=self.CONDITIONAL_ROUTES,
            node_trace=tuple(state.get("trace") or ()),
            gate_route=str(state.get("gateRoute") or "UNAVAILABLE"),
            gate_status=str(state.get("gateStatus") or "UNAVAILABLE"),
            readiness_counts=dict(state.get("readinessCounts") or {}),
            readiness_branch_counts=dict(state.get("readinessBranchCounts") or {}),
            readiness_next_actions=tuple(state.get("readinessNextActions") or ()),
            resume_gate=dict(state.get("resumeGate") or _default_resume_gate()),
            side_effect_boundary=dict(state.get("sideEffectBoundary") or _side_effect_boundary()),
            control_policy=str(state.get("controlPolicy") or self.CONTROL_POLICY),
            fallback_used=fallback_used,
            fallback_reason=fallback_reason,
        )


def _dominant_route(counts: Mapping[str, int]) -> tuple[str, tuple[str, ...]]:
    """根据 readiness 计数选择最保守的 dominant gate。"""

    if int(counts.get("totalCount") or 0) == 0:
        return "NO_TOOL_PLAN", ("NO_TOOL_PLAN",)
    if int(counts.get("blockedCount") or 0) > 0:
        return "BLOCKED", ("BLOCKED_TOOL_PRESENT",)
    if int(counts.get("clarificationRequiredCount") or 0) > 0:
        return "HUMAN_INPUT", ("PARAMETER_OR_CONTEXT_CLARIFICATION_REQUIRED",)
    if int(counts.get("approvalRequiredCount") or 0) > 0:
        return "HUMAN_APPROVAL", ("APPROVAL_REQUIRED_BEFORE_EXECUTION",)
    if int(counts.get("throttledCount") or 0) > 0:
        return "CAPACITY_WAIT", ("TOOL_BUDGET_OR_WORKER_CAPACITY_LIMITED",)
    if int(counts.get("draftOnlyCount") or 0) > 0:
        return "DRAFT_REVIEW", ("DRAFT_REVIEW_REQUIRED_BEFORE_SIDE_EFFECT",)
    return "RESUME_PREFLIGHT", ("READY_OR_QUEUED_TOOL_REQUIRES_JAVA_CONTROL_PLANE",)


def _default_resume_gate() -> dict[str, Any]:
    """默认 resume gate 摘要。"""

    return {
        "status": "NOT_APPLICABLE_BEFORE_EXECUTION_CHECKPOINT",
        "resumePreviewReady": False,
        "requiredFactTypes": (),
        "checkpointMutationAllowed": False,
        "meaning": "当前阶段尚未进入可恢复 checkpoint；如果后续产生副作用，必须由 Java 控制面提供恢复事实。",
    }


def _resume_gate_waiting_for(fact_type: str) -> dict[str, Any]:
    """生成等待某类 host fact 的 resume gate 摘要。"""

    return {
        "status": "WAITING_FOR_HOST_FACT",
        "resumePreviewReady": False,
        "requiredFactTypes": (fact_type,),
        "checkpointMutationAllowed": False,
        "meaning": "该分支需要受控服务端事实，不能由客户端自报字段直接越过。",
    }


def _side_effect_boundary() -> dict[str, Any]:
    """执行门禁图的固定副作用边界。"""

    return {
        "toolExecuted": False,
        "outboxWritten": False,
        "approvalCreated": False,
        "checkpointMutated": False,
        "workerDispatched": False,
        "javaControlPlaneRequiredForSideEffects": True,
        "workerReceiptRequiredForSideEffects": True,
    }


def _truthy_env(name: str, *, default: bool) -> bool:
    """读取布尔环境变量。"""

    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}
