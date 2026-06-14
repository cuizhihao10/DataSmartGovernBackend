"""工具动作执行前控制流预览服务。

本模块是 DataSmart Agent tool runtime 的一个关键中间层：它不执行工具，而是把“某个入口想触发工具
或任务动作”统一解释成一份可审计、可展示、可被图编排器消费的控制流快照。

为什么需要独立于 `ToolActionIntakeService` 再做这一层：
- `ToolActionIntakeService` 只回答“入口意图能否归一成 ToolPlan，或是否是 A2A 控制面事实”；
- `ToolExecutionReadinessService` 只回答“ToolPlan 当前是否具备执行准备度”；
- `ToolExecutionReadinessGraphBuilder` 只回答“readiness 结果应该路由到哪个条件分支”；
- 商业化 Agent Host 还需要把上述三者组合成一份完整契约，明确 preview-only、低敏输出、生产化缺口、
  human-in-the-loop、outbox/worker 边界以及下一步动作建议。

这类分层更接近 Codex / Claude Code / OpenAI Agents / LangGraph 的工具调用控制流：模型或外部协议
提出动作后，运行时先暂停在执行前治理节点，等待参数澄清、审批、预算或异步队列，而不是直接产生副作用。
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.domain.contracts import ModelToolCall, ToolDefinition
from datasmart_ai_runtime.services.tools.tool_action_intake import (
    ToolActionIntakeBoundary,
    ToolActionIntakeReport,
    ToolActionIntakeService,
    ToolActionIntakeSource,
)
from datasmart_ai_runtime.services.tools.tool_action_command_proposal_template import (
    build_tool_action_command_proposal_templates,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness import (
    ToolExecutionReadinessPolicy,
    ToolExecutionReadinessReport,
    ToolExecutionReadinessService,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness_graph import (
    build_tool_execution_readiness_graph_response,
)


@dataclass(frozen=True)
class ToolActionControlFlowReport:
    """单次工具动作控制流预览报告。

    字段说明：
    - `source`：动作来源，区分模型 tool_call、MCP tools/call、A2A task/action；
    - `protocol_family`：面向网关和前端展示的协议族，例如 MODEL、MCP、A2A；
    - `intake`：入口归一化报告，说明哪些动作变成了 ToolPlan，哪些在 readiness 前被拒绝；
    - `readiness`：执行准备度报告，只包含低敏决策、字段名、计数和 reason code；
    - `readiness_graph`：执行前条件图，未来可被 LangGraph/OpenClaw 风格编排器映射成条件节点；
    - `control_plane_decision`：A2A 等非 ToolPlan 入口的控制面决策摘要，避免把任务协作事实伪装成工具；
    - `policy_metadata`：本轮策略来源摘要，只允许保存 source、protocol、previewOnly、工具数量等低敏字段。
    """

    source: ToolActionIntakeSource
    protocol_family: str
    intake: ToolActionIntakeReport
    readiness: ToolExecutionReadinessReport
    readiness_graph: Mapping[str, Any]
    control_plane_decision: Mapping[str, Any] | None = None
    policy_metadata: Mapping[str, Any] | None = None

    def to_low_sensitive_response(
        self,
        *,
        schema_version: str = "datasmart.python-ai-runtime.tool-action-control-flow-preview.v1",
        route: Mapping[str, Any] | None = None,
        input_payload_policy: Mapping[str, Any] | None = None,
        command_context: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        """转换成 HTTP、runtime event 或本地诊断都能复用的低敏响应。

        这里故意由白名单逐项组装字段，而不是直接把 dataclass 或内部对象序列化出去。这样即使后续
        intake/readiness 内部为了执行器接入新增了参数值、payload 引用或调试上下文，也不会意外泄漏到
        公开响应、WebSocket timeline 或 Java projection 中。

        `command_context` 是面向 Java command proposal 的低敏上下文字段，例如 tenantId、projectId、
        requestId、runId、sessionId、policyVersion。它不能包含工具参数值或原始 payload；本方法只把它用于
        生成“如何调用 Java proposal 接口”的请求模板，不会写 outbox。
        """

        intake_summary = self.intake.to_low_sensitive_summary()
        readiness_summary = _tool_execution_readiness_summary(self.readiness)
        return {
            "schemaVersion": schema_version,
            "previewOnly": True,
            "toolExecutionEnabled": False,
            "source": self.source.value,
            "protocolFamily": self.protocol_family,
            "route": dict(route or {}),
            "inputPayloadPolicy": dict(input_payload_policy or _default_input_payload_policy(self.source)),
            "executionContract": _execution_contract(self.source),
            "toolActionIntake": intake_summary,
            "toolExecutionReadiness": readiness_summary,
            "toolExecutionReadinessGraph": dict(self.readiness_graph),
            "controlPlaneDecision": dict(self.control_plane_decision or {}),
            "toolActionCommandProposalTemplates": build_tool_action_command_proposal_templates(
                source=self.source,
                protocol_family=self.protocol_family,
                readiness_summary=readiness_summary,
                command_context=command_context,
            ),
            "productionReadiness": _production_readiness(self.source, intake_summary, readiness_summary),
            "nextSteps": _next_steps(self.source, intake_summary, readiness_summary, self.control_plane_decision),
        }


class ToolActionControlFlowService:
    """统一工具动作入口、准备度和执行前条件图的编排服务。

    该服务目前仍是“只读预览层”，它不会调用真实工具、不会写 outbox、不会创建审批单、不会发 Kafka。
    这种克制是有意为之：只有当 Java agent-runtime、permission-admin、task-management、worker receipt
    和审计投影都具备生产化闭环后，READY 分支才应该被真实执行器消费。

    未来演进方向：
    - 在 LangGraph/OpenClaw 图中把本服务作为 `tool_action_gate` 条件节点；
    - READY 分支进入 Java outbox，WAITING_APPROVAL 分支进入 permission-admin，NEEDS_CLARIFICATION
      分支返回用户追问，THROTTLED 分支进入预算等待；
    - MCP/A2A/模型工具调用都复用同一份低敏控制流契约，避免协议入口越多、治理规则越分裂。
    """

    def __init__(
        self,
        *,
        intake_service: ToolActionIntakeService | None = None,
        readiness_service: ToolExecutionReadinessService | None = None,
    ) -> None:
        self._intake_service = intake_service or ToolActionIntakeService()
        self._readiness_service = readiness_service or ToolExecutionReadinessService()

    def from_model_tool_calls(
        self,
        tool_calls: Iterable[ModelToolCall],
        *,
        registered_tools: tuple[ToolDefinition, ...],
        visible_tools: tuple[ToolDefinition, ...] | None = None,
        readiness_policy: ToolExecutionReadinessPolicy | None = None,
        policy_metadata: Mapping[str, Any] | None = None,
    ) -> ToolActionControlFlowReport:
        """把模型返回的 tool_calls 转换为统一控制流预览。

        模型 tool_call 是最典型的 Agent 工具意图来源，但它仍然不可信：参数可能缺失、工具可能不可见、
        风险可能需要审批，甚至模型可能输出不存在的工具名。本方法只负责归一化和执行前判断，不直接执行。
        """

        intake = self._intake_service.from_model_tool_calls(
            tool_calls,
            registered_tools=registered_tools,
            visible_tools=visible_tools,
        )
        return self._build_report(
            source=ToolActionIntakeSource.MODEL_TOOL_CALL,
            protocol_family="MODEL",
            intake=intake,
            readiness_policy=readiness_policy,
            policy_metadata={
                "source": "model_tool_call_control_flow_preview",
                "protocol": "MODEL_TOOL_CALL",
                "previewOnly": True,
                "registeredToolCount": len(registered_tools),
                "visibleToolCount": len(visible_tools) if visible_tools is not None else len(registered_tools),
                **dict(policy_metadata or {}),
            },
        )

    def from_mcp_tools_call(
        self,
        call: Mapping[str, Any] | None,
        *,
        registered_tools: tuple[ToolDefinition, ...],
        visible_tools: tuple[ToolDefinition, ...] | None = None,
        readiness_policy: ToolExecutionReadinessPolicy | None = None,
        policy_metadata: Mapping[str, Any] | None = None,
    ) -> ToolActionControlFlowReport:
        """把 MCP `tools/call` 请求转换为统一控制流预览。

        MCP 入口常来自外部 Agent Host 或 IDE/自动化工具。它的风险在于调用方可能已经把动作包装成
        “我要调用某工具”，但 DataSmart 仍必须重新验证工具目录、可见性、参数 schema、审批和预算。
        """

        intake = self._intake_service.from_mcp_tools_call(
            call,
            registered_tools=registered_tools,
            visible_tools=visible_tools,
        )
        return self._build_report(
            source=ToolActionIntakeSource.MCP_TOOLS_CALL,
            protocol_family="MCP",
            intake=intake,
            readiness_policy=readiness_policy,
            policy_metadata={
                "source": "mcp_tools_call_control_flow_preview",
                "protocol": "MCP",
                "previewOnly": True,
                "registeredToolCount": len(registered_tools),
                "visibleToolCount": len(visible_tools) if visible_tools is not None else len(registered_tools),
                **dict(policy_metadata or {}),
            },
        )

    def from_a2a_task_action(
        self,
        contract: Mapping[str, Any] | None,
        *,
        policy_metadata: Mapping[str, Any] | None = None,
    ) -> ToolActionControlFlowReport:
        """把 A2A task/action 合同转换为控制面预览。

        A2A task/action 往往表达的是“另一个 Agent 或任务系统当前处于什么状态、下一步应该如何协作”，
        不是标准工具调用。因此这里不会强行生成 ToolPlan，而是保留 A2A 决策摘要，同时构造空 readiness
        graph，让调用方明确看到本轮没有可执行工具计划。
        """

        intake = self._intake_service.from_a2a_task_action(contract)
        readiness = self._readiness_service.evaluate(
            (),
            policy_metadata={
                "source": "a2a_task_action_control_flow_preview",
                "protocol": "A2A",
                "previewOnly": True,
                **dict(policy_metadata or {}),
            },
        )
        return ToolActionControlFlowReport(
            source=ToolActionIntakeSource.A2A_TASK_ACTION,
            protocol_family="A2A",
            intake=intake,
            readiness=readiness,
            readiness_graph=build_tool_execution_readiness_graph_response(readiness),
            control_plane_decision=_a2a_control_plane_decision(intake),
            policy_metadata=readiness.policy_metadata,
        )

    def _build_report(
        self,
        *,
        source: ToolActionIntakeSource,
        protocol_family: str,
        intake: ToolActionIntakeReport,
        readiness_policy: ToolExecutionReadinessPolicy | None,
        policy_metadata: Mapping[str, Any],
    ) -> ToolActionControlFlowReport:
        """把 intake 结果继续推进到 readiness 和 readiness graph。"""

        readiness = self._readiness_service.evaluate(
            intake.accepted_tool_plans,
            policy=readiness_policy,
            policy_metadata=policy_metadata,
        )
        return ToolActionControlFlowReport(
            source=source,
            protocol_family=protocol_family,
            intake=intake,
            readiness=readiness,
            readiness_graph=build_tool_execution_readiness_graph_response(readiness),
            policy_metadata=readiness.policy_metadata,
        )


def _tool_execution_readiness_summary(readiness: ToolExecutionReadinessReport) -> dict[str, Any]:
    """把 readiness report 压缩为低敏摘要。

    该摘要复用 `/agent/plans` 与 MCP preview 已经形成的字段口径，方便 Java projection、前端治理卡片
    和测试用例不必理解 Python 内部对象。注意这里仍然只返回参数字段名和 issue/reason code。
    """

    return {
        "snapshotType": "TOOL_EXECUTION_READINESS",
        "payloadPolicy": "LOW_SENSITIVE_METADATA_ONLY",
        "policy": dict(readiness.policy_metadata or {}),
        "totalCount": readiness.total_count,
        "executableCount": readiness.executable_count,
        "approvalRequiredCount": readiness.approval_required_count,
        "clarificationRequiredCount": readiness.clarification_required_count,
        "draftOnlyCount": readiness.draft_only_count,
        "queuedAsyncCount": readiness.queued_async_count,
        "throttledCount": readiness.throttled_count,
        "blockedCount": readiness.blocked_count,
        "hasBlockingDecision": readiness.has_blocking_decision,
        "nextActions": readiness.next_actions,
        "items": tuple(
            {
                "planIndex": item.plan_index,
                "toolName": item.tool_name,
                "decision": item.decision.value,
                "executable": item.executable,
                "queueRequired": item.queue_required,
                "requiresHumanApproval": item.requires_human_approval,
                "riskLevel": item.risk_level,
                "executionMode": item.execution_mode,
                "targetService": item.target_service,
                "argumentFieldNames": item.argument_field_names,
                "sensitiveArgumentNames": item.sensitive_argument_names,
                "parameterIssueCount": item.parameter_issue_count,
                "issueCodes": item.issue_codes,
                "reasonCodes": item.reason_codes,
                "retryHint": item.retry_hint,
                "payloadPolicy": item.payload_policy,
            }
            for item in readiness.items
        ),
    }


def _execution_contract(source: ToolActionIntakeSource) -> dict[str, Any]:
    """声明本响应的执行边界，避免 READY 被误读为已经执行。"""

    return {
        "previewOnly": True,
        "toolExecuted": False,
        "outboxWritten": False,
        "approvalCreated": False,
        "workerReceiptRequiredForSideEffects": True,
        "sourceBoundary": source.value,
        "meaning": "本响应只描述执行前控制流，不代表工具已经执行，也不代表审批、outbox 或 worker 已创建。",
    }


def _default_input_payload_policy(source: ToolActionIntakeSource) -> dict[str, Any]:
    """生成没有协议专属输入策略时的默认低敏说明。"""

    return {
        "accepted": f"{source.value}_LOW_SENSITIVE_CONTROL_FLOW_PREVIEW",
        "notEchoed": True,
        "rawExecutionPayloadAllowed": False,
        "sensitiveFieldHandling": "COUNT_AND_DROP_WITHOUT_FIELD_NAMES_OR_VALUES",
    }


def _production_readiness(
    source: ToolActionIntakeSource,
    intake_summary: Mapping[str, Any],
    readiness_summary: Mapping[str, Any],
) -> dict[str, Any]:
    """生成源类型相关的生产化缺口清单。

    这不是“当前代码缺陷列表”，而是产品路线提示：即使某个工具 readiness 已经 READY，真实商业执行仍然
    需要身份、审批、幂等、队列、审计、结果脱敏和 artifact 引用等闭环。
    """

    source_requirements = {
        ToolActionIntakeSource.MODEL_TOOL_CALL: (
            "MODEL_TOOL_CALL_STREAM_AGGREGATION_AND_CONFIRMATION",
            "MODEL_OUTPUT_TOOL_SCHEMA_REPAIR_AND_RETRY_POLICY",
        ),
        ToolActionIntakeSource.MCP_TOOLS_CALL: (
            "MCP_JSON_RPC_SERVER_AND_SESSION_LIFECYCLE",
            "MCP_CLIENT_AUTHENTICATION_AND_TOOL_SCOPE",
        ),
        ToolActionIntakeSource.A2A_TASK_ACTION: (
            "A2A_TASK_FACT_STORE_AND_STATE_REPLAY",
            "A2A_AGENT_IDENTITY_AND_TASK_SCOPE",
        ),
    }
    common_requirements = (
        "PERMISSION_ADMIN_POLICY_AND_APPROVAL_BINDING",
        "IDEMPOTENCY_KEY_AND_RATE_LIMIT",
        "OUTBOX_COMMAND_AND_WORKER_RECEIPT",
        "RUNTIME_EVENT_TIMELINE_AND_REPLAY_PROJECTION",
        "TOOL_RESULT_SANITIZATION_AND_ARTIFACT_REFERENCE",
    )
    return {
        "readyForExecution": False,
        "intakeAcceptedToolPlanCount": int(intake_summary.get("acceptedToolPlanCount") or 0),
        "readinessExecutableCount": int(readiness_summary.get("executableCount") or 0),
        "missingProductionRequirements": tuple(source_requirements.get(source, ()) + common_requirements),
        "boundary": "当前只是执行前控制流预览，不代表真实工具执行链路已经生产可用。",
    }


def _next_steps(
    source: ToolActionIntakeSource,
    intake_summary: Mapping[str, Any],
    readiness_summary: Mapping[str, Any],
    control_plane_decision: Mapping[str, Any] | None,
) -> tuple[str, ...]:
    """根据控制流结果生成下一步建议。

    建议是产品/工程路线，而不是自动命令。真实执行仍需 Java 控制面、审批事实、outbox 和 worker receipt。
    """

    if source == ToolActionIntakeSource.A2A_TASK_ACTION:
        actions = tuple(control_plane_decision.get("suggestedActions", ())) if control_plane_decision else ()
        if actions:
            return tuple(f"按 A2A 控制面建议处理：{action}" for action in actions[:5])
        return ("保持 A2A 任务只读诊断态，先补齐任务事实存储、状态回放和跨 Agent 身份边界。",)
    if int(intake_summary.get("rejectedBeforeReadinessCount") or 0) > 0:
        return (
            "先检查工具名、协议方法、工具可见性和参数 JSON object 形态，拒绝项不能进入执行器。",
            "把拒绝原因写入低敏 runtime event，方便智能网关、MCP Host 和审计台定位协议或权限问题。",
        )
    if int(readiness_summary.get("clarificationRequiredCount") or 0) > 0:
        return (
            "让 Agent 生成低敏追问，等待用户或上游 Host 补齐必填参数后重新预检。",
            "不要让模型自行猜测数据源、SQL、导出路径、审批理由或其他可能产生副作用的参数。",
        )
    if int(readiness_summary.get("approvalRequiredCount") or 0) > 0:
        return (
            "把该工具计划送入 permission-admin 审批/确认链路，审批通过前不能执行真实副作用。",
            "审批页只展示字段名、风险等级、工具说明和脱敏摘要，不展示原始参数值。",
        )
    if int(readiness_summary.get("executableCount") or 0) > 0:
        return (
            "下一阶段应接 Java outbox/worker receipt，而不是在 HTTP preview handler 内直接执行工具。",
            "补齐租户级速率限制、幂等键、执行超时预算、结果脱敏和 artifact 引用策略。",
        )
    return ("当前没有可进入 readiness 的工具计划，继续保持只读诊断态。",)


def _a2a_control_plane_decision(intake: ToolActionIntakeReport) -> dict[str, Any] | None:
    """提取 A2A 控制面决策摘要。"""

    item = next((candidate for candidate in intake.items if candidate.a2a_decision is not None), None)
    if item is None or item.a2a_decision is None:
        return None
    decision = item.a2a_decision.to_summary()
    return {
        "boundary": ToolActionIntakeBoundary.A2A_TASK_CONTROL_PLANE_DECISION.value,
        "mode": decision.get("mode", ""),
        "suggestedActions": tuple(decision.get("suggestedActions", ())),
        "snapshot": dict(decision.get("snapshot", {})),
        "payloadPolicy": "LOW_SENSITIVE_A2A_CONTROL_PLANE_SUMMARY_ONLY",
    }
