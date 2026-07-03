"""DataSmart 出站 MCP Client 的目录发现与受控调用运行时。

`McpClientRuntime` 是 Agent Host 和官方 MCP SDK 之间的治理层：

1. 连接前验证 HTTP host/stdio command 边界；
2. 执行 `initialize + tools/list`，把远端工具映射成统一 `ToolDefinition`；
3. 调用前验证 readiness、permission、approval、allowlist 与参数大小；
4. 执行真实 `tools/call`，但把结果正文限制在本轮内存对象；
5. 对诊断只返回工具数量、摘要哈希和稳定错误码。

该运行时没有把 MCP 当成“绕过 Java 控制面的快捷 HTTP 客户端”。相反，它要求所有调用携带可信 admission，
以便后续与 command proposal、durable outbox、worker receipt 和 LangGraph execution gate 对齐。
"""

from __future__ import annotations

import asyncio
import json
import re
from collections.abc import Callable, Mapping
from concurrent.futures import ThreadPoolExecutor
from typing import Any, AsyncContextManager

from datasmart_ai_runtime.domain.contracts import ToolDefinition
from datasmart_ai_runtime.services.tools.mcp.configuration import (
    McpClientRuntimeSettings,
    mcp_configuration_diagnostics,
    validate_mcp_server_configuration,
)
from datasmart_ai_runtime.services.tools.mcp.contracts import (
    McpClientError,
    McpDiscoveredTool,
    McpServerConfiguration,
    McpSessionPort,
    McpToolCallRequest,
    McpToolCallResult,
    McpToolCatalogSnapshot,
    namespaced_tool_name,
    result_digest,
)
from datasmart_ai_runtime.services.tools.mcp.official_sdk import open_official_mcp_session


McpSessionOpener = Callable[[McpServerConfiguration], AsyncContextManager[McpSessionPort]]
_MAX_ARGUMENT_BYTES = 64 * 1024
_SENSITIVE_ARGUMENT_NAMES = {
    "password",
    "passwd",
    "secret",
    "token",
    "access_token",
    "api_key",
    "apikey",
    "authorization",
    "private_key",
    "credential",
}
_SENSITIVE_ARGUMENT_COMPACT_NAMES = {
    "password",
    "passwd",
    "secret",
    "token",
    "accesstoken",
    "apikey",
    "authorization",
    "privatekey",
    "credential",
}


