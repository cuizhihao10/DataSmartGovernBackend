"""模型工具调用的执行反馈提供器。

真实商业化 Agent loop 中，模型提出工具调用后，Python Runtime 不应该直接执行工具。DataSmart 的边界是：
Python 负责模型推理和计划编排，Java `agent-runtime` 负责审批、审计、幂等和真实业务工具执行。

本文件先定义“工具执行反馈 provider”抽象，并提供一个受控模拟实现：
- 当前阶段用于把多步 Agent loop 的消息链路跑通；
- 后续可以替换为 Java HTTP client、Kafka request/reply、事件回放查询或工作流引擎适配器；
- 调用方只消费 `ToolExecutionFeedback`，不关心反馈来自模拟、Java 还是其他执行控制面。
"""

from __future__ import annotations

from typing import Protocol

from datasmart_ai_runtime.domain.contracts import ModelToolCall, ToolPlan
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)


class ModelToolExecutionFeedbackProvider(Protocol):
    """工具执行反馈提供器协议。

    该协议描述“模型工具调用经过平台执行或治理后，如何得到可回填模型的摘要”。它刻意不暴露真实
    HTTP、Kafka 或数据库细节，原因是这些属于集成方式，而不是 Agent loop 的核心语义。
    """

    def feedback_for(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        tool_plans: tuple[ToolPlan, ...],
    ) -> tuple[ToolExecutionFeedback, ...]:
        """为一组模型工具调用生成执行反馈。"""


