"""模型 Provider 抽象。

模型 Provider 负责“把统一的模型调用请求转换成某种具体推理服务调用”。它和模型路由是两层：
`ModelRouteRegistry` 决定用哪条路由，Provider 决定如何执行这条路由。这样我们可以同时支持
OpenAI-compatible、vLLM、SGLang、本地 dry-run、未来企业内部模型网关，而不把 HTTP 细节写进
Agent 编排器。
"""

from __future__ import annotations

import os
import time
from typing import Iterator, Protocol

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationChunk,
    ModelInvocationRequest,
    ModelInvocationResult,
    ProviderType,
)
from datasmart_ai_runtime.services.model_gateway.openai_compatible_provider import (
    ModelProviderHttpTransport,
    OpenAICompatibleModelProvider,
    OpenAICompatibleProviderSettings,
)


class ModelProvider(Protocol):
    """模型服务提供者协议。

    使用 Protocol 而不是继承基类，是为了让后续 Provider 可以轻量实现，只要拥有 `invoke`
    方法即可被注册。对于快速接入 vLLM/SGLang/OpenAI-compatible 网关，这种鸭子类型更灵活。
    """

    def invoke(self, request: ModelInvocationRequest) -> ModelInvocationResult:
        """执行一次模型调用，并返回统一结果。"""


class DryRunModelProvider:
    """不真正访问模型的 Provider。

    它用于本地开发、单元测试和契约验证。很多商业系统早期最大的问题不是“模型不够强”，而是
    控制面、审计、路由、上下文、工具契约还没稳定就强行接模型。Dry-run Provider 能让这些
    平台能力先跑通，再替换为真实推理服务。
    """

    def invoke(self, request: ModelInvocationRequest) -> ModelInvocationResult:
        started_at = time.perf_counter()
        user_messages = [message.content for message in request.messages if message.role == "user"]
        summary = user_messages[-1] if user_messages else "无用户消息"
        latency_ms = int((time.perf_counter() - started_at) * 1000)
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content=f"[DRY_RUN] 已选择模型路由 `{request.route.model_name}`，待真实 Provider 接入。输入摘要：{summary}",
            latency_ms=latency_ms,
        )

    def stream(self, request: ModelInvocationRequest) -> Iterator[ModelInvocationChunk]:
        """以单片段形式返回 dry-run 结果。

        dry-run 不需要模拟逐 token 输出，但仍实现 `stream(...)`，是为了让上层 Agent loop 可以统一
        消费流式接口：本地开发走 dry-run，生产环境走真实 SSE，调用方代码不需要分叉。
        """

        result = self.invoke(request)
        yield ModelInvocationChunk(
            provider_name=result.provider_name,
            model_name=result.model_name,
            content_delta=result.content,
            finish_reason="dry_run",
            sequence=1,
            error_code=result.error_code,
        )


class ModelProviderRegistry:
    """按 Provider 类型管理模型调用实现。

    路由表和 Provider 表分离后，可以出现这样的生产配置：
    - 同一个 `OPENAI_COMPATIBLE` Provider 同时承载 Qwen3.5 与 DeepSeek-V3.2；
    - Agent 推理走 vLLM，Rerank 走本地 Python 服务；
    - 灰度租户走 SGLang，普通租户走稳定 vLLM。
    编排器只需要拿 route.provider_type 找到对应 Provider，不需要知道这些部署细节。
    """

    def __init__(self, providers: dict[ProviderType, ModelProvider] | None = None) -> None:
        self._providers = providers or {
            ProviderType.DRY_RUN: DryRunModelProvider(),
            ProviderType.OPENAI_COMPATIBLE: OpenAICompatibleModelProvider(),
        }

    def invoke(self, request: ModelInvocationRequest) -> ModelInvocationResult:
        """按路由中的 Provider 类型执行模型调用。"""

        provider = self._providers.get(request.route.provider_type)
        if provider is None:
            raise ValueError(f"尚未注册模型 Provider：{request.route.provider_type}")
        return provider.invoke(request)

    def stream(self, request: ModelInvocationRequest) -> Iterator[ModelInvocationChunk]:
        """按路由执行流式模型调用。

        如果 Provider 暂未实现原生流式能力，则自动回退为一次性 invoke 结果。这个兼容策略能让
        Agent loop 优先面向 streaming 编程，而不要求所有 Provider 在同一阶段全部升级。
        """

        provider = self._providers.get(request.route.provider_type)
        if provider is None:
            raise ValueError(f"尚未注册模型 Provider：{request.route.provider_type}")
        stream_method = getattr(provider, "stream", None)
        if callable(stream_method):
            yield from stream_method(request)
            return
        result = provider.invoke(request)
        yield ModelInvocationChunk(
            provider_name=result.provider_name,
            model_name=result.model_name,
            content_delta=result.content,
            finish_reason="invoke_fallback",
            sequence=1,
            error_code=result.error_code,
        )


