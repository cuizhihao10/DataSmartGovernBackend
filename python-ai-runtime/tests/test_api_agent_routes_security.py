import os
import sys
import unittest
from dataclasses import dataclass
from types import SimpleNamespace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.routes import register_agent_runtime_routes
from datasmart_ai_runtime.api.gateway.security import GatewaySignatureSecurityStats


class FakeHttpException(Exception):
    """测试用 HTTP 异常，模拟 FastAPI HTTPException 的关键字段。"""

    def __init__(self, status_code: int, detail: dict[str, object]) -> None:
        self.status_code = status_code
        self.detail = detail
        super().__init__(detail.get("message", "HTTP error"))


class FakeApp:
    """极简路由注册器，用于在不安装 FastAPI 的情况下测试 handler 行为。"""

    def __init__(self) -> None:
        self.get_routes: dict[str, object] = {}
        self.post_routes: dict[str, object] = {}
        self.websocket_routes: dict[str, object] = {}

    def get(self, path: str):
        """记录 GET handler。"""

        def decorator(func):
            self.get_routes[path] = func
            return func

        return decorator

    def post(self, path: str):
        """记录 POST handler。

        真实 FastAPI 的 `@app.post(...)` 会返回装饰器；这里复刻最小行为，让测试可以拿到注册后的函数。
        """

        def decorator(func):
            self.post_routes[path] = func
            return func

        return decorator

    def websocket(self, path: str):
        """记录 WebSocket handler，避免注册函数因 fake app 缺少 websocket 装饰器而失败。"""

        def decorator(func):
            self.websocket_routes[path] = func
            return func

        return decorator


@dataclass(frozen=True)
class FakeRequest:
    """测试用 Request，只提供路由代码需要的 headers 和 url.path。"""

    headers: dict[str, str]
    path: str = "/agent/plans"

    @property
    def url(self) -> SimpleNamespace:
        """模拟 Starlette Request.url.path。"""

        return SimpleNamespace(path=self.path)


class ApiAgentRoutesSecurityTest(unittest.TestCase):
    """Agent API 安全错误映射测试。"""

    def test_gateway_signature_failure_is_mapped_to_http_error_detail(self) -> None:
        """签名失败应返回稳定错误码和排障字段，而不是冒泡成 500。"""

        app = FakeApp()
        stats = GatewaySignatureSecurityStats()
        register_agent_runtime_routes(
            app,
            request_type=FakeRequest,
            orchestrator=object(),
            event_store=None,
            session_manager=None,
            live_push_hub=None,
            event_publisher=None,
            runtime_event_replay_sources=(),
            plan_ingestion_client=None,
            control_plane_feedback_collector=None,
            runtime_event_feedback_bridge=None,
            loop_control_evaluator=None,
            second_turn_orchestrator=None,
            memory_write_governance=None,
            gateway_signature_error_factory=lambda detail: FakeHttpException(status_code=401, detail=detail),
            gateway_signature_security_stats=stats,
        )

        with _patched_env(
            DATASMART_GATEWAY_SIGNATURE_REQUIRED="true",
            DATASMART_GATEWAY_SIGNATURE_SECRET="secret-for-test",
        ):
            with self.assertLogs("datasmart_ai_runtime.api.agent.routes", level="WARNING") as logs:
                with self.assertRaises(FakeHttpException) as raised:
                    app.post_routes["/agent/plans"](
                        {"variables": {"trustedControlPlane": {"toolBudget": {"actorRole": "PLATFORM_ADMIN"}}}},
                        FakeRequest(
                            headers={
                                "X-DataSmart-Source-Service": "datasmart-govern-gateway",
                                "X-DataSmart-Trace-Id": "trace-security-001",
                                "X-Gateway-Original-Path": "/api/agent/plans",
                            }
                        ),
                    )

        self.assertEqual(401, raised.exception.status_code)
        detail = raised.exception.detail
        self.assertEqual("GATEWAY_SIGNATURE_INVALID", detail["code"])
        self.assertEqual("missing-signature-headers", detail["reason"])
        self.assertEqual("trace-security-001", detail["traceId"])
        self.assertEqual("datasmart-govern-gateway", detail["sourceService"])
        self.assertEqual("/agent/plans", detail["path"])
        self.assertIn("Gateway 内部签名校验失败", logs.output[0])
        self.assertNotIn("secret-for-test", logs.output[0])
        self.assertEqual({"missing-signature-headers": 1}, stats.snapshot()["failureCountByReason"])


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
