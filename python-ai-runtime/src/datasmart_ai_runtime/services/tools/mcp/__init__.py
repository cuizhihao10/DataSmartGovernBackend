"""DataSmart 出站 MCP Client 能力包。

该包负责把外部 MCP Server 的 tools/list 与 tools/call 接入平台统一工具治理，不负责绕过权限执行工具。
"""

from datasmart_ai_runtime.services.tools.mcp.configuration import (
    McpClientRuntimeSettings,
    mcp_client_runtime_settings_from_env,
    mcp_configuration_diagnostics,
    mcp_server_configurations_from_env,
    validate_mcp_server_configuration,
)
from datasmart_ai_runtime.services.tools.mcp.contracts import (
    MCP_CLIENT_SCHEMA_VERSION,
    McpClientError,
    McpDiscoveredTool,
    McpServerConfiguration,
    McpSessionPort,
    McpToolCallAdmission,
    McpToolCallRequest,
    McpToolCallResult,
    McpToolCatalogSnapshot,
    McpTransportType,
    namespaced_tool_name,
)
from datasmart_ai_runtime.services.tools.mcp.official_sdk import open_official_mcp_session
from datasmart_ai_runtime.services.tools.mcp.runtime import McpClientRuntime, McpSessionOpener

__all__ = [
    "MCP_CLIENT_SCHEMA_VERSION",
    "McpClientError",
    "McpClientRuntime",
    "McpClientRuntimeSettings",
    "McpDiscoveredTool",
    "McpServerConfiguration",
    "McpSessionOpener",
    "McpSessionPort",
    "McpToolCallAdmission",
    "McpToolCallRequest",
    "McpToolCallResult",
    "McpToolCatalogSnapshot",
    "McpTransportType",
    "mcp_client_runtime_settings_from_env",
    "mcp_configuration_diagnostics",
    "mcp_server_configurations_from_env",
    "namespaced_tool_name",
    "open_official_mcp_session",
    "validate_mcp_server_configuration",
]
