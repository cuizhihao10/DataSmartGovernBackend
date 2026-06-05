"""模型 Provider 主动健康探测 Prometheus 指标渲染器。

本模块承接 `model_provider_health_probe.py` 的诊断输出，但刻意不放在探测服务本身：
- 探测服务负责“访问谁、如何判断健康、如何回写 health registry”；
- 指标渲染器负责“哪些聚合事实可以暴露给 Prometheus、哪些明细必须留在诊断或审计链路”；
- API 层只负责把多类指标拼接到 `/agent/metrics`，不理解每个指标的业务含义。

这样拆分可以避免 `ModelProviderHealthProbeService` 接近 500 行后继续膨胀，也让后续替换为
`prometheus_client`、OpenTelemetry exporter 或多进程 collector 时，只需要替换这一层。

指标安全原则：
- 允许：outcome、health status、startup enabled、最近一轮是否截断等有限枚举/布尔/计数；
- 禁止：providerName、完整 URL、tenantId、projectId、sessionId、runId、traceId、prompt、工具参数、
  模型输出、API Key、真实 KV cache 内容；
- 单个 Provider 的排障继续走 `/agent/models/provider-health/diagnostics` 或 runtime event，而不是
  Prometheus label。Prometheus 更适合回答“某类问题是否升高”，不适合作为业务明细数据库。
"""

from __future__ import annotations

from typing import Any, Iterable


_OUTCOMES = ("success", "failure", "skipped")
_HEALTH_STATUSES = ("healthy", "degraded", "unavailable", "unknown")

_METRIC_HELP = {
    "datasmart_ai_model_provider_health_probe_runs_total": (
        "Total explicit model provider health probe runs."
    ),
    "datasmart_ai_model_provider_health_probe_outcomes_total": (
        "Total model provider health probe outcomes grouped by bounded outcome."
    ),
    "datasmart_ai_model_provider_health_probe_startup_enabled": (
        "Whether startup provider health probing is enabled for this Python AI Runtime process."
    ),
    "datasmart_ai_model_provider_health_probe_timeout_seconds": (
        "Configured timeout in seconds for a single model provider health probe request."
    ),
    "datasmart_ai_model_provider_health_probe_max_routes_per_run": (
        "Configured maximum provider candidates inspected by a single health probe run."
    ),
    "datasmart_ai_model_provider_health_probe_last_run_candidates": (
        "Provider candidate count observed in the latest health probe run."
    ),
    "datasmart_ai_model_provider_health_probe_last_run_probed": (
        "Provider candidate count actually inspected in the latest health probe run after the per-run limit."
    ),
    "datasmart_ai_model_provider_health_probe_last_run_truncated": (
        "Whether the latest health probe run was truncated by the configured per-run limit."
    ),
    "datasmart_ai_model_provider_health_probe_last_run_outcomes": (
        "Latest health probe run outcome counts grouped by bounded outcome."
    ),
    "datasmart_ai_model_provider_health_probe_last_run_status_providers": (
        "Latest health probe run provider counts grouped by bounded health status."
    ),
}

_METRIC_TYPES = {
    "datasmart_ai_model_provider_health_probe_runs_total": "counter",
    "datasmart_ai_model_provider_health_probe_outcomes_total": "counter",
    "datasmart_ai_model_provider_health_probe_startup_enabled": "gauge",
    "datasmart_ai_model_provider_health_probe_timeout_seconds": "gauge",
    "datasmart_ai_model_provider_health_probe_max_routes_per_run": "gauge",
    "datasmart_ai_model_provider_health_probe_last_run_candidates": "gauge",
    "datasmart_ai_model_provider_health_probe_last_run_probed": "gauge",
    "datasmart_ai_model_provider_health_probe_last_run_truncated": "gauge",
    "datasmart_ai_model_provider_health_probe_last_run_outcomes": "gauge",
    "datasmart_ai_model_provider_health_probe_last_run_status_providers": "gauge",
}


def render_model_provider_health_probe_prometheus(probe_service: Any) -> str:
    """从主动探测服务读取诊断快照并渲染为 Prometheus exposition text。

    参数：
    - `probe_service`：通常是 `ModelProviderHealthProbeService`。这里使用 duck typing，是为了让测试、
      未来远程诊断代理或组合型 exporter 只要提供 `diagnostics()` 就能复用本渲染器。

    返回：
    - 可直接作为 `/agent/metrics` response body 片段的文本，末尾包含一个换行。

    副作用：
    - 本函数只读诊断快照，不触发主动探测、不访问外部模型网关、不写回 health registry。
    """

    return render_model_provider_health_probe_diagnostics_prometheus(probe_service.diagnostics())


