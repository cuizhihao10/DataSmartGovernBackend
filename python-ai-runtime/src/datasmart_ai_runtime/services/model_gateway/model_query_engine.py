"""模型查询引擎。

模型网关治理服务已经负责“选哪条路由”，Provider 注册表已经负责“怎么调用具体模型服务”。本文件补上
中间缺失的一层：把一次模型调用按商业化 Agent Host 的要求做成可治理查询流程。

为什么需要单独的 Query Engine：
- Agent 主链不能直接调用 Provider，否则 retry、fallback、rate-limit、token-limit、cache 和健康回写会散落
  在多个节点里，后续很难审计和优化；
- OpenAI-compatible、vLLM、SGLang、LiteLLM 或企业内部模型网关都可能返回不同错误形态，Query Engine 负责
  把这些差异收敛成 DataSmart 内部稳定语义；
- 项目明确不做自研推理内核、微调或后训练，因此这里的“推理优化”只做成熟推理服务接入侧治理：安全缓存
  边界、请求级限流、Provider fallback、token 预算闸门和低敏执行摘要。
"""

from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass, replace
from typing import Callable

from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelInvocationRequest,
    ModelInvocationResult,
    ModelRoute,
)
from datasmart_ai_runtime.domain.model_gateway import (
    ModelGatewayRequestContext,
    ModelGatewayRoutingDecision,
)
from datasmart_ai_runtime.services.model_gateway.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_query_engine_components import (
    InMemoryModelQueryRateLimiter,
    InMemoryModelQueryResultCache,
    ModelQueryRateLimitPolicy,
    estimate_prompt_tokens,
    message_digest_payload,
)


@dataclass(frozen=True)
class ModelQueryEngineSettings:
    """模型查询引擎运行设置。

    字段说明：
    - `max_attempts_per_route`：同一 Provider 路由最多尝试次数。Provider 自身可能已经有 HTTP retry，这里是
      Agent 查询层的保守兜底，主要覆盖测试桩、企业网关异常和 Provider 抽象层异常；
    - `enable_result_cache`：是否允许 Query Engine 使用进程内结果缓存。该缓存只在 `SESSION_ONLY` cache plan
      下启用，避免把用户目标或工具反馈总结跨用户/项目复用；
    - `rate_limit_policy`：请求级限流策略；
    - `default_result_cache_ttl_seconds`：当 cachePlan 未提供 TTL 时的兜底 TTL。
    """

    max_attempts_per_route: int = 1
    enable_result_cache: bool = True
    rate_limit_policy: ModelQueryRateLimitPolicy = ModelQueryRateLimitPolicy()
    default_result_cache_ttl_seconds: int = 120

@dataclass(frozen=True)
class ModelQueryAttemptSummary:
    """单次 Provider 尝试的低敏摘要。

    该对象用于解释 retry/fallback 过程，不保存 prompt、请求正文、工具参数、模型输出或上游错误 body。
    """

    provider_name: str | None
    model_name: str | None
    attempt_number: int
    outcome: str
    error_code: str | None = None
    cache_hit: bool = False
    rate_limited: bool = False
    token_limited: bool = False
    latency_ms: int = 0

    def to_summary(self) -> dict[str, object]:
        """输出给 runtime event 或治理响应的低敏字段。"""

        return {
            "providerName": self.provider_name,
            "modelName": self.model_name,
            "attemptNumber": self.attempt_number,
            "outcome": self.outcome,
            "errorCode": self.error_code,
            "cacheHit": self.cache_hit,
            "rateLimited": self.rate_limited,
            "tokenLimited": self.token_limited,
            "latencyMs": self.latency_ms,
        }


