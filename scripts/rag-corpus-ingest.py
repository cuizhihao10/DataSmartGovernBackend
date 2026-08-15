#!/usr/bin/env python3
"""校验或受控摄取 DataSmart RAG 合成评测语料。

脚本默认只校验，不连接数据库。真正写入必须同时提供 ``--ingest`` 和
``--confirm-synthetic-evaluation-corpus``，用于提醒操作者这些文档是评测资产，不是客户生产知识。
数据库 DSN、Embedding Key 等只能通过运行时环境或 Secret 注入，脚本不会接收或输出凭据。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Sequence


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PYTHON_RUNTIME_ROOT = REPOSITORY_ROOT / "python-ai-runtime"
SOURCE_ROOT = PYTHON_RUNTIME_ROOT / "src"
DEFAULT_ASSET_ROOT = PYTHON_RUNTIME_ROOT / "evaluation" / "rag"
if str(SOURCE_ROOT) not in sys.path:
    sys.path.insert(0, str(SOURCE_ROOT))

from datasmart_ai_runtime.services.rag import (
    PostgresRagKnowledgeBase,
    build_rag_knowledge_base_runtime,
    load_rag_evaluation_dataset,
    rag_embedding_provider_from_env,
    rag_knowledge_base_settings_from_env,
    validate_synthetic_evaluation_ingest_runtime,
)


def build_argument_parser() -> argparse.ArgumentParser:
    """定义默认只读、显式确认后才写库的命令行参数。"""

    parser = argparse.ArgumentParser(description="校验或摄取 DataSmart RAG 合成评测语料。")
    parser.add_argument(
        "--asset-root",
        type=Path,
        default=DEFAULT_ASSET_ROOT,
        help="包含 Manifest、黄金集和异构原文件的评测资产目录。",
    )
    parser.add_argument("--ingest", action="store_true", help="连接已配置的持久知识库并写入文档。")
    parser.add_argument(
        "--confirm-synthetic-evaluation-corpus",
        action="store_true",
        help="确认导入的是纯合成评测资料，不是客户生产知识。",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """先验证全部资产，再按显式参数决定是否连接持久知识库。"""

    args = build_argument_parser().parse_args(argv)
    try:
        dataset = load_rag_evaluation_dataset(args.asset_root)
        if not args.ingest:
            _print_summary(
                status="VALIDATED_NOT_INGESTED",
                documents=len(dataset.documents),
                chunks=None,
                store_type=None,
                vector_enabled=False,
                embedding_model=None,
                fingerprint=dataset.fingerprint,
            )
            return 0
        if not args.confirm_synthetic_evaluation_corpus:
            raise ValueError(
                "写入前必须提供 --confirm-synthetic-evaluation-corpus，确认资产仅用于合成评测。"
            )
        if dataset.asset_boundary != "synthetic-only":
            raise ValueError("当前摄取入口只接受 assetBoundary=synthetic-only 的评测资产。")

        settings = rag_knowledge_base_settings_from_env()
        validate_synthetic_evaluation_ingest_runtime(settings.runtime_mode)
        embedding_provider = rag_embedding_provider_from_env()
        runtime = build_rag_knowledge_base_runtime(
            settings=settings,
            embedding_provider=embedding_provider,
        )
        if not runtime.available or not runtime.persistent:
            raise RuntimeError("RAG 持久知识库未配置或不可用，已拒绝摄取。")
        knowledge_base = runtime.knowledge_base
        if not isinstance(knowledge_base, PostgresRagKnowledgeBase):
            raise RuntimeError("RAG 摄取只允许写入 PostgreSQL/pgvector 持久实现。")
        try:
            written_chunks = knowledge_base.upsert_documents(dataset.documents)
        finally:
            knowledge_base.close()
        _print_summary(
            status="INGESTED",
            documents=len(dataset.documents),
            chunks=written_chunks,
            store_type=settings.store_type,
            vector_enabled=settings.vector_enabled,
            embedding_model=settings.embedding_model or None,
            fingerprint=dataset.fingerprint,
        )
        return 0
    except Exception as exc:  # noqa: BLE001 - 只输出异常类型和稳定低敏说明。
        print(
            json.dumps(
                {
                    "status": "FAILED",
                    "errorType": type(exc).__name__,
                    "rootErrorType": _root_error_type(exc),
                    "message": _safe_error_message(exc),
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2


def _print_summary(
    *,
    status: str,
    documents: int,
    chunks: int | None,
    store_type: str | None,
    vector_enabled: bool,
    embedding_model: str | None,
    fingerprint: str,
) -> None:
    """输出不含正文、DSN、Endpoint 和密钥的摄取摘要。"""

    print(
        json.dumps(
            {
                "status": status,
                "documents": documents,
                "writtenChunks": chunks,
                "storeType": store_type,
                "vectorEnabled": vector_enabled,
                "embeddingModel": embedding_model,
                "datasetFingerprint": fingerprint,
                "assetBoundary": "synthetic-only",
                "payloadPolicy": "RAG_CORPUS_INGEST_SUMMARY_NO_DOCUMENT_DSN_ENDPOINT_OR_SECRET",
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def _safe_error_message(exc: Exception) -> str:
    """保留本地合同错误，隐藏数据库、HTTP 与 Provider 原始异常正文。"""

    message = str(exc)
    lowered = message.lower()
    if isinstance(exc, ValueError) and not any(
        marker in lowered
        for marker in ("postgres", "dsn", "password", "secret", "http", "endpoint", "provider")
    ):
        return message
    return "RAG 摄取失败；数据库、Provider、Endpoint 和凭据详情已隐藏。"


def _root_error_type(exc: BaseException) -> str:
    """返回异常链最底层的类名，同时彻底丢弃可能含敏感信息的 message。

    持久化层会把 HTTP、向量校验和 SQL 异常统一包装成稳定业务异常，这对 API 调用方是安全的，
    但摄取运维仍需要知道问题属于哪一类。异常类名通常足以区分 ``HTTPError``、``DataError``、
    ``ValueError`` 等排查方向；原始 message、请求体、DSN 和凭据均不进入终端或报告。
    """

    current: BaseException = exc
    seen: set[int] = set()
    for _ in range(16):
        if id(current) in seen:
            break
        seen.add(id(current))
        nested = current.__cause__ or current.__context__
        if nested is None:
            break
        current = nested
    return type(current).__name__


if __name__ == "__main__":
    raise SystemExit(main())
