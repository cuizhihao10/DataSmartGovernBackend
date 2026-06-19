"""Java 恢复事实查询 DTO 构造器。

本模块只负责把 Python checkpoint 与 resume-preview 请求中的低敏定位线索，转换为 Java
`AgentToolActionResumeFactBundleQueryRequest` 风格的 dict。它被两个客户端复用：

- `tool_action_resume_fact_bundle_client.py`：调用 Java 5.70 fact bundle；
- `tool_action_resume_gate_graph_client.py`：调用 Java 5.85 resume gate graph。

为什么要独立拆分：
- gate graph 是更新的控制面能力，不应该为了构造请求 DTO 而依赖旧 fact bundle 客户端实例；
- DTO 构造规则涉及 checkpoint hint、request alias、requiredFactTypes、tenant/project/actor 等
  关键业务语义，必须只有一份实现，避免两个 provider 对同一个 checkpoint 得出不同恢复事实需求；
- 本模块不发送 HTTP、不解析 Java 响应、不执行工具，因此更容易单独阅读和复用。

低敏边界：
- 可以发送 checkpointId、threadId、runId、sessionId、commandId、approvalFactId、clarificationFactId、
  outboxId、toolCode、policyVersion 等控制面定位字段给 Java 验真；
- 绝不把 prompt、messages、SQL、工具 arguments、payload body、样本数据、模型输出、凭证或内部 endpoint
  放入请求 DTO。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.services.tools.tool_action_resume_fact_checkpoint_hints import (
    checkpoint_resume_fact_bundle_hints,
)
from datasmart_ai_runtime.services.tools.tool_action_resume_fact_provider import resume_fact_types_from_mapping


SERVER_BACKED_FACT_TYPES = {
    "APPROVAL_CONFIRMATION_FACT",
    "CLARIFICATION_FACT",
    "OUTBOX_WRITE_CONFIRMATION",
    "WORKER_RECEIPT_PROJECTION",
}


def build_resume_fact_bundle_payload(
    *,
    checkpoint: Any,
    request_payload: Mapping[str, Any],
) -> dict[str, Any]:
    """构造 Java 恢复事实查询请求体。

    字段来源优先级：
    1. 请求显式低敏字段；
    2. checkpoint 的低敏执行图摘要；
    3. checkpoint 对象上的基础定位字段。

    这些字段只用于 Java 控制面回查 host facts，不代表 Python 已经采信调用方自报事实。例如
    `approvalFactId` 和 `outboxId` 只是“查询线索”，最终是否可用必须以 Java 返回的 available/missing/rejected
    fact types 为准。
    """

    context = request_payload.get("context")
    context_mapping = context if isinstance(context, Mapping) else {}
    checkpoint_hints = checkpoint_resume_fact_bundle_hints(checkpoint)
    return {
        "checkpointId": first_text(
            request_payload.get("checkpointId"),
            context_mapping.get("checkpointId"),
            getattr(checkpoint, "checkpoint_id", None),
        ),
        "threadId": first_text(
            request_payload.get("threadId"),
            context_mapping.get("threadId"),
            getattr(checkpoint, "thread_id", None),
        ),
        "sessionId": first_text(
            request_payload.get("sessionId"),
            context_mapping.get("sessionId"),
            getattr(checkpoint, "session_id", None),
        ),
        "runId": first_text(
            request_payload.get("runId"),
            context_mapping.get("runId"),
            getattr(checkpoint, "run_id", None),
            getattr(checkpoint, "thread_id", None),
        ),
        "commandId": command_id_from_payload_or_checkpoint(request_payload, checkpoint, checkpoint_hints),
        "outboxId": outbox_id_from_payload_or_checkpoint(request_payload, context_mapping, checkpoint_hints),
        "approvalFactId": approval_fact_id_from_payload(request_payload, checkpoint_hints),
        "clarificationFactId": first_text(
            request_payload.get("clarificationFactId"),
            context_mapping.get("clarificationFactId"),
            fact_value_from_payload(request_payload, "clarificationFactId"),
            checkpoint_hints.get("clarificationFactId"),
        ),
        "toolCode": tool_code_from_payload_or_checkpoint(request_payload, checkpoint_hints),
        "requestedPolicyVersion": policy_version_from_payload_or_checkpoint(request_payload, checkpoint_hints),
        "tenantId": optional_int(
            first_text(
                request_payload.get("tenantId"),
                context_mapping.get("tenantId"),
                getattr(checkpoint, "tenant_id", None),
            )
        ),
        "projectId": optional_int(
            first_text(
                request_payload.get("projectId"),
                context_mapping.get("projectId"),
                getattr(checkpoint, "project_id", None),
            )
        ),
        "actorId": first_text(
            request_payload.get("actorId"),
            context_mapping.get("actorId"),
            getattr(checkpoint, "actor_id", None),
        ),
        "requiredFactTypes": required_fact_types(
            checkpoint=checkpoint,
            request_payload=request_payload,
            checkpoint_hints=checkpoint_hints,
        ),
        "includeOutboxSummary": True,
        "includeReceiptSummary": True,
    }


def required_fact_types(
    *,
    checkpoint: Any,
    request_payload: Mapping[str, Any],
    checkpoint_hints: Mapping[str, str],
) -> list[str]:
    """推断需要 Java 控制面回查的事实类型。

    推断分三层：
    - checkpoint.resume_requirements 表达执行图为什么暂停，是最稳定的服务端事实需求来源；
    - request_payload.resumeFacts 表达调用方自报了哪些事实，但仍必须由 Java 控制面验真；
    - checkpoint_hints 表达执行图摘要里已经存在某些事实定位符，即使调用方未重复传，也应该回查。
    """

    required: list[str] = []
    for requirement in tuple(getattr(checkpoint, "resume_requirements", ()) or ()):
        if requirement == "APPROVAL_CONFIRMATION_FACT":
            append_once(required, "APPROVAL_CONFIRMATION_FACT")
        elif requirement == "CLARIFICATION_FACT":
            append_once(required, "CLARIFICATION_FACT")
        elif requirement == "OUTBOX_WRITE_CONFIRMATION":
            append_once(required, "OUTBOX_WRITE_CONFIRMATION")
            append_once(required, "WORKER_RECEIPT_PROJECTION")
        elif requirement == "TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY":
            append_once(required, "TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY")
    for fact_type in resume_fact_types_from_mapping(request_payload):
        if fact_type in {"APPROVAL_CONFIRMATION_FACT", "CLARIFICATION_FACT", "OUTBOX_WRITE_CONFIRMATION"}:
            append_once(required, fact_type)
    if checkpoint_hints.get("approvalFactId"):
        append_once(required, "APPROVAL_CONFIRMATION_FACT")
    if checkpoint_hints.get("clarificationFactId"):
        append_once(required, "CLARIFICATION_FACT")
    if command_id_from_payload_or_checkpoint(request_payload, checkpoint, checkpoint_hints) or checkpoint_hints.get(
        "outboxId"
    ):
        append_once(required, "OUTBOX_WRITE_CONFIRMATION")
        append_once(required, "WORKER_RECEIPT_PROJECTION")
    if not required:
        required.extend(("APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION", "WORKER_RECEIPT_PROJECTION"))
    return required


def outbox_id_from_payload_or_checkpoint(
    payload: Mapping[str, Any],
    context_mapping: Mapping[str, Any],
    checkpoint_hints: Mapping[str, str],
) -> str | None:
    """解析 outbox 定位符。

    历史请求可能使用 `outboxConfirmationId` 表达“我认为 outbox 已写入”。这里允许它作为 Java 查询线索，
    但后续是否采信必须以 Java fact bundle/gate graph 返回为准。
    """

    return first_text(
        payload.get("outboxId"),
        payload.get("outboxConfirmationId"),
        context_mapping.get("outboxId"),
        context_mapping.get("outboxConfirmationId"),
        fact_value_from_payload(payload, "outboxId"),
        fact_value_from_payload(payload, "outboxConfirmationId"),
        checkpoint_hints.get("outboxId"),
    )


def approval_fact_id_from_payload(payload: Mapping[str, Any], checkpoint_hints: Mapping[str, str]) -> str | None:
    """从请求中提取 approval fact ID，仅用于发往 Java 控制面验真。"""

    return first_text(
        payload.get("approvalFactId"),
        payload.get("approvalConfirmationId"),
        payload.get("confirmationId"),
        fact_value_from_payload(payload, "approvalFactId"),
        fact_value_from_payload(payload, "approvalConfirmationId"),
        fact_value_from_payload(payload, "confirmationId"),
        checkpoint_hints.get("approvalFactId"),
    )


def command_id_from_payload_or_checkpoint(
    payload: Mapping[str, Any],
    checkpoint: Any,
    checkpoint_hints: Mapping[str, str],
) -> str | None:
    """解析 commandId，用于 Java outbox 与 worker receipt 查询。"""

    context = payload.get("context")
    context_mapping = context if isinstance(context, Mapping) else {}
    return first_text(
        payload.get("commandId"),
        payload.get("clientRequestId"),
        context_mapping.get("commandId"),
        context_mapping.get("clientRequestId"),
        checkpoint_hints.get("commandId"),
        getattr(checkpoint, "request_id", None),
        getattr(checkpoint, "checkpoint_id", None),
    )


def tool_code_from_payload_or_checkpoint(payload: Mapping[str, Any], checkpoint_hints: Mapping[str, str]) -> str | None:
    """解析工具编码。"""

    params = payload.get("params")
    params_mapping = params if isinstance(params, Mapping) else {}
    return first_text(
        payload.get("toolCode"),
        payload.get("toolName"),
        params_mapping.get("name"),
        checkpoint_hints.get("toolCode"),
    )


def policy_version_from_payload_or_checkpoint(
    payload: Mapping[str, Any],
    checkpoint_hints: Mapping[str, str],
) -> str | None:
    """解析工具策略版本。"""

    context = payload.get("context")
    context_mapping = context if isinstance(context, Mapping) else {}
    return first_text(
        payload.get("policyVersion"),
        context_mapping.get("policyVersion"),
        fact_value_from_payload(payload, "policyVersion"),
        checkpoint_hints.get("requestedPolicyVersion"),
    )


def fact_value_from_payload(payload: Mapping[str, Any], key: str) -> Any:
    """从 resumeFacts 包装中读取指定低敏事实定位值。"""

    facts = payload.get("resumeFacts")
    return facts.get(key) if isinstance(facts, Mapping) else None


def append_once(items: list[str], value: str) -> None:
    """向列表追加去重 code。"""

    if value not in items:
        items.append(value)


def first_text(*values: Any) -> str | None:
    """返回第一个非空字符串表示。"""

    for value in values:
        result = text_value(value)
        if result:
            return result
    return None


def optional_int(value: Any) -> int | None:
    """把可选值转换成 int，失败时返回 None。"""

    result = text_value(value)
    if not result:
        return None
    try:
        return int(result)
    except ValueError:
        return None


def text_value(value: Any) -> str | None:
    """清洗字符串值。"""

    if value is None:
        return None
    result = str(value).strip()
    return result or None
