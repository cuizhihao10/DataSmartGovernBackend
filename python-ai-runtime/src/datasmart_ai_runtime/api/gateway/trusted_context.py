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

import hmac
import json
import os
from dataclasses import replace
from typing import Any, Mapping

from datasmart_ai_runtime.api.gateway.signature import (
    GatewaySignatureNonceStore,
    GatewaySignatureVerificationError,
    GatewaySignatureVerificationConfig,
    ensure_gateway_signature,
    gateway_signature_config_from_env,
)


GATEWAY_SOURCE_SERVICE = "datasmart-govern-gateway"
AGENT_RUNTIME_SOURCE_SERVICE = "agent-runtime"
TRUSTED_ROOT_KEY = "trustedControlPlane"
TOOL_POLICY_ENVELOPE_HEADER = "X-DataSmart-Tool-Policy-Envelope"
MAX_TOOL_POLICY_ENVELOPE_BYTES = 4096
RAG_SENSITIVITY_LEVEL_HEADER = "X-DataSmart-Rag-Sensitivity-Level"

# RAG 正文分级必须由受信控制面声明。普通请求体中的 sensitivityLevel 只是一段自报文本，
# 不能用来把真实的受限日志降级成 public/internal 后送往外部 Embedding 或 Reranker。
_RAG_SENSITIVITY_LEVELS = {"public", "internal", "confidential", "restricted", "sensitive"}


