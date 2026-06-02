"""基于 runtime-event replay 的 Agent 控制面反馈桥。

4.13 已经让 Python AI Runtime 的 HTTP replay / WebSocket subscribe 可以读取 Java `agent-runtime`
的 runtime-event 投影。但如果这批事件只展示给前端，而不进入 Agent loop 决策，Python 仍然会过度依赖
同步工具结果查询：同步查询短暂失败时，loop 会认为“缺少反馈”；而 Java 事件投影里可能已经能看到
工具等待审批、执行成功或执行失败。

本模块把 replay 到的 Java 工具状态事件转换成 `AgentControlPlaneFeedbackSnapshot` 的补充来源：

- 如果同步反馈缺失，可以用 Java runtime-event 中的终态事件补齐；
- 如果同步反馈还是等待审批/跳过，但 replay 里出现更晚的成功/失败事件，可以刷新反馈；
- 如果 replay 只看到 PLANNED/EXECUTING 这类非终态事件，则不伪造成可用工具结果，继续让 loop 等待。

这样做让 DataSmart 的 Agent 主链更接近真实工具型 Agent：事件流不仅用于 UI 可见性，还会参与
“是否继续推理、等待、转人工或停止”的控制决策。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedbackStatus
from datasmart_ai_runtime.services.runtime_events.runtime_event_replay_source import (
    RuntimeEventReplayCoordinator,
    RuntimeEventReplaySource,
)


@dataclass(frozen=True)
class AgentRuntimeEventFeedbackAugmentation:
    """一次基于 runtime-event replay 的反馈增强结果。

    字段说明：
    - `snapshot`：增强后的控制面反馈快照，后续 loop policy 和二轮推理都应消费它；
    - `replayed_event_count`：本次从外部 replay source 读取到的事件数量；
    - `derived_feedback_count`：通过事件补齐的缺失反馈数量；
    - `replaced_feedback_count`：通过更新的事件刷新旧反馈的数量；
    - `external_errors`：外部事件源失败摘要。失败不会阻断主链，只会进入诊断响应。
    """

    snapshot: AgentControlPlaneFeedbackSnapshot
    replayed_event_count: int = 0
    derived_feedback_count: int = 0
    replaced_feedback_count: int = 0
    external_errors: tuple[dict[str, str], ...] = ()
    source_names: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 诊断摘要。

        该摘要不暴露完整事件 attributes，只说明本次是否使用了事件 replay 参与 loop 决策。这样前端和
        运维能判断“为什么这次二轮推理从等待变成允许/失败解释”，但不会额外扩大敏感事件字段。
        """

        return {
            "sourceNames": self.source_names,
            "replayedEventCount": self.replayed_event_count,
            "derivedFeedbackCount": self.derived_feedback_count,
            "replacedFeedbackCount": self.replaced_feedback_count,
            "externalErrors": self.external_errors,
            "effectiveFeedbackCount": len(self.snapshot.feedback_items),
            "effectiveSecondTurnEligible": self.snapshot.second_turn_eligible,
        }


@dataclass(frozen=True)
class _ToolFeedbackReference:
    """ToolPlan 与 Java 工具审计的关联引用。

    这里单独建内部对象，是为了让主流程不要反复从 `governance_hints` 中读字符串键。字段来自
    AgentPlan ingestion 写回的 `agentRuntimeSessionId/runId/auditId` 和原始 `modelToolCallId`。
    """

    model_tool_call_id: str
    tool_name: str
    audit_id: str | None
    run_id: str | None
    session_id: str | None


