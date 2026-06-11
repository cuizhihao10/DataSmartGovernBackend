"""Python API 边界的 gateway 可信上下文装配器。

外部调用方提交的 JSON 请求体不能直接决定角色、租户或智能网关预算。即使请求体中包含
``trustedControlPlane``，API 边界也必须先删除它，再根据统一 gateway 已清理和重建的 Header 构造
新的最小可信快照。

当前实现是迁移期桥接，不是最终服务间认证方案：
- ``X-DataSmart-Source-Service`` 只能说明请求声称来自哪个服务，不能替代签名、mTLS 或服务账号 Token；
- 生产环境必须禁止公网或终端直连 Python Runtime；
- 后续应由 gateway 或 agent-runtime 注入 permission-admin 权限快照、策略版本和容量快照。
"""

from __future__ import annotations

import json
from typing import Any, Mapping

from datasmart_ai_runtime.api.gateway.signature import (
    GatewaySignatureNonceStore,
    GatewaySignatureVerificationConfig,
    ensure_gateway_signature,
    gateway_signature_config_from_env,
)


GATEWAY_SOURCE_SERVICE = "datasmart-govern-gateway"
TRUSTED_ROOT_KEY = "trustedControlPlane"
TOOL_POLICY_ENVELOPE_HEADER = "X-DataSmart-Tool-Policy-Envelope"
MAX_TOOL_POLICY_ENVELOPE_BYTES = 4096


