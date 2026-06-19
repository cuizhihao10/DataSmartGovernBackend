import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.platform import register_platform_convergence_routes
from datasmart_ai_runtime.services.platform_convergence import (
    ConvergencePhase,
    default_platform_convergence_diagnostics_service,
)


class FakeApp:
    """极简 FastAPI 替身，用来验证路由注册而不启动真实 HTTP 服务。"""

    def __init__(self) -> None:
        self.get_routes: dict[str, object] = {}

    def get(self, path: str):
        """模拟 FastAPI 的 `@app.get(...)` 装饰器。"""

        def decorator(func):
            self.get_routes[path] = func
            return func

        return decorator


class PlatformConvergenceDiagnosticsTest(unittest.TestCase):
    """平台收敛诊断测试。

    这组测试保护的是“项目开始收口”的工程契约，而不是某个单点业务规则：
    - 诊断必须覆盖 Java 微服务、智能网关、Agent、模型、部署和观测等大模块；
    - 数据同步、任务管理、权限中心这些商业闭环核心不能被模型/Agent 局部工作遮蔽；
    - 响应只能暴露低敏规划信息，不能变成运行时数据导出。
    """

    def test_diagnostics_covers_core_platform_domains(self) -> None:
        """默认诊断应覆盖收敛阶段必须关注的关键产品域。"""

        service = default_platform_convergence_diagnostics_service()
        diagnostics = service.diagnostics()
        domains = {domain["domainId"]: domain for domain in diagnostics["domains"]}

        self.assertEqual("datasmart.platform-convergence-diagnostics.v1", diagnostics["schemaVersion"])
        self.assertIn("gateway-intelligent-access", domains)
        self.assertIn("permission-admin", domains)
        self.assertIn("task-management", domains)
        self.assertIn("datasource-management-data-sync", domains)
        self.assertIn("data-quality", domains)
        self.assertIn("agent-runtime", domains)
        self.assertIn("python-ai-runtime", domains)
        self.assertIn("model-gateway", domains)
        self.assertIn("observability", domains)
        self.assertIn("deployment-middleware", domains)
        self.assertEqual(len(domains), diagnostics["domainCount"])
        self.assertIn("datasource-management-data-sync", diagnostics["p0FocusDomains"])

    def test_data_sync_and_task_are_explicit_convergence_gaps(self) -> None:
        """收敛诊断必须提醒我们补业务闭环，而不是继续只扩展 AI preview。"""

        diagnostics = default_platform_convergence_diagnostics_service().diagnostics()
        domains = {domain["domainId"]: domain for domain in diagnostics["domains"]}
        data_sync = domains["datasource-management-data-sync"]
        task = domains["task-management"]
        agent_runtime = domains["agent-runtime"]

        self.assertEqual("P0", data_sync["priority"])
        self.assertEqual(ConvergencePhase.FOUNDATION_READY.value, data_sync["currentPhase"])
        self.assertTrue(any("checkpoint" in item or "重试" in item for item in data_sync["openGaps"]))
        self.assertTrue(any("task-management" in item for item in data_sync["dependsOn"]))
        self.assertTrue(any("data-sync" in item or "数据同步" in item for item in task["nextActions"]))
        self.assertTrue(any("真实" in item or "outbox" in item for item in agent_runtime["openGaps"]))
        self.assertIn("不再无限扩展单个 preview", diagnostics["convergenceBoundary"])

    def test_diagnostics_is_low_sensitive_and_not_runtime_export(self) -> None:
        """诊断响应只能返回规划与缺口，不能泄露运行时敏感字段。"""

        diagnostics = default_platform_convergence_diagnostics_service().diagnostics()
        serialized = str(diagnostics).lower()

        self.assertNotIn("internal-model-gateway", serialized)
        self.assertNotIn("api_key", serialized)
        self.assertNotIn("secret", serialized)
        self.assertNotIn("token", serialized)
        self.assertNotIn("select * from", serialized)
        self.assertNotIn("raw prompt", serialized)
        self.assertNotIn("sample data", serialized)

    def test_routes_register_direct_and_gateway_paths(self) -> None:
        """平台收敛诊断应同时支持直连路径和未来网关代理路径。"""

        app = FakeApp()
        service = default_platform_convergence_diagnostics_service()

        register_platform_convergence_routes(app, diagnostics_service=service)

        self.assertIn("/agent/platform/convergence/diagnostics", app.get_routes)
        self.assertIn("/api/agent/platform/convergence/diagnostics", app.get_routes)
        direct_response = app.get_routes["/agent/platform/convergence/diagnostics"]()
        gateway_response = app.get_routes["/api/agent/platform/convergence/diagnostics"]()
        self.assertEqual(direct_response, gateway_response)
        self.assertEqual("PLATFORM_CONVERGENCE_CONTROL_PLANE", direct_response["diagnosticType"])


if __name__ == "__main__":
    unittest.main()
