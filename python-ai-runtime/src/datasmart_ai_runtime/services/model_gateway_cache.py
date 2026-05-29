"""模型网关 prefix/KV cache 治理计划器。

大模型推理服务中的 prefix cache / KV cache 能明显降低长上下文 prefill 成本，但在企业数据治理平台里，
缓存绝不能只按 prompt 文本哈希复用。因为相同文本片段在不同租户、项目、工作空间或会话中可能拥有
完全不同的权限语义，一旦跨边界复用，就可能造成敏感上下文泄露。

本模块只生成“缓存治理计划”，不保存 prompt，不读取缓存，也不绑定 vLLM/SGLang/LiteLLM 的具体实现。
后续真实模型网关可以把这里输出的 `namespace/keyPrefix/ttlSeconds` 转换为推理服务请求头、Redis key、
vLLM prefix cache 标签或内部审计字段。
"""

from __future__ import annotations

import re

from datasmart_ai_runtime.domain.contracts import ModelCacheKeyScope, ModelRoute
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayCachePlan, ModelGatewayRequestContext


class ModelGatewayCachePlanner:
    """根据模型网关上下文生成安全 cache namespace。

    设计原则：
    - `NO_CACHE` 永远禁用缓存；
    - `GLOBAL_SAFE` 只适合公开系统提示和通用工具 schema，不携带租户/项目信息；
    - `TENANT_SAFE` 必须绑定租户；
    - `PROJECT_SAFE` 必须绑定租户和项目；
    - `SESSION_ONLY` 必须绑定租户、项目和会话，缺少会话时宁可禁用缓存，也不扩大复用范围。
    """

    DEFAULT_TTLS = {
        ModelCacheKeyScope.GLOBAL_SAFE: 24 * 60 * 60,
        ModelCacheKeyScope.TENANT_SAFE: 6 * 60 * 60,
        ModelCacheKeyScope.PROJECT_SAFE: 2 * 60 * 60,
        ModelCacheKeyScope.SESSION_ONLY: 30 * 60,
        ModelCacheKeyScope.NO_CACHE: 0,
    }

    def plan(
        self,
        *,
        context: ModelGatewayRequestContext,
        scope: ModelCacheKeyScope,
        selected_route: ModelRoute | None,
    ) -> ModelGatewayCachePlan:
        """生成一次模型调用的 cache 计划。

        `selected_route` 可以为空，例如预算不足或没有可用 Provider 时。此时仍返回一个禁用计划，方便
        API 和事件层解释“本次为什么没有缓存 key”。
        """

        if scope == ModelCacheKeyScope.NO_CACHE:
            return self._disabled(scope, issue="CACHE_SCOPE_NO_CACHE")
        if selected_route is None:
            return self._disabled(scope, issue="MODEL_ROUTE_UNAVAILABLE")

        isolation_key, issues = self._isolation_key(context, scope)
        if issues:
            return self._disabled(scope, issue=issues[0], isolation_key=isolation_key)

        namespace = f"model-cache:{scope.value}:{isolation_key}"
        route_part = self._safe(f"{selected_route.provider_name}:{selected_route.model_name}")
        workload_part = self._safe(context.workload.value)
        key_prefix = f"{namespace}:workload:{workload_part}:route:{route_part}"
        return ModelGatewayCachePlan(
            enabled=True,
            scope=scope,
            namespace=namespace,
            key_prefix=key_prefix,
            isolation_key=isolation_key,
            ttl_seconds=self.DEFAULT_TTLS[scope],
            reusable_context_hint=self._hint(scope),
        )

    def _isolation_key(
        self,
        context: ModelGatewayRequestContext,
        scope: ModelCacheKeyScope,
    ) -> tuple[str, tuple[str, ...]]:
        """按 cache scope 生成隔离键并返回缺失问题。"""

        tenant = self._safe(context.tenant_id)
        project = self._safe(context.project_id or "")
        session = self._safe(self._attribute(context, "sessionId", "session_id") or "")

        if scope == ModelCacheKeyScope.GLOBAL_SAFE:
            return "global", ()
        if scope == ModelCacheKeyScope.TENANT_SAFE:
            return f"tenant:{tenant}", () if tenant else ("TENANT_ID_MISSING",)
        if scope == ModelCacheKeyScope.PROJECT_SAFE:
            if not tenant:
                return "tenant:missing:project:missing", ("TENANT_ID_MISSING",)
            if not project:
                return f"tenant:{tenant}:project:missing", ("PROJECT_ID_MISSING",)
            return f"tenant:{tenant}:project:{project}", ()
        if scope == ModelCacheKeyScope.SESSION_ONLY:
            if not tenant:
                return "tenant:missing:project:missing:session:missing", ("TENANT_ID_MISSING",)
            if not project:
                return f"tenant:{tenant}:project:missing:session:missing", ("PROJECT_ID_MISSING",)
            if not session:
                return f"tenant:{tenant}:project:{project}:session:missing", ("SESSION_ID_MISSING",)
            return f"tenant:{tenant}:project:{project}:session:{session}", ()
        return "disabled", ("CACHE_SCOPE_UNSUPPORTED",)

    def _disabled(
        self,
        scope: ModelCacheKeyScope,
        *,
        issue: str,
        isolation_key: str = "disabled",
    ) -> ModelGatewayCachePlan:
        """生成禁用缓存计划。"""

        return ModelGatewayCachePlan(
            enabled=False,
            scope=scope,
            namespace="",
            key_prefix="",
            isolation_key=isolation_key,
            ttl_seconds=0,
            reusable_context_hint="本次模型调用不允许使用 prefix/KV cache。",
            issues=(issue,),
        )

    @staticmethod
    def _hint(scope: ModelCacheKeyScope) -> str:
        """返回人读复用说明。"""

        return {
            ModelCacheKeyScope.GLOBAL_SAFE: "仅可复用公开系统提示、通用工具 schema 和无租户敏感内容。",
            ModelCacheKeyScope.TENANT_SAFE: "仅可在同一租户内复用租户级术语、策略和公开治理说明。",
            ModelCacheKeyScope.PROJECT_SAFE: "仅可在同一租户项目内复用项目级元数据、规则摘要和安全上下文。",
            ModelCacheKeyScope.SESSION_ONLY: "仅可在同一会话内复用临时目标、审批上下文和会话级摘要。",
            ModelCacheKeyScope.NO_CACHE: "不允许缓存。",
        }[scope]

    @staticmethod
    def _attribute(context: ModelGatewayRequestContext, *keys: str) -> str | None:
        """从上下文 attributes 中读取字符串属性。"""

        for key in keys:
            value = context.attributes.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        return None

    @staticmethod
    def _safe(value: str) -> str:
        """把外部 ID 归一化为适合 cache key 的安全片段。"""

        text = str(value or "").strip()
        return re.sub(r"[^A-Za-z0-9_.:-]+", "_", text)
