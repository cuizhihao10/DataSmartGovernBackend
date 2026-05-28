import os
import sys
import types
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator
from datasmart_ai_runtime.api_memory_write import register_memory_write_routes
from datasmart_ai_runtime.api_plan_response import build_plan_response
from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ToolExecutionMode,
    ToolPlan,
    ToolRiskLevel,
)
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.memory_write_governance import (
    AgentMemoryWriteGovernanceService,
    approve_memory_write_candidate,
    reject_memory_write_candidate,
)
from datasmart_ai_runtime.services.model_tool_result_feedback import ToolExecutionFeedbackStatus


class AgentMemoryWriteGovernanceTest(unittest.TestCase):
    def test_low_risk_success_feedback_becomes_draft_candidate(self) -> None:
        """低风险工具结果只生成草稿候选，不直接写入长期记忆。

        这个测试验证 DataSmart 的第一条安全边界：即使工具执行成功、descriptor 也声明了
        `memoryWritePolicy=episodic`，Python Runtime 仍然只提出候选，由后续审批/持久化层处理。
        """

        request = self._request()
        plan = self._plan(
            ToolPlan(
                tool_name="quality.rule.suggest",
                reason="生成质量规则草案",
                risk_level=ToolRiskLevel.LOW,
                execution_mode=ToolExecutionMode.DRAFT_ONLY,
                governance_hints={"memoryWritePolicy": "episodic"},
            )
        )
        feedback = self._feedback(
            AgentControlPlaneFeedbackItem(
                model_tool_call_id="call-quality",
                tool_name="quality.rule.suggest",
                status=ToolExecutionFeedbackStatus.SUCCEEDED,
                summary="已生成手机号格式校验规则草案，等待业务确认。",
                result={"ruleCount": 3, "sampleRows": 10},
                audit_id="audit-001",
                run_id="run-001",
                output_ref="minio://quality/rule-001.json",
            )
        )

        report = AgentMemoryWriteGovernanceService().propose(
            request=request,
            plan=plan,
            control_plane_feedback=feedback,
        )

        self.assertEqual(1, len(report.candidates))
        candidate = report.candidates[0]
        self.assertEqual(AgentMemoryWriteCandidateStatus.DRAFT, candidate.status)
        self.assertFalse(candidate.approval_required)
        self.assertEqual(AgentMemoryType.EPISODIC, candidate.memory_type)
        self.assertEqual(AgentMemoryScope.PROJECT, candidate.scope)
        self.assertEqual("audit-001", candidate.source_audit_id)
        self.assertEqual(("ruleCount", "sampleRows"), candidate.attributes["resultKeys"])

    def test_sensitive_high_risk_feedback_requires_approval(self) -> None:
        """敏感或高风险工具结果必须进入待审批状态。

        长期记忆会被后续 Agent 检索和复用，如果工具结果带有敏感字段或来自高风险动作，
        平台不能让它自动进入项目级/租户级记忆，否则会形成隐性数据泄露通道。
        """

        request = self._request()
        plan = self._plan(
            ToolPlan(
                tool_name="task.create.draft",
                reason="创建包含敏感样本的任务草案",
                risk_level=ToolRiskLevel.HIGH,
                execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
                requires_human_approval=True,
                governance_hints={"memoryWritePolicy": "episodic"},
            ),
            memory_plan=AgentMemoryPlan(
                writable_memory_types=(AgentMemoryType.EPISODIC,),
                default_scope=AgentMemoryScope.SESSION,
                retention_days=7,
                approval_required_for_write=True,
                privacy_notes=("敏感任务结果只能在当前会话范围内短期保留。",),
            ),
        )
        feedback = self._feedback(
            AgentControlPlaneFeedbackItem(
                model_tool_call_id="call-task",
                tool_name="task.create.draft",
                status=ToolExecutionFeedbackStatus.SUCCEEDED,
                summary="任务草案已生成，但包含手机号异常样本引用。",
                sensitive_fields=("phone", "sampleRows"),
            )
        )

        report = AgentMemoryWriteGovernanceService().propose(
            request=request,
            plan=plan,
            control_plane_feedback=feedback,
        )

        candidate = report.candidates[0]
        self.assertEqual(AgentMemoryWriteCandidateStatus.PENDING_APPROVAL, candidate.status)
        self.assertTrue(candidate.approval_required)
        self.assertEqual("sensitive", candidate.sensitivity_level)
        self.assertEqual(7, candidate.retention_days)
        self.assertEqual(1, report.approval_required_count)

    def test_no_writable_types_skips_all_candidates(self) -> None:
        """没有可写记忆类型时不做猜测性写入。"""

        request = self._request()
        plan = self._plan(
            ToolPlan(
                tool_name="datasource.metadata.read",
                reason="读取数据源元数据",
                governance_hints={"memoryWritePolicy": "semantic"},
            ),
            memory_plan=AgentMemoryPlan(),
        )

        report = AgentMemoryWriteGovernanceService().propose(request=request, plan=plan)

        self.assertEqual(0, len(report.candidates))
        self.assertTrue(any("没有声明可写入记忆类型" in reason for reason in report.skipped_reasons))

    def test_waiting_approval_tool_feedback_is_not_memory_candidate_yet(self) -> None:
        """工具本身还在等待审批时，不能先把未完成结果写成记忆候选。"""

        request = self._request()
        plan = self._plan(
            ToolPlan(
                tool_name="task.create.draft",
                reason="创建任务草案",
                governance_hints={"memoryWritePolicy": "episodic"},
            )
        )
        feedback = self._feedback(
            AgentControlPlaneFeedbackItem(
                model_tool_call_id="call-task",
                tool_name="task.create.draft",
                status=ToolExecutionFeedbackStatus.WAITING_APPROVAL,
                summary="任务创建仍在等待项目负责人审批。",
            )
        )

        report = AgentMemoryWriteGovernanceService().propose(
            request=request,
            plan=plan,
            control_plane_feedback=feedback,
        )

        self.assertEqual(0, len(report.candidates))
        self.assertTrue(any("等待工具执行审批" in reason for reason in report.skipped_reasons))

    def test_approve_and_reject_record_operator_reason_and_terminal_status(self) -> None:
        """审批/拒绝会记录操作者与原因，并阻止空原因决策。"""

        service = AgentMemoryWriteGovernanceService()
        report = service.propose(
            request=self._request(),
            plan=self._plan(
                ToolPlan(
                    tool_name="quality.rule.suggest",
                    reason="生成质量规则草案",
                    governance_hints={"memoryWritePolicy": "episodic"},
                )
            ),
        )
        candidate_id = report.candidates[0].candidate_id

        approved = approve_memory_write_candidate(
            service,
            candidate_id=candidate_id,
            operator_id="auditor-a",
            reason="规则草案只包含治理摘要，可进入项目经验库。",
        )

        self.assertEqual(AgentMemoryWriteCandidateStatus.APPROVED, approved.status)
        self.assertEqual("auditor-a", approved.decided_by)
        self.assertIn("项目经验库", approved.decision_reason)
        with self.assertRaises(ValueError):
            reject_memory_write_candidate(
                service,
                candidate_id=candidate_id,
                operator_id="auditor-a",
                reason="",
            )

    def test_plan_response_can_expose_memory_write_proposal_when_injected(self) -> None:
        """API 响应层通过显式注入暴露写入候选，不改变默认 plan 行为。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请为客户主数据生成质量规则",
            variables={"datasourceId": "ds-001", "sessionId": "session-a"},
        )
        response = build_plan_response(
            request,
            build_default_orchestrator(),
            memory_write_governance=AgentMemoryWriteGovernanceService(),
        )

        self.assertIn("memoryWriteProposal", response)
        self.assertGreaterEqual(response["memoryWriteProposal"]["candidateCount"], 1)
        event_types = {event["event_type"] for event in response["plan"]["runtime_events"]}
        self.assertIn("memory_write_candidate_proposed", event_types)

    def test_service_can_list_candidates_by_scope_and_status(self) -> None:
        """治理服务提供审批台所需的候选列表查询入口。"""

        service = AgentMemoryWriteGovernanceService()
        service.propose(
            request=self._request(),
            plan=self._plan(
                ToolPlan(
                    tool_name="quality.rule.suggest",
                    reason="生成质量规则草案",
                    governance_hints={"memoryWritePolicy": "episodic"},
                )
            ),
        )
        service.propose(
            request=AgentRequest(
                tenant_id="tenant-b",
                project_id="project-b",
                actor_id="analyst-b",
                objective="另一个租户的候选不应出现在 tenant-a 查询结果中",
            ),
            plan=self._plan(
                ToolPlan(
                    tool_name="quality.rule.suggest",
                    reason="生成质量规则草案",
                    governance_hints={"memoryWritePolicy": "episodic"},
                )
            ),
        )

        candidates = service.list_candidates(
            tenant_id="tenant-a",
            project_id="project-a",
            status=AgentMemoryWriteCandidateStatus.DRAFT,
        )

        self.assertEqual(1, len(candidates))
        self.assertEqual("tenant-a", candidates[0].tenant_id)

    def test_memory_write_routes_support_list_detail_and_decision_without_fastapi_dependency(self) -> None:
        """用 fake FastAPI 模块验证路由契约，避免默认测试环境必须安装可选 API 依赖。"""

        service = AgentMemoryWriteGovernanceService()
        report = service.propose(
            request=self._request(),
            plan=self._plan(
                ToolPlan(
                    tool_name="quality.rule.suggest",
                    reason="生成质量规则草案",
                    governance_hints={"memoryWritePolicy": "episodic"},
                )
            ),
        )
        app = FakeFastApiApp()
        with fake_fastapi_module():
            register_memory_write_routes(app, service)

        list_response = app.call(
            "GET",
            "/agent/memory/write-candidates",
            tenantId="tenant-a",
            projectId="project-a",
            status="draft",
        )
        detail_response = app.call(
            "GET",
            "/agent/memory/write-candidates/{candidate_id}",
            report.candidates[0].candidate_id,
        )
        approve_response = app.call(
            "POST",
            "/agent/memory/write-candidates/{candidate_id}/approve",
            report.candidates[0].candidate_id,
            {"operatorId": "auditor-a", "reason": "候选摘要可进入项目经验库。"},
        )

        self.assertEqual(1, list_response["candidateCount"])
        self.assertEqual(report.candidates[0].candidate_id, detail_response["candidate"]["candidateId"])
        self.assertEqual("approved", approve_response["candidate"]["status"])

    @staticmethod
    def _request() -> AgentRequest:
        return AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请基于历史治理经验继续规划本次任务",
            variables={"sessionId": "session-a"},
        )

    @staticmethod
    def _plan(
        *tool_plans: ToolPlan,
        memory_plan: AgentMemoryPlan | None = None,
    ) -> AgentPlan:
        return AgentPlan(
            request_id="request-a",
            selected_route=None,
            state_trace=("plan_tools", "plan_memory"),
            tool_plans=tool_plans,
            requires_human_approval=any(plan.requires_human_approval for plan in tool_plans),
            response_summary="已生成工具计划。",
            memory_plan=memory_plan
            or AgentMemoryPlan(
                writable_memory_types=(AgentMemoryType.EPISODIC,),
                default_scope=AgentMemoryScope.PROJECT,
                retention_days=30,
            ),
        )

    @staticmethod
    def _feedback(*items: AgentControlPlaneFeedbackItem) -> AgentControlPlaneFeedbackSnapshot:
        status_counts: dict[str, int] = {}
        for item in items:
            status_counts[item.status.value] = status_counts.get(item.status.value, 0) + 1
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=len(items),
            feedback_items=items,
            missing_tool_call_ids=(),
            status_counts=status_counts,
            second_turn_eligible=True,
            recommended_actions=("工具反馈已完整，可用于后续治理。",),
        )

class FakeHttpException(Exception):
    """测试用 HTTPException，模拟 FastAPI 异常对象的最小字段。"""

    def __init__(self, status_code: int, detail: str) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


class fake_fastapi_module:
    """临时向 sys.modules 注入 fake fastapi，避免测试依赖可选包。"""

    def __enter__(self) -> None:
        self._old = sys.modules.get("fastapi")
        module = types.ModuleType("fastapi")
        module.HTTPException = FakeHttpException
        sys.modules["fastapi"] = module

    def __exit__(self, exc_type, exc, tb) -> None:
        if self._old is None:
            sys.modules.pop("fastapi", None)
        else:
            sys.modules["fastapi"] = self._old


class FakeFastApiApp:
    """捕获路由装饰器注册结果的测试桩。"""

    def __init__(self) -> None:
        self.routes: dict[tuple[str, str], object] = {}

    def get(self, path: str):
        def decorator(func):
            self.routes[("GET", path)] = func
            return func

        return decorator

    def post(self, path: str):
        def decorator(func):
            self.routes[("POST", path)] = func
            return func

        return decorator

    def call(self, method: str, path: str, *args, **kwargs):
        return self.routes[(method, path)](*args, **kwargs)


if __name__ == "__main__":
    unittest.main()
