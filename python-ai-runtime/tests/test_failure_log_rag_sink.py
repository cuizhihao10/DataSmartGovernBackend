"""失败日志正式 RAG 摄取边界回归测试。"""

from __future__ import annotations

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.failure_log_rag_sink import (
    FailureLogRagIngestionError,
    ingest_failure_log_documents,
)
from datasmart_ai_runtime.services.rag.models import RagChunkSourceType, RagDocument


class _FakeSink:
    """记录 upsert 输入，验证 sink 不会把文档拆成额外的不可控调用。"""

    def __init__(self, chunk_count: int = 2) -> None:
        self.documents = ()
        self.chunk_count = chunk_count

    def upsert_documents(self, documents):
        self.documents = tuple(documents)
        return self.chunk_count


def _document() -> RagDocument:
    return RagDocument(
        document_id="datasync-failure-log:tenant:application:project:log:hash",
        title="data-sync failure log log-1",
        content="errorCode: NOT_NULL_VIOLATION\nmessage: target field is null",
        source_uri="datasync://tasks/task-1/executions/execution-1/logs/log-1",
        tenant_id="tenant",
        application_id="application",
        project_id="project",
        workspace_key="*",
        source_type=RagChunkSourceType.EXACT_SEARCH,
        tags=("failure-log",),
        sensitivity_level="internal",
    )


class FailureLogRagSinkTest(unittest.TestCase):
    """验证持久化声明、空输入和底层失败都会收口为稳定结果。"""

    def test_upserts_documents_and_returns_low_sensitive_counts(self) -> None:
        sink = _FakeSink(chunk_count=3)
        result = ingest_failure_log_documents((_document(),), sink)
        self.assertEqual("UPSERTED", result.status)
        self.assertEqual(1, result.document_count)
        self.assertEqual(3, result.chunk_count)
        self.assertEqual(1, len(sink.documents))
        self.assertNotIn("target field is null", result.to_dict().__repr__())

    def test_empty_failure_logs_do_not_touch_database(self) -> None:
        sink = _FakeSink()
        result = ingest_failure_log_documents((), sink)
        self.assertEqual("NO_FAILURE_LOGS", result.status)
        self.assertEqual((), sink.documents)

    def test_nonpersistent_sink_is_rejected_by_default(self) -> None:
        with self.assertRaisesRegex(FailureLogRagIngestionError, "PERSISTENCE_REQUIRED"):
            ingest_failure_log_documents((_document(),), _FakeSink(), persistent=False)

    def test_missing_upsert_is_rejected(self) -> None:
        with self.assertRaisesRegex(FailureLogRagIngestionError, "UPSERT_UNAVAILABLE"):
            ingest_failure_log_documents((_document(),), object())

    def test_underlying_failure_does_not_leak_exception_text(self) -> None:
        class BrokenSink:
            def upsert_documents(self, _documents):
                raise RuntimeError("password=secret and SQL=select * from customer")

        with self.assertRaisesRegex(FailureLogRagIngestionError, "UPSERT_FAILED") as context:
            ingest_failure_log_documents((_document(),), BrokenSink())
        self.assertNotIn("secret", str(context.exception))
        self.assertNotIn("select", str(context.exception).lower())


if __name__ == "__main__":
    unittest.main()
