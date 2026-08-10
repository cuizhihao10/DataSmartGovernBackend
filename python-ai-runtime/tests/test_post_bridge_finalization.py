"""执行后复核模块的严格单元测试。

本文件专门验证 bridge/Durable 返回真实控制面事实之后的最后一道边界：

* 没有可信 taskId/executionId 时不能凭模型文本启动专业 Agent；
* 新任务只启动一轮 PRECHECK_AGENT + MONITOR_AGENT；
* 同一任务出现新的 executionId 时允许启动下一轮；
* 反馈顺序、重复生命周期节点和普通模型正文不会制造重复波次；
* 最终 readiness、closure、Durable checkpoint 和 turn checkpoint 必须消费 bridge 后计划。

测试只使用 Python 内存替身，不连接数据库、不调用模型、不触发 Java 真实副作用。真正的 Java 回执
信任依据在测试夹具中用 ``auditId + runId + agent-runtime://sessions/...`` 三件套表达，避免把
“看起来像工具结果”的普通文本误当成真实资源事实。
"""

from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest


SRC_ROOT = Path(__file__).resolve().parents[1] / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from datasmart_ai_runtime.api.agent import post_bridge_finalization
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.agent_execution.post_resource_specialist_verification import (
    control_plane_resource_fingerprint as shared_control_plane_resource_fingerprint,
    run_post_bridge_verification_wave as shared_run_post_bridge_verification_wave,
)
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_coordinator import (
    SpecialistExecutionBatchResult,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness import ToolExecutionReadinessPolicy
from datasmart_ai_runtime.services.tools.tool_execution_readiness_policy_provider import (
    ToolExecutionReadinessPolicySnapshot,
)


class RecordingSpecialistCoordinator:
    """记录验证波次输入的协调器替身。

    真实协调器会根据 turn runner、session、角色白名单和 checkpoint 调用六类专业 Agent。这里不运行
    模型，只把每次调用的完整低敏参数保存下来，并主动向事件 sink 写入两条公开动作事件，便于测试
    “首次事件可见、重复指纹没有第二份事件”的幂等行为。
    """

    def __init__(self) -> None:
        """初始化调用记录和公开事件计数。"""

        self.calls: list[dict[str, Any]] = []

    def run(self, **kwargs: Any) -> SpecialistExecutionBatchResult:
        """模拟一次 PRECHECK/MONITOR 专业 Agent 波次并返回低敏结果。"""

        self.calls.append(kwargs)
        attempts = tuple(kwargs["turn_runner"]["turnAttempts"])
        results: list[SpecialistTurnResult] = []
        for attempt in attempts:
            role = AgentSessionRole(str(attempt["agentRole"]))
            turn_id = str(attempt["turnId"])
            event_sink = kwargs.get("event_sink")
            if event_sink is not None:
                event_sink(
                    {
                        "agentRole": role.value,
                        "turnId": turn_id,
                        "action": "verification.completed",
                        "status": "COMPLETED",
                        "publicSummary": f"{role.value} 已完成只读复核。",
                    }
                )
            results.append(
                SpecialistTurnResult(
                    agent_id=f"{role.value.lower()}-test",
                    role=role,
                    turn_id=turn_id,
                    status=SpecialistTurnStatus.COMPLETED,
                    public_summary=f"{role.value} 测试结果。",
                )
            )
        return SpecialistExecutionBatchResult(
            status="COMPLETED",
            results=tuple(results),
            skipped_roles={},
            execution_waves=(tuple(str(item["agentRole"]) for item in attempts),),
        )


class SummaryValue:
    """提供 ``to_summary`` 的最小控制面替身，避免测试依赖具体工作流实现。"""

    def __init__(self, summary: dict[str, Any]) -> None:
        """保存待返回的低敏摘要。"""

        self.summary = summary

    def to_summary(self) -> dict[str, Any]:
        """返回固定摘要，模拟真实工作流的序列化边界。"""

        return dict(self.summary)


class RecordingReadinessService:
    """记录 readiness 接收的 ToolPlan，验证它没有继续使用 bridge 前计划。"""

    def __init__(self) -> None:
        """初始化最后一次评估输入。"""

        self.tool_plans: tuple[ToolPlan, ...] = ()

    def evaluate(self, tool_plans: Any, **_: Any) -> SimpleNamespace:
        """保存本次 ToolPlan 并返回带标记的准备度报告。"""

        self.tool_plans = tuple(tool_plans)
        return SimpleNamespace(marker="bridge-readiness", blocked_count=0)


class RecordingClosureService:
    """记录 closure 输入，验证闭环摘要基于最新计划。"""

    def __init__(self) -> None:
        """初始化最后一次 closure 输入。"""

        self.plan: AgentPlan | None = None

    def build(self, *, plan: AgentPlan, **_: Any) -> SummaryValue:
        """保存计划并返回标记为 bridge 的 closure 摘要。"""

        self.plan = plan
        return SummaryValue({"source": "bridge-closure"})


class RecordingDurableLoop:
    """记录 Durable loop checkpoint 输入。"""

    def __init__(self) -> None:
        """初始化最后一次 checkpoint 输入。"""

        self.plan: AgentPlan | None = None

    def record(self, *, plan: AgentPlan, **_: Any) -> SummaryValue:
        """保存 bridge 后计划并返回低敏 Durable checkpoint 摘要。"""

        self.plan = plan
        return SummaryValue({"source": "bridge-durable-checkpoint"})


class RecordingCollaborationWorkflow:
    """记录协作图输入，并以固定摘要模拟 LangGraph 协作结果。"""

    plan: AgentPlan | None = None

    @classmethod
    def from_env(cls) -> "RecordingCollaborationWorkflow":
        """模拟按环境变量装配协作工作流。"""

        return cls()

    def run(self, *, plan: AgentPlan, **_: Any) -> SummaryValue:
        """保存最新计划并返回协作摘要。"""

        type(self).plan = plan
        return SummaryValue({"source": "bridge-collaboration"})


class RecordingExecutionPlanWorkflow:
    """记录执行计划图输入，并以固定摘要模拟 LangGraph 执行计划结果。"""

    plan: AgentPlan | None = None

    @classmethod
    def from_env(cls) -> "RecordingExecutionPlanWorkflow":
        """模拟按环境变量装配执行计划工作流。"""

        return cls()

    def run(self, *, plan: AgentPlan, **_: Any) -> SummaryValue:
        """保存最新计划并返回执行计划摘要。"""

        type(self).plan = plan
        return SummaryValue({"source": "bridge-execution-plan"})


class RecordingSessionService:
    """记录多 Agent 执行会话构建输入。"""

    plan: AgentPlan | None = None

    def build(self, *, plan: AgentPlan, **_: Any) -> SummaryValue:
        """保存 bridge 后计划并返回执行会话摘要。"""

        type(self).plan = plan
        return SummaryValue({"source": "bridge-session"})


class RecordingTurnRunner:
    """记录 turn runner 输入，并返回可序列化的低敏 runner 摘要。"""

    def __init__(self) -> None:
        """初始化最后一次 turn runner 计划。"""

        self.plan: AgentPlan | None = None

    def run(self, *, plan: AgentPlan, **_: Any) -> SummaryValue:
        """保存 bridge 后计划并返回 turn runner 摘要。"""

        self.plan = plan
        return SummaryValue({"source": "bridge-turn-runner"})


class RecordingExecutionGate:
    """记录 execution gate 接收的 readiness 报告。"""

    readiness: Any = None

    @classmethod
    def from_env(cls) -> "RecordingExecutionGate":
        """模拟按环境变量装配 execution gate。"""

        return cls()

    def run(self, readiness: Any) -> "RecordingExecutionGate":
        """保存 bridge 后 readiness 并返回自身作为 gate 结果。"""

        type(self).readiness = readiness
        return self

    def to_summary(self) -> dict[str, Any]:
        """返回低敏 gate 摘要。"""

        return {"source": "bridge-gate"}


def _request() -> AgentRequest:
    """构造带稳定 requestId 的测试请求，保证验证 turnId 可重复计算。"""

    return AgentRequest(
        tenant_id="tenant-1",
        project_id="project-101",
        actor_id="user-1",
        objective="验证 bridge 后同步任务。",
        request_id="request-1",
    )


def _plan(*tool_names: str) -> AgentPlan:
    """构造只包含工具名称的最小 AgentPlan，避免测试携带业务参数。"""

    return AgentPlan(
        request_id="request-1",
        selected_route=None,
        state_trace=("bridge",),
        tool_plans=tuple(
            ToolPlan(tool_name=name, reason="post-bridge test") for name in tool_names
        ),
        requires_human_approval=False,
        response_summary="测试计划。",
    )


def _java_feedback_item(
    *,
    result: dict[str, Any],
    tool_name: str = "sync.task.draft.save",
    status: ToolExecutionFeedbackStatus = ToolExecutionFeedbackStatus.SUCCEEDED,
    audit_id: str = "audit-1",
    run_id: str = "run-1",
    session_id: str = "session-1",
) -> AgentControlPlaneFeedbackItem:
    """构造形似 Java 客户端结果的反馈条目。

    ``output_ref`` 有意严格使用 Java 客户端的路径格式；如果生产代码放宽其中任一条件，相关安全测试
    会失败，从而提醒维护者不能让模型或模拟回退结果伪造真实同步资源。
    """

    output_ref = f"agent-runtime://sessions/{session_id}/runs/{run_id}/tool-executions/{audit_id}/result"
    return AgentControlPlaneFeedbackItem(
        model_tool_call_id="call-1",
        tool_name=tool_name,
        status=status,
        summary="Java 控制面反馈摘要。",
        result=result,
        audit_id=audit_id,
        run_id=run_id,
        output_ref=output_ref,
    )


def _feedback(*items: AgentControlPlaneFeedbackItem) -> AgentControlPlaneFeedbackSnapshot:
    """把反馈条目包成控制面快照，模拟真实 Collector 输出。"""

    return AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=len(items),
        feedback_items=items,
        missing_tool_call_ids=(),
        status_counts={"succeeded": len(items)},
        second_turn_eligible=True,
        recommended_actions=(),
    )


