"""Skill Publication Manifest 刷新控制策略。

这个模块只负责回答一个很小但很关键的生产问题：Python Runtime 当前缓存的
Java Skill Publication Manifest 什么时候应该刷新？

为什么要把它从 `skill_publication_diagnostics.py` 中拆出来：
- Manifest 诊断回答“当前看到了什么低敏状态”；
- 刷新控制回答“基于这些状态，现在是否应该再访问 Java 控制面”；
- 两者生命周期不同，后续如果把刷新动作交给 Redis 锁、后台 worker、Kafka outbox 或 gateway
  管理，也只需要替换本模块，而不需要重写诊断响应结构。

低敏边界：
- 本模块只读取 diagnostics 中已经裁剪过的状态、时间、指纹和错误摘要；
- 不读取 Skill descriptor 全量内容，不读取 prompt、SQL、工具参数、样本数据、模型输出或内部 endpoint；
- 输出只包含原因码、TTL、冷却时间、是否过期和建议动作，适合暴露给运维诊断接口。
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable


@dataclass(frozen=True)
class AgentSkillPublicationRefreshPolicy:
    """Skill Manifest 刷新策略配置。

    字段说明：
    - `enabled`：是否启用刷新决策。关闭后诊断仍可展示缓存状态，但不会建议自动刷新。
    - `stale_after_seconds`：最近一次刷新超过该时间后，缓存被视为过期，需要低频刷新。
    - `min_refresh_interval_seconds`：两次刷新之间的最小冷却时间，防止多个健康检查或管理页刷新把
      Java `agent-runtime` 控制面打成高频依赖。
    - `retry_after_error_seconds`：上次刷新失败后等待多久才建议重试，避免远端故障时形成热循环。

    这些配置都不是安全权限本身，只是运行时缓存治理参数。真正的 Skill 可见性仍要由 Java
    Manifest、permission-admin、工具 readiness、HITL 和 runtime protection 共同决定。
    """

    enabled: bool = True
    stale_after_seconds: int = 300
    min_refresh_interval_seconds: int = 30
    retry_after_error_seconds: int = 60


@dataclass(frozen=True)
class AgentSkillPublicationRefreshDecision:
    """一次刷新决策的低敏结果。

    字段说明：
    - `should_refresh`：调用方是否应该触发一次 Manifest 刷新；
    - `reason_code`：稳定机器原因码，便于测试、告警规则和运维面板聚合；
    - `cache_status`：面向产品/运维展示的缓存状态；
    - `age_seconds`：最近一次刷新距离当前的秒数。没有刷新记录时为 `None`；
    - `cooldown_seconds_remaining`：距离下一次允许普通刷新还剩多久；
    - `stale`：是否已经超过 `stale_after_seconds`；
    - `recommended_actions`：低敏建议动作，只描述治理动作，不泄露远端地址或 Manifest 明细。
    """

    policy: AgentSkillPublicationRefreshPolicy
    should_refresh: bool
    reason_code: str
    cache_status: str
    age_seconds: int | None
    cooldown_seconds_remaining: int
    stale: bool
    recommended_actions: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """转换为诊断接口可直接返回的低敏字典。

        响应中包含策略阈值，是为了让运维看到“为什么现在没有刷新”或“为什么现在应该刷新”。
        这里不返回真实 Java URL、HTTP Header、异常堆栈、Skill 列表正文或权限明细。
        """

        return {
            "schemaVersion": "datasmart.agent.skill-publication-refresh-control.v1",
            "controlType": "SKILL_PUBLICATION_MANIFEST_REFRESH_CONTROL",
            "payloadPolicy": "LOW_SENSITIVE_REFRESH_METADATA_ONLY",
            "enabled": self.policy.enabled,
            "shouldRefresh": self.should_refresh,
            "reasonCode": self.reason_code,
            "cacheStatus": self.cache_status,
            "ageSeconds": self.age_seconds,
            "stale": self.stale,
            "staleAfterSeconds": self.policy.stale_after_seconds,
            "minRefreshIntervalSeconds": self.policy.min_refresh_interval_seconds,
            "retryAfterErrorSeconds": self.policy.retry_after_error_seconds,
            "cooldownSecondsRemaining": self.cooldown_seconds_remaining,
            "recommendedActions": self.recommended_actions,
        }


class AgentSkillPublicationManifestRefreshController:
    """基于诊断快照判断 Manifest 是否需要刷新。

    这个控制器不直接访问网络，也不持有 Java client。它只消费 `diagnostics()` 已经返回的低敏快照。
    这样做有两个好处：
    1. 策略容易单测，不需要启动 FastAPI、Java 服务或 Keycloak；
    2. 刷新执行方可以是当前进程、后台 worker、gateway 管理接口或未来的 outbox 消费者。
    """

    def __init__(
        self,
        policy: AgentSkillPublicationRefreshPolicy | None = None,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._policy = policy or AgentSkillPublicationRefreshPolicy()
        self._now = now or (lambda: datetime.now(timezone.utc))

    def decide(self, diagnostics: dict[str, Any] | None, *, force: bool = False) -> AgentSkillPublicationRefreshDecision:
        """生成刷新决策。

        `force=True` 只绕过 TTL 与冷却时间，不绕过“远端 Manifest 诊断未启用/没有 client”这一硬边界。
        也就是说，管理接口可以请求强制刷新，但如果当前 Python Runtime 根本没有配置 Java
        `agent-runtime` base URL，控制器仍会返回 `shouldRefresh=false`，避免把配置问题伪装成成功刷新。
        """

        snapshot = diagnostics or {}
        policy = self._policy
        if not policy.enabled:
            return self._decision(
                should_refresh=False,
                reason_code="REFRESH_CONTROL_DISABLED",
                cache_status="disabled",
                age_seconds=None,
                stale=False,
                cooldown_seconds_remaining=0,
                recommended_actions=("刷新控制已关闭；如需运行时自动检查 Manifest，请开启刷新控制配置。",),
            )

        if not bool(snapshot.get("enabled")):
            return self._decision(
                should_refresh=False,
                reason_code="REMOTE_MANIFEST_DIAGNOSTICS_DISABLED",
                cache_status="local-default-only",
                age_seconds=None,
                stale=False,
                cooldown_seconds_remaining=0,
                recommended_actions=(
                    "当前未启用远端 Skill Manifest 诊断，Python Runtime 只使用本地默认 Skill。",
                    "如需接入 Java 发布事实源，请配置 DATASMART_AGENT_RUNTIME_BASE_URL。",
                ),
            )

        last_refresh_at = _parse_datetime(snapshot.get("lastRefreshAt"))
        if last_refresh_at is None:
            return self._decision(
                should_refresh=True,
                reason_code="NEVER_REFRESHED",
                cache_status="not-refreshed",
                age_seconds=None,
                stale=True,
                cooldown_seconds_remaining=0,
                recommended_actions=("尚未刷新过远端 Skill Manifest，应触发一次低频刷新以建立发布指纹基线。",),
            )

        age_seconds = max(0, int((self._now() - last_refresh_at).total_seconds()))
        cooldown_seconds_remaining = max(0, policy.min_refresh_interval_seconds - age_seconds)
        stale = age_seconds >= policy.stale_after_seconds
        has_error = bool(snapshot.get("lastError"))

        if force:
            return self._decision(
                should_refresh=True,
                reason_code="FORCE_REFRESH_REQUESTED",
                cache_status="force-refresh",
                age_seconds=age_seconds,
                stale=stale,
                cooldown_seconds_remaining=0,
                recommended_actions=("已收到强制刷新请求，可绕过普通 TTL 与冷却时间重新拉取 Manifest。",),
            )

        if has_error:
            # 远端错误后的重试窗口要同时考虑“普通刷新冷却”和“错误退避”两个阈值。
            # 只看 min_refresh_interval 会造成一个隐蔽问题：如果最小刷新间隔是 10 秒，
            # 错误重试窗口是 60 秒，那么第 11 秒就会被误判为可以进入后续 fresh/stale 分支。
            # 生产环境里这会让控制面故障期的诊断结果变得含糊，甚至造成管理页轮询形成重试噪声。
            error_retry_remaining = max(
                cooldown_seconds_remaining,
                policy.retry_after_error_seconds - age_seconds,
                0,
            )
            if error_retry_remaining > 0:
                return self._decision(
                    should_refresh=False,
                    reason_code="REMOTE_ERROR_RETRY_WINDOW_ACTIVE",
                    cache_status="remote-error-retry-window",
                    age_seconds=age_seconds,
                    stale=stale,
                    cooldown_seconds_remaining=error_retry_remaining,
                    recommended_actions=("上次远端 Manifest 读取失败，错误重试窗口尚未结束，暂不形成重试热循环。",),
                )

            return self._decision(
                should_refresh=True,
                reason_code="RETRY_AFTER_REMOTE_ERROR",
                cache_status="remote-error-retryable",
                age_seconds=age_seconds,
                stale=stale,
                cooldown_seconds_remaining=0,
                recommended_actions=("上次远端 Manifest 读取失败且已超过重试间隔，应触发一次受控重试。",),
            )

        if stale and cooldown_seconds_remaining > 0:
            return self._decision(
                should_refresh=False,
                reason_code="REFRESH_COOLDOWN_ACTIVE",
                cache_status="stale-but-cooldown",
                age_seconds=age_seconds,
                stale=stale,
                cooldown_seconds_remaining=cooldown_seconds_remaining,
                recommended_actions=("Manifest 已过期但刷新冷却期尚未结束，暂时复用旧快照并等待下一轮刷新窗口。",),
            )

        if stale:
            return self._decision(
                should_refresh=True,
                reason_code="MANIFEST_CACHE_STALE",
                cache_status="stale",
                age_seconds=age_seconds,
                stale=True,
                cooldown_seconds_remaining=0,
                recommended_actions=("Manifest 诊断快照已超过 TTL，应低频刷新以发现发布、下线或回滚变化。",),
            )

        return self._decision(
            should_refresh=False,
            reason_code="MANIFEST_CACHE_FRESH",
            cache_status="fresh",
            age_seconds=age_seconds,
            stale=False,
            cooldown_seconds_remaining=0,
            recommended_actions=("当前 Manifest 诊断快照仍在有效期内，无需访问 Java 控制面。",),
        )

    def _decision(
        self,
        *,
        should_refresh: bool,
        reason_code: str,
        cache_status: str,
        age_seconds: int | None,
        stale: bool,
        cooldown_seconds_remaining: int,
        recommended_actions: tuple[str, ...],
    ) -> AgentSkillPublicationRefreshDecision:
        """创建不可变刷新决策对象，集中保证阈值和原因码一起返回。"""

        return AgentSkillPublicationRefreshDecision(
            policy=self._policy,
            should_refresh=should_refresh,
            reason_code=reason_code,
            cache_status=cache_status,
            age_seconds=age_seconds,
            cooldown_seconds_remaining=cooldown_seconds_remaining,
            stale=stale,
            recommended_actions=recommended_actions,
        )


def _parse_datetime(value: object | None) -> datetime | None:
    """解析诊断快照中的 ISO 时间。

    诊断接口可能来自 Python 自身，也可能未来由 Java/网关转发。这里兼容 `Z` 与 offset 格式；
    解析失败时返回 `None`，让上层按“未刷新过”处理，而不是抛异常打断健康检查。
    """

    if value is None or not str(value).strip():
        return None
    try:
        parsed = datetime.fromisoformat(str(value).strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
