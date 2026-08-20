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
import os
import sys
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHON_SRC = ROOT / "python-ai-runtime" / "src"
if str(PYTHON_SRC) not in sys.path:
    sys.path.insert(0, str(PYTHON_SRC))

from datasmart_ai_runtime.services.rag.business_graph_builder import BusinessGraphBuilder


def main() -> int:
    """读取本地或 data-sync 实时快照、生成事实包并输出低敏构建摘要。"""

    parser = argparse.ArgumentParser(description="生成 DataSmart 业务 GraphRAG 待审批事实包")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--snapshot", help="结构化业务快照 JSON 路径")
    source.add_argument("--source-url", help="data-sync 内部快照 URL 根地址")
    parser.add_argument("--task-id", type=int, help="实时快照对应的 data-sync taskId")
    parser.add_argument("--execution-id", type=int, help="可选的真实 executionId")
    parser.add_argument("--tenant-id", help="实时快照租户范围")
    parser.add_argument("--application-id", help="实时快照对应的 applicationId")
    parser.add_argument("--project-id", help="实时快照项目范围")
    parser.add_argument("--actor-id", help="实时快照读取主体")
    parser.add_argument(
        "--source-token",
        help="内部服务令牌；优先使用参数，否则读取 DATASMART_GRAPH_SOURCE_TOKEN，不会写入输出",
    )
    parser.add_argument("--source-service", default="python-ai-runtime", help="内部来源服务名")
    parser.add_argument("--output", required=True, help="待审批 graph-facts JSON 输出路径")
    args = parser.parse_args()

    output_path = Path(args.output).resolve()
    if args.snapshot:
        snapshot = json.loads(Path(args.snapshot).resolve().read_text(encoding="utf-8"))
    else:
        if not args.task_id or not args.application_id or not args.tenant_id or not args.project_id:
            parser.error("--source-url 模式必须同时提供 --task-id、--tenant-id、--application-id 和 --project-id")
        snapshot = _load_live_snapshot(args)
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


def _load_live_snapshot(args: argparse.Namespace) -> dict[str, object]:
    """从受信 data-sync 读取真实业务快照；响应只在内存中短暂存在。"""

    base = str(args.source_url).rstrip("/")
    # 当前 Controller 位于 /sync-tasks/{taskId} 下，保留该路径能复用已有任务范围校验。
    url = f"{base}/sync-tasks/{args.task_id}/internal/data-sync/graph-facts/snapshot?"
    params = {"applicationId": str(args.application_id)}
    if args.execution_id:
        params["executionId"] = str(args.execution_id)
    source_token = str(args.source_token or os.getenv("DATASMART_GRAPH_SOURCE_TOKEN") or "")
    request = Request(
        url + urlencode(params),
        headers={
            "X-DataSmart-Source-Service": str(args.source_service),
            "X-DataSmart-Internal-Service-Token": source_token,
            "X-DataSmart-Application-Id": str(args.application_id),
            "X-DataSmart-Tenant-Id": str(args.tenant_id),
            "X-DataSmart-Project-Id": str(args.project_id),
            "X-DataSmart-Actor-Id": str(args.actor_id or "agent-runtime"),
            "X-DataSmart-Actor-Role": "SERVICE_ACCOUNT",
            "Accept": "application/json",
        },
        method="GET",
    )
    with urlopen(request, timeout=30) as response:  # noqa: S310 - URL 必须由部署操作者显式提供
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict) or payload.get("code") not in (0, "0", None):
        raise RuntimeError("data-sync 业务图谱快照接口返回失败")
    snapshot = payload.get("data", payload)
    if not isinstance(snapshot, dict):
        raise RuntimeError("data-sync 业务图谱快照响应不是 JSON 对象")
    return snapshot


if __name__ == "__main__":
    raise SystemExit(main())
