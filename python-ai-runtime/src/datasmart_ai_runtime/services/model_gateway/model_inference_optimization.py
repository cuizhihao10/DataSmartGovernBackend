"""成熟推理服务优化诊断。

本模块用于关闭 `llm.inference-optimization` 的控制面缺口。这里的“推理优化”不是在
DataSmart 项目内自研 CUDA kernel、KV cache 调度器、微调或后训练，而是把成熟 serving
stack 的关键配置与观测指标纳入模型网关诊断：

- vLLM/SGLang 这类自托管推理服务需要关注 TTFT、TPS、queue time、batching、prefix/KV
  cache hit rate、KV cache 压力和 GPU 显存压力；
- LiteLLM 或企业模型网关更偏向统一 Provider 路由、限流、fallback、成本与审计，但仍需要把
  上游 serving 的低敏指标透传到治理面；
- 托管 OpenAI-compatible Provider 可以先进入路由与健康治理，但如果无法提供 cache/batch/queue
  指标，就不能被误标为“推理优化已经生产闭环”。

低敏边界：
- 只返回 providerName、modelName、workload、serving engine、指标名称、指标数值和建议动作；
- 不返回 endpoint、API Key、prompt、messages、SQL、工具参数、样本数据、模型输出、请求正文、
  真实 URL 或内部网络地址；
- 当前服务不主动访问任何外部模型网关，指标由调用方或未来 Prometheus/控制面适配器注入。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelLatencyTier,
    ModelRoute,
    ProviderType,
)
from datasmart_ai_runtime.services.model_gateway.model_inference_profiles import (
    ModelInferenceEngineProfile,
    default_inference_engine_profiles,
)


INFERENCE_OPTIMIZATION_SCHEMA_VERSION = "datasmart.model-inference-optimization.v1"
INFERENCE_OPTIMIZATION_PAYLOAD_POLICY = "LOW_SENSITIVE_INFERENCE_GOVERNANCE_ONLY"


class InferenceOptimizationStatus(str, Enum):
    """单条模型路由在推理优化控制面里的成熟度。

    这些状态刻意不使用简单 done/todo，因为当前项目的收敛目标是“知道还缺什么，并且能以稳定
    合同接入成熟组件”，而不是假装已经完成真实 GPU 压测。
    """

    DEVELOPMENT_ONLY = "development_only"
    CONTROL_PLANE_READY_METRICS_MISSING = "control_plane_ready_metrics_missing"
    NEEDS_TUNING = "needs_tuning"
    OVERLOADED = "overloaded"
    PRODUCTION_CANDIDATE = "production_candidate"


@dataclass(frozen=True)
class ModelInferenceServingPolicy:
    """推理服务诊断阈值策略。

    字段说明：
    - `interactive_ttft_p95_ms`：交互式会话可接受的首 token P95 延迟上限；
    - `standard_ttft_p95_ms`：普通 Agent 推理可接受的首 token P95 延迟上限；
    - `batch_queue_time_p95_ms`：批处理任务可接受的队列等待上限；
    - `max_queue_time_p95_ms`：交互/标准链路共用的队列等待硬阈值；
    - `min_cache_hit_rate`：当路由声明可缓存时，prefix/KV cache 命中率的最低建议值；
    - `max_kv_cache_usage_ratio`：KV cache 使用率过高时容易触发驱逐和尾延迟抖动；
    - `max_gpu_memory_usage_ratio`：GPU 显存压力过高时应降并发、扩容或调小 batch；
    - `max_waiting_requests`：等待队列过长时说明路由已接近拥塞，应启用 fallback 或排队降级。
    """

    interactive_ttft_p95_ms: int = 1200
    standard_ttft_p95_ms: int = 2500
    batch_queue_time_p95_ms: int = 10000
    max_queue_time_p95_ms: int = 5000
    min_cache_hit_rate: float = 0.20
    max_kv_cache_usage_ratio: float = 0.90
    max_gpu_memory_usage_ratio: float = 0.92
    max_waiting_requests: int = 64


@dataclass(frozen=True)
class ModelInferenceServingMetricsSnapshot:
    """单个 Provider 的成熟推理服务低敏指标快照。

    该对象代表“外部观测系统已经采集到的事实”，而不是本服务主动去抓取指标。未来可以由
    Prometheus、LiteLLM admin API、vLLM metrics endpoint、SGLang metrics endpoint 或 Java 控制面
    把这些字段注入进来。

    注意：这里故意不保存 metric endpoint、真实 URL、请求样本、prompt、模型输出或 GPU 机器名。
    """

    provider_name: str
    engine_profile_id: str | None = None
    ttft_ms_p50: int | None = None
    ttft_ms_p95: int | None = None
    tokens_per_second_p50: float | None = None
    queue_time_ms_p95: int | None = None
    cache_hit_rate: float | None = None
    prefix_cache_hit_rate: float | None = None
    kv_cache_usage_ratio: float | None = None
    active_batch_size: int | None = None
    max_batch_size: int | None = None
    running_requests: int | None = None
    waiting_requests: int | None = None
    gpu_memory_usage_ratio: float | None = None
    observed_at: str | None = None

    def available_metric_names(self) -> tuple[str, ...]:
        """返回当前快照里已经具备的指标名称。

        诊断服务用这个方法计算缺口，而不是在多个分支里重复判断 `None`。这使后续新增
        `prefillTokensPerSecond`、`decodeTokensPerSecond` 或 `speculativeDecodeAcceptedRate` 时更容易维护。
        """

        values = {
            "ttftMsP50": self.ttft_ms_p50,
            "ttftMsP95": self.ttft_ms_p95,
            "tokensPerSecondP50": self.tokens_per_second_p50,
            "queueTimeMsP95": self.queue_time_ms_p95,
            "cacheHitRate": self.cache_hit_rate,
            "prefixCacheHitRate": self.prefix_cache_hit_rate,
            "kvCacheUsageRatio": self.kv_cache_usage_ratio,
            "activeBatchSize": self.active_batch_size,
            "maxBatchSize": self.max_batch_size,
            "runningRequests": self.running_requests,
            "waitingRequests": self.waiting_requests,
            "gpuMemoryUsageRatio": self.gpu_memory_usage_ratio,
        }
        return tuple(name for name, value in values.items() if value is not None)

    def to_summary(self) -> dict[str, Any]:
        """输出低敏指标摘要。"""

        return {
            "providerName": self.provider_name,
            "engineProfileId": self.engine_profile_id,
            "ttftMsP50": self.ttft_ms_p50,
            "ttftMsP95": self.ttft_ms_p95,
            "tokensPerSecondP50": self.tokens_per_second_p50,
            "queueTimeMsP95": self.queue_time_ms_p95,
            "cacheHitRate": self.cache_hit_rate,
            "prefixCacheHitRate": self.prefix_cache_hit_rate,
            "kvCacheUsageRatio": self.kv_cache_usage_ratio,
            "activeBatchSize": self.active_batch_size,
            "maxBatchSize": self.max_batch_size,
            "runningRequests": self.running_requests,
            "waitingRequests": self.waiting_requests,
            "gpuMemoryUsageRatio": self.gpu_memory_usage_ratio,
            "observedAt": self.observed_at,
            "availableMetrics": self.available_metric_names(),
        }


@dataclass(frozen=True)
class ModelInferenceRouteDiagnostic:
    """单条模型路由的推理优化诊断结果。"""

    provider_name: str
    model_name: str
    workload: str
    provider_type: str
    engine_profile: ModelInferenceEngineProfile
    status: InferenceOptimizationStatus
    missing_metrics: tuple[str, ...] = ()
    issues: tuple[str, ...] = ()
    warnings: tuple[str, ...] = ()
    recommended_actions: tuple[str, ...] = ()
    metrics_snapshot: ModelInferenceServingMetricsSnapshot | None = None

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 可返回的低敏摘要。"""

        return {
            "providerName": self.provider_name,
            "modelName": self.model_name,
            "workload": self.workload,
            "providerType": self.provider_type,
            "engineProfile": self.engine_profile.to_summary(),
            "status": self.status.value,
            "missingMetrics": self.missing_metrics,
            "issues": self.issues,
            "warnings": self.warnings,
            "recommendedActions": self.recommended_actions,
            "metricsSnapshot": self.metrics_snapshot.to_summary() if self.metrics_snapshot else None,
        }