def _run_wave(
    coordinator: RecordingSpecialistCoordinator,
    feedback: AgentControlPlaneFeedbackSnapshot,
    *,
    previous_fingerprint: str | None,
    event_sink: list[dict[str, Any]],
    base_context: dict[str, Any] | None = None,
) -> tuple[Any, dict[str, Any]]:
    """用固定权限、session 和 checkpoint 运行一次 post-bridge 验证波次。"""

    return post_bridge_finalization.run_post_bridge_verification_wave(
        request=_request(),
        plan=_plan("sync.task.draft.save"),
        control_plane_feedback=feedback,
        previous_resource_fingerprint=previous_fingerprint,
        specialist_agent_coordinator=coordinator,
        specialist_allowed_tools_by_role={
            "PRECHECK_AGENT": ("sync.task.precheck",),
            "MONITOR_AGENT": ("sync.execution.status",),
        },
        checkpoint_recorded=True,
        event_sink=event_sink.append,
        base_context=base_context if base_context is not None else {"base": "context"},
        execution_session={"sessionId": "session-1", "runId": "python-run-1", "workItems": ()},
    )


def test_api_module_reexports_the_single_post_resource_verification_policy() -> None:
    """Keep API compatibility without allowing a second security policy to drift.

    The initial plan bridge imports these names from the API module for historical
    reasons, while post-confirm continuation imports the execution-layer service
    directly.  Identity checks make that compatibility seam explicit: both paths
    must execute the same implementation that validates Java audit/run/output
    references, allow-listed successful tool receipts, positive identifiers and
    idempotent resource fingerprints.
    """

    assert (
        post_bridge_finalization.control_plane_resource_fingerprint
        is shared_control_plane_resource_fingerprint
    )
    assert (
        post_bridge_finalization.run_post_bridge_verification_wave
        is shared_run_post_bridge_verification_wave
    )