@dataclass(frozen=True)
class ModelQueryEngineResult:
    """模型查询引擎执行结果。

    `result` 是给调用方继续处理的真实模型响应；`attempts` 和布尔字段是低敏治理摘要。调用方如果要写
    runtime event，应写 `to_summary()`，不要把 `result.content` 或 `result.tool_calls.arguments` 放进事件。
    """

    result: ModelInvocationResult
    attempts: tuple[ModelQueryAttemptSummary, ...]
    fallback_used: bool = False
    cache_hit: bool = False
    rate_limited: bool = False
    token_limited: bool = False
    selected_provider_name: str | None = None
    selected_model_name: str | None = None

    def to_summary(self) -> dict[str, object]:
        """返回低敏查询摘要。

        注意：这里故意不返回模型输出 content、tool_calls、prompt、messages、工具参数或 endpoint，只返回
        计数、状态和稳定错误码，用于 Agent 事件、管理台和测试断言。
        """

        provider_invoked = any(
            attempt.outcome in {"succeeded", "provider_error"}
            for attempt in self.attempts
        )
        provider_succeeded = provider_invoked and self.result.error_code is None
        prompt_tokens = self.result.prompt_tokens
        completion_tokens = self.result.completion_tokens
        total_tokens = (
            prompt_tokens + completion_tokens
            if prompt_tokens is not None and completion_tokens is not None
            else None
        )
        return {
            "schemaVersion": "datasmart.model-query-engine.v1",
            "payloadPolicy": "LOW_SENSITIVE_QUERY_GOVERNANCE_ONLY",
            "selectedProviderName": self.selected_provider_name,
            "selectedModelName": self.selected_model_name,
            "providerInvoked": provider_invoked,
            "providerSucceeded": provider_succeeded,
            "fallbackUsed": self.fallback_used,
            "cacheHit": self.cache_hit,
            "rateLimited": self.rate_limited,
            "tokenLimited": self.token_limited,
            "resultErrorCode": self.result.error_code,
            "latencyMs": self.result.latency_ms,
            "promptTokens": prompt_tokens,
            "completionTokens": completion_tokens,
            "totalTokens": total_tokens,
            "toolCallCount": len(self.result.tool_calls),
            "attemptCount": len(self.attempts),
            "attempts": tuple(attempt.to_summary() for attempt in self.attempts),
        }

