import os
import sys
import time
import unittest
from dataclasses import dataclass
from types import SimpleNamespace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.tool_action_checkpoint_routes import register_tool_action_checkpoint_routes
from datasmart_ai_runtime.api.gateway.signature import (
    GATEWAY_SIGNATURE,
    GATEWAY_SIGNATURE_KEY_ID,
    GATEWAY_SIGNATURE_NONCE,
    GATEWAY_SIGNATURE_TIMESTAMP,
    GATEWAY_SIGNATURE_VERSION,
    sign_gateway_payload,
)
from datasmart_ai_runtime.api.gateway.security import GatewaySignatureSecurityStats
from datasmart_ai_runtime.services.tools import InMemoryToolActionExecutionCheckpointStore


class FakeHttpException(Exception):
    """测试用 HTTP 异常，模拟 FastAPI HTTPException 的关键字段。"""

    def __init__(self, status_code: int, detail: dict[str, object]) -> None:
        self.status_code = status_code
        self.detail = detail
        super().__init__(detail.get("message", "HTTP error"))


class FakeApp:
    """极简路由注册器，用于直接拿到 checkpoint handler。"""

    def __init__(self) -> None:
        self.post_routes: dict[str, object] = {}

    def post(self, path: str):
        """模拟 FastAPI 的 `@app.post(...)` 装饰器。"""

        def decorator(func):
            self.post_routes[path] = func
            return func

        return decorator


@dataclass(frozen=True)
class FakeRequest:
    """测试用 Request，只提供路由安全逻辑需要的 headers 与 url.path。"""

    headers: dict[str, str]
    path: str = "/agent/tool-actions/checkpoints/query"

    @property
    def url(self) -> SimpleNamespace:
        """模拟 Starlette Request.url.path。"""

        return SimpleNamespace(path=self.path)


class ToolActionCheckpointRouteSecurityTest(unittest.TestCase):
    """checkpoint query/resume-preview 的网关签名安全测试。

    这组测试不验证 HMAC 算法本身，HMAC 算法已有独立测试；这里验证的是 checkpoint 路由的产品级语义：
    - fail-closed 开启后，缺少签名的 gateway 请求必须拒绝；
    - 签名通过后，tenantId/actorId 应以 gateway Header 为准，而不是请求体自报；
    - 响应和 runtime event 只能返回低敏安全边界，不泄露签名、nonce、secret 或完整 Header。
    """

    def setUp(self) -> None:
        self.store = InMemoryToolActionExecutionCheckpointStore(max_checkpoints_per_thread=5, max_total_checkpoints=20)

    def test_required_signature_rejects_unsigned_checkpoint_query(self) -> None:
        """checkpoint 路由开启 fail-closed 后，伪造 gateway 来源但无签名应返回稳定安全错误。"""

        app = FakeApp()
        stats = GatewaySignatureSecurityStats()
        register_tool_action_checkpoint_routes(
            app,
            request_type=FakeRequest,
            checkpoint_store=self.store,
            gateway_signature_required=True,
            gateway_signature_error_factory=lambda detail: FakeHttpException(status_code=401, detail=detail),
            gateway_signature_security_stats=stats,
        )

        with _patched_env(DATASMART_GATEWAY_SIGNATURE_SECRET="secret-for-checkpoint-test"):
            with self.assertRaises(FakeHttpException) as raised:
                app.post_routes["/agent/tool-actions/checkpoints/query"](
                    {"checkpointId": "checkpoint-unsigned", "tenantId": "tenant-forged"},
                    FakeRequest(
                        headers={
                            "X-DataSmart-Source-Service": "datasmart-govern-gateway",
                            "X-DataSmart-Trace-Id": "trace-checkpoint-unsigned",
                        }
                    ),
                )

        self.assertEqual(401, raised.exception.status_code)
        detail = raised.exception.detail
        self.assertEqual("CHECKPOINT_GATEWAY_SIGNATURE_INVALID", detail["code"])
        self.assertEqual("missing-signature-headers", detail["reason"])
        self.assertEqual("trace-checkpoint-unsigned", detail["traceId"])
        self.assertEqual({"missing-signature-headers": 1}, stats.snapshot()["failureCountByReason"])
        self.assertNotIn("secret-for-checkpoint-test", str(detail))

    def test_signed_checkpoint_query_uses_gateway_identity_and_marks_readiness(self) -> None:
        """签名通过后，路由应使用 gateway 身份上下文查询 checkpoint，并标记本次授权缺口已闭合。"""

        checkpoint = self.store.save(
            _graph_run_for_resume(),
            context={
                "tenantId": "tenant-secure",
                "projectId": "project-secure",
                "actorId": "actor-secure",
                "runId": "run-secure",
            },
        )
        app = FakeApp()
        register_tool_action_checkpoint_routes(
            app,
            request_type=FakeRequest,
            checkpoint_store=self.store,
            gateway_signature_required=True,
            gateway_signature_error_factory=lambda detail: FakeHttpException(status_code=401, detail=detail),
        )

        headers = _signed_checkpoint_headers(
            tenant_id="tenant-secure",
            actor_id="actor-secure",
            authorized_project_ids="project-secure",
            path="/agent/tool-actions/checkpoints/query",
        )
        with _patched_env(DATASMART_GATEWAY_SIGNATURE_SECRET="secret-for-checkpoint-test"):
            response = app.post_routes["/agent/tool-actions/checkpoints/query"](
                {
                    "checkpointId": checkpoint.checkpoint_id,
                    "tenantId": "tenant-forged",
                    "actorId": "actor-forged",
                    "projectId": "project-secure",
                    "includeGraphRun": True,
                },
                FakeRequest(headers=headers),
            )

        security = response["securityBoundary"]
        production = response["productionReadiness"]
        event_attributes = response["runtimeEvent"]["attributes"]
        serialized = str(response)

        self.assertEqual(1, response["checkpointCount"])
        self.assertEqual("GATEWAY_HMAC_VERIFIED", security["authMode"])
        self.assertTrue(security["gatewaySignatureRequired"])
        self.assertTrue(security["gatewaySignatureVerified"])
        self.assertTrue(security["tenantContextApplied"])
        self.assertTrue(security["actorContextApplied"])
        self.assertEqual("GATEWAY_HMAC_VERIFIED", production["currentAuthorizationMode"])
        self.assertNotIn("GATEWAY_OR_SERVICE_ACCOUNT_AUTHORIZATION", production["missingProductionRequirements"])
        self.assertTrue(event_attributes["gatewaySignatureVerified"])
        self.assertTrue(event_attributes["checkpointAuthFailClosed"])
        self.assertEqual("GATEWAY_HMAC_VERIFIED", event_attributes["checkpointAuthMode"])
        self.assertNotIn(headers[GATEWAY_SIGNATURE], serialized)
        self.assertNotIn(headers[GATEWAY_SIGNATURE_NONCE], serialized)
        self.assertNotIn("secret-for-checkpoint-test", serialized)

    def test_required_signature_rejects_project_outside_gateway_scope(self) -> None:
        """fail-closed 模式下，请求 projectId 不在 gateway 授权项目集合中时应拒绝。"""

        app = FakeApp()
        register_tool_action_checkpoint_routes(
            app,
            request_type=FakeRequest,
            checkpoint_store=self.store,
            gateway_signature_required=True,
            gateway_signature_error_factory=lambda detail: FakeHttpException(status_code=401, detail=detail),
        )
        headers = _signed_checkpoint_headers(
            tenant_id="tenant-secure",
            actor_id="actor-secure",
            authorized_project_ids="project-allowed",
            path="/agent/tool-actions/checkpoints/query",
        )

        with _patched_env(DATASMART_GATEWAY_SIGNATURE_SECRET="secret-for-checkpoint-test"):
            with self.assertRaises(FakeHttpException) as raised:
                app.post_routes["/agent/tool-actions/checkpoints/query"](
                    {"checkpointId": "checkpoint-any", "projectId": "project-denied"},
                    FakeRequest(headers=headers),
                )

        self.assertEqual("project-not-authorized", raised.exception.detail["reason"])


