"""模型 Provider 错误信息低敏化工具。

真实模型 Provider 的错误正文经常包含敏感信息，例如：
- API Key 或鉴权失败原因；
- 内部模型网关 endpoint；
- 上游请求 ID、供应商账号、组织 ID；
- 被代理服务回显的 prompt、工具参数、SQL 或样本片段；
- vLLM/SGLang/LiteLLM 等网关在调试模式下输出的完整异常栈。

模型调用失败时，上层 Agent loop、runtime event、健康台账、前端实时输出和 Java 控制面都只需要知道
“错误类型”和“是否可重试”，不需要拿到原始错误正文。因此本文件把错误正文裁剪为稳定、低敏、可学习的
中文说明，避免为了排障方便把 Provider 私有响应扩散到更多系统。
"""

from __future__ import annotations

from urllib.error import HTTPError


_HTTP_ERROR_HINTS = {
    400: "请求格式不被 Provider 接受，请检查消息结构、tool schema、上下文长度或模型名。",
    401: "Provider 鉴权失败，请检查 API Key、服务账号或网关签名配置。",
    403: "Provider 拒绝访问，请检查模型权限、租户套餐、组织授权或网络访问策略。",
    404: "Provider 路由不存在，请检查 base URL、/v1 路径、模型名或网关路由。",
    408: "Provider 请求超时，请检查上游队列、网络链路或超时配置。",
    409: "Provider 返回资源冲突，请检查并发、幂等或网关状态。",
    413: "请求体过大，请检查上下文长度、工具 schema 数量或附件摘要。",
    429: "Provider 触发限流，请检查 RPM/TPM、租户预算、并发队列或 fallback 路由。",
    500: "Provider 内部错误，请检查模型服务、GPU 资源、网关日志或部署版本。",
    502: "Provider 网关错误，请检查反向代理、服务发现或上游推理服务。",
    503: "Provider 暂不可用，请检查部署健康、GPU 队列、维护窗口或自动扩缩容。",
    504: "Provider 网关超时，请检查推理耗时、队列积压、上下文长度或网络链路。",
}


def safe_http_error_message(exc: HTTPError) -> str:
    """把 HTTPError 转换为低敏说明。

    参数：
    - `exc`：urllib 抛出的 HTTPError。它可能包含完整 URL、response body 和上游异常文本。

    返回：
    - 面向调用方和事件流的低敏中文说明。

    设计原则：
    - 保留 HTTP 状态码，便于路由、告警和排障分类；
    - 不读取 `exc.read()`，避免把 response body 中的 key、endpoint、prompt 或 SQL 带出来；
    - 不返回 `exc.url`，因为 URL query 里可能有 token 或调试参数；
    - 结合常见状态码给出运维方向，让错误信息仍然有学习和排障价值。
    """

    status_code = int(getattr(exc, "code", 0) or 0)
    hint = _HTTP_ERROR_HINTS.get(status_code, "Provider 返回非预期 HTTP 状态，请检查模型网关和上游推理服务。")
    return f"模型 Provider HTTP 调用失败，状态码 {status_code}。{hint}原始响应正文已按低敏策略隐藏。"


def safe_transport_error_message(error_code: str) -> str:
    """把网络、超时或协议错误转换为低敏说明。

    这里不接收原始异常 message，是故意的：URLError/TimeoutError/JSONDecodeError 的字符串有时会包含
    endpoint、代理地址、底层库错误、响应片段或调试文本。稳定错误码已经足够让上层决定健康回写、
    fallback 或提示重试，正文应留在受控日志系统并按部署策略脱敏。
    """

    return {
        "MODEL_PROVIDER_NETWORK_ERROR": "模型 Provider 网络调用失败，请检查 DNS、网络策略、服务地址或代理配置。原始异常正文已按低敏策略隐藏。",
        "MODEL_PROVIDER_TIMEOUT": "模型 Provider 调用超时，请检查上下文长度、GPU 队列、超时配置或 fallback 路由。原始异常正文已按低敏策略隐藏。",
        "MODEL_PROVIDER_STREAM_MALFORMED": "模型 Provider 流式响应格式异常，请检查 OpenAI-compatible SSE 协议兼容性。原始响应片段已按低敏策略隐藏。",
    }.get(error_code, "模型 Provider 调用失败，原始异常正文已按低敏策略隐藏。")