def test_no_trusted_resource_skips_without_specialist_or_event() -> None:
    """模型正文中的 taskId 不能启动复核，且不得产生专业 Agent 或事件。"""

    feedback = _feedback(
        _java_feedback_item(
            result={
                "message": "模型建议 taskId=999、executionId=888，但这不是 Java 结构化结果。",
                "modelOutput": {"taskId": 999, "executionId": 888},
            }
        )
    )
    coordinator = RecordingSpecialistCoordinator()
    events: list[dict[str, Any]] = []

    batch, summary = _run_wave(
        coordinator,
        feedback,
        previous_fingerprint=None,
        event_sink=events,
    )

    assert batch is None
    assert summary["status"] == "SKIPPED_NO_TRUSTED_TASK_FACT"
    assert post_bridge_finalization.control_plane_resource_fingerprint(feedback) is None
    assert coordinator.calls == []
    assert events == []


@pytest.mark.parametrize(
    ("tool_name", "status", "result"),
    (
        ("knowledge.rag.query", ToolExecutionFeedbackStatus.SUCCEEDED, {"taskId": 91}),
        ("sync.task.draft.save", ToolExecutionFeedbackStatus.FAILED, {"taskId": 92}),
        ("sync.task.draft.save", ToolExecutionFeedbackStatus.SUCCEEDED, {"taskId": 0}),
        ("sync.task.draft.save", ToolExecutionFeedbackStatus.SUCCEEDED, {"taskId": -1}),
        ("sync.task.draft.save", ToolExecutionFeedbackStatus.SUCCEEDED, {"taskId": "not-an-id"}),
    ),
)
def test_untrusted_or_invalid_resource_facts_are_ignored(
    tool_name: str,
    status: ToolExecutionFeedbackStatus,
    result: dict[str, Any],
) -> None:
    """工具名、成功状态或正整数 Java ID 任一不满足时都必须安全跳过。"""

    feedback = _feedback(
        _java_feedback_item(tool_name=tool_name, status=status, result=result)
    )

    assert post_bridge_finalization.control_plane_resource_fingerprint(feedback) is None


