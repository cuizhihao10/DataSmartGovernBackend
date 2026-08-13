import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_plan_response
from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ModelToolCall,
    ToolParameterIssue,
    ToolParameterIssueAction,
    ToolParameterValidationResult,
    ToolPlan,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackCollector
from datasmart_ai_runtime.services.agent_plan_ingestion_client import (
    AgentPlanIngestionResult,
    AgentToolAuditReference,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import AgentLoopControlPolicyEvaluator
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnResult
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)


class AgentControlPlaneFeedbackCollectorTest(unittest.TestCase):
    """验证计划接入 Java 后的控制面反馈快照。

    这些测试聚焦“状态可见性”，而不是自动执行。真实商业化 Agent 需要先判断工具审计当前是成功、
    失败、等待审批还是暂未返回，再决定是否进入二轮模型、等待审批、触发重试或展示诊断信息。
    """

    def test_collect_marks_second_turn_eligible_when_all_feedback_is_terminal(self) -> None:
        collector = AgentControlPlaneFeedbackCollector(
            FakeFeedbackProvider({"call-001": ToolExecutionFeedbackStatus.SUCCEEDED})
        )

        snapshot = collector.collect(self._plan(self._tool_plan("call-001")))

        self.assertEqual(1, snapshot.expected_tool_call_count)
        self.assertEqual({"succeeded": 1}, snapshot.status_counts)
        self.assertTrue(snapshot.second_turn_eligible)
        self.assertEqual((), snapshot.missing_tool_call_ids)
        self.assertEqual("atea-call-001", snapshot.feedback_items[0].audit_id)

    def test_collect_blocks_second_turn_when_tool_waits_for_human_approval(self) -> None:
        collector = AgentControlPlaneFeedbackCollector(
            FakeFeedbackProvider({"call-approval": ToolExecutionFeedbackStatus.WAITING_APPROVAL})
        )

        snapshot = collector.collect(self._plan(self._tool_plan("call-approval")))

        self.assertFalse(snapshot.second_turn_eligible)
        self.assertEqual({"waiting_approval": 1}, snapshot.status_counts)
        self.assertTrue(any("审批" in action for action in snapshot.recommended_actions))

    def test_collect_blocks_second_turn_when_tool_is_still_pending(self) -> None:
        collector = AgentControlPlaneFeedbackCollector(
            FakeFeedbackProvider({"call-pending": ToolExecutionFeedbackStatus.PENDING})
        )

        snapshot = collector.collect(self._plan(self._tool_plan("call-pending")))

        self.assertFalse(snapshot.second_turn_eligible)
        self.assertEqual({"pending": 1}, snapshot.status_counts)
        self.assertTrue(any("执行中" in action for action in snapshot.recommended_actions))

    def test_collect_reports_missing_feedback_for_diagnostics(self) -> None:
        collector = AgentControlPlaneFeedbackCollector(FakeFeedbackProvider({}))

        snapshot = collector.collect(self._plan(self._tool_plan("call-missing")))

        self.assertFalse(snapshot.second_turn_eligible)
        self.assertEqual(("call-missing",), snapshot.missing_tool_call_ids)
        self.assertTrue(any("缺失" in action or "尚未拿到" in action for action in snapshot.recommended_actions))

    def test_bounded_metadata_wait_refreshes_pending_java_fact(self) -> None:
        """Java 元数据 worker 稍晚完成时，bridge 应看到第二次真实反馈。"""

        clock = FakeClock()
        provider = SequencedFeedbackProvider(
            (
                {
                    "call-source": ToolExecutionFeedbackStatus.SUCCEEDED,
                    "call-target": ToolExecutionFeedbackStatus.PENDING,
                },
                {
                    "call-source": ToolExecutionFeedbackStatus.SUCCEEDED,
                    "call-target": ToolExecutionFeedbackStatus.SUCCEEDED,
                },
            )
        )
        collector = AgentControlPlaneFeedbackCollector(
            provider,
            metadata_wait_timeout_seconds=1.0,
            metadata_poll_interval_seconds=0.25,
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )

        snapshot = collector.collect_with_bounded_metadata_wait(
            self._plan(
                self._metadata_tool_plan("call-source", "datasource.source.metadata.read"),
                self._metadata_tool_plan("call-target", "datasource.target.metadata.read"),
            )
        )

        self.assertEqual(2, provider.calls)
        self.assertEqual(
            [("call-source", "call-target"), ("call-target",)],
            provider.requested_call_ids,
        )
        self.assertEqual({"succeeded": 2}, snapshot.status_counts)
        self.assertTrue(snapshot.second_turn_eligible)
        self.assertEqual([0.25], clock.sleeps)

    def test_bounded_metadata_wait_keeps_pending_fact_after_deadline(self) -> None:
        """超出短等待预算时必须保持 pending，不能伪造成功。"""

        clock = FakeClock()
        provider = SequencedFeedbackProvider(
            ({
                "call-source": ToolExecutionFeedbackStatus.SUCCEEDED,
                "call-target": ToolExecutionFeedbackStatus.PENDING,
            },)
        )
        collector = AgentControlPlaneFeedbackCollector(
            provider,
            metadata_wait_timeout_seconds=0.5,
            metadata_poll_interval_seconds=0.25,
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )

        snapshot = collector.collect_with_bounded_metadata_wait(
            self._plan(
                self._metadata_tool_plan("call-source", "datasource.source.metadata.read"),
                self._metadata_tool_plan("call-target", "datasource.target.metadata.read"),
            )
        )

        self.assertEqual(3, provider.calls)
        self.assertEqual({"succeeded": 1, "pending": 1}, snapshot.status_counts)
        self.assertFalse(snapshot.second_turn_eligible)
        self.assertLessEqual(clock.now, 0.5)

    def test_bounded_metadata_wait_stops_on_failure_or_approval(self) -> None:
        """终态失败或审批等待不能被轮询隐藏，也不能被自动批准。"""

        clock = FakeClock()
        provider = SequencedFeedbackProvider(
            (
                {"call-target": ToolExecutionFeedbackStatus.PENDING},
                {"call-target": ToolExecutionFeedbackStatus.WAITING_APPROVAL},
            )
        )
        collector = AgentControlPlaneFeedbackCollector(
            provider,
            metadata_wait_timeout_seconds=10.0,
            metadata_poll_interval_seconds=0.25,
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )

        snapshot = collector.collect_with_bounded_metadata_wait(
            self._plan(self._metadata_tool_plan("call-target", "datasource.target.metadata.read"))
        )

        self.assertEqual(2, provider.calls)
        self.assertEqual({"waiting_approval": 1}, snapshot.status_counts)
        self.assertEqual([0.25], clock.sleeps)

    def test_bounded_metadata_wait_does_not_require_non_metadata_write_nodes(self) -> None:
        """元数据可完成时只刷新元数据，草稿/发布/运行节点仍由各自门禁决定。"""

        clock = FakeClock()
        provider = SequencedFeedbackProvider(
            (
                {
                    "call-target": ToolExecutionFeedbackStatus.PENDING,
                    "call-draft": ToolExecutionFeedbackStatus.PENDING,
                },
                {
                    "call-target": ToolExecutionFeedbackStatus.SUCCEEDED,
                    "call-draft": ToolExecutionFeedbackStatus.PENDING,
                },
            )
        )
        collector = AgentControlPlaneFeedbackCollector(
            provider,
            metadata_wait_timeout_seconds=1.0,
            metadata_poll_interval_seconds=0.25,
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )

        snapshot = collector.collect_with_bounded_metadata_wait(
            self._plan(
                self._metadata_tool_plan("call-target", "datasource.target.metadata.read"),
                self._metadata_tool_plan("call-draft", "sync.task.draft.save"),
            )
        )

        self.assertEqual(2, provider.calls)
        self.assertEqual(
            [("call-target", "call-draft"), ("call-target",)],
            provider.requested_call_ids,
        )
        by_call_id = {item.model_tool_call_id: item for item in snapshot.feedback_items}
        self.assertEqual(ToolExecutionFeedbackStatus.SUCCEEDED, by_call_id["call-target"].status)
        self.assertEqual(ToolExecutionFeedbackStatus.PENDING, by_call_id["call-draft"].status)

    def test_bounded_metadata_wait_preserves_initial_auto_execution_summary(self) -> None:
        """元数据子查询清空 Provider 摘要时，响应仍保留首轮真实自动执行事实。"""

        clock = FakeClock()
        provider = SequencedFeedbackProvider(
            (
                {"call-target": ToolExecutionFeedbackStatus.PENDING},
                {"call-target": ToolExecutionFeedbackStatus.SUCCEEDED},
            ),
            auto_execution_summaries=(
                {"executedCount": 1, "selectedAuditIds": ("audit-call-target",)},
                None,
            ),
        )
        collector = AgentControlPlaneFeedbackCollector(
            provider,
            metadata_wait_timeout_seconds=1.0,
            metadata_poll_interval_seconds=0.25,
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )

        snapshot = collector.collect_with_bounded_metadata_wait(
            self._plan(self._metadata_tool_plan("call-target", "datasource.target.metadata.read"))
        )

        self.assertEqual(
            {"executedCount": 1, "selectedAuditIds": ("audit-call-target",)},
            snapshot.auto_execution_summary,
        )

    def _metadata_tool_plan(self, call_id: str, tool_name: str) -> ToolPlan:
        return ToolPlan(
            tool_name=tool_name,
            reason="测试用 Java 元数据读取。",
            arguments={"datasourceId": 1001},
            governance_hints={"modelToolCallId": call_id},
        )

    def test_build_plan_response_exposes_control_plane_feedback_after_ingestion(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="20",
            actor_id="user-a",
            objective="读取数据源元数据并生成治理建议。",
        )
        plan = self._plan(self._tool_plan("call-001"))
        orchestrator = FakeOrchestrator(plan)
        ingestion_client = FakePlanIngestionClient()
        collector = AgentControlPlaneFeedbackCollector(
            FakeFeedbackProvider({"call-001": ToolExecutionFeedbackStatus.SUCCEEDED})
        )

        response = build_plan_response(
            request,
            orchestrator,
            plan_ingestion_client=ingestion_client,
            control_plane_feedback_collector=collector,
            loop_control_evaluator=AgentLoopControlPolicyEvaluator(),
            second_turn_orchestrator=FakeSecondTurnOrchestrator(),
        )

        feedback = response["controlPlaneFeedback"]
        self.assertEqual(1, feedback["feedbackCount"])
        self.assertTrue(feedback["secondTurnEligible"])
        self.assertEqual("atea-call-001", feedback["items"][0]["auditId"])
        self.assertTrue(response["agentLoopControl"]["allowed"])
        self.assertEqual("allow_second_turn", response["agentLoopControl"]["action"])
        self.assertTrue(response["agentSecondTurn"]["executed"])
        self.assertEqual("fake second turn", response["agentSecondTurn"]["summary"])
        self.assertEqual("ags-001", response["plan"]["tool_plans"][0]["governance_hints"]["agentRuntimeSessionId"])

    def test_ready_read_only_probe_is_ingested_while_downstream_draft_waits_for_clarification(self) -> None:
        """下游草稿缺参不能阻止目录探测，否则 Agent 无法用真实事实补齐缺项。"""

        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1004",
            objective="按已说明的数据源和同名表创建全量同步任务。",
        )
        catalog = ToolPlan(
            tool_name="datasource.source.catalog.search",
            reason="先解析当前项目中的真实源端数据源。",
            arguments={"keyword": "mysql2pgsql_test_0709_source", "datasourceType": "MYSQL"},
            governance_hints={"modelToolCallId": "call-source-catalog"},
        )
        incomplete_draft = ToolPlan(
            tool_name="sync.task.draft.save",
            reason="元数据齐备后保存任务草稿。",
            arguments={"sourceDatasourceId": 27, "targetDatasourceId": 28},
            parameter_validation=ToolParameterValidationResult(
                can_execute=False,
                can_create_draft=True,
                issues=(
                    ToolParameterIssue(
                        parameter_name="objectMappings",
                        expected_type="array",
                        action=ToolParameterIssueAction.MUST_CLARIFY,
                        message="对象映射需由真实元数据补齐。",
                    ),
                ),
            ),
            governance_hints={"modelToolCallId": "call-draft"},
        )
        ingestion_client = RecordingPlanIngestionClient()
        feedback_collector = RecordingControlPlaneFeedbackCollector()

        response = build_plan_response(
            request,
            FakeOrchestrator(self._plan(catalog, incomplete_draft)),
            plan_ingestion_client=ingestion_client,
            control_plane_feedback_collector=feedback_collector,
        )

        self.assertEqual(
            ("datasource.source.catalog.search",),
            tuple(item.tool_name for item in ingestion_client.ingested_plan.tool_plans),
        )
        self.assertEqual(
            ("datasource.source.catalog.search",),
            tuple(item.tool_name for item in feedback_collector.collected_plan.tool_plans),
        )
        self.assertEqual(2, len(response["plan"]["tool_plans"]))
        self.assertEqual("ags-partial", response["controlPlaneIngestion"]["sessionId"])

    def test_complete_sync_lifecycle_is_ingested_as_one_confirmable_run(self) -> None:
        """完整任务只需一次确认，不能因自动执行预算拆成依赖模型续跑的多批 Run。"""

        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="把两张客户表从 MySQL 全量同步到 PostgreSQL。",
        )
        tool_names = (
            "datasource.source.connection.test",
            "datasource.target.connection.test",
            "datasource.source.metadata.read",
            "datasource.target.metadata.read",
            "sync.task.draft.save",
            "sync.task.precheck",
            "sync.task.publish",
            "sync.task.run",
            "sync.execution.status",
        )
        tool_plans = tuple(
            ToolPlan(
                tool_name=tool_name,
                reason=f"execute {tool_name}",
                arguments={
                    "sourceDatasourceId": 27,
                    "targetDatasourceId": 28,
                    "objectMappings": [{
                        "sourceObjectName": "fs_test_customer_source",
                        "targetSchemaName": "public",
                        "targetObjectName": "fs_test_customer_source",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                    "syncMode": "FULL",
                } if tool_name == "sync.task.draft.save" else {},
                requires_human_approval=tool_name in {
                    "sync.task.draft.save",
                    "sync.task.publish",
                    "sync.task.run",
                },
                governance_hints={"modelToolCallId": f"call-{index}"},
            )
            for index, tool_name in enumerate(tool_names, start=1)
        )
        ingestion_client = RecordingPlanIngestionClient()

        response = build_plan_response(
            request,
            FakeOrchestrator(self._plan(*tool_plans)),
            plan_ingestion_client=ingestion_client,
        )

        self.assertEqual(
            tool_names,
            tuple(item.tool_name for item in ingestion_client.ingested_plan.tool_plans),
        )
        self.assertEqual(9, response["controlPlaneIngestion"]["toolAuditCount"])

    def _tool_plan(self, call_id: str) -> ToolPlan:
        return ToolPlan(
            tool_name="datasource.metadata.read",
            reason="模型需要读取元数据作为后续治理计划依据。",
            arguments={"datasourceId": 1001},
            governance_hints={"modelToolCallId": call_id},
        )

    def _plan(self, *tool_plans: ToolPlan) -> AgentPlan:
        return AgentPlan(
            request_id="req-001",
            selected_route=None,
            state_trace=("receive_goal", "plan_tools"),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="已生成工具计划。",
            next_actions=("提交 Java 控制面。",),
        )


class FakeFeedbackProvider:
    """测试用反馈 Provider，用最小数据模拟 Java 查询结果。"""

    def __init__(self, statuses: dict[str, ToolExecutionFeedbackStatus]) -> None:
        self._statuses = statuses

    def feedback_for(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        tool_plans: tuple[ToolPlan, ...],
    ) -> tuple[ToolExecutionFeedback, ...]:
        feedback: list[ToolExecutionFeedback] = []
        for tool_call in tool_calls:
            if not tool_call.call_id or tool_call.call_id not in self._statuses:
                continue
            status = self._statuses[tool_call.call_id]
            feedback.append(
                ToolExecutionFeedback(
                    tool_call_id=tool_call.call_id,
                    tool_name=tool_call.name,
                    status=status,
                    summary=f"{tool_call.name} 当前状态为 {status.value}",
                    audit_id=f"atea-{tool_call.call_id}",
                    run_id="agr-001",
                    output_ref=f"agent-runtime://tool-results/{tool_call.call_id}",
                )
            )
        return tuple(feedback)


class SequencedFeedbackProvider:
    """按查询次数返回状态序列，模拟 Java worker 的异步状态变化。"""

    def __init__(
        self,
        states: tuple[dict[str, ToolExecutionFeedbackStatus], ...],
        *,
        auto_execution_summaries: tuple[dict[str, object] | None, ...] = (),
    ) -> None:
        self._states = states
        self._auto_execution_summaries = auto_execution_summaries
        self._last_auto_execution_summary: FakeAutoExecutionSummary | None = None
        self.calls = 0
        self.requested_call_ids: list[tuple[str, ...]] = []

    @property
    def last_auto_execution_summary(self) -> "FakeAutoExecutionSummary | None":
        """Expose the summary produced by the latest simulated Provider call."""

        return self._last_auto_execution_summary

    def feedback_for(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        tool_plans: tuple[ToolPlan, ...],
    ) -> tuple[ToolExecutionFeedback, ...]:
        state = self._states[min(self.calls, len(self._states) - 1)]
        summary = (
            self._auto_execution_summaries[min(self.calls, len(self._auto_execution_summaries) - 1)]
            if self._auto_execution_summaries
            else None
        )
        self._last_auto_execution_summary = FakeAutoExecutionSummary(summary) if summary else None
        self.requested_call_ids.append(tuple(call.call_id for call in tool_calls if call.call_id))
        self.calls += 1
        return tuple(
            ToolExecutionFeedback(
                tool_call_id=tool_call.call_id,
                tool_name=tool_call.name,
                status=status,
                summary=f"{tool_call.name} 当前状态为 {status.value}",
                audit_id=f"audit-{tool_call.call_id}",
                run_id="run-sequenced",
                output_ref=f"agent-runtime://tool-results/{tool_call.call_id}",
                result={"objects": [{"name": "customer"}]} if status is ToolExecutionFeedbackStatus.SUCCEEDED else {},
            )
            for tool_call in tool_calls
            if (status := state.get(tool_call.call_id)) is not None
        )


class FakeAutoExecutionSummary:
    """Minimal Java auto-execution summary projection used by collector tests."""

    def __init__(self, summary: dict[str, object]) -> None:
        self._summary = summary

    def to_event_summary(self) -> dict[str, object]:
        """Return the same low-sensitivity shape exposed by the real Provider."""

        return dict(self._summary)


class FakeClock:
    """无真实等待的单调时钟，用于验证轮询预算。"""

    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


class FakePlanIngestionClient:
    """测试用计划接入 client，模拟 Java 返回 auditId 映射。"""

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
                    audit_id="atea-call-001",
                    state="SUCCEEDED",
                ),
            ),
            raw_response={},
        )


