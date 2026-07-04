"""MCP 安全 modelFeedback 到真实二轮模型调用的受控桥接服务。

本模块解决的是 MCP 执行闭环中的最后一段“模型继续推理”问题：

1. Java `agent-runtime` 已经通过 outbox、permission、approval、lease 和 worker receipt 控制真实工具执行；
2. Python MCP durable worker 已经把工具结果转成 `ToolExecutionFeedback`，并由 `McpToolFeedbackAdapter`
   判断哪些小结果允许进入模型上下文；
3. 这里再把该安全 feedback 转成 OpenAI-compatible 的 assistant/tool messages，并交给项目统一的
   `ModelQueryEngine` 发起第二轮模型调用。

安全边界：
- 本服务不接收、不恢复、不记录 MCP arguments；
- 不读取 MCP Server endpoint、Authorization、stdio 命令或远端错误正文；
- 不把完整二轮 messages 暴露给 API，只返回 provider、model、token、错误码和模型摘要；
- 二轮请求显式设置 `tool_choice="none"` 且不暴露 tools，避免当前 MCP worker 内部 API 形成无限工具递归；
- 如果真实 Provider 未配置，默认 dry-run Provider 仍可验证编排闭环；配置 OpenAI-compatible endpoint 后才会真实调用模型。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationRequest,
    ModelMessage,
    ModelToolCall,
    WorkloadType,
)
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_gateway_context import build_model_gateway_context
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_provider_metadata import build_model_provider_metadata
from datasmart_ai_runtime.services.model_gateway.model_query_engine import (
    ModelQueryEngine,
    estimate_prompt_tokens,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ModelToolResultFeedbackBuilder,
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)


MCP_MODEL_FEEDBACK_SECOND_TURN_SCHEMA_VERSION = "datasmart.mcp-model-feedback-second-turn.v1"


@dataclass(frozen=True)
class McpModelFeedbackSecondTurnSettings:
    """MCP 二轮模型调用运行设置。

    字段说明：
    - `enabled`：是否启用二轮模型调用。关闭后仍返回安全 `modelFeedback`，但不会调用 Provider；
    - `fail_open`：模型侧异常是否低敏跳过。内部 worker 的首要职责是写回工具 receipt，二轮模型失败不应
      反向破坏 Java outbox/receipt 已完成的事实，因此默认 fail-open；
    - `temperature/max_output_tokens`：传给模型 Provider 的生成参数。这里使用偏低 temperature，目标是稳定
      总结工具结果，而不是鼓励模型发散；
    - `allow_failed_tool_feedback`：失败或拒绝的工具反馈是否允许进入二轮解释。默认允许，因为模型可以基于
      稳定错误码向用户解释下一步；等待审批仍然始终阻断；
    - `summary_max_chars`：API 返回模型摘要的最大字符数，防止二轮输出异常膨胀。
    """

    enabled: bool = True
    fail_open: bool = True
    temperature: float = 0.2
    max_output_tokens: int = 1024
    allow_failed_tool_feedback: bool = True
    summary_max_chars: int = 4000


@dataclass(frozen=True)
class McpModelFeedbackSecondTurnResult:
    """MCP modelFeedback 二轮模型调用结果。

    该结果可以进入内部 API 响应、测试断言和低敏诊断，但不包含 prompt、messages、工具参数或工具结果正文。
    `summary` 是模型输出摘要；它只基于经过 `ModelToolResultFeedbackBuilder` 过滤后的消息生成。
    """

    executed: bool
    skipped: bool
    reason: str
    summary: str = ""
    provider_name: str | None = None
    model_name: str | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    error_code: str | None = None
    feedback_count: int = 0
    message_count: int = 0
    query_engine_summary: dict[str, Any] = field(default_factory=dict)
    recommended_actions: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """输出 HTTP/API 友好的低敏摘要。

        注意这里不返回二轮请求 messages，也不返回 `ToolExecutionFeedback.result`。如果需要复盘完整执行，
        应通过 Java 审计、worker receipt、LangGraph checkpoint event 或受控 artifact resolver 查看。
        """

        return {
            "schemaVersion": MCP_MODEL_FEEDBACK_SECOND_TURN_SCHEMA_VERSION,
            "executed": self.executed,
            "skipped": self.skipped,
            "reason": self.reason,
            "summary": self.summary,
            "providerName": self.provider_name,
            "modelName": self.model_name,
            "promptTokens": self.prompt_tokens,
            "completionTokens": self.completion_tokens,
            "errorCode": self.error_code,
            "feedbackCount": self.feedback_count,
            "messageCount": self.message_count,
            "queryEngine": dict(self.query_engine_summary),
            "recommendedActions": self.recommended_actions,
            "payloadPolicy": "LOW_SENSITIVE_MODEL_SECOND_TURN_SUMMARY_ONLY",
        }


class McpModelFeedbackSecondTurnService:
    """把安全 MCP tool feedback 推进到受治理二轮模型调用。

    这个服务是 MCP worker 和通用 Agent 二轮编排器之间的“窄桥”：
    - 输入只允许是已经构造好的 `ToolExecutionFeedback`，不再接触 MCP arguments；
    - 模型路由、预算、限流、fallback 和 Provider 错误低敏化继续委托给 `ModelQueryEngine`；
    - 当前只做单工具结果二轮总结，多工具、多分支、多 Agent 状态恢复后续交给 LangGraph checkpointer。
    """

    def __init__(
        self,
        *,
        model_routes: ModelRouteRegistry,
        model_gateway: ModelGatewayGovernanceService,
        model_providers: ModelProviderRegistry,
        feedback_builder: ModelToolResultFeedbackBuilder | None = None,
        query_engine: ModelQueryEngine | None = None,
        settings: McpModelFeedbackSecondTurnSettings | None = None,
    ) -> None:
        self._model_routes = model_routes
        self._model_gateway = model_gateway
        self._model_providers = model_providers
        self._feedback_builder = feedback_builder or ModelToolResultFeedbackBuilder()
        self._query_engine = query_engine or ModelQueryEngine(
            model_gateway=self._model_gateway,
            model_providers=self._model_providers,
        )
        self._settings = settings or McpModelFeedbackSecondTurnSettings()

    def run(
        self,
        *,
        feedback: ToolExecutionFeedback,
        feedback_summary: Mapping[str, Any],
        control_facts: Mapping[str, Any],
        trace_id: str | None = None,
        workspace_key: str | None = None,
        current_workspace_key: str | None = None,
    ) -> McpModelFeedbackSecondTurnResult:
        """基于单条安全 MCP feedback 尝试执行二轮模型调用。

        参数说明：
        - `feedback`：由 `McpToolFeedbackAdapter` 输出的安全工具反馈对象；
        - `feedback_summary`：同一适配器生成的低敏准入摘要，用于判断 inlineResult 是否允许；
        - `control_facts`：Java 控制面低敏事实，只读取 tenant/project/actor/run/session 等路由字段；
        - `trace_id/workspace_key/current_workspace_key`：链路追踪与 workspace 隔离字段。
        """

        skip_reason = self._skip_reason(feedback, feedback_summary)
        if skip_reason is not None:
            return self._skipped(skip_reason)

        try:
            return self._run_model_query(
                feedback=feedback,
                feedback_summary=feedback_summary,
                control_facts=control_facts,
                trace_id=trace_id,
                workspace_key=workspace_key,
                current_workspace_key=current_workspace_key,
            )
        except Exception as exc:
            if not self._settings.fail_open:
                raise
            return McpModelFeedbackSecondTurnResult(
                executed=False,
                skipped=True,
                reason="model_second_turn_exception",
                error_code="MCP_MODEL_SECOND_TURN_EXCEPTION",
                recommended_actions=(
                    "二轮模型调用异常已按 fail-open 跳过，不影响 MCP worker receipt 写回。",
                    f"请检查模型路由、Provider 配置或 Query Engine 依赖：{exc.__class__.__name__}。",
                ),
            )

    def _run_model_query(
        self,
        *,
        feedback: ToolExecutionFeedback,
        feedback_summary: Mapping[str, Any],
        control_facts: Mapping[str, Any],
        trace_id: str | None,
        workspace_key: str | None,
        current_workspace_key: str | None,
    ) -> McpModelFeedbackSecondTurnResult:
        """构造二轮消息并通过 Query Engine 调用模型。"""

        tool_call = ModelToolCall(
            call_id=feedback.tool_call_id,
            name=feedback.tool_name,
            arguments="{}",
            raw_call={
                "source": "mcp_model_feedback_second_turn",
                "argumentsPolicy": "MCP_ARGUMENTS_NEVER_RECONSTRUCTED",
            },
        )
        bundle = self._feedback_builder.build(
            (tool_call,),
            (feedback,),
            current_workspace_key=current_workspace_key or workspace_key,
        )
        if not bundle.complete or not bundle.messages:
            return McpModelFeedbackSecondTurnResult(
                executed=False,
                skipped=True,
                reason="feedback_message_bundle_incomplete",
                feedback_count=1,
                message_count=len(bundle.messages),
                recommended_actions=(
                    "检查 MCP feedback 的 toolCallId 是否与上一轮模型 tool_call_id 一致。",
                    "不要绕过 McpToolFeedbackAdapter 直接拼接二轮模型消息。",
                ),
            )

        messages = self._context_messages(
            feedback=feedback,
            feedback_summary=feedback_summary,
            control_facts=control_facts,
        ) + bundle.messages
        context = self._gateway_context(
            control_facts=control_facts,
            trace_id=trace_id,
            messages=messages,
        )
        route = self._model_routes.route_for(WorkloadType.AGENT_REASONING)
        model_request = ModelInvocationRequest(
            route=route,
            messages=messages,
            temperature=self._settings.temperature,
            max_output_tokens=self._settings.max_output_tokens,
            trace_id=context.trace_id,
            available_tools=(),
            tool_choice="none",
            provider_metadata=build_model_provider_metadata(context),
        )
        query_result = self._query_engine.invoke(model_request, context=context)
        result = query_result.result
        return McpModelFeedbackSecondTurnResult(
            executed=result.error_code is None,
            skipped=False,
            reason="model_second_turn_completed" if result.error_code is None else "model_second_turn_provider_error",
            summary=self._safe_summary(result.content),
            provider_name=result.provider_name,
            model_name=result.model_name,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
            error_code=result.error_code,
            feedback_count=1,
            message_count=len(bundle.messages),
            query_engine_summary=query_result.to_summary(),
            recommended_actions=(
                "如果本次仍是 dry-run，请配置 DATASMART_AI_OPENAI_COMPATIBLE_BASE_URL 与模型 API Key 后验证真实二轮调用。",
                "下一阶段将把该二轮调用节点迁入 LangGraph PostgreSQL Durable Checkpointer。",
            ),
        )

    def _skip_reason(
        self,
        feedback: ToolExecutionFeedback,
        feedback_summary: Mapping[str, Any],
    ) -> str | None:
        """判断当前 feedback 是否允许触发二轮模型调用。"""

        if not self._settings.enabled:
            return "model_second_turn_disabled"
        if feedback.status == ToolExecutionFeedbackStatus.WAITING_APPROVAL:
            return "tool_waiting_approval"
        if feedback.status in {ToolExecutionFeedbackStatus.FAILED, ToolExecutionFeedbackStatus.REJECTED}:
            if not self._settings.allow_failed_tool_feedback:
                return "failed_tool_feedback_blocked_by_policy"
        if not feedback.tool_call_id or feedback.tool_call_id == "unknown":
            return "tool_call_id_missing"
        # `inlineResultAllowed=false` 不必然阻断二轮：此时 builder 会只把摘要、错误码或 artifact 引用交给模型。
        # 但如果适配器连 runtime_result 都没有看到，说明工具执行阶段没有产生可解释事实，二轮推理意义不大。
        if (
            feedback.status == ToolExecutionFeedbackStatus.SUCCEEDED
            and feedback_summary.get("runtimeResultPresent") is False
        ):
            return "runtime_result_missing"
        return None

    @staticmethod
    def _context_messages(
        *,
        feedback: ToolExecutionFeedback,
        feedback_summary: Mapping[str, Any],
        control_facts: Mapping[str, Any],
    ) -> tuple[ModelMessage, ...]:
        """构造二轮模型的系统/用户上下文。

        这里的用户消息只包含低敏控制面定位字段和执行摘要，不包含 MCP arguments、工具正文或 Java receipt
        内部 token。真正的工具结果内容由后续 role=tool 消息承载，并且已经经过字段级过滤。
        """

        return (
            ModelMessage(
                role="system",
                content=(
                    "你是 DataSmart Govern 的 MCP 工具结果二轮推理节点。"
                    "你只能基于后续 role=tool 消息中的安全工具反馈进行总结、解释失败原因和给出下一步建议；"
                    "不要声称执行了新的工具，不要继续提出工具调用，不要编造未返回的结果。"
                ),
            ),
            ModelMessage(
                role="user",
                content=(
                    "请基于一次受控 MCP 工具执行结果生成面向用户和运维的简短总结。\n"
                    f"工具：{feedback.tool_name}\n"
                    f"状态：{feedback.status.value}\n"
                    f"摘要：{feedback.summary}\n"
                    f"runId：{_text(control_facts.get('runId') or control_facts.get('run_id')) or 'unknown'}\n"
                    f"sessionId：{_text(control_facts.get('sessionId') or control_facts.get('session_id')) or 'unknown'}\n"
                    f"inlineDecision：{_text(feedback_summary.get('inlineDecisionReason')) or 'unknown'}"
                ),
            ),
        )

    @staticmethod
    def _gateway_context(
        *,
        control_facts: Mapping[str, Any],
        trace_id: str | None,
        messages: tuple[ModelMessage, ...],
    ) -> ModelGatewayRequestContext:
        """根据 Java 控制面事实构造模型网关治理上下文。"""

        resolved_trace_id = (
            _text(trace_id)
            or _text(control_facts.get("traceId") or control_facts.get("trace_id"))
            or _text(control_facts.get("runId") or control_facts.get("run_id"))
        )
        return ModelGatewayRequestContext(
            tenant_id=_text(control_facts.get("tenantId") or control_facts.get("tenant_id")) or "unknown-tenant",
            project_id=_text(control_facts.get("projectId") or control_facts.get("project_id")) or "unknown-project",
            actor_id=_text(control_facts.get("actorId") or control_facts.get("actor_id")) or "unknown-actor",
            workload=WorkloadType.AGENT_REASONING,
            estimated_prompt_tokens=estimate_prompt_tokens(messages),
            estimated_completion_tokens=1024,
            trace_id=resolved_trace_id,
            attributes={
                "sessionId": _text(control_facts.get("sessionId") or control_facts.get("session_id")),
                "runId": _text(control_facts.get("runId") or control_facts.get("run_id")),
                "source": "mcp_model_feedback_second_turn",
            },
        )

    def _safe_summary(self, value: str) -> str:
        """裁剪模型摘要，避免异常 Provider 输出过长内容。"""

        text = str(value or "").strip()
        if len(text) <= self._settings.summary_max_chars:
            return text
        return text[: self._settings.summary_max_chars] + "...[TRUNCATED]"

    @staticmethod
    def _skipped(reason: str) -> McpModelFeedbackSecondTurnResult:
        """生成跳过二轮模型调用的低敏结果。"""

        return McpModelFeedbackSecondTurnResult(
            executed=False,
            skipped=True,
            reason=reason,
            recommended_actions=(
                "保留安全 modelFeedback 给 Java/前端诊断，不触发真实二轮模型调用。",
                "如需推进，请检查配置开关、toolCallId、审批状态或 MCP 执行结果完整性。",
            ),
        )


def _text(value: Any) -> str | None:
    """读取可选非空文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def mcp_model_feedback_second_turn_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> McpModelFeedbackSecondTurnSettings:
    """从环境变量读取 MCP modelFeedback 二轮调用配置。

    支持配置：
    - `DATASMART_AI_MCP_MODEL_SECOND_TURN_ENABLED`：默认 true；
    - `DATASMART_AI_MCP_MODEL_SECOND_TURN_FAIL_OPEN`：默认 true；
    - `DATASMART_AI_MCP_MODEL_SECOND_TURN_TEMPERATURE`：默认 0.2；
    - `DATASMART_AI_MCP_MODEL_SECOND_TURN_MAX_OUTPUT_TOKENS`：默认 1024；
    - `DATASMART_AI_MCP_MODEL_SECOND_TURN_ALLOW_FAILED_FEEDBACK`：默认 true；
    - `DATASMART_AI_MCP_MODEL_SECOND_TURN_SUMMARY_MAX_CHARS`：默认 4000。

    这些开关只控制“工具结果安全回填后的二轮模型调用”，不影响 MCP 工具执行、Java receipt 写回、
    outbox 状态推进或 permission-admin 授权判断。
    """

    source = os.environ if environ is None else environ
    return McpModelFeedbackSecondTurnSettings(
        enabled=_truthy(source.get("DATASMART_AI_MCP_MODEL_SECOND_TURN_ENABLED"), default=True),
        fail_open=_truthy(source.get("DATASMART_AI_MCP_MODEL_SECOND_TURN_FAIL_OPEN"), default=True),
        temperature=_non_negative_float(
            source.get("DATASMART_AI_MCP_MODEL_SECOND_TURN_TEMPERATURE"),
            default=0.2,
        ),
        max_output_tokens=_positive_int(
            source.get("DATASMART_AI_MCP_MODEL_SECOND_TURN_MAX_OUTPUT_TOKENS"),
            default=1024,
        ),
        allow_failed_tool_feedback=_truthy(
            source.get("DATASMART_AI_MCP_MODEL_SECOND_TURN_ALLOW_FAILED_FEEDBACK"),
            default=True,
        ),
        summary_max_chars=_positive_int(
            source.get("DATASMART_AI_MCP_MODEL_SECOND_TURN_SUMMARY_MAX_CHARS"),
            default=4000,
        ),
    )


def _truthy(value: str | None, *, default: bool) -> bool:
    """读取布尔风格配置。"""

    if value is None or not str(value).strip():
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on", "enabled"}


def _positive_int(value: str | None, *, default: int) -> int:
    """读取正整数配置，非法值回退默认值。"""

    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError, AttributeError):
        return default
    return parsed if parsed > 0 else default


def _non_negative_float(value: str | None, *, default: float) -> float:
    """读取非负浮点配置，非法值回退默认值。"""

    try:
        parsed = float(str(value).strip())
    except (TypeError, ValueError, AttributeError):
        return default
    return parsed if parsed >= 0 else default


__all__ = [
    "MCP_MODEL_FEEDBACK_SECOND_TURN_SCHEMA_VERSION",
    "McpModelFeedbackSecondTurnResult",
    "McpModelFeedbackSecondTurnService",
    "McpModelFeedbackSecondTurnSettings",
    "mcp_model_feedback_second_turn_settings_from_env",
]