def test_real_java_task_receipt_triggers_precheck_and_monitor_exactly_once() -> None:
    """新 taskId 应精确触发一次 PRECHECK_AGENT 和 MONITOR_AGENT。"""

    feedback = _feedback(_java_feedback_item(result={"taskId": "000101", "state": "DRAFT"}))
    fingerprint = post_bridge_finalization.control_plane_resource_fingerprint(feedback)
    coordinator = RecordingSpecialistCoordinator()
    events: list[dict[str, Any]] = []

    batch, summary = _run_wave(
        coordinator,
        feedback,
        previous_fingerprint=None,
        event_sink=events,
    )

    assert fingerprint is not None
    assert batch is not None
    assert summary["status"] == "EXECUTED"
    assert summary["taskId"] == "101"
    assert summary["executionId"] is None
    assert summary["executedRoles"] == ("PRECHECK_AGENT", "MONITOR_AGENT")
    assert len(coordinator.calls) == 1
    first_call = coordinator.calls[0]
    assert tuple(item["agentRole"] for item in first_call["turn_runner"]["turnAttempts"]) == (
        "PRECHECK_AGENT",
        "MONITOR_AGENT",
    )
    assert first_call["base_context"]["taskId"] == "101"
    assert first_call["base_context"]["resourceReference"] == "101"
    assert first_call["execution_session"]["runId"] == "run-1"
    assert len(events) == 2

    skipped_batch, skipped_summary = _run_wave(
        coordinator,
        feedback,
        previous_fingerprint=fingerprint,
        event_sink=events,
    )

    assert skipped_batch is None
    assert skipped_summary["status"] == "SKIPPED_RESOURCE_FACT_UNCHANGED"
    assert len(coordinator.calls) == 1
    assert len(events) == 2


