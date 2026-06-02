"""Agent 模型意图节点。

`AgentOrchestrator` 负责串联状态流，但不应该把“构造模型消息、暴露候选工具、调用 Provider、解析
tool_calls、写 runtime events、回写模型网关 usage”全部塞进一个类。这个文件把模型意图节点拆成
独立服务，使编排器保持清晰，也让后续接 streaming tool call、模型重试、Provider fallback 和工具
结果回填时有独立扩展位置。

节点边界：
- 输入：已完成上下文构建、意图分析、Skill 选择和模型网关路由的请求状态；
- 输出：模型文本摘要，以及模型通过 tool_calls 提出的、已经经过 DataSmart 治理的 `ToolPlan`；
- 不做：真实工具执行、审批单创建、Java 微服务调用。这些仍属于 Java agent-runtime 控制面职责。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from datasmart_ai_runtime.domain.contracts import (
    AgentRequest,
    ModelInvocationChunk,
    ModelInvocationRequest,
    ModelInvocationResult,
    ModelMessage,
    ModelRoute,
    ModelToolCall,
    ToolDefinition,
    ToolPlan,
)
from datasmart_ai_runtime.domain.context import ContextBlock
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.domain.intent import IntentAnalysis
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.domain.skills import AgentSkillPlan
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_provider_metadata import build_model_provider_metadata
from datasmart_ai_runtime.services.model_tool_feedback_provider import (
    ModelToolExecutionFeedbackProvider,
    SimulatedModelToolExecutionFeedbackProvider,
)
from datasmart_ai_runtime.services.model_tool_call_aggregator import (
    ModelToolCallAssemblyReport,
    ModelToolCallDeltaAggregator,
)
from datasmart_ai_runtime.services.model_tool_call_events import record_model_tool_call_planning_events
from datasmart_ai_runtime.services.model_tool_call_budget_guard import ModelToolCallBudgetGuard
from datasmart_ai_runtime.services.model_tool_call_budget_policy_provider import (
    EnvAndRequestModelToolCallBudgetPolicyProvider,
    ModelToolCallBudgetPolicyProvider,
)
from datasmart_ai_runtime.services.model_tool_call_planner import ModelToolCallPlanner
from datasmart_ai_runtime.services.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_tool_result_feedback import ModelToolResultFeedbackBuilder
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


@dataclass(frozen=True)
class AgentModelIntentNodeResult:
    """模型意图节点输出。

    字段说明：
    - `summary`：模型返回的自然语言摘要，主要用于计划解释和前端展示；
    - `model_tool_plans`：模型通过 `tool_calls` 提出的工具计划。它们已经经过工具存在性、本轮可见性、
      JSON 参数形态、风险和审批语义治理，但仍不是“已经执行”的工具结果；
    - `visible_tool_names`：本轮传给模型 Provider 的候选工具名；
    - `tool_call_count`：模型实际返回的工具调用数量，用于后续统计工具使用率和幻觉率。
    - `streaming_source_chunk_count`：如果本次走 streaming 路径，记录参与聚合的 chunk 数；
    - `streaming_source_delta_count`：如果本次走 streaming 路径，记录参与聚合的 tool call delta 数；
    - `streaming_assembly_issue_count`：流式聚合阶段发现的结构问题数量，例如缺少 name/id/arguments。
    - `tool_feedback_count`：本轮为模型工具调用构建了多少条工具结果反馈；
    - `second_turn_summary`：工具结果回填后，模型第二轮生成的最终摘要。
    """

    summary: str
    model_tool_plans: tuple[ToolPlan, ...] = ()
    visible_tool_names: tuple[str, ...] = ()
    tool_call_count: int = 0
    streaming_source_chunk_count: int = 0
    streaming_source_delta_count: int = 0
    streaming_assembly_issue_count: int = 0
    tool_feedback_count: int = 0
    second_turn_summary: str = ""


class AgentModelIntentNode:
    """执行 Agent 主流程中的模型意图节点。

    该节点刻意采用“模型建议、平台治理”的实现方式。OpenAI-compatible function calling 和 MCP tools
    的共同趋势都是让模型输出结构化工具调用意图，但真正执行外部动作必须由应用侧运行时负责。
    对 DataSmart 来说，应用侧运行时就是 Java 控制面 + Python 编排层共同组成的治理边界。
    """

    def __init__(
        self,
        model_providers: ModelProviderRegistry,
        model_gateway: ModelGatewayGovernanceService,
        tool_planner: ToolPlanner,
        model_tool_call_planner: ModelToolCallPlanner | None = None,
        model_tool_call_budget_guard: ModelToolCallBudgetGuard | None = None,
        model_tool_call_budget_policy_provider: ModelToolCallBudgetPolicyProvider | None = None,
        tool_call_delta_aggregator_factory: type[ModelToolCallDeltaAggregator] = ModelToolCallDeltaAggregator,
        tool_execution_feedback_provider: ModelToolExecutionFeedbackProvider | None = None,
        tool_result_feedback_builder: ModelToolResultFeedbackBuilder | None = None,
    ) -> None:
        self._model_providers = model_providers
        self._model_gateway = model_gateway
        self._tool_planner = tool_planner
        self._model_tool_call_planner = model_tool_call_planner or ModelToolCallPlanner()
        self._model_tool_call_budget_guard = model_tool_call_budget_guard or ModelToolCallBudgetGuard()
        self._model_tool_call_budget_policy_provider = (
            model_tool_call_budget_policy_provider or EnvAndRequestModelToolCallBudgetPolicyProvider()
        )
        self._tool_call_delta_aggregator_factory = tool_call_delta_aggregator_factory
        self._tool_execution_feedback_provider = tool_execution_feedback_provider or SimulatedModelToolExecutionFeedbackProvider()
        self._tool_result_feedback_builder = tool_result_feedback_builder or ModelToolResultFeedbackBuilder()

    def invoke(
        self,
        selected_route: ModelRoute | None,
        request: AgentRequest,
        context_blocks: tuple[ContextBlock, ...],
        model_gateway_context: ModelGatewayRequestContext,
        intent_analysis: IntentAnalysis,
        skill_plan: AgentSkillPlan,
        event_recorder: RuntimeEventRecorder,
    ) -> AgentModelIntentNodeResult:
        """调用模型意图节点并治理模型返回的工具调用。

        这个方法覆盖三种结果路径：
        1. 没有可用模型路由：返回可解释降级摘要，后续规则式工具规划仍可继续；
        2. 模型只返回文本：记录 usage 后返回摘要，不产生模型工具计划；
        3. 模型返回 tool_calls：先用本轮可见工具和平台注册表做治理，再把可接受候选作为 ToolPlan 输出。

        发生 Provider 异常时不直接让整个规划失败，是为了保留规则式安全基线。商业部署可在未来增加
        租户策略，例如“高风险租户禁止模型失败后自动降级执行”。
        """

        if selected_route is None:
            return AgentModelIntentNodeResult(
                summary="模型网关未选择可用路由，当前使用规则式意图分析结果继续生成安全基线计划。"
            )

        available_tools = self._tool_planner.model_visible_tools(
            request=request,
            intent_analysis=intent_analysis,
            context_blocks=context_blocks,
            skill_plan=skill_plan,
        )
        model_request = ModelInvocationRequest(
            route=selected_route,
            messages=self._build_messages(request, context_blocks),
            trace_id=request.variables.get("traceId") or request.variables.get("trace_id"),
            available_tools=available_tools,
            provider_metadata=build_model_provider_metadata(model_gateway_context),
        )
        try:
            if self._should_use_streaming(request):
                return self._invoke_streaming(
                    model_request=model_request,
                    request=request,
                    model_gateway_context=model_gateway_context,
                    available_tools=available_tools,
                    event_recorder=event_recorder,
                )
            return self._invoke_non_streaming(
                model_request=model_request,
                request=request,
                model_gateway_context=model_gateway_context,
                available_tools=available_tools,
                event_recorder=event_recorder,
            )
        except Exception as exc:  # pragma: no cover - 真实 Provider 异常在集成测试中覆盖
            return AgentModelIntentNodeResult(summary=f"模型意图识别节点降级：{exc}")

    def _invoke_non_streaming(
        self,
        model_request: ModelInvocationRequest,
        request: AgentRequest,
        model_gateway_context: ModelGatewayRequestContext,
        available_tools: tuple[ToolDefinition, ...],
        event_recorder: RuntimeEventRecorder,
    ) -> AgentModelIntentNodeResult:
        """执行非流式模型调用路径。

        非流式路径消费 `ModelInvocationResult.tool_calls`。它仍是必要能力，因为不是所有 Provider、
        私有化模型网关或单元测试桩都支持 SSE；真实产品必须允许“可流式则流式，不可流式则安全降级”。
        """

        result = self._model_providers.invoke(model_request)
        self._record_model_usage(model_gateway_context, result)
        model_tool_plans = self._govern_model_tool_calls(
            tool_calls=result.tool_calls,
            request=request,
            visible_tools=available_tools,
            event_recorder=event_recorder,
        )
        second_turn_summary, feedback_count = self._complete_simulated_tool_feedback_turn(
            model_request=model_request,
            model_gateway_context=model_gateway_context,
            tool_calls=result.tool_calls,
            model_tool_plans=model_tool_plans,
            event_recorder=event_recorder,
        )
        return AgentModelIntentNodeResult(
            summary=self._combine_summaries(result.content, second_turn_summary),
            model_tool_plans=model_tool_plans,
            visible_tool_names=tuple(tool.name for tool in available_tools),
            tool_call_count=len(result.tool_calls),
            tool_feedback_count=feedback_count,
            second_turn_summary=second_turn_summary,
        )

    def _invoke_streaming(
        self,
        model_request: ModelInvocationRequest,
        request: AgentRequest,
        model_gateway_context: ModelGatewayRequestContext,
        available_tools: tuple[ToolDefinition, ...],
        event_recorder: RuntimeEventRecorder,
    ) -> AgentModelIntentNodeResult:
        """执行流式模型调用路径，并聚合 tool call delta。

        OpenAI-compatible streaming 下，文本和工具调用片段可能混在同一批 chunk 中返回。这里拼接文本
        delta，并把 `tool_call_deltas` 聚合为完整 `ModelToolCall` 后继续进入同一套治理。
        """

        chunks = tuple(self._stream_chunks(model_request))
        if not chunks:
            return AgentModelIntentNodeResult(
                summary="模型流式节点未返回任何 chunk，当前使用规则式安全基线继续规划。",
                visible_tool_names=tuple(tool.name for tool in available_tools),
            )

        summary = "".join(chunk.content_delta for chunk in chunks).strip()
        assembly_report = self._aggregate_streaming_tool_calls(chunks)
        model_tool_plans = self._govern_model_tool_calls(
            tool_calls=assembly_report.tool_calls,
            request=request,
            visible_tools=available_tools,
            event_recorder=event_recorder,
        )
        second_turn_summary, feedback_count = self._complete_simulated_tool_feedback_turn(
            model_request=model_request,
            model_gateway_context=model_gateway_context,
            tool_calls=assembly_report.tool_calls,
            model_tool_plans=model_tool_plans,
            event_recorder=event_recorder,
        )
        # 当前 ModelInvocationChunk 还没有 usage 字段，因此这里只调用一次空 usage 记录，保持预算台账
        # 的调用后生命周期位置稳定；后续可从 Provider chunk trailer 或最终 usage event 中补齐 token。
        self._model_gateway.record_invocation_usage(model_gateway_context, prompt_tokens=None, completion_tokens=None)
        return AgentModelIntentNodeResult(
            summary=self._combine_summaries(
                summary or "模型流式节点已返回工具调用候选，等待平台治理与后续执行。",
                second_turn_summary,
            ),
            model_tool_plans=model_tool_plans,
            visible_tool_names=tuple(tool.name for tool in available_tools),
            tool_call_count=len(assembly_report.tool_calls),
            streaming_source_chunk_count=assembly_report.source_chunk_count,
            streaming_source_delta_count=assembly_report.source_delta_count,
            streaming_assembly_issue_count=len(assembly_report.issues),
            tool_feedback_count=feedback_count,
            second_turn_summary=second_turn_summary,
        )

    def _govern_model_tool_calls(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        request: AgentRequest,
        visible_tools: tuple[ToolDefinition, ...],
        event_recorder: RuntimeEventRecorder,
    ) -> tuple[ToolPlan, ...]:
        """把模型返回的 tool_calls 转为受治理的 ToolPlan。

        Provider 解析出的 `tool_calls` 仍然只是模型输出，不能直接执行。这里同时传入完整注册表和
        本轮 visible tools，用于区分“工具不存在”和“工具存在但本轮不可见”两类治理问题。
        """

        if not tool_calls:
            return ()
        report = self._model_tool_call_planner.plan(
            tool_calls=tool_calls,
            registered_tools=self._tool_planner.registered_tools(),
            visible_tools=visible_tools,
        )
        budget_policy = self._model_tool_call_budget_policy_provider.policy_for(request)
        guarded = self._model_tool_call_budget_guard.evaluate(report, policy=budget_policy)
        if guarded.budget_issue_codes:
            event_recorder.record(
                AgentRuntimeEventType.MODEL_TOOL_CALL_BUDGET_GUARDED,
                "guard_model_tool_call_budget",
                "智能网关已根据工具调用预算阻断部分模型工具调用候选。",
                attributes=guarded.to_summary(),
            )
        record_model_tool_call_planning_events(event_recorder, guarded.guarded_report)
        return guarded.guarded_report.accepted_tool_plans

    def _complete_simulated_tool_feedback_turn(
        self,
        model_request: ModelInvocationRequest,
        model_gateway_context: ModelGatewayRequestContext,
        tool_calls: tuple[ModelToolCall, ...],
        model_tool_plans: tuple[ToolPlan, ...],
        event_recorder: RuntimeEventRecorder,
    ) -> tuple[str, int]:
        """用模拟工具反馈完成第二轮模型调用。

        这是通往真实多步 Agent loop 的“协议验证层”。当前不调用 Java 执行工具，而是生成受控反馈并
        构造 OpenAI-compatible 的 assistant/tool messages。后续替换为 Java provider 时主流程仍成立。
        """

        if not tool_calls or not model_tool_plans:
            return "", 0
        feedback_items = self._tool_execution_feedback_provider.feedback_for(tool_calls, model_tool_plans)
        feedback_bundle = self._tool_result_feedback_builder.build(
            tool_calls,
            feedback_items,
            current_workspace_key=self._workspace_key_from_tool_plans(model_tool_plans),
        )
        event_recorder.record(
            AgentRuntimeEventType.TOOL_RESULT_FEEDBACK_BUILT,
            "build_tool_result_feedback",
            "已构建工具执行结果回填消息，准备进入模型第二轮推理。",
            attributes={
                "feedbackCount": len(feedback_items),
                "messageCount": len(feedback_bundle.messages),
                "missingFeedbackCallIds": feedback_bundle.missing_feedback_call_ids,
                "extraFeedbackCallIds": feedback_bundle.extra_feedback_call_ids,
                "complete": feedback_bundle.complete,
                "resourceResolutionCount": len(feedback_bundle.resource_resolution_summaries),
                "resourceResolutionBlockedCount": self._resource_blocked_count(
                    feedback_bundle.resource_resolution_summaries
                ),
                "resourceResolutionModelBlockedCount": self._resource_model_blocked_count(
                    feedback_bundle.resource_resolution_summaries
                ),
                "resourceResolutions": feedback_bundle.resource_resolution_summaries,
                "resultFilterCount": len(feedback_bundle.result_filter_summaries),
                "resultFilterMaskedCount": self._result_filter_path_count(
                    feedback_bundle.result_filter_summaries, "maskedPaths"
                ),
                "resultFilterRemovedCount": self._result_filter_path_count(
                    feedback_bundle.result_filter_summaries, "removedPaths"
                ),
                "resultFilterTruncatedCount": self._result_filter_path_count(
                    feedback_bundle.result_filter_summaries, "truncatedPaths"
                ),
                "resultFilters": feedback_bundle.result_filter_summaries,
            },
        )
        if not feedback_bundle.complete or not feedback_bundle.messages:
            return "", len(feedback_items)

        second_turn_request = ModelInvocationRequest(
            route=model_request.route,
            messages=model_request.messages + feedback_bundle.messages,
            temperature=model_request.temperature,
            max_output_tokens=model_request.max_output_tokens,
            trace_id=model_request.trace_id,
            # 第二轮已经是“基于工具结果总结”，不再继续暴露 tools，避免模拟阶段形成无限工具调用循环。
            available_tools=(),
            tool_choice="none",
            strict_tool_schema=model_request.strict_tool_schema,
            provider_metadata=model_request.provider_metadata,
        )
        result = self._model_providers.invoke(second_turn_request)
        self._record_model_usage(model_gateway_context, result)
        event_recorder.record(
            AgentRuntimeEventType.MODEL_SECOND_TURN_COMPLETED,
            "invoke_model_second_turn",
            "模型已基于工具结果回填完成第二轮推理。",
            attributes={
                "feedbackCount": len(feedback_items),
                "promptTokens": result.prompt_tokens,
                "completionTokens": result.completion_tokens,
                "errorCode": result.error_code,
            },
        )
        return result.content, len(feedback_items)

    @staticmethod
    def _resource_blocked_count(summaries: tuple[dict[str, object], ...]) -> int:
        """统计二轮消息构建时被资源治理完全阻断的引用数量。"""

        return sum(1 for item in summaries if item.get("decision") == "blocked")

    @staticmethod
    def _resource_model_blocked_count(summaries: tuple[dict[str, object], ...]) -> int:
        """统计不允许进入模型上下文的资源数量。

        这类资源不一定是错误，例如 `audit_only` 可供审计台使用，只是不应进入模型。
        """

        return sum(1 for item in summaries if not item.get("modelContextAllowed"))

    @staticmethod
    def _result_filter_path_count(summaries: tuple[dict[str, object], ...], key: str) -> int:
        """统计字段级过滤报告中某类路径的数量。"""

        return sum(len(item.get(key) or ()) for item in summaries)

    @staticmethod
    def _workspace_key_from_tool_plans(tool_plans: tuple[ToolPlan, ...]) -> str | None:
        """从 ToolPlan 治理提示中提取当前运行 workspaceKey。

        二轮推理构造工具结果消息时复用该字段，让反馈构建器校验输出资源是否越过 workspace 边界。
        历史计划没有 workspaceKey 时按兼容模式运行，避免阻断渐进式迁移。
        """

        for plan in tool_plans:
            value = plan.governance_hints.get("workspaceKey")
            if value is not None and str(value).strip():
                return str(value).strip()
        return None

    def _aggregate_streaming_tool_calls(
        self,
        chunks: tuple[ModelInvocationChunk, ...],
    ) -> ModelToolCallAssemblyReport:
        """聚合 streaming tool call delta。

        聚合器作为可注入 factory，主要是为了后续测试和扩展：如果不同 Provider 需要兼容特殊 delta
        形态，可以替换聚合器而不改变 AgentModelIntentNode 的整体生命周期。
        """

        return self._tool_call_delta_aggregator_factory.from_chunks(chunks)

    @staticmethod
    def _combine_summaries(first_turn_summary: str, second_turn_summary: str) -> str:
        """合并第一轮和第二轮模型摘要。

        保留两段信息能让前端和审计回放看到完整链路，而不是只看到最终答案。
        """

        if not second_turn_summary:
            return first_turn_summary
        if not first_turn_summary:
            return f"工具结果回填后二轮摘要：{second_turn_summary}"
        return f"{first_turn_summary}\n工具结果回填后二轮摘要：{second_turn_summary}"

    def _stream_chunks(self, model_request: ModelInvocationRequest) -> Iterable[ModelInvocationChunk]:
        """从 Provider 读取流式 chunk。

        这里通过 `getattr` 判断而不是强制依赖具体类型，是为了兼容测试桩和未来多 Provider 实现。
        """

        stream_method = getattr(self._model_providers, "stream")
        return stream_method(model_request)

    def _should_use_streaming(self, request: AgentRequest) -> bool:
        """判断本次模型意图节点是否优先走 streaming。

        默认启用 streaming；旧 Provider 没有 `stream(...)` 时自动回到非流式路径。请求变量允许显式关闭。
        """

        explicit = request.variables.get("streamModelIntent") or request.variables.get("stream_model_intent")
        if explicit is not None:
            return self._truthy(explicit)
        return callable(getattr(self._model_providers, "stream", None))

    def _record_model_usage(
        self,
        model_gateway_context: ModelGatewayRequestContext,
        result: ModelInvocationResult,
    ) -> None:
        """把模型调用 usage 回写给模型网关治理服务。

        usage 统计是预算、限额、成本报表和异常诊断的基础，后续 streaming trailer usage 可复用。
        """

        self._model_gateway.record_invocation_usage(
            model_gateway_context,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )

    @staticmethod
    def _truthy(value: object) -> bool:
        """把请求变量中的开关值解析为布尔值。

        API 调用方可能传入 `false`、`0`、`no` 这类字符串。如果直接使用 `bool("false")`，Python 会因为
        非空字符串而得到 True，导致用户明明想关闭 streaming 却仍然走流式路径。这里做显式归一化，
        让运维排障和灰度开关行为更符合直觉。
        """

        if isinstance(value, bool):
            return value
        text = str(value).strip().lower()
        if text in {"false", "0", "no", "off", "disabled"}:
            return False
        if text in {"true", "1", "yes", "on", "enabled"}:
            return True
        return bool(text)

    @staticmethod
    def _build_messages(request: AgentRequest, context_blocks: tuple[ContextBlock, ...]) -> tuple[ModelMessage, ...]:
        """构造模型意图节点消息。

        系统消息明确要求模型只做意图总结和工具调用建议，不直接宣称已经执行工具。这样做能减少用户误解：
        模型返回 `tool_calls` 只是“建议调用”，真正执行要经过 DataSmart 平台治理和 Java 控制面。
        """

        context_digest = "\n".join(f"- {block.title}: {block.content}" for block in context_blocks[:5])
        return (
            ModelMessage(
                role="system",
                content=(
                    "你是 DataSmart Govern 的治理 Agent 意图识别节点。"
                    "请总结用户目标、涉及治理域、上下文依据和风险提示；"
                    "如果需要工具，请只提出结构化工具调用意图，不要声称已经执行。"
                ),
            ),
            ModelMessage(role="user", content=f"用户目标：{request.objective}\n\n可用上下文：\n{context_digest}"),
        )
