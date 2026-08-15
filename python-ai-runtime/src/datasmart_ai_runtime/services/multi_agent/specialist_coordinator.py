"""主编排 Agent 调度真实专业 Agent turn 的受控协调器。

本模块是“协作图/turn runner 诊断骨架”和“真正专业 Agent 执行”之间的桥。它不会保存任务、修改数据源
或绕过 Java 控制面；它只在 durable checkpoint 已建立、角色已注册、工具白名单已明确时执行低风险的
专业分析 turn，并把低敏结果交还主 Agent。
"""

from __future__ import annotations

import hashlib
from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistEventSink,
    SpecialistTurnBudget,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_langgraph_fanout import (
    LangGraphSpecialistFanoutExecutor,
    SpecialistFanoutExecution,
)
from datasmart_ai_runtime.services.multi_agent.specialist_registry import SpecialistAgentRegistry


_EXECUTABLE_TURN_STATUSES = {
    "READY_FOR_SPECIALIST_TURN",
    "READY_FOR_DRAFT_ONLY_TURN",
}

_KNOWLEDGE_READ_ONLY_TURN_STATUSES = _EXECUTABLE_TURN_STATUSES | {
    "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF",
}

# Recovery 的高风险建议需要先由 Recovery specialist 读取诊断事实并生成低敏 ToolPlan
# 草案，然后才能交给 Java 控制面审批。这里允许它消费 handoff 状态只是“允许生成建议”，
# 并不意味着 Python 可以直接执行恢复动作；真正的写操作仍由 bridge 和 Java 控制面负责。
_RECOVERY_HANDOFF_TURN_STATUSES = _EXECUTABLE_TURN_STATUSES | {
    "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF",
}


# 结果登记器只接收已经通过 SpecialistTurnResult 合同校验的低敏对象。
# 返回值故意不纳入协调器控制流：Java 客户端可以返回 receipt，但 fail-open receipt 不能阻断
# 后续角色依赖；只有登记器明确抛出异常（例如 fail-closed）时，才由协调器向调用方传播。
SpecialistTurnResultSink = Callable[[SpecialistTurnRequest, SpecialistTurnResult], Any]


@dataclass(frozen=True)
class SpecialistExecutionBatchResult:
    """一次主 Agent 委派批次的低敏结果。"""

    status: str
    results: tuple[SpecialistTurnResult, ...]
    skipped_roles: Mapping[str, str]
    execution_waves: tuple[tuple[str, ...], ...]
    orchestration_engine: str = "none"
    dispatch_mode: str = "NONE"
    dynamic_dispatch_count: int = 0
    subgraph_invocation_count: int = 0
    dispatched_roles: tuple[str, ...] = ()
    fanout_graph_nodes: tuple[str, ...] = ()
    fanout_graph_edges: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """生成 API、runtime event 和主 Agent 二轮上下文可共同消费的摘要。"""

        return {
            "status": self.status,
            "executedCount": len(self.results),
            "completedCount": sum(1 for item in self.results if item.status == SpecialistTurnStatus.COMPLETED),
            "waitingInputCount": sum(
                1 for item in self.results if item.status == SpecialistTurnStatus.WAITING_FOR_INPUT
            ),
            "failedCount": sum(1 for item in self.results if item.status == SpecialistTurnStatus.FAILED),
            "results": tuple(item.to_summary() for item in self.results),
            "skippedRoles": dict(self.skipped_roles),
            "executionWaves": self.execution_waves,
            # 这组字段只描述 LangGraph 编排事实，不包含专业输入、模型输出、工具参数或 sink 内容。
            "runtimeFanout": {
                "engine": self.orchestration_engine,
                "dispatchMode": self.dispatch_mode,
                "dynamicDispatchCount": max(0, self.dynamic_dispatch_count),
                "subgraphInvocationCount": max(0, self.subgraph_invocation_count),
                "dispatchedRoles": self.dispatched_roles,
                "graphNodes": self.fanout_graph_nodes,
                "graphEdges": self.fanout_graph_edges,
                "runtimeSelectedRoster": (
                    self.orchestration_engine == "langgraph"
                    and self.dynamic_dispatch_count > 0
                ),
                "javaControlPlaneBoundaryPreserved": True,
            },
            "executionBoundary": "SPECIALIST_ANALYSIS_ONLY_NO_BUSINESS_SIDE_EFFECTS",
            "payloadPolicy": "LOW_SENSITIVE_SPECIALIST_BATCH_RESULT_ONLY",
        }

    def to_runtime_event_action(self) -> dict[str, Any]:
        """生成可进入统一 Runtime Event 的低敏 fan-out 编排事实。

        API 摘要可以携带角色列表和图边名称，但持久事件还会被 WebSocket、Kafka、回放和审计共同消费，
        因此这里进一步只保留有界枚举与计数。专业输入、模型输出、工具参数和具体业务对象均不会进入
        该事件；单个 Specialist 的角色与状态仍由原有动作事件分别记录。
        """

        return {
            "eventType": "SPECIALIST_RUNTIME_FANOUT_COMPLETED",
            "action": "specialist.runtime_fanout.completed",
            "status": self.status,
            "publicSummary": "Specialist 运行时动态派发已完成。",
            "statistics": {
                "dynamicDispatchCount": max(0, self.dynamic_dispatch_count),
                "subgraphInvocationCount": max(0, self.subgraph_invocation_count),
                "executionWaveCount": len(self.execution_waves),
                "graphNodeCount": len(self.fanout_graph_nodes),
                "graphEdgeCount": len(self.fanout_graph_edges),
            },
            "attributes": {
                "orchestrationEngine": self.orchestration_engine,
                "dispatchMode": self.dispatch_mode,
                "runtimeSelectedRoster": (
                    self.orchestration_engine == "langgraph"
                    and self.dynamic_dispatch_count > 0
                ),
            },
        }