def render_model_provider_health_probe_diagnostics_prometheus(diagnostics: dict[str, Any]) -> str:
    """把 `ModelProviderHealthProbeService.diagnostics()` 转成低基数 Prometheus 指标。

    这里故意只读取 diagnostics 中的聚合字段：
    - `metrics.probeRunCount/probeSuccessCount/probeFailureCount/probeSkippedCount` 用于累计 counter；
    - `startupProbeEnabled/timeoutSeconds/maxRoutesPerRun` 用于配置 gauge；
    - `lastRun.candidateProviderCount/probedProviderCount/truncated/statusCounts` 用于最近一轮 gauge。

    即使 diagnostics 中的 `lastRun.results` 包含 providerName 和 sanitized probeUrl，指标渲染也不会读取
    这些明细。这样可以防止未来有人把 providerName、URL 或业务 ID 当作 label，造成时序爆炸或信息泄露。
    """

    counters = _dict_value(diagnostics.get("metrics"))
    last_run = _dict_value(diagnostics.get("lastRun"))
    status_counts = _dict_value(last_run.get("statusCounts"))
    series = {
        "datasmart_ai_model_provider_health_probe_runs_total": (
            ({}, _number(counters.get("probeRunCount"))),
        ),
        "datasmart_ai_model_provider_health_probe_outcomes_total": tuple(
            ({"outcome": outcome}, _number(counters.get(_counter_key_for_outcome(outcome))))
            for outcome in _OUTCOMES
        ),
        "datasmart_ai_model_provider_health_probe_startup_enabled": (
            ({}, _bool_number(diagnostics.get("startupProbeEnabled"))),
        ),
        "datasmart_ai_model_provider_health_probe_timeout_seconds": (
            ({}, _number(diagnostics.get("timeoutSeconds"))),
        ),
        "datasmart_ai_model_provider_health_probe_max_routes_per_run": (
            ({}, _number(diagnostics.get("maxRoutesPerRun"))),
        ),
        "datasmart_ai_model_provider_health_probe_last_run_candidates": (
            ({}, _number(last_run.get("candidateProviderCount"))),
        ),
        "datasmart_ai_model_provider_health_probe_last_run_probed": (
            ({}, _number(last_run.get("probedProviderCount"))),
        ),
        "datasmart_ai_model_provider_health_probe_last_run_truncated": (
            ({}, _bool_number(last_run.get("truncated"))),
        ),
        "datasmart_ai_model_provider_health_probe_last_run_outcomes": tuple(
            ({"outcome": outcome}, _number(last_run.get(_last_run_key_for_outcome(outcome))))
            for outcome in _OUTCOMES
        ),
        "datasmart_ai_model_provider_health_probe_last_run_status_providers": tuple(
            ({"status": status}, _number(status_counts.get(status))) for status in _HEALTH_STATUSES
        ),
    }
    return _render_metrics(series)


def _render_metrics(series: dict[str, Iterable[tuple[dict[str, str], float]]]) -> str:
    """按 Prometheus 文本格式渲染 HELP、TYPE 与样本行。

    说明：
    - HELP/TYPE 始终输出，便于运维确认 endpoint 存在；
    - 样本行即使值为 0 也输出，因为这些是固定低基数指标，不会造成动态序列膨胀；
    - label key/value 都来自本文件的固定枚举，不从业务对象读取。
    """

    lines: list[str] = []
    for name, help_text in _METRIC_HELP.items():
        lines.append(f"# HELP {name} {help_text}")
        lines.append(f"# TYPE {name} {_METRIC_TYPES[name]}")
        for labels, value in series.get(name, ()):
            lines.append(f"{name}{_label_text(labels)} {_format_number(value)}")
    lines.append("")
    return "\n".join(lines)


def _counter_key_for_outcome(outcome: str) -> str:
    """把 outcome 枚举映射到 diagnostics 中的累计计数字段名。"""

    return {
        "success": "probeSuccessCount",
        "failure": "probeFailureCount",
        "skipped": "probeSkippedCount",
    }[outcome]


def _last_run_key_for_outcome(outcome: str) -> str:
    """把 outcome 枚举映射到 latest run summary 中的计数字段名。"""

    return {
        "success": "successCount",
        "failure": "failureCount",
        "skipped": "skippedCount",
    }[outcome]


def _dict_value(value: Any) -> dict[str, Any]:
    """把可选 dict 规整为普通字典，缺失或类型错误时返回空字典。"""

    return value if isinstance(value, dict) else {}


def _number(value: Any) -> float:
    """把诊断值转换为非负数字，非法值回退为 0。"""

    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return 0.0
    return parsed if parsed >= 0 else 0.0


def _bool_number(value: Any) -> float:
    """把布尔值转换为 Prometheus gauge 使用的 0/1。"""

    return 1.0 if bool(value) else 0.0


def _label_text(labels: dict[str, str]) -> str:
    """渲染 Prometheus label 文本。"""

    if not labels:
        return ""
    joined = ",".join(f'{key}="{_escape_label_value(value)}"' for key, value in sorted(labels.items()))
    return "{" + joined + "}"


def _escape_label_value(value: str) -> str:
    """转义 Prometheus label value。"""

    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _format_number(value: float) -> str:
    """把浮点数渲染为更易读的 Prometheus 数字。"""

    return str(int(value)) if value.is_integer() else str(value)