def test_realistic_java_identifier_lengths_preserve_the_complete_output_reference() -> None:
    """UUID-sized Java IDs must not lose the ``/result`` trust suffix at the Python boundary.

    Production IDs include the ``ags_``/``agr_``/``atea_`` prefixes and make the full URI 164 characters.
    This regression test protects against reusing the shorter audit-scalar limit for an identity-bearing URI:
    truncating it would silently reject every real post-confirm receipt while short unit-test IDs still passed.
    """

    item = _java_feedback_item(
        result={"taskId": 79, "executionId": 1976},
        session_id="ags_cad3d7c901fd41faae0cee6534e43e53",
        run_id="agr_8988bfb4b37147dc822dde2a8da99401",
        audit_id="atea_3dda0f9ecf7a4a64840fde2855f126ea",
    )
    feedback = _feedback(item)

    assert len(item.output_ref or "") > 160
    assert (item.output_ref or "").endswith("/result")
    assert post_bridge_finalization.control_plane_resource_fingerprint(feedback) is not None


def test_pair_mismatch_does_not_combine_task_and_execution_from_different_receipts() -> None:
    """A task-only receipt and an execution-only receipt must not become one locator pair."""

    feedback = _feedback(
        _java_feedback_item(
            result={"taskId": 101},
            audit_id="audit-task-only",
            run_id="run-task-only",
        ),
        _java_feedback_item(
            tool_name="sync.execution.status",
            result={"executionId": 202, "executionState": "RUNNING"},
            audit_id="audit-execution-only",
            run_id="run-execution-only",
        ),
    )
    coordinator = RecordingSpecialistCoordinator()
    events: list[dict[str, Any]] = []

    batch, summary = _run_wave(
        coordinator,
        feedback,
        previous_fingerprint=None,
        event_sink=events,
    )

    assert post_bridge_finalization.control_plane_resource_fingerprint(feedback) is None
    assert batch is None
    assert summary["status"] == "SKIPPED_NO_TRUSTED_TASK_FACT"
    assert summary["taskId"] is None
    assert summary["executionId"] is None
    assert coordinator.calls == []
    assert events == []


def test_new_execution_id_triggers_a_new_wave_and_same_execution_is_deduplicated() -> None:
    """同一 task 的新 executionId 允许新一轮复核，但相同 executionId 不重复。"""

    task_feedback = _feedback(_java_feedback_item(result={"taskId": 101}, audit_id="audit-task"))
    execution_feedback = _feedback(
        _java_feedback_item(
            tool_name="sync.execution.status",
            result={"taskId": 101, "executionId": 202, "executionState": "RUNNING"},
            audit_id="audit-execution",
            run_id="run-2",
        )
    )
    task_fingerprint = post_bridge_finalization.control_plane_resource_fingerprint(task_feedback)
    execution_fingerprint = post_bridge_finalization.control_plane_resource_fingerprint(execution_feedback)
    coordinator = RecordingSpecialistCoordinator()
    events: list[dict[str, Any]] = []

    first_batch, _ = _run_wave(
        coordinator,
        task_feedback,
        previous_fingerprint=None,
        event_sink=events,
    )
    second_batch, second_summary = _run_wave(
        coordinator,
        execution_feedback,
        previous_fingerprint=task_fingerprint,
        event_sink=events,
    )
    third_batch, third_summary = _run_wave(
        coordinator,
        execution_feedback,
        previous_fingerprint=execution_fingerprint,
        event_sink=events,
    )

    assert first_batch is not None
    assert second_batch is not None
    assert second_summary["taskId"] == "101"
    assert second_summary["executionId"] == "202"
    assert third_batch is None
    assert third_summary["status"] == "SKIPPED_RESOURCE_FACT_UNCHANGED"
    assert len(coordinator.calls) == 2
    assert len(events) == 4
    assert coordinator.calls[1]["base_context"]["executionId"] == "202"
    assert coordinator.calls[1]["execution_session"]["runId"] == "run-2"


