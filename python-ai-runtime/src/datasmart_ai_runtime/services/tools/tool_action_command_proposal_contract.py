"""工具动作 command proposal 的低敏跨语言契约辅助函数。

本文件从 `tool_action_command_proposal_client.py` 拆出，专门维护 Python -> Java proposal 请求/响应的
安全白名单。这样主客户端只关心“何时提交、如何处理提交结果”，而本文件集中解释“哪些字段允许跨服务流动”。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any


ALLOWED_JAVA_REQUEST_FIELDS = (
    "graphId",
    "contractId",
    "tenantId",
    "projectId",
    "actorId",
    "requestId",
    "runId",
    "sessionId",
    "afterSequence",
    "limit",
    "payloadReference",
    "approvalConfirmationId",
    "clarificationFactId",
    "policyVersion",
    "commandSchemaVersion",
    "workerReceiptMode",
    "clientRequestId",
)
SAFE_PAYLOAD_REFERENCE_PREFIXES = ("agent-payload:", "payload-ref:", "artifact-ref:")
SAFE_PAYLOAD_REFERENCE_PATTERN = re.compile(r"^[a-zA-Z0-9_.:/=@+-]+$")
INLINE_PAYLOAD_MARKERS = (
    "{",
    "}",
    "[",
    "]",
    "\n",
    "\r",
    "select ",
    "insert ",
    "update ",
    "delete ",
    "password",
    "secret",
    "token",
    "credential",
    "http://",
    "https://",
)


def proposal_response_summary(data: Mapping[str, Any]) -> dict[str, Any]:
    """从 Java proposal 响应中裁剪低敏字段。

    Java `AgentToolActionCommandProposalResponse` 未来可能继续扩展内部审计字段，但 Python Runtime 的
    API、runtime event 和 WebSocket replay 都不能自动透传未知字段。本函数采用显式白名单，只保留：
    proposal 状态、图/租户/运行定位、命令 schema、payloadReference 受控引用、worker receipt 配置、
    以及低敏 evidence/reason/action 机器码。
    """

    result: dict[str, Any] = {}
    scalar_keys = (
        "proposalId",
        "proposalState",
        "graphId",
        "contractId",
        "sourceEventIdentityKey",
        "tenantId",
        "projectId",
        "actorId",
        "requestId",
        "runId",
        "sessionId",
        "proposedAt",
        "toolName",
        "commandType",
        "commandSchemaVersion",
        "idempotencyKey",
        "payloadReference",
        "payloadPolicy",
        "workerReceiptMode",
        "graphState",
        "terminalState",
    )
    tuple_keys = (
        "acceptedEvidence",
        "missingEvidence",
        "rejectedEvidence",
        "guardrailNotes",
        "summaryReasons",
        "recommendedActions",
    )
    for key in scalar_keys:
        if value := text(data.get(key)):
            result[key] = value
    if (sequence := optional_positive_int(data.get("sourceReplaySequence"))) is not None:
        result["sourceReplaySequence"] = sequence
    for key in ("outboxWriteAllowedByPreflight", "workerReceiptRequired"):
        if isinstance(data.get(key), bool):
            result[key] = data[key]
    for key in tuple_keys:
        values = string_tuple(data.get(key))
        if values:
            result[key] = values
    return result


def redact_rejected_request_payload(
    request_payload: Mapping[str, Any],
    rejected_evidence: tuple[str, ...],
) -> dict[str, Any]:
    """对本地预检拒绝的请求体做二次脱敏。

    正常 proposal 请求中的 `payloadReference` 是受控引用，可以展示；但如果本地预检已经判断它像 URL、
    JSON、SQL 或凭证，就不能再把原值放进 skipped 摘要，否则“拒绝原因本身”会变成泄露通道。
    """

    result = dict(request_payload)
    if "PAYLOAD_REFERENCE_UNSAFE_OR_INLINE" in rejected_evidence:
        result["payloadReference"] = "[REJECTED_UNSAFE_REFERENCE]"
    return result


def is_safe_payload_reference(value: str) -> bool:
    """判断 payloadReference 是否像一个受控引用，而不是内联敏感内容。

    这不是最终安全机制，Java payload store 仍必须重新复核引用；但 Python 侧先做一层保守过滤，可以避免
    调用方把 URL、JSON、SQL、凭证或自由文本误塞入 proposal 请求。
    """

    value = value.strip()
    lowered = value.lower()
    if len(value) > 512:
        return False
    if not lowered.startswith(SAFE_PAYLOAD_REFERENCE_PREFIXES):
        return False
    if any(marker in lowered for marker in INLINE_PAYLOAD_MARKERS):
        return False
    return bool(SAFE_PAYLOAD_REFERENCE_PATTERN.fullmatch(value))


def json_safe(value: Any) -> Any:
    """把简单值规范化为 JSON 友好对象，复杂对象拒绝透传。"""

    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, (list, tuple)):
        return tuple(item for item in (json_safe(item) for item in value) if item is not None)
    return str(value)


def string_tuple(value: Any) -> tuple[str, ...]:
    """解析低敏字符串列表。"""

    if value is None:
        return ()
    if isinstance(value, str):
        candidates = value.split(",")
    elif isinstance(value, (list, tuple, set, frozenset)):
        candidates = value
    else:
        return ()
    return tuple(item_text for item in candidates if (item_text := text(item)))


def text(value: Any) -> str | None:
    """读取非空字符串。"""

    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


def positive_int(value: Any, *, default: int) -> int:
    """解析正整数，非法或小于等于 0 时回退默认值。"""

    parsed = optional_positive_int(value)
    return parsed if parsed is not None else default


def optional_positive_int(value: Any) -> int | None:
    """解析可选正整数。"""

    if value is None or str(value).strip() == "":
        return None
    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None
