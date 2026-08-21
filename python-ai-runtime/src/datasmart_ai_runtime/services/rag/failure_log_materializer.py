"""把 data-sync 失败日志物化为可治理的 RAG 文档。

失败日志和业务图事实必须共用 tenant/application/project 范围，但两者的用途不同：图事实回答
“实体之间有什么关系”，RAG 文档负责按 errorCode、SQLState、任务/执行 ID 和阶段检索处理依据。
本模块只接受 Java 控制面已经裁剪过的日志快照，并再次执行低敏裁剪，避免把 SQL、凭据、完整堆栈
或样本值送入 Embedding/Reranker。
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Iterable, Mapping

from datasmart_ai_runtime.services.rag.models import RagChunkSourceType, RagDocument


_SENSITIVE_PATTERNS = (
    re.compile(r"(?i)(password|passwd|secret|token|authorization|api[_-]?key)\s*[:=]\s*[^\s,;]+"),
    re.compile(r"(?is)\b(select|insert|update|delete|merge|alter|create|drop)\b.*?(?=\n|$)"),
    re.compile(r"(?is)(stacktrace|traceback|exception)\s*[:=].*?(?=\n\n|$)"),
)


def _low_sensitivity_text(value: Any, *, limit: int = 512) -> str:
    """只保留可用于故障定位的短摘要，并删除常见凭据/SQL/堆栈模式。"""

    text = str(value or "").strip()
    for pattern in _SENSITIVE_PATTERNS:
        text = pattern.sub("[REDACTED]", text)
    return " ".join(text.split())[:limit]


def materialize_failure_log_documents(snapshot: Mapping[str, Any]) -> tuple[RagDocument, ...]:
    """从业务快照生成幂等失败日志文档。

    文档 ID 由范围、执行、日志 ID 和内容摘要组成；同一日志重复摄取只会更新同一份文档。
    普通 INFO 日志不进入故障知识库，避免把正常时间线噪声送入向量索引。
    """

    scope = snapshot.get("scope") if isinstance(snapshot.get("scope"), Mapping) else {}
    tenant_id = str(scope.get("tenantId") or scope.get("tenant_id") or "").strip()
    application_id = str(scope.get("applicationId") or scope.get("application_id") or "").strip()
    project_id = str(scope.get("projectId") or scope.get("project_id") or "").strip()
    if not tenant_id or not application_id or not project_id:
        raise ValueError("失败日志物化需要 tenantId/applicationId/projectId 范围")
    snapshot_id = _low_sensitivity_text(snapshot.get("snapshotId") or "snapshot", limit=160)
    documents: list[RagDocument] = []
    for index, raw in enumerate(snapshot.get("logs") or ()):
        if not isinstance(raw, Mapping):
            continue
        level = _low_sensitivity_text(raw.get("logLevel"), limit=32).upper()
        status = _low_sensitivity_text(raw.get("eventStatus"), limit=32).upper()
        if level not in {"ERROR", "WARN", "FATAL"} and status not in {"FAILED", "BLOCKED", "ERROR"}:
            continue
        log_id = _low_sensitivity_text(raw.get("id") or f"index-{index}", limit=96)
        execution_id = _low_sensitivity_text(raw.get("executionId"), limit=96)
        error_ids = tuple(_low_sensitivity_text(item, limit=96) for item in (raw.get("errorIds") or ()))
        error_code = _low_sensitivity_text(raw.get("errorCode"), limit=96)
        sql_state = _low_sensitivity_text(raw.get("sqlState"), limit=32)
        message = _low_sensitivity_text(raw.get("message"), limit=512)
        content_lines = [
            f"executionId: {execution_id}",
            f"logLevel: {level}",
            f"eventStatus: {status}",
            f"logStage: {_low_sensitivity_text(raw.get('logStage'), limit=96)}",
            f"eventType: {_low_sensitivity_text(raw.get('eventType'), limit=96)}",
            f"errorCode: {error_code}",
            f"sqlState: {sql_state}",
            f"errorIds: {', '.join(error_ids)}",
            f"message: {message}",
        ]
        content = "\n".join(line for line in content_lines if not line.endswith(": "))
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()[:24]
        document_id = f"datasync-failure-log:{tenant_id}:{application_id}:{project_id}:{log_id}:{digest}"
        documents.append(RagDocument(
            document_id=document_id,
            title=f"data-sync failure log {log_id}",
            content=content,
            source_uri=f"datasync://tasks/{_low_sensitivity_text(raw.get('taskId'), limit=64)}/executions/{execution_id}/logs/{log_id}",
            tenant_id=tenant_id,
            application_id=application_id,
            project_id=project_id,
            workspace_key="*",
            source_type=RagChunkSourceType.EXACT_SEARCH,
            tags=("data-sync", "failure-log", "auto-materialized", *("error:" + code for code in error_ids if code)),
            sensitivity_level="internal",
            metadata={
                "snapshotId": snapshot_id,
                "taskId": _low_sensitivity_text(raw.get("taskId"), limit=64),
                "executionId": execution_id,
                "logId": log_id,
                "errorCode": error_code,
                "sqlState": sql_state,
                "graphFactDocumentId": f"business-graph:{snapshot_id}",
                "payloadPolicy": "LOW_SENSITIVITY_FAILURE_SUMMARY_NO_SQL_NO_CREDENTIALS_NO_STACKTRACE_NO_SAMPLE",
            },
        ))
    return tuple(documents)


__all__ = ["materialize_failure_log_documents"]
