import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

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
    AgentSkillPublicationManifestRefreshController,
    AgentSkillPublicationRefreshPolicy,
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
        self.assertEqual("MANIFEST_CACHE_FRESH", diagnostics["refreshControl"]["reasonCode"])
        self.assertFalse(diagnostics["refreshControl"]["shouldRefresh"])

    def test_diagnostics_marks_remote_manifest_as_never_refreshed_before_first_refresh(self) -> None:
        """远端 Manifest 已启用但尚未刷新时，应提示控制面先建立指纹基线。"""

        service = AgentSkillPublicationManifestDiagnosticsService(
            FakePublicationManifestClient(manifest=_manifest()),
            AgentSkillPublicationDiagnosticsSettings(enabled=True),
        )

        diagnostics = service.diagnostics()

        self.assertEqual("REMOTE_NOT_REFRESHED", diagnostics["status"])
        self.assertEqual("NEVER_REFRESHED", diagnostics["refreshControl"]["reasonCode"])
        self.assertTrue(diagnostics["refreshControl"]["shouldRefresh"])

    def test_refresh_if_needed_force_refreshes_manifest_cache(self) -> None:
        """强制刷新应显式触发远端读取，并返回前后指纹对比结果。"""

        client = FakePublicationManifestClient(manifest=_manifest())
        service = AgentSkillPublicationManifestDiagnosticsService(
            client,
            AgentSkillPublicationDiagnosticsSettings(enabled=True, include_disabled=True),
        )

        result = service.refresh_if_needed(trace_id="trace-force", force=True)

        self.assertTrue(result["refreshTriggered"])
        self.assertTrue(result["force"])
        self.assertEqual([True], client.calls)
        self.assertIsNone(result["beforeManifestFingerprint"])
        self.assertEqual("f" * 64, result["afterManifestFingerprint"])
        self.assertEqual("REMOTE_READY", result["diagnostics"]["status"])

    def test_refresh_if_needed_skips_fresh_manifest_without_remote_call(self) -> None:
        """Manifest 仍在有效期内时，普通刷新应复用缓存而不访问远端。"""

        client = FakePublicationManifestClient(manifest=_manifest())
        service = AgentSkillPublicationManifestDiagnosticsService(
            client,
            AgentSkillPublicationDiagnosticsSettings(
                enabled=True,
                include_disabled=True,
                refresh_min_interval_seconds=300,
            ),
        )
        service.refresh()
        client.calls.clear()

        result = service.refresh_if_needed(force=False)

        self.assertFalse(result["refreshTriggered"])
        self.assertEqual([], client.calls)
        self.assertEqual("MANIFEST_CACHE_FRESH", result["decision"]["reasonCode"])

    def test_refresh_controller_retries_after_remote_error_window(self) -> None:
        """上次远端失败超过重试窗口后，应建议重新刷新而不是永久停在 fallback。"""

        now = datetime(2026, 6, 30, 10, 0, 0, tzinfo=timezone.utc)
        controller = AgentSkillPublicationManifestRefreshController(
            AgentSkillPublicationRefreshPolicy(
                stale_after_seconds=300,
                min_refresh_interval_seconds=10,
                retry_after_error_seconds=60,
            ),
            now=lambda: now,
        )
        diagnostics = {
            "enabled": True,
            "lastRefreshAt": (now - timedelta(seconds=90)).isoformat(),
            "lastError": "REMOTE_UNAVAILABLE",
        }

        decision = controller.decide(diagnostics)

        self.assertTrue(decision.should_refresh)
        self.assertEqual("RETRY_AFTER_REMOTE_ERROR", decision.reason_code)

    def test_refresh_controller_waits_during_remote_error_retry_window(self) -> None:
        """远端失败后尚未到错误重试窗口时，应明确停留在退避状态。"""

        now = datetime(2026, 6, 30, 10, 0, 0, tzinfo=timezone.utc)
        controller = AgentSkillPublicationManifestRefreshController(
            AgentSkillPublicationRefreshPolicy(
                stale_after_seconds=300,
                min_refresh_interval_seconds=10,
                retry_after_error_seconds=60,
            ),
            now=lambda: now,
        )
        diagnostics = {
            "enabled": True,
            "lastRefreshAt": (now - timedelta(seconds=30)).isoformat(),
            "lastError": "REMOTE_UNAVAILABLE",
        }

        decision = controller.decide(diagnostics)

        self.assertFalse(decision.should_refresh)
        self.assertEqual("REMOTE_ERROR_RETRY_WINDOW_ACTIVE", decision.reason_code)
        self.assertEqual(30, decision.cooldown_seconds_remaining)

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
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_CONTROL_ENABLED": "true",
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_STALE_AFTER_SECONDS": "120",
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_MIN_INTERVAL_SECONDS": "15",
                "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_RETRY_AFTER_ERROR_SECONDS": "45",
            }
        )

        self.assertTrue(settings.enabled)
        self.assertTrue(settings.required)
        self.assertFalse(settings.include_disabled)
        self.assertFalse(settings.refresh_on_startup)
        self.assertEqual(3, settings.max_non_ready_items)
        self.assertTrue(settings.refresh_control_enabled)
        self.assertEqual(120, settings.refresh_stale_after_seconds)
        self.assertEqual(15, settings.refresh_min_interval_seconds)
        self.assertEqual(45, settings.refresh_retry_after_error_seconds)


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
