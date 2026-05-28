import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.services.skill_registry_client import (
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


if __name__ == "__main__":
    unittest.main()
