#!/usr/bin/env python3
"""使用官方 MCP SDK 验证 DataSmart 出站 MCP Client 的真实链路。

执行路径：
1. DataSmart Client 通过受控 stdio 启动本仓库 smoke Server；
2. 完成 MCP initialize；
3. 调用 tools/list 并映射成 `mcp.local-smoke.datasmart_smoke_echo`；
4. 构造 READY + permission + allowlist admission；
5. 执行真实 tools/call；
6. 验证正文只存在于运行时结果，低敏 summary 不包含正文。

脚本不会连接公网、不会读取业务数据，也不会把工具结果正文写入普通输出。
"""

from __future__ import annotations

import asyncio
import os
import sys
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


async def run_smoke() -> None:
    """执行一次真实 stdio MCP 发现和调用。"""

    server_script = REPO_ROOT / "scripts" / "mcp-stdio-smoke-server.py"
    executable_name = Path(sys.executable).name
    runtime = McpClientRuntime(
        settings=McpClientRuntimeSettings(
            enabled=True,
            fail_open=False,
            stdio_enabled=True,
            stdio_allowed_commands=(executable_name,),
            stdio_allowed_roots=(str(REPO_ROOT),),
        ),
        configurations=(
            McpServerConfiguration(
                server_id="local-smoke",
                display_name="DataSmart local MCP smoke",
                transport=McpTransportType.STDIO,
                enabled=True,
                command=sys.executable,
                args=(str(server_script),),
                cwd=str(REPO_ROOT),
                required=True,
                max_tools=10,
                max_result_bytes=8192,
            ),
        ),
    )
    snapshot = await runtime.discover_server("local-smoke")
    expected_tool = "mcp.local-smoke.datasmart_smoke_echo"
    if expected_tool not in {tool.internal_name for tool in snapshot.tools}:
        raise RuntimeError("MCP smoke 未发现预期工具。")

    result = await runtime.call_tool(
        McpToolCallRequest(
            server_id="local-smoke",
            internal_tool_name=expected_tool,
            arguments={"message": "datasmart-safe-smoke"},
            admission=McpToolCallAdmission(
                tenant_id="smoke-tenant",
                project_id="smoke-project",
                workspace_key="tenant:smoke-tenant:project:smoke-project",
                actor_id="smoke-operator",
                run_id="mcp-client-smoke",
                call_id="mcp-client-smoke-call",
                readiness_decision="READY",
                permission_granted=True,
                approval_verified=True,
                allowed_internal_tool_names=(expected_tool,),
                source="SMOKE_TEST",
            ),
        )
    )
    serialized_body = str(result.content_blocks) + str(result.structured_content)
    if "datasmart-safe-smoke" not in serialized_body:
        raise RuntimeError("MCP smoke tools/call 未返回预期运行时正文。")
    if "datasmart-safe-smoke" in str(result.to_summary()):
        raise RuntimeError("MCP smoke 发现低敏 summary 泄露工具结果正文。")

    print("[OK] DataSmart outbound MCP Client stdio smoke passed.")
    print(f"[OK] discoveredToolCount={len(snapshot.tools)}")
    print(f"[OK] internalToolName={expected_tool}")
    print(f"[OK] resultByteCount={result.result_byte_count}")
    print(f"[OK] resultDigest={result.result_digest}")
    print("[OK] resultBodyReturnedInSummary=false")


if __name__ == "__main__":
    # Windows 上官方 SDK stdio transport 使用 anyio/asyncio；保持默认事件循环策略即可。
    os.environ.setdefault("PYTHONUTF8", "1")
    asyncio.run(run_smoke())
