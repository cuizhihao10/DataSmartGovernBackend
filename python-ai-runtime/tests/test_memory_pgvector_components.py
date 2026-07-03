"""pgvector 长期记忆运行时装配测试。

适配器的 SQL 行为由真实 PostgreSQL smoke 验证；本文件聚焦配置决策，确保本地默认零依赖、开发环境
可受控回退、生产环境可以快速失败，并且启用成功后确实生成 VECTOR 二级索引。
"""

import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    DeterministicHashEmbeddingProvider,
    MemoryEmbeddingProviderSettings,
    MemoryEmbeddingProviderType,
)
from datasmart_ai_runtime.services.memory.memory_pgvector_adapter import PgvectorAgentMemorySecondaryIndex
from datasmart_ai_runtime.services.memory.memory_pgvector_components import (
    PgvectorMemoryIndexRuntimeSettings,
    build_pgvector_memory_index_runtime,
    pgvector_memory_index_diagnostics,
    pgvector_memory_index_runtime_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_store import InMemoryAgentMemoryStore


class PgvectorMemoryIndexComponentsTest(unittest.TestCase):
    """验证 pgvector 的启用、回退和诊断策略。"""

    def test_default_configuration_is_disabled_without_opening_connection(self) -> None:
        """默认关闭时不能连接数据库，也不能偷偷启用测试向量。"""

        connection_calls = []
        runtime = build_pgvector_memory_index_runtime(
            memory_store=InMemoryAgentMemoryStore(),
            runtime_settings=PgvectorMemoryIndexRuntimeSettings(),
            connection_factory=lambda settings: connection_calls.append(settings),
        )

        self.assertFalse(runtime.configured_enabled)
        self.assertFalse(runtime.active)
        self.assertIsNone(runtime.index)
        self.assertEqual([], connection_calls)

    def test_enabled_fail_open_reports_missing_provider_without_breaking_runtime(self) -> None:
        """开发环境缺少模型配置时应明确回退，而不是伪装成 pgvector 已激活。"""

        runtime = build_pgvector_memory_index_runtime(
            memory_store=InMemoryAgentMemoryStore(),
            runtime_settings=PgvectorMemoryIndexRuntimeSettings(
                enabled=True,
                postgresql_dsn="host=postgresql password=secret",
                fail_open=True,
            ),
            embedding_settings=MemoryEmbeddingProviderSettings(),
        )
        diagnostics = pgvector_memory_index_diagnostics(runtime)

        self.assertTrue(runtime.configured_enabled)
        self.assertFalse(runtime.active)
        self.assertIsNone(runtime.index)
        self.assertTrue(diagnostics["fallback"])
        self.assertNotIn("secret", json.dumps(diagnostics, ensure_ascii=False))

    def test_enabled_fail_fast_rejects_invalid_production_configuration(self) -> None:
        """生产 fail-open=false 时，缺少 Provider 必须阻断启动，避免静默降低召回能力。"""

        with self.assertRaises(ValueError):
            build_pgvector_memory_index_runtime(
                memory_store=InMemoryAgentMemoryStore(),
                runtime_settings=PgvectorMemoryIndexRuntimeSettings(
                    enabled=True,
                    postgresql_dsn="host=postgresql",
                    fail_open=False,
                ),
                embedding_settings=MemoryEmbeddingProviderSettings(),
            )

    def test_enabled_runtime_builds_real_vector_adapter(self) -> None:
        """DSN、模型和连接都有效时，应生成同时支持同步与检索的 pgvector adapter。"""

        connection = object()
        runtime = build_pgvector_memory_index_runtime(
            memory_store=InMemoryAgentMemoryStore(),
            runtime_settings=PgvectorMemoryIndexRuntimeSettings(
                enabled=True,
                postgresql_dsn="host=postgresql dbname=datasmart_govern",
                fail_open=False,
            ),
            embedding_settings=MemoryEmbeddingProviderSettings(
                provider_type=MemoryEmbeddingProviderType.DETERMINISTIC,
                model="datasmart-test-hash-v1",
                dimensions=16,
            ),
            connection_factory=lambda _: connection,
            embedding_provider=DeterministicHashEmbeddingProvider(dimensions=16),
        )

        self.assertTrue(runtime.active)
        self.assertIsInstance(runtime.index, PgvectorAgentMemorySecondaryIndex)

    def test_environment_parser_supports_dedicated_and_shared_postgresql_dsn(self) -> None:
        """专用 pgvector DSN 优先；未配置时可复用 AI Memory PostgreSQL DSN。"""

        dedicated = pgvector_memory_index_runtime_settings_from_env(
            {
                "DATASMART_AI_MEMORY_PGVECTOR_ENABLED": "true",
                "DATASMART_AI_MEMORY_PGVECTOR_POSTGRESQL_DSN": "host=dedicated",
                "DATASMART_AI_MEMORY_POSTGRESQL_DSN": "host=shared",
                "DATASMART_AI_MEMORY_PGVECTOR_FAIL_OPEN": "false",
            }
        )
        shared = pgvector_memory_index_runtime_settings_from_env(
            {
                "DATASMART_AI_MEMORY_PGVECTOR_ENABLED": "true",
                "DATASMART_AI_MEMORY_POSTGRESQL_DSN": "host=shared",
            }
        )

        self.assertEqual("host=dedicated", dedicated.postgresql_dsn)
        self.assertFalse(dedicated.fail_open)
        self.assertEqual("host=shared", shared.postgresql_dsn)


if __name__ == "__main__":
    unittest.main()
