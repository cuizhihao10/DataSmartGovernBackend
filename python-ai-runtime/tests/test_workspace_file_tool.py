import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    WorkspaceFileOperationStatus,
    WorkspaceFileReadRequest,
    WorkspaceFileToolService,
    WorkspaceFileToolSettings,
    WorkspaceFileWriteRequest,
)


class WorkspaceFileToolServiceTest(unittest.TestCase):
    """受控 workspace 文件工具测试。

    这组测试保护的是 Codex/Claude Code 类 Agent 的基础文件能力，但测试重点不是“能读写本机文件”这么简单，
    而是确保该能力始终被限制在受控 workspace 内，并且摘要不泄露文件正文、相对路径或宿主机真实路径。
    """

    def test_read_text_returns_internal_content_but_summary_is_metadata_only(self) -> None:
        """读取成功时，正文只能留在内部字段，不能进入 summary。"""

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "docs").mkdir()
            Path(workspace, "docs", "note.md").write_text("治理说明：仅供内部推理使用", encoding="utf-8")
            result = self._service(workspace).read_text(
                WorkspaceFileReadRequest(
                    session_id="session-file-001",
                    run_id="run-file-001",
                    operation_id="read-001",
                    workspace_root=workspace,
                    workspace_reference="agent-workspace:tenant-10/project-20/session-file-001",
                    relative_path="docs/note.md",
                )
            )
            summary = result.to_summary()
            serialized = json.dumps(summary, ensure_ascii=False)

        self.assertEqual(WorkspaceFileOperationStatus.SUCCEEDED, result.status)
        self.assertEqual("治理说明：仅供内部推理使用", result.content)
        self.assertEqual("METADATA_ONLY_NO_FILE_BODY_NO_REAL_PATH_NO_PROMPT_NO_SQL_NO_TOOL_ARGUMENT_BODY", summary["payloadPolicy"])
        self.assertIn("contentSha256", summary)
        self.assertNotIn("治理说明", serialized)
        self.assertNotIn("docs/note.md", serialized)
        self.assertNotIn(workspace, serialized)

    def test_write_text_creates_file_and_hides_content_from_summary(self) -> None:
        """写入成功时，应创建文件并返回 hash/artifact 引用，而不是返回正文。"""

        with tempfile.TemporaryDirectory() as workspace:
            result = self._service(workspace).write_text(
                WorkspaceFileWriteRequest(
                    session_id="session-file-001",
                    run_id="run-file-001",
                    operation_id="write-001",
                    workspace_root=workspace,
                    workspace_reference="agent-workspace:tenant-10/project-20/session-file-001",
                    relative_path="outputs/report.md",
                    content="低敏治理报告草稿",
                    create_parent_directories=True,
                )
            )
            written = Path(workspace, "outputs", "report.md").read_text(encoding="utf-8")
            serialized = json.dumps(result.to_summary(), ensure_ascii=False)

        self.assertEqual(WorkspaceFileOperationStatus.SUCCEEDED, result.status)
        self.assertEqual("低敏治理报告草稿", written)
        self.assertTrue(result.process_performed)
        self.assertTrue(result.artifact_reference.startswith("workspace-file-write:"))
        self.assertNotIn("低敏治理报告草稿", serialized)
        self.assertNotIn("outputs/report.md", serialized)
        self.assertNotIn(workspace, serialized)

    def test_path_escape_hidden_file_and_secret_suffix_are_blocked(self) -> None:
        """目录逃逸、隐藏路径和凭据后缀必须在 Python 侧 fail-closed。"""

        with tempfile.TemporaryDirectory() as workspace:
            service = self._service(workspace)
            escape = service.read_text(self._read_request(workspace, "../outside.md"))
            hidden = service.read_text(self._read_request(workspace, ".git/config"))
            secret = service.write_text(self._write_request(workspace, "certs/private.pem", "not-a-real-secret"))

        self.assertEqual(WorkspaceFileOperationStatus.BLOCKED, escape.status)
        self.assertIn("WORKSPACE_FILE_PATH_INVALID", escape.issue_codes)
        self.assertEqual(WorkspaceFileOperationStatus.BLOCKED, hidden.status)
        self.assertIn("WORKSPACE_FILE_HIDDEN_PATH_BLOCKED", hidden.issue_codes)
        self.assertEqual(WorkspaceFileOperationStatus.BLOCKED, secret.status)
        self.assertIn("WORKSPACE_FILE_DENIED_SUFFIX", secret.issue_codes)

    def test_write_conflict_and_expected_hash_mismatch_are_reported_without_path(self) -> None:
        """覆盖冲突和 hash 不匹配应返回低敏冲突结果，不泄露路径或正文。"""

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "report.md").write_text("old-content", encoding="utf-8")
            service = self._service(workspace)
            conflict = service.write_text(self._write_request(workspace, "report.md", "new-content"))
            mismatch = service.write_text(
                self._write_request(
                    workspace,
                    "report.md",
                    "new-content",
                    overwrite=True,
                    expected_sha256="wrong-hash",
                )
            )
            serialized = json.dumps({"conflict": conflict.to_summary(), "mismatch": mismatch.to_summary()}, ensure_ascii=False)

        self.assertEqual(WorkspaceFileOperationStatus.CONFLICT, conflict.status)
        self.assertIn("WORKSPACE_FILE_ALREADY_EXISTS", conflict.issue_codes)
        self.assertEqual(WorkspaceFileOperationStatus.CONFLICT, mismatch.status)
        self.assertIn("WORKSPACE_FILE_EXPECTED_HASH_MISMATCH", mismatch.issue_codes)
        self.assertNotIn("report.md", serialized)
        self.assertNotIn("old-content", serialized)
        self.assertNotIn("new-content", serialized)

    def test_disabled_or_untrusted_workspace_is_blocked(self) -> None:
        """服务未启用、workspace 引用不可信或 root 不在 allowlist 时都不能访问文件。"""

        with tempfile.TemporaryDirectory() as workspace:
            disabled = WorkspaceFileToolService().read_text(self._read_request(workspace, "a.md"))
            unsafe_reference = self._service(workspace).read_text(
                WorkspaceFileReadRequest(
                    session_id="session-file-001",
                    run_id="run-file-001",
                    operation_id="read-001",
                    workspace_root=workspace,
                    workspace_reference="https://internal/workspace?token=secret",
                    relative_path="a.md",
                )
            )
            with tempfile.TemporaryDirectory() as other_root:
                not_allowed = self._service(workspace).read_text(self._read_request(other_root, "a.md"))

        self.assertEqual(WorkspaceFileOperationStatus.BLOCKED, disabled.status)
        self.assertIn("WORKSPACE_FILE_TOOL_DISABLED", disabled.issue_codes)
        self.assertEqual(WorkspaceFileOperationStatus.BLOCKED, unsafe_reference.status)
        self.assertIn("WORKSPACE_FILE_REFERENCE_INVALID", unsafe_reference.issue_codes)
        self.assertEqual(WorkspaceFileOperationStatus.BLOCKED, not_allowed.status)
        self.assertIn("WORKSPACE_FILE_ROOT_NOT_ALLOWED", not_allowed.issue_codes)

    def _service(self, workspace: str) -> WorkspaceFileToolService:
        return WorkspaceFileToolService(
            WorkspaceFileToolSettings(
                enabled=True,
                workspace_root_allowlist=(workspace,),
                max_read_bytes=64,
                max_write_bytes=128,
            )
        )

    def _read_request(self, workspace: str, relative_path: str) -> WorkspaceFileReadRequest:
        return WorkspaceFileReadRequest(
            session_id="session-file-001",
            run_id="run-file-001",
            operation_id="read-001",
            workspace_root=workspace,
            workspace_reference="agent-workspace:tenant-10/project-20/session-file-001",
            relative_path=relative_path,
        )

    def _write_request(self, workspace: str, relative_path: str, content: str, **overrides) -> WorkspaceFileWriteRequest:
        values = {
            "session_id": "session-file-001",
            "run_id": "run-file-001",
            "operation_id": "write-001",
            "workspace_root": workspace,
            "workspace_reference": "agent-workspace:tenant-10/project-20/session-file-001",
            "relative_path": relative_path,
            "content": content,
            "create_parent_directories": True,
        }
        values.update(overrides)
        return WorkspaceFileWriteRequest(**values)


if __name__ == "__main__":
    unittest.main()
