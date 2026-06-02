"""Agent Skill 准入策略。

Skill 注册表回答“这个能力包是否适合当前目标”，准入策略回答“当前用户与运行上下文是否允许启用
这个能力包”。这两个问题必须分开：如果只按语义命中 Skill，高风险任务创建、权限解释、导出治理等
能力很容易在缺少授权证据时被暴露给模型；如果只按权限过滤，又会让产品失去“为什么能力没有启用”的
可解释性。

本模块先提供轻量本地策略，消费 `AgentRequest.variables` 中由 Java gateway、permission-admin 或
测试夹具注入的上下文。未来如果准入判断迁移到 Java 控制面，本模块可以退化为远程 client 或 provider，
而 `AgentSkillRegistry` 仍然只消费统一的 admission decision。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor


@dataclass(frozen=True)
class AgentSkillAdmissionDecision:
    """单个 Skill 的准入判断结果。

    字段说明：
    - `allowed`：是否允许本轮启用该 Skill。只有允许的 Skill 才会进入 `selected_skills`，从而影响
      后续工具 schema 暴露和能力解释。
    - `status`：面向前端、审计和测试的稳定状态。当前使用字符串保持轻量，后续可升级为枚举。
    - `reasons`：中文解释，说明通过、拒绝或条件性推荐的依据。这里不记录用户敏感输入，只记录策略事实。
    """

    allowed: bool
    status: str
    reasons: tuple[str, ...]


class AgentSkillAdmissionPolicy:
    """基于请求上下文执行 Skill 准入判断。

    当前策略分三类处理：
    - 如果调用方显式注入 `grantedPermissions`，则严格校验 `required_permissions`；
    - 如果没有权限事实，则允许 Skill 作为“条件性推荐”进入计划，避免本地学习环境完全失去 Skill；
    - 对 HIGH/CRITICAL 风险 Skill 增加角色兜底，避免普通用户在显式权限不足时启用高风险能力。

    这不是最终 permission-admin 策略中心，只是 Python Runtime 的安全基线。真实生产环境应由 Java
    gateway 或 permission-admin 注入可信权限事实，或者把这里替换成远程 evaluate provider。
    """

    HIGH_RISK_ROLES = frozenset(
        {
            "PROJECT_OWNER",
            "TENANT_ADMIN",
            "PLATFORM_ADMIN",
            "OPERATOR",
            "SERVICE_ACCOUNT",
        }
    )

    def evaluate(
        self,
        skill: AgentSkillDescriptor,
        request: AgentRequest | None = None,
    ) -> AgentSkillAdmissionDecision:
        """判断当前请求是否允许启用某个 Skill。

        `request` 允许为空，是为了兼容离线测试、旧调用和纯意图分析场景。没有请求时不做强阻断，只返回
        条件性推荐，让调用方知道准入上下文不足。只要显式传入了权限事实，本策略就会按事实执行阻断。
        """

        if request is None:
            return AgentSkillAdmissionDecision(
                allowed=True,
                status="CONDITIONAL",
                reasons=("缺少 AgentRequest，上下文不足；仅按 Skill 语义做条件性推荐。",),
            )

        variables = request.variables or {}
        reasons: list[str] = []
        permission_decision = self._permission_decision(skill, variables)
        if not permission_decision.allowed:
            return permission_decision
        reasons.extend(permission_decision.reasons)

        risk_decision = self._risk_decision(skill, variables)
        if not risk_decision.allowed:
            return risk_decision
        reasons.extend(risk_decision.reasons)

        if not reasons:
            reasons.append("Skill 未声明特殊权限或高风险约束，允许启用。")
        status = (
            "ALLOWED"
            if permission_decision.status == "ALLOWED" and risk_decision.status == "ALLOWED"
            else "CONDITIONAL"
        )
        return AgentSkillAdmissionDecision(allowed=True, status=status, reasons=tuple(reasons))

    def _permission_decision(
        self,
        skill: AgentSkillDescriptor,
        variables: Mapping[str, object],
    ) -> AgentSkillAdmissionDecision:
        """校验 Skill 所需权限。

        只有调用方显式提供 `grantedPermissions` / `granted_permissions` 时才强校验。这样做的原因是当前
        Python Runtime 仍支持本地离线运行：如果没有 Java gateway 注入权限事实，直接拒绝所有带权限
        声明的 Skill 会让学习环境无法验证完整 Agent 流程。
        """

        required = set(skill.required_permissions)
        if not required:
            return AgentSkillAdmissionDecision(True, "ALLOWED", ("Skill 未声明必需权限。",))

        granted_raw = variables.get("grantedPermissions", variables.get("granted_permissions"))
        if granted_raw is None:
            return AgentSkillAdmissionDecision(
                True,
                "CONDITIONAL",
                (
                    "请求未携带 grantedPermissions，暂按条件性推荐处理；生产环境应由 gateway 或 permission-admin 注入权限事实。",
                ),
            )

        granted = _string_set(granted_raw)
        missing = tuple(sorted(required - granted))
        if missing:
            return AgentSkillAdmissionDecision(
                False,
                "DENIED_MISSING_PERMISSION",
                ("缺少启用 Skill 所需权限：" + "、".join(missing),),
            )
        return AgentSkillAdmissionDecision(True, "ALLOWED", ("已满足 Skill 所需权限。",))

    def _risk_decision(
        self,
        skill: AgentSkillDescriptor,
        variables: Mapping[str, object],
    ) -> AgentSkillAdmissionDecision:
        """校验高风险 Skill 的角色兜底。

        这里不替代正式 RBAC，只是给 HIGH/CRITICAL Skill 增加一层本地防线。即使调用方误把权限事实传得
        过宽，普通用户也不会在 Python 侧直接启用高风险能力包。后续接 permission-admin 后，可以把该
        逻辑升级为“远程策略版本 + 审计证据”。
        """

        risk_level = str(skill.risk_level or "LOW").strip().upper()
        if risk_level not in {"HIGH", "CRITICAL"}:
            return AgentSkillAdmissionDecision(True, "ALLOWED", (f"Skill 风险等级为 {risk_level}，无需高风险角色兜底。",))

        actor_role_raw = variables.get("actorRole") or variables.get("actor_role") or variables.get("role")
        if actor_role_raw is None:
            return AgentSkillAdmissionDecision(
                True,
                "CONDITIONAL",
                (
                    f"Skill 风险等级为 {risk_level}，但请求未携带 actorRole；本地仅做条件性推荐，生产环境应注入角色事实。",
                ),
            )
        actor_role = str(actor_role_raw).strip().upper()
        if actor_role in self.HIGH_RISK_ROLES:
            return AgentSkillAdmissionDecision(True, "ALLOWED", (f"高风险 Skill 已由角色 {actor_role} 通过兜底校验。",))
        return AgentSkillAdmissionDecision(
            False,
            "DENIED_RISK_ROLE",
            (f"Skill 风险等级为 {risk_level}，当前角色 {actor_role} 不允许启用高风险能力包。",),
        )


def _string_set(value: object) -> set[str]:
    """把请求变量中的权限集合规范化为字符串集合。

    调用方可能传 list/tuple/set，也可能传逗号分隔字符串。统一在这里处理，可以避免策略代码到处写
    类型判断。空字符串会被忽略，权限名保持原始大小写，便于与 Java 控制面的权限编码精确匹配。
    """

    if isinstance(value, str):
        return {item.strip() for item in value.split(",") if item.strip()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return {str(item).strip() for item in value if str(item).strip()}
    return set()
