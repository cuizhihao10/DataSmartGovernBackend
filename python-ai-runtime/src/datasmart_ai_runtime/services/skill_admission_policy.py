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

import json
from dataclasses import dataclass
from typing import Any, Callable, Mapping
from urllib.request import Request, urlopen

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


class PermissionAdminSkillAdmissionClientError(RuntimeError):
    """permission-admin Skill 准入策略客户端错误。

    单独定义异常类型，是为了让远程 provider 能区分“Java 控制面不可用”和“本地准入规则本身异常”。
    本地学习环境通常希望远程不可用时回退；生产环境则可以关闭回退，让策略中心故障显式暴露。
    """


class JavaPermissionAdminSkillAdmissionClient:
    """调用 Java permission-admin 的 Agent Skill admission evaluate 接口。

    该客户端只负责协议适配：
    - 把 Python `AgentSkillDescriptor + AgentRequest` 映射为 Java `AgentSkillAdmissionEvaluateRequest`；
    - 解析 `PlatformApiResponse<AgentSkillAdmissionPolicyView>`；
    - 转换为 Python `AgentSkillAdmissionDecision`。

    它不做 Skill 语义选择，也不执行任何工具。Skill 是否命中仍由 `AgentSkillRegistry` 的评分逻辑处理；
    Skill 是否允许启用，则由本客户端连接的 Java 控制面回答。
    """

    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: int = 3,
        evaluate_path: str = "/permissions/agent/skill-admissions/evaluate",
        urlopen_func: Callable[..., Any] = urlopen,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._evaluate_path = evaluate_path
        self._urlopen = urlopen_func

    def evaluate(
        self,
        skill: AgentSkillDescriptor,
        request_context: AgentRequest,
        trace_id: str | None = None,
    ) -> AgentSkillAdmissionDecision:
        """向 permission-admin 请求单个 Skill 的准入结果。"""

        body = json.dumps(self.build_payload(skill, request_context), ensure_ascii=False).encode("utf-8")
        http_request = Request(
            url=f"{self._base_url}{self._evaluate_path}",
            data=body,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json; charset=utf-8",
                "X-DataSmart-Trace-Id": trace_id or _string_var(request_context.variables, "traceId", "trace_id") or "",
                "X-DataSmart-Source-Service": "python-ai-runtime",
            },
            method="POST",
        )
        try:
            with self._urlopen(http_request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误由集成测试或外部环境覆盖
            raise PermissionAdminSkillAdmissionClientError(f"调用 permission-admin Skill 准入策略失败：{exc}") from exc
        return self.parse_platform_response(payload)

    @classmethod
    def build_payload(
        cls,
        skill: AgentSkillDescriptor,
        request_context: AgentRequest,
    ) -> dict[str, Any]:
        """构造 Java `AgentSkillAdmissionEvaluateRequest`。

        这里显式把 Skill descriptor 中的权限、风险和编码带给 Java 控制面，是为了避免 Java 侧必须重新
        查询 Python 当前使用的 Skill descriptor。后续 Skill Marketplace 成熟后，可以让 Java 根据
        skillCode 自行加载正式 descriptor，并把 Python 传入值作为防漂移校验。
        """

        variables = request_context.variables or {}
        return {
            "tenantId": _optional_int(request_context.tenant_id) or _optional_int(variables.get("tenantId")),
            "projectId": str(variables.get("projectId") or request_context.project_id),
            "workspaceKey": _string_var(variables, "workspaceKey", "workspace_key"),
            "skillCode": skill.skill_code,
            "riskLevel": str(skill.risk_level or "LOW").upper(),
            "requiredPermissions": tuple(skill.required_permissions),
            "grantedPermissions": tuple(_string_set(_first_present(variables, ("grantedPermissions", "granted_permissions")))),
            "actorRole": _string_var(variables, "actorRole", "actor_role", "role") or "ORDINARY_USER",
            "tenantSkillEnabled": _bool_var(variables, True, "tenantSkillEnabled", "tenant_skill_enabled"),
            "workspaceRiskLevel": _string_var(variables, "workspaceRiskLevel", "workspace_risk_level") or "NORMAL",
            "tenantPlanCode": _string_var(variables, "tenantPlanCode", "tenant_plan_code") or "STANDARD",
        }

    @classmethod
    def parse_platform_response(cls, payload: Mapping[str, Any]) -> AgentSkillAdmissionDecision:
        """解析 Java 平台统一响应并返回 Python 准入决策。"""

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "permission-admin Skill 准入策略接口返回失败")
            raise PermissionAdminSkillAdmissionClientError(f"{reason}: {message}")
        data = payload.get("data")
        if not isinstance(data, Mapping):
            raise PermissionAdminSkillAdmissionClientError("permission-admin Skill 准入响应 data 必须是对象")
        allowed = bool(data.get("allowed", False))
        status = str(data.get("admissionStatus") or ("ALLOWED" if allowed else "DENIED"))
        reason = data.get("rejectionReason")
        notes = tuple(str(item) for item in data.get("notes") or ())
        policy_version = data.get("policyVersion")
        matched_policy = data.get("matchedPolicy")
        reasons = tuple(item for item in (str(reason) if reason else "", *notes) if item)
        if policy_version:
            reasons += (f"permission-admin policyVersion={policy_version}",)
        if matched_policy:
            reasons += (f"matchedPolicy={matched_policy}",)
        return AgentSkillAdmissionDecision(allowed=allowed, status=status, reasons=reasons or ("permission-admin 已完成 Skill 准入评估。",))


class RemoteThenLocalAgentSkillAdmissionPolicy:
    """远程优先、本地回退的 Skill 准入策略。

    远程结果来自 Java permission-admin，是生产控制面事实；本地策略作为开发、离线测试或远程不可用时的
    降级路径。默认允许回退，是为了不破坏本地学习环境；生产可关闭回退实现 fail-closed。
    """

    def __init__(
        self,
        remote_client: JavaPermissionAdminSkillAdmissionClient,
        *,
        local_policy: AgentSkillAdmissionPolicy | None = None,
        allow_remote_fallback: bool = True,
        trace_id: str | None = None,
    ) -> None:
        self._remote_client = remote_client
        self._local_policy = local_policy or AgentSkillAdmissionPolicy()
        self._allow_remote_fallback = allow_remote_fallback
        self._trace_id = trace_id

    def evaluate(
        self,
        skill: AgentSkillDescriptor,
        request: AgentRequest | None = None,
    ) -> AgentSkillAdmissionDecision:
        """先尝试远程准入；失败时按配置回退本地准入策略。"""

        if request is None:
            return self._local_policy.evaluate(skill, request)
        try:
            return self._remote_client.evaluate(skill, request, trace_id=self._trace_id)
        except PermissionAdminSkillAdmissionClientError:
            if not self._allow_remote_fallback:
                raise
            return self._local_policy.evaluate(skill, request)


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


def _first_present(mapping: Mapping[str, object], keys: tuple[str, ...]) -> object | None:
    """按候选键读取第一个存在值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _string_var(mapping: Mapping[str, object], *keys: str) -> str | None:
    """从变量表中读取非空字符串。"""

    value = _first_present(mapping, keys)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _optional_int(value: object | None) -> int | None:
    """仅在值可安全表示为整数时返回整数。"""

    if value is None:
        return None
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _bool_var(mapping: Mapping[str, object], default: bool, *keys: str) -> bool:
    """读取布尔变量，兼容 bool 与常见字符串写法。"""

    value = _first_present(mapping, keys)
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}
