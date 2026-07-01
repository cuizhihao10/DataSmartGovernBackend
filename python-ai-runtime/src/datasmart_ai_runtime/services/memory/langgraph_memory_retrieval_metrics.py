"""LangGraph 长期记忆检索观察图的低基数 Prometheus 指标桥接器。

本模块把 `/agent/plans.agentMemoryRetrievalWorkflow` 中已经脱敏的 summary 转换为 Prometheus
聚合指标，用于回答生产运维和产品验收最关心的几个问题：
- LangGraph 记忆检索节点是否稳定执行，是否经常 fallback；
- 本轮长期记忆检索是可用、空召回、被跳过，还是根本没有检索目标；
- 平台当前主要在检索哪些记忆类型和可见范围；
- MEMORY_AGENT 是否被调度为上下文治理角色，哪些既定 Agent 正在消费记忆上下文。

为什么不把完整 workflow summary 直接作为指标：
- Prometheus 擅长低基数时间序列，不适合保存每个请求、workspace、memory namespace 或 memoryId；
- 单次请求排障应继续走 runtime event、HTTP response、Java projection 和审计日志；
- 指标标签一旦包含 tenant/project/session/run/memoryId/queryHint 等动态字段，会在真实客户环境中制造
  大量时序，并扩大敏感信息泄露面。

因此本类只使用固定枚举标签：workflow_status、retrieval_status、result、fallback、memory_type、
memory_scope、agent_role、delivery_tier 等。即使上游 summary 里意外出现长文本或敏感值，也不会被
渲染成 Prometheus label。
"""

from __future__ import annotations

from collections import defaultdict
from threading import RLock
from typing import Any, Mapping

from datasmart_ai_runtime.services.multi_agent.product_agent_catalog import runtime_agent_delivery_tiers


