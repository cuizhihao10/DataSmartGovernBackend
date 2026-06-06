"""A2A Task 控制面合同到 Python Agent 规划决策的适配器。

Java Agent Runtime 5.28-5.30 已经形成 A2A Agent Card、Task 状态机、Task runtime event 和查询预览。
但如果 Python Runtime 仍然只理解本地 `AgentPlan`，这些控制面合同就无法真正进入 Master Agent
会话编排。这个 adapter 的职责就是把 Java 侧低敏 task 查询视图转换成 Python 可消费的规划决策：

- submitted：先做权限、幂等、配额和租户边界预检；
- working：允许规划 worker pre-check 或查询进度，但不直接执行工具；
- input-required：等待用户补充信息，模型不能脑补；
- auth-required：等待授权/审批/权限包更新，凭证不能写进 A2A 消息；
- completed/failed/canceled/rejected：终态只展示事实，不允许自动继续执行；
- unknown：未知状态按 fail-closed 进入诊断。

本模块刻意不调用 HTTP、不查询 Java、不写 runtime event、不执行工具。它只是一个纯函数式适配层，
方便后续被 API 路由、Kafka consumer、LangGraph 节点或离线测试复用。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.domain.protocols import (
    A2aTaskArtifactReference,
    A2aTaskControlPlaneSnapshot,
    A2aTaskHistoryEvent,
    A2aTaskState,
    AgentTaskPlanningDecision,
    AgentTaskPlanningMode,
    AgentTaskPlanningStatus,
    AgentTaskSuggestedAction,
)
from datasmart_ai_runtime.services.agent_gateway.a2a_task_mapping_support import (
    count_forbidden_fields,
    first_mapping,
    first_sequence,
    int_tuple,
    int_value,
    latest_history_sequence,
    safe_text,
    safe_text_tuple,
    value,
)


class A2aTaskPlanningAdapter:
    """把 A2A task 控制面合同转换成 Python Runtime 规划决策。

    真实产品里，Java 控制面是 A2A task fact、权限、审批、outbox、worker receipt 和 artifact
    metadata 的事实源；Python Runtime 更适合做模型编排、会话策略、工具预检和下一步建议。这个类
    正是二者之间的“防腐层”：Java 字段名可以演进，Python 规划语义保持稳定。
    """

    def adapt(self, contract: Mapping[str, Any] | None) -> AgentTaskPlanningDecision:
        """根据 Java/A2A 低敏合同生成规划决策。

        参数 `contract` 支持两类输入：
        - Java 5.30 `AgentA2aTaskQueryPreviewResponse` 风格字典，包含 `task/historyEvents/artifactReferences`；
        - 更靠近真实 A2A task 的扁平字典，直接包含 `currentState/a2aState/internalPhase` 等字段。

        任何未知或缺失状态都会按 fail-closed 处理，这是多租户 Agent 平台的安全默认值。
        """

        if not isinstance(contract, Mapping):
            snapshot = A2aTaskControlPlaneSnapshot()
            return self._diagnostic_decision(snapshot, "A2A_TASK_CONTRACT_MISSING_OR_INVALID")

        sensitive_count = count_forbidden_fields(contract)
        snapshot = self._snapshot_from_contract(contract, sensitive_count=sensitive_count)
        mode = self._planning_mode(snapshot)

        if mode == AgentTaskPlanningMode.PRECHECK_REQUIRED:
            return self._precheck_decision(snapshot)
        if mode == AgentTaskPlanningMode.WORKER_PLANNING_ALLOWED:
            return self._worker_planning_decision(snapshot)
        if mode == AgentTaskPlanningMode.WAIT_FOR_USER_INPUT:
            return self._wait_for_user_decision(snapshot)
        if mode == AgentTaskPlanningMode.WAIT_FOR_AUTHORIZATION:
            return self._wait_for_authorization_decision(snapshot)
        if mode == AgentTaskPlanningMode.TERMINAL_NO_EXECUTION:
            return self._terminal_decision(snapshot)
        return self._diagnostic_decision(snapshot, "A2A_TASK_STATE_UNKNOWN_OR_UNTRUSTED")

    def _snapshot_from_contract(
        self,
        contract: Mapping[str, Any],
        *,
        sensitive_count: int,
    ) -> A2aTaskControlPlaneSnapshot:
        """从多种 Java/A2A 响应形态中抽取低敏 task 快照。

        Java 5.30 的主体字段在 `task` 下，但后续真实 `tasks/get` 或测试可能直接传入扁平 task。
        这里通过 `_first_mapping(...)` 和 `_value(...)` 做兼容，不让调用方关心字段布局。
        """

        task = first_mapping(contract.get("task"), contract.get("taskPreview"), contract)
        history_events = self._history_events(contract, task)
        artifact_refs = self._artifact_references(contract, task)
        state = A2aTaskState.from_value(
            value(task, "currentState", "a2aState", "state", "taskState", default=None)
        )
        latest_sequence = int_value(
            value(task, "sequence", "latestSequence", default=latest_history_sequence(history_events))
        )

        return A2aTaskControlPlaneSnapshot(
            task_public_id=safe_text(value(task, "taskPublicId", "taskId", "id", default="")),
            context_public_id=safe_text(value(task, "contextPublicId", "contextId", default="")),
            state=state,
            internal_phase=safe_text(value(task, "internalPhase", "phase", default="")),
            sequence=latest_sequence,
            terminal=bool(value(task, "terminal", default=state.terminal)),
            interrupted=bool(value(task, "interrupted", default=state.waiting_for_external_input)),
            reason_code=safe_text(value(task, "reasonCode", default="")),
            allowed_client_operations=safe_text_tuple(value(task, "allowedClientOperations", default=())),
            governance_summary=safe_text_tuple(value(task, "governanceSummary", default=())),
            history_events=history_events,
            artifact_references=artifact_refs,
            source_schema_version=safe_text(value(contract, "schemaVersion", default="")),
            source_scenario=safe_text(value(contract, "scenario", default="")),
            preview_only=bool(value(contract, "previewOnly", default=True)),
            task_endpoint_enabled=bool(value(contract, "taskEndpointEnabled", default=False)),
            sensitive_field_ignored_count=sensitive_count,
        )

    def _history_events(
        self,
        contract: Mapping[str, Any],
        task: Mapping[str, Any],
    ) -> tuple[A2aTaskHistoryEvent, ...]:
        """抽取低敏 history 事件。

        只保留 sequence、事件类型、状态、阶段、原因码和 artifactRef。自由文本摘要、消息 parts、
        tool result、错误详情等字段即使存在也会被忽略。
        """

        raw_events = first_sequence(
            contract.get("historyEvents"),
            contract.get("history"),
            task.get("historyEvents"),
            task.get("history"),
        )
        events: list[A2aTaskHistoryEvent] = []
        for raw in raw_events:
            if not isinstance(raw, Mapping):
                continue
            state = A2aTaskState.from_value(value(raw, "a2aState", "state", "currentState", default=None))
            events.append(
                A2aTaskHistoryEvent(
                    sequence=int_value(value(raw, "sequence", default=0)),
                    event_type=safe_text(value(raw, "eventType", "type", default="")),
                    a2a_state=state,
                    internal_phase=safe_text(value(raw, "internalPhase", "phase", default="")),
                    stream_event_kind=safe_text(value(raw, "streamEventKind", default="")),
                    terminal=bool(value(raw, "terminal", default=state.terminal)),
                    reason_code=safe_text(value(raw, "reasonCode", default="")),
                    artifact_ref=safe_text(value(raw, "artifactRef", default="")),
                )
            )
        return tuple(sorted(events, key=lambda event: event.sequence))

    def _artifact_references(
        self,
        contract: Mapping[str, Any],
        task: Mapping[str, Any],
    ) -> tuple[A2aTaskArtifactReference, ...]:
        """抽取 artifact metadata-only 引用。

        如果没有显式 `artifactReferences`，但 history 里出现 artifactRef，当前版本不会反推 artifact 对象。
        这是刻意的：history artifactRef 只能证明“有一个引用出现过”，不能证明 artifact metadata 已经通过
        二次鉴权、保留期和可用性检查。
        """

        raw_refs = first_sequence(
            contract.get("artifactReferences"),
            contract.get("artifacts"),
            task.get("artifactReferences"),
            task.get("artifacts"),
        )
        refs: list[A2aTaskArtifactReference] = []
        for raw in raw_refs:
            if not isinstance(raw, Mapping):
                continue
            artifact_ref = safe_text(value(raw, "artifactRef", "ref", "id", default=""))
            if not artifact_ref:
                continue
            refs.append(
                A2aTaskArtifactReference(
                    artifact_ref=artifact_ref,
                    artifact_type=safe_text(value(raw, "artifactType", "type", default="")),
                    available=bool(value(raw, "available", default=False)),
                    metadata_only=bool(value(raw, "metadataOnly", default=True)),
                    linked_event_sequences=int_tuple(value(raw, "linkedEventSequences", default=())),
                )
            )
        return tuple(refs)

    @staticmethod
    def _planning_mode(snapshot: A2aTaskControlPlaneSnapshot) -> AgentTaskPlanningMode:
        """根据 A2A 状态和 DataSmart 内部阶段计算规划模式。

        内部阶段拥有更强的安全优先级：例如 A2A 外部状态仍是 submitted，但 DataSmart 内部已经进入
        `APPROVAL_WAITING`，Python 侧就必须等待授权，而不是继续推进 worker 预检。
        """

        phase = snapshot.internal_phase.upper()
        if phase == "INPUT_WAITING":
            return AgentTaskPlanningMode.WAIT_FOR_USER_INPUT
        if phase == "APPROVAL_WAITING":
            return AgentTaskPlanningMode.WAIT_FOR_AUTHORIZATION
        if phase in {"DEAD_LETTER", "EXPIRED"}:
            return AgentTaskPlanningMode.TERMINAL_NO_EXECUTION
        if snapshot.state == A2aTaskState.TASK_STATE_SUBMITTED:
            return AgentTaskPlanningMode.PRECHECK_REQUIRED
        if snapshot.state == A2aTaskState.TASK_STATE_WORKING:
            return AgentTaskPlanningMode.WORKER_PLANNING_ALLOWED
        if snapshot.state == A2aTaskState.TASK_STATE_INPUT_REQUIRED:
            return AgentTaskPlanningMode.WAIT_FOR_USER_INPUT
        if snapshot.state == A2aTaskState.TASK_STATE_AUTH_REQUIRED:
            return AgentTaskPlanningMode.WAIT_FOR_AUTHORIZATION
        if snapshot.state.terminal:
            return AgentTaskPlanningMode.TERMINAL_NO_EXECUTION
        return AgentTaskPlanningMode.REJECTED_OR_DIAGNOSTIC

    def _precheck_decision(self, snapshot: A2aTaskControlPlaneSnapshot) -> AgentTaskPlanningDecision:
        """submitted 状态：只允许进入执行前治理检查。"""

        return AgentTaskPlanningDecision(
            mode=AgentTaskPlanningMode.PRECHECK_REQUIRED,
            status=AgentTaskPlanningStatus.READY_FOR_PRECHECK,
            executable=False,
            should_start_worker=False,
            should_wait_for_human=False,
            snapshot=snapshot,
            suggested_actions=(
                AgentTaskSuggestedAction.REQUEST_PERMISSION_PRECHECK,
                AgentTaskSuggestedAction.VALIDATE_IDEMPOTENCY_KEY,
                AgentTaskSuggestedAction.QUERY_TASK_HISTORY,
            ),
            guardrails=self._base_guardrails(snapshot)
            + (
                "PERMISSION_ADMIN_PRECHECK_REQUIRED_BEFORE_ANY_TOOL_EXECUTION",
                "IDEMPOTENCY_KEY_OR_HASH_REQUIRED_FOR_TASK_CONTINUATION",
            ),
            decision_reason="A2A task 已提交但尚未越过 DataSmart 执行前治理边界。",
        )

    def _worker_planning_decision(self, snapshot: A2aTaskControlPlaneSnapshot) -> AgentTaskPlanningDecision:
        """working 状态：允许规划 worker pre-check，但仍不在 adapter 内执行。"""

        return AgentTaskPlanningDecision(
            mode=AgentTaskPlanningMode.WORKER_PLANNING_ALLOWED,
            status=AgentTaskPlanningStatus.READY_FOR_WORKER_PLANNING,
            executable=True,
            should_start_worker=False,
            should_wait_for_human=False,
            snapshot=snapshot,
            suggested_actions=(
                AgentTaskSuggestedAction.PLAN_WORKER_PRECHECK,
                AgentTaskSuggestedAction.QUERY_TASK_HISTORY,
            ),
            guardrails=self._base_guardrails(snapshot)
            + (
                "WORKER_PRECHECK_REQUIRED_BEFORE_SIDE_EFFECT",
                "RUNTIME_EVENT_HISTORY_SHOULD_BE_QUERIED_BEFORE_NEXT_STEP",
            ),
            decision_reason="A2A task 正在处理中，Python 只能规划 worker 预检或查询进度，不直接执行工具。",
        )

    def _wait_for_user_decision(self, snapshot: A2aTaskControlPlaneSnapshot) -> AgentTaskPlanningDecision:
        """input-required 状态：必须等待用户补充信息。"""

        return AgentTaskPlanningDecision(
            mode=AgentTaskPlanningMode.WAIT_FOR_USER_INPUT,
            status=AgentTaskPlanningStatus.WAITING_FOR_USER,
            executable=False,
            should_start_worker=False,
            should_wait_for_human=True,
            snapshot=snapshot,
            suggested_actions=(
                AgentTaskSuggestedAction.ASK_USER_FOR_INPUT,
                AgentTaskSuggestedAction.QUERY_TASK_HISTORY,
            ),
            guardrails=self._base_guardrails(snapshot)
            + (
                "MODEL_MUST_NOT_GUESS_MISSING_USER_INPUT",
                "CONTINUE_REQUEST_MUST_USE_A_NEW_IDEMPOTENCY_KEY",
            ),
            decision_reason="A2A task 正在等待用户输入，Agent 不能自行补齐高风险参数。",
        )

    def _wait_for_authorization_decision(self, snapshot: A2aTaskControlPlaneSnapshot) -> AgentTaskPlanningDecision:
        """auth-required 或审批等待状态：必须等待控制面授权。"""

        return AgentTaskPlanningDecision(
            mode=AgentTaskPlanningMode.WAIT_FOR_AUTHORIZATION,
            status=AgentTaskPlanningStatus.WAITING_FOR_CONTROL_PLANE,
            executable=False,
            should_start_worker=False,
            should_wait_for_human=True,
            snapshot=snapshot,
            suggested_actions=(
                AgentTaskSuggestedAction.REQUEST_AUTHORIZATION,
                AgentTaskSuggestedAction.QUERY_TASK_HISTORY,
            ),
            guardrails=self._base_guardrails(snapshot)
            + (
                "CREDENTIALS_MUST_STAY_OUTSIDE_A2A_MESSAGE_BODY",
                "APPROVAL_OR_PERMISSION_SCOPE_MUST_BE_CONFIRMED_BY_CONTROL_PLANE",
            ),
            decision_reason="A2A task 需要授权或审批，Python Runtime 只能等待控制面事实更新。",
        )

    def _terminal_decision(self, snapshot: A2aTaskControlPlaneSnapshot) -> AgentTaskPlanningDecision:
        """终态：禁止自动继续执行，只允许展示低敏事实或 artifact 引用。"""

        actions: list[AgentTaskSuggestedAction] = [AgentTaskSuggestedAction.STOP_AUTOMATIC_EXECUTION]
        if snapshot.artifact_references:
            actions.append(AgentTaskSuggestedAction.SURFACE_ARTIFACT_REFERENCE)
        actions.append(AgentTaskSuggestedAction.QUERY_TASK_HISTORY)
        return AgentTaskPlanningDecision(
            mode=AgentTaskPlanningMode.TERMINAL_NO_EXECUTION,
            status=AgentTaskPlanningStatus.TERMINAL,
            executable=False,
            should_start_worker=False,
            should_wait_for_human=False,
            snapshot=snapshot,
            suggested_actions=tuple(actions),
            guardrails=self._base_guardrails(snapshot)
            + (
                "TERMINAL_TASK_MUST_NOT_REENTER_WORKING_STATE",
                "ARTIFACT_BODY_REQUIRES_SECONDARY_AUTHORIZATION",
            ),
            decision_reason="A2A task 已进入终态，后续只能查询历史、展示引用或创建新的补偿任务。",
        )

    def _diagnostic_decision(
        self,
        snapshot: A2aTaskControlPlaneSnapshot,
        reason: str,
    ) -> AgentTaskPlanningDecision:
        """未知或不可信合同：fail-closed，交给诊断/控制面处理。"""

        return AgentTaskPlanningDecision(
            mode=AgentTaskPlanningMode.REJECTED_OR_DIAGNOSTIC,
            status=AgentTaskPlanningStatus.BLOCKED,
            executable=False,
            should_start_worker=False,
            should_wait_for_human=True,
            snapshot=snapshot,
            suggested_actions=(
                AgentTaskSuggestedAction.OPEN_DIAGNOSTIC_REVIEW,
                AgentTaskSuggestedAction.STOP_AUTOMATIC_EXECUTION,
            ),
            guardrails=self._base_guardrails(snapshot)
            + (
                "UNKNOWN_OR_UNTRUSTED_A2A_STATE_FAIL_CLOSED",
                "CONTROL_PLANE_CONTRACT_VERSION_REVIEW_REQUIRED",
            ),
            decision_reason=reason,
        )

    @staticmethod
    def _base_guardrails(snapshot: A2aTaskControlPlaneSnapshot) -> tuple[str, ...]:
        """所有决策都必须携带的基础治理边界。"""

        guardrails = [
            "SUMMARY_ONLY_LOW_SENSITIVE_CONTROL_PLANE_FIELDS",
            "RAW_EXECUTION_PAYLOAD_MUST_STAY_IN_PROTECTED_CONTROL_PLANE",
            "PYTHON_ADAPTER_DOES_NOT_CREATE_CANCEL_OR_EXECUTE_A2A_TASKS",
        ]
        if not snapshot.task_public_id:
            guardrails.append("TASK_PUBLIC_ID_MISSING_OR_PREVIEW_ONLY")
        if snapshot.sensitive_field_ignored_count:
            guardrails.append("SENSITIVE_FIELDS_DROPPED_WITHOUT_NAMES_OR_VALUES")
        if snapshot.artifact_references:
            guardrails.append("ARTIFACT_REFERENCE_ONLY_NO_BODY_OR_DOWNLOAD_URL")
        return tuple(guardrails)


def build_a2a_task_planning_decision(contract: Mapping[str, Any] | None) -> dict[str, Any]:
    """便捷函数：返回 A2A task 规划决策摘要。

    API 层或测试如果只需要字典响应，可以调用该函数；需要更强类型对象时则直接使用
    `A2aTaskPlanningAdapter().adapt(...)`。
    """

    return A2aTaskPlanningAdapter().adapt(contract).to_summary()
