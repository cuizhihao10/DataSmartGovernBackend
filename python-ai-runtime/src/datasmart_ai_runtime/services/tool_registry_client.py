"""Java Agent Runtime 工具目录客户端。

Python AI Runtime 最终不应该维护一份与 Java 侧完全重复的工具清单。工具是否启用、风险等级、
执行模式、审批要求、目标微服务和输入字段，应该由 Java `agent-runtime` 控制面统一治理。

本文件先实现一个标准库版本的 HTTP 客户端和映射器，避免为了一个只读目录接口过早引入
`requests` 或完整服务发现依赖。后续接入 Nacos、网关鉴权、重试、熔断时，可以在这个类内部
演进，不影响 `ToolPlanner` 与 `AgentOrchestrator`。
"""

from __future__ import annotations

import json
from typing import Any
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.contracts import (
    ToolDefinition,
    ToolExecutionMode,
    ToolRiskLevel,
)


class ToolRegistryClientError(RuntimeError):
    """工具目录客户端错误。

    单独定义异常类型，是为了让 API 层或编排层未来可以区分“工具目录不可用”和“规划逻辑失败”。
    在商业化系统里，这两类错误的恢复策略不同：前者可能走缓存或降级，后者需要修规划器。
    """


class JavaAgentToolRegistryClient:
    """从 Java `agent-runtime` 拉取 Agent 工具目录。

    参数说明：
    - `base_url`：Java Agent Runtime 的基础地址，例如 `http://localhost:8091` 或网关地址。
    - `timeout_seconds`：只读目录接口的超时时间。目录同步不应长时间阻塞用户会话。

    当前客户端保留两类接口：
    - `/agent-runtime/tools`：旧版展示型工具清单，适合前端管理页面或兼容旧调用方；
    - `/agent-runtime/tools/descriptors`：新版 MCP-style 描述符，适合 Python Runtime、智能网关、
      审批系统和审计系统做机器消费。

    Python Runtime 会优先消费 descriptor，因为它包含租户/项目范围、敏感字段、记忆写入策略和缓存
    范围等治理信息。这样做不是追求“协议名词好看”，而是为了让工具调用在商业化环境中可控：
    Agent 选择工具之前就能知道哪些字段敏感、哪些动作需要审批、哪些上下文不能跨项目缓存。
    """

    def __init__(
        self,
        base_url: str,
        timeout_seconds: int = 3,
        tools_path: str = "/agent-runtime/tools",
        descriptors_path: str = "/agent-runtime/tools/descriptors",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._tools_path = tools_path
        self._descriptors_path = descriptors_path

    def list_tools(self, enabled_only: bool = True, trace_id: str | None = None) -> tuple[ToolDefinition, ...]:
        """读取工具目录并转换为 Python 领域契约。

        Java 返回的是 `PlatformApiResponse<List<AgentToolDefinitionView>>`。Python 层只关心 data
        中的工具定义，但仍会校验 `code == 0`，避免把失败响应误当成空工具清单。
        """

        query = urlencode({"enabledOnly": str(enabled_only).lower()})
        url = f"{self._base_url}{self._tools_path}?{query}"
        headers = {"Accept": "application/json"}
        if trace_id:
            headers["X-Trace-Id"] = trace_id

        request = Request(url=url, headers=headers, method="GET")
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成测试中覆盖
            raise ToolRegistryClientError(f"读取 Java Agent 工具目录失败：{exc}") from exc

        return self.parse_platform_response(payload)

    def list_tool_descriptors(self, enabled_only: bool = True, trace_id: str | None = None) -> tuple[ToolDefinition, ...]:
        """读取 Java MCP-style 工具描述符并转换为 Python 工具契约。

        descriptor 是后续推荐使用的入口。它比旧清单多了 `governance`、`memory`、`parameters`
        三块机器可消费信息，因此 Python 规划器可以提前做更细的风险判断：
        - `governance.sensitiveFields`：用于审批提示、审计脱敏和日志压缩；
        - `governance.tenantScoped/projectScoped`：用于避免工具计划跨租户、跨项目漂移；
        - `memory.cachePolicy`：用于未来模型网关做 prefix cache / KV cache 范围隔离；
        - `parameters.resolution`：用于判断缺失参数是追问用户，还是优先走上下文补齐。
        """

        query = urlencode({"enabledOnly": str(enabled_only).lower()})
        url = f"{self._base_url}{self._descriptors_path}?{query}"
        headers = {"Accept": "application/json"}
        if trace_id:
            headers["X-Trace-Id"] = trace_id

        request = Request(url=url, headers=headers, method="GET")
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成测试中覆盖
            raise ToolRegistryClientError(f"读取 Java Agent 工具描述符失败：{exc}") from exc

        return self.parse_descriptor_platform_response(payload)

    @classmethod
    def parse_platform_response(cls, payload: dict[str, Any]) -> tuple[ToolDefinition, ...]:
        """解析 Java 平台统一响应。

        这个方法单独抽出，是为了在单元测试中不依赖真实 HTTP 服务也能验证契约映射。后续如果
        Java 响应字段变更，测试会优先在这里暴露问题。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java 工具目录接口返回失败")
            raise ToolRegistryClientError(f"{reason}: {message}")

        raw_tools = payload.get("data") or []
        if not isinstance(raw_tools, list):
            raise ToolRegistryClientError("Java 工具目录响应 data 必须是数组")
        return tuple(cls._map_tool(item) for item in raw_tools)

    @classmethod
    def parse_descriptor_platform_response(cls, payload: dict[str, Any]) -> tuple[ToolDefinition, ...]:
        """解析 Java 工具描述符统一响应。

        这个方法与 `parse_platform_response(...)` 并存，是为了让迁移过程可回退：生产集成优先使用
        descriptor；如果 Java 侧某个环境尚未升级，API 层仍可降级到旧工具目录。两个解析器最终都
        输出 `ToolDefinition`，从而保护 `ToolPlanner` 不被 Java DTO 细节污染。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java 工具描述符接口返回失败")
            raise ToolRegistryClientError(f"{reason}: {message}")

        raw_descriptors = payload.get("data") or []
        if not isinstance(raw_descriptors, list):
            raise ToolRegistryClientError("Java 工具描述符响应 data 必须是数组")
        return tuple(cls._map_tool_descriptor(item) for item in raw_descriptors)

    @staticmethod
    def _map_tool(item: dict[str, Any]) -> ToolDefinition:
        """把 Java `AgentToolDefinitionView` 映射为 Python `ToolDefinition`。

        Java 枚举通常以 `LOW`、`SYNC` 这类大写形式返回；Python 领域枚举使用面向 API 的小写值。
        这里集中完成格式转换，避免规划器到处处理 Java/Python 命名差异。
        """

        input_schema = {
            field.get("name"): {
                "type": field.get("type"),
                "required": bool(field.get("required")),
                "description": field.get("description") or "",
                "example": field.get("example"),
            }
            for field in item.get("inputSchema") or []
            if field.get("name")
        }
        return ToolDefinition(
            name=item["toolCode"],
            display_name=item.get("displayName") or item["toolCode"],
            description=item.get("description") or "",
            risk_level=ToolRiskLevel(str(item.get("riskLevel", "LOW")).lower()),
            execution_mode=ToolExecutionMode(str(item.get("executionMode", "SYNC")).lower()),
            input_schema=input_schema,
            target_service=item.get("targetService") or "",
            target_endpoint=item.get("targetEndpoint") or "",
            read_only=bool(item.get("readOnly")),
            requires_approval=bool(item.get("requiresApproval")),
            idempotent=bool(item.get("idempotent")),
            timeout_ms=item.get("timeoutMs"),
            max_retries=item.get("maxRetries"),
            allowed_actions=tuple(item.get("allowedActions") or ()),
            tool_type=item.get("toolType") or "",
        )

    @staticmethod
    def _map_tool_descriptor(item: dict[str, Any]) -> ToolDefinition:
        """把 Java `AgentToolDescriptorView` 映射为 Python `ToolDefinition`。

        descriptor 结构更接近 MCP 工具描述思想：工具能力、调用方式、治理策略和参数 schema 被拆成
        独立块。Python 这里不直接照搬 Java record，而是折叠进 `ToolDefinition`，原因是规划器真正
        需要的是“能不能选这个工具、缺什么参数、是否要审批、上下文/缓存如何隔离”。这样可以在
        保留协议演进空间的同时，避免运行时领域模型过早变得臃肿。
        """

        invocation = item.get("invocation") or {}
        governance = item.get("governance") or {}
        memory = item.get("memory") or {}
        parameters = item.get("parameters") or []
        input_schema = {
            field.get("name"): {
                "type": field.get("type"),
                "required": bool(field.get("required")),
                "sensitive": bool(field.get("sensitive")),
                "resolution": JavaAgentToolRegistryClient._normalize_resolution(field.get("resolution")),
                "description": field.get("description") or "",
                "example": field.get("example"),
            }
            for field in parameters
            if field.get("name")
        }
        return ToolDefinition(
            name=item["toolCode"],
            display_name=item.get("displayName") or item["toolCode"],
            description=item.get("description") or "",
            risk_level=ToolRiskLevel(str(governance.get("riskLevel", "LOW")).lower()),
            execution_mode=ToolExecutionMode(str(invocation.get("executionMode", "SYNC")).lower()),
            input_schema=input_schema,
            target_service=invocation.get("targetService") or "",
            target_endpoint=invocation.get("targetEndpoint") or "",
            read_only=bool(governance.get("readOnly")),
            requires_approval=bool(governance.get("requiresApproval")),
            idempotent=bool(invocation.get("idempotent")),
            timeout_ms=invocation.get("timeoutMs"),
            max_retries=invocation.get("maxRetries"),
            allowed_actions=tuple(governance.get("allowedActions") or ()),
            schema_version=item.get("schemaVersion") or "",
            descriptor_type=item.get("descriptorType") or "",
            protocol_hint=item.get("protocolHint") or "",
            tool_type=item.get("toolType") or "",
            tenant_scoped=bool(governance.get("tenantScoped", True)),
            project_scoped=bool(governance.get("projectScoped", True)),
            sensitive_fields=tuple(governance.get("sensitiveFields") or ()),
            memory_write_policy=JavaAgentToolRegistryClient._normalize_policy(memory.get("memoryWritePolicy") or "none"),
            cache_policy=JavaAgentToolRegistryClient._normalize_policy(memory.get("cachePolicy") or "session_only"),
        )

    @staticmethod
    def _normalize_resolution(value: Any) -> str:
        """归一化 Java 参数解析策略。

        Java 侧为了便于配置和展示，通常使用 `CAN_FILL_FROM_CONTEXT`、`USER_REQUIRED` 这类大写枚举。
        Python 校验器使用小写 snake_case。统一在客户端边界转换，可以让后续本地默认工具、远程
        Java descriptor、未来 MCP server adapter 都走同一套参数校验逻辑。
        """

        return str(value or "").strip().lower().replace("-", "_")

    @staticmethod
    def _normalize_policy(value: Any) -> str:
        """归一化记忆写入与缓存策略。

        这些策略未来会进入模型网关、记忆层和审计系统。提前统一大小写和连接符，能避免
        `PROJECT_SAFE`、`project-safe`、`project_safe` 在不同模块里变成三个隐式分支。
        """

        return str(value or "none").strip().lower().replace("-", "_")
