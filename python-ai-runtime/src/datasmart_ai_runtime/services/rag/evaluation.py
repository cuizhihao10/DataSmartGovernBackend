"""RAG 黄金评测集加载与低敏指标计算。

本模块把“评测数据是否可信”和“检索结果是否达标”拆成两个阶段：

1. 加载阶段验证 Manifest、异构原文件及提取文本 SHA-256、引用、来源类型和租户/项目/工作区范围；
2. 执行阶段只把问题保留在内存中调用 RAG 管线，报告仅保存 caseId、文档 ID、来源 URI、指标和异常类型。

这样既能重复评估 embedding、reranker 或检索参数，也不会把黄金问题、模型原文、文档正文和密钥复制到
评测报告。该模块不绑定具体模型 Provider；调用方可以注入纯词法管线、硅基流动 BGE 管线或后续企业网关。
"""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from time import perf_counter
from typing import Any, Callable, Mapping

from datasmart_ai_runtime.services.rag.models import (
    RagChunkSourceType,
    RagDocument,
    RagPipelineResult,
    RagQuery,
    RagScoredChunk,
)
from datasmart_ai_runtime.services.rag.document_extractor import (
    RAG_DOCUMENT_EXTRACTION_VERSION,
    SUPPORTED_RAG_DOCUMENT_SUFFIXES,
    RagDocumentExtractionError,
    extract_rag_document_bytes,
)


RAG_EVALUATION_ASSET_SCHEMA_VERSION = "datasmart.rag-evaluation-assets.v2"
RAG_EVALUATION_REPORT_SCHEMA_VERSION = "datasmart.rag-evaluation-report.v1"
RAG_EVALUATION_REPORT_PAYLOAD_POLICY = (
    "RAG_EVALUATION_IDS_METRICS_AND_SOURCE_URIS_NO_QUESTION_DOCUMENT_MODEL_BODY_OR_SECRET"
)

RagEvaluationExecutor = Callable[[RagQuery], RagPipelineResult]
_SYNTHETIC_INGEST_ALLOWED_RUNTIME_MODES = frozenset(
    {"local", "development", "dev", "test", "testing", "learning"}
)


class RagEvaluationDatasetError(ValueError):
    """评测资产不满足完整性、引用或范围合同时抛出的稳定异常。"""


def validate_synthetic_evaluation_ingest_runtime(runtime_mode: str) -> str:
    """确保纯合成黄金语料只能写入明确的非生产运行环境。

    ``--confirm-synthetic-evaluation-corpus`` 只能防止误操作，不能代表生产变更授权。这里再检查 Runtime
    模式，使生产、预发布和未声明模式即使拿到数据库 DSN 也会 fail-closed。返回规范化模式便于脚本输出
    或测试复用，但错误中不包含 DSN、表名、文档正文或凭据。
    """

    normalized = str(runtime_mode or "").strip().lower()
    if normalized not in _SYNTHETIC_INGEST_ALLOWED_RUNTIME_MODES:
        raise ValueError("RAG 合成评测语料只允许在明确的非生产 Runtime 模式摄取。")
    return normalized


@dataclass(frozen=True)
class RagExpectedDocument:
    """一份黄金相关文档及其离散相关性等级。"""

    document_id: str
    relevance: int


@dataclass(frozen=True)
class RagGoldenCase:
    """一条只在评测进程内保留问题正文的黄金用例。

    `relevant_documents` 用于 Recall/MRR/nDCG，`expected_citation_uris` 用于引用指标，
    `forbidden_document_ids` 同时保护租户隔离和过期证据抑制。`should_refuse` 表示在当前范围内没有可引用
    依据，不能让生成模型依靠常识补答。
    """

    case_id: str
    case_type: str
    question: str
    tenant_id: str
    project_id: str
    workspace_key: str
    retrieval_mode: str
    top_k: int
    relevant_documents: tuple[RagExpectedDocument, ...]
    expected_citation_uris: tuple[str, ...]
    forbidden_document_ids: tuple[str, ...]
    should_refuse: bool
    refusal_reason: str | None
    source_types: tuple[str, ...]
    tags: tuple[str, ...]


@dataclass(frozen=True)
class RagEvaluationDataset:
    """经过完整性校验、可以直接执行的评测数据集。"""

    root: Path
    schema_version: str
    asset_boundary: str
    documents: tuple[RagDocument, ...]
    cases: tuple[RagGoldenCase, ...]
    fingerprint: str


@dataclass(frozen=True)
class RagEvaluationThresholds:
    """企业 RAG 最小质量门槛。

    默认值是首轮工程基线，不代表所有客户语料都应使用同一数值。范围泄漏必须始终为零；其他阈值应在
    固定数据集、索引版本和模型版本上经过多轮测量后再由发布流程固化。
    """

    minimum_recall_at_k: float = 0.80
    minimum_mrr: float = 0.70
    minimum_ndcg_at_k: float = 0.75
    minimum_citation_precision: float = 0.90
    minimum_citation_recall: float = 0.80
    minimum_refusal_f1: float = 0.90
    minimum_forbidden_document_pass_rate: float = 1.0
    minimum_case_pass_rate: float = 0.85
    maximum_scope_leakage_rate: float = 0.0

    def to_summary(self) -> dict[str, float]:
        """输出稳定的 camelCase 门槛合同。"""

        return {
            "minimumRecallAtK": self.minimum_recall_at_k,
            "minimumMrr": self.minimum_mrr,
            "minimumNdcgAtK": self.minimum_ndcg_at_k,
            "minimumCitationPrecision": self.minimum_citation_precision,
            "minimumCitationRecall": self.minimum_citation_recall,
            "minimumRefusalF1": self.minimum_refusal_f1,
            "minimumForbiddenDocumentPassRate": self.minimum_forbidden_document_pass_rate,
            "minimumCasePassRate": self.minimum_case_pass_rate,
            "maximumScopeLeakageRate": self.maximum_scope_leakage_rate,
        }


