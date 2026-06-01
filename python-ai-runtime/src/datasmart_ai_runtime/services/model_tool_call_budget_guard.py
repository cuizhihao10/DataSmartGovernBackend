"""模型工具调用预算与批量准入守卫。

Codex、Claude Code、LangGraph、MCP 类 Agent 的工具调用能力越强，越需要在“模型提出工具调用”
和“平台生成可执行计划”之间增加预算闸门。原因很直接：模型一次响应可能提出很多工具调用，参数体积
可能很大，甚至可能混入多个高风险或长耗时工具。如果只做工具是否存在、参数是否为 JSON，就会把执行
压力、审批压力和成本压力放大到 Java 控制面或下游微服务。

本文件提供一个轻量但可扩展的 guard：
- 限制单轮模型工具调用总数，防止 Agent loop 一次制造过多动作；
- 限制可自动执行工具数量，避免低风险工具批量自动化压垮下游；
- 限制单个/总 arguments 体积，避免 prompt 注入、大样本数据或异常 JSON 进入事件和审批链；
- 限制高风险工具数量，把复杂副作用收口到 human-in-the-loop 或重新规划。

它不替代 `ModelToolCallPlanner` 的工具注册表校验，也不替代 Java permission-admin 的最终授权。
更准确地说，它是智能网关的第一层“工具调用预算治理”。
"""

from __future__ import annotations

from dataclasses import dataclass, replace

from datasmart_ai_runtime.domain.contracts import ToolExecutionMode, ToolRiskLevel
from datasmart_ai_runtime.services.model_tool_call_planner import (
    ModelToolCallCandidate,
    ModelToolCallGovernanceIssue,
    ModelToolCallPlanningReport,
)


@dataclass(frozen=True)
class ModelToolCallBudgetPolicy:
    """单轮模型工具调用预算策略。

    字段说明：
    - `max_proposed_tool_calls`：模型一次响应最多允许提出多少个工具调用候选；
    - `max_auto_executable_tool_calls`：不需要人工审批、可自动继续推进的工具数量上限；
    - `max_high_risk_tool_calls`：HIGH/CRITICAL 工具数量上限，防止一次响应聚集过多高风险动作；
    - `max_single_arguments_bytes`：单个工具 arguments 原文的最大字节数；
    - `max_total_arguments_bytes`：本轮全部 arguments 原文的累计最大字节数。

    当前默认值偏保守，适合企业数据治理平台的早期生产化路线。后续可按租户套餐、项目等级、
    actor 角色、工具成本权重和当前 worker backlog 动态生成策略。
    """

    max_proposed_tool_calls: int = 8
    max_auto_executable_tool_calls: int = 3
    max_high_risk_tool_calls: int = 1
    max_single_arguments_bytes: int = 4096
    max_total_arguments_bytes: int = 16384


@dataclass(frozen=True)
class ModelToolCallBudgetGuardReport:
    """工具调用预算守卫评估结果。"""

    original_report: ModelToolCallPlanningReport
    guarded_report: ModelToolCallPlanningReport
    policy: ModelToolCallBudgetPolicy
    proposed_count: int
    accepted_count_before_guard: int
    accepted_count_after_guard: int
    auto_executable_count_before_guard: int
    high_risk_count_before_guard: int
    total_arguments_bytes: int

    @property
    def allowed(self) -> bool:
        """是否没有新增任何阻断性预算问题。"""

        return self.accepted_count_before_guard == self.accepted_count_after_guard

    @property
    def budget_issue_codes(self) -> tuple[str, ...]:
        """返回本次 guard 新增的预算问题码。"""

        return tuple(
            issue.code
            for candidate in self.guarded_report.candidates
            for issue in candidate.issues
            if issue.code.startswith("MODEL_TOOL_CALL_BUDGET_")
        )

    def to_summary(self) -> dict[str, object]:
        """转换为可进入诊断接口、runtime event 或测试断言的低敏摘要。"""

        return {
            "allowed": self.allowed,
            "proposedCount": self.proposed_count,
            "acceptedCountBeforeGuard": self.accepted_count_before_guard,
            "acceptedCountAfterGuard": self.accepted_count_after_guard,
            "autoExecutableCountBeforeGuard": self.auto_executable_count_before_guard,
            "highRiskCountBeforeGuard": self.high_risk_count_before_guard,
            "totalArgumentsBytes": self.total_arguments_bytes,
            "budgetIssueCodes": self.budget_issue_codes,
            "policy": {
                "maxProposedToolCalls": self.policy.max_proposed_tool_calls,
                "maxAutoExecutableToolCalls": self.policy.max_auto_executable_tool_calls,
                "maxHighRiskToolCalls": self.policy.max_high_risk_tool_calls,
                "maxSingleArgumentsBytes": self.policy.max_single_arguments_bytes,
                "maxTotalArgumentsBytes": self.policy.max_total_arguments_bytes,
            },
        }


