"""受控多 Agent turn runner 合同。

本模块把上一层 `agentExecutionSession` 继续推进到“可恢复 turn”层：
- `agentExecutionSession` 说明本轮有哪些 Agent work item、各自停在哪个状态；
- `agentTurnRunner` 说明下一轮可以如何推进这些 work item、哪些角色可以被主控以 manager-as-tools 方式调度、
  哪些证据必须先由 Java 控制面、人工审批或 worker receipt 补齐。

这里的 runner 仍然不是工具执行器，也不是模型递归调用器。它刻意保持 side-effect free：
- 不调用模型；
- 不执行工具；
- 不读取工具参数、SQL、样本数据或 artifact 正文；
- 不写 Java outbox；
- 不创建审批单；
- 不派发 worker；
- 不修改 Durable Agent Loop checkpoint。

为什么仍然称为 runner：
商业级 Agent 产品需要一个“可推进的运行层合同”，否则多 Agent 会一直停留在静态 roster 和诊断图。
本模块通过真实 LangGraph StateGraph 编排 turn 选择、manager-as-tools 描述、runner policy 和下一步动作，
让后续 Java 控制面或前端可以拿着这份低敏合同继续落 outbox、审批、worker receipt 和恢复执行。
"""

from __future__ import annotations

from typing import Any, Mapping, Protocol

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.agent_graph.multi_agent_turn_runner_contract import (
    build_multi_agent_turn_runner_graph_contract,
)
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphApi
from datasmart_ai_runtime.services.multi_agent.execution_plan_rules import string_tuple, string_value, truthy_env
from datasmart_ai_runtime.services.multi_agent.knowledge_agent_capability import (
    build_knowledge_agent_rag_capabilities,
)
from datasmart_ai_runtime.services.multi_agent.turn_runner_models import (
    ControlledAgentTurnAttempt,
    ControlledMultiAgentTurnRunnerDiagnostics,
    MultiAgentTurnRunnerState,
)
from datasmart_ai_runtime.services.multi_agent.turn_runner_rules import (
    current_turn_depth,
    manager_tool_descriptor,
    next_actions,
    non_negative_int,
    positive_env_int,
    required_evidence_codes,
    run_status,
    safe_fragment,
    turn_status,
)


class _CompiledGraph(Protocol):
    """LangGraph compile 后对象的最小协议。"""

    def invoke(self, input: dict[str, Any]) -> dict[str, Any]:
        """执行图并返回最终低敏状态。"""


class _StateGraph(Protocol):
    """StateGraph 最小协议，方便测试注入 fake graph。"""

    def add_node(self, node: str, action: Any) -> None:
        """注册节点函数。"""

    def add_edge(self, start_key: str, end_key: str) -> None:
        """注册固定边。"""

    def add_conditional_edges(self, source: str, path: Any, path_map: Mapping[str, str]) -> None:
        """注册条件边，让 state 中的低敏路由字段决定下一跳。"""

    def compile(self) -> _CompiledGraph:
        """编译为可执行图。"""


