"""模型网关治理领域契约。

模型网关不应该只是“把请求转发给某个模型 URL”。在真实企业产品中，它还需要负责健康检查、
fallback、预算控制、延迟等级、成本等级、缓存边界和审计说明。本文件只定义稳定领域对象，不
绑定 LiteLLM、vLLM、SGLang 或某个云厂商，保证后续技术栈可以替换。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum

from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelLatencyTier,
    ModelRoute,
    WorkloadType,
)


class ModelProviderHealthStatus(str, Enum):
    """模型 Provider 健康状态。

    健康状态用于路由决策，而不是日志装饰：
    - `HEALTHY`：可正常接收请求；
    - `DEGRADED`：可用但延迟、错误率或队列积压偏高，可作为次优候选；
    - `UNAVAILABLE`：不可用，路由时应跳过并触发 fallback；
    - `UNKNOWN`：还没有健康探测结果，开发环境可放行，生产环境应配告警。
    """

    HEALTHY = "healthy"
    DEGRADED = "degraded"
    UNAVAILABLE = "unavailable"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class ModelProviderHealthSnapshot:
    """一次 Provider 健康快照。

    字段说明：
    - `provider_name`：与 `ModelRoute.provider_name` 对齐，用于把健康状态绑定到路由候选；
    - `status`：当前健康状态；
    - `latency_ms`：最近健康检查或模型调用延迟，可用于后续更细的低延迟路由；
    - `error_rate`：近期错误率，当前只保存不参与复杂计算；
    - `checked_at`：快照时间，后续可用来判断健康结果是否过期；
    - `notes`：人读说明，方便运维面板解释为什么发生 fallback。
    """

    provider_name: str
    status: ModelProviderHealthStatus = ModelProviderHealthStatus.UNKNOWN
    latency_ms: int | None = None
    error_rate: float | None = None
    checked_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    notes: str = ""


@dataclass(frozen=True)
class ModelGatewayRequestContext:
    """一次模型路由治理请求上下文。

    Agent 编排器未来在调用模型前应构建该对象，让模型网关可以基于租户、项目、工作负载、预算、
    延迟等级和缓存范围做决策。当前先不强行接入编排主链，避免一次改动过大；但契约已经能被
    单元测试和后续 API 层复用。
    """

    tenant_id: str
    project_id: str
    actor_id: str
    workload: WorkloadType
    estimated_prompt_tokens: int = 0
    estimated_completion_tokens: int = 0
    latency_tier: ModelLatencyTier | None = None
    cache_key_scope: ModelCacheKeyScope | None = None
    allow_fallback: bool = True
    trace_id: str | None = None
    attributes: dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class ModelGatewayBudgetPolicy:
    """模型网关预算策略。

    当前策略按租户/项目维度控制 token 预算，不直接涉及人民币或美元金额。这样可以先把“是否允许
    本次调用”这条控制链跑通，后续再把 token 预算映射到真实计费、套餐、告警和账单。
    """

    tenant_id: str
    project_id: str | None
    monthly_token_budget: int
    warning_threshold_ratio: float = 0.8


@dataclass(frozen=True)
class ModelGatewayBudgetDecision:
    """预算评估结果。

    `allowed=False` 时，模型网关不应继续选择路由。真实产品中可以转为“请求管理员扩容预算”“切换
    低成本模型”“转离线批处理”等动作。
    """

    allowed: bool
    budget_key: str
    estimated_tokens: int
    used_tokens: int
    remaining_tokens: int | None
    warning: bool = False
    message: str = ""


@dataclass(frozen=True)
class ModelGatewayCachePlan:
    """模型 prefix/KV cache 治理计划。

    该对象不保存 prompt 内容，也不保存真实 KV cache 数据；它只描述一次模型调用应该使用怎样的缓存
    隔离边界。真实 vLLM/SGLang/LiteLLM 网关后续可以根据这些字段生成 prefix cache key、KV cache
    namespace 或请求标签。

    字段说明：
    - `enabled`：本次是否允许使用模型缓存；
    - `scope`：缓存复用范围，与 `ModelCacheKeyScope` 对齐；
    - `namespace`：缓存命名空间，必须体现租户、项目、会话或全局边界；
    - `key_prefix`：给模型网关或推理服务使用的稳定 key 前缀，不包含敏感 prompt；
    - `isolation_key`：用于审计和诊断的隔离键，解释缓存为什么只能在该边界内复用；
    - `ttl_seconds`：建议缓存保留时间。当前只是策略建议，真实存储可以按网关能力决定是否采纳；
    - `reusable_context_hint`：人读说明，解释哪些上下文适合复用；
    - `issues`：禁用或降级原因，例如缺少 projectId、scope=no_cache。
    """

    enabled: bool
    scope: ModelCacheKeyScope
    namespace: str
    key_prefix: str
    isolation_key: str
    ttl_seconds: int
    reusable_context_hint: str
    issues: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, object]:
        """转换为 API、事件和审计记录可直接使用的摘要。"""

        return {
            "enabled": self.enabled,
            "scope": self.scope.value,
            "namespace": self.namespace,
            "keyPrefix": self.key_prefix,
            "isolationKey": self.isolation_key,
            "ttlSeconds": self.ttl_seconds,
            "reusableContextHint": self.reusable_context_hint,
            "issues": self.issues,
        }


@dataclass(frozen=True)
class ModelGatewayRoutingDecision:
    """模型网关路由决策。

    这个对象用于解释一次模型请求最终为什么选中某条路由，是否发生 fallback，预算是否允许，
    推理缓存应该按什么范围生成 key。它是后续审计、成本面板、SLA 面板和智能网关可观测性的基础。
    """

    selected_route: ModelRoute | None
    candidate_routes: tuple[ModelRoute, ...]
    fallback_used: bool
    selected_health: ModelProviderHealthSnapshot | None
    budget_decision: ModelGatewayBudgetDecision
    cache_key_scope: ModelCacheKeyScope
    cache_plan: ModelGatewayCachePlan | None = None
    governance_notes: tuple[str, ...] = ()
    attributes: dict[str, object] = field(default_factory=dict)
