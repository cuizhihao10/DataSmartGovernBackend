import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackSnapshot
from datasmart_ai_runtime.services.agent_execution import (
    DurableAgentLoopPhase,
    DurableAgentLoopResumeAction,
    DurableAgentLoopService,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import (
    AgentLoopControlAction,
    AgentLoopControlDecision,
)


class DurableAgentLoopTest(unittest.TestCase):
    def test_waits_for_control_plane_when_model_tool_feedback_missing(self) -> None:
        """有模型 tool_call 但没有控制面反馈时，Durable Loop 必须等待 replay/反馈。"""

        service = DurableAgentLoopService()
        checkpoint = service.record(
            request=_request(),
            plan=_plan_with_model_tool_call(),
        )

        self.assertEqual(DurableAgentLoopPhase.WAITING_CONTROL_PLANE, checkpoint.phase)
        self.assertEqual(DurableAgentLoopResumeAction.WAIT_EVENT_REPLAY, checkpoint.resume_action)
        self.assertEqual(("CONTROL_PLANE_FEEDBACK_NOT_COLLECTED",), checkpoint.waiting_reason_codes)

    def test_allows_second_turn_when_loop_policy_allows(self) -> None:
        """当 loop policy 允许二轮时，checkpoint 应明确可恢复到二轮推理节点。"""

        service = DurableAgentLoopService()
        checkpoint = service.record(
            request=_request(),
            plan=_plan_with_model_tool_call(),
            control_plane_feedback=AgentControlPlaneFeedbackSnapshot(
                expected_tool_call_count=1,
                feedback_items=(),
                missing_tool_call_ids=(),
                status_counts={},
                second_turn_eligible=True,
                recommended_actions=(),
            ),
            loop_control_decision=AgentLoopControlDecision(
                allowed=True,
                action=AgentLoopControlAction.ALLOW_SECOND_TURN,
                reasons=("控制面反馈完整。",),
                recommended_actions=("进入受控二轮。",),
            ),
        )

        self.assertEqual(DurableAgentLoopPhase.READY_FOR_SECOND_TURN, checkpoint.phase)
        self.assertEqual(DurableAgentLoopResumeAction.RUN_SECOND_TURN, checkpoint.resume_action)
        self.assertEqual("allow_second_turn", checkpoint.loop_action)

    def test_checkpoint_can_be_queried_by_run_id_without_sensitive_payload(self) -> None:
        """checkpoint 查询只返回低敏状态，不包含工具参数。"""

        service = DurableAgentLoopService()
        checkpoint = service.record(request=_request(), plan=_plan_with_model_tool_call())
        summary = service.get(checkpoint.run_id or checkpoint.request_id)

        self.assertEqual("low_sensitive_loop_state_only", summary["payloadPolicy"].lower())
        self.assertNotIn("arguments", str(summary).lower())
        self.assertNotIn("secret", str(summary).lower())


def _request() -> AgentRequest:
    return AgentRequest(
        tenant_id="tenant-a",
        project_id="project-a",
        actor_id="user-a",
        objective="生成数据同步计划",
        variables={"sessionId": "session-a"},
    )


def _plan_with_model_tool_call() -> AgentPlan:
    return AgentPlan(
        request_id="request-a",
        selected_route=None,
        state_trace=("receive_goal", "plan_tools"),
        tool_plans=(
            ToolPlan(
                tool_name="datasource.metadata.read",
                reason="读取元数据",
                arguments={"datasourceId": "ds-secret-001"},
                governance_hints={"modelToolCallId": "call-a"},
            ),
        ),
        requires_human_approval=False,
        response_summary="已生成计划。",
        runtime_events=(
            AgentRuntimeEvent(
                event_type=AgentRuntimeEventType.TOOL_PLANNED,
                stage="plan_tools",
                message="工具已规划。",
                request_id="request-a",
                run_id="run-a",
                session_id="session-a",
            ),
        ),
    )


if __name__ == "__main__":
    unittest.main()
