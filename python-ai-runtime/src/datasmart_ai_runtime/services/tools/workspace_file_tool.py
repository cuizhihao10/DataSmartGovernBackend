"""受控 Workspace 文件读写工具。

Codex、Claude Code 这类 Agent 的核心能力之一是读取文件、写入文件和基于文件继续推理。但在企业级
数据治理平台里，文件工具绝不能等价于“给模型开放本机文件系统”。本模块实现的是受控 workspace 内
的最小读写闭环：

1. 调用方必须提供真实 workspace root 与低敏 workspaceReference；
2. 文件路径必须是相对路径，不能是绝对路径、URL、盘符路径或 `..` 逃逸路径；
3. 默认拒绝隐藏目录、凭据文件和常见敏感配置文件；
4. 读写都受字节预算约束，读结果正文只留在 `content` 内部字段，`to_summary()` 永远不返回正文；
5. 写操作返回内容 hash、字节数和 artifactReference，不返回写入正文或真实文件路径；
6. 后续接入 Java durable worker、MinIO artifact、DLP 扫描或容器沙箱时，可以替换存储适配层，而不用推翻上层工具合同。

这不是最终的生产文件沙箱。真正商用环境还应继续补：
- Java agent-runtime outbox/worker receipt；
- 对象存储 artifact grant、下载审计和 TTL；
- DLP/恶意内容扫描；
- 更强 OS/container 隔离与租户级配额。
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path, PurePosixPath
from typing import Any

from datasmart_ai_runtime.services.tools.controlled_command_worker_contract import (
    _is_safe_workspace_reference,
    _looks_sensitive,
)


WORKSPACE_FILE_TOOL_SCHEMA_VERSION = "datasmart.python-ai-runtime.workspace-file-tool.v1"
WORKSPACE_FILE_TOOL_PAYLOAD_POLICY = (
    "METADATA_ONLY_NO_FILE_BODY_NO_REAL_PATH_NO_PROMPT_NO_SQL_NO_TOOL_ARGUMENT_BODY"
)


class WorkspaceFileOperation(str, Enum):
    """受控文件工具支持的操作类型。"""

    READ_TEXT = "READ_TEXT"
    WRITE_TEXT = "WRITE_TEXT"


class WorkspaceFileOperationStatus(str, Enum):
    """受控文件工具的低敏结果状态。"""

    SUCCEEDED = "SUCCEEDED"
    BLOCKED = "BLOCKED"
    NOT_FOUND = "NOT_FOUND"
    CONFLICT = "CONFLICT"
    FAILED = "FAILED"


@dataclass(frozen=True)
class WorkspaceFileToolSettings:
    """Workspace 文件工具配置。

    字段说明：
    - `enabled`：默认关闭，避免本地学习或 preview 请求误触发真实文件读写；
    - `workspace_root_allowlist`：真实文件系统根目录白名单，只有这些目录及其子目录可被访问；
    - `max_read_bytes/max_write_bytes`：Python 侧硬预算，不依赖模型或上游请求自报；
    - `allow_partial_read`：超出读取预算时是否允许返回前 N 字节内部正文并标记 truncated；
    - `deny_hidden_paths`：默认拒绝 `.git`、`.env` 这类隐藏目录/文件；
    - `denied_path_segments/denied_file_names/denied_suffixes`：第一层低成本路径防护，生产还应叠加 DLP 与策略中心。
    """

    enabled: bool = False
    workspace_root_allowlist: tuple[str, ...] = field(default_factory=tuple)
    max_read_bytes: int = 64 * 1024
    max_write_bytes: int = 64 * 1024
    allow_partial_read: bool = True
    deny_hidden_paths: bool = True
    denied_path_segments: tuple[str, ...] = (
        ".git",
        ".ssh",
        ".aws",
        ".azure",
        ".kube",
        "__pycache__",
    )
    denied_file_names: tuple[str, ...] = (
        ".env",
        ".env.local",
        "id_rsa",
        "id_dsa",
        "known_hosts",
        "credentials",
    )
    denied_suffixes: tuple[str, ...] = (
        ".pem",
        ".key",
        ".pfx",
        ".p12",
        ".crt",
        ".sqlite",
        ".db",
    )


@dataclass(frozen=True)
class WorkspaceFileReadRequest:
    """受控文件读取请求。

    `relative_path` 是内部执行输入，可能包含业务文件名，因此不会进入 `to_summary()`；摘要只返回
    pathDigest 和 artifactReference。上层如果要给前端展示文件名，应由 Java 控制面根据权限另行解析。
    """

    session_id: str
    run_id: str
    operation_id: str
    workspace_root: str
    workspace_reference: str
    relative_path: str
    max_bytes: int | None = None
    encoding: str = "utf-8"


@dataclass(frozen=True)
class WorkspaceFileWriteRequest:
    """受控文件写入请求。

    `content` 是真实写入正文，只能在进程内短暂存在。它不会进入 summary、runtime event、projection
    或测试失败摘要。生产接入 Java durable action 后，应优先改为 payloadReference/objectReference，
    Python worker 在执行区内再读取正文。
    """

    session_id: str
    run_id: str
    operation_id: str
    workspace_root: str
    workspace_reference: str
    relative_path: str
    content: str
    overwrite: bool = False
    create_parent_directories: bool = False
    expected_sha256: str | None = None
    encoding: str = "utf-8"


@dataclass(frozen=True)
class WorkspaceFileOperationResult:
    """受控文件工具结果。

    `content` 只用于内部工具执行后继续推理，例如把安全读取的 Markdown 片段传给模型。调用 `to_summary()`
    时不会输出该字段，防止 runtime event、Java projection 或日志把文件正文扩散出去。
    """

    operation: WorkspaceFileOperation
    status: WorkspaceFileOperationStatus
    process_performed: bool
    path_digest: str
    artifact_reference: str | None = None
    content_sha256: str | None = None
    byte_count: int = 0
    truncated: bool = False
    issue_codes: tuple[str, ...] = ()
    evidence_codes: tuple[str, ...] = ()
    recommended_actions: tuple[str, ...] = ()
    content: str | None = None
    payload_policy: str = WORKSPACE_FILE_TOOL_PAYLOAD_POLICY

    def to_summary(self) -> dict[str, Any]:
        """输出低敏结果摘要。

        摘要只回答“发生了什么”和“如何对账”，不回答“文件里有什么”：
        - 不返回 content；
        - 不返回 relative path 或真实 workspace root；
        - 不返回写入正文、prompt、SQL、工具参数原文或内部 endpoint。
        """

        return {
            "schemaVersion": WORKSPACE_FILE_TOOL_SCHEMA_VERSION,
            "payloadPolicy": self.payload_policy,
            "operation": self.operation.value,
            "status": self.status.value,
            "processPerformed": self.process_performed,
            "pathDigest": self.path_digest,
            "artifactReference": self.artifact_reference,
            "contentSha256": self.content_sha256,
            "byteCount": self.byte_count,
            "truncated": self.truncated,
            "issueCodes": self.issue_codes,
            "evidenceCodes": self.evidence_codes,
            "recommendedActions": self.recommended_actions,
        }


class WorkspaceFileToolService:
    """受控 Workspace 文件读写服务。

    服务只做本地 workspace 文件操作，不做 HTTP 路由、不做模型调用、不写 Java outbox。这样拆分是为了让
    未来 Java worker、MCP adapter、A2A artifact adapter 或本地测试都能复用同一套路径安全规则。
    """

    def __init__(self, settings: WorkspaceFileToolSettings | None = None) -> None:
        self._settings = settings or WorkspaceFileToolSettings()

    def read_text(self, request: WorkspaceFileReadRequest) -> WorkspaceFileOperationResult:
        """读取 workspace 内文本文件。

        成功时 `result.content` 会携带内部文本片段；`result.to_summary()` 只携带 hash、字节数、截断标记和引用。
        如果路径非法、文件不存在、读取二进制文件或服务未启用，会返回 BLOCKED/NOT_FOUND，而不是泄露路径细节。
        """

        try:
            target, path_digest = self._resolve_target(request.workspace_root, request.workspace_reference, request.relative_path)
            if not target.exists():
                return self._result(
                    WorkspaceFileOperation.READ_TEXT,
                    WorkspaceFileOperationStatus.NOT_FOUND,
                    path_digest=path_digest,
                    issue_codes=("WORKSPACE_FILE_NOT_FOUND",),
                    recommended_actions=("确认文件已由受控工具创建，或让用户选择 workspace 内有效文件。",),
                )
            if not target.is_file():
                return self._blocked(WorkspaceFileOperation.READ_TEXT, path_digest, "WORKSPACE_FILE_NOT_REGULAR")
            max_bytes = self._read_budget(request.max_bytes)
            file_size = target.stat().st_size
            read_size = min(file_size, max_bytes)
            if file_size > max_bytes and not self._settings.allow_partial_read:
                return self._blocked(WorkspaceFileOperation.READ_TEXT, path_digest, "WORKSPACE_FILE_READ_LIMIT_EXCEEDED")
            with target.open("rb") as handle:
                raw = handle.read(read_size)
            if b"\x00" in raw:
                return self._blocked(WorkspaceFileOperation.READ_TEXT, path_digest, "WORKSPACE_FILE_BINARY_BLOCKED")
            content = raw.decode(request.encoding)
            return self._result(
                WorkspaceFileOperation.READ_TEXT,
                WorkspaceFileOperationStatus.SUCCEEDED,
                process_performed=True,
                path_digest=path_digest,
                artifact_reference=_artifact_reference("workspace-file-read", request.run_id, request.operation_id, path_digest),
                content_sha256=_sha256_text(content, request.encoding),
                byte_count=len(raw),
                truncated=file_size > read_size,
                evidence_codes=("WORKSPACE_PATH_VALIDATED", "READ_BUDGET_APPLIED"),
                recommended_actions=("仅将必要片段注入模型上下文；完整文件正文不得进入 runtime event 或 Java projection。",),
                content=content,
            )
        except UnicodeDecodeError:
            return self._blocked(WorkspaceFileOperation.READ_TEXT, _path_digest(request.relative_path), "WORKSPACE_FILE_DECODE_FAILED")
        except OSError:
            return self._result(
                WorkspaceFileOperation.READ_TEXT,
                WorkspaceFileOperationStatus.FAILED,
                path_digest=_path_digest(request.relative_path),
                issue_codes=("WORKSPACE_FILE_READ_FAILED",),
                recommended_actions=("检查 workspace 文件是否被其他进程占用，必要时重试或转人工处理。",),
            )
        except ValueError as exc:
            return self._blocked(WorkspaceFileOperation.READ_TEXT, _path_digest(request.relative_path), str(exc))

    def write_text(self, request: WorkspaceFileWriteRequest) -> WorkspaceFileOperationResult:
        """写入 workspace 内文本文件。

        写操作比读操作风险更高，因此这里默认不覆盖已有文件，除非调用方显式设置 `overwrite=True`。
        如果未来接入审批/确认页，确认事实应由 Java 控制面提供，Python 这里只负责执行前最后一层路径与预算校验。
        """

        try:
            target, path_digest = self._resolve_target(request.workspace_root, request.workspace_reference, request.relative_path)
            content_bytes = request.content.encode(request.encoding)
            if len(content_bytes) > self._settings.max_write_bytes:
                return self._blocked(WorkspaceFileOperation.WRITE_TEXT, path_digest, "WORKSPACE_FILE_WRITE_LIMIT_EXCEEDED")
            if _looks_sensitive(request.content):
                return self._blocked(WorkspaceFileOperation.WRITE_TEXT, path_digest, "WORKSPACE_FILE_CONTENT_SENSITIVE_BLOCKED")
            if target.exists() and not target.is_file():
                return self._blocked(WorkspaceFileOperation.WRITE_TEXT, path_digest, "WORKSPACE_FILE_TARGET_NOT_REGULAR")
            if target.exists() and not request.overwrite:
                return self._result(
                    WorkspaceFileOperation.WRITE_TEXT,
                    WorkspaceFileOperationStatus.CONFLICT,
                    path_digest=path_digest,
                    issue_codes=("WORKSPACE_FILE_ALREADY_EXISTS",),
                    recommended_actions=("如确需覆盖，请先进入人工确认，并携带 overwrite=true 与 expectedSha256。",),
                )
            if target.exists() and request.expected_sha256:
                current_hash = _sha256_bytes(target.read_bytes())
                if current_hash != request.expected_sha256:
                    return self._result(
                        WorkspaceFileOperation.WRITE_TEXT,
                        WorkspaceFileOperationStatus.CONFLICT,
                        path_digest=path_digest,
                        content_sha256=current_hash,
                        issue_codes=("WORKSPACE_FILE_EXPECTED_HASH_MISMATCH",),
                        recommended_actions=("文件已被其他流程修改，请重新读取摘要后再决定是否覆盖。",),
                    )
            if not target.parent.exists():
                if not request.create_parent_directories:
                    return self._blocked(WorkspaceFileOperation.WRITE_TEXT, path_digest, "WORKSPACE_FILE_PARENT_MISSING")
                target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(request.content, encoding=request.encoding)
            content_hash = _sha256_bytes(content_bytes)
            return self._result(
                WorkspaceFileOperation.WRITE_TEXT,
                WorkspaceFileOperationStatus.SUCCEEDED,
                process_performed=True,
                path_digest=path_digest,
                artifact_reference=_artifact_reference("workspace-file-write", request.run_id, request.operation_id, path_digest),
                content_sha256=content_hash,
                byte_count=len(content_bytes),
                evidence_codes=("WORKSPACE_PATH_VALIDATED", "WRITE_BUDGET_APPLIED", "CONTENT_HASH_RECORDED"),
                recommended_actions=("将写入事实交给 Java outbox/worker receipt 对账；不要在事件中返回文件正文。",),
            )
        except OSError:
            return self._result(
                WorkspaceFileOperation.WRITE_TEXT,
                WorkspaceFileOperationStatus.FAILED,
                path_digest=_path_digest(request.relative_path),
                issue_codes=("WORKSPACE_FILE_WRITE_FAILED",),
                recommended_actions=("检查 workspace 写权限或磁盘状态，必要时重试或转人工处理。",),
            )
        except ValueError as exc:
            return self._blocked(WorkspaceFileOperation.WRITE_TEXT, _path_digest(request.relative_path), str(exc))

    def _resolve_target(self, workspace_root: str, workspace_reference: str, relative_path: str) -> tuple[Path, str]:
        """解析并校验目标文件路径。

        返回真实 target path 只在服务内部使用；外部摘要只拿 pathDigest。这样可以降低文件名、目录结构或客户
        项目代号被写入日志/事件的风险。
        """

        if not self._settings.enabled:
            raise ValueError("WORKSPACE_FILE_TOOL_DISABLED")
        if not _is_safe_workspace_reference(workspace_reference):
            raise ValueError("WORKSPACE_FILE_REFERENCE_INVALID")
        root = Path(workspace_root).resolve()
        self._validate_workspace_root(root)
        path_parts = self._safe_relative_parts(relative_path)
        target = root.joinpath(*path_parts).resolve()
        if not (target == root or root in target.parents):
            raise ValueError("WORKSPACE_FILE_PATH_ESCAPE_BLOCKED")
        return target, _path_digest("/".join(path_parts))

    def _validate_workspace_root(self, root: Path) -> None:
        """校验 workspace root 是否在 allowlist 中。"""

        if not root.exists() or not root.is_dir():
            raise ValueError("WORKSPACE_FILE_ROOT_INVALID")
        allowed_roots = tuple(Path(item).resolve() for item in self._settings.workspace_root_allowlist)
        if not allowed_roots:
            raise ValueError("WORKSPACE_FILE_ROOT_ALLOWLIST_EMPTY")
        if not any(root == allowed or allowed in root.parents for allowed in allowed_roots):
            raise ValueError("WORKSPACE_FILE_ROOT_NOT_ALLOWED")

    def _safe_relative_parts(self, relative_path: str) -> tuple[str, ...]:
        """把相对路径拆成安全路径段。"""

        text = str(relative_path or "").strip().replace("\\", "/")
        if not text or "\x00" in text or "://" in text or text.startswith(("/", "~")):
            raise ValueError("WORKSPACE_FILE_PATH_INVALID")
        path = PurePosixPath(text)
        parts = tuple(part for part in path.parts if part not in {"", "."})
        if not parts or any(part == ".." or ":" in part for part in parts):
            raise ValueError("WORKSPACE_FILE_PATH_INVALID")
        lowered = tuple(part.lower() for part in parts)
        if self._settings.deny_hidden_paths and any(part.startswith(".") for part in lowered):
            raise ValueError("WORKSPACE_FILE_HIDDEN_PATH_BLOCKED")
        if any(part in {segment.lower() for segment in self._settings.denied_path_segments} for part in lowered):
            raise ValueError("WORKSPACE_FILE_DENIED_SEGMENT")
        if lowered[-1] in {name.lower() for name in self._settings.denied_file_names}:
            raise ValueError("WORKSPACE_FILE_DENIED_NAME")
        if any(lowered[-1].endswith(suffix.lower()) for suffix in self._settings.denied_suffixes):
            raise ValueError("WORKSPACE_FILE_DENIED_SUFFIX")
        return parts

    def _read_budget(self, requested: int | None) -> int:
        """计算本次读取预算。"""

        try:
            value = int(requested) if requested is not None else self._settings.max_read_bytes
        except (TypeError, ValueError):
            value = self._settings.max_read_bytes
        return max(1, min(value, self._settings.max_read_bytes))

    def _blocked(
        self,
        operation: WorkspaceFileOperation,
        path_digest: str,
        issue_code: str,
    ) -> WorkspaceFileOperationResult:
        """构造启动前阻断结果。"""

        return self._result(
            operation,
            WorkspaceFileOperationStatus.BLOCKED,
            path_digest=path_digest,
            issue_codes=(issue_code,),
            recommended_actions=("保持 fail-closed，并通过 workspace、权限或人工确认补齐后再重试。",),
        )

    @staticmethod
    def _result(
        operation: WorkspaceFileOperation,
        status: WorkspaceFileOperationStatus,
        *,
        process_performed: bool = False,
        path_digest: str,
        artifact_reference: str | None = None,
        content_sha256: str | None = None,
        byte_count: int = 0,
        truncated: bool = False,
        issue_codes: tuple[str, ...] = (),
        evidence_codes: tuple[str, ...] = (),
        recommended_actions: tuple[str, ...] = (),
        content: str | None = None,
    ) -> WorkspaceFileOperationResult:
        return WorkspaceFileOperationResult(
            operation=operation,
            status=status,
            process_performed=process_performed,
            path_digest=path_digest,
            artifact_reference=artifact_reference,
            content_sha256=content_sha256,
            byte_count=byte_count,
            truncated=truncated,
            issue_codes=issue_codes,
            evidence_codes=evidence_codes,
            recommended_actions=recommended_actions,
            content=content,
        )


def _path_digest(relative_path: str) -> str:
    """生成低敏路径摘要，避免摘要中出现真实路径或文件名。"""

    return hashlib.sha256(str(relative_path or "").replace("\\", "/").encode("utf-8")).hexdigest()[:24]


def _sha256_text(content: str, encoding: str) -> str:
    return _sha256_bytes(content.encode(encoding))


def _sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _artifact_reference(prefix: str, run_id: str, operation_id: str, path_digest: str) -> str:
    return f"{prefix}:{_safe_ref(run_id)}/{_safe_ref(operation_id)}/{path_digest}"


def _safe_ref(value: str) -> str:
    """把 run/operation 标识裁剪成可进入低敏 artifactReference 的片段。"""

    text = "".join(ch for ch in str(value or "unknown") if ch.isalnum() or ch in {"-", "_", "."})
    return text[:80] or "unknown"


__all__ = [
    "WORKSPACE_FILE_TOOL_PAYLOAD_POLICY",
    "WORKSPACE_FILE_TOOL_SCHEMA_VERSION",
    "WorkspaceFileOperation",
    "WorkspaceFileOperationResult",
    "WorkspaceFileOperationStatus",
    "WorkspaceFileReadRequest",
    "WorkspaceFileToolService",
    "WorkspaceFileToolSettings",
    "WorkspaceFileWriteRequest",
]
