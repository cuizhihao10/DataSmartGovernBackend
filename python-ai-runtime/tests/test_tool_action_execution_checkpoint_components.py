import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    InMemoryToolActionExecutionCheckpointStore,
    RedisToolActionExecutionCheckpointStore,
    build_tool_action_execution_checkpoint_store,
    tool_action_execution_checkpoint_store_diagnostics,
    tool_action_execution_checkpoint_store_settings_from_env,
)


class FakeRedisClient:
    """组件装配测试使用的 Redis fake。

    该 fake 只需要证明 builder 会调用传入的 factory，并把得到的 client 注入 Redis store；具体 Redis 行为
    已在 `test_tool_action_execution_checkpoint_redis_store.py` 中覆盖。
    """

    pass


class ToolActionExecutionCheckpointComponentsTest(unittest.TestCase):
    """工具动作 checkpoint store 组件装配测试。

    这组测试保护启动期配置契约：
    - 默认配置必须保持本地零依赖；
    - 显式配置 Redis 时必须走 factory，方便生产接 redis-py、Redis Cluster 或内部缓存 SDK；
    - 诊断接口必须脱敏 Redis URL，不能把密码暴露给运维页面或日志。
    """

    def test_default_settings_build_in_memory_store(self) -> None:
        """默认环境变量应创建 in-memory store。"""

        settings = tool_action_execution_checkpoint_store_settings_from_env({})
        store = build_tool_action_execution_checkpoint_store(settings)
        diagnostics = tool_action_execution_checkpoint_store_diagnostics(store, settings)

        self.assertEqual("in-memory", settings.store_type)
        self.assertIsInstance(store, InMemoryToolActionExecutionCheckpointStore)
        self.assertEqual("InMemoryToolActionExecutionCheckpointStore", diagnostics["implementation"])
        self.assertFalse(diagnostics["redis"]["enabled"])
        self.assertFalse(diagnostics["sensitiveDataPolicy"]["rawPromptStored"])

    def test_redis_settings_use_injected_factory_and_mask_diagnostics(self) -> None:
        """Redis 配置应通过注入 factory 创建 store，并在诊断中隐藏密码。"""

        factory_calls: list[str] = []

        def factory(redis_url: str) -> FakeRedisClient:
            factory_calls.append(redis_url)
            return FakeRedisClient()

        settings = tool_action_execution_checkpoint_store_settings_from_env(
            {
                "DATASMART_TOOL_ACTION_CHECKPOINT_STORE": "redis",
                "DATASMART_TOOL_ACTION_CHECKPOINT_REDIS_URL": "redis://user:secret@redis.local:6379/4",
                "DATASMART_TOOL_ACTION_CHECKPOINT_REDIS_KEY_PREFIX": "datasmart:test:tool-checkpoint",
                "DATASMART_TOOL_ACTION_CHECKPOINT_TTL_SECONDS": "900",
                "DATASMART_TOOL_ACTION_CHECKPOINT_MAX_PER_THREAD": "7",
                "DATASMART_TOOL_ACTION_CHECKPOINT_MAX_TOTAL": "77",
            }
        )
        store = build_tool_action_execution_checkpoint_store(settings, redis_client_factory=factory)
        diagnostics = tool_action_execution_checkpoint_store_diagnostics(store, settings)

        self.assertEqual(["redis://user:secret@redis.local:6379/4"], factory_calls)
        self.assertIsInstance(store, RedisToolActionExecutionCheckpointStore)
        self.assertEqual("redis", diagnostics["configuredType"])
        self.assertEqual("***:***@redis.local:6379", diagnostics["redis"]["url"].split("//", 1)[1].split("/", 1)[0])
        self.assertNotIn("secret", str(diagnostics))
        self.assertEqual("datasmart:test:tool-checkpoint", diagnostics["redis"]["keyPrefix"])
        self.assertEqual(900, diagnostics["redis"]["ttlSeconds"])
        self.assertEqual(7, diagnostics["maxCheckpointsPerThread"])

    def test_invalid_store_type_fails_fast(self) -> None:
        """非法 store 类型应在启动配置阶段快速失败。"""

        with self.assertRaises(ValueError):
            tool_action_execution_checkpoint_store_settings_from_env(
                {"DATASMART_TOOL_ACTION_CHECKPOINT_STORE": "filesystem"}
            )


if __name__ == "__main__":
    unittest.main()
