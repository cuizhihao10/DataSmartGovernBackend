"""使用 LangGraph ``Send`` 动态派发 Specialist，并通过子图收口单个专业 turn。

六个 Specialist 是平台能力目录，不代表每次请求都要沿固定路径执行六个角色。本模块接收协调器已经
完成权限、项目范围、依赖和工具白名单判断的“当前就绪波次”，再由 LangGraph 根据波次中的实际角色数
动态生成 ``Send``。每个分支调用同一个 Specialist 子图，最后通过 reducer 汇总结果。

该图只改变 Python 内专业分析的编排方式，不改变副作用边界：
- Specialist 仍只能调用其显式工具白名单；
- 事实登记仍由调用方注入的 result sink 完成，并保持原有幂等合同；
- Java 审批、AgentPlan 接入、Kafka outbox 和 data-sync worker 不会在本模块中执行；
- 图状态只保存当前进程内的受控 DTO 和低敏角色轨迹，不进入公开响应或长期记忆正文。
"""

from __future__ import annotations

import operator
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from threading import Lock
from types import MappingProxyType
from typing import Annotated, Any, Protocol, TypedDict

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistEventSink,
    SpecialistTurnRequest,
    SpecialistTurnResult,
)


SpecialistTurnResultSink = Callable[[SpecialistTurnRequest, SpecialistTurnResult], Any]
SpecialistTurnExecutor = Callable[
    [SpecialistTurnRequest, SpecialistEventSink | None, SpecialistTurnResultSink | None],
    SpecialistTurnResult,
]


@dataclass(frozen=True)
class SpecialistFanoutDispatch:
    """一次动态 ``Send`` 使用的进程内工作包。

    ``event_sink`` 和 ``result_sink`` 是现有受治理边界的函数引用，只在当前调用内传递。它们不会进入
    checkpoint、API 响应或日志；本执行器也不配置 checkpointer，避免把函数对象或专业输入误持久化。
    """

    role: AgentSessionRole
    request: SpecialistTurnRequest
    event_sink: SpecialistEventSink | None
    result_sink: SpecialistTurnResultSink | None


@dataclass(frozen=True)
class SpecialistFanoutOutcome:
    """单个 Specialist 子图返回给父图 reducer 的结果。"""

    role: AgentSessionRole
    result: SpecialistTurnResult
    subgraph_trace: tuple[str, ...]


@dataclass(frozen=True)
class SpecialistFanoutExecution:
    """一次 LangGraph fan-out 波次的低敏执行事实。"""

    engine: str
    dispatch_mode: str
    results: Mapping[AgentSessionRole, SpecialistTurnResult]
    dynamic_dispatch_count: int
    subgraph_invocation_count: int
    dispatched_roles: tuple[str, ...]
    subgraph_traces: Mapping[str, tuple[str, ...]]
    graph_nodes: tuple[str, ...]
    graph_edges: tuple[str, ...]


class SpecialistFanoutWorkerState(TypedDict, total=False):
    """单个 Specialist 子图的私有状态，不与其他并发角色共享可变对象。"""

    dispatch: SpecialistFanoutDispatch
    result: SpecialistTurnResult
    trace: tuple[str, ...]


class SpecialistFanoutParentState(TypedDict, total=False):
    """父图状态。

    ``outcomes`` 使用 ``operator.add`` reducer。LangGraph 在同一个 super-step 中运行多个 Send 分支时，
    每个分支只能返回自己的单元素列表；框架负责在进入汇总节点前合并全部列表，避免并发覆盖结果。
    """

    dispatches: tuple[SpecialistFanoutDispatch, ...]
    dispatch: SpecialistFanoutDispatch
    outcomes: Annotated[list[SpecialistFanoutOutcome], operator.add]
    fanout_completed: bool


class _CompiledGraph(Protocol):
    """LangGraph 编译对象的最小调用协议。"""

    def invoke(self, input: Mapping[str, Any]) -> Mapping[str, Any]:
        """执行一次无持久化图调用。"""


class _StateGraph(Protocol):
    """本模块使用的 StateGraph 最小协议，便于保持框架边界清晰。"""

    def add_node(self, node: str, action: Any) -> None:
        """注册普通函数、包装节点或已编译子图。"""

    def add_edge(self, start_key: str, end_key: str) -> None:
        """注册静态生命周期边。"""

    def add_conditional_edges(self, source: str, path: Any, path_map: Any = None) -> None:
        """注册返回动态 Send 列表的条件边。"""

    def compile(self) -> _CompiledGraph:
        """编译无 checkpointer 的进程内执行图。"""


