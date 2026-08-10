import os
import sys
import unittest
from dataclasses import dataclass, replace
from types import SimpleNamespace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    ModelRoute,
    ProviderType,
    ToolParameterValidationResult,
    ToolPlan,
    WorkloadType,
)
from datasmart_ai_runtime.services.agent_execution.post_confirm_continuation import (
    AgentPostConfirmContinuationCoordinator,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnResult
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry


class PostConfirmContinuationTest(unittest.TestCase):
    def test_successful_submission_skips_model_but_runs_post_resource_specialists(self) -> None:
        """A submitted job needs deterministic review, not a costly success paraphrase."""

        specialist_coordinator = _RecordingSpecialistCoordinator()
        second_turn = _FailIfCalledSecondTurn()
        coordinator = AgentPostConfirmContinuationCoordinator(
            model_routes=_routes(),
            second_turn_orchestrator=second_turn,
            loop_control_evaluator=_AllowLoop(),
            durable_loop_runner=_DurableRunner(waiting_confirmation=False),
            specialist_agent_coordinator=specialist_coordinator,
            specialist_allowed_tools_by_role={
                "PRECHECK_AGENT": ("sync.task.precheck",),
                "MONITOR_AGENT": ("sync.execution.status",),
            },
        )

        summary = coordinator.continue_after_confirmed_tools(
            _submission_payload()
        ).to_summary()

        self.assertEqual("BUSINESS_GOAL_REACHED", summary["status"])
        self.assertFalse(summary["continued"])
        self.assertEqual("TASK_SUBMITTED_OR_SCHEDULED", summary["stoppedReason"])
        self.assertIsNone(summary["modelSecondTurn"])
        self.assertEqual("EXECUTED", summary["postBridgeVerification"]["status"])
        self.assertEqual("77", summary["postBridgeVerification"]["taskId"])
        self.assertEqual("1958", summary["postBridgeVerification"]["executionId"])
        self.assertEqual(
            ("PRECHECK_AGENT", "MONITOR_AGENT"),
            tuple(summary["postBridgeVerification"]["executedRoles"]),
        )
        self.assertEqual(1, len(specialist_coordinator.calls))
        specialist_context = specialist_coordinator.calls[0]["base_context"]
        self.assertEqual("77", specialist_context["taskId"])
        self.assertEqual("1958", specialist_context["executionId"])
        self.assertTrue(specialist_context["postBridgeVerification"])
        specialist_request = specialist_coordinator.calls[0]["request"]
        self.assertEqual(
            "10010",
            specialist_request.variables["trustedControlPlane"]["applicationId"],
        )
        self.assertEqual(
            "delegation-parent-session-1",
            specialist_request.variables["trustedControlPlane"]["delegationId"],
        )

    def test_rejects_post_confirm_payload_without_application_scope(self) -> None:
        """Post-confirm facts must never fall back to tenant/project-only isolation."""

        payload = _submission_payload()
        payload.pop("applicationId")
        coordinator = AgentPostConfirmContinuationCoordinator(
            model_routes=_routes(),
            second_turn_orchestrator=_FailIfCalledSecondTurn(),
            loop_control_evaluator=_AllowLoop(),
            durable_loop_runner=_DurableRunner(waiting_confirmation=False),
        )

        with self.assertRaisesRegex(ValueError, "positive applicationId"):
            coordinator.continue_after_confirmed_tools(payload)

    def test_rejects_post_confirm_payload_without_parent_delegation(self) -> None:
        """Java continuation must preserve the session delegation used to derive Specialist children."""

        payload = _submission_payload()
        payload.pop("delegationId")
        coordinator = AgentPostConfirmContinuationCoordinator(
            model_routes=_routes(),
            second_turn_orchestrator=_FailIfCalledSecondTurn(),
            loop_control_evaluator=_AllowLoop(),
            durable_loop_runner=_DurableRunner(waiting_confirmation=False),
        )

        with self.assertRaisesRegex(ValueError, "delegationId"):
            coordinator.continue_after_confirmed_tools(payload)

    def test_preserves_all_confirmed_results_as_initial_evidence(self) -> None:
        second_turn = _SecondTurn()
        durable_runner = _DurableRunner(waiting_confirmation=True)
        coordinator = AgentPostConfirmContinuationCoordinator(
            model_routes=_routes(),
            second_turn_orchestrator=second_turn,
            loop_control_evaluator=_AllowLoop(),
            durable_loop_runner=durable_runner,
        )

        result = coordinator.continue_after_confirmed_tools(_payload())

        self.assertEqual(
            ("datasource.source.catalog.search", "datasource.target.catalog.search"),
            tuple(item.tool_name for item in second_turn.plan.tool_plans),
        )
        self.assertEqual(2, len(second_turn.feedback.feedback_items))
        self.assertEqual(2, len(durable_runner.initial_feedback.feedback_items))
        self.assertEqual("WAITING_CONFIRMATION", result.to_summary()["status"])
        self.assertEqual("run-write", result.to_summary()["nextRunId"])

    def test_accepts_failed_java_result_as_model_diagnosis_feedback(self) -> None:
        payload = _payload()
        payload["toolResults"][0]["audit"]["state"] = "FAILED"
        payload["toolResults"][0]["audit"]["errorCode"] = "SYNC_PRECHECK_BLOCKED"
        payload["toolResults"][0]["audit"]["message"] = "目标表不存在。"
        payload["toolResults"][0]["output"] = {
            "issueCodes": ["TARGET_TABLE_NOT_FOUND"],
            "recommendedActions": ["重新选择目标表"],
        }
        second_turn = _SecondTurn()
        coordinator = AgentPostConfirmContinuationCoordinator(
            model_routes=_routes(),
            second_turn_orchestrator=second_turn,
            loop_control_evaluator=_AllowLoop(),
            durable_loop_runner=_DurableRunner(waiting_confirmation=False),
        )

        result = coordinator.continue_after_confirmed_tools(payload)

        failed_feedback = second_turn.feedback.feedback_items[0]
        self.assertEqual("failed", failed_feedback.status.value)
        self.assertEqual("SYNC_PRECHECK_BLOCKED", failed_feedback.error_code)
        self.assertEqual("目标表不存在。", failed_feedback.error_message)
        self.assertTrue(second_turn.plan.response_summary.startswith("已收到真实工具失败事实"))
        self.assertTrue(result.model_turn.executed)

    def test_duplicate_task_name_failure_returns_exact_confirmation_gated_repair(self) -> None:
        payload = _payload()
        payload["toolResults"] = [
            {
                "audit": {
                    "auditId": "source-metadata-success",
                    "sessionId": "session-1",
                    "runId": "run-read",
                    "toolCode": "datasource.source.metadata.read",
                    "state": "SUCCEEDED",
                    "riskLevel": "LOW",
                    "executionMode": "AUTO",
                    "planArguments": {},
                    "governanceHints": {"modelToolCallId": "call-source-metadata"},
                },
                "output": {"metadata": {"datasourceId": 27, "objects": []}},
            },
            {
                "audit": {
                    "auditId": "target-metadata-success",
                    "sessionId": "session-1",
                    "runId": "run-read",
                    "toolCode": "datasource.target.metadata.read",
                    "state": "SUCCEEDED",
                    "riskLevel": "LOW",
                    "executionMode": "AUTO",
                    "planArguments": {},
                    "governanceHints": {"modelToolCallId": "call-target-metadata"},
                },
                "output": {"metadata": {"datasourceId": 28, "objects": []}},
            },
            {
            "audit": {
                "auditId": "draft-save-failed",
                "sessionId": "session-1",
                "runId": "run-read",
                "toolCode": "sync.task.draft.save",
                "state": "FAILED",
                "riskLevel": "HIGH",
                "executionMode": "APPROVAL_REQUIRED",
                "errorCode": "SYNC_DOWNSTREAM_ERROR",
                "message": (
                    '409 Conflict: {"reason":"DUPLICATE_OPERATION",'
                    '"message":"当前项目下已存在同名同步任务"}'
                ),
                "planArguments": {
                    "taskName": "Agent 创建的数据同步任务",
                    "syncMode": "FULL",
                    "sourceMetadataRef": {
                        "fromTool": "datasource.source.metadata.read",
                        "path": "metadata",
                    },
                    "targetMetadataRef": {
                        "fromTool": "datasource.target.metadata.read",
                        "path": "metadata",
                    },
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetObjectName": "customer",
                    }],
                },
                "governanceHints": {"modelToolCallId": "call-draft-failed"},
            },
            "output": {},
            },
        ]
        durable_runner = _DurableRunner(waiting_confirmation=True)
        coordinator = AgentPostConfirmContinuationCoordinator(
            model_routes=_routes(),
            second_turn_orchestrator=_SecondTurn(),
            loop_control_evaluator=_AllowLoop(),
            durable_loop_runner=durable_runner,
            tool_planner=_RepairToolPlanner(),
        )

        summary = coordinator.continue_after_confirmed_tools(payload).to_summary()

        proposal = summary["repairProposal"]
        self.assertEqual("DUPLICATE_TASK_NAME", proposal["kind"])
        self.assertEqual("Agent 创建的数据同步任务", proposal["originalTaskName"])
        self.assertTrue(proposal["proposedTaskName"].startswith("Agent 创建的数据同步任务_agent_"))
        self.assertTrue(proposal["requiresConfirmation"])
        self.assertEqual("WAITING_CONFIRMATION", summary["status"])
        self.assertEqual("run-write", summary["nextRunId"])
        repair_tools = durable_runner.first_model_turn.follow_up_tool_plans
        self.assertEqual("sync.task.draft.save", repair_tools[0].tool_name)
        self.assertEqual(proposal["proposedTaskName"], repair_tools[0].arguments["taskName"])
        self.assertEqual(
            "source-metadata-success",
            repair_tools[0].arguments["sourceMetadataRef"]["fromAuditId"],
        )
        self.assertEqual(
            "target-metadata-success",
            repair_tools[0].arguments["targetMetadataRef"]["fromAuditId"],
        )
        self.assertTrue(repair_tools[0].requires_human_approval)

    def test_http_route_accepts_json_body_and_request_context(self) -> None:
        """FastAPI must inject Request instead of treating it as a payload field."""

        try:
            from fastapi import FastAPI, HTTPException, Request
            from fastapi.testclient import TestClient
        except ImportError:
            self.skipTest("FastAPI API extras are not installed")

        from datasmart_ai_runtime.api.agent.post_confirm_continuation import (
            register_post_confirm_continuation_routes,
        )

        app = FastAPI()
        register_post_confirm_continuation_routes(
            app,
            request_type=Request,
            coordinator=AgentPostConfirmContinuationCoordinator(
                model_routes=_routes(),
                second_turn_orchestrator=_SecondTurn(),
                loop_control_evaluator=_AllowLoop(),
                durable_loop_runner=_DurableRunner(waiting_confirmation=True),
            ),
            service_account_token="service-token",
            error_factory=lambda status_code, detail: HTTPException(
                status_code=status_code,
                detail=detail,
            ),
        )

        response = TestClient(app).post(
            "/internal/agent/continuations/post-confirm",
            headers={"Authorization": "Bearer service-token"},
            json=_payload(),
        )

        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual("WAITING_CONFIRMATION", response.json()["status"])
        self.assertEqual("run-write", response.json()["nextRunId"])


