"""Java Agent Runtime 工具反馈 Provider。

该模块从 `agent_runtime_tool_feedback_client.py` 拆出，是为了遵守项目“单文件尽量不超过 500 行”的解耦规范。
Client 文件只负责 Java HTTP 契约、响应解析和 DTO 映射；Provider 文件负责把 ToolPlan 与 Java audit 引用串起来，
并在批量读取结果前按需触发受控同步自动执行。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import ModelToolCall, ToolPlan
from datasmart_ai_runtime.services.agent_runtime_tool_feedback_client import (
    AgentRuntimeToolFeedbackClient,
    AgentRuntimeToolFeedbackClientError,
)
from datasmart_ai_runtime.services.model_tool_feedback_provider import (
    ModelToolExecutionFeedbackProvider,
    SimulatedModelToolExecutionFeedbackProvider,
)
from datasmart_ai_runtime.services.model_tool_result_feedback import (
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)


class JavaAgentRuntimeToolFeedbackProvider:
    """基于 Java agent-runtime 查询结果的工具反馈 Provider。

    Provider 会尝试从 `ToolPlan.governance_hints` 中读取 Java 控制面引用：
    - `agentRuntimeSessionId` / `javaSessionId` / `sessionId`
    - `agentRuntimeRunId` / `javaRunId` / `runId`
    - `agentRuntimeAuditId` / `javaAuditId` / `auditId`

    如果显式启用同步自动执行，Provider 会在批量读取结果前先调用 Java `execution-policy` 和
    `auto-execute-sync`。这个步骤完全 fail-open：Java 不可用、旧版本不支持、policy 没候选或执行失败时，
    Python 仍会继续按原路径批量查询/回退模拟反馈，避免一个控制面增强能力拖垮整轮 Agent loop。
    """

    def __init__(
        self,
        client: AgentRuntimeToolFeedbackClient,
        fallback_provider: ModelToolExecutionFeedbackProvider | None = None,
        trace_id: str | None = None,
        auto_execute_sync_enabled: bool = False,
        auto_execute_dry_run: bool = False,
        max_auto_executions: int | None = None,
    ) -> None:
        self._client = client
        self._fallback_provider = fallback_provider or SimulatedModelToolExecutionFeedbackProvider()
        self._trace_id = trace_id
        self._auto_execute_sync_enabled = auto_execute_sync_enabled
        self._auto_execute_dry_run = auto_execute_dry_run
        self._max_auto_executions = max_auto_executions

    def feedback_for(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        tool_plans: tuple[ToolPlan, ...],
    ) -> tuple[ToolExecutionFeedback, ...]:
        """优先读取 Java 真实反馈，无法读取时回退模拟反馈。"""

        plan_by_call_id = {
            str(plan.governance_hints.get("modelToolCallId")): plan
            for plan in tool_plans
            if plan.governance_hints.get("modelToolCallId")
        }
        feedback_items: list[ToolExecutionFeedback] = []
        batch_feedback = self._try_batch_feedback(tool_calls, plan_by_call_id)
        if batch_feedback is not None:
            return batch_feedback
        for tool_call in tool_calls:
            if not tool_call.call_id:
                continue
            plan = plan_by_call_id.get(tool_call.call_id)
            if plan is None:
                continue
            feedback_items.append(self._feedback_for_call(tool_call, plan))
        return tuple(feedback_items)

    def _try_auto_execute_sync_candidates(self, refs_by_call_id: dict[str, dict[str, str]]) -> None:
        """在批量结果查询前尝试触发 Java 受控同步自动执行。

        该方法只选择当前 tool_calls 已知的 auditId，避免 Provider 执行本轮模型并不关心的其他工具。
        真正的 LOW/readOnly/idempotent/requiresApproval=false 过滤仍由 Java 控制面执行，Python 这里只做范围收窄。
        """

        if not self._auto_execute_sync_enabled:
            return
        policy_method = getattr(self._client, "get_run_tool_execution_policy", None)
        execute_method = getattr(self._client, "auto_execute_sync_tools", None)
        if not callable(policy_method) or not callable(execute_method):
            return
        first_refs = next(iter(refs_by_call_id.values()))
        try:
            policy = policy_method(
                session_id=first_refs["session_id"],
                run_id=first_refs["run_id"],
                trace_id=self._trace_id,
            )
            candidate_audit_ids = tuple(
                item.audit_id
                for item in policy.items
                if item.auto_executable and item.decision == "AUTO_EXECUTABLE"
            )
            known_audit_ids = {refs["audit_id"] for refs in refs_by_call_id.values()}
            selected = tuple(audit_id for audit_id in candidate_audit_ids if audit_id in known_audit_ids)
            if not selected:
                return
            execute_method(
                session_id=first_refs["session_id"],
                run_id=first_refs["run_id"],
                audit_ids=selected,
                max_executions=self._max_auto_executions,
                dry_run=self._auto_execute_dry_run,
                trace_id=self._trace_id,
            )
        except AgentRuntimeToolFeedbackClientError:
            return

    def _try_batch_feedback(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        plan_by_call_id: dict[str, ToolPlan],
    ) -> tuple[ToolExecutionFeedback, ...] | None:
        """同一 Java Run 内优先批量查询工具反馈。"""

        batch_method = getattr(self._client, "list_run_tool_execution_feedback", None)
        if not callable(batch_method):
            return None
        refs_by_call_id: dict[str, dict[str, str]] = {}
        for tool_call in tool_calls:
            if not tool_call.call_id:
                return None
            plan = plan_by_call_id.get(tool_call.call_id)
            if plan is None:
                return None
            refs = self._resolve_refs(plan)
            if refs is None:
                return None
            refs_by_call_id[tool_call.call_id] = refs
        if not refs_by_call_id:
            return None
        session_ids = {refs["session_id"] for refs in refs_by_call_id.values()}
        run_ids = {refs["run_id"] for refs in refs_by_call_id.values()}
        if len(session_ids) != 1 or len(run_ids) != 1:
            return None
        tool_call_ids_by_audit_id = {
            refs["audit_id"]: call_id for call_id, refs in refs_by_call_id.items()
        }
        first_refs = next(iter(refs_by_call_id.values()))
        try:
            self._try_auto_execute_sync_candidates(refs_by_call_id)
            feedback_by_call_id = {
                item.tool_call_id: item
                for item in batch_method(
                    session_id=first_refs["session_id"],
                    run_id=first_refs["run_id"],
                    tool_call_ids_by_audit_id=tool_call_ids_by_audit_id,
                    trace_id=self._trace_id,
                )
            }
        except AgentRuntimeToolFeedbackClientError:
            return None
        ordered = tuple(
            feedback_by_call_id[tool_call.call_id]
            for tool_call in tool_calls
            if tool_call.call_id in feedback_by_call_id
        )
        return ordered if len(ordered) == len(tool_calls) else None

    def _feedback_for_call(self, tool_call: ModelToolCall, plan: ToolPlan) -> ToolExecutionFeedback:
        """读取单个工具调用的 Java 控制面反馈。"""

        refs = self._resolve_refs(plan)
        if refs is None:
            return self._fallback(tool_call, plan)
        try:
            return self._client.get_tool_execution_feedback(
                session_id=refs["session_id"],
                run_id=refs["run_id"],
                audit_id=refs["audit_id"],
                tool_call_id=str(tool_call.call_id),
                trace_id=self._trace_id or self._hint(plan, "traceId", "trace_id"),
            )
        except AgentRuntimeToolFeedbackClientError:
            return self._fallback(tool_call, plan)

    def _fallback(self, tool_call: ModelToolCall, plan: ToolPlan) -> ToolExecutionFeedback:
        """对单个工具调用执行模拟回退，避免一个 Java 查询失败拖垮整轮 Agent loop。"""

        feedback = self._fallback_provider.feedback_for((tool_call,), (plan,))
        if feedback:
            return feedback[0]
        return ToolExecutionFeedback(
            tool_call_id=str(tool_call.call_id),
            tool_name=plan.tool_name,
            status=ToolExecutionFeedbackStatus.SKIPPED,
            summary="未找到可用的 Java 工具执行反馈，也无法生成模拟反馈。",
        )

    def _resolve_refs(self, plan: ToolPlan) -> dict[str, str] | None:
        """从 ToolPlan 治理提示中解析 Java 控制面引用。"""

        session_id = self._hint(plan, "agentRuntimeSessionId", "javaSessionId", "sessionId", "session_id")
        run_id = self._hint(plan, "agentRuntimeRunId", "javaRunId", "runId", "run_id")
        audit_id = self._hint(plan, "agentRuntimeAuditId", "javaAuditId", "auditId", "audit_id")
        if not session_id or not run_id or not audit_id:
            return None
        return {"session_id": session_id, "run_id": run_id, "audit_id": audit_id}

    @staticmethod
    def _hint(plan: ToolPlan, *keys: str) -> str | None:
        """按多个兼容字段名读取治理提示。"""

        for key in keys:
            value = plan.governance_hints.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        return None
