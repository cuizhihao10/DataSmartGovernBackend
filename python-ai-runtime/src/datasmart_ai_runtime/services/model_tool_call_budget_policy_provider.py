"""模型工具调用预算策略来源。

`ModelToolCallBudgetGuard` 只负责执行预算规则，不应该知道策略来自哪里。真实商业产品里，工具预算可能
来自租户套餐、项目等级、用户角色、实时 worker backlog、Java permission-admin 或运营开关。
本文件先提供轻量策略 provider：
- 环境变量作为部署级默认值；
- `AgentRequest.variables["toolCallBudget"]` 作为请求级覆盖；
- 非法、空值或小于 1 的配置会被忽略，避免错误配置把 Agent 主链路直接锁死。

后续接 Java 策略中心时，只需要实现同一协议，不必修改预算守卫算法。
"""

from __future__ import annotations

import os
from dataclasses import replace
from typing import Mapping, Protocol

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.model_tool_call_budget_guard import ModelToolCallBudgetPolicy


class ModelToolCallBudgetPolicyProvider(Protocol):
    """工具调用预算策略提供者协议。"""

    def policy_for(self, request: AgentRequest) -> ModelToolCallBudgetPolicy:
        """根据请求上下文返回本轮工具调用预算策略。"""


class EnvAndRequestModelToolCallBudgetPolicyProvider:
    """从环境变量和请求变量生成工具调用预算策略。

    优先级：
    1. `AgentRequest.variables["toolCallBudget"]`；
    2. `AgentRequest.variables` 顶层同名字段；
    3. 环境变量；
    4. `ModelToolCallBudgetPolicy` 默认值。

    请求级覆盖适合 Java gateway 或前端按场景下发，例如高敏感 workspace 收紧自动工具数量；
    环境变量适合私有化部署时先做统一默认值，等 permission-admin 策略中心成熟后再替换。
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
            if (parsed := _positive_int(self._environ.get(env_key))) is not None
        }
        return replace(policy, **updates) if updates else policy

    def _policy_from_request(
        self,
        policy: ModelToolCallBudgetPolicy,
        variables: Mapping[str, object],
    ) -> ModelToolCallBudgetPolicy:
        """读取请求级覆盖策略。"""

        nested = variables.get("toolCallBudget") or variables.get("tool_call_budget") or {}
        nested_mapping = nested if isinstance(nested, Mapping) else {}
        updates: dict[str, int] = {}
        for field_name, keys in self.REQUEST_KEYS.items():
            value = _first_present(nested_mapping, keys)
            if value is None:
                value = _first_present(variables, keys)
            if (parsed := _positive_int(value)) is not None:
                updates[field_name] = parsed
        return replace(policy, **updates) if updates else policy


def _first_present(mapping: Mapping[str, object], keys: tuple[str, ...]) -> object | None:
    """按候选键读取第一个存在值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _positive_int(value: object | None) -> int | None:
    """把配置值解析为正整数；非法值返回 None。"""

    if value is None:
        return None
    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None
