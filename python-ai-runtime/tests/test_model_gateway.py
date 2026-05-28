import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelCostTier,
    ModelLatencyTier,
    ModelRoute,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.domain.model_gateway import (
    ModelGatewayBudgetPolicy,
    ModelGatewayRequestContext,
    ModelProviderHealthSnapshot,
    ModelProviderHealthStatus,
)
from datasmart_ai_runtime.services.model_gateway import (
    InMemoryModelBudgetLedger,
    InMemoryModelProviderHealthRegistry,
    ModelGatewayGovernanceService,
)
from datasmart_ai_runtime.services.model_router import ModelRouteRegistry


class ModelGatewayGovernanceServiceTest(unittest.TestCase):
    def test_decision_falls_back_when_primary_provider_is_unavailable(self) -> None:
        """主 Provider 不可用时应选择备用候选，而不是让 Agent 编排直接失败。

        真实生产环境里，模型服务可能因为 GPU 队列、部署升级、供应商限流或网络抖动暂时不可用。
        模型网关需要把这种不稳定性控制在路由层，而不是让上层工具规划、审批和任务链路全部感知
        Provider 细节。
        """

        primary = _route("primary-agent", priority=1)
        fallback = _route("fallback-agent", priority=10)
        health = InMemoryModelProviderHealthRegistry(
            (
                ModelProviderHealthSnapshot(
                    provider_name="primary-agent",
                    status=ModelProviderHealthStatus.UNAVAILABLE,
                    notes="模拟主模型维护中。",
                ),
                ModelProviderHealthSnapshot(
                    provider_name="fallback-agent",
                    status=ModelProviderHealthStatus.HEALTHY,
                ),
            )
        )
        service = ModelGatewayGovernanceService(
            ModelRouteRegistry((primary, fallback)),
            health_registry=health,
        )

        decision = service.decide(_context())

        self.assertEqual("fallback-agent", decision.selected_route.provider_name)
        self.assertTrue(decision.fallback_used)
        self.assertTrue(any("不可用" in note for note in decision.governance_notes))

    def test_budget_policy_blocks_route_selection_when_tokens_exceed_remaining_budget(self) -> None:
        """预算不足时不应该继续选择模型路由。

        这条规则对应商业化产品里的成本保护：当租户或项目预算耗尽时，系统可以提示升级套餐、
        转离线任务或使用更低成本策略，但不能继续无约束消耗模型资源。
        """

        ledger = InMemoryModelBudgetLedger(
            (ModelGatewayBudgetPolicy(tenant_id="tenant-a", project_id="project-a", monthly_token_budget=100),)
        )
        ledger.record_usage("tenant-a", "project-a", used_tokens=95)
        service = ModelGatewayGovernanceService(
            ModelRouteRegistry((_route("primary-agent"),)),
            budget_ledger=ledger,
        )

        decision = service.decide(
            _context(
                estimated_prompt_tokens=10,
                estimated_completion_tokens=10,
            )
        )

        self.assertIsNone(decision.selected_route)
        self.assertFalse(decision.budget_decision.allowed)
        self.assertEqual("budget", decision.attributes["blockedBy"])

    def test_latency_tier_preference_can_reorder_candidates(self) -> None:
        """调用方要求交互式延迟时，交互式候选应优先于批处理候选。"""

        batch_route = _route(
            "batch-agent",
            priority=1,
            latency_tier=ModelLatencyTier.BATCH,
        )
        interactive_route = _route(
            "interactive-agent",
            priority=10,
            latency_tier=ModelLatencyTier.INTERACTIVE,
            cache_key_scope=ModelCacheKeyScope.PROJECT_SAFE,
        )
        service = ModelGatewayGovernanceService(ModelRouteRegistry((batch_route, interactive_route)))

        decision = service.decide(_context(latency_tier=ModelLatencyTier.INTERACTIVE))

        self.assertEqual("interactive-agent", decision.selected_route.provider_name)
        self.assertEqual(ModelCacheKeyScope.PROJECT_SAFE, decision.cache_key_scope)

    def test_explicit_cache_scope_overrides_route_default(self) -> None:
        """高敏感请求可以显式把缓存范围收紧为 NO_CACHE。"""

        service = ModelGatewayGovernanceService(ModelRouteRegistry((_route("primary-agent"),)))

        decision = service.decide(_context(cache_key_scope=ModelCacheKeyScope.NO_CACHE))

        self.assertEqual(ModelCacheKeyScope.NO_CACHE, decision.cache_key_scope)
        self.assertTrue(any("显式指定缓存范围" in note for note in decision.governance_notes))

    def test_record_invocation_usage_updates_budget_ledger_after_model_call(self) -> None:
        """模型调用完成后应按真实 usage 回写预算台账。

        预算预评估只能防止明显超额请求，真实成本闭环必须记录 provider 返回的 prompt/completion
        tokens。否则系统只能“调用前猜测”，无法支撑租户账单、套餐余量和成本告警。
        """

        ledger = InMemoryModelBudgetLedger(
            (ModelGatewayBudgetPolicy(tenant_id="tenant-a", project_id="project-a", monthly_token_budget=1000),)
        )
        service = ModelGatewayGovernanceService(
            ModelRouteRegistry((_route("primary-agent"),)),
            budget_ledger=ledger,
        )
        context = _context(estimated_prompt_tokens=10, estimated_completion_tokens=20)

        used_tokens = service.record_invocation_usage(context, prompt_tokens=33, completion_tokens=44)

        self.assertEqual(77, used_tokens)
        self.assertEqual(77, ledger.used_tokens("tenant-a", "project-a"))


def _route(
    provider_name: str,
    priority: int = 1,
    latency_tier: ModelLatencyTier = ModelLatencyTier.STANDARD,
    cache_key_scope: ModelCacheKeyScope = ModelCacheKeyScope.SESSION_ONLY,
) -> ModelRoute:
    return ModelRoute(
        workload=WorkloadType.AGENT_REASONING,
        provider_name=provider_name,
        provider_type=ProviderType.DRY_RUN,
        model_name=f"{provider_name}-model",
        priority=priority,
        latency_tier=latency_tier,
        cost_tier=ModelCostTier.MEDIUM,
        cache_key_scope=cache_key_scope,
    )


def _context(
    estimated_prompt_tokens: int = 100,
    estimated_completion_tokens: int = 100,
    latency_tier: ModelLatencyTier | None = None,
    cache_key_scope: ModelCacheKeyScope | None = None,
) -> ModelGatewayRequestContext:
    return ModelGatewayRequestContext(
        tenant_id="tenant-a",
        project_id="project-a",
        actor_id="user-a",
        workload=WorkloadType.AGENT_REASONING,
        estimated_prompt_tokens=estimated_prompt_tokens,
        estimated_completion_tokens=estimated_completion_tokens,
        latency_tier=latency_tier,
        cache_key_scope=cache_key_scope,
    )


if __name__ == "__main__":
    unittest.main()