class _AllowLoop:
    def evaluate(self, snapshot, state):
        del snapshot, state

        @dataclass(frozen=True)
        class Decision:
            allowed: bool = True
            action: object = None

        from datasmart_ai_runtime.services.agent_loop_control_policy import AgentLoopControlAction

        return Decision(action=AgentLoopControlAction.ALLOW_SECOND_TURN)


class _SecondTurn:
    def run(self, *, request, plan, control_plane_feedback, loop_control_decision):
        del request, loop_control_decision
        self.plan = plan
        self.feedback = control_plane_feedback
        return AgentSecondTurnResult(
            executed=True,
            allowed=True,
            action="continue_with_tools",
            summary="继续读取字段元数据。",
            follow_up_tool_plans=(
                ToolPlan(
                    tool_name="datasource.source.metadata.read",
                    reason="读取源表字段。",
                ),
            ),
        )


class _FailIfCalledSecondTurn:
    """Guard proving the successful submission path does not invoke the model."""

    def run(self, **kwargs):
        del kwargs
        raise AssertionError("a completed submission must not invoke a second model turn")


class _RecordingSpecialistCoordinator:
    """Record the trusted post-resource wave and return two low-sensitive facts."""

    def __init__(self) -> None:
        self.calls = []

    def run(self, **kwargs):
        self.calls.append(kwargs)
        results = tuple(
            SimpleNamespace(role=SimpleNamespace(value=role), to_summary=lambda: {})
            for role in ("PRECHECK_AGENT", "MONITOR_AGENT")
        )

        class Batch:
            status = "COMPLETED"
            skipped_roles = {}
            execution_waves = (("PRECHECK_AGENT", "MONITOR_AGENT"),)

            def __init__(self, values):
                self.results = values

            def to_summary(self):
                return {
                    "status": self.status,
                    "results": tuple(
                        {"agentRole": item.role.value, "status": "COMPLETED"}
                        for item in self.results
                    ),
                    "skippedRoles": {},
                }

        return Batch(results)


