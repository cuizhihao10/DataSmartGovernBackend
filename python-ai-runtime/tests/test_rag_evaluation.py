"""RAG 黄金评测集加载、指标计算与低敏报告合同测试。"""

from __future__ import annotations

import json
import os
import runpy
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag import (
    RagChunkSourceType,
    RagCitation,
    RagChunk,
    RagDocument,
    RagPipelineResult,
    RagScoredChunk,
)
from datasmart_ai_runtime.services.rag.evaluation import (
    RagEvaluationDataset,
    RagEvaluationDatasetError,
    RagEvaluationRunner,
    RagEvaluationThresholds,
    RagExpectedDocument,
    RagGoldenCase,
    load_rag_evaluation_dataset,
    validate_synthetic_evaluation_ingest_runtime,
)


class RagEvaluationTest(unittest.TestCase):
    """保护评测资产完整性、治理指标和报告脱敏边界。"""

    def test_repository_dataset_loads_all_documents_and_golden_cases(self) -> None:
        """仓库中的 356 份异构文档和 752 条黄金用例应能映射到运行时模型。"""

        asset_root = Path(__file__).resolve().parents[1] / "evaluation" / "rag"
        dataset = load_rag_evaluation_dataset(asset_root)

        self.assertEqual(356, len(dataset.documents))
        self.assertEqual(752, len(dataset.cases))
        self.assertEqual("synthetic-only", dataset.asset_boundary)
        self.assertRegex(dataset.fingerprint, r"^[0-9a-f]{64}$")
        self.assertTrue(
            {
                RagChunkSourceType.INCIDENT,
                RagChunkSourceType.TASK_CASE,
                RagChunkSourceType.DATASET,
            }.issubset({document.source_type for document in dataset.documents})
        )
        self.assertEqual(
            {"csv", "docx", "json", "jsonl", "log", "md", "sql", "txt", "xlsx"},
            {str(document.metadata.get("contentFormat")) for document in dataset.documents},
        )
        self.assertTrue(
            any(
                document.metadata.get("contentFormat") == "docx"
                and document.metadata.get("retrievalAnchor") in document.content
                for document in dataset.documents
            )
        )
        self.assertTrue(
            any(
                document.metadata.get("contentFormat") == "xlsx"
                and "工作表：数据" in document.content
                and document.metadata.get("sheetCount") == 6
                for document in dataset.documents
            )
        )

    def test_loader_rejects_tampered_document_bytes(self) -> None:
        """任一原文件被修改但 Manifest 哈希未更新时必须拒绝载入。"""

        source_root = Path(__file__).resolve().parents[1] / "evaluation" / "rag"
        with tempfile.TemporaryDirectory() as temporary_directory:
            copied_root = Path(temporary_directory) / "rag"
            _copy_evaluation_assets(source_root, copied_root)
            manifest = json.loads((copied_root / "manifest.json").read_text(encoding="utf-8"))
            first_document = copied_root / manifest["documents"][0]["path"]
            first_document.write_text(
                first_document.read_text(encoding="utf-8") + "\n未经 Manifest 登记的修改。\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RagEvaluationDatasetError, "哈希"):
                load_rag_evaluation_dataset(copied_root)

    def test_loader_rejects_tampered_extracted_text_hash_and_format_declaration(self) -> None:
        """原文件未变时，提取文本哈希或格式声明漂移也必须拒绝。"""

        source_root = Path(__file__).resolve().parents[1] / "evaluation" / "rag"
        mutations = (
            ("extractedTextSha256", "0" * 64, "提取文本哈希"),
            ("contentFormat", "txt", "格式声明"),
            ("mediaType", "text/plain", "格式声明"),
        )
        for field, value, expected_error in mutations:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary_directory:
                copied_root = Path(temporary_directory) / "rag"
                _copy_evaluation_assets(source_root, copied_root)
                manifest_path = copied_root / "manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                office_document = next(
                    item for item in manifest["documents"] if item["contentFormat"] == "docx"
                )
                office_document[field] = value
                manifest_path.write_text(
                    json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n",
                    encoding="utf-8",
                )

                with self.assertRaisesRegex(RagEvaluationDatasetError, expected_error):
                    load_rag_evaluation_dataset(copied_root)

    def test_loader_rejects_invalid_source_evidence_metadata(self) -> None:
        """来源状态、时间或可信度非法时，运行时不能只因 Markdown 哈希正确就接受资产。"""

        source_root = Path(__file__).resolve().parents[1] / "evaluation" / "rag"
        with tempfile.TemporaryDirectory() as temporary_directory:
            copied_root = Path(temporary_directory) / "rag"
            _copy_evaluation_assets(source_root, copied_root)
            manifest_path = copied_root / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["documents"][0]["metadata"]["sourceConfidence"] = 1.5
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RagEvaluationDatasetError, "来源证据 metadata"):
                load_rag_evaluation_dataset(copied_root)

    def test_loader_rejects_json_values_with_ambiguous_scalar_types(self) -> None:
        """布尔值和整数必须保持 JSON 原始类型，不能把字符串或小数静默转换成合法配置。"""

        source_root = Path(__file__).resolve().parents[1] / "evaluation" / "rag"
        mutations = (
            ("manifest", "enabled", "false", "enabled.*布尔"),
            ("case", "shouldRefuse", "false", "shouldRefuse.*布尔"),
            ("case", "topK", 3.5, "topK.*整数"),
            ("relevance", "relevance", 2.5, "相关性等级.*整数"),
        )
        for target, field, invalid_value, expected_error in mutations:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary_directory:
                copied_root = Path(temporary_directory) / "rag"
                _copy_evaluation_assets(source_root, copied_root)
                if target == "manifest":
                    path = copied_root / "manifest.json"
                    payload = json.loads(path.read_text(encoding="utf-8"))
                    payload["documents"][0][field] = invalid_value
                    path.write_text(
                        json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
                        encoding="utf-8",
                    )
                else:
                    path = copied_root / "golden_cases.jsonl"
                    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
                    if target == "relevance":
                        rows[0]["relevantDocuments"][0][field] = invalid_value
                    else:
                        rows[0][field] = invalid_value
                    path.write_text(
                        "\n".join(
                            json.dumps(row, ensure_ascii=False, separators=(",", ":"))
                            for row in rows
                        )
                        + "\n",
                        encoding="utf-8",
                    )

                with self.assertRaisesRegex(RagEvaluationDatasetError, expected_error):
                    load_rag_evaluation_dataset(copied_root)

    def test_synthetic_corpus_ingest_runtime_rejects_production_like_modes(self) -> None:
        """双重确认参数也不能让合成黄金语料进入生产或预发布知识库。"""

        for allowed_mode in ("local", "development", "dev", "test", "testing", "learning"):
            with self.subTest(allowed_mode=allowed_mode):
                self.assertEqual(
                    allowed_mode,
                    validate_synthetic_evaluation_ingest_runtime(allowed_mode),
                )
        for rejected_mode in ("production", "prod", "staging", "preprod", ""):
            with self.subTest(rejected_mode=rejected_mode):
                with self.assertRaisesRegex(ValueError, "非生产"):
                    validate_synthetic_evaluation_ingest_runtime(rejected_mode)

    def test_siliconflow_evaluation_keeps_dedicated_capability_keys_separate(self) -> None:
        """Embedding 与 Reranker 专用密钥不能互相回退，只有共享密钥可同时供两种能力使用。"""

        script_path = Path(__file__).resolve().parents[2] / "scripts" / "rag-evaluation.py"
        resolve_keys = runpy.run_path(str(script_path))["_resolve_siliconflow_api_keys"]

        with self.assertRaisesRegex(RuntimeError, "Reranker"):
            resolve_keys({"DATASMART_RAG_EMBEDDING_API_KEY": "embedding-test-placeholder"})
        with self.assertRaisesRegex(RuntimeError, "Embedding"):
            resolve_keys({"DATASMART_RAG_RERANK_API_KEY": "reranker-test-placeholder"})
        self.assertEqual(
            ("embedding-test-placeholder", "reranker-test-placeholder"),
            resolve_keys(
                {
                    "DATASMART_RAG_EMBEDDING_API_KEY": "embedding-test-placeholder",
                    "DATASMART_RAG_RERANK_API_KEY": "reranker-test-placeholder",
                }
            ),
        )
        self.assertEqual(
            ("shared-test-placeholder", "shared-test-placeholder"),
            resolve_keys({"SILICONFLOW_API_KEY": "shared-test-placeholder"}),
        )

    def test_runner_calculates_quality_governance_metrics_without_question_text(self) -> None:
        """评测报告应同时度量检索质量和范围泄漏，且不持久化问题正文。"""

        documents = (
            _document("doc-global", "synthetic://global", "*", "*", "*"),
            _document("doc-tenant-b", "synthetic://tenant-b", "tenant-b", "project-b", "space-b"),
            _document("doc-tenant-a", "synthetic://tenant-a", "tenant-a", "project-a", "space-a"),
        )
        cases = (
            RagGoldenCase(
                case_id="case-hit",
                case_type="exact_error_code",
                question="只应存在于内存执行过程的问题正文 A",
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="space-a",
                retrieval_mode="lexical",
                top_k=3,
                relevant_documents=(RagExpectedDocument("doc-global", 3),),
                expected_citation_uris=("synthetic://global",),
                forbidden_document_ids=(),
                should_refuse=False,
                refusal_reason=None,
                source_types=(),
                tags=("命中",),
            ),
            RagGoldenCase(
                case_id="case-refuse",
                case_type="no_answer",
                question="只应存在于内存执行过程的问题正文 B",
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="space-a",
                retrieval_mode="hybrid",
                top_k=3,
                relevant_documents=(),
                expected_citation_uris=(),
                forbidden_document_ids=("doc-tenant-b",),
                should_refuse=True,
                refusal_reason="NO_AUTHORIZED_EVIDENCE",
                source_types=(),
                tags=("拒答",),
            ),
            RagGoldenCase(
                case_id="case-leak",
                case_type="cross_scope_refusal",
                question="只应存在于内存执行过程的问题正文 C",
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="space-a",
                retrieval_mode="exact_search",
                top_k=3,
                relevant_documents=(RagExpectedDocument("doc-tenant-a", 3),),
                expected_citation_uris=("synthetic://tenant-a",),
                forbidden_document_ids=("doc-tenant-b",),
                should_refuse=False,
                refusal_reason=None,
                source_types=(),
                tags=("隔离",),
            ),
        )
        dataset = RagEvaluationDataset(
            root=Path("synthetic-evaluation"),
            schema_version="datasmart.rag-evaluation-assets.v2",
            asset_boundary="synthetic-only",
            documents=documents,
            cases=cases,
            fingerprint="0" * 64,
        )

        result_by_case = {
            "case-hit": _result(_citation(documents[0])),
            "case-refuse": _result(),
            "case-leak": _result(_citation(documents[1])),
        }
        runner = RagEvaluationRunner(
            dataset,
            execute_query=lambda query: result_by_case[str(query.trace_id).removeprefix("rag-eval:")],
            thresholds=RagEvaluationThresholds(
                minimum_recall_at_k=0.4,
                minimum_mrr=0.4,
                minimum_ndcg_at_k=0.4,
                minimum_citation_precision=0.4,
                minimum_citation_recall=0.4,
                minimum_refusal_f1=0.9,
                minimum_forbidden_document_pass_rate=1.0,
                maximum_scope_leakage_rate=0.0,
            ),
        )

        summary = runner.evaluate().to_summary()

        self.assertEqual(3, summary["counts"]["cases"])
        self.assertEqual(0.5, summary["metrics"]["recallAtK"])
        self.assertEqual(0.5, summary["metrics"]["mrr"])
        self.assertEqual(0.5, summary["metrics"]["ndcgAtK"])
        self.assertEqual(0.5, summary["metrics"]["citationPrecision"])
        self.assertEqual(0.5, summary["metrics"]["citationRecall"])
        self.assertEqual(1.0, summary["metrics"]["refusalF1"])
        self.assertEqual(0.5, summary["metrics"]["scopeLeakageRate"])
        self.assertEqual(0.5, summary["metrics"]["forbiddenDocumentPassRate"])
        self.assertFalse(summary["qualityGate"]["passed"])
        self.assertIn("casePassRate", summary["qualityGate"]["failures"])
        self.assertEqual(
            "synthetic://global",
            summary["caseResults"][0]["evidenceRecords"][0]["sourceUri"],
        )
        self.assertEqual(0.9, summary["caseResults"][0]["evidenceRecords"][0]["confidence"])
        self.assertEqual("COMPLETE", summary["caseResults"][0]["evidenceRecords"][0]["sourceStatus"])
        serialized = json.dumps(summary, ensure_ascii=False)
        self.assertNotIn("问题正文 A", serialized)
        self.assertNotIn("问题正文 B", serialized)
        self.assertNotIn("问题正文 C", serialized)

    def test_quality_gate_skips_refusal_metric_when_subset_has_no_refusal_case(self) -> None:
        """小范围连通性评测不应因没有正类样本而把拒答 F1 判为失败。"""

        document = _document("doc-only", "synthetic://only", "*", "*", "*")
        dataset = RagEvaluationDataset(
            root=Path("synthetic-evaluation"),
            schema_version="datasmart.rag-evaluation-assets.v2",
            asset_boundary="synthetic-only",
            documents=(document,),
            cases=(
                RagGoldenCase(
                    case_id="case-only-answerable",
                    case_type="exact_error_code",
                    question="仅用于子集门禁的合成问题",
                    tenant_id="tenant-a",
                    project_id="project-a",
                    workspace_key="space-a",
                    retrieval_mode="lexical",
                    top_k=1,
                    relevant_documents=(RagExpectedDocument("doc-only", 3),),
                    expected_citation_uris=("synthetic://only",),
                    forbidden_document_ids=(),
                    should_refuse=False,
                    refusal_reason=None,
                    source_types=(),
                    tags=(),
                ),
            ),
            fingerprint="1" * 64,
        )
        report = RagEvaluationRunner(
            dataset,
            execute_query=lambda query: _result(_citation(document)),
        ).evaluate()

        self.assertEqual(0.0, report.metrics["refusalF1"])
        self.assertTrue(report.quality_gate_passed)
        self.assertNotIn("refusalF1", report.quality_gate_failures)

    def test_candidate_scope_leak_is_detected_before_final_citation(self) -> None:
        """越权候选即使未形成引用，也必须使候选级范围门禁失败。"""

        private_document = _document(
            "doc-tenant-b",
            "synthetic://tenant-b",
            "tenant-b",
            "project-b",
            "space-b",
        )
        golden_case = RagGoldenCase(
            case_id="candidate-scope-leak",
            case_type="cross_scope_refusal",
            question="仅在内存中使用的跨范围候选检查问题",
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="space-a",
            retrieval_mode="hybrid",
            top_k=3,
            relevant_documents=(),
            expected_citation_uris=(),
            forbidden_document_ids=(private_document.document_id,),
            should_refuse=True,
            refusal_reason="CROSS_SCOPE",
            source_types=(),
            tags=("范围",),
        )
        dataset = RagEvaluationDataset(
            root=Path("synthetic-evaluation"),
            schema_version="datasmart.rag-evaluation-assets.v2",
            asset_boundary="synthetic-only",
            documents=(private_document,),
            cases=(golden_case,),
            fingerprint="2" * 64,
        )
        leaked_candidate = RagScoredChunk(
            chunk=RagChunk(
                chunk_id="doc-tenant-b#chunk-1",
                document_id=private_document.document_id,
                chunk_index=0,
                title=private_document.title,
                text=private_document.content,
                source_uri=private_document.source_uri,
                tenant_id=private_document.tenant_id,
                project_id=private_document.project_id,
                workspace_key=private_document.workspace_key,
                source_type=private_document.source_type,
            ),
            final_score=0.9,
        )
        result = _result(retrieved_chunks=(leaked_candidate,), reranker_input_chunks=(leaked_candidate,))

        summary = RagEvaluationRunner(dataset, execute_query=lambda query: result).evaluate().to_summary()

        self.assertEqual(1.0, summary["metrics"]["scopeLeakageRate"])
        self.assertEqual(0.0, summary["metrics"]["forbiddenDocumentPassRate"])
        self.assertEqual(
            ("doc-tenant-b",),
            summary["caseResults"][0]["rerankerInputScopeLeakageDocumentIds"],
        )
        self.assertFalse(summary["caseResults"][0]["passed"])