@dataclass(frozen=True)
class SpecialistFanoutLangGraphApi:
    """延迟注入 LangGraph 类型，避免模块导入阶段强绑可选依赖。"""

    state_graph: Any
    start: str
    end: str
    send: Any


class LangGraphSpecialistFanoutExecutor:
    """把一个已完成治理筛选的 Specialist 波次转换为动态 Send + 子图执行。

    父图拓扑本身保持稳定，因为它表达的是通用生命周期；真正动态的是 Send 的数量、角色和输入状态。
    单个 Specialist 的三节点子图可以被所有角色复用，从而把“角色数变化”和“专业 turn 生命周期”解耦。
    """

    GRAPH_NODES = (
        "plan_runtime_fanout",
        "execute_specialist_subgraph",
        "aggregate_runtime_fanout",
    )
    GRAPH_EDGES = (
        "START->plan_runtime_fanout",
        "plan_runtime_fanout-[Send:N]->execute_specialist_subgraph",
        "execute_specialist_subgraph->aggregate_runtime_fanout",
        "aggregate_runtime_fanout->END",
    )
    SUBGRAPH_TRACE = (
        "langgraph.specialist.prepare_turn",
        "langgraph.specialist.execute_turn",
        "langgraph.specialist.finalize_turn",
    )

    def __init__(
        self,
        execute_turn: SpecialistTurnExecutor,
        *,
        langgraph_api: SpecialistFanoutLangGraphApi | None = None,
        max_dispatches: int = 8,
    ) -> None:
        """创建执行器。

        ``execute_turn`` 必须是协调器现有的“执行并登记”边界。这样切换编排引擎不会绕开异常转 FAILED、
        双主体绑定或 Java durable fact sink。``max_dispatches`` 是进程级防御上限，实际并发还会先受到
        turn runner 的 ``maxConcurrentAgentTurns`` 限制。
        """

        if max_dispatches < 1:
            raise ValueError("Specialist fan-out 上限必须大于 0")
        self._execute_turn = execute_turn
        self._langgraph_api = langgraph_api
        self._max_dispatches = min(max_dispatches, 32)
        self._compile_lock = Lock()
        self._compiled_parent: _CompiledGraph | None = None

    def execute(
        self,
        requests: Mapping[AgentSessionRole, SpecialistTurnRequest],
        *,
        event_sink: SpecialistEventSink | None,
        result_sink: SpecialistTurnResultSink | None,
    ) -> SpecialistFanoutExecution:
        """动态执行一个依赖已满足的波次。

        输入映射的长度直接决定本轮 ``Send`` 数量。方法会在执行前拒绝空波次、角色和请求不一致、超过
        上限等脏状态；执行后再次核对 reducer 输出数量和角色唯一性，防止并发分支丢失或重复结果。
        """

        dispatches = self._build_dispatches(requests, event_sink, result_sink)
        graph = self._compiled_graph()
        state = graph.invoke({"dispatches": dispatches, "outcomes": []})
        outcomes = tuple(
            item for item in state.get("outcomes", ()) if isinstance(item, SpecialistFanoutOutcome)
        )
        if len(outcomes) != len(dispatches):
            raise RuntimeError("LangGraph Specialist fan-out 汇总数量与动态派发数量不一致")

        results: dict[AgentSessionRole, SpecialistTurnResult] = {}
        traces: dict[str, tuple[str, ...]] = {}
        for outcome in outcomes:
            if outcome.role in results:
                raise RuntimeError("LangGraph Specialist fan-out 返回了重复角色")
            if outcome.result.role != outcome.role:
                raise RuntimeError("LangGraph Specialist 子图结果角色与派发角色不一致")
            results[outcome.role] = outcome.result
            traces[outcome.role.value] = outcome.subgraph_trace

        dispatched_roles = tuple(dispatch.role.value for dispatch in dispatches)
        return SpecialistFanoutExecution(
            engine="langgraph",
            dispatch_mode="DYNAMIC_SEND_SUBGRAPH",
            results=MappingProxyType(results),
            dynamic_dispatch_count=len(dispatches),
            subgraph_invocation_count=len(outcomes),
            dispatched_roles=dispatched_roles,
            subgraph_traces=MappingProxyType(traces),
            graph_nodes=self.GRAPH_NODES,
            graph_edges=self.GRAPH_EDGES,
        )

    def _compiled_graph(self) -> _CompiledGraph:
        """线程安全地惰性编译父图和 Specialist 子图。

        编译结果不带 checkpointer，所有请求态都从 ``invoke`` 输入获得，因此可以被多个 HTTP 请求复用，
        同时不会把 sink、专业 DTO 或模型上下文写入 LangGraph 持久存储。
        """

        if self._compiled_parent is not None:
            return self._compiled_parent
        with self._compile_lock:
            if self._compiled_parent is None:
                api = self._langgraph_api or self._import_langgraph_api()
                self._compiled_parent = self._compile_parent_graph(api)
        return self._compiled_parent

    @staticmethod
    def _import_langgraph_api() -> SpecialistFanoutLangGraphApi:
        """延迟加载当前运行环境的 LangGraph Graph API 与 Send 类型。"""

        try:
            from langgraph.graph import END, START, StateGraph
            from langgraph.types import Send
        except ImportError as exc:
            raise RuntimeError("Specialist 动态 fan-out 需要安装 LangGraph") from exc
        return SpecialistFanoutLangGraphApi(
            state_graph=StateGraph,
            start=START,
            end=END,
            send=Send,
        )

    def _compile_parent_graph(self, api: SpecialistFanoutLangGraphApi) -> _CompiledGraph:
        """编译父图，并用包装节点组合私有状态 Specialist 子图。"""

        worker_subgraph = self._compile_worker_subgraph(api)

        def execute_specialist_subgraph(
            state: SpecialistFanoutParentState,
        ) -> SpecialistFanoutParentState:
            """把父图单个 Send 输入转换为子图状态，再只返回 reducer 可合并的结果。"""

            dispatch = state.get("dispatch")
            if not isinstance(dispatch, SpecialistFanoutDispatch):
                raise RuntimeError("LangGraph Specialist Send 缺少受控派发工作包")
            worker_state = worker_subgraph.invoke({"dispatch": dispatch, "trace": ()})
            result = worker_state.get("result")
            trace = tuple(worker_state.get("trace") or ())
            if not isinstance(result, SpecialistTurnResult):
                raise RuntimeError("LangGraph Specialist 子图没有返回规范结果")
            return {
                "outcomes": [
                    SpecialistFanoutOutcome(
                        role=dispatch.role,
                        result=result,
                        subgraph_trace=trace,
                    )
                ]
            }

        builder: _StateGraph = api.state_graph(SpecialistFanoutParentState)
        builder.add_node("plan_runtime_fanout", self._plan_runtime_fanout)
        builder.add_node("execute_specialist_subgraph", execute_specialist_subgraph)
        builder.add_node("aggregate_runtime_fanout", self._aggregate_runtime_fanout)
        builder.add_edge(api.start, "plan_runtime_fanout")
        builder.add_conditional_edges(
            "plan_runtime_fanout",
            lambda state: self._dispatch_sends(state, api),
            ("execute_specialist_subgraph",),
        )
        builder.add_edge("execute_specialist_subgraph", "aggregate_runtime_fanout")
        builder.add_edge("aggregate_runtime_fanout", api.end)
        return builder.compile()

    def _compile_worker_subgraph(self, api: SpecialistFanoutLangGraphApi) -> _CompiledGraph:
        """编译所有 Specialist 共用的三阶段子图。"""

        builder: _StateGraph = api.state_graph(SpecialistFanoutWorkerState)
        builder.add_node("prepare_specialist_turn", self._prepare_specialist_turn)
        builder.add_node("execute_specialist_turn", self._execute_specialist_turn)
        builder.add_node("finalize_specialist_turn", self._finalize_specialist_turn)
        builder.add_edge(api.start, "prepare_specialist_turn")
        builder.add_edge("prepare_specialist_turn", "execute_specialist_turn")
        builder.add_edge("execute_specialist_turn", "finalize_specialist_turn")
        builder.add_edge("finalize_specialist_turn", api.end)
        return builder.compile()

    def _build_dispatches(
        self,
        requests: Mapping[AgentSessionRole, SpecialistTurnRequest],
        event_sink: SpecialistEventSink | None,
        result_sink: SpecialistTurnResultSink | None,
    ) -> tuple[SpecialistFanoutDispatch, ...]:
        """把本轮真实就绪角色转换为稳定顺序的动态派发工作包。"""

        if not requests:
            raise ValueError("Specialist 动态 fan-out 不能执行空波次")
        if len(requests) > self._max_dispatches:
            raise ValueError("Specialist 动态 fan-out 超过单波次安全上限")

        dispatches: list[SpecialistFanoutDispatch] = []
        for role in sorted(requests, key=lambda item: item.value):
            request = requests[role]
            if request.role != role:
                raise ValueError("Specialist 请求角色与动态派发角色不一致")
            dispatches.append(
                SpecialistFanoutDispatch(
                    role=role,
                    request=request,
                    event_sink=event_sink,
                    result_sink=result_sink,
                )
            )
        return tuple(dispatches)

    def _plan_runtime_fanout(
        self,
        state: SpecialistFanoutParentState,
    ) -> SpecialistFanoutParentState:
        """父图规划节点只核对受控 dispatch，不重新选择角色或扩大工具范围。"""

        dispatches = tuple(state.get("dispatches") or ())
        if not dispatches or any(not isinstance(item, SpecialistFanoutDispatch) for item in dispatches):
            raise RuntimeError("LangGraph Specialist 父图没有可派发工作包")
        return {}

    @staticmethod
    def _dispatch_sends(
        state: SpecialistFanoutParentState,
        api: SpecialistFanoutLangGraphApi,
    ) -> list[Any]:
        """根据运行时波次长度创建 N 个 Send，而不是为六个角色预建固定边。"""

        return [
            api.send("execute_specialist_subgraph", {"dispatch": dispatch})
            for dispatch in tuple(state.get("dispatches") or ())
        ]

    @staticmethod
    def _aggregate_runtime_fanout(
        state: SpecialistFanoutParentState,
    ) -> SpecialistFanoutParentState:
        """在 LangGraph super-step barrier 后确认 reducer 已收到全部分支。"""

        if not tuple(state.get("outcomes") or ()):
            raise RuntimeError("LangGraph Specialist fan-out 没有可汇总结果")
        return {"fanout_completed": True}

    @staticmethod
    def _prepare_specialist_turn(
        state: SpecialistFanoutWorkerState,
    ) -> SpecialistFanoutWorkerState:
        """子图第一步核对角色和 turn 身份，避免错误 Send 进入专业执行器。"""

        dispatch = state.get("dispatch")
        if not isinstance(dispatch, SpecialistFanoutDispatch):
            raise RuntimeError("LangGraph Specialist 子图缺少派发工作包")
        if dispatch.request.role != dispatch.role:
            raise RuntimeError("LangGraph Specialist 子图派发角色不一致")
        return {"trace": (LangGraphSpecialistFanoutExecutor.SUBGRAPH_TRACE[0],)}

    def _execute_specialist_turn(
        self,
        state: SpecialistFanoutWorkerState,
    ) -> SpecialistFanoutWorkerState:
        """子图第二步复用协调器原有执行和事实登记边界。"""

        dispatch = state["dispatch"]
        result = self._execute_turn(
            dispatch.request,
            dispatch.event_sink,
            dispatch.result_sink,
        )
        if result.role != dispatch.role or result.turn_id != dispatch.request.turn_id:
            raise RuntimeError("Specialist 返回结果与当前动态派发身份不一致")
        return {
            "result": result,
            "trace": tuple(state.get("trace") or ()) + (self.SUBGRAPH_TRACE[1],),
        }

    @staticmethod
    def _finalize_specialist_turn(
        state: SpecialistFanoutWorkerState,
    ) -> SpecialistFanoutWorkerState:
        """子图第三步只收口轨迹；结果由父图包装节点写入 reducer。"""

        if not isinstance(state.get("result"), SpecialistTurnResult):
            raise RuntimeError("LangGraph Specialist 子图收口时缺少执行结果")
        return {
            "trace": tuple(state.get("trace") or ())
            + (LangGraphSpecialistFanoutExecutor.SUBGRAPH_TRACE[2],)
        }


__all__ = [
    "LangGraphSpecialistFanoutExecutor",
    "SpecialistFanoutExecution",
]
