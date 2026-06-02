import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackCollector,
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import AgentLoopControlPolicyEvaluator
from datasmart_ai_runtime.services.agent_plan_ingestion_client import (
    AgentPlanIngestionResult,
    AgentToolAuditReference,
)
from datasmart_ai_runtime.services.agent_runtime_event_feedback import AgentRuntimeEventFeedbackBridge
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnResult
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)


class AgentRuntimeEventFeedbackBridgeTest(unittest.TestCase):
    """验证 runtime-event replay 能否参与受控 Agent loop 决策。

    这些测试覆盖三个关键产品语义：
    - Java 事件已经成功时，可以补齐同步查询缺失的反馈；
    - Java 事件仍是执行中时，不能提前伪造成工具结果；
    - API 响应组装层必须在 loop policy 之前应用事件反馈桥，否则二轮推理仍会被旧快照阻断。
    """

    def test_replay_terminal_tool_event_fills_missing_feedback(self) -> None:
        bridge = AgentRuntimeEventFeedbackBridge(
            (FakeReplaySource((self._tool_event("SUCCEEDED", sequence=9),)),)
        )

        augmentation = bridge.augment(
            request=self._request(),
            plan=self._plan(),
            snapshot=self._missing_snapshot(),
        )

        snapshot = augmentation.snapshot
        self.assertEqual(1, augmentation.derived_feedback_count)
        self.assertEqual((), snapshot.missing_tool_call_ids)
        self.assertTrue(snapshot.second_turn_eligible)
        self.assertEqual({"succeeded": 1}, snapshot.status_counts)
        self.assertEqual("atea-001", snapshot.feedback_items[0].audit_id)
        self.assertTrue(snapshot.feedback_items[0].result["eventDerived"])

    def test_replay_executing_tool_event_keeps_feedback_missing(self) -> None:
        bridge = AgentRuntimeEventFeedbackBridge(
            (FakeReplaySource((self._tool_event("EXECUTING", sequence=8),)),)
        )

        augmentation = bridge.augment(
            request=self._request(),
            plan=self._plan(),
            snapshot=self._missing_snapshot(),
        )

        self.assertEqual(0, augmentation.derived_feedback_count)
        self.assertEqual(("call-001",), augmentation.snapshot.missing_tool_call_ids)
        self.assertFalse(augmentation.snapshot.second_turn_eligible)

    def test_replay_succeeded_event_replaces_waiting_feedback(self) -> None:
        bridge = AgentRuntimeEventFeedbackBridge(
            (FakeReplaySource((self._tool_event("SUCCEEDED", sequence=10),)),)
        )
        waiting_snapshot = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=1,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-001",
                    tool_name="datasource.metadata.read",
                    status=ToolExecutionFeedbackStatus.WAITING_APPROVAL,
                    summary="工具仍在等待审批。",
                    audit_id="atea-001",
                    run_id="agr-001",
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"waiting_approval": 1},
            second_turn_eligible=False,
            recommended_actions=("等待审批。",),
        )

        augmentation = bridge.augment(
            request=self._request(),
            plan=self._plan(),
            snapshot=waiting_snapshot,
        )

        self.assertEqual(1, augmentation.replaced_feedback_count)
        self.assertEqual(ToolExecutionFeedbackStatus.SUCCEEDED, augmentation.snapshot.feedback_items[0].status)
        self.assertTrue(augmentation.snapshot.second_turn_eligible)

    def test_build_plan_response_uses_runtime_event_feedback_before_loop_decision(self) -> None:
        request = self._request()
        orchestrator = FakeOrchestrator(self._plan_without_java_refs())
        collector = AgentControlPlaneFeedbackCollector(EmptyFeedbackProvider())
        bridge = AgentRuntimeEventFeedbackBridge(
            (FakeReplaySource((self._tool_event("SUCCEEDED", sequence=11),)),)
        )

        response = build_plan_response(
            request,
            orchestrator,
            plan_ingestion_client=FakePlanIngestionClient(),
            control_plane_feedback_collector=collector,
            runtime_event_feedback_bridge=bridge,
            loop_control_evaluator=AgentLoopControlPolicyEvaluator(),
            second_turn_orchestrator=FakeSecondTurnOrchestrator(),
        )

        self.assertEqual(1, response["runtimeEventFeedback"]["derivedFeedbackCount"])
        self.assertTrue(response["controlPlaneFeedback"]["secondTurnEligible"])
        self.assertEqual("allow_second_turn", response["agentLoopControl"]["action"])
        self.assertTrue(response["agentSecondTurn"]["executed"])
        self.assertEqual(1, response["agentSecondTurn"]["feedbackCount"])

    @staticmethod
    def _request() -> AgentRequest:
        return AgentRequest(
            tenant_id="10",
            project_id="20",
            actor_id="1001",
            objective="读取数据源元数据并继续生成治理建议。",
            variables={"sessionId": "ags-001"},
        )

    @staticmethod
    def _plan() -> AgentPlan:
        return AgentPlan(
            request_id="req-001",
            selected_route=None,
            state_trace=("receive_goal", "plan_tools"),
            tool_plans=(
                ToolPlan(
                    tool_name="datasource.metadata.read",
                    reason="测试通过 runtime-event replay 补齐工具状态。",
                    arguments={"datasourceId": 1001},
                    governance_hints={
                        "modelToolCallId": "call-001",
                        "agentRuntimeSessionId": "ags-001",
                        "agentRuntimeRunId": "agr-001",
                        "agentRuntimeAuditId": "atea-001",
                    },
                ),
            ),
            requires_human_approval=False,
            response_summary="已生成工具计划。",
            next_actions=("提交 Java 控制面。",),
        )

    @staticmethod
    def _plan_without_java_refs() -> AgentPlan:
        return AgentPlan(
            request_id="req-001",
            selected_route=None,
            state_trace=("receive_goal", "plan_tools"),
            tool_plans=(
                ToolPlan(
                    tool_name="datasource.metadata.read",
                    reason="先由 plan ingestion 写回 Java 审计引用。",
                    arguments={"datasourceId": 1001},
                    governance_hints={"modelToolCallId": "call-001"},
                ),
            ),
            requires_human_approval=False,
            response_summary="已生成工具计划。",
            next_actions=("提交 Java 控制面。",),
        )

    @staticmethod
    def _missing_snapshot() -> AgentControlPlaneFeedbackSnapshot:
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=1,
            feedback_items=(),
            missing_tool_call_ids=("call-001",),
            status_counts={},
            second_turn_eligible=False,
            recommended_actions=("等待 Java 控制面反馈。",),
        )

    @staticmethod
    def _tool_event(current_state: str, sequence: int) -> AgentRuntimeEvent:
        return AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,
            stage="tool_completed" if current_state == "SUCCEEDED" else "tool_executing",
            message=f"Java 工具状态为 {current_state}。",
            severity=AgentRuntimeEventSeverity.INFO,
            tenant_id="10",
            project_id="20",
            actor_id="1001",
            request_id="req-001",
            run_id="agr-001",
            session_id="ags-001",
            sequence=sequence,
            attributes={
                "auditId": "atea-001",
                "toolCode": "datasource.metadata.read",
                "currentState": current_state,
                "javaProjectionIdentityKey": f"event-{current_state.lower()}",
                "javaProjectionSource": "agent-runtime",
            },
        )


