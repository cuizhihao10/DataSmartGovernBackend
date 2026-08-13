"""受控 workspace 本地精确文本检索工具。

这个模块提供类似本地 ``rg`` 的精确检索能力，但它刻意不是 shell 包装器，也不是联网或
Elasticsearch 检索器。所有文件遍历和读取都使用 Python 标准库，并复用
``WorkspaceFileToolService`` 已建立的 workspace root、相对路径、隐藏路径和凭据路径安全边界。

工具目前只支持 literal 子串匹配。Python 标准库 ``re`` 没有跨平台、可依赖的单次匹配超时，
因此在受控 Agent 执行路径中开放任意正则会带来 ReDoS 风险；请求 ``REGEX`` 时服务会明确
fail-closed，而不会尝试“看起来安全”的不完整过滤。
"""

from __future__ import annotations

import hashlib
import os
import stat
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

from datasmart_ai_runtime.services.tools.controlled_command_worker_contract import _looks_sensitive
from datasmart_ai_runtime.services.tools.workspace_file_tool import (
    WorkspaceFileToolService,
    WorkspaceFileToolSettings,
)


WORKSPACE_TEXT_SEARCH_TOOL_SCHEMA_VERSION = "datasmart.python-ai-runtime.workspace-text-search-tool.v1"
WORKSPACE_TEXT_SEARCH_TOOL_PAYLOAD_POLICY = (
    "MATCHES_ONLY_NO_ABSOLUTE_PATH_NO_FILE_BODY_NO_QUERY_NO_CREDENTIAL_BODY"
)
# ``_looks_sensitive`` 是跨工具共用的低成本防护；这里补充常见令牌字段，确保搜索命中行的
# snippet 不会因为某个凭据字段未被公共 marker 覆盖而回显 secret value。
_SENSITIVE_SNIPPET_MARKERS = (
    "password",
    "passwd",
    "secret",
    "token",
    "api_key",
    "apikey",
    "access_key",
    "accesskey",
    "authorization",
    "bearer ",
    "private_key",
    "private key",
)


class WorkspaceTextSearchMode(str, Enum):
    """声明请求希望使用的检索语义。

    ``REGEX`` 保留在执行合同中，是为了让调用方得到稳定、可审计的拒绝结果，而不是把它悄悄
    当成 literal 处理。当前版本唯一可执行的模式是 ``LITERAL``。
    """

    LITERAL = "LITERAL"
    REGEX = "REGEX"


class WorkspaceTextSearchStatus(str, Enum):
    """低敏文本搜索结果的状态值。"""

    SUCCEEDED = "SUCCEEDED"
    NO_MATCH = "NO_MATCH"
    BLOCKED = "BLOCKED"
    FAILED = "FAILED"


@dataclass(frozen=True)
class WorkspaceTextSearchSettings:
    """文本搜索的执行开关、安全边界和硬预算。

    字段按执行顺序分组，便于初学者理解一条搜索请求如何被收敛：
    - ``enabled`` 和 ``workspace_root_allowlist`` 决定是否可以接触真实文件系统；
    - 路径拒绝项与 ``deny_hidden_paths`` 复用文件读取工具的规则，阻止搜索绕过 ``.git``、
      ``.env``、私钥或本地数据库文件；
    - ``allowed_file_suffixes`` 是文件类型白名单，未知类型不会尝试解码；
    - 其余数字均是服务端硬上限，调用方的 ``max_results`` 只能收紧，不能放大。

    这些值不应由模型提供。生产部署应从受控配置加载，并为每个 tenant/workspace 设置更小的
    预算，而不是把整个宿主机目录暴露给单次 Agent tool call。
    """

    enabled: bool = False
    workspace_root_allowlist: tuple[str, ...] = field(default_factory=tuple)
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
    allowed_file_suffixes: tuple[str, ...] = (
        ".py",
        ".java",
        ".kt",
        ".go",
        ".rs",
        ".js",
        ".jsx",
        ".ts",
        ".tsx",
        ".vue",
        ".html",
        ".css",
        ".scss",
        ".md",
        ".rst",
        ".txt",
        ".json",
        ".yaml",
        ".yml",
        ".toml",
        ".ini",
        ".cfg",
        ".properties",
        ".xml",
        ".csv",
        ".sql",
        ".sh",
        ".ps1",
    )
    max_query_chars: int = 256
    max_files: int = 200
    max_file_bytes: int = 256 * 1024
    max_total_scan_bytes: int = 2 * 1024 * 1024
    max_matches: int = 40
    max_line_chars: int = 800
    max_snippet_chars: int = 240


