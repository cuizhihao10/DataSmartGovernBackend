"""受控 Agent 二轮推理编排器。

4.08 已经引入 `AgentLoopControlPolicyEvaluator`，能判断当前控制面反馈是否允许进入二轮模型推理；
4.09 又把 API 响应组装从 `api.py` 中拆出，避免把所有 Agent 副作用都塞进 HTTP 路由层。本模块承接
下一步：在策略明确允许时，基于 Java 控制面工具反馈构造 tool result messages，并允许模型选择下一批工具。

职责边界非常重要：
- 本模块不执行真实工具，不审批，不重试，不推进 Java 状态机；
- 它只消费已经形成的 `AgentControlPlaneFeedbackSnapshot` 和 `AgentLoopControlDecision`；
- 只有 `decision.allowed=True` 且 action 为 `allow_second_turn` 时才会调用模型；
- 后续轮次只暴露意图、Skill 和显式生命周期边能到达的工具，并始终重新经过 intake、预算和重复调用守卫。

这样做能把 DataSmart 的 Agent 主链从“能展示二轮条件”推进到“具备受控二轮推理”，同时仍然保持
Java `agent-runtime` 作为执行、审批、审计和幂等事实源。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Callable

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ModelInvocationChunk,
    ModelInvocationRequest,
    ModelInvocationResult,
    ModelMessage,
    ModelToolCall,
    ToolPlan,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import AgentFollowUpToolPlanner
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackSnapshot
from datasmart_ai_runtime.services.agent_loop_control_policy import AgentLoopControlAction, AgentLoopControlDecision
from datasmart_ai_runtime.services.agent_second_turn_events import SecondTurnEventBuilder
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_gateway_context import build_model_gateway_context
from datasmart_ai_runtime.services.model_gateway.model_tool_schema import OpenAICompatibleToolSchemaBuilder
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_query_engine import ModelQueryEngine
from datasmart_ai_runtime.services.model_gateway.model_provider_metadata import build_model_provider_metadata
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ModelToolResultFeedbackBuilder


@dataclass(frozen=True)
class AgentSecondTurnResult:
    """一次受控二轮推理的结果摘要。

    字段说明：
    - `executed`：是否真正调用了模型。策略不允许、缺少路由、反馈不完整时都为 False；
    - `allowed`：loop policy 是否允许自动推进，便于前端区分“策略拒绝”和“技术失败”；
    - `action`：来自 loop policy 的下一步动作，例如 `allow_second_turn` 或 `require_human_takeover`；
    - `summary`：模型二轮输出或跳过原因，可直接用于 API 摘要；
    - `feedback_count/message_count`：用于诊断工具反馈是否已经被转换成下一轮模型消息；
    - `missing/extra_feedback_call_ids`：用于排查 OpenAI-compatible tool result message 不完整问题；
    - `runtime_events`：本次编排新增的事件，会被 API 响应组装器追加到 AgentPlan 事件流中。
    """

    executed: bool
    allowed: bool
    action: str
    summary: str
    reasons: tuple[str, ...] = ()
    recommended_actions: tuple[str, ...] = ()
    feedback_count: int = 0
    message_count: int = 0
    missing_feedback_call_ids: tuple[str, ...] = ()
    extra_feedback_call_ids: tuple[str, ...] = ()
    provider_name: str | None = None
    model_name: str | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    error_code: str | None = None
    visible_tool_names: tuple[str, ...] = ()
    required_evidence_tool_names: tuple[str, ...] = ()
    model_tool_call_count: int = 0
    follow_up_tool_plans: tuple[ToolPlan, ...] = ()
    repeated_tool_call_count: int = 0
    governance_issue_codes: tuple[str, ...] = ()
    governance_issue_messages: tuple[str, ...] = ()
    budget_issue_codes: tuple[str, ...] = ()
    cache_hit: bool = False
    runtime_events: tuple[AgentRuntimeEvent, ...] = field(default_factory=tuple)

    @property
    def continues(self) -> bool:
        """Whether another governed control-plane tool run must be submitted."""

        return bool(self.follow_up_tool_plans)

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 响应友好的摘要。

        这里不返回第二轮完整消息列表，也不返回 tool result 原始 payload。原因是消息列表可能包含
        工具结果、审计引用和模型上下文，应该走 runtime event 脱敏策略或审计存储，而不是裸露给普通 API。
        """

        return {
            "executed": self.executed,
            "allowed": self.allowed,
            "action": self.action,
            "summary": self.summary,
            "reasons": self.reasons,
            "recommendedActions": self.recommended_actions,
            "feedbackCount": self.feedback_count,
            "messageCount": self.message_count,
            "missingFeedbackCallIds": self.missing_feedback_call_ids,
            "extraFeedbackCallIds": self.extra_feedback_call_ids,
            "providerName": self.provider_name,
            "modelName": self.model_name,
            "promptTokens": self.prompt_tokens,
            "completionTokens": self.completion_tokens,
            "errorCode": self.error_code,
            "visibleToolNames": self.visible_tool_names,
            "requiredEvidenceToolNames": self.required_evidence_tool_names,
            "modelToolCallCount": self.model_tool_call_count,
            "followUpToolCount": len(self.follow_up_tool_plans),
            "followUpToolNames": tuple(plan.tool_name for plan in self.follow_up_tool_plans),
            "repeatedToolCallCount": self.repeated_tool_call_count,
            "governanceIssueCodes": self.governance_issue_codes,
            "governanceIssueMessages": self.governance_issue_messages,
            "budgetIssueCodes": self.budget_issue_codes,
            "cacheHit": self.cache_hit,
            "continues": self.continues,
        }


