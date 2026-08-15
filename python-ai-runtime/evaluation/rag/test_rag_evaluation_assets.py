"""DataSmart 中文 RAG 合成评测资产的轻量合同测试。

本测试只读取同目录下的静态评测资产，刻意不导入应用服务、不连接数据库，也不需要
pytest 或第三方 SDK。它保护的不是模型效果，而是评测集自身能否安全、稳定地被检索
评测器消费：数量不能缩水、哈希不能漂移、引用必须存在、范围必须可达、拒答必须完整。
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import unittest
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any


ASSET_ROOT = Path(__file__).resolve().parent
PYTHON_RUNTIME_SOURCE = ASSET_ROOT.parents[1] / "src"
if str(PYTHON_RUNTIME_SOURCE) not in sys.path:
    sys.path.insert(0, str(PYTHON_RUNTIME_SOURCE))

from datasmart_ai_runtime.services.rag.document_extractor import extract_rag_document_bytes  # noqa: E402


MANIFEST_PATH = ASSET_ROOT / "manifest.json"
CASES_PATH = ASSET_ROOT / "golden_cases.jsonl"

REQUIRED_DOCUMENT_FIELDS = {
    "documentId",
    "title",
    "path",
    "sourceUri",
    "tenantId",
    "projectId",
    "workspaceKey",
    "sourceType",
    "tags",
    "sensitivityLevel",
    "metadata",
    "enabled",
    "contentFormat",
    "mediaType",
    "contentSha256",
    "extractedTextSha256",
}
REQUIRED_CASE_FIELDS = {
    "caseId",
    "question",
    "scope",
    "retrievalMode",
    "topK",
    "relevantDocuments",
    "expectedCitationUris",
    "forbiddenDocumentIds",
    "shouldRefuse",
    "refusalReason",
    "sourceTypes",
    "tags",
    "caseType",
}
VALID_SOURCE_TYPES = {
    "document",
    "rule",
    "metadata",
    "runbook",
    "incident",
    "task_case",
    "dataset",
    "memory_export",
    "wiki",
    "git_history",
    "exact_search",
}
EXPECTED_SCOPE_COUNTS = {
    ("*", "*", "*"): 47,
    ("10", "101", "tenant-10-project-101"): 47,
    ("10", "102", "tenant-10-project-102"): 47,
    ("20", "201", "tenant-20-project-201"): 47,
}
EXPECTED_CASE_TYPE_COUNTS = {
    "exact_error_code": 80,
    "history_lookup": 16,
    "semantic_paraphrase": 24,
    "multi_document": 12,
    "no_answer": 12,
    "cross_scope_refusal": 20,
    "stale_conflict": 12,
    "multiformat_exact": 92,
    "cross_format_semantic": 24,
    "cross_format_multi_document": 16,
}
EXPECTED_FORMAT_COUNTS = {
    "csv": 4,
    "docx": 40,
    "json": 8,
    "jsonl": 4,
    "log": 4,
    "md": 96,
    "sql": 4,
    "txt": 8,
    "xlsx": 20,
}

# 合同只扫描实际评测资产，不扫描本测试或生成器，因为代码中的正则字面量本身会包含
# 若干敏感模式关键词。模式选择“高置信度”形态，避免把正常的中文治理说明误判为泄露。
SENSITIVE_PATTERNS = (
    re.compile(r"(?i)\b(?:password|passwd|api[_-]?key|secret|access[_-]?key)\b\s*[:=]\s*\S+"),
    re.compile(r"(?i)\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"(?i)\bsk-[a-z0-9]{16,}\b"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?i)\bbearer\s+[a-z0-9._-]{12,}\b"),
    re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
    re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"),
    re.compile(r"(?<!\d)[1-9]\d{16}[0-9Xx](?!\d)"),
)


class RagEvaluationAssetsTest(unittest.TestCase):
    """校验可再生 RAG 语料的安全边界和消费合同。"""

    @classmethod
    def setUpClass(cls) -> None:
        """一次性读取资产并建立 ID、URI 索引，保证每个测试使用同一份输入。"""

        cls.manifest: dict[str, Any] = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        cls.documents: list[dict[str, Any]] = cls.manifest["documents"]
        cls.cases: list[dict[str, Any]] = [
            json.loads(line)
            for line in CASES_PATH.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        cls.documents_by_id = {document["documentId"]: document for document in cls.documents}
        cls.documents_by_uri = {document["sourceUri"]: document for document in cls.documents}

    def test_document_count_scope_distribution_and_required_categories(self) -> None:
        """防止文档缩水，并证明四个范围的相似干扰文档都存在。"""

        self.assertEqual(188, len(self.documents))
        distribution = Counter(
            (document["tenantId"], document["projectId"], document["workspaceKey"])
            for document in self.documents
        )
        self.assertEqual(EXPECTED_SCOPE_COUNTS, dict(distribution))
        categories = {document["metadata"]["category"] for document in self.documents}
        self.assertTrue(
            {"architecture", "product", "runbook", "incident", "sync", "metadata", "governance", "history"}
            .issubset(categories)
        )
        source_types = {document["sourceType"] for document in self.documents}
        self.assertTrue({"incident", "task_case", "dataset"}.issubset(source_types))
        for scope_key in ("global", "tenant-10-project-101", "tenant-10-project-102", "tenant-20-project-201"):
            self.assertEqual(
                47,
                sum(1 for document in self.documents if f"/{scope_key}/" in document["sourceUri"]),
            )
        self.assertEqual(
            EXPECTED_FORMAT_COUNTS,
            dict(Counter(document["contentFormat"] for document in self.documents)),
        )

    def test_manifest_fields_paths_and_content_hashes(self) -> None:
        """验证 Manifest、原文件哈希、提取文本哈希和格式声明。"""

        self.assertEqual("datasmart.rag-evaluation-assets.v2", self.manifest["schemaVersion"])
        self.assertEqual("synthetic-only", self.manifest["assetBoundary"])
        self.assertEqual(188, len(self.documents_by_id))
        self.assertEqual(188, len(self.documents_by_uri))
        self.assertEqual(EXPECTED_FORMAT_COUNTS, self.manifest["formatCounts"])

        for document in self.documents:
            self.assertTrue(REQUIRED_DOCUMENT_FIELDS.issubset(document), document["documentId"])
            self.assertTrue(document["enabled"], document["documentId"])
            self.assertIn(document["sourceType"], VALID_SOURCE_TYPES)
            self.assertEqual("synthetic-only", document["metadata"]["assetBoundary"])
            self.assertIn(document["metadata"]["sourceStatus"], {"COMPLETE", "SUPERSEDED"})
            datetime.fromisoformat(document["metadata"]["effectiveAt"].replace("Z", "+00:00"))
            self.assertGreaterEqual(document["metadata"]["sourceConfidence"], 0.0)
            self.assertLessEqual(document["metadata"]["sourceConfidence"], 1.0)
            self.assertTrue(document["metadata"]["sourceConfidenceBasis"])
            self.assertTrue(document["sourceUri"].startswith("synthetic://datasmart-govern/rag-evaluation/"))
            self.assertRegex(document["contentSha256"], r"^[0-9a-f]{64}$")
            self.assertRegex(document["extractedTextSha256"], r"^[0-9a-f]{64}$")

            source_path = (ASSET_ROOT / document["path"]).resolve()
            self.assertTrue(source_path.is_relative_to(ASSET_ROOT.resolve()))
            payload = source_path.read_bytes()
            self.assertEqual(document["contentSha256"], hashlib.sha256(payload).hexdigest())
            extracted = extract_rag_document_bytes(payload, source_path.suffix)
            self.assertEqual(document["contentFormat"], extracted.format_name)
            self.assertEqual(document["mediaType"], extracted.media_type)
            self.assertEqual(
                document["extractedTextSha256"],
                hashlib.sha256(extracted.content.encode("utf-8")).hexdigest(),
            )
            content = extracted.content
            self.assertIn(document["metadata"]["retrievalAnchor"], content)
            self.assertIn(document["metadata"]["artifactCode"], content)

    def test_golden_case_count_and_reference_contract(self) -> None:
        """证明至少 120 条用例、每个 URI 有来源、每份文档均有独立检索用例。"""

        self.assertGreaterEqual(len(self.cases), 120)
        self.assertEqual(308, len(self.cases))
        case_ids = [golden_case["caseId"] for golden_case in self.cases]
        self.assertEqual(len(case_ids), len(set(case_ids)))
        case_types = Counter(golden_case["caseType"] for golden_case in self.cases)
        self.assertEqual(EXPECTED_CASE_TYPE_COUNTS, dict(case_types))

        exact_references = {
            reference["documentId"]
            for golden_case in self.cases
            if golden_case["caseType"] in {
                "exact_error_code",
                "history_lookup",
                "multiformat_exact",
            }
            for reference in golden_case["relevantDocuments"]
        }
        self.assertEqual(set(self.documents_by_id), exact_references)

        for golden_case in self.cases:
            self.assertTrue(REQUIRED_CASE_FIELDS.issubset(golden_case), golden_case["caseId"])
            self.assertIn(golden_case["retrievalMode"], {"hybrid", "lexical", "vector", "exact_search"})
            self.assertGreaterEqual(golden_case["topK"], 1)
            self.assertTrue(golden_case["question"].strip())
            scope = golden_case["scope"]
            self.assertEqual({"tenantId", "projectId", "workspaceKey"}, set(scope))

            relevant_ids = {reference["documentId"] for reference in golden_case["relevantDocuments"]}
            for reference in golden_case["relevantDocuments"]:
                self.assertIn(reference["documentId"], self.documents_by_id)
                self.assertIn(reference["relevance"], {1, 2, 3})
                self.assertTrue(self._is_accessible(scope, self.documents_by_id[reference["documentId"]]))
            expected_uris = set(golden_case["expectedCitationUris"])
            self.assertEqual(
                {self.documents_by_id[document_id]["sourceUri"] for document_id in relevant_ids},
                expected_uris,
            )
            self.assertTrue(expected_uris.issubset(self.documents_by_uri))
            for forbidden_id in golden_case["forbiddenDocumentIds"]:
                self.assertIn(forbidden_id, self.documents_by_id)
                self.assertNotIn(forbidden_id, relevant_ids)

    def test_scope_and_refusal_contracts_fail_closed(self) -> None:
        """验证无答案、越权和过期冲突都不能被普通可回答用例稀释。"""

        refusal_cases = [golden_case for golden_case in self.cases if golden_case["shouldRefuse"]]
        self.assertEqual(32, len(refusal_cases))
        for golden_case in refusal_cases:
            self.assertEqual([], golden_case["relevantDocuments"])
            self.assertEqual([], golden_case["expectedCitationUris"])
            self.assertTrue(golden_case["refusalReason"].strip())

        cross_scope_cases = [
            golden_case for golden_case in self.cases if golden_case["caseType"] == "cross_scope_refusal"
        ]
        self.assertEqual(20, len(cross_scope_cases))
        for golden_case in cross_scope_cases:
            self.assertTrue(golden_case["shouldRefuse"])
            self.assertTrue(golden_case["forbiddenDocumentIds"])
            for forbidden_id in golden_case["forbiddenDocumentIds"]:
                self.assertFalse(self._is_accessible(golden_case["scope"], self.documents_by_id[forbidden_id]))

        no_answer_cases = [golden_case for golden_case in self.cases if golden_case["caseType"] == "no_answer"]
        self.assertEqual(12, len(no_answer_cases))
        self.assertTrue(all(not golden_case["forbiddenDocumentIds"] for golden_case in no_answer_cases))

        stale_cases = [golden_case for golden_case in self.cases if golden_case["caseType"] == "stale_conflict"]
        self.assertEqual(12, len(stale_cases))
        for golden_case in stale_cases:
            self.assertFalse(golden_case["shouldRefuse"])
            self.assertTrue(golden_case["forbiddenDocumentIds"])
            self.assertIn("git_history", golden_case["sourceTypes"])
            self.assertTrue(
                all(
                    self.documents_by_id[document_id]["metadata"]["evidenceStatus"] == "superseded"
                    for document_id in golden_case["forbiddenDocumentIds"]
                )
            )
            self.assertTrue(
                all(
                    self.documents_by_id[reference["documentId"]]["metadata"]["evidenceStatus"] == "current"
                    for reference in golden_case["relevantDocuments"]
                )
            )

    def test_assets_do_not_match_high_confidence_sensitive_patterns(self) -> None:
        """维持“纯合成、无凭据/PII/原始客户数据”的静态最小安全合同。"""

        # SHA-256 是随机摘要，可能偶然出现一段满足身份证数字长度的连续字符；它不是
        # 语料字段，也不应该被 PII 检查误报。去掉这一类派生完整性字段后，Manifest 中
        # 的标题、范围、URI、标签和 metadata 仍全部纳入扫描。
        manifest_without_digests = json.loads(json.dumps(self.manifest))
        manifest_without_digests.pop("multiformatCatalogSha256", None)
        for document in manifest_without_digests["documents"]:
            document.pop("contentSha256")
            document.pop("extractedTextSha256")

        assets_to_scan = [
            ("manifest.json", json.dumps(manifest_without_digests, ensure_ascii=False)),
            ("golden_cases.jsonl", CASES_PATH.read_text(encoding="utf-8")),
            ("README.md", (ASSET_ROOT / "README.md").read_text(encoding="utf-8")),
        ]
        assets_to_scan.extend(
            (
                str(document["path"]),
                extract_rag_document_bytes(
                    (ASSET_ROOT / document["path"]).read_bytes(),
                    Path(document["path"]).suffix,
                ).content,
            )
            for document in self.documents
        )
        for asset_label, content in assets_to_scan:
            for pattern in SENSITIVE_PATTERNS:
                match = pattern.search(content)
                self.assertIsNone(
                    match,
                    f"{asset_label} 匹配到不允许的敏感模式：{pattern.pattern}",
                )

    @staticmethod
    def _is_accessible(scope: dict[str, str], document: dict[str, Any]) -> bool:
        """复刻 RAG 查询的 `*` 通配范围语义，用于静态隔离合同检查。"""

        return all(
            document[document_field] in {"*", scope[scope_field]}
            for document_field, scope_field in (
                ("tenantId", "tenantId"),
                ("projectId", "projectId"),
                ("workspaceKey", "workspaceKey"),
            )
        )


if __name__ == "__main__":
    unittest.main()