class ModelInferenceOptimizationDiagnosticsService:
    """成熟推理服务优化诊断服务。

    该服务只做三件事：
    1. 根据模型路由推断其当前 serving engine 画像；
    2. 对比该画像需要哪些低敏指标；
    3. 在有指标快照时判断 TTFT、TPS、queue、batch、cache 和 GPU/KV 压力是否健康。

    它不发起 HTTP 请求、不读取 Prometheus、不访问模型、不执行 benchmark。这样可以先把 DataSmart
    内部“应该如何接入成熟推理优化”的合同固定下来，后续再把真实指标源替换进来。
    """

    def __init__(
        self,
        *,
        engine_profiles: tuple[ModelInferenceEngineProfile, ...] | None = None,
        policy: ModelInferenceServingPolicy | None = None,
    ) -> None:
        self._profiles = engine_profiles or default_inference_engine_profiles()
        self._profiles_by_id = {profile.profile_id: profile for profile in self._profiles}
        self._policy = policy or ModelInferenceServingPolicy()

    def diagnostics(
        self,
        routes: tuple[ModelRoute, ...],
        metrics_snapshots: tuple[ModelInferenceServingMetricsSnapshot, ...] = (),
    ) -> dict[str, Any]:
        """生成推理优化控制面诊断。

        参数说明：
        - `routes`：当前模型路由表，通常来自 `ModelRouteRegistry.all_routes()`；
        - `metrics_snapshots`：外部观测系统注入的低敏指标，当前默认可以为空。

        返回值说明：
        - `routeDiagnostics` 按路由逐条给出状态、缺失指标、问题码和下一步；
        - `engineProfiles` 固化 vLLM/SGLang/LiteLLM/OpenAI-compatible 等成熟路线需要观测什么；
        - `strategyBoundary` 再次声明项目不做自研推理内核，避免后续路线漂移。
        """

        metrics_by_provider = {snapshot.provider_name: snapshot for snapshot in metrics_snapshots}
        route_diagnostics = tuple(
            self.assess_route(route, metrics_by_provider.get(route.provider_name))
            for route in routes
        )
        return {
            "schemaVersion": INFERENCE_OPTIMIZATION_SCHEMA_VERSION,
            "diagnosticType": "MODEL_INFERENCE_OPTIMIZATION_DIAGNOSTICS",
            "payloadPolicy": INFERENCE_OPTIMIZATION_PAYLOAD_POLICY,
            "strategyBoundary": (
                "DataSmart 只接入成熟推理服务的配置、健康和指标诊断；不在项目内自研推理内核、"
                "微调、后训练、KV cache 调度器或 GPU kernel。"
            ),
            "sensitiveDataPolicy": (
                "诊断不返回 endpoint、API Key、prompt、messages、SQL、工具参数、样本数据、模型输出、"
                "真实 URL 或内部网络地址。"
            ),
            "routeCount": len(routes),
            "metricsSnapshotCount": len(metrics_snapshots),
            "engineProfileCount": len(self._profiles),
            "statusCounts": _status_counts(route_diagnostics),
            "engineProfiles": tuple(profile.to_summary() for profile in self._profiles),
            "routeDiagnostics": tuple(item.to_summary() for item in route_diagnostics),
            "recommendedConvergenceRoute": (
                "下一步优先把 Prometheus/vLLM/SGLang/LiteLLM 的低敏指标接入本服务，再做基准压测和 "
                "permission-admin 套餐策略；不要在业务仓库内研发模型训练或底层推理优化。"
            ),
        }

    def assess_route(
        self,
        route: ModelRoute,
        metrics_snapshot: ModelInferenceServingMetricsSnapshot | None = None,
    ) -> ModelInferenceRouteDiagnostic:
        """评估单条路由是否具备成熟推理服务优化诊断条件。"""

        profile = self._resolve_profile(route, metrics_snapshot)
        missing_metrics = _missing_metrics(profile, metrics_snapshot)
        issues: list[str] = []
        warnings: list[str] = []
        actions: list[str] = []

        if route.provider_type == ProviderType.DRY_RUN:
            warnings.append("DRY_RUN_ROUTE_HAS_NO_REAL_INFERENCE_SERVICE")
            actions.append("生产前必须替换为真实 OpenAI-compatible、vLLM、SGLang、LiteLLM 或企业模型网关路由。")
            status = InferenceOptimizationStatus.DEVELOPMENT_ONLY
        elif metrics_snapshot is None or missing_metrics:
            warnings.extend(_missing_metric_warnings(missing_metrics))
            actions.append("接入 Prometheus、serving metrics endpoint 或企业模型网关指标回灌，补齐 TTFT/TPS/queue/cache/batch 观测。")
            status = InferenceOptimizationStatus.CONTROL_PLANE_READY_METRICS_MISSING
        else:
            status = self._evaluate_metrics(route, metrics_snapshot, issues, warnings, actions)

        if profile.profile_id == "openai-compatible-provider":
            warnings.append("OPENAI_COMPATIBLE_ENGINE_NEEDS_DEPLOYMENT_METADATA")
            actions.append("若背后是 LiteLLM、vLLM 或 SGLang，应在控制面声明 engineProfileId，避免只能按通用 Provider 诊断。")

        return ModelInferenceRouteDiagnostic(
            provider_name=route.provider_name,
            model_name=route.model_name,
            workload=route.workload.value,
            provider_type=route.provider_type.value,
            engine_profile=profile,
            status=status,
            missing_metrics=missing_metrics,
            issues=tuple(dict.fromkeys(issues)),
            warnings=tuple(dict.fromkeys(warnings)),
            recommended_actions=tuple(dict.fromkeys(actions)),
            metrics_snapshot=metrics_snapshot,
        )

    def _resolve_profile(
        self,
        route: ModelRoute,
        metrics_snapshot: ModelInferenceServingMetricsSnapshot | None,
    ) -> ModelInferenceEngineProfile:
        """解析路由对应的 serving engine 画像。"""

        if metrics_snapshot and metrics_snapshot.engine_profile_id in self._profiles_by_id:
            return self._profiles_by_id[str(metrics_snapshot.engine_profile_id)]
        provider_name = route.provider_name.lower()
        if "litellm" in provider_name:
            return self._profiles_by_id["litellm-gateway"]
        if route.provider_type == ProviderType.VLLM:
            return self._profiles_by_id["vllm"]
        if route.provider_type == ProviderType.SGLANG:
            return self._profiles_by_id["sglang"]
        if route.provider_type == ProviderType.PYTHON_LOCAL:
            return self._profiles_by_id["python-local"]
        if route.provider_type == ProviderType.DRY_RUN:
            return self._profiles_by_id["dry-run"]
        return self._profiles_by_id["openai-compatible-provider"]

    def _evaluate_metrics(
        self,
        route: ModelRoute,
        metrics: ModelInferenceServingMetricsSnapshot,
        issues: list[str],
        warnings: list[str],
        actions: list[str],
    ) -> InferenceOptimizationStatus:
        """根据低敏指标判断推理服务是否已具备生产候选条件。"""

        ttft_limit = self._ttft_limit(route.latency_tier)
        if metrics.ttft_ms_p95 is not None and metrics.ttft_ms_p95 > ttft_limit:
            issues.append("TTFT_P95_EXCEEDS_LATENCY_TIER_TARGET")
            actions.append("降低输入 token、启用 prefix cache、增加并发副本或把交互式流量切到低延迟路由。")
        queue_limit = self._queue_limit(route.latency_tier)
        if metrics.queue_time_ms_p95 is not None and metrics.queue_time_ms_p95 > queue_limit:
            issues.append("QUEUE_TIME_P95_TOO_HIGH")
            actions.append("检查 waiting queue、GPU worker 数、max batch tokens、并发上限和 fallback 策略。")
        if metrics.waiting_requests is not None and metrics.waiting_requests > self._policy.max_waiting_requests:
            issues.append("WAITING_REQUESTS_TOO_HIGH")
            actions.append("启用排队降级、限流或扩容，避免 Agent loop 被模型队列长期阻塞。")
        cache_hit_rate = metrics.prefix_cache_hit_rate if metrics.prefix_cache_hit_rate is not None else metrics.cache_hit_rate
        if route.cache_key_scope != ModelCacheKeyScope.NO_CACHE and cache_hit_rate is not None:
            if cache_hit_rate < self._policy.min_cache_hit_rate:
                warnings.append("CACHE_HIT_RATE_BELOW_EXPECTATION")
                actions.append("复核 cache namespace、系统提示稳定性、工具 schema 稳定性和租户/项目/会话隔离边界。")
        if metrics.kv_cache_usage_ratio is not None and metrics.kv_cache_usage_ratio > self._policy.max_kv_cache_usage_ratio:
            issues.append("KV_CACHE_PRESSURE_TOO_HIGH")
            actions.append("降低长上下文并发、缩短上下文、增加 GPU 资源或调整 serving cache 配额。")
        if metrics.gpu_memory_usage_ratio is not None and metrics.gpu_memory_usage_ratio > self._policy.max_gpu_memory_usage_ratio:
            issues.append("GPU_MEMORY_PRESSURE_TOO_HIGH")
            actions.append("降低 batch、启用量化/并行策略、增加副本或把批处理流量迁出交互路由。")

        if any(issue in issues for issue in ("QUEUE_TIME_P95_TOO_HIGH", "WAITING_REQUESTS_TOO_HIGH")):
            return InferenceOptimizationStatus.OVERLOADED
        if issues or warnings:
            return InferenceOptimizationStatus.NEEDS_TUNING
        actions.append("指标满足当前控制面阈值，可以进入真实 benchmark/eval、灰度和成本基线验证。")
        return InferenceOptimizationStatus.PRODUCTION_CANDIDATE

    def _ttft_limit(self, latency_tier: ModelLatencyTier) -> int:
        """根据路由延迟等级返回 TTFT P95 上限。"""

        if latency_tier == ModelLatencyTier.INTERACTIVE:
            return self._policy.interactive_ttft_p95_ms
        return self._policy.standard_ttft_p95_ms

    def _queue_limit(self, latency_tier: ModelLatencyTier) -> int:
        """根据路由延迟等级返回队列等待 P95 上限。"""

        if latency_tier == ModelLatencyTier.BATCH:
            return self._policy.batch_queue_time_p95_ms
        return self._policy.max_queue_time_p95_ms


