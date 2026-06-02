import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_publisher import (
    KafkaRuntimeEventPublisher,
    NoopRuntimeEventPublisher,
)


class FakeKafkaSendProducer:
    """模拟 kafka-python 风格 producer。

    kafka-python 使用 `send(topic, key=..., value=...)`，本 fake 只记录调用参数，避免单元测试依赖真实 Kafka broker。
    """

    def __init__(self) -> None:
        self.sent: list[dict[str, str]] = []
        self.flush_count = 0

    def send(self, topic: str, key: str, value: str) -> None:
        self.sent.append({"topic": topic, "key": key, "value": value})

    def flush(self) -> None:
        self.flush_count += 1


class FakeKafkaProduceProducer:
    """模拟 confluent-kafka 风格 producer。"""

    def __init__(self) -> None:
        self.produced: list[dict[str, str]] = []

    def produce(self, topic: str, key: str, value: str) -> None:
        self.produced.append({"topic": topic, "key": key, "value": value})


class RuntimeEventPublisherTest(unittest.TestCase):
    def test_noop_publisher_keeps_local_runtime_zero_dependency(self) -> None:
        publisher = NoopRuntimeEventPublisher()

        self.assertEqual(0, publisher.publish((_event(),)))

    def test_kafka_publisher_serializes_event_and_uses_run_id_as_partition_key(self) -> None:
        producer = FakeKafkaSendProducer()
        publisher = KafkaRuntimeEventPublisher(
            producer,
            topic="datasmart.test.agent-events",
            flush_on_publish=True,
        )

        count = publisher.publish((_event(run_id="run-001", session_id="session-ignored"),))

        self.assertEqual(1, count)
        self.assertEqual(1, producer.flush_count)
        self.assertEqual("datasmart.test.agent-events", producer.sent[0]["topic"])
        self.assertEqual("run-001", producer.sent[0]["key"])
        payload = json.loads(producer.sent[0]["value"])
        self.assertEqual("agent-runtime-event.v1", payload["schemaVersion"])
        self.assertEqual("python-ai-runtime", payload["source"])
        self.assertEqual("tool_planned", payload["eventType"])
        self.assertEqual("audit", payload["severity"])
        self.assertEqual("tenant-a", payload["tenantId"])
        self.assertEqual({"tokens": 128, "modes": ["sync", "approval"]}, payload["attributes"])
        self.assertIn("publishedAt", payload)
        self.assertIn("createdAt", payload)

    def test_kafka_publisher_supports_confluent_produce_style(self) -> None:
        producer = FakeKafkaProduceProducer()
        publisher = KafkaRuntimeEventPublisher(producer, topic="datasmart.test.agent-events")

        count = publisher.publish((_event(run_id=None, session_id="session-001"),))

        self.assertEqual(1, count)
        self.assertEqual("datasmart.test.agent-events", producer.produced[0]["topic"])
        self.assertEqual("session-001", producer.produced[0]["key"])

    def test_kafka_publisher_returns_zero_for_empty_events(self) -> None:
        producer = FakeKafkaSendProducer()
        publisher = KafkaRuntimeEventPublisher(producer)

        self.assertEqual(0, publisher.publish(()))
        self.assertEqual([], producer.sent)


def _event(run_id: str | None = "run-001", session_id: str | None = "session-001") -> AgentRuntimeEvent:
    """构造测试事件。

    事件字段尽量覆盖租户、项目、操作者、run/session/request、sequence 与 attributes，
    这样可以验证 Kafka payload 是否能满足后续 Java 控制面、审计和观测消费所需的核心上下文。
    """

    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.TOOL_PLANNED,
        stage="plan_tools",
        message="已生成工具调用计划",
        severity=AgentRuntimeEventSeverity.AUDIT,
        tenant_id="tenant-a",
        project_id="project-a",
        actor_id="actor-a",
        request_id="request-a",
        run_id=run_id,
        session_id=session_id,
        sequence=7,
        attributes={"tokens": 128, "modes": ("sync", "approval")},
    )


if __name__ == "__main__":
    unittest.main()