class SpecialistAgentCoordinator:
    """依据 turn runner 与执行会话事实推进真实专业 Agent。

    协调器遵循三个关键原则：
    1. **事实驱动**：只消费既有调度/turn attempt，不根据 objective 临时发明角色；
    2. **依赖驱动**：同一依赖波次可并发，但上游需要补参或失败时不会启动下游；
    3. **权限不放大**：每个 turn 获得独立 delegationId 和显式工具白名单，下游仍需再次做业务授权。
    """

    def __init__(
        self,
        registry: SpecialistAgentRegistry,
        *,
        default_budget: SpecialistTurnBudget | None = None,
        result_sink: SpecialistTurnResultSink | None = None,
        fanout_executor: LangGraphSpecialistFanoutExecutor | None = None,
    ) -> None:
        """创建专业 Agent 协调器。

        ``result_sink`` 是一次专业 turn 的事实登记边界。它可以是普通的二参数函数，也可以是
        ``JavaSpecialistTurnFactClient`` 实例本身，因为事实客户端实现了同样的可调用协议。
        协调器只把已经确定的 ``SpecialistTurnRequest`` 和 ``SpecialistTurnResult`` 交给它，
        不会把 objective、工具参数或其它未经过结果合同约束的正文单独拼接进登记请求。

        登记器的可靠性策略由登记器自己负责：
        - fail-open 返回失败 receipt 时，协调器忽略 receipt 并继续依赖判断；
        - fail-closed 抛出异常时，协调器不伪造成功，也不把登记失败改写成专业 Agent 失败。
        这样事实链路的审计策略不会被协调器中的业务依赖逻辑悄悄覆盖。
        """

        self._registry = registry
        self._default_budget = default_budget or SpecialistTurnBudget()
        self._result_sink = result_sink
        # 执行器延迟编译 LangGraph，因此应用启动时不会调用模型或访问外部服务。注入点保留给聚焦测试，
        # 生产默认使用真实 Send + Specialist 子图，LangGraph 缺失时在首个真实 turn 上 fail-closed。
        self._fanout_executor = fanout_executor or LangGraphSpecialistFanoutExecutor(
            self._execute_and_record
        )

    def run(
        self,
        *,
        request: AgentRequest,
        turn_runner: Mapping[str, Any],
        execution_session: Mapping[str, Any],
        allowed_tools_by_role: Mapping[str, tuple[str, ...]],
        base_context: Mapping[str, Any] | None = None,
        checkpoint_recorded: bool,
        event_sink: SpecialistEventSink | None = None,
        result_sink: SpecialistTurnResultSink | None = None,
    ) -> SpecialistExecutionBatchResult:
        """执行本轮符合条件的专业 turn。

        `checkpoint_recorded` 必须由调用方根据真实 checkpointer 写入结果提供，不能用配置开关伪造。没有
        checkpoint 时返回显式阻断结果，以保证服务重启后仍能解释“为什么没有开始专业 Agent”。

        ``result_sink`` 可以在某一次运行中覆盖构造器上的登记器，便于任务级租户或测试注入不同的
        事实存储；不传时使用构造器配置。每一个真正调用过注册表的角色，无论返回 COMPLETED、
        WAITING_FOR_INPUT、CANCELLED，还是注册表抛异常后转换出的 FAILED，都只登记一次。
        """

        if not checkpoint_recorded:
            return SpecialistExecutionBatchResult(
                status="BLOCKED_CHECKPOINT_REQUIRED",
                results=(),
                skipped_roles={"*": "TURN_CHECKPOINT_REQUIRED"},
                execution_waves=(),
            )

        # 项目是所有专业工具查询的最小资源边界。即使下游客户端自己还会做 RBAC，
        # 协调器也不能在缺失项目范围时把请求送到任何 Agent，否则会形成“先访问控制面、
        # 后发现范围不完整”的审计漏洞。
        if not self._has_project_scope(request.project_id):
            return SpecialistExecutionBatchResult(
                status="BLOCKED_PROJECT_SCOPE_REQUIRED",
                results=(),
                skipped_roles={"*": "PROJECT_SCOPE_REQUIRED"},
                execution_waves=(),
            )

        attempts, attempt_rejections = self._eligible_attempts_with_rejections(turn_runner)
        available_roles = set(self._registry.available_roles())
        work_items = self._work_items_by_role(execution_session)
        skipped: dict[str, str] = dict(attempt_rejections)
        shared_context = dict(base_context or {})
        pending: dict[AgentSessionRole, Mapping[str, Any]] = {}
        for role, attempt in attempts.items():
            if role not in available_roles:
                skipped[role.value] = "SPECIALIST_NOT_REGISTERED"
                continue
            if role.value not in work_items:
                # attempt 和 work item 必须一一对应。没有 work item 时无法验证依赖、
                # durable checkpoint 或上游 handoff，宁可跳过也不能凭空执行。
                skipped[role.value] = "WORK_ITEM_NOT_FOUND"
                continue
            if role.value not in allowed_tools_by_role:
                skipped[role.value] = "TOOL_ALLOWLIST_NOT_ASSIGNED"
                continue
            if role == AgentSessionRole.PRECHECK_AGENT:
                precheck_locators = self._monitor_resource_locators(shared_context)
                if not precheck_locators:
                    # Java 确定性预检接口只能校验已持久化的同步任务。规划元数据可供 DATA_SYNC_AGENT
                    # 使用，但它不是 taskId，不能假装草稿已经落库后传给预检适配器。可信 Java 回执
                    # 给出真实资源后，确认后的复核波次会再次调度 PRECHECK。
                    skipped[role.value] = "RUNTIME_RESOURCE_NOT_AVAILABLE_YET"
                    continue
                shared_context.update(precheck_locators)
            if role == AgentSessionRole.RECOVERY_AGENT:
                recovery_context = self._recovery_failure_context(shared_context)
                if not recovery_context:
                    # Recovery 不是成功规划的必经阶段。只有控制面给出一个确定的失败 execution 后才允许
                    # 进入；否则诊断客户端会拿规划数据查找不存在的运行，并持久化虚假专业失败。稳定的
                    # 跳过原因也用于通知失败后编排：可信失败事实到达后需要重新调度 Recovery turn。
                    skipped[role.value] = "FAILED_EXECUTION_NOT_AVAILABLE_YET"
                    continue
                # 只提升 fail-closed 辅助方法选出的规范定位和失败标记。无论 Gateway/Java 最初把事实放在
                # failureContext、recoveryContext 还是 controlPlaneFacts，RecoverySpecialistAgent 最终都
                # 只接收同一份规范顶层合同。
                shared_context.update(recovery_context)
            if role == AgentSessionRole.MONITOR_AGENT:
                monitor_locators = self._monitor_resource_locators(shared_context)
                if not monitor_locators:
                    # 规划 turn 尚无持久任务；此时调用 MONITOR 会把预期中的 taskId 缺失变成
                    # MONITOR_TASK_ID_REQUIRED，并登记虚假失败。这里用稳定生命周期原因跳过；可信 Java
                    # 回执给出真实任务定位后，资源后复核会重新调度 MONITOR turn。
                    skipped[role.value] = "RUNTIME_RESOURCE_NOT_AVAILABLE_YET"
                    continue
                # 嵌套恢复/监控上下文和控制面事实数组都是允许的生命周期载体，但 MonitorSpecialistAgent
                # 只读取规范顶层 taskId。这里只提升经过校验的十进制定位，保持下游合同确定性。
                shared_context.update(monitor_locators)
            pending[role] = attempt

        if not pending:
            return SpecialistExecutionBatchResult(
                status="NO_EXECUTABLE_SPECIALISTS",
                results=(),
                skipped_roles=skipped,
                execution_waves=(),
            )

        results_by_role: dict[AgentSessionRole, SpecialistTurnResult] = {}
        waves: list[tuple[str, ...]] = []
        dispatched_roles: list[str] = []
        dynamic_dispatch_count = 0
        subgraph_invocation_count = 0
        orchestration_engine = "none"
        dispatch_mode = "NONE"
        fanout_graph_nodes: tuple[str, ...] = ()
        fanout_graph_edges: tuple[str, ...] = ()
        max_concurrency = max(1, min(int(turn_runner.get("maxConcurrentAgentTurns") or 1), 8))
        effective_result_sink = result_sink if result_sink is not None else self._result_sink

        while pending:
            ready_roles = tuple(
                role
                for role in sorted(pending, key=lambda item: item.value)
                if self._dependencies_ready(role, work_items, pending, results_by_role, skipped)
            )
            if not ready_roles:
                for role in pending:
                    skipped[role.value] = "DEPENDENCY_NOT_COMPLETED"
                break

            wave_roles = ready_roles[:max_concurrency]
            waves.append(tuple(role.value for role in wave_roles))
            wave_requests = {
                role: self._build_turn_request(
                    request=request,
                    attempt=pending[role],
                    execution_session=execution_session,
                    role=role,
                    allowed_tools=allowed_tools_by_role[role.value],
                    base_context=shared_context,
                    dependency_results=results_by_role,
                )
                for role in wave_roles
            }
            wave_execution = self._execute_wave(
                wave_requests,
                event_sink,
                effective_result_sink,
            )
            orchestration_engine = wave_execution.engine
            dispatch_mode = wave_execution.dispatch_mode
            dynamic_dispatch_count += wave_execution.dynamic_dispatch_count
            subgraph_invocation_count += wave_execution.subgraph_invocation_count
            dispatched_roles.extend(wave_execution.dispatched_roles)
            fanout_graph_nodes = wave_execution.graph_nodes
            fanout_graph_edges = wave_execution.graph_edges
            for role, result in wave_execution.results.items():
                results_by_role[role] = result
                pending.pop(role, None)

        ordered_results = tuple(results_by_role[role] for role in sorted(results_by_role, key=lambda item: item.value))
        return SpecialistExecutionBatchResult(
            status=self._batch_status(ordered_results, skipped),
            results=ordered_results,
            skipped_roles=skipped,
            execution_waves=tuple(waves),
            orchestration_engine=orchestration_engine,
            dispatch_mode=dispatch_mode,
            dynamic_dispatch_count=dynamic_dispatch_count,
            subgraph_invocation_count=subgraph_invocation_count,
            dispatched_roles=tuple(dispatched_roles),
            fanout_graph_nodes=fanout_graph_nodes,
            fanout_graph_edges=fanout_graph_edges,
        )

    def _execute_wave(
        self,
        requests: Mapping[AgentSessionRole, SpecialistTurnRequest],
        event_sink: SpecialistEventSink | None,
        result_sink: SpecialistTurnResultSink | None,
    ) -> SpecialistFanoutExecution:
        """通过 LangGraph 动态 Send 执行一个没有相互依赖的角色波次。

        协调器在进入本方法前已经按依赖和并发预算选择了本波次角色，因此 Send 数量始终小于等于
        ``maxConcurrentAgentTurns``。执行器内部的 Specialist 子图继续调用 ``_execute_and_record``，
        原有异常转 FAILED、可信双主体绑定和 Java 事实登记语义不会因切换编排引擎而丢失。
        """

        return self._fanout_executor.execute(
            requests,
            event_sink=event_sink,
            result_sink=result_sink,
        )

    def _execute_and_record(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        result_sink: SpecialistTurnResultSink | None,
    ) -> SpecialistTurnResult:
        """执行一个专业 turn，并把最终结果交给事实登记器一次。

        这里刻意把 ``try`` 只包住注册表执行，不包住 ``result_sink``：
        - 专业 Agent、角色回传合同或运行时工具异常，会被转换为一个可审计的 FAILED 结果；
        - 事实登记器的 fail-closed 异常必须原样向上传播，不能被伪装成 Agent 业务失败；
        - fail-open 客户端返回的失败 receipt 不会抛异常，因此自然不会阻断依赖角色。

        无论结果是哪一种终态，sink 都只在这里调用一次。其返回值（例如 Java receipt）不改变
        ``SpecialistTurnResult``，因为事实写回是否成功与专业 Agent 的业务结果是两个独立维度。
        """

        try:
            result = self._registry.execute(request, event_sink)
        except Exception as exc:  # noqa: BLE001 - 统一转换为低敏 FAILED 事实。
            result = SpecialistTurnResult(
                agent_id=f"{request.role.value.lower()}-runtime",
                role=request.role,
                turn_id=request.turn_id,
                status=SpecialistTurnStatus.FAILED,
                public_summary="专业 Agent 本轮执行失败，已停止依赖该结果的后续委派。",
                error_code=type(exc).__name__.upper(),
            )

        # 专业 Agent 的 structured_output 属于不可信建议，不能让它自行声明 tenant、project、
        # session、run 或 delegation。协调器已经持有本轮不可变 SpecialistTurnRequest，因此在
        # 结果离开执行边界前附加一份不公开的可信绑定。后续 Bridge 可以用它绑定审批来源，同时
        # 继续把 Java feedback 的 runId 仅用于结果引用定位，避免两类 Run 共用一个字段。
        result = replace(
            result,
            delegated_scope_binding=self._delegated_scope_binding(request),
        )

        if result_sink is not None:
            result_sink(request, result)
        return result

    @staticmethod
    def _delegated_scope_binding(request: SpecialistTurnRequest) -> dict[str, str | None]:
        """从协调器创建的 turn request 生成专业结果的可信双主体绑定。

        返回值不包含 objective、工具参数或证据正文，只保留审批和审计所需的稳定身份。
        ``userId`` 与 ``actorId`` 当前同值，分别表达业务资源所有者和本次操作主体；未来接入
        service account 代办时可以在不修改 Bridge 合同的前提下拆成两个独立字段。
        """

        return {
            "tenantId": request.scope.tenant_id,
            "applicationId": request.scope.application_id,
            "projectId": request.scope.project_id,
            "actorId": request.scope.actor_id,
            "userId": request.scope.actor_id,
            "sessionId": request.session_id,
            "runId": request.run_id,
            "delegationId": request.scope.delegation_id,
        }

    @staticmethod
    def _eligible_attempts(turn_runner: Mapping[str, Any]) -> dict[AgentSessionRole, Mapping[str, Any]]:
        """从低敏 turn runner 摘要中筛选真正可执行的专业角色。

        这是一个兼容性的窄包装。真正的调度入口使用
        ``_eligible_attempts_with_rejections``，因为重复角色不能静默覆盖；保留本方法
        便于已有单元测试和诊断代码只需要角色映射时继续工作。
        """

        attempts, _ = SpecialistAgentCoordinator._eligible_attempts_with_rejections(turn_runner)
        return attempts

    @staticmethod
    def _eligible_attempts_with_rejections(
        turn_runner: Mapping[str, Any],
    ) -> tuple[dict[AgentSessionRole, Mapping[str, Any]], dict[str, str]]:
        """解析 turn attempt，并对重复角色执行 fail-closed 处理。

        ``dict`` 赋值会让后一个同角色 attempt 覆盖前一个 attempt，导致 turnId、checkpoint
        和审计事实不再确定。发现重复角色时本方法会移除该角色的全部候选，并返回稳定的拒绝码，
        让上层把它展示为跳过，而不是选择一个“看起来最新”的 attempt。
        """

        attempts: dict[AgentSessionRole, Mapping[str, Any]] = {}
        rejected: dict[str, str] = {}
        duplicate_roles: set[AgentSessionRole] = set()
        for raw_attempt in turn_runner.get("turnAttempts") or ():
            if not isinstance(raw_attempt, Mapping):
                continue
            turn_status = str(raw_attempt.get("turnStatus") or "").upper()
            try:
                role = AgentSessionRole(str(raw_attempt.get("agentRole") or ""))
            except ValueError:
                continue
            allowed_statuses = (
                _KNOWLEDGE_READ_ONLY_TURN_STATUSES
                if role == AgentSessionRole.KNOWLEDGE_AGENT
                else _RECOVERY_HANDOFF_TURN_STATUSES
                if role == AgentSessionRole.RECOVERY_AGENT
                else _EXECUTABLE_TURN_STATUSES
            )
            if turn_status not in allowed_statuses:
                continue
            if role == AgentSessionRole.MASTER_ORCHESTRATOR:
                continue
            if role in duplicate_roles:
                continue
            if role in attempts:
                attempts.pop(role, None)
                duplicate_roles.add(role)
                rejected[role.value] = "DUPLICATE_TURN_ATTEMPTS"
                continue
            attempts[role] = raw_attempt
        return attempts, rejected

    @staticmethod
    def _work_items_by_role(execution_session: Mapping[str, Any]) -> dict[str, Mapping[str, Any]]:
        """建立角色到执行会话工作项的索引，用于依赖判断。"""

        return {
            str(item.get("agentRole")): item
            for item in execution_session.get("workItems") or ()
            if isinstance(item, Mapping) and item.get("agentRole")
        }

    @staticmethod
    def _dependencies_ready(
        role: AgentSessionRole,
        work_items: Mapping[str, Mapping[str, Any]],
        pending: Mapping[AgentSessionRole, Mapping[str, Any]],
        results: Mapping[AgentSessionRole, SpecialistTurnResult],
        skipped: Mapping[str, str],
    ) -> bool:
        """判断专业 Agent 的已注册依赖是否已经成功完成。"""

        # 调用方会在进入这里前检查自身 work item，但保留此防御式判断，避免未来
        # 其它入口直接调用该方法时把不存在的角色当成“无依赖”放行。
        item = work_items.get(role.value)
        if not isinstance(item, Mapping):
            return False
        dependencies = tuple(str(value) for value in item.get("dependsOnRoles") or ())
        for dependency_name in dependencies:
            if dependency_name == AgentSessionRole.MASTER_ORCHESTRATOR.value:
                continue
            try:
                dependency_role = AgentSessionRole(dependency_name)
            except ValueError:
                # 未知依赖不能被当作已经完成；否则新增或拼写错误的依赖会静默失效。
                return False
            if dependency_name not in work_items:
                # 依赖虽然是合法角色，但执行会话没有为它创建 work item，无法证明
                # 它本轮被调度、完成并经过 checkpoint，因此必须阻断下游。
                return False
            if dependency_role in pending:
                return False
            dependency_result = results.get(dependency_role)
            if dependency_result is not None and dependency_result.status != SpecialistTurnStatus.COMPLETED:
                return False
            if dependency_name in skipped:
                return False
            if dependency_result is None:
                # 依赖没有等待、结果或跳过记录，说明它没有进入本轮可验证生命周期。
                return False
        return True

    def _build_turn_request(
        self,
        *,
        request: AgentRequest,
        attempt: Mapping[str, Any],
        execution_session: Mapping[str, Any],
        role: AgentSessionRole,
        allowed_tools: tuple[str, ...],
        base_context: Mapping[str, Any],
        dependency_results: Mapping[AgentSessionRole, SpecialistTurnResult],
    ) -> SpecialistTurnRequest:
        """把主 Agent 请求、委派范围和上游低敏结果组合为专业 turn。"""

        turn_id = str(attempt.get("turnId") or "").strip()
        session_id = str(execution_session.get("sessionId") or "").strip()
        run_id = str(execution_session.get("runId") or request.request_id or "").strip()
        application_id = self._trusted_context_value(request.variables, "applicationId")
        dependency_context = {
            dependency_role.value: result.to_summary()
            for dependency_role, result in dependency_results.items()
            if result.status == SpecialistTurnStatus.COMPLETED
        }
        context = {
            **dict(base_context),
            "dependencyResults": dependency_context,
            "locale": request.locale,
        }
        delegation_id = self._delegation_id(request, session_id, run_id, turn_id, role)
        return SpecialistTurnRequest(
            turn_id=turn_id,
            session_id=session_id,
            run_id=run_id,
            role=role,
            objective=request.objective,
            scope=SpecialistDelegationScope(
                tenant_id=request.tenant_id,
                application_id=application_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                delegation_id=delegation_id,
                allowed_tool_names=allowed_tools,
            ),
            budget=self._default_budget,
            context_summary=context,
        )

    @staticmethod
    def _delegation_id(
        request: AgentRequest,
        session_id: str,
        run_id: str,
        turn_id: str,
        role: AgentSessionRole,
    ) -> str:
        """生成绑定当前父委派的稳定子委派 ID。

        Java session 委派代表用户对主 Agent 的授权。Specialist 不能复用该身份，因为每个角色/turn 都有
        更窄的工具白名单和独立审计事实。摘要材料纳入可信父委派后会形成确定性子链接：Java 持有相同的
        作用域、父委派、turn 和角色，可在把事实视为完成证据前重算该值。系统只持久化摘要前缀，不把
        原始作用域值嵌入标识符。
        """

        parent_delegation_id = (
            SpecialistAgentCoordinator._trusted_context_value(request.variables, "delegationId") or ""
        )
        material = "|".join(
            (
                request.tenant_id,
                request.project_id,
                request.actor_id,
                session_id,
                run_id,
                parent_delegation_id,
                turn_id,
                role.value,
            )
        )
        return f"delegation-{hashlib.sha256(material.encode('utf-8')).hexdigest()[:24]}"

    @staticmethod
    def _trusted_context_value(variables: Mapping[str, Any], field_name: str) -> str | None:
        """只从 gateway 重建的 trustedControlPlane 读取应用维度。"""

        trusted = variables.get("trustedControlPlane")
        if not isinstance(trusted, Mapping):
            return None
        value = str(trusted.get(field_name) or "").strip()
        return value or None

    @classmethod
    def _monitor_resource_locators(cls, context: Mapping[str, Any]) -> dict[str, str]:
        """提取 MONITOR 可消费的规范化任务/执行定位。

        MONITOR_AGENT 读取的是已经创建的 data-sync 任务，而不是模型尚未落库的同步规划。因此协调器
        只接受顶层、受控监控/恢复/失败上下文，以及 ``controlPlaneFacts`` 中的正整数 ``taskId``。
        不扫描任意递归对象，也不从自然语言、资源引用或 ``executionId`` 猜测 taskId，避免把另一个
        项目的数字、模型虚构值或执行实例编号误当作任务主键。Java 成功回执产生真实 ID 后，后置复核
        会重新调度 MONITOR；在此之前跳过并不是执行失败，也不应登记 FAILED 专业事实。

        返回值统一使用十进制字符串，因为 HTTP/JSON 和 Java ``Long`` 回执在 Python 边界可能表现为
        ``int`` 或数字字符串，两者代表同一个受控主键。``executionId`` 不是任务监控的必填项；但调用方
        一旦显式提供，就必须同样是正整数，避免审计快照携带互相矛盾的执行定位。
        """

        candidate_contexts: list[Mapping[str, Any]] = [context]
        for context_name in (
            "monitoringRequest",
            "monitorRequest",
            "monitoringContext",
            "recoveryContext",
            "failureContext",
        ):
            nested = context.get(context_name)
            if isinstance(nested, Mapping):
                candidate_contexts.append(nested)

        control_plane_facts = context.get("controlPlaneFacts")
        if isinstance(control_plane_facts, Mapping):
            candidate_contexts.append(control_plane_facts)
        elif isinstance(control_plane_facts, (tuple, list)):
            candidate_contexts.extend(
                fact for fact in control_plane_facts if isinstance(fact, Mapping)
            )

        task_values = tuple(
            value
            for candidate in candidate_contexts
            for value in (
                candidate.get("taskId"),
                candidate.get("task_id"),
                candidate.get("monitorTaskId"),
                candidate.get("monitor_task_id"),
            )
            if value is not None
        )
        execution_values = tuple(
            value
            for candidate in candidate_contexts
            for value in (candidate.get("executionId"), candidate.get("execution_id"))
            if value is not None
        )
        task_id = next(
            (str(value).strip() for value in task_values if cls._is_positive_integer_identifier(value)),
            None,
        )
        if task_id is None:
            return {}
        if execution_values and not all(cls._is_positive_integer_identifier(value) for value in execution_values):
            return {}

        locators = {"taskId": task_id}
        execution_id = next((str(value).strip() for value in execution_values), None)
        if execution_id is not None:
            locators["executionId"] = execution_id
        return locators

    @classmethod
    def _recovery_failure_context(cls, context: Mapping[str, Any]) -> dict[str, str]:
        """在准入 ``RECOVERY_AGENT`` 前提取一个确定的失败 execution。

        Recovery 与监控的要求不同：只要存在 ``taskId`` 就能观察任务，但修复故障必须同时指明失败任务、
        失败 execution 和显式失败标记。这三项事实必须来自同一个白名单载体，防止把不同载荷分支中的
        无关 ID 拼成虚假失败上下文。

        本方法只接受控制面拥有的生命周期载体，并主动忽略用户自然语言、RAG 案例证据和依赖摘要，因为
        它们可能描述历史故障，而不是当前待修复 execution。返回值是有界、规范化元数据；原始日志、SQL、
        模型输出和凭据都不会穿过该准入边界。
        """

        candidate_contexts: list[Mapping[str, Any]] = [context]
        for context_name in ("failureContext", "recoveryContext"):
            nested = context.get(context_name)
            if isinstance(nested, Mapping):
                candidate_contexts.append(nested)

        control_plane_facts = context.get("controlPlaneFacts")
        if isinstance(control_plane_facts, Mapping):
            candidate_contexts.append(control_plane_facts)
        elif isinstance(control_plane_facts, (tuple, list)):
            candidate_contexts.extend(
                fact for fact in control_plane_facts if isinstance(fact, Mapping)
            )

        for candidate in candidate_contexts:
            task_id = cls._first_positive_identifier(candidate, "taskId", "task_id")
            execution_id = cls._first_positive_identifier(candidate, "executionId", "execution_id")
            failure_marker = cls._explicit_failure_marker(candidate)
            if task_id is None or execution_id is None or failure_marker is None:
                continue
            return {
                "taskId": task_id,
                "executionId": execution_id,
                failure_marker[0]: failure_marker[1],
            }
        return {}

    @classmethod
    def _first_positive_identifier(cls, context: Mapping[str, Any], *field_names: str) -> str | None:
        """从显式字段白名单中返回第一个有效的正整数数据库标识。"""

        for field_name in field_names:
            value = context.get(field_name)
            if cls._is_positive_integer_identifier(value):
                return str(value).strip()
        return None

    @staticmethod
    def _explicit_failure_marker(context: Mapping[str, Any]) -> tuple[str, str] | None:
        """规范化显式失败码、失败引用或失败终态。

        空字符串以及成功/运行中状态都会被拒绝。240 字符上限刻意小于普通日志行，因为这些字段只是
        路由元数据，不是传递异常正文的通道。状态标记统一规范为 ``failureCode``，使下游诊断合同保持一致。
        """

        for field_name, canonical_name in (
            ("failureCode", "failureCode"),
            ("failure_code", "failureCode"),
            ("failureReference", "failureReference"),
            ("failure_reference", "failureReference"),
        ):
            value = context.get(field_name)
            if isinstance(value, str):
                normalized = value.strip()
                if normalized and len(normalized) <= 240:
                    return canonical_name, normalized

        for field_name in ("executionStatus", "execution_status", "taskStatus", "task_status", "status"):
            normalized = str(context.get(field_name) or "").strip().upper()
            if normalized in {"FAILED", "ERROR", "PARTIALLY_FAILED"}:
                return "failureCode", normalized
        return None

    @staticmethod
    def _is_positive_integer_identifier(value: object) -> bool:
        """校验数据库型资源 ID，显式拒绝布尔值、零、负数、小数和自由文本引用。"""

        if isinstance(value, bool):
            return False
        if isinstance(value, int):
            return value > 0
        if isinstance(value, str):
            normalized = value.strip()
            return normalized.isdecimal() and int(normalized) > 0
        return False

    @staticmethod
    def _has_project_scope(project_id: object) -> bool:
        """判断请求是否携带真实项目边界，而不是空值或通配租户范围。

        Specialist 工具全部围绕项目内数据源、任务、日志或知识证据工作。``*``、``all``
        等通配值不能作为项目授权范围使用；宁可阻断本轮，也不能把一次项目级请求升级为
        租户级查询。
        """

        normalized = str(project_id or "").strip()
        return bool(normalized) and normalized.casefold() not in {"*", "all", "tenant", "tenant_scope"}

    @staticmethod
    def _batch_status(
        results: tuple[SpecialistTurnResult, ...],
        skipped: Mapping[str, str],
    ) -> str:
        """把多个专业 turn 结果压缩为稳定批次状态。"""

        if any(item.status == SpecialistTurnStatus.FAILED for item in results):
            return "PARTIALLY_FAILED"
        if any(item.status == SpecialistTurnStatus.WAITING_FOR_INPUT for item in results):
            return "WAITING_FOR_INPUT"
        if skipped:
            return "PARTIALLY_EXECUTED"
        return "COMPLETED"
