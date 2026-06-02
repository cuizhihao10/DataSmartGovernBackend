"""模型工具调用治理结果的 Runtime Event 适配器。

`ModelToolCallPlanner` 的职责是把模型提出的 tool_calls 治理成候选 `ToolPlan`；它不应该同时关心
前端实时展示、WebSocket 推送、审计回放或事件脱敏。这里单独增加一个事件适配器，是为了把“治理判断”
和“可观察性输出”解耦：后续 AgentOrchestrator、Kafka Consumer、Java agent-runtime 回调都可以复用
同一套事件语义，而不需要把事件拼装逻辑复制到多个流程里。

安全边界说明：
- 本模块不会把模型生成的原始 arguments 值写入事件 attributes。工具参数经常包含数据源 ID、SQL、
  任务 payload、导出路径等敏感信息，直接进入实时事件会扩大泄露面。
- 对于已接受候选，只记录参数字段名、参数校验问题数量、风险等级和审批标记；真实参数值仍留在
  `ToolPlan` 中，后续由 Java 控制面按权限、审批和脱敏策略展示。
- 对于被拒绝候选，只记录工具名、callId 和 issue code，便于用户理解“为什么没执行”，同时避免把
  模型幻觉内容或非法 JSON 作为可见业务事实传播。
"""

from __future__ import annotations

from dataclasses import dataclass