def _signed_checkpoint_headers(
    *,
    tenant_id: str,
    actor_id: str,
    authorized_project_ids: str,
    path: str,
) -> dict[str, str]:
    """构造与 Java gateway 签名协议一致的 checkpoint 测试 Header。"""

    timestamp = str(int(time.time() * 1000))
    nonce = f"nonce-{timestamp}"
    headers = {
        "X-DataSmart-Source-Service": "datasmart-govern-gateway",
        "X-Gateway-Original-Path": path,
        "X-Gateway-Route-Prefix": "/api/agent",
        "X-DataSmart-Trace-Id": "trace-checkpoint-signed",
        "X-DataSmart-Tenant-Id": tenant_id,
        "X-DataSmart-Actor-Id": actor_id,
        "X-DataSmart-Actor-Role": "PROJECT_OWNER",
        "X-DataSmart-Actor-Type": "USER",
        "X-DataSmart-Workspace-Id": "workspace-secure",
        "X-DataSmart-Request-Source": "WEB_UI",
        "X-DataSmart-Tenant-Plan-Code": "STANDARD",
        "X-DataSmart-Workspace-Risk-Level": "NORMAL",
        "X-DataSmart-Tool-Budget-Policy-Version": "gateway-default-v1",
        "X-DataSmart-Data-Scope-Level": "PROJECT",
        "X-DataSmart-Authorized-Project-Ids": authorized_project_ids,
        GATEWAY_SIGNATURE_VERSION: "v1",
        GATEWAY_SIGNATURE_TIMESTAMP: timestamp,
        GATEWAY_SIGNATURE_NONCE: nonce,
        GATEWAY_SIGNATURE_KEY_ID: "gateway-local-v1",
    }
    headers[GATEWAY_SIGNATURE] = sign_gateway_payload(
        headers,
        timestamp=timestamp,
        nonce=nonce,
        key_id="gateway-local-v1",
        secret="secret-for-checkpoint-test",
    )
    return headers


def _graph_run_for_resume() -> dict[str, object]:
    """生成一个等待 payload/policy 证据的低敏执行前图摘要。"""

    return {
        "schemaVersion": "datasmart.python-ai-runtime.tool-action-execution-graph-runner.v1",
        "previewOnly": True,
        "statusCounts": {"WAITING_COMMAND_PROPOSAL_EVIDENCE": 1},
        "steps": (
            {
                "nodeType": "TOOL_ACTION_COMMAND_PROPOSAL",
                "toolName": "datasource.metadata.read",
                "decision": "ready",
                "stepStatus": "WAITING_COMMAND_PROPOSAL_EVIDENCE",
            },
        ),
        "resumeRequirements": ("GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE",),
        "sideEffectBoundary": {
            "toolExecuted": False,
            "outboxWritten": False,
            "workerDispatched": False,
            "approvalCreated": False,
        },
    }


class _patched_env:
    """临时设置环境变量，避免安全测试污染其他用例。"""

    def __init__(self, **values: str) -> None:
        self._values = values
        self._previous: dict[str, str | None] = {}

    def __enter__(self) -> None:
        for key, value in self._values.items():
            self._previous[key] = os.environ.get(key)
            os.environ[key] = value

    def __exit__(self, exc_type, exc, tb) -> None:
        for key, previous in self._previous.items():
            if previous is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = previous


if __name__ == "__main__":
    unittest.main()
