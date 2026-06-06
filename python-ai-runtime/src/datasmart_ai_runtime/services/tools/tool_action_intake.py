"""工具动作意图统一入口。

本模块用于把多种外部或模型侧的“想调用工具”信号收敛到同一个 DataSmart 控制面语义里。
它刻意不执行工具、不写 outbox、不创建审批单、不启动 worker，也不读取 artifact 正文。原因是：

- 模型 `tool_call`、MCP `tools/call`、A2A task/action 看起来都像“工具调用”，但安全语义完全不同；
- 商业化 Agent Host 必须先把这些入口归一为可治理事实，再进入 readiness、审批、澄清、outbox、
  worker receipt 和审计链路；
- 如果每种协议入口各自直接调用下游服务，就会绕过 permission-admin、限流、幂等和低敏事件边界。

因此本模块只负责“intake”，也就是：
1. 识别来源；
2. 对模型/MCP 工具调用尝试生成 `ToolPlan`；
3. 对 A2A task/action 生成低敏控制面决策；
4. 输出低敏摘要，告诉上层下一跳应该进入 readiness graph、A2A 控制面诊断，还是在执行前直接拒绝。

注意：`ToolPlan` 内部仍会保留参数字典，因为后续参数校验和 readiness 判断需要真实字段值；
但 `to_low_sensitive_summary()` 永远只返回参数名、计数、状态、issue code 等摘要，不返回参数值、
SQL、prompt、样本数据、模型输出、凭证或内部 endpoint。
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.contracts import ModelToolCall, ToolDefinition, ToolPlan
from datasmart_ai_runtime.domain.protocols import AgentTaskPlanningDecision, AgentTaskPlanningMode
from datasmart_ai_runtime.services.agent_gateway import A2aTaskPlanningAdapter
from datasmart_ai_runtime.services.agent_gateway.a2a_task_mapping_support import count_forbidden_fields
from datasmart_ai_runtime.services.model_gateway.model_tool_call_planner import (
    ModelToolCallGovernanceIssue,
    ModelToolCallPlanner,
    ModelToolCallPlanningReport,
)


class ToolActionIntakeSource(str, Enum):
    """工具动作意图来源。

    统一来源枚举的目的，是让后续 readiness event、timeline、MCP/A2A 网关和前端确认页都能清楚知道：
    当前 `ToolPlan` 是模型自己提出的，还是外部 MCP 客户端请求的，还是来自 A2A task 控制面事实。
    这会影响权限说明、幂等键、审批文案、审计归因和异常处理策略。
    """

    MODEL_TOOL_CALL = "MODEL_TOOL_CALL"
    MCP_TOOLS_CALL = "MCP_TOOLS_CALL"
    A2A_TASK_ACTION = "A2A_TASK_ACTION"


class ToolActionIntakeBoundary(str, Enum):
    """intake 之后的下一跳边界。

    - `TOOL_PLAN_READINESS_GRAPH`：已经归一出 ToolPlan，可继续进入 readiness/report/graph；
    - `A2A_TASK_CONTROL_PLANE_DECISION`：这是 A2A task 控制面事实，不应伪装成普通 ToolPlan；
    - `REJECTED_BEFORE_READINESS`：入口在生成 ToolPlan 前就被阻断，例如未知工具、未暴露工具或非法参数。
    """

    TOOL_PLAN_READINESS_GRAPH = "TOOL_PLAN_READINESS_GRAPH"
    A2A_TASK_CONTROL_PLANE_DECISION = "A2A_TASK_CONTROL_PLANE_DECISION"
    REJECTED_BEFORE_READINESS = "REJECTED_BEFORE_READINESS"


@dataclass(frozen=True)
class ToolActionIntakeIssue:
    """intake 阶段的治理问题。

    `message` 当前保留中文学习说明，但低敏摘要默认只输出 `code` 和 `blocking`。
    这样可以避免未来某个协议适配器把包含用户输入的自由文本错误塞进 message 后，被 summary 透传出去。
    """

    code: str
    message: str
    blocking: bool = True
    tool_name: str = ""

    @classmethod
    def from_model_issue(cls, issue: ModelToolCallGovernanceIssue) -> "ToolActionIntakeIssue":
        """把模型工具调用规划器的 issue 转换为 intake 统一 issue。"""

        return cls(
            code=issue.code,
            message=issue.message,
            blocking=issue.blocking,
            tool_name=issue.tool_name,
        )


@dataclass(frozen=True)
class ToolActionIntakeItem:
    """单个工具动作意图的归一化结果。

    `tool_plan` 是内部强类型对象，只给后续 readiness/graph 使用；`to_low_sensitive_summary()` 不会直接
    `asdict(tool_plan)`，避免把 arguments、target endpoint 或其他治理 hint 值泄露给 HTTP 响应和事件。
    """

    source: ToolActionIntakeSource
    boundary: ToolActionIntakeBoundary
    accepted: bool
    source_call_id: str = ""
    tool_name: str = ""
    tool_plan: ToolPlan | None = None
    issues: tuple[ToolActionIntakeIssue, ...] = ()
    a2a_decision: AgentTaskPlanningDecision | None = None
    sensitive_field_ignored_count: int = 0

    def to_low_sensitive_summary(self) -> dict[str, Any]:
        """输出可进入 API、runtime event 或 timeline 的低敏摘要。"""

        summary: dict[str, Any] = {
            "source": self.source.value,
            "boundary": self.boundary.value,
            "accepted": self.accepted,
            "sourceCallId": self.source_call_id,
            "toolName": self.tool_name,
            "readinessCandidate": self.tool_plan is not None and self.boundary == ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH,
            "issueCodes": tuple(issue.code for issue in self.issues),
            "blockingIssueCount": sum(1 for issue in self.issues if issue.blocking),
            "sensitiveFieldIgnoredCount": self.sensitive_field_ignored_count,
        }
        if self.tool_plan is not None:
            summary["toolPlan"] = self._tool_plan_summary(self.tool_plan)
        if self.a2a_decision is not None:
            summary["a2aDecision"] = self.a2a_decision.to_summary()
        return summary

    @staticmethod
    def _tool_plan_summary(tool_plan: ToolPlan) -> dict[str, Any]:
        """把 ToolPlan 压缩为低敏摘要。

        这里故意只返回参数名和治理枚举，不返回 `arguments` 的值。比如 `datasourceId` 字段名可以帮助
        前端说明“还需要选择数据源”，但真实 `ds-xxx` 值不应该进入事件或公共摘要。
        """

        return {
            "toolName": tool_plan.tool_name,
            "riskLevel": tool_plan.risk_level.value,
            "executionMode": tool_plan.execution_mode.value,
            "requiresHumanApproval": tool_plan.requires_human_approval,
            "parameterCanExecute": tool_plan.parameter_validation.can_execute,
            "parameterIssueCount": len(tool_plan.parameter_validation.issues),
            "argumentNames": tuple(sorted(tool_plan.arguments.keys())),
            "sensitiveArgumentNames": tuple(tool_plan.governance_hints.get("sensitiveFields", ())),
            "targetService": tool_plan.governance_hints.get("targetService", ""),
            "source": tool_plan.governance_hints.get("source", ""),
        }


@dataclass(frozen=True)
class ToolActionIntakeReport:
    """一次 intake 调用的聚合报告。

    `planning_report` 是内部治理对象，主要给模型工具调用预算守卫、事件记录器和单元测试复用。
    它可能间接关联 `ToolPlan.arguments`，因此不会出现在 `to_low_sensitive_summary()` 中；对外只暴露
    `items` 的低敏摘要。
    """

    source: ToolActionIntakeSource
    items: tuple[ToolActionIntakeItem, ...] = ()
    planning_report: ModelToolCallPlanningReport | None = None

    @property
    def accepted_tool_plans(self) -> tuple[ToolPlan, ...]:
        """返回已经可以进入 readiness/graph 的 ToolPlan。"""

        return tuple(item.tool_plan for item in self.items if item.tool_plan is not None)

    @property
    def rejected_items(self) -> tuple[ToolActionIntakeItem, ...]:
        """返回在 readiness 前就被阻断的入口项。"""

        return tuple(item for item in self.items if item.boundary == ToolActionIntakeBoundary.REJECTED_BEFORE_READINESS)

    def to_low_sensitive_summary(self) -> dict[str, Any]:
        """输出报告级低敏摘要。"""

        boundaries = [item.boundary.value for item in self.items]
        return {
            "source": self.source.value,
            "payloadPolicy": "LOW_SENSITIVE_TOOL_ACTION_INTAKE_SUMMARY_ONLY",
            "totalCount": len(self.items),
            "acceptedToolPlanCount": len(self.accepted_tool_plans),
            "rejectedBeforeReadinessCount": len(self.rejected_items),
            "boundaryCounts": {boundary: boundaries.count(boundary) for boundary in sorted(set(boundaries))},
            "items": tuple(item.to_low_sensitive_summary() for item in self.items),
        }


class ToolActionIntakeService:
    """多协议工具动作意图的统一入口服务。

    当前服务更像一个“归一化控制面节点”，而不是执行器。后续可以被以下入口复用：
    - 模型网关解析出的 `message.tool_calls`；
    - MCP Server 的 `tools/call`；
    - A2A task/action 的继续推进请求；
    - 前端确认页重新提交的工具计划。

    所有入口都先经过这里，再进入 readiness graph 或 A2A 控制面决策，避免各协议适配层各自发明
    权限、审批、幂等、参数校验和低敏摘要规则。
    """

    def __init__(
        self,
        *,
        model_tool_call_planner: ModelToolCallPlanner | None = None,
        a2a_task_planning_adapter: A2aTaskPlanningAdapter | None = None,
    ) -> None:
        self._model_tool_call_planner = model_tool_call_planner or ModelToolCallPlanner()
        self._a2a_task_planning_adapter = a2a_task_planning_adapter or A2aTaskPlanningAdapter()

    def from_model_tool_calls(
        self,
        tool_calls: Iterable[ModelToolCall],
        *,
        registered_tools: tuple[ToolDefinition, ...],
        visible_tools: tuple[ToolDefinition, ...] | None = None,
    ) -> ToolActionIntakeReport:
        """把模型返回的 tool_calls 归一为 intake report。"""

        planning_report = self._model_tool_call_planner.plan(
            tool_calls=tool_calls,
            registered_tools=registered_tools,
            visible_tools=visible_tools,
        )
        return self._from_planning_report(ToolActionIntakeSource.MODEL_TOOL_CALL, planning_report)

    def from_mcp_tools_call(
        self,
        call: Mapping[str, Any] | None,
        *,
        registered_tools: tuple[ToolDefinition, ...],
        visible_tools: tuple[ToolDefinition, ...] | None = None,
    ) -> ToolActionIntakeReport:
        """把 MCP `tools/call` 风格请求归一为 intake report。

        真实 MCP `tools/call` 是外部 Agent 触发副作用的高风险入口。DataSmart 当前阶段不直接执行，
        只把它转换为 `ToolPlan` 候选，然后继续进入 readiness/graph/审批/outbox。
        """

        if not isinstance(call, Mapping):
            return ToolActionIntakeReport(
                source=ToolActionIntakeSource.MCP_TOOLS_CALL,
                items=(
                    ToolActionIntakeItem(
                        source=ToolActionIntakeSource.MCP_TOOLS_CALL,
                        boundary=ToolActionIntakeBoundary.REJECTED_BEFORE_READINESS,
                        accepted=False,
                        issues=(
                            ToolActionIntakeIssue(
                                code="MCP_TOOLS_CALL_MISSING_OR_INVALID",
                                message="MCP tools/call 请求体缺失或不是对象，不能进入 ToolPlan/readiness 链路。",
                            ),
                        ),
                    ),
                ),
            )

        tool_call = self._mcp_call_to_model_tool_call(call)
        planning_report = self._model_tool_call_planner.plan(
            tool_calls=(tool_call,),
            registered_tools=registered_tools,
            visible_tools=visible_tools,
        )
        return self._from_planning_report(
            ToolActionIntakeSource.MCP_TOOLS_CALL,
            planning_report,
            source_call_id=str(call.get("callId") or call.get("id") or tool_call.call_id or ""),
            sensitive_field_ignored_count=count_forbidden_fields(call),
        )

    def from_a2a_task_action(self, contract: Mapping[str, Any] | None) -> ToolActionIntakeReport:
        """把 A2A task/action 控制面合同归一为 intake report。

        A2A task 通常不是“直接调用某个工具”的请求，而是一个跨 Agent 协作任务事实。它应该进入
        `A2A_TASK_CONTROL_PLANE_DECISION`，由后续节点判断是否等待输入、等待授权、查询历史、展示 artifact
        引用或规划 worker pre-check，而不是伪装成普通 ToolPlan。
        """

        decision = self._a2a_task_planning_adapter.adapt(contract)
        boundary = (
            ToolActionIntakeBoundary.REJECTED_BEFORE_READINESS
            if decision.mode == AgentTaskPlanningMode.REJECTED_OR_DIAGNOSTIC
            else ToolActionIntakeBoundary.A2A_TASK_CONTROL_PLANE_DECISION
        )
        item = ToolActionIntakeItem(
            source=ToolActionIntakeSource.A2A_TASK_ACTION,
            boundary=boundary,
            accepted=boundary != ToolActionIntakeBoundary.REJECTED_BEFORE_READINESS,
            source_call_id=decision.snapshot.task_public_id,
            tool_name="",
            a2a_decision=decision,
            sensitive_field_ignored_count=decision.snapshot.sensitive_field_ignored_count,
        )
        return ToolActionIntakeReport(source=ToolActionIntakeSource.A2A_TASK_ACTION, items=(item,))

    @staticmethod
    def _mcp_call_to_model_tool_call(call: Mapping[str, Any]) -> ModelToolCall:
        """把 MCP 请求转换成现有工具规划器能理解的 `ModelToolCall`。

        这里复用 `ModelToolCallPlanner` 不是说 MCP 来自模型，而是为了复用工具名解析、可见性校验、
        JSON object 校验、参数 schema 校验和风险提示规则。转换后会在 governance hints 中重新标记来源。
        """

        raw_arguments = call.get("arguments", {})
        if isinstance(raw_arguments, str):
            arguments = raw_arguments
        elif isinstance(raw_arguments, Mapping):
            arguments = json.dumps(raw_arguments, ensure_ascii=False, sort_keys=True)
        else:
            arguments = json.dumps(raw_arguments, ensure_ascii=False)
        return ModelToolCall(
            call_id=str(call.get("callId") or call.get("id") or ""),
            type="mcp_tools_call",
            name=str(call.get("name") or call.get("toolName") or ""),
            arguments=arguments,
            raw_call={},
        )

    def _from_planning_report(
        self,
        source: ToolActionIntakeSource,
        planning_report: ModelToolCallPlanningReport,
        *,
        source_call_id: str = "",
        sensitive_field_ignored_count: int = 0,
    ) -> ToolActionIntakeReport:
        """把模型工具调用规划报告转换成 intake 报告。"""

        items = []
        for candidate in planning_report.candidates:
            issues = tuple(ToolActionIntakeIssue.from_model_issue(issue) for issue in candidate.issues)
            tool_plan = self._relabel_tool_plan_source(candidate.tool_plan, source, source_call_id)
            accepted = tool_plan is not None and not any(issue.blocking for issue in issues)
            boundary = (
                ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH
                if accepted
                else ToolActionIntakeBoundary.REJECTED_BEFORE_READINESS
            )
            items.append(
                ToolActionIntakeItem(
                    source=source,
                    boundary=boundary,
                    accepted=accepted,
                    source_call_id=source_call_id or str(candidate.source_call.call_id or ""),
                    tool_name=candidate.resolved_tool_name,
                    tool_plan=tool_plan if accepted else None,
                    issues=issues,
                    sensitive_field_ignored_count=sensitive_field_ignored_count,
                )
            )
        return ToolActionIntakeReport(source=source, items=tuple(items), planning_report=planning_report)

    @staticmethod
    def _relabel_tool_plan_source(
        tool_plan: ToolPlan | None,
        source: ToolActionIntakeSource,
        source_call_id: str,
    ) -> ToolPlan | None:
        """把复用规划器生成的 ToolPlan 重新标记为真实入口来源。"""

        if tool_plan is None:
            return None
        if source == ToolActionIntakeSource.MODEL_TOOL_CALL:
            return tool_plan
        hints = dict(tool_plan.governance_hints)
        if source == ToolActionIntakeSource.MCP_TOOLS_CALL:
            hints.update(
                {
                    "source": "mcp_tools_call",
                    "protocol": "MCP",
                    "mcpCallId": source_call_id or hints.get("modelToolCallId", ""),
                    "executionBoundary": ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH.value,
                }
            )
            hints.pop("modelToolCallId", None)
            hints.pop("modelToolCallType", None)
        return replace(tool_plan, governance_hints=hints)