def enrich_agent_plan_payload_from_gateway_headers(
    payload: Mapping[str, object],
    headers: Mapping[str, str],
    *,
    required_source_service: str = GATEWAY_SOURCE_SERVICE,
    signature_config: GatewaySignatureVerificationConfig | None = None,
    now_ms: int | None = None,
    nonce_store: GatewaySignatureNonceStore | None = None,
) -> dict[str, object]:
    """清理调用方伪造事实，并按 gateway Header 重建 Agent plan 请求。

    输入与输出说明：
    - ``payload``：终端或上游提交的原始 JSON 对象；
    - ``headers``：FastAPI/Starlette 请求头对象或测试字典；
    - 返回新的字典，不原地修改原请求，避免日志、测试夹具和后续中间件观察到被悄悄篡改的对象。

    只有 source-service 命中统一 gateway 时才会注入可信快照。未命中时仍会删除请求体中的
    ``trustedControlPlane``，但保留普通业务字段，让本地离线规划可以继续运行。
    """

    sanitized = dict(payload)
    raw_variables = sanitized.get("variables")
    variables = dict(raw_variables) if isinstance(raw_variables, Mapping) else {}
    variables.pop(TRUSTED_ROOT_KEY, None)

    source_service = _header(headers, "X-DataSmart-Source-Service")
    if source_service != required_source_service:
        sanitized["variables"] = variables
        return sanitized

    # 只有命中统一 gateway 来源时才执行内部签名校验。
    #
    # 设计原因：
    # 1. 非 gateway 请求本来就不会注入 trustedControlPlane，不需要额外验签；
    # 2. gateway 请求一旦要把 Header 变成可信事实，就必须证明这些 Header 确实由 gateway 生成；
    # 3. 本地学习环境可能没有配置密钥，因此默认由环境变量决定是否强制校验。生产环境应设置
    #    DATASMART_GATEWAY_SIGNATURE_REQUIRED=true 和 DATASMART_GATEWAY_SIGNATURE_SECRET。
    effective_signature_config = signature_config or gateway_signature_config_from_env()
    ensure_gateway_signature(headers, effective_signature_config, now_ms=now_ms, nonce_store=nonce_store)

    tenant_id = _header(headers, "X-DataSmart-Tenant-Id")
    actor_id = _header(headers, "X-DataSmart-Actor-Id")
    actor_role = _header(headers, "X-DataSmart-Actor-Role")
    workspace_id = _header(headers, "X-DataSmart-Workspace-Id")
    trace_id = _header(headers, "X-DataSmart-Trace-Id")
    authorized_project_ids = _csv(_header(headers, "X-DataSmart-Authorized-Project-Ids"))
    tenant_plan_code = _header(headers, "X-DataSmart-Tenant-Plan-Code") or "STANDARD"
    workspace_risk_level = _header(headers, "X-DataSmart-Workspace-Risk-Level") or "NORMAL"
    tool_budget_policy_version = _header(headers, "X-DataSmart-Tool-Budget-Policy-Version")
    skill_visibility_cache_key = _header(headers, "X-DataSmart-Skill-Visibility-Cache-Key")
    skill_visibility_cache_version = _header(headers, "X-DataSmart-Skill-Visibility-Cache-Version")
    skill_visibility_cache_scope = _header(headers, "X-DataSmart-Skill-Visibility-Cache-Scope")
    skill_visibility_cache_ttl_seconds = _positive_int(
        _header(headers, "X-DataSmart-Skill-Visibility-Cache-Ttl-Seconds"),
        0,
    )
    tool_policy_envelope = _tool_policy_envelope_from_header(headers)

    # tenantId 与 actorId 属于认证主体事实。只要 gateway 已提供，就覆盖请求体同名字段；
    # 如果 Header 缺失则保留请求体值，兼容本地开发，但生产 gateway 应配置为身份缺失时拒绝请求。
    if tenant_id:
        sanitized["tenant_id"] = tenant_id
    if actor_id:
        sanitized["actor_id"] = actor_id
    if trace_id:
        variables["traceId"] = trace_id

    common_policy_facts = {
        "workspaceKey": workspace_id,
        "actorRole": actor_role,
        "tenantPlanCode": tenant_plan_code,
        "workspaceRiskLevel": workspace_risk_level,
    }
    trusted_control_plane = {
        "requestContext": {
            "sourceService": source_service,
            "traceId": trace_id,
            "tenantId": tenant_id,
            "actorId": actor_id,
            "actorRole": actor_role,
            "workspaceId": workspace_id,
            "authorizedProjectIds": authorized_project_ids,
        },
        "skillAdmission": dict(common_policy_facts),
        "toolBudget": {
            **common_policy_facts,
            "policyVersion": tool_budget_policy_version,
        },
    }
    if tool_policy_envelope is not None:
        # 工具策略 envelope 是 gateway/permission-admin 一次性下发给 Python Runtime 的低敏控制面快照。
        # 它必须在验签通过之后才能解析，并且只允许进入 trustedControlPlane；请求体中伪造的同名字段已经在
        # 函数开头被删除。这样模型工具预算与执行准备度策略可以共享同一次 Java 控制面评估结果，避免
        # `/agent/plans` 内部为了 toolCallBudget 和 readiness policy 分别远程调用 permission-admin。
        tool_call_budget = _tool_call_budget_from_envelope(tool_policy_envelope.get("toolCallBudget"))
        if tool_call_budget:
            trusted_control_plane["toolBudget"].update(tool_call_budget)
        readiness_policy = _tool_readiness_policy_from_envelope(
            tool_policy_envelope.get("toolExecutionReadinessPolicy")
        )
        if readiness_policy:
            trusted_control_plane["toolExecutionReadinessPolicy"] = readiness_policy
    if skill_visibility_cache_key:
        # 只在 gateway 提供缓存 key 时注入缓存上下文。该 key 已被 gateway HMAC 签名保护，
        # Python Runtime 仍会把它与 project/session/Skill Manifest 指纹再次组合，避免跨项目、
        # 跨会话或跨 Skill 发布版本复用准入判断。
        trusted_control_plane["skillVisibilityCache"] = {
            "enabled": True,
            "gatewayCacheKey": skill_visibility_cache_key,
            "version": skill_visibility_cache_version or "v1",
            "scope": skill_visibility_cache_scope or "session-ready-skill-admission",
            "ttlSeconds": skill_visibility_cache_ttl_seconds,
            "tenantPlanCode": tenant_plan_code,
            "workspaceRiskLevel": workspace_risk_level,
            "toolBudgetPolicyVersion": tool_budget_policy_version,
        }
    variables[TRUSTED_ROOT_KEY] = trusted_control_plane
    sanitized["variables"] = variables
    return sanitized


