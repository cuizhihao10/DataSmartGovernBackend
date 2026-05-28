import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.runtime_event_outbox_store import (
    InMemoryRuntimeEventOutboxStore,
    RedisRuntimeEventOutboxStore,
)
from datasmart_ai_runtime.services.runtime_event_websocket import (
    RuntimeEventWebSocketFrame,
    RuntimeEventWebSocketFrameType,
)


class FakeRedisListClient:
    """测试用 Redis list fake。

    真实 `RedisRuntimeEventOutboxStore` 只依赖 `rpush/lrange/delete/expire`，因此 fake 也只实现这些方法。
    这样测试聚焦在 outbox 语义，而不是把 redis-py 作为测试依赖拉进项目。
    """

    def __init__(self) -> None:
        self.storage: dict[str, list[str]] = {}
        self.ttl: dict[str, int] = {}

    def rpush(self, key: str, *values: str) -> None:
        self.storage.setdefault(key, []).extend(values)

    def lrange(self, key: str, start: int, end: int) -> list[str]:
        items = self.storage.get(key, [])
        if end == -1:
            return items[start:]
        return items[start : end + 1]

    def delete(self, key: str) -> None:
        self.storage.pop(key, None)
        self.ttl.pop(key, None)

    def expire(self, key: str, seconds: int) -> None:
        self.ttl[key] = seconds


class RuntimeEventOutboxStoreTest(unittest.TestCase):
    def test_in_memory_outbox_preserves_order_and_drains_once(self) -> None:
        store = InMemoryRuntimeEventOutboxStore()
        first = self._frame("first")
        second = self._frame("second")

        store.enqueue("sub-a", (first,))
        store.enqueue("sub-a", (second,))
        drained = store.drain("sub-a")
        drained_again = store.drain("sub-a")

        self.assertEqual(("first", "second"), tuple(frame.payload["message"] for frame in drained))
        self.assertEqual((), drained_again)

    def test_redis_outbox_preserves_order_sets_ttl_and_drains_once(self) -> None:
        redis_client = FakeRedisListClient()
        store = RedisRuntimeEventOutboxStore(redis_client, ttl_seconds=120)

        store.enqueue("sub-redis", (self._frame("first"), self._frame("second")))
        drained = store.drain("sub-redis")
        drained_again = store.drain("sub-redis")

        self.assertEqual(("first", "second"), tuple(frame.payload["message"] for frame in drained))
        self.assertEqual((), drained_again)
        self.assertEqual({}, redis_client.storage)
        self.assertEqual({}, redis_client.ttl)

    def test_redis_outbox_serializes_nested_runtime_payload(self) -> None:
        redis_client = FakeRedisListClient()
        store = RedisRuntimeEventOutboxStore(redis_client, ttl_seconds=60)
        frame = RuntimeEventWebSocketFrame(
            frame_type=RuntimeEventWebSocketFrameType.EVENT_ENVELOPE,
            payload={
                "events": (
                    {
                        "sequence": 1,
                        "eventType": RuntimeEventWebSocketFrameType.CONTROL_RESPONSE,
                    },
                )
            },
        )

        store.enqueue("sub-json", (frame,))
        drained = store.drain("sub-json")

        self.assertEqual("control_response", drained[0].payload["events"][0]["eventType"])

    @staticmethod
    def _frame(message: str) -> RuntimeEventWebSocketFrame:
        return RuntimeEventWebSocketFrame(
            frame_type=RuntimeEventWebSocketFrameType.EVENT_ENVELOPE,
            payload={"message": message},
        )


if __name__ == "__main__":
    unittest.main()
