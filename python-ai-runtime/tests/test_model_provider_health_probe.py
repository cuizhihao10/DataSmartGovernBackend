import io
import os
import sys
import unittest
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError

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
from datasmart_ai_runtime.domain.model_gateway import ModelProviderHealthStatus
from datasmart_ai_runtime.services.model_gateway import InMemoryModelProviderHealthRegistry
from datasmart_ai_runtime.services.model_gateway.model_provider_health_probe import (
    ModelProviderHealthProbeService,
    ModelProviderHealthProbeSettings,
)


class ModelProviderHealthProbeServiceTest(unittest.TestCase):
    """模型 Provider 主动健康探测测试。

    这组测试覆盖的是 5.22 的新增能力：健康状态不只来自真实模型调用后的被动回写，也可以来自运维、
    gateway 或启动流程显式触发的主动探测。探测结果仍然必须保持低敏，不允许把 API Key、URL query、
    prompt、工具参数或模型输出扩散到诊断响应。
    """

    def test_successful_probe_marks_provider_healthy(self) -> None:
        """HTTP 2xx 探测成功时，应把 Provider 标记为 HEALTHY。"""

        registry = InMemoryModelProviderHealthRegistry(clock=_fixed_now)
        service = ModelProviderHealthProbeService(
            registry,
            settings=ModelProviderHealthProbeSettings(timeout_seconds=1),
            transport=FakeProbeTransport(status=204),
            clock=_fixed_now,
        )
        route = _route(
            "openai-compatible-agent",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            endpoint="https://models.example.com/v1/chat/completions?api_key=secret",
            health_check_path="/healthz",
        )

        summary = service.probe_routes((route,), requested_by="unit-test")
        snapshot = registry.snapshot_for(route)

        self.assertEqual(1, summary["successCount"])
        self.assertEqual(0, summary["failureCount"])
        self.assertEqual(ModelProviderHealthStatus.HEALTHY, snapshot.status)
        self.assertEqual("https://models.example.com/healthz", summary["results"][0]["probeUrl"])
        self.assertNotIn("secret", str(summary))
        self.assertIn("主动健康探测成功", snapshot.notes)

    def test_http_503_probe_marks_provider_unavailable_without_sensitive_payload(self) -> None:
        """HTTP 5xx 应标记为 UNAVAILABLE，并保持诊断输出低敏。"""

        registry = InMemoryModelProviderHealthRegistry(clock=_fixed_now)
        route = _route(
            "sglang-agent",
            provider_type=ProviderType.SGLANG,
            endpoint="https://sglang.example.com/openai/v1/chat/completions?token=hidden",
            health_check_path="/health",
        )
        service = ModelProviderHealthProbeService(
            registry,
            transport=FakeProbeTransport(http_error=503),
            clock=_fixed_now,
        )

        summary = service.probe_routes((route,), requested_by="unit-test")
        snapshot = registry.snapshot_for(route)

        self.assertEqual(1, summary["failureCount"])
        self.assertEqual(ModelProviderHealthStatus.UNAVAILABLE, snapshot.status)
        self.assertEqual("PROBE_HTTP_503", summary["results"][0]["errorCode"])
        self.assertEqual("https://sglang.example.com/health", summary["results"][0]["probeUrl"])
        self.assertNotIn("hidden", str(summary))
        self.assertNotIn("'prompt':", str(summary).lower())
        self.assertNotIn("toolArguments", str(summary))

    def test_dry_run_and_dry_run_provider_do_not_write_registry(self) -> None:
        """dry-run 请求和 dry-run Provider 都不应访问网络或写回 registry。"""

        registry = InMemoryModelProviderHealthRegistry(clock=_fixed_now)
        transport = FakeProbeTransport(status=200)
        service = ModelProviderHealthProbeService(registry, transport=transport, clock=_fixed_now)
        dry_run_provider = _route("local-placeholder")
        real_provider = _route(
            "vllm-agent",
            provider_type=ProviderType.VLLM,
            endpoint="https://vllm.example.com/v1/chat/completions",
        )

        summary = service.probe_routes((dry_run_provider, real_provider), dry_run=True)

        self.assertEqual(0, transport.call_count)
        self.assertEqual(2, summary["skippedCount"])
        self.assertEqual(ModelProviderHealthStatus.UNKNOWN, registry.snapshot_for(real_provider).status)
        self.assertEqual("DRY_RUN_PROVIDER_SKIPPED", summary["results"][0]["errorCode"])
        self.assertEqual("DRY_RUN_REQUESTED", summary["results"][1]["errorCode"])

    def test_network_error_updates_probe_metrics_and_unavailable_status(self) -> None:
        """网络错误应写回不可用快照，并增加低基数探测计数。"""

        registry = InMemoryModelProviderHealthRegistry(clock=_fixed_now)
        route = _route(
            "internal-router",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            endpoint="https://internal-router.example.com/v1/chat/completions",
        )
        service = ModelProviderHealthProbeService(
            registry,
            transport=FakeProbeTransport(url_error=True),
            clock=_fixed_now,
        )

        service.probe_routes((route,), requested_by="unit-test")
        diagnostics = service.diagnostics()

        self.assertEqual(ModelProviderHealthStatus.UNAVAILABLE, registry.snapshot_for(route).status)
        self.assertEqual(1, diagnostics["metrics"]["probeRunCount"])
        self.assertEqual(1, diagnostics["metrics"]["probeFailureCount"])
        self.assertEqual(0, diagnostics["metrics"]["probeSuccessCount"])
        self.assertIn("不要把完整 URL", diagnostics["recommendedActions"][1])


class FakeProbeResponse:
    """测试用 HTTP 响应对象，模拟 urllib response 的 context manager 行为。"""

    def __init__(self, status: int) -> None:
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None


class FakeProbeTransport:
    """测试用探测传输层。"""

    def __init__(self, status: int = 200, http_error: int | None = None, url_error: bool = False) -> None:
        self._status = status
        self._http_error = http_error
        self._url_error = url_error
        self.call_count = 0

    def __call__(self, request, timeout):
        self.call_count += 1
        if self._url_error:
            raise URLError("network unreachable")
        if self._http_error is not None:
            raise HTTPError(
                request.full_url,
                self._http_error,
                "probe failed",
                hdrs=None,
                fp=io.BytesIO(b"{}"),
            )
        return FakeProbeResponse(self._status)


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
    return datetime(2026, 6, 6, 10, 0, tzinfo=timezone.utc)


if __name__ == "__main__":
    unittest.main()
