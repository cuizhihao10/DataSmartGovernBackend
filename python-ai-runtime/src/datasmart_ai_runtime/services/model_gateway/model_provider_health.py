"""模型 Provider 健康治理与熔断台账。

模型网关如果只会“按配置表选择 Provider”，在真实生产环境里会非常脆弱：主模型可能因为 GPU 队列、
供应商限流、网关升级、网络抖动或上下文过长而连续失败。如果没有健康台账，Agent 每次仍会优先打到
已经故障的 Provider，造成用户等待、预算浪费和工具链路反复降级。

本模块提供一个轻量、可替换的内存实现：
- 记录最近一段 Provider 调用结果；
- 根据连续失败、错误率和延迟把 Provider 标记为 healthy/degraded/unavailable；
- 连续失败达到阈值后打开熔断窗口，让模型路由自动跳过该 Provider；
- 输出低敏诊断摘要，供 FastAPI 诊断接口、前端运维卡片和后续 Prometheus 指标复用。

它暂时不直接访问真实 `/health` endpoint，也不依赖 Prometheus。这样做是为了先固定“调用结果如何影响
路由”的产品契约，后续可以把数据来源替换为 Redis、MySQL、Prometheus、服务网格或 Java 控制面。
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Callable

from datasmart_ai_runtime.domain.contracts import ModelRoute
from datasmart_ai_runtime.domain.model_gateway import (
    ModelProviderHealthSnapshot,
    ModelProviderHealthStatus,
)


@dataclass(frozen=True)
class ModelProviderHealthPolicy:
    """模型 Provider 健康判定策略。

    字段说明：
    - `recent_window_size`：保留最近多少次调用结果。窗口太小会被偶发错误影响，太大又会让恢复速度变慢；
    - `failure_threshold`：连续失败达到多少次后打开熔断。连续失败比总错误率更适合保护实时会话体验；
    - `degraded_error_rate_threshold`：窗口内错误率达到该比例时标记 degraded，仍可作为 fallback 候选；
    - `unavailable_error_rate_threshold`：窗口内错误率达到该比例时标记 unavailable，路由应跳过；
    - `min_error_rate_sample_size`：错误率阈值生效所需最小样本数，避免单次失败就因为 100% 错误率被判死；
    - `degraded_latency_ms`：平均延迟超过该值时标记 degraded，用于识别“能用但太慢”的 Provider；
    - `unavailable_latency_ms`：严重延迟告警阈值；成功调用即使超过该值也只标记 degraded，不能仅因
      xhigh/长推理请求较慢就把唯一可用 Provider 永久踢出路由；
    - `circuit_breaker_cooldown_seconds`：熔断打开后的冷却时间。冷却期内不继续打主 Provider，给服务恢复时间。
    """

    recent_window_size: int = 20
    failure_threshold: int = 3
    degraded_error_rate_threshold: float = 0.25
    unavailable_error_rate_threshold: float = 0.6
    min_error_rate_sample_size: int = 3
    degraded_latency_ms: int = 3_000
    unavailable_latency_ms: int = 15_000
    circuit_breaker_cooldown_seconds: int = 60


@dataclass(frozen=True)
class ModelProviderInvocationHealthEvent:
    """一次模型调用对 Provider 健康的影响事实。

    这里刻意不保存 prompt、工具参数、响应正文或用户输入，只保存健康治理所需的低敏字段。商业化产品中，
    Provider 健康诊断通常会被运维、客服、租户管理员甚至自动告警系统读取，因此不能把模型上下文泄漏到
    诊断面板。
    """

    provider_name: str
    succeeded: bool
    latency_ms: int | None = None
    error_code: str | None = None
    recorded_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


@dataclass
class _ProviderRuntimeStats:
    """单个 Provider 的运行时健康统计。

    该对象只在内存 registry 内部使用，因此允许可变字段。外部暴露时会转换为
    `ModelProviderHealthSnapshot` 和 diagnostics dict，避免调用方直接修改统计状态。
    """

    recent_events: deque[ModelProviderInvocationHealthEvent]
    consecutive_failures: int = 0
    circuit_open_until: datetime | None = None


class InMemoryModelProviderHealthRegistry:
    """内存版 Provider 健康注册表。

    生产环境中，健康状态可能来自 Prometheus 指标、网关 `/health`、最近 N 次模型调用错误率、
    GPU 队列长度或人工熔断开关。这里先用内存统计，是为了让模型网关路由策略可测试、可解释，
    后续替换为 Redis、数据库或观测系统时不影响决策契约。
    """

    def __init__(
        self,
        snapshots: tuple[ModelProviderHealthSnapshot, ...] = (),
        *,
        policy: ModelProviderHealthPolicy | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._policy = policy or ModelProviderHealthPolicy()
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._snapshots = {snapshot.provider_name: snapshot for snapshot in snapshots}
        self._stats: dict[str, _ProviderRuntimeStats] = {}

    def mark(self, snapshot: ModelProviderHealthSnapshot) -> None:
        """人工或外部探针更新某个 Provider 的健康快照。

        `mark(...)` 适合运维手动熔断、外部健康探针回灌或测试构造固定状态。若手动标记为 HEALTHY，
        这里会清理内存熔断窗口，表示运维已经确认服务恢复；若标记为 UNAVAILABLE，则路由会立即跳过。
        """

        self._snapshots[snapshot.provider_name] = snapshot
        stats = self._stats.get(snapshot.provider_name)
        if stats is not None and snapshot.status == ModelProviderHealthStatus.HEALTHY:
            stats.consecutive_failures = 0
            stats.circuit_open_until = None

    def record_invocation(
        self,
        provider_name: str,
        *,
        succeeded: bool,
        latency_ms: int | None = None,
        error_code: str | None = None,
        recorded_at: datetime | None = None,
    ) -> ModelProviderHealthSnapshot:
        """记录一次 Provider 调用结果并返回新的健康快照。

        调用结果是最接近用户体验的健康信号：即使 Provider 的 `/health` 返回正常，只要真实模型调用连续
        超时或 5xx，Agent 路由也应该尽快降级。这里的判定规则保持简单透明，方便学习和后续迁移：
        1. 先把结果写入最近窗口；
        2. 更新连续失败计数；
        3. 连续失败超过阈值时打开熔断冷却期；
        4. 重新计算错误率、平均延迟和最终状态。
        """

        event = ModelProviderInvocationHealthEvent(
            provider_name=provider_name,
            succeeded=succeeded,
            latency_ms=max(latency_ms, 0) if latency_ms is not None else None,
            error_code=error_code,
            recorded_at=recorded_at or self._clock(),
        )
        stats = self._stats_for(provider_name)
        stats.recent_events.append(event)
        if succeeded:
            stats.consecutive_failures = 0
            if stats.circuit_open_until and event.recorded_at >= stats.circuit_open_until:
                stats.circuit_open_until = None
        else:
            stats.consecutive_failures += 1
            if stats.consecutive_failures >= max(self._policy.failure_threshold, 1):
                stats.circuit_open_until = event.recorded_at + timedelta(
                    seconds=max(self._policy.circuit_breaker_cooldown_seconds, 1)
                )

        snapshot = self._snapshot_from_stats(provider_name, now=event.recorded_at)
        self._snapshots[provider_name] = snapshot
        return snapshot

    def snapshot_for(self, route: ModelRoute) -> ModelProviderHealthSnapshot:
        """读取路由对应的健康快照。

        没有快照时返回 `UNKNOWN`，而不是直接判定不可用。这样本地开发和刚启动的环境不会因为暂未
        上报健康状态就完全不可用；生产环境可以通过诊断接口和告警要求 Provider 必须定期上报。
        """

        return self._snapshot_for_provider_name(route.provider_name, now=self._clock())

    def diagnostics(self, routes: tuple[ModelRoute, ...] = ()) -> dict[str, object]:
        """生成模型 Provider 健康诊断摘要。

        诊断摘要只包含 provider/model/workload/状态/错误率/延迟/熔断窗口等低敏字段，不包含任何 prompt、
        响应正文、工具参数或租户数据。它的目标是让前端治理卡片和运维面板知道“为什么发生 fallback”，
        而不是替代完整日志或审计链。
        """

        now = self._clock()
        provider_names = set(self._snapshots) | set(self._stats) | {route.provider_name for route in routes}
        providers = tuple(
            self._provider_diagnostics(provider_name, routes=routes, now=now)
            for provider_name in sorted(provider_names)
        )
        status_counts = {
            status.value: sum(1 for item in providers if item["status"] == status.value)
            for status in ModelProviderHealthStatus
        }
        circuit_open_count = sum(1 for item in providers if item["circuitOpen"])
        return {
            "schemaVersion": "datasmart.model-provider-health.v1",
            "overallStatus": self._overall_status(status_counts),
            "providerCount": len(providers),
            "healthyCount": status_counts[ModelProviderHealthStatus.HEALTHY.value],
            "degradedCount": status_counts[ModelProviderHealthStatus.DEGRADED.value],
            "unavailableCount": status_counts[ModelProviderHealthStatus.UNAVAILABLE.value],
            "unknownCount": status_counts[ModelProviderHealthStatus.UNKNOWN.value],
            "circuitOpenCount": circuit_open_count,
            "providers": providers,
            "operationalWarnings": self._operational_warnings(status_counts, circuit_open_count),
            "recommendedActions": self._recommended_actions(status_counts, circuit_open_count),
        }

    def _stats_for(self, provider_name: str) -> _ProviderRuntimeStats:
        """读取或创建 Provider 运行时统计对象。"""

        if provider_name not in self._stats:
            self._stats[provider_name] = _ProviderRuntimeStats(
                recent_events=deque(maxlen=max(self._policy.recent_window_size, 1))
            )
        return self._stats[provider_name]

    def _snapshot_for_provider_name(self, provider_name: str, *, now: datetime) -> ModelProviderHealthSnapshot:
        """按 providerName 读取快照，并优先应用未过期熔断窗口。"""

        stats = self._stats.get(provider_name)
        if stats is not None and stats.circuit_open_until and now < stats.circuit_open_until:
            return self._snapshot_from_stats(provider_name, now=now)
        return self._snapshots.get(
            provider_name,
            ModelProviderHealthSnapshot(
                provider_name=provider_name,
                status=ModelProviderHealthStatus.UNKNOWN,
                checked_at=now,
                notes="尚未收到 Provider 健康快照或真实调用结果。",
            ),
        )

    def _snapshot_from_stats(self, provider_name: str, *, now: datetime) -> ModelProviderHealthSnapshot:
        """根据最近调用窗口计算健康快照。"""

        stats = self._stats_for(provider_name)
        recent_events = tuple(stats.recent_events)
        failure_rate = _failure_rate(recent_events)
        average_latency = _average_latency(recent_events)
        circuit_open = stats.circuit_open_until is not None and now < stats.circuit_open_until
        # 错误率是窗口统计，必须满足最小样本数后再用于 unavailable/degraded 判定。
        # 否则一次 503 会让错误率变成 100%，模型网关会过早把 Provider 判死，造成不必要的 fallback 抖动。
        enough_error_rate_samples = len(recent_events) >= max(self._policy.min_error_rate_sample_size, 1)
        if circuit_open:
            status = ModelProviderHealthStatus.UNAVAILABLE
        elif enough_error_rate_samples and failure_rate >= self._policy.unavailable_error_rate_threshold:
            status = ModelProviderHealthStatus.UNAVAILABLE
        # 延迟只能证明 Provider “慢”，不能证明它“不可用”。尤其是 xhigh reasoning 或长上下文模型，
        # 一次成功调用很可能超过 15 秒；如果据此直接标记 UNAVAILABLE，下一轮路由会跳过该 Provider，
        # 且因为再也没有真实调用样本而无法自动恢复。硬不可用只由熔断或达到最小样本量的失败率判定，
        # 严重高延迟保留为 DEGRADED，让路由仍可选择它并继续积累健康事实。
        elif average_latency is not None and average_latency >= self._policy.unavailable_latency_ms:
            status = ModelProviderHealthStatus.DEGRADED
        elif enough_error_rate_samples and failure_rate >= self._policy.degraded_error_rate_threshold:
            status = ModelProviderHealthStatus.DEGRADED
        elif average_latency is not None and average_latency >= self._policy.degraded_latency_ms:
            status = ModelProviderHealthStatus.DEGRADED
        elif stats.consecutive_failures > 0:
            status = ModelProviderHealthStatus.DEGRADED
        else:
            status = ModelProviderHealthStatus.HEALTHY

        return ModelProviderHealthSnapshot(
            provider_name=provider_name,
            status=status,
            latency_ms=int(average_latency) if average_latency is not None else None,
            error_rate=failure_rate,
            checked_at=now,
            notes=self._snapshot_notes(stats, recent_events, failure_rate, average_latency, now),
        )

    def _provider_diagnostics(
        self,
        provider_name: str,
        *,
        routes: tuple[ModelRoute, ...],
        now: datetime,
    ) -> dict[str, object]:
        """生成单个 Provider 的诊断行。"""

        snapshot = self._snapshot_for_provider_name(provider_name, now=now)
        stats = self._stats.get(provider_name)
        provider_routes = tuple(route for route in routes if route.provider_name == provider_name)
        circuit_open_until = stats.circuit_open_until if stats and stats.circuit_open_until else None
        circuit_open = circuit_open_until is not None and now < circuit_open_until
        return {
            "providerName": provider_name,
            "status": snapshot.status.value,
            "latencyMs": snapshot.latency_ms,
            "errorRate": snapshot.error_rate,
            "checkedAt": snapshot.checked_at.isoformat(),
            "notes": snapshot.notes,
            "circuitOpen": circuit_open,
            "circuitOpenUntil": circuit_open_until.isoformat() if circuit_open_until else None,
            "consecutiveFailures": stats.consecutive_failures if stats else 0,
            "recentSampleCount": len(stats.recent_events) if stats else 0,
            "routeWorkloads": tuple(route.workload.value for route in provider_routes),
            "routeModels": tuple(route.model_name for route in provider_routes),
            "recommendedActions": self._provider_actions(snapshot.status, circuit_open),
        }

    def _snapshot_notes(
        self,
        stats: _ProviderRuntimeStats,
        recent_events: tuple[ModelProviderInvocationHealthEvent, ...],
        failure_rate: float,
        average_latency: float | None,
        now: datetime,
    ) -> str:
        """构建人读健康说明，方便前端和运维理解状态来源。"""

        base = (
            f"最近 {len(recent_events)} 次模型调用失败率 {failure_rate:.2%}，"
            f"平均延迟 {int(average_latency) if average_latency is not None else '未知'}ms，"
            f"连续失败 {stats.consecutive_failures} 次。"
        )
        if stats.circuit_open_until and now < stats.circuit_open_until:
            return base + f" 熔断已打开，将持续到 {stats.circuit_open_until.isoformat()}。"
        return base

    @staticmethod
    def _overall_status(status_counts: dict[str, int]) -> str:
        """计算整体健康状态，供治理卡片快速展示。"""

        if status_counts.get(ModelProviderHealthStatus.UNAVAILABLE.value, 0) > 0:
            return "partial_outage"
        if status_counts.get(ModelProviderHealthStatus.DEGRADED.value, 0) > 0:
            return "degraded"
        if status_counts.get(ModelProviderHealthStatus.UNKNOWN.value, 0) > 0:
            return "unknown"
        return "healthy"

    @staticmethod
    def _operational_warnings(status_counts: dict[str, int], circuit_open_count: int) -> tuple[str, ...]:
        """生成低敏运维告警说明。"""

        warnings: list[str] = []
        if circuit_open_count > 0:
            warnings.append(f"当前有 {circuit_open_count} 个模型 Provider 处于熔断冷却期。")
        if status_counts.get(ModelProviderHealthStatus.UNAVAILABLE.value, 0) > 0:
            warnings.append("存在不可用 Provider，模型网关应确认 fallback 路由和降级提示是否可用。")
        if status_counts.get(ModelProviderHealthStatus.DEGRADED.value, 0) > 0:
            warnings.append("存在降级 Provider，建议关注延迟、错误率、GPU 队列或供应商限流。")
        if status_counts.get(ModelProviderHealthStatus.UNKNOWN.value, 0) > 0:
            warnings.append("存在未知健康状态 Provider，生产环境应接入真实健康探测或调用结果回灌。")
        if not warnings:
            warnings.append("未发现明显 Provider 健康缺口。")
        return tuple(warnings)

    @staticmethod
    def _recommended_actions(status_counts: dict[str, int], circuit_open_count: int) -> tuple[str, ...]:
        """生成模型网关下一步治理建议。"""

        actions: list[str] = []
        if circuit_open_count > 0:
            actions.append("保持主路由熔断冷却，优先验证 fallback 模型质量、预算和上下文长度是否满足当前工作负载。")
        if status_counts.get(ModelProviderHealthStatus.UNAVAILABLE.value, 0) > 0:
            actions.append("排查不可用 Provider 的 API Key、endpoint、部署健康、GPU 资源、队列积压和供应商限流。")
        if status_counts.get(ModelProviderHealthStatus.DEGRADED.value, 0) > 0:
            actions.append("为降级 Provider 增加延迟告警、错误率告警和并发保护，必要时降低其路由优先级。")
        if status_counts.get(ModelProviderHealthStatus.UNKNOWN.value, 0) > 0:
            actions.append("为生产环境配置真实健康探测、Prometheus 回灌或模型调用结果上报，避免长期 UNKNOWN。")
        if not actions:
            actions.append("可以继续推进真实 Provider 调用、KV cache 命中率和多模型质量评估。")
        return tuple(actions)

    @staticmethod
    def _provider_actions(status: ModelProviderHealthStatus, circuit_open: bool) -> tuple[str, ...]:
        """生成单 Provider 诊断建议。"""

        if circuit_open:
            return ("等待熔断冷却结束，期间确认 fallback 路由是否可用。",)
        if status == ModelProviderHealthStatus.UNAVAILABLE:
            return ("暂时跳过该 Provider，并排查 endpoint、认证、部署健康和上游限流。",)
        if status == ModelProviderHealthStatus.DEGRADED:
            return ("保留为次优候选，同时观察延迟、错误率和队列积压趋势。",)
        if status == ModelProviderHealthStatus.UNKNOWN:
            return ("补充健康探测或真实调用结果回灌，避免生产路由长期依赖未知状态。",)
        return ("当前 Provider 可作为正常候选。",)


def _failure_rate(events: tuple[ModelProviderInvocationHealthEvent, ...]) -> float:
    """计算最近窗口错误率。"""

    if not events:
        return 0.0
    failed = sum(1 for event in events if not event.succeeded)
    return failed / len(events)


def _average_latency(events: tuple[ModelProviderInvocationHealthEvent, ...]) -> float | None:
    """计算最近窗口平均延迟。"""

    values = tuple(event.latency_ms for event in events if event.latency_ms is not None)
    if not values:
        return None
    return sum(values) / len(values)
