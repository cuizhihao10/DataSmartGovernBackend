import os
import sys
import unittest
import json

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
import datasmart_ai_runtime.services.skill_registry_client as skill_registry_client_module
from datasmart_ai_runtime.services.skill_registry_client import (
    AgentSkillPublicationManifest,
    JavaAgentSkillRegistryClient,
    SkillRegistryClientError,
)


class JavaAgentSkillRegistryClientTest(unittest.TestCase):
    def test_publication_manifest_request_forwards_tenant_and_project_scope(self) -> None:
        """读取 Manifest 时应透传租户/项目范围，才能消费 Java scoped Manifest。"""

        captured: dict[str, object] = {}
        original_urlopen = skill_registry_client_module.urlopen

        def fake_urlopen(request, timeout):  # noqa: ANN001 - 测试替身需要贴合 urllib 调用形态
            captured["url"] = request.full_url
            captured["tenantHeader"] = request.get_header("X-datasmart-tenant-id")
            captured["projectHeader"] = request.get_header("X-datasmart-project-id")
            captured["traceHeader"] = request.get_header("X-trace-id")
            captured["timeout"] = timeout
            return _FakeHttpResponse(
                {
                    "code": 0,
                    "reason": "SUCCESS",
                    "data": {
                        "schemaVersion": "datasmart.agent.skill.publication-manifest.v1",
                        "manifestType": "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST",
                        "protocolHint": "MCP_STYLE_SKILL_MANIFEST",
                        "descriptorSchemaVersion": "datasmart.agent.skill.v1",
                        "publicationMode": "READY_ONLY",
                        "contentFingerprint": "s" * 64,
                        "includeDisabled": False,
                        "skillCount": 0,
                        "skills": [],
                    },
                }
            )

        skill_registry_client_module.urlopen = fake_urlopen
        try:
            client = JavaAgentSkillRegistryClient(base_url="http://agent-runtime.local", timeout_seconds=7)
            manifest = client.get_publication_manifest(
                include_disabled=False,
                tenant_id="tenant-10",
                project_id="project-20",
                trace_id="trace-scope",
            )
        finally:
            skill_registry_client_module.urlopen = original_urlopen

        self.assertEqual("s" * 64, manifest.content_fingerprint)
        self.assertIn("tenantId=tenant-10", str(captured["url"]))
        self.assertIn("projectId=project-20", str(captured["url"]))
        self.assertEqual("tenant-10", captured["tenantHeader"])
        self.assertEqual("project-20", captured["projectHeader"])
        self.assertEqual("trace-scope", captured["traceHeader"])
        self.assertEqual(7, captured["timeout"])

    def test_parse_java_skill_descriptor_response_to_skill_definition(self) -> None:
        payload = {
            "code": 0,
            "reason": "SUCCESS",
            "data": [
                {
                    "schemaVersion": "datasmart.agent.skill.v1",
                    "descriptorType": "DATASMART_AGENT_SKILL",
                    "protocolHint": "AGENT_CARD_STYLE",
                    "skillCode": "quality.rule.design",
                    "displayName": "质量规则设计 Skill",
                    "description": "根据元数据和历史异常生成规则草案",
                    "domain": "DATA_QUALITY",
                    "requiredTools": ["datasource.metadata.read", "quality.rule.suggest"],
                    "requiredPermissions": ["quality:rule:draft"],
                    "triggerKeywords": ["质量", "规则"],
                    "examples": ["请生成客户主数据质量规则"],
                    "governance": {
                        "enabled": True,
                        "riskLevel": "MEDIUM",
                        "approvalPolicy": "DRAFT_REVIEW",
                        "tenantScoped": True,
                        "projectScoped": True,
                        "auditRequired": True,
                    },
                    "memory": {
                        "memoryDependencies": ["SEMANTIC", "EPISODIC"],
                        "defaultMemoryScope": "PROJECT",
                        "retentionDays": 30,
                    },
                }
            ],
        }

        skills = JavaAgentSkillRegistryClient.parse_descriptor_platform_response(payload)

        self.assertEqual(1, len(skills))
        skill = skills[0]
        self.assertEqual("quality.rule.design", skill.skill_code)
        self.assertEqual(GovernanceDomain.DATA_QUALITY, skill.domain)
        self.assertEqual(("datasource.metadata.read", "quality.rule.suggest"), skill.required_tools)
        self.assertEqual((AgentMemoryType.SEMANTIC, AgentMemoryType.EPISODIC), skill.memory_dependencies)
        self.assertEqual("MEDIUM", skill.risk_level)
        self.assertEqual("DRAFT_REVIEW", skill.approval_policy)
        self.assertEqual("AGENT_CARD_STYLE", skill.attributes["protocolHint"])
        self.assertTrue(skill.attributes["tenantScoped"])
        self.assertEqual("PROJECT", skill.attributes["defaultMemoryScope"])

    def test_non_success_response_raises_clear_error(self) -> None:
        with self.assertRaises(SkillRegistryClientError):
            JavaAgentSkillRegistryClient.parse_descriptor_platform_response(
                {"code": 500, "reason": "FAILED", "message": "Skill 注册表不可用", "data": None}
            )

    def test_parse_java_skill_publication_manifest_response(self) -> None:
        """验证 Python Runtime 可以消费 Java 控制面的 Skill 发布 Manifest。

        这个测试不是简单检查字段拷贝，而是在固定一条跨语言契约：
        Java 使用 camelCase record 输出，Python 运行时转换为 snake_case 不可变对象。
        后续如果 Manifest 要接 MCP-style tools/resources/prompts 适配器，这个对象会成为运行时侧的基础输入。
        """

        payload = {
            "code": 0,
            "reason": "SUCCESS",
            "data": {
                "schemaVersion": "datasmart.agent.skill.publication-manifest.v1",
                "manifestType": "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST",
                "protocolHint": "MCP_STYLE_SKILL_MANIFEST",
                "descriptorSchemaVersion": "datasmart.agent.skill.v1",
                "publicationMode": "SNAPSHOT",
                "contentFingerprint": "f" * 64,
                "generatedAt": "2026-06-04T19:20:00Z",
                "includeDisabled": False,
                "domainFilter": "ALL",
                "riskLevelFilter": "ALL",
                "skillCount": 1,
                "skills": [
                    {
                        "skillCode": "quality.rule.design",
                        "displayName": "质量规则设计 Skill",
                        "domain": "DATA_QUALITY",
                        "publicationState": "READY",
                        "contentFingerprint": "a" * 64,
                        "descriptorEndpoints": [
                            "/agent-runtime/skills/quality.rule.design/descriptor",
                            "/api/agent/skills/quality.rule.design/descriptor",
                        ],
                        "enabled": True,
                        "riskLevel": "MEDIUM",
                        "approvalPolicy": "DRAFT_REVIEW",
                        "auditRequired": True,
                        "tenantScoped": True,
                        "projectScoped": True,
                        "requiredTools": ["datasource.metadata.read", "quality.rule.suggest"],
                        "requiredPermissions": ["quality:rule:draft"],
                        "memoryDependencies": ["SEMANTIC", "EPISODIC"],
                        "publicationWarnings": ["未发现明显治理缺口"],
                    }
                ],
                "consumerGuidance": ["Python Runtime 应优先比较 contentFingerprint"],
                "compatibilityNotes": ["当前 Manifest 是 DataSmart 内部 MCP-style 契约"],
                "recommendedActions": ["后续迁移为数据库发布表"],
            },
        }

        manifest = JavaAgentSkillRegistryClient.parse_publication_manifest_platform_response(payload)

        self.assertIsInstance(manifest, AgentSkillPublicationManifest)
        self.assertEqual("datasmart.agent.skill.publication-manifest.v1", manifest.schema_version)
        self.assertEqual("MCP_STYLE_SKILL_MANIFEST", manifest.protocol_hint)
        self.assertEqual("SNAPSHOT", manifest.publication_mode)
        self.assertEqual("f" * 64, manifest.content_fingerprint)
        self.assertFalse(manifest.include_disabled)
        self.assertEqual(1, manifest.skill_count)
        self.assertEqual(("Python Runtime 应优先比较 contentFingerprint",), manifest.consumer_guidance)

        skill = manifest.skills[0]
        self.assertEqual("quality.rule.design", skill.skill_code)
        self.assertEqual("READY", skill.publication_state)
        self.assertEqual("a" * 64, skill.content_fingerprint)
        self.assertEqual(("datasource.metadata.read", "quality.rule.suggest"), skill.required_tools)
        self.assertEqual(("SEMANTIC", "EPISODIC"), skill.memory_dependencies)
        self.assertTrue(skill.audit_required)
        self.assertIn("/api/agent/skills/quality.rule.design/descriptor", skill.descriptor_endpoints)

    def test_publication_manifest_error_response_raises_clear_error(self) -> None:
        with self.assertRaises(SkillRegistryClientError):
            JavaAgentSkillRegistryClient.parse_publication_manifest_platform_response(
                {"code": 503, "reason": "SERVICE_UNAVAILABLE", "message": "Skill 发布 Manifest 不可用", "data": None}
            )


class _FakeHttpResponse:
    """urllib `urlopen` 的最小测试响应对象。

    客户端只依赖上下文管理器和 `read()`，因此测试替身也只实现这两个行为，避免为了一个请求构造测试
    引入真实 HTTP server。响应正文仍是平台统一 JSON，不包含 token、endpoint 或任何敏感 Manifest 明细。
    """

    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:  # noqa: ANN001 - 上下文管理器协议参数
        return False

    def read(self) -> bytes:
        return json.dumps(self._payload).encode("utf-8")


if __name__ == "__main__":
    unittest.main()
