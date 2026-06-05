"""模型网关运行时事件属性构建器。

本模块专门负责把 `ModelGatewayRoutingDecision` 转换成可以写入 Agent Runtime timeline 的
低敏 attributes。它之所以从 `AgentOrchestrator` 中拆出来，是为了避免主编排器继续膨胀：
编排器应该像状态机一样表达“先做什么、后做什么”，而不是承载每个治理事件的字段裁剪细节。

设计原则：
- 事件只记录路由、预算、fallback、Provider 健康、cache plan 和评分摘要；
- 不记录 prompt、messages、工具参数、模型输出、SQL、样例数据或真实 KV cache 内容；
- 对候选评分做数量截断，避免未来一个租户配置大量 Provider 时把单条事件变成大对象；
- 字段命名保持与 API 响应、Java projection 和前端 timeline 容易对齐，减少跨语言映射成本。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRoutingDecision

_MAX_ROUTE_SCORING_EVENT_ITEMS = 8
_EVENT_PAYLOAD_POLICY = "SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT_NO_KV_CACHE"


def build_model_gateway_routed_event_attributes(
    decision: ModelGatewayRoutingDecision,
) -> dict[str, object]:
    """构建 `MODEL_GATEWAY_ROUTED` 运行时事件的低敏属性。

    参数说明：
    - `decision`：模型网关本次路由决策，已经包含预算评估、候选路由、fallback、健康状态和 cache plan。

    返回说明：
    - 返回值可以直接放入 `RuntimeEventRecorder.record(..., attributes=...)`；
    - 所有字段都应能被前端、Java 控制面、审计台和运维面板直接读取；
    - 返回值不应包含任何用户输入正文、模型上下文、工具参数或 Provider 原始响应。

    为什么这里要单独做裁剪：
    `ModelGatewayRoutingDecision` 是领域对象，未来可能继续增加更多诊断字段；运行时事件则会进入
    timeline、WebSocket、审计和查询索引。如果直接把 `decision.attributes` 原样写入事件，一旦未来
    有人把 raw request、prompt hash 以外的上下文、Provider response 或工具参数塞进 attributes，
    就可能造成低敏事件面被污染。因此这里采用 allow-list，只挑选明确安全且有产品解释价值的字段。
    """

    selected_route = decision.selected_route
    cache_plan = decision.cache_plan
    route_scoring = _safe_route_scoring(decision.attributes.get("routeScoring"))
    return {
        # schemaVersion 让后续 Java projection 或前端 timeline 可以按版本演进字段，而不是靠猜测。
        "schemaVersion": "datasmart.ai-runtime.model-gateway-routed.v2",
        # eventPayloadPolicy 是显式的数据安全承诺：该事件只包含摘要，不包含可执行内容或敏感上下文。
        "eventPayloadPolicy": _EVENT_PAYLOAD_POLICY,
        "selectedProvider": selected_route.provider_name if selected_route else None,
        "selectedModel": selected_route.model_name if selected_route else None,
        "selectedHealthStatus": decision.selected_health.status.value if decision.selected_health else None,
        "configuredPrimaryProvider": decision.attributes.get("configuredPrimaryProvider"),
        "orderedCandidateProviders": _as_tuple(decision.attributes.get("orderedCandidateProviders")),
        "candidateCount": len(decision.candidate_routes),
        "fallbackUsed": decision.fallback_used,
        "budgetAllowed": decision.budget_decision.allowed,
        "budgetWarning": decision.budget_decision.warning,
        "cacheAwareRouting": bool(decision.attributes.get("cacheAwareRouting", False)),
        "cacheKeyScope": decision.cache_key_scope.value,
        "cachePlanEnabled": cache_plan.enabled if cache_plan else False,
        "cachePlanScope": cache_plan.scope.value if cache_plan else None,
        "cachePlanNamespace": cache_plan.namespace if cache_plan else None,
        "cachePlanTtlSeconds": cache_plan.ttl_seconds if cache_plan else 0,
        "cachePlanIssues": cache_plan.issues if cache_plan else (),
        "routeScoring": route_scoring,
        "routeScoringCount": len(route_scoring),
        "routeScoringTruncated": _is_route_scoring_truncated(decision.attributes.get("routeScoring")),
    }


def _safe_route_scoring(value: object) -> tuple[dict[str, object], ...]:
    """把模型网关候选评分裁剪成事件可记录的低敏摘要。

    `routeScoring` 来自模型网关内部评分，当前字段都属于低敏控制面信息。但为了让事件层长期安全，
    这里仍然执行 allow-list：
    - 允许 Provider、模型名、健康状态、延迟等级、缓存范围、缓存启停、禁用原因、配置优先级和排序 key；
    - 不允许 `keyPrefix`、`isolationKey`、真实 prompt 前缀、工具参数、SQL、messages 或模型输出；
    - 最多保留 `_MAX_ROUTE_SCORING_EVENT_ITEMS` 个候选，防止大量候选导致单条事件过大。
    """

    if not isinstance(value, (tuple, list)):
        return ()

    safe_items: list[dict[str, object]] = []
    for item in value[:_MAX_ROUTE_SCORING_EVENT_ITEMS]:
        if not isinstance(item, dict):
            continue
        safe_items.append(
            {
                "providerName": item.get("providerName"),
                "modelName": item.get("modelName"),
                "healthStatus": item.get("healthStatus"),
                "latencyTier": item.get("latencyTier"),
                "cacheScope": item.get("cacheScope"),
                "cachePlanEnabled": bool(item.get("cachePlanEnabled", False)),
                "cacheIssues": _as_tuple(item.get("cacheIssues")),
                "priority": item.get("priority"),
                "sortKey": _as_tuple(item.get("sortKey")),
            }
        )
    return tuple(safe_items)


def _as_tuple(value: Any) -> tuple[object, ...]:
    """把外部传入的 list/tuple 安全转换为 tuple。

    运行时事件最终会通过 API 或消息系统传输，tuple/list 都可以被序列化；这里统一返回 tuple，
    是为了和项目中现有 domain contract 的不可变风格保持一致，减少测试中的可变对象副作用。
    """

    if isinstance(value, tuple):
        return value
    if isinstance(value, list):
        return tuple(value)
    if value is None:
        return ()
    return (value,)


def _is_route_scoring_truncated(value: object) -> bool:
    """判断 route scoring 是否因为候选过多被截断。"""

    return isinstance(value, (tuple, list)) and len(value) > _MAX_ROUTE_SCORING_EVENT_ITEMS
