"""工具动作执行图 runner 的最小低敏实现。

本模块把 5.62 的统一控制流、5.63 的 command proposal 模板、5.64 的 Java proposal client 串成一个
“执行前图 runner”。它仍然不是完整 LangGraph/OpenClaw 持久化引擎，也不会执行工具或写 outbox。

为什么先做最小 runner：
- 成熟 Agent Host 不能让 READY 工具动作停留在同步 preview 字段里，后续必须能进入可恢复节点；
- 但直接开放真实工具执行会绕过审批、payload store、outbox、worker receipt 等尚未完全串好的控制面；
- 因此本模块只把每个 template 路由到“等待证据、等待审批、等待澄清、等待预算、提交 proposal”等低敏节点，
  为后续真正接入 durable checkpoint store 和 Java outbox writer 保留稳定契约。
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any

from datasmart_ai_runtime.services.tools.tool_action_command_proposal_client import (
    JavaToolActionCommandProposalClient,
    ToolActionCommandProposalClientError,
    ToolActionCommandProposalEvidence,
)


@dataclass(frozen=True)
class ToolActionCommandProposalEvidenceSelection:
    """执行图 runner 可使用的 proposal 证据选择器。

    真实产品中，graphId、payloadReference、approvalFact 等证据通常不会和 tool_call 同时出现：
    - 第一次 preview 只告诉用户/网关需要哪些证据；
    - Java graph projection、payload store 或 permission-admin 补齐证据后，再按 templateId 或 toolName 继续推进；
    - 因此这里支持三种选择方式：按 templateId、按 toolName、或单模板默认证据。
    """

    default_evidence: ToolActionCommandProposalEvidence | None = None
    by_template_id: Mapping[str, ToolActionCommandProposalEvidence] = field(default_factory=dict)
    by_tool_name: Mapping[str, ToolActionCommandProposalEvidence] = field(default_factory=dict)

    def evidence_for(self, template: Mapping[str, Any]) -> ToolActionCommandProposalEvidence | None:
        """为单个 proposal template 选择最合适的证据。"""

        template_id = _text(template.get("templateId"))
        tool_name = _text(template.get("toolName"))
        if template_id and template_id in self.by_template_id:
            return self.by_template_id[template_id]
        if tool_name and tool_name in self.by_tool_name:
            return self.by_tool_name[tool_name]
        return self.default_evidence


@dataclass(frozen=True)
class ToolActionExecutionGraphRunResult:
    """一次执行前图 runner 的低敏结果。

    字段说明：
    - `steps`：每个 proposal template 对应一个节点执行结果；
    - `truncated_count`：当模板过多时只处理前 N 个，避免异常模型输出导致响应过大；
    - `side_effect_boundary`：明确 runner 没有执行工具、没有写 outbox、没有派发 worker。
    """

    steps: tuple[dict[str, Any], ...]
    truncated_count: int = 0

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 响应可直接挂载的低敏摘要。"""

        status_counts = _count_by_key(self.steps, "stepStatus")
        return {
            "schemaVersion": "datasmart.python-ai-runtime.tool-action-execution-graph-runner.v1",
            "previewOnly": True,
            "executionBoundary": "PRE_EXECUTION_GRAPH_RUNNER_ONLY",
            "stepCount": len(self.steps),
            "truncatedCount": self.truncated_count,
            "statusCounts": status_counts,
            "steps": self.steps,
            "sideEffectBoundary": {
                "toolExecuted": False,
                "outboxWritten": False,
                "workerDispatched": False,
                "approvalCreated": False,
                "checkpointPersisted": False,
                "meaning": "本 runner 只推进执行前节点，不代表工具、outbox、worker 或审批事实已经创建。",
            },
            "resumeRequirements": _resume_requirements(self.steps),
        }