class AgentSecondTurnOrchestrator:
    """在 loop policy 允许时执行模型二轮推理。

    真实 Agent 产品的二轮推理不是“模型自己继续说两句”，而是一个受控协议：
    1. Java 控制面先形成工具反馈事实；
    2. Python 把反馈转换成 assistant/tool messages；
    3. loop policy 判断预算、步数、审批和反馈完整性；
    4. 只有通过策略闸门，才允许模型基于工具结果总结或给出下一步建议。
    """

    def __init__(
        self,
        model_providers: ModelProviderRegistry,
        feedback_builder: ModelToolResultFeedbackBuilder | None = None,
        model_gateway: ModelGatewayGovernanceService | None = None,
        follow_up_tool_planner: AgentFollowUpToolPlanner | None = None,
        model_query_engine: ModelQueryEngine | None = None,
    ) -> None:
        self._model_providers = model_providers
        self._feedback_builder = feedback_builder or ModelToolResultFeedbackBuilder()
        self._model_gateway = model_gateway
        self._follow_up_tool_planner = follow_up_tool_planner
        self._model_query_engine = model_query_engine or (
            ModelQueryEngine(model_gateway=model_gateway, model_providers=model_providers)
            if model_gateway is not None
            else None
        )

    def run(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
        loop_control_decision: AgentLoopControlDecision | None,
        progress_event_sink: Callable[[AgentRuntimeEvent], None] | None = None,
    ) -> AgentSecondTurnResult:
        """根据控制面反馈和 loop 决策尝试执行二轮模型推理。

        调用方可以放心把该方法接在 API 响应组装链路后面：如果缺少反馈、策略不允许、路由为空或
        tool result messages 不完整，方法会返回 skipped 结果并写事件，不会抛出异常中断主响应。
        """

        events = SecondTurnEventBuilder(
            request=request,
            plan=plan,
            transient_event_sink=progress_event_sink,
        )
        if control_plane_feedback is None or loop_control_decision is None:
            return self._skipped(
                events,
                allowed=False,
                action="control_plane_feedback_unavailable",
                summary="缺少控制面反馈或 loop 决策，当前不触发二轮模型推理。",
                reasons=("控制面反馈快照或 loop 策略决策不存在。",),
                recommended_actions=("等待 plan ingestion 与控制面反馈收集完成后再评估二轮推理。",),
            )

        events.record_loop_decision(loop_control_decision, control_plane_feedback)
        if control_plane_feedback.auto_execution_summary is not None:
            events.record_auto_execution_summary(control_plane_feedback.auto_execution_summary)
        if not (
            loop_control_decision.allowed
            and loop_control_decision.action == AgentLoopControlAction.ALLOW_SECOND_TURN
        ):
            return self._skipped(
                events,
                allowed=loop_control_decision.allowed,
                action=loop_control_decision.action.value,
                summary="loop 策略未允许自动二轮推理，当前仅返回控制面反馈和建议动作。",
                reasons=loop_control_decision.reasons,
                recommended_actions=loop_control_decision.recommended_actions,
            )

        if plan.selected_route is None:
            return self._skipped(
                events,
                allowed=True,
                action=loop_control_decision.action.value,
                summary="当前计划没有可用模型路由，无法执行二轮推理。",
                reasons=("模型网关没有选出可用路由。",),
                recommended_actions=("检查模型 Provider 健康、预算额度和模型路由配置。",),
            )

        tool_calls = self._tool_calls_from_plan(plan)
        feedback_items = tuple(item.to_tool_feedback() for item in control_plane_feedback.feedback_items)
        feedback_bundle = self._feedback_builder.build(
            tool_calls,
            feedback_items,
            current_workspace_key=self._workspace_key_from_plan(plan),
        )
        events.record_feedback_built(
            feedback_count=len(feedback_items),
            message_count=len(feedback_bundle.messages),
            missing_feedback_call_ids=feedback_bundle.missing_feedback_call_ids,
            extra_feedback_call_ids=feedback_bundle.extra_feedback_call_ids,
            complete=feedback_bundle.complete,
            resource_resolution_summaries=feedback_bundle.resource_resolution_summaries,
            result_filter_summaries=feedback_bundle.result_filter_summaries,
        )
        if not feedback_bundle.complete or not feedback_bundle.messages:
            return self._skipped(
                events,
                allowed=True,
                action=loop_control_decision.action.value,
                summary="工具反馈消息不完整，已停止二轮推理以避免模型上下文错配。",
                reasons=("assistant/tool result message 未满足 OpenAI-compatible 完整性要求。",),
                recommended_actions=("检查 Java 工具反馈是否覆盖每个 modelToolCallId，并按 runId replay。",),
                feedback_count=len(feedback_items),
                message_count=len(feedback_bundle.messages),
                missing_feedback_call_ids=feedback_bundle.missing_feedback_call_ids,
                extra_feedback_call_ids=feedback_bundle.extra_feedback_call_ids,
            )

        visible_tools = (
            self._follow_up_tool_planner.visible_tools(request, plan)
            if self._follow_up_tool_planner is not None
            else ()
        )
        required_evidence_tool_names = self._required_evidence_tool_names(
            request,
            plan,
            control_plane_feedback,
            visible_tools,
        )
        if required_evidence_tool_names:
            required_set = set(required_evidence_tool_names)
            # Only expose the incomplete read-only frontier during mandatory
            # evidence collection.  This prevents a model from skipping target
            # metadata and jumping directly to a draft merely because both tools
            # are reachable somewhere in the accumulated lifecycle graph.
            visible_tools = tuple(
                tool for tool in visible_tools if tool.name in required_set
            )
        gateway_context = self._gateway_context_from_plan(request, plan)
        second_turn_request = ModelInvocationRequest(
            route=plan.selected_route,
            messages=self._build_context_messages(
                request,
                plan,
                required_evidence_tool_names=required_evidence_tool_names,
            ) + feedback_bundle.messages,
            trace_id=request.variables.get("traceId") or request.variables.get("trace_id") or plan.request_id,
            available_tools=visible_tools,
            # `auto` is essential: the model may finish with text or choose another
            # governed tool batch.  `required` would force hallucinated work when the
            # original goal is already complete.
            tool_choice=(
                "required"
                if required_evidence_tool_names
                else ("auto" if visible_tools else "none")
            ),
            provider_metadata=build_model_provider_metadata(gateway_context),
        )
        cache_hit = False
        if self._model_query_engine is not None:
            public_content_parts: list[str] = []

            def publish_public_chunk(chunk: ModelInvocationChunk) -> None:
                if not chunk.content_delta:
                    return
                public_content_parts.append(chunk.content_delta)
                events.publish_public_output_delta("".join(public_content_parts))

            query_result = self._model_query_engine.invoke(
                second_turn_request,
                context=gateway_context,
                chunk_sink=publish_public_chunk,
            )
            result = query_result.result
            cache_hit = query_result.cache_hit
        else:
            result = self._model_providers.invoke(second_turn_request)
            self._record_usage_if_possible(request, plan, result)

        if result.error_code:
            events.record_second_turn_completed(
                feedback_count=len(feedback_items),
                prompt_tokens=result.prompt_tokens,
                completion_tokens=result.completion_tokens,
                error_code=result.error_code,
            )
            return AgentSecondTurnResult(
                executed=True,
                allowed=False,
                action="model_provider_failed",
                summary=(
                    f"模型服务本轮调用失败（{result.error_code}），自动处理已安全停止；"
                    "已完成的只读检查结果会保留，可稍后重试，不会重复执行状态变更。"
                ),
                reasons=(f"模型 Provider 返回错误：{result.error_code}。",),
                recommended_actions=("检查模型 Provider 协议兼容性或稍后重试当前 Agent 会话。",),
                feedback_count=len(feedback_items),
                message_count=len(feedback_bundle.messages),
                missing_feedback_call_ids=feedback_bundle.missing_feedback_call_ids,
                extra_feedback_call_ids=feedback_bundle.extra_feedback_call_ids,
                provider_name=result.provider_name,
                model_name=result.model_name,
                prompt_tokens=result.prompt_tokens,
                completion_tokens=result.completion_tokens,
                error_code=result.error_code,
                visible_tool_names=tuple(tool.name for tool in visible_tools),
                required_evidence_tool_names=required_evidence_tool_names,
                model_tool_call_count=len(result.tool_calls),
                cache_hit=cache_hit,
                runtime_events=events.events(),
            )

        follow_up = (
            self._follow_up_tool_planner.govern(
                request=request,
                plan=plan,
                tool_calls=result.tool_calls,
                visible_tools=visible_tools,
                control_plane_feedback=control_plane_feedback,
            )
            if self._follow_up_tool_planner is not None
            else None
        )
        if follow_up is not None:
            events.record_follow_up_tool_planning(follow_up.to_summary())
        public_summary = result.content
        if (
            follow_up is not None
            and follow_up.state_guard_rejected_count > 0
            and not follow_up.accepted_tool_plans
            and follow_up.state_guard_issue_messages
        ):
            public_summary = (
                "模型提出的下一步未通过平台真实状态校验："
                + "；".join(follow_up.state_guard_issue_messages)
            )
        events.record_second_turn_completed(
            feedback_count=len(feedback_items),
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
            error_code=result.error_code,
            public_content=public_summary,
        )
        return AgentSecondTurnResult(
            executed=True,
            allowed=True,
            action="continue_with_tools" if follow_up and follow_up.continues else "complete_with_answer",
            summary=public_summary,
            reasons=loop_control_decision.reasons,
            recommended_actions=loop_control_decision.recommended_actions,
            feedback_count=len(feedback_items),
            message_count=len(feedback_bundle.messages),
            missing_feedback_call_ids=feedback_bundle.missing_feedback_call_ids,
            extra_feedback_call_ids=feedback_bundle.extra_feedback_call_ids,
            provider_name=result.provider_name,
            model_name=result.model_name,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
            error_code=result.error_code,
            visible_tool_names=tuple(tool.name for tool in visible_tools),
            required_evidence_tool_names=required_evidence_tool_names,
            model_tool_call_count=len(result.tool_calls),
            follow_up_tool_plans=follow_up.accepted_tool_plans if follow_up else (),
            repeated_tool_call_count=follow_up.repeated_count if follow_up else 0,
            governance_issue_codes=follow_up.state_guard_issue_codes if follow_up else (),
            governance_issue_messages=follow_up.state_guard_issue_messages if follow_up else (),
            budget_issue_codes=follow_up.budget_issue_codes if follow_up else (),
            cache_hit=cache_hit,
            runtime_events=events.events(),
        )

    @staticmethod
    def _gateway_context_from_plan(request: AgentRequest, plan: AgentPlan) -> ModelGatewayRequestContext:
        """从已有计划恢复二轮模型调用的 Provider metadata。

        受控二轮推理通常发生在 Java 工具执行完成之后，时间上晚于首次 AgentPlan 生成。此时不应重新做一次
        路由决策，也不应丢失第一次模型网关给出的缓存治理计划。因此这里从 `plan.model_gateway_decision`
        读取 cachePlan，并构造一个最小 `ModelGatewayRequestContext` 交给统一 metadata 构建器。
        """

        decision = plan.model_gateway_decision
        attributes: dict[str, object] = {
            "sessionId": request.variables.get("sessionId") or request.variables.get("session_id"),
        }
        if decision is not None and getattr(decision, "cache_plan", None) is not None:
            attributes["cachePlan"] = decision.cache_plan.to_summary()
        context = ModelGatewayRequestContext(
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            workload=request.preferred_workload,
            trace_id=request.variables.get("traceId") or request.variables.get("trace_id") or plan.request_id,
            attributes=attributes,
        )
        return context

    @staticmethod
    def _tool_calls_from_plan(plan: AgentPlan) -> tuple[ModelToolCall, ...]:
        """从 ToolPlan 还原模型上一轮 tool_calls。

        二轮回填需要 assistant(tool_calls) 历史消息。这里使用 ToolPlan 中经过平台治理后的参数，而不是
        原始模型字符串，避免把未校验的模型幻觉参数再次放回上下文。
        """

        calls: list[ModelToolCall] = []
        for tool_plan in plan.tool_plans:
            call_id = tool_plan.governance_hints.get("modelToolCallId")
            if call_id is None or not str(call_id).strip():
                continue
            model_function_name = str(
                tool_plan.governance_hints.get("modelToolFunctionName") or ""
            ).strip()
            if not model_function_name:
                model_function_name = OpenAICompatibleToolSchemaBuilder._normalize_function_name(
                    tool_plan.tool_name
                )
            calls.append(
                ModelToolCall(
                    call_id=str(call_id).strip(),
                    name=tool_plan.tool_name,
                    arguments=json.dumps(tool_plan.arguments, ensure_ascii=False, sort_keys=True),
                    raw_call={
                        "type": "function_call",
                        "call_id": str(call_id).strip(),
                        "name": model_function_name,
                        "source": "agent_second_turn_orchestrator",
                    },
                )
            )
        return tuple(calls)

    @staticmethod
    def _workspace_key_from_plan(plan: AgentPlan) -> str | None:
        """从计划中的工具治理提示读取当前工作空间。

        二轮推理使用的是 Java 控制面反馈，反馈中可能携带对象存储、agent-runtime 或 memory 引用。把当前
        workspaceKey 传给消息构建器后，构建器可以阻断跨 workspace 的资源进入模型上下文。这里从
        ToolPlan 读取而不是从请求变量临时拼接，是为了沿用前序 `AgentWorkspaceContextBuilder` 已经统一
        生成的隔离语义，避免不同层各自拼接 key 造成细微不一致。
        """

        for tool_plan in plan.tool_plans:
            value = tool_plan.governance_hints.get("workspaceKey")
            if value is not None and str(value).strip():
                return str(value).strip()
        return None

    @staticmethod
    def _build_context_messages(
        request: AgentRequest,
        plan: AgentPlan,
        *,
        required_evidence_tool_names: tuple[str, ...] = (),
    ) -> tuple[ModelMessage, ...]:
        """构造二轮模型上下文消息。

        模型只能根据受控反馈与本轮显式 tools 决定“结束回答”或“提出下一批工具”。它不能声称工具已经
        执行，也不能输出隐藏思维链；后续工具仍会重新进入平台治理与 Java/MCP Durable 控制面。
        """

        required_evidence_instruction = ""
        if required_evidence_tool_names == ("sync.task.draft.save",):
            required_evidence_instruction = (
                "双端数据源、连接和元数据证据已经齐全，且用户原始目标明确给出了同步模式与对象映射。"
                "本轮必须调用 sync.task.draft.save 生成与手工向导同契约的任务草稿；"
                "数据源 ID 和元数据引用由平台注入，不得猜测。"
            )
        elif required_evidence_tool_names:
            required_evidence_instruction = (
                "当前状态机仍缺少以下必需的只读证据工具："
                f"{'、'.join(required_evidence_tool_names)}。"
                "本轮只允许调用这些工具，至少调用一个；不要提前生成任务草稿或结束回答。"
            )

        return (
            ModelMessage(
                role="system",
                content=(
                    "你是 DataSmart Govern 的受控 Agent 后续推理节点。"
                    "只能基于 role=tool 的受控反馈和本轮公开工具 schema 决策。"
                    "如果目标已完成，直接给出公开结论；如果仍需事实或动作，使用原生 tool_calls 选择最少工具。"
                    "数据源目录搜索只有 matchStatus=EXACT 且存在 resolvedDatasourceId 时，"
                    "才能把该 ID 原样用于同方向连接测试；AMBIGUOUS 或 NOT_FOUND 必须停止并向用户说明候选或错误。"
                    "连接测试成功后读取对应方向元数据；用户明确提供 schema 或表名时，"
                    "用 schemaPattern/tableNamePattern 缩小元数据范围。两端元数据齐全后，"
                    "按照用户原始目标中明确给出的任务名称、五种产品同步模式、源表到目标表映射、"
                    "字段映射、WHERE、调度配置或只读 SQL 生成 sync.task.draft.save。"
                    "真实元数据与用户描述冲突时不要猜测修正，应指出不存在或不兼容的表、字段、schema，"
                    "给出可选修复并等待用户确认。"
                    f"{required_evidence_instruction}"
                    "不要伪造参数、不要声称尚未执行的工具已经成功、不要输出隐藏思维链。"
                ),
            ),
            ModelMessage(
                role="user",
                content=(
                    f"用户目标：{request.objective}\n"
                    f"当前计划摘要：{plan.response_summary}\n"
                    f"下一步建议：{'；'.join(plan.next_actions)}"
                ),
            ),
        )

    @staticmethod
    def _required_evidence_tool_names(
        request: AgentRequest,
        plan: AgentPlan,
        feedback: AgentControlPlaneFeedbackSnapshot,
        visible_tools: tuple[Any, ...],
    ) -> tuple[str, ...]:
        """Return the incomplete read-only frontier of a source-to-target flow.

        The model decides arguments and read scope, but the platform owns workflow
        invariants.  Successful tools from prior Java Runs are accepted only from
        the server-created resource ledger.  Current-run facts come from terminal
        control-plane feedback.  An ambiguous catalog result is intentionally not
        treated as successful evidence even though the catalog tool itself returned
        normally.
        """

        if plan.intent_analysis is None or not any(
            getattr(domain, "value", domain) == "data_sync"
            for domain in plan.intent_analysis.governance_domains
        ):
            return ()

        succeeded: set[str] = set()
        for tool_plan in plan.tool_plans:
            raw_ledger = tool_plan.governance_hints.get("agentLoopResourceRefs")
            if not isinstance(raw_ledger, dict):
                continue
            for tool_name, raw_reference in raw_ledger.items():
                if not isinstance(raw_reference, dict):
                    continue
                normalized_name = str(tool_name).strip()
                if (
                    normalized_name
                    and str(raw_reference.get("toolCode") or "").strip() == normalized_name
                    and str(raw_reference.get("auditId") or "").strip()
                    and str(raw_reference.get("runId") or "").strip()
                ):
                    succeeded.add(normalized_name)

        for item in feedback.feedback_items:
            if item.status.value != "succeeded":
                continue
            if item.tool_name in {
                "datasource.source.catalog.search",
                "datasource.target.catalog.search",
            } and str(item.result.get("matchStatus") or "").strip().upper() != "EXACT":
                continue
            succeeded.add(item.tool_name)

        lifecycle_edges = (
            ("datasource.source.catalog.search", "datasource.source.connection.test"),
            ("datasource.target.catalog.search", "datasource.target.connection.test"),
            ("datasource.source.connection.test", "datasource.source.metadata.read"),
            ("datasource.target.connection.test", "datasource.target.metadata.read"),
        )
        visible_names = {tool.name for tool in visible_tools}
        incomplete_evidence = tuple(
            downstream
            for upstream, downstream in lifecycle_edges
            if upstream in succeeded
            and downstream not in succeeded
            and downstream in visible_names
        )
        if incomplete_evidence:
            return incomplete_evidence

        current_failed = any(item.status.value == "failed" for item in feedback.feedback_items)
        if (
            not current_failed
            and "datasource.source.metadata.read" in succeeded
            and "datasource.target.metadata.read" in succeeded
            and "sync.task.draft.save" not in succeeded
            and "sync.task.draft.save" in visible_names
            and AgentSecondTurnOrchestrator._objective_has_explicit_sync_definition(request)
        ):
            return ("sync.task.draft.save",)
        return ()

    @staticmethod
    def _objective_has_explicit_sync_definition(request: AgentRequest) -> bool:
        """Detect whether forcing a draft is safer than asking for more input.

        This is only a workflow gate.  The model still produces structured
        mappings and every field is validated against real metadata.  A vague
        request therefore remains free to ask a focused clarification instead
        of being forced into a guessed task draft.
        """

        structured = request.variables.get("dataSyncRequest")
        if isinstance(structured, dict):
            mappings = structured.get("objectMappings")
            if isinstance(mappings, list) and mappings:
                return True

        objective = request.objective.strip().lower()
        mapping_markers = (
            "映射到",
            "同步到",
            "迁移到",
            "->",
            "→",
            "map ",
            "mapping",
        )
        mode_markers = (
            "全量",
            "定期批量",
            "定期全量",
            "sql",
            "实时",
            "full",
            "scheduled",
            "cdc",
        )
        side_markers = ("源端", "源表", "目标端", "目标表", "source", "target")
        return (
            any(marker in objective for marker in mapping_markers)
            and any(marker in objective for marker in mode_markers)
            and sum(marker in objective for marker in side_markers) >= 2
        )

    def _record_usage_if_possible(
        self,
        request: AgentRequest,
        plan: AgentPlan,
        result: ModelInvocationResult,
    ) -> None:
        """把二轮模型调用结果回写到模型网关治理服务。

        `model_gateway` 是可选注入项。没有注入时仍然允许二轮推理完成，原因是当前项目还处于渐进式集成；
        但生产环境建议注入共享治理服务，确保二轮 token、错误码和延迟都进入预算、成本、Provider 健康
        和告警统计。否则一次 Agent 请求的首轮健康可见，二轮总结故障却不可见，会影响真实排障。
        """

        if self._model_gateway is None:
            return
        self._model_gateway.record_invocation_result(build_model_gateway_context(request, plan.context_blocks), result)

    @staticmethod
    def _skipped(
        events: SecondTurnEventBuilder,
        *,
        allowed: bool,
        action: str,
        summary: str,
        reasons: tuple[str, ...],
        recommended_actions: tuple[str, ...],
        feedback_count: int = 0,
        message_count: int = 0,
        missing_feedback_call_ids: tuple[str, ...] = (),
        extra_feedback_call_ids: tuple[str, ...] = (),
    ) -> AgentSecondTurnResult:
        """生成跳过二轮的结果，并写入可见事件。"""

        events.record_second_turn_skipped(action=action, reasons=reasons)
        return AgentSecondTurnResult(
            executed=False,
            allowed=allowed,
            action=action,
            summary=summary,
            reasons=reasons,
            recommended_actions=recommended_actions,
            feedback_count=feedback_count,
            message_count=message_count,
            missing_feedback_call_ids=missing_feedback_call_ids,
            extra_feedback_call_ids=extra_feedback_call_ids,
            runtime_events=events.events(),
        )
