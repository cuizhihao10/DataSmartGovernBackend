"""模型工具 schema 暴露与 OpenAI-compatible 转换。

DataSmart 内部的 `ToolDefinition` 是平台治理契约：它包含风险等级、执行模式、审批要求、敏感字段、
租户/项目隔离、记忆写入和缓存策略。OpenAI-compatible Chat Completions 的 `tools` 参数则是模型侧
契约：它只关心工具名称、描述、JSON Schema 参数和 strict 开关。

本文件负责把“平台治理契约”安全地投影成“模型可见工具契约”。这样 Provider 不需要理解 DataSmart
的全部治理语义，也避免把所有工具无差别暴露给模型。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.domain.contracts import ToolDefinition, ToolExecutionMode, ToolRiskLevel


@dataclass(frozen=True)
class ModelToolSchemaExposurePolicy:
    """模型可见工具暴露策略。

    工具调用产品化时，一个很容易踩坑的点是“把平台注册的全部工具都塞给模型”。这会带来三个问题：
    - 上下文浪费：工具太多会挤占 prompt，降低模型判断质量；
    - 安全噪声：模型可能尝试调用当前用户无权或高风险工具；
    - 审计混乱：模型看见了不该看的内部工具名称和参数。

    因此这里用独立策略控制暴露范围。当前策略是 MVP 级别，但已经保留商业化需要的关键旋钮：
    - `max_tools`：限制单次请求暴露工具数量，后续可替换为向量检索 top-k 工具选择；
    - `allow_critical_tools`：默认不暴露 CRITICAL 工具，避免模型直接规划破坏性动作；
    - `allow_approval_required_tools`：允许模型“提出需审批工具调用”，但执行前仍必须走 Java 控制面；
    - `strict`：是否在 OpenAI-compatible tools 中启用 strict schema，需考虑不同模型网关兼容性。
    """

    max_tools: int = 16
    allow_critical_tools: bool = False
    allow_approval_required_tools: bool = True
    strict: bool = False


class OpenAICompatibleToolSchemaBuilder:
    """把 DataSmart 工具定义转换为 OpenAI-compatible tools 参数。

    这个类只做“暴露和转换”，不做工具执行。模型即使返回了 tool_calls，也只是表示“模型建议调用某个
    工具”。真正执行前仍要经过：
    - 工具注册表校验：工具是否存在、是否启用、调用协议是否匹配；
    - 参数 schema 校验：模型生成的 JSON 是否完整、是否越权、是否含敏感字段；
    - 权限与数据范围校验：当前 actor 是否能访问租户/项目/数据源；
    - 审批与审计：高风险工具是否已审批，执行过程是否可追踪。
    """

    def build(
        self,
        tools: tuple[ToolDefinition, ...],
        policy: ModelToolSchemaExposurePolicy | None = None,
    ) -> list[dict[str, Any]]:
        """构建 OpenAI-compatible `tools` 数组。

        输入的 `tools` 应该已经由 Agent loop 根据租户、项目、角色和意图做过第一轮裁剪。这里再做
        第二层 Provider 前置防线，避免误配置导致 CRITICAL 工具或过多工具直接进入模型上下文。
        """

        exposure_policy = policy or ModelToolSchemaExposurePolicy()
        exposed: list[dict[str, Any]] = []
        for tool in tools:
            if len(exposed) >= max(0, exposure_policy.max_tools):
                break
            if not self._can_expose(tool, exposure_policy):
                continue
            exposed.append(self._to_openai_tool(tool, exposure_policy.strict))
        return exposed

    def build_name_aliases(self, tools: tuple[ToolDefinition, ...]) -> dict[str, str]:
        """构建“模型函数名 -> DataSmart 原始工具名”的映射。

        发送给模型的 function name 会把点号转换为下划线，例如 `quality.rule.suggest` 会变成
        `quality_rule_suggest`。模型回传 tool_calls 时也会使用转换后的名称。为了让后续 ToolPlanner、
        Java agent-runtime 和审计系统继续使用 DataSmart 原始工具名，这里提供反向映射。
        """

        return {self._normalize_function_name(tool.name): tool.name for tool in tools}

    @staticmethod
    def _can_expose(tool: ToolDefinition, policy: ModelToolSchemaExposurePolicy) -> bool:
        """判断工具是否允许暴露给模型。

        暴露给模型不等于执行，但它仍然是一种信息披露：模型会看到工具名称、参数字段、敏感字段提示和
        大致能力。因此 CRITICAL 工具默认隐藏；需审批工具默认可以展示给模型提出计划，但描述里会明确
        标注“必须审批”，避免后续前端或审计误以为这是可自动执行工具。
        """

        if tool.risk_level == ToolRiskLevel.CRITICAL and not policy.allow_critical_tools:
            return False
        if tool.requires_approval and not policy.allow_approval_required_tools:
            return False
        return True

    @classmethod
    def _to_openai_tool(cls, tool: ToolDefinition, strict: bool) -> dict[str, Any]:
        """把单个 `ToolDefinition` 转换为 OpenAI-compatible function tool。

        OpenAI-compatible Chat Completions 的 function tool 通常长这样：
        `{ "type": "function", "function": {"name": "...", "description": "...", "parameters": {...}} }`。
        DataSmart 的工具名使用点号，例如 `quality.rule.suggest`；部分模型服务对函数名字符集更严格，
        因此这里统一转换为下划线形式。原始工具名会写入描述，后续解析 tool call 时可以再映射回原名。
        """

        function: dict[str, Any] = {
            "name": cls._normalize_function_name(tool.name),
            "description": cls._build_description(tool),
            "parameters": cls._build_parameters(tool.input_schema, strict),
        }
        if strict:
            function["strict"] = True
        return {
            "type": "function",
            "function": function,
        }

    @staticmethod
    def _normalize_function_name(name: str) -> str:
        """把 DataSmart 工具名转换为模型函数名。

        许多 OpenAI-compatible 实现要求 function name 使用字母、数字、下划线或连字符。DataSmart
        内部工具名使用点号分层表达领域，例如 `datasource.metadata.read`。这里转成
        `datasource_metadata_read`，既兼容模型网关，又保留可读性。
        """

        normalized = "".join(ch if ch.isalnum() or ch in {"_", "-"} else "_" for ch in name)
        return normalized.strip("_") or "datasmart_tool"

    @staticmethod
    def _build_description(tool: ToolDefinition) -> str:
        """构建给模型看的工具描述。

        工具描述不是给用户看的营销文案，而是给模型做工具选择的“操作边界说明”。因此这里会把
        DataSmart 治理提示写进去：风险等级、执行模式、是否只读、是否需审批、敏感字段、租户/项目范围。
        这些信息可以降低模型误选工具的概率，也方便后续审计回放解释“模型当时看到了什么约束”。
        """

        lines = [
            tool.description or tool.display_name or tool.name,
            f"DataSmart 原始工具名：{tool.name}。",
            f"风险等级：{tool.risk_level.value}；执行模式：{tool.execution_mode.value}。",
        ]
        if tool.read_only:
            lines.append("该工具为只读工具，不能直接改变业务状态。")
        if tool.execution_mode == ToolExecutionMode.DRAFT_ONLY:
            lines.append("该工具只能生成草案或建议，不能直接落库启用。")
        if tool.requires_approval:
            lines.append("该工具需要人工审批，模型只能提出调用意图，不能绕过审批直接执行。")
        if tool.tenant_scoped or tool.project_scoped:
            scope = "租户级" if tool.tenant_scoped else ""
            scope = f"{scope}/项目级" if tool.project_scoped else scope
            lines.append(f"调用必须遵守 {scope.strip('/')} 数据边界。")
        if tool.sensitive_fields:
            lines.append(f"敏感字段：{', '.join(tool.sensitive_fields)}，输出或日志展示时需要脱敏。")
        return "\n".join(line for line in lines if line)

    @classmethod
    def _build_parameters(cls, input_schema: dict[str, Any], strict: bool) -> dict[str, Any]:
        """把 DataSmart 输入 schema 转换为 JSON Schema。

        Java descriptor 当前会把参数字段折叠成 `{字段名: {type, required, description, ...}}`。模型侧
        需要的是标准 JSON Schema：`type=object`、`properties`、`required`。这里保持宽松映射：
        - 未知类型默认按 string 处理；
        - required 字段进入 JSON Schema required 数组；
        - sensitive/resolution/example 会写进字段描述，帮助模型理解参数来源和安全限制；
        - strict 开启时补 `additionalProperties=false`，避免模型生成未声明字段。
        """

        properties: dict[str, Any] = {}
        required: list[str] = []
        for name, raw_definition in input_schema.items():
            if not isinstance(raw_definition, dict):
                properties[name] = {"type": "string", "description": str(raw_definition)}
                continue
            if raw_definition.get("required"):
                required.append(name)
            properties[name] = {
                "type": cls._normalize_json_schema_type(raw_definition.get("type")),
                "description": cls._build_parameter_description(raw_definition),
            }
        schema: dict[str, Any] = {
            "type": "object",
            "properties": properties,
        }
        if required:
            schema["required"] = required
        if strict:
            schema["additionalProperties"] = False
        return schema

    @staticmethod
    def _build_parameter_description(definition: dict[str, Any]) -> str:
        """构建单个参数描述。

        参数描述里保留 `resolution` 和 `sensitive`，是为了让模型知道“缺参时该追问、从上下文补齐，
        还是只能生成草案”。这对真实 Agent 很重要：模型不应该为缺失 datasourceId 编造一个值。
        """

        parts = [str(definition.get("description") or "").strip()]
        if definition.get("resolution"):
            parts.append(f"参数补齐策略：{definition.get('resolution')}。")
        if definition.get("sensitive"):
            parts.append("该参数包含敏感含义，展示和日志需要脱敏。")
        if definition.get("example") is not None:
            parts.append(f"示例：{definition.get('example')}。")
        return " ".join(part for part in parts if part)

    @staticmethod
    def _normalize_json_schema_type(value: Any) -> str:
        """归一化参数类型到 JSON Schema 基础类型。

        Java descriptor、MCP server、人工配置可能会使用 `INTEGER`、`int`、`map`、`dict` 等不同写法。
        这里统一映射到 JSON Schema 常见类型，避免工具 schema 因命名差异被模型网关拒绝。
        """

        normalized = str(value or "string").strip().lower()
        aliases = {
            "int": "integer",
            "long": "integer",
            "float": "number",
            "double": "number",
            "decimal": "number",
            "bool": "boolean",
            "map": "object",
            "dict": "object",
            "list": "array",
        }
        normalized = aliases.get(normalized, normalized)
        return normalized if normalized in {"string", "integer", "number", "boolean", "object", "array"} else "string"
