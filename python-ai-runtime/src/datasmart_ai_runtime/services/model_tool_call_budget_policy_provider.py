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

import json
import os
from dataclasses import replace
from typing import Any, Callable, Mapping, Protocol
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.model_tool_call_budget_guard import ModelToolCallBudgetPolicy
from datasmart_ai_runtime.services.trusted_control_plane_context import AgentTrustedControlPlaneContextReader


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
        """读取请求级覆盖策略。

        这一步允许 Java Gateway、permission-admin 或前端控制面在一次具体请求中下发更细粒度的预算。
        例如：
        - 普通问答场景可以允许模型提出较多候选工具，提升探索能力；
        - 高风险数据删除、权限变更、跨租户查询等场景应收紧自动执行数量；
        - worker backlog 较高时可以临时降低工具数量，避免 Agent 把队列打爆。

        这里同时支持嵌套 `toolCallBudget` 和顶层同名字段，是为了兼容不同调用方的协议成熟度。
        真正生产化后，建议由 permission-admin 统一输出标准 DTO，Python 侧只消费一个稳定结构。
        """

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


class PermissionAdminToolBudgetPolicyClientError(RuntimeError):
    """permission-admin 工具预算策略客户端错误。

    单独定义异常类型，是为了让 provider 能区分“远程策略中心不可用”和“本地配置解析失败”。
    生产环境通常希望远程不可用时 fail-closed 或走降级策略；本地开发则更希望回退默认预算。
    """


class JavaPermissionAdminToolBudgetPolicyClient:
    """调用 Java permission-admin 的 Agent 工具预算策略评估接口。

    当前客户端使用标准库 `urllib`，保持 Python Runtime 轻依赖。它只负责 HTTP 契约：
    - 构造 `AgentToolBudgetPolicyEvaluateRequest`；
    - 解析 `PlatformApiResponse<AgentToolBudgetPolicyView>`；
    - 把 Java camelCase `toolCallBudget` 转换为 Python `ModelToolCallBudgetPolicy`。

    它不直接参与预算执行，也不替代 Java permission-admin 的访问授权。
    """

    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: int = 3,
        evaluate_path: str = "/permissions/agent/tool-budget-policies/evaluate",
        urlopen_func: Callable[..., Any] = urlopen,
        allow_legacy_request_variables: bool = False,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._evaluate_path = evaluate_path
        self._urlopen = urlopen_func
        self._allow_legacy_request_variables = allow_legacy_request_variables

    def evaluate(self, request_context: AgentRequest, trace_id: str | None = None) -> ModelToolCallBudgetPolicy:
        """向 permission-admin 请求本轮工具调用预算策略。"""

        body = json.dumps(
            self.build_payload(
                request_context,
                allow_legacy_request_variables=self._allow_legacy_request_variables,
            ),
            ensure_ascii=False,
        ).encode("utf-8")
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
        except Exception as exc:  # pragma: no cover - 网络错误由集成测试覆盖
            raise PermissionAdminToolBudgetPolicyClientError(f"调用 permission-admin 工具预算策略失败：{exc}") from exc
        return self.parse_platform_response(payload)

    @classmethod
    def build_payload(
        cls,
        request_context: AgentRequest,
        *,
        allow_legacy_request_variables: bool = False,
    ) -> dict[str, Any]:
        """构造 Java `AgentToolBudgetPolicyEvaluateRequest`。

        Python `AgentRequest` 的 tenant/project 当前是字符串，Java DTO 的 tenantId 是 Long。
        为了避免本地测试里的 `tenant-a` 被错误发送成非法 Long，这里只在能安全转换时传 `tenantId`，
        其余隔离信息放在 `projectId/workspaceKey` 等字符串字段中。
        """

        variables = request_context.variables or {}
        trusted_context = AgentTrustedControlPlaneContextReader.tool_budget(
            request_context,
            allow_legacy_variables=allow_legacy_request_variables,
        )
        return {
            "tenantId": _optional_int(request_context.tenant_id) or _optional_int(variables.get("tenantId")),
            "projectId": str(variables.get("projectId") or request_context.project_id),
            "workspaceKey": trusted_context.workspace_key,
            "actorRole": trusted_context.actor_role or "ORDINARY_USER",
            "tenantPlanCode": trusted_context.tenant_plan_code,
            "workspaceRiskLevel": trusted_context.workspace_risk_level,
            "workerBacklogLevel": trusted_context.worker_backlog_level,
            "requestedToolRiskLevel": trusted_context.requested_tool_risk_level,
        }

    @classmethod
    def parse_platform_response(cls, payload: Mapping[str, Any]) -> ModelToolCallBudgetPolicy:
        """解析 Java 统一响应并返回 Python 预算策略。"""

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "permission-admin 工具预算策略接口返回失败")
            raise PermissionAdminToolBudgetPolicyClientError(f"{reason}: {message}")
        data = payload.get("data")
        if not isinstance(data, Mapping):
            raise PermissionAdminToolBudgetPolicyClientError("permission-admin 工具预算响应 data 必须是对象")
        budget = data.get("toolCallBudget")
        if not isinstance(budget, Mapping):
            raise PermissionAdminToolBudgetPolicyClientError("permission-admin 工具预算响应缺少 toolCallBudget")
        return _policy_from_budget_mapping(budget)


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

        try:
            return self._remote_client.evaluate(request, trace_id=self._trace_id)
        except PermissionAdminToolBudgetPolicyClientError:
            if not self._allow_remote_fallback:
                raise
            return self._local_provider.policy_for(request)


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


def _policy_from_budget_mapping(mapping: Mapping[str, object]) -> ModelToolCallBudgetPolicy:
    """把 Java/Python 兼容字段映射为 `ModelToolCallBudgetPolicy`。"""

    policy = ModelToolCallBudgetPolicy()
    updates: dict[str, int] = {}
    for field_name, keys in EnvAndRequestModelToolCallBudgetPolicyProvider.REQUEST_KEYS.items():
        if (parsed := _positive_int(_first_present(mapping, keys))) is not None:
            updates[field_name] = parsed
    return replace(policy, **updates) if updates else policy


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
