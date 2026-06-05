"""模型 Provider 主动健康探测服务。

5.20 已经让 Provider 健康状态参与模型路由评分，5.21 又把路由评分写入 runtime event。但如果健康状态
只依赖“真实模型调用结束后的回写”，系统在冷启动、低流量或故障恢复阶段仍然会长期处于 UNKNOWN。
本模块补齐主动探测能力：按模型路由配置主动访问 Provider 的健康检查地址，并把低敏结果回灌到
`InMemoryModelProviderHealthRegistry`，让后续路由决策、诊断接口和事件回放都能消费同一份健康事实。

安全边界：
- 默认不会在导入模块时访问网络，只有调用 `probe_routes(...)` 才会探测；
- 未配置 endpoint 或 dry-run Provider 会被跳过，不会误报故障；
- 诊断只暴露 provider、workload、model、健康状态、延迟和 sanitized probeUrl，不暴露 query、fragment、
  API Key、prompt、工具参数、模型输出或真实 KV cache 内容；
- Prometheus 方向只保留低基数计数字段，不把 tenantId、projectId、runId、traceId、完整 URL 放进指标维度。
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http import HTTPStatus
from typing import Any, Callable, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.contracts import ModelRoute, ProviderType
from datasmart_ai_runtime.domain.model_gateway import (
    ModelProviderHealthSnapshot,
    ModelProviderHealthStatus,
)
from datasmart_ai_runtime.services.model_gateway.model_provider_health import InMemoryModelProviderHealthRegistry


class ModelProviderHealthProbeTransport(Protocol):
    """健康探测使用的 HTTP 传输协议。

    默认实现使用标准库 `urlopen`，但测试、生产网关或后续 OpenTelemetry 版本可以注入自定义 transport。
    这样探测服务不绑定某个 HTTP 客户端，也不会为了单元测试启动真实模型服务。
    """

    def __call__(self, request: Request, timeout: int):
        """发送健康探测请求并返回类 HTTP response 对象。"""


@dataclass(frozen=True)
class ModelProviderHealthProbeSettings:
    """主动健康探测运行配置。

    字段说明：
    - `timeout_seconds`：单个健康探测请求的超时时间，必须足够短，避免运维探测阻塞 Agent 主链；
    - `max_routes_per_run`：单轮最多处理多少个 Provider 候选，防止错误配置导致一次 API 触发大量外部请求；
    - `probe_user_agent`：发送给模型网关的 User-Agent，方便上游区分真实模型调用和治理探测；
    - `startup_probe_enabled`：是否允许 FastAPI 启动时自动探测，默认关闭，避免本地学习环境被外部网络拖慢。
    """

    timeout_seconds: int = 3
    max_routes_per_run: int = 20
    probe_user_agent: str = "DataSmart-AI-Runtime-Provider-Health-Probe"
    startup_probe_enabled: bool = False


@dataclass(frozen=True)
class ModelProviderHealthProbeResult:
    """单个 Provider 主动探测结果。

    这里的 result 是低敏运维事实，不是模型调用结果。它只描述“健康探测是否成功、最终标记为什么状态”，
    不包含任何模型请求正文、响应正文、认证信息或租户数据。
    """

    provider_name: str
    provider_type: str
    route_workloads: tuple[str, ...]
    route_models: tuple[str, ...]
    probe_url: str | None
    outcome: str
    health_status: ModelProviderHealthStatus
    latency_ms: int | None = None
    error_code: str | None = None
    message: str = ""
    checked_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_summary(self) -> dict[str, object]:
        """转换为 API/诊断可直接返回的低敏摘要。"""

        return {
            "providerName": self.provider_name,
            "providerType": self.provider_type,
            "routeWorkloads": self.route_workloads,
            "routeModels": self.route_models,
            "probeUrl": self.probe_url,
            "outcome": self.outcome,
            "healthStatus": self.health_status.value,
            "latencyMs": self.latency_ms,
            "errorCode": self.error_code,
            "message": self.message,
            "checkedAt": self.checked_at.isoformat(),
        }


class ModelProviderHealthProbeService:
    """模型 Provider 主动健康探测编排器。

    该服务不直接参与模型路由；它只负责把外部 Provider 的健康探测结果写回 health registry。这样 5.20
    已经落地的路由评分逻辑无需改动：只要 registry 中状态更新，下一次 `ModelGatewayGovernanceService`
    决策就会自然使用新的 Provider 健康事实。
    """

    def __init__(
        self,
        health_registry: InMemoryModelProviderHealthRegistry,
        *,
        settings: ModelProviderHealthProbeSettings | None = None,
        transport: ModelProviderHealthProbeTransport | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._health_registry = health_registry
        self._settings = settings or ModelProviderHealthProbeSettings()
        self._transport = transport or urlopen
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._probe_run_count = 0
        self._probe_success_count = 0
        self._probe_failure_count = 0
        self._probe_skipped_count = 0
        self._last_run: dict[str, object] | None = None

    def probe_routes(
        self,
        routes: tuple[ModelRoute, ...],
        *,
        requested_by: str = "api",
        dry_run: bool = False,
    ) -> dict[str, object]:
        """主动探测一组模型路由对应的 Provider。

        执行流程：
        1. 按 providerName 去重，避免同一个 Provider 因多个 workload 重复探测；
        2. 只选择有 endpoint 且不是 DRY_RUN 的路由进行真实探测；
        3. dry-run 模式只返回将要探测的目标，不访问网络也不写回 registry；
        4. 非 dry-run 模式把 HEALTHY/DEGRADED/UNAVAILABLE 快照写回 health registry；
        5. 返回低敏运行摘要，供 API、运维台和后续指标系统读取。
        """

        self._probe_run_count += 1
        candidates = self._probe_candidates(routes)
        truncated = len(candidates) > self._settings.max_routes_per_run
        limited_candidates = candidates[: self._settings.max_routes_per_run]
        results = tuple(self._probe_candidate(candidate, dry_run=dry_run) for candidate in limited_candidates)
        self._probe_success_count += sum(1 for result in results if result.outcome == "success")
        self._probe_failure_count += sum(1 for result in results if result.outcome == "failure")
        self._probe_skipped_count += sum(1 for result in results if result.outcome == "skipped")
        summary = {
            "schemaVersion": "datasmart.model-provider-health-probe.v1",
            "component": "model-provider-health-probe",
            "requestedBy": requested_by,
            "dryRun": dry_run,
            "candidateProviderCount": len(candidates),
            "probedProviderCount": len(limited_candidates),
            "truncated": truncated,
            "successCount": sum(1 for result in results if result.outcome == "success"),
            "failureCount": sum(1 for result in results if result.outcome == "failure"),
            "skippedCount": sum(1 for result in results if result.outcome == "skipped"),
            "statusCounts": _status_counts(results),
            "results": tuple(result.to_summary() for result in results),
            "checkedAt": self._clock().isoformat(),
            "eventPayloadPolicy": "SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT_NO_SECRETS",
        }
        self._last_run = summary
        return summary

    def diagnostics(self) -> dict[str, object]:
        """返回主动探测组件的低基数诊断摘要。

        这里的 `metrics` 只使用 outcome 这类低基数维度，不把 providerName、URL、tenantId、projectId、
        runId 或 traceId 放进计数标签。后续接 Prometheus 时应延续这个原则，避免高基数指标拖垮监控系统。
        """

        return {
            "schemaVersion": "datasmart.model-provider-health-probe-diagnostics.v1",
            "component": "model-provider-health-probe",
            "startupProbeEnabled": self._settings.startup_probe_enabled,
            "timeoutSeconds": self._settings.timeout_seconds,
            "maxRoutesPerRun": self._settings.max_routes_per_run,
            "metrics": {
                "probeRunCount": self._probe_run_count,
                "probeSuccessCount": self._probe_success_count,
                "probeFailureCount": self._probe_failure_count,
                "probeSkippedCount": self._probe_skipped_count,
            },
            "lastRun": self._last_run,
            "recommendedActions": (
                "生产环境建议为真实 OpenAI-compatible、vLLM、SGLang 或内部模型网关配置 endpoint 与 healthCheckPath。",
                "不要把完整 URL、API Key、tenantId、projectId、runId 或 traceId 作为 Prometheus 标签。",
                "如果 Provider 长期 UNKNOWN，应启用主动探测、调用结果回写或 Prometheus 回灌至少一种健康事实来源。",
            ),
        }

    def _probe_candidates(self, routes: tuple[ModelRoute, ...]) -> tuple[tuple[ModelRoute, ...], ...]:
        """按 providerName 对路由去重并保留同 Provider 的 workload/model 摘要。"""

        grouped: dict[str, list[ModelRoute]] = {}
        for route in routes:
            grouped.setdefault(route.provider_name, []).append(route)
        return tuple(tuple(items) for items in grouped.values())

    def _probe_candidate(
        self,
        routes: tuple[ModelRoute, ...],
        *,
        dry_run: bool,
    ) -> ModelProviderHealthProbeResult:
        """探测单个 providerName 对应的一组路由。"""

        route = _first_probeable_route(routes) or routes[0]
        probe_url = _health_probe_url(route)
        route_workloads = tuple(item.workload.value for item in routes)
        route_models = tuple(item.model_name for item in routes)
        if route.provider_type == ProviderType.DRY_RUN:
            return self._skipped_result(
                route,
                route_workloads,
                route_models,
                probe_url,
                "DRY_RUN_PROVIDER_SKIPPED",
                "dry-run Provider 不需要主动健康探测，真实生产路由应配置 endpoint 后再启用探测。",
            )
        if probe_url is None:
            return self._skipped_result(
                route,
                route_workloads,
                route_models,
                None,
                "PROVIDER_ENDPOINT_MISSING",
                "模型路由未配置 endpoint，无法构造健康探测地址。",
            )
        if dry_run:
            return self._skipped_result(
                route,
                route_workloads,
                route_models,
                probe_url,
                "DRY_RUN_REQUESTED",
                "dry-run 模式只返回探测计划，不访问外部 Provider，也不写回健康注册表。",
            )

        started_at = time.perf_counter()
        request = Request(
            url=probe_url,
            headers={
                "Accept": "application/json",
                "User-Agent": self._settings.probe_user_agent,
                "X-DataSmart-Health-Probe": "true",
            },
            method="GET",
        )
        try:
            with self._transport(request, timeout=self._settings.timeout_seconds) as response:  # noqa: S310
                status_code = _response_status_code(response)
                latency_ms = int((time.perf_counter() - started_at) * 1000)
                return self._record_http_status(
                    route,
                    route_workloads,
                    route_models,
                    probe_url,
                    status_code,
                    latency_ms,
                )
        except HTTPError as exc:
            latency_ms = int((time.perf_counter() - started_at) * 1000)
            return self._record_http_status(
                route,
                route_workloads,
                route_models,
                probe_url,
                exc.code,
                latency_ms,
                error_code=f"PROBE_HTTP_{exc.code}",
            )
        except TimeoutError:
            latency_ms = int((time.perf_counter() - started_at) * 1000)
            return self._record_failure(route, route_workloads, route_models, probe_url, latency_ms, "PROBE_TIMEOUT")
        except URLError as exc:
            latency_ms = int((time.perf_counter() - started_at) * 1000)
            message = f"健康探测网络错误：{exc.reason}"
            return self._record_failure(
                route,
                route_workloads,
                route_models,
                probe_url,
                latency_ms,
                "PROBE_NETWORK_ERROR",
                message,
            )

    def _record_http_status(
        self,
        route: ModelRoute,
        route_workloads: tuple[str, ...],
        route_models: tuple[str, ...],
        probe_url: str,
        status_code: int,
        latency_ms: int,
        error_code: str | None = None,
    ) -> ModelProviderHealthProbeResult:
        """把 HTTP 状态码转换为 Provider 健康快照。"""

        health_status = _health_status_from_http(status_code)
        outcome = "success" if health_status == ModelProviderHealthStatus.HEALTHY else "failure"
        message = (
            f"主动健康探测成功，HTTP {status_code}，延迟 {latency_ms}ms。"
            if outcome == "success"
            else f"主动健康探测未通过，HTTP {status_code}，延迟 {latency_ms}ms。"
        )
        self._health_registry.mark(
            ModelProviderHealthSnapshot(
                provider_name=route.provider_name,
                status=health_status,
                latency_ms=latency_ms,
                checked_at=self._clock(),
                notes=message,
            )
        )
        return ModelProviderHealthProbeResult(
            provider_name=route.provider_name,
            provider_type=route.provider_type.value,
            route_workloads=route_workloads,
            route_models=route_models,
            probe_url=probe_url,
            outcome=outcome,
            health_status=health_status,
            latency_ms=latency_ms,
            error_code=error_code,
            message=message,
            checked_at=self._clock(),
        )

    def _record_failure(
        self,
        route: ModelRoute,
        route_workloads: tuple[str, ...],
        route_models: tuple[str, ...],
        probe_url: str,
        latency_ms: int,
        error_code: str,
        message: str | None = None,
    ) -> ModelProviderHealthProbeResult:
        """记录网络、超时等无法取得 HTTP 状态码的失败。"""

        final_message = message or f"主动健康探测失败，错误码 {error_code}，延迟 {latency_ms}ms。"
        self._health_registry.mark(
            ModelProviderHealthSnapshot(
                provider_name=route.provider_name,
                status=ModelProviderHealthStatus.UNAVAILABLE,
                latency_ms=latency_ms,
                checked_at=self._clock(),
                notes=final_message,
            )
        )
        return ModelProviderHealthProbeResult(
            provider_name=route.provider_name,
            provider_type=route.provider_type.value,
            route_workloads=route_workloads,
            route_models=route_models,
            probe_url=probe_url,
            outcome="failure",
            health_status=ModelProviderHealthStatus.UNAVAILABLE,
            latency_ms=latency_ms,
            error_code=error_code,
            message=final_message,
            checked_at=self._clock(),
        )

    def _skipped_result(
        self,
        route: ModelRoute,
        route_workloads: tuple[str, ...],
        route_models: tuple[str, ...],
        probe_url: str | None,
        error_code: str,
        message: str,
    ) -> ModelProviderHealthProbeResult:
        """构建跳过探测的结果，不写回 health registry。"""

        return ModelProviderHealthProbeResult(
            provider_name=route.provider_name,
            provider_type=route.provider_type.value,
            route_workloads=route_workloads,
            route_models=route_models,
            probe_url=probe_url,
            outcome="skipped",
            health_status=ModelProviderHealthStatus.UNKNOWN,
            error_code=error_code,
            message=message,
            checked_at=self._clock(),
        )


def model_provider_health_probe_settings_from_env(environ: dict[str, str] | None = None) -> ModelProviderHealthProbeSettings:
    """从环境变量构造主动健康探测配置。"""

    source = environ if environ is not None else os.environ
    return ModelProviderHealthProbeSettings(
        timeout_seconds=_positive_int(source.get("DATASMART_AI_MODEL_PROVIDER_HEALTH_PROBE_TIMEOUT_SECONDS"), 3),
        max_routes_per_run=_positive_int(source.get("DATASMART_AI_MODEL_PROVIDER_HEALTH_PROBE_MAX_ROUTES"), 20),
        startup_probe_enabled=_truthy(source.get("DATASMART_AI_MODEL_PROVIDER_HEALTH_PROBE_ON_STARTUP")),
    )


def _first_probeable_route(routes: tuple[ModelRoute, ...]) -> ModelRoute | None:
    """优先选择具备 endpoint 且不是 dry-run 的路由作为探测目标。"""

    for route in routes:
        if route.provider_type != ProviderType.DRY_RUN and route.endpoint:
            return route
    return None


def _health_probe_url(route: ModelRoute) -> str | None:
    """根据模型路由构造健康探测 URL，并移除 query/fragment。"""

    if not route.endpoint:
        return None
    health_path = route.health_check_path or "/health"
    if health_path.startswith("http://") or health_path.startswith("https://"):
        return _sanitize_url(health_path)
    endpoint = urlsplit(route.endpoint)
    if not endpoint.scheme or not endpoint.netloc:
        return None
    normalized_path = health_path if health_path.startswith("/") else f"/{health_path}"
    return _sanitize_url(urlunsplit((endpoint.scheme, endpoint.netloc, normalized_path, "", "")))


def _sanitize_url(url: str) -> str:
    """移除 URL 中的 query 与 fragment，避免 token、租户参数或调试参数进入诊断响应。"""

    parsed = urlsplit(url)
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path or "/", "", ""))


def _response_status_code(response: Any) -> int:
    """兼容 `urllib` 与测试 fake response 的状态码读取。"""

    if getattr(response, "status", None) is not None:
        return int(response.status)
    if hasattr(response, "getcode"):
        return int(response.getcode())
    return int(HTTPStatus.OK)


def _health_status_from_http(status_code: int) -> ModelProviderHealthStatus:
    """把 HTTP 状态码映射为模型 Provider 健康状态。"""

    if 200 <= status_code < 300:
        return ModelProviderHealthStatus.HEALTHY
    if status_code in {401, 403, 404, 429}:
        return ModelProviderHealthStatus.DEGRADED
    return ModelProviderHealthStatus.UNAVAILABLE


def _status_counts(results: tuple[ModelProviderHealthProbeResult, ...]) -> dict[str, int]:
    """生成低基数健康状态计数。"""

    return {
        status.value: sum(1 for result in results if result.health_status == status)
        for status in ModelProviderHealthStatus
    }


def _positive_int(value: str | None, default: int) -> int:
    """解析正整数环境变量，非法值回退默认值。"""

    if value is None or not value.strip():
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return parsed if parsed > 0 else default


def _truthy(value: str | None) -> bool:
    """解析布尔环境变量。"""

    if value is None:
        return False
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}