class McpClientRuntime:
    """管理多个外部 MCP Server 的轻量运行时。

    当前不维护长连接池。目录发现和工具调用各自创建短 Session，优先保证生命周期与安全边界清晰。
    `_snapshots` 只缓存低敏工具描述，不缓存 session、token、工具参数或结果正文。
    """

    def __init__(
        self,
        *,
        settings: McpClientRuntimeSettings,
        configurations: tuple[McpServerConfiguration, ...],
        session_opener: McpSessionOpener = open_official_mcp_session,
    ) -> None:
        self._settings = settings
        self._configurations = {item.server_id: item for item in configurations}
        self._session_opener = session_opener
        self._snapshots: dict[str, McpToolCatalogSnapshot] = {}

    async def discover_server(self, server_id: str) -> McpToolCatalogSnapshot:
        """真实连接一个 Server 并分页读取 `tools/list`。

        工具 annotations 只作为风险提示，绝不作为授权事实。远端未声明 readOnly 时按可写处理，未声明
        destructive/openWorld 时按高风险默认值处理，这是面对不可信 Server 的 fail-closed 策略。
        """

        configuration = self._required_enabled_configuration(server_id)
        try:
            validate_mcp_server_configuration(configuration, self._settings)
            tools: list[McpDiscoveredTool] = []
            cursor: str | None = None
            async with self._session_opener(configuration) as session:
                while True:
                    response = await _list_tools(session, cursor)
                    for raw_tool in tuple(getattr(response, "tools", ()) or ()):
                        tools.append(_discovered_tool(configuration.server_id, raw_tool))
                        if len(tools) > configuration.max_tools:
                            raise McpClientError(
                                "MCP_TOOL_LIMIT_EXCEEDED",
                                "MCP Server 返回工具数量超过配置上限。",
                            )
                    cursor = _optional_text(getattr(response, "nextCursor", None))
                    if not cursor:
                        break
            snapshot = McpToolCatalogSnapshot(
                server_id=configuration.server_id,
                transport=configuration.transport,
                tools=tuple(tools),
                connected=True,
                initialized=True,
            )
        except McpClientError:
            raise
        except Exception as exc:
            raise McpClientError(
                "MCP_DISCOVERY_FAILED",
                "MCP 工具目录发现失败；地址、凭据和上游响应已隐藏。",
            ) from exc
        self._snapshots[server_id] = snapshot
        return snapshot

    async def discover_all(self) -> tuple[McpToolCatalogSnapshot, ...]:
        """发现全部启用 Server，并按 required/fail-open 决定失败行为。"""

        if not self._settings.enabled:
            return ()
        snapshots: list[McpToolCatalogSnapshot] = []
        for configuration in self._configurations.values():
            if not configuration.enabled:
                continue
            try:
                snapshots.append(await self.discover_server(configuration.server_id))
            except McpClientError as exc:
                if configuration.required or not self._settings.fail_open:
                    raise
                fallback = McpToolCatalogSnapshot(
                    server_id=configuration.server_id,
                    transport=configuration.transport,
                    connected=False,
                    initialized=False,
                    error_code=exc.code,
                )
                self._snapshots[configuration.server_id] = fallback
                snapshots.append(fallback)
        return tuple(snapshots)

    def discover_all_sync(self) -> tuple[McpToolCatalogSnapshot, ...]:
        """在同步 API bootstrap 中执行目录发现。

        普通脚本和多数 app factory 在事件循环启动前执行，可直接使用 `asyncio.run`。部分 Uvicorn/ASGI
        版本会在已运行的事件循环里调用 `--factory`，此时不能嵌套 `asyncio.run`；这里改用一个短生命周期
        专用线程执行完整发现并等待结果。Session 在该线程内创建和关闭，不跨事件循环复用。

        启动发现本来就是显式 opt-in 的阻塞步骤。生产如果 Server 很多，应进一步迁移到 lifespan 异步预热
        和可原子替换 ToolPlanner 的目录快照，而不是无限增加启动线程。
        """

        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(self.discover_all())
        with ThreadPoolExecutor(max_workers=1, thread_name_prefix="datasmart-mcp-bootstrap") as executor:
            return executor.submit(lambda: asyncio.run(self.discover_all())).result()

    def merged_tool_definitions(
        self,
        base_tools: tuple[ToolDefinition, ...],
    ) -> tuple[ToolDefinition, ...]:
        """把已发现 MCP 工具合并到平台工具目录。

        内部 MCP 名称带 server namespace，理论上不会与原生工具冲突；这里仍显式拒绝重复，防止错误配置
        静默覆盖 `datasource.metadata.read` 等平台关键工具。
        """

        merged = list(base_tools)
        existing_names = {tool.name for tool in base_tools}
        for server_id, snapshot in self._snapshots.items():
            configuration = self._configurations[server_id]
            for tool in snapshot.tool_definitions(configuration):
                if tool.name in existing_names:
                    raise McpClientError("MCP_TOOL_NAME_CONFLICT", "MCP 工具内部名称与现有目录冲突。")
                existing_names.add(tool.name)
                merged.append(tool)
        return tuple(merged)

    async def call_tool(self, request: McpToolCallRequest) -> McpToolCallResult:
        """执行一次具备可信准入事实的真实 MCP `tools/call`。"""

        configuration = self._required_enabled_configuration(request.server_id)
        validate_mcp_server_configuration(configuration, self._settings)
        snapshot = self._snapshots.get(request.server_id)
        if snapshot is None or not snapshot.initialized:
            snapshot = await self.discover_server(request.server_id)
        tool = next(
            (item for item in snapshot.tools if item.internal_name == request.internal_tool_name),
            None,
        )
        if tool is None:
            raise McpClientError("MCP_TOOL_NOT_DISCOVERED", "请求工具不在当前 MCP 目录快照中。")
        _validate_admission(request, tool)
        arguments = _validate_arguments(request.arguments)
        try:
            async with self._session_opener(configuration) as session:
                raw_result = await session.call_tool(tool.remote_name, arguments)
        except McpClientError:
            raise
        except Exception as exc:
            raise McpClientError(
                "MCP_TOOL_CALL_FAILED",
                "MCP tools/call 失败；参数、地址、凭据和上游响应已隐藏。",
            ) from exc
        return _normalize_call_result(
            configuration,
            request.internal_tool_name,
            raw_result,
        )

    def diagnostics(self) -> dict[str, Any]:
        """输出配置与最近目录快照的低敏诊断。"""

        diagnostics = mcp_configuration_diagnostics(
            self._settings,
            tuple(self._configurations.values()),
        )
        diagnostics["catalogSnapshots"] = [
            snapshot.to_summary()
            for snapshot in sorted(self._snapshots.values(), key=lambda item: item.server_id)
        ]
        diagnostics["discoveredToolCount"] = sum(
            len(snapshot.tools) for snapshot in self._snapshots.values()
        )
        diagnostics["sdkVersionPolicy"] = "mcp>=1.27,<2"
        diagnostics["toolResultBodiesReturned"] = False
        return diagnostics

    def _required_enabled_configuration(self, server_id: str) -> McpServerConfiguration:
        """读取启用配置，并验证 MCP 总开关。"""

        if not self._settings.enabled:
            raise McpClientError("MCP_CLIENT_DISABLED", "MCP Client 未启用。")
        configuration = self._configurations.get(server_id)
        if configuration is None:
            raise McpClientError("MCP_SERVER_NOT_CONFIGURED", "MCP Server 未配置。")
        if not configuration.enabled:
            raise McpClientError("MCP_SERVER_DISABLED", "MCP Server 未启用。")
        return configuration


