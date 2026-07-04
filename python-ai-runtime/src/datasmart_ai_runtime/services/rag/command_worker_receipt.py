"""RAG 查询结果写回 Java command worker receipt 的低敏辅助器。

RAG 在 Agent 产品里很容易被误实现成“把问题、答案、引用正文直接塞进日志或控制面”。这在 demo 中方便，但在
商业化项目中会带来三类问题：
1. 用户问题可能包含客户业务秘密、表名、字段名或排障细节；
2. RAG answer 和 compressed context 是模型上下文正文，不应该进入 Java runtime event / receipt index；
3. sourceUri、文档正文、chunk 文本一旦进入控制面，后续审计导出、Grafana、前端 timeline 都可能成为二次泄露面。

因此本模块只生成 Java `AgentToolActionCommandWorkerReceiptRequest` 可消费的低敏事实：
- `commandId/runId/sessionId`：串联 proposal -> outbox -> worker receipt；
- `queryRef`：已经哈希化或物化后的查询引用，不是原始 question；
- `artifactReference`：受控答案产物引用，不是答案正文；
- 候选数、选中 chunk 数、引用数等计数：用于观测和排障，不暴露文档内容。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any


RAG_COMMAND_WORKER_RECEIPT_SCHEMA_VERSION = "datasmart.python-ai-runtime.rag-command-worker-receipt.v1"
RAG_COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY = (
    "RAG_RECEIPT_SUMMARY_ONLY_NO_QUESTION_NO_ANSWER_NO_CONTEXT_NO_DOCUMENT_TEXT_NO_SOURCE_URI"
)
RAG_TOOL_CODE = "knowledge.rag.query"

_SAFE_QUERY_REF_PATTERN = re.compile(r"^(rag-query|query-ref|agent-query):[a-zA-Z0-9_.:/=@+-]{1,180}$")
_SAFE_REFERENCE_PATTERN = re.compile(r"^[a-zA-Z0-9_.:/=@+-]{1,220}$")
_SAFE_ARTIFACT_REFERENCE_PREFIXES = (
    "agent-artifact:",
    "artifact:",
    "minio-object:",
    "agent-output:",
    "command-output:",
    "task-artifact:",
)
_SENSITIVE_MARKERS = (
    "select ",
    "insert ",
    "update ",
    "delete ",
    "authorization:",
    "bearer ",
    "password",
    "secret",
    "credential",
    "api_key",
    "apikey",
    "prompt:",
    "question:",
    "answer:",
    "compressedcontext",
    "sourceuri",
    "document text",
    "chunk text",
    "http://",
    "https://",
    "jdbc:",
    "{",
    "}",
    "\n",
    "\r",
)


class RagCommandWorkerReceiptOutcome(str, Enum):
    """RAG worker receipt 的业务结果。

    Java 侧当前允许低敏 outcome 扩展，因此这里使用 `RAG_QUERY_COMPLETED` 表达“只读 RAG 查询完成并登记摘要”。
    它不等同于 shell 类命令的 `EXECUTION_SUCCEEDED`，也不会声明 `sideEffectExecuted=true`，避免要求 command
    worker lease 才能记录读操作结果。
    """

    RAG_QUERY_COMPLETED = "RAG_QUERY_COMPLETED"
    RAG_QUERY_FAILED_PRECHECK = "RAG_QUERY_FAILED_PRECHECK"


@dataclass(frozen=True)
class RagCommandWorkerReceiptEvidence:
    """构造 RAG command worker receipt 所需的低敏证据。

    字段说明：
    - `command_id/session_id/run_id`：来自 Java command outbox 与 Agent 会话，是回执定位主键；
    - `query_ref`：原始问题的受控引用，例如 `rag-query:sha256:xxxx`，不能是问题正文；
    - `answer_artifact_reference`：RAG 答案或检索摘要的受控产物引用，不能是 URL、路径或正文；
    - `candidate_count/selected_chunk_count/citation_count`：只保存数量，用于质量分析和运维排障；
    - `retrieval_policy_version`：记录当时使用的 RAG 检索策略，便于后续复盘为什么召回了这些证据。
    """

    command_id: str
    session_id: str
    run_id: str
    query_ref: str
    tenant_id: int | None = None
    project_id: int | None = None
    actor_id: int | None = None
    task_id: int | None = None
    task_run_id: int | None = None
    executor_id: str = "python-rag-query-worker"
    answer_artifact_reference: str | None = None
    artifact_reference_type: str = "AGENT_RAG_ANSWER_ARTIFACT"
    candidate_count: int = 0
    selected_chunk_count: int = 0
    citation_count: int = 0
    retrieval_policy_version: str = "rag-policy.v1"
    idempotency_key: str | None = None
    recommended_actions: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class RagCommandWorkerReceipt:
    """RAG receipt 的 Python 表达。

    `JavaCommandWorkerReceiptClient` 只要求对象具备 `outcome.value` 与 `java_payload`，因此该类可以作为结构化
    receipt 被直接 POST 到 Java worker receipt 路由，同时保留 RAG 专属 `rag_summary` 供测试和诊断查看。
    """

    outcome: RagCommandWorkerReceiptOutcome
    java_payload: dict[str, Any]
    rag_summary: dict[str, Any]
    schema_version: str = RAG_COMMAND_WORKER_RECEIPT_SCHEMA_VERSION
    payload_policy: str = RAG_COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY

    def to_summary(self) -> dict[str, Any]:
        """输出低敏摘要，不包含 question、answer、sourceUri、chunk 文本或 compressedContext。"""

        return {
            "schemaVersion": self.schema_version,
            "payloadPolicy": self.payload_policy,
            "outcome": self.outcome.value,
            "javaPayload": dict(self.java_payload),
            "ragSummary": dict(self.rag_summary),
        }


class RagCommandWorkerReceiptBuilder:
    """把 RAG 查询结果低敏化为 Java command worker receipt payload。"""

    def build_receipt(self, evidence: RagCommandWorkerReceiptEvidence) -> RagCommandWorkerReceipt:
        """构造 RAG worker receipt。

        该方法只接收低敏引用和计数，不接收原始 question、answer、compressedContext 或文档正文。调用方如果只有
        `RagPipelineResult`，也必须先把答案正文写入受控 artifact store，再把 artifactReference 传入本方法。
        """

        query_ref = _safe_query_ref(evidence.query_ref)
        artifact_reference = _safe_artifact_reference(evidence.answer_artifact_reference)
        outcome = RagCommandWorkerReceiptOutcome.RAG_QUERY_COMPLETED
        candidate_count = _non_negative_int(evidence.candidate_count)
        selected_chunk_count = _non_negative_int(evidence.selected_chunk_count)
        citation_count = _non_negative_int(evidence.citation_count)
        message = (
            "RAG 查询已完成低敏回执："
            f"candidateCount={candidate_count}, selectedChunkCount={selected_chunk_count}, citationCount={citation_count}。"
            "答案正文与证据正文仅能通过受控 artifactReference 读取。"
        )
        recommended_actions = _safe_actions(
            (
                "通过 artifactReference 进入受控产物读取流程，不要从 worker receipt 读取答案正文。",
                "如需二轮模型调用，先用 commandId 查询 worker receipt index 并校验租户/项目边界。",
                *evidence.recommended_actions,
            )
        )
        java_payload = {
            "commandId": _required_reference(evidence.command_id, "command_id"),
            "taskId": _optional_int(evidence.task_id),
            "taskRunId": _optional_int(evidence.task_run_id),
            "executorId": _safe_short_text(evidence.executor_id, fallback="python-rag-query-worker", max_length=160),
            "tenantId": _optional_int(evidence.tenant_id),
            "projectId": _optional_int(evidence.project_id),
            "actorId": _optional_int(evidence.actor_id),
            "taskStatus": "SUCCEEDED",
            "outcome": outcome.value,
            "preCheckPassed": True,
            "sideEffectStarted": False,
            "sideEffectExecuted": False,
            "workerLeaseRequired": False,
            "commandSafetyDecision": "ALLOW_READ_ONLY_RAG_QUERY",
            "commandSafetyPolicyVersion": _safe_short_text(
                evidence.retrieval_policy_version,
                fallback="rag-policy.v1",
                max_length=160,
            ),
            "commandSafetyIssueCodes": [],
            "normalizedTimeoutSeconds": 0,
            "normalizedOutputByteLimitBytes": 0,
            "artifactReferenceType": _safe_short_text(
                evidence.artifact_reference_type,
                fallback="AGENT_RAG_ANSWER_ARTIFACT",
                max_length=80,
            ),
            "artifactReference": artifact_reference,
            "artifactAvailable": bool(artifact_reference),
            "errorCode": "AGENT_RAG_QUERY_COMPLETED",
            "auditId": query_ref,
            "toolCode": RAG_TOOL_CODE,
            "targetService": "python-ai-runtime-rag",
            "workerReceiptMode": "READ_ONLY_QUERY_SUMMARY",
            "message": message,
            "recommendedActions": list(recommended_actions),
            "idempotencyKey": _idempotency_key(evidence, query_ref),
        }
        rag_summary = {
            "queryRef": query_ref,
            "candidateCount": candidate_count,
            "selectedChunkCount": selected_chunk_count,
            "citationCount": citation_count,
            "artifactReferencePresent": bool(artifact_reference),
            "payloadPolicy": RAG_COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY,
        }
        return RagCommandWorkerReceipt(
            outcome=outcome,
            java_payload=_drop_none(java_payload),
            rag_summary=rag_summary,
        )


def _safe_query_ref(value: str) -> str:
    """校验 queryRef 是受控引用，而不是原始问题。"""

    text_value = _required_reference(value, "query_ref")
    if not _SAFE_QUERY_REF_PATTERN.fullmatch(text_value) or _looks_sensitive(text_value):
        raise ValueError("query_ref 必须是 rag-query:/query-ref:/agent-query: 开头的低敏引用，不能是问题正文")
    return text_value


def _safe_artifact_reference(value: str | None) -> str | None:
    """校验 artifactReference 是受控产物引用，而不是 URL、路径或答案正文。"""

    if value is None or str(value).strip() == "":
        return None
    reference = str(value).strip()
    lowered = reference.lower()
    if not lowered.startswith(_SAFE_ARTIFACT_REFERENCE_PREFIXES):
        raise ValueError("answer_artifact_reference 必须使用受控 artifact scheme")
    if _looks_sensitive(reference) or "://" in lowered or ".." in lowered or "\\" in reference:
        raise ValueError("answer_artifact_reference 不能是 URL、路径、正文、凭据或带路径逃逸的引用")
    if not _SAFE_REFERENCE_PATTERN.fullmatch(reference):
        raise ValueError("answer_artifact_reference 包含非法字符")
    return reference


def _required_reference(value: Any, field_name: str) -> str:
    """读取必填低敏引用字段。"""

    text_value = _safe_short_text(value, fallback=None, max_length=220)
    if not text_value:
        raise ValueError(f"{field_name} 不能为空")
    if not _SAFE_REFERENCE_PATTERN.fullmatch(text_value):
        raise ValueError(f"{field_name} 只能包含低敏引用允许的字符")
    return text_value


def _safe_actions(actions: tuple[str, ...]) -> tuple[str, ...]:
    """清洗推荐动作，限制数量并拒绝敏感正文。"""

    result: list[str] = []
    for action in actions[:6]:
        text_value = _safe_short_text(action, fallback=None, max_length=220)
        if text_value:
            result.append(text_value)
    return tuple(result)


def _safe_short_text(value: Any, *, fallback: str | None, max_length: int) -> str | None:
    """普通低敏文本清洗。"""

    if value is None:
        return fallback
    text_value = str(value).strip()
    if not text_value:
        return fallback
    if _looks_sensitive(text_value):
        if fallback is None:
            raise ValueError("低敏文本字段疑似包含问题、答案、SQL、URL、凭据、上下文或文档正文")
        return fallback
    return text_value[:max_length]


def _looks_sensitive(value: Any) -> bool:
    """用轻量 marker 判断文本是否疑似包含敏感正文。"""

    lowered = str(value or "").lower()
    return any(marker in lowered for marker in _SENSITIVE_MARKERS)


def _non_negative_int(value: Any) -> int:
    """把计数字段规整为非负整数。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return 0
    return max(parsed, 0)


def _optional_int(value: Any) -> int | None:
    """解析可选非负整数 ID。"""

    if value is None or str(value).strip() == "":
        return None
    parsed = _non_negative_int(value)
    return parsed


def _idempotency_key(evidence: RagCommandWorkerReceiptEvidence, query_ref: str) -> str:
    """生成 RAG receipt 幂等键。"""

    explicit = _safe_short_text(evidence.idempotency_key, fallback=None, max_length=220)
    if explicit:
        return explicit
    return f"rag-worker:{evidence.run_id}:{evidence.command_id}:{query_ref}"


def _drop_none(payload: dict[str, Any]) -> dict[str, Any]:
    """删除 None 字段，保持 Java JSON payload 简洁。"""

    return {key: value for key, value in payload.items() if value is not None}
