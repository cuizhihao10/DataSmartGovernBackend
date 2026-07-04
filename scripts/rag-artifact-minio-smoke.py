#!/usr/bin/env python
"""RAG answer artifact 的 MinIO/S3-compatible 真实 smoke。

这个脚本用于补齐“Python RAG writer 已能写对象，但还没有真实对象存储网络级验收”的最后一段。
它会连接一个已经启动的 MinIO/S3-compatible endpoint，完成以下闭环：

1. 构造一条包含 answer、citation snippet、compressedContext 的 RAG 结果；
2. 使用生产同款 `S3CompatibleRagAnswerArtifactWriter` 写入对象存储；
3. 使用与 Java `AgentToolActionArtifactMinioObjectLocator` 对齐的 objectName 规则做 `head_object/get_object`；
4. 校验对象正文确实包含 RAG 正文级内容，但脚本输出只返回低敏摘要；
5. 生成可交给 Java 控制面的 receipt/grant/probe/final-check 合同摘要，方便联调下一段 E2E。

安全边界：
- 脚本不会打印 endpoint、bucket、objectName、accessKey、secretKey、answer、compressedContext、citation snippet；
- 输出只包含 artifactReference、hash、byteSize、objectKeyDigest、对象存在性和 Java 控制面低敏请求模板；
- 如果输出摘要意外包含上述敏感值，脚本会 fail-closed。

运行前提：
- MinIO 或 S3-compatible endpoint 已启动，例如仓库 `docker-compose.yml` 中的 `minio` 服务；
- 已安装可选依赖：`pip install -e "python-ai-runtime[object-store]"`；
- 默认配置对齐仓库 Compose：endpoint `http://localhost:9000`，accessKey `datasmart`，secretKey `datasmart123`。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


REPO_ROOT = Path(__file__).resolve().parents[1]
PYTHON_RUNTIME_SRC = REPO_ROOT / "python-ai-runtime" / "src"
if str(PYTHON_RUNTIME_SRC) not in sys.path:
    sys.path.insert(0, str(PYTHON_RUNTIME_SRC))

from datasmart_ai_runtime.services.rag import (  # noqa: E402
    RagAnswerArtifactWriteInput,
    RagCitation,
    RagPipelineResult,
    S3CompatibleRagAnswerArtifactWriter,
)
from datasmart_ai_runtime.services.rag.s3_artifact_writer import (  # noqa: E402
    _minio_object_name_from_reference,
    reference_prefix_mapping_from_env,
)


ANSWER_MARKER = "SMOKE_RAG_ANSWER_BODY_DO_NOT_PRINT"
CONTEXT_MARKER = "SMOKE_COMPRESSED_CONTEXT_DO_NOT_PRINT"
CITATION_MARKER = "SMOKE_RAG_CITATION_SNIPPET_DO_NOT_PRINT"


@dataclass(frozen=True)
class SmokeConfig:
    """MinIO/S3 smoke 所需配置。

    这些字段只在脚本内部用于连接对象存储，绝不会原样输出。最终 summary 只会返回 digest 或布尔状态，
    避免把本地 endpoint、bucket 或密钥复制到终端、CI 日志和后续对话上下文。
    """

    endpoint_url: str
    bucket: str
    access_key: str
    secret_key: str
    region: str | None
    object_root_prefix: str
    create_bucket: bool
    command_id: str
    run_id: str
    session_id: str
    tenant_id: str
    project_id: str
    workspace_key: str
    actor_id: str


def main(argv: list[str] | None = None) -> int:
    """脚本入口。"""

    config = _config_from_args(argv)
    try:
        client = _build_s3_client(config)
        if config.create_bucket:
            _ensure_bucket(client, config.bucket, config.region)
        write_result = _write_rag_artifact(config, client)
        object_name = _minio_object_name_from_reference(
            write_result.artifact_reference,
            object_key_root_prefix=config.object_root_prefix,
            reference_prefix_object_key_prefixes=reference_prefix_mapping_from_env(os.environ),
        )
        object_facts = _verify_object(client, config.bucket, object_name, write_result.content_sha256)
        summary = _low_sensitive_summary(config, write_result, object_name, object_facts)
        _assert_summary_is_low_sensitive(config, object_name, summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except Exception as exc:  # noqa: BLE001 - smoke 脚本需要把失败转换成低敏、可行动的终端信息。
        print(
            json.dumps(
                {
                    "accepted": False,
                    "decision": "RAG_ARTIFACT_MINIO_SMOKE_FAILED",
                    "issueCode": type(exc).__name__,
                    "safeMessage": _safe_error_message(exc),
                    "recommendedActions": [
                        "确认 Docker Desktop/MinIO 已启动，默认 compose 服务端口为 localhost:9000。",
                        "确认已安装 python-ai-runtime[object-store]，并设置正确的 endpoint、bucket 与凭据环境变量。",
                        "不要把 endpoint、bucket、objectName、accessKey 或 secretKey 粘贴到工单、日志或模型上下文中。",
                    ],
                },
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
        )
        return 1


def _config_from_args(argv: list[str] | None) -> SmokeConfig:
    """解析命令行与环境变量。

    命令行优先级高于环境变量，环境变量再高于仓库 Compose 默认值。这样开发者可以直接跑默认本地 MinIO，
    也可以在 CI/预生产环境通过 Secret 注入覆盖配置。
    """

    parser = argparse.ArgumentParser(description="Run a real MinIO/S3 RAG answer artifact smoke.")
    parser.add_argument("--endpoint", default=_first_env("DATASMART_RAG_ARTIFACT_MINIO_ENDPOINT", "http://localhost:9000"))
    parser.add_argument("--bucket", default=_first_env("DATASMART_RAG_ARTIFACT_MINIO_BUCKET", "datasmart-agent-artifacts"))
    parser.add_argument("--access-key", default=_first_env("DATASMART_RAG_ARTIFACT_MINIO_ACCESS_KEY", "datasmart"))
    parser.add_argument("--secret-key", default=_first_env("DATASMART_RAG_ARTIFACT_MINIO_SECRET_KEY", "datasmart123"))
    parser.add_argument("--region", default=_optional_text(os.getenv("DATASMART_RAG_ARTIFACT_MINIO_REGION")))
    parser.add_argument(
        "--object-root-prefix",
        default=_first_env("DATASMART_RAG_ARTIFACT_MINIO_OBJECT_ROOT_PREFIX", "agent-runtime/artifacts"),
    )
    parser.add_argument("--no-create-bucket", action="store_true", help="Do not create bucket automatically.")
    parser.add_argument("--command-id", default="cmd-rag-minio-smoke-001")
    parser.add_argument("--run-id", default="run-rag-minio-smoke")
    parser.add_argument("--session-id", default="session-rag-minio-smoke")
    parser.add_argument("--tenant-id", default="10")
    parser.add_argument("--project-id", default="20")
    parser.add_argument("--workspace-key", default="30")
    parser.add_argument("--actor-id", default="1001")
    args = parser.parse_args(argv)
    return SmokeConfig(
        endpoint_url=_require_text(args.endpoint, "endpoint"),
        bucket=_require_text(args.bucket, "bucket"),
        access_key=_require_text(args.access_key, "accessKey"),
        secret_key=_require_text(args.secret_key, "secretKey"),
        region=_optional_text(args.region),
        object_root_prefix=_require_text(args.object_root_prefix, "objectRootPrefix"),
        create_bucket=not bool(args.no_create_bucket),
        command_id=_require_text(args.command_id, "commandId"),
        run_id=_require_text(args.run_id, "runId"),
        session_id=_require_text(args.session_id, "sessionId"),
        tenant_id=_require_text(args.tenant_id, "tenantId"),
        project_id=_require_text(args.project_id, "projectId"),
        workspace_key=_require_text(args.workspace_key, "workspaceKey"),
        actor_id=_require_text(args.actor_id, "actorId"),
    )


def _build_s3_client(config: SmokeConfig) -> Any:
    """构造 boto3 S3 client。

    boto3 只在 smoke 真正运行时导入，保持普通 Python Runtime 单测不被对象存储 SDK 绑定。
    """

    try:
        import boto3  # type: ignore[import-not-found]
    except ImportError as exc:
        raise RuntimeError("缺少 boto3，请先执行：pip install -e \"python-ai-runtime[object-store]\"") from exc
    client_kwargs: dict[str, Any] = {
        "endpoint_url": config.endpoint_url,
        "aws_access_key_id": config.access_key,
        "aws_secret_access_key": config.secret_key,
    }
    if config.region:
        client_kwargs["region_name"] = config.region
    return boto3.client("s3", **client_kwargs)


def _ensure_bucket(client: Any, bucket: str, region: str | None) -> None:
    """确认 bucket 存在，不存在时创建。

    这里不打印 bucket 名。bucket 属于对象存储基础设施定位信息，只应以 digest 形式进入输出摘要。
    """

    try:
        client.head_bucket(Bucket=bucket)
        return
    except Exception:
        create_kwargs: dict[str, Any] = {"Bucket": bucket}
        if region and region != "us-east-1":
            create_kwargs["CreateBucketConfiguration"] = {"LocationConstraint": region}
        client.create_bucket(**create_kwargs)


def _write_rag_artifact(config: SmokeConfig, client: Any):
    """使用生产 writer 写入一条 RAG answer artifact。"""

    writer = S3CompatibleRagAnswerArtifactWriter(
        bucket=config.bucket,
        s3_client=client,
        object_key_root_prefix=config.object_root_prefix,
    )
    return writer.write(
        write_input=RagAnswerArtifactWriteInput(
            command_id=config.command_id,
            run_id=config.run_id,
            session_id=config.session_id,
            query_ref="rag-query:sha256:miniosmokeabcdef123456",
            tenant_id=config.tenant_id,
            project_id=config.project_id,
            workspace_key=config.workspace_key,
            actor_id=config.actor_id,
            trace_id="trace-rag-minio-smoke",
        ),
        result=_pipeline_result(),
    )


def _pipeline_result() -> RagPipelineResult:
    """构造包含正文级 RAG 字段的烟测结果。"""

    return RagPipelineResult(
        answer=f"真实对象存储 smoke 答案正文：{ANSWER_MARKER}",
        citations=(
            RagCitation(
                citation_id="C1",
                document_id="doc-rag-minio-smoke",
                chunk_id="chunk-rag-minio-smoke-001",
                title="DataSmart RAG MinIO Smoke",
                source_uri="minio-object:knowledge-base/rag-minio-smoke",
                snippet=f"真实对象存储 smoke 引用片段：{CITATION_MARKER}",
                final_score=0.93,
            ),
        ),
        selected_chunks=(),
        compressed_context=f"真实对象存储 smoke 压缩上下文：{CONTEXT_MARKER}",
        retrieval_summary={
            "candidateCount": 3,
            "evidenceAcceptedCount": 1,
            "selectedCount": 1,
            "weakEvidenceRejectedCount": 2,
            "payloadPolicy": "LOW_SENSITIVE_RAG_RETRIEVAL_SUMMARY_ONLY",
        },
        model_summary={"provider": "smoke-dry-run", "modelFamily": "replaceable-open-model"},
        generated=True,
    )


def _verify_object(client: Any, bucket: str, object_name: str, expected_sha256: str) -> dict[str, Any]:
    """通过 head_object/get_object 验证对象可读且正文完整。"""

    head = client.head_object(Bucket=bucket, Key=object_name)
    response = client.get_object(Bucket=bucket, Key=object_name)
    body = response["Body"].read()
    actual_sha256 = hashlib.sha256(body).hexdigest()
    if actual_sha256 != expected_sha256:
        raise RuntimeError("对象正文 sha256 与 writer 返回值不一致")
    body_text = body.decode("utf-8")
    for marker in (ANSWER_MARKER, CONTEXT_MARKER, CITATION_MARKER):
        if marker not in body_text:
            raise RuntimeError("对象正文缺少 RAG smoke 标记，说明写入或读取链路不完整")
    return {
        "contentLengthBytes": int(head.get("ContentLength") or len(body)),
        "contentType": str(head.get("ContentType") or "application/octet-stream").lower(),
        "etagDigest": _digest(str(head.get("ETag") or "")) if head.get("ETag") else None,
        "metadataKeyCount": len(head.get("Metadata") or {}),
        "bodySha256": actual_sha256,
    }


def _low_sensitive_summary(config: SmokeConfig, write_result: Any, object_name: str, object_facts: Mapping[str, Any]) -> dict[str, Any]:
    """构造低敏 smoke 结果摘要。"""

    artifact_summary = write_result.to_summary()
    return {
        "accepted": True,
        "decision": "RAG_ARTIFACT_MINIO_SMOKE_SUCCEEDED",
        "artifactWrite": artifact_summary,
        "objectStoreProbeFacts": {
            "objectAvailable": True,
            "contentLengthBytes": object_facts["contentLengthBytes"],
            "contentType": object_facts["contentType"],
            "metadataKeyCount": object_facts["metadataKeyCount"],
            "etagDigest": object_facts["etagDigest"],
            "objectNameDigest": _digest(object_name),
            "endpointDigest": _digest(config.endpoint_url),
            "bucketDigest": _digest(config.bucket),
            "bodySha256MatchesWriter": object_facts["bodySha256"] == write_result.content_sha256,
        },
        "javaControlPlaneContract": {
            "workerReceipt": {
                "commandId": config.command_id,
                "runId": config.run_id,
                "sessionId": config.session_id,
                "outcome": "RAG_QUERY_COMPLETED",
                "preCheckPassed": True,
                "sideEffectStarted": False,
                "sideEffectExecuted": False,
                "workerLeaseRequired": False,
                "artifactReferenceType": write_result.artifact_reference_type,
                "artifactReference": write_result.artifact_reference,
                "artifactAvailable": True,
                "toolCode": "knowledge.rag.query",
                "workerReceiptMode": "READ_ONLY_QUERY_SUMMARY",
                "payloadPolicy": "SUMMARY_ONLY_NO_QUESTION_NO_ANSWER_NO_CONTEXT_NO_BUCKET_KEY",
            },
            "bodyReadGrantRequest": {
                "commandId": config.command_id,
                "artifactReference": write_result.artifact_reference,
                "artifactReferenceType": write_result.artifact_reference_type,
                "readPurpose": "RAG_ANSWER_VIEW",
                "requestedContentMode": "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
                "toolCode": "knowledge.rag.query",
                "requesterComponent": "agent-runtime",
            },
            "objectStoreProbeRequestTemplate": {
                "previousGrantDecisionReference": "<from-java-body-read-grants>",
                "requestedProbeBytes": 4096,
                "expectedDecision": "OBJECT_STORE_PROBE_VERIFIED_NO_BODY_RETURNED",
            },
            "finalCheckRequestTemplate": {
                "previousGrantDecisionReference": "<from-java-body-read-grants>",
                "readPurpose": "RAG_ANSWER_VIEW",
                "requestedContentMode": "TRUNCATED_TEXT_PREVIEW",
                "sanitizedPreviewTextSource": "object-store-service-after-dlp",
            },
        },
        "payloadPolicy": "SMOKE_SUMMARY_ONLY_NO_ENDPOINT_NO_BUCKET_NO_OBJECT_KEY_NO_ANSWER_NO_CONTEXT_NO_TOKEN",
    }


def _assert_summary_is_low_sensitive(config: SmokeConfig, object_name: str, summary: Mapping[str, Any]) -> None:
    """防止 smoke 输出把对象存储定位、凭据或 RAG 正文带到终端。"""

    serialized = json.dumps(summary, ensure_ascii=False)
    forbidden_values = [
        config.endpoint_url,
        config.bucket,
        config.access_key,
        config.secret_key,
        object_name,
        ANSWER_MARKER,
        CONTEXT_MARKER,
        CITATION_MARKER,
    ]
    leaked = [value for value in forbidden_values if value and value in serialized]
    if leaked:
        raise RuntimeError("smoke summary 包含高敏值，已拒绝输出")


def _safe_error_message(exc: Exception) -> str:
    """生成低敏错误说明。"""

    raw = str(exc)
    if not raw:
        return "RAG artifact MinIO smoke 执行失败，请检查本地对象存储环境。"
    lowered = raw.lower()
    if any(token in lowered for token in ("access", "secret", "password", "token", "http://", "https://", "bucket")):
        return "RAG artifact MinIO smoke 执行失败，错误详情包含基础设施或凭据相关信息，已做低敏隐藏。"
    return raw[:240]


def _first_env(name: str, fallback: str) -> str:
    """读取环境变量，不存在时使用 fallback。"""

    return _optional_text(os.getenv(name)) or fallback


def _optional_text(value: Any) -> str | None:
    """读取可选文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _require_text(value: Any, field_name: str) -> str:
    """读取必填文本。"""

    text = _optional_text(value)
    if text is None:
        raise ValueError(f"{field_name} 不能为空")
    return text


def _digest(value: str) -> str:
    """生成低敏短摘要。"""

    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


if __name__ == "__main__":
    raise SystemExit(main())
