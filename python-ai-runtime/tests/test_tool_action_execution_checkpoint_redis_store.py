import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import RedisToolActionExecutionCheckpointStore


class FakeRedisClient:
    """用于单元测试的最小 Redis 兼容客户端。

    真实 `RedisToolActionExecutionCheckpointStore` 只依赖 `set/get/delete/incr/rpush/ltrim/lrange/expire`
    这几个基础命令。测试中不用真实 Redis，是为了让 CI、本地学习环境和离线调试都能验证持久化契约，
    而不需要额外启动中间件。
    """

    def __init__(self) -> None:
        self.storage: dict[str, str] = {}
        self.lists: dict[str, list[str]] = {}
        self.counters: dict[str, int] = {}
        self.ttl: dict[str, int] = {}

    def set(self, key: str, value: str, ex: int | None = None) -> None:
        self.storage[key] = value
        if ex is not None:
            self.ttl[key] = ex

    def get(self, key: str):
        return self.storage.get(key)

    def delete(self, key: str) -> None:
        self.storage.pop(key, None)
        self.lists.pop(key, None)
        self.counters.pop(key, None)
        self.ttl.pop(key, None)

    def incr(self, key: str) -> int:
        self.counters[key] = self.counters.get(key, 0) + 1
        return self.counters[key]

    def rpush(self, key: str, value: str) -> None:
        self.lists.setdefault(key, []).append(value)

    def ltrim(self, key: str, start: int, end: int) -> None:
        values = self.lists.get(key, [])
        self.lists[key] = values[_redis_index(values, start) : _redis_index(values, end, inclusive_end=True)]

    def lrange(self, key: str, start: int, end: int):
        values = self.lists.get(key, [])
        return values[_redis_index(values, start) : _redis_index(values, end, inclusive_end=True)]

    def expire(self, key: str, ttl_seconds: int) -> None:
        self.ttl[key] = ttl_seconds


class RedisToolActionExecutionCheckpointStoreTest(unittest.TestCase):
    """Redis 工具动作 checkpoint store 测试。

    这组测试保护的是“短期可恢复状态”能力：
    - 不同 store 实例只要共享同一个 Redis client，就应能读到同一批 checkpoint；
    - 同 thread sequence 必须递增，方便恢复和审计按时间线定位；
    - per-thread 上限必须生效，避免异常循环无限堆积；
    - payload 必须保持低敏，不允许敏感正文因为 Redis 持久化而扩散。
    """

    def test_redis_store_can_restore_checkpoint_across_instances(self) -> None:
        """Redis store 应支持跨实例读取同一 checkpoint。"""

        client = FakeRedisClient()
        first_store = RedisToolActionExecutionCheckpointStore(
            client,
            ttl_seconds=600,
            max_checkpoints_per_thread=5,
        )
        checkpoint = first_store.save(
            _graph_run_with_sensitive_fields(),
            context={
                "source": "MCP_TOOLS_CALL",
                "protocolFamily": "MCP",
                "tenantId": "tenant-redis",
                "projectId": "project-redis",
                "actorId": "actor-redis",
                "runId": "run-redis",
            },
        )

        restored_store = RedisToolActionExecutionCheckpointStore(
            client,
            ttl_seconds=600,
            max_checkpoints_per_thread=5,
        )
        restored = restored_store.get(checkpoint.checkpoint_id)
        thread_items = restored_store.list_by_thread("run-redis")
        serialized = str(restored.to_summary(include_graph_run=True)) if restored else ""

        self.assertIsNotNone(restored)
        self.assertEqual("run-redis", restored.thread_id)
        self.assertEqual(1, restored.sequence)
        self.assertEqual((checkpoint.checkpoint_id,), tuple(item.checkpoint_id for item in thread_items))
        self.assertTrue(any(key.endswith(":thread:run-redis") for key in client.ttl))
        self.assertTrue(any(key.endswith(":sequence:run-redis") for key in client.ttl))
        self.assertNotIn("ds-redis-secret", serialized)
        self.assertNotIn("select * from redis_hidden_table", serialized)
        self.assertNotIn("raw prompt should not be stored", serialized)
        self.assertNotIn("http://internal.redis.local", serialized)

    def test_redis_store_trims_old_thread_checkpoints(self) -> None:
        """单 thread 超出保留上限时，应只保留最近 checkpoint。"""

        client = FakeRedisClient()
        store = RedisToolActionExecutionCheckpointStore(
            client,
            ttl_seconds=300,
            max_checkpoints_per_thread=2,
        )
        first = store.save(_minimal_graph_run("WAITING_APPROVAL_FACT"), context={"runId": "thread-trim"})
        second = store.save(_minimal_graph_run("WAITING_CLARIFICATION_FACT"), context={"runId": "thread-trim"})
        third = store.save(_minimal_graph_run("WAITING_OUTBOX_CONFIRMATION"), context={"runId": "thread-trim"})

        thread_items = store.list_by_thread("thread-trim")

        self.assertIsNone(store.get(first.checkpoint_id))
        self.assertEqual((second.checkpoint_id, third.checkpoint_id), tuple(item.checkpoint_id for item in thread_items))
        self.assertEqual((2, 3), tuple(item.sequence for item in thread_items))


