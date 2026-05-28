"""Agent Skill 注册与选择服务。

当前实现是内存注册表，目的是先稳定 Skill 描述和选择语义。后续商业化版本可以把 Skill 来源替换为
Java `agent-runtime`、插件市场、租户配置、Git 仓库或 MCP prompt/resource，而不影响编排器对
`AgentSkillPlan` 的消费方式。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor, AgentSkillPlan, AgentSkillSelection


class AgentSkillRegistry:
    """按意图分析结果选择 Agent Skill。

    这里不是做复杂推荐算法，而是提供一个清晰、可测试、可解释的商业化基线：
    - 治理域命中是主要依据；
    - 候选工具命中可以提升分数；
    - 用户目标关键词作为规则式降级；
    - 所有选择结果都带 reason，方便审计和用户理解。
    """

    def __init__(self, skills: tuple[AgentSkillDescriptor, ...]) -> None:
        self._skills = tuple(skill for skill in skills if skill.enabled)

    def select(self, objective: str, intent_analysis: IntentAnalysis | None) -> AgentSkillPlan:
        """选择适合当前请求的 Skill。

        `objective` 用于关键词兜底；`intent_analysis` 用于结构化选择。后续接入 LLM Skill router 时，
        可以保持返回 `AgentSkillPlan` 不变，只替换内部评分实现。
        """

        objective_text = objective.lower()
        domains = set(intent_analysis.governance_domains if intent_analysis else ())
        candidate_tools = set(intent_analysis.candidate_tools if intent_analysis else ())
        selections: list[AgentSkillSelection] = []

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

            if score <= 0:
                continue
            selections.append(
                AgentSkillSelection(
                    skill_code=skill.skill_code,
                    display_name=skill.display_name,
                    domain=skill.domain,
                    score=min(score, 1.0),
                    reason="；".join(reasons),
                    required_tools=skill.required_tools,
                    memory_dependencies=skill.memory_dependencies,
                    approval_policy=skill.approval_policy,
                )
            )

        ordered = tuple(sorted(selections, key=lambda item: item.score, reverse=True)[:3])
        return AgentSkillPlan(
            selected_skills=ordered,
            available_skill_count=len(self._skills),
            rationale=self._build_rationale(ordered, len(self._skills)),
        )

    @staticmethod
    def _build_rationale(selections: tuple[AgentSkillSelection, ...], available_count: int) -> str:
        """构建 Skill 选择解释。"""

        if not selections:
            return f"当前共有 {available_count} 个可用 Skill，但本次请求没有命中明确能力包。"
        skill_text = "、".join(item.display_name for item in selections)
        return f"当前共有 {available_count} 个可用 Skill，本次推荐启用：{skill_text}。"