class LangGraphMemoryRetrievalMetrics:
    """长期记忆检索 LangGraph workflow 的低基数指标注册器。

    设计边界：
    - 只消费 `agentMemoryRetrievalWorkflow.to_summary()` 这种低敏摘要；
    - 不读取 prompt、objective、queryHint、reason、SQL、工具参数、模型输出、记忆正文或 token；
    - 不把 tenantId、projectId、actorId、requestId、runId、sessionId、memoryId、namespace 放入 label；
    - 使用进程内锁保护计数器，满足 FastAPI 多请求并发下的轻量聚合需求。
    """

    _WORKFLOW_STATUSES = {"observed", "disabled", "dependency_missing", "execution_failed", "not_executed", "other"}
    _RETRIEVAL_STATUSES = {
        "no_retrieval_targets",
        "retrieval_available",
        "retrieval_skipped_or_empty",
        "retrieval_empty",
        "not_executed",
        "unknown",
        "other",
    }
    _RESULTS = {"observed", "disabled", "dependency_missing", "execution_failed", "fallback", "other"}
    _BOOL_LABELS = {"true", "false"}
    _MEMORY_TYPES = {"short_term", "semantic", "episodic", "procedural", "resource", "other"}
    _MEMORY_SCOPES = {"session", "project", "tenant", "global", "unknown", "other"}
    _RESULT_COUNT_TYPES = {"retrieval_result", "retrieved", "empty_result", "skipped_result"}
    _AGENT_ROLES = {
        "master_orchestrator",
        "datasource_agent",
        "data_quality_agent",
        "permission_agent",
        "task_agent",
        "memory_agent",
        "ops_agent",
        "data_sync_agent",
        "etl_development_agent",
        "data_asset_agent",
        "compliance_masking_agent",
        "reflection_optimization_agent",
        "knowledge_agent",
        "other",
    }
    _DELIVERY_TIERS = {"must_do", "controlled_scope", "lightweight", "runtime_support", "other"}

    def __init__(self) -> None:
        self._lock = RLock()
        self._counters: dict[tuple[str, tuple[tuple[str, str], ...]], float] = defaultdict(float)

    def record_summary(self, summary: Mapping[str, Any] | None) -> bool:
        """记录一份 `agentMemoryRetrievalWorkflow` 低敏摘要。

        参数：
        - `summary`：来自 `LangGraphMemoryRetrievalWorkflowDiagnostics.to_summary()` 的字典。

        返回：
        - `True`：summary 形状可识别，已经转换为指标；
        - `False`：summary 缺失或不是字典，已安全忽略。

        这里不要求调用方传入 AgentRequest 或 AgentPlan，是故意的安全设计：指标采集不应为了获得上下文而
        接触请求正文、租户、项目、操作者或 memory namespace。
        """

        if not isinstance(summary, Mapping):
            return False
        workflow_status = _workflow_status_label(summary.get("status"))
        retrieval_status = _retrieval_status_label(summary.get("retrievalStatus"), self._RETRIEVAL_STATUSES)
        fallback = _safe_label(str(bool(summary.get("fallbackUsed"))).lower(), self._BOOL_LABELS, "false")
        result = _result_label(workflow_status, fallback == "true")
        retrieval_scope = _mapping(summary.get("retrievalScope"))
        retrieval_report = _mapping(summary.get("retrievalReport"))
        memory_context = _mapping(summary.get("multiAgentMemoryContext"))

        with self._lock:
            self._inc(
                "datasmart_ai_langgraph_memory_retrieval_workflows_total",
                {
                    "workflow_status": workflow_status,
                    "retrieval_status": retrieval_status,
                    "result": result,
                    "fallback": fallback,
                },
            )
            self._record_memory_type_counts(retrieval_scope)
            self._record_memory_scope_counts(retrieval_scope)
            self._record_retrieval_result_counts(retrieval_report, retrieval_status)
            self._record_memory_agent_context(memory_context, retrieval_status)
            self._record_consumer_agent_roles(memory_context, retrieval_status)
        return True

    def snapshot(self) -> dict[str, Any]:
        """返回指标快照，供单元测试或未来诊断接口复用。"""

        with self._lock:
            return {
                "metricCount": len(self._counters),
                "series": tuple(
                    {"name": name, "labels": dict(labels), "value": value}
                    for (name, labels), value in sorted(self._counters.items())
                ),
            }

    def render_prometheus(self) -> str:
        """渲染 Prometheus exposition text。"""

        with self._lock:
            counters = dict(self._counters)
        lines: list[str] = []
        for name, help_text in _METRIC_HELP.items():
            lines.append(f"# HELP {name} {help_text}")
            lines.append(f"# TYPE {name} counter")
            for (metric_name, labels), value in sorted(counters.items()):
                if metric_name != name:
                    continue
                lines.append(f"{name}{_label_text(labels)} {_format_number(value)}")
        lines.append("")
        return "\n".join(lines)

    def _record_memory_type_counts(self, retrieval_scope: Mapping[str, Any]) -> None:
        """记录本轮检索目标的记忆类型分布。"""

        for memory_type, value in _mapping(retrieval_scope.get("memoryTypeCounts")).items():
            self._inc(
                "datasmart_ai_langgraph_memory_retrieval_targets_total",
                {"memory_type": _safe_label(memory_type, self._MEMORY_TYPES, "other")},
                _non_negative_number(value),
            )

    def _record_memory_scope_counts(self, retrieval_scope: Mapping[str, Any]) -> None:
        """记录本轮检索目标的可见范围分布。"""

        for memory_scope, value in _mapping(retrieval_scope.get("memoryScopeCounts")).items():
            self._inc(
                "datasmart_ai_langgraph_memory_retrieval_scopes_total",
                {"memory_scope": _safe_label(memory_scope, self._MEMORY_SCOPES, "other")},
                _non_negative_number(value),
            )

    def _record_retrieval_result_counts(
        self,
        retrieval_report: Mapping[str, Any],
        retrieval_status: str,
    ) -> None:
        """记录召回结果、实际召回、空结果和跳过结果数量。"""

        for source_key, result_type in (
            ("retrievalResultCount", "retrieval_result"),
            ("retrievedCount", "retrieved"),
            ("emptyResultCount", "empty_result"),
            ("skippedResultCount", "skipped_result"),
        ):
            self._inc(
                "datasmart_ai_langgraph_memory_retrieval_results_total",
                {
                    "result_type": _safe_label(result_type, self._RESULT_COUNT_TYPES, "retrieval_result"),
                    "retrieval_status": retrieval_status,
                },
                _non_negative_number(retrieval_report.get(source_key)),
            )

    def _record_memory_agent_context(
        self,
        memory_context: Mapping[str, Any],
        retrieval_status: str,
    ) -> None:
        """记录 MEMORY_AGENT 是否被调度以及是否被本轮检索图认为需要。"""

        scheduled = _safe_label(str(bool(memory_context.get("memoryAgentScheduled"))).lower(), self._BOOL_LABELS, "false")
        required = _safe_label(str(bool(memory_context.get("memoryAgentRequired"))).lower(), self._BOOL_LABELS, "false")
        self._inc(
            "datasmart_ai_langgraph_memory_retrieval_agent_context_total",
            {
                "memory_agent_scheduled": scheduled,
                "memory_agent_required": required,
                "retrieval_status": retrieval_status,
            },
        )

    def _record_consumer_agent_roles(
        self,
        memory_context: Mapping[str, Any],
        retrieval_status: str,
    ) -> None:
        """记录消费长期记忆上下文的 Agent 角色分布。"""

        for role in _sequence(memory_context.get("consumerAgentRoles")):
            agent_role = _safe_label(role, self._AGENT_ROLES, "other")
            self._inc(
                "datasmart_ai_langgraph_memory_retrieval_consumer_agents_total",
                {
                    "agent_role": agent_role,
                    "delivery_tier": _delivery_tier_for_role(agent_role),
                    "retrieval_status": retrieval_status,
                },
            )

    def _inc(self, name: str, labels: dict[str, str], value: float = 1) -> None:
        """递增一个指标序列；`value <= 0` 时不创建噪声序列。"""

        if value <= 0:
            return
        key = (name, tuple(sorted(labels.items())))
        self._counters[key] += float(value)


