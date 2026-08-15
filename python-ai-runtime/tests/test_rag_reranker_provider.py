"""RAG 远程 Reranker Provider 合同测试。

这些测试不访问真实硅基流动服务，只验证请求、响应、错误脱敏与排序映射。真实模型质量由黄金评测集
验证，不能用 HTTP fake 的通过结果代替 Recall、MRR、nDCG 或延迟结论。
"""

import json
import os
import sys
import unittest
from urllib import error

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.models import (
    RagChunk,
    RagChunkSourceType,
    RagQuery,
    RagScoredChunk,
)
from datasmart_ai_runtime.services.rag.reranker_provider import (
    RagRerankerProviderSettings,
    RagRerankerProviderType,
    SiliconFlowRagReranker,
    build_rag_reranker_provider,
    rag_reranker_provider_settings_from_env,
)


class RagRerankerProviderTest(unittest.TestCase):
    """验证硅基流动 Reranker 的低敏、完整和确定性合同。"""

    def test_siliconflow_request_does_not_ask_provider_to_echo_documents(self) -> None:
        """远程请求应返回全部候选的索引与分数，但不能要求第三方回显文档正文。"""

        transport = FakeUrlOpen(
            {
                "id": "rerank-safe-id",
                "results": [
                    {"index": 1, "relevance_score": 0.91},
                    {"index": 0, "relevance_score": 0.42},
                ],
                "tokens": {"input_tokens": 24, "output_tokens": 0},
            }
        )
        reranker = SiliconFlowRagReranker(self._settings(), urlopen=transport)

        reranked = reranker.rerank(self._query(), self._candidates())

        self.assertEqual(("doc-b", "doc-a"), tuple(item.chunk.document_id for item in reranked))
        self.assertEqual((0.91, 0.42), tuple(item.rerank_score for item in reranked))
        self.assertEqual("https://api.siliconflow.cn/v1/rerank", transport.request.full_url)
        self.assertEqual(12, transport.timeout)
        payload = json.loads(transport.request.data.decode("utf-8"))
        self.assertEqual("BAAI/bge-reranker-v2-m3", payload["model"])
        self.assertEqual(2, payload["top_n"])
        self.assertFalse(payload["return_documents"])
        self.assertEqual(2, len(payload["documents"]))
        self.assertNotIn("instruction", payload)
        self.assertEqual("Bearer test-key", transport.request.get_header("Authorization"))

    def test_incomplete_or_duplicate_result_indices_are_rejected(self) -> None:
        """缺失、重复或越界索引会破坏候选映射，必须整体拒绝而不是猜测顺序。"""

        invalid_payloads = (
            {"results": [{"index": 0, "relevance_score": 0.8}]},
            {
                "results": [
                    {"index": 0, "relevance_score": 0.8},
                    {"index": 0, "relevance_score": 0.7},
                ]
            },
            {
                "results": [
                    {"index": 0, "relevance_score": 0.8},
                    {"index": 9, "relevance_score": 0.7},
                ]
            },
        )
        for payload in invalid_payloads:
            with self.subTest(payload=payload):
                reranker = SiliconFlowRagReranker(self._settings(), urlopen=FakeUrlOpen(payload))
                with self.assertRaises(RuntimeError):
                    reranker.rerank(self._query(), self._candidates())

    def test_fractional_result_index_is_rejected(self) -> None:
        """小数 index 不能经 ``int`` 截断后指向另一条候选证据。"""

        reranker = SiliconFlowRagReranker(
            self._settings(),
            urlopen=FakeUrlOpen(
                {
                    "results": [
                        {"index": 0.5, "relevance_score": 0.8},
                        {"index": 1, "relevance_score": 0.7},
                    ]
                }
            ),
        )

        with self.assertRaisesRegex(RuntimeError, "index 或分数非法"):
            reranker.rerank(self._query(), self._candidates())

    def test_http_errors_and_diagnostics_do_not_expose_endpoint_key_or_body(self) -> None:
        """远端异常只保留稳定状态码，不能把密钥、Endpoint 或上游正文带入日志。"""

        transport = RaisingUrlOpen(
            error.HTTPError(
                "https://api.siliconflow.cn/v1/rerank",
                429,
                "rate limited",
                hdrs=None,
                fp=None,
            )
        )
        reranker = SiliconFlowRagReranker(self._settings(), urlopen=transport)

        with self.assertRaisesRegex(RuntimeError, "status=429") as captured:
            reranker.rerank(self._query(), self._candidates())

        serialized = json.dumps(reranker.diagnostics(), ensure_ascii=False)
        self.assertNotIn("test-key", serialized)
        self.assertNotIn("api.siliconflow.cn", serialized)
        self.assertNotIn("rate limited", str(captured.exception))
        self.assertEqual("RAG_RERANK_PROVIDER_HTTP_ERROR", reranker.diagnostics()["lastErrorCode"])

    def test_environment_builder_requires_explicit_provider_model_endpoint_and_key(self) -> None:
        """未配置时保持规则重排；显式启用后，关键字段缺失必须快速失败。"""

        self.assertIsNone(build_rag_reranker_provider(RagRerankerProviderSettings()))
        settings = rag_reranker_provider_settings_from_env(
            {
                "DATASMART_RAG_RERANK_PROVIDER": "siliconflow",
                "DATASMART_RAG_RERANK_ENDPOINT": "https://api.siliconflow.cn/v1/rerank",
                "DATASMART_RAG_RERANK_API_KEY": "test-key",
                "DATASMART_RAG_RERANK_MODEL": "BAAI/bge-reranker-v2-m3",
                "DATASMART_RAG_RERANK_TIMEOUT_SECONDS": "12",
                "DATASMART_RAG_RERANK_MAX_DOCUMENTS": "64",
            }
        )

        self.assertEqual(RagRerankerProviderType.SILICONFLOW, settings.provider_type)
        self.assertEqual(64, settings.max_documents)
        self.assertIsInstance(build_rag_reranker_provider(settings, urlopen=FakeUrlOpen({})), SiliconFlowRagReranker)

        with self.assertRaises(ValueError):
            build_rag_reranker_provider(
                RagRerankerProviderSettings(
                    provider_type=RagRerankerProviderType.SILICONFLOW,
                    endpoint=settings.endpoint,
                    model=settings.model,
                )
            )

        with self.assertRaisesRegex(ValueError, "官方主机"):
            build_rag_reranker_provider(
                RagRerankerProviderSettings(
                    provider_type=RagRerankerProviderType.SILICONFLOW,
                    endpoint="https://untrusted.example/v1/rerank",
                    api_key="test-key",
                    model=settings.model,
                )
            )

    @staticmethod
    def _settings() -> RagRerankerProviderSettings:
        return RagRerankerProviderSettings(
            provider_type=RagRerankerProviderType.SILICONFLOW,
            endpoint="https://api.siliconflow.cn/v1/rerank",
            api_key="test-key",
            model="BAAI/bge-reranker-v2-m3",
            timeout_seconds=12,
            max_documents=64,
        )

    @staticmethod
    def _query() -> RagQuery:
        return RagQuery(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            question="字段映射失败后应该如何安全恢复？",
        )

    @staticmethod
    def _candidates() -> tuple[RagScoredChunk, ...]:
        return tuple(
            RagScoredChunk(
                chunk=RagChunk(
                    chunk_id=f"chunk-{suffix}",
                    document_id=f"doc-{suffix}",
                    chunk_index=0,
                    title=title,
                    text=text,
                    source_uri=f"corpus://doc-{suffix}",
                    tenant_id="10",
                    project_id="101",
                    workspace_key="*",
                    source_type=RagChunkSourceType.RUNBOOK,
                ),
                lexical_score=0.5,
                vector_score=0.7,
                fused_score=0.02,
                diversity_penalty=0.01,
            )
            for suffix, title, text in (
                ("a", "普通重试说明", "仅在传输错误时重试失败对象。"),
                ("b", "字段映射修复手册", "刷新元数据并修复唯一可证明的字段映射。"),
            )
        )


class FakeUrlOpen:
    """记录请求并返回指定 JSON 的最小 HTTP fake。"""

    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload
        self.request = None
        self.timeout = None

    def __call__(self, http_request, *, timeout):
        self.request = http_request
        self.timeout = timeout
        return FakeHttpResponse(self._payload)


class RaisingUrlOpen:
    """始终抛出指定异常，用于验证失败脱敏。"""

    def __init__(self, exception: BaseException) -> None:
        self._exception = exception

    def __call__(self, http_request, *, timeout):
        raise self._exception


class FakeHttpResponse:
    """支持上下文管理器的 JSON 响应。"""

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