def _header(headers: Mapping[str, str], name: str) -> str | None:
    """大小写不敏感读取 Header，兼容 Starlette Headers 与普通测试字典。"""

    value = headers.get(name) or headers.get(name.lower())
    if value is None:
        lowered_name = name.lower()
        value = next((item for key, item in headers.items() if str(key).lower() == lowered_name), None)
    text = str(value).strip() if value is not None else ""
    return text or None


def _csv(value: str | None) -> tuple[str, ...]:
    """把 gateway 物化后的逗号分隔 Header 转换为不可变快照。"""

    if not value:
        return ()
    return tuple(item.strip() for item in value.split(",") if item.strip())


def _tool_policy_envelope_from_header(headers: Mapping[str, str]) -> Mapping[str, Any] | None:
    """读取并解析 gateway 签名保护的工具策略 envelope。

    Header 解析原则：
    - Header 不存在时返回 None，兼容尚未升级的 gateway；
    - Header 存在但不是 JSON object 时直接拒绝请求，因为这代表控制面策略注入出现集成错误；
    - Header 长度限制为 4KB，避免把大量权限明细、prompt 或工具参数误塞进 HTTP Header；
    - 即使 JSON 中包含未知字段，后续也只按白名单裁剪 `toolCallBudget` 与 `toolExecutionReadinessPolicy`。

    为什么选择 fail-closed：
    如果 gateway 明确下发了策略 envelope，却因为格式错误被 Python 静默忽略，本轮请求可能退回更宽松的本地默认预算。
    对商业化 Agent 来说，这比请求失败更危险。因此 envelope 存在但不可解析时应暴露为安全边界错误。
    """

    raw_value = _header(headers, TOOL_POLICY_ENVELOPE_HEADER)
    if raw_value is None:
        return None
    if len(raw_value.encode("utf-8")) > MAX_TOOL_POLICY_ENVELOPE_BYTES:
        raise PermissionError("gateway tool policy envelope is too large")
    try:
        parsed = json.loads(raw_value)
    except json.JSONDecodeError as exc:
        raise PermissionError("gateway tool policy envelope must be a JSON object") from exc
    if not isinstance(parsed, Mapping):
        raise PermissionError("gateway tool policy envelope must be a JSON object")
    return parsed


def _tool_call_budget_from_envelope(value: object | None) -> dict[str, object]:
    """从 envelope 中裁剪模型工具调用预算字段。

    `toolCallBudget` 会进入 `trustedControlPlane.toolBudget`，供本地 `ModelToolCallBudgetPolicyProvider`
    优先消费。这里不接受 actorRole、workspaceRiskLevel 等身份事实，因为这些事实已经由独立 Header 构建；
    envelope 只补充预算数字和策略版本，避免一个 JSON 字段覆盖整套可信身份上下文。
    """

    if not isinstance(value, Mapping):
        return {}
    result: dict[str, object] = {}
    for target_key, aliases in {
        "policyVersion": ("policyVersion", "policy_version"),
        "maxProposedToolCalls": ("maxProposedToolCalls", "max_proposed_tool_calls"),
        "maxAutoExecutableToolCalls": ("maxAutoExecutableToolCalls", "max_auto_executable_tool_calls"),
        "maxHighRiskToolCalls": ("maxHighRiskToolCalls", "max_high_risk_tool_calls"),
        "maxSingleArgumentsBytes": ("maxSingleArgumentsBytes", "max_single_arguments_bytes"),
        "maxTotalArgumentsBytes": ("maxTotalArgumentsBytes", "max_total_arguments_bytes"),
    }.items():
        raw_field_value = _first_present(value, *aliases)
        if target_key == "policyVersion":
            if text := _string_value(raw_field_value):
                result[target_key] = text
        elif (number := _non_negative_int(raw_field_value)) is not None:
            result[target_key] = number
    return result