class ToolActionExecutionGraphRunner:
    """最小执行前图 runner。

    Runner 的输入是已经脱敏的 `control_flow_response`，而不是原始工具 arguments。这样可以从结构上降低误用：
    即使未来某个 API 调用方把 prompt、SQL 或样本数据放进请求，runner 也只看 proposal template 和 evidence。
    """

    def __init__(
        self,
        *,
        proposal_client: JavaToolActionCommandProposalClient | None = None,
        max_templates_per_run: int = 20,
    ) -> None:
        self._proposal_client = proposal_client or JavaToolActionCommandProposalClient()
        self._max_templates_per_run = max(1, max_templates_per_run)

    def run(
        self,
        control_flow_response: Mapping[str, Any],
        *,
        evidence_selection: ToolActionCommandProposalEvidenceSelection | None = None,
        trace_id: str | None = None,
    ) -> ToolActionExecutionGraphRunResult:
        """运行一次低敏执行前图。

        流程说明：
        1. 从控制流响应中读取 `toolActionCommandProposalTemplates.templates`；
        2. 非 READY/QUEUED 的模板只生成等待/阻断节点，不调用 Java；
        3. READY/QUEUED 且 outbox preflight candidate 的模板调用 proposal client；
        4. proposal client 默认禁用或缺证据时只返回 skipped，不会产生网络副作用。
        """

        templates = _proposal_templates(control_flow_response)
        selected = templates[: self._max_templates_per_run]
        truncated_count = max(0, len(templates) - len(selected))
        selection = evidence_selection or ToolActionCommandProposalEvidenceSelection()
        steps = tuple(self._run_template(template, selection, trace_id=trace_id) for template in selected)
        return ToolActionExecutionGraphRunResult(steps=steps, truncated_count=truncated_count)

    def _run_template(
        self,
        template: Mapping[str, Any],
        evidence_selection: ToolActionCommandProposalEvidenceSelection,
        *,
        trace_id: str | None,
    ) -> dict[str, Any]:
        """执行单个模板节点。"""

        base_step = _base_step(template)
        if not template.get("outboxPreflightCandidate"):
            return {
                **base_step,
                "stepStatus": _non_candidate_status(template),
                "proposalSubmission": {},
                "nextAction": template.get("nextAction"),
            }
        try:
            proposal_result = self._proposal_client.propose(
                template,
                evidence_selection.evidence_for(template),
                trace_id=trace_id,
            )
            proposal_summary = proposal_result.to_summary()
            return {
                **base_step,
                "stepStatus": _proposal_step_status(proposal_summary),
                "proposalSubmission": proposal_summary,
                "nextAction": _next_action_after_proposal(proposal_summary),
            }
        except ToolActionCommandProposalClientError as exc:
            # 不把 Java 原始错误消息透传到图结果里，避免远端异常文本携带内部状态或路径。
            return {
                **base_step,
                "stepStatus": "COMMAND_PROPOSAL_CLIENT_ERROR",
                "proposalSubmission": {
                    "errorType": exc.__class__.__name__,
                    "errorCode": "JAVA_COMMAND_PROPOSAL_CLIENT_ERROR",
                },
                "nextAction": "RETRY_AFTER_CONTROL_PLANE_RECOVERY_OR_SWITCH_TO_MANUAL_REVIEW",
            }


def evidence_selection_from_payload(payload: Mapping[str, Any] | None) -> ToolActionCommandProposalEvidenceSelection:
    """从统一控制流请求中提取低敏 proposal evidence。

    该函数只读取 graphId、contractId、payloadReference、approval/clarification fact、policyVersion 等控制面证据。
    它不会读取 `arguments`、prompt、SQL、样本数据或模型输出；payloadReference 是否安全仍由 proposal client 复核。
    """

    if not isinstance(payload, Mapping):
        return ToolActionCommandProposalEvidenceSelection()
    default_evidence = _evidence_from_mapping(payload.get("commandProposalEvidence"))
    return ToolActionCommandProposalEvidenceSelection(
        default_evidence=default_evidence,
        by_template_id=_evidence_mapping(payload.get("commandProposalEvidenceByTemplateId")),
        by_tool_name=_evidence_mapping(payload.get("commandProposalEvidenceByToolName")),
    )


