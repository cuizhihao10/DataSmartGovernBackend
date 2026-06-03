import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelCostTier,
    ModelInvocationResult,
    ModelLatencyTier,
    ModelRoute,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.domain.model_gateway import (
    ModelGatewayRequestContext,
    ModelProviderHealthStatus,
)
from datasmart_ai_runtime.services.model_gateway import (
    InMemoryModelBudgetLedger,
    InMemoryModelProviderHealthRegistry,
    ModelGatewayGovernanceService,
    ModelProviderHealthPolicy,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry


class ModelProviderHealthRegistryTest(unittest.TestCase):
    """模型 Provider 健康治理测试。

    这些测试关注“真实调用结果如何影响后续路由”。如果这层治理不稳定，Agent 即使有很好的工具规划、
    记忆和审批，也会反复打到故障模型，最终表现为用户等待、fallback 不清晰、预算浪费和排障困难。
    """

    def test_consecutive_failures_open_circuit_breaker(self) -> None:
        """连续失败达到阈值后应打开熔断窗口。"""

        now = datetime(2026, 6, 3, 12, 0, tzinfo=timezone.utc)
        registry = InMemoryModelProviderHealthRegistry(
            policy=ModelProviderHealthPolicy(
                failure_threshold=2,
                circuit_breaker_cooldown_seconds=30,
            ),
            clock=lambda: now,
        )

        registry.record_invocation("primary-agent", succeeded=False, latency_ms=5000, error_code="HTTP_503")
        snapshot = registry.record_invocation("primary-agent", succeeded=False, latency_ms=6000, error_code="HTTP_503")
        diagnostics = registry.diagnostics((_route("primary-agent"),))

        self.assertEqual(ModelProviderHealthStatus.UNAVAILABLE, snapshot.status)
        self.assertEqual(1, diagnostics["circuitOpenCount"])
        provider = diagnostics["providers"][0]
        self.assertTrue(provider["circuitOpen"])
        self.assertEqual(2, provider["consecutiveFailures"])
        self.assertIn("熔断", provider["notes"])

    def test_model_gateway_skips_circuit_open_primary_and_uses_fallback(self) -> None:
        """主 Provider 熔断时，模型网关应自动选择 fallback 候选。"""

        registry = InMemoryModelProviderHealthRegistry(
            policy=ModelProviderHealthPolicy(failure_threshold=1),
            clock=lambda: datetime(2026, 6, 3, 12, 0, tzinfo=timezone.utc),
        )
        registry.record_invocation("primary-agent", succeeded=False, latency_ms=9000, error_code="TIMEOUT")
        routes = ModelRouteRegistry(
            (
                _route("primary-agent", priority=1),
                _route("fallback-agent", priority=10),
            )
        )
        service = ModelGatewayGovernanceService(routes, health_registry=registry)

        decision = service.decide(_context())

        self.assertEqual("fallback-agent", decision.selected_route.provider_name)
        self.assertTrue(decision.fallback_used)
        self.assertTrue(any("不可用" in note for note in decision.governance_notes))

    def test_record_invocation_result_updates_budget_and_provider_health(self) -> None:
        """调用完成后应同时回写 token usage 和 Provider 健康事实。"""

        health = InMemoryModelProviderHealthRegistry()
        budget = InMemoryModelBudgetLedger()
        service = ModelGatewayGovernanceService(
            ModelRouteRegistry((_route("primary-agent"),)),
            health_registry=health,
            budget_ledger=budget,
        )

        used_tokens = service.record_invocation_result(
            _context(),
            ModelInvocationResult(
                provider_name="primary-agent",
                model_name="primary-agent-model",
                content="upstream error",
                latency_ms=4500,
                prompt_tokens=11,
                completion_tokens=7,
                error_code="MODEL_PROVIDER_HTTP_503",
            ),
        )

        snapshot = health.snapshot_for(_route("primary-agent"))
        self.assertEqual(18, used_tokens)
        self.assertEqual(18, budget.used_tokens("tenant-a", "project-a"))
        self.assertEqual(ModelProviderHealthStatus.DEGRADED, snapshot.status)
        self.assertEqual(1.0, snapshot.error_rate)
        self.assertIn("连续失败 1 次", snapshot.notes)

    def test_diagnostics_exposes_unknown_route_provider_without_sensitive_payload(self) -> None:
        """没有调用结果的路由也应进入诊断摘要，并保持低敏输出。"""

        registry = InMemoryModelProviderHealthRegistry(
            clock=lambda: datetime(2026, 6, 3, 12, 0, tzinfo=timezone.utc),
        )

        diagnostics = registry.diagnostics((_route("embedding-router", workload=WorkloadType.EMBEDDING),))

        self.assertEqual("datasmart.model-provider-health.v1", diagnostics["schemaVersion"])
        self.assertEqual("unknown", diagnostics["overallStatus"])
        self.assertEqual(1, diagnostics["unknownCount"])
        provider = diagnostics["providers"][0]
        self.assertEqual("embedding-router", provider["providerName"])
        self.assertEqual(("embedding",), provider["routeWorkloads"])
        self.assertEqual(("embedding-router-model",), provider["routeModels"])
        self.assertTrue(any("健康探测" in action for action in diagnostics["recommendedActions"]))
        self.assertNotIn("prompt", str(diagnostics).lower())

    def test_success_after_cooldown_closes_circuit(self) -> None:
        """熔断冷却结束后，成功探测应关闭熔断但保留降级观察。"""

        current_time = [datetime(2026, 6, 3, 12, 0, tzinfo=timezone.utc)]
        registry = InMemoryModelProviderHealthRegistry(
            policy=ModelProviderHealthPolicy(
                failure_threshold=1,
                circuit_breaker_cooldown_seconds=10,
                min_error_rate_sample_size=2,
            ),
            clock=lambda: current_time[0],
        )
        registry.record_invocation("primary-agent", succeeded=False, latency_ms=1000, error_code="TIMEOUT")
        current_time[0] = current_time[0] + timedelta(seconds=11)

        snapshot = registry.record_invocation("primary-agent", succeeded=True, latency_ms=80)

        # 最近窗口里仍有一次失败，因此不再处于硬熔断，但会保持 degraded 观察一段时间。
        # 这比一次成功就立刻恢复 healthy 更稳，能避免 Provider 在抖动期反复开关。
        self.assertEqual(ModelProviderHealthStatus.DEGRADED, snapshot.status)
        self.assertFalse(registry.diagnostics((_route("primary-agent"),))["providers"][0]["circuitOpen"])


def _route(
    provider_name: str,
    *,
    priority: int = 1,
    workload: WorkloadType = WorkloadType.AGENT_REASONING,
) -> ModelRoute:
    return ModelRoute(
        workload=workload,
        provider_name=provider_name,
        provider_type=ProviderType.DRY_RUN,
        model_name=f"{provider_name}-model",
        priority=priority,
        latency_tier=ModelLatencyTier.STANDARD,
        cost_tier=ModelCostTier.MEDIUM,
        cache_key_scope=ModelCacheKeyScope.SESSION_ONLY,
    )


def _context() -> ModelGatewayRequestContext:
    return ModelGatewayRequestContext(
        tenant_id="tenant-a",
        project_id="project-a",
        actor_id="user-a",
        workload=WorkloadType.AGENT_REASONING,
        estimated_prompt_tokens=10,
        estimated_completion_tokens=20,
    )


if __name__ == "__main__":
    unittest.main()
