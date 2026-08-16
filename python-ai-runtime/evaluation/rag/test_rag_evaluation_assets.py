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
    ("*", "*", "*"): 89,
    ("10", "101", "tenant-10-project-101"): 89,
    ("10", "102", "tenant-10-project-102"): 89,
    ("20", "201", "tenant-20-project-201"): 89,
}
EXPECTED_CASE_TYPE_COUNTS = {
    "exact_error_code": 80,
    "history_lookup": 16,
    "semantic_paraphrase": 24,
    "multi_document": 12,
    "no_answer": 12,
    "cross_scope_refusal": 28,
    "stale_conflict": 12,
    "multiformat_exact": 260,
    "cross_format_semantic": 260,
    "cross_format_multi_document": 48,
}
EXPECTED_FORMAT_COUNTS = {
    "csv": 16,
    "docx": 120,
    "json": 16,
    "jsonl": 16,
    "log": 8,
    "md": 96,
    "sql": 8,
    "txt": 16,
    "xlsx": 60,
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

        self.assertEqual(356, len(self.documents))
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
                89,
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
        self.assertEqual(356, len(self.documents_by_id))
        self.assertEqual(356, len(self.documents_by_uri))
        self.assertEqual(EXPECTED_FORMAT_COUNTS, self.manifest["formatCounts"])
        extracted_by_anchor: dict[str, str] = {}

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
            extracted_by_anchor[document["metadata"]["retrievalAnchor"]] = content
            self.assertIn(document["metadata"]["retrievalAnchor"], content)
            self.assertIn(document["metadata"]["artifactCode"], content)
            content_format = document["contentFormat"]
            if content_format == "docx":
                self.assertGreaterEqual(len(content), 30_000, document["documentId"])
            elif content_format == "xlsx":
                self.assertGreaterEqual(len(content), 80_000, document["documentId"])
                self.assertGreaterEqual(content.count("工作表："), 5, document["documentId"])
            elif content_format == "txt":
                self.assertGreaterEqual(len(content.splitlines()), 200, document["documentId"])
            elif content_format == "json":
                self.assertGreaterEqual(content.count('"recordId"'), 240, document["documentId"])
            elif content_format == "jsonl":
                self.assertGreaterEqual(content.count('"recordId"'), 600, document["documentId"])
            elif content_format == "csv":
                self.assertGreaterEqual(len(content.splitlines()), 601, document["documentId"])
            elif content_format == "log":
                self.assertGreaterEqual(content.count("traceId="), 1_200, document["documentId"])
            elif content_format == "sql":
                self.assertGreaterEqual(
                    content.count("INSERT INTO synthetic_recovery_case"),
                    320,
                    document["documentId"],
                )

        # 同一合成任务身份必须跨任务案例、日志、恢复事件和数据库台账出现，才能评测 RAG 的
        # 多来源证据拼接，而不是只在单文件内部命中关键词。
        correlated_anchors = (
            "global:workbook-full-load-task-cases",
            "global:worker-execution",
            "global:task-case-library",
            "global:database-recovery-ledger",
        )
        for anchor in correlated_anchors:
            self.assertIn("TASK-global-0001", extracted_by_anchor[anchor])
        self.assertGreaterEqual(
            extracted_by_anchor["global:reference-api-websocket"].count("接口编号："),
            400,
        )

    def test_document_types_keep_their_own_content_responsibility(self) -> None:
        """防止通用事故模板再次污染用户、管理员、接口、产品和部署类文档。"""

        misplaced_case_markers = (
            "任务失败、运维与事故案例",
            "关联事故与任务案例",
            "失败原因：",
            "修复动作风险目录",
        )
        incident_slugs = {
            "record-operations-incident",
            "postmortem-schema-drift",
            "postmortem-foreign-key",
            "postmortem-rate-limit",
            "postmortem-checkpoint",
            "postmortem-kafka-backlog",
        }
        for document in self.documents:
            if document["workspaceKey"] != "*" or document["contentFormat"] != "docx":
                continue
            slug = Path(document["path"]).stem
            if slug in incident_slugs:
                continue
            content = self._global_document_content(slug)
            for marker in misplaced_case_markers:
                self.assertNotIn(marker, content, slug)

        responsibility_markers = {
            "manual-user-guide": ("用户操作编号：", "操作步骤：", "所需权限："),
            "manual-administrator-guide": ("管理操作编号：", "适用管理员：", "审计结果："),
            "manual-deployment-guide": ("部署步骤编号：", "配置项：", "验收命令："),
            "product-feature-specification": ("特性编号：", "目标用户：", "功能边界："),
            "manual-security-approval": ("策略编号：", "适用主体：", "审批要求："),
        }
        for slug, required_markers in responsibility_markers.items():
            content = self._global_document_content(slug)
            for marker in misplaced_case_markers:
                self.assertNotIn(marker, content, slug)
            for marker in required_markers:
                self.assertIn(marker, content, slug)

        self.assertGreaterEqual(
            self._global_document_content("manual-user-guide").count("用户操作编号："),
            80,
        )
        self.assertGreaterEqual(
            self._global_document_content("manual-administrator-guide").count("管理操作编号："),
            100,
        )
        self.assertGreaterEqual(
            self._global_document_content("product-feature-specification").count("特性编号："),
            100,
        )

    def test_api_documents_only_describe_real_contracts(self) -> None:
        """接口文档必须记录真实接口、参数与示例，不能混入任务事故账本。"""

        api_slugs = (
            "reference-api-websocket",
            "reference-authentication-api",
            "reference-agent-api",
            "reference-task-api",
            "reference-data-sync-api",
            "reference-recovery-api",
            "reference-websocket-events",
        )
        required_markers = (
            "接口编号：",
            "来源控制器：",
            "请求方法：",
            "访问路径：",
            "请求参数：",
            "请求示例：",
            "成功响应示例：",
            "错误响应：",
        )
        for slug in api_slugs:
            content = self._global_document_content(slug)
            for marker in required_markers:
                self.assertIn(marker, content, slug)
            self.assertNotIn("任务失败、运维与事故案例", content, slug)
            self.assertNotIn("失败原因：", content, slug)

        comprehensive = self._global_document_content("reference-api-websocket")
        self.assertEqual(475, comprehensive.count("接口编号："))
        for marker in ("来源控制器：", "请求方法：", "访问路径：", "请求示例：", "成功响应示例："):
            self.assertEqual(475, comprehensive.count(marker), marker)
        self.assertIn("公开接口", comprehensive)
        self.assertIn("内部控制面接口", comprehensive)
        self.assertNotIn("：undefined", comprehensive)
        self.assertNotIn("This route is anonymous", comprehensive)
        self.assertNotIn("A future trusted gateway trace", comprehensive)

    def test_operational_incident_and_test_documents_use_domain_specific_records(self) -> None:
        """运维、事故和测试资料可以有记录，但记录结构必须符合各自业务含义。"""

        operations = self._global_document_content("manual-operations-guide")
        self.assertGreaterEqual(operations.count("运维作业编号："), 120)
        self.assertIn("检查命令：", operations)
        self.assertIn("回滚步骤：", operations)
        self.assertNotIn("任务失败、运维与事故案例", operations)

        incident = self._global_document_content("record-operations-incident")
        self.assertGreaterEqual(incident.count("事故编号："), 200)
        for marker in ("影响范围：", "根因：", "证据来源：", "处置时间线：", "恢复验证："):
            self.assertIn(marker, incident)

        report = self._global_document_content("report-platform-test")
        self.assertGreaterEqual(report.count("测试用例编号："), 180)
        for marker in ("测试目标：", "前置条件：", "测试步骤：", "预期结果：", "实际结果："):
            self.assertIn(marker, report)
        self.assertNotIn("修复动作风险目录", report)

    def test_workbooks_and_structured_files_use_topic_specific_schemas(self) -> None:
        """表格和结构化资料不能因为复用生成器而全部退化成失败诊断台账。"""

        successful = self._global_document_content("workbook-success-task-parameters")
        self.assertIn("工作表：成功任务", successful)
        self.assertNotIn("工作表：失败诊断", successful)
        self.assertNotIn("失败原因", successful)

        test_matrix = self._global_document_content("workbook-test-result-matrix")
        self.assertIn("工作表：测试用例", test_matrix)
        self.assertIn("工作表：缺陷记录", test_matrix)
        self.assertNotIn("工作表：失败诊断", test_matrix)

        incident_ledger = self._global_document_content("workbook-incident-repair-ledger")
        self.assertIn("工作表：事故记录", incident_ledger)
        self.assertIn("工作表：根因与修复", incident_ledger)

        connector = self._global_document_content("connector-capabilities")
        self.assertIn('"connectorId"', connector)
        self.assertIn('"maximumBatchSize"', connector)
        self.assertNotIn('"errorCode"', connector)
        self.assertNotIn('"incidentId"', connector)

        successful_runs = self._global_document_content("successful-runs")
        header = successful_runs.splitlines()[0]
        self.assertIn("config_version", header)
        self.assertNotIn("error_code", header)
        self.assertNotIn("failure_reason", header)

        task_cases = self._global_document_content("task-case-library")
        self.assertIn('"failureReason"', task_cases)
        self.assertIn('"rootCause"', task_cases)

    def _global_document_content(self, slug: str) -> str:
        """按全局范围与文件名读取一份资产的安全提取文本。"""

        suffix_marker = f"/{slug}."
        document = next(
            item
            for item in self.documents
            if item["workspaceKey"] == "*" and suffix_marker in item["path"].replace("\\", "/")
        )
        source_path = ASSET_ROOT / document["path"]
        return extract_rag_document_bytes(source_path.read_bytes(), source_path.suffix).content

    def test_golden_case_count_and_reference_contract(self) -> None:
        """证明至少 120 条用例、每个 URI 有来源、每份文档均有独立检索用例。"""

        self.assertGreaterEqual(len(self.cases), 120)
        self.assertEqual(752, len(self.cases))
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
        self.assertEqual(40, len(refusal_cases))
        for golden_case in refusal_cases:
            self.assertEqual([], golden_case["relevantDocuments"])
            self.assertEqual([], golden_case["expectedCitationUris"])
            self.assertTrue(golden_case["refusalReason"].strip())

        cross_scope_cases = [
            golden_case for golden_case in self.cases if golden_case["caseType"] == "cross_scope_refusal"
        ]
        self.assertEqual(28, len(cross_scope_cases))
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