class _DurableRunner:
    def __init__(self, *, waiting_confirmation: bool) -> None:
        self._waiting_confirmation = waiting_confirmation

    def run(self, *, request, plan, first_model_turn, initial_feedback):
        del request
        self.first_model_turn = first_model_turn
        self.initial_feedback = initial_feedback
        latest_plan = AgentPlan(
            request_id="write-plan",
            selected_route=plan.selected_route,
            state_trace=plan.state_trace,
            tool_plans=(
                ToolPlan(
                    tool_name="sync.task.draft.save",
                    reason="保存同步任务草稿。",
                    governance_hints={
                        "agentRuntimeSessionId": "session-1",
                        "agentRuntimeRunId": "run-write",
                    },
                ),
            ),
            requires_human_approval=True,
            response_summary="等待确认。",
        )

        @dataclass(frozen=True)
        class Result:
            latest_plan: AgentPlan
            stopped_reason: str

            def to_summary(self):
                return {"stoppedReason": self.stopped_reason}

        return Result(
            latest_plan=latest_plan,
            stopped_reason=(
                "WAITING_APPROVAL" if self._waiting_confirmation else "MODEL_COMPLETED_WITHOUT_MORE_TOOLS"
            ),
        )


class _RepairToolPlanner:
    def revalidate_plan(self, plan, arguments):
        return replace(
            plan,
            arguments=dict(arguments),
            parameter_validation=ToolParameterValidationResult(can_execute=True),
        )

    def expand_confirmed_data_sync_lifecycle(self, draft_plan):
        return (
            draft_plan,
            ToolPlan(
                tool_name="sync.task.precheck",
                reason="precheck repaired draft",
                requires_human_approval=True,
            ),
            ToolPlan(
                tool_name="sync.task.publish",
                reason="publish repaired draft",
                requires_human_approval=True,
            ),
            ToolPlan(
                tool_name="sync.task.run",
                reason="run repaired task",
                requires_human_approval=True,
            ),
        )


