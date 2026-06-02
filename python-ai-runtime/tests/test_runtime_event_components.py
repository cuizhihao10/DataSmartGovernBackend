import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_events.runtime_event_checkpoint_store import (
    InMemoryRuntimeEventCheckpointStore,
    RedisRuntimeEventCheckpointStore,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_components import (
    RuntimeEventComponentSettings,
    build_runtime_event_components,
    runtime_event_component_diagnostics,
    runtime_event_settings_from_env,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_outbox_store import (
    InMemoryRuntimeEventOutboxStore,
    RedisRuntimeEventOutboxStore,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_publisher import (
    KafkaRuntimeEventPublisher,
    NoopRuntimeEventPublisher,
)
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import InMemoryRuntimeEventStore, RedisStreamRuntimeEventStore


class FakeRedisClient:
    """同时满足 checkpoint 与 outbox 测试所需的最小 Redis fake。"""

    def __init__(self) -> None:
        self.values: dict[str, str] = {}
        self.lists: dict[str, list[str]] = {}
        self.streams: dict[str, list[tuple[str, dict[str, str]]]] = {}
        self.ttl: dict[str, int] = {}

    def set(self, key: str, value: str, ex: int | None = None) -> None:
        self.values[key] = value
        if ex is not None:
            self.ttl[key] = ex

    def get(self, key: str):
        return self.values.get(key)

    def rpush(self, key: str, *values: str) -> None:
        self.lists.setdefault(key, []).extend(values)

    def lrange(self, key: str, start: int, end: int) -> list[str]:
        items = self.lists.get(key, [])
        if end == -1:
            return items[start:]
        return items[start : end + 1]

    def expire(self, key: str, seconds: int) -> None:
        self.ttl[key] = seconds

    def xadd(
        self,
        name: str,
        fields: dict[str, str],
        id: str = "*",
        maxlen: int | None = None,
        approximate: bool = True,
    ) -> str:
        stream_id = f"1700000000000-{len(self.streams.get(name, ())) }"
        self.streams.setdefault(name, []).append((stream_id, fields))
        return stream_id

    def xrange(self, name: str, min: str = "-", max: str = "+", count: int | None = None):
        items = tuple(self.streams.get(name, ()))
        if count is not None:
            items = items[:count]
        return items

    def delete(self, key: str) -> None:
        self.values.pop(key, None)
        self.lists.pop(key, None)
        self.ttl.pop(key, None)


class FakeKafkaProducer:
    """模拟 Kafka producer，用于验证组件组装层是否正确注入发布器。"""

    def __init__(self) -> None:
        self.sent: list[dict[str, str]] = []
        self.flush_count = 0

    def send(self, topic: str, key: str, value: str) -> None:
        self.sent.append({"topic": topic, "key": key, "value": value})

    def flush(self) -> None:
        self.flush_count += 1


class RuntimeEventComponentsTest(unittest.TestCase):
    def test_default_components_use_in_memory_stores(self) -> None:
        components = build_runtime_event_components(RuntimeEventComponentSettings())

        self.assertIsInstance(components.checkpoint_store, InMemoryRuntimeEventCheckpointStore)
        self.assertIsInstance(components.outbox_store, InMemoryRuntimeEventOutboxStore)
        self.assertIsInstance(components.event_store, InMemoryRuntimeEventStore)
        self.assertIsInstance(components.event_publisher, NoopRuntimeEventPublisher)
        self.assertIs(components.live_push_hub._session_manager, components.session_manager)
        self.assertEqual(0, components.visibility_stats.snapshot()["policyEvaluationCount"])

    def test_redis_components_share_single_client_and_keep_runtime_flow_working(self) -> None:
        created_clients: list[FakeRedisClient] = []

        def factory(redis_url: str) -> FakeRedisClient:
            self.assertEqual("redis://redis.example:6379/4", redis_url)
            client = FakeRedisClient()
            created_clients.append(client)
            return client

        components = build_runtime_event_components(
            RuntimeEventComponentSettings(
                checkpoint_store="redis",
                outbox_store="redis",
                redis_url="redis://redis.example:6379/4",
                checkpoint_ttl_seconds=1800,
                outbox_ttl_seconds=90,
            ),
            redis_client_factory=factory,
        )
        snapshot = components.session_manager.subscribe(
            RuntimeEventSubscriptionRequest(client_id="client-a", session_id="session-a")
        )
        components.session_manager.acknowledge(snapshot.plan.subscription_id, 7)

        self.assertEqual(1, len(created_clients))
        self.assertIsInstance(components.checkpoint_store, RedisRuntimeEventCheckpointStore)
        self.assertIsInstance(components.outbox_store, RedisRuntimeEventOutboxStore)
        self.assertIn(1800, created_clients[0].ttl.values())

    def test_redis_stream_event_store_can_share_runtime_redis_client(self) -> None:
        created_clients: list[FakeRedisClient] = []

        def factory(redis_url: str) -> FakeRedisClient:
            client = FakeRedisClient()
            created_clients.append(client)
            return client

        components = build_runtime_event_components(
            RuntimeEventComponentSettings(
                event_store="redis-stream",
                checkpoint_store="redis",
                outbox_store="redis",
                redis_url="redis://redis.example:6379/5",
                event_stream_key="datasmart:test:runtime-events",
                event_stream_max_length=500,
                event_replay_max_entries=50,
            ),
            redis_client_factory=factory,
        )

        self.assertEqual(1, len(created_clients))
        self.assertIsInstance(components.event_store, RedisStreamRuntimeEventStore)
        self.assertIsInstance(components.checkpoint_store, RedisRuntimeEventCheckpointStore)
        self.assertIsInstance(components.outbox_store, RedisRuntimeEventOutboxStore)

    def test_settings_from_env_parses_store_types_and_ttls(self) -> None:
        settings = runtime_event_settings_from_env(
            {
                "DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_STORE": "redis",
                "DATASMART_AI_RUNTIME_EVENT_STORE": "redis-stream",
                "DATASMART_AI_RUNTIME_EVENT_OUTBOX_STORE": "REDIS",
                "DATASMART_AI_RUNTIME_REDIS_URL": "redis://localhost:6380/2",
                "DATASMART_AI_RUNTIME_EVENT_STREAM_KEY": "datasmart:test:events",
                "DATASMART_AI_RUNTIME_EVENT_STREAM_MAX_LENGTH": "5000",
                "DATASMART_AI_RUNTIME_EVENT_REPLAY_MAX_ENTRIES": "250",
                "DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_TTL_SECONDS": "7200",
                "DATASMART_AI_RUNTIME_EVENT_OUTBOX_TTL_SECONDS": "600",
                "DATASMART_AI_RUNTIME_EVENT_HEARTBEAT_TIMEOUT_SECONDS": "30",
            }
        )

        self.assertEqual("redis-stream", settings.event_store)
        self.assertEqual("redis", settings.checkpoint_store)
        self.assertEqual("redis", settings.outbox_store)
        self.assertEqual("redis://localhost:6380/2", settings.redis_url)
        self.assertEqual("datasmart:test:events", settings.event_stream_key)
        self.assertEqual(5000, settings.event_stream_max_length)
        self.assertEqual(250, settings.event_replay_max_entries)
        self.assertEqual(7200, settings.checkpoint_ttl_seconds)
        self.assertEqual(600, settings.outbox_ttl_seconds)
        self.assertEqual(30, settings.heartbeat_timeout_seconds)

    def test_settings_from_env_parses_kafka_event_publisher(self) -> None:
        settings = runtime_event_settings_from_env(
            {
                "DATASMART_AI_RUNTIME_EVENT_PUBLISHER": "kafka",
                "DATASMART_AI_RUNTIME_KAFKA_BOOTSTRAP_SERVERS": "kafka-a:9092,kafka-b:9092",
                "DATASMART_AI_RUNTIME_EVENT_TOPIC": "datasmart.test.agent-runtime.events",
                "DATASMART_AI_RUNTIME_KAFKA_CLIENT_ID": "python-runtime-test",
                "DATASMART_AI_RUNTIME_KAFKA_FLUSH_ON_PUBLISH": "true",
            }
        )

        self.assertEqual("kafka", settings.event_publisher)
        self.assertEqual("kafka-a:9092,kafka-b:9092", settings.kafka_bootstrap_servers)
        self.assertEqual("datasmart.test.agent-runtime.events", settings.kafka_event_topic)
        self.assertEqual("python-runtime-test", settings.kafka_client_id)
        self.assertTrue(settings.kafka_flush_on_publish)

    def test_kafka_event_publisher_can_be_injected_without_real_broker(self) -> None:
        created: list[tuple[str, str]] = []

        def factory(bootstrap_servers: str, client_id: str) -> FakeKafkaProducer:
            created.append((bootstrap_servers, client_id))
            return FakeKafkaProducer()

        components = build_runtime_event_components(
            RuntimeEventComponentSettings(
                event_publisher="kafka",
                kafka_bootstrap_servers="kafka-a:9092",
                kafka_event_topic="datasmart.test.agent-runtime.events",
                kafka_client_id="python-runtime-test",
                kafka_flush_on_publish=True,
            ),
            kafka_producer_factory=factory,
        )

        self.assertEqual([("kafka-a:9092", "python-runtime-test")], created)
        self.assertIsInstance(components.event_publisher, KafkaRuntimeEventPublisher)

    def test_invalid_store_type_fails_fast(self) -> None:
        with self.assertRaises(ValueError):
            runtime_event_settings_from_env(
                {
                    "DATASMART_AI_RUNTIME_EVENT_CHECKPOINT_STORE": "mysql",
                }
            )

    def test_diagnostics_exposes_component_types_without_leaking_redis_secret(self) -> None:
        components = build_runtime_event_components(
            RuntimeEventComponentSettings(
                event_store="redis-stream",
                checkpoint_store="redis",
                outbox_store="redis",
                redis_url="redis://user:super-secret@redis.example:6379/6",
                event_stream_key="datasmart:test:events",
                event_stream_max_length=1234,
                event_replay_max_entries=88,
                checkpoint_ttl_seconds=180,
                outbox_ttl_seconds=60,
                heartbeat_timeout_seconds=15,
            ),
            redis_client_factory=lambda redis_url: FakeRedisClient(),
        )

        diagnostics = runtime_event_component_diagnostics(components)

        self.assertEqual("redis-stream", diagnostics["eventStore"]["configuredType"])
        self.assertEqual("RedisStreamRuntimeEventStore", diagnostics["eventStore"]["implementation"])
        self.assertEqual("datasmart:test:events", diagnostics["eventStore"]["streamKey"])
        self.assertEqual(1234, diagnostics["eventStore"]["streamMaxLength"])
        self.assertEqual(88, diagnostics["eventStore"]["replayMaxEntries"])
        self.assertEqual(180, diagnostics["checkpointStore"]["ttlSeconds"])
        self.assertEqual(60, diagnostics["outboxStore"]["ttlSeconds"])
        self.assertEqual("none", diagnostics["eventPublisher"]["configuredType"])
        self.assertEqual("NoopRuntimeEventPublisher", diagnostics["eventPublisher"]["implementation"])
        self.assertEqual("RuntimeEventVisibilityPolicy", diagnostics["visibilityPolicy"]["implementation"])
        self.assertEqual(0, diagnostics["visibilityPolicy"]["stats"]["policyEvaluationCount"])
        self.assertEqual(15, diagnostics["sessionManager"]["heartbeatTimeoutSeconds"])
        self.assertTrue(diagnostics["redis"]["enabled"])
        self.assertEqual("redis://***:***@redis.example:6379/6", diagnostics["redis"]["url"])
        self.assertNotIn("super-secret", str(diagnostics))

    def test_diagnostics_exposes_kafka_publisher_configuration(self) -> None:
        components = build_runtime_event_components(
            RuntimeEventComponentSettings(
                event_publisher="kafka",
                kafka_bootstrap_servers="kafka-a:9092",
                kafka_event_topic="datasmart.test.agent-runtime.events",
                kafka_client_id="python-runtime-test",
                kafka_flush_on_publish=True,
            ),
            kafka_producer_factory=lambda bootstrap_servers, client_id: FakeKafkaProducer(),
        )

        diagnostics = runtime_event_component_diagnostics(components)

        self.assertEqual("kafka", diagnostics["eventPublisher"]["configuredType"])
        self.assertEqual("KafkaRuntimeEventPublisher", diagnostics["eventPublisher"]["implementation"])
        self.assertEqual("datasmart.test.agent-runtime.events", diagnostics["eventPublisher"]["topic"])
        self.assertEqual("kafka-a:9092", diagnostics["eventPublisher"]["bootstrapServers"])
        self.assertEqual("python-runtime-test", diagnostics["eventPublisher"]["clientId"])
        self.assertTrue(diagnostics["eventPublisher"]["flushOnPublish"])

    def test_visibility_stats_are_shared_by_live_push_components(self) -> None:
        """组件组装层应让 live push 与 diagnostics 共享同一个可见性统计器。"""

        components = build_runtime_event_components(RuntimeEventComponentSettings())
        components.session_manager.subscribe(
            RuntimeEventSubscriptionRequest(
                client_id="browser-a",
                roles=("PROJECT_OWNER",),
                session_id="session-metrics",
            )
        )
        recorder = RuntimeEventRecorder(
            request=AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="测试可见性指标",
            ),
            request_id="request-metrics",
            run_id="run-metrics",
            session_id="session-metrics",
        )
        recorder.record(
            AgentRuntimeEventType.TOOL_PLANNED,
            "plan_tools",
            "已规划工具。",
            attributes={"sql": "select * from sensitive_table"},
        )

        components.live_push_hub.publish(recorder.events())
        diagnostics = runtime_event_component_diagnostics(components)
        stats = diagnostics["visibilityPolicy"]["stats"]

        self.assertEqual(1, stats["policyEvaluationCount"])
        self.assertEqual(1, stats["evaluatedEventCount"])
        self.assertEqual(1, stats["visibleEventCount"])
        self.assertEqual(1, stats["maskedEventCount"])
        self.assertEqual(1, stats["maskedFieldCount"])
        self.assertEqual(1, stats["levelHitCounts"]["PROJECT"])


if __name__ == "__main__":
    unittest.main()