def _evidence_mapping(value: Any) -> dict[str, ToolActionCommandProposalEvidence]:
    """解析按 templateId/toolName 索引的 evidence 映射。"""

    if not isinstance(value, Mapping):
        return {}
    result: dict[str, ToolActionCommandProposalEvidence] = {}
    for key, item in value.items():
        if text_key := _text(key):
            if evidence := _evidence_from_mapping(item):
                result[text_key] = evidence
    return result


def _evidence_from_mapping(value: Any) -> ToolActionCommandProposalEvidence | None:
    """把外部 evidence 对象转换成强类型证据。"""

    if not isinstance(value, Mapping):
        return None
    return ToolActionCommandProposalEvidence(
        graph_id=_text(_first(value, "graphId", "graph_id")),
        contract_id=_text(_first(value, "contractId", "contract_id")),
        tenant_id=_text(_first(value, "tenantId", "tenant_id")),
        project_id=_text(_first(value, "projectId", "project_id")),
        actor_id=_text(_first(value, "actorId", "actor_id")),
        request_id=_text(_first(value, "requestId", "request_id")),
        run_id=_text(_first(value, "runId", "run_id")),
        session_id=_text(_first(value, "sessionId", "session_id")),
        after_sequence=_optional_int(_first(value, "afterSequence", "after_sequence")),
        limit=_optional_int(value.get("limit")),
        payload_reference=_text(_first(value, "payloadReference", "payload_reference")),
        approval_confirmation_id=_text(_first(value, "approvalConfirmationId", "approval_confirmation_id")),
        clarification_fact_id=_text(_first(value, "clarificationFactId", "clarification_fact_id")),
        policy_version=_text(_first(value, "policyVersion", "policy_version")),
        command_schema_version=_text(_first(value, "commandSchemaVersion", "command_schema_version")),
        worker_receipt_mode=_text(_first(value, "workerReceiptMode", "worker_receipt_mode")),
        client_request_id=_text(_first(value, "clientRequestId", "client_request_id")),
    )


def _proposal_templates(control_flow_response: Mapping[str, Any]) -> tuple[Mapping[str, Any], ...]:
    """读取控制流响应中的 proposal templates。"""

    root = control_flow_response.get("toolActionCommandProposalTemplates")
    if not isinstance(root, Mapping):
        return ()
    templates = root.get("templates")
    if not isinstance(templates, (list, tuple)):
        return ()
    return tuple(item for item in templates if isinstance(item, Mapping))


def _base_step(template: Mapping[str, Any]) -> dict[str, Any]:
    """生成节点基础信息。"""

    return {
        "nodeType": "TOOL_ACTION_COMMAND_PROPOSAL",
        "templateId": _text(template.get("templateId")),
        "toolName": _text(template.get("toolName")),
        "planIndex": template.get("planIndex"),
        "decision": _text(template.get("decision")),
        "outboxPreflightCandidate": bool(template.get("outboxPreflightCandidate")),
        "payloadPolicy": _text(template.get("payloadPolicy")),
    }


def _non_candidate_status(template: Mapping[str, Any]) -> str:
    """把非 READY/QUEUED 分支映射为等待或阻断节点。"""

    decision = _text(template.get("decision")) or ""
    if template.get("approvalConfirmationRequired"):
        return "WAITING_APPROVAL_FACT"
    if template.get("clarificationFactRequired") or decision == "needs_clarification":
        return "WAITING_CLARIFICATION_FACT"
    if decision == "throttled":
        return "WAITING_TOOL_BUDGET"
    if decision == "draft_only":
        return "DRAFT_REVIEW_REQUIRED"
    if decision == "blocked":
        return "BLOCKED_BEFORE_COMMAND_PROPOSAL"
    return "CONTROL_FLOW_PREVIEW_ONLY"


