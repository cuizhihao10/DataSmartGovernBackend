"""把业务图谱构建产生的失败日志文档送入正式 RAG 知识库。

业务图事实和失败日志故意走两条不同的摄取边界：图事实必须经过
``permission-admin -> Kafka -> Neo4j`` 审批链；失败日志只允许以低敏摘要进入
PostgreSQL/pgvector，供后续模型按 errorCode、SQLState、executionId 和 Runbook
检索。这个模块把后者的运行时合同集中起来，避免 CLI、定时任务和 HTTP worker
各自直接调用 ``upsert_documents``，从而漏掉持久化可用性和容量检查。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Protocol

from datasmart_ai_runtime.services.rag.models import RagDocument


class FailureLogRagKnowledgeSink(Protocol):
    """正式 RAG 知识库所需的最小摄取协议。"""

    def upsert_documents(self, documents: Iterable[RagDocument]) -> int:
        """按文档 ID 幂等替换 chunk，并返回实际写入的 chunk 数。"""


class FailureLogRagIngestionError(RuntimeError):
    """失败日志不能进入正式 RAG 索引时的稳定异常。"""


@dataclass(frozen=True)
class FailureLogRagIngestionResult:
    """一次失败日志摄取的低敏结果。

    结果只包含数量和持久化状态，不携带日志正文、错误消息、sourceUri 或 embedding。
    该结构可以直接写入构建器摘要和审计事件，不会扩大模型上下文。
    """

    document_count: int
    chunk_count: int
    persistent: bool
    status: str
    reason_code: str | None = None

    def to_dict(self) -> dict[str, object]:
        """输出稳定的 camelCase 低敏摘要。"""

        return {
            "documentCount": self.document_count,
            "chunkCount": self.chunk_count,
            "persistent": self.persistent,
            "status": self.status,
            "reasonCode": self.reason_code,
            "payloadPolicy": "FAILURE_LOG_RAG_RESULT_NO_DOCUMENT_BODY_OR_SECRET",
        }


def ingest_failure_log_documents(
    documents: Iterable[RagDocument],
    knowledge_sink: FailureLogRagKnowledgeSink,
    *,
    persistent: bool = True,
    require_persistent: bool = True,
) -> FailureLogRagIngestionResult:
    """将低敏失败日志文档幂等写入正式 RAG 知识库。

    ``documents`` 会先物化成有限 tuple，保证空日志不会触发数据库调用，也避免把可重复
    iterable 消费两次造成计数漂移。生产调用方必须声明 ``persistent=True``；如果传入的是
    内存知识库或不可用的 fail-closed adapter，默认直接拒绝，防止 E2E/CLI 把“构建成功”误报
    成“日志已经可检索”。单条文档重复执行的稳定 documentId 由
    ``failure_log_materializer`` 生成，底层 PostgreSQL adapter 负责幂等 upsert。
    """

    materialized = tuple(documents)
    if not materialized:
        return FailureLogRagIngestionResult(
            document_count=0,
            chunk_count=0,
            persistent=persistent,
            status="NO_FAILURE_LOGS",
        )
    if require_persistent and not persistent:
        raise FailureLogRagIngestionError("FAILURE_LOG_RAG_PERSISTENCE_REQUIRED")
    upsert = getattr(knowledge_sink, "upsert_documents", None)
    if not callable(upsert):
        raise FailureLogRagIngestionError("FAILURE_LOG_RAG_UPSERT_UNAVAILABLE")
    try:
        chunk_count = int(upsert(materialized))
    except Exception as exc:  # noqa: BLE001 - 对外只暴露稳定错误码，正文不能进入审计/模型。
        raise FailureLogRagIngestionError("FAILURE_LOG_RAG_UPSERT_FAILED") from exc
    if chunk_count < 0:
        raise FailureLogRagIngestionError("FAILURE_LOG_RAG_UPSERT_COUNT_INVALID")
    return FailureLogRagIngestionResult(
        document_count=len(materialized),
        chunk_count=chunk_count,
        persistent=persistent,
        status="UPSERTED",
    )


__all__ = [
    "FailureLogRagIngestionError",
    "FailureLogRagIngestionResult",
    "FailureLogRagKnowledgeSink",
    "ingest_failure_log_documents",
]
