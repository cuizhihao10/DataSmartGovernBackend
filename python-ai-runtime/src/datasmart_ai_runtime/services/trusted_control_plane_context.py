"""Agent 可信控制面上下文读取器。

普通 ``AgentRequest.variables`` 本质上属于调用方输入：终端、前端或上游服务都可能写入其中。
因此，权限集合、角色、租户 Skill 开关等会影响授权结果的事实，不能继续和普通业务参数混在一起读取。

本模块约定使用 ``trustedControlPlane`` 保留命名空间承载由 gateway、agent-runtime 或测试夹具注入的
控制面事实。保留命名空间并不等于已经完成服务间认证：生产环境仍必须只允许受控内部链路写入它。
它解决的是 Python Runtime 内部的第一道边界问题：普通终端变量不能再被误当成可信授权事实。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

from datasmart_ai_runtime.domain.contracts import AgentRequest


@dataclass(frozen=True)
class AgentTrustedSkillAdmissionContext:
    """单次 Skill admission 评估所需的可信事实快照。

    字段保持轻量且显式，避免把完整用户权限对象、Token 或认证声明透传给 Python Runtime。
    ``granted_permissions`` 只携带本次 Skill 准入需要比较的权限编码集合；后续正式接入 gateway 时，
    应由 Java 控制面根据已认证主体生成该快照，而不是由客户端提交。
    """

    workspace_key: str | None = None
    actor_role: str | None = None
    granted_permissions: tuple[str, ...] = ()
    tenant_skill_enabled: bool = True
    workspace_risk_level: str = "NORMAL"
    tenant_plan_code: str = "STANDARD"
    policy_version: str | None = None


class AgentTrustedControlPlaneContextReader:
    """从保留命名空间读取 Skill admission 可信事实。

    ``allow_legacy_variables`` 仅用于迁移期联调。默认关闭时，读取器绝不会从普通 variables 中回退读取
    角色或权限；这使安全默认值保持为“没有可信证据”，而不是“相信调用方自报身份”。
    """

    ROOT_KEY = "trustedControlPlane"
    SKILL_ADMISSION_KEY = "skillAdmission"

    @classmethod
    def skill_admission(
        cls,
        request: AgentRequest,
        *,
        allow_legacy_variables: bool = False,
    ) -> AgentTrustedSkillAdmissionContext:
        """构造 Skill admission 可信事实快照。

        ``trustedControlPlane.skillAdmission`` 不存在时返回保守默认值。这样远程 permission-admin 仍可
        返回条件性准入或拒绝结果，同时不会把普通变量中的伪造角色和权限升级为可信事实。
        """

        variables = request.variables or {}
        source = cls._trusted_skill_admission_mapping(variables)
        if source is None and allow_legacy_variables:
            source = variables
        source = source or {}
        return AgentTrustedSkillAdmissionContext(
            workspace_key=_string_value(source, "workspaceKey", "workspace_key"),
            actor_role=_string_value(source, "actorRole", "actor_role", "role"),
            granted_permissions=tuple(sorted(_string_set(_first_present(source, "grantedPermissions", "granted_permissions")))),
            tenant_skill_enabled=_bool_value(source, True, "tenantSkillEnabled", "tenant_skill_enabled"),
            workspace_risk_level=_string_value(source, "workspaceRiskLevel", "workspace_risk_level") or "NORMAL",
            tenant_plan_code=_string_value(source, "tenantPlanCode", "tenant_plan_code") or "STANDARD",
            policy_version=_string_value(source, "policyVersion", "policy_version"),
        )

    @classmethod
    def _trusted_skill_admission_mapping(cls, variables: Mapping[str, object]) -> Mapping[str, object] | None:
        """读取两层保留对象；任意一层类型不正确时按“不存在可信事实”处理。"""

        root = variables.get(cls.ROOT_KEY)
        if not isinstance(root, Mapping):
            return None
        skill_admission = root.get(cls.SKILL_ADMISSION_KEY)
        return skill_admission if isinstance(skill_admission, Mapping) else None


def _first_present(mapping: Mapping[str, object], *keys: str) -> object | None:
    """返回第一个显式存在的键值，保留 ``False`` 等有效布尔值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _string_value(mapping: Mapping[str, object], *keys: str) -> str | None:
    """把可选字段规范化为非空字符串。"""

    value = _first_present(mapping, *keys)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _string_set(value: object | None) -> set[str]:
    """把列表或逗号分隔文本规范化为权限编码集合。"""

    if isinstance(value, str):
        return {item.strip() for item in value.split(",") if item.strip()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return {str(item).strip() for item in value if str(item).strip()}
    return set()


def _bool_value(mapping: Mapping[str, object], default: bool, *keys: str) -> bool:
    """读取常见布尔表示；字段缺失时使用显式安全默认值。"""

    value = _first_present(mapping, *keys)
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}
