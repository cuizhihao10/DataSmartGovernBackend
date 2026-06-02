import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ModelInvocationResult, ToolPlan
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import (
    AgentLoopControlAction,
    AgentLoopControlDecision,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnOrchestrator
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedbackStatus


class AgentSecondTurnOrchestratorTest(unittest.TestCase):
    """验证受控二轮推理编排器。

    这些测试关注的是“策略允许后才调用模型、工具反馈能转成 role=tool 消息、跳过场景不产生隐藏副作用”。
    它们不测试真实 Java 工具执行，因为真实执行继续属于 Java `agent-runtime` 控制面。
    """

    def test_invokes_second_turn_when_loop_policy_allows(self) -> None:
        provider = CapturingProviderRegistry()
        orchestrator = AgentSecondTurnOrchestrator(provider)

        result = orchestrator.run(
            request=self._request(),
            plan=self._plan(),
            control_plane_feedback=self._snapshot(),
            loop_control_decision=self._allow_decision(),
        )

        self.assertTrue(result.executed)
        self.assertEqual("已根据工具结果生成治理总结。", result.summary)
        self.assertEqual(1, len(provider.requests))
        self.assertEqual("none", provider.requests[0].tool_choice)
        self.assertEqual((), provider.requests[0].available_tools)
        self.assertEqual("10", provider.requests[0].provider_metadata["tenantId"])
        self.assertEqual("20", provider.requests[0].provider_metadata["projectId"])
        self.assertEqual("session-001", provider.requests[0].provider_metadata["sessionId"])
        self.assertEqual("tool", provider.requests[0].messages[-1].role)
        self.assertEqual("call-001", provider.requests[0].messages[-1].tool_call_id)
        self.assertIn(
            AgentRuntimeEventType.AGENT_LOOP_CONTROL_DECIDED,
            {event.event_type for event in result.runtime_events},
        )
        self.assertIn(
            AgentRuntimeEventType.MODEL_SECOND_TURN_COMPLETED,
            {event.event_type for event in result.runtime_events},
        )
        feedback_event = next(
            event
            for event in result.runtime_events
            if event.event_type == AgentRuntimeEventType.TOOL_RESULT_FEEDBACK_BUILT
        )
        self.assertEqual(1, feedback_event.attributes["resourceResolutionCount"])
        self.assertEqual(0, feedback_event.attributes["resourceResolutionBlockedCount"])
        self.assertEqual(1, feedback_event.attributes["resourceResolutionModelBlockedCount"])
        self.assertEqual("agent_runtime", feedback_event.attributes["resourceResolutions"][0]["referenceKind"])
        self.assertEqual("audit_only", feedback_event.attributes["resourceResolutions"][0]["contextPolicy"])
        self.assertEqual(1, feedback_event.attributes["resultFilterCount"])
        self.assertEqual("resource_not_allowed_for_model", feedback_event.attributes["resultFilters"][0]["mode"])
        self.assertEqual((6, 7, 8), tuple(event.sequence for event in result.runtime_events))

    def test_skips_without_calling_model_when_policy_blocks(self) -> None:
        provider = CapturingProviderRegistry()
        orchestrator = AgentSecondTurnOrchestrator(provider)

        result = orchestrator.run(
            request=self._request(),
            plan=self._plan(),
            control_plane_feedback=self._snapshot(),
            loop_control_decision=AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.REQUIRE_HUMAN_TAKEOVER,
                reasons=("存在等待审批的工具。",),
                recommended_actions=("展示审批入口。",),
            ),
        )

        self.assertFalse(result.executed)
        self.assertEqual(0, len(provider.requests))
        self.assertEqual("require_human_takeover", result.action)
        self.assertIn(
            AgentRuntimeEventType.MODEL_SECOND_TURN_SKIPPED,
            {event.event_type for event in result.runtime_events},
        )

    def test_skips_when_feedback_messages_are_incomplete(self) -> None:
        provider = CapturingProviderRegistry()
        orchestrator = AgentSecondTurnOrchestrator(provider)
        snapshot = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=1,
            feedback_items=(),
            missing_tool_call_ids=("call-001",),
            status_counts={},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        result = orchestrator.run(
            request=self._request(),
            plan=self._plan(),
            control_plane_feedback=snapshot,
            loop_control_decision=self._allow_decision(),
        )

        self.assertFalse(result.executed)
        self.assertEqual(0, len(provider.requests))
        self.assertEqual(("call-001",), result.missing_feedback_call_ids)
        self.assertTrue(any(event.event_type == AgentRuntimeEventType.TOOL_RESULT_FEEDBACK_BUILT for event in result.runtime_events))

    def test_records_auto_execution_summary_event_when_snapshot_contains_batch_summary(self) -> None:
        provider = CapturingProviderRegistry()
        orchestrator = AgentSecondTurnOrchestrator(provider)
        snapshot = self._snapshot_with_auto_execution_summary()

        result = orchestrator.run(
            request=self._request(),
            plan=self._plan(),
            control_plane_feedback=snapshot,
            loop_control_decision=self._allow_decision(),
        )

        auto_event = next(
            event
            for event in result.runtime_events
            if event.event_type == AgentRuntimeEventType.TOOL_AUTO_EXECUTION_SYNC_COMPLETED
        )
        self.assertTrue(result.executed)
        self.assertEqual(1, auto_event.attributes["executedCount"])
        self.assertEqual(0, auto_event.attributes["failedCount"])
        self.assertEqual("EXECUTED", auto_event.attributes["items"][0]["action"])
        self.assertEqual((6, 7, 8, 9), tuple(event.sequence for event in result.runtime_events))

    @staticmethod
    def _request() -> AgentRequest:
        return AgentRequest(
            tenant_id="10",
            project_id="20",
            actor_id="user-a",
            objective="请读取数据源元数据并给出下一步治理建议。",
            variables={"sessionId": "session-001"},
        )

    @staticmethod
    def _plan() -> AgentPlan:
        return AgentPlan(
            request_id="req-001",
            selected_route=default_model_routes()[0],
            state_trace=("receive_goal", "plan_tools"),
            tool_plans=(
                ToolPlan(
                    tool_name="datasource.metadata.read",
                    reason="二轮推理需要使用控制面反馈中的元数据摘要。",
                    arguments={"datasourceId": 1001},
                    governance_hints={
                        "modelToolCallId": "call-001",
                        "agentRuntimeSessionId": "ags-001",
                        "agentRuntimeRunId": "agr-001",
                    },
                ),
            ),
            requires_human_approval=False,
            response_summary="已生成读取元数据的工具计划。",
            next_actions=("提交 Java 控制面执行。",),
            runtime_events=tuple(
                AgentRuntimeEvent(
                    event_type=AgentRuntimeEventType.TOOL_PLANNED,
                    stage=f"existing_{index}",
                    message="已有计划事件。",
                    sequence=index,
                    request_id="req-001",
                    run_id="local-run",
                    session_id="session-001",
                )
                for index in range(1, 6)
            ),
        )

    @staticmethod
    def _snapshot() -> AgentControlPlaneFeedbackSnapshot:
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=1,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-001",
                    tool_name="datasource.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="已读取 12 张表和 120 个字段。",
                    result={"tableCount": 12, "columnCount": 120, "datasourceId": "ds-sensitive"},
                    audit_id="atea-001",
                    run_id="agr-001",
                    output_ref="agent-runtime://tool-results/call-001",
                    sensitive_fields=("datasourceId",),
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 1},
            second_turn_eligible=True,
            recommended_actions=("允许二轮推理。",),
        )

    @staticmethod
    def _snapshot_with_auto_execution_summary() -> AgentControlPlaneFeedbackSnapshot:
        snapshot = AgentSecondTurnOrchestratorTest._snapshot()
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=snapshot.expected_tool_call_count,
            feedback_items=snapshot.feedback_items,
            missing_tool_call_ids=snapshot.missing_tool_call_ids,
            status_counts=snapshot.status_counts,
            second_turn_eligible=snapshot.second_turn_eligible,
            recommended_actions=snapshot.recommended_actions,
            auto_execution_summary={
                "sessionId": "session-001",
                "runId": "agr-001",
                "dryRun": False,
                "requestedLimit": 2,
                "effectiveLimit": 2,
                "executedCount": 1,
                "failedCount": 0,
                "skippedCount": 0,
                "items": (
                    {
                        "auditId": "atea-001",
                        "toolCode": "datasource.metadata.read",
                        "policyDecision": "AUTO_EXECUTABLE",
                        "action": "EXECUTED",
                        "reason": "测试自动执行成功。",
                    },
                ),
            },
        )

    @staticmethod
    def _allow_decision() -> AgentLoopControlDecision:
        return AgentLoopControlDecision(
            allowed=True,
            action=AgentLoopControlAction.ALLOW_SECOND_TURN,
            reasons=("控制面反馈完整。",),
            recommended_actions=("进入受控二轮推理。",),
        )


class CapturingProviderRegistry:
    """测试用模型 Provider，只记录请求并返回固定二轮摘要。"""

    def __init__(self) -> None:
        self.requests = []

    def invoke(self, request):
        self.requests.append(request)
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content="已根据工具结果生成治理总结。",
            prompt_tokens=101,
            completion_tokens=33,
        )


if __name__ == "__main__":
    unittest.main()
