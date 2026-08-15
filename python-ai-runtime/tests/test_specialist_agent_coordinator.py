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
    """构造不含无关 Specialist 依赖的单个可执行观察 turn。

    聚焦监控测试需要单独证明协调器准入边界。turn runner 只保留一个角色，可避免成功的规划或知识 turn
    掩盖 MONITOR_AGENT 是否在触达注册表适配器和事实 sink 前被正确跳过。
    """

    return {
        "maxConcurrentAgentTurns": 1,
        "turnAttempts": (
            {"turnId": "turn-monitor", "agentRole": "MONITOR_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
        ),
    }


def _monitor_execution_session() -> dict:
    """构造运行监控观察 turn 所需的最小持久 work item。

    此处让 MONITOR_AGENT 只依赖主编排 Agent。资源定位校验属于协调器准入门禁，因此测试可以区分
    “任务/execution 引用不可用”和“依赖调度失败”。
    """

    return {
        "sessionId": "session-monitor-1",
        "runId": "run-monitor-1",
        "workItems": (
            {"agentRole": "MONITOR_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
        ),
    }


def _precheck_turn_runner() -> dict:
    """构造不含无关规划角色依赖的单个确定性预检 attempt。"""

    return {
        "maxConcurrentAgentTurns": 1,
        "turnAttempts": (
            {"turnId": "turn-precheck", "agentRole": "PRECHECK_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
        ),
    }


def _precheck_execution_session() -> dict:
    """构造测试预检资源准入所需的最小持久 work item。"""

    return {
        "sessionId": "session-precheck-1",
        "runId": "run-precheck-1",
        "workItems": (
            {"agentRole": "PRECHECK_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
        ),
    }


def _recovery_turn_runner() -> dict:
    """构造单个可执行 Recovery attempt，使准入测试不依赖其他 Specialist。"""

    return {
        "maxConcurrentAgentTurns": 1,
        "turnAttempts": (
            {"turnId": "turn-recovery", "agentRole": "RECOVERY_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
        ),
    }


def _recovery_execution_session() -> dict:
    """构造独立测试失败 execution 准入所需的最小持久 work item。"""

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
        """缺少有效资源定位时，在执行前拒绝监控 turn。

        新规划任务尚无可观察的持久任务，因此 taskId 必填，并允许可信 Java 回执使用正整数或正整数字符串。
        executionId 可选，因为任务在第一次执行前也可被观察；一旦提供，同样必须是正整数形式。非法定位
        应以 RUNTIME_RESOURCE_NOT_AVAILABLE_YET 明确跳过，不能调用监控器或持久化误导性的 FAILED 事实。
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
                    """记录真实事实登记尝试，确保被跳过的观察器不会被显示为失败。"""

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
        """不要把规划阶段预期的 taskId 缺失变成 PRECHECK 失败事实。

        PRECHECK 使用确定性 Java 任务接口，无法校验只存在于模型中的规划对象。反例证明调用注册表/sink
        前会跳过，正例证明草稿创建后，可信任务定位可以调度并登记同一角色。
        """

        for base_context, should_execute in (({}, False), ({"taskId": "77"}, True)):
            with self.subTest(base_context=base_context):
                calls: list[str] = []
                registrations: list[SpecialistTurnResult] = []
                registry = SpecialistAgentRegistry(
                    (_RecordingAgent(AgentSessionRole.PRECHECK_AGENT, calls, SpecialistTurnStatus.COMPLETED),)
                )

                def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
                    """只记录真实执行；规划阶段的跳过不能持久化为事实。"""

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
        """允许 MONITOR_AGENT 使用整数或十进制字符串形式的有效 Java 回执标识。

        确认后路径把可信 Java 资源 ID 投影为十进制字符串，部分内部控制面适配器则保留数值。本正例同时
        验证两种形式：taskId=77、executionId="1958" 有效；第一次执行前只有任务十进制字符串定位也有效。
        每个获准观察 turn 都必须返回 COMPLETED 并登记一条成功事实，防止新准入门禁过度阻断确认后监控。
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
                    """记录预期的一条成功事实，不暴露实现内部信息。"""

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
        """不要诊断成功/规划中的任务或不完整失败定位。

        只有 task 和 execution ID 也可能描述健康运行；缺少 executionId 的失败码则无法定位待修复 attempt。
        两类输入都必须停在协调器准入边界，避免诊断客户端或持久 Specialist 事实 sink 登记误导性失败。
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
                    """捕获任何意外持久化尝试；被跳过的 Recovery 必须保持为空。"""

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
        """同一受控载体包含任务、execution 和失败事实时允许 Recovery。"""

        calls: list[str] = []
        registrations: list[SpecialistTurnResult] = []
        registry = SpecialistAgentRegistry(
            (_RecordingAgent(AgentSessionRole.RECOVERY_AGENT, calls, SpecialistTurnStatus.COMPLETED),)
        )

        def result_sink(request: SpecialistTurnRequest, result: SpecialistTurnResult) -> None:
            """登记获准的 Recovery 结果，证明门禁不会过度阻断真实失败。"""

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
        """证明持久失败不会启动 DATA_SYNC 规划，也不会让 Recovery 输入发生竞争。

        协调器接收 task 76 / execution 1805 定位。KNOWLEDGE_AGENT 和 MONITOR_AGENT 作为首个只读波次
        运行；RECOVERY_AGENT 等待两者完成，并通过受控依赖上下文接收摘要。本恢复批次不注册或执行
        DATA_SYNC_AGENT。
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
        """动态 Send 波次中的所有完成结果都必须登记一次，并公开低敏编排事实。"""

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
        summary = result.to_summary()["runtimeFanout"]
        self.assertEqual("langgraph", summary["engine"])
        self.assertEqual("DYNAMIC_SEND_SUBGRAPH", summary["dispatchMode"])
        self.assertEqual(3, summary["dynamicDispatchCount"])
        self.assertEqual(3, summary["subgraphInvocationCount"])
        self.assertTrue(summary["runtimeSelectedRoster"])

        runtime_action = result.to_runtime_event_action()
        self.assertEqual(
            {
                "dynamicDispatchCount": 3,
                "subgraphInvocationCount": 3,
                "executionWaveCount": 1,
                "graphNodeCount": 3,
                "graphEdgeCount": 4,
            },
            runtime_action["statistics"],
        )
        self.assertEqual("langgraph", runtime_action["attributes"]["orchestrationEngine"])

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
