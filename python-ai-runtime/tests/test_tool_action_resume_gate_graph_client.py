import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    AgentRuntimeResumeGateGraphClientSettings,
    JavaAgentRuntimeToolActionResumeGateGraphClient,
)


class FakeHttpResponse:
    """极简 HTTP 响应对象，模拟 urllib 的上下文管理器行为。"""

    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def read(self) -> bytes:
        return json.dumps(self._payload, ensure_ascii=False).encode("utf-8")


class FakeCheckpoint:
    """只提供 Java gate graph 查询需要的低敏 checkpoint 字段。"""

    checkpoint_id = "tool-action-checkpoint:gate-001"
    thread_id = "thread-gate-001"
    tenant_id = "101"
    project_id = "202"
    actor_id = "1001"
    request_id = "command-gate-001"
    run_id = "run-gate-001"
    session_id = "session-gate-001"
    resume_requirements = ("APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION")


class ToolActionResumeGateGraphClientTest(unittest.TestCase):
    """Java agent-runtime 恢复门控图客户端测试。

    这组测试保护 Python -> Java 5.85 gate graph 的关键契约：
    - 默认关闭不能访问网络；
    - 启用后只提交低敏定位 DTO；
    - Java 图状态进入 `serverSideResumeFacts.resumeGateGraph` 摘要；
    - Java missing/rejected 会覆盖请求自报事实；
    - 响应不泄露 approvalFactId、outboxId、payloadReference、SQL、prompt、token 或节点正文。
    """

    def test_disabled_client_returns_empty_snapshot_without_http(self) -> None:
        """默认关闭态只返回空事实，不访问网络。"""

        client = JavaAgentRuntimeToolActionResumeGateGraphClient(urlopen_func=self._failing_urlopen)

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())

        self.assertEqual("AGENT_RUNTIME_RESUME_GATE_GRAPH_PROVIDER_DISABLED", snapshot.source)
        self.assertEqual((), snapshot.available_fact_types)
        self.assertEqual((), snapshot.rejected_fact_types)

    def test_enabled_client_posts_low_sensitive_gate_graph_query_and_accepts_ready_graph(self) -> None:
        """启用 gate graph 后，应请求 Java 5.85 接口并解析 READY 图摘要。"""

        captured: dict[str, object] = {}

        def fake_urlopen(request, timeout: int):
            captured["url"] = request.full_url
            captured["timeout"] = timeout
            captured["headers"] = {key.lower(): value for key, value in request.header_items()}
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "schemaVersion": "datasmart.agent-runtime.tool-action-resume-gate-graph-query.v1",
                        "graphState": "READY_FOR_RESUME_PREVIEW",
                        "terminalState": "READY_FOR_PYTHON_RESUME_PREVIEW_ONLY",
                        "resumePreviewReady": True,
                        "nodeCount": 6,
                        "edgeCount": 8,
                        "payloadPolicy": "FACT_TYPE_AND_CONTROL_STATUS_ONLY_NO_FACT_VALUES_NO_PAYLOAD_BODY",
                        "recommendedActions": [
                            "CALL_PYTHON_RUNTIME_RESUME_PREVIEW_WITHOUT_DIRECT_SIDE_EFFECT_EXECUTION"
                        ],
                        "graph": {
                            "requiredFactTypes": [
                                "APPROVAL_CONFIRMATION_FACT",
                                "OUTBOX_WRITE_CONFIRMATION",
                                "WORKER_RECEIPT_PROJECTION",
                            ],
                            "availableFactTypes": [
                                "APPROVAL_CONFIRMATION_FACT",
                                "OUTBOX_WRITE_CONFIRMATION",
                                "WORKER_RECEIPT_PROJECTION",
                            ],
                            "missingFactTypes": [],
                            "rejectedFactTypes": [],
                            "blockedNodeCount": 0,
                            "executableNodeCount": 6,
                            "requestedLocator": {
                                "approvalFactId": "approval-secret-should-not-leak",
                                "outboxId": "outbox-secret-should-not-leak",
                            },
                            "nodes": [
                                {"nodeId": "resume-gate", "missingRequirements": []},
                            ],
                        },
                    },
                }
            )

        client = JavaAgentRuntimeToolActionResumeGateGraphClient(
            AgentRuntimeResumeGateGraphClientSettings(
                enabled=True,
                base_url="http://agent-runtime.test",
                timeout_seconds=4,
                service_token="agent-runtime-token-secret",
            ),
            urlopen_func=fake_urlopen,
        )

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())
        summary = snapshot.to_summary()

        self.assertEqual(
            "http://agent-runtime.test/agent-runtime/tool-action-resume-gates/graphs/preview",
            captured["url"],
        )
        self.assertEqual(4, captured["timeout"])
        self.assertEqual("python-ai-runtime", captured["headers"]["x-datasmart-source-service"])
        self.assertEqual("101", captured["headers"]["x-datasmart-tenant-id"])
        self.assertEqual("1001", captured["headers"]["x-datasmart-actor-id"])
        self.assertEqual("SERVICE_ACCOUNT", captured["headers"]["x-datasmart-actor-role"])
        self.assertEqual("approval-secret-001", captured["payload"]["approvalFactId"])
        self.assertEqual("command-gate-001", captured["payload"]["commandId"])
        self.assertEqual("outbox-secret-001", captured["payload"]["outboxId"])
        self.assertIn("WORKER_RECEIPT_PROJECTION", captured["payload"]["requiredFactTypes"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", snapshot.available_fact_types)
        self.assertTrue(summary["resumeGateGraph"]["resumePreviewReady"])
        self.assertEqual("READY_FOR_RESUME_PREVIEW", summary["resumeGateGraph"]["graphState"])
        self.assertNotIn("approval-secret-001", str(summary))
        self.assertNotIn("outbox-secret-001", str(summary))
        self.assertNotIn("agent-runtime-token-secret", str(summary))
        self.assertNotIn("agent-payload:should-not-leak", str(summary))
        self.assertNotIn("select * from hidden_table", str(summary))
        self.assertNotIn("raw prompt should not leak", str(summary))

    def test_missing_request_supplied_outbox_fact_should_reject_it(self) -> None:
        """调用方自报 outbox 事实但 Java gate graph 缺失时，Python 必须 fail-closed。"""

        def fake_urlopen(request, timeout: int):
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "graphState": "WAITING_RESUME_FACTS",
                        "terminalState": "WAIT_FOR_CONTROL_PLANE_FACTS",
                        "resumePreviewReady": False,
                        "graph": {
                            "availableFactTypes": ["APPROVAL_CONFIRMATION_FACT"],
                            "missingFactTypes": ["OUTBOX_WRITE_CONFIRMATION"],
                            "rejectedFactTypes": [],
                            "requiredFactTypes": ["APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION"],
                            "nodes": [
                                {
                                    "nodeId": "fact-outbox-write-confirmation",
                                    "missingRequirements": ["OUTBOX_RECORD_NOT_FOUND_OR_NOT_VISIBLE"],
                                }
                            ],
                        },
                    },
                }
            )

        client = JavaAgentRuntimeToolActionResumeGateGraphClient(
            AgentRuntimeResumeGateGraphClientSettings(enabled=True),
            urlopen_func=fake_urlopen,
        )

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())

        self.assertIn("OUTBOX_WRITE_CONFIRMATION", snapshot.missing_fact_types)
        self.assertIn("OUTBOX_WRITE_CONFIRMATION", snapshot.rejected_fact_types)
        self.assertIn("OUTBOX_RECORD_NOT_FOUND_OR_NOT_VISIBLE", snapshot.error_codes)
        self.assertFalse(snapshot.to_summary()["resumeGateGraph"]["resumePreviewReady"])

    @staticmethod
    def _request_payload() -> dict[str, object]:
        """生成带恢复事实和敏感噪声字段的 resume-preview 请求。"""

        return {
            "resumeFacts": {
                "approvalConfirmationId": "approval-secret-001",
                "outboxConfirmationId": "outbox-secret-001",
            },
            "context": {
                "traceId": "trace-gate-001",
                "commandId": "command-gate-001",
                "tenantId": "101",
                "projectId": "202",
            },
            "params": {
                "name": "datasource.metadata.read",
                "arguments": {"datasourceId": "ds-secret"},
            },
            "payloadReference": "agent-payload:should-not-leak",
            "sql": "select * from hidden_table",
            "prompt": "raw prompt should not leak",
        }

    @staticmethod
    def _failing_urlopen(*args, **kwargs):
        """禁用态或本地预检失败时不应访问网络。"""

        raise AssertionError("不应该调用 Java agent-runtime gate graph")


if __name__ == "__main__":
    unittest.main()
