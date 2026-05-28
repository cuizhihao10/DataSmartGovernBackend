"""模型网关 API 展示适配。

领域层的 `ModelGatewayRoutingDecision` 面向服务内部治理，字段包含嵌套 dataclass、枚举和候选路由。
API 响应还需要一份更扁平、更适合前端确认页和 Java 控制面读取的摘要。本模块只做“展示适配”，
不参与实际路由决策，避免把 UI/协议字段反向污染模型网关领域对象。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRoutingDecision


def build_model_gateway_governance_response(
    decision: ModelGatewayRoutingDecision | None,
) -> dict[str, Any]:
    """把模型网关决策转换为 API 友好的治理摘要。

    返回字段设计目标：
    - 前端确认页能直接展示预算状态、fallback 状态、缓存范围和降级原因；
    - Java 控制面能把这份摘要写入审计记录，而不必理解 Python 内部 dataclass 结构；
    - 后续即使 `ModelGatewayRoutingDecision` 增加更多细节，API 摘要也可以保持向后兼容。
    """

    if decision is None:
        return {
            "available": False,
            "displaySummary": "模型网关尚未生成治理决策。",
            "recommendedActions": ("检查 Python AI Runtime 是否已接入模型网关治理服务。",),
        }

    selected_route = decision.selected_route
    selected_health = decision.selected_health
    budget = decision.budget_decision
    available = selected_route is not None and budget.allowed
    return {
        "available": available,
        "selectedProvider": selected_route.provider_name if selected_route else None,
        "selectedModel": selected_route.model_name if selected_route else None,
        "selectedProviderType": selected_route.provider_type.value if selected_route else None,
        "selectedLatencyTier": selected_route.latency_tier.value if selected_route else None,
        "selectedCostTier": selected_route.cost_tier.value if selected_route else None,
        "selectedHealthStatus": selected_health.status.value if selected_health else None,
        "fallbackUsed": decision.fallback_used,
        "budgetAllowed": budget.allowed,
        "budgetKey": budget.budget_key,
        "estimatedTokens": budget.estimated_tokens,
        "usedTokens": budget.used_tokens,
        "remainingTokens": budget.remaining_tokens,
        "budgetWarning": budget.warning,
        "cacheKeyScope": decision.cache_key_scope.value,
        "candidateCount": len(decision.candidate_routes),
        "candidateProviders": tuple(route.provider_name for route in decision.candidate_routes),
        "governanceNotes": decision.governance_notes,
        "displaySummary": _display_summary(decision),
        "recommendedActions": _recommended_actions(decision),
    }


def _display_summary(decision: ModelGatewayRoutingDecision) -> str:
    """生成适合前端卡片展示的一句话摘要。"""

    if not decision.budget_decision.allowed:
        return "模型预算不足，已阻止真实模型调用并进入规则式降级路径。"
    if decision.selected_route is None:
        return "当前没有可用模型路由，已进入规则式降级路径。"
    fallback_text = "，并使用备用模型" if decision.fallback_used else ""
    return (
        f"已选择模型 {decision.selected_route.model_name}{fallback_text}，"
        f"缓存范围为 {decision.cache_key_scope.value}。"
    )


def _recommended_actions(decision: ModelGatewayRoutingDecision) -> tuple[str, ...]:
    """根据治理决策生成下一步建议。

    这些建议不是可执行命令，而是给前端、运维或 Java 控制面展示的产品化提示。
    """

    if not decision.budget_decision.allowed:
        return (
            "提示租户管理员或项目负责人检查模型预算余量。",
            "可选择低成本模型、离线批处理或申请临时预算提升。",
        )
    if decision.selected_route is None:
        return (
            "检查 Provider 健康状态、手动熔断开关和模型路由配置。",
            "如果是临时故障，可保留规则式草案并稍后重试模型节点。",
        )
    actions: list[str] = []
    if decision.fallback_used:
        actions.append("记录 fallback 原因，并在运营面板关注主模型 Provider 健康。")
    if decision.budget_decision.warning:
        actions.append("预算接近告警阈值，建议运营侧关注模型消耗趋势。")
    if decision.cache_key_scope.value in {"session_only", "no_cache"}:
        actions.append("当前缓存范围较保守，适合敏感或会话级任务。")
    return tuple(actions)
