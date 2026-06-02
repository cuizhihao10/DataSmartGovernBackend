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


GATEWAY_SOURCE_SERVICE = "datasmart-govern-gateway"
TRUSTED_ROOT_KEY = "trustedControlPlane"


def enrich_agent_plan_payload_from_gateway_headers(
    payload: Mapping[str, object],
    headers: Mapping[str, str],
    *,
    required_source_service: str = GATEWAY_SOURCE_SERVICE,
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

    tenant_id = _header(headers, "X-DataSmart-Tenant-Id")
    actor_id = _header(headers, "X-DataSmart-Actor-Id")
    actor_role = _header(headers, "X-DataSmart-Actor-Role")
    workspace_id = _header(headers, "X-DataSmart-Workspace-Id")
    trace_id = _header(headers, "X-DataSmart-Trace-Id")
    authorized_project_ids = _csv(_header(headers, "X-DataSmart-Authorized-Project-Ids"))

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
    }
    variables[TRUSTED_ROOT_KEY] = {
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
        "toolBudget": dict(common_policy_facts),
    }
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
