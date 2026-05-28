"""受控二轮推理 runtime event 构建器。

`AgentSecondTurnOrchestrator` 应聚焦“是否调用模型、如何构造二轮请求、如何返回结果”。事件编号、
run/session 继承和事件属性组装虽然重要，但属于可观测性适配细节；如果都留在编排器文件里，后续增加
SSE token 流、模型耗时指标、失败诊断和告警等级时会很快再次逼近 500 行。

因此本模块单独承载二轮推理相关事件构建：
- 继续沿用已有 AgentPlan 的 sequence，不重新从 1 开始；
- 尽量继承 Java control-plane 的 runId/sessionId，便于 WebSocket replay 和审计回放串联；
- 只写入摘要性 attributes，不把 tool result 原文、prompt 或大 payload 放进事件。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackSnapshot
from datasmart_ai_runtime.services.agent_loop_control_policy import AgentLoopControlDecision


class SecondTurnEventBuilder:
    """为受控二轮编排生成连续 runtime events。

    不直接复用 `RuntimeEventRecorder`，是因为当前 AgentPlan 已经有一批事件。这里需要从已有最大 sequence
    之后继续编号，保证 HTTP snapshot、WebSocket replay 和审计回放看到的是一条连续事件流。
    """

    def __init__(self, *, request: AgentRequest, plan: AgentPlan) -> None:
        self._request = request
        self._plan = plan
        self._events: list[AgentRuntimeEvent] = []
        self._next_sequence = self._resolve_next_sequence(plan)

    def record_loop_decision(
        self,
        decision: AgentLoopControlDecision,
        snapshot: AgentControlPlaneFeedbackSnapshot,
    ) -> None:
        """记录 loop 策略决策事件。"""

        self._record(
            AgentRuntimeEventType.AGENT_LOOP_CONTROL_DECIDED,
            "agent_loop_control_decided",
            "已完成受控 Agent loop 策略决策。",
            severity=AgentRuntimeEventSeverity.AUDIT if decision.allowed else AgentRuntimeEventSeverity.WARNING,
            attributes={
                "allowed": decision.allowed,
                "action": decision.action.value,
                "reasonCount": len(decision.reasons),
                "feedbackCount": len(snapshot.feedback_items),
                "expectedToolCallCount": snapshot.expected_tool_call_count,
                "missingToolCallIds": snapshot.missing_tool_call_ids,
                "statusCounts": dict(snapshot.status_counts),
            },
        )

    def record_feedback_built(
        self,
        *,
        feedback_count: int,
        message_count: int,
        missing_feedback_call_ids: tuple[str, ...],
        extra_feedback_call_ids: tuple[str, ...],
        complete: bool,
        resource_resolution_summaries: tuple[dict[str, Any], ...] = (),
    ) -> None:
        """记录工具结果消息构建事件。

        `resource_resolution_summaries` 用来解释工具结果资源是否进入模型上下文。它只包含资源类型、
        URI、workspace、contextPolicy、decision 和 issue code，不包含工具 result 原文。这样前端和审计
        台可以展示“为什么 result 被裁剪”，同时不会把敏感工具输出扩散到事件流。
        """

        self._record(
            AgentRuntimeEventType.TOOL_RESULT_FEEDBACK_BUILT,
            "build_control_plane_tool_result_feedback",
            "已根据 Java 控制面反馈构建二轮模型工具结果消息。",
            severity=AgentRuntimeEventSeverity.INFO if complete else AgentRuntimeEventSeverity.WARNING,
            attributes={
                "feedbackCount": feedback_count,
                "messageCount": message_count,
                "missingFeedbackCallIds": missing_feedback_call_ids,
                "extraFeedbackCallIds": extra_feedback_call_ids,
                "complete": complete,
                "resourceResolutionCount": len(resource_resolution_summaries),
                "resourceResolutionBlockedCount": self._resource_blocked_count(resource_resolution_summaries),
                "resourceResolutionModelBlockedCount": self._resource_model_blocked_count(resource_resolution_summaries),
                "resourceResolutions": resource_resolution_summaries,
            },
        )

    def record_second_turn_completed(
        self,
        *,
        feedback_count: int,
        prompt_tokens: int | None,
        completion_tokens: int | None,
        error_code: str | None,
    ) -> None:
        """记录二轮模型调用完成事件。"""

        self._record(
            AgentRuntimeEventType.MODEL_SECOND_TURN_COMPLETED,
            "invoke_controlled_model_second_turn",
            "模型已基于 Java 控制面工具反馈完成受控二轮推理。",
            severity=AgentRuntimeEventSeverity.ERROR if error_code else AgentRuntimeEventSeverity.INFO,
            attributes={
                "feedbackCount": feedback_count,
                "promptTokens": prompt_tokens,
                "completionTokens": completion_tokens,
                "errorCode": error_code,
            },
        )

    def record_second_turn_skipped(self, *, action: str, reasons: tuple[str, ...]) -> None:
        """记录二轮被跳过的事件。"""

        self._record(
            AgentRuntimeEventType.MODEL_SECOND_TURN_SKIPPED,
            "skip_controlled_model_second_turn",
            "当前未触发二轮模型推理。",
            severity=AgentRuntimeEventSeverity.WARNING,
            attributes={"action": action, "reasonCount": len(reasons)},
        )

    def events(self) -> tuple[AgentRuntimeEvent, ...]:
        """返回本次二轮编排新增事件。"""

        return tuple(self._events)

    def _record(
        self,
        event_type: AgentRuntimeEventType,
        stage: str,
        message: str,
        *,
        severity: AgentRuntimeEventSeverity,
        attributes: dict[str, Any],
    ) -> None:
        event = AgentRuntimeEvent(
            event_type=event_type,
            stage=stage,
            message=message,
            severity=severity,
            tenant_id=self._request.tenant_id,
            project_id=self._request.project_id,
            actor_id=self._request.actor_id,
            request_id=self._plan.request_id,
            run_id=self._resolve_run_id(),
            session_id=self._resolve_session_id(),
            sequence=self._next_sequence,
            attributes=attributes,
        )
        self._events.append(event)
        self._next_sequence += 1

    @staticmethod
    def _resolve_next_sequence(plan: AgentPlan) -> int:
        sequences = [event.sequence for event in plan.runtime_events if event.sequence is not None]
        return (max(sequences) + 1) if sequences else len(plan.runtime_events) + 1

    def _resolve_run_id(self) -> str | None:
        for tool_plan in self._plan.tool_plans:
            run_id = tool_plan.governance_hints.get("agentRuntimeRunId")
            if run_id:
                return str(run_id)
        for event in self._plan.runtime_events:
            if event.run_id:
                return event.run_id
        return None

    def _resolve_session_id(self) -> str | None:
        for tool_plan in self._plan.tool_plans:
            session_id = tool_plan.governance_hints.get("agentRuntimeSessionId")
            if session_id:
                return str(session_id)
        for key in ("sessionId", "session_id"):
            value = self._request.variables.get(key)
            if value:
                return str(value)
        for event in self._plan.runtime_events:
            if event.session_id:
                return event.session_id
        return None

    @staticmethod
    def _resource_blocked_count(summaries: tuple[dict[str, Any], ...]) -> int:
        """统计资源引用被治理层完全阻断的数量。"""

        return sum(1 for item in summaries if item.get("decision") == "blocked")

    @staticmethod
    def _resource_model_blocked_count(summaries: tuple[dict[str, Any], ...]) -> int:
        """统计资源可审计/可下载但不允许进入模型上下文的数量。"""

        return sum(1 for item in summaries if not item.get("modelContextAllowed"))
