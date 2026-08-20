"""真实数据同步业务图谱候选构建回归测试。"""

from __future__ import annotations

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.business_graph_builder import BusinessGraphBuilder
from datasmart_ai_runtime.services.rag.graph_ingestion import ControlledGraphRagIngestor


class BusinessGraphBuilderTest(unittest.TestCase):
    """验证快照可以串起任务、字段映射、执行错误和恢复资料。"""

    def snapshot(self) -> dict:
        return {
            "schemaVersion": "datasmart.business-graph-snapshot.v1",
            "snapshotId": "sync-snapshot-001",
            "scope": {"tenantId": "tenant-10", "applicationId": "app-20", "projectId": "project-30"},
            "sourceUri": "postgres://control-plane/snapshot/sync-snapshot-001",
            "asOf": "2026-08-20T12:00:00Z",
            "applications": [{"id": "app-20", "name": "FlashSync"}],
            "projects": [{"id": "project-30", "name": "订单同步项目", "applicationId": "app-20"}],
            "dataSources": [{"id": "source-1", "name": "订单源库", "projectId": "project-30"},
                            {"id": "target-1", "name": "数仓目标", "projectId": "project-30"}],
            "schemas": [{"id": "schema-1", "name": "public", "dataSourceId": "source-1"}],
            "tables": [{"id": "table-1", "name": "orders", "schemaId": "schema-1"}],
            "fields": [{"id": "field-1", "name": "customer_id", "tableId": "table-1"}],
            "constraints": [{"id": "constraint-1", "name": "customer_id NOT NULL", "fieldId": "field-1"}],
            "tasks": [{"id": "task-1", "name": "订单同步", "projectId": "project-30",
                       "sourceDataSourceId": "source-1", "targetDataSourceId": "target-1",
                       "sourceTableId": "table-1", "successfulVersionId": "version-7"}],
            "taskVersions": [{"id": "version-7", "name": "成功配置 v7", "taskId": "task-1"}],
            "executions": [{"id": "execution-1", "name": "执行 1", "taskId": "task-1", "errorId": "error-1", "logId": "log-1"}],
            "errors": [{"id": "error-1", "name": "字段映射错误", "errorCode": "FIELD_MAPPING_INVALID"}],
            "logs": [{"id": "log-1", "name": "execution-1 error log", "executionId": "execution-1", "errorId": "error-1"}],
            "runbooks": [{"id": "runbook-1", "name": "字段映射修复手册", "recommendedAction": "REFRESH_METADATA"}],
            "actions": [{"id": "REFRESH_METADATA", "name": "刷新元数据"}],
            "mappings": [{"id": "mapping-1", "sourceFieldId": "field-1", "targetFieldId": "field-1"}],
            "dependencies": [],
        }

    def test_builds_scoped_candidate_with_provenance(self) -> None:
        result = BusinessGraphBuilder().build(self.snapshot())
        self.assertGreaterEqual(result.entity_count, 12)
        self.assertGreaterEqual(result.edge_count, 10)
        self.assertEqual("app-20", result.document.application_id)
        self.assertNotIn("workspaceKey", result.to_fact_bundle()["documents"][0])
        self.assertTrue(all("workspaceKey" not in entity for entity in result.document.metadata["graphEntities"]))
        self.assertTrue(all("workspaceKey" not in edge for edge in result.document.metadata["graphRelations"]))
        self.assertEqual("PROPOSED", result.document.metadata["graphIngestionApproval"]["status"])
        self.assertTrue(all(edge["sourceUri"] for edge in result.document.metadata["graphRelations"]))
        self.assertTrue(all(edge["sourceChunkId"] for edge in result.document.metadata["graphRelations"]))
        relation_names = {edge["relation"] for edge in result.document.metadata["graphRelations"]}
        self.assertIn("EXECUTION_HAS_LOG", relation_names)
        self.assertIn("LOG_MATCHES_ERROR", relation_names)
        self.assertIn("FIELD_HAS_CONSTRAINT", relation_names)

    def test_candidate_cannot_be_ingested_before_approval(self) -> None:
        result = BusinessGraphBuilder().build(self.snapshot())
        with self.assertRaisesRegex(ValueError, "APPROVED"):
            ControlledGraphRagIngestor().validate_documents((result.document,))

    def test_fingerprint_is_stable_for_same_snapshot(self) -> None:
        first = BusinessGraphBuilder().build(self.snapshot())
        second = BusinessGraphBuilder().build(self.snapshot())
        self.assertEqual(first.fingerprint, second.fingerprint)


if __name__ == "__main__":
    unittest.main()
