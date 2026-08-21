"""真实数据同步业务图谱候选构建回归测试。"""

from __future__ import annotations

import importlib.util
import os
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

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
            "sourceStatus": "COMPLETE",
            "metadataWarnings": [],
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
            "runbooks": [{"id": "runbook-1", "name": "字段映射修复手册", "recommendedAction": "REFRESH_METADATA",
                           "errorId": "error-1"}],
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
        self.assertIn("RUNBOOK_ADDRESSES_ERROR", relation_names)

    def test_candidate_cannot_be_ingested_before_approval(self) -> None:
        result = BusinessGraphBuilder().build(self.snapshot())
        with self.assertRaisesRegex(ValueError, "APPROVED"):
            ControlledGraphRagIngestor().validate_documents((result.document,))

    def test_fingerprint_is_stable_for_same_snapshot(self) -> None:
        first = BusinessGraphBuilder().build(self.snapshot())
        second = BusinessGraphBuilder().build(self.snapshot())
        self.assertEqual(first.fingerprint, second.fingerprint)

    def test_incomplete_metadata_snapshot_fails_closed(self) -> None:
        snapshot = self.snapshot()
        snapshot["sourceStatus"] = "INCOMPLETE"
        snapshot["metadataWarnings"] = ["TARGET_METADATA_DISCOVERY_UNAVAILABLE"]
        with self.assertRaisesRegex(ValueError, "sourceStatus"):
            BusinessGraphBuilder().build(snapshot)

    def test_minio_uploader_uses_scoped_immutable_object_key(self) -> None:
        """MinIO 上传必须按 tenant/application/project/fingerprint 生成低敏稳定对象地址。

        该测试直接加载 CLI 脚本而不启动网络服务，并用假的 boto3 client 记录调用。除了验证 bucket、
        key、ContentType 和双摘要元数据，还特意把凭据设置成醒目标记，确认它们只进入 SDK 构造参数，
        不会进入 s3 URI、对象 key 或 metadata。这样 E2E 可以安全把 URI写入 Kafka/outbox，而无需传播 Secret。
        """

        repository_root = Path(__file__).resolve().parents[2]
        script_path = repository_root / "scripts" / "rag-business-graph-build.py"
        spec = importlib.util.spec_from_file_location("rag_business_graph_build_test_module", script_path)
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader if spec else None)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        class FakeS3Client:
            """记录 head/create/put 调用，模拟 bucket 尚不存在后由脚本创建。"""

            def __init__(self) -> None:
                self.created: list[dict] = []
                self.puts: list[dict] = []

            def head_bucket(self, **_: object) -> None:
                raise RuntimeError("missing bucket")

            def create_bucket(self, **kwargs: object) -> None:
                self.created.append(dict(kwargs))

            def put_object(self, **kwargs: object) -> None:
                self.puts.append(dict(kwargs))

        fake_client = FakeS3Client()
        client_arguments: dict[str, object] = {}

        def build_client(_service_name: str, **kwargs: object) -> FakeS3Client:
            client_arguments.update(kwargs)
            return fake_client

        fake_boto3 = types.SimpleNamespace(client=build_client)
        fingerprint = "a" * 64
        with patch.dict(
            os.environ,
            {
                "DATASMART_GRAPH_FACT_MINIO_ACCESS_KEY": "test-access-secret-marker",
                "DATASMART_GRAPH_FACT_MINIO_SECRET_KEY": "test-private-secret-marker",
            },
            clear=False,
        ), patch.dict(sys.modules, {"boto3": fake_boto3}):
            uri = module._upload_fact_bundle(
                b'{"schemaVersion":"datasmart.graph-facts.v1"}',
                endpoint="http://minio:9000",
                bucket="datasmart-graph-facts",
                prefix="business-graph",
                tenant_id="10",
                application_id="10001",
                project_id="101",
                fingerprint=fingerprint,
                region="us-east-1",
            )

        expected_key = f"business-graph/10/10001/101/{fingerprint}.json"
        self.assertEqual(f"s3://datasmart-graph-facts/{expected_key}", uri)
        self.assertEqual([{"Bucket": "datasmart-graph-facts"}], fake_client.created)
        self.assertEqual(expected_key, fake_client.puts[0]["Key"])
        self.assertEqual("application/json; charset=utf-8", fake_client.puts[0]["ContentType"])
        self.assertEqual(fingerprint, fake_client.puts[0]["Metadata"]["graph-fact-fingerprint"])
        self.assertRegex(fake_client.puts[0]["Metadata"]["bundle-sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual("test-access-secret-marker", client_arguments["aws_access_key_id"])
        self.assertNotIn("secret-marker", uri)
        self.assertNotIn("secret-marker", str(fake_client.puts[0]["Metadata"]))


if __name__ == "__main__":
    unittest.main()
