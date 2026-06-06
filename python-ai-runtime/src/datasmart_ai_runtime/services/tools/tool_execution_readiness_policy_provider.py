"""工具执行准备度策略来源。

本模块负责把 Java gateway、permission-admin 或测试夹具注入的低敏控制面快照，转换为
`ToolExecutionReadinessService` 可以直接消费的 `ToolExecutionReadinessPolicy`。

为什么要单独拆出这个文件：
- readiness 评估服务应该只关心“给定一批 ToolPlan 和一份策略，如何判定可执行/审批/澄清/限流”；
- API 响应组装层应该只关心“什么时候读取策略、什么时候发布事件、怎么返回 HTTP 结构”；
- 策略来源则属于控制面合同，未来可能来自 permission-admin HTTP、Redis 配额、worker backlog、
  租户套餐、角色授权或灰度开关。如果把这些解析逻辑塞进 readiness 服务或 API 文件，会很快形成
  新的高耦合大文件。

安全边界：
- 默认只信任 `AgentRequest.variables["trustedControlPlane"]` 命名空间里的控制面字段；
- 普通业务变量中的 `toolCallBudget` 只作为兼容输入读取预算数字，不读取角色、套餐、风险或 backlog；
- 输出摘要只包含策略来源、版本、角色/套餐/风险/backlog 枚举和影响码，不包含 prompt、工具参数、
  SQL、样本数据、模型输出、凭证或内部 endpoint。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.tools.tool_execution_readiness import ToolExecutionReadinessPolicy


@dataclass(frozen=True)
class ToolExecutionReadinessPolicySnapshot:
    """一次 Agent 请求的工具执行准备度策略快照。

    字段说明：
    - `policy`：真正交给 readiness 评估器执行的预算与风险策略；
    - `source`：策略来源。`trusted-control-plane` 表示来自受控命名空间，`request-tool-call-budget`
      表示只从现有工具预算字段推导，`local-default` 表示完全使用本地默认策略；
    - `policy_version`：permission-admin 或 gateway 下发的策略版本，用于审计和排障；
    - `actor_role`、`tenant_plan_code`、`workspace_risk_level`、`worker_backlog_level`：只保留低敏枚举，
      用来解释“为什么本轮预算被收紧”，不携带完整权限对象；
    - `influence_codes`：稳定机器码，便于前端、Java projection 和审计系统做聚合，不依赖中文文案。
    """

    policy: ToolExecutionReadinessPolicy
    source: str
    policy_version: str | None = None
    actor_role: str | None = None
    tenant_plan_code: str = "STANDARD"
    workspace_risk_level: str = "NORMAL"
    worker_backlog_level: str = "NORMAL"
    influence_codes: tuple[str, ...] = ()

    def to_low_sensitive_summary(self) -> dict[str, Any]:
        """返回可进入 HTTP 响应和 runtime event 的低敏策略摘要。

        这里刻意只暴露“策略如何影响预算/风险”的元数据，不返回任何工具参数值或用户目标文本。
        这样前端能解释当前决策，Java 控制面也能回放策略版本，但不会把 Python Runtime 变成权限日志
        或敏感上下文转储点。
        """

        return {
            "source": self.source,
            "policyVersion": self.policy_version,
            "actorRole": self.actor_role,
            "tenantPlanCode": self.tenant_plan_code,
            "workspaceRiskLevel": self.workspace_risk_level,
            "workerBacklogLevel": self.worker_backlog_level,
            "maxAutoSyncTools": self.policy.max_auto_sync_tools,
            "maxAsyncTools": self.policy.max_async_tools,
            "highRiskRequiresApproval": self.policy.high_risk_requires_approval,
            "criticalRiskBlocked": self.policy.critical_risk_blocked,
            "allowDraftWithoutAllParameters": self.policy.allow_draft_without_all_parameters,
            "influenceCodes": self.influence_codes,
            "payloadPolicy": "LOW_SENSITIVE_POLICY_METADATA_ONLY",
        }


class ToolExecutionReadinessPolicyProvider:
    """从请求上下文生成工具执行准备度策略。

    当前实现是“本地解析控制面快照”，不是远程 HTTP 客户端。这样做的原因是：
    - gateway 或 Java agent-runtime 已经可以在进入 Python Runtime 前完成认证、签名校验和策略评估；
    - Python 侧先固定消费合同，后续无论策略来自 permission-admin、Redis、worker backlog 还是服务网格，
      都能注入同一份低敏快照；
    - 本地学习和单元测试不需要启动完整 Java 微服务，也不会把策略读取变成同步网络依赖。
    """

    TRUSTED_ROOT_KEY = "trustedControlPlane"
    READINESS_POLICY_KEY = "toolExecutionReadinessPolicy"

    def policy_for(self, request: AgentRequest) -> ToolExecutionReadinessPolicySnapshot:
        """根据请求生成本轮 readiness policy snapshot。

        读取优先级：
        1. `trustedControlPlane.toolExecutionReadinessPolicy`，用于未来 gateway/permission-admin 标准合同；
        2. `trustedControlPlane.toolBudget` 或 `toolCallBudget` 中的低敏预算数字，用于兼容现有工具预算策略；
        3. 本地默认策略，用于没有控制面上下文的开发和测试场景。
        """

        variables = request.variables or {}
        trusted_policy = self._trusted_section(variables, self.READINESS_POLICY_KEY)
        if trusted_policy is not None:
            return self._snapshot_from_trusted_policy(trusted_policy)
        budget_source = self._trusted_section(variables, "toolBudget") or self._mapping_value(
            variables,
            "toolCallBudget",
            "tool_call_budget",
        )
        if budget_source is not None:
            return self._snapshot_from_budget_mapping(budget_source)
        return ToolExecutionReadinessPolicySnapshot(
            policy=ToolExecutionReadinessPolicy(),
            source="local-default",
            influence_codes=("LOCAL_DEFAULT_POLICY",),
        )

    def _snapshot_from_trusted_policy(
        self,
        source: Mapping[str, object],
    ) -> ToolExecutionReadinessPolicySnapshot:
        """解析标准 readiness policy 控制面快照。

        该快照允许控制面同时下发显式预算字段和解释性上下文。显式字段优先，然后根据角色、租户套餐、
        workspace 风险和 worker backlog 继续做保守收敛。这样即使控制面只下发“当前 backlog=HIGH”，
        Python 侧也能先采用安全降载策略。
        """

        policy = ToolExecutionReadinessPolicy(
            max_auto_sync_tools=_non_negative_int(_first_present(source, "maxAutoSyncTools", "max_auto_sync_tools"), 3),
            max_async_tools=_non_negative_int(_first_present(source, "maxAsyncTools", "max_async_tools"), 2),
            high_risk_requires_approval=_bool_value(source, True, "highRiskRequiresApproval", "high_risk_requires_approval"),
            critical_risk_blocked=_bool_value(source, True, "criticalRiskBlocked", "critical_risk_blocked"),
            allow_draft_without_all_parameters=_bool_value(
                source,
                True,
                "allowDraftWithoutAllParameters",
                "allow_draft_without_all_parameters",
            ),
        )
        metadata = _PolicyMetadata(
            policy_version=_string_value(source, "policyVersion", "policy_version"),
            actor_role=_normalize_code(_string_value(source, "actorRole", "actor_role", "role")),
            tenant_plan_code=_normalize_code(_string_value(source, "tenantPlanCode", "tenant_plan_code") or "STANDARD"),
            workspace_risk_level=_normalize_code(
                _string_value(source, "workspaceRiskLevel", "workspace_risk_level") or "NORMAL"
            ),
            worker_backlog_level=_normalize_code(
                _string_value(source, "workerBacklogLevel", "worker_backlog_level") or "NORMAL"
            ),
        )
        policy, influence_codes = self._apply_contextual_safety_caps(policy, metadata)
        return ToolExecutionReadinessPolicySnapshot(
            policy=policy,
            source="trusted-control-plane",
            policy_version=metadata.policy_version,
            actor_role=metadata.actor_role,
            tenant_plan_code=metadata.tenant_plan_code,
            workspace_risk_level=metadata.workspace_risk_level,
            worker_backlog_level=metadata.worker_backlog_level,
            influence_codes=influence_codes or ("TRUSTED_CONTROL_PLANE_POLICY",),
        )

    def _snapshot_from_budget_mapping(
        self,
        source: Mapping[str, object],
    ) -> ToolExecutionReadinessPolicySnapshot:
        """从现有工具预算字段推导 readiness policy。

        现有 `toolCallBudget` 更偏“模型最多能提出多少工具调用”，而 readiness policy 更偏“哪些工具可以
        进入执行前准备态”。两者不是同一个领域对象，但在 5.38 阶段可以先建立兼容映射：
        - `maxAutoExecutableToolCalls` 映射到同步自动工具预算；
        - `maxAsyncTools` 或 `maxAsyncToolCalls` 映射到异步入队预算；
        - 未提供的风险策略继续使用 readiness 默认值。
        """

        policy = ToolExecutionReadinessPolicy(
            max_auto_sync_tools=_non_negative_int(
                _first_present(source, "maxAutoExecutableToolCalls", "max_auto_executable_tool_calls"),
                3,
            ),
            max_async_tools=_non_negative_int(
                _first_present(source, "maxAsyncTools", "maxAsyncToolCalls", "max_async_tools", "max_async_tool_calls"),
                2,
            ),
        )
        return ToolExecutionReadinessPolicySnapshot(
            policy=policy,
            source="request-tool-call-budget",
            policy_version=_string_value(source, "policyVersion", "policy_version"),
            influence_codes=("DERIVED_FROM_TOOL_CALL_BUDGET",),
        )

    @staticmethod
    def _apply_contextual_safety_caps(
        policy: ToolExecutionReadinessPolicy,
        metadata: "_PolicyMetadata",
    ) -> tuple[ToolExecutionReadinessPolicy, tuple[str, ...]]:
        """根据角色、套餐、风险和 backlog 对策略做保守收敛。

        这里使用“只收紧、不放宽”的原则：控制面可以显式给较大的预算，但当 workspace 风险或 worker backlog
        显示压力较高时，Python 侧仍会把预算压低，避免模型一次性把执行队列打爆。
        """

        max_sync = policy.max_auto_sync_tools
        max_async = policy.max_async_tools
        high_risk_requires_approval = policy.high_risk_requires_approval
        critical_risk_blocked = policy.critical_risk_blocked
        allow_draft = policy.allow_draft_without_all_parameters
        influence_codes: list[str] = []

        if metadata.actor_role in {"AUDITOR", "READ_ONLY_ANALYST", "COMPLIANCE_REVIEWER"}:
            max_sync = min(max_sync, 0)
            max_async = min(max_async, 0)
            influence_codes.append("READ_ONLY_ROLE_BLOCKS_AUTO_EXECUTION")
        if metadata.tenant_plan_code in {"FREE", "STARTER", "TRIAL"}:
            max_sync = min(max_sync, 1)
            max_async = min(max_async, 0)
            influence_codes.append("TENANT_PLAN_LIMITS_TOOL_BUDGET")
        if metadata.workspace_risk_level in {"HIGH", "RESTRICTED"}:
            max_sync = min(max_sync, 1)
            high_risk_requires_approval = True
            influence_codes.append("WORKSPACE_RISK_REQUIRES_APPROVAL")
        if metadata.workspace_risk_level in {"CRITICAL", "LOCKED"}:
            max_sync = min(max_sync, 0)
            max_async = min(max_async, 0)
            high_risk_requires_approval = True
            critical_risk_blocked = True
            allow_draft = False
            influence_codes.append("WORKSPACE_RISK_BLOCKS_AUTO_EXECUTION")
        if metadata.worker_backlog_level in {"HIGH", "BUSY"}:
            max_sync = min(max_sync, 1)
            max_async = min(max_async, 1)
            influence_codes.append("WORKER_BACKLOG_REDUCES_TOOL_BUDGET")
        if metadata.worker_backlog_level in {"CRITICAL", "SATURATED"}:
            max_sync = min(max_sync, 0)
            max_async = min(max_async, 0)
            influence_codes.append("WORKER_BACKLOG_BLOCKS_TOOL_BUDGET")

        return (
            ToolExecutionReadinessPolicy(
                max_auto_sync_tools=max_sync,
                max_async_tools=max_async,
                high_risk_requires_approval=high_risk_requires_approval,
                critical_risk_blocked=critical_risk_blocked,
                allow_draft_without_all_parameters=allow_draft,
            ),
            tuple(influence_codes),
        )

    @classmethod
    def _trusted_section(
        cls,
        variables: Mapping[str, object],
        section_key: str,
    ) -> Mapping[str, object] | None:
        """读取受控命名空间下的某个章节。"""

        root = variables.get(cls.TRUSTED_ROOT_KEY)
        if not isinstance(root, Mapping):
            return None
        section = root.get(section_key)
        return section if isinstance(section, Mapping) else None

    @staticmethod
    def _mapping_value(variables: Mapping[str, object], *keys: str) -> Mapping[str, object] | None:
        """从普通变量中读取兼容预算对象；只用于预算数字，不读取身份或权限事实。"""

        value = _first_present(variables, *keys)
        return value if isinstance(value, Mapping) else None


@dataclass(frozen=True)
class _PolicyMetadata:
    """策略上下文的内部规范化结构，避免在收敛函数里反复处理字符串空值。"""

    policy_version: str | None
    actor_role: str | None
    tenant_plan_code: str
    workspace_risk_level: str
    worker_backlog_level: str


def _first_present(mapping: Mapping[str, object], *keys: str) -> object | None:
    """返回第一个显式存在的字段值，保留 0/False 这类有效配置。"""

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


def _normalize_code(value: str | None) -> str | None:
    """把角色、套餐、风险和 backlog 枚举统一成大写下划线形式。"""

    if value is None:
        return None
    return value.strip().replace("-", "_").replace(" ", "_").upper() or None


def _bool_value(mapping: Mapping[str, object], default: bool, *keys: str) -> bool:
    """读取常见布尔配置，字段不存在时使用显式默认值。"""

    value = _first_present(mapping, *keys)
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _non_negative_int(value: object | None, default: int) -> int:
    """把预算字段解析为非负整数；非法值回退默认值。"""

    if value is None:
        return default
    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError):
        return default
    return parsed if parsed >= 0 else default
