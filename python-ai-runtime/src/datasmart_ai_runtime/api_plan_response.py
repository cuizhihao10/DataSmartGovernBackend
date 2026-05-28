"""Agent 计划响应组装器。

`api.py` 的职责应该是创建 FastAPI 应用、声明路由和装配运行时依赖；而 Agent plan 的响应组装已经
变得越来越复杂：它要处理事件 envelope、事件存储、实时推送、Kafka 发布、Java plan ingestion、
控制面反馈快照和受控 loop 决策。如果继续把这些逻辑留在 `api.py`，后续接二轮推理编排器时很容易
突破单文件 500 行约束，也会让 API 路由层承担过多业务编排职责。

本模块专门负责“同步 HTTP Agent plan 响应”的组装。它仍然不直接执行业务工具、不推进审批、不触发
模型二轮；它只把已经由编排器生成的 AgentPlan 和可选控制面集成结果整理成统一响应，方便前端、网关
和 Java 控制面消费。
"""

from __future__ import annotations

from dataclasses import asdict, replace
from typing import Any

from datasmart_ai_runtime.api_model_gateway import build_model_gateway_governance_response
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.agent_workspace import AgentWorkspaceContext, AgentWorkspaceContextBuilder
from datasmart_ai_runtime.services.runtime_event_live_push import RuntimeEventLivePushHub
from datasmart_ai_runtime.services.runtime_event_publisher import RuntimeEventPublisher
from datasmart_ai_runtime.services.runtime_event_store import RuntimeEventStore
from datasmart_ai_runtime.services.runtime_event_transport import RuntimeEventTransportBuilder


