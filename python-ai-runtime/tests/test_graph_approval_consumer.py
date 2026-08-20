"""图事实审批事件消费回归测试。"""

from __future__ import annotations

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.business_graph_builder import BusinessGraphBuilder
from datasmart_ai_runtime.services.rag.graph_approval_consumer import GraphFactApprovalConsumer, GraphFactApprovalConsumerError
from datasmart_ai_runtime.services.rag.graph_ingestion import ControlledGraphRagIngestor


class _Writer:
    def __init__(self) -> None:
        self.entities = ()
        self.edges = ()

    def upsert_entities(self, entities) -> None:
        self.entities = tuple(entities)

    def upsert_edges(self, edges) -> None:
        self.edges = tuple(edges)


class GraphApprovalConsumerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.snapshot = {
            "schemaVersion": "datasmart.business-graph-snapshot.v1",
            "snapshotId": "approval-snapshot-001",
            "scope": {"tenantId": "tenant-10", "applicationId": "app-20", "projectId": "project-30"},
            "applications": [{"id": "app-20", "name": "FlashSync"}],
            "projects": [{"id": "project-30", "name": "项目", "applicationId": "app-20"}],
            "tasks": [{"id": "task-1", "name": "订单同步", "projectId": "project-30"}],
        }
        self.built = BusinessGraphBuilder().build(self.snapshot)
        self.document = self.built.document.__class__(
            **{**self.built.document.__dict__,
               "metadata": {**self.built.document.metadata,
                            "graphIngestionApproval": {"status": "APPROVED", "approvalId": "approval-1"}}}
        )

    def event(self) -> dict:
        return {
            "schemaVersion": "datasmart.graph-facts-approved.v1",
            "eventId": "event-1",
            "approvalFactId": "approval-1",
            "factBundleUri": "memory://approval-snapshot-001",
            "factFingerprint": self.built.fingerprint,
            "tenantId": "tenant-10",
            "applicationId": "app-20",
            "projectId": "project-30",
            "entityCount": self.built.entity_count,
            "edgeCount": self.built.edge_count,
        }

    def test_approved_event_writes_only_after_server_evaluation(self) -> None:
        writer = _Writer()
        consumer = GraphFactApprovalConsumer(
            approval_evaluator=lambda event: {"approved": True, "approvalFactId": "approval-1"},
            bundle_loader=lambda uri: (self.document,),
            ingestor=ControlledGraphRagIngestor(),
        )
        result = consumer.handle(self.event(), writer)
        self.assertEqual("INGESTED", result.status)
        self.assertEqual(self.built.entity_count, len(writer.entities))
        self.assertEqual(self.built.edge_count, len(writer.edges))

    def test_unapproved_event_fails_closed(self) -> None:
        consumer = GraphFactApprovalConsumer(
            approval_evaluator=lambda event: {"approved": False, "approvalFactId": "approval-1"},
            bundle_loader=lambda uri: (self.document,),
        )
        with self.assertRaisesRegex(GraphFactApprovalConsumerError, "未批准"):
            consumer.handle(self.event(), _Writer())

    def test_fingerprint_mismatch_fails_before_write(self) -> None:
        event = self.event()
        event["factFingerprint"] = "0" * 64
        consumer = GraphFactApprovalConsumer(
            approval_evaluator=lambda current: {"approved": True, "approvalFactId": "approval-1"},
            bundle_loader=lambda uri: (self.document,),
        )
        with self.assertRaisesRegex(ValueError, "指纹"):
            consumer.handle(event, _Writer())


if __name__ == "__main__":
    unittest.main()
