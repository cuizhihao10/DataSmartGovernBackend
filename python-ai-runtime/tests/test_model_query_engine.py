import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes, default_skill_registry, default_tool_registry
from datasmart_ai_runtime.domain.contracts import (
    AgentRequest,
    ModelCacheKeyScope,
    ModelCostTier,
    ModelInvocationRequest,
    ModelInvocationResult,
    ModelLatencyTier,
    ModelMessage,
    ModelRoute,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.model_gateway import (
    InMemoryModelQueryRateLimiter,
    InMemoryModelQueryResultCache,
    ModelGatewayGovernanceService,
    ModelQueryEngine,
    ModelQueryEngineSettings,
    ModelQueryRateLimitPolicy,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class ModelQueryEngineTest(unittest.TestCase):
    """模型查询引擎治理测试。

    这组测试保护的是“模型层怎么闭环”，不是某个模型回答得好不好。Query Engine 应该把成熟推理服务接入
    所需的 retry/fallback、rate-limit、token-limit、cache 和低敏摘要固定下来，后续无论接
    DeepSeek、Qwen、GLM、vLLM、SGLang 还是企业内部 OpenAI-compatible 网关，都不需要改 Agent 主链。
    """

    def test_provider_error_falls_back_to_next_candidate(self) -> None:
        """主 Provider 返回可重试错误时，应尝试 fallback 候选并记录低敏尝试摘要。"""

        primary = _route("primary-agent", priority=1)
        fallback = _route("fallback-agent", priority=10)
        model_gateway = ModelGatewayGovernanceService(ModelRouteRegistry((primary, fallback)))
        context = _context()
        decision = model_gateway.decide(context)
        providers = ScriptedModelProviders(
            {
                "primary-agent": (
                    ModelInvocationResult(
                        provider_name="primary-agent",
                        model_name="primary-agent-model",
                        content="[MODEL_PROVIDER_ERROR] 状态码 503",
                        error_code="MODEL_PROVIDER_HTTP_503",
                    ),
                ),
                "fallback-agent": (
                    ModelInvocationResult(
                        provider_name="fallback-agent",
                        model_name="fallback-agent-model",
                        content="fallback ok",
                        prompt_tokens=5,
                        completion_tokens=3,
                    ),
                ),
            }
        )
        engine = ModelQueryEngine(model_gateway=model_gateway, model_providers=providers)

        result = engine.invoke(_request(decision.selected_route), context=context, routing_decision=decision)

        self.assertEqual("fallback ok", result.result.content)
        self.assertTrue(result.fallback_used)
        self.assertEqual(("primary-agent", "fallback-agent"), tuple(call.route.provider_name for call in providers.calls))
        self.assertEqual(("provider_error", "succeeded"), tuple(attempt.outcome for attempt in result.attempts))
        self.assertEqual("LOW_SENSITIVE_QUERY_GOVERNANCE_ONLY", result.to_summary()["payloadPolicy"])
        self.assertTrue(result.to_summary()["providerInvoked"])
        self.assertTrue(result.to_summary()["providerSucceeded"])
        self.assertEqual(8, result.to_summary()["totalTokens"])

    def test_token_limit_blocks_before_provider_call(self) -> None:
        """明显超过上下文窗口的请求必须在 Provider 调用前阻断。"""

        route = _route("small-context-agent", max_context_tokens=32)
        routes = ModelRouteRegistry((route,))
        model_gateway = ModelGatewayGovernanceService(routes)
        context = _context(estimated_prompt_tokens=64, estimated_completion_tokens=64)
        decision = model_gateway.decide(context)
        providers = ScriptedModelProviders({"small-context-agent": (_ok_result(route),)})
        engine = ModelQueryEngine(model_gateway=model_gateway, model_providers=providers)

        result = engine.invoke(_request(route), context=context, routing_decision=decision)

        self.assertEqual("MODEL_QUERY_TOKEN_LIMIT_EXCEEDED", result.result.error_code)
        self.assertTrue(result.token_limited)
        self.assertEqual(0, len(providers.calls))
        self.assertTrue(result.attempts[0].token_limited)
        self.assertFalse(result.to_summary()["providerInvoked"])

    def test_rate_limit_blocks_repeated_query_without_leaking_quota_formula(self) -> None:
        """限流响应只返回稳定 code 和低敏窗口摘要，不暴露内部套餐或计算公式。"""

        route = _route("limited-agent")
        routes = ModelRouteRegistry((route,))
        model_gateway = ModelGatewayGovernanceService(routes)
        context = _context()
        decision = model_gateway.decide(context)
        providers = ScriptedModelProviders({"limited-agent": (_ok_result(route), _ok_result(route, content="second"))})
        engine = ModelQueryEngine(
            model_gateway=model_gateway,
            model_providers=providers,
            settings=ModelQueryEngineSettings(
                enable_result_cache=False,
                rate_limit_policy=ModelQueryRateLimitPolicy(max_requests_per_window=1, window_seconds=60),
            ),
            rate_limiter=InMemoryModelQueryRateLimiter(
                ModelQueryRateLimitPolicy(max_requests_per_window=1, window_seconds=60),
                clock=lambda: 1000.0,
            ),
        )

        first = engine.invoke(_request(route), context=context, routing_decision=decision)
        second = engine.invoke(_request(route), context=context, routing_decision=decision)
        serialized_summary = str(second.to_summary()).lower()

        self.assertIsNone(first.result.error_code)
        self.assertEqual("MODEL_QUERY_RATE_LIMITED", second.result.error_code)
        self.assertTrue(second.rate_limited)
        self.assertEqual(1, len(providers.calls))
        self.assertNotIn("secret", serialized_summary)
        self.assertNotIn("quota formula", serialized_summary)
        self.assertNotIn("select * from", serialized_summary)

    def test_session_only_result_cache_reuses_safe_result_by_digest(self) -> None:
        """会话级 cache plan 下，相同请求可命中结果缓存，摘要不能泄露 prompt 或模型输出。"""

        route = _route("cache-agent", cache_scope=ModelCacheKeyScope.SESSION_ONLY)
        model_gateway = ModelGatewayGovernanceService(ModelRouteRegistry((route,)))
        context = _context()
        decision = model_gateway.decide(context)
        providers = ScriptedModelProviders({"cache-agent": (_ok_result(route, content="cached answer"),)})
        engine = ModelQueryEngine(
            model_gateway=model_gateway,
            model_providers=providers,
            result_cache=InMemoryModelQueryResultCache(clock=lambda: 1000.0),
        )
        request = _request(
            route,
            content="请总结敏感客户数据治理策略，secret-token 不应进入摘要",
            metadata=_session_cache_metadata(),
        )

        first = engine.invoke(request, context=context, routing_decision=decision)
        second = engine.invoke(request, context=context, routing_decision=decision)
        serialized_summary = str(second.to_summary())

        self.assertEqual("cached answer", first.result.content)
        self.assertEqual("cached answer", second.result.content)
        self.assertEqual(1, len(providers.calls))
        self.assertTrue(second.cache_hit)
        self.assertNotIn("secret-token", serialized_summary)
        self.assertNotIn("cached answer", serialized_summary)

    def test_agent_orchestrator_records_model_query_event_without_sensitive_payload(self) -> None:
        """Agent 主链应产生模型查询事件，但事件摘要不能包含用户敏感目标。"""

        provider = ScriptedModelProviders(
            {
                "open-weight-agent-router": (
                    ModelInvocationResult(
                        provider_name="open-weight-agent-router",
                        model_name="Qwen3.5-or-DeepSeek-V3.2-agent-placeholder",
                        content="captured",
                    ),
                )
            }
        )
        orchestrator = AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请分析 secret-customer-table 的质量问题",
                variables={"datasourceId": "ds-sensitive", "streamModelIntent": False},
            )
        )
        query_event = next(
            event for event in plan.runtime_events if event.event_type == AgentRuntimeEventType.MODEL_QUERY_EXECUTED
        )
        serialized_event = str(query_event.attributes)

        self.assertEqual("LOW_SENSITIVE_QUERY_GOVERNANCE_ONLY", query_event.attributes["payloadPolicy"])
        self.assertEqual(1, query_event.attributes["attemptCount"])
        self.assertNotIn("secret-customer-table", serialized_event)
        self.assertNotIn("ds-sensitive", serialized_event)
        self.assertNotIn("captured", serialized_event)


