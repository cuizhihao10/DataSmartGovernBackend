import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.tool_action_control_flow import (
    build_tool_action_control_flow_preview_response,
)
from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.services.tools import (
    JavaToolActionCommandProposalClient,
    ToolActionCommandProposalClientError,
    ToolActionCommandProposalClientSettings,
    ToolActionCommandProposalEvidence,
)


class FakeHttpResponse:
    """极简 HTTP 响应对象。

    `JavaToolActionCommandProposalClient` 使用 `with urlopen(...) as response` 形态读取响应。
    单元测试里不需要真实网络，只要提供上下文管理器和 `read()` 方法即可验证请求体、响应解析和错误处理。
    """

    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def read(self) -> bytes:
        return json.dumps(self._payload, ensure_ascii=False).encode("utf-8")


class ToolActionCommandProposalClientTest(unittest.TestCase):
    """Python -> Java command proposal 客户端契约测试。

    这组测试刻意不启动 Java 服务，保护的是“客户端能否安全地准备和提交 proposal 请求”：
    - 默认禁用时只生成低敏请求摘要，不产生网络副作用；
    - 缺少 graph/payload/policy 等关键证据时 fail-closed；
    - payloadReference 必须像受控引用，不能是 URL、JSON、SQL 或凭证片段；
    - 启用 HTTP 时只提交 Java DTO 白名单字段，不把模板里的展示 hint 或工具参数值带给 Java。
    """

    def setUp(self) -> None:
        self.tools = default_tool_registry()

    def test_disabled_client_builds_low_sensitive_request_without_submission(self) -> None:
        """默认禁用态应能生成请求摘要，但不能真的调用 Java。"""

        client = JavaToolActionCommandProposalClient()
        result = client.propose(
            self._ready_template(),
            self._complete_evidence(
                graph_id="graph-ready-001",
                payload_reference="agent-payload:run-proposal/datasource-metadata-read",
            ),
        )
        summary = result.to_summary()
        serialized = str(summary)

        self.assertFalse(result.submitted)
        self.assertTrue(result.skipped)
        self.assertEqual("CLIENT_DISABLED", result.submission_state)
        self.assertEqual("graph-ready-001", summary["requestPayload"]["graphId"])
        self.assertEqual("agent-payload:run-proposal/datasource-metadata-read", summary["requestPayload"]["payloadReference"])
        self.assertNotIn("toolNameHint", summary["requestPayload"])
        self.assertNotIn("planIndexHint", summary["requestPayload"])
        self.assertNotIn("ds-client-secret", serialized)
        self.assertNotIn("select * from hidden_table", serialized)

    def test_missing_required_evidence_is_skipped_before_http(self) -> None:
        """缺少 execution graph 和 payloadReference 时应在 Python 侧直接阻断。"""

        client = JavaToolActionCommandProposalClient(
            ToolActionCommandProposalClientSettings(enabled=True),
            urlopen_func=self._failing_urlopen,
        )
        result = client.propose(self._ready_template(), ToolActionCommandProposalEvidence())

        self.assertFalse(result.submitted)
        self.assertTrue(result.skipped)
        self.assertEqual("VALIDATION_FAILED", result.submission_state)
        self.assertIn("GRAPH_ID_OR_CONTRACT_ID_REQUIRED", result.missing_evidence)
        self.assertIn("PAYLOAD_REFERENCE_REQUIRED", result.missing_evidence)

    def test_unsafe_payload_reference_is_rejected_before_http(self) -> None:
        """payloadReference 如果像内联 URL 或外部地址，不能进入 Java proposal。"""

        client = JavaToolActionCommandProposalClient(
            ToolActionCommandProposalClientSettings(enabled=True),
            urlopen_func=self._failing_urlopen,
        )
        result = client.propose(
            self._ready_template(),
            self._complete_evidence(
                contract_id="contract-ready-001",
                payload_reference="https://internal.example.local/payload?token=secret",
            ),
        )

        self.assertFalse(result.submitted)
        self.assertTrue(result.skipped)
        self.assertIn("PAYLOAD_REFERENCE_UNSAFE_OR_INLINE", result.rejected_evidence)
        self.assertNotIn("internal.example.local", str(result.to_summary()))

    def test_enabled_client_posts_java_request_and_parses_low_sensitive_response(self) -> None:
        """启用客户端后应 POST Java proposal，并只解析响应白名单字段。"""

        captured: dict[str, object] = {}

        def fake_urlopen(request, timeout: int):
            captured["url"] = request.full_url
            captured["timeout"] = timeout
            captured["headers"] = dict(request.header_items())
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "proposalId": "proposal-001",
                        "proposalState": "READY_FOR_OUTBOX_CONFIRMATION",
                        "outboxWriteAllowedByPreflight": True,
                        "graphId": "graph-ready-001",
                        "sourceReplaySequence": 18,
                        "toolName": "datasource.metadata.read",
                        "payloadReference": "agent-payload:run-proposal/datasource-metadata-read",
                        "payloadPolicy": "REFERENCE_ONLY",
                        "workerReceiptRequired": True,
                        "workerReceiptMode": "REQUIRED",
                        "acceptedEvidence": ["COMMAND_TYPE:AGENT_TOOL_ACTION_CONTROLLED_COMMAND"],
                        "arguments": {"datasourceId": "ds-client-secret"},
                        "sql": "select * from hidden_table",
                    },
                }
            )

        client = JavaToolActionCommandProposalClient(
            ToolActionCommandProposalClientSettings(
                enabled=True,
                base_url="http://agent-runtime.test",
                timeout_seconds=7,
            ),
            urlopen_func=fake_urlopen,
        )
        result = client.propose(
            self._ready_template(),
            self._complete_evidence(
                graph_id="graph-ready-001",
                payload_reference="agent-payload:run-proposal/datasource-metadata-read",
            ),
            trace_id="trace-command-proposal",
        )
        summary = result.to_summary()
        response_summary = summary["javaProposal"]

        self.assertTrue(result.submitted)
        self.assertFalse(result.skipped)
        self.assertEqual("SUBMITTED_TO_JAVA_PROPOSAL", result.submission_state)
        self.assertEqual("http://agent-runtime.test/agent-runtime/tool-action-commands/proposals", captured["url"])
        self.assertEqual(7, captured["timeout"])
        self.assertEqual("graph-ready-001", captured["payload"]["graphId"])
        self.assertEqual("tool-readiness-policy.v1", captured["payload"]["policyVersion"])
        self.assertNotIn("toolNameHint", captured["payload"])
        self.assertNotIn("planIndexHint", captured["payload"])
        self.assertEqual("READY_FOR_OUTBOX_CONFIRMATION", response_summary["proposalState"])
        self.assertTrue(response_summary["outboxWriteAllowedByPreflight"])
        self.assertEqual(18, response_summary["sourceReplaySequence"])
        self.assertNotIn("arguments", response_summary)
        self.assertNotIn("sql", response_summary)
        self.assertNotIn("ds-client-secret", str(summary))
        self.assertNotIn("hidden_table", str(summary))

    def test_java_platform_error_raises_client_error(self) -> None:
        """Java 统一响应 code 非 0 时不能被当成 proposal 成功。"""

        with self.assertRaises(ToolActionCommandProposalClientError):
            JavaToolActionCommandProposalClient.parse_platform_response(
                {
                    "code": 400,
                    "reason": "BUSINESS_STATE_CONFLICT",
                    "message": "execution graph 尚未进入 READY_FOR_OUTBOX_WRITE",
                }
            )

    def _ready_template(self) -> dict[str, object]:
        """生成一个 READY 的 MCP 工具动作 proposal 模板。"""

        response = build_tool_action_control_flow_preview_response(
            {
                "source": "MCP_TOOLS_CALL",
                "jsonrpc": "2.0",
                "id": "rpc-command-proposal-client",
                "method": "tools/call",
                "params": {
                    "name": "datasource.metadata.read",
                    "arguments": {"datasourceId": "ds-client-secret"},
                },
                "visibleToolNames": ["datasource.metadata.read"],
                "context": {
                    "tenantId": "tenant-command-proposal",
                    "projectId": "project-command-proposal",
                    "actorId": "actor-command-proposal",
                    "requestId": "request-command-proposal",
                    "runId": "run-command-proposal",
                    "sessionId": "session-command-proposal",
                    "policyVersion": "tool-readiness-policy.v1",
                    "clientRequestId": "client-command-proposal",
                },
                "sql": "select * from hidden_table",
            },
            registered_tools=self.tools,
        )
        return response["toolActionCommandProposalTemplates"]["templates"][0]

    def _complete_evidence(
        self,
        *,
        graph_id: str | None = None,
        contract_id: str | None = None,
        payload_reference: str,
    ) -> ToolActionCommandProposalEvidence:
        """生成测试用完整证据。"""

        return ToolActionCommandProposalEvidence(
            graph_id=graph_id,
            contract_id=contract_id,
            payload_reference=payload_reference,
            policy_version="tool-readiness-policy.v1",
            client_request_id="client-command-proposal",
        )

    @staticmethod
    def _failing_urlopen(*args, **kwargs):
        """如果预检正确，缺证据/危险引用场景不应该触发 HTTP。"""

        raise AssertionError("不应该在本地预检失败时调用 Java proposal 接口")


if __name__ == "__main__":
    unittest.main()
