"""MCP Durable Tool Execution Bridge 测试。

这些测试关注的不是官方 MCP SDK 自身，而是 DataSmart 的企业级 Agent Host 语义：
- MCP 工具必须先经过 admission 才能执行；
- 执行结果正文只能留在本轮运行时对象中；
- 可进入 checkpoint、runtime event、Java worker receipt 的只能是低敏摘要。
"""

import os
import sys
import unittest
from contextlib import asynccontextmanager
from types import SimpleNamespace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools.mcp import (
    McpClientRuntime,
    McpClientRuntimeSettings,
    McpDurableExecutionStatus,
    McpDurableToolExecutionRequest,
    McpDurableToolExecutionService,
    McpServerConfiguration,
    McpToolCallAdmission,
    McpTransportType,
)


class McpDurableToolExecutionServiceTest(unittest.IsolatedAsyncioTestCase):
    """验证 MCP tools/call 已经具备进入 Durable Agent Loop 的最小执行节点语义。"""

    async def test_execute_success_keeps_body_in_runtime_and_exports_low_sensitive_receipt_draft(self) -> None:
        """成功调用后，正文供 Agent 本轮使用，摘要和 receipt draft 不泄露正文。"""

        session = FakeMcpSession(
            tools=(fake_tool("search", read_only=True, destructive=False, open_world=False),),
            call_result=SimpleNamespace(
                content=(FakeContent({"type": "text", "text": "customer private result"}),),
                structuredContent={"rowId": "sensitive-row-001"},
                isError=False,
            ),
        )
        service = McpDurableToolExecutionService(self._runtime(session))

        result = await service.execute(
            self._request(
                internal_tool_name="mcp.enterprise.search",
                approval_verified=False,
                execution_node_id="langgraph.node.mcp_search",
                command_proposal_id="proposal-001",
                outbox_message_id="outbox-001",
                checkpoint_id="checkpoint-001",
            )
        )

        self.assertEqual(McpDurableExecutionStatus.SUCCEEDED, result.status)
        self.assertEqual("customer private result", result.runtime_result.content_blocks[0]["text"])
        self.assertEqual([("search", {"query": "quality rules"})], session.calls)

        summary_text = str(result.to_summary())
        self.assertIn("LOW_SENSITIVE_WORKER_RECEIPT_DRAFT_ONLY", summary_text)
        self.assertIn("proposal-001", summary_text)
        self.assertIn("outbox-001", summary_text)
        self.assertNotIn("customer private result", summary_text)
        self.assertNotIn("sensitive-row-001", summary_text)
        self.assertFalse(result.to_summary()["runtimeResultBodyReturned"])
        self.assertFalse(result.to_summary()["sideEffectBoundary"]["javaWorkerReceiptWritten"])

    async def test_execute_blocks_high_risk_tool_without_approval_before_external_call(self) -> None:
        """高风险 MCP 工具缺少已验证人工审批时，必须在执行前失败，不触发外部 Server。"""

        session = FakeMcpSession(
            tools=(fake_tool("write_record", read_only=False, destructive=True, open_world=False),)
        )
        runtime = self._runtime(session)
        await runtime.discover_server("enterprise")
        service = McpDurableToolExecutionService(runtime)

        result = await service.execute(
            self._request(
                internal_tool_name="mcp.enterprise.write_record",
                approval_verified=False,
            )
        )

        self.assertEqual(McpDurableExecutionStatus.FAILED_PRECHECK, result.status)
        self.assertEqual("MCP_APPROVAL_REQUIRED", result.error_code)
        self.assertEqual([], session.calls)
        self.assertEqual("MCP_APPROVAL_REQUIRED", result.worker_receipt_draft.error_code)

    async def test_execute_maps_remote_tool_failure_to_tool_call_failure(self) -> None:
        """远端 MCP Server 调用失败时，状态应区别于 admission/precheck 失败。"""

        session = FakeMcpSession(
            tools=(fake_tool("search", read_only=True, destructive=False, open_world=False),),
            call_error=RuntimeError("remote stack trace should not leak"),
        )
        service = McpDurableToolExecutionService(self._runtime(session))

        result = await service.execute(
            self._request(
                internal_tool_name="mcp.enterprise.search",
                approval_verified=False,
            )
        )

        self.assertEqual(McpDurableExecutionStatus.FAILED_TOOL_CALL, result.status)
        self.assertEqual("MCP_TOOL_CALL_FAILED", result.error_code)
        self.assertNotIn("remote stack trace", str(result.to_summary()))

    @staticmethod
    def _request(
        *,
        internal_tool_name: str,
        approval_verified: bool,
        execution_node_id: str = "mcp_tools_call",
        command_proposal_id: str | None = None,
        outbox_message_id: str | None = None,
        checkpoint_id: str | None = None,
    ) -> McpDurableToolExecutionRequest:
        """构造完整 admission 的执行请求。

        测试数据保持低敏：参数只是固定字符串，不包含真实 datasource、SQL、文件路径或业务样本。
        """

        return McpDurableToolExecutionRequest(
            server_id="enterprise",
            internal_tool_name=internal_tool_name,
            arguments={"query": "quality rules"},
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
                source="MCP_TOOLS_CALL",
            ),
            execution_node_id=execution_node_id,
            command_proposal_id=command_proposal_id,
            outbox_message_id=outbox_message_id,
            checkpoint_id=checkpoint_id,
            trace_id="trace-a",
        )

    @staticmethod
    def _runtime(session: "FakeMcpSession") -> McpClientRuntime:
        """构造只连接 fake session 的 MCP Runtime，避免单测访问公网或启动真实子进程。"""

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


class FakeMcpSession:
    """模拟官方 ClientSession 的最小端口。"""

    def __init__(self, *, tools, call_result=None, call_error: Exception | None = None) -> None:
        self._tools = tools
        self._call_result = call_result or SimpleNamespace(content=(), structuredContent=None, isError=False)
        self._call_error = call_error
        self.calls = []

    async def list_tools(self, cursor=None):
        """返回固定工具目录，cursor 参数用于兼容分页调用签名。"""

        return SimpleNamespace(tools=self._tools, nextCursor=None)

    async def call_tool(self, name, arguments):
        """记录调用并按测试场景返回成功或失败。"""

        self.calls.append((name, arguments))
        if self._call_error:
            raise self._call_error
        return self._call_result


class FakeContent:
    """模拟 MCP SDK/Pydantic content block。"""

    def __init__(self, payload) -> None:
        self._payload = payload

    def model_dump(self, **kwargs):
        """按 SDK 常见接口返回可 JSON 化字典。"""

        return dict(self._payload)


def fake_tool(
    name: str,
    *,
    read_only=None,
    destructive=None,
    idempotent=None,
    open_world=None,
):
    """构造带 MCP annotations 的测试工具。"""

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
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
        },
        outputSchema={"type": "object"},
        annotations=annotations,
        execution=None,
    )


if __name__ == "__main__":
    unittest.main()
