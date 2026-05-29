"""Agent 控制面反馈快照收集器。

4.06 已经让 Python Runtime 可以把 `AgentPlan` 提交给 Java `agent-runtime`，并把 Java 返回的
`sessionId/runId/auditId` 写回 `ToolPlan.governance_hints`。但这一步仍然只是“建立审计引用”，
还没有回答一个产品层很关键的问题：前端、网关或 Python 自己如何知道这些工具审计当前是否已经
成功、失败、等待审批或暂无结果？

本文件提供一个轻量的“反馈快照”协作者：
- 它不触发真实工具执行，不推进 Java 状态机；
- 它只读取 ToolPlan 中已有的 Java 控制面引用，并调用可替换的反馈 Provider；
- 它把每个工具的状态整理成 API 友好的摘要，供确认页、调试面板、审计回放和后续 Agent loop 使用。

这样设计的原因是：商业化 Agent 不能在接入 Java 后立刻假设工具已经执行完成。真正的运行路径可能是：
等待审批、排队执行、执行失败、部分成功、人工取消或需要重试。先把“状态快照”做成独立契约，后续再
升级为 Kafka/WebSocket 事件驱动和受控多步 loop，会比直接把所有逻辑塞进 `api.py` 更稳。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, ModelToolCall, ToolPlan
from datasmart_ai_runtime.services.model_tool_feedback_provider import ModelToolExecutionFeedbackProvider
from datasmart_ai_runtime.services.model_tool_result_feedback import (
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)


@dataclass(frozen=True)
class AgentControlPlaneFeedbackItem:
    """单个工具计划对应的 Java 控制面反馈摘要。

    字段说明：
    - `model_tool_call_id`：模型生成的 tool_call_id，是后续 role=tool 消息和审计映射的关联键；
    - `tool_name`：DataSmart 内部工具编码，例如 `datasource.metadata.read`；
    - `status`：工具结果状态，来源于 Java feedback Provider，可能是成功、失败、等待审批或跳过；
    - `audit_id/run_id/output_ref`：Java 控制面事实引用，用于前端跳转、审计回放和后续结果查询；
    - `output_workspace_key/output_context_policy`：输出资源进入模型前的工作空间和上下文准入策略；
    - `model_context_*_paths`：字段级上下文过滤策略，决定 result 中哪些字段进入模型、删除或遮蔽；
    - `summary`：可直接展示给用户或运维人员的中文状态摘要；
    - `error_code`：失败或拒绝时的稳定错误码，便于后续告警、重试策略和产品提示区分原因。
    """

    model_tool_call_id: str
    tool_name: str
    status: ToolExecutionFeedbackStatus
    summary: str
    result: dict[str, Any] = field(default_factory=dict)
    audit_id: str | None = None
    run_id: str | None = None
    output_ref: str | None = None
    output_workspace_key: str | None = None
    output_context_policy: str = "audit_only"
    error_code: str | None = None
    error_message: str | None = None
    sensitive_fields: tuple[str, ...] = ()
    model_context_include_paths: tuple[str, ...] = ()
    model_context_exclude_paths: tuple[str, ...] = ()
    sensitive_result_paths: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 响应可直接序列化的字典。

        这里有意不暴露完整 result。原因是 result 可能包含数据源字段、质量规则候选、任务 payload
        或其他敏感业务信息。当前反馈快照只用于“状态可见性”和“下一步决策”，真正要把结果回填给
        模型时，应继续通过 `ModelToolResultFeedbackBuilder` 做字段级脱敏和消息构造。
        """

        return {
            "modelToolCallId": self.model_tool_call_id,
            "toolName": self.tool_name,
            "status": self.status.value,
            "summary": self.summary,
            "hasModelSafeResult": bool(self.result),
            "auditId": self.audit_id,
            "runId": self.run_id,
            "outputRef": self.output_ref,
            "outputWorkspaceKey": self.output_workspace_key,
            "outputContextPolicy": self.output_context_policy,
            "modelContextIncludePathCount": len(self.model_context_include_paths),
            "modelContextExcludePathCount": len(self.model_context_exclude_paths),
            "sensitiveResultPathCount": len(self.sensitive_result_paths),
            "errorCode": self.error_code,
        }

    def to_tool_feedback(self) -> ToolExecutionFeedback:
        """转换为模型二轮回填构建器可消费的工具反馈。

        API 摘要默认不暴露 `result`，但二轮模型推理需要读取 Java 控制面允许进入模型的安全结果摘要。
        因此内部对象保留 `result/sensitive_fields/error_message`，并通过该方法交给
        `ModelToolResultFeedbackBuilder` 统一做 assistant/tool message 构造和字段脱敏。
        """

        return ToolExecutionFeedback(
            tool_call_id=self.model_tool_call_id,
            tool_name=self.tool_name,
            status=self.status,
            summary=self.summary,
            result=dict(self.result),
            error_code=self.error_code,
            error_message=self.error_message,
            audit_id=self.audit_id,
            run_id=self.run_id,
            output_ref=self.output_ref,
            output_workspace_key=self.output_workspace_key,
            output_context_policy=self.output_context_policy,
            sensitive_fields=self.sensitive_fields,
            model_context_include_paths=self.model_context_include_paths,
            model_context_exclude_paths=self.model_context_exclude_paths,
            sensitive_result_paths=self.sensitive_result_paths,
        )