@dataclass(frozen=True)
class RagEvaluationRunProfile:
    """报告中允许持久化的模型与检索配置摘要。"""

    profile_name: str = "unspecified"
    retrieval_backend: str = "in-memory"
    embedding_model: str | None = None
    reranker_model: str | None = None
    generation_enabled: bool = False

    def to_summary(self) -> dict[str, Any]:
        """仅输出模型名和逻辑后端，不输出 Endpoint、凭据或环境变量。"""

        return {
            "profileName": self.profile_name,
            "retrievalBackend": self.retrieval_backend,
            "embeddingModel": self.embedding_model,
            "rerankerModel": self.reranker_model,
            "generationEnabled": self.generation_enabled,
        }


@dataclass(frozen=True)
class RagEvaluationReport:
    """不含问题和正文的 RAG 评测报告。"""

    dataset_fingerprint: str
    generated_at: str
    duration_ms: int
    counts: Mapping[str, int]
    metrics: Mapping[str, float | int]
    thresholds: RagEvaluationThresholds
    run_profile: RagEvaluationRunProfile
    quality_gate_passed: bool
    quality_gate_failures: tuple[str, ...]
    case_results: tuple[Mapping[str, Any], ...]

    def to_summary(self) -> dict[str, Any]:
        """生成可以写入 JSON 的低敏摘要。"""

        return {
            "schemaVersion": RAG_EVALUATION_REPORT_SCHEMA_VERSION,
            "datasetFingerprint": self.dataset_fingerprint,
            "generatedAt": self.generated_at,
            "durationMs": self.duration_ms,
            "counts": dict(self.counts),
            "metrics": dict(self.metrics),
            "runProfile": self.run_profile.to_summary(),
            "qualityGate": {
                "passed": self.quality_gate_passed,
                "failures": self.quality_gate_failures,
                "thresholds": self.thresholds.to_summary(),
            },
            "caseResults": tuple(dict(item) for item in self.case_results),
            "payloadPolicy": RAG_EVALUATION_REPORT_PAYLOAD_POLICY,
        }


