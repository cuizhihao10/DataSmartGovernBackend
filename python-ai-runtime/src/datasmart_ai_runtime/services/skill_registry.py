"""Agent Skill 注册与选择服务。

当前实现是内存注册表，目的是先稳定 Skill 描述和选择语义。后续商业化版本可以把 Skill 来源替换为
Java `agent-runtime`、插件市场、租户配置、Git 仓库或 MCP prompt/resource，而不影响编排器对
`AgentSkillPlan` 的消费方式。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor, AgentSkillPlan, AgentSkillSelection
from datasmart_ai_runtime.services.skill_admission_policy import AgentSkillAdmissionPolicy
from datasmart_ai_runtime.services.skills.skill_visibility_cache import (
    SkillVisibilityAdmissionCache,
    skill_registry_fingerprint,
)


class AgentSkillRegistry:
    """按意图分析结果选择 Agent Skill。

    这里不是做复杂推荐算法，而是提供一个清晰、可测试、可解释的商业化基线：
    - 治理域命中是主要依据；
    - 候选工具命中可以提升分数；
    - 用户目标关键词作为规则式降级；
    - 所有选择结果都带 reason，方便审计和用户理解。
    """

    def __init__(
        self,
        skills: tuple[AgentSkillDescriptor, ...],
        *,
        admission_policy: AgentSkillAdmissionPolicy | None = None,
        visibility_cache: SkillVisibilityAdmissionCache | None = None,
    ) -> None:
        self._skills = tuple(skill for skill in skills if skill.enabled)
        self._admission_policy = admission_policy or AgentSkillAdmissionPolicy()
        # Skill 注册表指纹用于保护缓存不会跨 Skill 发布版本复用。即使 gateway cache key 相同，
        # 只要本地/远端 Skill descriptor 发生变化，准入缓存也会自动失效。
        self._registry_fingerprint = skill_registry_fingerprint(self._skills)
        self._visibility_cache = visibility_cache or SkillVisibilityAdmissionCache()

    def select(
        self,
        objective: str,
        intent_analysis: IntentAnalysis | None,
        request: AgentRequest | None = None,
    ) -> AgentSkillPlan:
        """选择适合当前请求的 Skill。

        `objective` 用于关键词兜底；`intent_analysis` 用于结构化选择。后续接入 LLM Skill router 时，
        可以保持返回 `AgentSkillPlan` 不变，只替换内部评分实现。

        `request` 用于准入治理。没有请求时保持条件性推荐，兼容旧测试和离线分析；传入请求后，如果
        `variables` 中包含 `grantedPermissions`、`actorRole` 等控制面事实，就会按权限和风险策略过滤。
        """

        objective_text = objective.lower()
        domains = set(intent_analysis.governance_domains if intent_analysis else ())
        candidate_tools = set(intent_analysis.candidate_tools if intent_analysis else ())
        selections: list[AgentSkillSelection] = []
        rejected: list[AgentSkillSelection] = []

        for skill in self._skills:
            score = 0.0
            reasons: list[str] = []
            if skill.domain in domains:
                score += 0.65
                reasons.append(f"治理域命中 {skill.domain.value}")
            matched_tools = tuple(tool for tool in skill.required_tools if tool in candidate_tools)
            if matched_tools:
                score += 0.25
                reasons.append("候选工具命中 " + "、".join(matched_tools))
            matched_keywords = tuple(keyword for keyword in skill.trigger_keywords if keyword.lower() in objective_text)
            if matched_keywords:
                score += 0.15
                reasons.append("关键词命中 " + "、".join(matched_keywords[:3]))

            # 某些 Skill 不是“看到同一个治理域就可以默认启用”的泛化能力，而是会把用户意图推进到派单、
            # 审批、整改、执行准备等更高影响链路。例如质量异常治理任务草案如果只因为 DATA_QUALITY
            # 领域命中就被选中，会让普通“帮我设计质量规则”的请求同时出现一个被拒绝的派单 Skill，
            # 既增加前端噪声，也可能让用户误以为系统准备创建治理任务。`requiresExplicitTrigger`
            # 用来表达这种收敛规则：只有专用工具命中或目标文本出现明确触发词时，才进入后续准入判断。
            if skill.attributes.get("requiresExplicitTrigger") and not matched_tools and not matched_keywords:
                continue

            if score <= 0:
                continue
            admission = self._visibility_cache.get_or_evaluate(
                skill=skill,
                request=request,
                registry_fingerprint=self._registry_fingerprint,
                evaluator=lambda current_skill=skill: self._admission_policy.evaluate(current_skill, request),
            )
            selection = AgentSkillSelection(
                skill_code=skill.skill_code,
                display_name=skill.display_name,
                domain=skill.domain,
                score=min(score, 1.0),
                reason="；".join(reasons),
                required_tools=skill.required_tools,
                memory_dependencies=skill.memory_dependencies,
                approval_policy=skill.approval_policy,
                required_permissions=skill.required_permissions,
                risk_level=str(skill.risk_level or "LOW").upper(),
                admission_status=admission.status,
                admission_reasons=admission.reasons,
            )
            if admission.allowed:
                selections.append(selection)
            else:
                rejected.append(selection)

        ordered = tuple(sorted(selections, key=lambda item: item.score, reverse=True)[:3])
        rejected_ordered = tuple(sorted(rejected, key=lambda item: item.score, reverse=True)[:3])
        return AgentSkillPlan(
            selected_skills=ordered,
            rejected_skills=rejected_ordered,
            available_skill_count=len(self._skills),
            rationale=self._build_rationale(ordered, rejected_ordered, len(self._skills)),
        )

    @staticmethod
    def _build_rationale(
        selections: tuple[AgentSkillSelection, ...],
        rejected: tuple[AgentSkillSelection, ...],
        available_count: int,
    ) -> str:
        """构建 Skill 选择解释。"""

        if not selections and not rejected:
            return f"当前共有 {available_count} 个可用 Skill，但本次请求没有命中明确能力包。"
        if not selections:
            rejected_text = "、".join(item.display_name for item in rejected)
            return f"当前共有 {available_count} 个可用 Skill，本次命中但准入未通过：{rejected_text}。"
        skill_text = "、".join(item.display_name for item in selections)
        if not rejected:
            return f"当前共有 {available_count} 个可用 Skill，本次推荐启用：{skill_text}。"
        rejected_text = "、".join(item.display_name for item in rejected)
        return f"当前共有 {available_count} 个可用 Skill，本次推荐启用：{skill_text}；另有 Skill 命中但准入未通过：{rejected_text}。"