def runtime_event_access_context_from_gateway_headers(
    headers: Mapping[str, str],
    *,
    signature_config: GatewaySignatureVerificationConfig | None = None,
    now_ms: int | None = None,
    nonce_store: GatewaySignatureNonceStore | None = None,
) -> Any:
    """Build the event authorization context from verified Gateway headers.

    Event control messages are client supplied JSON and therefore must never be
    used as the source of tenant, project, actor, or role facts.  This helper is
    intentionally at the API trust boundary so HTTP and WebSocket routes share
    the same signature verification and identity projection.

    When signature enforcement is disabled, an empty context is returned for
    local development.  Production deployments enable enforcement and therefore
    fail closed for missing or invalid Gateway evidence.
    """

    from datasmart_ai_runtime.services.runtime_events.runtime_event_authorization import (
        RuntimeEventAccessContext,
    )

    effective_signature_config = signature_config or gateway_signature_config_from_env()
    source_service = _header(headers, "X-DataSmart-Source-Service")
    if source_service == GATEWAY_SOURCE_SERVICE:
        # Event subscriptions can expose persisted execution history, so a
        # claimed Gateway source is always required to prove its HMAC.
        ensure_gateway_signature(
            headers,
            replace(effective_signature_config, required=True),
            now_ms=now_ms,
            nonce_store=nonce_store,
        )
    elif effective_signature_config.required:
        raise GatewaySignatureVerificationError("missing-trusted-source")
    else:
        return RuntimeEventAccessContext()

    actor_role = _header(headers, "X-DataSmart-Actor-Role")
    roles = _csv(actor_role)
    normalized_roles = {role.strip().upper() for role in roles}
    return RuntimeEventAccessContext(
        tenant_id=_header(headers, "X-DataSmart-Tenant-Id"),
        project_id=_header(headers, "X-DataSmart-Project-Id"),
        actor_id=_header(headers, "X-DataSmart-Actor-Id"),
        roles=roles,
        is_platform_admin=bool(normalized_roles & {"PLATFORM_ADMIN", "PLATFORM_ADMINISTRATOR"}),
        is_tenant_admin=bool(normalized_roles & {"TENANT_ADMIN", "TENANT_ADMINISTRATOR"}),
        is_auditor=bool(normalized_roles & {"AUDITOR", "PLATFORM_AUDITOR"}),
        attributes={
            key: value
            for key, value in {
                "traceId": _header(headers, "X-DataSmart-Trace-Id"),
                "applicationId": _header(headers, "X-DataSmart-Application-Id"),
                "workspaceId": _header(headers, "X-DataSmart-Workspace-Id"),
            }.items()
            if value
        },
    )


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
    application_id = _header(headers, "X-DataSmart-Application-Id")
    project_id = _header(headers, "X-DataSmart-Project-Id")
    actor_id = _header(headers, "X-DataSmart-Actor-Id")
    actor_role = _header(headers, "X-DataSmart-Actor-Role")
    actor_type = _header(headers, "X-DataSmart-Actor-Type")
    workspace_id = _header(headers, "X-DataSmart-Workspace-Id")
    trace_id = _header(headers, "X-DataSmart-Trace-Id")
    authorized_project_ids = _csv(_header(headers, "X-DataSmart-Authorized-Project-Ids"))
    authorized_project_roles = _header(headers, "X-DataSmart-Authorized-Project-Roles")
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

    if project_id and authorized_project_ids and project_id not in authorized_project_ids:
        # Gateway 已经在路由授权阶段校验当前项目。Python 再做一次 fail-closed 防御，是为了避免
        # 过滤器顺序、灰度版本或内部调用方错误地组合“当前项目”和“授权项目集合”。
        raise PermissionError("trusted project context is outside authorized project scope")
    if project_id and not _positive_id(application_id):
        # 应用范围不能从 projectId、应用名称、用户正文或模型结果推断。Gateway 已识别并签名的项目请求
        # 如果缺少正整数 applicationId，说明授权上下文重建不完整，必须在 Python API 边界 fail-closed。
        raise PermissionError("trusted application context is missing or invalid")

    # tenantId 与 actorId 属于认证主体事实。只要 gateway 已提供，就覆盖请求体同名字段；
    # 如果 Header 缺失则保留请求体值，兼容本地开发，但生产 gateway 应配置为身份缺失时拒绝请求。
    if tenant_id:
        sanitized["tenant_id"] = tenant_id
    # 对已识别为 gateway 的请求，项目只能来自纳入 HMAC 的 Header，绝不回退到请求体自报值。
    sanitized.pop("project_id", None)
    if project_id:
        sanitized["project_id"] = project_id
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
        # Specialist coordinator 从根级读取该字段，保证所有专业 Agent 使用同一份 Gateway 受信应用范围。
        # requestContext 同时保留一份用于低敏审计展示；两者都来自同一个已签名 Header。
        "applicationId": application_id,
        "requestContext": {
            "sourceService": source_service,
            "traceId": trace_id,
            "tenantId": tenant_id,
            "applicationId": application_id,
            "projectId": project_id,
            "actorId": actor_id,
            "actorRole": actor_role,
            "actorType": actor_type,
            "workspaceId": workspace_id,
            "authorizedProjectIds": authorized_project_ids,
            "authorizedProjectRoles": authorized_project_roles,
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


def enrich_rag_query_payload_from_trusted_headers(
    payload: Mapping[str, object],
    headers: Mapping[str, str],
    *,
    signature_config: GatewaySignatureVerificationConfig | None = None,
    now_ms: int | None = None,
    nonce_store: GatewaySignatureNonceStore | None = None,
    internal_service_token: str | None = None,
) -> dict[str, object]:
    """为 RAG 查询重建受信租户/应用/项目/用户/workspace 作用域。

    RAG 与普通 Agent plan 一样会读取项目知识和历史案例，但它的请求体是一个看起来很普通的 JSON，
    很容易被误认为可以直接信任 ``tenantId``、``projectId`` 或 ``actorId``。该函数把两条合法调用链
    明确分开：

    - 浏览器 -> Gateway -> Python：必须由 Gateway HMAC 证明 Header 快照，正文作用域字段全部丢弃；
    - Java Agent Runtime -> Python：必须携带 Agent Runtime 内部共享凭证，作用域仍只来自 Header；
    - 其他直连：仅在本地未启用可信边界时保留离线单测兼容，生产/Compose 的强制验签配置会拒绝。

    RAG 调整参数、question 和 session/trace 关联可以继续来自正文；身份、范围和授权集合不能来自正文。
    这里不把 trustedControlPlane 快照写回 RAG payload，避免 LangGraph checkpoint 或回答摘要意外保存控制面细节。
    """

    sanitized = dict(payload)
    source_service = _header(headers, "X-DataSmart-Source-Service")
    effective_signature_config = signature_config or gateway_signature_config_from_env()

    if source_service == GATEWAY_SOURCE_SERVICE:
        # RAG 会直接读取向量/案例证据，不能沿用普通 plan 的“签名可选”迁移兼容模式。
        # 是否允许本地无 gateway 的离线调用由下面的 no-source 分支控制；一旦请求声称来自 gateway，
        # 该路径始终进入强制 HMAC 校验，防止只伪造 source-service 就获得跨项目查询能力。
        enriched = enrich_agent_plan_payload_from_gateway_headers(
            {"variables": {}},
            headers,
            signature_config=replace(effective_signature_config, required=True),
            now_ms=now_ms,
            nonce_store=nonce_store,
        )
        trusted = enriched.get("variables", {}).get(TRUSTED_ROOT_KEY, {})
        request_context = trusted.get("requestContext", {}) if isinstance(trusted, Mapping) else {}
        return _apply_trusted_rag_scope(
            sanitized,
            tenant_id=_header(headers, "X-DataSmart-Tenant-Id"),
            application_id=trusted.get("applicationId") if isinstance(trusted, Mapping) else None,
            project_id=request_context.get("projectId") if isinstance(request_context, Mapping) else None,
            actor_id=request_context.get("actorId") if isinstance(request_context, Mapping) else None,
            workspace_key=request_context.get("workspaceId") if isinstance(request_context, Mapping) else None,
            trace_id=request_context.get("traceId") if isinstance(request_context, Mapping) else None,
            authorized_project_ids=request_context.get("authorizedProjectIds")
            if isinstance(request_context, Mapping)
            else (),
            sensitivity_level=_trusted_rag_sensitivity(headers, default="internal"),
        )

    if source_service == AGENT_RUNTIME_SOURCE_SERVICE:
        expected_token = (
            internal_service_token
            or os.getenv("DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN")
            or ""
        ).strip()
        actual_token = (_header(headers, "X-DataSmart-Internal-Service-Token") or "").strip()
        if not expected_token or not actual_token or not hmac.compare_digest(actual_token, expected_token):
            raise PermissionError("agent-runtime internal service token is missing or invalid")
        return _apply_trusted_rag_scope(
            sanitized,
            tenant_id=_header(headers, "X-DataSmart-Tenant-Id"),
            application_id=_header(headers, "X-DataSmart-Application-Id"),
            project_id=_header(headers, "X-DataSmart-Project-Id"),
            actor_id=_header(headers, "X-DataSmart-Actor-Id"),
            workspace_key=_header(headers, "X-DataSmart-Workspace-Id"),
            trace_id=_header(headers, "X-DataSmart-Trace-Id"),
            authorized_project_ids=_csv(_header(headers, "X-DataSmart-Authorized-Project-Ids")),
            sensitivity_level=_trusted_rag_sensitivity(headers, default="internal"),
        )

    if effective_signature_config.required:
        raise GatewaySignatureVerificationError("missing-trusted-source")

    # 仅保留未启用可信边界的离线学习/单测兼容。即使本地没有强制验签，也不能把请求体自报的
    # public 当成可信分级；无受信来源时按最高保护级别处理，外部模型会默认 fail-closed。
    return _apply_untrusted_rag_sensitivity(sanitized)


def _apply_trusted_rag_scope(
    payload: Mapping[str, object],
    *,
    tenant_id: object,
    application_id: object,
    project_id: object,
    actor_id: object,
    workspace_key: object,
    trace_id: object,
    authorized_project_ids: object,
    sensitivity_level: str,
) -> dict[str, object]:
    """把已验真的作用域覆盖到 RAG payload，并执行应用/项目/授权集合完整性校验。"""

    tenant_text = _string_value(tenant_id)
    application_text = _string_value(application_id)
    project_text = _string_value(project_id)
    actor_text = _string_value(actor_id)
    workspace_text = _string_value(workspace_key)
    if not tenant_text or not application_text or not project_text or not actor_text or not workspace_text:
        raise PermissionError("trusted RAG tenant/application/project/actor/workspace context is incomplete")
    if not _positive_id(application_text):
        raise PermissionError("trusted RAG application context is missing or invalid")
    project_scope = tuple(str(item).strip() for item in authorized_project_ids or () if str(item).strip())
    if not project_scope or project_text not in project_scope:
        raise PermissionError("trusted RAG project context is outside authorized project scope")

    sanitized = dict(payload)
    # camelCase/snake_case 两套正文身份字段都清掉，避免调用方通过别名绕过覆盖。
    for key in (
        "tenantId", "tenant_id", "applicationId", "application_id", "projectId", "project_id",
        "actorId", "actor_id", "workspaceKey", "workspace_key", "traceId", "trace_id",
        "sensitivityLevel", "sensitivity_level", "trustedControlPlane", "variables",
    ):
        sanitized.pop(key, None)
    sanitized.update({
        "tenantId": tenant_text,
        "projectId": project_text,
        "actorId": actor_text,
        "workspaceKey": workspace_text,
        "sensitivityLevel": _normalize_trusted_rag_sensitivity(sensitivity_level, default="restricted"),
    })
    if trace_text := _string_value(trace_id):
        sanitized["traceId"] = trace_text
    return sanitized


def _apply_untrusted_rag_sensitivity(payload: Mapping[str, object]) -> dict[str, object]:
    """清除未验真的分级声明，并按 restricted 处理直连正文。"""

    sanitized = dict(payload)
    sanitized.pop("sensitivityLevel", None)
    sanitized.pop("sensitivity_level", None)
    sanitized["sensitivityLevel"] = "restricted"
    return sanitized


def _trusted_rag_sensitivity(headers: Mapping[str, str], *, default: str) -> str:
    """读取已纳入 Gateway HMAC 或内部服务令牌边界的 RAG 分级。"""

    raw_value = _header(headers, RAG_SENSITIVITY_LEVEL_HEADER)
    # 兼容旧 gateway 未发送该 Header 的情况，但不能把“发送了未知值”当成正常 internal。
    # 未知值可能来自版本不一致或恶意构造，统一按 restricted 处理，避免错误配置扩大外发范围。
    if raw_value is None or not str(raw_value).strip():
        return _normalize_trusted_rag_sensitivity(None, default=default)
    return _normalize_trusted_rag_sensitivity(raw_value, default="restricted")


def _normalize_trusted_rag_sensitivity(value: object, *, default: str) -> str:
    """规范化受信分级；未知值降级到更保守的默认级别。"""

    normalized_default = str(default or "restricted").strip().lower()
    if normalized_default not in _RAG_SENSITIVITY_LEVELS:
        normalized_default = "restricted"
    normalized_value = str(value or "").strip().lower()
    return normalized_value if normalized_value in _RAG_SENSITIVITY_LEVELS else normalized_default


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


def _positive_id(value: str | None) -> bool:
    """判断 Gateway 重建的作用域标识是否为正整数，拒绝名称、零值和缺失值。

    该校验只用于可信控制面 ID，不会尝试从 projectId 或请求正文补值。这样应用隔离错误会在模型、工具和
    Specialist 执行之前暴露，而不是等到 Java 事实登记接口返回一个难以关联原请求的 400。
    """

    try:
        return int(str(value or "").strip()) > 0
    except (TypeError, ValueError):
        return False


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
