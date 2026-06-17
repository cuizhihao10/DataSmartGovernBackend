"""工具动作 checkpoint 路由的内部调用安全边界。

checkpoint query/resume-preview 虽然不会真正执行工具，但它们会读取暂停点、恢复事实和状态机决策。
在真实商业化 Agent Host 中，这类接口不能长期保持“谁都可以直连 Python Runtime 访问”的形态，否则会出现：

- 调用方绕过 Java gateway，伪造 tenantId/actorId 查询其他租户 checkpoint；
- 外部 Agent 或脚本重放旧请求，反复探测审批、澄清或 outbox 是否已经齐备；
- 运维审计只能看到一次普通 HTTP 调用，无法判断该调用是否来自可信控制面。

本模块不重新发明认证协议，而是复用 `api.gateway.signature` 已有的 HMAC + timestamp + nonce 机制。
它只负责 checkpoint 路由自己的三件事：

1. 决定本次请求是否必须 fail-closed 验签；
2. 验签通过后用 gateway Header 覆盖请求体中可伪造的租户/操作者上下文；
3. 把低敏安全边界写回响应和 productionReadiness，供前端、Java 控制面、审计事件和测试理解当前保护等级。
"""

from __future__ import annotations

from dataclasses import replace
from typing import Any, Mapping

from datasmart_ai_runtime.api.gateway.signature import (
    GatewaySignatureVerificationConfig,
    GatewaySignatureVerificationError,
    GatewaySignatureVerificationResult,
    gateway_signature_config_from_env,
    verify_gateway_signature,
)
from datasmart_ai_runtime.api.gateway.trusted_context import GATEWAY_SOURCE_SERVICE


CHECKPOINT_GATEWAY_SIGNATURE_ERROR_CODE = "CHECKPOINT_GATEWAY_SIGNATURE_INVALID"
SECURITY_PAYLOAD_POLICY = "LOW_SENSITIVE_CHECKPOINT_SECURITY_CONTEXT_ONLY"


