"""模型查询引擎的限流与结果缓存组件。

本文件从 `model_query_engine.py` 拆出，是为了让主查询引擎保持在 500 行以内，也让限流、缓存后续可以
独立替换为 Redis、API Gateway、Envoy、LiteLLM 或企业模型网关实现。
"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass
from typing import Callable

from datasmart_ai_runtime.domain.contracts import ModelInvocationResult, ModelMessage


@dataclass(frozen=True)
class ModelQueryRateLimitPolicy:
    """模型查询限流策略。

    字段说明：
    - `max_requests_per_window`：同一个限流 key 在一个窗口内允许的最大请求数。当前是本地内存实现，生产多实例
      应替换为 Redis、API Gateway、Envoy 或模型网关内置限流；
    - `window_seconds`：固定窗口长度。固定窗口足以保护当前同步 Agent 调用，未来高并发可以替换为滑动窗口
      或令牌桶；
    - `enabled`：是否启用限流。测试和离线任务可以关闭，但生产在线会话不建议关闭。
    """

    max_requests_per_window: int = 60
    window_seconds: int = 60
    enabled: bool = True


@dataclass(frozen=True)
class ModelQueryRateLimitDecision:
    """一次模型查询限流判断结果。

    这里只返回窗口、剩余额度和 retryAfter，不返回租户套餐、内部公式或全局配额，避免把商业计费和限流策略
    暴露给普通 API。
    """

    allowed: bool
    key: str
    remaining: int
    retry_after_seconds: int = 0

    def to_summary(self) -> dict[str, object]:
        """转换为低敏摘要，供事件、测试和诊断复用。"""

        return {
            "allowed": self.allowed,
            "keyDigest": _digest_text(self.key),
            "remaining": self.remaining,
            "retryAfterSeconds": self.retry_after_seconds,
        }


class InMemoryModelQueryRateLimiter:
    """进程内固定窗口限流器。

    该实现只用于本地学习、单实例开发和单元测试。生产多实例时必须替换为 Redis、云网关或服务网格限流，
    否则每个 Python 进程都会有自己的计数窗口，无法形成全局配额保护。
    """

    def __init__(
        self,
        policy: ModelQueryRateLimitPolicy | None = None,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self._policy = policy or ModelQueryRateLimitPolicy()
        self._clock = clock or time.time
        self._windows: dict[str, tuple[int, int]] = {}

    def check(self, key: str) -> ModelQueryRateLimitDecision:
        """检查并占用一次限流额度。

        固定窗口逻辑：
        1. 根据当前时间计算窗口编号；
        2. 如果 key 首次出现或窗口已切换，则从 0 重新计数；
        3. 达到上限时返回 denied，但不暴露内部业务配额公式。
        """

        if not self._policy.enabled:
            return ModelQueryRateLimitDecision(allowed=True, key=key, remaining=self._policy.max_requests_per_window)
        window_seconds = max(self._policy.window_seconds, 1)
        window_id = int(self._clock() // window_seconds)
        _, current_count = self._windows.get(key, (window_id, 0))
        if self._windows.get(key, (None, None))[0] != window_id:
            current_count = 0
        if current_count >= self._policy.max_requests_per_window:
            retry_after = int(((window_id + 1) * window_seconds) - self._clock())
            return ModelQueryRateLimitDecision(
                allowed=False,
                key=key,
                remaining=0,
                retry_after_seconds=max(retry_after, 1),
            )
        current_count += 1
        self._windows[key] = (window_id, current_count)
        return ModelQueryRateLimitDecision(
            allowed=True,
            key=key,
            remaining=max(self._policy.max_requests_per_window - current_count, 0),
        )


@dataclass(frozen=True)
class _ModelQueryCacheEntry:
    """模型结果缓存条目。

    该缓存是进程内优化，不是审计事实源。它保存完整 `ModelInvocationResult`，因此只允许在会话级隔离边界内
    使用，并且不会把缓存内容写入事件、日志或诊断响应。
    """

    result: ModelInvocationResult
    expires_at: float


class InMemoryModelQueryResultCache:
    """会话级模型结果缓存。

    这里做的是应用侧 completion/result cache，而不是 vLLM/SGLang 的底层 KV cache。由于 completion 可能
    包含用户目标、工具反馈摘要或模型生成的业务建议，所以默认只接受 `SESSION_ONLY` cache plan，并且 key
    只使用哈希，不暴露 prompt 明文。
    """

    def __init__(self, clock: Callable[[], float] | None = None) -> None:
        self._clock = clock or time.time
        self._entries: dict[str, _ModelQueryCacheEntry] = {}

    def get(self, key: str) -> ModelInvocationResult | None:
        """按哈希 key 读取缓存结果，过期条目会被懒清理。"""

        entry = self._entries.get(key)
        if entry is None:
            return None
        if entry.expires_at <= self._clock():
            self._entries.pop(key, None)
            return None
        return entry.result

    def put(self, key: str, result: ModelInvocationResult, ttl_seconds: int) -> None:
        """写入缓存。

        失败结果不缓存，避免把短暂 Provider 故障固化到同一会话后续请求中。
        """

        if ttl_seconds <= 0 or result.error_code is not None:
            return
        self._entries[key] = _ModelQueryCacheEntry(
            result=result,
            expires_at=self._clock() + ttl_seconds,
        )


def _digest_text(value: object) -> str:
    """生成低敏稳定摘要。"""

    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()


def estimate_prompt_tokens(messages: tuple[ModelMessage, ...]) -> int:
    """粗略估算 prompt token 数。

    估算规则采用“4 个字符约等于 1 token”的保守近似。它不能替代模型 tokenizer，但足以在没有真实 Provider
    的本地闭环中防止明显超长请求进入模型服务。
    """

    total_chars = sum(len(message.content or "") for message in messages)
    return max((total_chars + 3) // 4, 1)


def message_digest_payload(message: ModelMessage) -> dict[str, object]:
    """把模型消息转换为仅供哈希使用的结构。

    该结构会在内存中参与 SHA-256 计算，但不会被返回到事件或日志。tool_calls 只保留 id/type/name 和参数
    digest，避免参数明文成为缓存诊断面。
    """

    return {
        "role": message.role,
        "contentDigest": _digest_text(message.content),
        "toolCallId": message.tool_call_id,
        "name": message.name,
        "toolCalls": tuple(
            {
                "callId": item.call_id,
                "type": item.type,
                "name": item.name,
                "argumentsDigest": _digest_text(item.arguments),
            }
            for item in message.tool_calls
        ),
    }