class ScriptedModelProviders:
    """按 providerName 返回预设结果的测试 Provider 注册表。"""

    def __init__(self, scripts: dict[str, tuple[ModelInvocationResult, ...]]) -> None:
        self._scripts = {key: list(values) for key, values in scripts.items()}
        self.calls: list[ModelInvocationRequest] = []

    def invoke(self, request: ModelInvocationRequest) -> ModelInvocationResult:
        """记录请求并返回当前 provider 的下一条预设结果。"""

        self.calls.append(request)
        script = self._scripts.get(request.route.provider_name)
        if not script:
            return _ok_result(request.route)
        if len(script) == 1:
            return script[0]
        return script.pop(0)


def _route(
    provider_name: str,
    *,
    priority: int = 1,
    max_context_tokens: int = 4096,
    cache_scope: ModelCacheKeyScope = ModelCacheKeyScope.SESSION_ONLY,
) -> ModelRoute:
    return ModelRoute(
        workload=WorkloadType.AGENT_REASONING,
        provider_name=provider_name,
        provider_type=ProviderType.DRY_RUN,
        model_name=f"{provider_name}-model",
        max_context_tokens=max_context_tokens,
        priority=priority,
        latency_tier=ModelLatencyTier.STANDARD,
        cost_tier=ModelCostTier.MEDIUM,
        cache_key_scope=cache_scope,
    )


def _context(estimated_prompt_tokens: int = 10, estimated_completion_tokens: int = 10) -> ModelGatewayRequestContext:
    return ModelGatewayRequestContext(
        tenant_id="tenant-a",
        project_id="project-a",
        actor_id="owner-a",
        workload=WorkloadType.AGENT_REASONING,
        estimated_prompt_tokens=estimated_prompt_tokens,
        estimated_completion_tokens=estimated_completion_tokens,
        attributes={"sessionId": "session-query-engine"},
    )


def _request(
    route: ModelRoute,
    *,
    content: str = "请生成治理建议",
    metadata: dict[str, object] | None = None,
) -> ModelInvocationRequest:
    return ModelInvocationRequest(
        route=route,
        messages=(ModelMessage(role="user", content=content),),
        provider_metadata=metadata or _session_cache_metadata(),
    )


def _ok_result(route: ModelRoute, content: str = "ok") -> ModelInvocationResult:
    return ModelInvocationResult(
        provider_name=route.provider_name,
        model_name=route.model_name,
        content=content,
        prompt_tokens=3,
        completion_tokens=2,
    )


def _session_cache_metadata() -> dict[str, object]:
    return {
        "cachePlan": {
            "enabled": True,
            "scope": "session_only",
            "namespace": "model-cache:session_only:tenant:tenant-a:project:project-a:session:session-query-engine",
            "keyPrefix": "model-cache:session_only:tenant:tenant-a:project:project-a:session:session-query-engine:route:p:m",
            "ttlSeconds": 120,
        }
    }


if __name__ == "__main__":
    unittest.main()