class RagEvaluationRunner:
    """逐条执行黄金用例并计算检索、引用、拒答和隔离指标。"""

    def __init__(
        self,
        dataset: RagEvaluationDataset,
        *,
        execute_query: RagEvaluationExecutor,
        thresholds: RagEvaluationThresholds | None = None,
        run_profile: RagEvaluationRunProfile | None = None,
    ) -> None:
        """保存已校验数据集与管线调用入口，不在对象中缓存模型响应正文。"""

        self._dataset = dataset
        self._execute_query = execute_query
        self._thresholds = thresholds or RagEvaluationThresholds()
        self._run_profile = run_profile or RagEvaluationRunProfile()
        self._documents_by_id = {document.document_id: document for document in dataset.documents}

    def evaluate(self) -> RagEvaluationReport:
        """执行全部用例并返回低敏报告。

        单条 Provider 或检索异常不会中止整套评测：该用例按失败计分，报告只记录异常类名。这样可以区分
        “模型质量不达标”和“供应商/协议不稳定”，同时避免 HTTP body、Endpoint 或问题文本进入产物。
        """

        started_at = perf_counter()
        case_results: list[dict[str, Any]] = []
        recall_values: list[float] = []
        reciprocal_ranks: list[float] = []
        ndcg_values: list[float] = []
        citation_precision_values: list[float] = []
        citation_recall_values: list[float] = []
        latencies: list[int] = []
        total_observed_documents = 0
        scope_leakage_documents = 0
        forbidden_case_count = 0
        forbidden_pass_count = 0
        stale_case_count = 0
        stale_pass_count = 0
        true_refusal = 0
        false_refusal = 0
        missed_refusal = 0
        correct_answerable = 0
        execution_errors = 0

        for golden_case in self._dataset.cases:
            case_started_at = perf_counter()
            error_type: str | None = None
            result: RagPipelineResult | None = None
            try:
                result = self._execute_query(self._query_for_case(golden_case))
            except Exception as exc:  # noqa: BLE001 - 评测必须继续，其内容不能进入报告。
                error_type = type(exc).__name__
                execution_errors += 1
            latency_ms = max(0, int((perf_counter() - case_started_at) * 1000))
            latencies.append(latency_ms)

            citations = tuple(result.citations) if result is not None else ()
            evidence_records = _safe_evidence_records(result)
            actual_document_ids = _unique_tuple(citation.document_id for citation in citations)
            retrieved_document_ids = _stage_document_ids(result, "retrieved_chunks")
            reranker_input_document_ids = _stage_document_ids(result, "reranker_input_chunks")
            observed_document_ids = _unique_tuple(
                (*retrieved_document_ids, *reranker_input_document_ids, *actual_document_ids)
            )
            actual_citation_uris = _unique_tuple(citation.source_uri for citation in citations)
            expected_relevance = {
                item.document_id: item.relevance for item in golden_case.relevant_documents
            }
            expected_document_ids = tuple(expected_relevance)
            expected_document_set = set(expected_document_ids)
            expected_uri_set = set(golden_case.expected_citation_uris)
            actual_document_set = set(actual_document_ids)
            actual_uri_set = set(actual_citation_uris)
            relevant_hits = tuple(
                document_id for document_id in actual_document_ids if document_id in expected_document_set
            )
            forbidden_hits = tuple(
                document_id
                for document_id in observed_document_ids
                if document_id in set(golden_case.forbidden_document_ids)
            )
            scope_leakage_ids = tuple(
                document_id
                for document_id in observed_document_ids
                if not self._document_visible(document_id, golden_case)
            )
            reranker_scope_leakage_ids = tuple(
                document_id
                for document_id in reranker_input_document_ids
                if not self._document_visible(document_id, golden_case)
            )
            total_observed_documents += len(observed_document_ids)
            scope_leakage_documents += len(scope_leakage_ids)

            completed = error_type is None
            actual_refusal = completed and not actual_document_ids
            if golden_case.should_refuse:
                if actual_refusal:
                    true_refusal += 1
                else:
                    missed_refusal += 1
            elif actual_refusal:
                false_refusal += 1
            elif completed:
                correct_answerable += 1

            if not golden_case.should_refuse:
                recall_values.append(
                    len(set(relevant_hits)) / len(expected_document_set)
                    if expected_document_set
                    else 0.0
                )
                reciprocal_ranks.append(_reciprocal_rank(actual_document_ids, expected_document_set))
                ndcg_values.append(_ndcg_at_k(actual_document_ids, expected_relevance, golden_case.top_k))
                citation_precision_values.append(
                    len(actual_uri_set & expected_uri_set) / len(actual_uri_set)
                    if actual_uri_set
                    else 0.0
                )
                citation_recall_values.append(
                    len(actual_uri_set & expected_uri_set) / len(expected_uri_set)
                    if expected_uri_set
                    else 0.0
                )

            if golden_case.forbidden_document_ids:
                forbidden_case_count += 1
                if not forbidden_hits:
                    forbidden_pass_count += 1
            if golden_case.case_type == "stale_conflict":
                stale_case_count += 1
                if not forbidden_hits:
                    stale_pass_count += 1

            expected_evidence_complete = expected_document_set.issubset(actual_document_set)
            citations_exact = actual_uri_set == expected_uri_set
            case_passed = (
                completed
                and not forbidden_hits
                and not scope_leakage_ids
                and (
                    actual_refusal
                    if golden_case.should_refuse
                    else (not actual_refusal and expected_evidence_complete and citations_exact)
                )
            )
            case_results.append(
                {
                    "caseId": golden_case.case_id,
                    "caseType": golden_case.case_type,
                    "requestedRetrievalMode": golden_case.retrieval_mode,
                    "resolvedRetrievalMode": _runtime_retrieval_mode(golden_case.retrieval_mode),
                    "topK": golden_case.top_k,
                    "expectedRefusal": golden_case.should_refuse,
                    "actualRefusal": actual_refusal,
                    "expectedDocumentIds": expected_document_ids,
                    "actualDocumentIds": actual_document_ids,
                    "expectedCitationUris": golden_case.expected_citation_uris,
                    "actualCitationUris": actual_citation_uris,
                    "relevantHitDocumentIds": relevant_hits,
                    "forbiddenDocumentIds": golden_case.forbidden_document_ids,
                    "forbiddenHitDocumentIds": forbidden_hits,
                    "retrievedDocumentIds": retrieved_document_ids,
                    "rerankerInputDocumentIds": reranker_input_document_ids,
                    "scopeLeakageDocumentIds": scope_leakage_ids,
                    "rerankerInputScopeLeakageDocumentIds": reranker_scope_leakage_ids,
                    "evidenceRecords": evidence_records,
                    "firstRelevantRank": _first_relevant_rank(actual_document_ids, expected_document_set),
                    "latencyMs": latency_ms,
                    "executionErrorType": error_type,
                    "passed": case_passed,
                }
            )

        refusal_precision = _safe_ratio(true_refusal, true_refusal + false_refusal)
        refusal_recall = _safe_ratio(true_refusal, true_refusal + missed_refusal)
        refusal_f1 = _f1(refusal_precision, refusal_recall)
        metrics: dict[str, float | int] = {
            "recallAtK": _rounded_mean(recall_values),
            "mrr": _rounded_mean(reciprocal_ranks),
            "ndcgAtK": _rounded_mean(ndcg_values),
            "citationPrecision": _rounded_mean(citation_precision_values),
            "citationRecall": _rounded_mean(citation_recall_values),
            "refusalPrecision": round(refusal_precision, 6),
            "refusalRecall": round(refusal_recall, 6),
            "refusalF1": round(refusal_f1, 6),
            "refusalAccuracy": round(
                _safe_ratio(
                    true_refusal + correct_answerable,
                    len(self._dataset.cases),
                ),
                6,
            ),
            "scopeLeakageRate": round(
                _safe_ratio(scope_leakage_documents, total_observed_documents),
                6,
            ),
            "forbiddenDocumentPassRate": round(
                _safe_ratio(forbidden_pass_count, forbidden_case_count, empty_value=1.0),
                6,
            ),
            "staleEvidenceSuppressionRate": round(
                _safe_ratio(stale_pass_count, stale_case_count, empty_value=1.0),
                6,
            ),
            "casePassRate": round(
                _safe_ratio(sum(1 for item in case_results if item["passed"]), len(case_results)),
                6,
            ),
            "latencyP50Ms": _percentile(latencies, 0.50),
            "latencyP95Ms": _percentile(latencies, 0.95),
        }
        answerable_case_count = sum(1 for item in self._dataset.cases if not item.should_refuse)
        refusal_case_count = len(self._dataset.cases) - answerable_case_count
        failures = _quality_gate_failures(
            metrics,
            self._thresholds,
            answerable_case_count=answerable_case_count,
            refusal_case_count=refusal_case_count,
            forbidden_case_count=forbidden_case_count,
        )
        duration_ms = max(0, int((perf_counter() - started_at) * 1000))
        return RagEvaluationReport(
            dataset_fingerprint=self._dataset.fingerprint,
            generated_at=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            duration_ms=duration_ms,
            counts={
                "documents": len(self._dataset.documents),
                "cases": len(self._dataset.cases),
                "answerableCases": answerable_case_count,
                "refusalCases": refusal_case_count,
                "executionErrors": execution_errors,
                "passedCases": sum(1 for item in case_results if item["passed"]),
            },
            metrics=metrics,
            thresholds=self._thresholds,
            run_profile=self._run_profile,
            quality_gate_passed=not failures and execution_errors == 0,
            quality_gate_failures=(
                failures + (("executionErrors",) if execution_errors else ())
            ),
            case_results=tuple(case_results),
        )

    @staticmethod
    def _query_for_case(golden_case: RagGoldenCase) -> RagQuery:
        """把黄金用例转换为禁止生成答案的运行时查询。"""

        return RagQuery(
            tenant_id=golden_case.tenant_id,
            project_id=golden_case.project_id,
            workspace_key=golden_case.workspace_key,
            actor_id="rag-evaluation-runner",
            question=golden_case.question,
            top_k=golden_case.top_k,
            candidate_limit=max(32, min(200, golden_case.top_k * 8)),
            generate_answer=False,
            trace_id=f"rag-eval:{golden_case.case_id}",
            retrieval_mode=_runtime_retrieval_mode(golden_case.retrieval_mode),
            source_types=golden_case.source_types,
        )

    def _document_visible(self, document_id: str, golden_case: RagGoldenCase) -> bool:
        """复核最终引用仍满足硬范围，未知文档 ID 也按泄漏处理。"""

        document = self._documents_by_id.get(document_id)
        if document is None:
            return False
        return _scope_value_visible(document.tenant_id, golden_case.tenant_id) and _scope_value_visible(
            document.project_id,
            golden_case.project_id,
        ) and _scope_value_visible(document.workspace_key, golden_case.workspace_key)