@dataclass(frozen=True)
class AgentControlPlaneFeedbackSnapshot:
    """一次 AgentPlan 接入后的控制面反馈快照。

    `expected_tool_call_count` 表示本次计划中有多少工具具备模型调用 ID，理论上可以和 Java 审计映射；
    `feedback_items` 表示实际拿到多少条反馈。两者不一致时，说明当前还不能安全进入严格的二轮模型
    回填，因为 OpenAI-compatible tool result message 通常要求每个 tool_call_id 都有对应结果。

    `second_turn_eligible` 不是说“已经应该自动调用模型”，而是表示从工具反馈完整性角度看，后续具备
    进入二轮推理的基本条件。真正是否进入二轮，还要结合预算、最大步数、审批等待、用户确认和租户策略。
    """

    expected_tool_call_count: int
    feedback_items: tuple[AgentControlPlaneFeedbackItem, ...]
    missing_tool_call_ids: tuple[str, ...]
    status_counts: dict[str, int]
    second_turn_eligible: bool
    recommended_actions: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 层可展示摘要。"""

        return {
            "expectedToolCallCount": self.expected_tool_call_count,
            "feedbackCount": len(self.feedback_items),
            "missingToolCallIds": self.missing_tool_call_ids,
            "statusCounts": dict(self.status_counts),
            "secondTurnEligible": self.second_turn_eligible,
            "recommendedActions": self.recommended_actions,
            "items": tuple(item.to_summary() for item in self.feedback_items),
        }


class AgentControlPlaneFeedbackCollector:
    """收集 Java 控制面工具反馈快照。

    该类只依赖 `ModelToolExecutionFeedbackProvider` 协议，因此可以复用现有的
    `JavaAgentRuntimeToolFeedbackProvider`，也可以在测试中注入 fake provider。它把“如何查 Java”
    和“如何给 API 展示反馈摘要”拆开，避免 API 层直接理解 session/run/audit 查询细节。
    """

    def __init__(self, feedback_provider: ModelToolExecutionFeedbackProvider) -> None:
        self._feedback_provider = feedback_provider

    def collect(self, plan: AgentPlan) -> AgentControlPlaneFeedbackSnapshot:
        """根据 AgentPlan 中的 ToolPlan 控制面引用收集反馈。

        工作流程：
        1. 从 ToolPlan governance hints 中读取 `modelToolCallId`，构造轻量 `ModelToolCall`；
        2. 调用反馈 Provider 查询 Java 工具状态；
        3. 统计成功、失败、等待审批等状态数量；
        4. 判断是否具备进入二轮模型推理的反馈完整性条件；
        5. 输出面向 API、前端和后续编排器的摘要。
        """

        tool_calls = self._tool_calls_from_plan(plan)
        if not tool_calls:
            return self._empty_snapshot()

        feedback_items = self._feedback_provider.feedback_for(tool_calls, plan.tool_plans)
        item_by_call_id = {
            item.tool_call_id: AgentControlPlaneFeedbackItem(
                model_tool_call_id=item.tool_call_id,
                tool_name=item.tool_name,
                status=item.status,
                summary=item.summary,
                result=dict(item.result),
                audit_id=item.audit_id,
                run_id=item.run_id,
                output_ref=item.output_ref,
                output_workspace_key=item.output_workspace_key,
                output_context_policy=item.output_context_policy,
                error_code=item.error_code,
                error_message=item.error_message,
                sensitive_fields=item.sensitive_fields,
                model_context_include_paths=item.model_context_include_paths,
                model_context_exclude_paths=item.model_context_exclude_paths,
                sensitive_result_paths=item.sensitive_result_paths,
            )
            for item in feedback_items
        }
        expected_ids = tuple(call.call_id for call in tool_calls if call.call_id)
        missing_ids = tuple(call_id for call_id in expected_ids if call_id not in item_by_call_id)
        ordered_items = tuple(item_by_call_id[call_id] for call_id in expected_ids if call_id in item_by_call_id)
        status_counts = self._status_counts(ordered_items)
        second_turn_eligible = self._second_turn_eligible(
            expected_tool_call_count=len(expected_ids),
            missing_tool_call_ids=missing_ids,
            status_counts=status_counts,
        )
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=len(expected_ids),
            feedback_items=ordered_items,
            missing_tool_call_ids=missing_ids,
            status_counts=status_counts,
            second_turn_eligible=second_turn_eligible,
            recommended_actions=self._recommended_actions(
                expected_count=len(expected_ids),
                missing_ids=missing_ids,
                status_counts=status_counts,
                second_turn_eligible=second_turn_eligible,
            ),
        )

    @staticmethod
    def _tool_calls_from_plan(plan: AgentPlan) -> tuple[ModelToolCall, ...]:
        """从 ToolPlan 还原最小模型工具调用引用。

        这里不是重新相信模型输出，而是为了复用现有反馈 Provider 的 `feedback_for(tool_calls, tool_plans)`
        协议。`arguments` 使用当前 ToolPlan 中已通过平台治理的参数，而不是原始模型字符串；这样后续
        日志和诊断看到的是治理后的计划，不是未经校验的原始幻觉参数。
        """

        calls: list[ModelToolCall] = []
        for tool_plan in plan.tool_plans:
            call_id = AgentControlPlaneFeedbackCollector._model_tool_call_id(tool_plan)
            if not call_id:
                continue
            calls.append(
                ModelToolCall(
                    call_id=call_id,
                    name=tool_plan.tool_name,
                    arguments=json.dumps(tool_plan.arguments, ensure_ascii=False, sort_keys=True),
                    raw_call={"source": "agent_plan_control_plane_feedback"},
                )
            )
        return tuple(calls)

    @staticmethod
    def _model_tool_call_id(tool_plan: ToolPlan) -> str | None:
        """读取 ToolPlan 中的模型调用 ID。"""

        value = tool_plan.governance_hints.get("modelToolCallId")
        if value is None or not str(value).strip():
            return None
        return str(value).strip()

    @staticmethod
    def _status_counts(items: tuple[AgentControlPlaneFeedbackItem, ...]) -> dict[str, int]:
        """统计各类工具反馈状态数量。"""

        counts: dict[str, int] = {}
        for item in items:
            key = item.status.value
            counts[key] = counts.get(key, 0) + 1
        return counts

    @staticmethod
    def _second_turn_eligible(
        *,
        expected_tool_call_count: int,
        missing_tool_call_ids: tuple[str, ...],
        status_counts: dict[str, int],
    ) -> bool:
        """判断是否具备进入二轮模型推理的基础条件。

        失败、拒绝和跳过都可以作为“工具结果语义”回填给模型，让模型解释原因或建议下一步；但等待审批
        表示真实工具尚未完成，此时不应让模型假装拿到了结果。因此这里把 `waiting_approval` 视为阻断项。
        """

        if expected_tool_call_count == 0 or missing_tool_call_ids:
            return False
        return status_counts.get(ToolExecutionFeedbackStatus.WAITING_APPROVAL.value, 0) == 0

    @staticmethod
    def _recommended_actions(
        *,
        expected_count: int,
        missing_ids: tuple[str, ...],
        status_counts: dict[str, int],
        second_turn_eligible: bool,
    ) -> tuple[str, ...]:
        """生成面向产品和运维的下一步建议。"""

        if expected_count == 0:
            return ("当前计划没有模型工具调用 ID，暂不需要查询 Java 控制面反馈。",)
        if missing_ids:
            return (
                "存在工具调用尚未拿到 Java 控制面反馈，建议等待执行事件或稍后按 runId replay。",
                "如果长期缺失，需要检查 plan ingestion 是否成功写回 auditId，以及 Java 结果查询接口是否可用。",
            )
        if status_counts.get(ToolExecutionFeedbackStatus.WAITING_APPROVAL.value, 0) > 0:
            return (
                "存在等待审批的工具，当前不应触发自动二轮推理或继续执行后续高风险动作。",
                "建议前端展示审批入口，并由项目负责人、审批人或审计员处理。",
            )
        if status_counts.get(ToolExecutionFeedbackStatus.FAILED.value, 0) > 0:
            return (
                "存在执行失败的工具，可进入二轮模型解释失败原因，但真实重试仍应由 Java 控制面按策略触发。",
            )
        if second_turn_eligible:
            return ("工具反馈已完整，后续可以在受控预算和最大步数限制下进入二轮模型推理。",)
        return ("控制面反馈已返回，但暂不满足自动二轮推理条件，建议保留为审计和前端诊断信息。",)

    @staticmethod
    def _empty_snapshot() -> AgentControlPlaneFeedbackSnapshot:
        """构造无工具调用场景的空快照。"""

        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=0,
            feedback_items=(),
            missing_tool_call_ids=(),
            status_counts={},
            second_turn_eligible=False,
            recommended_actions=("当前计划没有可映射到 Java auditId 的模型工具调用。",),
        )
