import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    JavaPermissionAdminToolActionResumeFactClient,
    PermissionAdminResumeFactClientSettings,
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
    """只提供远程审批事实校验需要的低敏 checkpoint 字段。"""

    checkpoint_id = "tool-action-checkpoint:remote-001"
    thread_id = "run-remote-001"
    tenant_id = "101"
    project_id = "202"
    actor_id = "actor-remote"
    request_id = "request-remote"
    run_id = "run-remote-001"
    session_id = "session-remote-001"
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


class ToolActionResumeFactClientTest(unittest.TestCase):
    """permission-admin 恢复事实远程客户端测试。

    这组测试不启动 Java 服务，保护的是 Python -> permission-admin 的安全契约：
    - 默认关闭时不能产生 HTTP 副作用；
    - 缺少 approvalFactId 时不能绕过校验；
    - 启用后只提交 Java DTO 白名单字段；
    - Java 拒绝时必须返回 rejectedFactTypes，让 API 层剔除客户端自报事实。
    """

    def test_disabled_client_returns_empty_snapshot_without_http(self) -> None:
        """默认关闭态只返回空事实，不访问网络。"""

        client = JavaPermissionAdminToolActionResumeFactClient(urlopen_func=self._failing_urlopen)

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())

        self.assertEqual("PERMISSION_ADMIN_APPROVAL_FACT_PROVIDER_DISABLED", snapshot.source)
        self.assertEqual((), snapshot.available_fact_types)
        self.assertEqual((), snapshot.rejected_fact_types)

    def test_missing_approval_fact_id_is_not_sent_to_java(self) -> None:
        """没有 approvalFactId 时应 fail-closed，并且不能调用 permission-admin。"""

        client = JavaPermissionAdminToolActionResumeFactClient(
            PermissionAdminResumeFactClientSettings(enabled=True),
            urlopen_func=self._failing_urlopen,
        )

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload={"context": {"traceId": "trace-001"}})

        self.assertEqual("PERMISSION_ADMIN_APPROVAL_FACT_PROVIDER", snapshot.source)
        self.assertIn("APPROVAL_CONFIRMATION_FACT", snapshot.missing_fact_types)
        self.assertIn("APPROVAL_FACT_ID_REQUIRED_FOR_REMOTE_VALIDATION", snapshot.error_codes)

    def test_enabled_client_posts_low_sensitive_java_request_and_accepts_approved_fact(self) -> None:
        """启用远程校验后，应 POST Java 审批事实评估接口并只返回低敏事实类型。"""

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
                        "approvalFactId": "approval-secret-001",
                        "approved": True,
                        "decision": "APPROVED",
                        "policyVersion": "tool-readiness-policy.v1",
                        "arguments": {"datasourceId": "ds-secret"},
                        "sql": "select * from hidden_table",
                    },
                }
            )

        client = JavaPermissionAdminToolActionResumeFactClient(
            PermissionAdminResumeFactClientSettings(
                enabled=True,
                base_url="http://permission-admin.test",
                timeout_seconds=5,
                service_token="service-token-secret",
            ),
            urlopen_func=fake_urlopen,
        )

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())
        summary = snapshot.to_summary()

        self.assertEqual(
            "http://permission-admin.test/permissions/agent/tool-action-approvals/evaluate",
            captured["url"],
        )
        self.assertEqual(5, captured["timeout"])
        self.assertEqual("approval-secret-001", captured["payload"]["approvalFactId"])
        self.assertEqual(101, captured["payload"]["tenantId"])
        self.assertEqual(202, captured["payload"]["projectId"])
        self.assertEqual("actor-remote", captured["payload"]["actorId"])
        self.assertEqual("command-remote-001", captured["payload"]["commandId"])
        self.assertEqual("datasource.metadata.read", captured["payload"]["toolCode"])
        self.assertEqual("tool-readiness-policy.v1", captured["payload"]["requestedPolicyVersion"])
        self.assertNotIn("arguments", captured["payload"])
        self.assertNotIn("sql", captured["payload"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", snapshot.available_fact_types)
        self.assertIn("POLICY_VERSION", snapshot.available_fact_types)
        self.assertEqual(1, snapshot.fact_reference_count)
        self.assertNotIn("approval-secret-001", str(summary))
        self.assertNotIn("service-token-secret", str(summary))
        self.assertNotIn("ds-secret", str(summary))
        self.assertNotIn("hidden_table", str(summary))

    def test_not_approved_response_rejects_request_supplied_approval_fact(self) -> None:
        """Java 判定未通过时必须返回 rejectedFactTypes，禁止客户端自报 approval 继续生效。"""

        def fake_urlopen(request, timeout: int):
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "approvalFactId": "approval-secret-001",
                        "approved": False,
                        "retryable": False,
                        "decision": "SCOPE_MISMATCH",
                        "issueCodes": ["SCOPE_MISMATCH"],
                    },
                }
            )

        client = JavaPermissionAdminToolActionResumeFactClient(
            PermissionAdminResumeFactClientSettings(enabled=True),
            urlopen_func=fake_urlopen,
        )

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())
        summary = snapshot.to_summary()

        self.assertEqual((), snapshot.available_fact_types)
        self.assertEqual(("APPROVAL_CONFIRMATION_FACT",), snapshot.rejected_fact_types)
        self.assertIn("APPROVAL_FACT_NOT_APPROVED", snapshot.error_codes)
        self.assertIn("SCOPE_MISMATCH", snapshot.error_codes)
        self.assertNotIn("approval-secret-001", str(summary))

    def test_platform_error_rejects_approval_fact_without_exposing_java_message(self) -> None:
        """Java 统一响应 code 非 0 时，摘要只暴露低敏错误码。"""

        def fake_urlopen(request, timeout: int):
            return FakeHttpResponse(
                {
                    "code": 403,
                    "message": "内部审批策略详情不应出现在 Python 响应中",
                    "data": None,
                }
            )

        client = JavaPermissionAdminToolActionResumeFactClient(
            PermissionAdminResumeFactClientSettings(enabled=True),
            urlopen_func=fake_urlopen,
        )

        snapshot = client.collect(checkpoint=FakeCheckpoint(), request_payload=self._request_payload())

        self.assertEqual(("APPROVAL_CONFIRMATION_FACT",), snapshot.rejected_fact_types)
        self.assertEqual(("PERMISSION_ADMIN_PLATFORM_RESPONSE_REJECTED",), snapshot.error_codes)
        self.assertNotIn("内部审批策略详情", str(snapshot.to_summary()))

    @staticmethod
    def _request_payload() -> dict[str, object]:
        """生成带审批事实和敏感噪声字段的恢复预检请求。"""

        return {
            "resumeFacts": {
                "approvalConfirmationId": "approval-secret-001",
            },
            "context": {
                "traceId": "trace-remote-001",
                "commandId": "command-remote-001",
            },
            "params": {
                "name": "datasource.metadata.read",
                "arguments": {"datasourceId": "ds-secret"},
            },
            "arguments": {"datasourceId": "ds-secret"},
            "sql": "select * from hidden_table",
        }

    @staticmethod
    def _failing_urlopen(*args, **kwargs):
        """禁用态或本地预检失败时不应访问网络。"""

        raise AssertionError("不应该调用 permission-admin")


if __name__ == "__main__":
    unittest.main()