def prepare_checkpoint_payload_for_route_security(
    payload: Mapping[str, Any] | None,
    http_request: Any | None,
    *,
    gateway_signature_required: bool = False,
    nonce_store: Any | None = None,
    signature_config: GatewaySignatureVerificationConfig | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """为 checkpoint 路由执行网关签名校验并返回安全后的 payload。

    参数说明：
    - `payload`：调用方提交的 JSON 请求体。函数会复制它，不会原地修改，避免测试夹具、日志或上游中间件观察到
      被悄悄篡改的对象。
    - `http_request`：FastAPI Request 或测试 fake request。只有 route 层能看到 Header，因此安全处理必须发生
      在 route 层，而不是下沉到纯业务 helper。
    - `gateway_signature_required`：checkpoint 路由自己的 fail-closed 开关。它独立于全局
      `DATASMART_GATEWAY_SIGNATURE_REQUIRED`，用于分阶段把高风险控制面接口先收紧。
    - `nonce_store`：HMAC 验签成功后登记 nonce，避免时间窗口内重放。
    - `signature_config`：测试或未来其他运行载体可以显式注入配置；生产默认从环境变量读取。

    返回值：
    - 第一个元素是已经清理/覆盖身份上下文的 payload；
    - 第二个元素是低敏 `securityBoundary`，可直接附加到响应、事件和 readiness 摘要中。

    关键安全语义：
    - 只要请求声称来自统一 gateway，就必须按当前签名配置校验；配置了 secret 但签名错误时会拒绝；
    - 当 `gateway_signature_required=true` 或全局 required=true 时，缺少 gateway 来源或签名会 fail-closed；
    - 验签通过后，tenantId/actorId/traceId 等控制面字段以 Header 为准，避免请求体伪造；
    - projectId 仍来自业务请求，但如果 Header 提供 authorized projects，则会做一层轻量包含性校验。
    """

    sanitized_payload = _copy_payload_without_forged_trusted_context(payload)
    headers = _request_headers(http_request)
    base_config = signature_config or gateway_signature_config_from_env()
    effective_config = replace(base_config, required=bool(base_config.required or gateway_signature_required))
    source_service = _header(headers, "X-DataSmart-Source-Service")
    should_verify = bool(effective_config.required or source_service == GATEWAY_SOURCE_SERVICE)

    if not should_verify:
        return sanitized_payload, _security_boundary(
            auth_mode="UNSIGNED_LEGACY_COMPATIBLE",
            required=False,
            fail_closed=False,
            source_service=source_service,
            result=GatewaySignatureVerificationResult(valid=True, reason="not-attempted"),
            headers=headers,
            context_applied=False,
        )

    if http_request is None:
        raise GatewaySignatureVerificationError("missing-http-request")
    if source_service != GATEWAY_SOURCE_SERVICE:
        raise GatewaySignatureVerificationError("missing-or-unexpected-source-service")

    result = verify_gateway_signature(headers, effective_config, nonce_store=nonce_store)
    if not result.valid:
        raise GatewaySignatureVerificationError(result.reason)

    enriched_payload = _apply_gateway_context_to_checkpoint_payload(
        sanitized_payload,
        headers,
        enforce_project_authorization=effective_config.required,
    )
    boundary = _security_boundary(
        auth_mode="GATEWAY_HMAC_VERIFIED" if result.reason == "ok" else "GATEWAY_CONTEXT_LEGACY_UNSIGNED",
        required=effective_config.required,
        fail_closed=effective_config.required,
        source_service=source_service,
        result=result,
        headers=headers,
        context_applied=True,
    )
    return enriched_payload, boundary


def attach_checkpoint_security_boundary(
    response: dict[str, Any],
    security_boundary: Mapping[str, Any],
) -> None:
    """把安全边界附加到 checkpoint 响应，并同步修正 productionReadiness。

    这个函数故意放在安全模块而不是业务 helper 中：
    - 纯 helper 仍可被离线测试、脚本或未来内部调用复用，不强依赖 HTTP Header；
    - route 层已经知道本次请求是否验签，因此由 route 层把“本次访问保护等级”写回响应；
    - productionReadiness 是面向产品和运维的解释，不应该让底层 checkpoint store 去理解网关安全策略。
    """

    response["securityBoundary"] = dict(security_boundary)
    production = response.get("productionReadiness")
    if not isinstance(production, dict):
        return

    current_mode = _text(security_boundary.get("authMode")) or "UNKNOWN"
    production["currentAuthorizationMode"] = current_mode
    production["authorizationBoundaryMeaning"] = (
        "checkpoint 路由已在 HTTP 边界记录本次调用的网关签名保护等级。"
        "只有 failClosed=true 且 gatewaySignatureVerified=true 时，才表示该请求已经满足当前阶段的服务间授权要求。"
    )
    if bool(security_boundary.get("failClosed")) and bool(security_boundary.get("gatewaySignatureVerified")):
        production["missingProductionRequirements"] = tuple(
            item
            for item in _sequence(production.get("missingProductionRequirements"))
            if item != "GATEWAY_OR_SERVICE_ACCOUNT_AUTHORIZATION"
        )


def checkpoint_gateway_signature_error_detail(
    http_request: Any | None,
    exc: GatewaySignatureVerificationError,
) -> dict[str, Any]:
    """构造 checkpoint 路由验签失败的低敏 HTTP 错误详情。

    该详情可以安全返回给调用方、写入日志或交给 `GatewaySignatureSecurityStats` 聚合，因为它不包含：
    secret、签名值、签名原文、完整 Header、nonce 原文、payload 正文或工具参数。
    """

    headers = _request_headers(http_request)
    return {
        "code": CHECKPOINT_GATEWAY_SIGNATURE_ERROR_CODE,
        "message": "Checkpoint 控制面接口需要通过统一网关或服务账号签名访问，请确认 HMAC 密钥、时间窗口和 nonce 配置一致。",
        "reason": exc.reason,
        "traceId": _header(headers, "X-DataSmart-Trace-Id"),
        "sourceService": _header(headers, "X-DataSmart-Source-Service"),
        "path": _request_path(http_request, headers),
        "routeFamily": "tool-action-checkpoint",
    }


def _copy_payload_without_forged_trusted_context(payload: Mapping[str, Any] | None) -> dict[str, Any]:
    """复制请求体并删除调用方伪造的 trustedControlPlane。

    checkpoint API 目前只把 `tenantId/projectId/actorId/context` 当作预览级过滤字段；如果未来调用方在 body
    里塞入 `trustedControlPlane`，这里会统一删除，确保可信控制面事实只能由 gateway Header 重建。
    """

    data = dict(payload) if isinstance(payload, Mapping) else {}
    data.pop("trustedControlPlane", None)
    context = data.get("context")
    if isinstance(context, Mapping):
        copied_context = dict(context)
        copied_context.pop("trustedControlPlane", None)
        data["context"] = copied_context
    return data


def _apply_gateway_context_to_checkpoint_payload(
    payload: dict[str, Any],
    headers: Mapping[str, Any],
    *,
    enforce_project_authorization: bool,
) -> dict[str, Any]:
    """用验签后的 gateway Header 覆盖 checkpoint 查询上下文。

    覆盖规则：
    - tenantId 与 actorId 是身份事实，一旦 Header 存在就覆盖请求体；
    - traceId/requestId 用于事件和审计关联，不参与 checkpoint 查询过滤；
    - projectId 仍允许由调用方指定，因为一个操作者可能授权多个项目，但 required 模式下会校验它是否属于
      Header 中的 authorizedProjectIds。
    """

    context = dict(payload.get("context")) if isinstance(payload.get("context"), Mapping) else {}
    tenant_id = _header(headers, "X-DataSmart-Tenant-Id")
    actor_id = _header(headers, "X-DataSmart-Actor-Id")
    trace_id = _header(headers, "X-DataSmart-Trace-Id")
    requested_project_id = _first_text((payload, context), "projectId", "project_id")
    authorized_project_ids = _csv(_header(headers, "X-DataSmart-Authorized-Project-Ids"))

    if enforce_project_authorization and requested_project_id and authorized_project_ids:
        if requested_project_id not in authorized_project_ids:
            raise GatewaySignatureVerificationError("project-not-authorized")

    if tenant_id:
        payload["tenantId"] = tenant_id
        context["tenantId"] = tenant_id
    if actor_id:
        payload["actorId"] = actor_id
        context["actorId"] = actor_id
    if trace_id:
        context.setdefault("requestId", trace_id)
        context["traceId"] = trace_id

    payload["context"] = context
    payload["trustedControlPlane"] = {
        "sourceService": _header(headers, "X-DataSmart-Source-Service"),
        "traceId": trace_id,
        "tenantId": tenant_id,
        "actorId": actor_id,
        "actorRole": _header(headers, "X-DataSmart-Actor-Role"),
        "actorType": _header(headers, "X-DataSmart-Actor-Type"),
        "workspaceId": _header(headers, "X-DataSmart-Workspace-Id"),
        "dataScopeLevel": _header(headers, "X-DataSmart-Data-Scope-Level"),
        "authorizedProjectCount": len(authorized_project_ids),
        "payloadPolicy": SECURITY_PAYLOAD_POLICY,
    }
    return payload


def _security_boundary(
    *,
    auth_mode: str,
    required: bool,
    fail_closed: bool,
    source_service: str | None,
    result: GatewaySignatureVerificationResult,
    headers: Mapping[str, Any],
    context_applied: bool,
) -> dict[str, Any]:
    """生成可返回给调用方和 runtime event 的低敏安全边界摘要。"""

    return {
        "payloadPolicy": SECURITY_PAYLOAD_POLICY,
        "authMode": auth_mode,
        "gatewaySignatureRequired": bool(required),
        "gatewaySignatureVerified": bool(result.valid and result.reason == "ok"),
        "verificationResult": result.reason,
        "failClosed": bool(fail_closed),
        "sourceService": source_service,
        "traceId": _header(headers, "X-DataSmart-Trace-Id"),
        "tenantContextApplied": bool(context_applied and _header(headers, "X-DataSmart-Tenant-Id")),
        "actorContextApplied": bool(context_applied and _header(headers, "X-DataSmart-Actor-Id")),
        "authorizedProjectCount": len(_csv(_header(headers, "X-DataSmart-Authorized-Project-Ids"))),
        "meaning": (
            "该摘要只说明 checkpoint 控制面接口的服务间调用保护等级，"
            "不会返回 HMAC secret、签名值、nonce、完整 Header 或 checkpoint 正文。"
        ),
    }


def _request_headers(http_request: Any | None) -> Mapping[str, Any]:
    """读取 Request.headers，兼容测试 fake request。"""

    headers = getattr(http_request, "headers", None)
    return headers if isinstance(headers, Mapping) or hasattr(headers, "items") else {}


def _request_path(http_request: Any | None, headers: Mapping[str, Any]) -> str | None:
    """尽可能提取触发安全失败的路由路径。"""

    url = getattr(http_request, "url", None)
    path = getattr(url, "path", None)
    if path:
        return str(path)
    return _header(headers, "X-Gateway-Original-Path")


def _header(headers: Mapping[str, Any], name: str) -> str | None:
    """大小写不敏感读取 Header。"""

    try:
        value = headers.get(name) or headers.get(name.lower())
    except AttributeError:
        return None
    if value is None and hasattr(headers, "items"):
        lowered_name = name.lower()
        value = next((item for key, item in headers.items() if str(key).lower() == lowered_name), None)
    text = str(value).strip() if value is not None else ""
    return text or None


def _csv(value: str | None) -> tuple[str, ...]:
    """读取逗号分隔的低敏 ID 列表。"""

    if not value:
        return ()
    return tuple(item.strip() for item in value.split(",") if item.strip())


def _first_text(contexts: tuple[Mapping[str, Any], ...], *keys: str) -> str | None:
    """从多个上下文中读取第一个非空文本字段。"""

    for context in contexts:
        for key in keys:
            text = _text(context.get(key))
            if text:
                return text
    return None


def _sequence(value: Any) -> tuple[Any, ...]:
    """把列表/元组统一收敛为 tuple。"""

    if isinstance(value, (list, tuple)):
        return tuple(value)
    return ()


def _text(value: Any) -> str | None:
    """把可选字段转换成非空文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None
