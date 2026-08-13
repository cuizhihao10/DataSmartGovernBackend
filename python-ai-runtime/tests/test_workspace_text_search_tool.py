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
    WorkspaceTextSearchMode,
    WorkspaceTextSearchRequest,
    WorkspaceTextSearchService,
    WorkspaceTextSearchSettings,
    WorkspaceTextSearchStatus,
)


class WorkspaceTextSearchServiceTest(unittest.TestCase):
    """受控本地精确检索的服务级安全与预算测试。"""

    def test_literal_search_returns_relative_path_line_snippet_and_hashes(self) -> None:
        """成功匹配只返回相对路径、行号、短片段和 hash，绝不回显 workspace root。"""

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "src").mkdir()
            Path(workspace, "src", "example.py").write_text(
                "def helper():\n    return 'needle-for-search'\n",
                encoding="utf-8",
            )

            result = self._service(workspace).search(
                self._request(workspace, "needle-for-search", relative_path_prefix=".")
            )
            summary = result.to_summary()
            serialized = json.dumps(summary, ensure_ascii=False)

        self.assertEqual(WorkspaceTextSearchStatus.SUCCEEDED, result.status)
        self.assertEqual(1, len(result.matches))
        self.assertEqual("src/example.py", result.matches[0].relative_path)
        self.assertEqual(2, result.matches[0].line_number)
        self.assertIn("needle-for-search", result.matches[0].snippet)
        self.assertEqual(24, len(result.matches[0].path_digest))
        self.assertEqual(64, len(result.matches[0].content_sha256))
        self.assertEqual("src/example.py", summary["matches"][0]["relativePath"])
        self.assertNotIn(workspace, serialized)
        self.assertNotIn("workspaceRoot", serialized)

    def test_no_match_keeps_query_out_of_the_summary(self) -> None:
        """无命中也返回稳定状态，但不会把模型提供的原始查询回显到结果载荷。"""

        query = "not-present-unique-literal"
        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "notes.txt").write_text("ordinary workspace note", encoding="utf-8")
            result = self._service(workspace).search(self._request(workspace, query))
            serialized = json.dumps(result.to_summary(), ensure_ascii=False)

        self.assertEqual(WorkspaceTextSearchStatus.NO_MATCH, result.status)
        self.assertTrue(result.process_performed)
        self.assertEqual((), result.matches)
        self.assertNotIn(query, serialized)
        self.assertNotIn(workspace, serialized)

    def test_escape_hidden_and_credential_paths_are_blocked_or_skipped(self) -> None:
        """显式越界/隐藏范围 fail-closed，遍历时则跳过 .env 和私钥文件。"""

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, ".env").write_text("PASSWORD=must-not-leak", encoding="utf-8")
            Path(workspace, "certs").mkdir()
            Path(workspace, "certs", "private.pem").write_text("private-key-body", encoding="utf-8")
            Path(workspace, "visible.txt").write_text("ordinary text", encoding="utf-8")

            escape = self._service(workspace).search(
                self._request(workspace, "ordinary", relative_path_prefix="../outside")
            )
            hidden = self._service(workspace).search(
                self._request(workspace, "ordinary", relative_path_prefix=".private")
            )
            skipped = self._service(workspace).search(self._request(workspace, "must-not-leak"))
            serialized = json.dumps(skipped.to_summary(), ensure_ascii=False)

        self.assertEqual(WorkspaceTextSearchStatus.BLOCKED, escape.status)
        self.assertIn("WORKSPACE_FILE_PATH_INVALID", escape.issue_codes)
        self.assertEqual(WorkspaceTextSearchStatus.BLOCKED, hidden.status)
        self.assertIn("WORKSPACE_FILE_HIDDEN_PATH_BLOCKED", hidden.issue_codes)
        self.assertEqual(WorkspaceTextSearchStatus.NO_MATCH, skipped.status)
        self.assertGreaterEqual(skipped.skipped_protected, 2)
        self.assertNotIn(".env", serialized)
        self.assertNotIn("private.pem", serialized)
        self.assertNotIn("must-not-leak", serialized)

    def test_binary_large_file_total_byte_file_count_and_match_budgets_are_enforced(self) -> None:
        """二进制、大文件、总扫描、文件数和命中数都由服务端硬预算约束。"""

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "binary.txt").write_bytes(b"needle\x00")
            Path(workspace, "huge.txt").write_text("needle" * 20, encoding="utf-8")
            Path(workspace, "small.txt").write_text("needle small", encoding="utf-8")
            byte_limited = self._service(
                workspace,
                max_file_bytes=32,
                max_total_scan_bytes=10,
            ).search(self._request(workspace, "needle"))

        self.assertEqual(WorkspaceTextSearchStatus.NO_MATCH, byte_limited.status)
        self.assertEqual(1, byte_limited.skipped_binary)
        self.assertEqual(1, byte_limited.skipped_oversized)
        self.assertIn("WORKSPACE_TEXT_SEARCH_BINARY_FILE_SKIPPED", byte_limited.issue_codes)
        self.assertIn("WORKSPACE_TEXT_SEARCH_FILE_SIZE_LIMIT_REACHED", byte_limited.issue_codes)
        self.assertIn("WORKSPACE_TEXT_SEARCH_TOTAL_SCAN_BUDGET_REACHED", byte_limited.issue_codes)
        self.assertLessEqual(byte_limited.scanned_bytes, 10)

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "a.txt").write_text("not the requested term", encoding="utf-8")
            Path(workspace, "b.txt").write_text("also not the requested term", encoding="utf-8")
            file_limited = self._service(workspace, max_files=1).search(self._request(workspace, "needle"))

        self.assertEqual(WorkspaceTextSearchStatus.NO_MATCH, file_limited.status)
        self.assertEqual(1, file_limited.files_considered)
        self.assertEqual(1, file_limited.files_scanned)
        self.assertIn("WORKSPACE_TEXT_SEARCH_FILE_COUNT_LIMIT_REACHED", file_limited.issue_codes)

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "a.txt").write_text("needle one", encoding="utf-8")
            Path(workspace, "b.txt").write_text("needle two", encoding="utf-8")
            match_limited = self._service(workspace, max_matches=1).search(
                self._request(workspace, "needle", max_results=100)
            )

        self.assertEqual(WorkspaceTextSearchStatus.SUCCEEDED, match_limited.status)
        self.assertEqual(1, len(match_limited.matches))
        self.assertTrue(match_limited.truncated)
        self.assertIn("WORKSPACE_TEXT_SEARCH_MATCH_LIMIT_REACHED", match_limited.issue_codes)

    def test_long_lines_are_not_scanned_past_the_line_budget(self) -> None:
        """超长行只检查预算内前缀，防止日志式单行文本放大搜索和结果上下文。"""

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "line.txt").write_text("1234567890 needle-after-budget", encoding="utf-8")
            result = self._service(workspace, max_line_chars=8).search(self._request(workspace, "needle"))

        self.assertEqual(WorkspaceTextSearchStatus.NO_MATCH, result.status)
        self.assertIn("WORKSPACE_TEXT_SEARCH_LINE_LENGTH_LIMIT_APPLIED", result.issue_codes)

    def test_external_symlink_is_skipped_without_reading_the_target(self) -> None:
        """扫描器不得跟随指向 workspace 外部的符号链接。"""

        with tempfile.TemporaryDirectory() as workspace, tempfile.TemporaryDirectory() as outside:
            outside_file = Path(outside, "outside.txt")
            outside_file.write_text("outside-needle", encoding="utf-8")
            link = Path(workspace, "outside-link.txt")
            try:
                os.symlink(outside_file, link)
            except (OSError, NotImplementedError):
                self.skipTest("当前运行环境不允许创建符号链接")

            result = self._service(workspace).search(self._request(workspace, "outside-needle"))
            serialized = json.dumps(result.to_summary(), ensure_ascii=False)

        self.assertEqual(WorkspaceTextSearchStatus.NO_MATCH, result.status)
        self.assertGreaterEqual(result.skipped_symlink, 1)
        self.assertNotIn(outside, serialized)
        self.assertNotIn("outside-needle", serialized)

    def test_regex_is_explicitly_rejected_and_sensitive_line_is_redacted(self) -> None:
        """无可靠 regex 超时就明确拒绝，同时允许安全地定位敏感行但不回显凭据正文。"""

        with tempfile.TemporaryDirectory() as workspace:
            Path(workspace, "settings.py").write_text(
                'password = "credential-body-must-not-appear"\n',
                encoding="utf-8",
            )
            regex = self._service(workspace).search(
                self._request(workspace, "(password)+", search_mode=WorkspaceTextSearchMode.REGEX)
            )
            redacted = self._service(workspace).search(self._request(workspace, "password"))
            serialized = json.dumps(redacted.to_summary(), ensure_ascii=False)

        self.assertEqual(WorkspaceTextSearchStatus.BLOCKED, regex.status)
        self.assertIn("WORKSPACE_TEXT_SEARCH_REGEX_UNSUPPORTED", regex.issue_codes)
        self.assertEqual(WorkspaceTextSearchStatus.SUCCEEDED, redacted.status)
        self.assertEqual("[sensitive matching line redacted]", redacted.matches[0].snippet)
        self.assertNotIn("credential-body-must-not-appear", serialized)

    def _service(self, workspace: str, **overrides) -> WorkspaceTextSearchService:
        values = {
            "enabled": True,
            "workspace_root_allowlist": (workspace,),
            "max_files": 20,
            "max_file_bytes": 256,
            "max_total_scan_bytes": 1024,
            "max_matches": 10,
            "max_line_chars": 128,
            "max_snippet_chars": 80,
        }
        values.update(overrides)
        return WorkspaceTextSearchService(WorkspaceTextSearchSettings(**values))

    @staticmethod
    def _request(workspace: str, query: str, **overrides) -> WorkspaceTextSearchRequest:
        values = {
            "session_id": "session-text-search-001",
            "run_id": "run-text-search-001",
            "operation_id": "search-001",
            "workspace_root": workspace,
            "workspace_reference": "agent-workspace:tenant-10/project-20/session-text-search-001",
            "query": query,
        }
        values.update(overrides)
        return WorkspaceTextSearchRequest(**values)


if __name__ == "__main__":
    unittest.main()
