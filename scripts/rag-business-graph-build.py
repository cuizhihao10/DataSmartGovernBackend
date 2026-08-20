"""从数据同步控制面快照生成待审批 GraphRAG 事实包。

用法示例：
    python scripts/rag-business-graph-build.py \
        --snapshot evaluation/rag/graph/business-sync-snapshot.json \
        --output evaluation/rag/graph/business-sync-facts.json

脚本只生成 PROPOSED 候选，不连接 Neo4j、不执行写入，也不会读取或输出凭据。
生成的 fingerprint 应提交给 permission-admin 图事实审批接口，审批事件再由
受控 consumer 重新回查后摄取。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHON_SRC = ROOT / "python-ai-runtime" / "src"
if str(PYTHON_SRC) not in sys.path:
    sys.path.insert(0, str(PYTHON_SRC))

from datasmart_ai_runtime.services.rag.business_graph_builder import BusinessGraphBuilder


def main() -> int:
    """读取快照、生成事实包并输出低敏构建摘要。"""

    parser = argparse.ArgumentParser(description="生成 DataSmart 业务 GraphRAG 待审批事实包")
    parser.add_argument("--snapshot", required=True, help="结构化业务快照 JSON 路径")
    parser.add_argument("--output", required=True, help="待审批 graph-facts JSON 输出路径")
    args = parser.parse_args()

    snapshot_path = Path(args.snapshot).resolve()
    output_path = Path(args.output).resolve()
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    result = BusinessGraphBuilder().build(snapshot)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(result.to_fact_bundle(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "status": "PROPOSED",
        "output": str(output_path),
        "fingerprint": result.fingerprint,
        "entityCount": result.entity_count,
        "edgeCount": result.edge_count,
        "skippedRelationCount": result.skipped_relation_count,
        "warningCount": len(result.warnings),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