class AgentRuntimeEventFeedbackBridge:
    """把 Java runtime-event replay 转换为控制面反馈快照补充。

    该桥接器只读取事件，不执行工具、不推进审批、不直接调用模型。它位于“控制面反馈收集器”和
    “loop policy evaluator”之间，负责让最新事件事实先更新反馈快照，然后再进入受控 loop 判断。
    """

    def __init__(self, replay_sources: tuple[RuntimeEventReplaySource, ...]) -> None:
        """初始化事件反馈桥。

        `replay_sources` 复用 4.13 的外部 replay source 抽象。当前通常只有 Java HTTP 投影源；未来如果
        增加 Kafka replay、Redis Stream 或持久化审计库，不需要修改 loop policy。
        """

        self._replay_coordinator = RuntimeEventReplayCoordinator(replay_sources)

    def augment(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        snapshot: AgentControlPlaneFeedbackSnapshot,
    ) -> AgentRuntimeEventFeedbackAugmentation:
        """使用 replay 事件增强控制面反馈快照。

        工作流程：
        1. 从 ToolPlan 中解析 `modelToolCallId -> auditId/runId/sessionId`；
        2. 按 session/run/request 构造 runtime-event 订阅请求，只回放工具状态事件；
        3. 从事件中筛选匹配 auditId 的最新终态事件；
        4. 用事件反馈补齐缺失反馈，或刷新等待审批/跳过等较旧反馈；
        5. 重算 missing/statusCounts/secondTurnEligible/recommendedActions。
        """

        references = self._references_from_plan(plan)
        if not references:
            return AgentRuntimeEventFeedbackAugmentation(
                snapshot=snapshot,
                source_names=self._replay_coordinator.external_source_names,
            )

        subscription = self._subscription_request(request, plan, references)
        collection = self._replay_coordinator.collect((), subscription)
        event_items = self._feedback_items_from_events(collection.events, references)
        merged_snapshot, derived_count, replaced_count = self._merge_snapshot(
            original=snapshot,
            references=references,
            event_items=event_items,
        )
        return AgentRuntimeEventFeedbackAugmentation(
            snapshot=merged_snapshot,
            replayed_event_count=len(collection.events),
            derived_feedback_count=derived_count,
            replaced_feedback_count=replaced_count,
            external_errors=collection.external_errors,
            source_names=self._replay_coordinator.external_source_names,
        )

    def _subscription_request(
        self,
        request: AgentRequest,
        plan: AgentPlan,
        references: tuple[_ToolFeedbackReference, ...],
    ) -> RuntimeEventSubscriptionRequest:
        """构造用于内部 loop 决策的 replay 订阅请求。

        这里使用 `service_account` 角色，是因为该调用属于 Python Runtime 到 Java 控制面的服务间查询。
        范围并不会因此放大：请求仍会带上 tenant/project/actor/session/run/request 过滤条件，Java
        端也会继续按 Header 做数据范围收口和可见性脱敏。
        """

        return RuntimeEventSubscriptionRequest(
            client_id="python-agent-loop-event-feedback",
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            roles=("service_account",),
            session_id=self._first_present(*(reference.session_id for reference in references))
            or self._request_variable(request, "agentRuntimeSessionId", "sessionId", "session_id"),
            run_id=self._first_present(*(reference.run_id for reference in references)),
            request_id=plan.request_id,
            after_sequence=0,
            event_types=(AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,),
            include_snapshot=True,
        )

    def _feedback_items_from_events(
        self,
        events: tuple[AgentRuntimeEvent, ...],
        references: tuple[_ToolFeedbackReference, ...],
    ) -> dict[str, AgentControlPlaneFeedbackItem]:
        """从 replay 事件中提取每个 modelToolCallId 的最新可用反馈。

        只把终态或等待审批状态转换为反馈。PLANNED/EXECUTING 这类非终态事件会被忽略，让原快照继续
        表达“缺少反馈/等待控制面”，避免模型提前把执行中的工具当成已有结果。
        """

        reference_by_audit_id = {
            reference.audit_id: reference
            for reference in references
            if reference.audit_id
        }
        selected_events: dict[str, AgentRuntimeEvent] = {}
        for event in events:
            audit_id = self._attribute_text(event, "auditId")
            if not audit_id or audit_id not in reference_by_audit_id:
                continue
            if self._status_from_event(event) is None:
                continue
            call_id = reference_by_audit_id[audit_id].model_tool_call_id
            previous = selected_events.get(call_id)
            if previous is None or self._event_order_key(event) >= self._event_order_key(previous):
                selected_events[call_id] = event

        return {
            call_id: self._item_from_event(event, reference_by_audit_id[self._attribute_text(event, "auditId")])
            for call_id, event in selected_events.items()
        }

    def _merge_snapshot(
        self,
        *,
        original: AgentControlPlaneFeedbackSnapshot,
        references: tuple[_ToolFeedbackReference, ...],
        event_items: dict[str, AgentControlPlaneFeedbackItem],
    ) -> tuple[AgentControlPlaneFeedbackSnapshot, int, int]:
        """把事件派生反馈合并回原控制面反馈快照。"""

        original_by_call_id = {item.model_tool_call_id: item for item in original.feedback_items}
        merged_items: list[AgentControlPlaneFeedbackItem] = []
        derived_count = 0
        replaced_count = 0
        for reference in references:
            existing = original_by_call_id.get(reference.model_tool_call_id)
            from_event = event_items.get(reference.model_tool_call_id)
            if existing is None and from_event is not None:
                merged_items.append(from_event)
                derived_count += 1
                continue
            if existing is not None and from_event is not None and self._should_replace(existing, from_event):
                merged_items.append(from_event)
                replaced_count += 1
                continue
            if existing is not None:
                merged_items.append(existing)

        expected_ids = tuple(reference.model_tool_call_id for reference in references)
        merged_by_call_id = {item.model_tool_call_id: item for item in merged_items}
        missing_ids = tuple(call_id for call_id in expected_ids if call_id not in merged_by_call_id)
        status_counts = self._status_counts(tuple(merged_items))
        second_turn_eligible = self._second_turn_eligible(
            expected_tool_call_count=len(expected_ids),
            missing_tool_call_ids=missing_ids,
            status_counts=status_counts,
        )
        return (
            AgentControlPlaneFeedbackSnapshot(
                expected_tool_call_count=len(expected_ids),
                feedback_items=tuple(merged_items),
                missing_tool_call_ids=missing_ids,
                status_counts=status_counts,
                second_turn_eligible=second_turn_eligible,
                recommended_actions=self._recommended_actions(
                    missing_ids=missing_ids,
                    status_counts=status_counts,
                    second_turn_eligible=second_turn_eligible,
                    derived_count=derived_count,
                    replaced_count=replaced_count,
                ),
            ),
            derived_count,
            replaced_count,
        )

    def _item_from_event(
        self,
        event: AgentRuntimeEvent,
        reference: _ToolFeedbackReference,
    ) -> AgentControlPlaneFeedbackItem:
        """把单条 Java 工具状态事件转换成模型反馈摘要。"""

        status = self._status_from_event(event)
        if status is None:  # pragma: no cover - 调用方已过滤，这里是防御式保护
            status = ToolExecutionFeedbackStatus.SKIPPED
        current_state = self._attribute_text(event, "currentState") or event.stage
        audit_id = reference.audit_id
        run_id = reference.run_id or event.run_id
        return AgentControlPlaneFeedbackItem(
            model_tool_call_id=reference.model_tool_call_id,
            tool_name=reference.tool_name,
            status=status,
            summary=event.message or f"{reference.tool_name} 当前状态为 {current_state}",
            result={
                "state": current_state,
                "stage": event.stage,
                "eventType": str(getattr(event.event_type, "value", event.event_type)),
                "eventDerived": True,
                "javaProjectionIdentityKey": event.attributes.get("javaProjectionIdentityKey"),
                "javaProjectionSource": event.attributes.get("javaProjectionSource"),
            },
            audit_id=audit_id,
            run_id=run_id,
            output_ref=self._output_ref(reference.session_id or event.session_id, run_id, audit_id),
            error_code=self._attribute_text(event, "errorCode"),
            error_message=event.message if status == ToolExecutionFeedbackStatus.FAILED else None,
        )

    @staticmethod
    def _references_from_plan(plan: AgentPlan) -> tuple[_ToolFeedbackReference, ...]:
        """从 AgentPlan 中读取模型调用 ID 与 Java 审计引用。"""

        references: list[_ToolFeedbackReference] = []
        for tool_plan in plan.tool_plans:
            call_id = AgentRuntimeEventFeedbackBridge._hint(tool_plan, "modelToolCallId")
            if not call_id:
                continue
            references.append(
                _ToolFeedbackReference(
                    model_tool_call_id=call_id,
                    tool_name=tool_plan.tool_name,
                    audit_id=AgentRuntimeEventFeedbackBridge._hint(
                        tool_plan,
                        "agentRuntimeAuditId",
                        "javaAuditId",
                        "auditId",
                    ),
                    run_id=AgentRuntimeEventFeedbackBridge._hint(
                        tool_plan,
                        "agentRuntimeRunId",
                        "javaRunId",
                        "runId",
                    ),
                    session_id=AgentRuntimeEventFeedbackBridge._hint(
                        tool_plan,
                        "agentRuntimeSessionId",
                        "javaSessionId",
                        "sessionId",
                    ),
                )
            )
        return tuple(references)

    @staticmethod
    def _hint(tool_plan: ToolPlan, *keys: str) -> str | None:
        """按多个兼容键读取 ToolPlan governance hint。"""

        for key in keys:
            value = tool_plan.governance_hints.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        return None

    @staticmethod
    def _status_from_event(event: AgentRuntimeEvent) -> ToolExecutionFeedbackStatus | None:
        """把 Java 工具状态映射为模型反馈状态。

        非终态 `PLANNED/EXECUTING` 返回 None，不进入反馈快照，防止二轮推理误以为工具已有结果。
        """

        state = (
            AgentRuntimeEventFeedbackBridge._attribute_text(event, "currentState")
            or AgentRuntimeEventFeedbackBridge._attribute_text(event, "state")
            or event.stage
        ).strip().upper()
        if state == "SUCCEEDED":
            return ToolExecutionFeedbackStatus.SUCCEEDED
        if state == "FAILED":
            return ToolExecutionFeedbackStatus.FAILED
        if state == "REJECTED":
            return ToolExecutionFeedbackStatus.REJECTED
        if state == "WAITING_APPROVAL":
            return ToolExecutionFeedbackStatus.WAITING_APPROVAL
        if state in {"SKIPPED", "CANCELLED", "CANCELED"}:
            return ToolExecutionFeedbackStatus.SKIPPED
        return None

    @staticmethod
    def _should_replace(
        existing: AgentControlPlaneFeedbackItem,
        from_event: AgentControlPlaneFeedbackItem,
    ) -> bool:
        """判断 replay 事件反馈是否应该刷新已有同步反馈。

        成功反馈通常包含更完整的 Java 查询结果，不应被事件摘要覆盖；等待审批、跳过或缺省状态则可以被
        事件中的成功/失败/拒绝等更明确状态刷新。
        """

        if existing.status == ToolExecutionFeedbackStatus.SUCCEEDED:
            return False
        if from_event.status in {
            ToolExecutionFeedbackStatus.SUCCEEDED,
            ToolExecutionFeedbackStatus.FAILED,
            ToolExecutionFeedbackStatus.REJECTED,
        }:
            return True
        return existing.status == ToolExecutionFeedbackStatus.SKIPPED and from_event.status == ToolExecutionFeedbackStatus.WAITING_APPROVAL

    @staticmethod
    def _status_counts(items: tuple[AgentControlPlaneFeedbackItem, ...]) -> dict[str, int]:
        """统计反馈状态数量。"""

        counts: dict[str, int] = {}
        for item in items:
            counts[item.status.value] = counts.get(item.status.value, 0) + 1
        return counts

    @staticmethod
    def _second_turn_eligible(
        *,
        expected_tool_call_count: int,
        missing_tool_call_ids: tuple[str, ...],
        status_counts: dict[str, int],
    ) -> bool:
        """判断反馈完整性是否允许进入二轮推理。"""

        if expected_tool_call_count == 0 or missing_tool_call_ids:
            return False
        return status_counts.get(ToolExecutionFeedbackStatus.WAITING_APPROVAL.value, 0) == 0

    @staticmethod
    def _recommended_actions(
        *,
        missing_ids: tuple[str, ...],
        status_counts: dict[str, int],
        second_turn_eligible: bool,
        derived_count: int,
        replaced_count: int,
    ) -> tuple[str, ...]:
        """生成增强后快照的建议动作。"""

        prefix = ()
        if derived_count or replaced_count:
            prefix = (f"已基于 runtime-event replay 补齐 {derived_count} 条、刷新 {replaced_count} 条工具反馈。",)
        if missing_ids:
            return prefix + ("仍有工具反馈缺失，继续等待 Java 执行事件或稍后按 runId replay。",)
        if status_counts.get(ToolExecutionFeedbackStatus.WAITING_APPROVAL.value, 0) > 0:
            return prefix + ("存在等待审批的工具，应停止自动 loop 并展示审批入口。",)
        if second_turn_eligible:
            return prefix + ("事件反馈已满足二轮推理基础条件，可交给 loop policy 做预算、步数和超时判断。",)
        return prefix + ("事件反馈已合并，但暂不满足自动二轮推理条件。",)

    @staticmethod
    def _attribute_text(event: AgentRuntimeEvent, key: str) -> str:
        """读取事件 attributes 中的字符串字段。"""

        value = event.attributes.get(key)
        if value is None:
            return ""
        return str(value).strip()

    @staticmethod
    def _event_order_key(event: AgentRuntimeEvent) -> tuple[int, float]:
        """生成事件新旧比较键。"""

        sequence = event.sequence if event.sequence is not None else 0
        return sequence, event.created_at.timestamp()

    @staticmethod
    def _output_ref(session_id: str | None, run_id: str | None, audit_id: str | None) -> str | None:
        """生成事件派生反馈的输出引用。"""

        if not session_id or not run_id or not audit_id:
            return None
        return f"agent-runtime://sessions/{session_id}/runs/{run_id}/tool-executions/{audit_id}/event-replay"

    @staticmethod
    def _first_present(*values: str | None) -> str | None:
        """返回第一个非空字符串。"""

        for value in values:
            if value is not None and str(value).strip():
                return str(value).strip()
        return None

    @staticmethod
    def _request_variable(request: AgentRequest, *keys: str) -> str | None:
        """从请求 variables 中读取兼容键。"""

        for key in keys:
            value = request.variables.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        return None
