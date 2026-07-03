"""官方 MCP Python SDK 1.x 传输适配器。

本文件是项目中唯一直接依赖 `mcp`/`httpx` SDK 类型的位置。上层只依赖 `McpSessionPort`，因此未来
升级 MCP SDK 2.x 时，协议生命周期变化被限制在这个小适配器中。

当前 SDK 版本策略：
- 使用当前稳定的 `mcp>=1.27,<2`；
- 不采用 2.0 alpha/beta 作为商业运行依赖；
- 远程 Server 使用 Streamable HTTP；
- stdio 只在全局与 Server 两级校验均通过后启动，并且从不使用 shell。
"""

from __future__ import annotations

import os
from contextlib import AsyncExitStack, asynccontextmanager
from datetime import timedelta
from pathlib import Path
from typing import Any, AsyncIterator

from datasmart_ai_runtime.services.tools.mcp.contracts import (
    McpClientError,
    McpServerConfiguration,
    McpSessionPort,
    McpTransportType,
)


@asynccontextmanager
async def open_official_mcp_session(
    configuration: McpServerConfiguration,
) -> AsyncIterator[McpSessionPort]:
    """创建、initialize 并关闭一次官方 SDK ClientSession。

    为什么当前使用“每次操作一条短 Session”：
    - 先保证生命周期、异常和 secret 边界正确；
    - 避免在 FastAPI 全局对象中错误复用已关闭 anyio stream；
    - 对低频目录刷新和首版调用足够稳定。

    后续高并发生产可在本适配器之上增加按 Server 的连接池、session renewal、并发上限和断路器，而不改变
    工具目录或 Agent 调用合同。
    """

    try:
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client
        from mcp.client.streamable_http import streamable_http_client
    except ImportError as exc:
        raise McpClientError(
            "MCP_SDK_NOT_INSTALLED",
            "启用 MCP Client 需要安装 python-ai-runtime[mcp]。",
        ) from exc

    async with AsyncExitStack() as stack:
        if configuration.transport == McpTransportType.STREAMABLE_HTTP:
            http_client = await stack.enter_async_context(_http_client(configuration))
            streams = await stack.enter_async_context(
                streamable_http_client(
                    configuration.endpoint,
                    http_client=http_client,
                    terminate_on_close=True,
                )
            )
            read_stream, write_stream, _ = streams
        else:
            parameters = StdioServerParameters(
                command=configuration.command,
                args=list(configuration.args),
                env=_stdio_environment(configuration),
                cwd=Path(configuration.cwd),
            )
            read_stream, write_stream = await stack.enter_async_context(stdio_client(parameters))

        session = await stack.enter_async_context(
            ClientSession(
                read_stream,
                write_stream,
                read_timeout_seconds=timedelta(seconds=max(1, configuration.read_timeout_seconds)),
            )
        )
        try:
            await session.initialize()
        except Exception as exc:
            raise McpClientError(
                "MCP_INITIALIZE_FAILED",
                "MCP Server initialize 失败；地址、凭据与上游响应已隐藏。",
            ) from exc
        yield session


@asynccontextmanager
async def _http_client(configuration: McpServerConfiguration) -> AsyncIterator[Any]:
    """创建不跟随重定向的受控 HTTP Client。

    重定向可能把 Authorization Header 带向非 allowlist host，因此默认 `follow_redirects=False`。
    Bearer Token 只从环境变量读取，并只存在于 AsyncClient 生命周期内。
    """

    try:
        import httpx
    except ImportError as exc:
        raise McpClientError(
            "MCP_HTTP_CLIENT_NOT_INSTALLED",
            "MCP Streamable HTTP 需要官方 SDK 提供的 httpx 依赖。",
        ) from exc

    headers = {
        "User-Agent": "datasmart-govern-mcp-client/1.0",
        "Accept": "application/json, text/event-stream",
    }
    if configuration.auth_token_env:
        token = os.environ.get(configuration.auth_token_env)
        if not token:
            raise McpClientError(
                "MCP_AUTH_TOKEN_MISSING",
                "MCP Server 配置了 token 环境变量，但运行环境未注入该 Secret。",
            )
        headers["Authorization"] = f"Bearer {token}"
    timeout = httpx.Timeout(
        connect=max(1, configuration.connect_timeout_seconds),
        read=max(1, configuration.read_timeout_seconds),
        write=max(1, configuration.read_timeout_seconds),
        pool=max(1, configuration.connect_timeout_seconds),
    )
    async with httpx.AsyncClient(
        headers=headers,
        timeout=timeout,
        follow_redirects=False,
    ) as client:
        yield client


def _stdio_environment(configuration: McpServerConfiguration) -> dict[str, str]:
    """构造 stdio Server 最小环境变量。

    MCP SDK 会把它与 SDK 自身的安全默认环境合并。DataSmart 这里只追加显式 allowlist 中的变量，
    不会把整个父进程环境、数据库 DSN、模型 API Key 或网关签名密钥传给任意外部 Server。
    """

    environment: dict[str, str] = {}
    for key in configuration.environment_keys:
        value = os.environ.get(key)
        if value is not None:
            environment[key] = value
    return environment


__all__ = ["open_official_mcp_session"]
