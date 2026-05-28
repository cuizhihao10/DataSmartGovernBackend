"""工具参数校验服务。

Agent 生成工具计划时，最容易出现的问题不是“完全没有计划”，而是“计划看起来合理，但关键参数
不完整”。如果这类计划直接进入执行链路，就可能在 Java 控制面才失败；如果全部阻断，又会让质量
规则草案、任务草案这类本来可以先展示给用户的能力变得很笨重。

因此本文件把参数校验拆成独立服务：`ToolPlanner` 继续负责决定“要不要调用某个工具”，而
`ToolParameterValidator` 负责判断“这次工具参数是否足够执行、是否只能创建草案、缺失参数该如何
处理”。后续接入 Java 工具注册表的 JSON Schema、租户策略或智能网关澄清问题时，都优先扩展这里。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    ToolDefinition,
    ToolExecutionMode,
    ToolParameterIssue,
    ToolParameterIssueAction,
    ToolParameterValidationResult,
)


class ToolParameterValidator:
    """根据工具 schema 校验计划参数。

    当前实现兼容两种 schema 形态：
    1. 旧的轻量写法：`{"datasourceId": "string"}`，表示所有列出的字段都必填；
    2. 面向未来的扩展写法：`{"datasourceId": {"type": "string", "required": true,
       "resolution": "context_or_clarify"}}`，便于 Java 控制面或配置中心逐步下发更完整的字段策略。

    这里不做复杂 JSON Schema 全量实现，是因为当前阶段最重要的是建立“参数完整性决策点”。真正
    的类型校验、枚举校验、嵌套对象校验、数组元素校验，可以在工具契约稳定后逐步增强。
    """

    def validate(self, tool: ToolDefinition, arguments: dict[str, Any]) -> ToolParameterValidationResult:
        """校验单个工具计划的入参完整性。

        参数：
        - `tool`：工具注册定义，包含 input_schema、风险等级、执行模式等治理信息。
        - `arguments`：规划器为该工具生成的参数。

        返回：
        - `ToolParameterValidationResult`：包含是否允许执行、是否允许草案、以及每个缺失参数的
          处理动作。调用方可以把它原样返回给前端，也可以继续映射成审批或澄清任务。
        """

        issues: list[ToolParameterIssue] = []
        for parameter_name, raw_schema in tool.input_schema.items():
            spec = self._normalize_parameter_spec(raw_schema)
            if not spec["required"]:
                continue
            value = arguments.get(parameter_name)
            if self._is_missing(value):
                action = self._resolve_missing_action(tool, parameter_name, spec)
                issues.append(
                    ToolParameterIssue(
                        parameter_name=parameter_name,
                        expected_type=spec["type"],
                        action=action,
                        message=self._build_message(parameter_name, spec["type"], action),
                    )
                )

        can_execute = not any(issue.action == ToolParameterIssueAction.MUST_CLARIFY for issue in issues)
        can_create_draft = self._can_create_draft(tool, issues)
        return ToolParameterValidationResult(
            can_execute=can_execute and not issues,
            can_create_draft=can_create_draft,
            issues=tuple(issues),
        )

    @staticmethod
    def _normalize_parameter_spec(raw_schema: Any) -> dict[str, Any]:
        """把不同 schema 写法归一化为内部字段说明。

        旧版默认工具注册表里使用 `{"datasourceId": "string"}`，这对阅读很友好，但表达不了是否
        可选、能否从上下文补齐等策略。归一化后，后续逻辑只需要读取统一字段，避免在主流程里到处
        判断 schema 是字符串还是字典。
        """

        if isinstance(raw_schema, dict):
            return {
                "type": str(raw_schema.get("type", "object")),
                "required": bool(raw_schema.get("required", True)),
                "resolution": str(raw_schema.get("resolution", "")),
            }
        return {
            "type": str(raw_schema),
            "required": True,
            "resolution": "",
        }

    @staticmethod
    def _is_missing(value: Any) -> bool:
        """判断参数值是否缺失。

        `None`、空字符串、空列表、空字典都按缺失处理。这样做比只判断 key 是否存在更安全，因为
        Agent 规划器可能为了保留字段结构而填入 `None`，如果不识别空值，后续执行层仍会失败。
        """

        return value is None or value == "" or value == [] or value == {}

    @staticmethod
    def _resolve_missing_action(
        tool: ToolDefinition,
        parameter_name: str,
        spec: dict[str, Any],
    ) -> ToolParameterIssueAction:
        """为缺失参数选择处理动作。

        当前策略是产品化的保守基线：
        - schema 明确声明 `resolution=context`、`context_or_clarify`、`can_fill_from_context`
          或 `system_injected` 时，优先标记为可由上下文/系统补齐；
        - 草案类工具允许先生成草案，让用户在确认页补齐参数；
        - 审批类工具或真实执行工具缺少参数时必须澄清，不能把不完整请求推进到执行链路。
        """

        resolution = str(spec.get("resolution", "")).lower().replace("-", "_")
        if resolution in {"context", "context_or_clarify", "can_fill_from_context", "system_injected", "derived"}:
            return ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT
        if tool.execution_mode == ToolExecutionMode.DRAFT_ONLY:
            return ToolParameterIssueAction.ALLOW_DRAFT
        if parameter_name in {"businessGoal", "description", "remark"}:
            return ToolParameterIssueAction.ALLOW_DRAFT
        return ToolParameterIssueAction.MUST_CLARIFY

    @staticmethod
    def _can_create_draft(tool: ToolDefinition, issues: list[ToolParameterIssue]) -> bool:
        """判断存在参数问题时是否仍允许创建草案。

        同步执行工具没有草案语义，例如读取数据源元数据缺少 datasourceId 时应该直接澄清。草案类和
        审批类工具则可以保留计划，让用户或审批人在确认页看到缺什么、为什么缺、下一步该补什么。
        """

        if not issues:
            return True
        return tool.execution_mode in {ToolExecutionMode.DRAFT_ONLY, ToolExecutionMode.APPROVAL_REQUIRED}

    @staticmethod
    def _build_message(
        parameter_name: str,
        expected_type: str,
        action: ToolParameterIssueAction,
    ) -> str:
        """生成面向前端和学习阅读的中文说明。

        这里先生成简洁中文，后续如果前端需要多语言，可以把 action 和 parameterName 作为稳定机器
        字段传给前端，由前端国际化资源负责展示文案。
        """

        if action == ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT:
            return f"参数 `{parameter_name}` 缺失，期望类型为 {expected_type}，可尝试通过上下文检索或用户选择补齐。"
        if action == ToolParameterIssueAction.ALLOW_DRAFT:
            return f"参数 `{parameter_name}` 缺失，期望类型为 {expected_type}，当前仅允许生成草案，执行前必须补齐。"
        return f"参数 `{parameter_name}` 缺失，期望类型为 {expected_type}，必须先向用户澄清，不能进入执行链路。"
