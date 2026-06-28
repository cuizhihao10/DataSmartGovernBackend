import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator, build_plan_response
from datasmart_ai_runtime.api.agent.capabilities import register_agent_capability_routes
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_capability import (
    AgentCapabilityStatus,
    default_agent_capability_matrix_service,
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


class AgentCapabilityMatrixTest(unittest.TestCase):
    """Agent 能力完备度矩阵测试。

    这组测试保护的是项目最后收敛阶段的能力地图，而不是某个具体工具实现：
    - tools/skills/memory/query engine/context/permission/sub-agent/sessions/command/hook/tech stack/LLM
      这些能力域必须同时进入产品视野；
    - preview、控制面合同和计划中能力不能被误标成已经完成；
    - `/agent/plans` 只能返回压缩低敏摘要，详细矩阵走诊断接口。
    """

    def test_matrix_covers_complete_agent_capability_domains(self) -> None:
        """默认矩阵应覆盖完整 Agent Host 的一级能力域和关键子能力。"""

        diagnostics = default_agent_capability_matrix_service().diagnostics()
        domains = {domain["domainId"]: domain for domain in diagnostics["domains"]}

        self.assertEqual("datasmart.agent-capability-matrix.v1", diagnostics["schemaVersion"])
        self.assertEqual("AGENT_CAPABILITY_COMPLETENESS_MATRIX", diagnostics["diagnosticType"])
        self.assertIn("tools", domains)
        self.assertIn("skills", domains)
        self.assertIn("memory", domains)
        self.assertIn("query-engine", domains)
        self.assertIn("context", domains)
        self.assertIn("permission", domains)
        self.assertIn("sub-agent", domains)
        self.assertIn("sessions", domains)
        self.assertIn("command", domains)
        self.assertIn("hook", domains)
        self.assertIn("tech-stack", domains)
        self.assertIn("llm", domains)
        self.assertEqual(12, diagnostics["domainCount"])
        self.assertGreaterEqual(diagnostics["subCapabilityCount"], 35)
        self.assertIn("tools", diagnostics["p0GapDomains"])
        self.assertIn("command", diagnostics["p0GapDomains"])
        self.assertIn("COMPLETE_CORE_AGENT_CAPABILITIES_THEN_CLOSE_PROJECT_LOOP", diagnostics["convergenceMode"])

        tool_capabilities = {
            item["capabilityId"]
            for item in domains["tools"]["subCapabilities"]
        }
        memory_capabilities = {
            item["capabilityId"]
            for item in domains["memory"]["subCapabilities"]
        }
        permission_capabilities = {
            item["capabilityId"]
            for item in domains["permission"]["subCapabilities"]
        }

        self.assertIn("tool.file-read-write", tool_capabilities)
        self.assertIn("tool.exec-run-program", tool_capabilities)
        self.assertIn("tool.web-search", tool_capabilities)
        self.assertIn("tool.quality-remediation-task-draft", tool_capabilities)
        self.assertIn("memory.sqlite-fts", memory_capabilities)
        self.assertIn("memory.m-create-retrieve", memory_capabilities)
        self.assertIn("permission.dangerous-path-safe-cmd", permission_capabilities)
        self.assertIn(AgentCapabilityStatus.CONTROL_PLANE_READY.value, diagnostics["statusCounts"])
        self.assertIn(AgentCapabilityStatus.PLANNED.value, diagnostics["statusCounts"])

    def test_quality_remediation_tool_reflects_partial_real_closure(self) -> None:
        """质量治理工具状态应跟随最新提交闭环更新，避免继续按旧 dry-run 口径推进。"""

        diagnostics = default_agent_capability_matrix_service().diagnostics()
        tools = next(domain for domain in diagnostics["domains"] if domain["domainId"] == "tools")
        quality_tool = next(
            item for item in tools["subCapabilities"]
            if item["capabilityId"] == "tool.quality-remediation-task-draft"
        )
        serialized = str(quality_tool)

        self.assertEqual(AgentCapabilityStatus.PARTIAL_CLOSED_LOOP.value, quality_tool["status"])
        self.assertIn("Host submit", quality_tool["currentEvidence"])
        self.assertIn("提交事实", quality_tool["currentEvidence"])
        self.assertIn("UNKNOWN 人工恢复", quality_tool["currentEvidence"])
        self.assertIn("统一 ToolPlan", quality_tool["nextAction"])
        self.assertNotIn("尚未由 Java agent-runtime 将该 ToolPlan 写入真实", serialized)

    def test_matrix_is_low_sensitive_capability_metadata(self) -> None:
        """能力矩阵只能返回低敏能力元数据，不能变成运行时内容导出。"""

        diagnostics = default_agent_capability_matrix_service().diagnostics()
        serialized = str(diagnostics).lower()

        self.assertEqual("LOW_SENSITIVE_CAPABILITY_METADATA_ONLY", diagnostics["payloadPolicy"])
        self.assertNotIn("api_key", serialized)
        self.assertNotIn("secret", serialized)
        self.assertNotIn("select * from", serialized)
        self.assertNotIn("raw prompt", serialized)
        self.assertNotIn("sample data", serialized)
        self.assertNotIn("internal-model-gateway", serialized)

    def test_routes_register_direct_and_gateway_paths(self) -> None:
        """Agent 能力诊断应同时支持 Python Runtime 直连和统一网关路径。"""

        app = FakeApp()
        service = default_agent_capability_matrix_service()

        register_agent_capability_routes(app, capability_matrix_service=service)

        self.assertIn("/agent/capabilities/diagnostics", app.get_routes)
        self.assertIn("/api/agent/capabilities/diagnostics", app.get_routes)
        direct_response = app.get_routes["/agent/capabilities/diagnostics"]()
        gateway_response = app.get_routes["/api/agent/capabilities/diagnostics"]()
        self.assertEqual(direct_response, gateway_response)
        self.assertEqual("AGENT_CAPABILITY_COMPLETENESS_MATRIX", direct_response["diagnosticType"])

    def test_plan_response_contains_compressed_capability_closure(self) -> None:
        """`/agent/plans` 应返回压缩能力闭环摘要，而不是完整能力矩阵。"""

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析这个数据源并给出治理建议",
                variables={"datasourceId": "ds-capability-sensitive"},
            ),
            build_default_orchestrator(),
        )
        closure = response["agentCapabilityClosure"]
        domains = {domain["domainId"]: domain for domain in closure["domains"]}

        self.assertEqual("AGENT_CAPABILITY_CLOSURE", closure["snapshotType"])
        self.assertEqual("LOW_SENSITIVE_CAPABILITY_METADATA_ONLY", closure["payloadPolicy"])
        self.assertEqual(12, closure["domainCount"])
        self.assertIn("tools", domains)
        self.assertIn("llm", domains)
        self.assertIn("command", closure["p0GapDomains"])
        self.assertGreaterEqual(len(closure["nextClosureActions"]), 1)
        self.assertIn("topClosureGaps", domains["tools"])
        self.assertNotIn("subCapabilities", domains["tools"])
        self.assertNotIn("ds-capability-sensitive", str(closure))


if __name__ == "__main__":
    unittest.main()
