"""失败日志 RAG 物化的低敏和幂等合同测试。"""

from __future__ import annotations

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.failure_log_materializer import materialize_failure_log_documents


class FailureLogMaterializerTest(unittest.TestCase):
    """确保失败日志能被精确检索且不会把敏感正文送入 RAG。"""

    def test_materializes_only_failure_logs_and_redacts_sensitive_content(self) -> None:
        snapshot = {
            "snapshotId": "task-106-execution-2714",
            "scope": {"tenantId": "10", "applicationId": "100", "projectId": "101"},
            "logs": [
                {"id": 1, "taskId": 106, "executionId": 2714, "logLevel": "INFO", "eventStatus": "RUNNING", "message": "ok"},
                {
                    "id": 2, "taskId": 106, "executionId": 2714, "logLevel": "ERROR", "eventStatus": "FAILED",
                    "logStage": "WRITE", "eventType": "CONNECTOR_ERROR", "errorIds": ["NOT_NULL_VIOLATION"],
                    "message": "password=top-secret insert into target_table failed; stacktrace: java.lang.IllegalStateException",
                },
            ],
        }
        documents = materialize_failure_log_documents(snapshot)
        self.assertEqual(1, len(documents))
        document = documents[0]
        self.assertIn("NOT_NULL_VIOLATION", document.content)
        self.assertNotIn("top-secret", document.content)
        self.assertNotIn("insert into", document.content.lower())
        self.assertEqual("100", document.application_id)
        self.assertEqual("exact_search", document.source_type.value)
        self.assertIn("graphFactDocumentId", document.metadata)

    def test_same_snapshot_is_idempotent(self) -> None:
        snapshot = {"scope": {"tenantId": "10", "applicationId": "100", "projectId": "101"}, "logs": [{"id": 2, "executionId": 2714, "logLevel": "ERROR", "eventStatus": "FAILED", "message": "timeout"}]}
        first = materialize_failure_log_documents(snapshot)
        second = materialize_failure_log_documents(snapshot)
        self.assertEqual(first[0].document_id, second[0].document_id)
        self.assertEqual(first[0].content, second[0].content)


if __name__ == "__main__":
    unittest.main()
