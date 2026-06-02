import os
import sqlite3
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api_memory_runtime import api_memory_runtime_diagnostics, build_api_memory_runtime
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryRecord,
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
    AgentMemoryType,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStoreEntry
from datasmart_ai_runtime.services.memory.memory_store_components import (
    AgentMemoryStoreSettings,
    build_memory_store_runtime,
    memory_store_diagnostics,
    memory_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_store_retriever import StoreBackedAgentMemoryRetriever


class AgentMemoryStoreComponentsTest(unittest.TestCase):
    """正式长期记忆 store 运行时组装测试。

    本文件不重复验证 `SqlAgentMemoryStore` 每个 SQL 细节，而是验证“配置如何变成可用 store”。
    这类测试对商业化部署很关键：很多生产事故不是业务算法错了，而是配置以为启用了 MySQL，实际仍在用内存。
    """

    def test_default_settings_use_in_memory_store_for_local_runtime(self) -> None:
        """默认配置必须零依赖启动，并在诊断中明确不是持久化。"""

        settings = memory_store_settings_from_env({})
        runtime = build_memory_store_runtime(settings)
        diagnostics = memory_store_diagnostics(runtime)

        self.assertEqual("in-memory", settings.store_type)
        self.assertFalse(runtime.persistent)
        self.assertEqual("InMemoryAgentMemoryStore", diagnostics["implementation"])

    def test_sqlite_formal_store_can_persist_and_be_retrieved(self) -> None:
        """SQLite 模式应能证明正式记忆跨 Runtime 重建后仍可召回。"""

        with tempfile.TemporaryDirectory() as temp_dir:
            sqlite_path = os.path.join(temp_dir, "formal-memory.sqlite3")
            settings = AgentMemoryStoreSettings(store_type="sqlite", sqlite_path=sqlite_path)
            connections: list[sqlite3.Connection] = []

            def connection_factory(config: AgentMemoryStoreSettings) -> sqlite3.Connection:
                connection = self._sqlite_connection_with_schema(config)
                connections.append(connection)
                return connection

            try:
                first_runtime = build_memory_store_runtime(settings, connection_factory=connection_factory)
                first_runtime.store.save_if_absent(self._entry())
                second_runtime = build_memory_store_runtime(settings, connection_factory=connection_factory)
                report = StoreBackedAgentMemoryRetriever(second_runtime.store).retrieve(
                    self._request(),
                    self._memory_plan(),
                )
                diagnostics = memory_store_diagnostics(second_runtime)
            finally:
                for connection in connections:
                    connection.close()

        self.assertTrue(diagnostics["persistent"])
        self.assertEqual("sqlite", diagnostics["configuredType"])
        self.assertEqual(1, report.total_retrieved)
        self.assertEqual("memory-a", report.results[0].memories[0].memory_id)

    def test_mysql_connection_failure_can_fail_open_to_in_memory_with_diagnostics(self) -> None:
        """MySQL 不可用且 fail-open=true 时，应回退内存并脱敏 DSN。"""

        settings = AgentMemoryStoreSettings(
            store_type="mysql",
            mysql_dsn="mysql://root:secret@localhost:3306/datasmart?charset=utf8mb4",
            fail_open=True,
        )
        runtime = build_memory_store_runtime(
            settings,
            connection_factory=lambda _: (_ for _ in ()).throw(RuntimeError("模拟正式记忆 MySQL 不可用")),
        )
        diagnostics = memory_store_diagnostics(runtime)

        self.assertFalse(runtime.persistent)
        self.assertTrue(diagnostics["fallback"])
        self.assertIn("模拟正式记忆 MySQL 不可用", diagnostics["fallbackReason"])
        self.assertNotIn("secret", diagnostics["mysql"]["dsn"])

    def test_mysql_connection_failure_can_fast_fail_for_production(self) -> None:
        """生产环境可设置 fail-open=false，让正式记忆持久化异常直接阻断启动。"""

        settings = AgentMemoryStoreSettings(
            store_type="mysql",
            mysql_dsn="host=localhost;user=root;password=secret;database=datasmart",
            fail_open=False,
        )

        with self.assertRaises(RuntimeError):
            build_memory_store_runtime(
                settings,
                connection_factory=lambda _: (_ for _ in ()).throw(RuntimeError("模拟生产正式记忆连接失败")),
            )

    def test_api_memory_runtime_diagnostics_groups_candidate_and_formal_store(self) -> None:
        """API 记忆运行时诊断应同时展示候选 store、正式 store、retriever 和 materializer。"""

        with self._without_memory_env():
            components = build_api_memory_runtime()
        diagnostics = api_memory_runtime_diagnostics(components)

        self.assertEqual("python-ai-memory-runtime", diagnostics["component"])
        self.assertIn("candidateStore", diagnostics)
        self.assertIn("formalStore", diagnostics)
        self.assertIn("receiptStore", diagnostics)
        self.assertIn("leaseStore", diagnostics)
        self.assertIn("materializationRunner", diagnostics)
        self.assertEqual("StoreBackedAgentMemoryRetriever", diagnostics["retriever"]["implementation"])
        self.assertEqual("AgentMemoryMaterializationRunner", diagnostics["materializationRunner"]["implementation"])
        self.assertTrue(diagnostics["materializer"]["runnerAvailable"])
        self.assertFalse(diagnostics["materializer"]["workerEnabled"])
        self.assertFalse(diagnostics["materializationRunner"]["workerEnabled"])

    @staticmethod
    def _sqlite_connection_with_schema(settings: AgentMemoryStoreSettings) -> sqlite3.Connection:
        """创建正式记忆测试表。

        使用 `CREATE TABLE IF NOT EXISTS` 是为了模拟同一个 SQLite 文件被多个 Runtime 实例重复打开。
        """

        connection = sqlite3.connect(settings.sqlite_path)
        connection.row_factory = sqlite3.Row
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS agent_memory_store_entry (
                memory_id TEXT PRIMARY KEY,
                tenant_id TEXT,
                project_id TEXT,
                session_id TEXT,
                memory_type TEXT NOT NULL,
                scope TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT,
                importance_score REAL NOT NULL,
                sensitivity_level TEXT NOT NULL,
                tags_json TEXT,
                created_at TEXT,
                attributes_json TEXT,
                workspace_key TEXT NOT NULL,
                memory_namespace TEXT NOT NULL,
                namespace_json TEXT,
                idempotency_key TEXT NOT NULL UNIQUE,
                source_candidate_id TEXT NOT NULL UNIQUE,
                expires_at TEXT NOT NULL,
                materialized_at TEXT NOT NULL
            );
            """
        )
        return connection

    @staticmethod
    def _request() -> AgentRequest:
        return AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请参考手机号质量异常治理经验",
        )

    @staticmethod
    def _memory_plan() -> AgentMemoryPlan:
        return AgentMemoryPlan(
            retrieval_targets=(
                AgentMemoryRetrievalTarget(
                    memory_type=AgentMemoryType.EPISODIC,
                    scope=AgentMemoryScope.PROJECT,
                    query_hint="手机号 质量 异常",
                    reason="验证正式 store runtime builder 能接入召回链路。",
                ),
            )
        )

    @staticmethod
    def _entry() -> AgentMemoryStoreEntry:
        now = datetime.now(timezone.utc)
        return AgentMemoryStoreEntry(
            memory=AgentMemoryRecord(
                memory_id="memory-a",
                memory_type=AgentMemoryType.EPISODIC,
                scope=AgentMemoryScope.PROJECT,
                tenant_id="tenant-a",
                project_id="project-a",
                title="手机号质量异常治理经验",
                content="历史质量检测发现手机号格式异常，应优先复用正则校验与空值兜底规则。",
                source="unit-test",
                importance_score=0.8,
                sensitivity_level="internal",
                tags=("quality.rule.suggest", "episodic"),
                created_at=now,
                attributes={"payloadPolicy": "SUMMARY_ONLY"},
            ),
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            namespace=("memory-namespace", "memory:tenant:tenant-a:project:project-a", "type", "episodic"),
            idempotency_key="tenant-a|project-a|memory-a",
            source_candidate_id="candidate-a",
            expires_at=now + timedelta(days=30),
            materialized_at=now,
        )

    @staticmethod
    def _without_memory_env():
        """临时清理长期记忆相关环境变量，保证默认装配测试不受开发机外部配置影响。"""

        class _MemoryEnvGuard:
            keys = (
                "DATASMART_AI_MEMORY_WRITE_STORE",
                "DATASMART_AI_MEMORY_WRITE_SQLITE_PATH",
                "DATASMART_AI_MEMORY_WRITE_MYSQL_DSN",
                "DATASMART_AI_MEMORY_WRITE_SQL_CONNECT_TIMEOUT_SECONDS",
                "DATASMART_AI_MEMORY_WRITE_STORE_FAIL_OPEN",
                "DATASMART_AI_FORMAL_MEMORY_STORE",
                "DATASMART_AI_FORMAL_MEMORY_SQLITE_PATH",
                "DATASMART_AI_FORMAL_MEMORY_MYSQL_DSN",
                "DATASMART_AI_FORMAL_MEMORY_SQL_CONNECT_TIMEOUT_SECONDS",
                "DATASMART_AI_FORMAL_MEMORY_STORE_FAIL_OPEN",
                "DATASMART_AI_MEMORY_RECEIPT_STORE",
                "DATASMART_AI_MEMORY_RECEIPT_SQLITE_PATH",
                "DATASMART_AI_MEMORY_RECEIPT_MYSQL_DSN",
                "DATASMART_AI_MEMORY_RECEIPT_SQL_CONNECT_TIMEOUT_SECONDS",
                "DATASMART_AI_MEMORY_RECEIPT_STORE_FAIL_OPEN",
                "DATASMART_AI_MEMORY_LEASE_STORE",
                "DATASMART_AI_MEMORY_LEASE_SQLITE_PATH",
                "DATASMART_AI_MEMORY_LEASE_MYSQL_DSN",
                "DATASMART_AI_MEMORY_LEASE_SQL_CONNECT_TIMEOUT_SECONDS",
                "DATASMART_AI_MEMORY_LEASE_STORE_FAIL_OPEN",
                "DATASMART_AI_MEMORY_LEASE_SECONDS",
            )

            def __enter__(self):
                self.previous = {key: os.environ.get(key) for key in self.keys}
                for key in self.keys:
                    os.environ.pop(key, None)
                return self

            def __exit__(self, exc_type, exc, tb):
                for key, value in self.previous.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value

        return _MemoryEnvGuard()


if __name__ == "__main__":
    unittest.main()
