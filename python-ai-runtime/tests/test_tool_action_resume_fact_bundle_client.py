import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    AgentRuntimeResumeFactBundleClientSettings,
    JavaAgentRuntimeToolActionResumeFactBundleClient,
)


class FakeHttpResponse:
    """极简 HTTP 响应对象，模拟 `urllib.request.urlopen` 的上下文管理器行为。"""

    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def read(self) -> bytes:
        return json.dumps(self._payload, ensure_ascii=False).encode("utf-8")


class FakeCheckpoint:
    """只提供 Java fact bundle 查询需要的低敏 checkpoint 字段。"""

    checkpoint_id = "tool-action-checkpoint:bundle-001"
    thread_id = "run-bundle-001"
    tenant_id = "101"
    project_id = "202"
    actor_id = "1001"
    request_id = "command-bundle-001"
    run_id = "run-bundle-001"
    session_id = "session-bundle-001"
    resume_requirements = (
        "APPROVAL_CONFIRMATION_FACT",
        "OUTBOX_WRITE_CONFIRMATION",
    )
    graph_run_summary = {
        "steps": (
            {
                "toolName": "datasource.metadata.read",
                "proposalSubmission": {
                    "requestPayload": {
                        "policyVersion": "tool-readiness-policy.v1",
                    }
                },
            },
        )
    }


