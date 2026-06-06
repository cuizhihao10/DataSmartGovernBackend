"""模型工具调用预算策略来源。

`ModelToolCallBudgetGuard` 只负责执行预算规则，不应该知道策略来自哪里。真实商业产品里，工具预算可能
来自租户套餐、项目等级、用户角色、实时 worker backlog、Java permission-admin 或运营开关。
本文件先提供轻量策略 provider：
- 环境变量作为部署级默认值；
- `AgentRequest.variables["toolCallBudget"]` 作为请求级覆盖；
- 非法、空值或负数配置会被忽略；0 是合法值，表示控制面明确要求本轮阻断某类工具能力。

后续接 Java 策略中心时，只需要实现同一协议，不必修改预算守卫算法。
"""

from __future__ import annotations

import os
from dataclasses import replace
from typing import Mapping, Protocol

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_guard import ModelToolCallBudgetPolicy
from datasmart_ai_runtime.services.model_gateway.permission_admin_tool_budget_policy_client import (
    JavaPermissionAdminToolBudgetPolicyClient,
    PermissionAdminToolBudgetPolicyClientError,
)


class ModelToolCallBudgetPolicyProvider(Protocol):
    """工具调用预算策略提供者协议。"""

    def policy_for(self, request: AgentRequest) -> ModelToolCallBudgetPolicy:
        """根据请求上下文返回本轮工具调用预算策略。"""


class EnvAndRequestModelToolCallBudgetPolicyProvider:
    """从环境变量和请求变量生成工具调用预算策略。

    优先级：
    1. `trustedControlPlane.toolBudget`，由 gateway 验签后重建，优先级最高；
    2. `AgentRequest.variables["toolCallBudget"]`；
    3. `AgentRequest.variables` 顶层同名字段；
    4. 环境变量；
    5. `ModelToolCallBudgetPolicy` 默认值。

    受信控制面覆盖适合 Java gateway 或 permission-admin 按场景下发，例如高敏感 workspace 收紧自动工具数量；
    普通请求级覆盖仅作为本地学习和迁移期兼容入口，生产环境不应依赖客户端自报预算。
    """

    ENV_KEYS = {
        "max_proposed_tool_calls": "DATASMART_AI_TOOL_BUDGET_MAX_PROPOSED_CALLS",
        "max_auto_executable_tool_calls": "DATASMART_AI_TOOL_BUDGET_MAX_AUTO_EXECUTABLE_CALLS",
        "max_high_risk_tool_calls": "DATASMART_AI_TOOL_BUDGET_MAX_HIGH_RISK_CALLS",
        "max_single_arguments_bytes": "DATASMART_AI_TOOL_BUDGET_MAX_SINGLE_ARGUMENT_BYTES",
        "max_total_arguments_bytes": "DATASMART_AI_TOOL_BUDGET_MAX_TOTAL_ARGUMENT_BYTES",
    }
    REQUEST_KEYS = {
        "max_proposed_tool_calls": ("maxProposedToolCalls", "max_proposed_tool_calls"),
        "max_auto_executable_tool_calls": ("maxAutoExecutableToolCalls", "max_auto_executable_tool_calls"),
        "max_high_risk_tool_calls": ("maxHighRiskToolCalls", "max_high_risk_tool_calls"),
        "max_single_arguments_bytes": ("maxSingleArgumentsBytes", "max_single_arguments_bytes"),
        "max_total_arguments_bytes": ("maxTotalArgumentsBytes", "max_total_arguments_bytes"),
    }

    def __init__(
        self,
        *,
        default_policy: ModelToolCallBudgetPolicy | None = None,
        environ: Mapping[str, str] | None = None,
    ) -> None:
        self._default_policy = default_policy or ModelToolCallBudgetPolicy()
        self._environ = environ if environ is not None else os.environ

    def policy_for(self, request: AgentRequest) -> ModelToolCallBudgetPolicy:
        """合成当前请求的预算策略。"""

        policy = self._policy_from_environment(self._default_policy)
        return self._policy_from_request(policy, request.variables or {})

    def _policy_from_environment(self, policy: ModelToolCallBudgetPolicy) -> ModelToolCallBudgetPolicy:
        """读取部署级默认策略。"""

        updates = {
            field_name: parsed
            for field_name, env_key in self.ENV_KEYS.items()
            if (parsed := _budget_limit_int(self._environ.get(env_key))) is not None
        }
        return replace(policy, **updates) if updates else policy

    def _policy_from_request(
        self,
        policy: ModelToolCallBudgetPolicy,
        variables: Mapping[str, object],
    ) -> ModelToolCallBudgetPolicy:
        """读取请求级覆盖策略。

        这一步允许 Java Gateway、permission-admin 或前端控制面在一次具体请求中下发更细粒度的预算。
        例如：
        - 普通问答场景可以允许模型提出较多候选工具，提升探索能力；
        - 高风险数据删除、权限变更、跨租户查询等场景应收紧自动执行数量；
        - worker backlog 较高时可以临时降低工具数量，避免 Agent 把队列打爆。

        读取顺序刻意分为两层：
        1. 先读取普通 `toolCallBudget` / 顶层同名字段，兼容历史测试、CLI 和本地学习场景；
        2. 最后读取 `trustedControlPlane.toolBudget`，让 gateway 签名保护的策略 envelope 拥有最高优先级。

        这样做能防止请求体中伪造的预算覆盖 gateway/permission-admin 已经评估过的控制面结果。真正生产化后，
        推荐由 gateway 一次性注入 `toolCallBudget + toolExecutionReadinessPolicy` envelope，Python 侧无需再
        为预算与 readiness 分别远程调用 permission-admin。
        """

        policy = self._policy_from_budget_mapping(
            policy,
            self._mapping_value(variables, "toolCallBudget", "tool_call_budget") or {},
        )
        policy = self._policy_from_top_level_values(policy, variables)
        trusted_budget = self._trusted_tool_budget(variables)
        if trusted_budget is not None:
            policy = self._policy_from_budget_mapping(policy, trusted_budget)
        return policy

    def _policy_from_top_level_values(
        self,
        policy: ModelToolCallBudgetPolicy,
        variables: Mapping[str, object],
    ) -> ModelToolCallBudgetPolicy:
        """兼容历史顶层预算字段。

        顶层字段属于迁移期入口，不能覆盖受信控制面预算。保留它的原因是 CLI、离线单元测试和早期集成调用
        还可能直接传 `maxAutoExecutableToolCalls` 这类字段。正式 gateway 链路应优先使用
        `trustedControlPlane.toolBudget`。
        """

        updates: dict[str, int] = {}
        for field_name, keys in self.REQUEST_KEYS.items():
            if (parsed := _budget_limit_int(_first_present(variables, keys))) is not None:
                updates[field_name] = parsed
        return replace(policy, **updates) if updates else policy

    def _policy_from_budget_mapping(
        self,
        policy: ModelToolCallBudgetPolicy,
        mapping: Mapping[str, object],
    ) -> ModelToolCallBudgetPolicy:
        """从指定预算对象读取预算字段。"""

        updates: dict[str, int] = {}
        for field_name, keys in self.REQUEST_KEYS.items():
            if (parsed := _budget_limit_int(_first_present(mapping, keys))) is not None:
                updates[field_name] = parsed
        return replace(policy, **updates) if updates else policy

    @staticmethod
    def _mapping_value(variables: Mapping[str, object], *keys: str) -> Mapping[str, object] | None:
        """从普通 variables 中读取兼容预算对象。"""

        value = _first_present(variables, keys)
        return value if isinstance(value, Mapping) else None

    @staticmethod
    def _trusted_tool_budget(variables: Mapping[str, object]) -> Mapping[str, object] | None:
        """读取 gateway 签名保护后的 `trustedControlPlane.toolBudget`。

        该字段只能由 API 边界在验签通过后重建；请求体中伪造的 `trustedControlPlane` 会先被删除。
        因此这里把它放在最高优先级，覆盖普通 request/env 预算。
        """

        trusted_root = variables.get("trustedControlPlane")
        if not isinstance(trusted_root, Mapping):
            return None
        tool_budget = trusted_root.get("toolBudget")
        return tool_budget if isinstance(tool_budget, Mapping) else None


