import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.config import default_model_routes, default_skill_registry, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest, ModelInvocationResult, ModelToolCall
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class IntelligentGatewayGovernanceResponseTest(unittest.TestCase):
    """智能网关统一治理摘要响应测试。

    这些用例验证 API 层能把模型路由、工具预算、workspace 和记忆检索汇总到一个稳定字段中。
    它不重新测试每个治理组件的内部算法，而是保护“前端/Java gateway 可以用一个字段读到治理总览”
    这个产品契约。
    """

    def test_plan_response_contains_unified_gateway_summary(self) -> None:
        """默认计划响应应包含智能网关统一治理摘要。"""

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析数据源结构",
                variables={"datasourceId": "ds-001", "sessionId": "session-a"},
            ),
            self._orchestrator(),
        )

        governance = response["intelligentGatewayGovernance"]

        self.assertTrue(governance["available"])
        self.assertTrue(governance["modelGateway"]["available"])
        self.assertTrue(governance["skillAdmission"]["allowed"])
        self.assertGreaterEqual(governance["skillAdmission"]["selectedSkillCount"], 1)
        self.assertEqual("SESSION_SKILL_VISIBILITY_SNAPSHOT", governance["skillVisibility"]["snapshotType"])
        self.assertEqual("agent-plan-skill-admission", governance["skillVisibility"]["snapshotSource"])
        self.assertGreaterEqual(governance["skillVisibility"]["visibleSkillCount"], 1)
        self.assertEqual(0, governance["skillVisibility"]["hiddenSkillCount"])
        self.assertEqual("missing", governance["skillVisibility"]["visibilityFilters"]["permissionFactSource"])
        self.assertIn("visibleSkills", governance["skillVisibility"])
        self.assertTrue(governance["toolBudget"]["allowed"])
        self.assertEqual("tenant:tenant-a:project:project-a", governance["workspace"]["workspaceKey"])
        self.assertEqual("memory:tenant:tenant-a:project:project-a", governance["workspace"]["memoryNamespace"])
        self.assertGreaterEqual(governance["memory"]["retrievalTargetCount"], 1)
        execution_closure = governance["executionClosure"]
        self.assertTrue(execution_closure["available"])
        self.assertEqual("ready_for_control_plane_ingestion", execution_closure["closurePhase"])
        self.assertEqual("pre_execution_only", execution_closure["closedLoopLevel"])
        self.assertTrue(execution_closure["controlPlaneHandoffAvailable"])
        self.assertEqual(1, execution_closure["outboxPreflightCandidateCount"])
        self.assertIn("JAVA_AGENT_PLAN_INGESTION", execution_closure["missingRuntimeEvidence"])
        self.assertIn("GRAPH_ID_OR_CONTRACT_ID_REQUIRED", execution_closure["handoffMissingEvidenceCodes"])
        self.assertIn("PAYLOAD_REFERENCE_REQUIRED", execution_closure["handoffMissingEvidenceCodes"])
        self.assertNotIn("requestBodyTemplate", execution_closure)
        self.assertNotIn("templateSummaries", execution_closure)
        self.assertNotIn("ds-001", str(execution_closure))
        self.assertIn("模型路由", governance["displaySummary"])
        self.assertTrue(any("Java 控制面" in action for action in governance["recommendedActions"]))

    def test_skill_visibility_snapshot_is_recorded_as_runtime_event(self) -> None:
        """会话级 Skill 可见性快照应进入 runtime event 与 HTTP envelope。

        这个测试保护的是产品级可回放能力：如果快照只存在于 HTTP 响应顶层，用户刷新、WebSocket
        断线重连或 Java 控制面补索引时就无法还原“当时模型到底看见了哪些 Skill”。因此事件必须和
        本轮计划的其他 runtime events 一起发布，并保持低敏字段裁剪。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析数据源结构",
                variables={"datasourceId": "ds-001", "sessionId": "session-skill-visibility"},
            ),
            self._orchestrator(),
        )

        skill_visibility = response["intelligentGatewayGovernance"]["skillVisibility"]
        visibility_events = tuple(
            event
            for event in response["plan"]["runtime_events"]
            if event["event_type"] == AgentRuntimeEventType.SKILL_VISIBILITY_SNAPSHOT_RECORDED
        )

        self.assertEqual(1, len(visibility_events))
        event = visibility_events[0]
        attributes = event["attributes"]
        self.assertEqual(event, response["eventEnvelope"]["events"][-2])
        self.assertEqual(len(response["plan"]["runtime_events"]) - 1, event["sequence"])
        self.assertEqual("session-skill-visibility", event["session_id"])
        self.assertEqual("SESSION_SKILL_VISIBILITY_SNAPSHOT", attributes["snapshotType"])
        self.assertEqual(skill_visibility["visibleSkillCount"], attributes["visibleSkillCount"])
        self.assertEqual(skill_visibility["hiddenSkillCount"], attributes["hiddenSkillCount"])
        self.assertEqual(skill_visibility["visibleRiskLevelCounts"], attributes["visibleRiskLevelCounts"])
        self.assertEqual(
            tuple(item["skillCode"] for item in skill_visibility["visibleSkills"]),
            attributes["visibleSkillCodes"],
        )
        self.assertIn("datasource.profiling", attributes["visibleSkillCodes"])
        self.assertEqual(0, attributes["visibleSkillCodesTruncatedCount"])
        self.assertEqual("missing", attributes["permissionFactSource"])
        self.assertEqual(0, attributes["grantedPermissionCount"])
        self.assertNotIn("grantedPermissions", str(attributes))
        self.assertNotIn("requiredPermissions", str(attributes))
        self.assertNotIn("请分析数据源结构", str(attributes))

    def test_skill_visibility_snapshot_binds_skill_manifest_fingerprint(self) -> None:
        """会话级 Skill 可见性快照应绑定当前 Skill Manifest 指纹。

        这个测试保护的是商业化 Agent host 很关键的一条证据链：当某个租户反馈“某个 Skill 为什么
        本轮可见/不可见”时，控制面不能只回答权限和角色，还要能回答当时 Python Runtime 看到的是哪一版
        Java agent-runtime 发布目录。否则后续灰度、缓存、回放和 Marketplace 统计都会缺少版本维度。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析数据源结构",
                variables={"datasourceId": "ds-001", "sessionId": "session-manifest"},
            ),
            self._orchestrator(),
            skill_publication_diagnostics_service=FakeSkillPublicationDiagnosticsService(),
        )

        governance = response["intelligentGatewayGovernance"]
        skill_manifest = governance["skillManifest"]
        skill_visibility = governance["skillVisibility"]
        visibility_event = response["eventEnvelope"]["events"][-2]
        attributes = visibility_event["attributes"]

        self.assertEqual("BOUND_REMOTE_MANIFEST", skill_manifest["bindingStatus"])
        self.assertEqual("skill-manifest-fp-20260604", skill_manifest["manifestFingerprint"])
        self.assertEqual("skill-manifest-fp-20260604", skill_visibility["manifestBinding"]["manifestFingerprint"])
        self.assertEqual("BOUND_REMOTE_MANIFEST", skill_visibility["visibilityFilters"]["manifestBindingStatus"])
        self.assertEqual("REMOTE_READY", attributes["manifestStatus"])
        self.assertEqual("java-agent-runtime", attributes["manifestSource"])
        self.assertEqual("skill-manifest-fp-20260604", attributes["manifestFingerprint"])
        self.assertEqual("agent-skill-publication-manifest.v1", attributes["manifestSchemaVersion"])
        self.assertEqual(6, attributes["manifestSkillCount"])
        self.assertEqual(5, attributes["manifestReadySkillCount"])
        self.assertEqual(1, attributes["manifestNonReadySkillCount"])
        self.assertFalse(attributes["manifestFallback"])

    def test_skill_admission_rejection_is_exposed_in_unified_gateway_summary(self) -> None:
        """Skill 准入拒绝应进入智能网关摘要，便于前端治理卡片解释原因。"""

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="analyst-a",
                objective="请为客户主数据生成质量规则",
                variables={
                    "datasourceId": "ds-001",
                    "grantedPermissions": ("datasource:metadata:read",),
                    "actorRole": "PROJECT_OWNER",
                },
            ),
            self._orchestrator(),
        )

        governance = response["intelligentGatewayGovernance"]
        skill_admission = governance["skillAdmission"]

        self.assertFalse(governance["available"])
        self.assertFalse(skill_admission["allowed"])
        self.assertGreaterEqual(skill_admission["selectedSkillCount"], 1)
        self.assertEqual(1, skill_admission["rejectedSkillCount"])
        self.assertEqual("quality.rule.design", skill_admission["rejectedSkills"][0]["skillCode"])
        self.assertEqual("DENIED_MISSING_PERMISSION", skill_admission["rejectedSkills"][0]["admissionStatus"])
        skill_visibility = governance["skillVisibility"]
        self.assertFalse(skill_visibility["available"])
        self.assertEqual(1, skill_visibility["hiddenSkillCount"])
        self.assertEqual("quality.rule.design", skill_visibility["hiddenSkills"][0]["skillCode"])
        self.assertEqual(1, skill_visibility["hiddenAdmissionStatusCounts"]["DENIED_MISSING_PERMISSION"])
        self.assertEqual("legacy-request-variables", skill_visibility["visibilityFilters"]["permissionFactSource"])
        self.assertTrue(skill_visibility["visibilityFilters"]["legacyRequestVariablesDetected"])
        self.assertTrue(any("旧式请求变量" in action for action in skill_visibility["recommendedActions"]))
        self.assertTrue(any("被拒绝 Skill" in action for action in governance["recommendedActions"]))
        self.assertTrue(any("trustedControlPlane.skillAdmission" in action for action in governance["recommendedActions"]))

    def test_tool_budget_blocking_is_exposed_in_unified_gateway_summary(self) -> None:
        """工具预算阻断应在统一治理摘要中直接可见。"""

        provider = ToolCallingProvider(
            tuple(
                ModelToolCall(
                    call_id=f"call-metadata-{index}",
                    name="datasource_metadata_read",
                    arguments=f'{{"datasourceId":"ds-{index}"}}',
                )
                for index in range(4)
            )
        )
        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请连续读取多个数据源元数据",
                variables={"datasourceId": "ds-rule", "streamModelIntent": False},
            ),
            self._orchestrator(provider=provider),
        )

        tool_budget = response["intelligentGatewayGovernance"]["toolBudget"]

        self.assertFalse(response["intelligentGatewayGovernance"]["available"])
        self.assertFalse(response["intelligentGatewayGovernance"]["skillVisibility"]["available"])
        self.assertFalse(response["intelligentGatewayGovernance"]["skillVisibility"]["visibilityFilters"]["toolBudgetAllowed"])
        self.assertTrue(tool_budget["guarded"])
        self.assertEqual(4, tool_budget["proposedCount"])
        self.assertEqual(3, tool_budget["acceptedCountAfterGuard"])
        self.assertIn("MODEL_TOOL_CALL_BUDGET_AUTO_EXECUTABLE_COUNT_EXCEEDED", tool_budget["budgetIssueCodes"])
        self.assertTrue(
            any("缩小本轮模型工具调用批次" in action for action in response["intelligentGatewayGovernance"]["recommendedActions"])
        )

    def test_request_budget_policy_can_relax_tool_budget_in_main_flow(self) -> None:
        """请求级工具预算策略应能影响主编排链路。"""

        response = build_plan_response(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请连续读取多个数据源元数据",
                variables={
                    "datasourceId": "ds-rule",
                    "streamModelIntent": False,
                    "toolCallBudget": {"maxAutoExecutableToolCalls": 4},
                },
            ),
            self._orchestrator(provider=self._four_metadata_tool_call_provider()),
        )

        tool_budget = response["intelligentGatewayGovernance"]["toolBudget"]

        self.assertTrue(response["intelligentGatewayGovernance"]["available"])
        self.assertFalse(tool_budget["guarded"])
        self.assertEqual((), tool_budget["budgetIssueCodes"])

    @staticmethod
    def _orchestrator(provider: object | None = None) -> AgentOrchestrator:
        """构造测试用编排器。"""

        return AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

    @staticmethod
    def _four_metadata_tool_call_provider() -> "ToolCallingProvider":
        """构造一次返回 4 个元数据读取 tool_calls 的 Provider。"""

        return ToolCallingProvider(
            tuple(
                ModelToolCall(
                    call_id=f"call-metadata-{index}",
                    name="datasource_metadata_read",
                    arguments=f'{{"datasourceId":"ds-{index}"}}',
                )
                for index in range(4)
            )
        )


