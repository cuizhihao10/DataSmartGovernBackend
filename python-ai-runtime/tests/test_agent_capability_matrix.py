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
    AgentClosureGateDecision,
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
        closure_readiness = diagnostics["closureReadiness"]

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
        self.assertEqual("AGENT_CAPABILITY_CLOSURE_READINESS", closure_readiness["snapshotType"])
        self.assertEqual(
            AgentClosureGateDecision.CLOSE_CONTROL_PLANE_GAPS_FIRST.value,
            closure_readiness["gateDecision"],
        )
        self.assertFalse(closure_readiness["canStartFinalProjectClosure"])

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

    def test_context_micro_compact_reflects_partial_closed_loop(self) -> None:
        """上下文微压缩已接入主链，但不能误标为完全完成。

        这里保护项目闭口节奏：实现了确定性 micro-compact 和低敏事件后，应从 planned 移出；但长会话
        历史摘要、工具结果压缩和真实 tokenizer 仍是缺口，所以只能是 partial closed-loop。
        """

        diagnostics = default_agent_capability_matrix_service().diagnostics()
        context_domain = next(domain for domain in diagnostics["domains"] if domain["domainId"] == "context")
        micro_compact = next(
            item
            for item in context_domain["subCapabilities"]
            if item["capabilityId"] == "context.micro-compact"
        )
        serialized = str(micro_compact)

        self.assertEqual(AgentCapabilityStatus.PARTIAL_CLOSED_LOOP.value, micro_compact["status"])
        self.assertIn("ContextMicroCompactor", micro_compact["currentEvidence"])
        self.assertIn("CONTEXT_MICRO_COMPACTED", micro_compact["currentEvidence"])
        self.assertIn("长会话历史摘要", micro_compact["closureGap"])
        self.assertNotIn("prompt", serialized.lower())

    def test_web_search_tool_reflects_controlled_contract_closure(self) -> None:
        """网页搜索工具已具备受控契约，但还不是生产执行完成态。"""

        diagnostics = default_agent_capability_matrix_service().diagnostics()
        tools = next(domain for domain in diagnostics["domains"] if domain["domainId"] == "tools")
        web_search = next(
            item
            for item in tools["subCapabilities"]
            if item["capabilityId"] == "tool.web-search"
        )
        serialized = str(web_search)

        self.assertEqual(AgentCapabilityStatus.PARTIAL_CLOSED_LOOP.value, web_search["status"])
        self.assertIn("WebSearchGovernanceService", web_search["currentEvidence"])
        self.assertIn("searchQueryRef", web_search["currentEvidence"])
        self.assertIn("真实搜索 Provider", web_search["closureGap"])
        self.assertNotIn("api_key", serialized.lower())
        self.assertNotIn("endpoint", web_search["currentEvidence"].lower())

    def test_llm_inference_optimization_reflects_control_plane_diagnostics(self) -> None:
        """成熟推理优化已具备诊断控制面，但仍不能被误标为完全生产闭环。"""

        diagnostics = default_agent_capability_matrix_service().diagnostics()
        llm_domain = next(domain for domain in diagnostics["domains"] if domain["domainId"] == "llm")
        inference = next(
            item
            for item in llm_domain["subCapabilities"]
            if item["capabilityId"] == "llm.inference-optimization"
        )
        serialized = str(inference).lower()

        self.assertEqual(AgentCapabilityStatus.CONTROL_PLANE_READY.value, inference["status"])
        self.assertIn("ModelInferenceOptimizationDiagnosticsService", inference["currentEvidence"])
        self.assertIn("TTFT", inference["currentEvidence"])
        self.assertIn("vLLM/SGLang/LiteLLM", inference["currentEvidence"])
        self.assertIn("Prometheus", inference["closureGap"])
        self.assertNotIn("api_key", serialized)
        self.assertNotIn("endpoint", inference["currentEvidence"].lower())

    def test_sqlite_fts_memory_reflects_partial_closed_loop(self) -> None:
        """SQLite FTS 已形成本地全文索引闭环，但仍需要生产化同步和运维补强。"""

        diagnostics = default_agent_capability_matrix_service().diagnostics()
        memory_domain = next(domain for domain in diagnostics["domains"] if domain["domainId"] == "memory")
        sqlite_fts = next(
            item
            for item in memory_domain["subCapabilities"]
            if item["capabilityId"] == "memory.sqlite-fts"
        )
        serialized = str(sqlite_fts).lower()

        self.assertEqual(AgentCapabilityStatus.PARTIAL_CLOSED_LOOP.value, sqlite_fts["status"])
        self.assertIn("SQLiteFtsAgentMemorySecondaryIndex", sqlite_fts["currentEvidence"])
        self.assertIn("StoreBackedAgentMemoryRetriever", sqlite_fts["currentEvidence"])
        self.assertIn("materialization receipt", sqlite_fts["nextAction"])
        self.assertNotIn("raw prompt", serialized)
        self.assertNotIn("sample data", serialized)

    def test_skill_create_publish_reflects_lifecycle_state_machine_closure(self) -> None:
        """Skill 创建发布已具备状态机、Manifest 桥接和 Python 刷新控制，但仍需持久化/E2E。"""

        diagnostics = default_agent_capability_matrix_service().diagnostics()
        skills_domain = next(domain for domain in diagnostics["domains"] if domain["domainId"] == "skills")
        create_publish = next(
            item
            for item in skills_domain["subCapabilities"]
            if item["capabilityId"] == "skill.create-publish"
        )
        serialized = str(create_publish).lower()

        self.assertEqual(AgentCapabilityStatus.PARTIAL_CLOSED_LOOP.value, create_publish["status"])
        self.assertIn("状态机", create_publish["currentEvidence"])
        self.assertIn("Manifest", create_publish["currentEvidence"])
        self.assertIn("Python Runtime", create_publish["currentEvidence"])
        self.assertIn("refreshControl", create_publish["currentEvidence"])
        self.assertIn("Manifest", create_publish["closureGap"])
        self.assertIn("durable store", create_publish["closureGap"])
        self.assertNotIn("raw prompt", serialized)
        self.assertNotIn("select * from", serialized)

    def test_closure_readiness_groups_p0_gaps_for_final_project_convergence(self) -> None:
        """闭口门禁应把 P0 缺口分层，帮助项目停止发散并优先关闭硬阻塞。"""

        readiness = default_agent_capability_matrix_service().closure_readiness()
        hard_blocker_ids = {item["capabilityId"] for item in readiness["hardBlockers"]}
        control_plane_gap_ids = {item["capabilityId"] for item in readiness["controlPlaneGaps"]}
        operationalization_gap_ids = {item["capabilityId"] for item in readiness["operationalizationGaps"]}
        serialized = str(readiness).lower()

        self.assertEqual("datasmart.agent-capability-closure-readiness.v1", readiness["schemaVersion"])
        self.assertEqual("LOW_SENSITIVE_CAPABILITY_METADATA_ONLY", readiness["payloadPolicy"])
        self.assertGreater(readiness["readinessScore"], 0)
        self.assertLess(readiness["readinessScore"], 100)
        self.assertEqual(0, readiness["p0HardBlockerCount"])
        self.assertGreater(readiness["p0ControlPlaneGapCount"], 0)
        self.assertGreater(readiness["p0OperationalizationGapCount"], 0)
        self.assertNotIn("tool.file-read-write", hard_blocker_ids)
        self.assertNotIn("llm.inference-optimization", hard_blocker_ids)
        self.assertNotIn("memory.sqlite-fts", hard_blocker_ids)
        self.assertNotIn("skill.create-publish", hard_blocker_ids)
        self.assertIn("llm.inference-optimization", control_plane_gap_ids)
        self.assertIn("tool.exec-run-program", control_plane_gap_ids)
        self.assertIn("tool.file-read-write", operationalization_gap_ids)
        self.assertIn("P0 能力域不允许继续存在 planned 或 blocked 子能力。", readiness["stageExitCriteria"])
        self.assertNotIn("api_key", serialized)
        self.assertNotIn("select * from", serialized)
        self.assertNotIn("raw prompt", serialized)

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
        self.assertIn("/agent/capabilities/closure-readiness", app.get_routes)
        self.assertIn("/api/agent/capabilities/closure-readiness", app.get_routes)
        direct_response = app.get_routes["/agent/capabilities/diagnostics"]()
        gateway_response = app.get_routes["/api/agent/capabilities/diagnostics"]()
        closure_response = app.get_routes["/api/agent/capabilities/closure-readiness"]()
        self.assertEqual(direct_response, gateway_response)
        self.assertEqual("AGENT_CAPABILITY_COMPLETENESS_MATRIX", direct_response["diagnosticType"])
        self.assertEqual("AGENT_CAPABILITY_CLOSURE_READINESS", closure_response["snapshotType"])

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
        closure_readiness = closure["closureReadiness"]
        domains = {domain["domainId"]: domain for domain in closure["domains"]}

        self.assertEqual("AGENT_CAPABILITY_CLOSURE", closure["snapshotType"])
        self.assertEqual("LOW_SENSITIVE_CAPABILITY_METADATA_ONLY", closure["payloadPolicy"])
        self.assertEqual(12, closure["domainCount"])
        self.assertIn("tools", domains)
        self.assertIn("llm", domains)
        self.assertIn("command", closure["p0GapDomains"])
        self.assertGreaterEqual(len(closure["nextClosureActions"]), 1)
        self.assertIn("topClosureGaps", domains["tools"])
        self.assertEqual("AGENT_CAPABILITY_CLOSURE_READINESS", closure_readiness["snapshotType"])
        self.assertFalse(closure_readiness["canStartFinalProjectClosure"])
        self.assertIn("CONVERT_CONTROL_PLANE", closure_readiness["recommendedDeliveryMode"])
        self.assertNotIn("subCapabilities", domains["tools"])
        self.assertNotIn("ds-capability-sensitive", str(closure))


if __name__ == "__main__":
    unittest.main()