class RemoteThenLocalModelToolCallBudgetPolicyProvider:
    """远程优先、本地回退的工具预算策略 provider。

    远程策略来自 Java permission-admin，是商业化环境中的权威控制面；本地 provider 只作为开发、
    灰度或远程不可用时的降级路径。默认 `allow_remote_fallback=True` 是为了不破坏本地学习环境；
    生产环境可以关闭回退，让策略中心故障直接暴露为请求失败或上游降级。
    """

    def __init__(
        self,
        remote_client: JavaPermissionAdminToolBudgetPolicyClient,
        *,
        local_provider: ModelToolCallBudgetPolicyProvider | None = None,
        allow_remote_fallback: bool = True,
        trace_id: str | None = None,
    ) -> None:
        self._remote_client = remote_client
        self._local_provider = local_provider or EnvAndRequestModelToolCallBudgetPolicyProvider()
        self._allow_remote_fallback = allow_remote_fallback
        self._trace_id = trace_id

    def policy_for(self, request: AgentRequest) -> ModelToolCallBudgetPolicy:
        """先尝试远程策略；失败时按配置回退本地策略。"""

        if self._has_trusted_tool_budget(request):
            # gateway/agent-runtime 已经通过签名 envelope 注入受信预算时，不应再同步回源 permission-admin。
            # 这正是 5.42 的性能优化目标：同一次 `/agent/plans` 请求共享一份控制面评估结果，
            # 避免预算 provider 与 readiness provider 各自发起远程调用。
            return self._local_provider.policy_for(request)
        try:
            return self._remote_client.evaluate(request, trace_id=self._trace_id)
        except PermissionAdminToolBudgetPolicyClientError:
            if not self._allow_remote_fallback:
                raise
            return self._local_provider.policy_for(request)

    @staticmethod
    def _has_trusted_tool_budget(request: AgentRequest) -> bool:
        """判断请求是否已经携带签名保护后的工具预算。"""

        variables = request.variables or {}
        trusted_root = variables.get("trustedControlPlane")
        return isinstance(trusted_root, Mapping) and isinstance(trusted_root.get("toolBudget"), Mapping)


def _first_present(mapping: Mapping[str, object], keys: tuple[str, ...]) -> object | None:
    """按候选键读取第一个存在值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _budget_limit_int(value: object | None) -> int | None:
    """把预算上限解析为非负整数；非法值返回 None。

    预算字段和超时、TTL 这类配置不同：0 不是“没有配置”，而是非常重要的业务信号。
    例如 `maxHighRiskToolCalls=0` 表示本轮禁止 HIGH/CRITICAL 工具，`maxAutoExecutableToolCalls=0`
    表示只能生成草案或等待审批，不能自动继续推进工具。因此这里允许 0，并只丢弃负数、空值和非法文本。
    """

    if value is None:
        return None
    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return parsed if parsed >= 0 else None
