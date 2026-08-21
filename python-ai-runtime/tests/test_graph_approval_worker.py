"""图事实 Kafka worker 的幂等、手动提交、DLQ 和对象包测试。"""

from __future__ import annotations

import os
import sys
import unittest
from dataclasses import dataclass

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.graph_approval_consumer import GraphFactApprovalConsumer
from datasmart_ai_runtime.services.rag.graph_approval_worker import (
    GraphFactApprovalWorker,
    GraphFactApprovalWorkerSettings,
    InMemoryGraphFactApprovalReceiptStore,
)
from datasmart_ai_runtime.services.rag.graph_ingestion import ControlledGraphRagIngestor


@dataclass
class _Message:
    value: object
    topic: str = "datasmart.graph.facts.approved.v1"
    partition: int = 0
    offset: int = 1


class _KafkaConsumer:
    def __init__(self) -> None:
        self.commits = 0

    def commit(self) -> None:
        self.commits += 1

    def close(self) -> None:
        return None


class _KafkaProducer:
    def __init__(self) -> None:
        self.messages = []

    def send(self, topic: str, *, key: bytes | None, value: bytes):
        self.messages.append((topic, key, value))

    def flush(self, timeout=None) -> None:
        return None

    def close(self) -> None:
        return None


class _Writer:
    def __init__(self) -> None:
        self.entities = ()
        self.edges = ()

    def upsert_entities(self, entities) -> None:
        self.entities = tuple(entities)

    def upsert_edges(self, edges) -> None:
        self.edges = tuple(edges)


class GraphApprovalWorkerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.event = {
            "schemaVersion": "datasmart.graph-facts-approved.v1",
            "eventId": "event-1",
            "approvalFactId": "approval-1",
            "factBundleUri": "s3://facts/business-graph-1.json",
            "factFingerprint": "0" * 64,
            "tenantId": "tenant-1",
            "applicationId": "app-1",
            "projectId": "project-1",
            "userId": "user-1",
            "actorId": "actor-1",
            "agentId": "agent-1",
            "sessionId": "session-1",
            "runId": "run-1",
            "delegationId": "delegation-1",
            "commandId": "command-1",
            "entityCount": 0,
            "edgeCount": 0,
        }

    def _worker(self, *, approval_consumer=None, max_attempts=3):
        kafka = _KafkaConsumer()
        producer = _KafkaProducer()
        return GraphFactApprovalWorker(
            consumer=kafka,
            producer=producer,
            approval_consumer=approval_consumer or GraphFactApprovalConsumer(
                approval_evaluator=lambda event: {"approved": False, "approvalFactId": "approval-1"},
                bundle_loader=lambda uri: (),
                ingestor=ControlledGraphRagIngestor(),
            ),
            provider=_Writer(),
            receipt_store=InMemoryGraphFactApprovalReceiptStore(),
            settings=GraphFactApprovalWorkerSettings(max_attempts=max_attempts),
        ), kafka, producer

    def test_transient_failure_retries_same_message_before_offset_commit(self) -> None:
        """短暂故障必须在同一 process_message 调用内重试，不能依赖 Kafka 自动重投。"""

        class _TransientApproval:
            def __init__(self) -> None:
                self.calls = 0

            def handle(self, event, provider):
                self.calls += 1
                if self.calls == 1:
                    raise RuntimeError("temporary Neo4j outage")

                class Result:
                    def to_dict(self):
                        return {"status": "INGESTED", "eventId": event["eventId"], "entityCount": 0, "edgeCount": 0}

                return Result()

        approval = _TransientApproval()
        worker, kafka, producer = self._worker(approval_consumer=approval, max_attempts=3)
        worker._settings = GraphFactApprovalWorkerSettings(max_attempts=3, retry_backoff_seconds=0)

        result = worker.process_message(_Message(self.event))

        self.assertEqual("INGESTED", result["status"])
        self.assertEqual(2, approval.calls)
        self.assertEqual(1, kafka.commits)
        self.assertEqual([], producer.messages)

    def test_exhausted_message_goes_to_dlt_and_commits(self) -> None:
        worker, kafka, producer = self._worker(max_attempts=3)
        worker._settings = GraphFactApprovalWorkerSettings(max_attempts=3, retry_backoff_seconds=0)
        result = worker.process_message(_Message(self.event))
        self.assertEqual("DEAD", result["status"])
        self.assertEqual(1, kafka.commits)
        self.assertEqual(1, len(producer.messages))
        self.assertIn(b"GRAPH_FACT_DLT_NO_FACT_CONTENT_OR_SECRET", producer.messages[0][2])
        self.assertEqual(3, worker._receipt_store.get("event-1")["attemptCount"])

    def test_invalid_json_goes_to_low_sensitive_dlt_without_stopping_partition(self) -> None:
        """无法建立业务范围的毒消息不写伪 receipt，只按 Kafka 位置进入低敏 DLT。"""

        worker, kafka, producer = self._worker(max_attempts=3)

        result = worker.process_message(_Message(b"not-json", partition=2, offset=9))

        self.assertEqual("DEAD", result["status"])
        self.assertEqual("EVENT_JSON_INVALID", result["errorCode"])
        self.assertEqual(1, kafka.commits)
        self.assertEqual(1, len(producer.messages))
        self.assertNotIn(b"not-json", producer.messages[0][2])

    def test_success_is_idempotent_on_duplicate_delivery(self) -> None:
        class _Approved:
            def handle(self, event, provider):
                class Result:
                    def to_dict(self):
                        return {"status": "INGESTED", "eventId": event["eventId"], "entityCount": 0, "edgeCount": 0}
                return Result()

        worker, kafka, _ = self._worker(approval_consumer=_Approved())
        first = worker.process_message(_Message(self.event))
        second = worker.process_message(_Message(self.event, offset=2))
        self.assertEqual("INGESTED", first["status"])
        self.assertEqual("SUCCEEDED", second["status"])
        self.assertEqual(2, kafka.commits)


if __name__ == "__main__":
    unittest.main()
