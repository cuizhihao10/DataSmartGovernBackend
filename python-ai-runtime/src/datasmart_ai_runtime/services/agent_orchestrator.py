"""Agent 编排器。

这个编排器最初用手写顺序流模拟 OpenClaw/LangGraph 风格的“节点式状态流转”。现在主路径已经接入
`LangGraphAgentPlanningWorkflow` 作为工作流外壳：LangGraph 负责低敏控制流 preview 和框架诊断，
现有编排器继续负责稳定的业务计划。后续可以把 plan_tools、retrieve_memory、readiness、resume gate
逐步迁移成真实 LangGraph 节点，而不是一次性推翻已通过 smoke 的主链路。
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from uuid import uuid4

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ModelRoute,
    ToolPlan,
)
from datasmart_ai_runtime.domain.context import ContextBlock
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.domain.intent import IntentAnalysis
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.domain.skills import AgentSkillPlan
from datasmart_ai_runtime.services.agent_model_intent_node import AgentModelIntentNode, AgentModelIntentNodeResult
from datasmart_ai_runtime.services.agent_plan_presentation import (
    build_next_actions,
    build_response_summary,
    has_parameter_issues,
    requires_parameter_clarification,
)
from datasmart_ai_runtime.services.context_builder import ContextBuilder, DefaultContextBuilder
from datasmart_ai_runtime.services.intent_analyzer import IntentAnalyzer, RuleBasedIntentAnalyzer
from datasmart_ai_runtime.services.langgraph_planning_workflow import LangGraphAgentPlanningWorkflow
from datasmart_ai_runtime.services.memory.memory_planner import AgentMemoryPlanner
from datasmart_ai_runtime.services.memory.memory_retriever import AgentMemoryRetriever, InMemoryAgentMemoryRetriever
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_gateway_context import build_model_gateway_context
from datasmart_ai_runtime.services.model_gateway.model_gateway_runtime_event import (
    build_model_gateway_routed_event_attributes,
)
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_policy_provider import ModelToolCallBudgetPolicyProvider
from datasmart_ai_runtime.services.model_gateway.model_tool_feedback_provider import ModelToolExecutionFeedbackProvider
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_plan_dag import ToolPlanDagAnnotator
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class AgentOrchestrator:
    """面向治理目标的 Agent 规划入口。

    当前类刻意保持很薄：它不直接写模型调用、不直接执行工具、不直接访问数据库。它只负责编排
    关键步骤并输出结构化计划。这样后续扩展 RAG、GraphRAG、模型调用、Kafka 状态同步时，
    每个能力都可以作为独立节点或服务注入进来，避免形成一个难维护的大型实现类。
    """

    def __init__(
        self,
        model_routes: ModelRouteRegistry,
        tool_planner: ToolPlanner,
        model_providers: ModelProviderRegistry | None = None,
        context_builder: ContextBuilder | None = None,
        intent_analyzer: IntentAnalyzer | None = None,
        memory_planner: AgentMemoryPlanner | None = None,
        memory_retriever: AgentMemoryRetriever | None = None,
        model_gateway: ModelGatewayGovernanceService | None = None,
        skill_registry: AgentSkillRegistry | None = None,
        model_intent_node: AgentModelIntentNode | None = None,
        model_tool_call_budget_policy_provider: ModelToolCallBudgetPolicyProvider | None = None,
        tool_execution_feedback_provider: ModelToolExecutionFeedbackProvider | None = None,
        planning_workflow: LangGraphAgentPlanningWorkflow | None = None,
        user_profile_memory: Any | None = None,
    ) -> None:
        self._model_routes = model_routes
        self._tool_planner = tool_planner
        self._model_providers = model_providers or ModelProviderRegistry()
        self._context_builder = context_builder or DefaultContextBuilder()
        self._intent_analyzer = intent_analyzer or RuleBasedIntentAnalyzer()
        self._memory_planner = memory_planner or AgentMemoryPlanner()
        self._memory_retriever = memory_retriever or InMemoryAgentMemoryRetriever()
        self._model_gateway = model_gateway or ModelGatewayGovernanceService(model_routes)
        self._skill_registry = skill_registry
        self._dag_annotator = ToolPlanDagAnnotator()
        self._model_intent_node = model_intent_node or AgentModelIntentNode(
            model_providers=self._model_providers,
            model_gateway=self._model_gateway,
            tool_planner=self._tool_planner,
            model_tool_call_budget_policy_provider=model_tool_call_budget_policy_provider,
            tool_execution_feedback_provider=tool_execution_feedback_provider,
        )
        self._planning_workflow = planning_workflow or LangGraphAgentPlanningWorkflow.from_env()
        self._user_profile_memory = user_profile_memory

    def plan(
        self,
        request: AgentRequest,
        event_sink: Callable[[Any], None] | None = None,
    ) -> AgentPlan:
        """为用户治理目标生成 Agent 计划。

        返回值中的 `state_trace` 是后续可观测性的雏形。商业化 Agent 平台需要知道一次请求经过
        哪些节点、在哪个节点需要审批、哪个节点发生异常。当前先记录节点名称，后续可扩展为
        包含耗时、输入摘要、输出摘要、错误码、TraceId 的运行时事件。
        """

        # LangGraph 工作流外壳先运行，但它只产生低敏 workflow 诊断，不执行工具、不写 outbox、不调用模型。
        # 这样我们能尽快把主流 LangGraph 接入真实 `/agent/plans` 主路径，同时不破坏现有编排器已经稳定的
        # 模型路由、工具计划、记忆检索和 runtime event 逻辑。
        request_id = self._resolve_request_id(request)
        run_id = str(uuid4())
        event_recorder = RuntimeEventRecorder(
            request=request,
            request_id=request_id,
            run_id=run_id,
            session_id=self._resolve_session_id(request),
            event_sink=event_sink,
        )
        event_recorder.record(
            AgentRuntimeEventType.AGENT_PLAN_STARTED,
            "receive_goal",
            "已接收用户目标，开始构建本轮受控 Agent 计划。",
            attributes={
                "workflow": "langgraph_agent_planning",
                "progressMode": "REAL_STAGE_EVENTS",
            },
        )

        workflow_diagnostics = self._planning_workflow.run(request)
        state_trace: list[str] = [f"workflow:{workflow_diagnostics.status.lower()}"]

        state_trace.append("receive_goal")
        state_trace.append("select_model_route")
        state_trace.append("build_context")
        context_blocks, user_profile_context = self._build_context(request, event_recorder)

        state_trace.append("route_model_gateway")
        model_gateway_context = build_model_gateway_context(request, context_blocks)
        model_gateway_decision = self._model_gateway.decide(model_gateway_context)
        selected_route = model_gateway_decision.selected_route
        if model_gateway_decision.cache_plan:
            # `ModelGatewayRequestContext` 是冻结 dataclass，但 attributes 是一次请求内的扩展字典。
            # 这里把路由决策生成的 cachePlan 回填给上下文，是为了让后续模型 Provider metadata 能统一
            # 消费“模型网关治理结论”，而不把 AgentModelIntentNode 直接耦合到完整 RoutingDecision。
            model_gateway_context.attributes["cachePlan"] = model_gateway_decision.cache_plan.to_summary()
        event_recorder.record(
            AgentRuntimeEventType.MODEL_GATEWAY_ROUTED,
            "route_model_gateway",
            "已完成模型网关治理决策。",
            severity=AgentRuntimeEventSeverity.WARNING if selected_route is None else AgentRuntimeEventSeverity.INFO,
            attributes=build_model_gateway_routed_event_attributes(model_gateway_decision),
        )

        state_trace.append("analyze_intent")
        intent_analysis = self._intent_analyzer.analyze(request, context_blocks)
        event_recorder.record(
            AgentRuntimeEventType.INTENT_ANALYZED,
            "analyze_intent",
            "已完成结构化意图分析。",
            attributes={
                "domains": tuple(domain.value for domain in intent_analysis.governance_domains),
                "riskTags": tuple(tag.value for tag in intent_analysis.risk_tags),
                "candidateTools": intent_analysis.candidate_tools,
                "confidence": intent_analysis.confidence,
            },
        )

        state_trace.append("select_skills")
        skill_plan = self._select_skills(request, intent_analysis)
        self._record_skill_admission_event(event_recorder, skill_plan)

        state_trace.append("invoke_model_intent")
        model_intent_result = self._invoke_model_intent(
            selected_route,
            request,
            context_blocks,
            model_gateway_context,
            intent_analysis,
            skill_plan,
            event_recorder,
        )
        if model_intent_result.tool_call_count > 0:
            state_trace.append("govern_model_tool_calls")

        state_trace.append("plan_tools")
        rule_tool_plans = self._tool_planner.plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
        )
        tool_plans = self._dag_annotator.annotate(
            self._merge_tool_plans(model_intent_result.model_tool_plans, rule_tool_plans)
        )
        model_tool_names = tuple(plan.tool_name for plan in model_intent_result.model_tool_plans)
        rule_tool_names = tuple(plan.tool_name for plan in rule_tool_plans)
        final_tool_names = tuple(plan.tool_name for plan in tool_plans)
        tool_selection_source = self._tool_selection_source(model_tool_names, rule_tool_names)
        model_interaction_summary = {
            **dict(model_intent_result.public_interaction),
            "planning": {
                "toolSelectionSource": tool_selection_source,
                "modelGeneratedToolCount": len(model_tool_names),
                "modelGeneratedToolNames": model_tool_names,
                "ruleGeneratedToolCount": len(rule_tool_names),
                "ruleGeneratedToolNames": rule_tool_names,
                "finalToolCount": len(final_tool_names),
                "finalToolNames": final_tool_names,
            },
        }
        event_recorder.record(
            AgentRuntimeEventType.TOOL_PLANNED,
            "plan_tools",
            "已完成工具计划生成。",
            attributes={
                "toolCount": len(tool_plans),
                "toolNames": final_tool_names,
                "modelGeneratedToolCount": len(model_tool_names),
                "modelGeneratedToolNames": model_tool_names,
                "ruleGeneratedToolCount": len(rule_tool_names),
                "ruleGeneratedToolNames": rule_tool_names,
                "toolSelectionSource": tool_selection_source,
            },
        )

        state_trace.append("plan_memory")
        memory_plan = self._memory_planner.plan(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
            tool_plans=tool_plans,
        )
        state_trace.append("retrieve_memory")
        memory_retrieval_report = self._memory_retriever.retrieve(request, memory_plan)
        event_recorder.record(
            AgentRuntimeEventType.MEMORY_RETRIEVED,
            "retrieve_memory",
            "已根据记忆计划完成分层记忆检索。",
            attributes={
                "targetCount": len(memory_plan.retrieval_targets),
                "totalRetrieved": memory_retrieval_report.total_retrieved,
                "retriever": memory_retrieval_report.attributes.get("retriever", "custom"),
            },
        )

        requires_human_approval = any(plan.requires_human_approval for plan in tool_plans)
        has_parameter_issues_found = has_parameter_issues(tool_plans)
        requires_parameter_clarification_found = requires_parameter_clarification(tool_plans)

        if requires_parameter_clarification_found:
            state_trace.append("clarify_missing_parameters")
        elif has_parameter_issues_found:
            state_trace.append("prepare_draft_with_missing_parameters")

        if has_parameter_issues_found:
            event_recorder.record(
                AgentRuntimeEventType.TOOL_PARAMETER_VALIDATED,
                "validate_tool_parameters",
                "工具参数校验发现缺失项，当前计划不能直接进入真实执行。",
                severity=AgentRuntimeEventSeverity.WARNING,
                attributes={
                    "issueCount": sum(len(plan.parameter_validation.issues) for plan in tool_plans),
                    "mustClarify": requires_parameter_clarification_found,
                },
            )

        if requires_human_approval:
            state_trace.append("wait_human_approval")
            event_recorder.record(
                AgentRuntimeEventType.APPROVAL_WAITING,
                "wait_human_approval",
                "工具计划包含高风险或需审批操作，必须等待人工确认。",
                severity=AgentRuntimeEventSeverity.AUDIT,
                attributes={
                    "approvalToolNames": tuple(
                        plan.tool_name for plan in tool_plans if plan.requires_human_approval
                    ),
                },
            )
        elif not has_parameter_issues_found:
            state_trace.append("ready_for_control_plane_execution")

        response_summary = build_response_summary(
            tool_plans=tool_plans,
            requires_human_approval=requires_human_approval,
        )

        next_actions = build_next_actions(tool_plans, requires_human_approval)
        event_recorder.record(
            AgentRuntimeEventType.AGENT_PLAN_COMPLETED,
            "complete_agent_plan",
            "已完成模型辅助决策、Skill 选择、工具规划和记忆检索。",
            attributes={
                "toolCount": len(tool_plans),
                "requiresHumanApproval": requires_human_approval,
                "requiresClarification": requires_parameter_clarification_found,
            },
        )
        return AgentPlan(
            request_id=request_id,
            selected_route=selected_route,
            state_trace=tuple(state_trace),
            tool_plans=tool_plans,
            requires_human_approval=requires_human_approval,
            response_summary=response_summary,
            next_actions=next_actions,
            model_intent_summary=f"{intent_analysis.summary} 模型节点摘要：{model_intent_result.summary}",
            model_decision_summary=model_intent_result.summary,
            model_invocation_summary=dict(model_intent_result.invocation_summary),
            model_interaction_summary=model_interaction_summary,
            context_blocks=context_blocks,
            intent_analysis=intent_analysis,
            model_gateway_decision=model_gateway_decision,
            skill_plan=skill_plan,
            memory_plan=memory_plan,
            memory_retrieval_report=memory_retrieval_report,
            user_profile_context=user_profile_context,
            runtime_events=event_recorder.events(),
            workflow_diagnostics=workflow_diagnostics.to_summary(),
        )

    @staticmethod
    def _tool_selection_source(
        model_tool_names: tuple[str, ...],
        rule_tool_names: tuple[str, ...],
    ) -> str:
        """解释最终工具计划由谁提出，避免把规则兜底误标成模型决策。"""

        if model_tool_names and rule_tool_names:
            return "MODEL_AND_SYSTEM_RULE_MERGED"
        if model_tool_names:
            return "MODEL_PROPOSED"
        if rule_tool_names:
            return "SYSTEM_RULE_FALLBACK"
        return "NO_TOOL_SELECTED"

    def _select_skills(self, request: AgentRequest, intent_analysis: IntentAnalysis) -> AgentSkillPlan:
        """选择本次请求适用的 Skill。

        Skill 注册表在默认 API 启动路径中会注入；如果某些测试或极简运行环境没有配置 Skill 注册表，
        编排器仍会返回空计划而不是失败。这样可以保持向后兼容，也方便后续把 Skill 来源迁移到 Java
        控制面或插件市场。
        """

        if self._skill_registry is None:
            return AgentSkillPlan(rationale="当前运行环境未配置 Skill 注册表，跳过 Skill 选择。")
        return self._skill_registry.select(request.objective, intent_analysis, request=request)

    @staticmethod
    def _record_skill_admission_event(
        event_recorder: RuntimeEventRecorder,
        skill_plan: AgentSkillPlan,
    ) -> None:
        """记录 Skill 选择与准入结果。

        Skill admission 是“能力包级治理事实”，它早于工具 schema 暴露和工具计划生成：
        - 如果某个 Skill 被拒绝，后续模型就不应因为该 Skill 看到对应工具集合；
        - 如果某个 Skill 只是 CONDITIONAL，本地学习可以继续，但生产控制面应提示补充权限/角色事实；
        - 如果没有任何 Skill 命中，也应该被记录，方便运营判断是意图识别不足还是 Skill 目录缺失。

        事件 attributes 只保留低敏摘要，不写入用户 objective、权限全集、工具参数或模型消息，避免事件
        回放系统扩大敏感数据暴露面。
        """

        selected = tuple(
            {
                "skillCode": item.skill_code,
                "domain": item.domain.value,
                "score": item.score,
                "riskLevel": item.risk_level,
                "admissionStatus": item.admission_status,
                "admissionReasons": item.admission_reasons,
            }
            for item in skill_plan.selected_skills
        )
        rejected = tuple(
            {
                "skillCode": item.skill_code,
                "domain": item.domain.value,
                "score": item.score,
                "riskLevel": item.risk_level,
                "admissionStatus": item.admission_status,
                "admissionReasons": item.admission_reasons,
            }
            for item in skill_plan.rejected_skills
        )
        severity = AgentRuntimeEventSeverity.AUDIT if rejected else AgentRuntimeEventSeverity.INFO
        event_recorder.record(
            AgentRuntimeEventType.SKILL_ADMISSION_EVALUATED,
            "select_skills",
            "已完成 Agent Skill 选择与准入治理评估。",
            severity=severity,
            attributes={
                "availableSkillCount": skill_plan.available_skill_count,
                "selectedSkillCount": len(skill_plan.selected_skills),
                "rejectedSkillCount": len(skill_plan.rejected_skills),
                "selectedSkills": selected,
                "rejectedSkills": rejected,
                "rationale": skill_plan.rationale,
            },
        )

    def _invoke_model_intent(
        self,
        selected_route: ModelRoute | None,
        request: AgentRequest,
        context_blocks: tuple[ContextBlock, ...],
        model_gateway_context: ModelGatewayRequestContext,
        intent_analysis: IntentAnalysis,
        skill_plan: AgentSkillPlan,
        event_recorder: RuntimeEventRecorder,
    ) -> AgentModelIntentNodeResult:
        """调用模型意图节点。

        具体的 Provider 调用、候选工具暴露、模型 tool_calls 治理和事件记录已经拆到
        `AgentModelIntentNode`。编排器保留这一层薄封装，是为了让主流程读起来仍像状态机：
        build_context -> route_model_gateway -> analyze_intent -> invoke_model_intent -> plan_tools。
        """

        return self._model_intent_node.invoke(
            selected_route=selected_route,
            request=request,
            context_blocks=context_blocks,
            model_gateway_context=model_gateway_context,
            intent_analysis=intent_analysis,
            skill_plan=skill_plan,
            event_recorder=event_recorder,
        )

    @staticmethod
    def _merge_tool_plans(
        model_tool_plans: tuple[ToolPlan, ...],
        rule_tool_plans: tuple[ToolPlan, ...],
    ) -> tuple[ToolPlan, ...]:
        """合并模型生成计划与规则式安全基线计划。

        合并策略采用“规则顺序保依赖、模型参数优先”的保守方式：
        - 规则式规划通常更了解平台依赖顺序，例如先读元数据再生成质量规则；
        - 如果模型也提出了同名工具，说明它基于当前工具 schema 主动选择了该动作，此时用模型计划
          替换规则计划的同名位置，保留模型 arguments 与治理 hints；
        - 如果模型提出的是规则没覆盖到的新工具，则追加到末尾，避免直接丢弃模型发现的合理动作；
        - 同名工具只保留一份，避免一个工具在同一轮计划中重复进入审批或执行控制面。

        这不是最终的执行调度器，只是计划级去重。后续真实执行时仍要由 Java agent-runtime 根据工具
        依赖、引用参数、审批状态和幂等键决定执行顺序。
        """

        model_by_name = {plan.tool_name: plan for plan in model_tool_plans}
        merged: list[ToolPlan] = []
        seen: set[str] = set()
        for rule_plan in rule_tool_plans:
            plan = model_by_name.get(rule_plan.tool_name, rule_plan)
            if plan.tool_name in seen:
                continue
            merged.append(plan)
            seen.add(plan.tool_name)
        for plan in model_tool_plans:
            if plan.tool_name in seen:
                continue
            merged.append(plan)
            seen.add(plan.tool_name)
        return tuple(merged)

    def _build_context(
        self,
        request: AgentRequest,
        event_recorder: RuntimeEventRecorder,
    ) -> tuple[tuple[ContextBlock, ...], dict[str, Any]]:
        """构建上下文、追加用户画像，并把请求级事件收集器传递给支持它的构建器。

        这里仍然兼容旧的 `build(request)` 形式。这个兼容层很重要：未来 GraphRAG、Java 控制面、
        向量检索上下文来源可能由不同团队逐步实现，不能因为事件能力升级就强制所有插件同时改造。
        """

        try:
            context_blocks = self._context_builder.build(request, event_recorder)
        except TypeError:
            context_blocks = self._context_builder.build(request)
        if self._user_profile_memory is None:
            return context_blocks, {}
        try:
            profile_context = self._user_profile_memory.observe_and_build_context(request)
        except Exception as exc:  # pragma: no cover - 防御画像 store/抽取器替换实现异常
            event_recorder.record(
                AgentRuntimeEventType.CONTEXT_COLLECTED,
                "load_user_profile",
                "用户画像上下文加载失败，已跳过画像注入并继续主规划链路。",
                severity=AgentRuntimeEventSeverity.WARNING,
                attributes={
                    "profileLoaded": False,
                    "errorType": exc.__class__.__name__,
                    "fallback": True,
                },
            )
            return context_blocks, {
                "profileLoaded": False,
                "fallback": True,
                "errorType": exc.__class__.__name__,
            }
        summary = profile_context.to_summary()
        event_recorder.record(
            AgentRuntimeEventType.CONTEXT_COLLECTED,
            "load_user_profile",
            "已加载用户画像低敏上下文。",
            attributes={
                "profileLoaded": True,
                "contextBlockCount": summary.get("contextBlockCount", 0),
                "activeFacetCount": summary.get("activeFacetCount", 0),
                "observedFacetCount": summary.get("observedFacetCount", 0),
                "candidateFacetCount": summary.get("candidateFacetCount", 0),
                "payloadPolicy": "LOW_SENSITIVE_PROFILE_FACTS_ONLY",
            },
        )
        return context_blocks + profile_context.context_blocks, summary

    @staticmethod
    def _resolve_session_id(request: AgentRequest) -> str | None:
        """从请求变量中解析会话 ID。

        现在 `AgentRequest` 还没有独立 `session_id` 字段，因此先从 variables 中兼容读取。后续智能
        网关模块落地后，应把 sessionId 提升为正式 API 字段，支持多轮会话、断线续传和审计回放。
        """

        value = request.variables.get("sessionId") or request.variables.get("session_id")
        return str(value) if value else None

    @staticmethod
    def _resolve_request_id(request: AgentRequest) -> str:
        """选择客户端关联 ID 或生成新的请求 ID。

        流式页面需要在模型调用前就知道 requestId，因此允许客户端预生成；运行时仅接受长度受限的
        字母数字、短横线和下划线，防止任意文本进入日志、事件索引或下游控制面。
        """

        candidate = str(request.request_id or "").strip()
        if candidate and len(candidate) <= 128 and all(char.isalnum() or char in "-_" for char in candidate):
            return candidate
        return str(uuid4())
