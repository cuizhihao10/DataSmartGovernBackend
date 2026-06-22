import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator, build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest


class FakePlanIngestionResult:
    """最小 Java plan ingestion 结果替身。

    闭环报告只关心“Java 控制面是否已经接收计划”这个事实，不应该读取 Java 响应正文或工具审计详情。
    因此测试替身只实现 `build_plan_response` 必需的两个方法，用来验证闭环快照能在有控制面引用时推进
    到下一阶段。
    """

    def attach_to_plan(self, plan: AgentPlan) -> AgentPlan:
        """模拟 Java 已接收计划但不改写 ToolPlan。

        真实实现会把 sessionId/runId/auditId 写入 governance hints；本测试不需要这些字段，因为闭环
        快照不能暴露具体审计正文，只需要识别 ingestion 事实已经存在。
        """

        return plan

    def to_summary(self) -> dict[str, object]:
        """返回低敏摘要，模拟控制面已创建引用。"""

        return {
            "ingested": True,
            "sessionCreated": True,
            "runCreated": True,
            "toolAuditCount": 1,
        }


class FakePlanIngestionClient:
    """最小 Java plan ingestion client 替身。"""

    def ingest(self, request: AgentRequest, plan: AgentPlan, trace_id: str | None = None) -> FakePlanIngestionResult:
        """模拟把 Python AgentPlan 提交给 Java 控制面。

        参数中的 request/plan/trace_id 可能包含真实业务上下文，测试替身不读取也不回显这些值，符合低敏
        闭环报告的设计边界。
        """

        return FakePlanIngestionResult()


class AgentExecutionClosureTest(unittest.TestCase):
    """Agent 请求级执行闭环快照测试。

    这组测试保护的不是某个单点字段，而是项目收敛阶段非常关键的产品语义：
    - READY 工具不等于已经执行；
    - Java 控制面接收计划不等于 worker 已经产生副作用；
    - 审批、澄清、预算和反馈都应该成为明确门禁，而不是散落在多个响应字段里让调用方自行猜。
    """

    def test_ready_tool_waits_for_java_control_plane_ingestion(self) -> None:
        """低风险可执行工具应提示进入 Java 控制面，而不是宣称工具已执行。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-sensitive-closure-001"},
        )

        response = build_plan_response(request, build_default_orchestrator())
        closure = response["agentExecutionClosure"]

        self.assertEqual("AGENT_EXECUTION_CLOSURE", closure["snapshotType"])
        self.assertEqual("LOW_SENSITIVE_EXECUTION_METADATA_ONLY", closure["payloadPolicy"])
        self.assertEqual("ready_for_control_plane_ingestion", closure["closurePhase"])
        self.assertEqual("pre_execution_only", closure["closedLoopLevel"])
        self.assertIn("WAITING_JAVA_CONTROL_PLANE_INGESTION", closure["blockingGates"])
        self.assertIn("JAVA_AGENT_PLAN_INGESTION", closure["missingRuntimeEvidence"])
        self.assertIn("SUBMIT_PLAN_TO_JAVA_CONTROL_PLANE", closure["nextActions"])
        handoff = closure["controlPlaneHandoff"]
        self.assertTrue(handoff["available"])
        self.assertTrue(handoff["previewOnly"])
        self.assertFalse(handoff["toolExecutionEnabled"])
        self.assertEqual(1, handoff["totalTemplateCount"])
        self.assertEqual(1, handoff["outboxPreflightCandidateCount"])
        self.assertIn("GRAPH_ID_OR_CONTRACT_ID_REQUIRED", handoff["missingEvidenceCodes"])
        self.assertIn("PAYLOAD_REFERENCE_REQUIRED", handoff["missingEvidenceCodes"])
        self.assertNotIn("requestBodyTemplate", handoff)
        self.assertNotIn("requestBodyTemplate", handoff["templateSummaries"][0])
        self.assertFalse(closure["sideEffectBoundary"]["pythonRuntimeExecutedTool"])
        self.assertFalse(closure["sideEffectBoundary"]["pythonRuntimeWroteOutbox"])
        self.assertFalse(closure["sideEffectBoundary"]["javaControlPlaneIngested"])
        self.assertEqual(1, closure["counts"]["executableToolCount"])
        self.assertNotIn("ds-sensitive-closure-001", str(closure))

    def test_approval_and_draft_tools_are_reported_as_human_review_gate(self) -> None:
        """审批和草稿复核应成为明确闭环门禁。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="owner-a",
            objective="请基于客户主数据生成质量规则并创建任务",
            variables={
                "datasourceId": "ds-sensitive-closure-002",
                "businessGoal": "手机号唯一性",
                "createTask": True,
            },
        )

        response = build_plan_response(request, build_default_orchestrator())
        closure = response["agentExecutionClosure"]

        self.assertEqual("waiting_human_or_draft_review", closure["closurePhase"])
        self.assertIn("WAITING_HUMAN_APPROVAL", closure["blockingGates"])
        self.assertIn("WAITING_DRAFT_REVIEW", closure["blockingGates"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", closure["missingRuntimeEvidence"])
        self.assertIn("CREATE_OR_WAIT_APPROVAL", closure["nextActions"])
        self.assertIn("SHOW_DRAFT_FOR_REVIEW", closure["nextActions"])
        self.assertGreaterEqual(closure["counts"]["approvalRequiredToolCount"], 1)
        self.assertGreaterEqual(closure["counts"]["draftOnlyToolCount"], 1)
        self.assertFalse(closure["sideEffectBoundary"]["pythonRuntimeCreatedApproval"])
        self.assertNotIn("ds-sensitive-closure-002", str(closure))
        self.assertNotIn("手机号唯一性", str(closure))

    def test_ingested_plan_waits_for_control_plane_feedback(self) -> None:
        """当 Java 已接收计划但还没有反馈时，闭环快照应推进到等待反馈阶段。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-sensitive-closure-003"},
        )

        response = build_plan_response(
            request,
            build_default_orchestrator(),
            plan_ingestion_client=FakePlanIngestionClient(),
        )
        closure = response["agentExecutionClosure"]

        self.assertEqual("waiting_control_plane_feedback", closure["closurePhase"])
        self.assertEqual("control_plane_referenced", closure["closedLoopLevel"])
        self.assertIn("JAVA_CONTROL_PLANE_INGESTED", closure["completedStages"])
        self.assertIn("WAITING_CONTROL_PLANE_FEEDBACK", closure["blockingGates"])
        self.assertIn("CONTROL_PLANE_TOOL_FEEDBACK", closure["missingRuntimeEvidence"])
        self.assertIn("COLLECT_CONTROL_PLANE_FEEDBACK", closure["nextActions"])
        self.assertTrue(closure["sideEffectBoundary"]["javaControlPlaneIngested"])
        self.assertFalse(closure["sideEffectBoundary"]["controlPlaneFeedbackObserved"])
        self.assertNotIn("ds-sensitive-closure-003", str(closure))


if __name__ == "__main__":
    unittest.main()
