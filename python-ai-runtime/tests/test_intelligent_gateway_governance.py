import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api_plan_response import build_plan_response
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
        self.assertIn("模型路由", governance["displaySummary"])

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
        self.assertEqual(event, response["eventEnvelope"]["events"][-1])
        self.assertEqual(len(response["plan"]["runtime_events"]), event["sequence"])
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


if __name__ == "__main__":
    unittest.main()
