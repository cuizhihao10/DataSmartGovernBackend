import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis, IntentRiskTag
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor
from datasmart_ai_runtime.services.skill_admission_policy import AgentSkillAdmissionDecision
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.skills.skill_visibility_cache import SkillVisibilityAdmissionCache


class SkillVisibilityAdmissionCacheTest(unittest.TestCase):
    """Skill 可见性准入缓存测试。

    这些用例把缓存边界固定在“准入判断”层：语义匹配仍然每次执行，缓存只避免同一可信控制面上下文下
    对同一个 Skill 反复执行权限、角色、风险和套餐策略判断。
    """

    def test_same_trusted_context_reuses_admission_decision(self) -> None:
        policy = CountingAdmissionPolicy()
        registry = AgentSkillRegistry(
            (quality_skill(),),
            admission_policy=policy,
            visibility_cache=SkillVisibilityAdmissionCache(now=lambda: 100.0),
        )
        request = request_with_cache("gateway-key-001", project_id="project-a", session_id="session-a")
        intent = quality_intent()

        registry.select(request.objective, intent, request=request)
        registry.select(request.objective, intent, request=request)

        self.assertEqual(1, policy.call_count)

    def test_different_project_or_session_misses_cache(self) -> None:
        policy = CountingAdmissionPolicy()
        registry = AgentSkillRegistry(
            (quality_skill(),),
            admission_policy=policy,
            visibility_cache=SkillVisibilityAdmissionCache(now=lambda: 100.0),
        )

        registry.select("生成质量规则", quality_intent(), request=request_with_cache("gateway-key-001", "project-a", "s1"))
        registry.select("生成质量规则", quality_intent(), request=request_with_cache("gateway-key-001", "project-b", "s1"))
        registry.select("生成质量规则", quality_intent(), request=request_with_cache("gateway-key-001", "project-a", "s2"))

        self.assertEqual(3, policy.call_count)

    def test_without_trusted_gateway_key_does_not_cache(self) -> None:
        policy = CountingAdmissionPolicy()
        registry = AgentSkillRegistry(
            (quality_skill(),),
            admission_policy=policy,
            visibility_cache=SkillVisibilityAdmissionCache(now=lambda: 100.0),
        )
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="生成质量规则",
            variables={"sessionId": "session-a"},
        )

        registry.select(request.objective, quality_intent(), request=request)
        registry.select(request.objective, quality_intent(), request=request)

        self.assertEqual(2, policy.call_count)

    def test_skill_registry_fingerprint_change_misses_cache(self) -> None:
        shared_cache = SkillVisibilityAdmissionCache(now=lambda: 100.0)
        first_policy = CountingAdmissionPolicy()
        second_policy = CountingAdmissionPolicy()
        request = request_with_cache("gateway-key-001", project_id="project-a", session_id="session-a")

        AgentSkillRegistry(
            (quality_skill(content_fingerprint="manifest-a"),),
            admission_policy=first_policy,
            visibility_cache=shared_cache,
        ).select(request.objective, quality_intent(), request=request)
        AgentSkillRegistry(
            (quality_skill(content_fingerprint="manifest-b"),),
            admission_policy=second_policy,
            visibility_cache=shared_cache,
        ).select(request.objective, quality_intent(), request=request)

        self.assertEqual(1, first_policy.call_count)
        self.assertEqual(1, second_policy.call_count)


class CountingAdmissionPolicy:
    """用于测试的准入策略，记录实际评估次数。"""

    def __init__(self) -> None:
        self.call_count = 0

    def evaluate(self, skill: AgentSkillDescriptor, request: AgentRequest | None = None) -> AgentSkillAdmissionDecision:
        self.call_count += 1
        return AgentSkillAdmissionDecision(True, "ALLOWED", (f"{skill.skill_code} 测试准入通过。",))


def quality_skill(*, content_fingerprint: str = "manifest-a") -> AgentSkillDescriptor:
    """构造一个可命中质量治理意图的 Skill。"""

    return AgentSkillDescriptor(
        skill_code="quality.rule.design",
        display_name="质量规则设计 Skill",
        description="用于生成质量规则草案。",
        domain=GovernanceDomain.DATA_QUALITY,
        required_tools=("quality.rule.suggest",),
        required_permissions=("quality:rule:draft",),
        risk_level="medium",
        trigger_keywords=("质量", "规则"),
        attributes={"contentFingerprint": content_fingerprint},
    )


def quality_intent() -> IntentAnalysis:
    """构造质量规则意图。"""

    return IntentAnalysis(
        summary="质量规则设计",
        governance_domains=(GovernanceDomain.DATA_QUALITY,),
        candidate_tools=("quality.rule.suggest",),
        risk_tags=(IntentRiskTag.DRAFT_GENERATION,),
    )


def request_with_cache(gateway_key: str, project_id: str, session_id: str) -> AgentRequest:
    """构造带可信 Skill 可见性缓存上下文的请求。"""

    return AgentRequest(
        tenant_id="tenant-a",
        project_id=project_id,
        actor_id="analyst-a",
        objective="生成质量规则",
        variables={
            "sessionId": session_id,
            "trustedControlPlane": {
                "skillVisibilityCache": {
                    "enabled": True,
                    "gatewayCacheKey": gateway_key,
                    "version": "v1",
                    "scope": "session-ready-skill-admission",
                    "ttlSeconds": 120,
                    "tenantPlanCode": "STANDARD",
                    "workspaceRiskLevel": "NORMAL",
                    "toolBudgetPolicyVersion": "policy-v1",
                }
            },
        },
    )


if __name__ == "__main__":
    unittest.main()