async def _list_tools(session: McpSessionPort, cursor: str | None) -> Any:
    """兼容 SDK 与测试 fake 的分页调用。"""

    if cursor:
        return await session.list_tools(cursor)
    return await session.list_tools()


def _discovered_tool(server_id: str, raw_tool: Any) -> McpDiscoveredTool:
    """把 SDK Tool 转换成保守风险投影。"""

    remote_name = str(getattr(raw_tool, "name", "") or "").strip()
    if not remote_name:
        raise McpClientError("MCP_REMOTE_TOOL_NAME_MISSING", "MCP Server 返回了无名称工具。")
    annotations = getattr(raw_tool, "annotations", None)
    read_only = getattr(annotations, "readOnlyHint", None) is True
    destructive = False if read_only else getattr(annotations, "destructiveHint", None) is not False
    idempotent = getattr(annotations, "idempotentHint", None) is True
    open_world = getattr(annotations, "openWorldHint", None) is not False
    title = str(getattr(annotations, "title", "") or "").strip()
    execution = getattr(raw_tool, "execution", None)
    return McpDiscoveredTool(
        server_id=server_id,
        remote_name=remote_name,
        internal_name=namespaced_tool_name(server_id, remote_name),
        display_name=title or remote_name,
        description=str(getattr(raw_tool, "description", "") or "外部 MCP 工具。")[:2000],
        input_schema=_mapping(getattr(raw_tool, "inputSchema", None)),
        output_schema=_mapping(getattr(raw_tool, "outputSchema", None)),
        read_only=read_only,
        destructive=destructive,
        idempotent=idempotent,
        open_world=open_world,
        task_support=str(getattr(execution, "taskSupport", None) or "forbidden"),
    )


def _validate_admission(request: McpToolCallRequest, tool: McpDiscoveredTool) -> None:
    """验证调用是否具备 DataSmart Host 可信事实。"""

    admission = request.admission
    required_fields = (
        admission.tenant_id,
        admission.project_id,
        admission.workspace_key,
        admission.actor_id,
        admission.run_id,
        admission.call_id,
    )
    if not all(str(value).strip() for value in required_fields):
        raise McpClientError("MCP_ADMISSION_SCOPE_INCOMPLETE", "MCP 调用缺少完整租户、工作区或运行定位。")
    if admission.readiness_decision.strip().upper() != "READY":
        raise McpClientError("MCP_ADMISSION_NOT_READY", "MCP 工具未通过 readiness。")
    if not admission.permission_granted:
        raise McpClientError("MCP_PERMISSION_DENIED", "MCP 工具权限未获可信控制面授予。")
    if request.internal_tool_name not in admission.allowed_internal_tool_names:
        raise McpClientError("MCP_TOOL_NOT_ALLOWED_IN_TURN", "MCP 工具不在本轮准入 allowlist。")
    high_risk = tool.destructive or not tool.read_only or tool.open_world
    if high_risk and not admission.approval_verified:
        raise McpClientError("MCP_APPROVAL_REQUIRED", "高风险 MCP 工具缺少已验证人工审批。")