class ToolCallingProvider:
    """测试用模型 Provider，模拟模型一次返回多个 tool_calls。"""

    def __init__(self, tool_calls: tuple[ModelToolCall, ...]) -> None:
        self._tool_calls = tool_calls

    def invoke(self, request):
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content="tool calling",
            tool_calls=self._tool_calls,
        )


class FakeSkillPublicationDiagnosticsService:
    """测试用 Skill Manifest 诊断服务。

    真实服务会从 Java agent-runtime 拉取发布目录并缓存诊断快照；测试中只需要一个稳定对象，
    用来证明计划响应组装层会读取 `diagnostics()`，并把低敏指纹事实一路传入智能网关响应和 runtime event。
    """

    def diagnostics(self) -> dict[str, object]:
        return {
            "status": "REMOTE_READY",
            "source": "java-agent-runtime",
            "fallback": False,
            "remoteManifestAvailable": True,
            "schemaVersion": "agent-skill-publication-manifest.v1",
            "manifestType": "AGENT_SKILL_PUBLICATION_MANIFEST",
            "publicationMode": "READY_ONLY",
            "manifestFingerprint": "skill-manifest-fp-20260604",
            "generatedAt": "2026-06-04T11:30:00Z",
            "lastRefreshAt": "2026-06-04T11:31:00Z",
            "manifestSkillCount": 6,
            "readySkillCount": 5,
            "nonReadySkillCount": 1,
            "publicationStateCounts": {"READY": 5, "DRAFT": 1},
            "riskLevelCounts": {"LOW": 3, "MEDIUM": 2, "HIGH": 1},
        }


if __name__ == "__main__":
    unittest.main()
