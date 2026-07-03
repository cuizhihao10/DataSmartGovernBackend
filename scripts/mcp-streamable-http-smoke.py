#!/usr/bin/env python3
"""使用本地 Streamable HTTP Server 验证 DataSmart 远程 MCP Client。

该 smoke 只绑定 `127.0.0.1`，不访问公网。它补充 stdio smoke，验证生产优先传输的 initialize、tools/list、
tools/call、HTTP Session 与结果低敏边界。
"""

from __future__ import annotations

import asyncio
import multiprocessing
import socket
import sys
import time
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
PYTHON_RUNTIME_SRC = REPO_ROOT / "python-ai-runtime" / "src"
if str(PYTHON_RUNTIME_SRC) not in sys.path:
    sys.path.insert(0, str(PYTHON_RUNTIME_SRC))

from datasmart_ai_runtime.services.tools.mcp import (  # noqa: E402
    McpClientRuntime,
    McpClientRuntimeSettings,
    McpServerConfiguration,
    McpToolCallAdmission,
    McpToolCallRequest,
    McpTransportType,
)


HOST = "127.0.0.1"
PORT = 18990


def run_server() -> None:
    """在独立进程启动最小无副作用 FastMCP Server。"""

    from mcp.server.fastmcp import FastMCP
    from mcp.types import ToolAnnotations

    server = FastMCP(
        "datasmart-mcp-http-smoke",
        host=HOST,
        port=PORT,
        stateless_http=True,
        json_response=True,
        log_level="WARNING",
    )

    @server.tool(
        name="datasmart_http_smoke_echo",
        annotations=ToolAnnotations(
            readOnlyHint=True,
            destructiveHint=False,
            idempotentHint=True,
            openWorldHint=False,
        ),
        structured_output=True,
    )
    def echo(message: str) -> dict[str, str]:
        """返回低敏 smoke 字符串。"""

        return {"message": message, "transport": "streamable-http"}

    server.run(transport="streamable-http")


async def run_client() -> None:
    """连接本地 HTTP Server 并执行真实 MCP 调用。"""

    runtime = McpClientRuntime(
        settings=McpClientRuntimeSettings(enabled=True, fail_open=False),
        configurations=(
            McpServerConfiguration(
                server_id="http-smoke",
                display_name="DataSmart HTTP MCP smoke",
                transport=McpTransportType.STREAMABLE_HTTP,
                enabled=True,
                endpoint=f"http://{HOST}:{PORT}/mcp",
                allowed_hosts=(HOST,),
                allow_insecure_http=True,
                required=True,
            ),
        ),
    )
    snapshot = await runtime.discover_server("http-smoke")
    tool_name = "mcp.http-smoke.datasmart_http_smoke_echo"
    if tool_name not in {tool.internal_name for tool in snapshot.tools}:
        raise RuntimeError("Streamable HTTP smoke 未发现预期工具。")
    result = await runtime.call_tool(
        McpToolCallRequest(
            server_id="http-smoke",
            internal_tool_name=tool_name,
            arguments={"message": "datasmart-http-safe-smoke"},
            admission=McpToolCallAdmission(
                tenant_id="smoke-tenant",
                project_id="smoke-project",
                workspace_key="tenant:smoke-tenant:project:smoke-project",
                actor_id="smoke-operator",
                run_id="mcp-http-smoke",
                call_id="mcp-http-smoke-call",
                readiness_decision="READY",
                permission_granted=True,
                approval_verified=True,
                allowed_internal_tool_names=(tool_name,),
                source="SMOKE_TEST",
            ),
        )
    )
    if "datasmart-http-safe-smoke" not in str(result.content_blocks) + str(result.structured_content):
        raise RuntimeError("Streamable HTTP tools/call 未返回预期运行时正文。")
    if "datasmart-http-safe-smoke" in str(result.to_summary()):
        raise RuntimeError("Streamable HTTP summary 泄露结果正文。")
    print("[OK] DataSmart outbound MCP Client Streamable HTTP smoke passed.")
    print(f"[OK] discoveredToolCount={len(snapshot.tools)}")
    print(f"[OK] internalToolName={tool_name}")
    print(f"[OK] resultByteCount={result.result_byte_count}")
    print("[OK] resultBodyReturnedInSummary=false")


def wait_for_server(timeout_seconds: int = 20) -> None:
    """等待本地监听端口就绪，超时后 fail-fast。"""

    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((HOST, PORT), timeout=0.5):
                return
        except OSError:
            time.sleep(0.2)
    raise RuntimeError("Streamable HTTP smoke Server 启动超时。")


if __name__ == "__main__":
    process = multiprocessing.Process(target=run_server, daemon=True)
    process.start()
    try:
        wait_for_server()
        asyncio.run(run_client())
    finally:
        process.terminate()
        process.join(timeout=5)