class ModelQueryEngine:
    """可治理模型调用入口。

    调用顺序：
    1. 根据已完成的模型网关决策确定候选路由；
    2. 对每条候选做 token-limit 和 rate-limit 预检；
    3. 如果安全会话缓存命中，直接返回缓存结果；
    4. 调用 Provider，捕获异常并转换为低敏错误；
    5. 把每次 Provider 结果回写给模型网关，以更新 usage 与 Provider health；
    6. 主路由失败且允许 fallback 时尝试下一个候选。
    """

    def __init__(
        self,
        *,
        model_gateway: ModelGatewayGovernanceService,
        model_providers: ModelProviderRegistry,
        settings: ModelQueryEngineSettings | None = None,
        rate_limiter: InMemoryModelQueryRateLimiter | None = None,
        result_cache: InMemoryModelQueryResultCache | None = None,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self._model_gateway = model_gateway
        self._model_providers = model_providers
        self._settings = settings or ModelQueryEngineSettings()
        self._clock = clock or time.time
        self._rate_limiter = rate_limiter or InMemoryModelQueryRateLimiter(self._settings.rate_limit_policy, self._clock)
        self._result_cache = result_cache or InMemoryModelQueryResultCache(self._clock)

    def invoke(
        self,
        request: ModelInvocationRequest,
        *,
        context: ModelGatewayRequestContext,
        routing_decision: ModelGatewayRoutingDecision | None = None,
    ) -> ModelQueryEngineResult:
        """执行一次受治理模型调用。

        `routing_decision` 通常来自 `ModelGatewayGovernanceService.decide(...)`。如果调用方没有传入，Query
        Engine 仍可直接执行 `request.route`，但不会额外扩展 fallback 候选。
        """

        effective_decision = routing_decision or self._model_gateway.decide(context)
        if not effective_decision.budget_decision.allowed:
            result = _blocked_result(
                None,
                "MODEL_QUERY_BUDGET_BLOCKED",
                "模型预算策略已阻断本次查询。",
            )
            return ModelQueryEngineResult(
                result=result,
                attempts=(
                    ModelQueryAttemptSummary(
                        provider_name=None,
                        model_name=None,
                        attempt_number=1,
                        outcome="budget_blocked",
                        error_code=result.error_code,
                    ),
                ),
            )
        routes = self._candidate_routes(request.route, effective_decision, allow_fallback=context.allow_fallback)
        if not routes:
            result = _blocked_result(None, "MODEL_QUERY_ROUTE_UNAVAILABLE", "模型查询引擎没有可用路由。")
            return ModelQueryEngineResult(
                result=result,
                attempts=(
                    ModelQueryAttemptSummary(
                        provider_name=None,
                        model_name=None,
                        attempt_number=1,
                        outcome="route_unavailable",
                        error_code=result.error_code,
                    ),
                ),
            )

        attempts: list[ModelQueryAttemptSummary] = []
        last_result: ModelInvocationResult | None = None
        selected_provider_name: str | None = None
        selected_model_name: str | None = None
        for route_index, route in enumerate(routes):
            routed_request = self._request_for_route(request, route)
            token_issue = self._token_limit_issue(routed_request, context)
            if token_issue:
                last_result = _blocked_result(route, "MODEL_QUERY_TOKEN_LIMIT_EXCEEDED", token_issue)
                attempts.append(
                    ModelQueryAttemptSummary(
                        provider_name=route.provider_name,
                        model_name=route.model_name,
                        attempt_number=1,
                        outcome="token_limited",
                        error_code=last_result.error_code,
                        token_limited=True,
                    )
                )
                if not context.allow_fallback:
                    break
                continue

            rate_limit = self._rate_limiter.check(self._rate_limit_key(context, route))
            if not rate_limit.allowed:
                last_result = _blocked_result(route, "MODEL_QUERY_RATE_LIMITED", "模型查询已触发限流，请稍后重试。")
                attempts.append(
                    ModelQueryAttemptSummary(
                        provider_name=route.provider_name,
                        model_name=route.model_name,
                        attempt_number=1,
                        outcome="rate_limited",
                        error_code=last_result.error_code,
                        rate_limited=True,
                    )
                )
                if not context.allow_fallback:
                    break
                continue

            cache_key = self._cache_key(routed_request)
            if cache_key:
                cached_result = self._result_cache.get(cache_key)
                if cached_result is not None:
                    attempts.append(
                        ModelQueryAttemptSummary(
                            provider_name=route.provider_name,
                            model_name=route.model_name,
                            attempt_number=1,
                            outcome="cache_hit",
                            cache_hit=True,
                        )
                    )
                    return ModelQueryEngineResult(
                        result=cached_result,
                        attempts=tuple(attempts),
                        fallback_used=route_index > 0,
                        cache_hit=True,
                        selected_provider_name=route.provider_name,
                        selected_model_name=route.model_name,
                    )

            max_attempts = max(self._settings.max_attempts_per_route, 1)
            for attempt_number in range(1, max_attempts + 1):
                started_at = self._clock()
                result = self._invoke_provider_safely(routed_request)
                latency_ms = int((self._clock() - started_at) * 1000)
                self._model_gateway.record_invocation_result(context, result)
                last_result = result
                selected_provider_name = route.provider_name
                selected_model_name = route.model_name
                attempts.append(
                    ModelQueryAttemptSummary(
                        provider_name=route.provider_name,
                        model_name=route.model_name,
                        attempt_number=attempt_number,
                        outcome="provider_error" if result.error_code else "succeeded",
                        error_code=result.error_code,
                        latency_ms=latency_ms,
                    )
                )
                if result.error_code is None:
                    if cache_key:
                        self._result_cache.put(cache_key, result, self._cache_ttl_seconds(routed_request))
                    return ModelQueryEngineResult(
                        result=result,
                        attempts=tuple(attempts),
                        fallback_used=route_index > 0,
                        selected_provider_name=route.provider_name,
                        selected_model_name=route.model_name,
                    )
                if attempt_number < max_attempts and self._is_retryable_error(result.error_code):
                    continue
                break

        if last_result is None:
            last_result = _blocked_result(None, "MODEL_QUERY_ROUTE_UNAVAILABLE", "模型查询引擎没有得到任何执行结果。")
        return ModelQueryEngineResult(
            result=last_result,
            attempts=tuple(attempts),
            fallback_used=bool(selected_provider_name and selected_provider_name != request.route.provider_name),
            rate_limited=any(attempt.rate_limited for attempt in attempts),
            token_limited=any(attempt.token_limited for attempt in attempts),
            selected_provider_name=selected_provider_name,
            selected_model_name=selected_model_name,
        )

    @staticmethod
    def _candidate_routes(
        primary_route: ModelRoute,
        routing_decision: ModelGatewayRoutingDecision | None,
        *,
        allow_fallback: bool,
    ) -> tuple[ModelRoute, ...]:
        """根据路由决策生成 Query Engine 实际尝试顺序。"""

        routes: list[ModelRoute] = []
        if routing_decision and routing_decision.selected_route is not None:
            routes.append(routing_decision.selected_route)
        else:
            routes.append(primary_route)
        if allow_fallback and routing_decision is not None:
            for route in routing_decision.candidate_routes:
                if all(route.provider_name != existing.provider_name for existing in routes):
                    routes.append(route)
        return tuple(routes)

    @staticmethod
    def _request_for_route(request: ModelInvocationRequest, route: ModelRoute) -> ModelInvocationRequest:
        """把原始模型请求切换到当前尝试路由。

        如果 fallback 路由与原始路由不同，Provider metadata 中的 `cachePlan.keyPrefix` 可能仍然指向主路由。
        为避免备用 Provider 使用错误的 prefix/KV cache 标签，这里保守禁用 fallback 请求的 Provider 侧缓存计划。
        后续如果 Query Engine 接入完整 route-specific cache planner，可以在这里重新生成备用路由 cachePlan。
        """

        if route.provider_name == request.route.provider_name and route.model_name == request.route.model_name:
            return replace(request, route=route)
        metadata = dict(request.provider_metadata)
        cache_plan = metadata.get("cachePlan")
        if isinstance(cache_plan, dict):
            fallback_cache_plan = dict(cache_plan)
            fallback_cache_plan["enabled"] = False
            fallback_cache_plan["issues"] = tuple(cache_plan.get("issues") or ()) + (
                "FALLBACK_ROUTE_CACHE_PLAN_RECOMPUTE_REQUIRED",
            )
            metadata["cachePlan"] = fallback_cache_plan
        return replace(request, route=route, provider_metadata=metadata)

    def _invoke_provider_safely(self, request: ModelInvocationRequest) -> ModelInvocationResult:
        """调用 Provider，并把异常转换为低敏错误结果。"""

        try:
            return self._model_providers.invoke(request)
        except Exception:  # pragma: no cover - 单测覆盖结果语义，真实异常类型由 Provider/transport 决定
            return ModelInvocationResult(
                provider_name=request.route.provider_name,
                model_name=request.route.model_name,
                content="[MODEL_QUERY_ERROR] 模型 Provider 调用异常，原始异常已按低敏策略隐藏。",
                error_code="MODEL_QUERY_PROVIDER_EXCEPTION",
            )

    @staticmethod
    def _token_limit_issue(request: ModelInvocationRequest, context: ModelGatewayRequestContext) -> str | None:
        """执行模型上下文 token 上限预检。

        这里使用估算值而不是精确 tokenizer，是为了保持 Provider-neutral。真实生产可在 provider adapter 中接入
        Qwen/DeepSeek/GLM 对应 tokenizer 或模型网关返回的精确 token 统计。
        """

        prompt_tokens = context.estimated_prompt_tokens or estimate_prompt_tokens(request.messages)
        completion_tokens = max(context.estimated_completion_tokens or request.max_output_tokens, 0)
        estimated_total = prompt_tokens + completion_tokens
        if estimated_total <= request.route.max_context_tokens:
            return None
        return (
            "模型请求超过路由上下文上限，已在调用前阻断。"
            f"estimatedTotal={estimated_total}, maxContextTokens={request.route.max_context_tokens}"
        )

    @staticmethod
    def _rate_limit_key(context: ModelGatewayRequestContext, route: ModelRoute) -> str:
        """生成请求级限流 key。

        key 绑定租户、项目、工作负载和 Provider。这样同一租户的不同项目、不同模型 Provider 能够分别限流；
        生产中也可以把该 key 映射到套餐、角色、workspace 风险或实时队列 backlog。
        """

        return "|".join(
            (
                context.tenant_id,
                context.project_id or "*",
                context.workload.value,
                route.provider_name,
            )
        )

    def _cache_key(self, request: ModelInvocationRequest) -> str | None:
        """为可缓存请求生成哈希 key。

        安全约束：
        - 只有 `SESSION_ONLY` cache plan 才允许缓存完整模型结果；
        - key 中只保存 SHA-256 哈希，不返回、不记录 prompt 明文；
        - available tools、tool_choice、温度、输出上限和消息内容都会进入哈希，避免不同请求误命中。
        """

        if not self._settings.enable_result_cache:
            return None
        cache_plan = request.provider_metadata.get("cachePlan") if request.provider_metadata else None
        if not isinstance(cache_plan, dict) or not cache_plan.get("enabled"):
            return None
        if cache_plan.get("scope") != ModelCacheKeyScope.SESSION_ONLY.value:
            return None
        namespace = str(cache_plan.get("namespace") or "").strip()
        if not namespace:
            return None
        digest_payload = {
            "namespace": namespace,
            "provider": request.route.provider_name,
            "model": request.route.model_name,
            "temperature": request.temperature,
            "maxOutputTokens": request.max_output_tokens,
            "toolChoice": request.tool_choice,
            "strictToolSchema": request.strict_tool_schema,
            "toolNames": tuple(tool.name for tool in request.available_tools),
            "messages": tuple(message_digest_payload(message) for message in request.messages),
        }
        digest = hashlib.sha256(json.dumps(digest_payload, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()
        return f"{namespace}:completion:{digest}"

    def _cache_ttl_seconds(self, request: ModelInvocationRequest) -> int:
        """读取当前请求的结果缓存 TTL。"""

        cache_plan = request.provider_metadata.get("cachePlan") if request.provider_metadata else None
        if isinstance(cache_plan, dict):
            value = cache_plan.get("ttlSeconds")
            try:
                parsed = int(value)
                return parsed if parsed > 0 else self._settings.default_result_cache_ttl_seconds
            except (TypeError, ValueError):
                return self._settings.default_result_cache_ttl_seconds
        return self._settings.default_result_cache_ttl_seconds

    @staticmethod
    def _is_retryable_error(error_code: str | None) -> bool:
        """判断错误是否适合在 Query Engine 层重试。"""

        if not error_code:
            return False
        retryable_markers = ("HTTP_429", "HTTP_500", "HTTP_502", "HTTP_503", "HTTP_504", "TIMEOUT", "NETWORK")
        return error_code == "MODEL_QUERY_PROVIDER_EXCEPTION" or any(marker in error_code for marker in retryable_markers)


def _blocked_result(route: ModelRoute | None, error_code: str, message: str) -> ModelInvocationResult:
    """生成模型查询阻断结果。"""

    return ModelInvocationResult(
        provider_name=route.provider_name if route else "",
        model_name=route.model_name if route else "",
        content=f"[MODEL_QUERY_BLOCKED] {message}",
        error_code=error_code,
    )
