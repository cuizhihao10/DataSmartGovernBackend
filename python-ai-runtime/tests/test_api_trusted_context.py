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
from datasmart_ai_runtime.api.gateway.trusted_context import (
    enrich_agent_plan_payload_from_gateway_headers,
    enrich_rag_query_payload_from_trusted_headers,
    runtime_event_access_context_from_gateway_headers,
)


class ApiTrustedContextTest(unittest.TestCase):
    """Python API 边界可信事实装配测试。"""

    def test_java_python_gateway_signature_fixed_vector_is_stable(self) -> None:
        """固定向量必须与 Java gateway 完全一致，避免双端 Header 顺序静默漂移。"""

        headers = {
            "X-DataSmart-Source-Service": "datasmart-govern-gateway",
            "X-DataSmart-Trace-Id": "trace-001",
            "X-DataSmart-Tenant-Id": "10",
            "X-DataSmart-Application-Id": "10010",
            "X-DataSmart-Project-Id": "20",
            "X-DataSmart-Actor-Id": "1001",
        }

        self.assertEqual(
            "klJuOvLHb-PydGFyjStf2PwQ3Gy6ID80z-cClQ2iJkg",
            sign_gateway_payload(
                headers,
                timestamp="1800000000000",
                nonce="nonce-001",
                key_id="gateway-local-v1",
                secret="secret-for-test",
            ),
        )

    def test_runtime_event_context_comes_from_signed_headers(self) -> None:
        """Event subscription identity must come from the signed Gateway snapshot."""

        context = runtime_event_access_context_from_gateway_headers(
            self._signed_headers(original_path="/api/agent/events/control"),
            signature_config=GatewaySignatureVerificationConfig(
                required=True,
                secret="secret-for-test",
                key_id="gateway-local-v1",
            ),
            now_ms=1_800_000_000_100,
            nonce_store=InMemoryGatewaySignatureNonceStore(),
        )

        self.assertEqual("10", context.tenant_id)
        self.assertEqual("20", context.project_id)
        self.assertEqual("1001", context.actor_id)
        self.assertEqual(("PROJECT_OWNER",), context.roles)
        self.assertFalse(context.is_platform_admin)

    def test_runtime_event_context_fails_closed_without_gateway_evidence(self) -> None:
        """Required verification cannot fall back to client supplied identity."""

        with self.assertRaisesRegex(PermissionError, "missing-trusted-source"):
            runtime_event_access_context_from_gateway_headers(
                {},
                signature_config=GatewaySignatureVerificationConfig(
                    required=True,
                    secret="secret-for-test",
                ),
            )

    def test_gateway_headers_override_identity_and_rebuild_reserved_namespace(self) -> None:
        """统一 gateway 转发时，应覆盖请求体身份并重建最小可信上下文。"""

        payload = enrich_agent_plan_payload_from_gateway_headers(
            {
                "tenant_id": "forged-tenant",
                "project_id": "forged-project",
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
                "X-DataSmart-Application-Id": "10010",
                "X-DataSmart-Project-Id": "20",
                "X-DataSmart-Actor-Id": "1001",
                "X-DataSmart-Actor-Role": "PROJECT_OWNER",
                "X-DataSmart-Actor-Type": "USER",
                "X-DataSmart-Workspace-Id": "workspace-a",
                "X-DataSmart-Authorized-Project-Ids": "20, 30",
                "X-DataSmart-Authorized-Project-Roles": "20:OWNER,30:READER",
            },
        )

        self.assertEqual("10", payload["tenant_id"])
        self.assertEqual("20", payload["project_id"])
        self.assertEqual("1001", payload["actor_id"])
        self.assertEqual("ds-001", payload["variables"]["datasourceId"])
        trusted = payload["variables"]["trustedControlPlane"]
        self.assertEqual("PROJECT_OWNER", trusted["skillAdmission"]["actorRole"])
        self.assertEqual("workspace-a", trusted["toolBudget"]["workspaceKey"])
        self.assertEqual(("20", "30"), trusted["requestContext"]["authorizedProjectIds"])
        self.assertEqual("USER", trusted["requestContext"]["actorType"])
        self.assertEqual("10010", trusted["applicationId"])
        self.assertEqual("10010", trusted["requestContext"]["applicationId"])
        self.assertEqual("20", trusted["requestContext"]["projectId"])
        self.assertEqual("20:OWNER,30:READER", trusted["requestContext"]["authorizedProjectRoles"])

    def test_gateway_project_outside_authorized_projects_is_rejected(self) -> None:
        """当前项目与授权集合冲突时必须 fail-closed，不能继续信任请求体或工具参数。"""

        with self.assertRaisesRegex(PermissionError, "outside authorized project scope"):
            enrich_agent_plan_payload_from_gateway_headers(
                {"tenant_id": "10", "project_id": "20", "actor_id": "1001", "objective": "test"},
                {
                    "X-DataSmart-Source-Service": "datasmart-govern-gateway",
                    "X-DataSmart-Tenant-Id": "10",
                    "X-DataSmart-Application-Id": "10010",
                    "X-DataSmart-Project-Id": "999",
                    "X-DataSmart-Actor-Id": "1001",
                    "X-DataSmart-Authorized-Project-Ids": "20,30",
                },
            )

    def test_rag_gateway_headers_override_forged_body_scope(self) -> None:
        """RAG 查询只能消费签名 Header 中的身份和项目，正文同名字段没有授权效力。"""

        headers = self._signed_headers(original_path="/api/agent/rag/query")
        payload = enrich_rag_query_payload_from_trusted_headers(
            {
                "tenantId": "999",
                "projectId": "999",
                "actorId": "forged",
                "workspaceKey": "forged-workspace",
                "question": "如何恢复失败同步？",
            },
            headers,
            signature_config=GatewaySignatureVerificationConfig(
                required=True,
                secret="secret-for-test",
            ),
            now_ms=1_800_000_000_100,
        )

        self.assertEqual("10", payload["tenantId"])
        self.assertEqual("20", payload["projectId"])
        self.assertEqual("1001", payload["actorId"])
        self.assertEqual("workspace-a", payload["workspaceKey"])
        self.assertEqual("trace-001", payload["traceId"])
        self.assertEqual("如何恢复失败同步？", payload["question"])
        self.assertNotIn("trustedControlPlane", str(payload))

    def test_rag_body_sensitivity_cannot_downgrade_signed_gateway_default(self) -> None:
        """请求体自报 public 不能把没有可信分级的 gateway 请求降级。"""

        headers = self._signed_headers(original_path="/api/agent/rag/query")
        payload = enrich_rag_query_payload_from_trusted_headers(
            {
                "question": "查询受限故障日志",
                "sensitivityLevel": "public",
                "sensitivity_level": "public",
            },
            headers,
            signature_config=GatewaySignatureVerificationConfig(
                required=True,
                secret="secret-for-test",
            ),
            now_ms=1_800_000_000_100,
        )

        self.assertEqual("internal", payload["sensitivityLevel"])
        self.assertNotIn("sensitivity_level", payload)

    def test_signed_gateway_sensitivity_is_used_and_unknown_value_fails_closed(self) -> None:
        """只有纳入 HMAC 的合法分级才会生效，未知分级按 restricted 处理。"""

        headers = self._signed_headers(
            original_path="/api/agent/rag/query",
            sensitivity_level="confidential",
        )
        payload = enrich_rag_query_payload_from_trusted_headers(
            {"question": "查询历史事故", "sensitivityLevel": "public"},
            headers,
            signature_config=GatewaySignatureVerificationConfig(
                required=True,
                secret="secret-for-test",
            ),
            now_ms=1_800_000_000_100,
        )
        self.assertEqual("confidential", payload["sensitivityLevel"])

        unknown_headers = self._signed_headers(
            original_path="/api/agent/rag/query",
            sensitivity_level="not-a-real-level",
        )
        unknown_payload = enrich_rag_query_payload_from_trusted_headers(
            {"question": "查询历史事故", "sensitivityLevel": "public"},
            unknown_headers,
            signature_config=GatewaySignatureVerificationConfig(
                required=True,
                secret="secret-for-test",
            ),
            now_ms=1_800_000_000_100,
        )
        self.assertEqual("restricted", unknown_payload["sensitivityLevel"])

    def test_untrusted_rag_source_always_uses_restricted_sensitivity(self) -> None:
        """没有 gateway 或内部服务凭证时，任何正文分级都只能按 restricted 处理。"""

        payload = enrich_rag_query_payload_from_trusted_headers(
            {"question": "查询公开说明", "sensitivityLevel": "public"},
            {},
            signature_config=GatewaySignatureVerificationConfig(required=False),
        )

        self.assertEqual("restricted", payload["sensitivityLevel"])

    def test_agent_runtime_sensitivity_uses_header_not_body(self) -> None:
        """Agent Runtime 内部凭证通过后，分级仍只能来自受信 Header。"""

        headers = {
            "X-DataSmart-Source-Service": "agent-runtime",
            "X-DataSmart-Internal-Service-Token": "service-token",
            "X-DataSmart-Tenant-Id": "10",
            "X-DataSmart-Application-Id": "10010",
            "X-DataSmart-Project-Id": "20",
            "X-DataSmart-Authorized-Project-Ids": "20",
            "X-DataSmart-Actor-Id": "1001",
            "X-DataSmart-Workspace-Id": "workspace-a",
            "X-DataSmart-Rag-Sensitivity-Level": "confidential",
        }
        payload = enrich_rag_query_payload_from_trusted_headers(
            {"question": "查询内部日志", "sensitivityLevel": "public"},
            headers,
            internal_service_token="service-token",
        )

        self.assertEqual("confidential", payload["sensitivityLevel"])

    def test_rag_gateway_request_without_signature_fails_closed(self) -> None:
        """生产 RAG 入口不能把只有 source-service 的请求升级成可信项目上下文。"""

        headers = self._signed_headers(original_path="/api/agent/rag/query")
        headers.pop(GATEWAY_SIGNATURE)

        with self.assertRaisesRegex(PermissionError, "missing-signature-headers"):
            enrich_rag_query_payload_from_trusted_headers(
                {"question": "test"},
                headers,
                signature_config=GatewaySignatureVerificationConfig(
                    required=True,
                    secret="secret-for-test",
                ),
                now_ms=1_800_000_000_100,
            )

    def test_rag_agent_runtime_requires_internal_service_token(self) -> None:
        """Java Agent Host 直连 RAG 时也必须证明服务身份，并只使用 Header 作用域。"""

        headers = {
            "X-DataSmart-Source-Service": "agent-runtime",
            "X-DataSmart-Internal-Service-Token": "service-token",
            "X-DataSmart-Trace-Id": "trace-agent-runtime",
            "X-DataSmart-Tenant-Id": "10",
            "X-DataSmart-Application-Id": "10010",
            "X-DataSmart-Project-Id": "20",
            "X-DataSmart-Authorized-Project-Ids": "20",
            "X-DataSmart-Actor-Id": "1001",
            "X-DataSmart-Workspace-Id": "workspace-a",
        }
        payload = enrich_rag_query_payload_from_trusted_headers(
            {"projectId": "999", "question": "test"},
            headers,
            internal_service_token="service-token",
        )
        self.assertEqual("20", payload["projectId"])

        headers["X-DataSmart-Internal-Service-Token"] = "wrong-token"
        with self.assertRaisesRegex(PermissionError, "service token"):
            enrich_rag_query_payload_from_trusted_headers(
                {"question": "test"},
                headers,
                internal_service_token="service-token",
            )

    def test_authorized_project_roles_are_covered_by_gateway_signature(self) -> None:
        headers = self._signed_headers()
        headers["X-DataSmart-Authorized-Project-Roles"] = "20:MANAGER"
        headers[GATEWAY_SIGNATURE] = sign_gateway_payload(
            headers,
            timestamp=headers[GATEWAY_SIGNATURE_TIMESTAMP],
            nonce=headers[GATEWAY_SIGNATURE_NONCE],
            key_id=headers[GATEWAY_SIGNATURE_KEY_ID],
            secret="secret-for-test",
        )

        payload = enrich_agent_plan_payload_from_gateway_headers(
            {"tenant_id": "10", "project_id": "20", "actor_id": "1001", "objective": "test"},
            headers,
            signature_config=GatewaySignatureVerificationConfig(required=True, secret="secret-for-test"),
            now_ms=1_800_000_000_100,
        )

        self.assertEqual(
            "20:MANAGER",
            payload["variables"]["trustedControlPlane"]["requestContext"]["authorizedProjectRoles"],
        )

    def test_application_scope_is_covered_by_gateway_signature(self) -> None:
        """签名完成后篡改 applicationId 必须被拒绝，防止同租户跨应用复用项目上下文。"""

        headers = self._signed_headers()
        headers["X-DataSmart-Application-Id"] = "20020"

        with self.assertRaisesRegex(PermissionError, "signature-mismatch"):
            enrich_agent_plan_payload_from_gateway_headers(
                {"variables": {}},
                headers,
                signature_config=GatewaySignatureVerificationConfig(required=True, secret="secret-for-test"),
                now_ms=1_800_000_000_100,
            )

    def test_gateway_project_scope_without_application_is_rejected(self) -> None:
        """已识别的 Gateway 项目请求缺少应用 ID 时不能回退到请求体或 projectId。"""

        with self.assertRaisesRegex(PermissionError, "application context is missing or invalid"):
            enrich_agent_plan_payload_from_gateway_headers(
                {"tenant_id": "10", "project_id": "20", "actor_id": "1001", "variables": {}},
                {
                    "X-DataSmart-Source-Service": "datasmart-govern-gateway",
                    "X-DataSmart-Tenant-Id": "10",
                    "X-DataSmart-Project-Id": "20",
                    "X-DataSmart-Actor-Id": "1001",
                },
            )

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

    def _signed_headers(
        self,
        *,
        timestamp: str = "1800000000000",
        original_path: str = "/api/agent/plans",
        sensitivity_level: str | None = None,
    ) -> dict[str, str]:
        """构造与 Java gateway 签名协议一致的测试 Header。"""

        headers = {
            "X-DataSmart-Source-Service": "datasmart-govern-gateway",
            "X-Gateway-Original-Path": original_path,
            "X-Gateway-Route-Prefix": "/api/agent",
            "X-DataSmart-Trace-Id": "trace-001",
            "X-DataSmart-Tenant-Id": "10",
            "X-DataSmart-Application-Id": "10010",
            "X-DataSmart-Project-Id": "20",
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
        if sensitivity_level is not None:
            headers["X-DataSmart-Rag-Sensitivity-Level"] = sensitivity_level
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
