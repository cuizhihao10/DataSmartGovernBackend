"""Regression coverage for partial read-only Recovery preview batches."""

from __future__ import annotations

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import AgentFollowUpToolPlanner
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_toolplan_bridge import (
    SpecialistBridgeStatus,
    SpecialistToolPlanBridge,
)
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


def _request() -> AgentRequest:
    """构造带完整租户、项目、用户和 Agent Runtime 作用域的恢复请求。

    Bridge 会把这些字段写入 ToolPlan 治理提示和 Recovery handoff；测试使用固定值，
    便于同时验证部分动作被跳过时，剩余动作仍然保留原始委派边界。
    """

    return AgentRequest(
        tenant_id="tenant-10",
        project_id="project-101",
        actor_id="user-1001",
        objective="Preview recovery actions for a failed sync execution.",
        request_id="request-bridge-partial-1",
        variables={
            "trustedControlPlane": {"applicationId": "datasmart"},
            "agentRuntimeSessionId": "session-bridge-partial-1",
            "agentRuntimeRunId": "run-bridge-partial-1",
        },
    )


def _plan(request: AgentRequest) -> AgentPlan:
    """构造只声明恢复候选工具、尚未产生任何副作用的父计划。

    该计划模拟主 Agent 已经完成意图识别但还没有提交 Recovery ToolPlan 的时刻，
    让测试只观察 Specialist bridge 的筛选与治理行为。
    """

    return AgentPlan(
        request_id=request.request_id or "request-bridge-partial-1",
        selected_route=None,
        state_trace=("receive_goal", "specialist_agent"),
        tool_plans=(),
        requires_human_approval=False,
        response_summary="Partial Recovery preview regression plan.",
        intent_analysis=IntentAnalysis(
            summary="Recovery preview regression.",
            governance_domains=(GovernanceDomain.DATA_SYNC,),
            candidate_tools=(
                "sync.execution.diagnose",
                "sync.execution.rag.lookup",
                "sync.dirty-record.quarantine.preview",
                "datasource.schema.repair.preview",
            ),
            confidence=1.0,
        ),
    )


def _feedback() -> AgentControlPlaneFeedbackSnapshot:
    """提供诊断与 RAG 查询已经成功的控制面事实。

    这些回执只包含 bridge 派生 ``diagnosisRef`` 所需的审计定位，不伪造结构修复参数；
    因而缺少表字段定位的 schema preview 仍必须被识别为待补参动作。
    """

    items = (
        AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-diagnosis",
            tool_name="sync.execution.diagnose",
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="Execution diagnosis succeeded.",
            result={"failureCode": "DIRTY_RECORDS"},
            audit_id="audit-diagnosis",
            run_id="run-recovery",
            output_ref="agent-runtime://tool-results/audit-diagnosis",
        ),
        AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-rag",
            tool_name="sync.execution.rag.lookup",
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="Recovery evidence lookup succeeded.",
            result={"evidenceCount": 2},
            audit_id="audit-rag",
            run_id="run-recovery",
            output_ref="agent-runtime://tool-results/audit-rag",
        ),
    )
    return AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=len(items),
        feedback_items=items,
        missing_tool_call_ids=(),
        status_counts={"succeeded": len(items)},
        second_turn_eligible=True,
        recommended_actions=(),
    )


def _recovery_result() -> SpecialistTurnResult:
    """模拟同一 Recovery turn 同时返回完整预览和缺参预览。

    隔离预览可以由服务端使用有界的可重试样本选择器补全，结构预览则必须由模型或用户
    提供目标表、字段和白名单操作；这正是本回归测试要固定的差异化处理规则。
    """

    return SpecialistTurnResult(
        agent_id="recovery-agent-1",
        role=AgentSessionRole.RECOVERY_AGENT,
        turn_id="turn-recovery-partial-1",
        status=SpecialistTurnStatus.COMPLETED,
        public_summary="Recovery previews proposed.",
        structured_output={
            "actionFingerprint": "sha256:recovery-partial-preview-fingerprint",
            "executed": False,
            "repairActions": (
                {
                    "actionId": "quarantine-preview-complete",
                    "actionType": "PREVIEW_QUARANTINE",
                    "reason": "Preview retryable dirty records before any mutation.",
                },
                {
                    "actionId": "schema-preview-missing-location",
                    "actionType": "PREVIEW_SCHEMA_REPAIR",
                    "reason": "Inspect a possible schema repair without a grounded target.",
                },
            ),
        },
    )


def _bridge() -> SpecialistToolPlanBridge:
    """使用生产默认工具注册表创建真实 follow-up 治理链路。

    测试不注入宽松的伪工具目录，确保工具可见性、输入 schema、派生参数和审批属性与
    应用启动时使用的合同一致。
    """

    tool_planner = ToolPlanner(default_tool_registry())
    return SpecialistToolPlanBridge(
        tool_planner=tool_planner,
        follow_up_tool_planner=AgentFollowUpToolPlanner(tool_planner=tool_planner),
    )


def test_partial_read_only_previews_accept_complete_plan_and_report_missing_input() -> None:
    request = _request()

    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_recovery_result(),
        control_plane_feedback=_feedback(),
    )

    summary = result.to_summary()
    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert result.can_submit_durable_loop is True
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.dirty-record.quarantine.preview",
    )
    assert result.accepted_tool_plans[0].arguments == {
        "quarantineAllRetryableInExecution": True,
        "diagnosisRef": {
            "fromTool": "sync.execution.diagnose",
            "fromAuditId": "audit-diagnosis",
            "fromRunId": "run-recovery",
            "path": None,
        },
    }
    assert tuple(issue.code for issue in result.issues) == (
        "RECOVERY_ACTION_INPUT_INCOMPLETE",
    )
    assert result.recovery_handoff is not None
    assert tuple(item.tool_name for item in result.recovery_handoff.blueprints) == (
        "sync.dirty-record.quarantine.preview",
    )
    assert result.model_turn is not None
    assert result.model_turn.model_tool_call_count == 1
    assert summary["status"] == "ACCEPTED"
    assert summary["acceptedToolPlanCount"] == 1
    assert summary["acceptedToolNames"] == ("sync.dirty-record.quarantine.preview",)
    assert summary["canSubmitDurableLoop"] is True
    assert summary["toolArgumentNameSets"] == ((
        "diagnosisRef",
        "quarantineAllRetryableInExecution",
    ),)
    assert len(summary["issues"]) == 1
    assert summary["issues"][0]["code"] == "RECOVERY_ACTION_INPUT_INCOMPLETE"
    assert summary["recoveryHandoff"]["blueprintCount"] == 1
    assert summary["recoveryHandoff"]["requiresJavaRehydration"] is False
