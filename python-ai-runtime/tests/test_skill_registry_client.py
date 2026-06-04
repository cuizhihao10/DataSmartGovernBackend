import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.services.skill_registry_client import (
    AgentSkillPublicationManifest,
    JavaAgentSkillRegistryClient,
    SkillRegistryClientError,
)


class JavaAgentSkillRegistryClientTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