@dataclass(frozen=True)
class WorkspaceTextSearchRequest:
    """一次本地精确文本搜索的内部执行请求。

    ``workspace_root`` 是受控 worker 注入的真实目录，``workspace_reference`` 是低敏审计引用；
    两者都不是模型可自由伪造的参数。模型最多提供 literal ``query``、可选的 workspace 相对
    ``relative_path_prefix`` 与更小的结果数量。服务不会把 root、原始 query 或完整文件内容带入
    :meth:`WorkspaceTextSearchResult.to_summary`。
    """

    session_id: str
    run_id: str
    operation_id: str
    workspace_root: str
    workspace_reference: str
    query: str
    relative_path_prefix: str | None = None
    case_sensitive: bool = True
    search_mode: WorkspaceTextSearchMode | str = WorkspaceTextSearchMode.LITERAL
    max_results: int | None = None


@dataclass(frozen=True)
class WorkspaceTextSearchMatch:
    """单个可返回给模型的文本命中。

    ``relative_path`` 只相对于已经验证的 workspace root，因此可帮助模型继续调用
    ``workspace.file.read``，却不会泄露宿主机绝对路径。``snippet`` 永远是受长度限制的行片段；
    若整行具有凭据、SQL、内部 URL 等敏感信号，服务会保留位置证据但以固定文本替代正文。
    """

    relative_path: str
    line_number: int
    snippet: str
    path_digest: str
    content_sha256: str

    def to_summary(self) -> dict[str, object]:
        """输出可供后续模型推理使用的最小命中记录。

        返回值只含相对路径、行号、短片段及可对账的 hash，不含绝对 root、文件其余正文、搜索
        query、凭据内容或任何执行环境变量。
        """

        return {
            "relativePath": self.relative_path,
            "lineNumber": self.line_number,
            "snippet": self.snippet,
            "pathDigest": self.path_digest,
            "contentSha256": self.content_sha256,
        }


@dataclass(frozen=True)
class WorkspaceTextSearchResult:
    """受控文本搜索的低敏结果与预算账本。

    ``matches`` 是唯一可能包含工作区内容的位置，而且每项仅包含一段已经过长度与敏感内容处理的
    短片段。其余字段用于告诉执行器、模型和审计层本次扫描是否被文件数、字节数、命中数或行
    长度预算截断，帮助它们决定是否应缩小目录范围或改用 RAG，而不是盲目扩大本地扫描。
    """

    status: WorkspaceTextSearchStatus
    process_performed: bool
    query_digest: str
    path_scope_digest: str
    files_considered: int = 0
    files_scanned: int = 0
    scanned_bytes: int = 0
    matches: tuple[WorkspaceTextSearchMatch, ...] = ()
    skipped_by_type: int = 0
    skipped_oversized: int = 0
    skipped_binary: int = 0
    skipped_protected: int = 0
    skipped_symlink: int = 0
    truncated: bool = False
    issue_codes: tuple[str, ...] = ()
    evidence_codes: tuple[str, ...] = ()
    recommended_actions: tuple[str, ...] = ()
    payload_policy: str = WORKSPACE_TEXT_SEARCH_TOOL_PAYLOAD_POLICY

    def to_summary(self) -> dict[str, Any]:
        """返回可序列化的工具结果，不泄露 query、绝对路径或完整文件正文。

        调用方应把这个结果作为原生 tool result 回填给模型。因为搜索不是 ``ToolPlan`` 的强制
        步骤，模型可以先根据这些有限证据决定是否继续精确读取某个文件，或转向独立的
        ``knowledge.rag.query`` 工具。
        """

        return {
            "schemaVersion": WORKSPACE_TEXT_SEARCH_TOOL_SCHEMA_VERSION,
            "payloadPolicy": self.payload_policy,
            "status": self.status.value,
            "processPerformed": self.process_performed,
            "queryDigest": self.query_digest,
            "pathScopeDigest": self.path_scope_digest,
            "filesConsidered": self.files_considered,
            "filesScanned": self.files_scanned,
            "scannedBytes": self.scanned_bytes,
            "matchCount": len(self.matches),
            "matches": tuple(match.to_summary() for match in self.matches),
            "skippedByType": self.skipped_by_type,
            "skippedOversized": self.skipped_oversized,
            "skippedBinary": self.skipped_binary,
            "skippedProtected": self.skipped_protected,
            "skippedSymlink": self.skipped_symlink,
            "truncated": self.truncated,
            "issueCodes": self.issue_codes,
            "evidenceCodes": self.evidence_codes,
            "recommendedActions": self.recommended_actions,
        }


