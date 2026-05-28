"""Agent 编排器。

这个编排器模拟 OpenClaw/LangGraph 风格的“节点式状态流转”，但暂时不用外部框架。这样我们可以
先把业务状态、模型路由、工具规划、审批判断这些关键概念稳定下来，再把节点迁移到真正的
LangGraph Graph 或 OpenClaw Runtime 中。
"""

from __future__ import annotations

from uuid import uuid4

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ModelRoute,
    ToolParameterIssueAction,
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
from datasmart_ai_runtime.services.context_builder import ContextBuilder, DefaultContextBuilder
from datasmart_ai_runtime.services.intent_analyzer import IntentAnalyzer, RuleBasedIntentAnalyzer
from datasmart_ai_runtime.services.memory_planner import AgentMemoryPlanner
from datasmart_ai_runtime.services.memory_retriever import AgentMemoryRetriever, InMemoryAgentMemoryRetriever
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway_context import build_model_gateway_context
from datasmart_ai_runtime.services.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.model_tool_feedback_provider import ModelToolExecutionFeedbackProvider
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
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
        tool_execution_feedback_provider: ModelToolExecutionFeedbackProvider | None = None,
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
        self._model_intent_node = model_intent_node or AgentModelIntentNode(
            model_providers=self._model_providers,
            model_gateway=self._model_gateway,
            tool_planner=self._tool_planner,
            tool_execution_feedback_provider=tool_execution_feedback_provider,
        )

    def plan(self, request: AgentRequest) -> AgentPlan:
        """为用户治理目标生成 Agent 计划。

        返回值中的 `state_trace` 是后续可观测性的雏形。商业化 Agent 平台需要知道一次请求经过
        哪些节点、在哪个节点需要审批、哪个节点发生异常。当前先记录节点名称，后续可扩展为
        包含耗时、输入摘要、输出摘要、错误码、TraceId 的运行时事件。
        """

        state_trace: list[str] = []
        request_id = str(uuid4())
        run_id = str(uuid4())
        event_recorder = RuntimeEventRecorder(
            request=request,
            request_id=request_id,
            run_id=run_id,
            session_id=self._resolve_session_id(request),
        )

        state_trace.append("receive_goal")
        state_trace.append("select_model_route")
        state_trace.append("build_context")
        context_blocks = self._build_context(request, event_recorder)

        state_trace.append("route_model_gateway")
        model_gateway_context = build_model_gateway_context(request, context_blocks)
        model_gateway_decision = self._model_gateway.decide(model_gateway_context)
        selected_route = model_gateway_decision.selected_route
        event_recorder.record(
            AgentRuntimeEventType.MODEL_GATEWAY_ROUTED,
            "route_model_gateway",
            "已完成模型网关治理决策。",
            severity=AgentRuntimeEventSeverity.WARNING if selected_route is None else AgentRuntimeEventSeverity.INFO,
            attributes={
                "selectedProvider": selected_route.provider_name if selected_route else None,
                "selectedModel": selected_route.model_name if selected_route else None,
                "fallbackUsed": model_gateway_decision.fallback_used,
                "budgetAllowed": model_gateway_decision.budget_decision.allowed,
                "cacheKeyScope": model_gateway_decision.cache_key_scope.value,
                "candidateCount": len(model_gateway_decision.candidate_routes),
            },
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
        tool_plans = self._merge_tool_plans(model_intent_result.model_tool_plans, rule_tool_plans)
        event_recorder.record(
            AgentRuntimeEventType.TOOL_PLANNED,
            "plan_tools",
            "已完成工具计划生成。",
            attributes={
                "toolCount": len(tool_plans),
                "toolNames": tuple(plan.tool_name for plan in tool_plans),
                "modelGeneratedToolCount": len(model_intent_result.model_tool_plans),
                "ruleGeneratedToolCount": len(rule_tool_plans),
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
        has_parameter_issues = self._has_parameter_issues(tool_plans)
        requires_parameter_clarification = self._requires_parameter_clarification(tool_plans)

        if requires_parameter_clarification:
            state_trace.append("clarify_missing_parameters")
        elif has_parameter_issues:
            state_trace.append("prepare_draft_with_missing_parameters")

        if has_parameter_issues:
            event_recorder.record(
                AgentRuntimeEventType.TOOL_PARAMETER_VALIDATED,
                "validate_tool_parameters",
                "工具参数校验发现缺失项，当前计划不能直接进入真实执行。",
                severity=AgentRuntimeEventSeverity.WARNING,
                attributes={
                    "issueCount": sum(len(plan.parameter_validation.issues) for plan in tool_plans),
                    "mustClarify": requires_parameter_clarification,
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
        elif not has_parameter_issues:
            state_trace.append("ready_for_control_plane_execution")

        response_summary = self._build_response_summary(
            tool_plans=tool_plans,
            requires_human_approval=requires_human_approval,
        )

        next_actions = self._build_next_actions(tool_plans, requires_human_approval)
        return AgentPlan(
            request_id=request_id,
            selected_route=selected_route,
            state_trace=tuple(state_trace),
            tool_plans=tool_plans,
            requires_human_approval=requires_human_approval,
            response_summary=response_summary,
            next_actions=next_actions,
            model_intent_summary=f"{intent_analysis.summary} 模型节点摘要：{model_intent_result.summary}",
            context_blocks=context_blocks,
            intent_analysis=intent_analysis,
            model_gateway_decision=model_gateway_decision,
            skill_plan=skill_plan,
            memory_plan=memory_plan,
            memory_retrieval_report=memory_retrieval_report,
            runtime_events=event_recorder.events(),
        )

    def _select_skills(self, request: AgentRequest, intent_analysis: IntentAnalysis) -> AgentSkillPlan:
        """选择本次请求适用的 Skill。

        Skill 注册表在默认 API 启动路径中会注入；如果某些测试或极简运行环境没有配置 Skill 注册表，
        编排器仍会返回空计划而不是失败。这样可以保持向后兼容，也方便后续把 Skill 来源迁移到 Java
        控制面或插件市场。
        """

        if self._skill_registry is None:
            return AgentSkillPlan(rationale="当前运行环境未配置 Skill 注册表，跳过 Skill 选择。")
        return self._skill_registry.select(request.objective, intent_analysis)

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

    @staticmethod
    def _build_response_summary(tool_plans: tuple[ToolPlan, ...], requires_human_approval: bool) -> str:
        """生成给控制面或前端展示的摘要。

        摘要不替代结构化字段，只用于人读场景。真实产品中可把它展示在 Agent 会话窗口，
        帮助用户理解“系统为什么要先审批、为什么不是直接执行”。
        """

        tool_count = len(tool_plans)
        parameter_issue_count = sum(len(plan.parameter_validation.issues) for plan in tool_plans)
        if tool_count == 0:
            return "已完成目标解析，但当前没有命中可调用工具；建议补充工具注册或接入语义规划器。"
        if parameter_issue_count > 0 and requires_human_approval:
            return f"已生成 {tool_count} 个工具计划，其中 {parameter_issue_count} 个参数需要补齐，且包含需审批操作。"
        if parameter_issue_count > 0:
            return f"已生成 {tool_count} 个工具计划，但发现 {parameter_issue_count} 个参数需要补齐；当前不应直接执行。"
        if requires_human_approval:
            return f"已生成 {tool_count} 个工具计划，其中包含高风险或需审批操作，必须先进入人工确认。"
        return f"已生成 {tool_count} 个工具计划，当前均属于可自动进入控制面执行的低风险操作。"

    @staticmethod
    def _build_next_actions(tool_plans: tuple[ToolPlan, ...], requires_human_approval: bool) -> tuple[str, ...]:
        """根据风险结果给出下一步建议。

        这里的建议面向产品闭环，而不是纯技术执行。审批场景需要提示进入 Java Agent Runtime 的
        审计/审批 API；低风险场景则可以继续由控制面创建工具执行审计并触发执行。
        """

        parameter_actions = AgentOrchestrator._build_parameter_next_actions(tool_plans)
        if requires_human_approval:
            return parameter_actions + (
                "在 Java agent-runtime 中创建工具执行审计记录。",
                "由项目负责人、平台管理员或具备授权的审批人确认高风险工具计划。",
                "审批通过后再调用对应业务微服务执行。",
            )
        if parameter_actions:
            return parameter_actions + (
                "参数补齐后再将工具计划提交给 Java agent-runtime。",
            )
        return (
            "将工具计划提交给 Java agent-runtime 生成审计记录。",
            "按工具注册表的目标微服务触发执行并回写运行状态。",
        )

    @staticmethod
    def _has_parameter_issues(tool_plans: tuple[ToolPlan, ...]) -> bool:
        """判断工具计划中是否存在任何参数问题。

        这个方法单独存在，是为了让状态流转更清晰：参数问题不是审批问题，也不是模型问题，而是
        Agent 计划进入真实执行前必须处理的独立产品状态。
        """

        return any(plan.parameter_validation.issues for plan in tool_plans)

    @staticmethod
    def _requires_parameter_clarification(tool_plans: tuple[ToolPlan, ...]) -> bool:
        """判断是否存在必须向用户澄清的参数问题。

        `ALLOW_DRAFT` 可以进入草案确认页，`CAN_FILL_FROM_CONTEXT` 可以继续尝试检索上下文；
        但 `MUST_CLARIFY` 代表没有用户或管理员明确选择就不能安全推进。
        """

        return any(
            issue.action == ToolParameterIssueAction.MUST_CLARIFY
            for plan in tool_plans
            for issue in plan.parameter_validation.issues
        )

    @staticmethod
    def _build_parameter_next_actions(tool_plans: tuple[ToolPlan, ...]) -> tuple[str, ...]:
        """把参数校验问题转换为面向用户和控制面的下一步动作。

        为了避免一次返回过多噪声，当前最多展示前三个参数问题。完整问题列表仍保留在每个
        `ToolPlan.parameterValidation.issues` 中，前端可以展开查看。
        """

        actions: list[str] = []
        for plan in tool_plans:
            for issue in plan.parameter_validation.issues:
                if len(actions) >= 3:
                    return tuple(actions)
                actions.append(f"补齐工具 `{plan.tool_name}` 的参数 `{issue.parameter_name}`：{issue.message}")
        return tuple(actions)

    def _build_context(
        self,
        request: AgentRequest,
        event_recorder: RuntimeEventRecorder,
    ) -> tuple[ContextBlock, ...]:
        """构建上下文并把请求级事件收集器传递给支持它的构建器。

        这里仍然兼容旧的 `build(request)` 形式。这个兼容层很重要：未来 GraphRAG、Java 控制面、
        向量检索上下文来源可能由不同团队逐步实现，不能因为事件能力升级就强制所有插件同时改造。
        """

        try:
            return self._context_builder.build(request, event_recorder)
        except TypeError:
            return self._context_builder.build(request)

    @staticmethod
    def _resolve_session_id(request: AgentRequest) -> str | None:
        """从请求变量中解析会话 ID。

        现在 `AgentRequest` 还没有独立 `session_id` 字段，因此先从 variables 中兼容读取。后续智能
        网关模块落地后，应把 sessionId 提升为正式 API 字段，支持多轮会话、断线续传和审计回放。
        """

        value = request.variables.get("sessionId") or request.variables.get("session_id")
        return str(value) if value else None
