import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

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


if __name__ == "__main__":
    unittest.main()
