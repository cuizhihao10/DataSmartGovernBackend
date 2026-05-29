"""模型网关上下文构建 helper。

`AgentOrchestrator` 应该负责状态编排，而不是长期堆积各种治理上下文解析细节。本模块把请求变量、
上下文 token 估算、延迟偏好、缓存范围和 fallback 开关解析拆出来，避免编排器继续增长成大文件。
"""

from __future__ import annotations

from enum import Enum

from datasmart_ai_runtime.domain.contracts import (
    AgentRequest,
    ModelCacheKeyScope,
    ModelLatencyTier,
)
from datasmart_ai_runtime.domain.context import ContextBlock
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext


def build_model_gateway_context(
    request: AgentRequest,
    context_blocks: tuple[ContextBlock, ...],
) -> ModelGatewayRequestContext:
    """构建模型网关治理上下文。

    当前 token 估算仍是轻量近似值，主要用于预算预评估。生产环境应替换为 tokenizer 级估算，
    并按不同模型的 tokenizer 差异计算 prompt token、completion token 和缓存可复用片段。
    """

    objective_tokens = max(len(request.objective) // 2, 1)
    context_tokens = sum(max(len(block.content) // 2, 1) for block in context_blocks)
    latency_tier = _enum_from_variable(
        ModelLatencyTier,
        request.variables.get("latencyTier") or request.variables.get("latency_tier"),
    )
    cache_scope = _enum_from_variable(
        ModelCacheKeyScope,
        request.variables.get("cacheKeyScope") or request.variables.get("cache_key_scope"),
    )
    session_id = request.variables.get("sessionId") or request.variables.get("session_id")
    return ModelGatewayRequestContext(
        tenant_id=request.tenant_id,
        project_id=request.project_id,
        actor_id=request.actor_id,
        workload=request.preferred_workload,
        estimated_prompt_tokens=objective_tokens + context_tokens,
        estimated_completion_tokens=1024,
        latency_tier=latency_tier,
        cache_key_scope=cache_scope,
        allow_fallback=_bool_from_variable(
            request.variables.get("allowFallback", request.variables.get("allow_fallback", True))
        ),
        trace_id=request.variables.get("traceId") or request.variables.get("trace_id"),
        attributes={
            "contextBlockCount": len(context_blocks),
            "estimation": "char_length_half_plus_default_completion",
            # prefix/KV cache 的 SESSION_ONLY 范围必须绑定明确会话。
            # 这里不把会话写进顶层字段，是为了保持模型网关上下文的稳定主契约；
            # 具体的会话、渠道、前端页面等扩展属性统一放在 attributes 中，后续不同入口可以渐进补充。
            "sessionId": session_id,
        },
    )


def _enum_from_variable(enum_type: type[Enum], value: object) -> Enum | None:
    """把 API 变量中的字符串安全转换为枚举值。

    这里选择非法值返回 `None`，而不是让整次 Agent 计划失败。原因是这些字段属于偏好配置：
    用户或前端传错时，模型网关可以回退路由默认策略；真正的严格校验后续应放在 API DTO 层。
    """

    if value is None:
        return None
    try:
        return enum_type(str(value))
    except ValueError:
        return None


def _bool_from_variable(value: object) -> bool:
    """解析布尔型请求变量，兼容前端常见字符串写法。"""

    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() not in {"false", "0", "no", "off"}
    return bool(value)
