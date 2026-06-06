"""模型工具反馈二轮推理服务。

Agent 主流程里，模型第一次响应可能只提出 `tool_calls`。真实 Agent Host 通常还需要把工具执行结果
以 `tool` message 的形式回填给模型，再让模型生成面向用户的总结。本模块负责这个“工具反馈后二轮推理”
的协议验证层。

当前阶段仍然是模拟反馈，不直接调用 Java 工具执行器。拆出独立服务的原因有三点：
- `AgentModelIntentNode` 应保持状态机节点职责，不继续膨胀成大而全的实现类；
- 未来真实工具反馈、Java runtime event replay、artifact 引用解析和模型二轮总结可以在这里演进；
- 二轮反馈涉及资源引用过滤、结果脱敏和 workspace 边界校验，单独成类更容易测试和审计。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationRequest,
    ModelToolCall,
    ToolPlan,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_tool_feedback_provider import (
    ModelToolExecutionFeedbackProvider,
    SimulatedModelToolExecutionFeedbackProvider,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ModelToolResultFeedbackBuilder
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder


class AgentModelToolFeedbackTurnService:
    """完成工具结果反馈后的模型二轮推理。

    这个服务不负责判断 tool_call 是否允许进入 ToolPlan，也不负责预算守卫；这些已经在
    `ToolActionIntakeService` 和 `ModelToolCallBudgetGuard` 中完成。它只处理“已经有 ToolPlan 后，
    如何构造受控工具反馈并让模型继续总结”的后半段。
    """

    def __init__(
        self,
        *,
        model_providers: ModelProviderRegistry,
        model_gateway: ModelGatewayGovernanceService,
        tool_execution_feedback_provider: ModelToolExecutionFeedbackProvider | None = None,
        tool_result_feedback_builder: ModelToolResultFeedbackBuilder | None = None,
    ) -> None:
        self._model_providers = model_providers
        self._model_gateway = model_gateway
        self._tool_execution_feedback_provider = tool_execution_feedback_provider or SimulatedModelToolExecutionFeedbackProvider()
        self._tool_result_feedback_builder = tool_result_feedback_builder or ModelToolResultFeedbackBuilder()

    def complete(
        self,
        *,
        model_request: ModelInvocationRequest,
        model_gateway_context: ModelGatewayRequestContext,
        tool_calls: tuple[ModelToolCall, ...],
        model_tool_plans: tuple[ToolPlan, ...],
        event_recorder: RuntimeEventRecorder,
    ) -> tuple[str, int]:
        """用受控工具反馈完成第二轮模型调用。

        返回值：
        - 第一个元素是二轮模型摘要；没有执行二轮时为空字符串；
        - 第二个元素是本轮构建出的反馈条数，用于计划摘要和测试断言。

        这里仍然不会执行真实工具。`tool_execution_feedback_provider` 当前默认是模拟实现，未来可替换为
        Java runtime event replay provider，让模型看到真实工具结果的低敏摘要。
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
        self._model_gateway.record_invocation_result(model_gateway_context, result)
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
