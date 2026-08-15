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

import hashlib
import json
from dataclasses import asdict, replace
from collections.abc import Callable, Mapping
from threading import Lock
from typing import Any

from datasmart_ai_runtime.api.gateway.intelligent_gateway import build_intelligent_gateway_governance_response
from datasmart_ai_runtime.api.model_gateway import build_model_gateway_governance_response
from datasmart_ai_runtime.api.agent.plan_readiness_views import (
    build_command_proposal_context,
    build_tool_execution_readiness_response,
)
from datasmart_ai_runtime.api.agent.conversation_response import build_agent_conversation_response
from datasmart_ai_runtime.api.agent.observation_timeline import build_agent_observation_timeline
from datasmart_ai_runtime.api.agent.post_bridge_finalization import (
    control_plane_resource_fingerprint,
    recompute_post_bridge_views,
    run_post_bridge_verification_wave,
)
from datasmart_ai_runtime.api.agent.plan_response_events import (
    attach_agent_execution_gate_event,
    attach_agent_execution_session_event,
    attach_agent_session_scheduling_event,
    attach_agent_turn_runner_event,
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
from datasmart_ai_runtime.services.multi_agent import (
    LangGraphMultiAgentTurnRunnerWorkflow,
    MultiAgentExecutionSessionService,
    record_multi_agent_turn_runner_checkpoint,
)
from datasmart_ai_runtime.services.multi_agent.specialist_coordinator import (
    SpecialistAgentCoordinator,
    SpecialistExecutionBatchResult,
)
from datasmart_ai_runtime.services.multi_agent.specialist_events import build_specialist_runtime_events
from datasmart_ai_runtime.services.multi_agent.specialist_toolplan_bridge import (
    SpecialistBridgeStatus,
    SpecialistToolPlanBridge,
    SpecialistToolPlanBridgeResult,
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
    durable_model_tool_loop_runner: Any | None = None,
    durable_agent_loop_service: Any | None = None,
    memory_write_governance: Any | None = None,
    skill_publication_diagnostics_service: Any | None = None,
    tool_execution_readiness_policy_provider: ToolExecutionReadinessPolicyProviderProtocol | None = None,
    agent_capability_matrix_service: AgentCapabilityMatrixService | None = None,
    langgraph_execution_gate_metrics: Any | None = None,
    langgraph_memory_retrieval_metrics: Any | None = None,
    multi_agent_execution_session_metrics: Any | None = None,
    multi_agent_turn_runner_metrics: Any | None = None,
    multi_agent_turn_runner_workflow: Any | None = None,
    langgraph_checkpointer_service: Any | None = None,
    specialist_agent_coordinator: SpecialistAgentCoordinator | None = None,
    specialist_allowed_tools_by_role: dict[str, tuple[str, ...]] | None = None,
    specialist_toolplan_bridge: SpecialistToolPlanBridge | None = None,
    progress_event_sink: Callable[[Any], None] | None = None,
    cancellation_check: Callable[[], None] | None = None,
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

    check_cancelled = cancellation_check or (lambda: None)
    check_cancelled()
    # 流式入口注入 progress sink 后，RuntimeEventRecorder 会在每个真实节点完成时立即旁路事件；
    # 普通同步入口不传该参数，保持原有响应与持久化行为完全兼容。
    plan = (
        orchestrator.plan(request, event_sink=progress_event_sink)
        if progress_event_sink is not None
        else orchestrator.plan(request)
    )
    # The legacy master-planner RAG ToolPlan and the new Knowledge Specialist describe the same
    # retrieval capability but have different lifecycle semantics. If both survive this boundary,
    # the legacy ToolPlan is submitted to Java first and moves the whole execution session into
    # WAITING_CONTROL_PLANE_FEEDBACK before any Specialist can run. The Knowledge Agent then never
    # gets a turn, while a redundant ``knowledge.rag.query`` audit may remain permanently PLANNED.
    #
    # Delegate ownership only when the coordinator is active *and* its governed role/tool matrix
    # explicitly grants the Knowledge Agent this capability. Deployments without Specialists retain
    # the established Java command-worker RAG path. Other ToolPlans are untouched because datasource,
    # metadata and synchronization evidence still has to reach the Java control plane before a domain
    # Specialist may safely plan against it.
    plan = _delegate_legacy_knowledge_rag_to_specialist(
        plan,
        specialist_agent_coordinator=specialist_agent_coordinator,
        specialist_allowed_tools_by_role=specialist_allowed_tools_by_role,
    )
    # 模型调用结束与 Java 控制面提交之间必须再次检查。这样用户在最后一个 token 到达时点击停止，
    # 不会因为时间窗口很短而继续提交尚未执行的工具计划。
    check_cancelled()
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
    durable_loop_checkpoint = None
    durable_model_tool_loop = None
    durable_loop_plan = None
    agent_turn_runner_checkpoint = None
    memory_write_proposal = None
    specialist_agent_execution = None
    specialist_verification_execution = None
    specialist_bridge_results: list[SpecialistToolPlanBridgeResult] = []
    post_bridge_verification_summary: dict[str, Any] | None = None
    # 只有专业结果真正进入 bridge/Durable 后，才需要把下方已经计算过的
    # readiness、closure 和多 Agent 调度视图重新计算。普通请求不重复运行这些
    # workflow，避免改变原有响应的调用次数和运行成本。
    post_bridge_finalization_required = False

    # A complete synchronization request already contains the deterministic
    # lifecycle DAG: draft -> precheck -> publish -> run/status. Submit that
    # full plan as one approval scope. Otherwise only send the currently safe
    # read-only frontier so incomplete requests can still discover metadata.
    ingestion_plan = _control_plane_ingestion_subplan(plan, tool_execution_readiness)
    if (
        plan_ingestion_client is not None
        and bool(ingestion_plan.tool_plans)
        and tool_execution_readiness.blocked_count == 0
    ):
        check_cancelled()
        # 一个自然语言同步请求可以同时包含两类节点：
        # 1. 已具备参数、无需审批的目录/连接/元数据只读探测；
        # 2. 仍缺对象映射或等待确认的草稿保存、发布和运行节点。
        #
        # 不能因为第二类节点缺参就把第一类也一起拦住，否则 Agent 永远拿不到真实元数据来自动补齐
        # 对象映射，用户只能回到手工表单。这里仅把 readiness 明确标记为 executable 的子计划送入
        # Java 控制面；等待澄清、审批、限流和阻断节点仍保留在完整计划中供 UI 解释，但绝不提前执行。
        control_plane_ingestion = plan_ingestion_client.ingest(
            request,
            ingestion_plan,
            trace_id=plan.request_id,
        )
        # ingest 已经提交的控制面事实不能在 Python 中回滚；停止只会阻断后续反馈推理和新工具批次。
        check_cancelled()
        ingested_control_plane_plan = control_plane_ingestion.attach_to_plan(ingestion_plan)
        plan = control_plane_ingestion.attach_to_plan(plan)
        control_plane_feedback = _collect_control_plane_feedback(
            ingested_control_plane_plan,
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
            check_cancelled()
            # 受控二轮推理必须发生在 Java 控制面反馈与 loop policy 决策之后。
            # 这里仍通过显式注入开启，避免 API 默认路径因为一次计划响应而隐藏触发额外模型调用。
            second_turn_result = second_turn_orchestrator.run(
                request=request,
                plan=plan,
                control_plane_feedback=control_plane_feedback,
                loop_control_decision=loop_control_decision,
                progress_event_sink=progress_event_sink,
            )
            if second_turn_result.runtime_events:
                plan = replace(plan, runtime_events=plan.runtime_events + second_turn_result.runtime_events)
                if progress_event_sink is not None:
                    for event in second_turn_result.runtime_events:
                        progress_event_sink(event)

            if second_turn_result.continues and durable_model_tool_loop_runner is not None:
                check_cancelled()
                # A model-selected follow-up batch is not executed inside Python.  It
                # is submitted as a new Java run in the same Agent session, then real
                # feedback is returned to the model.  The bounded runner stops at an
                # approval/async gate and leaves a durable checkpoint for replay.
                durable_model_tool_loop = durable_model_tool_loop_runner.run(
                    request=request,
                    plan=plan,
                    first_model_turn=second_turn_result,
                    initial_feedback=control_plane_feedback,
                    progress_event_sink=progress_event_sink,
                )
                durable_loop_plan = durable_model_tool_loop.latest_plan
                # Keep the original response plan/tool batch stable for callers, but
                # append all continuation events so the UI sees one ordered timeline.
                plan = replace(plan, runtime_events=durable_loop_plan.runtime_events)
                if durable_model_tool_loop.latest_feedback is not None:
                    control_plane_feedback = durable_model_tool_loop.latest_feedback
                if durable_model_tool_loop.latest_loop_decision is not None:
                    loop_control_decision = durable_model_tool_loop.latest_loop_decision
                if durable_model_tool_loop.latest_model_turn is not None:
                    second_turn_result = durable_model_tool_loop.latest_model_turn

    if durable_agent_loop_service is not None:
        check_cancelled()
        # Durable Agent Loop checkpoint 是当前 Codex/Claude Code 类体验继续演进的关键基座。
        # 它只记录“本轮 Agent run 停在哪个可恢复阶段、下一步应该等待事件/审批/二轮/人工接管”，
        # 不执行工具、不创建审批、不写 outbox，也不额外调用模型。这样可以先让会话恢复、审计和
        # 多 Agent handoff 有稳定状态锚点，再逐步迁移到 Redis/MySQL/LangGraph durable runner。
        durable_loop_checkpoint = durable_agent_loop_service.record(
            request=request,
            plan=durable_loop_plan or plan,
            control_plane_feedback=control_plane_feedback,
            loop_control_decision=loop_control_decision,
            second_turn_result=second_turn_result,
        )

    # 这里必须放在首轮 Durable loop 之后、专业 Agent bridge 之前。
    # 首轮模型可能已经从 Java 控制面拿到最新反馈；如果在初始化变量时就计算指纹，
    # 桥接后的反馈会被误判为“新资源”，从而重复调度 PRECHECK/MONITOR。
    pre_bridge_resource_fingerprint = control_plane_resource_fingerprint(control_plane_feedback)

    if memory_write_governance is not None:
        check_cancelled()
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
    # `agentExecutionSession` 是执行前计划之后的受控会话层。它把每个 Agent 的 work item 映射为
    # 可恢复状态、resume action 和 roster coverage，但仍然不执行工具、不调用模型、不写 outbox。
    # 这一步让多 Agent 能力从“图诊断/合同”推进到“会话运行视图”，同时继续尊重 Java 控制面的副作用边界。
    agent_execution_session = MultiAgentExecutionSessionService().build(
        request=request,
        plan=plan,
        scheduling=intelligent_gateway_governance.get("agentSessionScheduling", {}),
        collaboration_execution_plan=agent_collaboration_execution_plan_summary,
        durable_loop=durable_loop_checkpoint.to_summary() if durable_loop_checkpoint is not None else None,
    )
    if multi_agent_execution_session_metrics is not None:
        multi_agent_execution_session_metrics.record_summary(agent_execution_session.to_summary())
    plan = attach_agent_execution_session_event(
        plan,
        request=request,
        agent_execution_session=agent_execution_session.to_summary(),
    )
    # `agentTurnRunner` 是多 Agent 从“会话状态”走向“可恢复 turn 合同”的最小闭环。
    # 它使用 LangGraph 节点选择本轮 turn attempt、生成 manager-as-tools 低敏描述，并列出 Java proposal、
    # checkpoint、approval 和 worker receipt 等执行前证据缺口；但它仍然不执行工具、不调用模型、不写 outbox。
    # 这样我们开始具备 Codex/Claude Code 类 Agent loop 的运行层骨架，同时不突破企业控制面边界。
    turn_runner_workflow = multi_agent_turn_runner_workflow or LangGraphMultiAgentTurnRunnerWorkflow.from_env()
    agent_turn_runner = turn_runner_workflow.run(
        request=request,
        plan=plan,
        execution_session=agent_execution_session.to_summary(),
        command_proposal_templates=command_proposal_templates,
        durable_loop=durable_loop_checkpoint.to_summary() if durable_loop_checkpoint is not None else None,
    )
    agent_turn_runner_summary = agent_turn_runner.to_summary()
    if multi_agent_turn_runner_metrics is not None:
        multi_agent_turn_runner_metrics.record_summary(agent_turn_runner_summary)
    if langgraph_checkpointer_service is not None:
        check_cancelled()
        # turn runner checkpoint 是多 Agent 状态机从“响应里的诊断字段”走向“可暂停/恢复现场”的关键一步。
        # 这里消费的是已经低敏化的 `agent_turn_runner_summary`，不会重新读取 ToolPlan.arguments，也不会把
        # 用户目标、prompt、模型输出或工具参数写入 durable state。真实工具执行仍必须等 Java 控制面 outbox
        # 与 worker receipt，因此该 checkpoint 只表示“下一步应该如何安全推进”，不是“Python 已执行副作用”。
        agent_turn_runner_checkpoint = record_multi_agent_turn_runner_checkpoint(
            langgraph_checkpointer_service,
            request=request,
            plan=plan,
            agent_turn_runner=agent_turn_runner_summary,
        )
    plan = attach_agent_turn_runner_event(
        plan,
        request=request,
        agent_turn_runner=agent_turn_runner_summary,
        agent_turn_runner_checkpoint=agent_turn_runner_checkpoint,
    )
    if specialist_agent_coordinator is not None:
        check_cancelled()
        # 真实专业 Agent 必须发生在 turn checkpoint 之后。协调器只推进已注册的低风险专业分析，
        # 任务保存、数据源修改和数据库写操作仍由 Java 控制面工具承接。
        streamed_specialist_events: list[Any] = []
        specialist_event_lock = Lock()

        def record_specialist_action(raw_event: dict[str, Any]) -> None:
            """立即把一个专业 Agent 公开动作转换为 SSE 事件，同时保留最终持久化副本。

            专业 Agent 可以在同一执行波次并发运行，因此这里用一把很小的锁保护 sequence 分配和列表
            追加。锁内不调用前端 sink，避免慢客户端阻塞其他专业 Agent；事件正文仍经过
            ``build_specialist_runtime_events`` 的白名单裁剪，不会把工具参数或模型原文推给浏览器。
            """

            with specialist_event_lock:
                event_plan = replace(
                    plan,
                    runtime_events=plan.runtime_events + tuple(streamed_specialist_events),
                )
                converted = build_specialist_runtime_events(
                    request=request,
                    plan=event_plan,
                    action_events=(raw_event,),
                )
                streamed_specialist_events.extend(converted)
            if progress_event_sink is not None:
                for event in converted:
                    progress_event_sink(event)

        specialist_agent_execution = specialist_agent_coordinator.run(
            request=request,
            turn_runner=agent_turn_runner_summary,
            execution_session=agent_execution_session.to_summary(),
            allowed_tools_by_role=specialist_allowed_tools_by_role or {},
            base_context=_specialist_base_context(
                request,
                plan,
                control_plane_feedback=control_plane_feedback,
            ),
            checkpoint_recorded=agent_turn_runner_checkpoint is not None,
            event_sink=record_specialist_action,
        )
        # 单个 Specialist 动作已经逐条进入事件总线；协调器收口后再补一条低敏编排事件，证明本次
        # 运行实际使用了多少个动态 Send 和子图调用。该事件不携带模型正文、工具参数或业务对象。
        record_specialist_action(specialist_agent_execution.to_runtime_event_action())
        specialist_runtime_events = tuple(streamed_specialist_events)
        if specialist_runtime_events:
            plan = replace(plan, runtime_events=plan.runtime_events + specialist_runtime_events)

        if specialist_toolplan_bridge is not None and specialist_agent_execution.results:
            # 专业 Agent 的结果在这里仍然是不可信建议。桥接层会重新执行主 Agent 的工具
            # 可见性、schema、预算、重复和真实元数据状态校验；只有它返回 ACCEPTED，
            # 才允许把结果交给与普通模型二轮相同的 Durable runner。
            bridge_parent_plan = durable_loop_plan or plan
            for specialist_result in specialist_agent_execution.results:
                if specialist_result.role.value not in {"DATA_SYNC_AGENT", "RECOVERY_AGENT"}:
                    continue
                bridge_result = specialist_toolplan_bridge.bridge(
                    request=request,
                    plan=bridge_parent_plan,
                    specialist_result=specialist_result,
                    control_plane_feedback=control_plane_feedback,
                )
                specialist_bridge_results.append(bridge_result)
                if (
                    bridge_result.status is not SpecialistBridgeStatus.ACCEPTED
                    or bridge_result.plan is None
                    or bridge_result.model_turn is None
                ):
                    # DATA_SYNC/RECOVERY 只有在桥接真正产出受治理 ToolPlan 时才进入 Durable runner。
                    # Recovery 缺少失败诊断、RAG 证据、预览回执或用户审批时，会安全停留在
                    # WAITING_FOR_CONTROL_PLANE_EVIDENCE / WAITING_FOR_APPROVAL /
                    # WAITING_FOR_JAVA_HANDOFF；这些等待态不能被当成瞬时失败自动重试。
                    continue

                # DATA_SYNC_AGENT 的任务生命周期计划和 RECOVERY_AGENT 的白名单恢复计划都与普通模型
                # follow-up 共用 AgentDurableModelToolLoopRunner。Runner 会创建 Java run，执行审批、
                # outbox 和 worker receipt 链路，再决定进入下一轮专业复核还是停在人工门禁。
                plan = bridge_result.plan
                post_bridge_finalization_required = True
                if durable_model_tool_loop_runner is not None:
                    check_cancelled()
                    durable_model_tool_loop = durable_model_tool_loop_runner.run(
                        request=request,
                        plan=bridge_result.plan,
                        first_model_turn=bridge_result.model_turn,
                        initial_feedback=control_plane_feedback,
                        progress_event_sink=progress_event_sink,
                    )
                    durable_loop_plan = durable_model_tool_loop.latest_plan
                    plan = replace(plan, runtime_events=durable_loop_plan.runtime_events)
                    if durable_model_tool_loop.latest_feedback is not None:
                        control_plane_feedback = durable_model_tool_loop.latest_feedback
                    if durable_model_tool_loop.latest_loop_decision is not None:
                        loop_control_decision = durable_model_tool_loop.latest_loop_decision
                    if durable_model_tool_loop.latest_model_turn is not None:
                        second_turn_result = durable_model_tool_loop.latest_model_turn
                    if runtime_event_feedback_bridge is not None and control_plane_feedback is not None:
                        # Durable runner 返回的反馈可能包含 worker receipt 或新 executionId。
                        # 重新经过事件反馈桥，确保后续验证波次和最终闭环视图看到的是同一份事实快照。
                        runtime_event_feedback = runtime_event_feedback_bridge.augment(
                            request=request,
                            plan=plan,
                            snapshot=control_plane_feedback,
                        )
                        control_plane_feedback = runtime_event_feedback.snapshot

                    # Recovery Specialist 会直接读取一次受保护的 Java 诊断供模型分析，但后续
                    # preview/retry/apply 仍必须引用 agent-runtime 创建的正式 diagnosis audit。
                    # 当第一次 Bridge 只提交了 sync.execution.diagnose bootstrap 时，Durable runner
                    # 已在上方取得新的 auditId/runId feedback；此处用同一个 Specialist 结果再桥接一次，
                    # 让真正的恢复动作从该 feedback 派生 diagnosisRef。这个分支最多执行一次，且只由
                    # “唯一 accepted 工具就是只读 diagnose”触发，避免 duplicate-name 或普通恢复计划
                    # 被重复提交，也避免形成无界 Python 循环。
                    bootstrap_tool_names = tuple(
                        item.tool_name for item in bridge_result.accepted_tool_plans
                    )
                    if (
                        specialist_result.role.value == "RECOVERY_AGENT"
                        and bootstrap_tool_names == ("sync.execution.diagnose",)
                        and control_plane_feedback is not None
                    ):
                        recovery_action_bridge = specialist_toolplan_bridge.bridge(
                            request=request,
                            plan=durable_loop_plan or plan,
                            specialist_result=specialist_result,
                            control_plane_feedback=control_plane_feedback,
                        )
                        specialist_bridge_results.append(recovery_action_bridge)
                        if (
                            recovery_action_bridge.status is SpecialistBridgeStatus.ACCEPTED
                            and recovery_action_bridge.plan is not None
                            and recovery_action_bridge.model_turn is not None
                        ):
                            plan = recovery_action_bridge.plan
                            if durable_model_tool_loop_runner is not None:
                                check_cancelled()
                                recovery_action_loop = durable_model_tool_loop_runner.run(
                                    request=request,
                                    plan=recovery_action_bridge.plan,
                                    first_model_turn=recovery_action_bridge.model_turn,
                                    initial_feedback=control_plane_feedback,
                                    progress_event_sink=progress_event_sink,
                                )
                                durable_model_tool_loop = recovery_action_loop
                                durable_loop_plan = recovery_action_loop.latest_plan
                                plan = replace(plan, runtime_events=durable_loop_plan.runtime_events)
                                if recovery_action_loop.latest_feedback is not None:
                                    control_plane_feedback = recovery_action_loop.latest_feedback
                                if recovery_action_loop.latest_loop_decision is not None:
                                    loop_control_decision = recovery_action_loop.latest_loop_decision
                                if recovery_action_loop.latest_model_turn is not None:
                                    second_turn_result = recovery_action_loop.latest_model_turn
                                if runtime_event_feedback_bridge is not None and control_plane_feedback is not None:
                                    runtime_event_feedback = runtime_event_feedback_bridge.augment(
                                        request=request,
                                        plan=plan,
                                        snapshot=control_plane_feedback,
                                    )
                                    control_plane_feedback = runtime_event_feedback.snapshot
                break

            # bridge/Durable 可能刚刚创建出真实 taskId/executionId。只有在控制面反馈
            # 相对 bridge 前确实出现新事实时，才重新调度两个只读专业 Agent；不能用
            # specialist 的模型草案或 Python 自己猜测的 ID 触发预检/监控。
            if post_bridge_finalization_required:
                verification_plan = durable_loop_plan or plan
                verification_event_offset = len(streamed_specialist_events)
                verification_result, post_bridge_verification_summary = run_post_bridge_verification_wave(
                    request=request,
                    plan=verification_plan,
                    control_plane_feedback=control_plane_feedback,
                    previous_resource_fingerprint=pre_bridge_resource_fingerprint,
                    specialist_agent_coordinator=specialist_agent_coordinator,
                    specialist_allowed_tools_by_role=specialist_allowed_tools_by_role or {},
                    checkpoint_recorded=agent_turn_runner_checkpoint is not None,
                    event_sink=record_specialist_action,
                    base_context=_specialist_base_context(
                        request,
                        verification_plan,
                        control_plane_feedback=control_plane_feedback,
                    ),
                    execution_session=agent_execution_session.to_summary(),
                )
                specialist_verification_execution = verification_result
                verification_events = tuple(streamed_specialist_events[verification_event_offset:])
                if verification_events:
                    verification_plan = replace(
                        verification_plan,
                        runtime_events=verification_plan.runtime_events + verification_events,
                    )
                    plan = verification_plan
                    if durable_loop_plan is not None:
                        durable_loop_plan = verification_plan

        # 专业 bridge 和 Durable runner 发生在首轮 LangGraph/readiness/checkpoint 之后。
        # 因此这里必须以最新 plan 和最新控制面反馈覆盖所有“最终视图”，否则响应会把
        # specialist 之前的旧 task/tool 数量、旧 closure 和旧协作调度返回给前端。
        if post_bridge_finalization_required:
            check_cancelled()
            final_state = recompute_post_bridge_views(
                request=request,
                plan=durable_loop_plan or plan,
                readiness_policy_snapshot=readiness_policy_snapshot,
                control_plane_ingestion=control_plane_ingestion,
                control_plane_feedback=control_plane_feedback,
                runtime_event_feedback=runtime_event_feedback,
                loop_control_decision=loop_control_decision,
                second_turn_result=second_turn_result,
                memory_write_proposal=memory_write_proposal,
                durable_agent_loop_service=durable_agent_loop_service,
                multi_agent_execution_session_metrics=multi_agent_execution_session_metrics,
                multi_agent_turn_runner_workflow=multi_agent_turn_runner_workflow,
                multi_agent_turn_runner_metrics=multi_agent_turn_runner_metrics,
                langgraph_checkpointer_service=langgraph_checkpointer_service,
                langgraph_execution_gate_metrics=langgraph_execution_gate_metrics,
                workspace_context=workspace_context,
                skill_manifest_diagnostics=skill_manifest_diagnostics,
                plan_runtime_event_sink=progress_event_sink,
            )
            plan = final_state["plan"]
            tool_execution_readiness = final_state["tool_execution_readiness"]
            tool_execution_readiness_response = final_state["tool_execution_readiness_response"]
            agent_execution_gate_summary = final_state["agent_execution_gate_summary"]
            command_proposal_templates = final_state["command_proposal_templates"]
            agent_execution_closure_summary = final_state["agent_execution_closure_summary"]
            intelligent_gateway_governance = final_state["intelligent_gateway_governance"]
            agent_collaboration_workflow = final_state["agent_collaboration_workflow"]
            agent_collaboration_execution_plan_summary = final_state["agent_collaboration_execution_plan_summary"]
            agent_execution_session = final_state["agent_execution_session"]
            agent_turn_runner_summary = final_state["agent_turn_runner_summary"]
            durable_loop_checkpoint = final_state["durable_loop_checkpoint"]
            agent_turn_runner_checkpoint = final_state["agent_turn_runner_checkpoint"]
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
    check_cancelled()
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
    response["agentExecutionSession"] = agent_execution_session.to_summary()
    response["agentTurnRunner"] = agent_turn_runner_summary
    if specialist_agent_execution is not None:
        response["specialistAgentExecution"] = specialist_agent_execution.to_summary()
    if specialist_verification_execution is not None:
        response["specialistVerificationExecution"] = specialist_verification_execution.to_summary()
    if post_bridge_verification_summary is not None:
        response["postBridgeVerification"] = post_bridge_verification_summary
    if specialist_bridge_results:
        # 每一个专业结果都保留独立摘要，便于前端区分“同步草案已进入 Durable”与
        #“恢复动作正在等待审批/Java handoff”。摘要不包含 ToolPlan 参数正文。
        response["specialistToolPlanBridges"] = tuple(
            item.to_summary() for item in specialist_bridge_results
        )
    if agent_turn_runner_checkpoint is not None:
        response["agentTurnRunnerCheckpoint"] = agent_turn_runner_checkpoint
    response["agentMemoryRetrievalWorkflow"] = agent_memory_retrieval_workflow_summary
    response["userProfileMemory"] = plan.user_profile_context
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
    conversation_source_plan = plan if post_bridge_finalization_required else durable_loop_plan
    conversation_plan = (
        replace(
            conversation_source_plan,
            tool_plans=durable_loop_plan.tool_plans,
            response_summary=(
                str(getattr(second_turn_result, "summary", "") or "").strip()
                or durable_loop_plan.response_summary
            ),
            next_actions=durable_loop_plan.next_actions,
        )
        if durable_loop_plan is not None and conversation_source_plan is not None
        else plan
    )
    conversation_readiness = ToolExecutionReadinessService().evaluate(
        conversation_plan.tool_plans,
        policy=readiness_policy_snapshot.policy,
        policy_metadata=readiness_policy_snapshot.to_low_sensitive_summary(),
    )
    agent_conversation = build_agent_conversation_response(
        request,
        conversation_plan,
        conversation_readiness,
        control_plane_ingested=control_plane_ingestion is not None,
        control_plane_feedback=control_plane_feedback,
        autonomous_resolution_stopped=bool(
            (
                durable_model_tool_loop is not None
                and (
                    durable_model_tool_loop.stopped_reason in {
                        "MODEL_COMPLETED_WITHOUT_MORE_TOOLS",
                        "MODEL_TURN_LIMIT_REACHED",
                    }
                    or durable_model_tool_loop.stopped_reason.startswith("STOP_")
                )
            )
            or (
                second_turn_result is not None
                and bool(getattr(second_turn_result, "error_code", None))
            )
            or (
                control_plane_feedback is not None
                and any(
                    getattr(getattr(item, "status", None), "value", "") == "failed"
                    for item in getattr(control_plane_feedback, "feedback_items", ())
                )
            )
            or (
                loop_control_decision is not None
                and not bool(getattr(loop_control_decision, "allowed", False))
                and str(getattr(getattr(loop_control_decision, "action", None), "value", ""))
                not in {"wait_for_control_plane", "wait_for_approval"}
            )
        ),
    )
    response["agentConversation"] = agent_conversation
    response["agentObservationTimeline"] = build_agent_observation_timeline(
        plan,
        conversation=agent_conversation,
        control_plane_handoff=agent_execution_closure_summary.get("controlPlaneHandoff", {}),
        control_plane_ingestion=control_plane_ingestion,
    )
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
    if durable_model_tool_loop is not None:
        response["agentDurableModelToolLoop"] = durable_model_tool_loop.to_summary()
    if durable_loop_checkpoint is not None:
        response["agentDurableLoop"] = durable_loop_checkpoint.to_summary()
    if memory_write_proposal is not None:
        response["memoryWriteProposal"] = memory_write_proposal.to_summary()
    check_cancelled()
    return response


def _delegate_legacy_knowledge_rag_to_specialist(
    plan: AgentPlan,
    *,
    specialist_agent_coordinator: SpecialistAgentCoordinator | None,
    specialist_allowed_tools_by_role: Mapping[str, tuple[str, ...]] | None,
) -> AgentPlan:
    """Transfer RAG ownership from the legacy ToolPlan to the governed Knowledge Agent.

    The function changes planning ownership, not authorization. Knowledge Specialist execution still
    requires a recorded turn checkpoint, a concrete project scope, a registered Specialist instance,
    and an allow-listed ``knowledge.rag.query`` capability. Its result remains low-sensitive and any
    follow-up side effect must still cross the Specialist ToolPlan bridge and Java control plane.

    A low-sensitive workflow diagnostic is retained so operators can explain why the model proposed a
    tool that does not appear in the Java ingestion batch. Prompt text, model output, query text and tool
    arguments are deliberately excluded from that diagnostic.
    """

    allowed_tools = tuple((specialist_allowed_tools_by_role or {}).get("KNOWLEDGE_AGENT", ()))
    specialist_owns_rag = (
        specialist_agent_coordinator is not None
        and "knowledge.rag.query" in allowed_tools
    )
    if not specialist_owns_rag:
        return plan

    delegated_count = sum(
        1 for tool_plan in plan.tool_plans if tool_plan.tool_name == "knowledge.rag.query"
    )
    if delegated_count == 0:
        return plan

    diagnostics = dict(plan.workflow_diagnostics)
    diagnostics["specialistToolOwnership"] = {
        "schemaVersion": "datasmart.specialist-tool-ownership.v1",
        "delegatedTools": ("knowledge.rag.query",),
        "delegatedPlanCount": delegated_count,
        "ownerRole": "KNOWLEDGE_AGENT",
        "javaLegacyPlanSuppressed": True,
        "payloadPolicy": "LOW_SENSITIVE_TOOL_NAME_AND_COUNT_ONLY",
    }
    return replace(
        plan,
        tool_plans=tuple(
            tool_plan
            for tool_plan in plan.tool_plans
            if tool_plan.tool_name != "knowledge.rag.query"
        ),
        state_trace=plan.state_trace + ("delegate_knowledge_rag_to_specialist",),
        workflow_diagnostics=diagnostics,
    )


def _control_plane_ready_subplan(
    plan: AgentPlan,
    readiness: Any,
) -> AgentPlan:
    """Select only tools that the readiness graph allows to execute now.

    The returned plan preserves the original request/model/event metadata so Java
    audit records remain correlated with the user turn. Only ``tool_plans`` and
    the aggregate approval flag are narrowed. This is intentionally a subplan,
    not a mutation of the user-visible plan.
    """

    ready_indices = {
        int(item.plan_index)
        for item in tuple(getattr(readiness, "items", ()) or ())
        if bool(getattr(item, "executable", False))
    }
    ready_indices.update(
        _required_directional_metadata_indices(
            plan,
            readiness,
            already_ready_indices=ready_indices,
        )
    )
    ready_tools = tuple(
        tool_plan
        for index, tool_plan in enumerate(plan.tool_plans, start=1)
        if index in ready_indices
    )
    return replace(
        plan,
        tool_plans=ready_tools,
        requires_human_approval=any(
            tool_plan.requires_human_approval for tool_plan in ready_tools
        ),
    )


def _required_directional_metadata_indices(
    plan: AgentPlan,
    readiness: Any,
    *,
    already_ready_indices: set[int],
) -> set[int]:
    """Keep a safe source/target metadata evidence wave from becoming asymmetric.

    The readiness budget is intentionally conservative and may allow the two
    connection tests plus only the first directional metadata read in a sync
    plan.  Treating the target-side sibling as a completely separate model turn
    leaves the specialist bridge with source-only evidence and makes task
    planning depend on a model rediscovering a deterministic platform step.

    This method permits one narrow exception: a directional metadata read that
    is *only* ``THROTTLED`` may join the same Java DAG when its matching
    connection test is already executable.  It never admits a node waiting for
    clarification or approval, a blocked node, an invalid parameter set, or any
    write-capable lifecycle tool.  Java still rechecks datasource permissions,
    executes the dependency edge, and records a separate audit fact for each
    read.

    Args:
        plan: The complete user-visible plan before the control-plane boundary.
        readiness: The low-sensitive readiness report for exactly that plan.
        already_ready_indices: One-based plan indices already admitted by the
            ordinary readiness decision.

    Returns:
        The one-based metadata plan indices that can safely join this evidence
        wave.  An empty set means the ordinary readiness frontier is preserved.
    """

    readiness_by_index = {
        int(item.plan_index): item
        for item in tuple(getattr(readiness, "items", ()) or ())
    }
    plan_index_by_tool = {
        tool_plan.tool_name: index
        for index, tool_plan in enumerate(plan.tool_plans, start=1)
    }
    evidence_pairs = tuple(
        (
            plan_index_by_tool.get(f"datasource.{direction}.connection.test"),
            plan_index_by_tool.get(f"datasource.{direction}.metadata.read"),
            direction,
        )
        for direction in ("source", "target")
    )
    # The exemption is intentionally pair-scoped.  If either connection test
    # is itself outside the admitted budget, adding only the other side's
    # metadata would recreate the asymmetric evidence state this method exists
    # to prevent and could make a datasource appear validated when its peer was
    # never checked.
    if any(connection_index not in already_ready_indices for connection_index, _, _ in evidence_pairs):
        return set()

    evidence_indices: set[int] = set()
    for connection_index, metadata_index, direction in evidence_pairs:
        metadata_name = f"datasource.{direction}.metadata.read"
        if connection_index is None or metadata_index is None:
            continue
        metadata_plan = plan.tool_plans[metadata_index - 1]
        metadata_readiness = readiness_by_index.get(metadata_index)
        decision = str(getattr(getattr(metadata_readiness, "decision", None), "value", ""))
        if (
            metadata_index not in already_ready_indices
            and decision == "throttled"
            and metadata_plan.parameter_validation.can_execute
            and not metadata_plan.requires_human_approval
            and metadata_plan.tool_name == metadata_name
        ):
            evidence_indices.add(metadata_index)
    return evidence_indices


def _control_plane_ingestion_subplan(
    plan: AgentPlan,
    readiness: Any,
) -> AgentPlan:
    """Choose the correct Java ingestion boundary for this user turn.

    A complete sync task must not depend on a second model turn to rediscover
    deterministic lifecycle nodes. Once the draft arguments, metadata and
    mappings are complete, Java receives the full DAG and keeps mutation nodes
    in ``WAITING_APPROVAL`` until the user confirms it. Incomplete requests
    still use the narrower read-only frontier to collect facts safely.
    """

    lifecycle_names = {
        "sync.task.draft.save",
        "sync.task.precheck",
        "sync.task.publish",
    }
    plan_names = {item.tool_name for item in plan.tool_plans}
    has_complete_lifecycle = lifecycle_names.issubset(plan_names) and all(
        bool(item.parameter_validation.can_execute)
        for item in plan.tool_plans
    )
    # THROTTLED only limits automatic pre-confirm execution. It must not split
    # an explicitly reviewed lifecycle DAG into several model-dependent runs.
    if (
        has_complete_lifecycle
        and readiness.blocked_count == 0
        and readiness.clarification_required_count == 0
    ):
        return plan
    return _control_plane_ready_subplan(plan, readiness)


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
    # Immediately after ingestion, datasource workers can still be committing a
    # source/target metadata audit.  The collector's bounded method only polls
    # those two read-only nodes and returns the latest Java-owned snapshot.  A
    # fake or legacy collector may expose only ``collect``; retaining that
    # fallback keeps the response assembler compatible with tests and older
    # integrations without weakening the bridge's strict evidence checks.
    bounded_collect = getattr(
        control_plane_feedback_collector,
        "collect_with_bounded_metadata_wait",
        None,
    )
    if callable(bounded_collect):
        return bounded_collect(plan)
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


def _specialist_base_context(
    request: AgentRequest,
    plan: AgentPlan,
    *,
    control_plane_feedback: Any | None = None,
) -> dict[str, Any]:
    """为专业 Agent 构建瞬时、字段白名单化的共享上下文。

    这里不复制完整 `request.variables`，因为其中未来可能包含制品定位、内部控制面字段或业务正文。首批
    专业 Agent 只需要数据源消歧提示、同步模式提示和主 Agent 已形成的低敏计划事实；SQL、WHERE、字段
    映射正文等内容后续应通过受控 payloadReference 获取，而不是进入可持久化 handoff 状态。
    """

    safe_variable_names = (
        "sourceDatasourceId",
        "sourceDatasourceName",
        "sourceConnectorType",
        "sourceDatabaseType",
        "targetDatasourceId",
        "targetDatasourceName",
        "targetConnectorType",
        "targetDatabaseType",
        "requestedDirections",
        "taskName",
        "syncMode",
        "writeMode",
        "scheduleType",
        "taskId",
        "executionId",
        "taskKind",
        "failureReference",
        "failureCode",
        "caseReference",
        "taskReference",
    )
    safe_variables = {
        name: request.variables[name]
        for name in safe_variable_names
        if name in request.variables and _is_low_sensitive_scalar_or_sequence(request.variables[name])
    }
    # 前端高级配置把用户已经审核过的任务名称、模式、对象/字段映射、WHERE、调度和 SQL 放在
    # ``variables.dataSyncRequest``。这些值是同步规划的业务输入，不是数据源凭据；如果专业 Agent
    # 只能看到顶层 datasourceId，它就会再次声称缺少映射，形成“用户已经补全、Agent 仍然追问”的假循环。
    #
    # 这里不能直接复制整个对象：未来前端可能新增连接信息或内部控制字段。因此通过专用白名单函数逐层
    # 重建瞬时上下文，只允许手工向导同契约字段进入本次进程内 handoff。专业事实表仍只保存低敏摘要，
    # 不保存该配置正文；真正持久化和执行继续由 Java ToolPlan、审批与 data-sync 控制面负责。
    data_sync_request = _specialist_sync_request(request.variables.get("dataSyncRequest"))
    if data_sync_request:
        safe_variables["dataSyncRequest"] = data_sync_request
    for direction_name in ("source", "target", "sourceDatasource", "targetDatasource"):
        nested = request.variables.get(direction_name)
        if isinstance(nested, dict):
            safe_variables[direction_name] = {
                key: value
                for key, value in nested.items()
                if key in {"datasourceId", "id", "datasourceName", "name", "connectorType", "databaseType", "type"}
                and _is_low_sensitive_scalar_or_sequence(value)
            }

    # 恢复和监控请求通常把定位字段放在一个小型嵌套对象中。这里只复制 ID、状态、类型和布尔开关，
    # 不复制日志正文、SQL、WHERE、字段映射、样本或任意凭据。
    for context_name in (
        "monitoringRequest",
        "monitorRequest",
        "recoveryContext",
        "failureContext",
    ):
        nested = request.variables.get(context_name)
        if isinstance(nested, Mapping):
            safe_variables[context_name] = {
                key: value
                for key, value in nested.items()
                if key in {
                    "taskId",
                    "executionId",
                    "taskKind",
                    "syncMode",
                    "status",
                    "failureCode",
                    "failureReference",
                    "includeLogs",
                    "includeObjects",
                }
                and _is_low_sensitive_scalar_or_sequence(value)
            }

    control_plane_facts = _specialist_control_plane_facts(plan, control_plane_feedback)
    if control_plane_facts:
        safe_variables["controlPlaneFacts"] = control_plane_facts
        # 三类确定性专业 Agent 都使用顶层定位字段。按反馈顺序保留最后一个非空值，可覆盖计划参数中的
        # 草稿 ID，但不能覆盖请求显式提供的定位，避免一次多工具计划串错对象。
        for locator_name in ("taskId", "executionId", "taskKind", "failureCode"):
            if locator_name in safe_variables:
                continue
            for fact in reversed(control_plane_facts):
                value = fact.get(locator_name)
                if value is not None:
                    safe_variables[locator_name] = value
                    break
        if "taskId" in safe_variables:
            safe_variables.setdefault("taskReference", safe_variables["taskId"])

    intent = plan.intent_analysis
    domains = tuple(
        getattr(domain, "value", str(domain))
        for domain in (getattr(intent, "governance_domains", ()) if intent is not None else ())
    )
    return {
        **safe_variables,
        "traceId": plan.request_id,
        "governanceDomains": domains,
        "intentConfidence": getattr(intent, "confidence", None) if intent is not None else None,
        "plannedToolNames": tuple(tool.tool_name for tool in plan.tool_plans),
        "payloadPolicy": "LOW_SENSITIVE_SPECIALIST_TRANSIENT_CONTEXT_ONLY",
    }


def _specialist_control_plane_facts(
    plan: AgentPlan,
    control_plane_feedback: Any | None,
) -> tuple[dict[str, Any], ...]:
    """提取专业 Agent 可用的控制面定位事实，不复制工具结果正文。

    控制面反馈的 ``result`` 可能包含元数据、字段映射或错误详情，绝不能整体放入多 Agent handoff。
    本方法只提取任务/执行定位、生命周期状态、失败码和审计引用。若工具尚未返回反馈，则从已经通过
    参数治理的 ToolPlan 中提取同一组低敏字段，让只读监控和预检查仍可定位既有任务。
    """

    facts: list[dict[str, Any]] = []
    for tool in plan.tool_plans:
        locator_values = _specialist_locator_values(tool.arguments)
        if not locator_values:
            continue
        facts.append({
            "toolName": tool.tool_name,
            "source": "GOVERNED_TOOL_PLAN",
            **locator_values,
        })

    for item in tuple(getattr(control_plane_feedback, "feedback_items", ()) or ()):
        result = getattr(item, "result", {})
        locator_values = _specialist_locator_values(result)
        status = getattr(getattr(item, "status", None), "value", None)
        fact = {
            "toolName": str(getattr(item, "tool_name", "") or ""),
            "status": str(status or ""),
            "auditId": getattr(item, "audit_id", None),
            "runId": getattr(item, "run_id", None),
            "outputRef": getattr(item, "output_ref", None),
            "errorCode": getattr(item, "error_code", None),
            "source": "JAVA_CONTROL_PLANE_FEEDBACK",
            **locator_values,
        }
        facts.append({key: value for key, value in fact.items() if value not in (None, "", (), [])})
    return tuple(facts[-30:])


def _specialist_locator_values(value: Any, *, depth: int = 0) -> dict[str, Any]:
    """在有限深度内查找任务定位字段，拒绝把未知嵌套对象带入 handoff。"""

    if not isinstance(value, Mapping) or depth > 3:
        return {}
    allowed_names = {
        "taskId",
        "executionId",
        "taskKind",
        "syncMode",
        "status",
        "precheckStatus",
        "failureCode",
        "failureReference",
    }
    result = {
        str(key): item
        for key, item in value.items()
        if str(key) in allowed_names and _is_low_sensitive_scalar_or_sequence(item)
    }
    for item in value.values():
        if not isinstance(item, Mapping):
            continue
        for key, nested_value in _specialist_locator_values(item, depth=depth + 1).items():
            result.setdefault(key, nested_value)
    return result


def _is_low_sensitive_scalar_or_sequence(value: Any) -> bool:
    """限制专业 handoff 的直接字段类型，拒绝任意嵌套正文。"""

    if isinstance(value, (str, int, float, bool)) or value is None:
        return True
    return isinstance(value, (tuple, list)) and len(value) <= 20 and all(
        isinstance(item, (str, int, float, bool)) or item is None for item in value
    )


def _specialist_sync_request(value: Any) -> dict[str, Any]:
    """重建 DATA_SYNC_AGENT 可消费的瞬时任务配置，拒绝任意未知字段穿透。

    该函数解决的是“必要业务正文”和“敏感连接凭据”必须分开的边界问题：WHERE、只读 SQL、表名和字段
    映射虽然不属于低敏诊断字段，但它们是用户明确交给同步 Agent 的任务内容；password、JDBC URL、Token
    等连接秘密则永远不应由自然语言 Agent 接收。为避免未来新增前端字段时意外扩大暴露面，本函数采用
    显式白名单和有界数组，而不是递归复制未知 JSON。

    返回对象只存在于当前请求内存中。调用方不得把它写入专业 Agent 低敏事实表、日志或运行事件。
    """

    if not isinstance(value, Mapping):
        return {}

    scalar_names = {
        "taskName",
        "taskDescription",
        "groupCode",
        "groupName",
        "sourceDatasourceId",
        "targetDatasourceId",
        "syncMode",
        "writeStrategy",
        "writeMode",
        "scheduleConfig",
        "customSqlText",
        "customSqlConfirmed",
        "targetTableResolution",
        "mappingDefaultsConfirmed",
    }
    result = {
        str(name): item
        for name, item in value.items()
        if str(name) in scalar_names and isinstance(item, (str, int, float, bool))
    }

    raw_mappings = value.get("objectMappings") or value.get("object_mappings")
    if not isinstance(raw_mappings, (list, tuple)):
        return result

    mappings: list[dict[str, Any]] = []
    mapping_names = {
        "objectKey",
        "sourceSchemaName",
        "sourceObjectName",
        "targetSchemaName",
        "targetObjectName",
        "whereCondition",
    }
    field_names = {
        "sourceField",
        "sourceType",
        "targetField",
        "targetType",
        "nullable",
        "primaryKey",
        "syncEnabled",
        "typeCompatible",
        "transform",
    }
    # 一个交互式任务最多传 500 条对象映射、每个对象最多 2,000 条字段映射。超出部分由确定性
    # 参数校验明确报错或要求批量导入，避免单次 Agent turn 被异常 JSON 占满内存。
    for raw_mapping in raw_mappings[:500]:
        if not isinstance(raw_mapping, Mapping):
            continue
        mapping = {
            str(name): item
            for name, item in raw_mapping.items()
            if str(name) in mapping_names and isinstance(item, (str, int, float, bool))
        }
        raw_fields = raw_mapping.get("fieldMappings") or raw_mapping.get("field_mappings")
        if isinstance(raw_fields, (list, tuple)):
            mapping["fieldMappings"] = [
                {
                    str(name): item
                    for name, item in raw_field.items()
                    if str(name) in field_names and isinstance(item, (str, int, float, bool))
                }
                for raw_field in raw_fields[:2_000]
                if isinstance(raw_field, Mapping)
            ]
        mappings.append(mapping)
    result["objectMappings"] = mappings
    return result


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
