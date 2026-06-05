"""Skill 可见性准入缓存。

该模块只缓存“某个 Skill 在可信控制面上下文下的准入判断”，不缓存用户目标、完整 AgentPlan、
模型输出、工具参数或工具结果。这样做是为了贴近 Codex/Claude Code 一类 Agent 产品的性能思路：
把稳定的控制面判断短期复用，把与用户目标强相关的语义选择和推理仍然逐次计算。
"""

from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass
from typing import Callable

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor
from datasmart_ai_runtime.services.skill_admission_policy import AgentSkillAdmissionDecision
from datasmart_ai_runtime.services.trusted_control_plane_context import AgentTrustedControlPlaneContextReader


@dataclass(frozen=True)
class SkillVisibilityAdmissionCacheKey:
    """单个 Skill 准入缓存 key。

    key 中不包含 objective/prompt，因为准入判断只回答“当前控制面上下文是否允许启用 Skill”，
    不回答“这个 Skill 是否语义上适合当前目标”。语义评分仍在 ``AgentSkillRegistry.select`` 中
    每次重新计算，避免用户换了目标却复用错误的 Skill 排名。
    """

    gateway_cache_key: str
    project_id: str
    session_id: str
    skill_code: str
    registry_fingerprint: str
    version: str
    scope: str
    tenant_plan_code: str
    workspace_risk_level: str
    tool_budget_policy_version: str


@dataclass(frozen=True)
class _CacheEntry:
    """缓存条目，保存准入决策与过期时间。"""

    decision: AgentSkillAdmissionDecision
    expires_at: float


class SkillVisibilityAdmissionCache:
    """会话级 READY Skill 准入缓存。

    当前实现是进程内内存缓存，适合 Python Runtime 单实例、本地学习环境和最小闭环验证。
    商业化生产如果有多实例或更高吞吐，应把该接口替换为 Redis/企业缓存 SDK，并保留同样的 key 组成：
    gateway key + project/session + Skill 发布指纹 + skillCode + 策略版本。
    """

    def __init__(
        self,
        *,
        max_ttl_seconds: int = 300,
        now: Callable[[], float] = time.time,
    ) -> None:
        self._max_ttl_seconds = max(1, max_ttl_seconds)
        self._now = now
        self._entries: dict[SkillVisibilityAdmissionCacheKey, _CacheEntry] = {}

    def get_or_evaluate(
        self,
        *,
        skill: AgentSkillDescriptor,
        request: AgentRequest | None,
        registry_fingerprint: str,
        evaluator: Callable[[], AgentSkillAdmissionDecision],
    ) -> AgentSkillAdmissionDecision:
        """读取缓存或执行准入评估。

        没有可信 gateway cache key 时直接执行 ``evaluator``，这是安全默认值：本地测试和绕过 gateway
        的请求不会因为普通 variables 自报字段而获得缓存复用能力。
        """

        key_and_ttl = self._build_key(skill, request, registry_fingerprint)
        if key_and_ttl is None:
            return evaluator()
        key, ttl_seconds = key_and_ttl
        now = self._now()
        entry = self._entries.get(key)
        if entry is not None and entry.expires_at > now:
            return entry.decision
        decision = evaluator()
        self._entries[key] = _CacheEntry(decision=decision, expires_at=now + ttl_seconds)
        return decision

    def _build_key(
        self,
        skill: AgentSkillDescriptor,
        request: AgentRequest | None,
        registry_fingerprint: str,
    ) -> tuple[SkillVisibilityAdmissionCacheKey, int] | None:
        """根据可信上下文构造缓存 key。

        ``sessionId`` 来自请求变量，是因为 gateway 当前不读取 body。若后续 Java gateway 管理会话并把
        会话 ID 放入 Header，可以把该字段升级为签名 Header，进一步收紧会话隔离。
        """

        if request is None:
            return None
        cache_context = AgentTrustedControlPlaneContextReader.skill_visibility_cache(request)
        if not cache_context.enabled or not cache_context.gateway_cache_key:
            return None
        ttl_seconds = min(cache_context.ttl_seconds or self._max_ttl_seconds, self._max_ttl_seconds)
        session_id = _string_var(request.variables, "sessionId", "session_id", "conversationId", "conversation_id")
        key = SkillVisibilityAdmissionCacheKey(
            gateway_cache_key=cache_context.gateway_cache_key,
            project_id=str(request.project_id or ""),
            session_id=session_id or "",
            skill_code=skill.skill_code,
            registry_fingerprint=registry_fingerprint,
            version=cache_context.version,
            scope=cache_context.scope,
            tenant_plan_code=cache_context.tenant_plan_code,
            workspace_risk_level=cache_context.workspace_risk_level,
            tool_budget_policy_version=cache_context.tool_budget_policy_version or "",
        )
        return key, ttl_seconds


def skill_registry_fingerprint(skills: tuple[AgentSkillDescriptor, ...]) -> str:
    """计算本地 Skill 注册表指纹。

    生产中远端 Skill Manifest 的 ``contentFingerprint`` 更权威；当前本地注册表可能还没有统一 Manifest，
    因此用 Skill descriptor 的低敏结构生成稳定摘要。只要权限、风险、启用状态或 Skill 编码变化，
    缓存 key 就会变化，避免继续复用旧准入结论。
    """

    payload = [
        {
            "skillCode": skill.skill_code,
            "enabled": skill.enabled,
            "riskLevel": str(skill.risk_level or "").upper(),
            "requiredPermissions": tuple(sorted(skill.required_permissions)),
            "approvalPolicy": skill.approval_policy,
            "contentFingerprint": str(skill.attributes.get("contentFingerprint") or ""),
        }
        for skill in skills
    ]
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()[:24]


def _string_var(mapping: dict[str, object] | None, *keys: str) -> str | None:
    """从请求变量中读取可选字符串。"""

    if not mapping:
        return None
    for key in keys:
        value = mapping.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None
