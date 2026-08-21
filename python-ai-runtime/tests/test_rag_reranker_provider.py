"""RAG 远程 Reranker Provider 合同测试。

这些测试不访问真实硅基流动服务，只验证请求、响应、错误脱敏与排序映射。真实模型质量由黄金评测集
验证，不能用 HTTP fake 的通过结果代替 Recall、MRR、nDCG 或延迟结论。
"""

import json
import http.client
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
        serialized_documents = "\n".join(payload["documents"])
        self.assertIn("资料类别：runbook", serialized_documents)
        self.assertIn("资料码：SAFE-RUNBOOK-001", serialized_documents)
        self.assertIn("检索锚点：test:runbook", serialized_documents)
        self.assertNotIn("should-not-leave-runtime", serialized_documents)
        self.assertNotIn("instruction", payload)
        self.assertEqual("Bearer test-key", transport.request.get_header("Authorization"))

    def test_unapproved_restricted_body_is_not_sent_to_reranker(self) -> None:
        """生产默认必须在发起 HTTP 前拒绝未批准的 restricted 正文。"""

        transport = FakeUrlOpen(
            {
                "results": [
                    {"index": 0, "relevance_score": 0.91},
                    {"index": 1, "relevance_score": 0.42},
                ]
            }
        )
        reranker = SiliconFlowRagReranker(self._settings(), urlopen=transport)
        candidates = self._candidates(sensitivity_level="restricted")

        with self.assertRaisesRegex(RuntimeError, "敏感级别未获外发批准") as captured:
            reranker.rerank(self._query(), candidates)

        self.assertIsNone(transport.request)
        self.assertNotIn("synthetic-restricted-body", str(captured.exception))
        self.assertEqual(
            "RAG_RERANK_PROVIDER_SENSITIVITY_NOT_APPROVED",
            reranker.diagnostics()["lastErrorCode"],
        )

    def test_unapproved_restricted_query_is_not_sent_to_reranker(self) -> None:
        """候选正文已批准也不能掩盖查询本身未获外发授权。"""

        transport = FakeUrlOpen(
            {
                "results": [
                    {"index": 0, "relevance_score": 0.91},
                    {"index": 1, "relevance_score": 0.42},
                ]
            }
        )
        reranker = SiliconFlowRagReranker(self._settings(), urlopen=transport)
        restricted_query = RagQuery(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            question="synthetic-restricted-query",
            sensitivity_level="restricted",
        )

        with self.assertRaisesRegex(RuntimeError, "敏感级别未获外发批准") as captured:
            reranker.rerank(restricted_query, self._candidates())

        self.assertIsNone(transport.request)
        self.assertNotIn("synthetic-restricted-query", str(captured.exception))
        self.assertEqual(
            "RAG_RERANK_PROVIDER_SENSITIVITY_NOT_APPROVED",
            reranker.diagnostics()["lastErrorCode"],
        )

    def test_synthetic_only_boundary_can_explicitly_allow_restricted_body(self) -> None:
        """仅 synthetic-only 评测可在显式配置后将 restricted 正文送入受控 Provider。"""

        transport = FakeUrlOpen(
            {
                "results": [
                    {"index": 0, "relevance_score": 0.91},
                    {"index": 1, "relevance_score": 0.42},
                ]
            }
        )
        settings = RagRerankerProviderSettings(
            **{
                **self._settings().__dict__,
                # 查询仍是 internal，候选是 restricted；两类正文都必须逐项显式批准。
                "approved_sensitivity_levels": ("internal", "restricted"),
                "synthetic_only_evaluation": True,
            }
        )
        reranker = SiliconFlowRagReranker(settings, urlopen=transport)

        reranker.rerank(self._query(), self._candidates(sensitivity_level="restricted"))

        payload = json.loads(transport.request.data.decode("utf-8"))
        self.assertIn("synthetic-restricted-body", "\n".join(payload["documents"]))

    def test_prepare_candidates_reports_the_actual_bounded_submission_window(self) -> None:
        """管线评测快照必须与 Provider 真正提交的候选窗口完全一致。

        Provider 的 ``max_documents`` 小于召回窗口时，未提交项既不能参与最终引用，也不能被评测报告
        误记为已由远端模型阅读。候选准备步骤因此需要在 HTTP 调用前显式可见，且仍保持原始排序。
        """

        settings = RagRerankerProviderSettings(
            **{**self._settings().__dict__, "max_documents": 1}
        )
        reranker = SiliconFlowRagReranker(settings, urlopen=FakeUrlOpen({"results": []}))

        submitted = reranker.prepare_candidates(self._candidates())

        self.assertEqual(1, len(submitted))
        self.assertEqual("doc-a", submitted[0].chunk.document_id)

    def test_prepare_candidates_round_robins_documents_before_same_document_chunks(self) -> None:
        """远端窗口应先覆盖更多独立文档，再补充同一文档的第二个分块。"""

        def candidate(document_id: str, chunk_index: int) -> RagScoredChunk:
            return RagScoredChunk(
                chunk=RagChunk(
                    chunk_id=f"{document_id}-chunk-{chunk_index}",
                    document_id=document_id,
                    chunk_index=chunk_index,
                    title=document_id,
                    text=f"{document_id} 的第 {chunk_index} 个分块。",
                    source_uri=f"corpus://{document_id}",
                    tenant_id="10",
                    project_id="101",
                    workspace_key="*",
                    source_type=RagChunkSourceType.DOCUMENT,
                ),
                fused_score=1.0 - (chunk_index * 0.01),
            )

        candidates = tuple(
            candidate(document_id, chunk_index)
            for chunk_index in range(3)
            for document_id in ("doc-a", "doc-b", "doc-c", "doc-d")
        )
        settings = RagRerankerProviderSettings(
            **{**self._settings().__dict__, "max_documents": 8}
        )
        reranker = SiliconFlowRagReranker(settings, urlopen=FakeUrlOpen({"results": []}))

        prepared = reranker.prepare_candidates(candidates)

        self.assertEqual(
            ("doc-a", "doc-b", "doc-c", "doc-d", "doc-a", "doc-b", "doc-c", "doc-d"),
            tuple(item.chunk.document_id for item in prepared),
        )

    def test_multi_evidence_facet_reserve_enters_real_sixteen_document_http_window(self) -> None:
        """原始排名第 17 的互补资料应进入真实 HTTP 窗口，而不是只在本地启发式中看见。

        这个回归覆盖此前最容易被误判的边界：召回器已经找到了 Recovery 事件资料，但它排在
        Provider 的 16 条外发上限之后。测试同时检查 ``prepare_candidates`` 和实际请求正文，确保
        评测快照、Provider 选择和 HTTP documents 三者使用完全相同的候选集合。
        """

        def make_candidate(index: int, text: str, category: str = "general_reference") -> RagScoredChunk:
            return RagScoredChunk(
                chunk=RagChunk(
                    chunk_id=f"window-{index}",
                    document_id=f"window-doc-{index}",
                    chunk_index=0,
                    title=category,
                    text=text,
                    source_uri=f"test://window/{index}",
                    tenant_id="*",
                    project_id="*",
                    workspace_key="*",
                    source_type=RagChunkSourceType.INCIDENT if category == "recovery_events" else RagChunkSourceType.DOCUMENT,
                    metadata={"category": category, "contentFormat": "jsonl"},
                ),
                fused_score=1.0 - index / 100.0,
                lexical_score=0.8 if index < 16 else 0.1,
            )

        candidates = tuple(
            make_candidate(index, f"普通候选资料 {index} 的背景说明。")
            for index in range(16)
        ) + (
            make_candidate(
                16,
                "FAILED_OBJECT_REPLAYED 分片 replay 后进入最终验证，Recovery 事件记录了处理结果。",
                "recovery_events",
            ),
        )
        query = RagQuery(
            tenant_id="*",
            project_id="*",
            actor_id="owner-a",
            question="请结合接口标识、失败分片 replay 和最终验证，说明如何恢复？",
        )
        transport = FakeUrlOpen(
            {"results": [{"index": index, "relevance_score": 0.5} for index in range(16)]}
        )
        settings = RagRerankerProviderSettings(
            **{**self._settings().__dict__, "max_documents": 16}
        )
        reranker = SiliconFlowRagReranker(settings, urlopen=transport)

        prepared = reranker.prepare_candidates(candidates, query=query)
        reranker.rerank(query, candidates)
        payload = json.loads(transport.request.data.decode("utf-8"))

        prepared_ids = tuple(item.chunk.document_id for item in prepared)
        self.assertEqual(16, len(prepared_ids))
        self.assertIn("window-doc-16", prepared_ids)
        self.assertEqual(
            ("window-doc-16", *[f"window-doc-{index}" for index in range(15)]),
            prepared_ids,
        )
        self.assertEqual(16, len(payload["documents"]))
        self.assertTrue(any("FAILED_OBJECT_REPLAYED" in document for document in payload["documents"]))
        self.assertFalse(any("普通候选资料 15" in document for document in payload["documents"]))

    def test_responsibility_candidate_enters_real_window_for_single_semantic_question(self) -> None:
        """单一职责问题的目标资料不能因长文档重复分块而在远端窗口前丢失。"""

        def make_candidate(index: int, text: str, category: str = "general_reference") -> RagScoredChunk:
            return RagScoredChunk(
                chunk=RagChunk(
                    chunk_id=f"responsibility-{index}",
                    document_id=f"responsibility-doc-{index}",
                    chunk_index=0,
                    title=category,
                    text=text,
                    source_uri=f"test://responsibility/{index}",
                    tenant_id="*",
                    project_id="*",
                    workspace_key="*",
                    source_type=RagChunkSourceType.RUNBOOK if category == "error_code_catalog" else RagChunkSourceType.DOCUMENT,
                    metadata={"category": category, "contentFormat": "txt"},
                ),
                fused_score=1.0 - index / 100.0,
                lexical_score=0.8 if index < 16 else 0.1,
            )

        candidates = tuple(
            make_candidate(index, f"普通运维说明资料 {index} 的背景内容。")
            for index in range(16)
        ) + (
            make_candidate(
                16,
                "错误码 CONNECTION_TIMEOUT 表示端点响应超时。系统先执行有界自动处理；仍失败时由人工接手，越权操作必须申请权限。",
                "error_code_catalog",
            ),
        )
        query = RagQuery(
            tenant_id="*",
            project_id="*",
            actor_id="owner-a",
            question="CONNECTION_TIMEOUT 报错时系统如何处理，什么时候需要人工或权限？",
        )
        settings = RagRerankerProviderSettings(
            **{**self._settings().__dict__, "max_documents": 16}
        )
        reranker = SiliconFlowRagReranker(
            settings,
            urlopen=FakeUrlOpen({"results": [{"index": index, "relevance_score": 0.5} for index in range(16)]}),
        )

        prepared = reranker.prepare_candidates(candidates, query=query)

        self.assertEqual(16, len(prepared))
        self.assertIn("responsibility-doc-16", {item.chunk.document_id for item in prepared})

    def test_vector_only_candidate_enters_real_window_even_when_lexical_chunks_fill_prefix(self) -> None:
        """Embedding 独有命中必须进入真实 Provider 窗口，不能被词法前缀静默截掉。"""

        def make_candidate(index: int, *, lexical: float, vector: float) -> RagScoredChunk:
            return RagScoredChunk(
                chunk=RagChunk(
                    chunk_id=f"vector-reserve-{index}",
                    document_id=f"vector-reserve-doc-{index}",
                    chunk_index=0,
                    title=f"候选资料 {index}",
                    text=f"候选正文 {index}，用于验证真实重排窗口。",
                    source_uri=f"test://vector-reserve/{index}",
                    tenant_id="*",
                    project_id="*",
                    workspace_key="*",
                    source_type=RagChunkSourceType.DOCUMENT,
                    metadata={"category": "general_reference", "contentFormat": "txt"},
                ),
                lexical_score=lexical,
                vector_score=vector,
                fused_score=1.0 - index / 100.0,
            )

        candidates = tuple(
            make_candidate(index, lexical=0.8, vector=0.0)
            for index in range(20)
        ) + (make_candidate(20, lexical=0.0, vector=0.94),)
        settings = RagRerankerProviderSettings(
            **{**self._settings().__dict__, "max_documents": 16, "vector_recall_reserve_ratio": 0.25}
        )
        reranker = SiliconFlowRagReranker(
            settings,
            urlopen=FakeUrlOpen({"results": [{"index": index, "relevance_score": 0.5} for index in range(16)]}),
        )

        prepared = reranker.prepare_candidates(candidates, query=self._query())

        prepared_ids = {item.chunk.document_id for item in prepared}
        self.assertEqual(16, len(prepared))
        self.assertIn("vector-reserve-doc-20", prepared_ids)

    def test_default_retrieval_prior_is_enabled_but_can_be_disabled_for_baseline(self) -> None:
        """默认启用小幅召回先验，同时保留显式 0 权重的纯远端基线。"""

        self.assertEqual(0.2, RagRerankerProviderSettings().retrieval_prior_weight)
        self.assertEqual(0.25, RagRerankerProviderSettings().vector_recall_reserve_ratio)
        configured = rag_reranker_provider_settings_from_env(
            {"DATASMART_RAG_RERANK_RETRIEVAL_PRIOR_WEIGHT": "0"}
        )
        self.assertEqual(0.0, configured.retrieval_prior_weight)

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

    def test_json_array_root_is_a_response_contract_error(self) -> None:
        """合法 JSON 但顶层不是对象时，也必须归类为脱敏的 Provider 合同错误。"""

        reranker = SiliconFlowRagReranker(
            self._settings(),
            urlopen=FakeUrlOpen(["synthetic-upstream-body"]),
        )

        with self.assertRaisesRegex(RuntimeError, "响应根节点必须是对象") as captured:
            reranker.rerank(self._query(), self._candidates())

        self.assertNotIn("synthetic-upstream-body", str(captured.exception))
        self.assertEqual(
            "RAG_RERANK_PROVIDER_INVALID_RESPONSE",
            reranker.diagnostics()["lastErrorCode"],
        )

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
        reranker = SiliconFlowRagReranker(
            self._settings(),
            urlopen=transport,
            sleep=lambda _: None,
        )

        with self.assertRaisesRegex(RuntimeError, "status=429") as captured:
            reranker.rerank(self._query(), self._candidates())

        serialized = json.dumps(reranker.diagnostics(), ensure_ascii=False)
        self.assertNotIn("test-key", serialized)
        self.assertNotIn("api.siliconflow.cn", serialized)
        self.assertNotIn("rate limited", str(captured.exception))
        self.assertEqual("RAG_RERANK_PROVIDER_HTTP_ERROR", reranker.diagnostics()["lastErrorCode"])

    def test_reranker_retries_transient_disconnect_and_preserves_candidate_mapping(self) -> None:
        """短暂断连后重试成功时，候选 index 仍必须映射回原始文档。"""

        transport = SequenceUrlOpen(
            (
                ConnectionError("temporary disconnect"),
                {
                    "results": [
                        {"index": 1, "relevance_score": 0.91},
                        {"index": 0, "relevance_score": 0.42},
                    ]
                },
            )
        )
        delays: list[float] = []
        settings = RagRerankerProviderSettings(
            **{
                **self._settings().__dict__,
                "retry_base_delay_ms": 10,
            }
        )
        reranker = SiliconFlowRagReranker(
            settings,
            urlopen=transport,
            sleep=delays.append,
        )

        ranked = reranker.rerank(self._query(), self._candidates())

        self.assertEqual(("doc-b", "doc-a"), tuple(item.chunk.document_id for item in ranked))
        self.assertEqual(2, transport.call_count)
        self.assertEqual([0.01], delays)

    def test_reranker_retries_incomplete_http_body_before_json_parsing(self) -> None:
        """Provider 截断 JSON 后应重试完整请求，而不是把 IncompleteRead 冒泡给评测器。"""

        transport = SequenceUrlOpen(
            (
                http.client.IncompleteRead(b'{"results":', 32),
                {
                    "results": [
                        {"index": 1, "relevance_score": 0.91},
                        {"index": 0, "relevance_score": 0.42},
                    ]
                },
            )
        )
        delays: list[float] = []
        settings = RagRerankerProviderSettings(
            **{
                **self._settings().__dict__,
                "retry_base_delay_ms": 10,
            }
        )
        reranker = SiliconFlowRagReranker(
            settings,
            urlopen=transport,
            sleep=delays.append,
        )

        ranked = reranker.rerank(self._query(), self._candidates())

        self.assertEqual(("doc-b", "doc-a"), tuple(item.chunk.document_id for item in ranked))
        self.assertEqual(2, transport.call_count)
        self.assertEqual([0.01], delays)
        self.assertIsNone(reranker.diagnostics()["lastErrorCode"])

    def test_reranker_classifies_exhausted_incomplete_body_as_network_error(self) -> None:
        """连续截断达到上限时应返回稳定网络错误码，且不能泄露上游 partial body。"""

        transport = SequenceUrlOpen(
            (
                http.client.IncompleteRead(b'{"results":', 32),
                http.client.IncompleteRead(b'{"results":', 32),
            )
        )
        settings = RagRerankerProviderSettings(
            **{
                **self._settings().__dict__,
                "max_attempts": 2,
            }
        )
        reranker = SiliconFlowRagReranker(
            settings,
            urlopen=transport,
            sleep=lambda _: None,
        )

        with self.assertRaisesRegex(RuntimeError, "网络连接失败") as captured:
            reranker.rerank(self._query(), self._candidates())

        self.assertNotIn("results", str(captured.exception))
        self.assertEqual(2, transport.call_count)
        self.assertEqual("RAG_RERANK_PROVIDER_NETWORK_ERROR", reranker.diagnostics()["lastErrorCode"])

    def test_retrieval_prior_blends_remote_rank_without_overwriting_raw_score(self) -> None:
        """开启排名融合时保留供应商原始分数，同时允许第一阶段候选校正偶然换序。"""

        candidates = tuple(
            RagScoredChunk(
                chunk=RagChunk(
                    chunk_id=f"prior-{index}",
                    document_id=f"prior-doc-{index}",
                    chunk_index=0,
                    title=f"候选 {index}",
                    text=f"候选正文 {index}",
                    source_uri=f"test://prior/{index}",
                    tenant_id="10",
                    project_id="101",
                    workspace_key="*",
                    source_type=RagChunkSourceType.DOCUMENT,
                ),
                fused_score=0.03 - index / 1000,
            )
            for index in range(3)
        )
        settings = RagRerankerProviderSettings(
            **{
                **self._settings().__dict__,
                "retrieval_prior_weight": 0.8,
            }
        )
        reranked = SiliconFlowRagReranker(
            settings,
            urlopen=FakeUrlOpen(
                {
                    "results": [
                        {"index": 2, "relevance_score": 0.99},
                        {"index": 1, "relevance_score": 0.80},
                        {"index": 0, "relevance_score": 0.70},
                    ]
                }
            ),
        ).rerank(self._query(), candidates)

        self.assertEqual(
            ("prior-doc-0", "prior-doc-1", "prior-doc-2"),
            tuple(item.chunk.document_id for item in reranked),
        )
        self.assertEqual((0.70, 0.80, 0.99), tuple(item.rerank_score for item in reranked))
        self.assertGreater(reranked[0].final_score, reranked[-1].final_score)

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
                "DATASMART_RAG_RERANK_RETRIEVAL_PRIOR_WEIGHT": "0.35",
            }
        )

        self.assertEqual(RagRerankerProviderType.SILICONFLOW, settings.provider_type)
        self.assertEqual(64, settings.max_documents)
        self.assertEqual(0.35, settings.retrieval_prior_weight)
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
            approved_sensitivity_levels=("internal",),
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
    def _candidates(*, sensitivity_level: str = "internal") -> tuple[RagScoredChunk, ...]:
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
                    sensitivity_level=sensitivity_level,
                    metadata=(
                        {
                            "category": "runbook",
                            "artifactCode": "SAFE-RUNBOOK-001",
                            "retrievalAnchor": "test:runbook",
                            "evidenceStatus": "current",
                            "secret": "should-not-leave-runtime",
                        }
                        if suffix == "b"
                        else {}
                    ),
                ),
                lexical_score=0.5,
                vector_score=0.7,
                fused_score=0.02,
                diversity_penalty=0.01,
            )
            for suffix, title, text in (
                ("a", "普通重试说明", "synthetic-restricted-body-a" if sensitivity_level == "restricted" else "仅在传输错误时重试失败对象。"),
                ("b", "字段映射修复手册", "synthetic-restricted-body-b" if sensitivity_level == "restricted" else "刷新元数据并修复唯一可证明的字段映射。"),
            )
        )


class FakeUrlOpen:
    """记录请求并返回指定 JSON 的最小 HTTP fake。"""

    def __init__(self, payload: object) -> None:
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
        self.call_count = 0

    def __call__(self, http_request, *, timeout):
        self.call_count += 1
        raise self._exception


class SequenceUrlOpen:
    """按顺序抛异常或返回 JSON，用于验证有限网络重试。"""

    def __init__(self, outcomes: tuple[BaseException | dict[str, object], ...]) -> None:
        self._outcomes = list(outcomes)
        self.call_count = 0

    def __call__(self, http_request, *, timeout):
        self.call_count += 1
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return FakeHttpResponse(outcome)


class FakeHttpResponse:
    """支持上下文管理器的 JSON 响应。"""

    def __init__(self, payload: object) -> None:
        self._body = json.dumps(payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def read(self) -> bytes:
        return self._body


if __name__ == "__main__":
    unittest.main()
