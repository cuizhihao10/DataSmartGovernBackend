#!/usr/bin/env python3
"""执行 DataSmart Govern 中文 RAG 黄金集评测。

默认 ``lexical`` 档位完全离线，用于验证语料、范围隔离、引用和基础检索回归；``siliconflow`` 档位使用
BAAI/bge-m3 与 BAAI/bge-reranker-v2-m3 评估真实语义召回和重排。脚本不接受命令行 API Key，密钥只能
由环境变量或 Secret 注入，避免进入 shell 历史、进程参数和报告。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
import sys
from dataclasses import replace
from pathlib import Path
from typing import Mapping, Sequence
from urllib import parse


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PYTHON_RUNTIME_ROOT = REPOSITORY_ROOT / "python-ai-runtime"
SOURCE_ROOT = PYTHON_RUNTIME_ROOT / "src"
DEFAULT_ASSET_ROOT = PYTHON_RUNTIME_ROOT / "evaluation" / "rag"
if str(SOURCE_ROOT) not in sys.path:
    sys.path.insert(0, str(SOURCE_ROOT))

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    MemoryEmbeddingProviderSettings,
    MemoryEmbeddingProviderType,
    build_memory_embedding_provider,
    validate_embedding_vector,
)
from datasmart_ai_runtime.services.model_gateway import (
    ModelGatewayGovernanceService,
    ModelProviderRegistry,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag import (
    InMemoryRagKnowledgeBase,
    RagEvaluationDataset,
    RagEvaluationRunProfile,
    RagEvaluationRunner,
    RagHybridRetriever,
    RagHybridRetrieverSettings,
    RagPipeline,
    RagPipelineSettings,
    RagRerankerProviderSettings,
    RagRerankerProviderType,
    build_rag_reranker_provider,
    load_rag_evaluation_dataset,
)


SILICONFLOW_EMBEDDING_ENDPOINT = "https://api.siliconflow.cn/v1/embeddings"
SILICONFLOW_RERANK_ENDPOINT = "https://api.siliconflow.cn/v1/rerank"
SILICONFLOW_EMBEDDING_MODEL = "BAAI/bge-m3"
SILICONFLOW_RERANK_MODEL = "BAAI/bge-reranker-v2-m3"


def build_argument_parser() -> argparse.ArgumentParser:
    """定义可重复、无命令行密钥的评测参数。"""

    parser = argparse.ArgumentParser(description="执行 DataSmart Govern 中文 RAG 黄金集评测。")
    parser.add_argument(
        "--asset-root",
        type=Path,
        default=DEFAULT_ASSET_ROOT,
        help="包含 manifest.json 与 golden_cases.jsonl 的评测资产目录。",
    )
    parser.add_argument(
        "--profile",
        choices=("lexical", "siliconflow-rerank", "siliconflow"),
        default="lexical",
        help=(
            "lexical 为离线基线；siliconflow-rerank 只启用真实 Reranker 做消融；"
            "siliconflow 同时启用真实 BGE embedding 与 reranker。"
        ),
    )
    parser.add_argument(
        "--case-type",
        action="append",
        default=[],
        help="只执行指定 caseType；可重复提供。",
    )
    parser.add_argument(
        "--case-id",
        action="append",
        default=[],
        help="只执行指定 caseId；可重复提供，并与 --case-type 共同取交集。",
    )
    parser.add_argument("--limit", type=int, default=0, help="按文件顺序限制用例数，0 表示全部。")
    parser.add_argument("--report", type=Path, help="可选低敏 JSON 报告路径。")
    parser.add_argument(
        "--embedding-cache",
        type=Path,
        help=(
            "可选：真实 Embedding 的本地 SQLite 缓存。只保存模型名、文本摘要和向量，"
            "不得放入仓库或生产知识目录。"
        ),
    )
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="只校验资产完整性，不执行检索。",
    )
    parser.add_argument(
        "--enforce-quality-gate",
        action="store_true",
        help="质量门禁未通过时返回退出码 2；默认只报告指标。",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """加载资产、选择检索档位、执行评测并可选写入低敏报告。"""

    args = build_argument_parser().parse_args(argv)
    try:
        dataset = load_rag_evaluation_dataset(args.asset_root)
        dataset = _select_cases(
            dataset,
            tuple(args.case_type),
            args.limit,
            requested_case_ids=tuple(args.case_id),
        )
        if args.validate_only:
            _print_validation_summary(dataset)
            return 0
        pipeline, run_profile = _build_pipeline(
            dataset,
            args.profile,
            embedding_cache_path=args.embedding_cache,
        )
        report = RagEvaluationRunner(
            dataset,
            execute_query=pipeline.answer,
            run_profile=run_profile,
        ).evaluate()
        summary = report.to_summary()
        if args.report is not None:
            _write_report(args.report, summary)
        _print_result_summary(summary)
        if args.enforce_quality_gate and not report.quality_gate_passed:
            return 2
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI 只输出低敏异常类型和稳定说明。
        print(
            json.dumps(
                {
                    "status": "FAILED",
                    "errorType": type(exc).__name__,
                    "message": _safe_cli_error_message(exc),
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2


def _select_cases(
    dataset: RagEvaluationDataset,
    requested_case_types: tuple[str, ...],
    limit: int,
    requested_case_ids: tuple[str, ...] = (),
) -> RagEvaluationDataset:
    """按 caseType、caseId 和数量生成可追溯子集，并派生新的数据集指纹。

    `caseType` 适合跑完整质量分桶，`caseId` 适合在调优过程中复现一条具体失败合同。两者同时提供时
    必须取交集，防止操作者误以为 caseId 可以绕过分桶限制。筛选只改变本次运行的黄金用例集合，不会
    删除语料、缩小知识库候选范围或把期望文档写入检索逻辑，因此逐案结果仍会面对完整干扰语料。
    """

    normalized_types = {value.strip() for value in requested_case_types if value.strip()}
    normalized_ids = {value.strip() for value in requested_case_ids if value.strip()}
    selected = tuple(
        golden_case
        for golden_case in dataset.cases
        if (not normalized_types or golden_case.case_type in normalized_types)
        and (not normalized_ids or golden_case.case_id in normalized_ids)
    )
    if limit < 0:
        raise ValueError("--limit 不能为负数。")
    if limit > 0:
        selected = selected[:limit]
    if not selected:
        raise ValueError("筛选后没有可执行的 RAG 黄金用例。")
    if selected == dataset.cases:
        return dataset
    subset_fingerprint = hashlib.sha256(
        (dataset.fingerprint + "|" + "|".join(item.case_id for item in selected)).encode("utf-8")
    ).hexdigest()
    return replace(dataset, cases=selected, fingerprint=subset_fingerprint)


def _build_pipeline(
    dataset: RagEvaluationDataset,
    profile: str,
    *,
    embedding_cache_path: Path | None = None,
) -> tuple[RagPipeline, RagEvaluationRunProfile]:
    """按档位组装同一套 RAG 管线，生成阶段始终关闭。"""

    embedding_provider = None
    reranker = None
    minimum_vector_score = _float_environment(
        "DATASMART_RAG_MINIMUM_VECTOR_SCORE",
        0.45,
    )
    hybrid_vector_candidate_ratio = _float_environment(
        "DATASMART_RAG_HYBRID_VECTOR_CANDIDATE_RATIO",
        0.5,
    )
    max_candidate_chunks_per_document = _positive_environment_int(
        "DATASMART_RAG_MAX_CANDIDATE_CHUNKS_PER_DOCUMENT",
        2,
    )
    evaluation_candidate_limit = max(
        max(32, min(200, golden_case.top_k * 8))
        for golden_case in dataset.cases
    )
    retrieval_parameters = {
        "maxCandidateLimit": evaluation_candidate_limit,
        "minimumVectorScore": minimum_vector_score,
        "hybridVectorCandidateRatio": hybrid_vector_candidate_ratio,
        "maxCandidateChunksPerDocument": max_candidate_chunks_per_document,
        "vectorRoutingCandidateLimit": _positive_environment_int(
            "DATASMART_RAG_VECTOR_ROUTING_CANDIDATE_LIMIT",
            24,
        ),
        "minimumUnanchoredVectorScore": _float_environment(
            "DATASMART_RAG_MINIMUM_UNANCHORED_VECTOR_SCORE",
            0.82,
        ),
    }
    reranker_max_documents: int | None = None
    run_profile = RagEvaluationRunProfile(
        profile_name="lexical",
        retrieval_backend="in-memory-scope-filtered",
        candidate_limit_policy="per-case:max(32,min(200,topK*8))",
        retrieval_parameters=retrieval_parameters,
    )
    if profile in {"siliconflow-rerank", "siliconflow"}:
        # 真实外部模型评测只允许仓库生成并由 Loader 完整校验过的合成资产。这里不能依赖文件路径或
        # 操作者口头约定，因为 approved_sensitivity_levels 会真正决定正文能否离开本机进程。
        if str(dataset.asset_boundary or "").strip().lower() != "synthetic-only":
            raise RuntimeError("远端 RAG 评测只允许 assetBoundary=synthetic-only 的合成黄金语料。")
        evaluation_approved_levels = ("internal", "restricted")
        reranker_api_key = _resolve_siliconflow_reranker_api_key()
        embedding_model = _environment_or_default(
            "DATASMART_RAG_EMBEDDING_MODEL",
            SILICONFLOW_EMBEDDING_MODEL,
        )
        reranker_model = _environment_or_default(
            "DATASMART_RAG_RERANK_MODEL",
            SILICONFLOW_RERANK_MODEL,
        )
        embedding_endpoint = _environment_or_default(
            "DATASMART_RAG_EMBEDDING_ENDPOINT",
            SILICONFLOW_EMBEDDING_ENDPOINT,
        )
        reranker_endpoint = _environment_or_default(
            "DATASMART_RAG_RERANK_ENDPOINT",
            SILICONFLOW_RERANK_ENDPOINT,
        )
        _validate_siliconflow_endpoint(reranker_endpoint, capability="Reranker")
        if profile == "siliconflow":
            embedding_api_key = _resolve_siliconflow_embedding_api_key()
            _validate_siliconflow_endpoint(embedding_endpoint, capability="Embedding")
            embedding_provider = build_memory_embedding_provider(
                MemoryEmbeddingProviderSettings(
                    provider_type=MemoryEmbeddingProviderType.OPENAI_COMPATIBLE,
                    endpoint=embedding_endpoint,
                    api_key=embedding_api_key,
                    model=embedding_model,
                    dimensions=_positive_environment_int("DATASMART_RAG_EMBEDDING_DIMENSIONS", 1024),
                    timeout_seconds=_positive_environment_int(
                        "DATASMART_RAG_EMBEDDING_TIMEOUT_SECONDS",
                        30,
                    ),
                    max_input_chars=_positive_environment_int(
                        "DATASMART_RAG_EMBEDDING_MAX_INPUT_CHARS",
                        8000,
                    ),
                    max_batch_size=_positive_environment_int(
                        "DATASMART_RAG_EMBEDDING_MAX_BATCH_SIZE",
                        16,
                    ),
                    max_attempts=_positive_environment_int(
                        "DATASMART_RAG_EMBEDDING_MAX_ATTEMPTS",
                        3,
                    ),
                    retry_base_delay_ms=_positive_environment_int(
                        "DATASMART_RAG_EMBEDDING_RETRY_BASE_DELAY_MS",
                        250,
                    ),
                    approved_sensitivity_levels=evaluation_approved_levels,
                    synthetic_only_evaluation=True,
                )
            )
            if embedding_cache_path is not None and embedding_provider is not None:
                embedding_provider = _SqliteEvaluationEmbeddingCache(
                    embedding_provider,
                    path=embedding_cache_path,
                    model=embedding_model,
                    cache_version=_environment_or_default(
                        "DATASMART_RAG_EVALUATION_EMBEDDING_CACHE_VERSION",
                        "v1",
                    ),
                )
        reranker = build_rag_reranker_provider(
            RagRerankerProviderSettings(
                provider_type=RagRerankerProviderType.SILICONFLOW,
                endpoint=reranker_endpoint,
                api_key=reranker_api_key,
                model=reranker_model,
                timeout_seconds=_positive_environment_int(
                    "DATASMART_RAG_RERANK_TIMEOUT_SECONDS",
                    30,
                ),
                max_documents=_positive_environment_int(
                    "DATASMART_RAG_RERANK_MAX_DOCUMENTS",
                    16,
                ),
                max_attempts=_positive_environment_int(
                    "DATASMART_RAG_RERANK_MAX_ATTEMPTS",
                    3,
                ),
                retry_base_delay_ms=_positive_environment_int(
                    "DATASMART_RAG_RERANK_RETRY_BASE_DELAY_MS",
                    250,
                ),
                retrieval_prior_weight=_float_environment(
                    "DATASMART_RAG_RERANK_RETRIEVAL_PRIOR_WEIGHT",
                    0.0,
                ),
                approved_sensitivity_levels=evaluation_approved_levels,
                synthetic_only_evaluation=True,
            )
        )
        reranker_max_documents = _positive_environment_int(
            "DATASMART_RAG_RERANK_MAX_DOCUMENTS",
            16,
        )
        run_profile = RagEvaluationRunProfile(
            profile_name=(
                "siliconflow-bge-m3"
                if profile == "siliconflow"
                else "siliconflow-reranker-ablation"
            ),
            retrieval_backend=(
                "in-memory-scope-filtered-hybrid"
                if profile == "siliconflow"
                else "in-memory-scope-filtered-lexical-rerank"
            ),
            embedding_model=embedding_model if profile == "siliconflow" else None,
            reranker_model=reranker_model,
            candidate_limit_policy="per-case:max(32,min(200,topK*8))",
            reranker_max_documents=reranker_max_documents,
            retrieval_parameters=retrieval_parameters,
        )

    knowledge_base = InMemoryRagKnowledgeBase(dataset.documents)
    retriever = RagHybridRetriever(
        knowledge_base,
        embedding_provider=embedding_provider,
        settings=RagHybridRetrieverSettings(
            minimum_vector_score=minimum_vector_score,
            hierarchical_vector_minimum_chunks=_positive_environment_int(
                "DATASMART_RAG_HIERARCHICAL_VECTOR_MINIMUM_CHUNKS",
                5000,
            ),
            vector_routing_group_size=_positive_environment_int(
                "DATASMART_RAG_VECTOR_ROUTING_GROUP_SIZE",
                24,
            ),
            vector_routing_candidate_limit=retrieval_parameters["vectorRoutingCandidateLimit"],
            vector_routing_groups_per_document=_positive_environment_int(
                "DATASMART_RAG_VECTOR_ROUTING_GROUPS_PER_DOCUMENT",
                2,
            ),
            vector_routing_chunks_per_group=_positive_environment_int(
                "DATASMART_RAG_VECTOR_ROUTING_CHUNKS_PER_GROUP",
                2,
            ),
            hybrid_vector_candidate_ratio=hybrid_vector_candidate_ratio,
            max_candidate_chunks_per_document=max_candidate_chunks_per_document,
            exact_match_weight=_float_environment(
                "DATASMART_RAG_EXACT_MATCH_WEIGHT",
                4.0,
            ),
            query_intent_rank_weight=_float_environment(
                "DATASMART_RAG_QUERY_INTENT_RANK_WEIGHT",
                0.16,
            ),
        ),
    )
    routes = ModelRouteRegistry(default_model_routes())
    pipeline = RagPipeline(
        retriever=retriever,
        reranker=reranker,
        model_routes=routes,
        model_gateway=ModelGatewayGovernanceService(routes),
        model_providers=ModelProviderRegistry(),
        settings=RagPipelineSettings(
            minimum_vector_score=minimum_vector_score,
            minimum_unanchored_vector_score=retrieval_parameters["minimumUnanchoredVectorScore"],
            minimum_absolute_rerank_score=_float_environment(
                "DATASMART_RAG_MINIMUM_ABSOLUTE_RERANK_SCORE",
                0.001 if reranker is not None else 0.0,
            ),
            minimum_relative_rerank_score=_float_environment(
                "DATASMART_RAG_MINIMUM_RELATIVE_RERANK_SCORE",
                0.82,
            ),
            multi_evidence_relative_rerank_score=_float_environment(
                "DATASMART_RAG_MULTI_EVIDENCE_RELATIVE_RERANK_SCORE",
                0.55,
            ),
            multi_evidence_facet_relative_score=_float_environment(
                "DATASMART_RAG_MULTI_EVIDENCE_FACET_RELATIVE_SCORE",
                0.80,
            ),
            query_intent_boost=_float_environment(
                "DATASMART_RAG_QUERY_INTENT_BOOST",
                0.08,
            ),
        ),
    )
    return pipeline, run_profile


class _SqliteEvaluationEmbeddingCache:
    """合成黄金集评测专用的低敏持久向量缓存。

    评测调参会重复使用完全相同的合成路由摘要。如果每次进程启动都重新调用远程模型，费用、限流和冷启动
    时间会淹没算法差异。本缓存只保存 ``model + cacheVersion + sha256(normalizedText) -> vector``，不
    保存原始问题、文档正文、Endpoint、API Key 或供应商响应。模型服务升级但名称不变时，操作者必须
    提升 cacheVersion，避免旧向量和新向量混用。

    这是离线评测工具，不替代 PostgreSQL/pgvector。生产知识的权限谓词、来源、时效与审计字段仍必须由
    持久知识库存储；缓存文件也不得提交到 Git 或放入客户资料目录。
    """

    def __init__(
        self,
        delegate: object,
        *,
        path: Path,
        model: str,
        cache_version: str,
    ) -> None:
        """创建缓存表；路径只用于本地评测，不写入最终报告。"""

        self._delegate = delegate
        self._model = str(model).strip()
        self._cache_version = str(cache_version).strip() or "v1"
        resolved = path.expanduser().resolve()
        resolved.parent.mkdir(parents=True, exist_ok=True)
        self._connection = sqlite3.connect(resolved)
        self._connection.execute(
            """
            CREATE TABLE IF NOT EXISTS evaluation_embedding_cache (
                model TEXT NOT NULL,
                cache_version TEXT NOT NULL,
                text_sha256 TEXT NOT NULL,
                dimensions INTEGER NOT NULL,
                vector_json TEXT NOT NULL,
                PRIMARY KEY (model, cache_version, text_sha256)
            )
            """
        )
        self._connection.commit()

    def embed_text(
        self,
        text: str,
        *,
        sensitivity_level: str = "internal",
    ) -> tuple[float, ...]:
        """复用批量实现，保证单条与批量请求使用同一分级缓存合同。"""

        return self.embed_texts(
            (text,),
            sensitivity_levels=(sensitivity_level,),
        )[0]

    def embed_texts(
        self,
        texts: tuple[str, ...],
        *,
        sensitivity_levels: tuple[str, ...] | None = None,
    ) -> tuple[tuple[float, ...], ...]:
        """按“文本摘要 + 分级”命中缓存，只把未命中项发送给真实 Provider。

        分级是缓存身份的一部分。否则相同正文先以 ``internal`` 生成向量后，再以 ``restricted`` 查询会
        直接命中缓存，使后一次请求绕过外部 Provider 的 restricted 门禁。
        """

        normalized = tuple(str(text or "").strip() for text in texts)
        if not normalized or any(not text for text in normalized):
            raise ValueError("Embedding 评测缓存输入不能为空。")
        levels = _evaluation_sensitivity_levels(normalized, sensitivity_levels)
        hashes = tuple(
            _evaluation_embedding_cache_digest(text, sensitivity_level)
            for text, sensitivity_level in zip(normalized, levels)
        )
        vectors_by_hash: dict[str, tuple[float, ...]] = {}
        for text_hash in dict.fromkeys(hashes):
            cached = self._load(text_hash)
            if cached is not None:
                vectors_by_hash[text_hash] = cached
        missing_item_by_hash = {
            text_hash: (text, sensitivity_level)
            for text_hash, text, sensitivity_level in zip(hashes, normalized, levels)
            if text_hash not in vectors_by_hash
        }
        if missing_item_by_hash:
            missing_hashes = tuple(missing_item_by_hash)
            missing_texts = tuple(
                missing_item_by_hash[text_hash][0]
                for text_hash in missing_hashes
            )
            missing_levels = tuple(
                missing_item_by_hash[text_hash][1]
                for text_hash in missing_hashes
            )
            embed_texts = getattr(self._delegate, "embed_texts", None)
            generated = (
                tuple(embed_texts(missing_texts, sensitivity_levels=missing_levels))
                if callable(embed_texts)
                else tuple(
                    self._delegate.embed_text(
                        text,
                        sensitivity_level=sensitivity_level,
                    )
                    for text, sensitivity_level in zip(missing_texts, missing_levels)
                )
            )
            if len(generated) != len(missing_hashes):
                raise ValueError("Embedding 评测缓存收到的向量数量与缺失文本数量不一致。")
            for text_hash, raw_vector in zip(missing_hashes, generated):
                vector = validate_embedding_vector(raw_vector)
                vectors_by_hash[text_hash] = vector
                self._store(text_hash, vector)
            self._connection.commit()
        return tuple(vectors_by_hash[text_hash] for text_hash in hashes)

    def _load(self, text_hash: str) -> tuple[float, ...] | None:
        """读取并重新校验向量；损坏条目视为未命中，不污染本次排序。"""

        row = self._connection.execute(
            """
            SELECT dimensions, vector_json
              FROM evaluation_embedding_cache
             WHERE model = ? AND cache_version = ? AND text_sha256 = ?
            """,
            (self._model, self._cache_version, text_hash),
        ).fetchone()
        if row is None:
            return None
        try:
            vector = validate_embedding_vector(json.loads(str(row[1])))
        except (json.JSONDecodeError, TypeError, ValueError):
            self._delete(text_hash)
            return None
        if len(vector) != int(row[0]):
            self._delete(text_hash)
            return None
        return vector

    def _store(self, text_hash: str, vector: tuple[float, ...]) -> None:
        """原子覆盖同一模型与版本的摘要向量，不保存模型输入。"""

        self._connection.execute(
            """
            INSERT INTO evaluation_embedding_cache (
                model, cache_version, text_sha256, dimensions, vector_json
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(model, cache_version, text_sha256) DO UPDATE SET
                dimensions = excluded.dimensions,
                vector_json = excluded.vector_json
            """,
            (
                self._model,
                self._cache_version,
                text_hash,
                len(vector),
                json.dumps(vector, ensure_ascii=True, separators=(",", ":")),
            ),
        )

    def _delete(self, text_hash: str) -> None:
        """删除损坏缓存项，让下一步重新向量化并恢复一致性。"""

        self._connection.execute(
            """
            DELETE FROM evaluation_embedding_cache
             WHERE model = ? AND cache_version = ? AND text_sha256 = ?
            """,
            (self._model, self._cache_version, text_hash),
        )
        self._connection.commit()

    def close(self) -> None:
        """显式关闭本地连接，便于 Windows 测试及时释放临时文件。"""

        self._connection.close()


def _evaluation_sensitivity_levels(
    texts: tuple[str, ...],
    sensitivity_levels: tuple[str, ...] | None,
) -> tuple[str, ...]:
    """校验评测缓存的正文与分级严格一一对应，并输出稳定比较键。"""

    if sensitivity_levels is None:
        return ("internal",) * len(texts)
    if isinstance(sensitivity_levels, str):
        raise ValueError("Embedding 评测缓存的敏感级别必须逐条提供。")
    normalized = tuple(
        str(level or "internal").strip().lower().replace("_", "-") or "internal"
        for level in sensitivity_levels
    )
    if len(normalized) != len(texts):
        raise ValueError("Embedding 评测缓存的正文与敏感级别数量不一致。")
    return normalized


def _evaluation_embedding_cache_digest(text: str, sensitivity_level: str) -> str:
    """生成不含原文的分级缓存摘要，旧版纯文本摘要会自然失效。"""

    return hashlib.sha256(f"{sensitivity_level}\0{text}".encode("utf-8")).hexdigest()


def _write_report(path: Path, summary: dict[str, object]) -> None:
    """通过同目录临时文件原子发布低敏 JSON 报告。"""

    resolved = path.expanduser().resolve()
    resolved.parent.mkdir(parents=True, exist_ok=True)
    temporary = resolved.with_name(f".{resolved.name}.tmp")
    temporary.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, resolved)


def _print_validation_summary(dataset: RagEvaluationDataset) -> None:
    """输出不含问题和正文的资产校验结果。"""

    print(
        json.dumps(
            {
                "status": "VALID",
                "documents": len(dataset.documents),
                "cases": len(dataset.cases),
                "datasetFingerprint": dataset.fingerprint,
                "assetBoundary": dataset.asset_boundary,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def _print_result_summary(summary: dict[str, object]) -> None:
    """只在终端输出运行档位、计数、指标和门禁，不输出逐用例问题。"""

    print(
        json.dumps(
            {
                "runProfile": summary["runProfile"],
                "counts": summary["counts"],
                "metrics": summary["metrics"],
                "qualityGate": summary["qualityGate"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def _safe_cli_error_message(exc: Exception) -> str:
    """只允许输出本地配置/资产异常说明；远程异常统一隐藏原始 message。"""

    if isinstance(exc, (ValueError, RuntimeError)) and not any(
        marker in str(exc).lower() for marker in ("http", "endpoint", "bearer", "response")
    ):
        return str(exc)
    return "RAG 评测失败；远程响应、Endpoint 和凭据详情已隐藏。"


def _resolve_siliconflow_api_keys(
    environ: Mapping[str, str] | None = None,
) -> tuple[str, str]:
    """分别解析 Embedding 与 Reranker 的运行时密钥。

    ``SILICONFLOW_API_KEY`` 是操作者明确选择的共享凭据，可以同时用于两种能力。两个 RAG 专用变量则
    只允许用于各自能力：不能因为其中一个非空，就把它悄悄发送到另一个模型接口。这样既支持简单本地
    评测，也保留企业环境按能力拆分权限、额度和轮换周期的安全边界。

    返回值只在当前进程内进入 HTTP Authorization Header，不写入报告、异常或日志。
    """

    source = environ if environ is not None else os.environ
    shared_key = str(source.get("SILICONFLOW_API_KEY") or "").strip()
    embedding_key = str(source.get("DATASMART_RAG_EMBEDDING_API_KEY") or "").strip() or shared_key
    reranker_key = str(source.get("DATASMART_RAG_RERANK_API_KEY") or "").strip() or shared_key
    if not embedding_key:
        raise RuntimeError("siliconflow 档位缺少 Embedding 环境密钥，请通过 Secret 注入。")
    if not reranker_key:
        raise RuntimeError("siliconflow 档位缺少 Reranker 环境密钥，请通过 Secret 注入。")
    return embedding_key, reranker_key


def _resolve_siliconflow_embedding_api_key(
    environ: Mapping[str, str] | None = None,
) -> str:
    """读取 Embedding 专用密钥；共享密钥只作为操作者显式选择的兼容项。"""

    source = environ if environ is not None else os.environ
    value = str(
        source.get("DATASMART_RAG_EMBEDDING_API_KEY")
        or source.get("SILICONFLOW_API_KEY")
        or ""
    ).strip()
    if not value:
        raise RuntimeError("siliconflow 档位缺少 Embedding 环境密钥，请通过 Secret 注入。")
    return value


def _resolve_siliconflow_reranker_api_key(
    environ: Mapping[str, str] | None = None,
) -> str:
    """读取 Reranker 专用密钥，使重排消融评测不必同时开放 Embedding 权限。"""

    source = environ if environ is not None else os.environ
    value = str(
        source.get("DATASMART_RAG_RERANK_API_KEY")
        or source.get("SILICONFLOW_API_KEY")
        or ""
    ).strip()
    if not value:
        raise RuntimeError("siliconflow 档位缺少 Reranker 环境密钥，请通过 Secret 注入。")
    return value


def _environment_or_default(name: str, default: str) -> str:
    """读取非空环境文本，否则使用审计过的默认值。"""

    return str(os.environ.get(name) or "").strip() or default


def _validate_siliconflow_endpoint(endpoint: str, *, capability: str) -> None:
    """确保 siliconflow 评测档位不会把共享密钥发送给自定义主机。"""

    parts = parse.urlsplit(str(endpoint or "").strip())
    if parts.scheme != "https" or (parts.hostname or "").lower() != "api.siliconflow.cn":
        raise ValueError(f"siliconflow {capability} Endpoint 必须使用硅基流动官方 HTTPS 主机。")
    if parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError(f"siliconflow {capability} Endpoint 不能包含凭据或查询参数。")


def _positive_environment_int(name: str, default: int) -> int:
    """读取正整数环境配置。"""

    raw = str(os.environ.get(name) or "").strip()
    if not raw:
        return default
    value = int(raw)
    if value <= 0:
        raise ValueError(f"{name} 必须是正整数。")
    return value


def _float_environment(name: str, default: float) -> float:
    """读取有限浮点环境配置。"""

    raw = str(os.environ.get(name) or "").strip()
    if not raw:
        return default
    value = float(raw)
    if not (-1.0 <= value <= 1.0):
        raise ValueError(f"{name} 必须位于 -1 到 1。")
    return value


if __name__ == "__main__":
    raise SystemExit(main())
