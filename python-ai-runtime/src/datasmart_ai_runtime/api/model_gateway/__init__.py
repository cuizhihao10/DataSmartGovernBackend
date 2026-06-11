"""模型网关 HTTP 响应适配包。

模型 provider、健康注册表、路由策略和调用实现位于 `services.model_gateway`。
本包只负责把模型网关决策转换成 HTTP/API 可返回的低敏治理摘要，避免 API 层泄露 prompt、密钥或模型正文。
"""

from datasmart_ai_runtime.api.model_gateway.governance import build_model_gateway_governance_response

__all__ = ["build_model_gateway_governance_response"]
