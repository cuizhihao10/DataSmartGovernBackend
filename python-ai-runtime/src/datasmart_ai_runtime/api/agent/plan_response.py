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

from datasmart_ai_runtime.api.gateway.intelligent_gateway import build_intelligent_gateway_governance_response
from datasmart_ai_runtime.api.model_gateway import build_model_gateway_governance_response
from datasmart_ai_runtime.api.agent.plan_readiness_views import (
    build_command_proposal_context,
    build_tool_execution_readiness_response,
)
from datasmart_ai_runtime.api.agent.plan_response_events import (
    attach_agent_execution_gate_event,
    attach_agent_session_scheduling_event,
    attach_skill_visibility_event,
    attach_tool_execution_readiness_event,
    publish_plan_events,
    record_agent_execution_gate_metrics,
)
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.services.agent_capability import (
    AgentCapabilityMatrixService,
    default_agent_capability_matrix_service,
)
from datasmart_ai_runtime.services.agent_execution import AgentExecutionClosureService
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.agent_workspace import AgentWorkspaceContext, AgentWorkspaceContextBuilder
from datasmart_ai_runtime.services.langgraph_multi_agent_collaboration import (
    LangGraphMultiAgentCollaborationWorkflow,
)
from datasmart_ai_runtime.services.multi_agent.langgraph_execution_plan import (
    LangGraphMultiAgentExecutionPlanWorkflow,
)
from datasmart_ai_runtime.services.memory import LangGraphMemoryRetrievalWorkflow
from datasmart_ai_runtime.services.runtime_events.runtime_event_live_push import RuntimeEventLivePushHub
from datasmart_ai_runtime.services.runtime_events.runtime_event_publisher import RuntimeEventPublisher
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import RuntimeEventStore
from datasmart_ai_runtime.services.runtime_events.runtime_event_transport import RuntimeEventTransportBuilder
from datasmart_ai_runtime.services.tools import (
    ToolActionIntakeSource,
    LangGraphExecutionGateWorkflow,
    ToolExecutionReadinessPolicyProvider,
    ToolExecutionReadinessPolicyProviderProtocol,
    ToolExecutionReadinessService,
    build_tool_action_command_proposal_templates,
    build_tool_execution_readiness_graph_response,
)


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
    skill_publication_diagnostics_service: Any | None = None,
    tool_execution_readiness_policy_provider: ToolExecutionReadinessPolicyProviderProtocol | None = None,
    agent_capability_matrix_service: AgentCapabilityMatrixService | None = None,
    langgraph_execution_gate_metrics: Any | None = None,
    langgraph_memory_retrieval_metrics: Any | None = None,
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
    # 工具执行准备度是“计划生成之后、真实执行之前”的治理快照。
    # 它不会执行工具，也不会创建审批单；这里只把 ToolPlan 转换成低敏、可解释的 readiness summary，
    # 让 HTTP 响应、runtime event、WebSocket replay 和未来 Java projection 都能看到同一份执行前事实。
    # readiness policy provider 通过参数显式注入，而不是在这里固定 new 远程/本地实现。
    # 这样 API 响应组装器只负责“什么时候需要策略”，不负责“策略从哪里来”；远程 permission-admin、
    # gateway trustedControlPlane、测试替身和未来 LangGraph 条件节点都可以在不改主流程的情况下接入。
    readiness_policy_provider = tool_execution_readiness_policy_provider or ToolExecutionReadinessPolicyProvider()
    readiness_policy_snapshot = readiness_policy_provider.policy_for(request)
    tool_execution_readiness = ToolExecutionReadinessService().evaluate(
        plan.tool_plans,
        policy=readiness_policy_snapshot.policy,
        policy_metadata=readiness_policy_snapshot.to_low_sensitive_summary(),
    )
    plan = attach_tool_execution_readiness_event(
        plan,
        request=request,
        tool_execution_readiness=tool_execution_readiness,
    )
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

    # Skill Manifest 诊断服务提供的是“能力发布目录版本证据”，不是新的准入决策。
    # 这里在响应组装层读取一次低敏快照，并把它继续传给智能网关摘要和 runtime event：
    # - 前端可以看见本轮会话绑定的 Manifest 指纹或本地回退状态；
    # - Java replay index 可以把同一指纹写入投影视图；
    # - 后续灰度、缓存命中、Marketplace 统计能够按 Manifest 版本聚合。
    # 如果诊断服务未注入或暂时不可用，本轮计划仍可继续生成，但快照会明确标记为未绑定/诊断不可用，
    # 避免把“没有版本证据”误解释成“已经绑定远端发布目录”。
    skill_manifest_diagnostics = _skill_publication_manifest_diagnostics_snapshot(
        skill_publication_diagnostics_service
    )
    # Agent 能力完备度矩阵是项目收敛阶段的“能力地图”。它不参与本轮模型规划、不改变工具可见性、
    # 不触发任何副作用；这里只把低敏压缩摘要放进 `/agent/plans`，让调用方知道当前 Agent Host
    # 离 tools/skills/memory/context/permission/command/LLM 等完整闭环还差哪些关键能力。
    capability_matrix_service = agent_capability_matrix_service or default_agent_capability_matrix_service()
    agent_capability_closure = capability_matrix_service.plan_summary()

    tool_execution_readiness_response = build_tool_execution_readiness_response(tool_execution_readiness)
    # execution gate workflow 是 readiness 之后的 LangGraph 条件门禁层：
    # - readiness response/graph 负责展示“每个工具当前是什么执行前决策”；
    # - execution gate workflow 负责用真实 LangGraph conditional edge 选择 dominant gate；
    # - resume gate 在这里仍然只是预检语义，不会恢复 checkpoint、不会写 outbox、不会派发 worker。
    agent_execution_gate_workflow = LangGraphExecutionGateWorkflow.from_env().run(tool_execution_readiness)
    agent_execution_gate_summary = agent_execution_gate_workflow.to_summary()
    plan = attach_agent_execution_gate_event(
        plan,
        request=request,
        execution_gate_summary=agent_execution_gate_summary,
    )
    record_agent_execution_gate_metrics(plan, metrics_recorder=langgraph_execution_gate_metrics)
    # command proposal 模板是“下一步如何进入 Java 控制面”的低敏导航，而不是 HTTP 提交动作。
    # 这里把 `/agent/plans` 生成的 ToolPlan 统一标记为 MODEL_TOOL_CALL + AGENT_PLAN 来源，和 MCP/A2A
    # 入口区分开；模板只读取 readiness response 中的字段名、状态、风险和计数，不读取 ToolPlan.arguments。
    command_proposal_templates = build_tool_action_command_proposal_templates(
        source=ToolActionIntakeSource.MODEL_TOOL_CALL,
        protocol_family="AGENT_PLAN",
        readiness_summary=tool_execution_readiness_response,
        command_context=build_command_proposal_context(request, plan, readiness_policy_snapshot),
    )
    # `agentExecutionClosure` 是本轮 Agent 请求的“闭环导航卡片”：
    # - 它不会执行工具、不会写 outbox、不会创建审批单；
    # - 它只汇总 plan/readiness/control-plane/loop/memory 等已存在事实，告诉调用方当前停在哪个门禁；
    # - 这里先于智能网关构建，是为了让智能网关可以聚合闭环状态，而不是让前端自行拼多个顶层字段。
    agent_execution_closure_summary = AgentExecutionClosureService().build(
        plan=plan,
        readiness=tool_execution_readiness,
        control_plane_ingestion=control_plane_ingestion,
        control_plane_feedback=control_plane_feedback,
        runtime_event_feedback=runtime_event_feedback,
        loop_control_decision=loop_control_decision,
        second_turn_result=second_turn_result,
        memory_write_proposal=memory_write_proposal,
        command_proposal_templates=command_proposal_templates,
    ).to_summary()

    # `intelligentGatewayGovernance` 以前只在 HTTP 响应末尾构建，因此 event store、WebSocket replay
    # 和 Kafka publisher 都看不到其中的 `skillVisibility`。现在先构建治理摘要，再把会话级 Skill
    # 可见性压缩成一条低敏 runtime event 追加到计划事件流，最后统一发布。这样同步响应、断线恢复、
    # Java replay index 和审计报表都能围绕同一条事实演进，避免“前端看到过，但事件系统无法回放”。
    intelligent_gateway_governance = build_intelligent_gateway_governance_response(
        plan,
        workspace_context,
        request,
        skill_manifest_diagnostics=skill_manifest_diagnostics,
        agent_execution_closure=agent_execution_closure_summary,
    )
    # LangGraph 多智能体协作图消费的是上一步已经生成的 `agentSessionScheduling` 低敏策略视图。
    # 它不重新做权限、Skill、工具预算或模型决策，也不执行任何工具；它只把“哪些 Agent 参与、哪些规划
    # Agent 尚未覆盖、当前全局状态是什么、是否需要 handoff”放进真实 LangGraph StateGraph 流转。
    # 这样既回应了项目技术路线中“LangGraph + 多智能体协作”的要求，又不会绕过 Java 控制面副作用边界。
    agent_collaboration_workflow = LangGraphMultiAgentCollaborationWorkflow.from_env().run(
        request=request,
        plan=plan,
        scheduling=intelligent_gateway_governance.get("agentSessionScheduling", {}),
    )
    # 多智能体执行前计划是协作图之后的第二层 LangGraph 能力：协作图回答“哪些 Agent 参与、全局状态如何”，
    # 执行计划图回答“每个 Agent 在执行前承担什么工作、依赖谁、由谁守门、下一步是否应该 handoff”。
    # 它仍然不执行工具、不调用模型、不写 outbox、不创建审批单；真实副作用继续由 Java 控制面承接。
    # 这样可以把多 Agent 能力从诊断视图推进到可被前端、gateway 和 Java projection 消费的执行前合同，
    # 同时不破坏项目正在收敛的安全边界。
    agent_collaboration_execution_plan = LangGraphMultiAgentExecutionPlanWorkflow.from_env().run(
        request=request,
        plan=plan,
        scheduling=intelligent_gateway_governance.get("agentSessionScheduling", {}),
        collaboration=agent_collaboration_workflow.to_summary(),
    )
    agent_collaboration_execution_plan_summary = agent_collaboration_execution_plan.to_summary()
    # 长期记忆检索以前只作为 `AgentOrchestrator` 内部的 `retrieve_memory` 顺序步骤存在。
    # 这里新增的 LangGraph workflow 不重复召回记忆、不读取正文、不修改记忆 store，而是把已经生成的
    # `memoryPlan + memoryRetrievalReport` 压缩成可观察节点 trace。这样前端、Java projection 和多 Agent
    # 协作视图能看见 MEMORY_AGENT 如何为专项 Agent 提供上下文支持，同时仍然保持“真实写入/落成由 Java
    # 控制面和 memory materialization worker 管理”的生产边界。
    agent_memory_retrieval_workflow = LangGraphMemoryRetrievalWorkflow.from_env().run(
        memory_plan=plan.memory_plan,
        retrieval_report=plan.memory_retrieval_report,
        workspace_context=workspace_context,
        scheduling=intelligent_gateway_governance.get("agentSessionScheduling", {}),
        collaboration_execution_plan=agent_collaboration_execution_plan_summary,
    )
    agent_memory_retrieval_workflow_summary = agent_memory_retrieval_workflow.to_summary()
    if langgraph_memory_retrieval_metrics is not None:
        langgraph_memory_retrieval_metrics.record_summary(agent_memory_retrieval_workflow_summary)
    plan = attach_skill_visibility_event(
        plan,
        request=request,
        intelligent_gateway_governance=intelligent_gateway_governance,
    )
    # 多 Agent 会话调度和 Skill 可见性一样，不能只停留在 HTTP 响应顶层。
    # 如果不事件化，WebSocket 断线恢复、Kafka 异步消费、Java replay projection 和审计报表都无法还原
    # “本轮有哪些 Agent 参与、谁需要 handoff、为什么降级”。这里把调度视图压缩成低敏事件后再发布，
    # 让同步响应和异步事件流围绕同一份会话事实演进。
    plan = attach_agent_session_scheduling_event(
        plan,
        request=request,
        intelligent_gateway_governance=intelligent_gateway_governance,
    )
    publish_plan_events(
        plan,
        event_store=event_store,
        live_push_hub=live_push_hub,
        event_publisher=event_publisher,
    )
    response = _build_base_response(plan, event_transport_builder)
    response["agentWorkflowDiagnostics"] = plan.workflow_diagnostics
    response["agentCollaborationWorkflow"] = agent_collaboration_workflow.to_summary()
    response["agentCollaborationExecutionPlan"] = agent_collaboration_execution_plan_summary
    response["agentMemoryRetrievalWorkflow"] = agent_memory_retrieval_workflow_summary
    response["agentWorkspace"] = workspace_context.to_summary()
    response["toolExecutionReadiness"] = tool_execution_readiness_response
    response["agentExecutionGateWorkflow"] = agent_execution_gate_summary
    response["agentCapabilityClosure"] = agent_capability_closure
    # `toolExecutionReadinessGraph` 是 readiness 的编排视角：readiness 摘要回答“每个工具当前是什么决策”，
    # graph 回答“这些决策会让执行图走向哪个条件分支”。它仍然是执行前低敏视图，不执行工具、不写 outbox、
    # 不创建审批单，只为后续 LangGraph/OpenClaw-style 条件节点和 Java projection 预留稳定契约。
    response["toolExecutionReadinessGraph"] = build_tool_execution_readiness_graph_response(tool_execution_readiness)
    response["toolExecutionReadinessPolicy"] = readiness_policy_snapshot.to_low_sensitive_summary()
    response["agentExecutionClosure"] = agent_execution_closure_summary
    response["intelligentGatewayGovernance"] = intelligent_gateway_governance
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


