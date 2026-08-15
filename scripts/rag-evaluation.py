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
        choices=("lexical", "siliconflow"),
        default="lexical",
        help="lexical 为离线基线；siliconflow 启用真实 BGE embedding 与 reranker。",
    )
    parser.add_argument(
        "--case-type",
        action="append",
        default=[],
        help="只执行指定 caseType；可重复提供。",
    )
    parser.add_argument("--limit", type=int, default=0, help="按文件顺序限制用例数，0 表示全部。")
    parser.add_argument("--report", type=Path, help="可选低敏 JSON 报告路径。")
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
        dataset = _select_cases(dataset, tuple(args.case_type), args.limit)
        if args.validate_only:
            _print_validation_summary(dataset)
            return 0
        pipeline, run_profile = _build_pipeline(dataset, args.profile)
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
) -> RagEvaluationDataset:
    """按 caseType 和数量生成可追溯子集，并派生新的数据集指纹。"""

    normalized_types = {value.strip() for value in requested_case_types if value.strip()}
    selected = tuple(
        golden_case
        for golden_case in dataset.cases
        if not normalized_types or golden_case.case_type in normalized_types
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
) -> tuple[RagPipeline, RagEvaluationRunProfile]:
    """按档位组装同一套 RAG 管线，生成阶段始终关闭。"""

    embedding_provider = None
    reranker = None
    run_profile = RagEvaluationRunProfile(
        profile_name="lexical",
        retrieval_backend="in-memory-scope-filtered",
    )
    if profile == "siliconflow":
        embedding_api_key, reranker_api_key = _resolve_siliconflow_api_keys()
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
        _validate_siliconflow_endpoint(embedding_endpoint, capability="Embedding")
        _validate_siliconflow_endpoint(reranker_endpoint, capability="Reranker")
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
            )
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
                    64,
                ),
            )
        )
        run_profile = RagEvaluationRunProfile(
            profile_name="siliconflow-bge-m3",
            retrieval_backend="in-memory-scope-filtered-hybrid",
            embedding_model=embedding_model,
            reranker_model=reranker_model,
        )

    knowledge_base = InMemoryRagKnowledgeBase(dataset.documents)
    retriever = RagHybridRetriever(
        knowledge_base,
        embedding_provider=embedding_provider,
        settings=RagHybridRetrieverSettings(
            minimum_vector_score=_float_environment(
                "DATASMART_RAG_MINIMUM_VECTOR_SCORE",
                0.65,
            )
        ),
    )
    routes = ModelRouteRegistry(default_model_routes())
    pipeline = RagPipeline(
        retriever=retriever,
        reranker=reranker,
        model_routes=routes,
        model_gateway=ModelGatewayGovernanceService(routes),
        model_providers=ModelProviderRegistry(),
    )
    return pipeline, run_profile


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