from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_planner import (
    ModelToolCallCandidate,
    ModelToolCallPlanningReport,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder


@dataclass(frozen=True)
class ModelToolCallEventRecordingSummary:
    """模型工具调用事件记录摘要。

    这个摘要主要服务单元测试、诊断接口和后续编排器节点判断。相比直接让调用方自己数事件类型，
    这里提前给出稳定计数，便于未来把该结果写入 metrics 或调试面板。

    字段说明：
    - `proposed_count`：模型本轮一共提出了多少个工具调用候选；
    - `accepted_count`：多少候选已生成 `ToolPlan`，可以进入参数校验、审批或执行控制面；
    - `rejected_count`：多少候选因为未知工具、未暴露工具、非法 JSON、CRITICAL 风险等被阻断；
    - `approval_required_count`：多少候选虽然可规划，但后续必须进入人工审批；
    - `events`：本次实际写入 recorder 的事件快照，保持顺序，便于实时推送和测试断言。
    """

    proposed_count: int = 0
    accepted_count: int = 0
    rejected_count: int = 0
    approval_required_count: int = 0
    events: tuple[AgentRuntimeEvent, ...] = ()


def record_model_tool_call_planning_events(
    recorder: RuntimeEventRecorder,
    report: ModelToolCallPlanningReport,
    stage: str = "plan_model_tool_calls",
) -> ModelToolCallEventRecordingSummary:
    """把模型工具调用候选治理报告写入 Runtime Event。

    该函数的输入是已经治理后的 `ModelToolCallPlanningReport`，因此它不会重新做权限判断、参数校验或
    风险判定。它只负责把报告中的事实转换成可订阅事件：
    1. 先记录一个汇总事件，说明模型提出了多少工具调用；
    2. 再逐个记录“已接受候选”，让前端和审计知道哪些工具计划进入了平台控制面；
    3. 对需要审批的候选追加审批事件，避免高风险动作在 UI 上看起来像普通自动执行；
    4. 最后记录被拒绝候选，帮助定位模型幻觉、schema 暴露不足或参数生成失败。

    返回值不是 recorder 的全部事件，而是本函数本次新增的事件集合。这样后续 AgentOrchestrator 可以
    很容易把“本节点新增事件”和“整次请求事件”分开处理。
    """

    events: list[AgentRuntimeEvent] = []
    candidates = report.candidates
    accepted_candidates = tuple(candidate for candidate in candidates if candidate.accepted)
    rejected_candidates = report.rejected_candidates
    approval_candidates = tuple(candidate for candidate in accepted_candidates if _requires_approval_event(candidate))

    if candidates:
        events.append(
            recorder.record(
                AgentRuntimeEventType.MODEL_TOOL_CALL_PROPOSED,
                stage,
                "模型已提出工具调用候选，平台开始执行最小权限、参数和风险治理。",
                attributes={
                    "proposedCount": len(candidates),
                    "acceptedCount": len(accepted_candidates),
                    "rejectedCount": len(rejected_candidates),
                    "approvalRequiredCount": len(approval_candidates),
                    "candidateToolNames": tuple(candidate.resolved_tool_name for candidate in candidates),
                },
            )
        )

    for candidate in accepted_candidates:
        events.append(_record_accepted_candidate(recorder, candidate, stage))
        if _requires_approval_event(candidate):
            events.append(_record_approval_required_candidate(recorder, candidate, stage))

    for candidate in rejected_candidates:
        events.append(_record_rejected_candidate(recorder, candidate, stage))

    return ModelToolCallEventRecordingSummary(
        proposed_count=len(candidates),
        accepted_count=len(accepted_candidates),
        rejected_count=len(rejected_candidates),
        approval_required_count=len(approval_candidates),
        events=tuple(events),
    )


def _record_accepted_candidate(
    recorder: RuntimeEventRecorder,
    candidate: ModelToolCallCandidate,
    stage: str,
) -> AgentRuntimeEvent:
    """记录已通过治理的模型工具调用候选。

    注意这里记录的是“候选已进入 ToolPlan”，不是“工具已经执行成功”。真实执行还需要 Java 控制面
    继续完成权限、审批、幂等、超时、重试和业务服务调用。把这两个阶段拆开记录，可以避免用户误解
    Agent 的当前状态，也能让审计回放准确还原“模型建议”和“平台执行”之间的边界。
    """

    plan = candidate.tool_plan
    issue_codes = tuple(issue.code for issue in candidate.issues)
    return recorder.record(
        AgentRuntimeEventType.MODEL_TOOL_CALL_ACCEPTED,
        stage,
        f"模型工具调用候选已通过平台治理：{candidate.resolved_tool_name}。",
        attributes={
            "toolName": candidate.resolved_tool_name,
            "modelToolCallId": candidate.source_call.call_id,
            "modelToolCallType": candidate.source_call.type,
            "riskLevel": getattr(plan.risk_level, "value", plan.risk_level) if plan else None,
            "executionMode": getattr(plan.execution_mode, "value", plan.execution_mode) if plan else None,
            "requiresHumanApproval": bool(plan and plan.requires_human_approval),
            "argumentFieldNames": tuple(plan.arguments.keys()) if plan else (),
            "parameterIssueCount": len(plan.parameter_validation.issues) if plan else 0,
            "issueCodes": issue_codes,
        },
    )


def _record_approval_required_candidate(
    recorder: RuntimeEventRecorder,
    candidate: ModelToolCallCandidate,
    stage: str,
) -> AgentRuntimeEvent:
    """记录模型工具调用进入审批等待的事实。

    审批事件使用 `AUDIT` 严重级别，是因为它不仅是前端进度，也是合规事实：模型曾建议执行某个
    高风险或需审批工具，但平台没有直接放行。后续接 Java agent-runtime 时，这类事件应与审批单、
    操作人确认和最终执行结果建立关联。
    """

    plan = candidate.tool_plan
    return recorder.record(
        AgentRuntimeEventType.MODEL_TOOL_CALL_APPROVAL_REQUIRED,
        stage,
        f"模型工具调用需要人工审批后才能继续：{candidate.resolved_tool_name}。",
        severity=AgentRuntimeEventSeverity.AUDIT,
        attributes={
            "toolName": candidate.resolved_tool_name,
            "modelToolCallId": candidate.source_call.call_id,
            "riskLevel": getattr(plan.risk_level, "value", plan.risk_level) if plan else None,
            "executionMode": getattr(plan.execution_mode, "value", plan.execution_mode) if plan else None,
            "issueCodes": tuple(issue.code for issue in candidate.issues),
            "approvalSource": "model_tool_call_governance",
        },
    )


def _record_rejected_candidate(
    recorder: RuntimeEventRecorder,
    candidate: ModelToolCallCandidate,
    stage: str,
) -> AgentRuntimeEvent:
    """记录被治理规则拒绝的模型工具调用候选。

    被拒绝不一定代表系统故障，它可能是正常的安全防线生效：例如模型猜了不存在的工具、尝试调用未暴露
    的工具，或者生成了非法 JSON。事件级别使用 `WARNING`，便于运维和产品侧观察模型工具调用质量，
    但不把它提升为 `ERROR`，避免把可恢复的模型输出问题误判成系统异常。
    """

    blocking_issues = tuple(issue for issue in candidate.issues if issue.blocking)
    return recorder.record(
        AgentRuntimeEventType.MODEL_TOOL_CALL_REJECTED,
        stage,
        f"模型工具调用候选已被平台治理拒绝：{candidate.resolved_tool_name}。",
        severity=AgentRuntimeEventSeverity.WARNING,
        attributes={
            "toolName": candidate.resolved_tool_name,
            "modelToolCallId": candidate.source_call.call_id,
            "modelToolCallType": candidate.source_call.type,
            "issueCodes": tuple(issue.code for issue in candidate.issues),
            "blockingIssueCount": len(blocking_issues),
        },
    )


def _requires_approval_event(candidate: ModelToolCallCandidate) -> bool:
    """判断候选是否需要额外记录审批事件。

    这里同时检查 `ToolPlan.requires_human_approval` 和治理 issue，是为了兼容两类来源：
    - 工具定义本身要求审批，规划器会把该事实写入 `ToolPlan`；
    - 后续更复杂策略可能以非阻断 issue 形式表达“本租户、本项目、本次风险预算要求审批”。
    """

    if candidate.tool_plan and candidate.tool_plan.requires_human_approval:
        return True
    return any(issue.code == "MODEL_TOOL_CALL_APPROVAL_REQUIRED" and not issue.blocking for issue in candidate.issues)