def _skill_publication_manifest_diagnostics_snapshot(
    skill_publication_diagnostics_service: Any | None,
) -> dict[str, Any] | None:
    """读取 Skill Publication Manifest 低敏诊断快照。

    这个函数刻意只调用 `diagnostics()`，不在每次 `/agent/plans` 请求中刷新远端 Manifest：
    - 刷新远端 Manifest 可能产生网络 IO，不应该混入用户同步规划路径；
    - FastAPI startup 已经可以按配置主动刷新，运维也可以通过诊断接口观察状态；
    - 计划响应只需要“当前 Python Runtime 已知的最近一次发布目录证据”，用于审计和版本绑定。

    失败处理原则：
    - Manifest 诊断属于证据增强，不属于本轮模型规划的硬依赖；
    - 因此这里不会让诊断异常把用户规划请求打成 500；
    - 但会返回一个稳定的 `DIAGNOSTICS_UNAVAILABLE` 快照，让治理卡片、事件和 Java 投影都能明确看见
      “这次没有拿到 Manifest 证据”，而不是静默丢字段。
    """

    if skill_publication_diagnostics_service is None:
        return None
    try:
        snapshot = skill_publication_diagnostics_service.diagnostics()
    except Exception as exc:  # pragma: no cover - 防御第三方启动装配或远端诊断对象异常
        return {
            "status": "DIAGNOSTICS_UNAVAILABLE",
            "source": "diagnostics-service",
            "fallback": True,
            "remoteManifestAvailable": False,
            "manifestFingerprint": None,
            "lastError": str(exc),
        }
    return snapshot if isinstance(snapshot, dict) else None


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