def test_execution_only_receipt_can_start_monitoring_without_inventing_task_id() -> None:
    """只有真实 executionId 的回执也应复核，但上下文不能伪造 taskId。"""

    feedback = _feedback(
        _java_feedback_item(
            tool_name="sync.execution.status",
            result={"executionId": 303, "executionState": "RUNNING"},
        )
    )
    coordinator = RecordingSpecialistCoordinator()
    events: list[dict[str, Any]] = []

    batch, summary = _run_wave(
        coordinator,
        feedback,
        previous_fingerprint=None,
        event_sink=events,
    )

    assert batch is not None
    assert summary["taskId"] is None
    assert summary["executionId"] == "303"
    context = coordinator.calls[0]["base_context"]
    assert "taskId" not in context
    assert context["executionId"] == "303"
    assert context["executionReference"] == "303"


def test_execution_only_receipt_cannot_inherit_task_locator_from_old_context() -> None:
    """A prior context carrier must not complete an execution-only receipt pair."""

    feedback = _feedback(
        _java_feedback_item(
            tool_name="sync.execution.status",
            result={"executionId": 303, "executionState": "RUNNING"},
        )
    )
    coordinator = RecordingSpecialistCoordinator()
    events: list[dict[str, Any]] = []

    batch, summary = _run_wave(
        coordinator,
        feedback,
        previous_fingerprint=None,
        event_sink=events,
        base_context={
            "taskId": "999",
            "executionReference": "998",
            "controlPlaneFacts": ({"taskId": "999", "executionId": "998"},),
        },
    )

    assert batch is not None
    assert summary["taskId"] is None
    assert summary["executionId"] == "303"
    context = coordinator.calls[0]["base_context"]
    assert "taskId" not in context
    assert context["controlPlaneFacts"] == ({},)
    assert context["executionId"] == "303"
    assert context["executionReference"] == "303"


def test_resource_fingerprint_is_independent_of_feedback_order() -> None:
    """批量 Java 反馈顺序变化不应让同一资源被判定为新资源。"""

    task_item = _java_feedback_item(result={"taskId": 101}, audit_id="audit-task")
    execution_item = _java_feedback_item(
        tool_name="sync.execution.status",
        result={"taskId": 101, "executionId": 202},
        audit_id="audit-execution",
    )

    first = post_bridge_finalization.control_plane_resource_fingerprint(
        _feedback(task_item, execution_item)
    )
    second = post_bridge_finalization.control_plane_resource_fingerprint(
        _feedback(execution_item, task_item)
    )

    assert first is not None
    assert first == second


