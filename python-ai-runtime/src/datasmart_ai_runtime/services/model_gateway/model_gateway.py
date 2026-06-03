"""模型网关治理服务。

本模块是 DataSmart 模型访问层从“路由表 + Provider 调用”升级到“可治理模型网关”的第一步。
当前实现仍然是内存版，不直接调用真实 LiteLLM/vLLM/SGLang 网关，但已经把商业化必备的健康状态、
fallback、预算控制、延迟等级和缓存边界做成稳定接口。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import ModelCacheKeyScope, ModelRoute
from datasmart_ai_runtime.domain.model_gateway import (
    ModelGatewayBudgetDecision,
    ModelGatewayBudgetPolicy,
    ModelGatewayRequestContext,
    ModelGatewayRoutingDecision,
    ModelProviderHealthSnapshot,
    ModelProviderHealthStatus,
)
from datasmart_ai_runtime.services.model_gateway.model_gateway_cache import ModelGatewayCachePlanner
from datasmart_ai_runtime.services.model_gateway.model_provider_health import InMemoryModelProviderHealthRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry


class InMemoryModelBudgetLedger:
    """内存版模型预算台账。

    预算治理是模型网关商业化必备能力：同一个租户可能有不同套餐，同一个项目也可能需要限制月度
    token 消耗。当前台账只在内存中记录使用量，用于固定“评估预算 -> 决定是否允许调用”的契约。
    """

    def __init__(self, policies: tuple[ModelGatewayBudgetPolicy, ...] = ()) -> None:
        self._policies = {_budget_key(policy.tenant_id, policy.project_id): policy for policy in policies}
        self._used_tokens: dict[str, int] = {}

    def set_policy(self, policy: ModelGatewayBudgetPolicy) -> None:
        """设置或覆盖租户/项目预算策略。"""

        self._policies[_budget_key(policy.tenant_id, policy.project_id)] = policy

    def record_usage(self, tenant_id: str, project_id: str | None, used_tokens: int) -> None:
        """记录一次模型调用后的 token 使用量。"""

        key = self._resolve_policy_key(tenant_id, project_id)
        self._used_tokens[key] = self._used_tokens.get(key, 0) + max(used_tokens, 0)

    def used_tokens(self, tenant_id: str, project_id: str | None = None) -> int:
        """查询当前预算 key 已使用 token 数。

        该方法主要服务单元测试、运营面板和后续 API 查询。真实生产系统中，这个值会来自 MySQL、
        Redis 或账单系统，而不是 Python 进程内存。
        """

        return self._used_tokens.get(self._resolve_policy_key(tenant_id, project_id), 0)

    def evaluate(self, context: ModelGatewayRequestContext) -> ModelGatewayBudgetDecision:
        """评估本次请求是否仍在预算内。

        如果没有配置预算策略，默认放行并在结果中说明“未配置预算”。这适合开发环境；生产环境应当
        至少配置租户级预算，否则无法做成本保护和超额告警。
        """

        key = self._resolve_policy_key(context.tenant_id, context.project_id)
        policy = self._policies.get(key)
        estimated_tokens = max(context.estimated_prompt_tokens, 0) + max(context.estimated_completion_tokens, 0)
        used_tokens = self._used_tokens.get(key, 0)
        if policy is None:
            return ModelGatewayBudgetDecision(
                allowed=True,
                budget_key=key,
                estimated_tokens=estimated_tokens,
                used_tokens=used_tokens,
                remaining_tokens=None,
                message="未配置模型预算策略，当前按开发/默认策略放行。",
            )

        remaining = policy.monthly_token_budget - used_tokens
        allowed = estimated_tokens <= remaining
        warning = allowed and (used_tokens + estimated_tokens) >= int(policy.monthly_token_budget * policy.warning_threshold_ratio)
        return ModelGatewayBudgetDecision(
            allowed=allowed,
            budget_key=key,
            estimated_tokens=estimated_tokens,
            used_tokens=used_tokens,
            remaining_tokens=max(remaining, 0),
            warning=warning,
            message="预算允许本次模型调用。" if allowed else "预算不足，模型网关应阻止调用或切换低成本/离线路径。",
        )

    def _resolve_policy_key(self, tenant_id: str, project_id: str | None) -> str:
        """优先使用项目级预算，没有项目级策略时回退租户级预算。"""

        project_key = _budget_key(tenant_id, project_id)
        if project_key in self._policies:
            return project_key
        return _budget_key(tenant_id, None)


class ModelGatewayGovernanceService:
    """模型网关治理决策服务。

    该服务当前只负责“选路由”，不直接调用模型。这样职责边界更清楚：
    - `ModelRouteRegistry` 管候选路由；
    - `ModelGatewayGovernanceService` 管健康、预算、fallback、缓存范围；
    - `ModelProviderRegistry` 管具体推理调用。
    """

    def __init__(
        self,
        model_routes: ModelRouteRegistry,
        health_registry: InMemoryModelProviderHealthRegistry | None = None,
        budget_ledger: InMemoryModelBudgetLedger | None = None,
        cache_planner: ModelGatewayCachePlanner | None = None,
    ) -> None:
        self._model_routes = model_routes
        self._health_registry = health_registry or InMemoryModelProviderHealthRegistry()
        self._budget_ledger = budget_ledger or InMemoryModelBudgetLedger()
        self._cache_planner = cache_planner or ModelGatewayCachePlanner()

    def decide(self, context: ModelGatewayRequestContext) -> ModelGatewayRoutingDecision:
        """根据治理上下文选择模型路由。

        决策顺序刻意保持明确：
        1. 先评估预算，预算不足时不选择任何 Provider；
        2. 再获取工作负载候选路由，并按延迟等级做优先排序；
        3. 跳过 `UNAVAILABLE` Provider；
        4. 如果主路由不可用且允许 fallback，则选择下一个可用候选；
        5. 最终返回缓存范围和人读治理说明。
        """

        budget_decision = self._budget_ledger.evaluate(context)
        candidates = self._model_routes.candidate_routes_for(context.workload)
        cache_key_scope = context.cache_key_scope or (candidates[0].cache_key_scope if candidates else ModelCacheKeyScope.NO_CACHE)
        if not budget_decision.allowed:
            cache_plan = self._cache_planner.plan(
                context=context,
                scope=cache_key_scope,
                selected_route=None,
            )
            return ModelGatewayRoutingDecision(
                selected_route=None,
                candidate_routes=candidates,
                fallback_used=False,
                selected_health=None,
                budget_decision=budget_decision,
                cache_key_scope=cache_key_scope,
                cache_plan=cache_plan,
                governance_notes=(budget_decision.message,),
                attributes={"blockedBy": "budget"},
            )

        ordered_candidates = self._order_candidates(candidates, context)
        selected_route: ModelRoute | None = None
        selected_health: ModelProviderHealthSnapshot | None = None
        unavailable_notes: list[str] = []
        for route in ordered_candidates:
            health = self._health_registry.snapshot_for(route)
            if health.status == ModelProviderHealthStatus.UNAVAILABLE:
                unavailable_notes.append(f"Provider {route.provider_name} 不可用，已跳过。")
                if not context.allow_fallback:
                    break
                continue
            selected_route = route
            selected_health = health
            break

        fallback_used = bool(selected_route and ordered_candidates and selected_route != ordered_candidates[0])
        final_cache_scope = context.cache_key_scope or (selected_route.cache_key_scope if selected_route else cache_key_scope)
        cache_plan = self._cache_planner.plan(
            context=context,
            scope=final_cache_scope,
            selected_route=selected_route,
        )
        notes = self._build_notes(context, selected_route, selected_health, fallback_used, unavailable_notes, budget_decision)
        if cache_plan.enabled:
            notes.append(
                f"模型缓存计划已启用，namespace={cache_plan.namespace}，TTL={cache_plan.ttl_seconds} 秒。"
            )
        else:
            notes.append(f"模型缓存计划未启用，原因：{', '.join(cache_plan.issues) or '未声明'}。")
        return ModelGatewayRoutingDecision(
            selected_route=selected_route,
            candidate_routes=ordered_candidates,
            fallback_used=fallback_used,
            selected_health=selected_health,
            budget_decision=budget_decision,
            cache_key_scope=final_cache_scope,
            cache_plan=cache_plan,
            governance_notes=tuple(notes),
            attributes={
                "workload": context.workload.value,
                "allowFallback": context.allow_fallback,
                "candidateCount": len(ordered_candidates),
            },
        )

    def record_invocation_usage(
        self,
        context: ModelGatewayRequestContext,
        prompt_tokens: int | None,
        completion_tokens: int | None,
    ) -> int:
        """在模型调用完成后记录实际 token 用量。

        预算评估只能使用预估 token，真正的成本闭环必须在调用结束后把 provider 返回的 usage 写回。
        当前方法先记录 token 数；后续可以扩展为写入审计流水、触发预算告警或同步 Java 控制面。
        """

        used_tokens = max(prompt_tokens or 0, 0) + max(completion_tokens or 0, 0)
        self._budget_ledger.record_usage(context.tenant_id, context.project_id, used_tokens)
        return used_tokens

    def record_invocation_result(
        self,
        context: ModelGatewayRequestContext,
        result: object,
    ) -> int:
        """记录模型调用结果，统一更新预算台账和 Provider 健康台账。

        真实 Agent 产品里，模型调用结束后不能只拿到文本就结束，还要把两个控制面事实写回：
        - `usage`：用于预算、套餐、成本报表和消耗告警；
        - `health`：用于后续路由、fallback、熔断和运维诊断。

        这里接收 `object` 而不是强行扩大类型依赖，是为了兼容测试桩和未来 Provider result 扩展；
        只要对象暴露 `provider_name/error_code/latency_ms/prompt_tokens/completion_tokens` 这些字段即可。
        """

        used_tokens = self.record_invocation_usage(
            context,
            prompt_tokens=getattr(result, "prompt_tokens", None),
            completion_tokens=getattr(result, "completion_tokens", None),
        )
        provider_name = str(getattr(result, "provider_name", "") or "").strip()
        if provider_name:
            error_code = getattr(result, "error_code", None)
            self._health_registry.record_invocation(
                provider_name,
                succeeded=error_code is None,
                latency_ms=getattr(result, "latency_ms", None),
                error_code=str(error_code) if error_code else None,
            )
        return used_tokens

    @staticmethod
    def _order_candidates(
        candidates: tuple[ModelRoute, ...],
        context: ModelGatewayRequestContext,
    ) -> tuple[ModelRoute, ...]:
        """按延迟等级偏好和优先级排序候选路由。"""

        if context.latency_tier is None:
            return candidates
        return tuple(
            sorted(
                candidates,
                key=lambda route: (
                    route.latency_tier != context.latency_tier,
                    route.priority,
                ),
            )
        )

    @staticmethod
    def _build_notes(
        context: ModelGatewayRequestContext,
        selected_route: ModelRoute | None,
        selected_health: ModelProviderHealthSnapshot | None,
        fallback_used: bool,
        unavailable_notes: list[str],
        budget_decision: ModelGatewayBudgetDecision,
    ) -> list[str]:
        """构建人读治理说明，方便后续前端、日志和审计面板展示。"""

        notes = list(unavailable_notes)
        notes.append(budget_decision.message)
        if budget_decision.warning:
            notes.append("本次调用后预算接近告警阈值，建议运营侧关注租户或项目模型消耗。")
        if selected_route is None:
            notes.append("没有可用模型路由，调用方应降级为排队、离线任务或提示稍后重试。")
            return notes
        health_text = selected_health.status.value if selected_health else "unknown"
        notes.append(f"已选择 {selected_route.provider_name}/{selected_route.model_name}，健康状态为 {health_text}。")
        if fallback_used:
            notes.append("主路由不可用或不匹配，本次已使用 fallback 候选。")
        if context.cache_key_scope:
            notes.append(f"调用方显式指定缓存范围 {context.cache_key_scope.value}，优先级高于路由默认值。")
        return notes


def _budget_key(tenant_id: str, project_id: str | None) -> str:
    """生成预算台账 key，项目为空时表示租户级预算。"""

    return f"{tenant_id}:{project_id or '*'}"