class SimulatedModelToolExecutionFeedbackProvider:
    """受控模拟工具执行反馈提供器。

    当前模拟实现不是为了假装工具已经真实执行，而是为了固定多步 loop 的协议形状。它遵守以下规则：
    - 需要人工审批的工具返回 `WAITING_APPROVAL`，不伪造成功结果；
    - 参数不完整的工具返回 `REJECTED`，提醒模型不能继续假设工具成功；
    - 低风险且参数完整的工具返回 `SUCCEEDED`，但结果只包含摘要、字段名和模拟引用，不包含真实数据；
    - 敏感字段只作为 `sensitive_fields` 传给回填构建器，由构建器脱敏。
    """

    def feedback_for(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        tool_plans: tuple[ToolPlan, ...],
    ) -> tuple[ToolExecutionFeedback, ...]:
        """根据受治理 ToolPlan 生成模拟执行反馈。

        这里按 `modelToolCallId` 将 ToolPlan 与原始 ModelToolCall 对齐。若某个 tool_call 没有对应计划，
        说明它在治理阶段被拒绝或没有生成可执行候选，不应继续模拟执行。
        """

        plan_by_call_id = {
            str(plan.governance_hints.get("modelToolCallId")): plan
            for plan in tool_plans
            if plan.governance_hints.get("modelToolCallId")
        }
        feedback_items: list[ToolExecutionFeedback] = []
        for tool_call in tool_calls:
            if not tool_call.call_id:
                continue
            plan = plan_by_call_id.get(tool_call.call_id)
            if plan is None:
                continue
            feedback_items.append(self._feedback_for_plan(tool_call, plan))
        return tuple(feedback_items)

    def _feedback_for_plan(self, tool_call: ModelToolCall, plan: ToolPlan) -> ToolExecutionFeedback:
        """把单个 ToolPlan 转换为模拟执行反馈。"""

        if plan.requires_human_approval:
            return self._waiting_approval_feedback(tool_call, plan)
        if not plan.parameter_validation.can_execute:
            return self._parameter_rejected_feedback(tool_call, plan)
        return self._succeeded_feedback(tool_call, plan)

    def _succeeded_feedback(self, tool_call: ModelToolCall, plan: ToolPlan) -> ToolExecutionFeedback:
        """生成低风险参数完整工具的模拟成功反馈。"""

        return ToolExecutionFeedback(
            tool_call_id=str(tool_call.call_id),
            tool_name=plan.tool_name,
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary=f"模拟 Java agent-runtime 已完成 `{plan.tool_name}` 的受控工具执行。",
            result={
                "toolName": plan.tool_name,
                "riskLevel": getattr(plan.risk_level, "value", plan.risk_level),
                "executionMode": getattr(plan.execution_mode, "value", plan.execution_mode),
                "argumentFieldNames": tuple(plan.arguments.keys()),
                "parameterIssueCount": len(plan.parameter_validation.issues),
            },
            audit_id=f"simulated-audit-{tool_call.call_id}",
            run_id=f"simulated-run-{tool_call.call_id}",
            output_ref=f"agent-runtime://tool-results/{tool_call.call_id}",
            output_workspace_key=self._workspace_key(plan),
            output_context_policy="model_summary_allowed",
            sensitive_fields=self._tuple_hint(plan, "sensitiveFields", "sensitive_fields"),
            model_context_include_paths=self._tuple_hint(
                plan,
                "modelContextIncludePaths",
                "model_context_include_paths",
            ),
            model_context_exclude_paths=self._tuple_hint(
                plan,
                "modelContextExcludePaths",
                "model_context_exclude_paths",
            ),
            sensitive_result_paths=self._tuple_hint(
                plan,
                "sensitiveResultPaths",
                "sensitive_result_paths",
            ),
        )

    def _waiting_approval_feedback(self, tool_call: ModelToolCall, plan: ToolPlan) -> ToolExecutionFeedback:
        """生成需审批工具的等待反馈。

        审批等待不能被模拟成成功，否则模型会基于不存在的执行结果继续推理，破坏合规边界。
        """

        return ToolExecutionFeedback(
            tool_call_id=str(tool_call.call_id),
            tool_name=plan.tool_name,
            status=ToolExecutionFeedbackStatus.WAITING_APPROVAL,
            summary=f"`{plan.tool_name}` 需要人工审批，当前未执行真实业务动作。",
            audit_id=f"simulated-audit-{tool_call.call_id}",
            run_id=f"simulated-run-{tool_call.call_id}",
            output_ref=f"agent-runtime://waiting-approval/{tool_call.call_id}",
            output_workspace_key=self._workspace_key(plan),
            output_context_policy="audit_only",
            sensitive_fields=self._tuple_hint(plan, "sensitiveFields", "sensitive_fields"),
            model_context_include_paths=self._tuple_hint(plan, "modelContextIncludePaths", "model_context_include_paths"),
            model_context_exclude_paths=self._tuple_hint(plan, "modelContextExcludePaths", "model_context_exclude_paths"),
            sensitive_result_paths=self._tuple_hint(plan, "sensitiveResultPaths", "sensitive_result_paths"),
        )

    def _parameter_rejected_feedback(self, tool_call: ModelToolCall, plan: ToolPlan) -> ToolExecutionFeedback:
        """生成参数不完整工具的拒绝反馈。"""

        missing_fields = tuple(issue.parameter_name for issue in plan.parameter_validation.issues)
        return ToolExecutionFeedback(
            tool_call_id=str(tool_call.call_id),
            tool_name=plan.tool_name,
            status=ToolExecutionFeedbackStatus.REJECTED,
            summary=f"`{plan.tool_name}` 参数不完整，平台拒绝进入真实执行。",
            error_code="TOOL_PARAMETERS_INCOMPLETE",
            error_message=f"缺失或不满足要求的参数：{', '.join(missing_fields) or 'unknown'}",
            result={"missingFields": missing_fields},
            audit_id=f"simulated-audit-{tool_call.call_id}",
            run_id=f"simulated-run-{tool_call.call_id}",
            output_ref=f"agent-runtime://rejected/{tool_call.call_id}",
            output_workspace_key=self._workspace_key(plan),
            output_context_policy="audit_only",
            sensitive_fields=self._tuple_hint(plan, "sensitiveFields", "sensitive_fields"),
            model_context_include_paths=self._tuple_hint(plan, "modelContextIncludePaths", "model_context_include_paths"),
            model_context_exclude_paths=self._tuple_hint(plan, "modelContextExcludePaths", "model_context_exclude_paths"),
            sensitive_result_paths=self._tuple_hint(plan, "sensitiveResultPaths", "sensitive_result_paths"),
        )

    @staticmethod
    def _workspace_key(plan: ToolPlan) -> str | None:
        """读取 ToolPlan 中由工作空间治理层写入的 workspaceKey。

        模拟反馈也要携带 workspaceKey，原因是它参与的是同一套二轮推理协议。即使当前并没有真实调用
        Java 工具，模型上下文过滤也应该提前验证“工具结果属于当前工作空间”这一生产语义。
        """

        value = plan.governance_hints.get("workspaceKey")
        return str(value).strip() if value is not None and str(value).strip() else None

    @staticmethod
    def _tuple_hint(plan: ToolPlan, *keys: str) -> tuple[str, ...]:
        """按多个兼容字段名读取路径列表治理提示。"""

        for key in keys:
            value = plan.governance_hints.get(key)
            if value is None:
                continue
            if isinstance(value, str):
                return (value,)
            return tuple(str(item) for item in value if str(item).strip())
        return ()
