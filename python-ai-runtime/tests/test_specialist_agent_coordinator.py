from __future__ import annotations

import unittest

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_coordinator import SpecialistAgentCoordinator
from datasmart_ai_runtime.services.multi_agent.specialist_fact_client import (
    JavaSpecialistTurnFactClient,
    JavaSpecialistTurnFactClientError,
    JavaSpecialistTurnFactClientSettings,
)
from datasmart_ai_runtime.services.multi_agent.specialist_registry import SpecialistAgentRegistry


class _RecordingAgent:
    def __init__(self, role: AgentSessionRole, calls: list[str], status: SpecialistTurnStatus) -> None:
        self._role = role
        self._calls = calls
        self._status = status
        self.requests: list[SpecialistTurnRequest] = []

    @property
    def role(self) -> AgentSessionRole:
        return self._role

    def execute(self, request: SpecialistTurnRequest, event_sink=None) -> SpecialistTurnResult:
        self._calls.append(self._role.value)
        self.requests.append(request)
        return SpecialistTurnResult(
            agent_id=f"{self._role.value.lower()}-1",
            role=self._role,
            turn_id=request.turn_id,
            status=self._status,
            public_summary=f"{self._role.value} 完成本轮分析",
            structured_output={"role": self._role.value},
        )


class _FailingAgent:
    """只用于测试注册表异常是否会先转换为 FAILED 再登记的专业 Agent。"""

    def __init__(self, role: AgentSessionRole, calls: list[str]) -> None:
        """保存角色和调用记录容器，便于同时验证下游是否被依赖阻断。"""

        self._role = role
        self._calls = calls

    @property
    def role(self) -> AgentSessionRole:
        """返回测试 Agent 负责的稳定角色。"""

        return self._role

    def execute(self, request: SpecialistTurnRequest, event_sink=None) -> SpecialistTurnResult:
        """记录一次调用后抛出运行时异常，模拟模型或工具执行失败。"""

        self._calls.append(self._role.value)
        raise RuntimeError("simulated specialist failure")


class _FailingTransport:
    """让 Java 事实客户端在测试中返回 fail-open receipt 或抛 fail-closed 异常。"""

    def __call__(self, request, timeout: int):
        """模拟内部登记服务不可达，且不读取或输出请求正文。"""

        raise OSError("simulated fact endpoint failure")


def _request() -> AgentRequest:
    return AgentRequest(
        tenant_id="1",
        project_id="101",
        actor_id="user-1",
        objective="生成同步任务",
        request_id="request-1",
        # 持久化 Specialist fact 的 Java V6 合同使用正整数 applicationId；该值模拟
        # Gateway 从认证上下文重建的可信应用范围，而不是应用名称或用户正文。
        variables={
            "trustedControlPlane": {
                "applicationId": "10010",
                "delegationId": "delegation-parent-request-1",
            }
        },
    )