class ToolActionResumeFactBundleClientTest(unittest.TestCase):
    """Java agent-runtime 恢复事实包客户端测试。

    这组测试保护 Python -> Java fact bundle 的关键契约：
    - 默认关闭不能访问网络；
    - 启用后只提交低敏控制面定位字段；
    - Java 可采信事实进入 available；
    - Java missing/rejected 会覆盖请求自报事实，防止任意字符串绕过恢复预检；
    - 响应摘要不泄露 approvalFactId、payloadReference、SQL、prompt 或服务 token。
    """

    def test_disabled_client_returns_empty_snapshot_without_http(self) -> None:
        """默认关闭态只返回空事实，不访问网络。"""

        client = JavaAgentRuntimeToolActionResumeFactBundleClient(urlopen_func=self._failing_urlopen)

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())

        self.assertEqual("AGENT_RUNTIME_RESUME_FACT_BUNDLE_PROVIDER_DISABLED", snapshot.source)
        self.assertEqual((), snapshot.available_fact_types)
        self.assertEqual((), snapshot.rejected_fact_types)

    def test_enabled_client_posts_low_sensitive_bundle_query_and_accepts_available_facts(self) -> None:
        """启用 Java fact bundle 后，应提交低敏 DTO 并解析 available fact types。"""

        captured: dict[str, object] = {}

        def fake_urlopen(request, timeout: int):
            captured["url"] = request.full_url
            captured["timeout"] = timeout
            # HTTP 请求头在 `urllib.request.Request` 内部可能被规范化为
            # `X-datasmart-source-service` 这类大小写组合；HTTP 语义本身对
            # Header 名称大小写不敏感，所以测试侧统一转为小写，避免把
            # 标准库实现细节误判为业务契约失败。
            captured["headers"] = {key.lower(): value for key, value in request.header_items()}
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "availableFactTypes": [
                            "APPROVAL_CONFIRMATION_FACT",
                            "OUTBOX_WRITE_CONFIRMATION",
                            "WORKER_RECEIPT_PROJECTION",
                        ],
                        "missingFactTypes": [],
                        "rejectedFactTypes": [],
                        "facts": [
                            {"factType": "APPROVAL_CONFIRMATION_FACT", "issueCodes": []},
                            {"factType": "OUTBOX_WRITE_CONFIRMATION", "issueCodes": []},
                        ],
                        "outboxSummary": {
                            "payloadReferencePresent": True,
                            "payloadReference": "agent-payload:should-not-be-used",
                        },
                    },
                }
            )

        client = JavaAgentRuntimeToolActionResumeFactBundleClient(
            AgentRuntimeResumeFactBundleClientSettings(
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
            "http://agent-runtime.test/agent-runtime/tool-action-resume-facts/bundles/query",
            captured["url"],
        )
        self.assertEqual(4, captured["timeout"])
        self.assertEqual("python-ai-runtime", captured["headers"]["x-datasmart-source-service"])
        self.assertEqual("101", captured["headers"]["x-datasmart-tenant-id"])
        self.assertEqual("1001", captured["headers"]["x-datasmart-actor-id"])
        self.assertEqual("SERVICE_ACCOUNT", captured["headers"]["x-datasmart-actor-role"])
        self.assertEqual("PLATFORM", captured["headers"]["x-datasmart-data-scope-level"])
        self.assertEqual("approval-secret-001", captured["payload"]["approvalFactId"])
        self.assertEqual("command-bundle-001", captured["payload"]["commandId"])
        self.assertEqual("datasource.metadata.read", captured["payload"]["toolCode"])
        self.assertIn("WORKER_RECEIPT_PROJECTION", captured["payload"]["requiredFactTypes"])
        self.assertNotIn("arguments", captured["payload"])
        self.assertNotIn("sql", captured["payload"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", snapshot.available_fact_types)
        self.assertIn("WORKER_RECEIPT_PROJECTION", snapshot.available_fact_types)
        self.assertEqual(2, snapshot.fact_reference_count)
        self.assertNotIn("approval-secret-001", str(summary))
        self.assertNotIn("agent-runtime-token-secret", str(summary))
        self.assertNotIn("agent-payload:should-not-be-used", str(summary))
        self.assertNotIn("select * from hidden_table", str(summary))
        self.assertNotIn("raw prompt should not leak", str(summary))

    def test_java_missing_request_supplied_outbox_fact_should_reject_it(self) -> None:
        """调用方自报 outboxConfirmationId 但 Java 查不到 outbox 时，必须进入 rejected。"""

        def fake_urlopen(request, timeout: int):
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "availableFactTypes": ["APPROVAL_CONFIRMATION_FACT"],
                        "missingFactTypes": ["OUTBOX_WRITE_CONFIRMATION"],
                        "rejectedFactTypes": [],
                        "facts": [
                            {
                                "factType": "OUTBOX_WRITE_CONFIRMATION",
                                "issueCodes": ["OUTBOX_RECORD_NOT_FOUND_OR_NOT_VISIBLE"],
                            }
                        ],
                    },
                }
            )

        client = JavaAgentRuntimeToolActionResumeFactBundleClient(
            AgentRuntimeResumeFactBundleClientSettings(enabled=True),
            urlopen_func=fake_urlopen,
        )

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())

        self.assertIn("OUTBOX_WRITE_CONFIRMATION", snapshot.missing_fact_types)
        self.assertIn("OUTBOX_WRITE_CONFIRMATION", snapshot.rejected_fact_types)
        self.assertIn("OUTBOX_RECORD_NOT_FOUND_OR_NOT_VISIBLE", snapshot.error_codes)

    def test_platform_error_rejects_server_backed_request_facts_without_exposing_message(self) -> None:
        """Java 平台错误时只返回低敏错误码，不暴露 Java message。"""

        def fake_urlopen(request, timeout: int):
            return FakeHttpResponse(
                {
                    "code": 500,
                    "message": "内部 Java 控制面错误不应进入 Python 响应",
                    "data": None,
                }
            )

        client = JavaAgentRuntimeToolActionResumeFactBundleClient(
            AgentRuntimeResumeFactBundleClientSettings(enabled=True),
            urlopen_func=fake_urlopen,
        )

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())

        self.assertIn("AGENT_RUNTIME_FACT_BUNDLE_PLATFORM_REJECTED", snapshot.error_codes)
        self.assertIn("APPROVAL_CONFIRMATION_FACT", snapshot.rejected_fact_types)
        self.assertIn("OUTBOX_WRITE_CONFIRMATION", snapshot.rejected_fact_types)
        self.assertNotIn("内部 Java 控制面错误", str(snapshot.to_summary()))

    @staticmethod
    def _request_payload() -> dict[str, object]:
        """生成带恢复事实和敏感噪声字段的 resume-preview 请求。"""

        return {
            "resumeFacts": {
                "approvalConfirmationId": "approval-secret-001",
                "outboxConfirmationId": "outbox-secret-001",
            },
            "context": {
                "traceId": "trace-bundle-001",
                "commandId": "command-bundle-001",
                "tenantId": "101",
                "projectId": "202",
            },
            "params": {
                "name": "datasource.metadata.read",
                "arguments": {"datasourceId": "ds-secret"},
            },
            "arguments": {"datasourceId": "ds-secret"},
            "sql": "select * from hidden_table",
            "prompt": "raw prompt should not leak",
        }

    @staticmethod
    def _failing_urlopen(*args, **kwargs):
        """禁用态或本地预检失败时不应访问网络。"""

        raise AssertionError("不应该调用 Java agent-runtime fact bundle")


if __name__ == "__main__":
    unittest.main()