class WorkspaceTextSearchService:
    """在受控 workspace 中执行有硬预算的 literal 文本检索。

    这个类是实际 I/O 服务，不负责 HTTP、模型调用、shell、网络或 Java 控制面交互。它复用
    ``WorkspaceFileToolService`` 的 root/reference/path 规则，并在自己的目录遍历中使用
    ``os.scandir(..., follow_symlinks=False)`` 与文件描述符校验，确保不会把扫描范围扩展到
    workspace 外部。这样上层无论来自 native tool_calls、MCP adapter 还是本地单测，都会得到
    相同的 fail-closed 行为。
    """

    def __init__(self, settings: WorkspaceTextSearchSettings | None = None) -> None:
        """保存搜索预算，并构造复用现有文件安全边界的内部 guard。

        ``WorkspaceTextSearchSettings`` 只承载搜索特有的文件与输出预算。路径校验逻辑不在这里
        重写，而是转换成 ``WorkspaceFileToolSettings`` 后交给现有文件工具，从而避免未来新增
        一个凭据后缀时读文件和搜索文件出现安全策略漂移。
        """

        self._settings = settings or WorkspaceTextSearchSettings()
        self._workspace_guard = WorkspaceFileToolService(
            WorkspaceFileToolSettings(
                enabled=self._settings.enabled,
                workspace_root_allowlist=self._settings.workspace_root_allowlist,
                deny_hidden_paths=self._settings.deny_hidden_paths,
                denied_path_segments=self._settings.denied_path_segments,
                denied_file_names=self._settings.denied_file_names,
                denied_suffixes=self._settings.denied_suffixes,
            )
        )

    def search(self, request: WorkspaceTextSearchRequest) -> WorkspaceTextSearchResult:
        """执行一次 literal 搜索并返回受预算约束的匹配摘要。

        输入 query 必须是短的单行 literal；服务不执行 regex、shell 或网络调用。搜索成功时返回
        相对路径、行号和短片段；无命中返回 ``NO_MATCH``；任何 workspace、路径、模式或预算
        边界问题都以稳定机器码表示，且不会在结果中回显真实 root、query 或被拒绝的文件名。
        """

        query_digest = _text_digest(request.query)
        scope_digest = _path_digest(request.relative_path_prefix)
        query = self._validate_literal_query(request.query)
        if query is None:
            return self._blocked(query_digest, scope_digest, "WORKSPACE_TEXT_SEARCH_QUERY_INVALID")
        mode_issue = self._validate_search_mode(request.search_mode)
        if mode_issue is not None:
            return self._blocked(query_digest, scope_digest, mode_issue)

        try:
            root = self._workspace_guard.resolve_workspace_root(
                request.workspace_root,
                request.workspace_reference,
            )
            search_root = self._resolve_search_root(root, request.relative_path_prefix)
        except ValueError as exc:
            return self._blocked(query_digest, scope_digest, str(exc))
        except OSError:
            return self._failed(query_digest, scope_digest, "WORKSPACE_TEXT_SEARCH_ROOT_UNAVAILABLE")

        if not search_root.exists():
            return WorkspaceTextSearchResult(
                status=WorkspaceTextSearchStatus.NO_MATCH,
                process_performed=False,
                query_digest=query_digest,
                path_scope_digest=scope_digest,
                issue_codes=("WORKSPACE_TEXT_SEARCH_SCOPE_NOT_FOUND",),
                recommended_actions=("选择当前受控 workspace 内存在的目录或文本文件后重试。",),
            )

        try:
            return self._search_root(
                root=root,
                search_root=search_root,
                query=query,
                case_sensitive=bool(request.case_sensitive),
                max_results=self._result_budget(request.max_results),
                query_digest=query_digest,
                scope_digest=scope_digest,
            )
        except OSError:
            return self._failed(query_digest, scope_digest, "WORKSPACE_TEXT_SEARCH_FAILED")

    def _search_root(
        self,
        *,
        root: Path,
        search_root: Path,
        query: str,
        case_sensitive: bool,
        max_results: int,
        query_digest: str,
        scope_digest: str,
    ) -> WorkspaceTextSearchResult:
        """遍历一个已验证范围，并在每次读取前后应用所有硬预算。

        ``root`` 和 ``search_root`` 已经通过共享路径 guard 验证。这里仍不相信目录项本身：每个
        项都会先拒绝符号链接和受保护相对路径，再检查类型、单文件字节数、累计扫描字节数与
        命中数。这样任何一个预算达到上限时，服务停止扩大 I/O，而不是依赖模型自我节制。
        """

        matches: list[WorkspaceTextSearchMatch] = []
        issue_codes: list[str] = []
        files_considered = 0
        files_scanned = 0
        scanned_bytes = 0
        skipped_by_type = 0
        skipped_oversized = 0
        skipped_binary = 0
        skipped_protected = 0
        skipped_symlink = 0
        truncated = False
        directories: list[Path] = []
        files: list[Path] = []

        if search_root.is_symlink():
            return self._blocked(query_digest, scope_digest, "WORKSPACE_TEXT_SEARCH_SYMLINK_BLOCKED")
        if search_root.is_file():
            files.append(search_root)
        elif search_root.is_dir():
            directories.append(search_root)
        else:
            return self._blocked(query_digest, scope_digest, "WORKSPACE_TEXT_SEARCH_SCOPE_NOT_REGULAR")

        stop = False
        file_budget_exhausted = False
        while (directories or files) and not stop:
            if files:
                path = files.pop(0)
                relative_path = path.relative_to(root).as_posix()
                files_considered += 1
                if files_considered > self._positive_budget(self._settings.max_files):
                    truncated = True
                    _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_FILE_COUNT_LIMIT_REACHED")
                    break

                outcome = self._read_candidate_file(path, scanned_bytes)
                if outcome.kind == "protected":
                    skipped_protected += 1
                    continue
                if outcome.kind == "symlink":
                    skipped_symlink += 1
                    continue
                if outcome.kind == "type":
                    skipped_by_type += 1
                    continue
                if outcome.kind == "oversized":
                    skipped_oversized += 1
                    _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_FILE_SIZE_LIMIT_REACHED")
                    continue
                if outcome.kind == "total_budget":
                    truncated = True
                    _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_TOTAL_SCAN_BUDGET_REACHED")
                    continue
                if outcome.kind == "binary":
                    files_scanned += 1
                    scanned_bytes += outcome.byte_count
                    skipped_binary += 1
                    _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_BINARY_FILE_SKIPPED")
                    continue
                if outcome.kind == "unreadable":
                    _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_FILE_READ_SKIPPED")
                    continue

                files_scanned += 1
                scanned_bytes += outcome.byte_count
                file_matches, line_truncated = self._find_matches_in_text(
                    relative_path=relative_path,
                    text=outcome.text,
                    content_sha256=outcome.content_sha256,
                    query=query,
                    case_sensitive=case_sensitive,
                    remaining=max_results - len(matches),
                )
                matches.extend(file_matches)
                if line_truncated:
                    _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_LINE_LENGTH_LIMIT_APPLIED")
                if len(matches) >= max_results:
                    truncated = True
                    _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_MATCH_LIMIT_REACHED")
                    stop = True
                continue

            if file_budget_exhausted:
                break
            directory = directories.pop()
            if directory.is_symlink():
                skipped_symlink += 1
                continue
            try:
                entries = os.scandir(directory)
            except OSError:
                _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_DIRECTORY_READ_SKIPPED")
                continue
            try:
                with entries:
                    for entry in entries:
                        path = Path(entry.path)
                        if entry.is_symlink():
                            skipped_symlink += 1
                            continue
                        try:
                            relative_path = path.relative_to(root).as_posix()
                            self._workspace_guard.validate_relative_path(relative_path)
                        except (ValueError, OSError):
                            skipped_protected += 1
                            continue
                        if entry.is_dir(follow_symlinks=False):
                            directories.append(path)
                        elif entry.is_file(follow_symlinks=False):
                            # Count the candidate before buffering it. This keeps a directory containing
                            # millions of files from turning the configured file-count budget into an
                            # unbounded in-memory path list.
                            if files_considered + len(files) >= self._positive_budget(self._settings.max_files):
                                truncated = True
                                _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_FILE_COUNT_LIMIT_REACHED")
                                file_budget_exhausted = True
                                break
                            files.append(path)
            except OSError:
                _append_unique(issue_codes, "WORKSPACE_TEXT_SEARCH_DIRECTORY_READ_SKIPPED")

        status = WorkspaceTextSearchStatus.SUCCEEDED if matches else WorkspaceTextSearchStatus.NO_MATCH
        evidence_codes = [
            "WORKSPACE_ROOT_VALIDATED",
            "LITERAL_ONLY_SEARCH",
            "SYMLINKS_NOT_FOLLOWED",
            "SEARCH_BUDGETS_APPLIED",
        ]
        if skipped_protected:
            evidence_codes.append("PROTECTED_PATHS_SKIPPED")
        if skipped_binary:
            evidence_codes.append("BINARY_FILES_SKIPPED")
        return WorkspaceTextSearchResult(
            status=status,
            process_performed=bool(files_scanned or files_considered),
            query_digest=query_digest,
            path_scope_digest=scope_digest,
            files_considered=files_considered,
            files_scanned=files_scanned,
            scanned_bytes=scanned_bytes,
            matches=tuple(matches),
            skipped_by_type=skipped_by_type,
            skipped_oversized=skipped_oversized,
            skipped_binary=skipped_binary,
            skipped_protected=skipped_protected,
            skipped_symlink=skipped_symlink,
            truncated=truncated,
            issue_codes=tuple(issue_codes),
            evidence_codes=tuple(evidence_codes),
            recommended_actions=self._recommended_actions(matches, truncated),
        )

    def _resolve_search_root(self, root: Path, relative_path_prefix: str | None) -> Path:
        """把可选相对范围解析为不经过符号链接的 workspace 内路径。

        空范围表示已验证 root。非空范围先复用文件工具的隐藏/凭据/``..`` 防护，再逐段检查是否为
        符号链接；即使链接目标仍在 workspace 内也不跟随，因为扫描器无法安全地把该行为和普通
        目录遍历区分开。调用方只会在结果中看到范围 digest，不会看到这里返回的真实路径。
        """

        normalized_prefix = str(relative_path_prefix or "").strip().replace("\\", "/")
        if normalized_prefix in {"", "."}:
            return root
        parts = self._workspace_guard.validate_relative_path(normalized_prefix)
        candidate = root
        for part in parts:
            candidate = candidate / part
            if candidate.is_symlink():
                raise ValueError("WORKSPACE_TEXT_SEARCH_SYMLINK_BLOCKED")
        return candidate

    def _read_candidate_file(self, path: Path, scanned_bytes: int) -> "_CandidateReadOutcome":
        """在不跟随链接的前提下读取一个已枚举的候选文件。

        本方法先以 ``lstat`` 观察目录项，再通过 ``os.open`` 和 ``fstat`` 验证打开的对象仍是同
        一个常规文件，降低扫描期间文件被替换为符号链接的风险。它从不返回完整结果给外层；
        外层只会把已经解码、匹配并裁短的文本片段加入 ``WorkspaceTextSearchMatch``。
        """

        try:
            before = path.lstat()
        except OSError:
            return _CandidateReadOutcome(kind="unreadable")
        if stat.S_ISLNK(before.st_mode):
            return _CandidateReadOutcome(kind="symlink")
        if not stat.S_ISREG(before.st_mode):
            return _CandidateReadOutcome(kind="unreadable")
        if path.suffix.lower() not in {suffix.lower() for suffix in self._settings.allowed_file_suffixes}:
            return _CandidateReadOutcome(kind="type")
        if before.st_size > self._positive_budget(self._settings.max_file_bytes):
            return _CandidateReadOutcome(kind="oversized")
        remaining = self._positive_budget(self._settings.max_total_scan_bytes) - scanned_bytes
        if before.st_size > remaining:
            return _CandidateReadOutcome(kind="total_budget")

        raw = self._read_regular_file_without_following(path, before)
        if raw is None:
            return _CandidateReadOutcome(kind="unreadable")
        if b"\x00" in raw:
            return _CandidateReadOutcome(kind="binary", byte_count=len(raw))
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            return _CandidateReadOutcome(kind="binary", byte_count=len(raw))
        return _CandidateReadOutcome(
            kind="text",
            text=text,
            byte_count=len(raw),
            content_sha256=hashlib.sha256(raw).hexdigest(),
        )

    @staticmethod
    def _read_regular_file_without_following(path: Path, before: os.stat_result) -> bytes | None:
        """以文件描述符复核方式读取常规文件，拒绝读取竞态中替换掉的对象。

        支持 ``O_NOFOLLOW`` 的平台会在打开阶段直接拒绝最后一段是符号链接的路径；其他平台仍会
        在读取前比较 ``lstat`` 与 ``fstat`` 的设备和 inode。文件尺寸若在打开后增长，方法返回
        ``None``，避免把未计入预算的新内容作为部分扫描结果。
        """

        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(path, flags)
        except OSError:
            return None
        try:
            opened = os.fstat(descriptor)
            if (
                not stat.S_ISREG(opened.st_mode)
                or opened.st_dev != before.st_dev
                or opened.st_ino != before.st_ino
                or opened.st_size > before.st_size
            ):
                return None
            with os.fdopen(descriptor, "rb", closefd=True) as handle:
                descriptor = -1
                raw = handle.read(before.st_size + 1)
            if len(raw) > before.st_size:
                return None
            return raw
        except OSError:
            return None
        finally:
            if descriptor >= 0:
                os.close(descriptor)

    def _find_matches_in_text(
        self,
        *,
        relative_path: str,
        text: str,
        content_sha256: str,
        query: str,
        case_sensitive: bool,
        remaining: int,
    ) -> tuple[list[WorkspaceTextSearchMatch], bool]:
        """按行查找 literal 子串，并为每个命中构造最小可见证据。

        ``remaining`` 来自全局命中预算，因此一个大文件不能挤占后续执行器输出。超长行只检查前
        ``max_line_chars`` 个字符，既限制内存和片段长度，也明确记录可能存在未扫描尾部；这比
        把一整行日志或压缩文本送进模型上下文更可控。
        """

        matches: list[WorkspaceTextSearchMatch] = []
        line_truncated = False
        lookup_query = query if case_sensitive else query.lower()
        line_limit = self._positive_budget(self._settings.max_line_chars)
        for line_number, raw_line in enumerate(text.splitlines(), start=1):
            if len(matches) >= remaining:
                break
            line = raw_line[:line_limit]
            if len(raw_line) > line_limit:
                line_truncated = True
            lookup_line = line if case_sensitive else line.lower()
            index = lookup_line.find(lookup_query)
            if index < 0:
                continue
            snippet = self._safe_snippet(line, index, len(query), raw_line)
            matches.append(
                WorkspaceTextSearchMatch(
                    relative_path=relative_path,
                    line_number=line_number,
                    snippet=snippet,
                    path_digest=_path_digest(relative_path),
                    content_sha256=content_sha256,
                )
            )
        return matches, line_truncated

    def _safe_snippet(self, line: str, match_index: int, query_length: int, raw_line: str) -> str:
        """从命中行提取受长度限制且不会泄露敏感正文的片段。

        敏感判断针对完整原始行而不是已裁短的 ``line``，避免凭据刚好位于裁剪尾部时误把前半行
        当作可公开内容。普通文本只返回围绕命中的窗口，并用 ASCII 省略号标识被省略的前后文。
        """

        if _looks_sensitive(raw_line) or any(marker in raw_line.lower() for marker in _SENSITIVE_SNIPPET_MARKERS):
            return "[sensitive matching line redacted]"
        limit = self._positive_budget(self._settings.max_snippet_chars)
        if len(line) <= limit:
            return line
        half_context = max(0, (limit - min(query_length, limit)) // 2)
        start = max(0, match_index - half_context)
        end = min(len(line), start + limit)
        start = max(0, end - limit)
        prefix = "..." if start else ""
        suffix = "..." if end < len(line) else ""
        return f"{prefix}{line[start:end]}{suffix}"

    def _validate_literal_query(self, query: object) -> str | None:
        """验证 query 是一个短的单行 literal，防止大段上下文进入本地扫描器。

        空值、NUL、换行和超长文本都会被拒绝。这里不使用正则表达式，因此查询自身不会触发
        pattern 编译或回溯；实际匹配只调用字符串 ``find``。
        """

        text = str(query or "").strip()
        if (
            not text
            or "\x00" in text
            or "\n" in text
            or "\r" in text
            or len(text) > self._positive_budget(self._settings.max_query_chars)
        ):
            return None
        return text

    @staticmethod
    def _validate_search_mode(search_mode: WorkspaceTextSearchMode | str) -> str | None:
        """明确拒绝 regex，避免在无可靠超时的 CPython ``re`` 上暴露 ReDoS 面。

        返回 ``None`` 表示可安全执行 literal。其余返回值是稳定机器码，供原生 tool result 和
        调用方 UI 显示，不会包含模型提供的模式原文。
        """

        value = search_mode.value if isinstance(search_mode, WorkspaceTextSearchMode) else str(search_mode or "")
        normalized = value.strip().upper()
        if normalized == WorkspaceTextSearchMode.LITERAL.value:
            return None
        if normalized == WorkspaceTextSearchMode.REGEX.value:
            return "WORKSPACE_TEXT_SEARCH_REGEX_UNSUPPORTED"
        return "WORKSPACE_TEXT_SEARCH_MODE_INVALID"

    def _result_budget(self, requested: int | None) -> int:
        """将调用方请求的结果数量收敛到服务端 ``max_matches`` 硬上限。"""

        try:
            value = int(requested) if requested is not None else self._settings.max_matches
        except (TypeError, ValueError):
            value = self._settings.max_matches
        return max(1, min(value, self._positive_budget(self._settings.max_matches)))

    @staticmethod
    def _positive_budget(value: int) -> int:
        """把配置错误降级为最小正预算，确保所有循环始终有确定的上限。"""

        try:
            return max(1, int(value))
        except (TypeError, ValueError):
            return 1

    @staticmethod
    def _recommended_actions(
        matches: list[WorkspaceTextSearchMatch],
        truncated: bool,
    ) -> tuple[str, ...]:
        """生成不含路径、query 或正文的下一步建议。"""

        if truncated:
            return ("缩小 workspace 相对目录范围或使用更精确 literal 后重试。",)
        if matches:
            return ("仅在需要更完整上下文时，再读取已命中的单个受控文件。",)
        return ("尝试更精确的 literal，或改用独立的治理知识库 RAG 查询。",)

    @staticmethod
    def _blocked(query_digest: str, scope_digest: str, issue_code: str) -> WorkspaceTextSearchResult:
        """构造 fail-closed 结果，避免把底层路径异常或请求正文直接返回给模型。"""

        return WorkspaceTextSearchResult(
            status=WorkspaceTextSearchStatus.BLOCKED,
            process_performed=False,
            query_digest=query_digest,
            path_scope_digest=scope_digest,
            issue_codes=(issue_code,),
            recommended_actions=("保持 workspace 安全边界；补齐受控范围后再重试。",),
        )

    @staticmethod
    def _failed(query_digest: str, scope_digest: str, issue_code: str) -> WorkspaceTextSearchResult:
        """构造不可恢复 I/O 失败结果，不透露宿主机文件系统细节。"""

        return WorkspaceTextSearchResult(
            status=WorkspaceTextSearchStatus.FAILED,
            process_performed=False,
            query_digest=query_digest,
            path_scope_digest=scope_digest,
            issue_codes=(issue_code,),
            recommended_actions=("检查受控 worker 的 workspace 挂载状态后重试。",),
        )


@dataclass(frozen=True)
class _CandidateReadOutcome:
    """目录扫描器与文件读取器之间的内部结果，不会序列化给模型或事件。"""

    kind: str
    text: str = ""
    byte_count: int = 0
    content_sha256: str = ""


def _text_digest(value: object) -> str:
    """生成 query 的不可逆摘要，供审计关联而不回显 query 正文。"""

    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()


def _path_digest(relative_path: object) -> str:
    """生成相对路径范围摘要，避免结果携带绝对 root 或未命中的目录名。"""

    normalized = str(relative_path or "workspace-root").replace("\\", "/")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:24]


def _append_unique(items: list[str], value: str) -> None:
    """保留首次出现顺序地记录低敏机器码，方便测试和审计稳定对账。"""

    if value not in items:
        items.append(value)


__all__ = [
    "WORKSPACE_TEXT_SEARCH_TOOL_PAYLOAD_POLICY",
    "WORKSPACE_TEXT_SEARCH_TOOL_SCHEMA_VERSION",
    "WorkspaceTextSearchMatch",
    "WorkspaceTextSearchMode",
    "WorkspaceTextSearchRequest",
    "WorkspaceTextSearchResult",
    "WorkspaceTextSearchService",
    "WorkspaceTextSearchSettings",
    "WorkspaceTextSearchStatus",
]
