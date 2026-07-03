"""长期记忆 Embedding Provider 单元测试。

测试重点不是衡量模型语义质量，而是验证模型接入边界：配置解析、协议兼容、输入裁剪、向量合法性和
诊断脱敏。真实召回质量必须由离线数据集和线上指标验收，不能用确定性测试向量代替。
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
    OpenAICompatibleMemoryEmbeddingProvider,
    memory_embedding_provider_diagnostics,
    memory_embedding_provider_settings_from_env,
    validate_embedding_vector,
)


class MemoryEmbeddingProviderTest(unittest.TestCase):
    """验证可替换 Embedding Provider 的稳定契约。"""

    def test_deterministic_provider_is_stable_and_dimension_bounded(self) -> None:
        """smoke Provider 对同一输入应稳定，但诊断必须明确它不具备生产语义能力。"""

        provider = DeterministicHashEmbeddingProvider(dimensions=8)

        first = provider.embed_text("客户手机号字段质量规则")
        second = provider.embed_text("客户手机号字段质量规则")

        self.assertEqual(first, second)
        self.assertEqual(8, len(first))
        self.assertTrue(all(-1.0 <= value <= 1.0 for value in first))

    def test_openai_compatible_provider_sends_bounded_input_and_parses_vector(self) -> None:
        """OpenAI-compatible 适配器应使用标准 embeddings 路由，并解析有限浮点向量。"""

        transport = FakeUrlOpen({"data": [{"embedding": [0.25, -0.5, 0.75]}]})
        settings = MemoryEmbeddingProviderSettings(
            provider_type=MemoryEmbeddingProviderType.OPENAI_COMPATIBLE,
            endpoint="http://embedding.local/v1",
            api_key="test-secret",
            model="embedding-model-v1",
            timeout_seconds=7,
            max_input_chars=100,
        )
        provider = OpenAICompatibleMemoryEmbeddingProvider(settings, urlopen=transport)

        vector = provider.embed_text("x" * 150)

        self.assertEqual((0.25, -0.5, 0.75), vector)
        self.assertEqual("http://embedding.local/v1/embeddings", transport.request.full_url)
        self.assertEqual(7, transport.timeout)
        payload = json.loads(transport.request.data.decode("utf-8"))
        self.assertEqual("embedding-model-v1", payload["model"])
        self.assertEqual(100, len(payload["input"]))
        self.assertEqual("Bearer test-secret", transport.request.get_header("Authorization"))

    def test_invalid_embedding_values_are_rejected_before_index_write(self) -> None:
        """空数组、NaN 和 Infinity 不能进入 pgvector，否则距离计算和排序结果不可信。"""

        invalid_values = ([], [float("nan")], [float("inf")])
        for value in invalid_values:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    validate_embedding_vector(value)

    def test_environment_and_diagnostics_do_not_expose_credentials_or_endpoint(self) -> None:
        """诊断只声明配置状态，不回显 API Key 和完整 endpoint。"""

        settings = memory_embedding_provider_settings_from_env(
            {
                "DATASMART_AI_MEMORY_EMBEDDING_PROVIDER": "openai-compatible",
                "DATASMART_AI_MEMORY_EMBEDDING_ENDPOINT": "https://embedding.example.internal/v1",
                "DATASMART_AI_MEMORY_EMBEDDING_API_KEY": "secret-value",
                "DATASMART_AI_MEMORY_EMBEDDING_MODEL": "enterprise-embedding-v2",
                "DATASMART_AI_MEMORY_EMBEDDING_DIMENSIONS": "1024",
            }
        )
        diagnostics = memory_embedding_provider_diagnostics(settings)

        self.assertEqual(MemoryEmbeddingProviderType.OPENAI_COMPATIBLE, settings.provider_type)
        self.assertEqual(1024, settings.dimensions)
        self.assertTrue(diagnostics["productionReady"])
        self.assertTrue(diagnostics["endpointConfigured"])
        self.assertTrue(diagnostics["apiKeyConfigured"])
        serialized = json.dumps(diagnostics, ensure_ascii=False)
        self.assertNotIn("secret-value", serialized)
        self.assertNotIn("embedding.example.internal", serialized)


class FakeUrlOpen:
    """最小 HTTP transport fake，用于观察请求而不访问网络。"""

    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload
        self.request = None
        self.timeout = None

    def __call__(self, http_request, *, timeout):
        self.request = http_request
        self.timeout = timeout
        return FakeHttpResponse(self._payload)


class FakeHttpResponse:
    """支持 context manager 的 JSON 响应。"""

    def __init__(self, payload: dict[str, object]) -> None:
        self._body = json.dumps(payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def read(self) -> bytes:
        return self._body


if __name__ == "__main__":
    unittest.main()
