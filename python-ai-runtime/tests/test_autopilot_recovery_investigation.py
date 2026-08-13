from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_execution.autopilot_recovery import AutopilotRecoveryRequest
from datasmart_ai_runtime.services.agent_execution.autopilot_recovery_investigation import (
    AutopilotRecoveryInvestigationCollaborator,
    AutopilotRecoveryInvestigationError,
)
from datasmart_ai_runtime.services.agent_plan_ingestion_client import (
    AgentPlanIngestionResult,
    AgentToolAuditReference,
)
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import AgentFollowUpToolPlanner
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedbackStatus
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_toolplan_bridge import SpecialistToolPlanBridge
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class _FakeIngestionClient:
    """返回 Java audit 引用的最小接入替身，并记录阶段幂等键。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, tuple[str, ...]]] = []
        self._sequence = 0

    def ingest(self, request: AgentRequest, plan: AgentPlan, trace_id: str | None = None) -> AgentPlanIngestionResult:
        """为每个 plan 生成稳定形状的 session/run/audit 绑定，不执行任何工具。"""

        self._sequence += 1
        self.calls.append(
            (
                str(request.variables.get("idempotencyKey")),
                tuple(item.tool_name for item in plan.tool_plans),
            )
        )
        return AgentPlanIngestionResult(
            session_id="java-session-1",
            run_id=f"java-run-{self._sequence}",
            tool_audit_references=tuple(
                AgentToolAuditReference(
                    model_tool_call_id="",
                    tool_name=item.tool_name,
                    session_id="java-session-1",
                    run_id=f"java-run-{self._sequence}",
                    audit_id=f"audit-{self._sequence}",
                    state="PLANNED",
                    sequence=index,
                )
                for index, item in enumerate(plan.tool_plans, start=1)
            ),
            raw_response={},
        )


class _FakeFeedbackCollector:
    """按 diagnosis/preview 阶段返回成功 Java receipt。"""

    def __init__(self, *, real_receipts: bool = True) -> None:
        self.real_receipts = real_receipts
        self.calls: list[tuple[str, ...]] = []

    def collect(self, plan: AgentPlan) -> AgentControlPlaneFeedbackSnapshot:
        """将当前计划中的每个工具映射为低敏成功或缺失 receipt。"""

        names = tuple(item.tool_name for item in plan.tool_plans)
        self.calls.append(names)
        items = ()
        if self.real_receipts:
            items = tuple(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id=str(item.governance_hints.get("modelToolCallId") or ""),
                    tool_name=item.tool_name,
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary=f"{item.tool_name} succeeded",
                    result=(
                        {"failureCode": "TARGET_NOT_NULL_VIOLATION"}
                        if item.tool_name == "sync.execution.diagnose"
                        else {
                            "taskId": 31,
                            "executionId": 41,
                            "selectedCount": 2,
                            "eligibleCount": 2,
                            "operationState": "PREVIEWED",
                            "confirmationDigest": "a" * 64,
                            "selectedSampleIds": (9, 3),
                            "issueCodes": (),
                            "message": "must-not-reach-the-next-model-turn",
                        }
                    ),
                    audit_id=str(item.governance_hints.get("agentRuntimeAuditId") or ""),
                    run_id=str(item.governance_hints.get("agentRuntimeRunId") or ""),
                    output_ref=(
                        "agent-runtime://tool-results/"
                        + str(item.governance_hints.get("agentRuntimeAuditId") or "missing")
                    ),
                )
                for item in plan.tool_plans
            )
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=len(names),
            feedback_items=items,
            missing_tool_call_ids=(),
            status_counts={"succeeded": len(items)} if items else {},
            second_turn_eligible=bool(items),
            recommended_actions=(),
        )


def _autopilot_request() -> AutopilotRecoveryRequest:
    """构造 Java 已验证的低敏 Autopilot 请求。"""

    return AutopilotRecoveryRequest(
        event_id="autopilot-trigger:investigation-test",
        root_session_id="session-1",
        root_run_id="run-1",
        tenant_id="10",
        application_id="20",
        project_id="30",
        user_id="40",
        actor_id="40",
        agent_id="master-agent",
        delegation_id="delegation-1",
        workspace_key="project-30",
        sync_task_id="31",
        root_execution_id="41",
        current_execution_id="41",
        cycle=1,
        max_recovery_cycles=5,
        deadline_at=(datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
        error_fingerprint="b" * 64,
        repeated_error_count=0,
        issue_codes=("TARGET_NOT_NULL_VIOLATION",),
        triggered_at=datetime.now(timezone.utc).isoformat(),
    )


def _specialist_result(request: AutopilotRecoveryRequest) -> SpecialistTurnResult:
    """构造只提出 quarantine preview 的 Recovery 结果，绑定来自控制面而非模型。"""

    return SpecialistTurnResult(
        agent_id="recovery-agent",
        role=AgentSessionRole.RECOVERY_AGENT,
        turn_id="turn-recovery-investigation",
        status=SpecialistTurnStatus.COMPLETED,
        public_summary="先预览可隔离错误样本",
        structured_output={
            "repairActions": ({"actionId": "preview-1", "actionType": "PREVIEW_QUARANTINE"},),
            "actionFingerprint": "sha256:" + "c" * 64,
            "evidenceAudit": {
                "evidenceCount": 1,
                "evidenceRecords": ({"evidenceId": "diagnosis-1"},),
            },
            "diagnosticEvidenceGate": {"satisfied": True},
            "modelConfidence": 0.9,
        },
        control_plane_fact_binding={
            "source": "data-sync-control-plane",
            "factType": "SYNC_EXECUTION_DIAGNOSIS",
            "tenantId": request.tenant_id,
            "applicationId": request.application_id,
            "projectId": request.project_id,
            "actorId": request.actor_id,
            "sessionId": request.root_session_id,
            "runId": request.root_run_id,
            "delegationId": request.delegation_id,
            "taskId": request.sync_task_id,
            "executionId": request.current_execution_id,
        },
    )


def _collaborator(client: _FakeIngestionClient, feedback: _FakeFeedbackCollector) -> AutopilotRecoveryInvestigationCollaborator:
    """使用生产工具目录装配真实 bridge，避免测试使用私有白名单。"""

    planner = ToolPlanner(default_tool_registry())
    bridge = SpecialistToolPlanBridge(
        tool_planner=planner,
        follow_up_tool_planner=AgentFollowUpToolPlanner(tool_planner=planner),
    )
    return AutopilotRecoveryInvestigationCollaborator(
        bridge=bridge,
        plan_ingestion_client=client,  # type: ignore[arg-type]
        feedback_collector=feedback,  # type: ignore[arg-type]
    )


def test_investigation_uses_two_governed_ingestion_stages_and_returns_receipt_evidence() -> None:
    """调查必须先诊断、再 preview，并把 Java receipt 作为下一轮证据。"""

    request = _autopilot_request()
    client = _FakeIngestionClient()
    feedback = _FakeFeedbackCollector()
    collaborator = _collaborator(client, feedback)

    result = collaborator.investigate(
        request=request,
        specialist_result=_specialist_result(request),
        action_type="PREVIEW_QUARANTINE",
    )

    assert result.completed is True
    assert result.reason_code == "RECOVERY_INVESTIGATION_COMPLETED"
    assert [item[1] for item in client.calls] == [
        ("sync.execution.diagnose",),
        ("sync.dirty-record.quarantine.preview",),
    ]
    assert client.calls[0][0].endswith(":diagnosis")
    assert client.calls[1][0].endswith(":preview:preview_quarantine")
    assert result.evidence_summary["result"] == {
        "taskId": 31,
        "executionId": 41,
        "selectedCount": 2,
        "eligibleCount": 2,
        "operationState": "PREVIEWED",
        "confirmationDigest": "a" * 64,
        "selectedSampleIds": (9, 3),
        "issueCodes": (),
    }
    assert "failureCode" not in result.evidence_summary["result"]
    assert "message" not in result.evidence_summary["result"]


def test_investigation_never_treats_missing_real_receipt_as_success() -> None:
    """Java 未返回真实 receipt 时必须进入技术重试，而非继续无人值守恢复。"""

    request = _autopilot_request()
    client = _FakeIngestionClient()
    collaborator = _collaborator(client, _FakeFeedbackCollector(real_receipts=False))

    with pytest.raises(AutopilotRecoveryInvestigationError, match="REAL_RECEIPT_MISSING"):
        collaborator.investigate(
            request=request,
            specialist_result=_specialist_result(request),
            action_type="PREVIEW_QUARANTINE",
        )