class RecordingPlanIngestionClient:
    """记录实际送往 Java 的可执行子计划，验证缺参节点不会混入。"""

    def __init__(self) -> None:
        self.ingested_plan: AgentPlan | None = None

    def ingest(self, request_context: AgentRequest, plan: AgentPlan, trace_id: str | None = None):
        self.ingested_plan = plan
        references = tuple(
            AgentToolAuditReference(
                model_tool_call_id=str(item.governance_hints.get("modelToolCallId") or ""),
                tool_name=item.tool_name,
                session_id="ags-partial",
                run_id="agr-partial",
                audit_id=f"atea-partial-{index}",
                state="SUCCEEDED",
            )
            for index, item in enumerate(plan.tool_plans, start=1)
        )
        return AgentPlanIngestionResult(
            session_id="ags-partial",
            run_id="agr-partial",
            tool_audit_references=references,
            raw_response={},
        )


class RecordingControlPlaneFeedbackCollector:
    """记录反馈查询范围；未接入的缺参节点不应参与 auditId 匹配。"""

    def __init__(self) -> None:
        self.collected_plan: AgentPlan | None = None

    def collect(self, plan: AgentPlan):
        self.collected_plan = plan
        return None


class FakeOrchestrator:
    """测试用编排器，只返回固定 AgentPlan，避免测试依赖真实模型路由。"""

    def __init__(self, plan: AgentPlan) -> None:
        self._plan = plan

    def plan(self, request: AgentRequest) -> AgentPlan:
        return self._plan


class FakeSecondTurnOrchestrator:
    """测试用二轮编排器，验证 API 响应组装层会在 loop 决策后调用它。"""

    def run(
        self,
        *,
        request,
        plan,
        control_plane_feedback,
        loop_control_decision,
        progress_event_sink=None,
    ):
        return AgentSecondTurnResult(
            executed=True,
            allowed=True,
            action=loop_control_decision.action.value,
            summary="fake second turn",
            feedback_count=len(control_plane_feedback.feedback_items),
        )


if __name__ == "__main__":
    unittest.main()