def _document(
    document_id: str,
    source_uri: str,
    tenant_id: str,
    project_id: str,
    workspace_key: str,
) -> RagDocument:
    """构造指标测试所需的最小文档。"""

    return RagDocument(
        document_id=document_id,
        title=document_id,
        content=f"{document_id} 的合成证据。",
        source_uri=source_uri,
        tenant_id=tenant_id,
        project_id=project_id,
        workspace_key=workspace_key,
    )


def _copy_evaluation_assets(source_root: Path, copied_root: Path) -> None:
    """复制稳定评测提交物，忽略生成器的瞬态 staging 和 Python 缓存。

    CI 可能并行执行资产 ``--check`` 与加载器测试。主生成器会创建后立即删除 ``.staging``，若
    ``copytree`` 恰好遍历到其中会出现与产品代码无关的竞态；测试只需要最终提交物，因此明确忽略。
    """

    shutil.copytree(
        source_root,
        copied_root,
        ignore=shutil.ignore_patterns(".staging", "__pycache__", "*.pyc"),
    )


def _citation(document: RagDocument) -> RagCitation:
    """把测试文档转换为低敏引用。"""

    return RagCitation(
        citation_id="C1",
        document_id=document.document_id,
        chunk_id=f"{document.document_id}#chunk-1",
        title=document.title,
        source_uri=document.source_uri,
        snippet="合成证据",
        final_score=0.9,
    )


