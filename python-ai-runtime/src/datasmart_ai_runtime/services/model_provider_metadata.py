"""模型 Provider 治理 metadata 构建器。

真实商业化 Agent 平台里的模型调用通常不会只包含 messages。为了让智能网关执行路由、预算、
prefix/KV cache、审计、追踪、限流和灰度策略，请求还需要携带一组“治理元数据”。

本模块只负责把 DataSmart 内部的模型网关上下文转换为安全、可序列化、可审计的 metadata：
- 不包含 prompt 原文；
- 不包含工具结果；
- 不包含连接密钥、样本数据、SQL 明文等敏感内容；
- 只包含 Provider 或模型网关可以消费的策略字段。

这样做能把 Agent 主链与具体推理后端解耦。未来无论后端是 LiteLLM、vLLM、SGLang、OpenAI-compatible
网关，还是企业内部 OpenClaw 风格智能网关，都可以复用同一套 metadata 契约。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext


def build_model_provider_metadata(context: ModelGatewayRequestContext) -> dict[str, object]:
    """构建模型 Provider 可消费的治理 metadata。

    参数：
    - `context`：模型网关治理上下文，包含租户、项目、调用人、工作负载、traceId 和扩展属性。

    返回：
    - 一个只包含简单 JSON 类型的字典。Provider 可以把它写入请求体 `metadata`，也可以拆成 HTTP Header。

    设计说明：
    - `cachePlan` 来自模型网关路由决策，当前通过 `context.attributes` 传入，避免把 Provider 层直接耦合
      到完整的 `ModelGatewayRoutingDecision`；
    - `traceId`、tenant/project/actor/workload 是审计和问题排查的基础标签；
    - 不把 `estimatedPromptTokens` 放入缓存 key，只作为网关可观测字段，避免估算差异影响命中稳定性。
    """

    metadata: dict[str, object] = {
        "tenantId": context.tenant_id,
        "projectId": context.project_id,
        "actorId": context.actor_id,
        "workload": context.workload.value,
        "estimatedPromptTokens": context.estimated_prompt_tokens,
        "estimatedCompletionTokens": context.estimated_completion_tokens,
    }
    if context.trace_id:
        metadata["traceId"] = context.trace_id

    cache_plan = context.attributes.get("cachePlan")
    if isinstance(cache_plan, dict):
        metadata["cachePlan"] = _safe_cache_plan(cache_plan)

    session_id = context.attributes.get("sessionId") or context.attributes.get("session_id")
    if session_id is not None and str(session_id).strip():
        metadata["sessionId"] = str(session_id).strip()

    return metadata


def _safe_cache_plan(cache_plan: dict[str, object]) -> dict[str, object]:
    """裁剪 cachePlan，只保留 Provider/网关需要的非敏感字段。

    即使当前 `cachePlan` 本身不包含 prompt，也不要把未知扩展字段原样透传给 Provider。未来如果决策对象
    增加了更详细的诊断内容，这里的白名单可以防止敏感字段意外进入第三方模型网关。
    """

    allowed_keys = {
        "enabled",
        "scope",
        "namespace",
        "keyPrefix",
        "isolationKey",
        "ttlSeconds",
        "issues",
    }
    return {key: cache_plan[key] for key in allowed_keys if key in cache_plan}
