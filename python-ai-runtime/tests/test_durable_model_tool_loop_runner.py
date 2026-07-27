"""Durable model-selected tool loop regression tests.

The tests keep the two most important commercial safety properties explicit:
successful tool feedback may re-enter the model, while an approval gate must
pause the loop before another model invocation.  Progress events are asserted
as part of the contract because the UI consumes them incrementally.
"""

from __future__ import annotations

import os
import sys
import unittest
from dataclasses import replace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_execution.durable_model_tool_loop_runner import (
    AgentDurableModelToolLoopRunner,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import (
    AgentLoopControlPolicy,
    AgentLoopControlPolicyEvaluator,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnResult
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)


class AgentDurableModelToolLoopRunnerTest(unittest.TestCase):
    """Verify bounded continuation, progress streaming and approval suspension."""

    def test_successful_feedback_reenters_model_and_streams_each_durable_step(self) -> None:
        model = _SecondTurnOrchestrator()
        streamed: list[AgentRuntimeEvent] = []
        runner = self._runner(
            feedback=_feedback(ToolExecutionFeedbackStatus.SUCCEEDED),
            second_turn=model,
        )

        result = runner.run(
            request=self._request(),
            plan=self._plan(),
            first_model_turn=_model_turn(self._tool_plan()),
            progress_event_sink=streamed.append,
        )

        self.assertEqual("MODEL_COMPLETED_WITHOUT_MORE_TOOLS", result.stopped_reason)
        self.assertEqual(1, model.call_count)
        self.assertEqual("session-001", result.turns[0].session_id)
        self.assertEqual("run-001", result.turns[0].run_id)
        self.assertEqual(
            [
                AgentRuntimeEventType.TOOL_PLANNED,
                AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,
                AgentRuntimeEventType.MODEL_SECOND_TURN_COMPLETED,
            ],
            [event.event_type for event in streamed],
        )

    def test_waiting_approval_pauses_before_another_model_turn(self) -> None:
        model = _SecondTurnOrchestrator()
        streamed: list[AgentRuntimeEvent] = []
        runner = AgentDurableModelToolLoopRunner(
            plan_ingestion_client=_PlanIngestionClient(),
            feedback_collector=_FeedbackCollector(_feedback(ToolExecutionFeedbackStatus.WAITING_APPROVAL)),
            loop_control_evaluator=AgentLoopControlPolicyEvaluator(
                AgentLoopControlPolicy(max_total_tokens=1)
            ),
            second_turn_orchestrator=model,
        )

        result = runner.run(
            request=self._request(),
            plan=self._plan(),
            first_model_turn=_model_turn(self._tool_plan(requires_approval=True)),
            progress_event_sink=streamed.append,
        )

        self.assertEqual("HUMAN_TAKEOVER_REQUIRED", result.stopped_reason)
        self.assertEqual(0, model.call_count)
        self.assertEqual("require_human_takeover", result.turns[0].loop_action)
        self.assertEqual(2, len(streamed))
        self.assertEqual(
            AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,
            streamed[-1].event_type,
        )

    def test_pending_feedback_pauses_before_another_model_turn(self) -> None:
        model = _SecondTurnOrchestrator()
        runner = self._runner(
            feedback=_feedback(ToolExecutionFeedbackStatus.PENDING),
            second_turn=model,
        )

        result = runner.run(
            request=self._request(),
            plan=self._plan(),
            first_model_turn=_model_turn(self._tool_plan()),
        )

        self.assertEqual("WAITING_CONTROL_PLANE", result.stopped_reason)
        self.assertEqual(0, model.call_count)
        self.assertEqual("wait_for_control_plane", result.turns[0].loop_action)

    def test_configured_turn_budget_can_reach_fifth_tool_batch(self) -> None:
        model = _ChainedSecondTurnOrchestrator(
            tool_batches=tuple(
                (self._tool_plan(tool_name=f"datasource.read.stage-{index}"),)
                for index in range(2, 6)
            )
        )
        runner = AgentDurableModelToolLoopRunner(
            plan_ingestion_client=_PlanIngestionClient(),
            feedback_collector=_FeedbackCollector(_feedback(ToolExecutionFeedbackStatus.SUCCEEDED)),
            loop_control_evaluator=AgentLoopControlPolicyEvaluator(
                AgentLoopControlPolicy(max_tool_steps=6, max_second_turns=6)
            ),
            second_turn_orchestrator=model,
            max_model_turns=6,
        )

        result = runner.run(
            request=self._request(),
            plan=self._plan(),
            first_model_turn=_model_turn(self._tool_plan(tool_name="datasource.read.stage-1")),
        )

        self.assertEqual("MODEL_COMPLETED_WITHOUT_MORE_TOOLS", result.stopped_reason)
        self.assertEqual(5, len(result.turns))
        self.assertEqual(
            [f"datasource.read.stage-{index}" for index in range(1, 6)],
            [turn.submitted_tool_names[0] for turn in result.turns],
        )

    def test_configured_next_turn_estimate_is_enforced_by_total_token_budget(self) -> None:
        """The runner must reserve a realistic next prompt before another call."""

        runner = AgentDurableModelToolLoopRunner(
            plan_ingestion_client=_PlanIngestionClient(),
            feedback_collector=_FeedbackCollector(_feedback(ToolExecutionFeedbackStatus.SUCCEEDED)),
            loop_control_evaluator=AgentLoopControlPolicyEvaluator(
                AgentLoopControlPolicy(max_total_tokens=9000)
            ),
            second_turn_orchestrator=_SecondTurnOrchestrator(),
            estimated_next_turn_tokens=8192,
        )

        result = runner.run(
            request=self._request(),
            plan=self._plan(),
            first_model_turn=AgentSecondTurnResult(
                executed=True,
                allowed=True,
                action="continue_with_tools",
                summary="continue",
                prompt_tokens=900,
                completion_tokens=100,
                follow_up_tool_plans=(self._tool_plan(),),
            ),
        )

        self.assertEqual("STOP_BUDGET_EXCEEDED", result.stopped_reason)
        self.assertEqual("stop_budget_exceeded", result.turns[0].loop_action)

    def test_each_model_turn_receives_accumulated_cross_run_evidence(self) -> None:
        """逐表元数据分批执行时，后续模型不能遗忘前一批工具结果。"""

        catalog = ToolPlan(
            tool_name="datasource.source.catalog.search",
            reason="resolve source",
            governance_hints={"modelToolCallId": "call-catalog"},
        )
        connection = ToolPlan(
            tool_name="datasource.source.connection.test",
            reason="test source",
            governance_hints={"modelToolCallId": "call-connection"},
        )
        metadata = ToolPlan(
            tool_name="datasource.source.metadata.read",
            reason="read source table",
            governance_hints={"modelToolCallId": "call-metadata"},
        )
        initial_plan = replace(self._plan(), tool_plans=(catalog,))
        initial_feedback = _feedback_for_plan(initial_plan)
        model = _EvidenceAccumulatingSecondTurnOrchestrator(metadata)
        runner = AgentDurableModelToolLoopRunner(
            plan_ingestion_client=_PlanIngestionClient(),
            feedback_collector=_DynamicFeedbackCollector(),
            loop_control_evaluator=AgentLoopControlPolicyEvaluator(),
            second_turn_orchestrator=model,
        )

        result = runner.run(
            request=self._request(),
            plan=initial_plan,
            first_model_turn=_model_turn(connection),
            initial_feedback=initial_feedback,
        )

        self.assertEqual("MODEL_COMPLETED_WITHOUT_MORE_TOOLS", result.stopped_reason)
        self.assertEqual(
            [
                (
                    "datasource.source.catalog.search",
                    "datasource.source.connection.test",
                ),
                (
                    "datasource.source.catalog.search",
                    "datasource.source.connection.test",
                    "datasource.source.metadata.read",
                ),
            ],
            model.observed_plan_tools,
        )
        self.assertEqual(model.observed_plan_tools, model.observed_feedback_tools)
        self.assertEqual(3, result.latest_feedback.expected_tool_call_count)
        self.assertEqual(3, len(result.latest_feedback.feedback_items))

    @staticmethod
    def _runner(
        *,
        feedback: AgentControlPlaneFeedbackSnapshot,
        second_turn: "_SecondTurnOrchestrator",
    ) -> AgentDurableModelToolLoopRunner:
        return AgentDurableModelToolLoopRunner(
            plan_ingestion_client=_PlanIngestionClient(),
            feedback_collector=_FeedbackCollector(feedback),
            loop_control_evaluator=AgentLoopControlPolicyEvaluator(),
            second_turn_orchestrator=second_turn,
        )

    @staticmethod
    def _request() -> AgentRequest:
        return AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="检查导入任务并在必要时提出修复。",
            variables={"sessionId": "browser-session"},
            request_id="request-001",
        )

    @staticmethod
    def _plan() -> AgentPlan:
        return AgentPlan(
            request_id="request-001",
            selected_route=None,
            state_trace=("plan_tools",),
            tool_plans=(),
            requires_human_approval=False,
            response_summary="模型已提出第一批工具。",
        )

    @staticmethod
    def _tool_plan(
        *,
        requires_approval: bool = False,
        tool_name: str = "sync.task.import.dry-run",
    ) -> ToolPlan:
        return ToolPlan(
            tool_name=tool_name,
            reason="先验证导入制品。",
            arguments={"artifactRef": "sync-import-test"},
            requires_human_approval=requires_approval,
            governance_hints={"modelToolCallId": "call-001"},
        )