class ModelToolCallBudgetGuard:
    """对模型工具调用规划报告施加批量、体积和风险预算。

    该类接收 `ModelToolCallPlanningReport`，返回一个新的 guarded report。它不会修改原对象，
    因为规划报告通常也会进入审计、事件和调试面板；保留 before/after 能让我们解释“模型原本想做什么”
    以及“智能网关为什么削减或阻断其中一部分”。
    """

    def __init__(self, policy: ModelToolCallBudgetPolicy | None = None) -> None:
        self._policy = policy or ModelToolCallBudgetPolicy()

    def evaluate(self, report: ModelToolCallPlanningReport) -> ModelToolCallBudgetGuardReport:
        """评估并返回带预算问题的新报告。"""

        guarded_candidates: list[ModelToolCallCandidate] = []
        accepted_before = len(report.accepted_tool_plans)
        total_argument_bytes = sum(_argument_bytes(candidate) for candidate in report.candidates)
        auto_seen = 0
        high_risk_seen = 0
        cumulative_argument_bytes = 0
        for index, candidate in enumerate(report.candidates, start=1):
            issues = list(candidate.issues)
            cumulative_argument_bytes += _argument_bytes(candidate)
            issues.extend(self._candidate_budget_issues(
                candidate=candidate,
                index=index,
                auto_seen=auto_seen,
                high_risk_seen=high_risk_seen,
                cumulative_argument_bytes=cumulative_argument_bytes,
                total_argument_bytes=total_argument_bytes,
            ))
            if candidate.accepted and _is_auto_executable(candidate):
                auto_seen += 1
            if candidate.accepted and _is_high_risk(candidate):
                high_risk_seen += 1
            guarded_candidates.append(replace(candidate, issues=tuple(issues)))

        guarded_report = ModelToolCallPlanningReport(candidates=tuple(guarded_candidates))
        return ModelToolCallBudgetGuardReport(
            original_report=report,
            guarded_report=guarded_report,
            policy=self._policy,
            proposed_count=len(report.candidates),
            accepted_count_before_guard=accepted_before,
            accepted_count_after_guard=len(guarded_report.accepted_tool_plans),
            auto_executable_count_before_guard=sum(1 for item in report.candidates if item.accepted and _is_auto_executable(item)),
            high_risk_count_before_guard=sum(1 for item in report.candidates if item.accepted and _is_high_risk(item)),
            total_arguments_bytes=total_argument_bytes,
        )

    def _candidate_budget_issues(
        self,
        *,
        candidate: ModelToolCallCandidate,
        index: int,
        auto_seen: int,
        high_risk_seen: int,
        cumulative_argument_bytes: int,
        total_argument_bytes: int,
    ) -> tuple[ModelToolCallGovernanceIssue, ...]:
        """为单个候选生成预算问题。

        预算问题都设置为 blocking。这样 guarded report 的 `accepted_tool_plans` 会自然排除超预算候选，
        而不是让调用方额外记一套“哪些候选虽然 accepted 但不能执行”的状态。
        """

        issues: list[ModelToolCallGovernanceIssue] = []
        tool_name = candidate.resolved_tool_name
        if index > self._policy.max_proposed_tool_calls:
            issues.append(_issue(
                tool_name,
                "MODEL_TOOL_CALL_BUDGET_PROPOSED_COUNT_EXCEEDED",
                f"模型本轮提出的工具调用数量超过上限 {self._policy.max_proposed_tool_calls}，请拆分任务或重新规划。",
            ))
        if _argument_bytes(candidate) > self._policy.max_single_arguments_bytes:
            issues.append(_issue(
                tool_name,
                "MODEL_TOOL_CALL_BUDGET_SINGLE_ARGUMENTS_TOO_LARGE",
                f"单个工具 arguments 超过 {self._policy.max_single_arguments_bytes} 字节，禁止直接进入工具计划。",
            ))
        if cumulative_argument_bytes > self._policy.max_total_arguments_bytes or total_argument_bytes > self._policy.max_total_arguments_bytes:
            issues.append(_issue(
                tool_name,
                "MODEL_TOOL_CALL_BUDGET_TOTAL_ARGUMENTS_TOO_LARGE",
                f"本轮工具 arguments 总体积超过 {self._policy.max_total_arguments_bytes} 字节，需缩小上下文或改走资源引用。",
            ))
        if candidate.accepted and _is_auto_executable(candidate) and auto_seen >= self._policy.max_auto_executable_tool_calls:
            issues.append(_issue(
                tool_name,
                "MODEL_TOOL_CALL_BUDGET_AUTO_EXECUTABLE_COUNT_EXCEEDED",
                f"可自动推进的工具数量超过上限 {self._policy.max_auto_executable_tool_calls}，后续动作应拆批或进入审批。",
            ))
        if candidate.accepted and _is_high_risk(candidate) and high_risk_seen >= self._policy.max_high_risk_tool_calls:
            issues.append(_issue(
                tool_name,
                "MODEL_TOOL_CALL_BUDGET_HIGH_RISK_COUNT_EXCEEDED",
                f"高风险工具数量超过上限 {self._policy.max_high_risk_tool_calls}，请保留最关键动作并重新规划。",
            ))
        return tuple(issues)


def _issue(tool_name: str, code: str, message: str) -> ModelToolCallGovernanceIssue:
    """创建阻断性预算问题。"""

    return ModelToolCallGovernanceIssue(tool_name=tool_name, code=code, message=message, blocking=True)


def _argument_bytes(candidate: ModelToolCallCandidate) -> int:
    """计算模型原始 arguments 的 UTF-8 字节数。"""

    return len((candidate.source_call.arguments or "").encode("utf-8"))


def _is_auto_executable(candidate: ModelToolCallCandidate) -> bool:
    """判断候选是否看起来可以自动继续推进。

    这里并不代表工具会被 Python 直接执行；真实执行仍由 Java 控制面负责。该判断只用于预算守卫：
    如果候选不需要人工审批、不是 DRAFT_ONLY、不是 APPROVAL_REQUIRED，就应计入自动推进数量。
    """

    plan = candidate.tool_plan
    return bool(
        plan
        and candidate.accepted
        and not plan.requires_human_approval
        and plan.execution_mode not in {ToolExecutionMode.DRAFT_ONLY, ToolExecutionMode.APPROVAL_REQUIRED}
    )


def _is_high_risk(candidate: ModelToolCallCandidate) -> bool:
    """判断候选是否为高风险工具。"""

    plan = candidate.tool_plan
    return bool(plan and plan.risk_level in {ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL})