class FakeReplaySource:
    source_name = "fake-java-runtime-event-source"

    def __init__(self, events: tuple[AgentRuntimeEvent, ...]) -> None:
        self._events = events
        self.requests = []

    def replay(self, request):
        self.requests.append(request)
        return self._events


class EmptyFeedbackProvider:
    def feedback_for(self, tool_calls, tool_plans):
        return ()


class FakePlanIngestionClient:
    def ingest(self, request_context: AgentRequest, plan: AgentPlan, trace_id: str | None = None):
        return AgentPlanIngestionResult(
            session_id="ags-001",
            run_id="agr-001",
            tool_audit_references=(
                AgentToolAuditReference(
                    model_tool_call_id="call-001",
                    tool_name="datasource.metadata.read",
                    session_id="ags-001",
                    run_id="agr-001",
                    audit_id="atea-001",
                    state="EXECUTING",
                ),
            ),
            raw_response={},
        )


class FakeOrchestrator:
    def __init__(self, plan: AgentPlan) -> None:
        self._plan = plan

    def plan(self, request: AgentRequest) -> AgentPlan:
        return self._plan


class FakeSecondTurnOrchestrator:
    def run(self, *, request, plan, control_plane_feedback, loop_control_decision):
        return AgentSecondTurnResult(
            executed=loop_control_decision.allowed,
            allowed=loop_control_decision.allowed,
            action=loop_control_decision.action.value,
            summary="event feedback second turn",
            feedback_count=len(control_plane_feedback.feedback_items),
        )


if __name__ == "__main__":
    unittest.main()
