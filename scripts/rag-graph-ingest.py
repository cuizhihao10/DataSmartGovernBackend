#!/usr/bin/env python3
"""校验或受控摄取 GraphRAG 图事实包。

脚本默认只做 dry-run。真正写入 Neo4j 必须同时提供 ``--ingest`` 和
``--confirm-controlled-graph-facts``，而且事实包中的每个来源文档必须声明
``sourceStatus=COMPLETE`` 以及 ``graphIngestionApproval.status=APPROVED``。
脚本不会接收、打印或持久化 Neo4j 密码、RAG API Key、Endpoint 查询参数或文档正文。
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
DEFAULT_FACTS = PYTHON_RUNTIME_ROOT / "evaluation" / "rag" / "graph" / "organization-reporting-facts.json"
if str(SOURCE_ROOT) not in sys.path:
    sys.path.insert(0, str(SOURCE_ROOT))

from datasmart_ai_runtime.services.rag import (  # noqa: E402
    ControlledGraphRagIngestor,
    GraphRagIngestionError,
    Neo4jGraphRagProvider,
    graph_rag_provider_from_env,
    load_graph_fact_documents,
)


def build_argument_parser() -> argparse.ArgumentParser:
    """定义默认只读、显式确认后才能写 Neo4j 的参数。"""

    parser = argparse.ArgumentParser(description="校验或受控摄取 DataSmart GraphRAG 图事实。")
    parser.add_argument(
        "--facts",
        type=Path,
        default=DEFAULT_FACTS,
        help="包含已审批来源文档和结构化 graphEntities/graphRelations 的 JSON 事实包。",
    )
    parser.add_argument("--ingest", action="store_true", help="连接已配置的 Neo4j 并写入图数据。")
    parser.add_argument(
        "--confirm-controlled-graph-facts",
        action="store_true",
        help="确认事实包已完成来源、范围和图摄取审批，可以写入 Neo4j。",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """执行“读取事实包 -> 全量校验 -> 可选写入”的受控流程。"""

    args = build_argument_parser().parse_args(argv)
    provider = None
    try:
        documents = load_graph_fact_documents(args.facts)
        ingestor = ControlledGraphRagIngestor()
        if not args.ingest:
            result = ingestor.ingest(documents, object(), dry_run=True)
        else:
            if not args.confirm_controlled_graph_facts:
                raise GraphRagIngestionError(
                    "写入前必须提供 --confirm-controlled-graph-facts。"
                )
            provider = graph_rag_provider_from_env()
            if not isinstance(provider, Neo4jGraphRagProvider):
                raise GraphRagIngestionError(
                    "当前 GraphRAG Provider 不是可写 Neo4j 实现，已拒绝写入。"
                )
            result = ingestor.ingest(documents, provider)
        print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI 只输出稳定低敏错误。
        print(
            json.dumps(
                {
                    "status": "FAILED",
                    "errorType": type(exc).__name__,
                    "message": _safe_error_message(exc),
                    "payloadPolicy": "GRAPH_INGEST_ERROR_NO_ENDPOINT_PASSWORD_SECRET_OR_DOCUMENT_CONTENT",
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2
    finally:
        close = getattr(provider, "close", None)
        if callable(close):
            close()


def _safe_error_message(exc: Exception) -> str:
    """只保留本地事实合同错误，隐藏 Driver、网络和凭据异常正文。"""

    message = str(exc)
    lowered = message.lower()
    if isinstance(exc, (GraphRagIngestionError, ValueError)) and not any(
        marker in lowered
        for marker in ("password", "secret", "endpoint", "bolt://", "neo4j", "driver", "http")
    ):
        return message
    return "GraphRAG 图事实摄取失败；数据库、网络、Provider 和凭据详情已隐藏。"


if __name__ == "__main__":
    raise SystemExit(main())