def _result(
    *citations: RagCitation,
    retrieved_chunks: tuple[RagScoredChunk, ...] = (),
    reranker_input_chunks: tuple[RagScoredChunk, ...] = (),
) -> RagPipelineResult:
    """构造不调用生成模型的评测结果。"""

    return RagPipelineResult(
        answer="",
        citations=tuple(citations),
        selected_chunks=(),
        compressed_context="",
        retrieval_summary={
            "evidenceRecords": tuple(
                {
                    "evidenceId": f"evidence:{citation.document_id}",
                    "citationId": citation.citation_id,
                    "documentId": citation.document_id,
                    "chunkId": citation.chunk_id,
                    "sourceType": "document",
                    "sourceRef": citation.source_uri,
                    "sourceUri": citation.source_uri,
                    "retrievedAt": "2026-08-15T00:00:00Z",
                    "finalScore": citation.final_score,
                    "confidence": citation.final_score,
                    "confidenceBasis": "TEST_SCORE",
                    "sourceStatus": "COMPLETE",
                    "sourceEffectiveAt": "2026-08-15T00:00:00Z",
                    "sourceConfidence": 0.95,
                    "sourceConfidenceBasis": "SYNTHETIC_TEST",
                }
                for citation in citations
            )
        },
        model_summary={"skipped": True},
        generated=False,
        retrieved_chunks=retrieved_chunks,
        reranker_input_chunks=reranker_input_chunks,
    )


if __name__ == "__main__":
    unittest.main()