def _proposal_step_status(proposal_summary: Mapping[str, Any]) -> str:
    """根据 proposal client 摘要生成 runner 节点状态。"""

    submission_state = _text(proposal_summary.get("submissionState")) or ""
    if submission_state == "VALIDATION_FAILED":
        return "WAITING_COMMAND_PROPOSAL_EVIDENCE"
    if submission_state == "CLIENT_DISABLED":
        return "COMMAND_PROPOSAL_CLIENT_DISABLED"
    if submission_state != "SUBMITTED_TO_JAVA_PROPOSAL":
        return "COMMAND_PROPOSAL_NOT_SUBMITTED"
    java_proposal = proposal_summary.get("javaProposal")
    if isinstance(java_proposal, Mapping) and java_proposal.get("outboxWriteAllowedByPreflight") is True:
        return "WAITING_OUTBOX_CONFIRMATION"
    return "JAVA_COMMAND_PROPOSAL_RECORDED"


def _next_action_after_proposal(proposal_summary: Mapping[str, Any]) -> str:
    """根据 proposal 结果给出下一跳建议。"""

    status = _proposal_step_status(proposal_summary)
    mapping = {
        "WAITING_COMMAND_PROPOSAL_EVIDENCE": "COMPLETE_GRAPH_PAYLOAD_POLICY_OR_APPROVAL_EVIDENCE_THEN_RESUME",
        "COMMAND_PROPOSAL_CLIENT_DISABLED": "ENABLE_CONTROL_PLANE_CLIENT_AFTER_OPERATOR_APPROVAL",
        "WAITING_OUTBOX_CONFIRMATION": "CALL_JAVA_OUTBOX_WRITER_AFTER_OPERATOR_OR_GRAPH_CONFIRMATION",
        "JAVA_COMMAND_PROPOSAL_RECORDED": "INSPECT_JAVA_PROPOSAL_STATE_AND_RESUME_BY_RECOMMENDED_ACTIONS",
        "COMMAND_PROPOSAL_NOT_SUBMITTED": "KEEP_PRE_EXECUTION_PREVIEW_ONLY",
    }
    return mapping.get(status, "KEEP_PRE_EXECUTION_PREVIEW_ONLY")


def _resume_requirements(steps: tuple[Mapping[str, Any], ...]) -> tuple[str, ...]:
    """汇总后续恢复执行图所需的证据类型。"""

    requirements: list[str] = []
    status_to_requirement = {
        "WAITING_COMMAND_PROPOSAL_EVIDENCE": "GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE",
        "WAITING_APPROVAL_FACT": "APPROVAL_CONFIRMATION_FACT",
        "WAITING_CLARIFICATION_FACT": "CLARIFICATION_FACT",
        "WAITING_TOOL_BUDGET": "TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY",
        "COMMAND_PROPOSAL_CLIENT_DISABLED": "CONTROL_PLANE_CLIENT_ENABLEMENT",
        "WAITING_OUTBOX_CONFIRMATION": "OUTBOX_WRITE_CONFIRMATION",
    }
    for step in steps:
        requirement = status_to_requirement.get(str(step.get("stepStatus") or ""))
        if requirement and requirement not in requirements:
            requirements.append(requirement)
    return tuple(requirements)


def _count_by_key(items: tuple[Mapping[str, Any], ...], key: str) -> dict[str, int]:
    """按固定低基数字段计数。"""

    counts: dict[str, int] = {}
    for item in items:
        value = _text(item.get(key)) or "UNKNOWN"
        counts[value] = counts.get(value, 0) + 1
    return counts


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """按候选键读取第一个存在值，保留 0/False 这类显式值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _optional_int(value: Any) -> int | None:
    """解析可选整数。"""

    if value is None or str(value).strip() == "":
        return None
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _text(value: Any) -> str | None:
    """读取非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None