_METRIC_HELP = {
    "datasmart_ai_langgraph_memory_retrieval_workflows_total": (
        "Total LangGraph memory retrieval workflow summaries grouped by bounded status, retrieval status, result and fallback."
    ),
    "datasmart_ai_langgraph_memory_retrieval_targets_total": (
        "Total memory retrieval target counts grouped by bounded memory type."
    ),
    "datasmart_ai_langgraph_memory_retrieval_scopes_total": (
        "Total memory retrieval target counts grouped by bounded memory scope."
    ),
    "datasmart_ai_langgraph_memory_retrieval_results_total": (
        "Total memory retrieval result counts grouped by bounded result type and retrieval status."
    ),
    "datasmart_ai_langgraph_memory_retrieval_agent_context_total": (
        "Total MEMORY_AGENT context observations grouped by scheduled/required flags and retrieval status."
    ),
    "datasmart_ai_langgraph_memory_retrieval_consumer_agents_total": (
        "Total consumer Agent roles observed by memory retrieval workflow grouped by bounded role and delivery tier."
    ),
}


def _workflow_status_label(value: Any) -> str:
    """把 workflow status 压缩成有限标签。"""

    normalized = _normalize_label_value(value)
    if "observed" in normalized:
        return "observed"
    if "disabled" in normalized:
        return "disabled"
    if "dependency_missing" in normalized:
        return "dependency_missing"
    if "execution_failed" in normalized:
        return "execution_failed"
    if "not_executed" in normalized:
        return "not_executed"
    return "other"


def _retrieval_status_label(value: Any, allowed: set[str]) -> str:
    """把 retrieval status 压缩成有限标签。

    缺失值表示 workflow 没能提供召回状态，因此归为 `unknown`；存在但不在白名单内的值更可能是上游新增
    状态或错误输入，统一归为 `other`，避免动态文本进入标签。
    """

    if value is None or str(value).strip() == "":
        return "unknown"
    return _safe_label(value, allowed, "other")


def _result_label(workflow_status: str, fallback_used: bool) -> str:
    """根据 workflow status 与 fallback 标志推导聚合 result。"""

    if not fallback_used and workflow_status == "observed":
        return "observed"
    if workflow_status in {"disabled", "dependency_missing", "execution_failed"}:
        return workflow_status
    if fallback_used:
        return "fallback"
    return "other"


def _delivery_tier_for_role(agent_role: str) -> str:
    """把运行时 Agent 角色映射到用户确认的交付优先级。"""

    priorities = {
        _normalize_label_value(role): tier
        for role, tier in runtime_agent_delivery_tiers().items()
    }
    if agent_role == "knowledge_agent":
        return "runtime_support"
    return _safe_label(priorities.get(agent_role), LangGraphMemoryRetrievalMetrics._DELIVERY_TIERS, "other")


def _mapping(value: Any) -> Mapping[str, Any]:
    """安全读取字典字段。"""

    return value if isinstance(value, Mapping) else {}


def _sequence(value: Any) -> tuple[Any, ...]:
    """安全读取列表或元组字段；字符串按单个元素处理。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return (value,) if value.strip() else ()
    return tuple(value) if isinstance(value, (list, tuple, set, frozenset)) else ()


def _non_negative_number(value: Any) -> float:
    """把计数字段转换为非负数字。"""

    try:
        return max(0.0, float(value or 0))
    except (TypeError, ValueError):
        return 0.0


def _safe_label(value: Any, allowed: set[str], default: str = "other") -> str:
    """把动态值收敛到允许枚举，防止指标标签基数失控。"""

    normalized = _normalize_label_value(value or default)
    return normalized if normalized in allowed else default


def _normalize_label_value(value: Any) -> str:
    """归一化 Prometheus label 候选值。"""

    return str(value or "").strip().lower().replace("-", "_")


def _label_text(labels: tuple[tuple[str, str], ...]) -> str:
    """渲染 Prometheus 标签文本。"""

    if not labels:
        return ""
    joined = ",".join(f'{key}="{_escape_label_value(value)}"' for key, value in labels)
    return "{" + joined + "}"


def _escape_label_value(value: str) -> str:
    """转义 Prometheus label value。"""

    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _format_number(value: float) -> str:
    """把浮点计数渲染为更易读的 Prometheus 数字。"""

    return str(int(value)) if value.is_integer() else str(value)
