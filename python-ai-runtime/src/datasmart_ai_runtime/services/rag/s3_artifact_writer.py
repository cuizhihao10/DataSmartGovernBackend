"""MinIO/S3-compatible RAG answer artifact writer。

本模块只承载“把 RAG 正文级结果写入对象存储”的 adapter 逻辑。核心协议、local writer 和环境装配仍在
`artifact_writer.py`，是为了让职责更清晰：
- `artifact_writer.py` 说明 RAG answer artifact 的领域契约；
- `s3_artifact_writer.py` 说明 MinIO/S3-compatible 对象存储如何安全落地；
- `command_worker.py` 只关心 writer 协议，不直接依赖 boto3、MinIO SDK 或 bucket/key。

安全边界：
- RAG answer、citations、compressedContext 可以写入对象正文，但不进入 Java receipt、runtime event 或 checkpoint；
- Python writer 对外只返回 artifactReference、hash、byteSize、objectKeyDigest 等低敏事实；
- bucket、objectName、endpoint、accessKey、secretKey、签名 URL 都只能在 writer 内部短生命周期使用；
- objectName 生成规则与 Java `AgentToolActionArtifactMinioObjectLocator` 对齐，保证后续 artifact grant/read 能按同一引用定位。
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Mapping

from datasmart_ai_runtime.services.rag.artifact_writer import (
    RAG_ANSWER_ARTIFACT_SCHEMA_VERSION,
    RagAnswerArtifactWriteInput,
    RagAnswerArtifactWriteResult,
    _artifact_document,
    _artifact_reference,
    _optional_text,
)
from datasmart_ai_runtime.services.rag.models import RagPipelineResult


_SAFE_OBJECT_LOGICAL_PATH_PATTERN = re.compile(r"^[A-Za-z0-9._/-]{1,512}$")
_DEFAULT_MINIO_OBJECT_ROOT_PREFIX = "agent-runtime/artifacts"
_DEFAULT_REFERENCE_OBJECT_KEY_PREFIXES = {
    "agent-artifact:": "agent-artifact",
    "artifact:": "artifact",
    "artifact-ref:": "artifact-ref",
    "command-output:": "command-output",
    "task-artifact:": "task-artifact",
    "minio-object:": "minio-object",
}


class S3CompatibleRagAnswerArtifactWriter:
    """MinIO/S3-compatible RAG answer artifact writer。

    这个实现用于把 RAG answer、citations、compressedContext 等正文级结果写入真实对象存储。它遵守两个核心原则：
    - 对外仍只返回低敏 `artifactReference`、hash、字节数和对象 key 摘要，不返回 bucket/key、endpoint 或签名 URL；
    - 内部 objectName 的生成规则与 Java `AgentToolActionArtifactMinioObjectLocator` 对齐，保证 Python 写入后的对象能被
      Java artifact grant/final-check 链路通过同一个低敏引用再次定位。

    依赖策略：
    - 单元测试和本地学习可以注入 fake `s3_client`，完全不需要安装 boto3；
    - 真实 MinIO/S3-compatible 环境未注入 client 时，才会懒加载 boto3。这样 Python Runtime 的默认依赖仍然保持轻量，
      不会让只做 Agent 规划、LangGraph checkpoint 或离线 RAG 单测的场景被对象存储 SDK 绑定。
    """

    def __init__(
        self,
        *,
        bucket: str,
        s3_client: Any | None = None,
        endpoint_url: str | None = None,
        access_key_id: str | None = None,
        secret_access_key: str | None = None,
        region_name: str | None = None,
        object_key_root_prefix: str = _DEFAULT_MINIO_OBJECT_ROOT_PREFIX,
        reference_prefix_object_key_prefixes: Mapping[str, str] | None = None,
        storage_backend: str = "minio-s3-compatible-controlled-artifact",
    ) -> None:
        self._bucket = _require_config_text(bucket, "RAG artifact MinIO/S3 bucket")
        self._s3_client = s3_client
        self._endpoint_url = _optional_text(endpoint_url)
        self._access_key_id = _optional_text(access_key_id)
        self._secret_access_key = _optional_text(secret_access_key)
        self._region_name = _optional_text(region_name)
        self._object_key_root_prefix = object_key_root_prefix
        self._reference_prefix_object_key_prefixes = dict(
            reference_prefix_object_key_prefixes or _DEFAULT_REFERENCE_OBJECT_KEY_PREFIXES
        )
        self._storage_backend = storage_backend

    def write(
        self,
        *,
        write_input: RagAnswerArtifactWriteInput,
        result: RagPipelineResult,
    ) -> RagAnswerArtifactWriteResult:
        """把 RAG 正文写入 MinIO/S3-compatible 对象存储。

        写入步骤刻意保持显式，方便后续学习和排障：
        1. 先构造受控 JSON artifact body，仍然不保存原始 question 和原始文档正文；
        2. 计算 contentSha256，作为写入幂等、审计对账和 Java final-check 的核心指纹；
        3. 生成或校验低敏 artifactReference；
        4. 按 Java locator 的映射规则得到内部 objectName；
        5. 调用 S3 `put_object` 写入正文，并把低敏元数据放到 object metadata；
        6. 返回 metadata-only 结果，不泄露 bucket、objectName、endpoint、accessKey 或 secret。
        """

        artifact_document = _artifact_document(write_input, result)
        body = json.dumps(artifact_document, ensure_ascii=False, sort_keys=True, indent=2).encode("utf-8")
        content_sha256 = hashlib.sha256(body).hexdigest()
        artifact_reference = _artifact_reference(write_input, content_sha256)
        object_name = _minio_object_name_from_reference(
            artifact_reference,
            object_key_root_prefix=self._object_key_root_prefix,
            reference_prefix_object_key_prefixes=self._reference_prefix_object_key_prefixes,
        )
        self._client().put_object(
            Bucket=self._bucket,
            Key=object_name,
            Body=body,
            ContentType="application/json; charset=utf-8",
            Metadata=_s3_object_metadata(write_input, content_sha256, result),
        )
        return RagAnswerArtifactWriteResult(
            artifact_reference=artifact_reference,
            artifact_reference_type="AGENT_RAG_ANSWER_ARTIFACT",
            storage_backend=self._storage_backend,
            byte_size=len(body),
            content_sha256=content_sha256,
            citation_count=len(result.citations),
            selected_chunk_count=len(result.selected_chunks),
            generated=bool(result.generated),
            object_key_digest=hashlib.sha256(object_name.encode("utf-8")).hexdigest()[:32],
        )

    def diagnostics(self) -> dict[str, Any]:
        """返回 writer 低敏诊断信息。

        诊断接口只暴露配置是否存在、bucket/endpoint 的摘要和映射数量，不能返回真实 bucket、endpoint、object root、
        accessKey、secretKey 或任何 objectName。真实定位只能在 writer 内部短生命周期使用。
        """

        return {
            "writer": "S3CompatibleRagAnswerArtifactWriter",
            "enabled": True,
            "storageBackend": self._storage_backend,
            "bucketDigest": _digest_for_diagnostics(self._bucket),
            "endpointConfigured": bool(self._endpoint_url),
            "endpointDigest": _digest_for_diagnostics(self._endpoint_url) if self._endpoint_url else None,
            "objectRootPrefixDigest": _digest_for_diagnostics(self._object_key_root_prefix),
            "referencePrefixMappingCount": len(self._reference_prefix_object_key_prefixes),
            "payloadPolicy": "RAG_ARTIFACT_WRITER_DIAGNOSTICS_NO_BUCKET_NO_KEY_NO_ENDPOINT_NO_BODY",
        }

    def _client(self) -> Any:
        """懒加载 S3 client。

        这里不在构造函数里导入 boto3，是为了让默认单测、离线学习和只使用 local writer 的环境不需要安装对象存储 SDK。
        如果生产环境启用了 MinIO writer 但镜像没有安装 `python-ai-runtime[object-store]`，异常会在实际写入时抛出，
        再交给 Java outbox 重试/死信治理，而不是生成一个“receipt 成功但正文未写入”的假成功。
        """

        if self._s3_client is not None:
            return self._s3_client
        try:
            import boto3  # type: ignore[import-not-found]
        except ImportError as exc:  # pragma: no cover - 本地单测通过 fake client 覆盖协议行为。
            raise RuntimeError(
                "启用 MinIO/S3 RAG artifact writer 需要安装可选依赖：pip install -e python-ai-runtime[object-store]"
            ) from exc
        client_kwargs: dict[str, Any] = {}
        if self._endpoint_url:
            client_kwargs["endpoint_url"] = self._endpoint_url
        if self._access_key_id:
            client_kwargs["aws_access_key_id"] = self._access_key_id
        if self._secret_access_key:
            client_kwargs["aws_secret_access_key"] = self._secret_access_key
        if self._region_name:
            client_kwargs["region_name"] = self._region_name
        self._s3_client = boto3.client("s3", **client_kwargs)
        return self._s3_client


def reference_prefix_mapping_from_env(env: Mapping[str, str]) -> Mapping[str, str]:
    """读取可选的前缀映射 JSON。

    默认映射与 Java `AgentArtifactObjectStoreMinioProperties` 保持一致。只有当部署方确实改了 Java 侧映射时，
    才需要通过 `DATASMART_RAG_ARTIFACT_MINIO_REFERENCE_PREFIX_OBJECT_KEY_PREFIXES_JSON` 覆盖。
    """

    raw = _optional_text(env.get("DATASMART_RAG_ARTIFACT_MINIO_REFERENCE_PREFIX_OBJECT_KEY_PREFIXES_JSON"))
    if raw is None:
        return _DEFAULT_REFERENCE_OBJECT_KEY_PREFIXES
    parsed = json.loads(raw)
    if not isinstance(parsed, dict):
        raise ValueError("RAG artifact MinIO prefix mapping 必须是 JSON object")
    mapping = {str(key): str(value) for key, value in parsed.items()}
    if not mapping:
        raise ValueError("RAG artifact MinIO prefix mapping 不能为空")
    return mapping


def _minio_object_name_from_reference(
    artifact_reference: str,
    *,
    object_key_root_prefix: str,
    reference_prefix_object_key_prefixes: Mapping[str, str],
) -> str:
    """按 Java locator 规则把低敏 artifactReference 映射成内部 objectName。"""

    reference = artifact_reference.strip()
    prefix, mapped_prefix = _resolve_reference_mapping(reference, reference_prefix_object_key_prefixes)
    logical_path = reference[len(prefix):]
    _validate_object_logical_path(logical_path)
    return _join_object_name(
        _normalize_object_prefix(object_key_root_prefix),
        _normalize_object_prefix(mapped_prefix),
        logical_path,
    )


def _resolve_reference_mapping(reference: str, mapping: Mapping[str, str]) -> tuple[str, str]:
    """选择最长 artifactReference 前缀映射，保持与 Java locator 一致。"""

    if not mapping:
        raise ValueError("RAG artifact MinIO 前缀映射不能为空")
    lowered = reference.lower()
    candidates = [
        (prefix, object_prefix)
        for prefix, object_prefix in mapping.items()
        if lowered.startswith(str(prefix).lower())
    ]
    if not candidates:
        raise ValueError("artifactReference 未配置 MinIO objectName 映射前缀")
    return max(candidates, key=lambda item: len(str(item[0])))


def _validate_object_logical_path(logical_path: str) -> None:
    """校验 object logical path 不能携带 URL、路径逃逸、bucket/key 明文或平台路径形态。"""

    value = _require_config_text(logical_path, "RAG artifact logical path")
    lowered = value.lower()
    if (
        not _SAFE_OBJECT_LOGICAL_PATH_PATTERN.fullmatch(value)
        or value.startswith("/")
        or value.endswith("/")
        or "//" in value
        or "../" in lowered
        or "..\\" in lowered
        or "http://" in lowered
        or "https://" in lowered
        or "bucket" in lowered
        or "object-key" in lowered
        or "object_key" in lowered
        or re.match(r"^[a-z]:\\.*", lowered)
        or lowered.startswith("\\\\")
    ):
        raise ValueError("artifactReference logical path 不符合受控 MinIO objectName 映射规则")


def _normalize_object_prefix(value: Any) -> str:
    """规范化 objectName 前缀，避免把路径逃逸或连续分隔符写入对象存储命名空间。"""

    text = _optional_text(value)
    if text is None:
        return ""
    normalized = text.replace("\\", "/")
    while normalized.startswith("/"):
        normalized = normalized[1:]
    while normalized.endswith("/"):
        normalized = normalized[:-1]
    lowered = normalized.lower()
    if "../" in lowered or "//" in normalized or "..\\" in lowered:
        raise ValueError("RAG artifact objectName 前缀不能包含路径逃逸或连续分隔符")
    return normalized


def _join_object_name(*parts: str) -> str:
    """连接 objectName 片段。"""

    return "/".join(part for part in parts if part)


def _s3_object_metadata(
    write_input: RagAnswerArtifactWriteInput,
    content_sha256: str,
    result: RagPipelineResult,
) -> dict[str, str]:
    """构造可写入 S3 object metadata 的低敏元数据。"""

    return {
        "schema-version": RAG_ANSWER_ARTIFACT_SCHEMA_VERSION,
        "payload-policy": "rag-answer-controlled-artifact-no-question-no-raw-document-body",
        "content-sha256": content_sha256,
        "query-ref-digest": hashlib.sha256(write_input.query_ref.encode("utf-8")).hexdigest()[:32],
        "citation-count": str(len(result.citations)),
        "selected-chunk-count": str(len(result.selected_chunks)),
        "generated": str(bool(result.generated)).lower(),
    }


def _require_config_text(value: Any, field_name: str) -> str:
    """读取必填配置文本。"""

    text = _optional_text(value)
    if text is None:
        raise ValueError(f"{field_name} 不能为空")
    return text


def _digest_for_diagnostics(value: str) -> str:
    """生成诊断用短摘要，避免直接暴露 endpoint、bucket 或 object root。"""

    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


__all__ = [
    "S3CompatibleRagAnswerArtifactWriter",
    "reference_prefix_mapping_from_env",
]
