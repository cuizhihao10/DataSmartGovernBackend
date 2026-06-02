import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api_gateway_signature import (
    GatewaySignatureVerificationConfig,
    GATEWAY_SIGNATURE,
    GATEWAY_SIGNATURE_KEY_ID,
    GATEWAY_SIGNATURE_NONCE,
    GATEWAY_SIGNATURE_TIMESTAMP,
    GATEWAY_SIGNATURE_VERSION,
    sign_gateway_payload,
)
from datasmart_ai_runtime.api_trusted_context import enrich_agent_plan_payload_from_gateway_headers


class ApiTrustedContextTest(unittest.TestCase):
    """Python API 边界可信事实装配测试。"""

    def test_gateway_headers_override_identity_and_rebuild_reserved_namespace(self) -> None:
        """统一 gateway 转发时，应覆盖请求体身份并重建最小可信上下文。"""

        payload = enrich_agent_plan_payload_from_gateway_headers(
            {
                "tenant_id": "forged-tenant",
                "actor_id": "forged-actor",
                "variables": {
                    "datasourceId": "ds-001",
                    "trustedControlPlane": {"skillAdmission": {"actorRole": "PLATFORM_ADMIN"}},
                },
            },
            {
                "X-DataSmart-Source-Service": "datasmart-govern-gateway",
                "X-DataSmart-Trace-Id": "trace-001",
                "X-DataSmart-Tenant-Id": "10",
                "X-DataSmart-Actor-Id": "1001",
                "X-DataSmart-Actor-Role": "PROJECT_OWNER",
                "X-DataSmart-Workspace-Id": "workspace-a",
                "X-DataSmart-Authorized-Project-Ids": "20, 30",
            },
        )

        self.assertEqual("10", payload["tenant_id"])
        self.assertEqual("1001", payload["actor_id"])
        self.assertEqual("ds-001", payload["variables"]["datasourceId"])
        trusted = payload["variables"]["trustedControlPlane"]
        self.assertEqual("PROJECT_OWNER", trusted["skillAdmission"]["actorRole"])
        self.assertEqual("workspace-a", trusted["toolBudget"]["workspaceKey"])
        self.assertEqual(("20", "30"), trusted["requestContext"]["authorizedProjectIds"])

    def test_untrusted_source_strips_forged_reserved_namespace_without_injecting_headers(self) -> None:
        """直连或来源不明请求不能通过请求体或伪造身份字段创建可信上下文。"""

        payload = enrich_agent_plan_payload_from_gateway_headers(
            {"variables": {"trustedControlPlane": {"toolBudget": {"actorRole": "PLATFORM_ADMIN"}}}},
            {"X-DataSmart-Actor-Role": "PLATFORM_ADMIN"},
        )

        self.assertNotIn("trustedControlPlane", payload["variables"])

    def test_signed_gateway_headers_can_inject_trusted_context(self) -> None:
        """强制验签开启后，签名正确的 gateway Header 才能重建可信上下文。"""

        headers = self._signed_headers()
        payload = enrich_agent_plan_payload_from_gateway_headers(
            {"variables": {"datasourceId": "ds-001"}},
            headers,
            signature_config=GatewaySignatureVerificationConfig(required=True, secret="secret-for-test"),
            now_ms=1_800_000_000_100,
        )

        trusted = payload["variables"]["trustedControlPlane"]
        self.assertEqual("datasmart-govern-gateway", trusted["requestContext"]["sourceService"])
        self.assertEqual("PROJECT_OWNER", trusted["toolBudget"]["actorRole"])

    def test_required_signature_rejects_forged_gateway_source(self) -> None:
        """只伪造 source-service 但没有签名时，应拒绝注入可信上下文。"""

        with self.assertRaisesRegex(PermissionError, "missing-signature-headers"):
            enrich_agent_plan_payload_from_gateway_headers(
                {"variables": {"trustedControlPlane": {"toolBudget": {"actorRole": "PLATFORM_ADMIN"}}}},
                {"X-DataSmart-Source-Service": "datasmart-govern-gateway"},
                signature_config=GatewaySignatureVerificationConfig(required=True, secret="secret-for-test"),
                now_ms=1_800_000_000_100,
            )

    def test_bad_signature_is_rejected_when_secret_configured(self) -> None:
        """配置密钥后，即使 required=false，也应拒绝错误签名。"""

        headers = self._signed_headers()
        headers[GATEWAY_SIGNATURE] = "bad-signature"

        with self.assertRaisesRegex(PermissionError, "signature-mismatch"):
            enrich_agent_plan_payload_from_gateway_headers(
                {"variables": {}},
                headers,
                signature_config=GatewaySignatureVerificationConfig(required=False, secret="secret-for-test"),
                now_ms=1_800_000_000_100,
            )

    def test_expired_signature_is_rejected(self) -> None:
        """超过时间窗口的签名应被拒绝，降低抓包后重放请求的风险。"""

        headers = self._signed_headers(timestamp="1800000000000")

        with self.assertRaisesRegex(PermissionError, "timestamp-out-of-window"):
            enrich_agent_plan_payload_from_gateway_headers(
                {"variables": {}},
                headers,
                signature_config=GatewaySignatureVerificationConfig(
                    required=True,
                    secret="secret-for-test",
                    max_skew_seconds=1,
                ),
                now_ms=1_800_000_010_000,
            )

    def _signed_headers(self, *, timestamp: str = "1800000000000") -> dict[str, str]:
        """构造与 Java gateway 签名协议一致的测试 Header。"""

        headers = {
            "X-DataSmart-Source-Service": "datasmart-govern-gateway",
            "X-Gateway-Original-Path": "/api/agent/plans",
            "X-Gateway-Route-Prefix": "/api/agent",
            "X-DataSmart-Trace-Id": "trace-001",
            "X-DataSmart-Tenant-Id": "10",
            "X-DataSmart-Actor-Id": "1001",
            "X-DataSmart-Actor-Role": "PROJECT_OWNER",
            "X-DataSmart-Actor-Type": "USER",
            "X-DataSmart-Workspace-Id": "workspace-a",
            "X-DataSmart-Request-Source": "WEB_UI",
            "X-DataSmart-Data-Scope-Level": "PROJECT",
            "X-DataSmart-Authorized-Project-Ids": "20,30",
            GATEWAY_SIGNATURE_VERSION: "v1",
            GATEWAY_SIGNATURE_TIMESTAMP: timestamp,
            GATEWAY_SIGNATURE_NONCE: "nonce-001",
            GATEWAY_SIGNATURE_KEY_ID: "gateway-local-v1",
        }
        headers[GATEWAY_SIGNATURE] = sign_gateway_payload(
            headers,
            timestamp=timestamp,
            nonce="nonce-001",
            key_id="gateway-local-v1",
            secret="secret-for-test",
        )
        return headers


if __name__ == "__main__":
    unittest.main()
