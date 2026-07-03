#!/usr/bin/env python3
"""DataSmart 出站 MCP Client 的本地 stdio smoke Server。

该进程不是产品 MCP Server，只为验证官方 SDK 的 initialize、tools/list、tools/call 和 stdio 生命周期。
它只提供一个无副作用回显工具，不读取文件、不访问网络、不读取环境变量 Secret，也不写业务数据库。
"""

from __future__ import annotations

from mcp.server.fastmcp import FastMCP
from mcp.types import ToolAnnotations


server = FastMCP("datasmart-mcp-stdio-smoke")


@server.tool(
    name="datasmart_smoke_echo",
    title="DataSmart MCP smoke echo",
    description="返回低敏 smoke 文本，用于验证 MCP Client 数据路径。",
    annotations=ToolAnnotations(
        readOnlyHint=True,
        destructiveHint=False,
        idempotentHint=True,
        openWorldHint=False,
    ),
    structured_output=True,
)
def datasmart_smoke_echo(message: str) -> dict[str, str]:
    """返回调用方提供的低敏 smoke 文本。"""

    return {
        "message": message,
        "purpose": "datasmart-outbound-mcp-client-smoke",
    }


if __name__ == "__main__":
    server.run(transport="stdio")