class _AttachedIngestion:
    def attach_to_plan(self, plan: AgentPlan) -> AgentPlan:
        tool_plans = tuple(
            replace(
                item,
                governance_hints={
                    **item.governance_hints,
                    "agentRuntimeSessionId": "session-001",
                    "agentRuntimeRunId": "run-001",
                },
            )
            for item in plan.tool_plans
        )
        return replace(plan, tool_plans=tool_plans)


class _PlanIngestionClient:
    def ingest(self, request, plan, *, trace_id):
        del request, plan, trace_id
        return _AttachedIngestion()


class _FeedbackCollector:
    def __init__(self, feedback: AgentControlPlaneFeedbackSnapshot) -> None:
        self._feedback = feedback

    def collect(self, plan: AgentPlan) -> AgentControlPlaneFeedbackSnapshot:
        del plan
        return self._feedback


class _SecondTurnOrchestrator:
    def __init__(self) -> None:
        self.call_count = 0

    def run(self, **kwargs) -> AgentSecondTurnResult:
        self.call_count += 1
        plan = kwargs["plan"]
        return AgentSecondTurnResult(
            executed=True,
            allowed=True,
            action="allow_second_turn",
            summary="工具结果有效，当前目标已经完成。",
            prompt_tokens=40,
            completion_tokens=12,
            runtime_events=(
                AgentRuntimeEvent(
                    event_type=AgentRuntimeEventType.MODEL_SECOND_TURN_COMPLETED,
                    stage="invoke_controlled_model_second_turn",
                    message="模型已根据真实工具反馈完成下一轮决策。",
                    request_id=plan.request_id,
                    session_id="session-001",
                    run_id="run-001",
                    sequence=20_001,
                ),
            ),
        )