def _tool_readiness_policy_from_envelope(value: object | None) -> dict[str, object]:
    """从 envelope 中裁剪执行准备度策略字段。

    readiness policy 是“执行前是否继续”的关键控制面输入，因此只允许低敏白名单：
    策略来源、版本、角色/套餐/风险/backlog 枚举、同步/异步预算、审批/阻断/草案布尔开关和影响码。
    任何 prompt、SQL、工具参数值、样本数据、模型输出、凭证或内部 endpoint 都不会进入 trustedControlPlane。
    """

    if not isinstance(value, Mapping):
        return {}
    result: dict[str, object] = {}
    for target_key, aliases in {
        "source": ("source",),
        "policyVersion": ("policyVersion", "policy_version"),
        "actorRole": ("actorRole", "actor_role", "role"),
        "tenantPlanCode": ("tenantPlanCode", "tenant_plan_code"),
        "workspaceRiskLevel": ("workspaceRiskLevel", "workspace_risk_level"),
        "workerBacklogLevel": ("workerBacklogLevel", "worker_backlog_level"),
    }.items():
        if text := _string_value(_first_present(value, *aliases)):
            result[target_key] = text
    for target_key, aliases in {
        "maxAutoSyncTools": ("maxAutoSyncTools", "max_auto_sync_tools"),
        "maxAsyncTools": ("maxAsyncTools", "max_async_tools"),
    }.items():
        if (number := _non_negative_int(_first_present(value, *aliases))) is not None:
            result[target_key] = number
    for target_key, aliases in {
        "highRiskRequiresApproval": ("highRiskRequiresApproval", "high_risk_requires_approval"),
        "criticalRiskBlocked": ("criticalRiskBlocked", "critical_risk_blocked"),
        "allowDraftWithoutAllParameters": (
            "allowDraftWithoutAllParameters",
            "allow_draft_without_all_parameters",
        ),
    }.items():
        if (flag := _optional_bool(_first_present(value, *aliases))) is not None:
            result[target_key] = flag
    influence_codes = _string_tuple(_first_present(value, "influenceCodes", "influence_codes"))
    if influence_codes:
        result["influenceCodes"] = influence_codes
    return result


def _first_present(mapping: Mapping[str, object], *keys: str) -> object | None:
    """返回第一个显式存在的字段值，保留 0/False 这类有效策略配置。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _string_value(value: object | None) -> str | None:
    """把可选字段规范化为非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _non_negative_int(value: object | None) -> int | None:
    """读取非负整数策略字段；非法值返回 None，由下游默认值兜底。"""

    if value is None:
        return None
    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return parsed if parsed >= 0 else None


def _optional_bool(value: object | None) -> bool | None:
    """读取可选布尔策略字段；字段缺失时返回 None。"""

    if value is None:
        return None
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _string_tuple(value: object | None) -> tuple[str, ...]:
    """读取 influenceCodes 这类低敏机器码列表。"""

    if value is None:
        return ()
    if isinstance(value, str):
        candidates = value.split(",")
    elif isinstance(value, (list, tuple, set, frozenset)):
        candidates = value
    else:
        return ()
    return tuple(text for item in candidates if (text := str(item).strip()))


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数 Header。

    gateway Header 属于外部输入边界，即使已经验签，也可能因为配置错误携带空值、负数或非数字。
    Python Runtime 在读取 TTL 时采用保守兜底：非法值不让缓存无限期生效，而是回退为调用方指定的默认值。
    """

    if value is None:
        return default
    try:
        parsed = int(str(value).strip())
    except ValueError:
        return default
    return parsed if parsed > 0 else default
