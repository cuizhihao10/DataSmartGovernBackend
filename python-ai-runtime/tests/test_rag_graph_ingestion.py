"""受控 GraphRAG 图事实摄取回归测试。"""

from __future__ import annotations

import os
import sys
import unittest
from datetime import datetime, timezone


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.graph_ingestion import (
    ControlledGraphRagIngestor,
    GraphRagIngestionError,
    load_graph_fact_documents,
)
from datasmart_ai_runtime.services.rag.models import RagDocument


AS_OF = datetime(2026, 8, 20, 12, 0, tzinfo=timezone.utc)


def _document(*, entities=None, relations=None, approval_status="APPROVED", scope="tenant-10") -> RagDocument:
    """构造一份含结构化图事实的已审批来源文档。"""

    return RagDocument(
        document_id="doc-org-reporting",
        title="组织汇报关系事实",
        content="张三向李四汇报，李四向王五汇报。",
        source_uri="synthetic://datasmart-govern/graph/org-reporting",
        tenant_id=scope,
        project_id="project-101",
        workspace_key="tenant-10-project-101",
        metadata={
            "sourceStatus": "COMPLETE",
            "graphIngestionApproval": {
                "status": approval_status,
                "approvalId": "approval-graph-test-001",
            },
            "graphEntities": entities or [
                {"standardId": "person-zhang", "canonicalName": "张三", "aliases": ["小张", "张工"]},
                {"standardId": "person-li", "canonicalName": "李四"},
                {"standardId": "person-wang", "canonicalName": "王五"},
            ],
            "graphRelations": relations or [
                {
                    "sourceEntityId": "person-zhang",
                    "targetEntityId": "person-li",
                    "relation": "REPORTS_TO",
                    "sourceDocumentId": "doc-org-reporting",
                    "sourceUri": "synthetic://datasmart-govern/graph/org-reporting",
                    "sourceChunkId": "org-reporting-c1",
                    "assertedAt": "2026-08-01T00:00:00Z",
                    "effectiveAt": "2026-08-01T00:00:00Z",
                    "confidence": 0.94,
                },
                {
                    "sourceEntityId": "person-li",
                    "targetEntityId": "person-wang",
                    "relation": "REPORTS_TO",
                    "sourceDocumentId": "doc-org-reporting",
                    "sourceUri": "synthetic://datasmart-govern/graph/org-reporting",
                    "sourceChunkId": "org-reporting-c2",
                    "assertedAt": "2026-08-01T00:00:00Z",
                    "effectiveAt": "2026-08-01T00:00:00Z",
                    "confidence": 0.93,
                },
            ],
        },
    )


class _FakeGraphWriter:
    """只记录写入参数，验证校验失败时不会调用 Provider。"""

    def __init__(self) -> None:
        self.entities = ()
        self.edges = ()

    def upsert_entities(self, entities) -> None:
        self.entities = tuple(entities)

    def upsert_edges(self, edges) -> None:
        self.edges = tuple(edges)


class ControlledGraphRagIngestorTest(unittest.TestCase):
    """验证图事实摄取的审批、来源、范围和冲突门禁。"""

    def test_valid_facts_are_written_idempotently_by_provider_contract(self) -> None:
        """合法的两跳关系应写入三实体两关系，并得到稳定指纹。"""

        writer = _FakeGraphWriter()
        ingestor = ControlledGraphRagIngestor()
        first = ingestor.ingest((_document(),), writer)
        second = ingestor.ingest((_document(),), writer)

        self.assertEqual("INGESTED", first.status)
        self.assertEqual(first.fingerprint, second.fingerprint)
        self.assertEqual((3, 2), (first.entity_count, first.edge_count))
        self.assertEqual((3, 2), (len(writer.entities), len(writer.edges)))
        self.assertEqual(("person-li", "person-wang", "person-zhang"), tuple(item.standard_id for item in writer.entities))

    def test_dry_run_does_not_call_provider(self) -> None:
        """dry-run 可用于审批前校验，不能产生数据库副作用。"""

        writer = _FakeGraphWriter()
        result = ControlledGraphRagIngestor().ingest((_document(),), writer, dry_run=True)

        self.assertEqual("VALIDATED_NOT_WRITTEN", result.status)
        self.assertEqual((), writer.entities)
        self.assertEqual((), writer.edges)

    def test_missing_approval_fails_closed_before_write(self) -> None:
        """模型提案没有审批状态时必须拒绝，不能直接进入图。"""

        writer = _FakeGraphWriter()
        with self.assertRaisesRegex(GraphRagIngestionError, "APPROVED"):
            ControlledGraphRagIngestor().ingest((_document(approval_status="PROPOSED"),), writer)
        self.assertEqual((), writer.entities)
        self.assertEqual((), writer.edges)

    def test_relation_provenance_must_match_owner_document(self) -> None:
        """关系不能借用其他文档的 URI 或 chunk 冒充当前来源。"""

        relations = [dict(_document().metadata["graphRelations"][0], sourceUri="synthetic://other-doc")]
        with self.assertRaisesRegex(GraphRagIngestionError, "来源绑定"):
            ControlledGraphRagIngestor().validate_documents((_document(relations=relations),))

    def test_current_conflict_fails_before_write(self) -> None:
        """同一员工同时指向两个当前上级时不能靠 confidence 猜答案。"""

        entities = [
            {"standardId": "person-zhang", "canonicalName": "张三"},
            {"standardId": "person-li", "canonicalName": "李四"},
            {"standardId": "person-wang", "canonicalName": "王五"},
        ]
        base = _document().metadata["graphRelations"][0]
        relations = [base, dict(base, targetEntityId="person-wang", sourceChunkId="org-reporting-conflict")]
        with self.assertRaisesRegex(GraphRagIngestionError, "存在冲突"):
            ControlledGraphRagIngestor().validate_documents((_document(entities=entities, relations=relations),))

    def test_private_document_cannot_widen_fact_to_global_scope(self) -> None:
        """私有文档中的图事实改成全局范围时必须拒绝。"""

        entities = [dict(item, tenantId="*") for item in _document().metadata["graphEntities"]]
        with self.assertRaisesRegex(GraphRagIngestionError, "超出了来源文档范围"):
            ControlledGraphRagIngestor().validate_documents((_document(entities=entities),))

    def test_json_fact_bundle_is_loaded_as_explicit_document_metadata(self) -> None:
        """JSON 事实包应保留来源文档和结构化字段，交给同一校验器处理。"""

        import json
        import tempfile
        from pathlib import Path

        payload = {
            "schemaVersion": "datasmart.graph-facts.v1",
            "documents": [
                {
                    "documentId": "doc-json",
                    "title": "JSON 图事实",
                    "content": "受控事实摘要",
                    "sourceUri": "synthetic://json/doc",
                    "tenantId": "tenant-10",
                    "applicationId": "application-101",
                    "projectId": "project-101",
                    "metadata": {
                        "sourceStatus": "COMPLETE",
                        "graphIngestionApproval": {"status": "APPROVED", "approvalId": "approval-json"},
                    },
                    "graphEntities": [{"standardId": "entity-a", "canonicalName": "实体A"}],
                    "graphRelations": [],
                }
            ],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "facts.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            documents = load_graph_fact_documents(path)

        result = ControlledGraphRagIngestor().ingest(documents, _FakeGraphWriter(), dry_run=True)
        self.assertEqual(1, result.entity_count)
        self.assertEqual(0, result.edge_count)


if __name__ == "__main__":
    unittest.main()
