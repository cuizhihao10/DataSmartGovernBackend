"""多 Agent Turn Runner 的 LangGraph Runtime 合同。

本文件把 `controlled_turn_runner.py` 中的图拓扑解释拆出来，避免编排文件继续膨胀。
它回答三个产品级问题：

- 节点是否是可复用、可观测、可替换的工作单元；
- 边是否已经从固定流水线升级为可分支、可控制的状态机；
- 状态是否具备低敏、可 checkpoint、可恢复执行现场的能力。

注意：这里声明的 `LOOP` 边是 Durable Agent Loop 的跨请求恢复边。它不表示 Python 在一次同步
`/agent/plans` 调用里直接递归执行 specialist Agent，而是表示当 Java 控制面补齐 checkpoint、
outbox、approval fact、worker receipt 或 replay event 后，可以从同一份低敏状态合同恢复下一轮。
"""

from __future__ import annotations

from datasmart_ai_runtime.services.agent_graph.runtime_contracts import (
    AgentGraphEdgeContract,
    AgentGraphEdgeKind,
    AgentGraphNodeContract,
    AgentGraphRuntimeContract,
    AgentGraphStateContract,
)


def build_multi_agent_turn_runner_graph_contract() -> AgentGraphRuntimeContract:
    """构建受控多 Agent Turn Runner 的图运行合同。

    该函数不依赖请求内容，也不会读取任何租户、项目、用户、prompt、SQL 或工具参数。
    因此它可以安全进入 API 响应、runtime event、测试断言和后续 Java 投影。
    """

    return AgentGraphRuntimeContract(
        graph_id="datasmart.agent_graph.multi_agent_turn_runner",
        graph_name="受控多 Agent Turn Runner",
        graph_kind="durable_multi_agent_state_machine",
        purpose=(
            "把 agentExecutionSession 中的低敏 work item 推进为可审批、可等待、可交给 Java 控制面、"
            "可由 checkpoint 恢复下一轮的多 Agent turn 状态机。"
        ),
        nodes=(
            _node(
                "load_execution_session",
                "读取 agentExecutionSession 中已低敏化的 work item、durable phase 和恢复动作。",
            ),
            _node(
                "select_turn_candidates",
                "按最大并发、当前 turn 深度和 work item 状态选择本轮候选 Agent turn。",
            ),
            _node(
                "build_manager_as_tools",
                "把 specialist Agent 映射为 manager-as-tools 描述，但不注册成 Python 可执行工具。",
            ),
            _node(
                "enforce_runner_policy",
                "应用最大循环深度、Java 控制面、worker receipt、checkpoint 和无副作用边界。",
            ),
            _node(
                "wait_approval_fact",
                "当需要审批或人工 handoff 时进入等待分支，直到外部事实补齐后才能恢复。",
            ),
            _node(
                "wait_control_plane_feedback",
                "等待 Java 控制面反馈、worker receipt 或 replay event，避免 Python 单边确认副作用。",
            ),
            _node(
                "prepare_control_plane_handoff",
                "准备把低敏 command proposal 交给 Java agent-runtime，而不是在 Python 内直接执行。",
            ),
            _node(
                "prepare_specialist_turn",
                "准备 specialist Agent 下一轮，但要求先持久化 checkpoint 与恢复事实。",
            ),
            _node(
                "block_turn_runner",
                "当深度超限、恢复事实缺失或未知状态出现时阻断自主循环。",
            ),
            _node(
                "finalize_turn_runner",
                "统一生成 runStatus、nextActions、nodeTrace 和响应摘要。",
            ),
        ),
        edges=(
            AgentGraphEdgeContract(
                source="START",
                target="load_execution_session",
                kind=AgentGraphEdgeKind.DIRECT,
                control_meaning="进入低敏会话读取节点。",
            ),
            AgentGraphEdgeContract(
                source="load_execution_session",
                target="select_turn_candidates",
                kind=AgentGraphEdgeKind.DIRECT,
                control_meaning="会话读取完成后选择本轮 turn 候选。",
            ),
            AgentGraphEdgeContract(
                source="select_turn_candidates",
                target="build_manager_as_tools",
                kind=AgentGraphEdgeKind.DIRECT,
                control_meaning="候选 turn 生成后构建 manager-as-tools 描述。",
            ),
            AgentGraphEdgeContract(
                source="build_manager_as_tools",
                target="enforce_runner_policy",
                kind=AgentGraphEdgeKind.DIRECT,
                control_meaning="虚拟调度能力生成后进入策略约束节点。",
            ),
            _route("WAIT_APPROVAL", "wait_approval_fact", "runnerRoute", "需要审批或人工 handoff fact。"),
            _route(
                "WAIT_CONTROL_PLANE",
                "wait_control_plane_feedback",
                "runnerRoute",
                "需要等待 Java 控制面、worker receipt 或 replay event。",
            ),
            _route(
                "READY_CONTROL_PLANE_HANDOFF",
                "prepare_control_plane_handoff",
                "runnerRoute",
                "可以把低敏 proposal 交给 Java 控制面继续处理。",
            ),
            _route(
                "READY_SPECIALIST_TURN",
                "prepare_specialist_turn",
                "runnerRoute",
                "可以准备 specialist turn，但必须通过 checkpoint 恢复进入下一轮。",
            ),
            _route("BLOCKED", "block_turn_runner", "runnerRoute", "阻断自主执行并等待人工或恢复事实。"),
            _route("SUMMARIZE_ONLY", "finalize_turn_runner", "runnerRoute", "没有可执行 turn，仅输出观察摘要。"),
            AgentGraphEdgeContract(
                source="prepare_specialist_turn",
                target="select_turn_candidates",
                kind=AgentGraphEdgeKind.LOOP,
                route_key="DURABLE_REENTER_AFTER_CHECKPOINT",
                condition_field="loopDecision",
                max_loop_depth_field="maxTurnDepth",
                control_meaning=(
                    "跨请求 Durable Loop 恢复边：只有 Java checkpoint、幂等键、worker receipt 和恢复事实齐备后，"
                    "下一次运行才允许重新进入候选 turn 选择。"
                ),
            ),
            AgentGraphEdgeContract(
                source="*_runner_route",
                target="finalize_turn_runner",
                kind=AgentGraphEdgeKind.TERMINAL,
                control_meaning="所有实际分支最终汇总为低敏 runStatus 与 nextActions。",
            ),
            AgentGraphEdgeContract(
                source="finalize_turn_runner",
                target="END",
                kind=AgentGraphEdgeKind.TERMINAL,
                control_meaning="结束本次同步图运行，等待 Java 控制面或下一次恢复调用。",
            ),
        ),
        state=AgentGraphStateContract(
            schema_name="MultiAgentTurnRunnerState",
            schema_version="datasmart.multi-agent.turn-runner.state.v1",
            low_sensitive=True,
            serializable=True,
            checkpointable=True,
            resumable=True,
            identity_fields=("requestId", "runId", "sessionId"),
            progress_fields=("currentTurnDepth", "runnerRoute", "runnerStatus", "loopDecision", "trace"),
            control_fields=(
                "maxTurnDepth",
                "maxConcurrentAgentTurns",
                "requiredEvidenceCodes",
                "javaProposalRoutes",
            ),
            forbidden_payloads=(
                "prompt",
                "objective",
                "ToolPlan.arguments",
                "sql",
                "sampleData",
                "modelOutput",
                "token",
                "credential",
                "artifactBody",
                "internalEndpoint",
            ),
        ),
        java_control_plane_required=True,
        side_effect_policy="NO_DIRECT_SIDE_EFFECT_REQUIRES_JAVA_CONTROL_PLANE_AND_WORKER_RECEIPT",
        observability_policy="NODE_TRACE_RUNTIME_EVENT_PROMETHEUS_LOW_CARDINALITY_ONLY",
        replacement_policy="EACH_NODE_CAN_BE_REPLACED_BY_LOCAL_FUNCTION_REMOTE_SERVICE_OR_STRONGER_AGENT",
    )


def _node(node_id: str, responsibility: str) -> AgentGraphNodeContract:
    """生成默认节点合同，集中声明输入输出和副作用策略。"""

    return AgentGraphNodeContract(
        node_id=node_id,
        responsibility=responsibility,
        input_policy="只消费低敏控制面字段，不读取 prompt、SQL、工具参数或模型输出正文。",
        output_policy="只输出状态、计数、证据编码、路由和下一步动作等低敏摘要。",
        side_effect_policy="节点不得直接执行工具、调用模型、写 outbox、创建审批或派发 worker。",
    )


def _route(route_key: str, target: str, condition_field: str, meaning: str) -> AgentGraphEdgeContract:
    """生成从策略节点出发的条件边合同。"""

    return AgentGraphEdgeContract(
        source="enforce_runner_policy",
        target=target,
        kind=AgentGraphEdgeKind.CONDITIONAL,
        route_key=route_key,
        condition_field=condition_field,
        control_meaning=meaning,
    )
