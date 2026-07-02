import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_execution import (
    DurableAgentLoopService,
    DurableAgentLoopStoreSettings,
    InMemoryDurableAgentLoopStore,
    RedisDurableAgentLoopStore,
    build_durable_agent_loop_store,
    durable_agent_loop_store_diagnostics,
    durable_agent_loop_store_settings_from_env,
)


class FakeRedisClient:
    """Durable Loop store 测试使用的最小 Redis fake。"""

    def __init__(self) -> None:
        self.storage: dict[str, str] = {}
        self.ttl: dict[str, int] = {}

    def set(self, key: str, value: str, ex: int | None = None) -> None:
        self.storage[key] = value
        if ex is not None:
            self.ttl[key] = ex

    def get(self, key: str):
        return self.storage.get(key)


class DurableAgentLoopRedisStoreTest(unittest.TestCase):
    """Redis Durable Agent Loop 持久化与启动装配测试。"""

    def test_checkpoint_can_be_restored_across_service_instances(self) -> None:
        """共享 Redis client 的新服务实例应恢复同一个 run 的最新现场。"""

        client = FakeRedisClient()
        writer = DurableAgentLoopService(RedisDurableAgentLoopStore(client, ttl_seconds=900))
        checkpoint = writer.record(request=_request(), plan=_plan())

        reader = DurableAgentLoopService(RedisDurableAgentLoopStore(client, ttl_seconds=900))
        restored = reader.get(checkpoint.run_id or checkpoint.request_id)
        serialized = str(client.storage)

        self.assertEqual("run-redis-loop", restored["runId"])
        self.assertEqual("session-redis-loop", restored["sessionId"])
        self.assertEqual("waiting_control_plane", restored["phase"])
        self.assertEqual("wait_event_replay", restored["resumeAction"])
        self.assertTrue(any(value == 900 for value in client.ttl.values()))
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("select * from hidden_customer", serialized)
        self.assertNotIn("secret-datasource", serialized)
        self.assertNotIn("arguments", serialized.lower())

    def test_store_settings_default_to_in_memory(self) -> None:
        """默认设置必须保持离线环境零 Redis 依赖。"""

        settings = durable_agent_loop_store_settings_from_env({})
        store = build_durable_agent_loop_store(settings)

        self.assertEqual("in-memory", settings.store_type)
        self.assertIsInstance(store, InMemoryDurableAgentLoopStore)

    def test_redis_settings_use_factory_and_mask_password(self) -> None:
        """生产 Redis 配置必须通过 factory 装配，诊断不得泄露凭据。"""

        factory_calls: list[str] = []
        client = FakeRedisClient()

        def factory(redis_url: str) -> FakeRedisClient:
            factory_calls.append(redis_url)
            return client

        settings = durable_agent_loop_store_settings_from_env(
            {
                "DATASMART_AGENT_DURABLE_LOOP_STORE": "redis",
                "DATASMART_AGENT_DURABLE_LOOP_REDIS_URL": "redis://agent:secret@redis.local:6379/6",
                "DATASMART_AGENT_DURABLE_LOOP_REDIS_KEY_PREFIX": "datasmart:test:durable-loop",
                "DATASMART_AGENT_DURABLE_LOOP_TTL_SECONDS": "1200",
            }
        )
        store = build_durable_agent_loop_store(settings, redis_client_factory=factory)
        diagnostics = durable_agent_loop_store_diagnostics(store, settings)

        self.assertEqual(["redis://agent:secret@redis.local:6379/6"], factory_calls)
        self.assertIsInstance(store, RedisDurableAgentLoopStore)
        self.assertEqual("redis", diagnostics["configuredType"])
        self.assertEqual("redis://***:***@redis.local:6379/6", diagnostics["redis"]["url"])
        self.assertEqual(1200, diagnostics["redis"]["ttlSeconds"])
        self.assertNotIn("secret", str(diagnostics))
        self.assertFalse(diagnostics["sensitiveDataPolicy"]["toolArgumentsStored"])

    def test_invalid_store_type_fails_fast(self) -> None:
        """未知仓储类型应在启动阶段阻断，不能静默退回内存。"""

        with self.assertRaises(ValueError):
            durable_agent_loop_store_settings_from_env(
                {"DATASMART_AGENT_DURABLE_LOOP_STORE": "filesystem"}
            )


def _request() -> AgentRequest:
    return AgentRequest(
        tenant_id="tenant-redis-loop",
        project_id="project-redis-loop",
        actor_id="actor-redis-loop",
        objective="secret objective",
        variables={
            "sessionId": "session-redis-loop",
            "datasourceId": "secret-datasource",
            "sql": "select * from hidden_customer",
        },
    )


def _plan() -> AgentPlan:
    return AgentPlan(
        request_id="request-redis-loop",
        selected_route=None,
        state_trace=("receive_goal", "plan_tools"),
        tool_plans=(
            ToolPlan(
                tool_name="datasource.metadata.read",
                reason="读取元数据。",
                arguments={"datasourceId": "secret-datasource"},
                governance_hints={"modelToolCallId": "call-redis-loop"},
            ),
        ),
        requires_human_approval=False,
        response_summary="已生成计划。",
        runtime_events=(
            AgentRuntimeEvent(
                event_type=AgentRuntimeEventType.TOOL_PLANNED,
                stage="plan_tools",
                message="工具已规划。",
                request_id="request-redis-loop",
                run_id="run-redis-loop",
                session_id="session-redis-loop",
            ),
        ),
    )


if __name__ == "__main__":
    unittest.main()
