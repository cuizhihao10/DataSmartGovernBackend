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

from typing import Mapping

from datasmart_ai_runtime.api_gateway_signature import (
    GatewaySignatureNonceStore,
    GatewaySignatureVerificationConfig,
    ensure_gateway_signature,
    gateway_signature_config_from_env,
)


GATEWAY_SOURCE_SERVICE = "datasmart-govern-gateway"
TRUSTED_ROOT_KEY = "trustedControlPlane"


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
