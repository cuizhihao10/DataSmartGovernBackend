"""出站 MCP Client 目录发现、工具映射与调用闸门测试。"""

import os
import sys
import unittest
from contextlib import asynccontextmanager
from types import SimpleNamespace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.services.tools.mcp import (
    McpClientError,
    McpClientRuntime,
    McpClientRuntimeSettings,
    McpServerConfiguration,
    McpToolCallAdmission,
    McpToolCallRequest,
    McpTransportType,
)


class McpClientRuntimeTest(unittest.IsolatedAsyncioTestCase):
    """验证真实 SDK 之外的 DataSmart MCP 治理语义。"""

    async def test_discovery_maps_remote_tools_into_namespaced_tool_definitions(self) -> None:
        """tools/list 应映射为带 Server namespace 的统一工具，避免同名覆盖。"""

        session = FakeMcpSession(
            tools=(
                fake_tool("search", read_only=True, destructive=False, open_world=True),
                fake_tool("write_record", read_only=False, destructive=True, open_world=False),
            )
        )
        runtime = self._runtime(session)

        snapshot = await runtime.discover_server("enterprise")
        merged = runtime.merged_tool_definitions(default_tool_registry())

        self.assertTrue(snapshot.connected)
        self.assertEqual(2, len(snapshot.tools))
        search = next(tool for tool in merged if tool.name == "mcp.enterprise.search")
        writer = next(tool for tool in merged if tool.name == "mcp.enterprise.write_record")
        # openWorld 即使是只读，也可能访问外部世界，因此按高风险进入审批。
        self.assertTrue(search.requires_approval)
        self.assertTrue(writer.requires_approval)
        self.assertEqual("MCP_REMOTE_TOOL", writer.descriptor_type)
        self.assertEqual("mcp://enterprise/tools/write_record", writer.target_endpoint)

    async def test_untrusted_missing_annotations_default_to_high_risk(self) -> None:
        """远端不声明 annotations 时不能乐观推断只读或幂等。"""

        runtime = self._runtime(FakeMcpSession(tools=(fake_tool("unknown"),)))

        snapshot = await runtime.discover_server("enterprise")
        tool = snapshot.tools[0]

        self.assertFalse(tool.read_only)
        self.assertTrue(tool.destructive)
        self.assertTrue(tool.open_world)
        self.assertFalse(tool.idempotent)

    async def test_high_risk_tool_is_blocked_before_sdk_call_without_approval(self) -> None:
        """readiness READY 和 permission granted 仍不能替代高风险人工审批。"""

        session = FakeMcpSession(tools=(fake_tool("write_record", destructive=True),))
        runtime = self._runtime(session)
        await runtime.discover_server("enterprise")

        with self.assertRaises(McpClientError) as raised:
            await runtime.call_tool(
                self._request(
                    "mcp.enterprise.write_record",
                    approval_verified=False,
                )
            )

        self.assertEqual("MCP_APPROVAL_REQUIRED", raised.exception.code)
        self.assertEqual([], session.calls)

    async def test_approved_tool_call_returns_body_in_memory_but_not_in_summary(self) -> None:
        """真实调用结果可供本轮 Agent 使用，但事件摘要只保存哈希与大小。"""

        session = FakeMcpSession(
            tools=(fake_tool("write_record", destructive=True),),
            call_result=SimpleNamespace(
                content=(FakeContent({"type": "text", "text": "sensitive business result"}),),
                structuredContent={"recordId": "record-001"},
                isError=False,
            ),
        )
        runtime = self._runtime(session)
        await runtime.discover_server("enterprise")

        result = await runtime.call_tool(
            self._request(
                "mcp.enterprise.write_record",
                approval_verified=True,
            )
        )

        self.assertEqual("sensitive business result", result.content_blocks[0]["text"])
        summary = str(result.to_summary())
        self.assertNotIn("sensitive business result", summary)
        self.assertNotIn("record-001", summary)
        self.assertTrue(result.result_digest)
        self.assertEqual([("write_record", {"record": "safe-value"})], session.calls)

    async def test_inline_secret_argument_is_rejected_before_external_call(self) -> None:
        """MCP 参数不能携带模型生成的 token/password，凭据必须由 Server 连接层注入。"""

        session = FakeMcpSession(tools=(fake_tool("write_record", destructive=True),))
        runtime = self._runtime(session)
        await runtime.discover_server("enterprise")
        request = self._request("mcp.enterprise.write_record", approval_verified=True)
        for sensitive_arguments in (
            {"api_key": "must-not-leave-runtime"},
            {"nested": {"accessToken": "must-not-leave-runtime"}},
        ):
            with self.subTest(sensitive_arguments=sensitive_arguments):
                guarded_request = McpToolCallRequest(
                    server_id=request.server_id,
                    internal_tool_name=request.internal_tool_name,
                    arguments=sensitive_arguments,
                    admission=request.admission,
                )
                with self.assertRaises(McpClientError) as raised:
                    await runtime.call_tool(guarded_request)
                self.assertEqual("MCP_INLINE_SECRET_REJECTED", raised.exception.code)
        self.assertEqual([], session.calls)

    async def test_sync_bootstrap_discovery_works_when_asgi_event_loop_is_running(self) -> None:
        """Uvicorn factory 位于事件循环时，同步 app bootstrap 仍应完成目录发现。"""

        runtime = self._runtime(FakeMcpSession(tools=(fake_tool("search", read_only=True),)))

        snapshots = runtime.discover_all_sync()

        self.assertEqual(1, len(snapshots))
        self.assertTrue(snapshots[0].initialized)

    def _runtime(self, session: "FakeMcpSession") -> McpClientRuntime:
        """构建只连接 fake session 的 Runtime。"""

        @asynccontextmanager
        async def opener(configuration):
            yield session

        return McpClientRuntime(
            settings=McpClientRuntimeSettings(enabled=True, fail_open=False),
            configurations=(
                McpServerConfiguration(
                    server_id="enterprise",
                    display_name="Enterprise MCP",
                    transport=McpTransportType.STREAMABLE_HTTP,
                    enabled=True,
                    endpoint="https://mcp.example.internal/mcp",
                    allowed_hosts=("mcp.example.internal",),
                ),
            ),
            session_opener=opener,
        )

    @staticmethod
    def _request(internal_tool_name: str, *, approval_verified: bool) -> McpToolCallRequest:
        """构造带完整可信边界的调用请求。"""

        return McpToolCallRequest(
            server_id="enterprise",
            internal_tool_name=internal_tool_name,
            arguments={"record": "safe-value"},
            admission=McpToolCallAdmission(
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="tenant:tenant-a:project:project-a",
                actor_id="actor-a",
                run_id="run-a",
                call_id="call-a",
                readiness_decision="READY",
                permission_granted=True,
                approval_verified=approval_verified,
                allowed_internal_tool_names=(internal_tool_name,),
            ),
        )


