"""工具动作 command proposal 低敏模板。

本模块承接 `ToolActionControlFlowService` 的输出，把 readiness item 进一步转换成“可以提交给 Java
agent-runtime command proposal 接口的请求模板”。它仍然不是工具执行器，也不是 outbox writer。

为什么不由 Python 直接写 command/outbox：
- 当前 Java `agent-runtime` 已经拥有 execution graph projection、command proposal、payload reference 校验、
  outbox writer、task-management inbox 和 dry-run receipt 这些控制面能力；
- Python Runtime 更适合做模型/MCP/A2A 意图归一、readiness 和低敏建议，不应该绕过 Java 事实源直接写队列；
- 因此本模块只生成“下一步该如何调用 Java proposal 接口”的模板，并明确哪些字段必须由 Java 图投影、
  payload store、permission-admin 或用户澄清事实补齐。
"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.services.tools.tool_action_intake import ToolActionIntakeSource


COMMAND_SCHEMA_VERSION = "agent-tool-action-command.v1"
WORKER_RECEIPT_MODE = "REQUIRED"
JAVA_PROPOSAL_ROUTE = "/agent-runtime/tool-action-commands/proposals"
JAVA_PROPOSAL_API_ROUTE = "/api/agent/tool-action-commands/proposals"


def build_tool_action_command_proposal_templates(
    *,
    source: ToolActionIntakeSource,
    protocol_family: str,
    readiness_summary: Mapping[str, Any],
    command_context: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """根据 readiness 低敏摘要构建 Java command proposal 请求模板。

    输入为什么使用 `readiness_summary` 而不是内部 `ToolPlan`：
    - command proposal 只需要工具名、decision、字段名、风险等级、执行模式等低敏信息；
    - `ToolPlan` 内部含有真实 arguments，虽然当前函数可以小心不读取，但直接传入会扩大误用风险；
    - 使用已经脱敏的 summary 能从类型层面提醒维护者：这里不是 payload 物化位置。

    `command_context` 允许 API 层传入 tenant/project/actor/request/run/session 等控制面 ID。它们仍是低敏
    路由字段，不包含工具参数值。若缺失，模板会保留 `None`，由调用方在 Java graph/projection 阶段补齐。
    """

    context = dict(command_context or {})
    items = tuple(item for item in readiness_summary.get("items", ()) if isinstance(item, Mapping))
    templates = tuple(
        _template_for_item(
            source=source,
            protocol_family=protocol_family,
            readiness_item=item,
            command_context=context,
        )
        for item in items
    )
    return {
        "schemaVersion": "datasmart.python-ai-runtime.tool-action-command-proposal-templates.v1",
        "payloadPolicy": "LOW_SENSITIVE_COMMAND_PROPOSAL_TEMPLATE_ONLY",
        "previewOnly": True,
        "toolExecutionEnabled": False,
        "targetControlPlaneRoutes": (
            {"method": "POST", "path": JAVA_PROPOSAL_ROUTE},
            {"method": "POST", "path": JAVA_PROPOSAL_API_ROUTE},
        ),
        "commandSchemaVersion": COMMAND_SCHEMA_VERSION,
        "workerReceiptMode": WORKER_RECEIPT_MODE,
        "totalTemplateCount": len(templates),
        "outboxPreflightCandidateCount": sum(1 for template in templates if template["outboxPreflightCandidate"]),
        "templates": templates,
        "guardrails": (
            "模板不会写 outbox、不会读取 payloadReference、不会调用 worker。",
            "graphId 或 contractId 必须来自 Java execution graph projection，不能由模型、MCP Host 或客户端随意伪造。",
            "payloadReference 必须是服务端受控引用，不能把 arguments、SQL、prompt、样本数据或模型输出塞入请求体。",
            "READY 只表示可以进入 proposal 预校验；正式 writer 仍必须重新校验权限、幂等、审批事实和 worker 容量。",
        ),
    }


def _template_for_item(
    *,
    source: ToolActionIntakeSource,
    protocol_family: str,
    readiness_item: Mapping[str, Any],
    command_context: Mapping[str, Any],
) -> dict[str, Any]:
    """把单个 readiness item 转换成 command proposal 模板。"""

    decision = str(readiness_item.get("decision") or "")
    tool_name = str(readiness_item.get("toolName") or "")
    return {
        "templateId": _template_id(source, protocol_family, readiness_item),
        "source": source.value,
        "protocolFamily": protocol_family,
        "planIndex": readiness_item.get("planIndex"),
        "toolName": tool_name,
        "decision": decision,
        "proposalStateHint": _proposal_state_hint(decision),
        "outboxPreflightCandidate": _outbox_preflight_candidate(readiness_item),
        "graphSelectionRequired": True,
        "payloadReferenceRequired": True,
        "approvalConfirmationRequired": _approval_confirmation_required(readiness_item),
        "clarificationFactRequired": decision == "needs_clarification",
        "policyVersionRequired": True,
        "requestBodyTemplate": _java_request_body_template(readiness_item, command_context),
        "missingBeforeJavaProposal": _missing_before_java_proposal(readiness_item, command_context),
        "acceptedEvidenceHints": _accepted_evidence_hints(readiness_item),
        "payloadPolicy": "LOW_SENSITIVE_COMMAND_PROPOSAL_TEMPLATE_ONLY",
        "notIncluded": (
            "arguments",
            "prompt",
            "sql",
            "sampleData",
            "modelOutput",
            "credential",
            "internalEndpoint",
            "artifactBody",
        ),
        "nextAction": _next_action(decision),
    }


def _java_request_body_template(
    readiness_item: Mapping[str, Any],
    command_context: Mapping[str, Any],
) -> dict[str, Any]:
    """生成与 Java `AgentToolActionCommandProposalRequest` 对齐的请求体模板。"""

    return {
        "graphId": None,
        "contractId": None,
        "tenantId": _text(command_context.get("tenantId")),
        "projectId": _text(command_context.get("projectId")),
        "actorId": _text(command_context.get("actorId")),
        "requestId": _text(command_context.get("requestId")),
        "runId": _text(command_context.get("runId")),
        "sessionId": _text(command_context.get("sessionId")),
        "afterSequence": command_context.get("afterSequence"),
        "limit": _positive_int(command_context.get("limit"), default=100),
        "payloadReference": None,
        "approvalConfirmationId": None,
        "clarificationFactId": None,
        "policyVersion": _text(command_context.get("policyVersion")),
        "commandSchemaVersion": COMMAND_SCHEMA_VERSION,
        "workerReceiptMode": WORKER_RECEIPT_MODE,
        "clientRequestId": _text(command_context.get("clientRequestId") or command_context.get("requestId")),
        "toolNameHint": readiness_item.get("toolName"),
        "planIndexHint": readiness_item.get("planIndex"),
    }


def _missing_before_java_proposal(
    readiness_item: Mapping[str, Any],
    command_context: Mapping[str, Any],
) -> tuple[str, ...]:
    """列出调用 Java proposal 前仍必须补齐的低敏证据。"""

    missing = ["GRAPH_ID_OR_CONTRACT_ID_REQUIRED", "PAYLOAD_REFERENCE_REQUIRED"]
    if not _text(command_context.get("policyVersion")):
        missing.append("POLICY_VERSION_REQUIRED")
    if _approval_confirmation_required(readiness_item):
        missing.append("APPROVAL_CONFIRMATION_ID_REQUIRED")
    if str(readiness_item.get("decision") or "") == "needs_clarification":
        missing.append("CLARIFICATION_FACT_ID_REQUIRED")
    return tuple(missing)


def _accepted_evidence_hints(readiness_item: Mapping[str, Any]) -> tuple[str, ...]:
    """生成 Java graph builder 后续可物化的低敏证据提示。"""

    hints = [
        "COMMAND_TYPE:AGENT_TOOL_ACTION_CONTROLLED_COMMAND",
        f"WORKER_RECEIPT_MODE:{WORKER_RECEIPT_MODE}",
        f"COMMAND_SCHEMA_VERSION:{COMMAND_SCHEMA_VERSION}",
    ]
    tool_name = _safe_token(readiness_item.get("toolName"))
    if tool_name:
        hints.append(f"TOOL_NAME:{tool_name}")
    target_service = _safe_token(readiness_item.get("targetService"))
    if target_service:
        hints.append(f"TARGET_SERVICE:{target_service}")
    for field_name in tuple(readiness_item.get("argumentFieldNames") or ())[:10]:
        token = _safe_token(field_name)
        if token:
            hints.append(f"ARGUMENT_FIELD:{token}")
    return tuple(hints)


def _proposal_state_hint(decision: str) -> str:
    """把 readiness decision 映射成 proposal 层状态提示。"""

    mapping = {
        "ready_to_execute": "READY_FOR_COMMAND_PROPOSAL",
        "queued_async": "READY_FOR_ASYNC_COMMAND_PROPOSAL",
        "waiting_approval": "WAITING_HUMAN_APPROVAL_FACT",
        "needs_clarification": "WAITING_CLARIFICATION_FACT",
        "throttled": "WAITING_BUDGET_OR_WORKER_CAPACITY",
        "blocked": "BLOCKED_BY_READINESS",
        "draft_only": "DRAFT_REVIEW_REQUIRED",
    }
    return mapping.get(decision, "WAITING_REQUIRED_EVIDENCE")


def _outbox_preflight_candidate(readiness_item: Mapping[str, Any]) -> bool:
    """判断该 item 是否已经可进入 Java proposal 的 outbox 预检候选。"""

    decision = str(readiness_item.get("decision") or "")
    return decision in {"ready_to_execute", "queued_async"} and bool(readiness_item.get("executable"))


def _approval_confirmation_required(readiness_item: Mapping[str, Any]) -> bool:
    """判断该 item 在 proposal 前是否需要人工审批或确认事实。"""

    return bool(readiness_item.get("requiresHumanApproval")) or str(readiness_item.get("decision") or "") == "waiting_approval"


def _next_action(decision: str) -> str:
    """生成模板级下一步动作建议。"""

    mapping = {
        "ready_to_execute": "CALL_JAVA_COMMAND_PROPOSAL_AFTER_GRAPH_AND_PAYLOAD_REFERENCE_READY",
        "queued_async": "CALL_JAVA_COMMAND_PROPOSAL_FOR_ASYNC_OUTBOX_AFTER_PAYLOAD_REFERENCE_READY",
        "waiting_approval": "WAIT_FOR_APPROVAL_FACT_THEN_REPLAY_READINESS",
        "needs_clarification": "WAIT_FOR_CLARIFICATION_FACT_THEN_REPLAY_READINESS",
        "throttled": "WAIT_FOR_TOOL_BUDGET_RECOVERY",
        "blocked": "DO_NOT_CREATE_COMMAND",
        "draft_only": "SHOW_DRAFT_AND_WAIT_FOR_REVIEW",
    }
    return mapping.get(decision, "KEEP_PREVIEW_ONLY")


def _template_id(
    source: ToolActionIntakeSource,
    protocol_family: str,
    readiness_item: Mapping[str, Any],
) -> str:
    """生成稳定模板 ID。"""

    seed = "\n".join(
        (
            source.value,
            protocol_family,
            str(readiness_item.get("planIndex") or ""),
            str(readiness_item.get("toolName") or ""),
            str(readiness_item.get("decision") or ""),
            str(readiness_item.get("targetService") or ""),
        )
    )
    return "tool-action-command-template:" + hashlib.sha256(seed.encode("utf-8")).hexdigest()[:24]


def _safe_token(value: Any) -> str:
    """把展示型 token 收敛到安全字符集，避免把自由文本误当证据扩散。"""

    text = _text(value) or ""
    return re.sub(r"[^a-zA-Z0-9_.:-]", "_", text)[:120]


def _text(value: Any) -> str | None:
    """规范化可选文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _positive_int(value: Any, *, default: int) -> int:
    """把外部上下文字段转成正整数，非法值回退默认值。

    虽然 API helper 已经做过一次清洗，但服务层模板也保持兜底，可以防止未来某个内部调用方直接传入
    `"bad"`、`0` 或负数后导致整个 preview 响应 500。控制流预览应该尽量返回可诊断缺口，而不是因为
    一个非核心展示字段中断。
    """

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default