def _turn_runner() -> dict:
    return {
        "maxConcurrentAgentTurns": 3,
        "turnAttempts": (
            {"turnId": "turn-knowledge", "agentRole": "KNOWLEDGE_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
            {"turnId": "turn-datasource", "agentRole": "DATASOURCE_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
            {"turnId": "turn-sync", "agentRole": "DATA_SYNC_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
        ),
    }


def _execution_session() -> dict:
    return {
        "sessionId": "session-1",
        "runId": "run-1",
        "workItems": (
            {"agentRole": "KNOWLEDGE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
            {"agentRole": "DATASOURCE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "KNOWLEDGE_AGENT")},
            {"agentRole": "DATA_SYNC_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "DATASOURCE_AGENT")},
        ),
    }


def _parallel_execution_session() -> dict:
    """构造三个只依赖主 Agent 的工作项，确保协调器进入并发波次。"""

    return {
        "sessionId": "session-1",
        "runId": "run-1",
        "workItems": (
            {"agentRole": "KNOWLEDGE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
            {"agentRole": "DATASOURCE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
            {"agentRole": "DATA_SYNC_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
        ),
    }


def _monitor_turn_runner() -> dict:
    """Build one executable observer attempt without unrelated specialist dependencies.

    The focused monitor tests must prove the coordinator's admission boundary itself.  Keeping the
    turn runner to one role prevents a successful planning or knowledge turn from obscuring whether
    MONITOR_AGENT was correctly skipped before its registry adapter and fact sink were touched.
    """

    return {
        "maxConcurrentAgentTurns": 1,
        "turnAttempts": (
            {"turnId": "turn-monitor", "agentRole": "MONITOR_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
        ),
    }


def _monitor_execution_session() -> dict:
    """Build the minimal durable work item required to run a monitor observer turn.

    MONITOR_AGENT is intentionally dependent only on the master orchestrator here.  Resource-location
    validation belongs to the coordinator's admission gate, so these tests can distinguish an unavailable
    task/execution reference from a dependency scheduling failure.
    """

    return {
        "sessionId": "session-monitor-1",
        "runId": "run-monitor-1",
        "workItems": (
            {"agentRole": "MONITOR_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
        ),
    }


def _precheck_turn_runner() -> dict:
    """Build one deterministic precheck attempt without unrelated planning-role dependencies."""

    return {
        "maxConcurrentAgentTurns": 1,
        "turnAttempts": (
            {"turnId": "turn-precheck", "agentRole": "PRECHECK_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
        ),
    }


def _precheck_execution_session() -> dict:
    """Build the minimal durable work item used to test precheck resource admission."""

    return {
        "sessionId": "session-precheck-1",
        "runId": "run-precheck-1",
        "workItems": (
            {"agentRole": "PRECHECK_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
        ),
    }


def _recovery_turn_runner() -> dict:
    """Build one executable Recovery attempt so admission tests do not depend on other specialists."""

    return {
        "maxConcurrentAgentTurns": 1,
        "turnAttempts": (
            {"turnId": "turn-recovery", "agentRole": "RECOVERY_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
        ),
    }


def _recovery_execution_session() -> dict:
    """Build the minimal durable work item for testing failed-execution admission independently."""

    return {
        "sessionId": "session-recovery-1",
        "runId": "run-recovery-1",
        "workItems": (
            {"agentRole": "RECOVERY_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
        ),
    }


class SpecialistAgentCoordinatorTest(unittest.TestCase):
    """验证专业 Agent 的 checkpoint、依赖和工具白名单边界。"""

    def test_executes_dependency_waves_in_order(self) -> None:
        calls: list[str] = []
        registry = SpecialistAgentRegistry(
            (
                _RecordingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATASOURCE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATA_SYNC_AGENT, calls, SpecialistTurnStatus.COMPLETED),
            )
        )
        coordinator = SpecialistAgentCoordinator(registry)

        result = coordinator.run(
            request=_request(),
            turn_runner=_turn_runner(),
            execution_session=_execution_session(),
            allowed_tools_by_role={
                "KNOWLEDGE_AGENT": ("rag.query",),
                "DATASOURCE_AGENT": ("datasource.access",),
                "DATA_SYNC_AGENT": ("task.create.draft",),
            },
            checkpoint_recorded=True,
        )

        self.assertEqual("COMPLETED", result.status)
        self.assertEqual(
            ["KNOWLEDGE_AGENT", "DATASOURCE_AGENT", "DATA_SYNC_AGENT"],
            calls,
        )
        self.assertEqual(
            (("KNOWLEDGE_AGENT",), ("DATASOURCE_AGENT",), ("DATA_SYNC_AGENT",)),
            result.execution_waves,
        )
        first_binding = result.results[0].delegated_scope_binding
        self.assertEqual("1", first_binding["tenantId"])
        self.assertEqual("101", first_binding["projectId"])
        self.assertEqual("user-1", first_binding["actorId"])
        self.assertEqual("10010", first_binding["applicationId"])
        self.assertEqual("session-1", first_binding["sessionId"])
        self.assertEqual("run-1", first_binding["runId"])
        # 公开结果按 role 排序，因此第一条是 DATASOURCE_AGENT，而不是第一波执行的 KNOWLEDGE_AGENT。
        self.assertEqual("delegation-caef25bf19b3b06238fcf61c", first_binding["delegationId"])
        # 内部可信绑定只供 Bridge/审批治理使用，不能随公开专业结果摘要返回前端。
        self.assertNotIn("delegatedScopeBinding", result.results[0].to_summary())

    def test_waiting_dependency_prevents_downstream_guessing(self) -> None:
        calls: list[str] = []
        registry = SpecialistAgentRegistry(
            (
                _RecordingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls, SpecialistTurnStatus.WAITING_FOR_INPUT),
                _RecordingAgent(AgentSessionRole.DATASOURCE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATA_SYNC_AGENT, calls, SpecialistTurnStatus.COMPLETED),
            )
        )

        result = SpecialistAgentCoordinator(registry).run(
            request=_request(),
            turn_runner=_turn_runner(),
            execution_session=_execution_session(),
            allowed_tools_by_role={
                "KNOWLEDGE_AGENT": ("rag.query",),
                "DATASOURCE_AGENT": ("datasource.access",),
                "DATA_SYNC_AGENT": ("task.create.draft",),
            },
            checkpoint_recorded=True,
        )

        self.assertEqual(["KNOWLEDGE_AGENT"], calls)
        self.assertEqual("WAITING_FOR_INPUT", result.status)
        self.assertEqual("DEPENDENCY_NOT_COMPLETED", result.skipped_roles["DATASOURCE_AGENT"])

    def test_checkpoint_is_required_before_real_specialist_turn(self) -> None:
        calls: list[str] = []
        registry = SpecialistAgentRegistry(
            (_RecordingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls, SpecialistTurnStatus.COMPLETED),)
        )

        result = SpecialistAgentCoordinator(registry).run(
            request=_request(),
            turn_runner=_turn_runner(),
            execution_session=_execution_session(),
            allowed_tools_by_role={"KNOWLEDGE_AGENT": ("rag.query",)},
            checkpoint_recorded=False,
        )

        self.assertEqual("BLOCKED_CHECKPOINT_REQUIRED", result.status)
        self.assertEqual([], calls)

    def test_monitor_without_task_id_or_with_invalid_execution_id_is_skipped_without_failed_fact(self) -> None:
        """Reject monitor turns before execution when no valid resource locator is available.

        A newly planned task has no durable task to observe, so taskId is required and may arrive from the
        trusted Java receipt either as a positive integer or a positive decimal string.  executionId is
        optional because a task can be observable before its first execution exists; when supplied, it must
        use the same positive-integer representation.  Invalid locators must skip with the explicit
        RUNTIME_RESOURCE_NOT_AVAILABLE_YET reason rather than invoke the monitor or persist a misleading
        FAILED fact.
        """

        invalid_contexts = (
            {},
            {"taskId": 0},
            {"taskId": "0"},
            {"taskId": 77, "executionId": 0},
            {"taskId": 77, "executionId": "not-a-decimal-id"},
        )

        for base_context in invalid_contexts:
            with self.subTest(base_context=base_context):
                calls: list[str] = []
                registrations: list[SpecialistTurnResult] = []
                registry = SpecialistAgentRegistry(
                    (_RecordingAgent(AgentSessionRole.MONITOR_AGENT, calls, SpecialistTurnStatus.COMPLETED),)
                )

                def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
                    """Record actual fact-registration attempts so a skipped observer cannot look failed."""

                    registrations.append(result)

                result = SpecialistAgentCoordinator(registry, result_sink=result_sink).run(
                    request=_request(),
                    turn_runner=_monitor_turn_runner(),
                    execution_session=_monitor_execution_session(),
                    allowed_tools_by_role={"MONITOR_AGENT": ("task.monitor.read",)},
                    base_context=base_context,
                    checkpoint_recorded=True,
                )

                self.assertEqual("NO_EXECUTABLE_SPECIALISTS", result.status)
                self.assertEqual("RUNTIME_RESOURCE_NOT_AVAILABLE_YET", result.skipped_roles["MONITOR_AGENT"])
                self.assertEqual([], calls)
                self.assertEqual([], registrations)
                self.assertFalse(any(item.status == SpecialistTurnStatus.FAILED for item in result.results))

    def test_precheck_waits_for_persisted_task_and_executes_after_java_locator_exists(self) -> None:
        """Do not turn an expected planning-stage absence of taskId into a PRECHECK failure fact.

        PRECHECK uses the deterministic Java task endpoint, so it cannot validate a model-only planning object.
        The negative case proves it skips before registry/sink invocation; the positive case proves a trusted
        task locator schedules and records the same role after draft creation.
        """

        for base_context, should_execute in (({}, False), ({"taskId": "77"}, True)):
            with self.subTest(base_context=base_context):
                calls: list[str] = []
                registrations: list[SpecialistTurnResult] = []
                registry = SpecialistAgentRegistry(
                    (_RecordingAgent(AgentSessionRole.PRECHECK_AGENT, calls, SpecialistTurnStatus.COMPLETED),)
                )

                def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
                    """Capture only actual executions; planning-stage skips must not persist a fact."""

                    registrations.append(result)

                result = SpecialistAgentCoordinator(registry, result_sink=result_sink).run(
                    request=_request(),
                    turn_runner=_precheck_turn_runner(),
                    execution_session=_precheck_execution_session(),
                    allowed_tools_by_role={"PRECHECK_AGENT": ("sync.task.precheck",)},
                    base_context=base_context,
                    checkpoint_recorded=True,
                )

                if should_execute:
                    self.assertEqual("COMPLETED", result.status)
                    self.assertEqual(["PRECHECK_AGENT"], calls)
                    self.assertEqual(1, len(registrations))
                    self.assertEqual({}, result.skipped_roles)
                else:
                    self.assertEqual("NO_EXECUTABLE_SPECIALISTS", result.status)
                    self.assertEqual(
                        "RUNTIME_RESOURCE_NOT_AVAILABLE_YET",
                        result.skipped_roles["PRECHECK_AGENT"],
                    )
                    self.assertEqual([], calls)
                    self.assertEqual([], registrations)

    def test_monitor_with_valid_java_resource_identifier_forms_executes_and_records_success(self) -> None:
        """Permit MONITOR_AGENT for valid integer or decimal-string Java receipt identifiers.

        The post-confirm path projects trusted Java resource IDs as decimal strings, while some internal
        control-plane adapters keep numeric values.  This positive control therefore verifies both forms:
        taskId=77 with executionId="1958" is valid, as is a task-only decimal-string locator before the
        first execution exists.  Each permitted observer turn must return COMPLETED and register one
        successful fact, preventing the new admission guard from over-blocking post-confirm monitoring.
        """

        valid_contexts = (
            {"taskId": 77, "executionId": "1958"},
            {"taskId": "77"},
        )

        for base_context in valid_contexts:
            with self.subTest(base_context=base_context):
                calls: list[str] = []
                registrations: list[SpecialistTurnResult] = []
                registry = SpecialistAgentRegistry(
                    (_RecordingAgent(AgentSessionRole.MONITOR_AGENT, calls, SpecialistTurnStatus.COMPLETED),)
                )

                def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
                    """Capture the one expected success fact without exposing implementation internals."""

                    registrations.append(result)

                result = SpecialistAgentCoordinator(registry, result_sink=result_sink).run(
                    request=_request(),
                    turn_runner=_monitor_turn_runner(),
                    execution_session=_monitor_execution_session(),
                    allowed_tools_by_role={"MONITOR_AGENT": ("task.monitor.read",)},
                    base_context=base_context,
                    checkpoint_recorded=True,
                )

                self.assertEqual("COMPLETED", result.status)
                self.assertEqual(["MONITOR_AGENT"], calls)
                self.assertEqual(1, len(registrations))
                self.assertEqual(SpecialistTurnStatus.COMPLETED, registrations[0].status)
                self.assertEqual({}, result.skipped_roles)

    def test_recovery_without_concrete_failed_execution_is_skipped_without_failed_fact(self) -> None:
        """Do not diagnose successful/planned tasks or incomplete failure locators.

        A task and execution ID alone can describe a healthy run, while a failure code without executionId
        cannot identify which attempt should be repaired.  Both shapes must stop at coordinator admission so
        neither the diagnostic client nor the durable specialist-fact sink records a misleading failure.
        """

        invalid_contexts = (
            {},
            {"taskId": 77, "executionId": 1958},
            {"taskId": 77, "failureCode": "SYNC_WRITE_FAILED"},
            {"taskId": 77, "executionId": 1958, "status": "SUCCEEDED"},
            {
                "failureContext": {
                    "taskId": 77,
                    "executionId": 0,
                    "failureCode": "SYNC_WRITE_FAILED",
                }
            },
        )

        for base_context in invalid_contexts:
            with self.subTest(base_context=base_context):
                calls: list[str] = []
                registrations: list[SpecialistTurnResult] = []
                registry = SpecialistAgentRegistry(
                    (_RecordingAgent(AgentSessionRole.RECOVERY_AGENT, calls, SpecialistTurnStatus.COMPLETED),)
                )

                def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
                    """Capture any accidental persistence attempt; skipped Recovery must leave this empty."""

                    registrations.append(result)

                result = SpecialistAgentCoordinator(registry, result_sink=result_sink).run(
                    request=_request(),
                    turn_runner=_recovery_turn_runner(),
                    execution_session=_recovery_execution_session(),
                    allowed_tools_by_role={"RECOVERY_AGENT": ("recovery.failure.diagnose",)},
                    base_context=base_context,
                    checkpoint_recorded=True,
                )

                self.assertEqual("NO_EXECUTABLE_SPECIALISTS", result.status)
                self.assertEqual(
                    "FAILED_EXECUTION_NOT_AVAILABLE_YET",
                    result.skipped_roles["RECOVERY_AGENT"],
                )
                self.assertEqual([], calls)
                self.assertEqual([], registrations)

    def test_recovery_with_scoped_failed_execution_executes_and_records_success(self) -> None:
        """Admit Recovery when one controlled carrier contains task, execution and failure facts."""

        calls: list[str] = []
        registrations: list[SpecialistTurnResult] = []
        registry = SpecialistAgentRegistry(
            (_RecordingAgent(AgentSessionRole.RECOVERY_AGENT, calls, SpecialistTurnStatus.COMPLETED),)
        )

        def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
            """Record the admitted Recovery result to prove the guard does not over-block real failures."""

            registrations.append(result)

        result = SpecialistAgentCoordinator(registry, result_sink=result_sink).run(
            request=_request(),
            turn_runner=_recovery_turn_runner(),
            execution_session=_recovery_execution_session(),
            allowed_tools_by_role={"RECOVERY_AGENT": ("recovery.failure.diagnose",)},
            base_context={
                "failureContext": {
                    "taskId": "77",
                    "executionId": 1958,
                    "failureCode": "SYNC_WRITE_FAILED",
                }
            },
            checkpoint_recorded=True,
        )

        self.assertEqual("COMPLETED", result.status)
        self.assertEqual(["RECOVERY_AGENT"], calls)
        self.assertEqual(1, len(registrations))
        self.assertEqual(SpecialistTurnStatus.COMPLETED, registrations[0].status)
        self.assertEqual({}, result.skipped_roles)

    def test_pure_failed_execution_runs_monitor_and_knowledge_before_recovery(self) -> None:
        """Prove a persisted failure does not start DATA_SYNC planning or race recovery inputs.

        The coordinator receives the task 76 / execution 1805 locator shape.  KNOWLEDGE_AGENT and
        MONITOR_AGENT run as read-only first-wave work, while RECOVERY_AGENT waits for both completed
        summaries and receives them through the controlled dependency context.  DATA_SYNC_AGENT is not
        registered or executable in this recovery batch.
        """

        calls: list[str] = []
        knowledge = _RecordingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls, SpecialistTurnStatus.COMPLETED)
        monitor = _RecordingAgent(AgentSessionRole.MONITOR_AGENT, calls, SpecialistTurnStatus.COMPLETED)
        recovery = _RecordingAgent(AgentSessionRole.RECOVERY_AGENT, calls, SpecialistTurnStatus.COMPLETED)
        coordinator = SpecialistAgentCoordinator(SpecialistAgentRegistry((knowledge, monitor, recovery)))
        request = AgentRequest(
            tenant_id="1",
            project_id="101",
            actor_id="user-1",
            objective="diagnose existing failed execution",
            request_id="request-recovery-76-1805",
            variables={
                "trustedControlPlane": {"applicationId": "10010", "delegationId": "delegation-parent"},
            },
        )
        result = coordinator.run(
            request=request,
            turn_runner={
                "maxConcurrentAgentTurns": 2,
                "turnAttempts": (
                    {"turnId": "turn-knowledge", "agentRole": "KNOWLEDGE_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
                    {"turnId": "turn-monitor", "agentRole": "MONITOR_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
                    {"turnId": "turn-recovery", "agentRole": "RECOVERY_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
                ),
            },
            execution_session={
                "sessionId": "session-recovery-76-1805",
                "runId": "run-recovery-76-1805",
                "workItems": (
                    {"agentRole": "KNOWLEDGE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
                    {"agentRole": "MONITOR_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
                    {"agentRole": "RECOVERY_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "KNOWLEDGE_AGENT", "MONITOR_AGENT")},
                ),
            },
            allowed_tools_by_role={
                "KNOWLEDGE_AGENT": ("knowledge.rag.query",),
                "MONITOR_AGENT": ("task.monitor.read",),
                "RECOVERY_AGENT": ("recovery.failure.diagnose",),
            },
            base_context={
                "failureContext": {
                    "taskId": 76,
                    "executionId": 1805,
                    "status": "FAILED",
                    "failureCode": "TARGET_WRITE_ERROR",
                }
            },
            checkpoint_recorded=True,
        )

        self.assertEqual(
            (("KNOWLEDGE_AGENT", "MONITOR_AGENT"), ("RECOVERY_AGENT",)),
            result.execution_waves,
        )
        self.assertEqual(
            {"KNOWLEDGE_AGENT", "MONITOR_AGENT"},
            set(recovery.requests[0].context_summary["dependencyResults"]),
        )
        self.assertNotIn("DATA_SYNC_AGENT", calls)

    def test_serial_results_are_registered_exactly_once(self) -> None:
        """串行依赖波次中的每个实际结果都应传给 sink 一次，receipt 不改变业务结果。"""

        calls: list[str] = []
        registrations: list[tuple[str, str, SpecialistTurnStatus]] = []

        def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> object:
            """记录低敏身份和状态，模拟 Java 客户端返回 receipt。"""

            registrations.append((request.role.value, result.turn_id, result.status))
            return {"registered": True}

        registry = SpecialistAgentRegistry(
            (
                _RecordingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATASOURCE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATA_SYNC_AGENT, calls, SpecialistTurnStatus.COMPLETED),
            )
        )

        result = SpecialistAgentCoordinator(registry, result_sink=result_sink).run(
            request=_request(),
            turn_runner=_turn_runner(),
            execution_session=_execution_session(),
            allowed_tools_by_role={
                "KNOWLEDGE_AGENT": ("rag.query",),
                "DATASOURCE_AGENT": ("datasource.access",),
                "DATA_SYNC_AGENT": ("task.create.draft",),
            },
            checkpoint_recorded=True,
        )

        self.assertEqual("COMPLETED", result.status)
        self.assertEqual(
            [
                ("KNOWLEDGE_AGENT", "turn-knowledge", SpecialistTurnStatus.COMPLETED),
                ("DATASOURCE_AGENT", "turn-datasource", SpecialistTurnStatus.COMPLETED),
                ("DATA_SYNC_AGENT", "turn-sync", SpecialistTurnStatus.COMPLETED),
            ],
            registrations,
        )

    def test_execution_exception_is_registered_as_failed_once(self) -> None:
        """注册表抛异常时，异常转换出的 FAILED 结果必须登记，且下游依赖不能猜测执行。"""

        calls: list[str] = []
        registrations: list[SpecialistTurnResult] = []

        def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
            """记录失败结果，验证 sink 接收的是规范化后的结果而非原始异常。"""

            registrations.append(result)

        registry = SpecialistAgentRegistry(
            (
                _FailingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls),
                _RecordingAgent(AgentSessionRole.DATASOURCE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATA_SYNC_AGENT, calls, SpecialistTurnStatus.COMPLETED),
            )
        )

        result = SpecialistAgentCoordinator(registry, result_sink=result_sink).run(
            request=_request(),
            turn_runner=_turn_runner(),
            execution_session=_execution_session(),
            allowed_tools_by_role={
                "KNOWLEDGE_AGENT": ("rag.query",),
                "DATASOURCE_AGENT": ("datasource.access",),
                "DATA_SYNC_AGENT": ("task.create.draft",),
            },
            checkpoint_recorded=True,
        )

        self.assertEqual("PARTIALLY_FAILED", result.status)
        self.assertEqual(["KNOWLEDGE_AGENT"], calls)
        self.assertEqual(1, len(registrations))
        self.assertEqual(SpecialistTurnStatus.FAILED, registrations[0].status)
        self.assertEqual("RUNTIMEERROR", registrations[0].error_code)

    def test_parallel_results_are_all_registered_once(self) -> None:
        """并发波次中的所有完成结果都必须登记一次，不能因 future 完成顺序丢失事实。"""

        calls: list[str] = []
        registrations: list[tuple[str, str]] = []

        def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
            """记录并发结果；CPython list append 在此测试中只用于计数和集合校验。"""

            registrations.append((request.role.value, result.turn_id))

        registry = SpecialistAgentRegistry(
            (
                _RecordingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATASOURCE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATA_SYNC_AGENT, calls, SpecialistTurnStatus.COMPLETED),
            )
        )

        result = SpecialistAgentCoordinator(registry, result_sink=result_sink).run(
            request=_request(),
            turn_runner=_turn_runner(),
            execution_session=_parallel_execution_session(),
            allowed_tools_by_role={
                "KNOWLEDGE_AGENT": ("rag.query",),
                "DATASOURCE_AGENT": ("datasource.access",),
                "DATA_SYNC_AGENT": ("task.create.draft",),
            },
            checkpoint_recorded=True,
        )

        self.assertEqual("COMPLETED", result.status)
        self.assertEqual(3, len(registrations))
        self.assertEqual(
            {
                ("KNOWLEDGE_AGENT", "turn-knowledge"),
                ("DATASOURCE_AGENT", "turn-datasource"),
                ("DATA_SYNC_AGENT", "turn-sync"),
            },
            set(registrations),
        )

    def test_java_fail_open_receipt_does_not_block_dependency_wave(self) -> None:
        """Java 客户端 fail-open 返回 receipt 时，专业结果仍可驱动后续依赖角色。"""

        calls: list[str] = []
        fact_client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(
                enabled=True,
                service_token="internal-token",
                fail_closed=False,
            ),
            transport=_FailingTransport(),
        )
        registry = SpecialistAgentRegistry(
            (
                _RecordingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATASOURCE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATA_SYNC_AGENT, calls, SpecialistTurnStatus.COMPLETED),
            )
        )

        result = SpecialistAgentCoordinator(registry, result_sink=fact_client).run(
            request=_request(),
            turn_runner=_turn_runner(),
            execution_session=_execution_session(),
            allowed_tools_by_role={
                "KNOWLEDGE_AGENT": ("rag.query",),
                "DATASOURCE_AGENT": ("datasource.access",),
                "DATA_SYNC_AGENT": ("task.create.draft",),
            },
            checkpoint_recorded=True,
        )

        self.assertEqual("COMPLETED", result.status)
        self.assertEqual(
            ["KNOWLEDGE_AGENT", "DATASOURCE_AGENT", "DATA_SYNC_AGENT"],
            calls,
        )

    def test_java_fail_closed_error_is_not_silently_converted(self) -> None:
        """Java 客户端 fail-closed 时，协调器必须传播低敏客户端错误而不是伪造批次成功。"""

        calls: list[str] = []
        fact_client = JavaSpecialistTurnFactClient(
            JavaSpecialistTurnFactClientSettings(
                enabled=True,
                service_token="internal-token",
                fail_closed=True,
            ),
            transport=_FailingTransport(),
        )
        registry = SpecialistAgentRegistry(
            (
                _RecordingAgent(AgentSessionRole.KNOWLEDGE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATASOURCE_AGENT, calls, SpecialistTurnStatus.COMPLETED),
                _RecordingAgent(AgentSessionRole.DATA_SYNC_AGENT, calls, SpecialistTurnStatus.COMPLETED),
            )
        )

        with self.assertRaises(JavaSpecialistTurnFactClientError) as raised:
            SpecialistAgentCoordinator(registry, result_sink=fact_client).run(
                request=_request(),
                turn_runner=_turn_runner(),
                execution_session=_execution_session(),
                allowed_tools_by_role={
                    "KNOWLEDGE_AGENT": ("rag.query",),
                    "DATASOURCE_AGENT": ("datasource.access",),
                    "DATA_SYNC_AGENT": ("task.create.draft",),
                },
                checkpoint_recorded=True,
            )

        self.assertEqual("SPECIALIST_TURN_FACT_HTTP_POST_FAILED", raised.exception.code)
        self.assertEqual(["KNOWLEDGE_AGENT"], calls)


if __name__ == "__main__":
    unittest.main()