def _routes() -> ModelRouteRegistry:
    return ModelRouteRegistry(
        (
            ModelRoute(
                workload=WorkloadType.AGENT_REASONING,
                provider_name="test-provider",
                provider_type=ProviderType.PYTHON_LOCAL,
                model_name="test-model",
                endpoint="http://model.invalid",
            ),
        )
    )


def _payload() -> dict:
    def result(tool_code: str, audit_id: str, output: dict) -> dict:
        return {
            "audit": {
                "auditId": audit_id,
                "sessionId": "session-1",
                "runId": "run-read",
                "toolCode": tool_code,
                "state": "SUCCEEDED",
                "riskLevel": "LOW",
                "executionMode": "SYNC",
                "governanceHints": {
                    "modelToolCallId": f"call-{audit_id}",
                    "outputContextPolicy": "model_summary_allowed",
                },
            },
            "output": output,
        }

    return {
        "tenantId": "10",
        "applicationId": "10010",
        "projectId": "101",
        "actorId": "1001",
        "delegationId": "delegation-parent-session-1",
        "sessionId": "session-1",
        "runId": "run-read",
        "objective": "创建 MySQL 到 PostgreSQL 的全量同步任务。",
        "workspaceKey": "tenant:10:project:101",
        "toolResults": [
            result("datasource.source.catalog.search", "source-catalog", {"items": [{"id": 27}]}),
            result("datasource.target.catalog.search", "target-catalog", {"items": [{"id": 28}]}),
        ],
    }


def _submission_payload() -> dict:
    """Build one complete Java lifecycle receipt with trusted resource IDs."""

    def result(tool_code: str, audit_id: str, output: dict) -> dict:
        return {
            "audit": {
                "auditId": audit_id,
                "sessionId": "session-1",
                "runId": "run-lifecycle",
                "toolCode": tool_code,
                "state": "SUCCEEDED",
                "riskLevel": "HIGH",
                "executionMode": "APPROVAL_REQUIRED",
                "planArguments": {"syncMode": "FULL"},
                "governanceHints": {"modelToolCallId": f"call-{audit_id}"},
            },
            "output": output,
        }

    return {
        "tenantId": "10",
        "applicationId": "10010",
        "projectId": "101",
        "actorId": "1001",
        "delegationId": "delegation-parent-session-1",
        "sessionId": "session-1",
        "runId": "run-lifecycle",
        "objective": "Create and submit one full synchronization task.",
        "workspaceKey": "tenant:10:project:101",
        "toolResults": [
            result("sync.task.draft.save", "draft", {"taskId": 77}),
            result("sync.task.precheck", "precheck", {"taskId": 77}),
            result("sync.task.publish", "publish", {"taskId": 77}),
            result("sync.task.run", "run", {"taskId": 77, "executionId": 1958}),
        ],
    }


if __name__ == "__main__":
    unittest.main()