class _ChainedSecondTurnOrchestrator:
    def __init__(self, *, tool_batches: tuple[tuple[ToolPlan, ...], ...]) -> None:
        self._tool_batches = list(tool_batches)

    def run(self, **kwargs) -> AgentSecondTurnResult:
        del kwargs
        next_tools = self._tool_batches.pop(0) if self._tool_batches else ()
        return _model_turn(*next_tools)


class _EvidenceAccumulatingSecondTurnOrchestrator:
    def __init__(self, metadata: ToolPlan) -> None:
        self._metadata = metadata
        self.observed_plan_tools: list[tuple[str, ...]] = []
        self.observed_feedback_tools: list[tuple[str, ...]] = []

    def run(self, **kwargs) -> AgentSecondTurnResult:
        plan = kwargs["plan"]
        feedback = kwargs["control_plane_feedback"]
        self.observed_plan_tools.append(tuple(item.tool_name for item in plan.tool_plans))
        self.observed_feedback_tools.append(tuple(item.tool_name for item in feedback.feedback_items))
        if len(self.observed_plan_tools) == 1:
            return _model_turn(self._metadata)
        return _model_turn()


class _DynamicFeedbackCollector:
    def collect(self, plan: AgentPlan) -> AgentControlPlaneFeedbackSnapshot:
        return _feedback_for_plan(plan)


def _model_turn(*tool_plans: ToolPlan) -> AgentSecondTurnResult:
    return AgentSecondTurnResult(
        executed=True,
        allowed=True,
        action="allow_second_turn",
        summary="模型选择了下一批工具。",
        prompt_tokens=30,
        completion_tokens=10,
        follow_up_tool_plans=tool_plans,
    )


def _feedback(status: ToolExecutionFeedbackStatus) -> AgentControlPlaneFeedbackSnapshot:
    return AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=1,
        feedback_items=(
            AgentControlPlaneFeedbackItem(
                model_tool_call_id="call-001",
                tool_name="sync.task.import.dry-run",
                status=status,
                summary=f"工具状态：{status.value}",
                audit_id="audit-001",
                run_id="run-001",
            ),
        ),
        missing_tool_call_ids=(),
        status_counts={status.value: 1},
        second_turn_eligible=status is ToolExecutionFeedbackStatus.SUCCEEDED,
        recommended_actions=("根据测试反馈继续。",),
    )


def _feedback_for_plan(plan: AgentPlan) -> AgentControlPlaneFeedbackSnapshot:
    items = tuple(
        AgentControlPlaneFeedbackItem(
            model_tool_call_id=str(item.governance_hints["modelToolCallId"]),
            tool_name=item.tool_name,
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="工具执行成功。",
        )
        for item in plan.tool_plans
    )
    return AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=len(items),
        feedback_items=items,
        missing_tool_call_ids=(),
        status_counts={ToolExecutionFeedbackStatus.SUCCEEDED.value: len(items)},
        second_turn_eligible=True,
        recommended_actions=("继续使用已验证证据。",),
    )


if __name__ == "__main__":
    unittest.main()
