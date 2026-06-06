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
from dataclasses import dataclass, replace
from typing import Any, Callable, Mapping, Protocol
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_guard import ModelToolCallBudgetPolicy
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


@dataclass(frozen=True)
class PermissionAdminToolBudgetPolicyResponse:
    """permission-admin Agent 工具策略评估响应的 Python 侧低敏快照。

    Java `AgentToolBudgetPolicyView` 现在同时承载两类策略：
    - `toolCallBudget`：模型工具调用预算，用于限制模型本轮最多提出多少工具、自动执行多少工具；
    - `toolExecutionReadinessPolicy`：执行准备度策略，用于判断 ToolPlan 是否可执行、是否等待审批、澄清、
      入队、限流或阻断。

    这里把二者放在一个响应对象里，是为了让 Python 侧先固定“一个控制面评估结果可以服务多个治理环节”的
    领域语义。当前预算 provider 和 readiness provider 仍可能分别调用远程接口；后续优化时可以把本对象作为
    请求级缓存值，避免一次 `/agent/plans` 发生重复 HTTP 策略评估。
    """

    tool_call_budget_policy: ModelToolCallBudgetPolicy
    tool_execution_readiness_policy: Mapping[str, Any] | None = None


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
        """向 permission-admin 请求本轮工具调用预算策略。

        该方法保留旧返回类型，避免已经接入 `RemoteThenLocalModelToolCallBudgetPolicyProvider` 的代码被迫
        同步升级。新链路如果需要同时读取 readiness policy，应调用 `evaluate_response(...)` 或
        `evaluate_readiness_policy(...)`。
        """

        return self.evaluate_response(request_context, trace_id=trace_id).tool_call_budget_policy

    def evaluate_response(
        self,
        request_context: AgentRequest,
        trace_id: str | None = None,
    ) -> PermissionAdminToolBudgetPolicyResponse:
        """向 permission-admin 请求完整 Agent 工具治理策略响应。

        这里仍然只把低敏 DTO 带回 Python：
        - budget 字段会转换成 `ModelToolCallBudgetPolicy`；
        - readiness 字段只保留枚举、预算、布尔开关和影响码；
        - prompt、SQL、工具实参、样本数据、模型输出、凭证、内部 endpoint 等字段即使 Java 意外返回，也会被裁剪。
        """

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
        return self.parse_platform_policy_response(payload)

    def evaluate_readiness_policy(
        self,
        request_context: AgentRequest,
        trace_id: str | None = None,
    ) -> Mapping[str, Any] | None:
        """读取 Java 控制面返回的标准 `toolExecutionReadinessPolicy`。

        这个方法服务 Python `RemoteThenLocalToolExecutionReadinessPolicyProvider`。返回 None 表示远程服务仍是
        旧版本、暂未输出标准 readiness policy；provider 会根据配置决定回退本地策略还是 fail-closed。
        """

        return self.evaluate_response(request_context, trace_id=trace_id).tool_execution_readiness_policy

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

        return cls.parse_platform_policy_response(payload).tool_call_budget_policy

    @classmethod
    def parse_platform_policy_response(
        cls,
        payload: Mapping[str, Any],
    ) -> PermissionAdminToolBudgetPolicyResponse:
        """解析 Java 统一响应并返回完整低敏策略快照。

        兼容性原则：
        - `toolCallBudget` 是旧链路必需字段，缺失时仍然视为远程响应无效；
        - `toolExecutionReadinessPolicy` 是 5.39 后新增字段，缺失时返回 None，交由上层 provider 决定是否回退；
        - readiness policy 解析采用字段白名单，防止远程响应中出现的敏感字段被透传到 HTTP 响应或 runtime event。
        """

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
        readiness_policy = _readiness_policy_from_policy_view(data.get("toolExecutionReadinessPolicy"))
        return PermissionAdminToolBudgetPolicyResponse(
            tool_call_budget_policy=_policy_from_budget_mapping(budget),
            tool_execution_readiness_policy=readiness_policy,
        )


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


def _readiness_policy_from_policy_view(value: object | None) -> dict[str, Any] | None:
    """从 Java `toolExecutionReadinessPolicy` 中裁剪低敏字段。

    这个函数是 Python Runtime 与 Java permission-admin 之间的安全闸口之一。Java 控制面未来可能为了审计、
    排障或内部策略解释返回更多字段，但 Python `/agent/plans`、runtime event 和 WebSocket replay 不能
    自动透传未知字段，否则很容易把 prompt、SQL、工具参数、样本数据、权限明细、内部 endpoint 或凭证带入
    Agent 事件流。这里采用显式白名单：
    - 字符串枚举：来源、版本、角色、套餐、风险、backlog；
    - 非负整数：同步/异步工具预算；
    - 布尔开关：高风险审批、关键风险阻断、缺参草案；
    - 影响码：低敏机器码列表。
    """

    if not isinstance(value, Mapping):
        return None
    result: dict[str, Any] = {}
    scalar_aliases: dict[str, tuple[str, ...]] = {
        "source": ("source",),
        "policyVersion": ("policyVersion", "policy_version"),
        "actorRole": ("actorRole", "actor_role", "role"),
        "tenantPlanCode": ("tenantPlanCode", "tenant_plan_code"),
        "workspaceRiskLevel": ("workspaceRiskLevel", "workspace_risk_level"),
        "workerBacklogLevel": ("workerBacklogLevel", "worker_backlog_level"),
    }
    integer_aliases: dict[str, tuple[str, ...]] = {
        "maxAutoSyncTools": ("maxAutoSyncTools", "max_auto_sync_tools"),
        "maxAsyncTools": ("maxAsyncTools", "max_async_tools"),
    }
    boolean_aliases: dict[str, tuple[str, ...]] = {
        "highRiskRequiresApproval": ("highRiskRequiresApproval", "high_risk_requires_approval"),
        "criticalRiskBlocked": ("criticalRiskBlocked", "critical_risk_blocked"),
        "allowDraftWithoutAllParameters": (
            "allowDraftWithoutAllParameters",
            "allow_draft_without_all_parameters",
        ),
    }
    for target_key, aliases in scalar_aliases.items():
        if text := _string_var(value, *aliases):
            result[target_key] = text
    for target_key, aliases in integer_aliases.items():
        if (parsed := _non_negative_int(_first_present(value, aliases))) is not None:
            result[target_key] = parsed
    for target_key, aliases in boolean_aliases.items():
        if (parsed := _optional_bool(_first_present(value, aliases))) is not None:
            result[target_key] = parsed
    influence_codes = _string_tuple(_first_present(value, ("influenceCodes", "influence_codes")))
    if influence_codes:
        result["influenceCodes"] = influence_codes
    return result or None


def _non_negative_int(value: object | None) -> int | None:
    """解析非负整数；非法值返回 None，避免远程异常字段污染本地策略。"""

    if value is None:
        return None
    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return parsed if parsed >= 0 else None


def _optional_bool(value: object | None) -> bool | None:
    """解析可选布尔字段；字段缺失时返回 None 以保留本地默认。"""

    if value is None:
        return None
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _string_tuple(value: object | None) -> tuple[str, ...]:
    """解析影响码列表，并丢弃空白或复杂对象。"""

    if value is None:
        return ()
    if isinstance(value, str):
        candidates = value.split(",")
    elif isinstance(value, (list, tuple, set, frozenset)):
        candidates = value
    else:
        return ()
    return tuple(text for item in candidates if (text := str(item).strip()))


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
