import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api_gateway_security import (
    GatewaySignatureSecurityStats,
    RedisGatewaySignatureNonceStore,
    build_gateway_signature_nonce_store,
    gateway_signature_nonce_store_settings_from_env,
    gateway_signature_security_diagnostics,
)


class FakeRedisNonceClient:
    """测试用 Redis client，只实现 nonce store 需要的 SET NX EX 语义。"""

    def __init__(self) -> None:
        self.storage: dict[str, str] = {}
        self.ttl: dict[str, int] = {}

    def set(self, key: str, value: str, *, ex: int, nx: bool) -> bool:
        """模拟 redis-py 的 set(..., ex=ttl, nx=True)。"""

        if nx and key in self.storage:
            return False
        self.storage[key] = value
        self.ttl[key] = ex
        return True


class ApiGatewaySecurityTest(unittest.TestCase):
    """gateway 签名安全组件测试。"""

    def test_redis_nonce_store_uses_set_nx_ex_to_reject_replay(self) -> None:
        """Redis nonce store 应依赖原子 SET NX EX 拒绝重复 nonce。"""

        redis_client = FakeRedisNonceClient()
        store = RedisGatewaySignatureNonceStore(redis_client, key_prefix="datasmart:test:nonce")

        first = store.try_mark_seen(key_id="gateway-local-v1", nonce="nonce-001", timestamp_ms=1000, ttl_seconds=300)
        replay = store.try_mark_seen(key_id="gateway-local-v1", nonce="nonce-001", timestamp_ms=1001, ttl_seconds=300)

        self.assertTrue(first)
        self.assertFalse(replay)
        self.assertEqual({"datasmart:test:nonce:gateway-local-v1:nonce-001": "1000"}, redis_client.storage)
        self.assertEqual({"datasmart:test:nonce:gateway-local-v1:nonce-001": 300}, redis_client.ttl)

    def test_nonce_store_settings_from_env_can_build_redis_store_with_injected_factory(self) -> None:
        """环境变量应能把 nonce store 切到 Redis，同时测试不依赖真实 redis-py。"""

        created_urls: list[str] = []

        def factory(redis_url: str) -> FakeRedisNonceClient:
            created_urls.append(redis_url)
            return FakeRedisNonceClient()

        settings = gateway_signature_nonce_store_settings_from_env(
            {
                "DATASMART_GATEWAY_SIGNATURE_NONCE_STORE": "redis",
                "DATASMART_GATEWAY_SIGNATURE_NONCE_REDIS_URL": "redis://redis.example:6379/9",
                "DATASMART_GATEWAY_SIGNATURE_NONCE_TTL_SECONDS": "600",
                "DATASMART_GATEWAY_SIGNATURE_NONCE_KEY_PREFIX": "datasmart:prod:gateway-nonce",
            }
        )
        store = build_gateway_signature_nonce_store(settings, redis_client_factory=factory)

        self.assertEqual("redis", settings.store_type)
        self.assertEqual(600, settings.ttl_seconds)
        self.assertEqual(["redis://redis.example:6379/9"], created_urls)
        self.assertTrue(store.diagnostics()["clusterSafe"])

    def test_security_stats_aggregates_failure_reason_without_sensitive_material(self) -> None:
        """安全统计只记录失败类别和排障字段，不保存签名材料。"""

        stats = GatewaySignatureSecurityStats(max_recent_failures=2)
        stats.record_failure(
            {
                "code": "GATEWAY_SIGNATURE_INVALID",
                "reason": "signature-mismatch",
                "traceId": "trace-001",
                "sourceService": "datasmart-govern-gateway",
                "path": "/agent/plans",
                "signature": "should-not-be-copied",
            }
        )
        stats.record_failure(
            {
                "code": "GATEWAY_SIGNATURE_INVALID",
                "reason": "signature-mismatch",
                "traceId": "trace-002",
                "sourceService": "datasmart-govern-gateway",
                "path": "/agent/plans",
            }
        )

        snapshot = stats.snapshot()

        self.assertEqual(2, snapshot["totalFailureCount"])
        self.assertEqual({"signature-mismatch": 2}, snapshot["failureCountByReason"])
        self.assertNotIn("signature", snapshot["recentFailures"][0])
        self.assertNotIn("should-not-be-copied", str(snapshot))

    def test_gateway_signature_security_diagnostics_combines_nonce_and_stats(self) -> None:
        """诊断响应应同时展示 nonce store 与签名失败统计。"""

        settings = gateway_signature_nonce_store_settings_from_env({"DATASMART_GATEWAY_SIGNATURE_NONCE_STORE": "in-memory"})
        store = build_gateway_signature_nonce_store(settings)
        stats = GatewaySignatureSecurityStats()
        stats.record_failure({"code": "GATEWAY_SIGNATURE_INVALID", "reason": "nonce-replayed"})

        diagnostics = gateway_signature_security_diagnostics(stats, store, settings)

        self.assertEqual("gateway-signature-security", diagnostics["component"])
        self.assertEqual("in-memory", diagnostics["nonceStore"]["configuredType"])
        self.assertEqual({"nonce-replayed": 1}, diagnostics["signatureFailures"]["failureCountByReason"])


if __name__ == "__main__":
    unittest.main()
