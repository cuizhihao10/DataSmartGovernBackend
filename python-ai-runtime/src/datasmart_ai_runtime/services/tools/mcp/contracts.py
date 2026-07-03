"""DataSmart 出站 MCP Client 的领域合同。

MCP SDK 负责 JSON-RPC、initialize、传输和协议对象；本文件负责 DataSmart 自己的商业安全语义。二者必须
分开，否则 SDK 升级会把租户隔离、工具命名、审批和低敏结果边界一起拖入重构。

这里固定四类核心对象：

- `McpServerConfiguration`：一个外部 MCP Server 的受控连接配置；
- `McpDiscoveredTool`：远端 `tools/list` 返回内容的内部安全投影；
- `McpToolCallAdmission`：调用工具前必须由 Host/权限链生成的可信准入事实；
- `McpToolCallResult`：工具结果的运行时对象，正文可供本轮 Agent 使用，但摘要绝不回显正文。
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Mapping, Protocol

from datasmart_ai_runtime.domain.contracts import (
    ToolDefinition,
    ToolExecutionMode,
    ToolRiskLevel,
)


MCP_CLIENT_SCHEMA_VERSION = "datasmart.mcp-client.v1"
_SAFE_IDENTIFIER = re.compile(r"[^a-zA-Z0-9_.-]+")
_SENSITIVE_FIELD_PATTERN = re.compile(
    r"(password|passwd|secret|token|api[_-]?key|credential|private[_-]?key|authorization)",
    re.IGNORECASE,
)


class McpTransportType(str, Enum):
    """DataSmart 当前支持的 MCP Client 传输。

    `STREAMABLE_HTTP` 是远程、集群化部署的生产优先方案；`STDIO` 会在 Runtime 主机启动子进程，只适合
    本地工具或受控 sidecar，因此需要额外开关和命令白名单。
    """

    STREAMABLE_HTTP = "streamable-http"
    STDIO = "stdio"


@dataclass(frozen=True)
class McpServerConfiguration:
    """单个外部 MCP Server 的连接与安全配置。

    字段说明：
    - `server_id`：平台内稳定 ID，会进入工具命名空间和审计摘要，不能包含 URL、租户信息或密钥；
    - `transport`：Streamable HTTP 或 stdio；
    - `endpoint`：远程 MCP URL，仅 HTTP 传输使用；
    - `allowed_hosts`：HTTP SSRF 防护白名单，endpoint host 必须精确命中或命中显式通配后缀；
    - `auth_token_env`：Bearer Token 所在环境变量名称，不在配置中保存 token 明文；
    - `command/args/cwd`：stdio Server 的启动信息；运行时必须再检查命令和工作目录白名单；
    - `environment_keys`：允许传给 stdio 子进程的环境变量名，未列出的变量不会继承；
    - `required`：连接失败是否阻断启动期目录刷新；
    - `max_tools`：单 Server 最大工具数，防止恶意或错误 Server 返回无限目录；
    - `max_result_bytes`：单次调用结果正文的内存上限，避免外部工具把 Runtime 撑爆；
    - `default_permission`：映射出的 ToolDefinition 所需权限，后续仍由 permission-admin 最终授权。
    """

    server_id: str
    display_name: str
    transport: McpTransportType
    enabled: bool = False
    endpoint: str = ""
    allowed_hosts: tuple[str, ...] = ()
    allow_insecure_http: bool = False
    auth_token_env: str = ""
    command: str = ""
    args: tuple[str, ...] = ()
    cwd: str = ""
    environment_keys: tuple[str, ...] = ()
    required: bool = False
    connect_timeout_seconds: int = 10
    read_timeout_seconds: int = 60
    max_tools: int = 100
    max_result_bytes: int = 64 * 1024
    default_permission: str = "agent:mcp:tool:call"


@dataclass(frozen=True)
class McpDiscoveredTool:
    """远端 MCP Tool 的受控目录投影。

    远端名称保留给 SDK `call_tool` 使用；`internal_name` 使用 server namespace，避免不同 Server 都提供
    `search`、`read_file` 等同名工具时互相覆盖。`annotations` 只保留 MCP 标准布尔提示，不信任远端
    `_meta` 中的任意扩展字段。
    """

    server_id: str
    remote_name: str
    internal_name: str
    display_name: str
    description: str
    input_schema: dict[str, Any]
    output_schema: dict[str, Any]
    read_only: bool
    destructive: bool
    idempotent: bool
    open_world: bool
    task_support: str = "forbidden"

    def to_tool_definition(self, configuration: McpServerConfiguration) -> ToolDefinition:
        """转换为 Agent/模型可消费的统一 ToolDefinition。

        MCP JSON Schema 使用标准 `{type, properties, required}` 结构，而项目历史工具定义使用
        `{parameterName: definition}`。这里转换成项目现有形态，避免修改 ModelToolSchemaAdapter 和
        ToolParameterValidator；原始 schema 仍保存在 `output_schema`/字段定义中供后续严格校验。
        """

        input_schema = _project_input_schema(self.input_schema)
        sensitive_fields = tuple(
            name for name in input_schema if _SENSITIVE_FIELD_PATTERN.search(name)
        )
        high_risk = self.destructive or not self.read_only or self.open_world
        execution_mode = (
            ToolExecutionMode.APPROVAL_REQUIRED
            if high_risk
            else ToolExecutionMode.SYNC
        )
        return ToolDefinition(
            name=self.internal_name,
            display_name=self.display_name,
            description=self.description,
            risk_level=ToolRiskLevel.HIGH if high_risk else ToolRiskLevel.MEDIUM,
            execution_mode=execution_mode,
            input_schema=input_schema,
            output_schema=dict(self.output_schema),
            required_permissions=(configuration.default_permission,),
            target_service="python-ai-runtime-mcp-client",
            target_endpoint=f"mcp://{configuration.server_id}/tools/{self.remote_name}",
            read_only=self.read_only,
            requires_approval=high_risk,
            idempotent=self.idempotent,
            timeout_ms=max(1, configuration.read_timeout_seconds) * 1000,
            max_retries=0 if self.destructive else 1,
            allowed_actions=("MCP_TOOLS_CALL",),
            schema_version=MCP_CLIENT_SCHEMA_VERSION,
            descriptor_type="MCP_REMOTE_TOOL",
            protocol_hint="MCP",
            tool_type="MCP_EXTERNAL_TOOL",
            tenant_scoped=True,
            project_scoped=True,
            sensitive_fields=sensitive_fields,
            memory_write_policy="none",
            cache_policy="session_only",
        )


@dataclass(frozen=True)
class McpToolCatalogSnapshot:
    """一次 Server 工具发现快照。

    快照不保存认证头、sessionId、endpoint、stdio 参数或 `_meta`。`tools` 只包含可安全暴露给模型的目录
    字段；诊断使用 `to_summary()`，不会返回完整 JSON Schema，避免管理接口成为工具 schema 批量导出面。
    """

    server_id: str
    transport: McpTransportType
    tools: tuple[McpDiscoveredTool, ...] = ()
    connected: bool = False
    initialized: bool = False
    error_code: str | None = None

    def tool_definitions(self, configuration: McpServerConfiguration) -> tuple[ToolDefinition, ...]:
        """把发现结果转换为统一工具目录。"""

        return tuple(tool.to_tool_definition(configuration) for tool in self.tools)

    def to_summary(self) -> dict[str, Any]:
        """生成低敏目录摘要。"""

        return {
            "schemaVersion": MCP_CLIENT_SCHEMA_VERSION,
            "serverId": self.server_id,
            "transport": self.transport.value,
            "connected": self.connected,
            "initialized": self.initialized,
            "toolCount": len(self.tools),
            "readOnlyToolCount": sum(1 for tool in self.tools if tool.read_only),
            "approvalRequiredToolCount": sum(
                1 for tool in self.tools if tool.destructive or not tool.read_only or tool.open_world
            ),
            "errorCode": self.error_code,
            "toolNamesDigest": _digest(tuple(tool.internal_name for tool in self.tools)),
            "rawSchemasReturned": False,
        }


@dataclass(frozen=True)
class McpToolCallAdmission:
    """真实 `tools/call` 前的可信准入事实。

    该对象不能由模型自由生成。它应来自 DataSmart readiness、permission-admin、人工审批和 durable
    command/outbox 控制链。MCP Client 只在以下条件全部满足时执行：

    - readiness 为 READY；
    - permission 已由可信控制面授予；
    - internal tool 在本轮 allowlist；
    - 高风险工具需要 approval_verified；
    - tenant/project/workspace/actor/run/call 定位字段完整。
    """

    tenant_id: str
    project_id: str
    workspace_key: str
    actor_id: str
    run_id: str
    call_id: str
    readiness_decision: str
    permission_granted: bool
    approval_verified: bool = False
    allowed_internal_tool_names: tuple[str, ...] = ()
    source: str = "MODEL_TOOL_CALL"


@dataclass(frozen=True)
class McpToolCallRequest:
    """受控 MCP 工具调用请求。

    `arguments` 是短生命周期敏感对象，只传给 MCP SDK；禁止进入普通日志、runtime event、Prometheus label
    或 `to_summary()`。调用方应使用 payloadReference/恢复事实重建参数，而不是把参数长期存入 checkpoint。
    """

    server_id: str
    internal_tool_name: str
    arguments: Mapping[str, Any]
    admission: McpToolCallAdmission


@dataclass(frozen=True)
class McpToolCallResult:
    """MCP 调用结果。

    `content_blocks` 与 `structured_content` 可以在本轮 Agent 上下文中使用，但属于敏感运行时正文。
    `to_summary()` 只输出大小、哈希、错误和截断状态，不输出正文或结构化字段。
    """

    server_id: str
    internal_tool_name: str
    is_error: bool
    content_blocks: tuple[dict[str, Any], ...] = ()
    structured_content: dict[str, Any] | None = None
    result_byte_count: int = 0
    truncated: bool = False
    result_digest: str = ""

    def to_summary(self) -> dict[str, Any]:
        """生成可进入事件、审计和诊断的低敏摘要。"""

        return {
            "schemaVersion": MCP_CLIENT_SCHEMA_VERSION,
            "serverId": self.server_id,
            "internalToolName": self.internal_tool_name,
            "isError": self.is_error,
            "contentBlockCount": len(self.content_blocks),
            "structuredContentPresent": self.structured_content is not None,
            "resultByteCount": self.result_byte_count,
            "truncated": self.truncated,
            "resultDigest": self.result_digest,
            "resultBodyReturned": False,
        }


class McpSessionPort(Protocol):
    """SDK 无关的单次 MCP Session 端口，便于测试和未来迁移 SDK 2.x。"""

    async def list_tools(self) -> Any:
        """调用远端 `tools/list`。"""

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        """调用远端 `tools/call`。"""


class McpClientError(RuntimeError):
    """稳定、低敏的 MCP Client 错误。

    `code` 可进入事件和指标；message 不允许包含 endpoint、token、命令参数、工具参数或结果正文。
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def namespaced_tool_name(server_id: str, remote_name: str) -> str:
    """生成稳定的 MCP 内部工具名。"""

    safe_server = _SAFE_IDENTIFIER.sub("-", server_id.strip()).strip(".-").lower()
    safe_tool = _SAFE_IDENTIFIER.sub("-", remote_name.strip()).strip(".-").lower()
    if not safe_server or not safe_tool:
        raise McpClientError("MCP_TOOL_NAME_INVALID", "MCP Server 或工具名称无法形成安全内部标识。")
    return f"mcp.{safe_server}.{safe_tool}"[:128]


