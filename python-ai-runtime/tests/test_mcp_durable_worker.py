"""MCP Durable Worker Adapter 测试。

本测试覆盖的是 outbox consumer 未来会调用的最小闭环：
control facts -> admission -> MCP durable execution -> low-sensitive worker receipt。
"""

import os
import sys
import unittest
from contextlib import asynccontextmanager
from types import SimpleNamespace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools.command_worker_receipt_client import CommandWorkerReceiptPostResult
from datasmart_ai_runtime.services.tools.controlled_command_worker_runner import CommandWorkerReceiptOutcome
from datasmart_ai_runtime.services.tools.mcp import (
    McpClientRuntime,
    McpClientRuntimeSettings,
    McpDurableToolExecutionService,
    McpDurableWorkerAdapter,
    McpDurableWorkerRunRequest,
    McpServerConfiguration,
    McpTransportType,
)


class McpDurableWorkerAdapterTest(unittest.IsolatedAsyncioTestCase):
    """验证 MCP worker adapter 的成功、阻断和 Java receipt 写回边界。"""

    async def test_successful_worker_run_generates_low_sensitive_execution_receipt(self) -> None:
        """成功执行 MCP 后，receipt 只能保存摘要，不能保存工具正文。"""

        session = FakeMcpSession(
            tools=(fake_tool("search", read_only=True, destructive=False, open_world=False),),
            call_result=SimpleNamespace(
                content=(FakeContent({"type": "text", "text": "private result body"}),),
                structuredContent={"secretRow": "row-001"},
                isError=False,
            ),
        )
        adapter = self._adapter(session)

        result = await adapter.run(self._request(post_to_java=False))

        self.assertEqual(CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED, result.receipt.outcome)
        self.assertTrue(result.receipt.java_payload["preCheckPassed"])
        self.assertTrue(result.receipt.java_payload["sideEffectExecuted"])
        self.assertEqual("python-ai-runtime-mcp-client", result.receipt.java_payload["targetService"])
        self.assertEqual([("search", {"query": "quality rules"})], session.calls)
        summary_text = str(result.to_summary())
        self.assertIn("mcpResultSummary", summary_text)
        self.assertNotIn("private result body", summary_text)
        self.assertNotIn("row-001", summary_text)

    async def test_admission_failure_generates_failed_precheck_receipt_without_mcp_call(self) -> None:
        """admission 缺权限时，worker 应生成失败 receipt，且不触发外部 MCP。"""

        session = FakeMcpSession(
            tools=(fake_tool("search", read_only=True, destructive=False, open_world=False),)
        )
        adapter = self._adapter(session)
        request = self._request(
            control_facts={
                **self._facts(),
                "permissionGranted": False,
                "allowedInternalToolNames": [],
            }
        )

        result = await adapter.run(request)

        self.assertEqual(CommandWorkerReceiptOutcome.FAILED_PRECHECK, result.receipt.outcome)
        self.assertIn("PERMISSION_NOT_GRANTED", result.receipt.java_payload["commandSafetyIssueCodes"])
        self.assertIn("TOOL_NOT_IN_ADMISSION_ALLOWLIST", result.receipt.java_payload["commandSafetyIssueCodes"])
        self.assertFalse(result.receipt.java_payload["sideEffectStarted"])
        self.assertEqual([], session.calls)

    async def test_worker_can_post_low_sensitive_receipt_to_java_client(self) -> None:
        """显式开启 post_to_java 时，adapter 应通过现有 Java receipt client 写回低敏 receipt。"""

        session = FakeMcpSession(
            tools=(fake_tool("search", read_only=True, destructive=False, open_world=False),)
        )
        receipt_client = FakeReceiptClient()
        adapter = self._adapter(session, receipt_client=receipt_client)

        result = await adapter.run(self._request(post_to_java=True))

        self.assertIsNotNone(result.post_result)
        self.assertTrue(result.post_result.posted)
        self.assertEqual("session-a", receipt_client.calls[0]["session_id"])
        self.assertEqual("run-a", receipt_client.calls[0]["run_id"])
        posted_payload = receipt_client.calls[0]["receipt"].java_payload
        self.assertEqual("call-a", posted_payload["commandId"])
        self.assertNotIn("query", str(posted_payload))

    @staticmethod
    def _facts() -> dict[str, object]:
        """构造 Java 控制面低敏 facts。"""

        return {
            "tenantId": "10",
            "projectId": "20",
            "workspaceKey": "tenant:10:project:20",
            "actorId": "30",
            "sessionId": "session-a",
            "runId": "run-a",
            "callId": "call-a",
            "readinessDecision": "READY",
            "permissionGranted": True,
            "approvalVerified": False,
            "allowedInternalToolNames": ["mcp.enterprise.search"],
            "source": "MCP_TOOLS_CALL",
            "commandProposalId": "proposal-a",
            "outboxMessageId": "outbox-a",
            "checkpointId": "checkpoint-a",
            "policyVersion": "tool-readiness-policy.v1",
        }

    def _request(
        self,
        *,
        control_facts: dict[str, object] | None = None,
        post_to_java: bool = False,
    ) -> McpDurableWorkerRunRequest:
        """生成 worker 请求。arguments 是短生命周期对象，不应进入 receipt。"""

        return McpDurableWorkerRunRequest(
            server_id="enterprise",
            internal_tool_name="mcp.enterprise.search",
            arguments={"query": "quality rules"},
            control_facts=control_facts or self._facts(),
            post_to_java=post_to_java,
            session_id="session-a",
            trace_id="trace-a",
        )

    @staticmethod
    def _adapter(session: "FakeMcpSession", *, receipt_client=None) -> McpDurableWorkerAdapter:
        """构造使用 fake MCP session 的 worker adapter。"""

        @asynccontextmanager
        async def opener(configuration):
            yield session

        runtime = McpClientRuntime(
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
        return McpDurableWorkerAdapter(
            McpDurableToolExecutionService(runtime),
            receipt_client=receipt_client,
        )


class FakeReceiptClient:
    """模拟 JavaCommandWorkerReceiptClient，避免单测访问 Java 服务。"""

    def __init__(self) -> None:
        self.calls = []

    def post_receipt(self, *, session_id, run_id, receipt, trace_id=None):
        """记录低敏 POST 请求，并返回成功摘要。"""

        self.calls.append(
            {
                "session_id": session_id,
                "run_id": run_id,
                "receipt": receipt,
                "trace_id": trace_id,
            }
        )
        return CommandWorkerReceiptPostResult(
            attempted=True,
            posted=True,
            skipped=False,
            duplicate=False,
            status_code=200,
            identity_key="receipt-a",
            outcome=receipt.outcome.value,
            error_code=None,
            endpoint_configured=True,
            message="accepted",
        )


class FakeMcpSession:
    """模拟官方 MCP ClientSession。"""

    def __init__(self, *, tools, call_result=None) -> None:
        self._tools = tools
        self._call_result = call_result or SimpleNamespace(content=(), structuredContent=None, isError=False)
        self.calls = []

    async def list_tools(self, cursor=None):
        """返回固定工具目录。"""

        return SimpleNamespace(tools=self._tools, nextCursor=None)

    async def call_tool(self, name, arguments):
        """记录调用并返回固定结果。"""

        self.calls.append((name, arguments))
        return self._call_result


class FakeContent:
    """模拟 MCP SDK content block。"""

    def __init__(self, payload) -> None:
        self._payload = payload

    def model_dump(self, **kwargs):
        """返回 JSON 化内容。"""

        return dict(self._payload)


def fake_tool(
    name: str,
    *,
    read_only=None,
    destructive=None,
    idempotent=None,
    open_world=None,
):
    """构造测试 MCP tool。"""

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
