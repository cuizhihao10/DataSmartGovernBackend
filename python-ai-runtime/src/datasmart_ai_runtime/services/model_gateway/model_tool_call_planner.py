"""模型工具调用候选治理与 ToolPlan 映射。

`ModelToolCall` 来自模型输出，不能直接等同于平台工具计划。真实商业化 Agent 必须先回答几个问题：
- 模型提到的工具是否存在于 DataSmart 工具注册表？
- 这个工具是否是本轮已经暴露给模型的候选工具，而不是模型幻觉或越权猜测？
- arguments 是否是合法 JSON object？
- 参数是否满足工具 schema？
- 工具风险是否要求审批或禁止自动执行？

本文件负责把模型工具调用意图转换为“可治理候选”。只有通过前置检查的候选才会生成 `ToolPlan`；
未通过的候选也不会丢失，而是以 issue 形式保留下来，方便前端提示、审计回放和后续重试策略。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Iterable

from datasmart_ai_runtime.domain.contracts import (
    ModelToolCall,
    ToolDefinition,
    ToolExecutionMode,
    ToolPlan,
    ToolRiskLevel,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_schema import OpenAICompatibleToolSchemaBuilder
from datasmart_ai_runtime.services.tool_parameter_validator import ToolParameterValidator


@dataclass(frozen=True)
class ModelToolCallGovernanceIssue:
    """模型工具调用治理问题。

    字段说明：
    - `tool_name`：模型声称要调用的工具名，可能是原始 DataSmart 名，也可能是模型函数名；
    - `code`：稳定机器码，后续可进入 runtime event、审计表和前端国际化；
    - `message`：中文解释，便于当前学习和本地调试；
    - `blocking`：是否阻断生成 `ToolPlan`。例如未知工具、未暴露工具、JSON 非法都是阻断问题；
      审批要求则不是阻断问题，而是要求后续进入人工审批。
    """

    tool_name: str
    code: str
    message: str
    blocking: bool = True


@dataclass(frozen=True)
class ModelToolCallCandidate:
    """单个模型工具调用候选的治理结果。"""

    source_call: ModelToolCall
    resolved_tool_name: str
    tool_plan: ToolPlan | None = None
    issues: tuple[ModelToolCallGovernanceIssue, ...] = ()

    @property
    def accepted(self) -> bool:
        """该候选是否已生成可进入后续控制面的 ToolPlan。"""

        return self.tool_plan is not None and not any(issue.blocking for issue in self.issues)


@dataclass(frozen=True)
class ModelToolCallPlanningReport:
    """模型工具调用候选规划报告。"""

    candidates: tuple[ModelToolCallCandidate, ...] = ()

    @property
    def accepted_tool_plans(self) -> tuple[ToolPlan, ...]:
        """返回所有可进入后续参数校验/审批/执行链路的 ToolPlan。"""

        return tuple(candidate.tool_plan for candidate in self.candidates if candidate.accepted and candidate.tool_plan)

    @property
    def rejected_candidates(self) -> tuple[ModelToolCallCandidate, ...]:
        """返回存在阻断问题的候选。"""

        return tuple(candidate for candidate in self.candidates if any(issue.blocking for issue in candidate.issues))

    @property
    def issues(self) -> tuple[ModelToolCallGovernanceIssue, ...]:
        """扁平化返回所有治理问题。"""

        return tuple(issue for candidate in self.candidates for issue in candidate.issues)


class ModelToolCallPlanner:
    """把模型工具调用意图转换为 DataSmart ToolPlan。

    这个类是模型输出和平台控制面之间的安全闸门。它不会执行工具，只会决定“模型提出的调用是否可以
    进入平台 ToolPlan 语义”。真正执行仍要由 Java agent-runtime 负责审计、审批、幂等和业务适配。
    """

    def __init__(self, parameter_validator: ToolParameterValidator | None = None) -> None:
        self._parameter_validator = parameter_validator or ToolParameterValidator()
        self._schema_builder = OpenAICompatibleToolSchemaBuilder()

    def plan(
        self,
        tool_calls: Iterable[ModelToolCall],
        registered_tools: tuple[ToolDefinition, ...],
        visible_tools: tuple[ToolDefinition, ...] | None = None,
    ) -> ModelToolCallPlanningReport:
        """规划一组模型工具调用候选。

        参数：
        - `tool_calls`：模型返回或 streaming 聚合出的工具调用意图；
        - `registered_tools`：平台工具注册表，通常来自 Java agent-runtime 或本地默认工具表；
        - `visible_tools`：本轮真正暴露给模型的候选工具。若传入，模型只能调用这些工具；若不传，
          默认按全部注册工具处理，适合离线兼容测试。
        """

        registered_by_name = self._tool_lookup(registered_tools)
        visible_lookup = self._tool_lookup(visible_tools if visible_tools is not None else registered_tools)
        candidates = [
            self._plan_single(tool_call, registered_by_name, visible_lookup)
            for tool_call in tool_calls
        ]
        return ModelToolCallPlanningReport(candidates=tuple(candidates))

    def _plan_single(
        self,
        tool_call: ModelToolCall,
        registered_by_name: dict[str, ToolDefinition],
        visible_lookup: dict[str, ToolDefinition],
    ) -> ModelToolCallCandidate:
        """治理单个模型工具调用。"""

        resolved_name = self._resolve_tool_name(tool_call.name, registered_by_name)
        issues: list[ModelToolCallGovernanceIssue] = []
        if not resolved_name:
            return ModelToolCallCandidate(
                source_call=tool_call,
                resolved_tool_name=tool_call.name,
                issues=(
                    ModelToolCallGovernanceIssue(
                        tool_name=tool_call.name,
                        code="MODEL_TOOL_CALL_UNKNOWN_TOOL",
                        message="模型提出的工具不存在于 DataSmart 工具注册表，可能是模型幻觉或协议映射错误。",
                    ),
                ),
            )

        tool = registered_by_name[resolved_name]
        if resolved_name not in visible_lookup:
            return ModelToolCallCandidate(
                source_call=tool_call,
                resolved_tool_name=resolved_name,
                issues=(
                    ModelToolCallGovernanceIssue(
                        tool_name=resolved_name,
                        code="MODEL_TOOL_CALL_NOT_EXPOSED",
                        message="模型尝试调用未在本轮候选集中暴露的工具，按最小权限原则拒绝进入执行计划。",
                    ),
                ),
            )

        arguments, parse_issue = self._parse_arguments(tool_call)
        if parse_issue is not None:
            return ModelToolCallCandidate(
                source_call=tool_call,
                resolved_tool_name=resolved_name,
                issues=(parse_issue,),
            )

        issues.extend(self._risk_issues(tool))
        parameter_validation = self._parameter_validator.validate(tool, arguments)
        tool_plan = ToolPlan(
            tool_name=resolved_name,
            reason="模型基于当前可见工具 schema 提出了工具调用意图；进入执行前仍需平台治理校验。",
            arguments=arguments,
            risk_level=tool.risk_level,
            execution_mode=tool.execution_mode,
            requires_human_approval=self._requires_approval(tool),
            parameter_validation=parameter_validation,
            governance_hints={
                "source": "model_tool_call",
                "modelToolCallId": tool_call.call_id,
                "modelToolCallType": tool_call.type,
                "targetService": tool.target_service,
                "targetEndpoint": tool.target_endpoint,
                "tenantScoped": tool.tenant_scoped,
                "projectScoped": tool.project_scoped,
                "sensitiveFields": tool.sensitive_fields,
                "memoryWritePolicy": tool.memory_write_policy,
                "cachePolicy": tool.cache_policy,
            },
        )
        return ModelToolCallCandidate(
            source_call=tool_call,
            resolved_tool_name=resolved_name,
            tool_plan=tool_plan,
            issues=tuple(issues),
        )

    def _tool_lookup(self, tools: tuple[ToolDefinition, ...]) -> dict[str, ToolDefinition]:
        """构建工具名查找表，同时支持原始名和 OpenAI-compatible 函数名。"""

        lookup: dict[str, ToolDefinition] = {}
        aliases = self._schema_builder.build_name_aliases(tools)
        for tool in tools:
            lookup[tool.name] = tool
        for model_name, original_name in aliases.items():
            if original_name in lookup:
                lookup[model_name] = lookup[original_name]
        return lookup

    @staticmethod
    def _resolve_tool_name(name: str, lookup: dict[str, ToolDefinition]) -> str:
        """把模型工具名解析回 DataSmart 原始工具名。"""

        tool = lookup.get(name)
        return tool.name if tool is not None else ""

    @staticmethod
    def _parse_arguments(tool_call: ModelToolCall) -> tuple[dict[str, Any], ModelToolCallGovernanceIssue | None]:
        """解析模型生成的 arguments 字符串。

        空字符串按空对象处理，因为某些工具理论上可以无参调用；是否允许无参，交给后续
        `ToolParameterValidator` 根据工具 schema 判断。非 JSON 或非 object 则是阻断问题。
        """

        if not tool_call.arguments.strip():
            return {}, None
        try:
            parsed = json.loads(tool_call.arguments)
        except json.JSONDecodeError as exc:
            return {}, ModelToolCallGovernanceIssue(
                tool_name=tool_call.name,
                code="MODEL_TOOL_CALL_ARGUMENTS_INVALID_JSON",
                message=f"模型工具调用参数不是合法 JSON：{exc}",
            )
        if not isinstance(parsed, dict):
            return {}, ModelToolCallGovernanceIssue(
                tool_name=tool_call.name,
                code="MODEL_TOOL_CALL_ARGUMENTS_NOT_OBJECT",
                message="模型工具调用参数必须是 JSON object，不能是数组、字符串或其他类型。",
            )
        return parsed, None

    @staticmethod
    def _risk_issues(tool: ToolDefinition) -> tuple[ModelToolCallGovernanceIssue, ...]:
        """根据工具风险生成非阻断治理提示。"""

        issues: list[ModelToolCallGovernanceIssue] = []
        if tool.requires_approval or tool.execution_mode == ToolExecutionMode.APPROVAL_REQUIRED:
            issues.append(
                ModelToolCallGovernanceIssue(
                    tool_name=tool.name,
                    code="MODEL_TOOL_CALL_APPROVAL_REQUIRED",
                    message="该模型工具调用涉及需审批工具，必须进入人工确认或审批链路后才能执行。",
                    blocking=False,
                )
            )
        if tool.risk_level == ToolRiskLevel.CRITICAL:
            issues.append(
                ModelToolCallGovernanceIssue(
                    tool_name=tool.name,
                    code="MODEL_TOOL_CALL_CRITICAL_RISK",
                    message="该模型工具调用命中 CRITICAL 风险工具，默认不应自动执行，需要平台管理员策略确认。",
                    blocking=True,
                )
            )
        return tuple(issues)

    @staticmethod
    def _requires_approval(tool: ToolDefinition) -> bool:
        """判断模型工具调用是否必须进入审批链路。"""

        return tool.requires_approval or tool.execution_mode == ToolExecutionMode.APPROVAL_REQUIRED or tool.risk_level in {
            ToolRiskLevel.HIGH,
            ToolRiskLevel.CRITICAL,
        }
