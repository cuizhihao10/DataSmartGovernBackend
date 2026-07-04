import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    JavaToolActionCommandOutboxClient,
    ToolActionCommandOutboxClientError,
    ToolActionCommandOutboxClientSettings,
)


class FakeHttpResponse:
    """单元测试用 HTTP 响应替身。

    outbox client 使用 `with urlopen(...) as response` 读取 Java 统一响应；测试只需要提供 `read()` 即可验证
    URL、Header、请求体和响应白名单解析，不需要启动真实 Java 服务。
    """

    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def read(self) -> bytes:
        return json.dumps(self._payload, ensure_ascii=False).encode("utf-8")


class ToolActionCommandOutboxClientTest(unittest.TestCase):
    """Python -> Java command outbox writer 客户端契约测试。

    这组测试保护的是“proposal 允许之后如何安全推进 outbox”：
    - 默认 disabled 时不产生网络副作用；
    - proposal 未允许时 fail-closed；
    - 启用后只 POST Java proposal request 白名单字段；
    - Java 响应即使夹带 payloadJson、arguments、SQL，也不会被 Python 摘要透出。
    """

    def test_disabled_client_skips_write_without_http_side_effect(self) -> None:
        """客户端默认关闭时应返回 OUTBOX_CLIENT_DISABLED，而不是误写 Java outbox。"""

        client = JavaToolActionCommandOutboxClient(urlopen_func=self._failing_urlopen)
        result = client.write(
            self._request_payload(),
            java_proposal={"outboxWriteAllowedByPreflight": True},
            trace_id="trace-outbox-disabled",
        )
        summary = result.to_summary()

        self.assertFalse(result.attempted)
        self.assertFalse(result.written)
        self.assertTrue(result.skipped)
        self.assertEqual("OUTBOX_CLIENT_DISABLED", result.write_state)
        self.assertNotIn("localhost:8091", str(summary))
        self.assertNotIn("select * from hidden_table", str(summary))

    def test_proposal_not_allowed_blocks_before_http(self) -> None:
        """Java proposal 未放行 outbox 时，Python 不允许绕过 proposal 直接写 writer。"""

        client = JavaToolActionCommandOutboxClient(
            ToolActionCommandOutboxClientSettings(enabled=True),
            urlopen_func=self._failing_urlopen,
        )
        result = client.write(
            self._request_payload(),
            java_proposal={"outboxWriteAllowedByPreflight": False},
        )

        self.assertFalse(result.attempted)
        self.assertTrue(result.skipped)
        self.assertEqual("OUTBOX_WRITE_BLOCKED", result.write_state)
        self.assertEqual("PROPOSAL_DID_NOT_ALLOW_OUTBOX_WRITE", result.skip_reason)

    def test_enabled_client_posts_writer_request_and_parses_low_sensitive_response(self) -> None:
        """启用后应 POST Java writer，并只解析低敏响应字段。"""

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
                        "writerState": "ENQUEUED",
                        "enqueued": True,
                        "duplicate": False,
                        "commandId": "taoc_outbox_001",
                        "proposalId": "proposal-outbox-001",
                        "graphId": "graph-outbox-001",
                        "contractId": "contract-outbox-001",
                        "runId": "run-outbox",
                        "payloadReference": "agent-payload:outbox/datasource-metadata-read",
                        "proposalState": "READY_FOR_OUTBOX_CONFIRMATION",
                        "summaryReasons": ["proposal 已通过写入前预检"],
                        "recommendedActions": ["等待 dispatcher 投递并观察 worker receipt"],
                        "record": {
                            "commandId": "taoc_outbox_001",
                            "topic": "datasmart.agent.tool.async.commands",
                            "consumerService": "task-management",
                            "payloadReference": "agent-payload:outbox/datasource-metadata-read",
                            "payloadJson": "{\"secret\":\"should-not-leak\"}",
                            "arguments": {"datasourceId": "ds-outbox-secret"},
                        },
                        "sql": "select * from hidden_table",
                    },
                }
            )

        client = JavaToolActionCommandOutboxClient(
            ToolActionCommandOutboxClientSettings(
                enabled=True,
                base_url="http://agent-runtime.test",
                timeout_seconds=8,
            ),
            urlopen_func=fake_urlopen,
        )
        result = client.write(
            self._request_payload(),
            java_proposal={"outboxWriteAllowedByPreflight": True},
            trace_id="trace-command-outbox",
        )
        summary = result.to_summary()

        self.assertTrue(result.attempted)
        self.assertTrue(result.written)
        self.assertEqual("ENQUEUED", result.write_state)
        self.assertEqual("http://agent-runtime.test/agent-runtime/tool-action-commands/outbox/write", captured["url"])
        self.assertEqual(8, captured["timeout"])
        self.assertEqual("graph-outbox-001", captured["payload"]["graphId"])
        self.assertEqual("tenant-outbox", captured["payload"]["tenantId"])
        self.assertEqual("taoc_outbox_001", summary["javaOutbox"]["commandId"])
        self.assertEqual("task-management", summary["javaOutbox"]["record"]["consumerService"])
        self.assertNotIn("payloadJson", summary["javaOutbox"]["record"])
        self.assertNotIn("arguments", summary["javaOutbox"]["record"])
        self.assertNotIn("ds-outbox-secret", str(summary))
        self.assertNotIn("hidden_table", str(summary))
        self.assertNotIn("agent-runtime.test", str(summary))

    def test_java_platform_error_raises_client_error(self) -> None:
        """Java 统一响应失败时不能被误判为 outbox 已写入。"""

        with self.assertRaises(ToolActionCommandOutboxClientError):
            JavaToolActionCommandOutboxClient.parse_platform_response(
                {
                    "code": 409,
                    "reason": "BUSINESS_STATE_CONFLICT",
                    "message": "outbox disabled",
                }
            )

    @staticmethod
    def _request_payload() -> dict[str, object]:
        """生成 Java proposal request 白名单 payload。"""

        return {
            "graphId": "graph-outbox-001",
            "contractId": "contract-outbox-001",
            "tenantId": "tenant-outbox",
            "projectId": "project-outbox",
            "actorId": "actor-outbox",
            "requestId": "request-outbox",
            "runId": "run-outbox",
            "sessionId": "session-outbox",
            "afterSequence": None,
            "limit": 100,
            "payloadReference": "agent-payload:outbox/datasource-metadata-read",
            "approvalConfirmationId": None,
            "clarificationFactId": None,
            "policyVersion": "tool-readiness-policy.v1",
            "commandSchemaVersion": "datasmart.agent.tool-action-command.v1",
            "workerReceiptMode": "REQUIRED",
            "clientRequestId": "client-outbox",
        }

    @staticmethod
    def _failing_urlopen(*args, **kwargs):
        """禁用或阻断场景不应触发真实 HTTP。"""

        raise AssertionError("不应在 outbox client 禁用或 proposal 未放行时调用 Java writer")


if __name__ == "__main__":
    unittest.main()
