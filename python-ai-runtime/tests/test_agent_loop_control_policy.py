import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import (
    AgentLoopControlAction,
    AgentLoopControlPolicy,
    AgentLoopControlPolicyEvaluator,
    AgentLoopControlState,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedbackStatus


class AgentLoopControlPolicyEvaluatorTest(unittest.TestCase):
    """验证受控 Agent loop 的安全推进策略。

    这些测试刻意覆盖“允许继续”和“必须停住”的边界。真实 Agent 产品最怕的不是不能继续，而是
    在审批、超时、预算不足或反馈缺失时仍然继续自动推理，最终造成错误执行、成本失控或审计断链。
    """

    def test_allows_second_turn_when_feedback_is_complete_and_policy_budget_allows(self) -> None:
        decision = AgentLoopControlPolicyEvaluator().evaluate(
            self._snapshot(statuses=(ToolExecutionFeedbackStatus.SUCCEEDED,))
        )

        self.assertTrue(decision.allowed)
        self.assertEqual(AgentLoopControlAction.ALLOW_SECOND_TURN, decision.action)

    def test_requires_human_takeover_when_feedback_waits_for_approval(self) -> None:
        decision = AgentLoopControlPolicyEvaluator().evaluate(
            self._snapshot(statuses=(ToolExecutionFeedbackStatus.WAITING_APPROVAL,))
        )

        self.assertFalse(decision.allowed)
        self.assertEqual(AgentLoopControlAction.REQUIRE_HUMAN_TAKEOVER, decision.action)
        self.assertTrue(any("审批" in action for action in decision.recommended_actions))

    def test_waits_for_control_plane_when_feedback_is_missing_before_timeout(self) -> None:
        decision = AgentLoopControlPolicyEvaluator().evaluate(
            self._snapshot(statuses=(), missing_ids=("call-001",)),
            AgentLoopControlState(waiting_control_plane_seconds=10),
        )

        self.assertFalse(decision.allowed)
        self.assertEqual(AgentLoopControlAction.WAIT_FOR_CONTROL_PLANE, decision.action)

    def test_stops_when_waiting_for_control_plane_exceeds_timeout(self) -> None:
        decision = AgentLoopControlPolicyEvaluator(
            AgentLoopControlPolicy(tool_wait_timeout_seconds=15)
        ).evaluate(
            self._snapshot(statuses=(), missing_ids=("call-001",)),
            AgentLoopControlState(waiting_control_plane_seconds=16),
        )

        self.assertFalse(decision.allowed)
        self.assertEqual(AgentLoopControlAction.STOP_TIMEOUT, decision.action)

    def test_stops_when_token_budget_would_be_exceeded(self) -> None:
        decision = AgentLoopControlPolicyEvaluator(
            AgentLoopControlPolicy(max_total_tokens=100)
        ).evaluate(
            self._snapshot(statuses=(ToolExecutionFeedbackStatus.SUCCEEDED,)),
            AgentLoopControlState(consumed_tokens=80, estimated_next_turn_tokens=30),
        )

        self.assertFalse(decision.allowed)
        self.assertEqual(AgentLoopControlAction.STOP_BUDGET_EXCEEDED, decision.action)

    def test_stops_when_second_turn_limit_is_reached(self) -> None:
        decision = AgentLoopControlPolicyEvaluator(
            AgentLoopControlPolicy(max_second_turns=1)
        ).evaluate(
            self._snapshot(statuses=(ToolExecutionFeedbackStatus.SUCCEEDED,)),
            AgentLoopControlState(completed_second_turns=1),
        )

        self.assertFalse(decision.allowed)
        self.assertEqual(AgentLoopControlAction.STOP_STEP_LIMIT, decision.action)

    def test_can_block_failed_feedback_when_tenant_policy_is_strict(self) -> None:
        decision = AgentLoopControlPolicyEvaluator(
            AgentLoopControlPolicy(allow_failed_feedback_second_turn=False)
        ).evaluate(self._snapshot(statuses=(ToolExecutionFeedbackStatus.FAILED,)))

        self.assertFalse(decision.allowed)
        self.assertEqual(AgentLoopControlAction.WAIT_FOR_CONTROL_PLANE, decision.action)

    def _snapshot(
        self,
        *,
        statuses: tuple[ToolExecutionFeedbackStatus, ...],
        missing_ids: tuple[str, ...] = (),
    ) -> AgentControlPlaneFeedbackSnapshot:
        items = tuple(
            AgentControlPlaneFeedbackItem(
                model_tool_call_id=f"call-{index}",
                tool_name="datasource.metadata.read",
                status=status,
                summary=f"工具状态：{status.value}",
                audit_id=f"atea-{index}",
                run_id="agr-001",
            )
            for index, status in enumerate(statuses, start=1)
        )
        status_counts: dict[str, int] = {}
        for item in items:
            status_counts[item.status.value] = status_counts.get(item.status.value, 0) + 1
        expected_count = len(items) + len(missing_ids)
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=expected_count,
            feedback_items=items,
            missing_tool_call_ids=missing_ids,
            status_counts=status_counts,
            second_turn_eligible=expected_count > 0
            and not missing_ids
            and status_counts.get(ToolExecutionFeedbackStatus.WAITING_APPROVAL.value, 0) == 0,
            recommended_actions=("测试快照。",),
        )


if __name__ == "__main__":
    unittest.main()
