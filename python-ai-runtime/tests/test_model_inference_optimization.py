import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelCostTier,
    ModelLatencyTier,
    ModelRoute,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.services.model_gateway import (
    INFERENCE_OPTIMIZATION_PAYLOAD_POLICY,
    InferenceOptimizationStatus,
    ModelInferenceServingMetricsSnapshot,
    default_inference_optimization_diagnostics_service,
)


class ModelInferenceOptimizationDiagnosticsTest(unittest.TestCase):
    """成熟推理服务优化诊断测试。

    这组测试保护的是“模型层收敛边界”：DataSmart 不在仓库里自研推理内核、微调或后训练，而是
    把 vLLM/SGLang/LiteLLM/企业模型网关的低敏指标接进统一控制面。测试不会访问任何真实模型、
    metrics endpoint 或外部网络。
    """

    def test_vllm_route_without_metrics_reports_control_plane_gap(self) -> None:
        """vLLM 路由没有指标时，应明确列出缺口，而不是假装完成推理优化。"""

        service = default_inference_optimization_diagnostics_service()
        route = _route(
            provider_name="tenant-agent-vllm",
            provider_type=ProviderType.VLLM,
            endpoint="http://internal-vllm.example.test/v1",
        )

        diagnostics = service.diagnostics((route,))
        route_summary = diagnostics["routeDiagnostics"][0]
        serialized = str(diagnostics).lower()

        self.assertEqual("datasmart.model-inference-optimization.v1", diagnostics["schemaVersion"])
        self.assertEqual(INFERENCE_OPTIMIZATION_PAYLOAD_POLICY, diagnostics["payloadPolicy"])
        self.assertEqual(InferenceOptimizationStatus.CONTROL_PLANE_READY_METRICS_MISSING.value, route_summary["status"])
        self.assertEqual("vllm", route_summary["engineProfile"]["profileId"])
        self.assertIn("ttftMsP95", route_summary["missingMetrics"])
        self.assertIn("tokensPerSecondP50", route_summary["missingMetrics"])
        self.assertIn("queueTimeMsP95", route_summary["missingMetrics"])
        self.assertIn("prefixCacheHitRate", route_summary["missingMetrics"])
        self.assertNotIn("internal-vllm", serialized)
        self.assertNotIn("http://internal-vllm.example.test", serialized)
        self.assertNotIn("api_key", serialized)

    def test_vllm_route_with_healthy_metrics_becomes_production_candidate(self) -> None:
        """当核心指标齐全且低于阈值时，路由可以进入 benchmark/eval 与灰度候选。"""

        service = default_inference_optimization_diagnostics_service()
        route = _route(provider_name="tenant-agent-vllm", provider_type=ProviderType.VLLM)
        metrics = ModelInferenceServingMetricsSnapshot(
            provider_name="tenant-agent-vllm",
            engine_profile_id="vllm",
            ttft_ms_p95=900,
            tokens_per_second_p50=88.5,
            queue_time_ms_p95=120,
            prefix_cache_hit_rate=0.52,
            kv_cache_usage_ratio=0.41,
            active_batch_size=8,
            waiting_requests=2,
            gpu_memory_usage_ratio=0.62,
        )

        summary = service.diagnostics((route,), (metrics,))["routeDiagnostics"][0]

        self.assertEqual(InferenceOptimizationStatus.PRODUCTION_CANDIDATE.value, summary["status"])
        self.assertEqual((), summary["missingMetrics"])
        self.assertEqual((), summary["issues"])
        self.assertEqual("vllm", summary["metricsSnapshot"]["engineProfileId"])
        self.assertIn("进入真实 benchmark/eval", summary["recommendedActions"][0])

    def test_queue_and_resource_pressure_are_reported_as_overloaded(self) -> None:
        """队列、KV cache 或 GPU 压力过高时，应给出稳定问题码和调优建议。"""

        service = default_inference_optimization_diagnostics_service()
        route = _route(
            provider_name="batch-sglang",
            provider_type=ProviderType.SGLANG,
            latency_tier=ModelLatencyTier.STANDARD,
        )
        metrics = ModelInferenceServingMetricsSnapshot(
            provider_name="batch-sglang",
            engine_profile_id="sglang",
            ttft_ms_p95=3600,
            tokens_per_second_p50=22.0,
            queue_time_ms_p95=9100,
            prefix_cache_hit_rate=0.05,
            kv_cache_usage_ratio=0.97,
            active_batch_size=32,
            waiting_requests=128,
            gpu_memory_usage_ratio=0.96,
        )

        summary = service.diagnostics((route,), (metrics,))["routeDiagnostics"][0]

        self.assertEqual(InferenceOptimizationStatus.OVERLOADED.value, summary["status"])
        self.assertIn("QUEUE_TIME_P95_TOO_HIGH", summary["issues"])
        self.assertIn("WAITING_REQUESTS_TOO_HIGH", summary["issues"])
        self.assertIn("KV_CACHE_PRESSURE_TOO_HIGH", summary["issues"])
        self.assertIn("GPU_MEMORY_PRESSURE_TOO_HIGH", summary["issues"])
        self.assertIn("CACHE_HIT_RATE_BELOW_EXPECTATION", summary["warnings"])

    def test_litellm_gateway_can_be_detected_without_endpoint_leakage(self) -> None:
        """LiteLLM/企业模型网关应按 gateway profile 诊断，但不能泄露真实 endpoint。"""

        service = default_inference_optimization_diagnostics_service()
        route = _route(
            provider_name="litellm-agent-gateway",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            endpoint="https://secret-litellm.example.test/v1/chat/completions",
        )
        metrics = ModelInferenceServingMetricsSnapshot(
            provider_name="litellm-agent-gateway",
            engine_profile_id="litellm-gateway",
            ttft_ms_p95=1000,
            tokens_per_second_p50=48.0,
            queue_time_ms_p95=300,
            cache_hit_rate=0.35,
            running_requests=12,
            waiting_requests=4,
        )

        diagnostics = service.diagnostics((route,), (metrics,))
        summary = diagnostics["routeDiagnostics"][0]
        serialized = str(diagnostics).lower()

        self.assertEqual("litellm-gateway", summary["engineProfile"]["profileId"])
        self.assertEqual(InferenceOptimizationStatus.PRODUCTION_CANDIDATE.value, summary["status"])
        self.assertNotIn("secret-litellm", serialized)
        self.assertNotIn("https://secret-litellm.example.test", serialized)
        self.assertNotIn("api_key", serialized)

    def test_default_routes_remain_development_only_for_inference_optimization(self) -> None:
        """默认 dry-run 路由仍必须被标记为开发占位，避免误导最终闭环判断。"""

        diagnostics = default_inference_optimization_diagnostics_service().diagnostics(default_model_routes())
        statuses = {item["providerName"]: item["status"] for item in diagnostics["routeDiagnostics"]}

        self.assertGreaterEqual(diagnostics["routeCount"], 1)
        self.assertTrue(all(status == InferenceOptimizationStatus.DEVELOPMENT_ONLY.value for status in statuses.values()))
        self.assertGreater(diagnostics["statusCounts"][InferenceOptimizationStatus.DEVELOPMENT_ONLY.value], 0)


def _route(
    provider_name: str,
    provider_type: ProviderType,
    endpoint: str | None = None,
    latency_tier: ModelLatencyTier = ModelLatencyTier.INTERACTIVE,
) -> ModelRoute:
    return ModelRoute(
        workload=WorkloadType.AGENT_REASONING,
        provider_name=provider_name,
        provider_type=provider_type,
        model_name=f"{provider_name}-model",
        endpoint=endpoint,
        max_context_tokens=131072,
        timeout_seconds=90,
        priority=1,
        latency_tier=latency_tier,
        cost_tier=ModelCostTier.HIGH,
        cache_key_scope=ModelCacheKeyScope.SESSION_ONLY,
    )


if __name__ == "__main__":
    unittest.main()