def load_rag_evaluation_dataset(root: str | Path) -> RagEvaluationDataset:
    """从目录加载、验证并映射 RAG 评测资产。

    文件路径必须留在评测根目录内，原始文件字节与提取文本必须分别匹配 Manifest SHA-256，所有黄金
    文档和引用都必须存在且可被用例范围访问。任何一项不满足时整体拒绝，避免在错误基准上得到看似
    精确的指标。DOCX/XLSX 在这里会经过受限 OOXML 提取器，不执行宏、公式或外部关系。
    """

    resolved_root = Path(root).resolve()
    manifest_path = resolved_root / "manifest.json"
    cases_path = resolved_root / "golden_cases.jsonl"
    try:
        manifest_bytes = manifest_path.read_bytes()
        cases_bytes = cases_path.read_bytes()
        manifest = json.loads(manifest_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RagEvaluationDatasetError("RAG 评测 Manifest 无法读取或解析。") from exc
    if not isinstance(manifest, Mapping):
        raise RagEvaluationDatasetError("RAG 评测 Manifest 根节点必须是对象。")
    schema_version = _required_text(manifest.get("schemaVersion"), "schemaVersion")
    if schema_version != RAG_EVALUATION_ASSET_SCHEMA_VERSION:
        raise RagEvaluationDatasetError("RAG 评测资产 schemaVersion 不受支持。")
    asset_boundary = _required_text(manifest.get("assetBoundary"), "assetBoundary")
    raw_documents = manifest.get("documents")
    if not isinstance(raw_documents, list) or not raw_documents:
        raise RagEvaluationDatasetError("RAG 评测 Manifest 必须包含非空 documents 数组。")

    documents: list[RagDocument] = []
    documents_by_id: dict[str, RagDocument] = {}
    documents_by_uri: dict[str, RagDocument] = {}
    for raw_document in raw_documents:
        if not isinstance(raw_document, Mapping):
            raise RagEvaluationDatasetError("RAG 评测文档条目必须是对象。")
        document_id = _required_text(raw_document.get("documentId"), "documentId")
        relative_path = Path(_required_text(raw_document.get("path"), "path"))
        content_path = (resolved_root / relative_path).resolve()
        if (
            not content_path.is_relative_to(resolved_root)
            or content_path.suffix.lower() not in SUPPORTED_RAG_DOCUMENT_SUFFIXES
        ):
            raise RagEvaluationDatasetError(f"RAG 评测文档路径越界或扩展名非法：{document_id}")
        try:
            content_bytes = content_path.read_bytes()
        except OSError as exc:
            raise RagEvaluationDatasetError(f"RAG 评测文档无法读取：{document_id}") from exc
        expected_hash = _required_text(raw_document.get("contentSha256"), "contentSha256")
        if hashlib.sha256(content_bytes).hexdigest() != expected_hash:
            raise RagEvaluationDatasetError(f"RAG 评测文档哈希不匹配：{document_id}")
        try:
            extracted = extract_rag_document_bytes(content_bytes, content_path.suffix)
        except RagDocumentExtractionError as exc:
            raise RagEvaluationDatasetError(f"RAG 评测文档内容无法安全提取：{document_id}") from exc
        content = extracted.content
        expected_extracted_hash = _required_text(
            raw_document.get("extractedTextSha256"),
            "extractedTextSha256",
        )
        if hashlib.sha256(content.encode("utf-8")).hexdigest() != expected_extracted_hash:
            raise RagEvaluationDatasetError(f"RAG 评测文档提取文本哈希不匹配：{document_id}")
        declared_format = _required_text(raw_document.get("contentFormat"), "contentFormat")
        declared_media_type = _required_text(raw_document.get("mediaType"), "mediaType")
        if declared_format != extracted.format_name or declared_media_type != extracted.media_type:
            raise RagEvaluationDatasetError(f"RAG 评测文档格式声明不匹配：{document_id}")
        try:
            source_type = RagChunkSourceType(
                _required_text(raw_document.get("sourceType"), "sourceType")
            )
        except ValueError as exc:
            raise RagEvaluationDatasetError(f"RAG 评测文档来源类型不受支持：{document_id}") from exc
        metadata = raw_document.get("metadata")
        if not isinstance(metadata, Mapping):
            raise RagEvaluationDatasetError(f"RAG 评测文档 metadata 必须是对象：{document_id}")
        _validate_source_evidence_metadata(metadata, document_id=document_id)
        source_uri = _required_text(raw_document.get("sourceUri"), "sourceUri")
        if document_id in documents_by_id or source_uri in documents_by_uri:
            raise RagEvaluationDatasetError("RAG 评测文档 ID 或 sourceUri 重复。")
        document = RagDocument(
            document_id=document_id,
            title=_required_text(raw_document.get("title"), "title"),
            content=content,
            source_uri=source_uri,
            tenant_id=_required_text(raw_document.get("tenantId"), "tenantId"),
            project_id=_required_text(raw_document.get("projectId"), "projectId"),
            workspace_key=_required_text(raw_document.get("workspaceKey"), "workspaceKey"),
            source_type=source_type,
            tags=_text_tuple(raw_document.get("tags"), "tags"),
            sensitivity_level=_required_text(
                raw_document.get("sensitivityLevel"),
                "sensitivityLevel",
            ),
            metadata={
                **dict(metadata),
                "contentFormat": extracted.format_name,
                "mediaType": extracted.media_type,
                "extractionVersion": RAG_DOCUMENT_EXTRACTION_VERSION,
                "sheetCount": extracted.sheet_count,
            },
            enabled=_required_boolean(raw_document.get("enabled", True), "enabled"),
        )
        documents.append(document)
        documents_by_id[document_id] = document
        documents_by_uri[source_uri] = document

    cases = _load_golden_cases(cases_bytes, documents_by_id, documents_by_uri)
    fingerprint = hashlib.sha256(manifest_bytes + b"\0" + cases_bytes).hexdigest()
    return RagEvaluationDataset(
        root=resolved_root,
        schema_version=schema_version,
        asset_boundary=asset_boundary,
        documents=tuple(documents),
        cases=cases,
        fingerprint=fingerprint,
    )


def _load_golden_cases(
    cases_bytes: bytes,
    documents_by_id: Mapping[str, RagDocument],
    documents_by_uri: Mapping[str, RagDocument],
) -> tuple[RagGoldenCase, ...]:
    """解析 JSONL，并验证期望证据、拒答和作用域合同。"""

    try:
        lines = cases_bytes.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise RagEvaluationDatasetError("RAG 黄金用例不是有效 UTF-8。") from exc
    cases: list[RagGoldenCase] = []
    seen_case_ids: set[str] = set()
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            raw_case = json.loads(line)
        except json.JSONDecodeError as exc:
            raise RagEvaluationDatasetError(f"RAG 黄金用例第 {line_number} 行不是有效 JSON。") from exc
        if not isinstance(raw_case, Mapping):
            raise RagEvaluationDatasetError(f"RAG 黄金用例第 {line_number} 行必须是对象。")
        case_id = _required_text(raw_case.get("caseId"), "caseId")
        if case_id in seen_case_ids:
            raise RagEvaluationDatasetError(f"RAG 黄金 caseId 重复：{case_id}")
        seen_case_ids.add(case_id)
        scope = raw_case.get("scope")
        if not isinstance(scope, Mapping):
            raise RagEvaluationDatasetError(f"RAG 黄金用例 scope 必须是对象：{case_id}")
        tenant_id = _required_text(scope.get("tenantId"), "scope.tenantId")
        project_id = _required_text(scope.get("projectId"), "scope.projectId")
        workspace_key = _required_text(scope.get("workspaceKey"), "scope.workspaceKey")
        retrieval_mode = _required_text(raw_case.get("retrievalMode"), "retrievalMode").lower()
        if retrieval_mode not in {"hybrid", "lexical", "vector", "exact_search"}:
            raise RagEvaluationDatasetError(f"RAG 黄金检索模式不受支持：{case_id}")
        top_k = _required_integer(raw_case.get("topK"), "topK", case_id=case_id)
        if top_k < 1 or top_k > 20:
            raise RagEvaluationDatasetError(f"RAG 黄金 topK 必须在 1 到 20 之间：{case_id}")

        raw_relevant = raw_case.get("relevantDocuments")
        if not isinstance(raw_relevant, list):
            raise RagEvaluationDatasetError(f"RAG 黄金 relevantDocuments 必须是数组：{case_id}")
        relevant_documents: list[RagExpectedDocument] = []
        for raw_reference in raw_relevant:
            if not isinstance(raw_reference, Mapping):
                raise RagEvaluationDatasetError(f"RAG 黄金相关文档条目非法：{case_id}")
            document_id = _required_text(raw_reference.get("documentId"), "documentId")
            relevance = _required_integer(
                raw_reference.get("relevance"),
                "相关性等级",
                case_id=case_id,
            )
            document = documents_by_id.get(document_id)
            if document is None or relevance not in {1, 2, 3}:
                raise RagEvaluationDatasetError(f"RAG 黄金相关文档或等级不存在：{case_id}")
            if not _document_matches_scope(document, tenant_id, project_id, workspace_key):
                raise RagEvaluationDatasetError(f"RAG 黄金相关文档超出用例范围：{case_id}")
            relevant_documents.append(RagExpectedDocument(document_id, relevance))
        if len({item.document_id for item in relevant_documents}) != len(relevant_documents):
            raise RagEvaluationDatasetError(f"RAG 黄金相关文档重复：{case_id}")

        expected_uris = _text_tuple(raw_case.get("expectedCitationUris"), "expectedCitationUris")
        if any(uri not in documents_by_uri for uri in expected_uris):
            raise RagEvaluationDatasetError(f"RAG 黄金期望引用不存在：{case_id}")
        relevant_uris = {
            documents_by_id[item.document_id].source_uri for item in relevant_documents
        }
        if set(expected_uris) != relevant_uris:
            raise RagEvaluationDatasetError(f"RAG 黄金期望引用与相关文档不一致：{case_id}")
        forbidden_ids = _text_tuple(raw_case.get("forbiddenDocumentIds"), "forbiddenDocumentIds")
        if any(document_id not in documents_by_id for document_id in forbidden_ids):
            raise RagEvaluationDatasetError(f"RAG 黄金禁止文档不存在：{case_id}")
        if set(forbidden_ids) & {item.document_id for item in relevant_documents}:
            raise RagEvaluationDatasetError(f"RAG 黄金文档不能同时相关和禁止：{case_id}")

        should_refuse = _required_boolean(
            raw_case.get("shouldRefuse"),
            "shouldRefuse",
            case_id=case_id,
        )
        refusal_reason_raw = raw_case.get("refusalReason")
        refusal_reason = str(refusal_reason_raw).strip() if refusal_reason_raw is not None else None
        if should_refuse and (relevant_documents or expected_uris or not refusal_reason):
            raise RagEvaluationDatasetError(f"RAG 黄金拒答合同不完整：{case_id}")
        if not should_refuse and not relevant_documents:
            raise RagEvaluationDatasetError(f"RAG 可回答用例必须声明相关文档：{case_id}")
        source_types = _text_tuple(raw_case.get("sourceTypes"), "sourceTypes")
        if any(value not in {item.value for item in RagChunkSourceType} for value in source_types):
            raise RagEvaluationDatasetError(f"RAG 黄金 sourceTypes 不受支持：{case_id}")
        cases.append(
            RagGoldenCase(
                case_id=case_id,
                case_type=_required_text(raw_case.get("caseType"), "caseType"),
                question=_required_text(raw_case.get("question"), "question"),
                tenant_id=tenant_id,
                project_id=project_id,
                workspace_key=workspace_key,
                retrieval_mode=retrieval_mode,
                top_k=top_k,
                relevant_documents=tuple(relevant_documents),
                expected_citation_uris=expected_uris,
                forbidden_document_ids=forbidden_ids,
                should_refuse=should_refuse,
                refusal_reason=refusal_reason,
                source_types=source_types,
                tags=_text_tuple(raw_case.get("tags"), "tags"),
            )
        )
    if not cases:
        raise RagEvaluationDatasetError("RAG 黄金用例不能为空。")
    return tuple(cases)


def _quality_gate_failures(
    metrics: Mapping[str, float | int],
    thresholds: RagEvaluationThresholds,
    *,
    answerable_case_count: int,
    refusal_case_count: int,
    forbidden_case_count: int,
) -> tuple[str, ...]:
    """比较适用于当前子集的指标与门槛，返回稳定指标名。

    例如只跑一条语义改写连通性用例时没有拒答正类，`refusalF1=0` 只是“不可计算”的占位值，不能作为
    门禁失败；完整黄金集合包含拒答样本时，该门槛会自动恢复。
    """

    checks = (
        (
            "recallAtK",
            not answerable_case_count
            or float(metrics["recallAtK"]) >= thresholds.minimum_recall_at_k,
        ),
        ("mrr", not answerable_case_count or float(metrics["mrr"]) >= thresholds.minimum_mrr),
        (
            "ndcgAtK",
            not answerable_case_count or float(metrics["ndcgAtK"]) >= thresholds.minimum_ndcg_at_k,
        ),
        (
            "citationPrecision",
            not answerable_case_count
            or float(metrics["citationPrecision"]) >= thresholds.minimum_citation_precision,
        ),
        (
            "citationRecall",
            not answerable_case_count
            or float(metrics["citationRecall"]) >= thresholds.minimum_citation_recall,
        ),
        (
            "refusalF1",
            not refusal_case_count or float(metrics["refusalF1"]) >= thresholds.minimum_refusal_f1,
        ),
        (
            "forbiddenDocumentPassRate",
            not forbidden_case_count
            or float(metrics["forbiddenDocumentPassRate"])
            >= thresholds.minimum_forbidden_document_pass_rate,
        ),
        (
            "scopeLeakageRate",
            float(metrics["scopeLeakageRate"]) <= thresholds.maximum_scope_leakage_rate,
        ),
        (
            "casePassRate",
            float(metrics["casePassRate"]) >= thresholds.minimum_case_pass_rate,
        ),
    )
    return tuple(metric for metric, passed in checks if not passed)


def _safe_evidence_records(result: RagPipelineResult | None) -> tuple[dict[str, Any], ...]:
    """从管线摘要提取来源、时间和可信度，不复制查询摘要或正文。"""

    if result is None or not isinstance(result.retrieval_summary, Mapping):
        return ()
    raw_records = result.retrieval_summary.get("evidenceRecords")
    if not isinstance(raw_records, (list, tuple)):
        return ()
    safe_records: list[dict[str, Any]] = []
    for raw_record in raw_records:
        if not isinstance(raw_record, Mapping):
            continue
        safe_records.append(
            {
                "evidenceId": _bounded_optional_text(raw_record.get("evidenceId"), 256),
                "citationId": _bounded_optional_text(raw_record.get("citationId"), 64),
                "documentId": _bounded_optional_text(raw_record.get("documentId"), 256),
                "chunkId": _bounded_optional_text(raw_record.get("chunkId"), 256),
                "sourceType": _bounded_optional_text(raw_record.get("sourceType"), 64),
                "sourceRef": _bounded_optional_text(raw_record.get("sourceRef"), 2000),
                "sourceUri": _bounded_optional_text(raw_record.get("sourceUri"), 2000),
                "retrievedAt": _bounded_optional_text(raw_record.get("retrievedAt"), 64),
                "finalScore": _finite_optional_float(raw_record.get("finalScore")),
                "confidence": _finite_optional_float(raw_record.get("confidence")),
                "confidenceBasis": _bounded_optional_text(
                    raw_record.get("confidenceBasis"),
                    128,
                ),
                "sourceStatus": _bounded_optional_text(raw_record.get("sourceStatus"), 64),
                "sourceEffectiveAt": _bounded_optional_text(
                    raw_record.get("sourceEffectiveAt"),
                    64,
                ),
                "sourceConfidence": _finite_optional_float(
                    raw_record.get("sourceConfidence")
                ),
                "sourceConfidenceBasis": _bounded_optional_text(
                    raw_record.get("sourceConfidenceBasis"),
                    128,
                ),
            }
        )
    return tuple(safe_records)


def _stage_document_ids(
    result: RagPipelineResult | None,
    field_name: str,
) -> tuple[str, ...]:
    """提取内部阶段快照的文档 ID，不复制标题、片段、查询或模型正文。

    评测器只允许读取 ``retrieved_chunks`` 和 ``reranker_input_chunks`` 两个固定字段，避免调用方用任意
    属性名扩大低敏报告范围。未知对象或非法条目按空集合处理，实际越权候选则会通过稳定文档 ID 进入
    scope/forbidden 指标。
    """

    if result is None or field_name not in {"retrieved_chunks", "reranker_input_chunks"}:
        return ()
    raw_chunks = getattr(result, field_name, ())
    if not isinstance(raw_chunks, (list, tuple)):
        return ()
    return _unique_tuple(
        str(item.chunk.document_id).strip()
        for item in raw_chunks
        if isinstance(item, RagScoredChunk) and str(item.chunk.document_id).strip()
    )


def _runtime_retrieval_mode(value: str) -> str:
    """把黄金集的精确检索语义映射到当前词法通道。"""

    normalized = str(value or "hybrid").strip().lower()
    if normalized == "exact_search":
        return "lexical"
    return normalized if normalized in {"hybrid", "lexical", "vector"} else "hybrid"


def _document_matches_scope(
    document: RagDocument,
    tenant_id: str,
    project_id: str,
    workspace_key: str,
) -> bool:
    """判断文档能否被给定范围访问。"""

    return (
        _scope_value_visible(document.tenant_id, tenant_id)
        and _scope_value_visible(document.project_id, project_id)
        and _scope_value_visible(document.workspace_key, workspace_key)
    )


def _scope_value_visible(document_value: str, query_value: str) -> bool:
    """实现公共 `*` 或精确匹配的单维范围规则。"""

    return document_value in {"*", query_value}


def _required_text(value: Any, field_name: str) -> str:
    """读取必填非空文本。"""

    normalized = str(value or "").strip()
    if not normalized:
        raise RagEvaluationDatasetError(f"RAG 评测字段不能为空：{field_name}")
    return normalized


def _required_boolean(value: Any, field_name: str, *, case_id: str | None = None) -> bool:
    """读取严格 JSON 布尔值，拒绝容易误判的字符串和数字。

    Python 的 ``bool("false")`` 会得到 ``True``，这对拒答标签和文档启用状态非常危险。评测资产是
    机器可执行合同，因此这里只接受 JSON 解析后真正的 ``bool``，并把用例 ID 放入错误信息帮助定位。
    """

    if type(value) is not bool:
        suffix = f"：{case_id}" if case_id else ""
        raise RagEvaluationDatasetError(f"RAG 评测字段 {field_name} 必须是布尔值{suffix}")
    return value


def _required_integer(value: Any, field_name: str, *, case_id: str) -> int:
    """读取严格 JSON 整数，防止小数经 ``int`` 截断后改变评测语义。"""

    if isinstance(value, bool) or not isinstance(value, int):
        raise RagEvaluationDatasetError(f"RAG 黄金 {field_name} 必须是整数：{case_id}")
    return value


def _validate_source_evidence_metadata(
    metadata: Mapping[str, Any],
    *,
    document_id: str,
) -> None:
    """校验黄金文档的来源状态、时间和可信度合同。

    Markdown 的 SHA-256 只能证明正文没有被悄悄修改，不能证明 Manifest 中描述来源质量的字段可靠。
    如果加载器接受 ``sourceConfidence=1.5``、无时区时间或“现行/已替代”互相矛盾的状态，后续评测
    报告仍会生成漂亮但无意义的证据记录。因此这些字段必须在进入运行时对象前独立校验。

    这里验证的是合成黄金资产的强合同，不会把所有生产知识文档都强迫为同一套状态。普通运行时文档
    缺少这些字段时，RAG 管线仍会明确返回 ``UNSPECIFIED``，由摄取治理流程决定是否允许其参与生产问答。
    """

    source_status = str(metadata.get("sourceStatus") or "").strip().upper()
    evidence_status = str(metadata.get("evidenceStatus") or "").strip().lower()
    expected_evidence_status = {
        "COMPLETE": "current",
        "SUPERSEDED": "superseded",
    }.get(source_status)
    if expected_evidence_status is None or evidence_status != expected_evidence_status:
        raise RagEvaluationDatasetError(
            f"RAG 评测文档来源证据 metadata 状态非法或互相矛盾：{document_id}"
        )

    effective_at = str(metadata.get("effectiveAt") or "").strip()
    try:
        parsed_effective_at = datetime.fromisoformat(effective_at.replace("Z", "+00:00"))
    except ValueError as exc:
        raise RagEvaluationDatasetError(
            f"RAG 评测文档来源证据 metadata 生效时间非法：{document_id}"
        ) from exc
    if parsed_effective_at.tzinfo is None:
        raise RagEvaluationDatasetError(
            f"RAG 评测文档来源证据 metadata 生效时间必须带时区：{document_id}"
        )

    try:
        source_confidence = float(metadata.get("sourceConfidence"))
    except (TypeError, ValueError) as exc:
        raise RagEvaluationDatasetError(
            f"RAG 评测文档来源证据 metadata 可信度非法：{document_id}"
        ) from exc
    if not math.isfinite(source_confidence) or not 0.0 <= source_confidence <= 1.0:
        raise RagEvaluationDatasetError(
            f"RAG 评测文档来源证据 metadata 可信度必须位于 0 到 1：{document_id}"
        )
    _required_text(metadata.get("sourceConfidenceBasis"), "metadata.sourceConfidenceBasis")


def _bounded_optional_text(value: Any, maximum_chars: int) -> str | None:
    """把可选诊断字段限制在固定字符预算内。"""

    if value is None:
        return None
    normalized = str(value).strip()
    return normalized[:maximum_chars] if normalized else None


def _finite_optional_float(value: Any) -> float | None:
    """只保留有限浮点分数，非法值在报告中记为 null。"""

    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return round(parsed, 6) if math.isfinite(parsed) else None


def _text_tuple(value: Any, field_name: str) -> tuple[str, ...]:
    """读取不允许空元素和重复值的文本数组。"""

    if not isinstance(value, (list, tuple)):
        raise RagEvaluationDatasetError(f"RAG 评测字段必须是数组：{field_name}")
    normalized = tuple(str(item).strip() for item in value)
    if any(not item for item in normalized) or len(set(normalized)) != len(normalized):
        raise RagEvaluationDatasetError(f"RAG 评测数组包含空值或重复值：{field_name}")
    return normalized


def _unique_tuple(values: Any) -> tuple[str, ...]:
    """按首次出现顺序去重字符串。"""

    return tuple(dict.fromkeys(str(value) for value in values))


def _first_relevant_rank(actual_ids: tuple[str, ...], expected_ids: set[str]) -> int | None:
    """返回第一个相关文档的一基排名。"""

    for index, document_id in enumerate(actual_ids, start=1):
        if document_id in expected_ids:
            return index
    return None


def _reciprocal_rank(actual_ids: tuple[str, ...], expected_ids: set[str]) -> float:
    """计算单条用例的倒数排名。"""

    rank = _first_relevant_rank(actual_ids, expected_ids)
    return 1.0 / rank if rank is not None else 0.0


def _ndcg_at_k(
    actual_ids: tuple[str, ...],
    expected_relevance: Mapping[str, int],
    top_k: int,
) -> float:
    """按三级相关性计算 nDCG@K。"""

    gains = [expected_relevance.get(document_id, 0) for document_id in actual_ids[:top_k]]
    dcg = sum((2**gain - 1) / math.log2(index + 2) for index, gain in enumerate(gains))
    ideal = sorted(expected_relevance.values(), reverse=True)[:top_k]
    ideal_dcg = sum((2**gain - 1) / math.log2(index + 2) for index, gain in enumerate(ideal))
    return dcg / ideal_dcg if ideal_dcg > 0 else 0.0


def _safe_ratio(numerator: int, denominator: int, *, empty_value: float = 0.0) -> float:
    """安全计算比例，显式定义空分母语义。"""

    return numerator / denominator if denominator else empty_value


def _f1(precision: float, recall: float) -> float:
    """计算二分类 F1。"""

    return 2 * precision * recall / (precision + recall) if precision + recall else 0.0


def _rounded_mean(values: list[float]) -> float:
    """返回六位小数的宏平均。"""

    return round(sum(values) / len(values), 6) if values else 0.0


def _percentile(values: list[int], percentile: float) -> int:
    """使用最近秩计算小样本也稳定的延迟分位数。"""

    if not values:
        return 0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(percentile * len(ordered)) - 1))
    return ordered[index]


__all__ = [
    "RAG_EVALUATION_ASSET_SCHEMA_VERSION",
    "RAG_EVALUATION_REPORT_PAYLOAD_POLICY",
    "RAG_EVALUATION_REPORT_SCHEMA_VERSION",
    "RagEvaluationDataset",
    "RagEvaluationDatasetError",
    "RagEvaluationReport",
    "RagEvaluationRunner",
    "RagEvaluationRunProfile",
    "RagEvaluationThresholds",
    "RagExpectedDocument",
    "RagGoldenCase",
    "load_rag_evaluation_dataset",
    "validate_synthetic_evaluation_ingest_runtime",
]
