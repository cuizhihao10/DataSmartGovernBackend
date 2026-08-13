"""Focused regressions for post-action Autopilot specialist verification."""

from __future__ import annotations

import unittest

from datasmart_ai_runtime.services.agent_execution.autopilot_post_recovery_verification import (
    AutopilotPostRecoveryVerificationCoordinator,
    AutopilotPostRecoveryVerificationRequest,
)
from datasmart_ai_runtime.services.agent_execution.langgraph_durable_checkpointer import (
    InMemoryLangGraphCheckpointStore,
    LangGraphDurableCheckpointerService,
)
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_coordinator import (
    SpecialistAgentCoordinator,
)
from datasmart_ai_runtime.services.multi_agent.specialist_registry import (
    SpecialistAgentRegistry,
)


class _CompletedSpecialist:
    """Return one completed result while recording the exact delegated turn."""

    def __init__(self, role: AgentSessionRole, calls: list[SpecialistTurnRequest]) -> None:
        """Bind this test specialist to one production role and call recorder."""

        self._role = role
        self._calls = calls

    @property
    def role(self) -> AgentSessionRole:
        """Expose the stable role required by ``SpecialistAgentRegistry``."""

        return self._role

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink=None,
    ) -> SpecialistTurnResult:
        """Record the governed request and return a low-sensitive success fact."""

        self._calls.append(request)
        return SpecialistTurnResult(
            agent_id=f"{self._role.value.lower()}-1",
            role=self._role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.COMPLETED,
            public_summary=f"{self._role.value} completed post-recovery verification.",
        )


class _RecordingFactSink:
    """Model the Java durable fact endpoint with stable idempotent acceptance."""

    def __init__(self, *, failure: Exception | None = None) -> None:
        """Optionally configure the sink to fail like an unavailable Java API."""

        self.calls: list[tuple[SpecialistTurnRequest, SpecialistTurnResult]] = []
        self._failure = failure

    def __call__(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
    ) -> dict[str, object]:
        """Record one fact or propagate the configured infrastructure failure."""

        self.calls.append((request, result))
        if self._failure is not None:
            raise self._failure
        return {"registered": True, "duplicate": False}


def _request() -> AutopilotPostRecoveryVerificationRequest:
    """Build the fixed Java projection of one successful data-sync retry receipt."""

    return AutopilotPostRecoveryVerificationRequest(
        event_id="event-1",
        root_session_id="session-1",
        root_run_id="run-1",
        tenant_id="11",
        application_id="12",
        project_id="13",
        user_id="14",
        actor_id="14",
        agent_id="main-agent",
        delegation_id="delegation-1",
        workspace_key="tenant:11:project:13",
        sync_task_id="31",
        current_execution_id="41",
        task_id="31",
        execution_id="41",
        case_id="81",
        recovery_action="RETRY_EXECUTION",
        cycle=1,
    )


def _coordinator(
    calls: list[SpecialistTurnRequest],
    sink: _RecordingFactSink,
) -> AutopilotPostRecoveryVerificationCoordinator:
    """Assemble the real coordinator around two deterministic test specialists."""

    registry = SpecialistAgentRegistry(
        (
            _CompletedSpecialist(AgentSessionRole.PRECHECK_AGENT, calls),
            _CompletedSpecialist(AgentSessionRole.MONITOR_AGENT, calls),
        )
    )
    return AutopilotPostRecoveryVerificationCoordinator(
        specialist_coordinator=SpecialistAgentCoordinator(registry),
        allowed_tools_by_role={
            "PRECHECK_AGENT": ("sync.task.precheck",),
            "MONITOR_AGENT": ("task.monitor.read",),
        },
        checkpointer=LangGraphDurableCheckpointerService(
            InMemoryLangGraphCheckpointStore()
        ),
        result_sink=sink,
    )


class AutopilotPostRecoveryVerificationTest(unittest.TestCase):
    """Prove success, replay idempotency, and fail-closed fact persistence."""

    def test_runs_and_persists_precheck_and_monitor_after_real_receipt(self) -> None:
        """Both read-only roles must complete and register before Java can ACK."""

        calls: list[SpecialistTurnRequest] = []
        sink = _RecordingFactSink()
        coordinator = _coordinator(calls, sink)

        result = coordinator.verify(_request())

        self.assertEqual("COMPLETED", result.batch_status)
        self.assertEqual(
            ("MONITOR_AGENT", "PRECHECK_AGENT"),
            result.completed_roles,
        )
        self.assertEqual(
            {AgentSessionRole.PRECHECK_AGENT, AgentSessionRole.MONITOR_AGENT},
            {item.role for item in calls},
        )
        self.assertEqual(2, len(sink.calls))
        self.assertTrue(all(item.context_summary["taskId"] == "31" for item in calls))
        self.assertTrue(all(item.context_summary["executionId"] == "41" for item in calls))

    def test_completed_checkpoint_replays_without_duplicate_specialist_turns(self) -> None:
        """A repeated Kafka delivery must reuse the terminal checkpoint and facts."""

        calls: list[SpecialistTurnRequest] = []
        sink = _RecordingFactSink()
        coordinator = _coordinator(calls, sink)

        first = coordinator.verify(_request())
        second = coordinator.verify(_request())

        self.assertFalse(first.replayed)
        self.assertTrue(second.replayed)
        self.assertEqual(first.checkpoint_thread_id, second.checkpoint_thread_id)
        self.assertEqual(2, len(calls))
        self.assertEqual(2, len(sink.calls))

    def test_fact_sink_failure_propagates_for_kafka_retry(self) -> None:
        """An unavailable Java fact endpoint cannot be reported as verification."""

        calls: list[SpecialistTurnRequest] = []
        failure = RuntimeError("SPECIALIST_TURN_FACT_NETWORK_ERROR")
        coordinator = _coordinator(calls, _RecordingFactSink(failure=failure))

        with self.assertRaisesRegex(RuntimeError, "SPECIALIST_TURN_FACT_NETWORK_ERROR"):
            coordinator.verify(_request())


if __name__ == "__main__":  # pragma: no cover - direct local learning entrypoint
    unittest.main()