def result_digest(payload: Any) -> str:
    """对 MCP 结果生成稳定哈希，不在摘要中保存正文。"""

    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _project_input_schema(schema: Mapping[str, Any]) -> dict[str, Any]:
    """把标准 MCP JSON Schema 转换为项目参数定义映射。"""

    properties = schema.get("properties")
    required = set(schema.get("required") or ())
    if not isinstance(properties, Mapping):
        return {}
    converted: dict[str, Any] = {}
    for raw_name, raw_definition in properties.items():
        name = str(raw_name)
        definition = dict(raw_definition) if isinstance(raw_definition, Mapping) else {"type": "string"}
        definition["required"] = name in required
        definition.setdefault("sensitive", bool(_SENSITIVE_FIELD_PATTERN.search(name)))
        definition.setdefault("resolution", "user_required" if name in required else "derived")
        converted[name] = definition
    return converted


def _digest(values: tuple[str, ...]) -> str | None:
    """对目录名称集合生成低敏指纹。"""

    if not values:
        return None
    return hashlib.sha256("|".join(sorted(values)).encode("utf-8")).hexdigest()


__all__ = [
    "MCP_CLIENT_SCHEMA_VERSION",
    "McpClientError",
    "McpDiscoveredTool",
    "McpServerConfiguration",
    "McpSessionPort",
    "McpToolCallAdmission",
    "McpToolCallRequest",
    "McpToolCallResult",
    "McpToolCatalogSnapshot",
    "McpTransportType",
    "namespaced_tool_name",
    "result_digest",
]