def test_final_views_and_checkpoints_consume_bridge_plan(monkeypatch: pytest.MonkeyPatch) -> None:
    """最终 readiness、closure、协作图、session、runner 和两个 checkpoint 都使用 bridge 后计划。"""

    readiness_service = RecordingReadinessService()
    closure_service = RecordingClosureService()
    durable_loop = RecordingDurableLoop()
    turn_runner = RecordingTurnRunner()
    bridge_plan = _plan("sync.task.draft.save", "sync.task.precheck", "sync.task.run")
    old_plan = _plan("datasource.source.metadata.read")
    checkpoint_plans: list[AgentPlan] = []

    class RecordingMetrics:
        """记录最终 session/runner 指标调用，确保测试夹具不引入真实监控依赖。"""

        def __init__(self) -> None:
            """初始化摘要列表。"""

            self.summaries: list[dict[str, Any]] = []

        def record_summary(self, summary: dict[str, Any]) -> None:
            """保存一次低敏指标摘要。"""

            self.summaries.append(summary)

    session_metrics = RecordingMetrics()
    runner_metrics = RecordingMetrics()

    monkeypatch.setattr(
        post_bridge_finalization,
        "ToolExecutionReadinessService",
        lambda: readiness_service,
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "build_tool_execution_readiness_response",
        lambda readiness: {"marker": readiness.marker},
    )
    monkeypatch.setattr(post_bridge_finalization, "LangGraphExecutionGateWorkflow", RecordingExecutionGate)
    monkeypatch.setattr(
        post_bridge_finalization,
        "attach_tool_execution_readiness_event",
        lambda plan, **_: plan,
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "attach_agent_execution_gate_event",
        lambda plan, **_: plan,
    )
    monkeypatch.setattr(post_bridge_finalization, "record_agent_execution_gate_metrics", lambda *_, **__: None)
    monkeypatch.setattr(
        post_bridge_finalization,
        "build_command_proposal_context",
        lambda *_, **__: {},
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "build_tool_action_command_proposal_templates",
        lambda **_: ("bridge-command",),
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "AgentExecutionClosureService",
        lambda: closure_service,
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "build_intelligent_gateway_governance_response",
        lambda *_args, **_kwargs: {"agentSessionScheduling": {"source": "bridge"}},
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "LangGraphMultiAgentCollaborationWorkflow",
        RecordingCollaborationWorkflow,
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "LangGraphMultiAgentExecutionPlanWorkflow",
        RecordingExecutionPlanWorkflow,
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "MultiAgentExecutionSessionService",
        RecordingSessionService,
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "attach_agent_execution_session_event",
        lambda plan, **_: plan,
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "attach_agent_turn_runner_event",
        lambda plan, **_: plan,
    )
    monkeypatch.setattr(
        post_bridge_finalization,
        "record_multi_agent_turn_runner_checkpoint",
        lambda _service, **kwargs: checkpoint_plans.append(kwargs["plan"]) or {"source": "bridge-turn-checkpoint"},
    )

    policy_snapshot = ToolExecutionReadinessPolicySnapshot(
        policy=ToolExecutionReadinessPolicy(),
        source="test",
    )
    final_state = post_bridge_finalization.recompute_post_bridge_views(
        request=_request(),
        plan=bridge_plan,
        readiness_policy_snapshot=policy_snapshot,
        control_plane_ingestion=None,
        control_plane_feedback=None,
        runtime_event_feedback=None,
        loop_control_decision=None,
        second_turn_result=None,
        memory_write_proposal=None,
        durable_agent_loop_service=durable_loop,
        multi_agent_execution_session_metrics=session_metrics,
        multi_agent_turn_runner_workflow=turn_runner,
        multi_agent_turn_runner_metrics=runner_metrics,
        langgraph_checkpointer_service=object(),
        langgraph_execution_gate_metrics=None,
        workspace_context=SimpleNamespace(),
        skill_manifest_diagnostics=None,
        plan_runtime_event_sink=lambda _event: None,
    )

    assert final_state["plan"] is bridge_plan
    assert readiness_service.tool_plans == bridge_plan.tool_plans
    assert closure_service.plan is bridge_plan
    assert durable_loop.plan is bridge_plan
    assert RecordingExecutionGate.readiness.marker == "bridge-readiness"
    assert RecordingCollaborationWorkflow.plan is bridge_plan
    assert RecordingExecutionPlanWorkflow.plan is bridge_plan
    assert RecordingSessionService.plan is bridge_plan
    assert turn_runner.plan is bridge_plan
    assert checkpoint_plans == [bridge_plan]
    assert final_state["tool_execution_readiness_response"] == {"marker": "bridge-readiness"}
    assert final_state["agent_execution_closure_summary"] == {"source": "bridge-closure"}
    assert final_state["durable_loop_checkpoint"].to_summary() == {
        "source": "bridge-durable-checkpoint"
    }
    assert final_state["agent_turn_runner_checkpoint"] == {"source": "bridge-turn-checkpoint"}