def build_plan_response(
    request: AgentRequest,
    orchestrator: AgentOrchestrator,
    event_transport_builder: RuntimeEventTransportBuilder | None = None,
    event_store: RuntimeEventStore | None = None,
    live_push_hub: RuntimeEventLivePushHub | None = None,
    event_publisher: RuntimeEventPublisher | None = None,
    plan_ingestion_client: Any | None = None,
    control_plane_feedback_collector: Any | None = None,
    runtime_event_feedback_bridge: Any | None = None,
    loop_control_evaluator: Any | None = None,
    second_turn_orchestrator: Any | None = None,
    memory_write_governance: Any | None = None,
) -> dict[str, Any]:
    """构建同步 HTTP 风格的 Agent 计划响应。

    返回结构说明：
    - `plan`：完整 Agent 计划，保留 runtimeEvents，便于兼容现有调试调用；
    - `eventEnvelope`：HTTP snapshot envelope，明确 schemaVersion、sequence 范围、ackMode 等传输语义；
    - `modelGatewayGovernance`：模型网关治理摘要，供前端确认页和 Java 审计消费；
    - `controlPlaneIngestion`：可选字段，表示 Python AgentPlan 已经提交 Java 控制面并获得 session/run/audit 映射；
    - `controlPlaneFeedback`：可选字段，表示 Java 当前工具反馈快照；
    - `agentLoopControl`：可选字段，表示当前是否允许进入自动二轮推理或应等待/停止/转人工；
    - `agentSecondTurn`：可选字段，表示受控二轮推理是否执行、跳过原因和模型输出摘要。

    注意：这个函数有多个可选副作用参数，但它们都是显式注入的。默认情况下只生成本地计划响应；
    只有调用方注入 event store、publisher、plan ingestion client 等对象时，才会发生对应的集成行为。
    """

    plan = orchestrator.plan(request)
    # 工作空间上下文是 Agent 安全边界的入口。它不会创建真实资源，但会给本次计划响应附上
    # workspaceKey、缓存 namespace、记忆 namespace 和产物 namespace。后续工具执行、长期记忆写入、
    # prefix/KV cache 和文件输出都应围绕这些 namespace 做隔离，而不是各自临时拼 key。
    workspace_context = AgentWorkspaceContextBuilder().build(request)
    plan = _attach_workspace_hints(plan, workspace_context)
    control_plane_ingestion = None
    control_plane_feedback = None
    runtime_event_feedback = None
    loop_control_decision = None
    second_turn_result = None
    memory_write_proposal = None

    if plan_ingestion_client is not None:
        # 控制面接入是显式副作用：只有调用方注入 client 或 API 启用环境开关时才执行。
        # 接入成功后会把 Java session/run/auditId 引用写回 ToolPlan，后续工具反馈 Provider 可据此查询真实结果。
        control_plane_ingestion = plan_ingestion_client.ingest(request, plan, trace_id=plan.request_id)
        plan = control_plane_ingestion.attach_to_plan(plan)
        control_plane_feedback = _collect_control_plane_feedback(
            plan,
            control_plane_feedback_collector=control_plane_feedback_collector,
        )
        if control_plane_feedback is not None and runtime_event_feedback_bridge is not None:
            # 事件反馈桥位于“同步结果查询”和“loop 策略决策”之间。
            # 它不会执行工具，只会用 Java runtime-event replay 补齐/刷新反馈快照，
            # 让 loop policy 能基于最新工具状态判断是否继续、等待、转人工或停止。
            runtime_event_feedback = runtime_event_feedback_bridge.augment(
                request=request,
                plan=plan,
                snapshot=control_plane_feedback,
            )
            control_plane_feedback = runtime_event_feedback.snapshot
        if control_plane_feedback is not None and loop_control_evaluator is not None:
            loop_control_decision = loop_control_evaluator.evaluate(control_plane_feedback)
        if second_turn_orchestrator is not None:
            # 受控二轮推理必须发生在 Java 控制面反馈与 loop policy 决策之后。
            # 这里仍通过显式注入开启，避免 API 默认路径因为一次计划响应而隐藏触发额外模型调用。
            second_turn_result = second_turn_orchestrator.run(
                request=request,
                plan=plan,
                control_plane_feedback=control_plane_feedback,
                loop_control_decision=loop_control_decision,
            )
            if second_turn_result.runtime_events:
                plan = replace(plan, runtime_events=plan.runtime_events + second_turn_result.runtime_events)

    if memory_write_governance is not None:
        # 记忆写入候选同样必须是显式副作用：只有调用方注入治理服务时才生成候选。
        # 这里不直接写入 Chroma/Neo4j，而是根据 AgentMemoryPlan、ToolPlan 和可选的 Java 控制面反馈
        # 生成“可审批的候选清单”。这种拆分能避免工具结果未经审批就沉淀为长期记忆。
        memory_write_proposal = memory_write_governance.propose(
            request=request,
            plan=plan,
            control_plane_feedback=control_plane_feedback,
        )
        memory_events = memory_write_governance.proposal_events(
            request=request,
            plan=plan,
            report=memory_write_proposal,
        )
        if memory_events:
            plan = replace(plan, runtime_events=plan.runtime_events + memory_events)

    _publish_plan_events(
        plan,
        event_store=event_store,
        live_push_hub=live_push_hub,
        event_publisher=event_publisher,
    )
    response = _build_base_response(plan, event_transport_builder)
    response["agentWorkspace"] = workspace_context.to_summary()
    if control_plane_ingestion is not None:
        response["controlPlaneIngestion"] = control_plane_ingestion.to_summary()
    if control_plane_feedback is not None:
        response["controlPlaneFeedback"] = control_plane_feedback.to_summary()
    if runtime_event_feedback is not None:
        response["runtimeEventFeedback"] = runtime_event_feedback.to_summary()
    if loop_control_decision is not None:
        response["agentLoopControl"] = loop_control_decision.to_summary()
    if second_turn_result is not None:
        response["agentSecondTurn"] = second_turn_result.to_summary()
    if memory_write_proposal is not None:
        response["memoryWriteProposal"] = memory_write_proposal.to_summary()
    return response


