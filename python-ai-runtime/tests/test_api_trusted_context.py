import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.gateway.signature import (
    GatewaySignatureVerificationConfig,
    GATEWAY_SIGNATURE,
    GATEWAY_SIGNATURE_KEY_ID,
    GATEWAY_SIGNATURE_NONCE,
    GATEWAY_SIGNATURE_TIMESTAMP,
    GATEWAY_SIGNATURE_VERSION,
    sign_gateway_payload,
)
from datasmart_ai_runtime.api.gateway.security import InMemoryGatewaySignatureNonceStore
from datasmart_ai_runtime.api.gateway.trusted_context import enrich_agent_plan_payload_from_gateway_headers


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

    def test_signed_gateway_cache_headers_inject_skill_visibility_cache_context(self) -> None:
        """签名保护的缓存 Header 应进入 Skill 可见性缓存上下文。

        这里验证的是安全装配边界：请求体中伪造的 ``trustedControlPlane`` 会先被删除，只有经过 gateway
        HMAC 签名保护的 Header 才能重建 ``skillVisibilityCache``。Python Runtime 后续只缓存 Skill
        准入判断，不缓存完整请求或模型输出。
        """

        headers = self._signed_headers()
        headers["X-DataSmart-Skill-Visibility-Cache-Key"] = "gateway-cache-key-001"
        headers["X-DataSmart-Skill-Visibility-Cache-Version"] = "v1"
        headers["X-DataSmart-Skill-Visibility-Cache-Scope"] = "session-ready-skill-admission"
        headers["X-DataSmart-Skill-Visibility-Cache-Ttl-Seconds"] = "120"
        headers[GATEWAY_SIGNATURE] = sign_gateway_payload(
            headers,
            timestamp=headers[GATEWAY_SIGNATURE_TIMESTAMP],
            nonce=headers[GATEWAY_SIGNATURE_NONCE],
            key_id=headers[GATEWAY_SIGNATURE_KEY_ID],
            secret="secret-for-test",
        )

        payload = enrich_agent_plan_payload_from_gateway_headers(
            {"variables": {"trustedControlPlane": {"skillVisibilityCache": {"gatewayCacheKey": "forged"}}}},
            headers,
            signature_config=GatewaySignatureVerificationConfig(required=True, secret="secret-for-test"),
            now_ms=1_800_000_000_100,
        )

        cache_context = payload["variables"]["trustedControlPlane"]["skillVisibilityCache"]
        self.assertTrue(cache_context["enabled"])
        self.assertEqual("gateway-cache-key-001", cache_context["gatewayCacheKey"])
        self.assertEqual(120, cache_context["ttlSeconds"])
        self.assertEqual("STANDARD", cache_context["tenantPlanCode"])

    def test_signed_gateway_tool_policy_envelope_injects_budget_and_readiness_policy(self) -> None:
        """签名保护的工具策略 envelope 应进入 trustedControlPlane。

        该用例固定 gateway 一次性注入 `toolCallBudget + toolExecutionReadinessPolicy` 的协议形态：
        请求体里伪造的受信命名空间会被删除，Header 中的 envelope 会被 HMAC 签名覆盖，Python 只裁剪
        低敏字段，不透传 prompt、SQL、工具参数或内部 endpoint。
        """

        headers = self._signed_headers()
        headers["X-DataSmart-Tool-Policy-Envelope"] = json.dumps(
            {
                "toolCallBudget": {
                    "policyVersion": "gateway-policy-v2",
                    "maxProposedToolCalls": 6,
                    "maxAutoExecutableToolCalls": 1,
                    "maxHighRiskToolCalls": 0,
                    "prompt": "should-not-leak",
                },
                "toolExecutionReadinessPolicy": {
                    "source": "permission-admin",
                    "policyVersion": "readiness-v2",
                    "actorRole": "AUDITOR",
                    "tenantPlanCode": "TRIAL",
                    "workspaceRiskLevel": "HIGH",
                    "workerBacklogLevel": "CRITICAL",
                    "maxAutoSyncTools": 0,
                    "maxAsyncTools": 0,
                    "allowDraftWithoutAllParameters": False,
                    "influenceCodes": ["REMOTE_PERMISSION_ADMIN_POLICY"],
                    "sql": "select * from secret_table",
                    "arguments": {"datasourceId": "ds-sensitive"},
                    "internalEndpoint": "http://permission-admin.internal",
                },
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        headers[GATEWAY_SIGNATURE] = sign_gateway_payload(
            headers,
            timestamp=headers[GATEWAY_SIGNATURE_TIMESTAMP],
            nonce=headers[GATEWAY_SIGNATURE_NONCE],
            key_id=headers[GATEWAY_SIGNATURE_KEY_ID],
            secret="secret-for-test",
        )

        payload = enrich_agent_plan_payload_from_gateway_headers(
            {
                "variables": {
                    "trustedControlPlane": {
                        "toolBudget": {"maxAutoExecutableToolCalls": 99},
                        "toolExecutionReadinessPolicy": {"actorRole": "PLATFORM_ADMIN"},
                    }
                }
            },
            headers,
            signature_config=GatewaySignatureVerificationConfig(required=True, secret="secret-for-test"),
            now_ms=1_800_000_000_100,
        )

        trusted = payload["variables"]["trustedControlPlane"]
        tool_budget = trusted["toolBudget"]
        readiness_policy = trusted["toolExecutionReadinessPolicy"]
        serialized_trusted = str(trusted)
        self.assertEqual("gateway-policy-v2", tool_budget["policyVersion"])
        self.assertEqual(6, tool_budget["maxProposedToolCalls"])
        self.assertEqual(1, tool_budget["maxAutoExecutableToolCalls"])
        self.assertEqual(0, tool_budget["maxHighRiskToolCalls"])
        self.assertEqual("permission-admin", readiness_policy["source"])
        self.assertEqual("readiness-v2", readiness_policy["policyVersion"])
        self.assertEqual("AUDITOR", readiness_policy["actorRole"])
        self.assertEqual(0, readiness_policy["maxAutoSyncTools"])
        self.assertEqual(("REMOTE_PERMISSION_ADMIN_POLICY",), readiness_policy["influenceCodes"])
        self.assertNotIn("should-not-leak", serialized_trusted)
        self.assertNotIn("secret_table", serialized_trusted)
        self.assertNotIn("ds-sensitive", serialized_trusted)
        self.assertNotIn("permission-admin.internal", serialized_trusted)

    def test_signed_gateway_tool_policy_envelope_rejects_malformed_json(self) -> None:
        """签名链路中的策略 envelope 格式错误时应 fail-closed。

        这里不是验证 HMAC 本身，而是验证“签名通过后的控制面载荷仍必须满足结构契约”。
        如果 gateway 或 agent-runtime 已经声明要下发工具策略 envelope，但实际内容不是 JSON object，
        Python Runtime 不能把它当作缺失处理后继续使用本地默认预算；否则高风险租户、试用套餐、审计角色
        或 worker backlog 限流策略会被意外绕过。
        """

        headers = self._signed_headers()
        headers["X-DataSmart-Tool-Policy-Envelope"] = "not-json"
        headers[GATEWAY_SIGNATURE] = sign_gateway_payload(
            headers,
            timestamp=headers[GATEWAY_SIGNATURE_TIMESTAMP],
            nonce=headers[GATEWAY_SIGNATURE_NONCE],
            key_id=headers[GATEWAY_SIGNATURE_KEY_ID],
            secret="secret-for-test",
        )

        with self.assertRaisesRegex(PermissionError, "gateway tool policy envelope must be a JSON object"):
            enrich_agent_plan_payload_from_gateway_headers(
                {"variables": {}},
                headers,
                signature_config=GatewaySignatureVerificationConfig(required=True, secret="secret-for-test"),
                now_ms=1_800_000_000_100,
            )

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

    def test_replayed_nonce_is_rejected_after_valid_signature_once(self) -> None:
        """同一个合法签名 nonce 在 TTL 内只能使用一次。"""

        headers = self._signed_headers()
        nonce_store = InMemoryGatewaySignatureNonceStore()
        signature_config = GatewaySignatureVerificationConfig(
            required=True,
            secret="secret-for-test",
            nonce_ttl_seconds=300,
        )

        enrich_agent_plan_payload_from_gateway_headers(
            {"variables": {"datasourceId": "ds-001"}},
            headers,
            signature_config=signature_config,
            now_ms=1_800_000_000_100,
            nonce_store=nonce_store,
        )

        with self.assertRaisesRegex(PermissionError, "nonce-replayed"):
            enrich_agent_plan_payload_from_gateway_headers(
                {"variables": {"datasourceId": "ds-001"}},
                headers,
                signature_config=signature_config,
                now_ms=1_800_000_000_200,
                nonce_store=nonce_store,
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
            "X-DataSmart-Tenant-Plan-Code": "STANDARD",
            "X-DataSmart-Workspace-Risk-Level": "NORMAL",
            "X-DataSmart-Tool-Budget-Policy-Version": "gateway-default-v1",
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
