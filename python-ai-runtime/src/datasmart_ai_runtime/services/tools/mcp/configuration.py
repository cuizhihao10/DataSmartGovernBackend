"""出站 MCP Client 配置读取与安全校验。

配置解析和网络/进程连接必须分离：解析阶段不访问 endpoint、不启动 stdio 进程，只把环境变量转换成
稳定对象并执行 fail-closed 校验。这样运维可以在启动诊断中看到配置问题，而不会因为打开诊断页就产生
网络或进程副作用。
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlsplit

from datasmart_ai_runtime.services.tools.mcp.contracts import (
    McpClientError,
    McpServerConfiguration,
    McpTransportType,
)


@dataclass(frozen=True)
class McpClientRuntimeSettings:
    """MCP Client 全局运行设置。

    - `enabled`：总开关；默认 false，避免配置文件出现 Server 后就自动联网；
    - `fail_open`：非 required Server 失败时是否继续启动并忽略其工具；
    - `stdio_enabled`：stdio 子进程总开关，独立于 MCP 总开关；
    - `stdio_allowed_commands`：允许启动的可执行文件 basename；
    - `stdio_allowed_roots`：stdio cwd 允许根目录；
    - `discovery_on_startup`：是否在 API 创建阶段真实连接并加载远端工具目录。
    """

    enabled: bool = False
    fail_open: bool = True
    stdio_enabled: bool = False
    stdio_allowed_commands: tuple[str, ...] = ()
    stdio_allowed_roots: tuple[str, ...] = ()
    discovery_on_startup: bool = False


def mcp_client_runtime_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> McpClientRuntimeSettings:
    """读取 MCP 全局环境变量。"""

    source = environ if environ is not None else os.environ
    return McpClientRuntimeSettings(
        enabled=_truthy(source.get("DATASMART_AI_MCP_ENABLED"), default=False),
        fail_open=_truthy(source.get("DATASMART_AI_MCP_FAIL_OPEN"), default=True),
        stdio_enabled=_truthy(source.get("DATASMART_AI_MCP_STDIO_ENABLED"), default=False),
        stdio_allowed_commands=_csv(source.get("DATASMART_AI_MCP_STDIO_ALLOWED_COMMANDS")),
        stdio_allowed_roots=_csv(source.get("DATASMART_AI_MCP_STDIO_ALLOWED_ROOTS")),
        discovery_on_startup=_truthy(
            source.get("DATASMART_AI_MCP_DISCOVERY_ON_STARTUP"),
            default=False,
        ),
    )


def mcp_server_configurations_from_env(
    environ: Mapping[str, str] | None = None,
) -> tuple[McpServerConfiguration, ...]:
    """从 JSON 环境变量读取 MCP Server 清单。

    使用单个 JSON 数组而不是不断增长的编号环境变量，是为了让 Kubernetes Secret/ConfigMap、Helm values、
    Nacos 和企业配置中心更容易整体管理。JSON 中禁止保存 token；只能配置 `authTokenEnv` 指向 Secret
    注入的环境变量。
    """

    source = environ if environ is not None else os.environ
    raw = (source.get("DATASMART_AI_MCP_SERVERS_JSON") or "").strip()
    if not raw:
        return ()
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise McpClientError("MCP_CONFIG_JSON_INVALID", "MCP Server JSON 配置无法解析。") from exc
    if not isinstance(payload, list):
        raise McpClientError("MCP_CONFIG_SHAPE_INVALID", "MCP Server 配置必须是 JSON 数组。")
    configurations = tuple(_server_configuration(item) for item in payload)
    server_ids = [configuration.server_id for configuration in configurations]
    if len(server_ids) != len(set(server_ids)):
        raise McpClientError("MCP_CONFIG_SERVER_ID_DUPLICATED", "MCP Server ID 不能重复。")
    return configurations


def validate_mcp_server_configuration(
    configuration: McpServerConfiguration,
    runtime: McpClientRuntimeSettings,
) -> None:
    """在连接前验证 Server 安全边界。

    校验失败时不会输出 endpoint、command args、token 环境变量值或 cwd，只返回稳定错误码。调用方可以
    根据 required/fail-open 决定阻断启动还是跳过该 Server。
    """

    if not configuration.server_id.strip():
        raise McpClientError("MCP_SERVER_ID_REQUIRED", "MCP Server 必须配置稳定 ID。")
    if configuration.transport == McpTransportType.STREAMABLE_HTTP:
        _validate_streamable_http(configuration)
        return
    _validate_stdio(configuration, runtime)


def mcp_configuration_diagnostics(
    runtime: McpClientRuntimeSettings,
    configurations: tuple[McpServerConfiguration, ...],
) -> dict[str, Any]:
    """生成不包含地址、命令参数和凭据的配置诊断。"""

    servers = []
    for configuration in configurations:
        error_code = None
        try:
            validate_mcp_server_configuration(configuration, runtime)
        except McpClientError as exc:
            error_code = exc.code
        servers.append(
            {
                "serverId": configuration.server_id,
                "displayName": configuration.display_name,
                "enabled": configuration.enabled,
                "required": configuration.required,
                "transport": configuration.transport.value,
                "configurationValid": error_code is None,
                "configurationErrorCode": error_code,
                "authTokenConfigured": bool(
                    configuration.auth_token_env
                    and os.environ.get(configuration.auth_token_env)
                ),
                "endpointReturned": False,
                "commandArgumentsReturned": False,
            }
        )
    return {
        "component": "datasmart-outbound-mcp-client",
        "enabled": runtime.enabled,
        "failOpen": runtime.fail_open,
        "discoveryOnStartup": runtime.discovery_on_startup,
        "stdioEnabled": runtime.stdio_enabled,
        "configuredServerCount": len(configurations),
        "enabledServerCount": sum(1 for item in configurations if item.enabled),
        "servers": servers,
    }


def _server_configuration(payload: Any) -> McpServerConfiguration:
    """把单个 JSON 对象转换为配置。"""

    if not isinstance(payload, Mapping):
        raise McpClientError("MCP_CONFIG_ITEM_INVALID", "每个 MCP Server 配置必须是 JSON 对象。")
    transport = _transport(payload.get("transport"))
    server_id = str(payload.get("serverId") or payload.get("server_id") or "").strip()
    return McpServerConfiguration(
        server_id=server_id,
        display_name=str(payload.get("displayName") or server_id or "MCP Server").strip(),
        transport=transport,
        enabled=bool(payload.get("enabled", False)),
        endpoint=str(payload.get("endpoint") or "").strip(),
        allowed_hosts=_string_tuple(payload.get("allowedHosts")),
        allow_insecure_http=bool(payload.get("allowInsecureHttp", False)),
        auth_token_env=str(payload.get("authTokenEnv") or "").strip(),
        command=str(payload.get("command") or "").strip(),
        args=_string_tuple(payload.get("args")),
        cwd=str(payload.get("cwd") or "").strip(),
        environment_keys=_string_tuple(payload.get("environmentKeys")),
        required=bool(payload.get("required", False)),
        connect_timeout_seconds=_bounded_int(payload.get("connectTimeoutSeconds"), 10, 1, 120),
        read_timeout_seconds=_bounded_int(payload.get("readTimeoutSeconds"), 60, 1, 600),
        max_tools=_bounded_int(payload.get("maxTools"), 100, 1, 1000),
        max_result_bytes=_bounded_int(payload.get("maxResultBytes"), 64 * 1024, 1024, 1024 * 1024),
        default_permission=str(payload.get("defaultPermission") or "agent:mcp:tool:call").strip(),
    )


def _validate_streamable_http(configuration: McpServerConfiguration) -> None:
    """校验远程 Streamable HTTP endpoint，阻断 SSRF 和凭据误放。"""

    parts = urlsplit(configuration.endpoint)
    if parts.scheme not in {"https", "http"} or not parts.hostname:
        raise McpClientError("MCP_HTTP_ENDPOINT_INVALID", "MCP HTTP endpoint 必须是绝对 HTTP(S) URL。")
    if parts.username or parts.password or parts.query or parts.fragment:
        raise McpClientError(
            "MCP_HTTP_ENDPOINT_SENSITIVE_COMPONENT",
            "MCP endpoint 禁止包含用户信息、query 或 fragment。",
        )
    if parts.scheme != "https" and not configuration.allow_insecure_http:
        raise McpClientError("MCP_HTTP_TLS_REQUIRED", "远程 MCP Server 默认必须使用 HTTPS。")
    if not configuration.allowed_hosts:
        raise McpClientError("MCP_HTTP_HOST_ALLOWLIST_REQUIRED", "MCP HTTP Server 必须配置 host allowlist。")
    if not _host_allowed(parts.hostname, configuration.allowed_hosts):
        raise McpClientError("MCP_HTTP_HOST_NOT_ALLOWED", "MCP endpoint host 不在 allowlist。")


def _validate_stdio(
    configuration: McpServerConfiguration,
    runtime: McpClientRuntimeSettings,
) -> None:
    """校验 stdio 子进程策略。"""

    if not runtime.stdio_enabled:
        raise McpClientError("MCP_STDIO_DISABLED", "MCP stdio 传输未启用。")
    command_name = Path(configuration.command).name.lower()
    allowed_commands = {Path(item).name.lower() for item in runtime.stdio_allowed_commands}
    if not command_name or command_name not in allowed_commands:
        raise McpClientError("MCP_STDIO_COMMAND_NOT_ALLOWED", "MCP stdio command 不在白名单。")
    if not configuration.cwd:
        raise McpClientError("MCP_STDIO_CWD_REQUIRED", "MCP stdio Server 必须配置受控工作目录。")
    cwd = Path(configuration.cwd).resolve()
    allowed_roots = tuple(Path(root).resolve() for root in runtime.stdio_allowed_roots)
    if not allowed_roots or not any(cwd == root or root in cwd.parents for root in allowed_roots):
        raise McpClientError("MCP_STDIO_CWD_NOT_ALLOWED", "MCP stdio cwd 不在允许根目录。")
    if any("\x00" in value or len(value) > 500 for value in configuration.args):
        raise McpClientError("MCP_STDIO_ARGUMENT_INVALID", "MCP stdio 参数包含非法或超长值。")


def _host_allowed(host: str, allowlist: tuple[str, ...]) -> bool:
    """执行精确 host 或显式 `*.example.com` 后缀匹配。"""

    normalized = host.lower().rstrip(".")
    for item in allowlist:
        candidate = item.lower().strip().rstrip(".")
        if candidate.startswith("*."):
            suffix = candidate[1:]
            if normalized.endswith(suffix) and normalized != suffix.lstrip("."):
                return True
        elif normalized == candidate:
            return True
    return False


def _transport(value: Any) -> McpTransportType:
    """规范化传输类型。旧 SSE 不作为新接入默认。"""

    normalized = str(value or "streamable-http").strip().lower().replace("_", "-")
    aliases = {"http": "streamable-http", "streamablehttp": "streamable-http"}
    normalized = aliases.get(normalized, normalized)
    try:
        return McpTransportType(normalized)
    except ValueError as exc:
        raise McpClientError(
            "MCP_TRANSPORT_UNSUPPORTED",
            "MCP transport 只支持 streamable-http 或 stdio。",
        ) from exc


def _string_tuple(value: Any) -> tuple[str, ...]:
    """读取 JSON 字符串数组。"""

    if value is None:
        return ()
    if not isinstance(value, list):
        raise McpClientError("MCP_CONFIG_ARRAY_FIELD_INVALID", "MCP 数组配置字段必须使用 JSON array。")
    return tuple(str(item).strip() for item in value if str(item).strip())


def _bounded_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    """读取并裁剪整数配置。"""

    if value in (None, ""):
        return default
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise McpClientError("MCP_CONFIG_INTEGER_INVALID", "MCP 数字配置无法解析。") from exc
    return max(minimum, min(maximum, parsed))


def _truthy(value: str | None, *, default: bool) -> bool:
    """读取布尔环境变量。"""

    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"true", "1", "yes", "on", "enabled"}


def _csv(value: str | None) -> tuple[str, ...]:
    """读取逗号分隔白名单。"""

    return tuple(item.strip() for item in (value or "").split(",") if item.strip())


__all__ = [
    "McpClientRuntimeSettings",
    "mcp_client_runtime_settings_from_env",
    "mcp_configuration_diagnostics",
    "mcp_server_configurations_from_env",
    "validate_mcp_server_configuration",
]