class LangGraphMultiAgentTurnRunnerWorkflow:
    """使用 LangGraph 生成受控多 Agent turn runner 合同。

    图节点职责：
    - `load_execution_session`：读取 `agentExecutionSession` 的低敏 work item；
    - `select_turn_candidates`：按最大并发和状态选择本轮可推进 attempt；
    - `build_manager_as_tools`：把 specialist Agent 转换成 manager-as-tools 的低敏描述；
    - `bind_knowledge_agent_rag_capabilities`：当 KNOWLEDGE_AGENT 参与时绑定 RAG 能力合同；
    - `enforce_runner_policy`：应用最大 turn 深度、Java 控制面、worker receipt 和无副作用边界；
    - `finalize_turn_runner`：生成全局 runStatus 与下一步动作。
    """

    GRAPH_NODES = (
        "load_execution_session",
        "select_turn_candidates",
        "build_manager_as_tools",
        "bind_knowledge_agent_rag_capabilities",
        "enforce_runner_policy",
        "wait_approval_fact",
        "wait_control_plane_feedback",
        "prepare_control_plane_handoff",
        "prepare_specialist_turn",
        "block_turn_runner",
        "finalize_turn_runner",
    )
    CONDITIONAL_ROUTES = {
        "WAIT_APPROVAL": "wait_approval_fact",
        "WAIT_CONTROL_PLANE": "wait_control_plane_feedback",
        "READY_CONTROL_PLANE_HANDOFF": "prepare_control_plane_handoff",
        "READY_SPECIALIST_TURN": "prepare_specialist_turn",
        "BLOCKED": "block_turn_runner",
        "SUMMARIZE_ONLY": "finalize_turn_runner",
    }
    GRAPH_EDGES = (
        "START->load_execution_session",
        "load_execution_session->select_turn_candidates",
        "select_turn_candidates->build_manager_as_tools",
        "build_manager_as_tools->bind_knowledge_agent_rag_capabilities",
        "bind_knowledge_agent_rag_capabilities->enforce_runner_policy",
        "enforce_runner_policy--WAIT_APPROVAL-->wait_approval_fact",
        "enforce_runner_policy--WAIT_CONTROL_PLANE-->wait_control_plane_feedback",
        "enforce_runner_policy--READY_CONTROL_PLANE_HANDOFF-->prepare_control_plane_handoff",
        "enforce_runner_policy--READY_SPECIALIST_TURN-->prepare_specialist_turn",
        "enforce_runner_policy--BLOCKED-->block_turn_runner",
        "enforce_runner_policy--SUMMARIZE_ONLY-->finalize_turn_runner",
        "*_runner_route->finalize_turn_runner",
        "finalize_turn_runner->END",
    )

    def __init__(
        self,
        *,
        enabled: bool = True,
        fail_closed: bool = False,
        max_turn_depth: int = 3,
        max_concurrent_agent_turns: int = 5,
        langgraph_api: LangGraphApi | None = None,
    ) -> None:
        self._enabled = enabled
        self._fail_closed = fail_closed
        self._max_turn_depth = max(1, max_turn_depth)
        self._max_concurrent_agent_turns = max(1, max_concurrent_agent_turns)
        self._langgraph_api = langgraph_api

    @classmethod
    def from_env(cls) -> "LangGraphMultiAgentTurnRunnerWorkflow":
        """从环境变量构建 turn runner。

        环境变量说明：
        - `DATASMART_AI_LANGGRAPH_MULTI_AGENT_TURN_RUNNER_ENABLED`：默认 true；
        - `DATASMART_AI_LANGGRAPH_MULTI_AGENT_TURN_RUNNER_FAIL_CLOSED`：默认 false；
        - `DATASMART_AI_MULTI_AGENT_TURN_MAX_DEPTH`：默认 3，防止无限循环；
        - `DATASMART_AI_MULTI_AGENT_TURN_MAX_CONCURRENT`：默认 5，防止一次请求激活过多 specialist。
        """

        return cls(
            enabled=truthy_env("DATASMART_AI_LANGGRAPH_MULTI_AGENT_TURN_RUNNER_ENABLED", default=True),
            fail_closed=truthy_env("DATASMART_AI_LANGGRAPH_MULTI_AGENT_TURN_RUNNER_FAIL_CLOSED", default=False),
            max_turn_depth=positive_env_int("DATASMART_AI_MULTI_AGENT_TURN_MAX_DEPTH", default=3),
            max_concurrent_agent_turns=positive_env_int("DATASMART_AI_MULTI_AGENT_TURN_MAX_CONCURRENT", default=5),
        )

    def run(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        execution_session: Mapping[str, Any],
        command_proposal_templates: Mapping[str, Any] | None = None,
        durable_loop: Mapping[str, Any] | None = None,
    ) -> ControlledMultiAgentTurnRunnerDiagnostics:
        """运行受控 turn runner 图。

        `plan`、`execution_session` 和 `durable_loop` 只用于读取低敏 requestId/session/run 定位字段；
        本图不会读取用户目标正文、
        ToolPlan.arguments 或模型输出。`command_proposal_templates` 只消费模板数量和目标 route，
        不消费 payloadReference 或工具参数。
        """

        if not self._enabled:
            return self._diagnostics(
                status="DISABLED",
                dependency_available=False,
                compiled=False,
                executed=False,
                state={},
                fallback_used=True,
                fallback_reason="LANGGRAPH_MULTI_AGENT_TURN_RUNNER_DISABLED",
            )

        api = self._langgraph_api or self._import_langgraph_api()
        if api is None:
            if self._fail_closed:
                raise RuntimeError("LangGraph dependency is required for multi-agent turn runner workflow.")
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
                    request=request,
                    plan=plan,
                    execution_session=execution_session,
                    command_proposal_templates=command_proposal_templates or {},
                    durable_loop=durable_loop or {},
                )
            )
        except Exception as exc:
            if self._fail_closed:
                raise RuntimeError("LangGraph multi-agent turn runner workflow failed.") from exc
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
            status="LANGGRAPH_MULTI_AGENT_TURN_RUNNER_BUILT",
            dependency_available=True,
            compiled=True,
            executed=True,
            state=result,
            fallback_used=False,
            fallback_reason=None,
        )

    def _import_langgraph_api(self) -> LangGraphApi | None:
        """延迟导入 LangGraph，避免核心测试强制安装可选依赖。"""

        try:
            from langgraph.graph import END, START, StateGraph
        except ImportError:
            return None
        return LangGraphApi(state_graph=StateGraph, start=START, end=END)

    def _compile_graph(self, api: LangGraphApi) -> _CompiledGraph:
        """构建并编译 turn runner StateGraph。"""

        builder: _StateGraph = api.state_graph(MultiAgentTurnRunnerState)
        builder.add_node("load_execution_session", self._load_execution_session)
        builder.add_node("select_turn_candidates", self._select_turn_candidates)
        builder.add_node("build_manager_as_tools", self._build_manager_as_tools)
        builder.add_node("bind_knowledge_agent_rag_capabilities", self._bind_knowledge_agent_rag_capabilities)
        builder.add_node("enforce_runner_policy", self._enforce_runner_policy)
        builder.add_node("wait_approval_fact", self._wait_approval_fact)
        builder.add_node("wait_control_plane_feedback", self._wait_control_plane_feedback)
        builder.add_node("prepare_control_plane_handoff", self._prepare_control_plane_handoff)
        builder.add_node("prepare_specialist_turn", self._prepare_specialist_turn)
        builder.add_node("block_turn_runner", self._block_turn_runner)
        builder.add_node("finalize_turn_runner", self._finalize_turn_runner)
        builder.add_edge(api.start, "load_execution_session")
        builder.add_edge("load_execution_session", "select_turn_candidates")
        builder.add_edge("select_turn_candidates", "build_manager_as_tools")
        builder.add_edge("build_manager_as_tools", "bind_knowledge_agent_rag_capabilities")
        builder.add_edge("bind_knowledge_agent_rag_capabilities", "enforce_runner_policy")
        builder.add_conditional_edges("enforce_runner_policy", self._select_runner_route, self.CONDITIONAL_ROUTES)
        for branch_node in set(self.CONDITIONAL_ROUTES.values()) - {"finalize_turn_runner"}:
            builder.add_edge(branch_node, "finalize_turn_runner")
        builder.add_edge("finalize_turn_runner", api.end)
        return builder.compile()

    def _initial_state(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        execution_session: Mapping[str, Any],
        command_proposal_templates: Mapping[str, Any],
        durable_loop: Mapping[str, Any],
    ) -> MultiAgentTurnRunnerState:
        """从上游会话、command 模板和 durable checkpoint 提取低敏初始状态。"""

        _ = request  # 明确不读取 objective/variables，避免用户正文进入 LangGraph 状态。
        route_hints = tuple(
            str(route.get("path"))
            for route in command_proposal_templates.get("targetControlPlaneRoutes", ())
            if isinstance(route, Mapping) and route.get("path")
        )
        work_items = tuple(
            item for item in execution_session.get("workItems", ()) if isinstance(item, Mapping)
        )
        return {
            # 三个定位字段只用于 checkpoint/replay 关联，不进入 Prometheus label，也不携带业务正文。
            "requestId": plan.request_id,
            "runId": (
                string_value(durable_loop.get("runId"))
                or string_value(execution_session.get("runId"))
                or plan.request_id
            ),
            "sessionId": (
                string_value(execution_session.get("sessionId"))
                or f"turn-runner-{safe_fragment(plan.request_id)}"
            ),
            "trace": (),
            "sessionStatus": string_value(execution_session.get("status")) or "UNKNOWN",
            "durablePhase": string_value(execution_session.get("durablePhase")) or "not_recorded",
            "durableResumeAction": string_value(execution_session.get("durableResumeAction")),
            "workItems": work_items,
            "commandTemplateCount": non_negative_int(command_proposal_templates.get("totalTemplateCount")),
            "javaProposalRoutes": route_hints,
            "maxTurnDepth": self._max_turn_depth,
            "maxConcurrentAgentTurns": self._max_concurrent_agent_turns,
            "currentTurnDepth": current_turn_depth(plan, durable_loop),
            "runnerRoute": "UNROUTED",
            "runnerStatus": "NOT_EVALUATED",
            "loopDecision": "WAIT_FOR_ROUTE",
        }

    def _load_execution_session(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """读取执行会话并追加 trace。"""

        return self._append_trace(state, "langgraph.multi_agent_turn.load_execution_session")

    def _select_turn_candidates(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """把 work item 映射为本轮可恢复 turn attempt。"""

        updated = self._append_trace(state, "langgraph.multi_agent_turn.select_turn_candidates")
        route_hints = tuple(state.get("javaProposalRoutes") or ())
        attempts = tuple(
            self._turn_attempt(raw_item=item, index=index, route_hints=route_hints, state=state)
            for index, item in enumerate(tuple(state.get("workItems") or ())[: int(state.get("maxConcurrentAgentTurns") or 1)])
        )
        updated["turnAttempts"] = attempts
        return updated

    def _build_manager_as_tools(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """生成 manager-as-tools 低敏描述。

        这里的“tool”是编排视角的虚拟工具描述，表示主控可以把一个 specialist Agent 当成可调度能力。
        它不是 Python 可执行函数，也没有 arguments 字段。
        """

        updated = self._append_trace(state, "langgraph.multi_agent_turn.build_manager_as_tools")
        updated["managerAsTools"] = tuple(
            manager_tool_descriptor(attempt)
            for attempt in tuple(state.get("turnAttempts") or ())
            if attempt.agent_role != "MASTER_ORCHESTRATOR"
        )
        return updated

    def _bind_knowledge_agent_rag_capabilities(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """为 `KNOWLEDGE_AGENT` 绑定可调度 RAG 能力合同。

        这个节点是“RAG 进入真实多 Agent runner”的收敛点，但它仍然不执行 RAG：
        - 不读取用户问题正文；
        - 不检索知识库；
        - 不调用模型；
        - 不保存 citation/sourceUri/document body；
        - 只把已经存在的 KNOWLEDGE_AGENT turn attempt 转换成低敏能力合同。

        后续 Java 控制面可以根据该合同创建 command proposal 或 worker outbox；RAG 执行器完成后再由
        `services/rag/langgraph_checkpoint.py` 写入 `rag_retrieve_knowledge -> rag_evidence_gate -> final`
        的可恢复 checkpoint。
        """

        updated = self._append_trace(state, "langgraph.multi_agent_turn.bind_knowledge_agent_rag_capabilities")
        updated["knowledgeAgentCapabilities"] = build_knowledge_agent_rag_capabilities(
            tuple(state.get("turnAttempts") or ())
        )
        return updated

    def _enforce_runner_policy(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """应用 turn 深度、并发、Java 控制面和 worker receipt 策略。"""

        updated = self._append_trace(state, "langgraph.multi_agent_turn.enforce_runner_policy")
        current_depth = int(state.get("currentTurnDepth") or 1)
        max_depth = int(state.get("maxTurnDepth") or 1)
        updated["runnerPolicy"] = {
            "policyVersion": "datasmart.multi-agent.turn-runner-policy.v1",
            "maxTurnDepth": max_depth,
            "currentTurnDepth": current_depth,
            "maxConcurrentAgentTurns": int(state.get("maxConcurrentAgentTurns") or 1),
            "turnDepthExceeded": current_depth > max_depth,
            "javaControlPlaneRequiredForSideEffects": True,
            "workerReceiptRequiredForSideEffects": True,
            "checkpointRequiredBeforeNextTurn": True,
            "toolExecutedByPython": False,
            "modelCalledByTurnRunner": False,
            "outboxWrittenByPython": False,
            "approvalCreatedByPython": False,
            "payloadPolicy": "LOW_SENSITIVE_TURN_RUNNER_POLICY_ONLY",
        }
        attempts = tuple(updated.get("turnAttempts") or ())
        runner_status = run_status(attempts, updated["runnerPolicy"])
        updated["runnerStatus"] = runner_status
        updated["runnerRoute"] = self._route_for_status(runner_status)
        return updated

    def _select_runner_route(self, state: MultiAgentTurnRunnerState) -> str:
        """供 LangGraph 条件边使用的路由选择器。

        路由只读取低敏 `runnerRoute`。如果上游状态脏写了未知值，默认进入 `BLOCKED`，避免脏状态被解释成
        可执行或可继续循环的状态。
        """

        route = str(state.get("runnerRoute") or "BLOCKED")
        return route if route in self.CONDITIONAL_ROUTES else "BLOCKED"

    def _wait_approval_fact(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """等待审批或人工 handoff fact 的分支节点。"""

        updated = self._append_trace(state, "langgraph.multi_agent_turn.wait_approval_fact")
        updated["runnerStatus"] = "WAITING_APPROVAL_OR_HANDOFF_FACT"
        updated["loopDecision"] = "WAIT_EXTERNAL_APPROVAL_FACT_BEFORE_REENTER"
        return updated

    def _wait_control_plane_feedback(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """等待 Java 控制面反馈、worker receipt 或 replay 事件的分支节点。"""

        updated = self._append_trace(state, "langgraph.multi_agent_turn.wait_control_plane_feedback")
        updated["runnerStatus"] = "WAITING_CONTROL_PLANE_FEEDBACK"
        updated["loopDecision"] = "WAIT_CONTROL_PLANE_EVENT_REPLAY_BEFORE_REENTER"
        return updated

    def _prepare_control_plane_handoff(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """准备交给 Java 控制面创建 command proposal/outbox 的分支节点。"""

        updated = self._append_trace(state, "langgraph.multi_agent_turn.prepare_control_plane_handoff")
        updated["runnerStatus"] = "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF"
        updated["loopDecision"] = "HANDOFF_TO_JAVA_CONTROL_PLANE_THEN_RESUME_FROM_CHECKPOINT"
        return updated

    def _prepare_specialist_turn(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """准备 specialist Agent 下一轮的分支节点。

        这里刻意不在 Python 当前调用中直接递归执行 specialist。真正的循环边由 runtime graph contract
        声明，并要求 Java checkpoint、幂等键、worker receipt 和恢复事实先落地，再由下一次受控恢复进入。
        """

        updated = self._append_trace(state, "langgraph.multi_agent_turn.prepare_specialist_turn")
        updated["runnerStatus"] = "READY_FOR_SPECIALIST_AGENT_TURNS"
        updated["loopDecision"] = "CONTROLLED_DURABLE_LOOP_REENTRY_REQUIRES_CHECKPOINT_AND_RECEIPT"
        return updated

    def _block_turn_runner(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """阻断分支节点，覆盖深度超限、运行时恢复缺口或未知路由。"""

        updated = self._append_trace(state, "langgraph.multi_agent_turn.block_turn_runner")
        updated["runnerStatus"] = str(state.get("runnerStatus") or "BLOCKED_WAITING_RECOVERY")
        updated["loopDecision"] = "STOP_AUTONOMOUS_LOOP_AND_REQUIRE_OPERATOR_RECOVERY"
        return updated

    def _finalize_turn_runner(self, state: MultiAgentTurnRunnerState) -> MultiAgentTurnRunnerState:
        """计算全局 runStatus 和下一步动作。"""

        updated = self._append_trace(state, "langgraph.multi_agent_turn.finalize_turn_runner")
        attempts = tuple(state.get("turnAttempts") or ())
        policy = dict(state.get("runnerPolicy") or {})
        updated["runStatus"] = str(state.get("runnerStatus") or run_status(attempts, policy))
        updated["nextActions"] = next_actions(updated["runStatus"], attempts, policy)
        return updated

    @staticmethod
    def _route_for_status(runner_status: str) -> str:
        """把全局 runnerStatus 映射为 LangGraph 条件边路由。"""

        if runner_status in {"BLOCKED_TURN_DEPTH_EXCEEDED", "BLOCKED_WAITING_RECOVERY"}:
            return "BLOCKED"
        if runner_status in {"WAITING_APPROVAL_OR_HANDOFF_FACT", "WAITING_HUMAN_TAKEOVER"}:
            return "WAIT_APPROVAL"
        if runner_status == "WAITING_CONTROL_PLANE_FEEDBACK":
            return "WAIT_CONTROL_PLANE"
        if runner_status == "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF":
            return "READY_CONTROL_PLANE_HANDOFF"
        if runner_status in {"READY_FOR_SPECIALIST_AGENT_TURNS", "READY_FOR_DRAFT_ONLY_TURNS"}:
            return "READY_SPECIALIST_TURN"
        return "SUMMARIZE_ONLY"

    def _turn_attempt(
        self,
        *,
        raw_item: Mapping[str, Any],
        index: int,
        route_hints: tuple[str, ...],
        state: Mapping[str, Any],
    ) -> ControlledAgentTurnAttempt:
        """把单个 work item 转换为 turn attempt。"""

        role = string_value(raw_item.get("agentRole")) or "UNKNOWN_AGENT"
        work_item_id = string_value(raw_item.get("workItemId")) or f"workitem-{index + 1}-{safe_fragment(role)}"
        session_status = string_value(raw_item.get("sessionStatus")) or string_value(raw_item.get("sourceStatus")) or "UNKNOWN"
        turn_status_value = turn_status(session_status, state)
        evidence_codes = required_evidence_codes(session_status, raw_item)
        return ControlledAgentTurnAttempt(
            turn_id=f"turn-{int(state.get('currentTurnDepth') or 1)}-{safe_fragment(work_item_id)}",
            work_item_id=work_item_id,
            agent_role=role,
            delivery_tier=string_value(raw_item.get("deliveryTier")) or "runtime_governance",
            turn_status=turn_status_value,
            resume_action=string_value(raw_item.get("resumeAction")) or "WAIT_FOR_SESSION_ORCHESTRATOR_REVIEW",
            execution_lane=string_value(raw_item.get("executionLane")) or "DOMAIN_SPECIALIST_DRAFT",
            manager_tool_name=f"agent.turn.{role.lower()}",
            required_evidence_codes=evidence_codes,
            waiting_reason_codes=string_tuple(raw_item.get("waitingReasonCodes")),
            blocked_by=string_tuple(raw_item.get("blockedBy")),
            planned_tool_count=non_negative_int(raw_item.get("plannedToolCount")),
            visible_skill_count=non_negative_int(raw_item.get("visibleSkillCount")),
            memory_dependency_count=non_negative_int(raw_item.get("memoryDependencyCount")),
            control_plane_route_hints=route_hints if "JAVA_COMMAND_PROPOSAL_OR_OUTBOX_REQUIRED" in evidence_codes else (),
        )

    @staticmethod
    def _append_trace(state: MultiAgentTurnRunnerState, node_name: str) -> MultiAgentTurnRunnerState:
        """返回追加 trace 后的新状态，避免原地修改 LangGraph 状态。"""

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
    ) -> ControlledMultiAgentTurnRunnerDiagnostics:
        """构造统一诊断对象。"""

        return ControlledMultiAgentTurnRunnerDiagnostics(
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
            run_status=str(state.get("runStatus") or "UNKNOWN"),
            runner_route=str(state.get("runnerRoute") or "UNROUTED"),
            runner_status=str(state.get("runnerStatus") or "NOT_EVALUATED"),
            loop_decision=str(state.get("loopDecision") or "WAIT_FOR_ROUTE"),
            session_status=str(state.get("sessionStatus") or "UNKNOWN"),
            durable_phase=str(state.get("durablePhase") or "not_recorded"),
            current_turn_depth=int(state.get("currentTurnDepth") or 0),
            max_turn_depth=int(state.get("maxTurnDepth") or self._max_turn_depth),
            max_concurrent_agent_turns=int(state.get("maxConcurrentAgentTurns") or self._max_concurrent_agent_turns),
            turn_attempts=tuple(state.get("turnAttempts") or ()),
            manager_as_tools=tuple(state.get("managerAsTools") or ()),
            knowledge_agent_capabilities=tuple(state.get("knowledgeAgentCapabilities") or ()),
            runner_policy=dict(state.get("runnerPolicy") or {}),
            next_actions=tuple(state.get("nextActions") or ()),
            runtime_graph_contract=build_multi_agent_turn_runner_graph_contract().to_summary(),
        )