def default_inference_optimization_diagnostics_service() -> ModelInferenceOptimizationDiagnosticsService:
    """构建默认推理优化诊断服务。"""

    return ModelInferenceOptimizationDiagnosticsService()


def _missing_metrics(
    profile: ModelInferenceEngineProfile,
    metrics_snapshot: ModelInferenceServingMetricsSnapshot | None,
) -> tuple[str, ...]:
    """计算指定画像缺少哪些关键指标。"""

    if metrics_snapshot is None:
        return profile.expected_metrics
    available = set(metrics_snapshot.available_metric_names())
    return tuple(metric for metric in profile.expected_metrics if metric not in available)


def _missing_metric_warnings(missing_metrics: tuple[str, ...]) -> tuple[str, ...]:
    """把缺失指标转换成稳定 warning code。"""

    if not missing_metrics:
        return ()
    return tuple(f"INFERENCE_METRIC_MISSING_{metric}" for metric in missing_metrics)


def _status_counts(route_diagnostics: tuple[ModelInferenceRouteDiagnostic, ...]) -> dict[str, int]:
    """统计各推理优化状态数量。"""

    return {
        status.value: sum(1 for item in route_diagnostics if item.status == status)
        for status in InferenceOptimizationStatus
    }


__all__ = [
    "INFERENCE_OPTIMIZATION_PAYLOAD_POLICY",
    "INFERENCE_OPTIMIZATION_SCHEMA_VERSION",
    "InferenceOptimizationStatus",
    "ModelInferenceEngineProfile",
    "ModelInferenceOptimizationDiagnosticsService",
    "ModelInferenceRouteDiagnostic",
    "ModelInferenceServingMetricsSnapshot",
    "ModelInferenceServingPolicy",
    "default_inference_engine_profiles",
    "default_inference_optimization_diagnostics_service",
]
