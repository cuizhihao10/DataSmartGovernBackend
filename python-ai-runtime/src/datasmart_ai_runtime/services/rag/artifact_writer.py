"""RAG 答案产物写入器。

本模块解决的是 RAG command worker 闭环里一个非常关键的商业化边界：模型答案、引用片段和压缩上下文
不能继续停留在 Java receipt、runtime event 或 projection 里，但它们又必须被安全保存，方便用户在通过
权限校验后查看完整答案。因此这里把“正文级结果”写入受控 artifact，把“控制面结果”收敛为
artifactReference、hash、字节数、引用数量和存储后端等低敏元数据。

为什么先实现本地文件 writer，而不是直接绑定 MinIO SDK？
- 当前 Python Runtime 的默认依赖保持很轻，单元测试和本地学习不应强制启动 MinIO；
- artifact writer 被定义成协议接口，后续替换为 MinIO/S3-compatible writer 时不需要改 RAG pipeline、
  command worker、receipt builder 或 Java dispatcher 合同；
- 本地 writer 仍然按对象存储思路工作：生成稳定 artifactReference、写 JSON 对象、记录 sha256、使用
  原子 replace，避免把它做成随意的临时文件输出。

安全边界：
- artifact body 可以包含 answer、citations、compressedContext，因为它不进入控制面响应；
- artifact body 不保存原始 question，避免把用户问题从短生命周期输入扩散为长期可读对象；
- write result 的 `to_summary()` 只返回低敏元数据，不返回本地路径、正文、sourceUri、snippet 或模型输出。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol
from uuid import uuid4

from datasmart_ai_runtime.services.rag.models import RagPipelineResult


RAG_ANSWER_ARTIFACT_SCHEMA_VERSION = "datasmart.python-ai-runtime.rag-answer-artifact.v1"
RAG_ANSWER_ARTIFACT_PAYLOAD_POLICY = (
    "RAG_ANSWER_BODY_STORED_IN_CONTROLLED_ARTIFACT_NO_QUESTION_NO_RAW_DOCUMENT_BODY"
)

_SAFE_REFERENCE_PATTERN = re.compile(r"^[a-zA-Z0-9_.:/=@+-]{1,240}$")
_SAFE_REFERENCE_PREFIXES = ("agent-artifact:", "artifact:", "minio-object:")


@dataclass(frozen=True)
class RagAnswerArtifactWriteInput:
    """一次 RAG answer artifact 写入所需的低敏定位事实。

    字段说明：
    - `command_id/run_id/session_id`：来自 Java outbox/Agent run，用于形成稳定对象命名空间；
    - `query_ref`：原始问题的 hash/ref，不是 question 正文；
    - `tenant_id/project_id/workspace_key/actor_id`：权限与隔离维度，只用于对象分区和审计元数据；
    - `requested_artifact_reference`：Java 或上游已经预分配的 artifactReference；如果为空，writer 会生成一个；
    - `trace_id`：链路排障字段，只进入 artifact metadata，不进入控制面摘要正文。
    """

    command_id: str
    run_id: str
    session_id: str
    query_ref: str
    tenant_id: str = "*"
    project_id: str = "*"
    workspace_key: str = "*"
    actor_id: str = "anonymous"
    requested_artifact_reference: str | None = None
    trace_id: str | None = None


@dataclass(frozen=True)
class RagAnswerArtifactWriteResult:
    """artifact 写入结果的低敏表达。

    该对象会被 command worker summary、receipt builder 和 LangGraph checkpoint 消费，因此它必须保持
    metadata-only。特别注意：这里不保存本地真实路径、不保存对象存储 bucket/key 明文、不保存 answer、
    compressedContext、citation snippet 或 sourceUri。后续 Java 控制面如果要读取正文，必须走 artifact grant。
    """

    artifact_reference: str
    artifact_reference_type: str
    storage_backend: str
    byte_size: int
    content_sha256: str
    citation_count: int
    selected_chunk_count: int
    generated: bool
    object_key_digest: str
    payload_policy: str = RAG_ANSWER_ARTIFACT_PAYLOAD_POLICY

    def to_summary(self) -> dict[str, Any]:
        """返回可进入控制面、checkpoint 和测试断言的低敏摘要。"""

        return {
            "artifactReference": self.artifact_reference,
            "artifactReferenceType": self.artifact_reference_type,
            "storageBackend": self.storage_backend,
            "byteSize": self.byte_size,
            "contentSha256": self.content_sha256,
            "citationCount": self.citation_count,
            "selectedChunkCount": self.selected_chunk_count,
            "generated": self.generated,
            "objectKeyDigest": self.object_key_digest,
            "payloadPolicy": self.payload_policy,
        }


class RagAnswerArtifactWriter(Protocol):
    """RAG answer artifact 写入协议。

    未来 MinIO/S3、数据库大对象、企业文档系统或加密对象存储都应实现这个协议，而不是让
    `RagCommandWorkerRunner` 依赖某个具体 SDK。这样 RAG 执行、artifact 存储、Java receipt 三个责任可以
    独立演进。
    """

    def write(
        self,
        *,
        write_input: RagAnswerArtifactWriteInput,
        result: RagPipelineResult,
    ) -> RagAnswerArtifactWriteResult:
        """写入 RAG 正文级结果，并返回低敏 artifact 元数据。"""


class LocalFileRagAnswerArtifactWriter:
    """本地文件型 RAG answer artifact writer。

    这个实现主要服务于本地闭环、CI 单元测试和没有 MinIO 的预验证环境。它不是“临时乱写文件”，而是按对象存储
    语义做了几个约束：
    - 写入根目录由配置控制，并通过 resolved path 校验阻断路径逃逸；
    - 文件名基于 artifactReference/hash 派生，不使用用户输入作为真实路径；
    - 写入采用临时文件 + replace，避免进程崩溃时留下半截 JSON；
    - 返回值只暴露 contentSha256、byteSize、objectKeyDigest，不暴露真实磁盘路径。
    """

    def __init__(self, root_dir: str | os.PathLike[str]) -> None:
        self._root_dir = Path(root_dir).expanduser()

    def write(
        self,
        *,
        write_input: RagAnswerArtifactWriteInput,
        result: RagPipelineResult,
    ) -> RagAnswerArtifactWriteResult:
        """把 RAG answer/citations/compressedContext 写成受控 JSON artifact。"""

        artifact_document = _artifact_document(write_input, result)
        body = json.dumps(artifact_document, ensure_ascii=False, sort_keys=True, indent=2).encode("utf-8")
        content_sha256 = hashlib.sha256(body).hexdigest()
        artifact_reference = _artifact_reference(write_input, content_sha256)
        object_key = _object_key(write_input, artifact_reference, content_sha256)
        target_path = self._safe_target_path(object_key)
        target_path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = target_path.with_name(f".{target_path.name}.{uuid4().hex}.tmp")
        temporary_path.write_bytes(body)
        temporary_path.replace(target_path)
        return RagAnswerArtifactWriteResult(
            artifact_reference=artifact_reference,
            artifact_reference_type="AGENT_RAG_ANSWER_ARTIFACT",
            storage_backend="local-file-controlled-artifact",
            byte_size=len(body),
            content_sha256=content_sha256,
            citation_count=len(result.citations),
            selected_chunk_count=len(result.selected_chunks),
            generated=bool(result.generated),
            object_key_digest=hashlib.sha256(object_key.encode("utf-8")).hexdigest()[:32],
        )

    def diagnostics(self) -> dict[str, Any]:
        """返回 writer 诊断信息，避免泄露本地绝对路径。"""

        return {
            "writer": "LocalFileRagAnswerArtifactWriter",
            "enabled": True,
            "storageBackend": "local-file-controlled-artifact",
            "rootDigest": hashlib.sha256(str(self._root_dir).encode("utf-8")).hexdigest()[:16],
            "payloadPolicy": "RAG_ARTIFACT_WRITER_DIAGNOSTICS_NO_LOCAL_PATH_NO_BODY",
        }

    def _safe_target_path(self, object_key: str) -> Path:
        """把对象 key 安全映射为 root 下的文件路径。"""

        root = self._root_dir.resolve()
        target = (root / object_key).resolve()
        if root != target and root not in target.parents:
            raise ValueError("RAG artifact object key 不能逃逸出配置的 artifact root")
        return target


def rag_answer_artifact_writer_from_env() -> RagAnswerArtifactWriter | None:
    """从环境变量构建 RAG answer artifact writer。

    支持的最小配置：
    - `DATASMART_RAG_ARTIFACT_WRITER_ENABLED=true`：启用 writer；
    - `DATASMART_RAG_ARTIFACT_STORE_BACKEND=local`：当前唯一实现，后续可扩展 `minio`；
    - `DATASMART_RAG_ARTIFACT_LOCAL_ROOT=.datasmart-ai-runtime/artifacts/rag`：本地落盘根目录。
    """

    if not _truthy(os.getenv("DATASMART_RAG_ARTIFACT_WRITER_ENABLED")):
        return None
    backend = (os.getenv("DATASMART_RAG_ARTIFACT_STORE_BACKEND") or "local").strip().lower()
    if backend != "local":
        raise ValueError("当前仅实现 local RAG artifact writer；MinIO/S3 adapter 应实现同一协议后再启用")
    root_dir = os.getenv("DATASMART_RAG_ARTIFACT_LOCAL_ROOT") or ".datasmart-ai-runtime/artifacts/rag"
    return LocalFileRagAnswerArtifactWriter(root_dir)


def _artifact_document(write_input: RagAnswerArtifactWriteInput, result: RagPipelineResult) -> dict[str, Any]:
    """构造 artifact 正文。

    这里允许保存 answer、citations、compressedContext，因为该对象不会直接返回给 Java 控制面。与此同时，
    原始 question 仍然不保存；如果后续需要审计“用户到底问了什么”，应通过受控 queryRef 反查专门的加密参数库，
    而不是把 question 扩散到每个 answer artifact。
    """

    return {
        "schemaVersion": RAG_ANSWER_ARTIFACT_SCHEMA_VERSION,
        "payloadPolicy": RAG_ANSWER_ARTIFACT_PAYLOAD_POLICY,
        "queryRef": write_input.query_ref,
        "commandId": write_input.command_id,
        "runId": write_input.run_id,
        "sessionId": write_input.session_id,
        "tenantId": write_input.tenant_id,
        "projectId": write_input.project_id,
        "workspaceKey": write_input.workspace_key,
        "actorId": write_input.actor_id,
        "traceId": write_input.trace_id,
        "generated": bool(result.generated),
        "answer": result.answer,
        "citations": [citation.to_summary() for citation in result.citations],
        "compressedContext": result.compressed_context,
        "retrievalSummary": dict(result.retrieval_summary),
        "modelSummary": dict(result.model_summary),
        "selectedChunkSummaries": [chunk.to_summary() for chunk in result.selected_chunks],
        "bodyPolicy": {
            "questionStored": False,
            "rawDocumentBodyStored": False,
            "answerStored": True,
            "compressedContextStored": bool(result.compressed_context),
            "citationSnippetStored": bool(result.citations),
        },
    }


def _artifact_reference(write_input: RagAnswerArtifactWriteInput, content_sha256: str) -> str:
    """生成或校验 artifactReference。"""

    requested = _optional_text(write_input.requested_artifact_reference)
    if requested:
        return _safe_artifact_reference(requested)
    run_id = _safe_reference_part(write_input.run_id)
    command_id = _safe_reference_part(write_input.command_id)
    return f"agent-artifact:{run_id}/{command_id}/rag-answer-{content_sha256[:16]}.json"


def _safe_artifact_reference(value: str) -> str:
    """确认 artifactReference 是受控引用，而不是 URL、路径或正文。"""

    reference = value.strip()
    lowered = reference.lower()
    if not lowered.startswith(_SAFE_REFERENCE_PREFIXES):
        raise ValueError("RAG artifactReference 必须使用受控 artifact scheme")
    if "://" in lowered or "\\" in reference or ".." in lowered or "\n" in reference or "\r" in reference:
        raise ValueError("RAG artifactReference 不能是 URL、真实路径、路径逃逸或多行正文")
    if not _SAFE_REFERENCE_PATTERN.fullmatch(reference):
        raise ValueError("RAG artifactReference 包含非法字符")
    return reference


def _object_key(write_input: RagAnswerArtifactWriteInput, artifact_reference: str, content_sha256: str) -> str:
    """生成内部对象 key。

    对象 key 不直接等于 artifactReference，而是重新按 tenant/project/run/command/hash 分区。这样即使上游传入
    一个合法但较长的引用，也不会影响真实文件路径结构。
    """

    tenant = _safe_path_part(write_input.tenant_id, fallback="tenant")
    project = _safe_path_part(write_input.project_id, fallback="project")
    run_id = _safe_path_part(write_input.run_id, fallback="run")
    command_id = _safe_path_part(write_input.command_id, fallback="command")
    reference_digest = hashlib.sha256(artifact_reference.encode("utf-8")).hexdigest()[:16]
    return "/".join(
        (
            tenant,
            project,
            run_id,
            command_id,
            f"rag-answer-{reference_digest}-{content_sha256[:16]}.json",
        )
    )


def _safe_path_part(value: Any, *, fallback: str) -> str:
    """把外部 ID 压成安全路径片段。"""

    text = _optional_text(value) or fallback
    safe = "".join(ch if ch.isalnum() or ch in "_.=-" else "_" for ch in text)
    return safe[:96] or fallback


def _safe_reference_part(value: Any) -> str:
    """把 run/command ID 压成 artifactReference 可用片段。"""

    text = _optional_text(value) or "unknown"
    safe = "".join(ch if ch.isalnum() or ch in "_.=-" else "-" for ch in text)
    return safe[:96] or "unknown"


def _optional_text(value: Any) -> str | None:
    """读取可选文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _truthy(value: str | None) -> bool:
    """兼容常见环境变量布尔写法。"""

    return str(value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


__all__ = [
    "RAG_ANSWER_ARTIFACT_PAYLOAD_POLICY",
    "RAG_ANSWER_ARTIFACT_SCHEMA_VERSION",
    "LocalFileRagAnswerArtifactWriter",
    "RagAnswerArtifactWriteInput",
    "RagAnswerArtifactWriteResult",
    "RagAnswerArtifactWriter",
    "rag_answer_artifact_writer_from_env",
]