def _validate_arguments(arguments: Mapping[str, Any]) -> dict[str, Any]:
    """限制 MCP 参数大小，并拒绝模型内联凭据。"""

    copied = dict(arguments)
    serialized = json.dumps(copied, ensure_ascii=False, default=str).encode("utf-8")
    if len(serialized) > _MAX_ARGUMENT_BYTES:
        raise McpClientError("MCP_ARGUMENTS_TOO_LARGE", "MCP 工具参数超过 64KB 上限。")
    if _contains_sensitive_argument(copied):
        raise McpClientError(
            "MCP_INLINE_SECRET_REJECTED",
            "MCP 工具参数包含疑似凭据字段；Secret 必须由 Server 环境或授权层注入。",
        )
    return copied


def _contains_sensitive_argument(value: Any) -> bool:
    """递归检查参数键名，不记录命中的字段名和值。"""

    if isinstance(value, Mapping):
        for key, nested in value.items():
            normalized = re.sub(r"(?<!^)(?=[A-Z])", "_", str(key)).lower().replace("-", "_")
            compact = re.sub(r"[^a-z0-9]", "", normalized)
            if (
                normalized in _SENSITIVE_ARGUMENT_NAMES
                or compact in _SENSITIVE_ARGUMENT_COMPACT_NAMES
                or _contains_sensitive_argument(nested)
            ):
                return True
    elif isinstance(value, (list, tuple)):
        return any(_contains_sensitive_argument(item) for item in value)
    return False


def _normalize_call_result(
    configuration: McpServerConfiguration,
    internal_tool_name: str,
    raw_result: Any,
) -> McpToolCallResult:
    """把 SDK CallToolResult 转换成有界运行时结果。"""

    raw_blocks = tuple(_model_dump(item) for item in tuple(getattr(raw_result, "content", ()) or ()))
    raw_structured = getattr(raw_result, "structuredContent", None)
    full_payload = {
        "content": raw_blocks,
        "structuredContent": raw_structured,
        "isError": bool(getattr(raw_result, "isError", False)),
    }
    encoded = json.dumps(full_payload, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")
    blocks, structured, truncated = _bounded_result_content(
        raw_blocks,
        _mapping_or_none(raw_structured),
        configuration.max_result_bytes,
    )
    return McpToolCallResult(
        server_id=configuration.server_id,
        internal_tool_name=internal_tool_name,
        is_error=bool(getattr(raw_result, "isError", False)),
        content_blocks=blocks,
        structured_content=structured,
        result_byte_count=len(encoded),
        truncated=truncated,
        result_digest=result_digest(full_payload),
    )


def _bounded_result_content(
    blocks: tuple[dict[str, Any], ...],
    structured: dict[str, Any] | None,
    limit: int,
) -> tuple[tuple[dict[str, Any], ...], dict[str, Any] | None, bool]:
    """按字节预算保留结果块；超限文本只保留安全前缀。"""

    remaining = max(1024, limit)
    selected: list[dict[str, Any]] = []
    truncated = False
    for block in blocks:
        encoded = json.dumps(block, ensure_ascii=False, default=str).encode("utf-8")
        if len(encoded) <= remaining:
            selected.append(block)
            remaining -= len(encoded)
            continue
        text = block.get("text")
        if isinstance(text, str) and remaining > 128:
            prefix = text.encode("utf-8")[: max(0, remaining - 128)].decode("utf-8", errors="ignore")
            selected.append({"type": block.get("type", "text"), "text": prefix})
        truncated = True
        break
    selected_structured = structured
    if structured is not None:
        structured_size = len(json.dumps(structured, ensure_ascii=False, default=str).encode("utf-8"))
        if structured_size > remaining:
            selected_structured = None
            truncated = True
    return tuple(selected), selected_structured, truncated


def _model_dump(value: Any) -> dict[str, Any]:
    """兼容 Pydantic SDK 对象与测试字典。"""

    if isinstance(value, Mapping):
        return dict(value)
    dump = getattr(value, "model_dump", None)
    if callable(dump):
        return dict(dump(by_alias=True, exclude_none=True))
    return {"type": type(value).__name__, "value": str(value)}


def _mapping(value: Any) -> dict[str, Any]:
    """把外部 schema 规范化为字典。"""

    return dict(value) if isinstance(value, Mapping) else {}


def _mapping_or_none(value: Any) -> dict[str, Any] | None:
    """把结构化结果规范化为可选字典。"""

    return dict(value) if isinstance(value, Mapping) else None


def _optional_text(value: Any) -> str | None:
    """读取可选分页游标。"""

    text = str(value or "").strip()
    return text or None


__all__ = ["McpClientRuntime", "McpSessionOpener"]