def _redis_index(values: list[str], index: int, *, inclusive_end: bool = False) -> int:
    """按 Redis list 负数下标语义转换 Python slice 下标。"""

    length = len(values)
    normalized = length + index if index < 0 else index
    if inclusive_end:
        normalized += 1
    return max(0, normalized)


def _minimal_graph_run(status: str) -> dict[str, object]:
    """生成最小低敏执行图摘要。"""

    return {
        "schemaVersion": "datasmart.python-ai-runtime.tool-action-execution-graph-runner.v1",
        "previewOnly": True,
        "executionBoundary": "PRE_EXECUTION_GRAPH_RUNNER_ONLY",
        "stepCount": 1,
        "truncatedCount": 0,
        "statusCounts": {status: 1},
        "steps": [
            {
                "nodeType": "TOOL_ACTION_COMMAND_PROPOSAL",
                "toolName": "datasource.metadata.read",
                "stepStatus": status,
                "proposalSubmission": {},
            }
        ],
        "sideEffectBoundary": {
            "toolExecuted": False,
            "outboxWritten": False,
            "workerDispatched": False,
            "approvalCreated": False,
            "checkpointPersisted": False,
        },
        "resumeRequirements": ["APPROVAL_CONFIRMATION_FACT"],
    }


def _graph_run_with_sensitive_fields() -> dict[str, object]:
    """生成包含敏感字段的执行图摘要，验证 Redis store 保存前会二次裁剪。"""

    graph = _minimal_graph_run("WAITING_COMMAND_PROPOSAL_EVIDENCE")
    graph["resumeRequirements"] = ["GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE"]
    graph["prompt"] = "raw prompt should not be stored"
    graph["sql"] = "select * from redis_hidden_table"
    graph["modelOutput"] = "model output body"
    graph["steps"][0].update(
        {
            "templateId": "template-redis-001",
            "decision": "ready",
            "payloadPolicy": "REFERENCE_ONLY",
            "arguments": {"datasourceId": "ds-redis-secret"},
            "targetEndpoint": "http://internal.redis.local/tools",
            "proposalSubmission": {
                "schemaVersion": "datasmart.python-ai-runtime.tool-action-command-proposal-client.v1",
                "submitted": False,
                "skipped": True,
                "requestPayload": {
                    "graphId": "graph-redis",
                    "payloadReference": "agent-payload:redis/datasource-metadata-read",
                    "arguments": {"datasourceId": "ds-redis-secret"},
                    "sql": "select * from redis_hidden_table",
                },
            },
            "nextAction": "COMPLETE_GRAPH_PAYLOAD_POLICY_OR_APPROVAL_EVIDENCE_THEN_RESUME",
        }
    )
    return graph


if __name__ == "__main__":
    unittest.main()
