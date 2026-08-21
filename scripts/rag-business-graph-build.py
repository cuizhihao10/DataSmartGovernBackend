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
import hashlib
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
    parser.add_argument(
        "--upload-minio",
        action="store_true",
        help="生成事实包后上传到 MinIO，并在摘要中返回稳定 s3:// URI；不会把凭据写入文件或 stdout",
    )
    parser.add_argument(
        "--minio-endpoint",
        default=os.getenv("DATASMART_GRAPH_FACT_MINIO_ENDPOINT") or os.getenv("DATASMART_RAG_ARTIFACT_MINIO_ENDPOINT") or "http://localhost:9000",
        help="MinIO/S3 endpoint，生产环境应通过部署配置注入",
    )
    parser.add_argument(
        "--minio-bucket",
        default=os.getenv("DATASMART_GRAPH_FACT_MINIO_BUCKET", "datasmart-graph-facts"),
        help="事实包固定 bucket",
    )
    parser.add_argument(
        "--minio-prefix",
        default=os.getenv("DATASMART_GRAPH_FACT_MINIO_PREFIX", "business-graph"),
        help="事实包对象 key 前缀",
    )
    parser.add_argument("--minio-region", default=os.getenv("DATASMART_GRAPH_FACT_MINIO_REGION", "us-east-1"))
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
    fact_bundle = result.to_fact_bundle()
    serialized_bundle = json.dumps(fact_bundle, ensure_ascii=False, indent=2) + "\n"
    output_path.write_text(serialized_bundle, encoding="utf-8")
    fact_uri = None
    if args.upload_minio:
        fact_uri = _upload_fact_bundle(
            serialized_bundle.encode("utf-8"),
            endpoint=args.minio_endpoint,
            bucket=args.minio_bucket,
            prefix=args.minio_prefix,
            tenant_id=args.tenant_id or str(snapshot.get("scope", {}).get("tenantId") or "unknown"),
            application_id=args.application_id or str(snapshot.get("scope", {}).get("applicationId") or "unknown"),
            project_id=args.project_id or str(snapshot.get("scope", {}).get("projectId") or "unknown"),
            fingerprint=result.fingerprint,
            region=args.minio_region,
        )
    print(json.dumps({
        "status": "PROPOSED",
        "output": str(output_path),
        "fingerprint": result.fingerprint,
        "entityCount": result.entity_count,
        "edgeCount": result.edge_count,
        "skippedRelationCount": result.skipped_relation_count,
        "warningCount": len(result.warnings),
        "factBundleUri": fact_uri,
        "uploadStatus": "UPLOADED" if fact_uri else "LOCAL_ONLY",
    }, ensure_ascii=False))
    return 0


def _upload_fact_bundle(
    body: bytes,
    *,
    endpoint: str,
    bucket: str,
    prefix: str,
    tenant_id: str,
    application_id: str,
    project_id: str,
    fingerprint: str,
    region: str,
) -> str:
    """把不可变图事实包上传到固定 MinIO bucket，并返回 worker 可读取的 s3 URI。

    <p>对象 key 只由租户/应用/项目范围和事实指纹组成，既保证相同事实的幂等覆盖，
    又不会把任务名称、SQL、字段样本或凭据写入对象存储路径。worker 只允许读取同一个
    bucket，因此审批事件中的 URI 不能跨租户访问任意对象。</p>
    """

    try:
        import boto3  # type: ignore[import-not-found]
    except ImportError as exc:
        raise RuntimeError("上传图事实包需要 python-ai-runtime[object-store] 的 boto3 依赖") from exc
    endpoint = str(endpoint or "").strip().rstrip("/")
    bucket = str(bucket or "").strip()
    prefix = str(prefix or "business-graph").strip("/")
    if not endpoint or not bucket or not fingerprint or any(not str(value).strip() for value in (tenant_id, application_id, project_id)):
        raise RuntimeError("图事实包 MinIO 上传配置不完整")
    if not all(str(value).replace("-", "").replace("_", "").isalnum() for value in (bucket, prefix)):
        raise RuntimeError("图事实包 bucket/prefix 不是安全对象存储标识")
    access_key = os.getenv("DATASMART_GRAPH_FACT_MINIO_ACCESS_KEY") or os.getenv("DATASMART_MINIO_ACCESS_KEY")
    secret_key = os.getenv("DATASMART_GRAPH_FACT_MINIO_SECRET_KEY") or os.getenv("DATASMART_MINIO_SECRET_KEY")
    if not access_key or not secret_key:
        raise RuntimeError("图事实包 MinIO 凭据未配置")
    client = boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name=region or "us-east-1",
    )
    try:
        client.head_bucket(Bucket=bucket)
    except Exception:
        client.create_bucket(Bucket=bucket)
    key = f"{prefix}/{tenant_id}/{application_id}/{project_id}/{fingerprint}.json"
    digest = hashlib.sha256(body).hexdigest()
    # fingerprint 是图内容指纹，不等于 JSON 文本摘要；两者都写入对象元数据，
    # 便于运维核对对象完整性，但 consumer 仍以事件 fingerprint 校验图实体/关系事实。
    metadata = {"graph-fact-fingerprint": fingerprint, "bundle-sha256": digest}
    client.put_object(
        Bucket=bucket,
        Key=key,
        Body=body,
        ContentType="application/json; charset=utf-8",
        Metadata=metadata,
    )
    return f"s3://{bucket}/{key}"


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
