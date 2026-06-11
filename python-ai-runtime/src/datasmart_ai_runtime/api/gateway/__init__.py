"""智能网关 HTTP 安全与治理适配包。

这里集中放置 Gateway HMAC 签名、nonce 去重、安全诊断、可信控制面上下文重建和智能网关治理摘要。
它们都位于 HTTP 边界，负责把网关 Header/运行时事件转换成低敏可解释事实，而不直接执行 Agent 工具。
"""

from datasmart_ai_runtime.api.gateway.intelligent_gateway import build_intelligent_gateway_governance_response
from datasmart_ai_runtime.api.gateway.security import (
    GatewaySignatureSecurityStats,
    InMemoryGatewaySignatureNonceStore,
    build_gateway_signature_nonce_store,
    gateway_signature_nonce_store_settings_from_env,
    gateway_signature_security_diagnostics,
)
from datasmart_ai_runtime.api.gateway.signature import (
    GATEWAY_SIGNATURE,
    GATEWAY_SIGNATURE_KEY_ID,
    GATEWAY_SIGNATURE_NONCE,
    GATEWAY_SIGNATURE_TIMESTAMP,
    GATEWAY_SIGNATURE_VERSION,
    GatewaySignatureNonceStore,
    GatewaySignatureVerificationConfig,
    GatewaySignatureVerificationError,
    GatewaySignatureVerificationResult,
    canonical_payload,
    ensure_gateway_signature,
    gateway_signature_config_from_env,
    sign_gateway_payload,
    verify_gateway_signature,
)
from datasmart_ai_runtime.api.gateway.trusted_context import enrich_agent_plan_payload_from_gateway_headers

__all__ = [
    "GatewaySignatureNonceStore",
    "GatewaySignatureSecurityStats",
    "GatewaySignatureVerificationConfig",
    "GatewaySignatureVerificationError",
    "GatewaySignatureVerificationResult",
    "GATEWAY_SIGNATURE",
    "GATEWAY_SIGNATURE_KEY_ID",
    "GATEWAY_SIGNATURE_NONCE",
    "GATEWAY_SIGNATURE_TIMESTAMP",
    "GATEWAY_SIGNATURE_VERSION",
    "InMemoryGatewaySignatureNonceStore",
    "build_gateway_signature_nonce_store",
    "build_intelligent_gateway_governance_response",
    "canonical_payload",
    "enrich_agent_plan_payload_from_gateway_headers",
    "ensure_gateway_signature",
    "gateway_signature_config_from_env",
    "gateway_signature_nonce_store_settings_from_env",
    "gateway_signature_security_diagnostics",
    "sign_gateway_payload",
    "verify_gateway_signature",
]