def model_provider_registry_from_env(environ: dict[str, str] | None = None) -> ModelProviderRegistry:
    """根据环境变量创建模型 Provider 注册表。

    该函数是让 Python AI Runtime 从 dry-run 走向真实模型调用的轻量入口。它不直接决定模型路由，
    只负责 Provider 层的认证、重试和通用 Header。典型配置：
    - `DATASMART_AI_OPENAI_COMPATIBLE_API_KEY`
    - `DATASMART_AI_OPENAI_COMPATIBLE_ORGANIZATION`
    - `DATASMART_AI_OPENAI_COMPATIBLE_USER_AGENT`
    - `DATASMART_AI_OPENAI_COMPATIBLE_WIRE_API`
    - `DATASMART_AI_AGENT_REASONING_EFFORT`
    - `DATASMART_AI_OPENAI_COMPATIBLE_STORE_RESPONSE`
    - `DATASMART_AI_OPENAI_COMPATIBLE_TOOL_CALL_MODE`
    - `DATASMART_AI_OPENAI_COMPATIBLE_MAX_RETRIES`
    - `DATASMART_AI_OPENAI_COMPATIBLE_RETRY_BACKOFF_SECONDS`

    API Key 留在环境变量中，而不是写入 `ModelRoute`，是为了避免模型密钥进入 Java 控制面、路由
    诊断或 Git 仓库。
    """

    source = environ if environ is not None else os.environ
    settings = OpenAICompatibleProviderSettings(
        api_key=source.get("DATASMART_AI_OPENAI_COMPATIBLE_API_KEY") or source.get("DATASMART_AI_MODEL_API_KEY"),
        organization=source.get("DATASMART_AI_OPENAI_COMPATIBLE_ORGANIZATION"),
        user_agent=_safe_user_agent(source.get("DATASMART_AI_OPENAI_COMPATIBLE_USER_AGENT")),
        wire_api=_wire_api(source.get("DATASMART_AI_OPENAI_COMPATIBLE_WIRE_API")),
        reasoning_effort=_reasoning_effort(source.get("DATASMART_AI_AGENT_REASONING_EFFORT")),
        store_response=_response_store_enabled(source),
        tool_call_mode=_tool_call_mode(source.get("DATASMART_AI_OPENAI_COMPATIBLE_TOOL_CALL_MODE")),
        max_retries=_non_negative_int(source.get("DATASMART_AI_OPENAI_COMPATIBLE_MAX_RETRIES"), default=1),
        retry_backoff_seconds=_non_negative_float(
            source.get("DATASMART_AI_OPENAI_COMPATIBLE_RETRY_BACKOFF_SECONDS"),
            default=0.05,
        ),
    )
    return ModelProviderRegistry(
        {
            ProviderType.DRY_RUN: DryRunModelProvider(),
            ProviderType.OPENAI_COMPATIBLE: OpenAICompatibleModelProvider(settings),
        }
    )


def _non_negative_int(value: str | None, default: int) -> int:
    """读取非负整数配置，非法或空值回退默认值。"""

    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed >= 0 else default


def _non_negative_float(value: str | None, default: float) -> float:
    """读取非负浮点配置，非法或空值回退默认值。"""

    if value is None or not value.strip():
        return default
    parsed = float(value)
    return parsed if parsed >= 0 else default


def _safe_user_agent(value: str | None) -> str:
    """读取低敏 User-Agent，并阻断换行符造成的 Header 注入。"""

    default = "DataSmart-AI-Runtime/1.0"
    normalized = (value or default).strip()
    if not normalized or "\r" in normalized or "\n" in normalized:
        return default
    return normalized[:160]


def _tool_call_mode(value: str | None) -> str:
    """只允许已实现的工具协议模式，未知值安全回退原生协议。"""

    normalized = str(value or "native").strip().lower()
    return normalized if normalized in {"native", "json_fallback"} else "native"


def _wire_api(value: str | None) -> str:
    """归一化 Provider wire API，未知值回退到兼容面更广的 Chat Completions。"""

    normalized = str(value or "chat_completions").strip().lower().replace("-", "_")
    aliases = {"chat": "chat_completions", "response": "responses"}
    normalized = aliases.get(normalized, normalized)
    return normalized if normalized in {"chat_completions", "responses"} else "chat_completions"


def _reasoning_effort(value: str | None) -> str | None:
    """只透传已知推理强度，防止错误值让所有 Agent 请求在 Provider 侧失败。"""

    if value is None or not value.strip():
        return None
    normalized = value.strip().lower()
    supported = {"none", "minimal", "low", "medium", "high", "xhigh"}
    return normalized if normalized in supported else None


def _response_store_enabled(source: dict[str, str]) -> bool:
    """读取响应存储策略，默认关闭并兼容 Codex 风格的反向开关。

    ``STORE_RESPONSE`` 是 DataSmart 的主配置；``DISABLE_RESPONSE_STORAGE`` 仅用于从现有 Codex
    配置迁移时减少歧义。两者同时出现时主配置优先，避免正反开关互相覆盖。
    """

    explicit = source.get("DATASMART_AI_OPENAI_COMPATIBLE_STORE_RESPONSE")
    if explicit is not None and explicit.strip():
        return _boolean(explicit, default=False)
    disable = source.get("DATASMART_AI_DISABLE_RESPONSE_STORAGE")
    if disable is not None and disable.strip():
        return not _boolean(disable, default=True)
    return False


def _boolean(value: str | None, default: bool) -> bool:
    """解析常见布尔文本；非法值不改变安全默认值。"""

    normalized = str(value or "").strip().lower()
    if normalized in {"true", "1", "yes", "on"}:
        return True
    if normalized in {"false", "0", "no", "off"}:
        return False
    return default
