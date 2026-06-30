"""Skill Publication Manifest 启动诊断服务。

Java `agent-runtime` 已经可以输出 Skill Publication Manifest。Python Runtime 下一步不能只“会解析”
这份 Manifest，还需要在启动和运维诊断中回答几个生产问题：

- 当前是否连上了 Java Skill 发布事实源；
- 远端 Manifest 的 `contentFingerprint` 是什么，是否可用于缓存和灰度比对；
- READY Skill 有多少，非 READY Skill 主要卡在哪些治理状态；
- 远端不可用时，Python 是否回退到了本地默认 Skill，以及这个回退是否是有意的。

本模块只做低敏诊断，不把完整 descriptor、prompt、工具参数、样本数据或权限明细暴露给诊断接口。
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable

from datasmart_ai_runtime.config import default_skill_registry
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor
from datasmart_ai_runtime.services.skill_registry_client import (
    AgentSkillPublicationItem,
    AgentSkillPublicationManifest,
    JavaAgentSkillRegistryClient,
    SkillRegistryClientError,
)
from datasmart_ai_runtime.services.skills.skill_publication_refresh import (
    AgentSkillPublicationManifestRefreshController,
    AgentSkillPublicationRefreshPolicy,
)


@dataclass(frozen=True)
class AgentSkillPublicationDiagnosticsSettings:
    """Skill Manifest 诊断配置。

    字段说明：
    - `enabled`：是否启用远端 Manifest 诊断。没有 Java base URL 时通常会关闭，只报告本地默认 Skill。
    - `required`：远端 Manifest 是否为启动必需。生产强治理环境可设为 true；本地学习环境建议保持 false。
    - `include_disabled`：诊断读取 Manifest 时是否包含禁用 Skill。诊断建议 true，因为我们需要看见非 READY 原因。
    - `refresh_on_startup`：FastAPI startup 是否主动刷新一次。开启后能更早暴露远端不可用问题。
    - `max_non_ready_items`：诊断中最多展示多少个非 READY Skill 摘要，避免响应过大或泄露过多目录细节。
    """

    enabled: bool = False
    required: bool = False
    include_disabled: bool = True
    refresh_on_startup: bool = True
    max_non_ready_items: int = 10
    refresh_control_enabled: bool = True
    refresh_stale_after_seconds: int = 300
    refresh_min_interval_seconds: int = 30
    refresh_retry_after_error_seconds: int = 60


class AgentSkillPublicationManifestDiagnosticsService:
    """Skill Publication Manifest 低敏诊断服务。

    该服务刻意不直接影响 `AgentOrchestrator` 的 Skill 选择主路径。当前主路径仍通过
    `load_skill_registry()` 读取 descriptor 或回退本地默认 Skill；诊断服务负责记录远端 Manifest 是否健康、
    指纹是什么、READY/非 READY 分布如何。

    这样做的好处是渐进安全：
    1. 远端 Manifest 新能力上线时，不会突然改变模型规划结果；
    2. 运维已经可以通过诊断观察远端发布目录健康度；
    3. 后续要切换为“Manifest 指纹驱动 Skill 缓存”时，有现成状态和测试作为基础。
    """

    def __init__(
        self,
        client: Any | None,
        settings: AgentSkillPublicationDiagnosticsSettings | None = None,
        local_skill_provider: Callable[[], tuple[AgentSkillDescriptor, ...]] = default_skill_registry,
        refresh_controller: AgentSkillPublicationManifestRefreshController | None = None,
    ) -> None:
        self._client = client
        self._settings = settings or AgentSkillPublicationDiagnosticsSettings()
        self._local_skill_provider = local_skill_provider
        self._refresh_controller = refresh_controller or AgentSkillPublicationManifestRefreshController(
            AgentSkillPublicationRefreshPolicy(
                enabled=self._settings.refresh_control_enabled,
                stale_after_seconds=self._settings.refresh_stale_after_seconds,
                min_refresh_interval_seconds=self._settings.refresh_min_interval_seconds,
                retry_after_error_seconds=self._settings.refresh_retry_after_error_seconds,
            )
        )
        self._last_manifest: AgentSkillPublicationManifest | None = None
        self._last_error: str | None = None
        self._last_refresh_at: str | None = None
        self._refresh_count = 0

    def refresh(self, trace_id: str | None = None) -> dict[str, Any]:
        """刷新 Manifest 并返回最新诊断。

        如果远端 Manifest 被配置为必需且刷新失败，本方法会抛出 `SkillRegistryClientError`。
        这允许生产环境在“能力发布事实源不可用”时 fail-closed；本地开发环境则保持 fail-open，
        通过诊断字段 `fallback=true` 明确说明当前使用了本地默认 Skill。
        """

        self._refresh_count += 1
        self._last_refresh_at = _utc_now_iso()

        if not self._settings.enabled or self._client is None:
            self._last_manifest = None
            self._last_error = None
            return self.diagnostics()

        try:
            self._last_manifest = self._client.get_publication_manifest(
                include_disabled=self._settings.include_disabled,
                trace_id=trace_id,
            )
            self._last_error = None
        except SkillRegistryClientError as exc:
            self._last_manifest = None
            self._last_error = _sanitize_error(exc)
            if self._settings.required:
                raise
        return self.diagnostics()

    def refresh_if_needed(self, trace_id: str | None = None, *, force: bool = False) -> dict[str, Any]:
        """按刷新控制策略执行一次“必要时刷新”。

        该方法面向运维面板、gateway 管理入口和本地 smoke check，而不是面向每一次用户规划请求。
        它会先基于当前低敏诊断快照生成刷新决策，再决定是否调用 `refresh()` 访问 Java 控制面。
        这样既能及时发现 Skill 发布、下线、回滚和指纹变化，又不会让 Manifest 成为 `/agent/plans`
        高频同步路径上的额外瓶颈。
        """

        before = self.diagnostics()
        decision = self._refresh_controller.decide(before, force=force)
        before_fingerprint = before.get("manifestFingerprint")
        if not decision.should_refresh:
            return {
                "schemaVersion": "datasmart.agent.skill-publication-refresh-result.v1",
                "refreshTriggered": False,
                "force": force,
                "decision": decision.to_summary(),
                "beforeManifestFingerprint": before_fingerprint,
                "afterManifestFingerprint": before_fingerprint,
                "fingerprintChanged": False,
                "diagnostics": before,
            }

        after = self.refresh(trace_id=trace_id)
        after_fingerprint = after.get("manifestFingerprint")
        return {
            "schemaVersion": "datasmart.agent.skill-publication-refresh-result.v1",
            "refreshTriggered": True,
            "force": force,
            "decision": decision.to_summary(),
            "beforeManifestFingerprint": before_fingerprint,
            "afterManifestFingerprint": after_fingerprint,
            "fingerprintChanged": bool(before_fingerprint and after_fingerprint and before_fingerprint != after_fingerprint),
            "diagnostics": after,
        }

    def should_refresh_on_startup(self) -> bool:
        """返回 FastAPI startup 是否应该主动刷新 Manifest。

        API 装配层只需要知道“是否刷新”，不应该直接读取 `_settings` 私有字段。
        保留这个小方法可以让后续策略变复杂时，例如只在特定 profile、租户或灰度环境刷新，
        不需要修改 `api.py`。
        """

        return self._settings.refresh_on_startup

    def diagnostics(self) -> dict[str, Any]:
        """返回低敏诊断快照。

        响应只包含数量、状态、指纹、低风险 skillCode 摘要和推荐动作。
        不返回完整 Skill descriptor、不返回权限明细解释、不返回工具参数或任何模型上下文。
        """

        local_skills = self._local_skill_provider()
        base = {
            "component": "agent-skill-publication-manifest",
            "enabled": self._settings.enabled,
            "required": self._settings.required,
            "includeDisabled": self._settings.include_disabled,
            "refreshOnStartup": self._settings.refresh_on_startup,
            "refreshCount": self._refresh_count,
            "lastRefreshAt": self._last_refresh_at,
            "localFallbackSkillCount": len(local_skills),
        }

        if not self._settings.enabled or self._client is None:
            return self._with_refresh_control({
                **base,
                "status": "LOCAL_DEFAULT_ONLY",
                "source": "local-default",
                "fallback": True,
                "remoteManifestAvailable": False,
                "manifestFingerprint": None,
                "readySkillCount": len(local_skills),
                "nonReadySkillCount": 0,
                "publicationStateCounts": {"LOCAL_DEFAULT": len(local_skills)},
                "riskLevelCounts": _risk_counts_from_local_skills(local_skills),
                "nonReadySkills": (),
                "lastError": None,
                "recommendedActions": (
                    "当前未启用远端 Skill Manifest 诊断，Python Runtime 使用本地默认 Skill。"
                    "如果要接入 Java 控制面的发布事实源，请配置 DATASMART_AGENT_RUNTIME_BASE_URL。",
                ),
            })

        if self._last_manifest is None:
            return self._with_refresh_control({
                **base,
                "status": "REMOTE_UNAVAILABLE_FALLBACK" if self._last_error else "REMOTE_NOT_REFRESHED",
                "source": "local-default",
                "fallback": True,
                "remoteManifestAvailable": False,
                "manifestFingerprint": None,
                "readySkillCount": len(local_skills),
                "nonReadySkillCount": 0,
                "publicationStateCounts": {"LOCAL_DEFAULT": len(local_skills)},
                "riskLevelCounts": _risk_counts_from_local_skills(local_skills),
                "nonReadySkills": (),
                "lastError": self._last_error,
                "recommendedActions": _fallback_actions(self._settings.required, bool(self._last_error)),
            })

        manifest = self._last_manifest
        publication_state_counts = Counter(item.publication_state for item in manifest.skills)
        ready_skills = tuple(item for item in manifest.skills if item.publication_state == "READY")
        non_ready_skills = tuple(item for item in manifest.skills if item.publication_state != "READY")

        return self._with_refresh_control({
            **base,
            "status": "REMOTE_READY",
            "source": "java-agent-runtime",
            "fallback": False,
            "remoteManifestAvailable": True,
            "schemaVersion": manifest.schema_version,
            "manifestType": manifest.manifest_type,
            "protocolHint": manifest.protocol_hint,
            "publicationMode": manifest.publication_mode,
            "manifestFingerprint": manifest.content_fingerprint,
            "generatedAt": manifest.generated_at,
            "domainFilter": manifest.domain_filter,
            "riskLevelFilter": manifest.risk_level_filter,
            "manifestSkillCount": manifest.skill_count,
            "readySkillCount": len(ready_skills),
            "nonReadySkillCount": len(non_ready_skills),
            "publicationStateCounts": dict(sorted(publication_state_counts.items())),
            "riskLevelCounts": _risk_counts_from_manifest(manifest.skills),
            "warningSkillCount": sum(1 for item in manifest.skills if item.publication_warnings),
            "nonReadySkills": tuple(
                _non_ready_item_summary(item) for item in non_ready_skills[: self._settings.max_non_ready_items]
            ),
            "lastError": None,
            "recommendedActions": _remote_ready_actions(manifest, len(non_ready_skills)),
        })

    def _with_refresh_control(self, snapshot: dict[str, Any]) -> dict[str, Any]:
        """给 Manifest 诊断快照追加刷新控制结果。

        注意：这里不会触发真实刷新，只把“当前是否应该刷新”作为低敏控制面元数据返回。
        真实刷新只能通过 `refresh()` 或 `refresh_if_needed()` 发生，避免诊断接口、`/agent/plans`
        或前端管理卡片在读取状态时隐式访问 Java 控制面。
        """

        return {
            **snapshot,
            "refreshControl": self._refresh_controller.decide(snapshot).to_summary(),
        }


def skill_publication_diagnostics_settings_from_env(source: dict[str, str] | None = None) -> AgentSkillPublicationDiagnosticsSettings:
    """从环境变量读取 Skill Manifest 诊断配置。

    这里没有直接读取 `os.environ`，而是允许传入 dict，是为了让单元测试可以不污染全局环境。
    """

    import os

    values = source or os.environ
    return AgentSkillPublicationDiagnosticsSettings(
        enabled=_truthy(values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_DIAGNOSTICS_ENABLED", "true")),
        required=_truthy(values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REQUIRED", "false")),
        include_disabled=_truthy(values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_INCLUDE_DISABLED", "true")),
        refresh_on_startup=_truthy(values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_ON_STARTUP", "true")),
        max_non_ready_items=_positive_int(
            values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_MAX_NON_READY_ITEMS"),
            10,
        ),
        refresh_control_enabled=_truthy(
            values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_CONTROL_ENABLED", "true")
        ),
        refresh_stale_after_seconds=_positive_int(
            values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_STALE_AFTER_SECONDS"),
            300,
        ),
        refresh_min_interval_seconds=_positive_int(
            values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_MIN_INTERVAL_SECONDS"),
            30,
        ),
        refresh_retry_after_error_seconds=_positive_int(
            values.get("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_RETRY_AFTER_ERROR_SECONDS"),
            60,
        ),
    )


def build_skill_publication_manifest_diagnostics_service(
    agent_runtime_base_url: str | None,
    settings: AgentSkillPublicationDiagnosticsSettings | None = None,
    client: Any | None = None,
) -> AgentSkillPublicationManifestDiagnosticsService:
    """构建 Skill Manifest 诊断服务。

    如果没有 Java `agent-runtime` 地址，即使配置默认 enabled=true，也会返回只报告本地默认 Skill 的服务。
    这样本地学习环境不需要启动 Java 服务；生产环境只要配置 base URL，就会自动具备远端 Manifest 诊断。
    """

    resolved_settings = settings or skill_publication_diagnostics_settings_from_env()
    resolved_client = client
    if resolved_client is None and agent_runtime_base_url:
        resolved_client = JavaAgentSkillRegistryClient(base_url=agent_runtime_base_url)
    effective_settings = AgentSkillPublicationDiagnosticsSettings(
        enabled=resolved_settings.enabled and resolved_client is not None,
        required=resolved_settings.required,
        include_disabled=resolved_settings.include_disabled,
        refresh_on_startup=resolved_settings.refresh_on_startup,
        max_non_ready_items=resolved_settings.max_non_ready_items,
        refresh_control_enabled=resolved_settings.refresh_control_enabled,
        refresh_stale_after_seconds=resolved_settings.refresh_stale_after_seconds,
        refresh_min_interval_seconds=resolved_settings.refresh_min_interval_seconds,
        refresh_retry_after_error_seconds=resolved_settings.refresh_retry_after_error_seconds,
    )
    return AgentSkillPublicationManifestDiagnosticsService(resolved_client, effective_settings)


def _risk_counts_from_manifest(skills: tuple[AgentSkillPublicationItem, ...]) -> dict[str, int]:
    return dict(sorted(Counter(item.risk_level for item in skills).items()))


def _risk_counts_from_local_skills(skills: tuple[AgentSkillDescriptor, ...]) -> dict[str, int]:
    return dict(sorted(Counter(skill.risk_level for skill in skills).items()))


def _non_ready_item_summary(item: AgentSkillPublicationItem) -> dict[str, Any]:
    """返回非 READY Skill 的低敏摘要。"""

    return {
        "skillCode": item.skill_code,
        "publicationState": item.publication_state,
        "riskLevel": item.risk_level,
        "enabled": item.enabled,
        "warningCount": len(item.publication_warnings),
    }


def _remote_ready_actions(manifest: AgentSkillPublicationManifest, non_ready_count: int) -> tuple[str, ...]:
    actions = [
        "记录 manifestFingerprint 到启动日志、运行时事件或运维面板，便于定位当前 Python Runtime 使用的 Skill 发布快照。",
        "默认只把 publicationState=READY 的 Skill 放进模型规划候选集；非 READY Skill 应进入后台诊断或市场治理视图。",
    ]
    if non_ready_count:
        actions.append("优先处理非 READY Skill 的审批、审计、隔离或禁用原因，避免能力市场出现灰色发布状态。")
    if not manifest.content_fingerprint:
        actions.append("远端 Manifest 缺少 contentFingerprint，应检查 Java agent-runtime 发布服务是否按新契约输出。")
    return tuple(actions)


def _fallback_actions(required: bool, has_error: bool) -> tuple[str, ...]:
    if not has_error:
        return (
            "远端 Skill Manifest 尚未刷新，建议在 FastAPI startup 或首次健康检查时调用 refresh()。",
        )
    if required:
        return (
            "远端 Skill Manifest 被配置为必需但读取失败，生产环境应阻止启动或触发高优先级告警。",
            "检查 DATASMART_AGENT_RUNTIME_BASE_URL、网络连通性、Java agent-runtime 端口和 /skills/publication/manifest 接口。",
        )
    return (
        "远端 Skill Manifest 读取失败，当前回退本地默认 Skill；本地开发可接受，生产环境应接入告警。",
        "检查 Java agent-runtime 是否启动、gateway 是否放通内部路由，以及 Manifest 接口是否返回平台统一响应。",
    )


def _sanitize_error(error: Exception) -> str:
    """对错误信息做长度限制，避免诊断响应过大。"""

    message = str(error)
    return message if len(message) <= 300 else f"{message[:300]}..."


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _truthy(value: str | None) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _positive_int(value: str | None, default: int) -> int:
    if value is None or not str(value).strip():
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return parsed if parsed > 0 else default
