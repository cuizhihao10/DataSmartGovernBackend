"""工具执行准备度评估服务。

真实 Codex / Claude Code 类 Agent 的工具调用链路通常不会在“模型提出 tool_call”之后立刻执行。
中间还需要一层执行前治理：参数是否完整、风险是否需要审批、工具是否只能生成草案、异步任务是否
应该进入队列、当前轮次是否超过并发预算、结果是否可能写入长期记忆或缓存。

本模块补齐的正是这层“执行准备度快照”。它不执行工具、不调用 Java 微服务、不写数据库，也不把
工具参数值放入可见摘要；它只把 `ToolPlan` 转换成可解释的低敏决策，供 API、Runtime Event、
Java 控制面或前端确认页消费。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Iterable, Mapping

from datasmart_ai_runtime.domain.contracts import (
    ToolExecutionMode,
    ToolParameterIssueAction,
    ToolPlan,
    ToolRiskLevel,
)


class ToolExecutionReadinessDecision(str, Enum):
    """单个工具计划的执行前决策。

    这些状态是“执行前状态”，不是工具最终状态。它们的作用是让平台在真正执行前先把风险、
    审批、澄清和队列语义讲清楚，避免用户误以为 Agent 已经完成了某个副作用动作。
    """

    READY_TO_EXECUTE = "ready_to_execute"
    DRAFT_ONLY = "draft_only"
    WAITING_APPROVAL = "waiting_approval"
    NEEDS_CLARIFICATION = "needs_clarification"
    QUEUED_ASYNC = "queued_async"
    THROTTLED = "throttled"
    BLOCKED = "blocked"


@dataclass(frozen=True)
class ToolExecutionReadinessPolicy:
    """执行准备度策略。

    字段说明：
    - `max_auto_sync_tools`：单轮最多允许多少个同步工具进入自动执行准备态，避免模型一次性提出过多
      只读调用造成后端抖动；
    - `max_async_tools`：单轮最多允许多少个异步任务入队，生产环境后续应接 Java/Redis 的真实队列预算；
    - `high_risk_requires_approval`：高风险工具是否默认进入审批；
    - `critical_risk_blocked`：CRITICAL 工具是否默认阻断，防止模型越过平台管理员策略；
    - `allow_draft_without_all_parameters`：参数不完整但校验器允许草案时，是否保留草案，而不是直接失败。
    """

    max_auto_sync_tools: int = 3
    max_async_tools: int = 2
    high_risk_requires_approval: bool = True
    critical_risk_blocked: bool = True
    allow_draft_without_all_parameters: bool = True


@dataclass(frozen=True)
class ToolExecutionReadinessItem:
    """单个工具计划的低敏准备度快照。

    注意：该对象只保存参数字段名、issue code、风险等级、执行模式、目标服务等低敏元数据。
    它不会保存 `ToolPlan.arguments` 的真实值，因为里面可能包含数据源 ID、SQL、导出路径、
    任务 payload、样例数据或其他敏感内容。
    """

    plan_index: int
    tool_name: str
    decision: ToolExecutionReadinessDecision
    executable: bool
    queue_required: bool
    requires_human_approval: bool
    risk_level: str
    execution_mode: str
    target_service: str
    argument_field_names: tuple[str, ...]
    sensitive_argument_names: tuple[str, ...]
    parameter_issue_count: int
    issue_codes: tuple[str, ...]
    reason_codes: tuple[str, ...]
    retry_hint: str
    payload_policy: str


@dataclass(frozen=True)
class ToolExecutionReadinessReport:
    """一轮 Agent 工具计划的执行准备度报告。

    该报告是面向编排器和控制面的聚合视图：它能快速回答“这轮是否存在可自动执行工具、是否需要
    用户澄清、是否有审批、是否有异步队列、是否被限流”。这比让调用方逐个读取 ToolPlan 并重复
    写判断逻辑更稳定，也能让后续 Runtime Event 和 Java projection 共用同一套统计口径。
    """

    total_count: int
    executable_count: int
    approval_required_count: int
    clarification_required_count: int
    draft_only_count: int
    queued_async_count: int
    throttled_count: int
    blocked_count: int
    items: tuple[ToolExecutionReadinessItem, ...]
    policy_metadata: Mapping[str, Any] | None = None

    @property
    def has_blocking_decision(self) -> bool:
        """判断本轮是否存在会阻断自动执行链路的问题。"""

        return self.blocked_count > 0 or self.clarification_required_count > 0 or self.throttled_count > 0

    @property
    def next_actions(self) -> tuple[str, ...]:
        """生成面向编排器或前端的下一步动作建议。"""

        actions: list[str] = []
        if self.executable_count:
            actions.append("EXECUTE_READY_TOOLS")
        if self.clarification_required_count:
            actions.append("REQUEST_USER_CLARIFICATION")
        if self.approval_required_count:
            actions.append("CREATE_OR_WAIT_APPROVAL")
        if self.queued_async_count:
            actions.append("SUBMIT_ASYNC_COMMAND")
        if self.draft_only_count:
            actions.append("SHOW_DRAFT_FOR_REVIEW")
        if self.throttled_count:
            actions.append("WAIT_FOR_TOOL_BUDGET")
        if self.blocked_count:
            actions.append("ESCALATE_TO_OPERATOR")
        return tuple(actions)


class ToolExecutionReadinessService:
    """把 ToolPlan 转换为执行前低敏治理决策。

    设计边界：
    - 输入：Python Runtime 已经生成或模型工具调用规划器已转换出的 `ToolPlan`；
    - 输出：只包含低敏元数据和决策码的 `ToolExecutionReadinessReport`；
    - 不做：真实工具执行、权限最终判定、审批单创建、Kafka 投递、数据库写入。

    这样拆分后，后续即使 Java 控制面、MCP 工具层、A2A task 或 LangGraph 节点都要消费工具计划，
    也可以先统一经过这层准备度评估，再分别进入各自的执行器或审批器。
    """

    def evaluate(
        self,
        tool_plans: Iterable[ToolPlan],
        policy: ToolExecutionReadinessPolicy | None = None,
        policy_metadata: Mapping[str, Any] | None = None,
    ) -> ToolExecutionReadinessReport:
        """评估一组工具计划的执行准备度。

        参数说明：
        - `tool_plans`：本轮 Agent 想要执行或展示的工具计划；
        - `policy`：本轮执行预算和风险策略。当前默认值适合本地学习和单元测试，生产环境后续应由
          Java 控制面按租户、角色、工作区、队列 backlog 和工具预算下发。
        """

        active_policy = policy or ToolExecutionReadinessPolicy()
        sync_ready_count = 0
        async_ready_count = 0
        items: list[ToolExecutionReadinessItem] = []

        for plan_index, plan in enumerate(tool_plans, start=1):
            item, sync_ready_count, async_ready_count = self._evaluate_one(
                plan_index=plan_index,
                plan=plan,
                policy=active_policy,
                sync_ready_count=sync_ready_count,
                async_ready_count=async_ready_count,
            )
            items.append(item)

        return ToolExecutionReadinessReport(
            total_count=len(items),
            executable_count=sum(1 for item in items if item.executable),
            approval_required_count=sum(1 for item in items if item.requires_human_approval),
            clarification_required_count=sum(
                1 for item in items if item.decision == ToolExecutionReadinessDecision.NEEDS_CLARIFICATION
            ),
            draft_only_count=sum(1 for item in items if item.decision == ToolExecutionReadinessDecision.DRAFT_ONLY),
            queued_async_count=sum(1 for item in items if item.decision == ToolExecutionReadinessDecision.QUEUED_ASYNC),
            throttled_count=sum(1 for item in items if item.decision == ToolExecutionReadinessDecision.THROTTLED),
            blocked_count=sum(1 for item in items if item.decision == ToolExecutionReadinessDecision.BLOCKED),
            items=tuple(items),
            policy_metadata=dict(policy_metadata or {}),
        )

    def _evaluate_one(
        self,
        *,
        plan_index: int,
        plan: ToolPlan,
        policy: ToolExecutionReadinessPolicy,
        sync_ready_count: int,
        async_ready_count: int,
    ) -> tuple[ToolExecutionReadinessItem, int, int]:
        """评估单个工具计划，并返回更新后的同步/异步预算计数。

        计数由调用方逐个传入和带回，是为了保持本服务无状态。无状态服务更容易在 FastAPI、
        LangGraph 节点、后台 worker 或单元测试中复用，也不会因为并发请求共享对象而出现串扰。
        """

        reason_codes: list[str] = []
        issue_codes = self._parameter_issue_codes(plan)
        decision = ToolExecutionReadinessDecision.READY_TO_EXECUTE
        executable = True
        queue_required = False
        requires_approval = bool(plan.requires_human_approval)

        if policy.critical_risk_blocked and plan.risk_level == ToolRiskLevel.CRITICAL:
            decision = ToolExecutionReadinessDecision.BLOCKED
            executable = False
            reason_codes.append("CRITICAL_RISK_BLOCKED")
        elif self._must_clarify(plan):
            decision = ToolExecutionReadinessDecision.NEEDS_CLARIFICATION
            executable = False
            reason_codes.append("PARAMETER_MUST_CLARIFY")
        elif self._can_only_create_draft(plan, policy):
            decision = ToolExecutionReadinessDecision.DRAFT_ONLY
            executable = False
            reason_codes.append("PARAMETER_DRAFT_ONLY")
        elif self._requires_approval(plan, policy):
            decision = ToolExecutionReadinessDecision.WAITING_APPROVAL
            executable = False
            requires_approval = True
            reason_codes.append("HUMAN_APPROVAL_REQUIRED")
        elif plan.execution_mode == ToolExecutionMode.DRAFT_ONLY:
            decision = ToolExecutionReadinessDecision.DRAFT_ONLY
            executable = False
            reason_codes.append("DRAFT_TOOL_REVIEW_REQUIRED")
        elif plan.execution_mode == ToolExecutionMode.ASYNC_TASK:
            if async_ready_count >= policy.max_async_tools:
                decision = ToolExecutionReadinessDecision.THROTTLED
                executable = False
                reason_codes.append("ASYNC_TOOL_BUDGET_EXCEEDED")
            else:
                decision = ToolExecutionReadinessDecision.QUEUED_ASYNC
                executable = True
                queue_required = True
                async_ready_count += 1
                reason_codes.append("ASYNC_QUEUE_REQUIRED")
        elif sync_ready_count >= policy.max_auto_sync_tools:
            decision = ToolExecutionReadinessDecision.THROTTLED
            executable = False
            reason_codes.append("SYNC_TOOL_BUDGET_EXCEEDED")
        else:
            sync_ready_count += 1
            reason_codes.append("READY_LOW_RISK_SYNC")

        return (
            ToolExecutionReadinessItem(
                plan_index=plan_index,
                tool_name=plan.tool_name,
                decision=decision,
                executable=executable,
                queue_required=queue_required,
                requires_human_approval=requires_approval,
                risk_level=self._enum_value(plan.risk_level),
                execution_mode=self._enum_value(plan.execution_mode),
                target_service=str(plan.governance_hints.get("targetService") or plan.governance_hints.get("target_service") or ""),
                argument_field_names=tuple(plan.arguments.keys()),
                sensitive_argument_names=self._sensitive_argument_names(plan),
                parameter_issue_count=len(plan.parameter_validation.issues),
                issue_codes=issue_codes,
                reason_codes=tuple(reason_codes),
                retry_hint=self._retry_hint(plan, decision),
                payload_policy="LOW_SENSITIVE_METADATA_ONLY",
            ),
            sync_ready_count,
            async_ready_count,
        )

    @staticmethod
    def _must_clarify(plan: ToolPlan) -> bool:
        """判断是否存在会阻止执行的参数问题。

        `MUST_CLARIFY` 一定需要用户补充；`CAN_FILL_FROM_CONTEXT` 则可能由 RAG、项目上下文或控制面补齐。
        但只要当前 `parameter_validation.can_execute=false`，就说明此刻还不能进入工具执行器，因此准备度
        仍归入 `NEEDS_CLARIFICATION`，后续再由编排器决定是先检索上下文还是直接追问用户。
        """

        return (
            not plan.parameter_validation.can_execute
            and any(
                issue.action in {ToolParameterIssueAction.MUST_CLARIFY, ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT}
                for issue in plan.parameter_validation.issues
            )
        )

    @staticmethod
    def _can_only_create_draft(plan: ToolPlan, policy: ToolExecutionReadinessPolicy) -> bool:
        """判断参数不完整时是否只能保留草案。"""

        if not policy.allow_draft_without_all_parameters:
            return False
        return (
            bool(plan.parameter_validation.issues)
            and not plan.parameter_validation.can_execute
            and plan.parameter_validation.can_create_draft
        )

    @staticmethod
    def _requires_approval(plan: ToolPlan, policy: ToolExecutionReadinessPolicy) -> bool:
        """判断工具计划是否应进入审批等待。"""

        if plan.requires_human_approval or plan.execution_mode == ToolExecutionMode.APPROVAL_REQUIRED:
            return True
        return policy.high_risk_requires_approval and plan.risk_level in {ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL}

    @staticmethod
    def _parameter_issue_codes(plan: ToolPlan) -> tuple[str, ...]:
        """把参数问题转换成稳定 issue code，避免事件层依赖中文文案。"""

        return tuple(
            f"PARAMETER_{issue.action.value.upper()}:{issue.parameter_name}"
            for issue in plan.parameter_validation.issues
        )

    @staticmethod
    def _sensitive_argument_names(plan: ToolPlan) -> tuple[str, ...]:
        """推导需要脱敏展示的参数字段名。

        优先使用 `governance_hints.sensitiveFields`，同时补充一些常见敏感字段名兜底。注意返回的仍是
        字段名，不是字段值。
        """

        configured = plan.governance_hints.get("sensitiveFields") or plan.governance_hints.get("sensitive_fields") or ()
        configured_names = {str(name) for name in configured if name}
        obvious_sensitive_names = {
            name
            for name in plan.arguments
            if any(token in name.lower() for token in ("password", "secret", "token", "sql", "payload", "sample"))
        }
        return tuple(sorted((configured_names | obvious_sensitive_names) & set(plan.arguments.keys())))

    @staticmethod
    def _retry_hint(plan: ToolPlan, decision: ToolExecutionReadinessDecision) -> str:
        """生成执行前重试提示。

        当前只是低敏策略摘要，不代表真正的重试调度。真实重试次数、退避和死信策略仍应由 Java
        task-management / agent-runtime 控制面根据工具类型和任务状态统一决定。
        """

        if decision == ToolExecutionReadinessDecision.QUEUED_ASYNC:
            return "RETRY_BY_TASK_QUEUE"
        if decision == ToolExecutionReadinessDecision.THROTTLED:
            return "RETRY_AFTER_BUDGET_RECOVERY"
        if plan.execution_mode == ToolExecutionMode.SYNC:
            return "NO_RUNTIME_RETRY_BEFORE_EXECUTION"
        return "WAIT_FOR_CONTROL_PLANE"

    @staticmethod
    def _enum_value(value: Any) -> str:
        """兼容 Enum 与字符串，输出稳定字符串值。"""

        return str(getattr(value, "value", value))
