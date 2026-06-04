import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.skill_registry_client import (  # noqa: E402
    AgentSkillPublicationItem,
    AgentSkillPublicationManifest,
    SkillRegistryClientError,
)
from datasmart_ai_runtime.services.skills import (  # noqa: E402
    AgentSkillPublicationDiagnosticsSettings,
    AgentSkillPublicationManifestDiagnosticsService,
    build_skill_publication_manifest_diagnostics_service,
    skill_publication_diagnostics_settings_from_env,
)


class FakePublicationManifestClient:
    """测试用远端 Manifest 客户端。

    真实生产中该对象由 `JavaAgentSkillRegistryClient` 承担；测试里只保留 `get_publication_manifest`
    这一条契约，能让诊断服务测试聚焦在刷新、fallback 和低敏摘要上。
    """

    def __init__(self, manifest: AgentSkillPublicationManifest | None = None, should_fail: bool = False) -> None:
        self.manifest = manifest
        self.should_fail = should_fail
        self.calls: list[bool] = []

    def get_publication_manifest(self, include_disabled: bool = False, trace_id: str | None = None):
        self.calls.append(include_disabled)
        if self.should_fail:
            raise SkillRegistryClientError("模拟 Java Skill Manifest 不可用")
        return self.manifest


class SkillPublicationDiagnosticsTest(unittest.TestCase):
    def test_diagnostics_reports_local_default_when_remote_is_not_configured(self) -> None:
        service = build_skill_publication_manifest_diagnostics_service(agent_runtime_base_url=None)

        diagnostics = service.diagnostics()

        self.assertEqual("LOCAL_DEFAULT_ONLY", diagnostics["status"])
        self.assertEqual("local-default", diagnostics["source"])
        self.assertTrue(diagnostics["fallback"])
        self.assertFalse(diagnostics["remoteManifestAvailable"])
        self.assertGreaterEqual(diagnostics["readySkillCount"], 1)
        self.assertIn("LOCAL_DEFAULT", diagnostics["publicationStateCounts"])

    def test_refresh_records_manifest_fingerprint_and_non_ready_summary(self) -> None:
        client = FakePublicationManifestClient(manifest=_manifest())
        service = AgentSkillPublicationManifestDiagnosticsService(
            client,
            AgentSkillPublicationDiagnosticsSettings(enabled=True, include_disabled=True, max_non_ready_items=5),
        )

        diagnostics = service.refresh(trace_id="trace-a")

        self.assertEqual([True], client.calls)
        self.assertEqual("REMOTE_READY", diagnostics["status"])
        self.assertEqual("java-agent-runtime", diagnostics["source"])
        self.assertFalse(diagnostics["fallback"])
        self.assertTrue(diagnostics["remoteManifestAvailable"])
        self.assertEqual("f" * 64, diagnostics["manifestFingerprint"])
        self.assertEqual(1, diagnostics["readySkillCount"])
        self.assertEqual(2, diagnostics["nonReadySkillCount"])
        self.assertEqual({"DISABLED": 1, "NEEDS_APPROVAL_POLICY": 1, "READY": 1}, diagnostics["publicationStateCounts"])
        self.assertEqual({"HIGH": 1, "LOW": 1, "MEDIUM": 1}, diagnostics["riskLevelCounts"])
        self.assertEqual("disabled.skill", diagnostics["nonReadySkills"][0]["skillCode"])
        self.assertTrue(any("manifestFingerprint" in item for item in diagnostics["recommendedActions"]))

    def test_remote_failure_falls_back_to_local_skills_when_not_required(self) -> None:
        client = FakePublicationManifestClient(should_fail=True)
        service = AgentSkillPublicationManifestDiagnosticsService(
            client,
            AgentSkillPublicationDiagnosticsSettings(enabled=True, required=False),
        )

        diagnostics = service.refresh()

        self.assertEqual("REMOTE_UNAVAILABLE_FALLBACK", diagnostics["status"])
        self.assertTrue(diagnostics["fallback"])
        self.assertFalse(diagnostics["remoteManifestAvailable"])
        self.assertIn("模拟 Java Skill Manifest 不可用", diagnostics["lastError"])
        self.assertGreaterEqual(diagnostics["localFallbackSkillCount"], 1)
        self.assertTrue(any("回退本地默认 Skill" in item for item in diagnostics["recommendedActions"]))

    def test_required_remote_manifest_raises_when_refresh_fails(self) -> None:
        client = FakePublicationManifestClient(should_fail=True)
        service = AgentSkillPublicationManifestDiagnosticsService(
            client,
            AgentSkillPublicationDiagnosticsSettings(enabled=True, required=True),
        )

        with self.assertRaises(SkillRegistryClientError):
            service.refresh()

    def test_settings_can_be_loaded_from_env_like_mapping(self) -> None:
        settings = skill_publication_diagnostics_settings_from_env(
            {
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_DIAGNOSTICS_ENABLED": "true",
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REQUIRED": "true",
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_INCLUDE_DISABLED": "false",
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_ON_STARTUP": "false",
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_MAX_NON_READY_ITEMS": "3",
            }
        )

        self.assertTrue(settings.enabled)
        self.assertTrue(settings.required)
        self.assertFalse(settings.include_disabled)
        self.assertFalse(settings.refresh_on_startup)
        self.assertEqual(3, settings.max_non_ready_items)


def _manifest() -> AgentSkillPublicationManifest:
    return AgentSkillPublicationManifest(
        schema_version="datasmart.agent.skill.publication-manifest.v1",
        manifest_type="DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST",
        protocol_hint="MCP_STYLE_SKILL_MANIFEST",
        descriptor_schema_version="datasmart.agent.skill.v1",
        publication_mode="SNAPSHOT",
        content_fingerprint="f" * 64,
        generated_at="2026-06-04T20:00:00Z",
        include_disabled=True,
        domain_filter="ALL",
        risk_level_filter="ALL",
        skill_count=3,
        skills=(
            _item("quality.rule.design", "READY", "MEDIUM", enabled=True),
            _item("disabled.skill", "DISABLED", "LOW", enabled=False),
            _item("dangerous.skill", "NEEDS_APPROVAL_POLICY", "HIGH", enabled=True),
        ),
        consumer_guidance=("比较 contentFingerprint",),
        compatibility_notes=("MCP-style 内部契约",),
        recommended_actions=("迁移数据库发布流",),
    )


def _item(skill_code: str, publication_state: str, risk_level: str, enabled: bool) -> AgentSkillPublicationItem:
    return AgentSkillPublicationItem(
        skill_code=skill_code,
        display_name=skill_code,
        domain="DATA_QUALITY",
        publication_state=publication_state,
        content_fingerprint=skill_code[:1] * 64,
        descriptor_endpoints=(f"/api/agent/skills/{skill_code}/descriptor",),
        enabled=enabled,
        risk_level=risk_level,
        approval_policy="NONE" if risk_level == "HIGH" else "DRAFT_REVIEW",
        audit_required=True,
        tenant_scoped=True,
        project_scoped=True,
        required_tools=("quality.rule.suggest",),
        required_permissions=("quality:rule:draft",),
        memory_dependencies=("SEMANTIC",),
        publication_warnings=("测试警告",) if publication_state != "READY" else (),
    )


if __name__ == "__main__":
    unittest.main()