def _collect_control_plane_feedback(
    plan: Any,
    *,
    control_plane_feedback_collector: Any | None,
) -> Any | None:
    """构建 Java 控制面反馈快照。

    这里拆成小函数，是为了让主流程保持线性可读：plan ingestion -> feedback snapshot -> loop decision。
    反馈快照只读取 Java 控制面状态，不触发执行、不推进审批、不调用模型二轮；loop 决策也只输出摘要，
    不在 API 层直接执行下一轮模型，避免把受控 Agent loop 做成隐藏副作用。
    """

    if control_plane_feedback_collector is None:
        return None
    return control_plane_feedback_collector.collect(plan)


def _attach_workspace_hints(plan: AgentPlan, workspace_context: AgentWorkspaceContext) -> AgentPlan:
    """把工作空间治理提示写入每个 ToolPlan。

    为什么不只在响应顶层返回 `agentWorkspace`：
    - Java plan ingestion 当前逐个接收 ToolPlan，并把 `governanceHints` 写入工具审计；
    - 后续工具执行器、输出引用解析器、长期记忆 worker 往往只处理单个工具计划或单条审计记录；
    - 如果 workspace 只在顶层响应里，工具执行链路就必须额外回查上下文，容易产生丢失或不一致。

    这里使用 `replace(...)` 生成新的不可变 dataclass 快照，既保留领域对象不可变习惯，也避免修改
    `AgentOrchestrator` 内部生成的原始计划对象。
    """

    workspace_hints = workspace_context.to_governance_hints()
    updated_tool_plans: list[ToolPlan] = []
    for tool_plan in plan.tool_plans:
        # ToolPlan 已有的治理提示优先保留；workspace 字段由响应组装层统一覆盖，确保同一次响应
        # 中所有工具使用同一个隔离边界，避免模型生成或规则分支自行伪造 workspaceKey。
        merged_hints = {
            **tool_plan.governance_hints,
            **workspace_hints,
        }
        updated_tool_plans.append(replace(tool_plan, governance_hints=merged_hints))
    return replace(plan, tool_plans=tuple(updated_tool_plans))


def _publish_plan_events(
    plan: Any,
    *,
    event_store: RuntimeEventStore | None,
    live_push_hub: RuntimeEventLivePushHub | None,
    event_publisher: RuntimeEventPublisher | None,
) -> None:
    """处理 AgentPlan 运行事件的同步存储、实时推送和异步发布。

    这三类动作服务不同产品场景：
    - event store：供 replay、断线恢复和审计回放使用；
    - live push hub：供当前 Python Runtime 内部 WebSocket 连接即时读取；
    - event publisher：供 Kafka、Java 控制面、告警和观测系统异步消费。

    当前实现仍然是同步调用。后续如果要提高可靠性，应把 publisher 升级为本地 outbox + 后台 worker，
    避免 Kafka broker 抖动拖慢用户的计划请求。
    """

    if event_store is not None:
        event_store.append_many(plan.runtime_events)
    if live_push_hub is not None:
        live_push_hub.publish(plan.runtime_events)
    if event_publisher is not None:
        event_publisher.publish(plan.runtime_events)


def _build_base_response(
    plan: Any,
    event_transport_builder: RuntimeEventTransportBuilder | None,
) -> dict[str, Any]:
    """构建 Agent plan 的基础 HTTP 响应结构。"""

    transport_builder = event_transport_builder or RuntimeEventTransportBuilder()
    envelope = transport_builder.build_snapshot(
        plan.runtime_events,
        attributes={
            "responseShape": "agent_plan_with_event_envelope",
            "transportHint": "同步 HTTP 响应使用 snapshot envelope；实时场景可切换为 WebSocket live envelope。",
        },
    )
    return {
        "plan": asdict(plan),
        "eventEnvelope": asdict(envelope),
        "modelGatewayGovernance": build_model_gateway_governance_response(plan.model_gateway_decision),
    }
