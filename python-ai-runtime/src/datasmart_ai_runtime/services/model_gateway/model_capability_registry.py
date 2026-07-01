"""模型能力矩阵与生产适配诊断。

这个文件是 DataSmart 在“模型层收敛”阶段补上的控制面能力：我们不在项目里训练模型、不做微调/
后训练，也不实现 CUDA kernel、KV cache 调度器或推理引擎内部优化。DataSmart 的职责是把成熟模型
服务、成熟推理框架和业务 Agent 治理链路连接起来，并且让每条模型路由在上线前都能被解释、被诊断、
被替换。

为什么需要能力矩阵：
- 同一个 OpenAI-compatible 接口背后可能是 DeepSeek、Qwen、GLM、vLLM、SGLang 或企业内部网关；
- Agent 场景不只需要“能聊天”，还需要工具调用、结构化输出、长上下文、缓存、流式输出和低敏治理；
- Embedding、Rerank、多模态理解不应该被主聊天模型硬凑，否则后续 RAG 质量、成本和延迟都会失控；
- 新一代模型迭代很快，业务代码不能把 `deepseek-v4-pro`、`qwen3.7` 或 `glm-5.2` 写死为唯一选择。

本文件的输出刻意保持“低敏”：
- 可以返回模型名、工作负载、provider 类型、上下文窗口、支持能力和生产缺口；
- 不返回 endpoint、API Key、请求正文、prompt、SQL、工具参数、样本数据、模型输出或内部服务地址；
- 诊断结果用于管理台、运维排障和上线前检查，不代表已经完成真实性能压测或供应商 SLA 验收。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelRoute,
    ProviderType,
    WorkloadType,
)


class CapabilitySupport(str, Enum):
    """单项模型能力的支持状态。

    使用四态而不是简单布尔值，是为了避免把“当前资料未确认”误判成“不支持”，也避免把“某些 SKU
    支持”误判成“所有部署方式都支持”。这对 Qwen、GLM、DeepSeek 这类快速迭代模型尤其重要：同一
    模型家族在托管 API、本地开源权重、不同地区服务、不同计费套餐里的能力可能不同。
    """

    SUPPORTED = "supported"
    UNSUPPORTED = "unsupported"
    VARIES_BY_SKU = "varies_by_sku"
    UNKNOWN = "unknown"


class ModelServingEngine(str, Enum):
    """模型服务承载方式。

    这里表达“模型如何被服务化”，不表达“模型是谁”。例如 DeepSeek V4 Pro 可以走 DeepSeek 托管 API，
    开源权重也可能经 vLLM/SGLang 承载；Qwen 也可能走 DashScope OpenAI-compatible API 或企业内网关。
    DataSmart 只依赖稳定协议和能力声明，不把业务流程绑定到某个供应商 SDK。
    """

    DRY_RUN = "dry_run"
    MANAGED_PROVIDER_API = "managed_provider_api"
    OPENAI_COMPATIBLE_API = "openai_compatible_api"
    VLLM = "vllm"
    SGLANG = "sglang"
    PYTHON_LOCAL = "python_local"


@dataclass(frozen=True)
class ModelCapabilityProfile:
    """某个模型家族或具体模型的能力画像。

    字段说明：
    - `profile_id`：能力画像的稳定标识，供测试、管理台和文档引用；
    - `aliases`：用于匹配配置中的 `model_name`，保持小写和宽松匹配，避免不同供应商写法略有差异；
    - `recommended_workloads`：推荐承接的 DataSmart 工作负载，不代表其他工作负载绝对不可用；
    - `preferred_provider_types`/`preferred_serving_engines`：推荐接入形态，用于发现“配置能跑但不适合生产”的路由；
    - `context_window_tokens`/`max_output_tokens`：公开资料可确认的容量；未知时保留 `None`，诊断时要求人工验证；
    - `recommended_cache_scope`：从安全角度建议的缓存复用边界，避免长上下文缓存跨租户、跨项目、跨会话复用；
    - `integration_notes`：接入建议，侧重“为什么这样接入”；
    - `production_gaps`：上线前必须补齐的验证事项，例如压测、工具调用兼容性、地区可用性或 SLA。
    """

    profile_id: str
    family: str
    generation: str
    aliases: tuple[str, ...]
    recommended_workloads: tuple[WorkloadType, ...]
    preferred_provider_types: tuple[ProviderType, ...]
    preferred_serving_engines: tuple[ModelServingEngine, ...]
    context_window_tokens: int | None = None
    max_output_tokens: int | None = None
    chat_completion: CapabilitySupport = CapabilitySupport.UNKNOWN
    streaming: CapabilitySupport = CapabilitySupport.UNKNOWN
    tool_calls: CapabilitySupport = CapabilitySupport.UNKNOWN
    streaming_tool_calls: CapabilitySupport = CapabilitySupport.UNKNOWN
    json_output: CapabilitySupport = CapabilitySupport.UNKNOWN
    reasoning_mode: CapabilitySupport = CapabilitySupport.UNKNOWN
    context_caching: CapabilitySupport = CapabilitySupport.UNKNOWN
    multimodal: CapabilitySupport = CapabilitySupport.UNKNOWN
    embedding: CapabilitySupport = CapabilitySupport.UNSUPPORTED
    rerank: CapabilitySupport = CapabilitySupport.UNSUPPORTED
    recommended_cache_scope: ModelCacheKeyScope = ModelCacheKeyScope.SESSION_ONLY
    integration_notes: tuple[str, ...] = ()
    production_gaps: tuple[str, ...] = ()
    source_refs: tuple[str, ...] = ()

    def matches_model_name(self, model_name: str) -> bool:
        """判断当前画像是否能匹配一条模型路由的模型名。

        模型配置来自环境变量、Nacos 或后续管理台时，大小写、连字符和后缀经常不完全一致。这里采用
        宽松包含匹配，是为了让诊断入口优先给出有价值建议；真正发起请求时仍由 Provider 使用精确
        `model_name`，不会被这个匹配逻辑改写。
        """

        normalized = model_name.lower()
        return any(alias in normalized for alias in self.aliases)

    def supports_workload(self, workload: WorkloadType) -> bool:
        """判断画像是否推荐承接指定工作负载。"""

        return workload in self.recommended_workloads

    def to_summary(self) -> dict[str, Any]:
        """输出管理台可消费的低敏能力摘要。

        响应只包含能力、建议和来源链接，不包含 provider endpoint、凭证、prompt、工具参数或模型输出。
        """

        return {
            "profileId": self.profile_id,
            "family": self.family,
            "generation": self.generation,
            "aliases": self.aliases,
            "recommendedWorkloads": tuple(workload.value for workload in self.recommended_workloads),
            "preferredProviderTypes": tuple(provider.value for provider in self.preferred_provider_types),
            "preferredServingEngines": tuple(engine.value for engine in self.preferred_serving_engines),
            "contextWindowTokens": self.context_window_tokens,
            "maxOutputTokens": self.max_output_tokens,
            "capabilities": {
                "chatCompletion": self.chat_completion.value,
                "streaming": self.streaming.value,
                "toolCalls": self.tool_calls.value,
                "streamingToolCalls": self.streaming_tool_calls.value,
                "jsonOutput": self.json_output.value,
                "reasoningMode": self.reasoning_mode.value,
                "contextCaching": self.context_caching.value,
                "multimodal": self.multimodal.value,
                "embedding": self.embedding.value,
                "rerank": self.rerank.value,
            },
            "recommendedCacheScope": self.recommended_cache_scope.value,
            "integrationNotes": self.integration_notes,
            "productionGaps": self.production_gaps,
            "sourceRefs": self.source_refs,
        }


@dataclass(frozen=True)
class ModelRouteCapabilityAssessment:
    """单条模型路由的能力适配评估结果。

    `compatibility_level` 用于快速分类：
    - `production_candidate`：能力画像与当前 workload/provider 基本匹配，可以进入压测与灰度；
    - `needs_provider_validation`：理论上可用，但存在 SKU、上下文、缓存或工具调用兼容性需要验证；
    - `development_only`：dry-run/placeholder，只适合本地学习和流程测试；
    - `incompatible`：工作负载明显不匹配，例如用聊天模型承接 rerank；
    - `unknown_model_profile`：仓库尚未维护该模型画像，不能贸然标记为生产就绪。
    """

    workload: WorkloadType
    provider_name: str
    provider_type: ProviderType
    model_name: str
    profile: ModelCapabilityProfile | None
    compatibility_level: str
    issues: tuple[str, ...] = ()
    warnings: tuple[str, ...] = ()
    recommended_actions: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """输出低敏路由诊断摘要。

        注意：这里不输出 `route.endpoint`。endpoint 可能包含内网域名、路径规划、供应商账号隔离信息，
        对管理台诊断并非必要，且不应该进入普通 runtime projection。
        """

        return {
            "workload": self.workload.value,
            "providerName": self.provider_name,
            "providerType": self.provider_type.value,
            "modelName": self.model_name,
            "matchedProfileId": self.profile.profile_id if self.profile else None,
            "compatibilityLevel": self.compatibility_level,
            "issues": self.issues,
            "warnings": self.warnings,
            "recommendedActions": self.recommended_actions,
            "profile": self.profile.to_summary() if self.profile else None,
        }


@dataclass(frozen=True)
class ModelCapabilityRegistry:
    """模型能力画像注册表。

    当前实现是内存只读表，适合作为仓库默认能力矩阵。商业化后可以把它升级为“内置基线 + 管理台覆盖 +
    定期趋势扫描更新”的组合，但业务代码仍只依赖本类暴露的 `resolve/assess_route/diagnostics` 接口。
    """

    profiles: tuple[ModelCapabilityProfile, ...] = field(default_factory=tuple)

    def resolve(self, model_name: str) -> ModelCapabilityProfile | None:
        """根据模型名解析能力画像。"""

        return next((profile for profile in self.profiles if profile.matches_model_name(model_name)), None)

    def assess_route(self, route: ModelRoute) -> ModelRouteCapabilityAssessment:
        """评估单条模型路由是否符合当前 DataSmart 工作负载。

        这个方法做的是“上线前控制面诊断”，不是运行时强制拦截。原因是模型生态变化很快，某个新模型
        可能尚未写入画像但已经在灰度验证；我们先给出低敏问题、警告和建议，让运维或平台管理员决定
        是否进入灰度，而不是在 Python Runtime 里把所有未知模型直接禁用。
        """

        profile = self.resolve(route.model_name)
        if profile is None:
            return ModelRouteCapabilityAssessment(
                workload=route.workload,
                provider_name=route.provider_name,
                provider_type=route.provider_type,
                model_name=route.model_name,
                profile=None,
                compatibility_level="unknown_model_profile",
                warnings=("MODEL_PROFILE_NOT_REGISTERED",),
                recommended_actions=(
                    "补充模型能力画像，至少确认 chat/tool/json/context/cache/embedding/rerank 等能力。",
                    "上线前执行真实 provider health probe、工具调用兼容性测试、长上下文压测和成本预算评估。",
                ),
            )

        issues: list[str] = []
        warnings: list[str] = []
        actions: list[str] = []

        self._append_workload_requirements(route, profile, issues, warnings, actions)
        if route.provider_type not in profile.preferred_provider_types:
            warnings.append("PROVIDER_TYPE_NOT_PROFILE_PREFERRED")
            actions.append("确认当前 provider 类型是否只是过渡配置；生产环境优先使用画像推荐的接入形态。")

        if route.max_context_tokens and profile.context_window_tokens:
            if route.max_context_tokens > profile.context_window_tokens:
                warnings.append("ROUTE_CONTEXT_EXCEEDS_PROFILE_CONTEXT")
                actions.append("降低路由 max_context_tokens，或确认供应商实际 SKU 已支持该上下文窗口。")

        if route.cache_key_scope != profile.recommended_cache_scope:
            warnings.append("CACHE_SCOPE_DIFFERS_FROM_PROFILE_RECOMMENDATION")
            actions.append("复核缓存复用边界，长上下文/prefix cache 不应跨租户、跨项目或跨会话泄露上下文。")

        if route.provider_type == ProviderType.DRY_RUN or "placeholder" in route.model_name.lower():
            return ModelRouteCapabilityAssessment(
                workload=route.workload,
                provider_name=route.provider_name,
                provider_type=route.provider_type,
                model_name=route.model_name,
                profile=profile,
                compatibility_level="development_only",
                warnings=tuple(dict.fromkeys((*warnings, "DRY_RUN_OR_PLACEHOLDER_ROUTE"))),
                recommended_actions=tuple(
                    dict.fromkeys(
                        (
                            *actions,
                            "当前路由仅适合本地学习、单元测试和流程演示；生产前必须替换为真实 Provider。",
                            "替换后重新运行 provider health、能力诊断、工具调用、缓存隔离和预算测试。",
                        )
                    )
                ),
            )

        if issues:
            level = "incompatible"
        elif warnings:
            level = "needs_provider_validation"
        else:
            level = "production_candidate"
            actions.append("可以进入灰度前压测：首包延迟、吞吐、工具调用成功率、缓存命中率、超时和降级策略。")

        return ModelRouteCapabilityAssessment(
            workload=route.workload,
            provider_name=route.provider_name,
            provider_type=route.provider_type,
            model_name=route.model_name,
            profile=profile,
            compatibility_level=level,
            issues=tuple(dict.fromkeys(issues)),
            warnings=tuple(dict.fromkeys(warnings)),
            recommended_actions=tuple(dict.fromkeys(actions)),
        )

    def diagnostics(self, routes: tuple[ModelRoute, ...]) -> dict[str, Any]:
        """生成模型能力矩阵诊断响应。

        这个响应可以直接暴露给运维诊断接口。它回答三个问题：
        1. 仓库当前认识哪些主流模型能力画像；
        2. 当前配置的模型路由分别匹配到了什么画像；
        3. 哪些路由仍是开发占位、能力未知、工作负载不匹配或需要供应商级验证。
        """

        assessments = tuple(self.assess_route(route) for route in routes)
        return {
            "schemaVersion": "datasmart.model-capability-registry.v1",
            "scope": "MODEL_ACCESS_AND_SERVING_GOVERNANCE_ONLY",
            "strategyBoundary": (
                "DataSmart 不在当前阶段承担模型算法研发、微调/后训练或底层推理内核优化；"
                "项目使用成熟 Provider API、vLLM/SGLang/OpenAI-compatible 接口、健康探测、预算、缓存和工具治理完成生产接入。"
            ),
            "sensitiveDataPolicy": (
                "诊断只返回模型名、工作负载、能力状态、生产缺口和建议；不返回 endpoint、API Key、prompt、SQL、"
                "工具参数、样本数据、模型输出或内部服务地址。"
            ),
            "registeredProfileCount": len(self.profiles),
            "routeAssessmentCount": len(assessments),
            "profiles": tuple(profile.to_summary() for profile in self.profiles),
            "routeAssessments": tuple(assessment.to_summary() for assessment in assessments),
            "recommendedConvergenceRoute": (
                "先把 DeepSeek/Qwen/GLM/vLLM/SGLang 都纳入可替换模型网关治理，再补 benchmark/eval 与灰度策略；"
                "暂不启动项目内训练、微调、后训练或底层推理引擎开发。"
            ),
        }

    def _append_workload_requirements(
        self,
        route: ModelRoute,
        profile: ModelCapabilityProfile,
        issues: list[str],
        warnings: list[str],
        actions: list[str],
    ) -> None:
        """按工作负载检查关键能力。

        这里把规则拆成独立方法，是为了让未来新增 `VISION_RAG`、`SQL_AGENT`、`AUDIO_TRANSCRIPTION` 等 workload
        时不把 `assess_route` 变成大而全的条件堆。每个 workload 只检查“最低必要能力”，SKU、延迟、吞吐和
        质量仍需要在 benchmark/eval 层完成。
        """

        if not profile.supports_workload(route.workload):
            warnings.append("WORKLOAD_NOT_IN_PROFILE_RECOMMENDATION")
            actions.append("确认该模型是否真的适合当前工作负载；如只是临时 fallback，应在路由说明中标注。")

        if route.workload == WorkloadType.EMBEDDING:
            if profile.embedding != CapabilitySupport.SUPPORTED:
                issues.append("WORKLOAD_REQUIRES_EMBEDDING_MODEL")
                actions.append("Embedding 工作负载必须使用专用向量模型，不要复用主聊天/Agent 模型。")
            return

        if route.workload == WorkloadType.RERANK:
            if profile.rerank != CapabilitySupport.SUPPORTED:
                issues.append("WORKLOAD_REQUIRES_RERANK_MODEL")
                actions.append("Rerank 工作负载必须使用专用重排模型或明确支持 rerank API 的 Provider。")
            return

        if route.workload == WorkloadType.MULTIMODAL_UNDERSTANDING:
            if profile.multimodal == CapabilitySupport.UNSUPPORTED:
                issues.append("WORKLOAD_REQUIRES_MULTIMODAL_MODEL")
                actions.append("多模态理解必须切换到支持图片/文档/视频输入的模型画像。")
            elif profile.multimodal in (CapabilitySupport.UNKNOWN, CapabilitySupport.VARIES_BY_SKU):
                warnings.append("MULTIMODAL_CAPABILITY_REQUIRES_SKU_VALIDATION")
                actions.append("确认具体 SKU、地区和 API 形态是否支持所需多模态输入。")

        if route.workload in (WorkloadType.AGENT_REASONING, WorkloadType.GOVERNANCE_QA, WorkloadType.CODE_GENERATION):
            if profile.chat_completion != CapabilitySupport.SUPPORTED:
                issues.append("WORKLOAD_REQUIRES_CHAT_COMPLETION")
                actions.append("Agent/问答/代码生成工作负载至少需要稳定 chat completion 能力。")
            if route.workload == WorkloadType.AGENT_REASONING:
                if profile.tool_calls == CapabilitySupport.UNSUPPORTED:
                    issues.append("AGENT_REASONING_REQUIRES_TOOL_CALLS")
                    actions.append("Agent 推理需要工具调用能力；如果模型只会生成文本，应仅作为解释型 fallback。")
                elif profile.tool_calls in (CapabilitySupport.UNKNOWN, CapabilitySupport.VARIES_BY_SKU):
                    warnings.append("TOOL_CALL_CAPABILITY_REQUIRES_PROVIDER_VALIDATION")
                    actions.append("上线前必须用 DataSmart 工具 schema 验证 tool_calls、strict schema 和 streaming tool delta。")
                if profile.context_caching in (CapabilitySupport.UNKNOWN, CapabilitySupport.VARIES_BY_SKU):
                    warnings.append("CONTEXT_CACHE_REQUIRES_PROVIDER_VALIDATION")
                    actions.append("长上下文 Agent 场景应验证 prefix/context cache 命中、隔离边界和缓存失效策略。")


def default_model_capability_registry() -> ModelCapabilityRegistry:
    """构造 DataSmart 当前默认模型能力矩阵。

    公开入口保留在本模块以兼容现有调用方，画像数据延迟加载自独立模块。延迟导入可以避免默认画像模块
    在定义 ``ModelCapabilityProfile`` 之前反向导入本模块形成循环依赖。
    """

    from .default_model_capability_profiles import build_default_model_capability_registry

    return build_default_model_capability_registry()
