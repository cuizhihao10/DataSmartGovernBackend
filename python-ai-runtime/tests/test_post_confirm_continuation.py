import os
import sys
import unittest
from dataclasses import dataclass

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    ModelRoute,
    ProviderType,
    ToolPlan,
    WorkloadType,
)
from datasmart_ai_runtime.services.agent_execution.post_confirm_continuation import (
    AgentPostConfirmContinuationCoordinator,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnResult
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry


class PostConfirmContinuationTest(unittest.TestCase):
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

    def test_rejects_non_successful_java_result(self) -> None:
        payload = _payload()
        payload["toolResults"][0]["audit"]["state"] = "FAILED"
        coordinator = AgentPostConfirmContinuationCoordinator(
            model_routes=_routes(),
            second_turn_orchestrator=_SecondTurn(),
            loop_control_evaluator=_AllowLoop(),
            durable_loop_runner=_DurableRunner(waiting_confirmation=False),
        )

        with self.assertRaises(ValueError):
            coordinator.continue_after_confirmed_tools(payload)

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


class _DurableRunner:
    def __init__(self, *, waiting_confirmation: bool) -> None:
        self._waiting_confirmation = waiting_confirmation

    def run(self, *, request, plan, first_model_turn, initial_feedback):
        del request, first_model_turn
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
        "projectId": "101",
        "actorId": "1001",
        "sessionId": "session-1",
        "runId": "run-read",
        "objective": "创建 MySQL 到 PostgreSQL 的全量同步任务。",
        "workspaceKey": "tenant:10:project:101",
        "toolResults": [
            result("datasource.source.catalog.search", "source-catalog", {"items": [{"id": 27}]}),
            result("datasource.target.catalog.search", "target-catalog", {"items": [{"id": 28}]}),
        ],
    }


if __name__ == "__main__":
    unittest.main()
