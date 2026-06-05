import io
import os
import sys
import unittest
from datetime import datetime, timezone
from urllib.error import HTTPError

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
from datasmart_ai_runtime.services.model_gateway import InMemoryModelProviderHealthRegistry
from datasmart_ai_runtime.services.model_gateway.model_provider_health_probe import (
    ModelProviderHealthProbeService,
    ModelProviderHealthProbeSettings,
)
from datasmart_ai_runtime.services.model_gateway.model_provider_health_probe_metrics import (
    render_model_provider_health_probe_diagnostics_prometheus,
    render_model_provider_health_probe_prometheus,
)


class ModelProviderHealthProbeMetricsTest(unittest.TestCase):
    """模型 Provider 主动探测 Prometheus 指标测试。

    这组测试不是验证“HTTP 探测是否成功”，而是验证 5.23 的观测边界：
    - `/agent/metrics` 应能看到主动探测的累计结果和最近一轮状态；
    - 指标标签必须保持低基数，只允许 outcome/status 这类固定枚举；
    - providerName、完整 URL、token、prompt、工具参数等明细不能进入指标文本。
    """

    def test_probe_diagnostics_are_rendered_as_low_cardinality_metrics(self) -> None:
        """一次混合探测应生成 success/failure/skipped 与健康状态分布指标。"""

        registry = InMemoryModelProviderHealthRegistry(clock=_fixed_now)
        service = ModelProviderHealthProbeService(
            registry,
            settings=ModelProviderHealthProbeSettings(timeout_seconds=2, max_routes_per_run=5),
            transport=RoutingProbeTransport(),
            clock=_fixed_now,
        )
        routes = (
            _route(
                "healthy-provider",
                provider_type=ProviderType.OPENAI_COMPATIBLE,
                endpoint="https://healthy.example.com/v1/chat/completions?api_key=secret",
            ),
            _route(
                "down-provider",
                provider_type=ProviderType.VLLM,
                endpoint="https://down.example.com/v1/chat/completions?token=hidden",
            ),
            _route("dry-run-provider"),
        )

        service.probe_routes(routes, requested_by="unit-test")
        text = render_model_provider_health_probe_prometheus(service)

        self.assertIn("# HELP datasmart_ai_model_provider_health_probe_runs_total", text)
        self.assertIn("datasmart_ai_model_provider_health_probe_runs_total 1", text)
        self.assertIn('datasmart_ai_model_provider_health_probe_outcomes_total{outcome="success"} 1', text)
        self.assertIn('datasmart_ai_model_provider_health_probe_outcomes_total{outcome="failure"} 1', text)
        self.assertIn('datasmart_ai_model_provider_health_probe_outcomes_total{outcome="skipped"} 1', text)
        self.assertIn("datasmart_ai_model_provider_health_probe_last_run_candidates 3", text)
        self.assertIn("datasmart_ai_model_provider_health_probe_last_run_probed 3", text)
        self.assertIn("datasmart_ai_model_provider_health_probe_last_run_truncated 0", text)
        self.assertIn('datasmart_ai_model_provider_health_probe_last_run_status_providers{status="healthy"} 1', text)
        self.assertIn(
            'datasmart_ai_model_provider_health_probe_last_run_status_providers{status="unavailable"} 1',
            text,
        )
        self.assertIn('datasmart_ai_model_provider_health_probe_last_run_status_providers{status="unknown"} 1', text)
        self.assertNotIn("healthy-provider", text)
        self.assertNotIn("down-provider", text)
        self.assertNotIn("dry-run-provider", text)
        self.assertNotIn("healthy.example.com", text)
        self.assertNotIn("down.example.com", text)
        self.assertNotIn("secret", text)
        self.assertNotIn("hidden", text)
        self.assertNotIn("prompt", text.lower())
        self.assertNotIn("toolArguments", text)

    def test_renderer_outputs_zero_series_when_probe_has_never_run(self) -> None:
        """未发生探测时也应输出固定低基数指标，方便 Prometheus 发现端点存在。"""

        text = render_model_provider_health_probe_diagnostics_prometheus(
            {
                "startupProbeEnabled": True,
                "timeoutSeconds": 3,
                "maxRoutesPerRun": 20,
                "metrics": {
                    "probeRunCount": 0,
                    "probeSuccessCount": 0,
                    "probeFailureCount": 0,
                    "probeSkippedCount": 0,
                },
                "lastRun": None,
            }
        )

        self.assertIn("datasmart_ai_model_provider_health_probe_startup_enabled 1", text)
        self.assertIn("datasmart_ai_model_provider_health_probe_timeout_seconds 3", text)
        self.assertIn("datasmart_ai_model_provider_health_probe_max_routes_per_run 20", text)
        self.assertIn("datasmart_ai_model_provider_health_probe_runs_total 0", text)
        self.assertIn('datasmart_ai_model_provider_health_probe_outcomes_total{outcome="success"} 0', text)
        self.assertIn('datasmart_ai_model_provider_health_probe_last_run_status_providers{status="unknown"} 0', text)


class RoutingProbeResponse:
    """测试用 HTTP 响应对象，只模拟 status 与 context manager。"""

    def __init__(self, status: int) -> None:
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None


class RoutingProbeTransport:
    """按请求 URL 模拟健康/不可用 Provider。

    真实生产中 transport 会访问 OpenAI-compatible、vLLM、SGLang 或内部模型网关的健康端点。测试中只用
    host 名分流，避免单元测试依赖外部网络。
    """

    def __call__(self, request, timeout):
        if "down.example.com" in request.full_url:
            raise HTTPError(
                request.full_url,
                503,
                "probe failed",
                hdrs=None,
                fp=io.BytesIO(b"{}"),
            )
        return RoutingProbeResponse(204)


def _route(
    provider_name: str,
    *,
    provider_type: ProviderType = ProviderType.DRY_RUN,
    endpoint: str | None = None,
    health_check_path: str = "/health",
) -> ModelRoute:
    """生成测试用模型路由。"""

    return ModelRoute(
        workload=WorkloadType.AGENT_REASONING,
        provider_name=provider_name,
        provider_type=provider_type,
        model_name=f"{provider_name}-model",
        endpoint=endpoint,
        priority=1,
        latency_tier=ModelLatencyTier.STANDARD,
        cost_tier=ModelCostTier.MEDIUM,
        cache_key_scope=ModelCacheKeyScope.SESSION_ONLY,
        health_check_path=health_check_path,
    )


def _fixed_now() -> datetime:
    """固定测试时间，避免快照时间影响断言。"""

    return datetime(2026, 6, 6, 12, 0, tzinfo=timezone.utc)


if __name__ == "__main__":
    unittest.main()