class FakeMcpSession:
    """模拟官方 ClientSession 的最小端口。"""

    def __init__(self, *, tools, call_result=None) -> None:
        self._tools = tools
        self._call_result = call_result or SimpleNamespace(content=(), structuredContent=None, isError=False)
        self.calls = []

    async def list_tools(self, cursor=None):
        return SimpleNamespace(tools=self._tools, nextCursor=None)

    async def call_tool(self, name, arguments):
        self.calls.append((name, arguments))
        return self._call_result


class FakeContent:
    """模拟 MCP Pydantic ContentBlock。"""

    def __init__(self, payload) -> None:
        self._payload = payload

    def model_dump(self, **kwargs):
        return dict(self._payload)


def fake_tool(
    name: str,
    *,
    read_only=None,
    destructive=None,
    idempotent=None,
    open_world=None,
):
    """构造带 MCP annotations hints 的测试 Tool。"""

    annotations = None
    if any(value is not None for value in (read_only, destructive, idempotent, open_world)):
        annotations = SimpleNamespace(
            title=f"{name} title",
            readOnlyHint=read_only,
            destructiveHint=destructive,
            idempotentHint=idempotent,
            openWorldHint=open_world,
        )
    return SimpleNamespace(
        name=name,
        description=f"{name} description",
        inputSchema={
            "type": "object",
            "properties": {"record": {"type": "string"}},
            "required": ["record"],
        },
        outputSchema={"type": "object"},
        annotations=annotations,
        execution=None,
    )


if __name__ == "__main__":
    unittest.main()
