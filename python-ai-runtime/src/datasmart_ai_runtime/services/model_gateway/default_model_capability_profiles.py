"""DataSmart 默认模型能力画像。

本模块只维护可替换的默认画像数据，不承担路由匹配、生产缺口判定或 Provider 调用。把画像与
``ModelCapabilityRegistry`` 的诊断算法分开有三个目的：

1. 模型家族和具体 SKU 的变化频率远高于诊断规则，更新画像不应迫使维护者阅读整套判定流程；
2. 默认画像是启动基线，不是硬编码供应商绑定，企业部署仍可注入自己的 registry；
3. 模型名、能力和资料来源属于低敏控制面元数据；endpoint、API Key、prompt 和模型输出不得进入这里。

画像中的 ``VARIES_BY_SKU`` 和 ``UNKNOWN`` 是刻意保守的状态。它们会推动上线前验证，而不是根据
家族宣传资料把所有托管 API、本地权重和地区套餐误判成完全等价。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ProviderType,
    WorkloadType,
)

from .model_capability_registry import (
    CapabilitySupport,
    ModelCapabilityProfile,
    ModelCapabilityRegistry,
    ModelServingEngine,
)


def build_default_model_capability_registry() -> ModelCapabilityRegistry:
    """构造内置模型能力矩阵，供稳定公开入口延迟加载。

    返回全新的 registry，避免测试或运行时对画像集合的替换污染后续请求。具体路由仍由调用方配置，
    本函数不会选择默认模型、连接 Provider，也不会根据模型名称发起任何网络请求。
    """

    return ModelCapabilityRegistry(
        profiles=(
            ModelCapabilityProfile(
                profile_id="deepseek-v4-pro",
                family="DeepSeek",
                generation="V4 Pro",
                aliases=("deepseek-v4-pro", "deepseek v4 pro", "deepseek-v4"),
                recommended_workloads=(
                    WorkloadType.AGENT_REASONING,
                    WorkloadType.GOVERNANCE_QA,
                    WorkloadType.CODE_GENERATION,
                ),
                preferred_provider_types=(ProviderType.OPENAI_COMPATIBLE, ProviderType.VLLM, ProviderType.SGLANG),
                preferred_serving_engines=(
                    ModelServingEngine.MANAGED_PROVIDER_API,
                    ModelServingEngine.OPENAI_COMPATIBLE_API,
                    ModelServingEngine.VLLM,
                    ModelServingEngine.SGLANG,
                ),
                context_window_tokens=1_000_000,
                max_output_tokens=384_000,
                chat_completion=CapabilitySupport.SUPPORTED,
                streaming=CapabilitySupport.SUPPORTED,
                tool_calls=CapabilitySupport.SUPPORTED,
                streaming_tool_calls=CapabilitySupport.VARIES_BY_SKU,
                json_output=CapabilitySupport.SUPPORTED,
                reasoning_mode=CapabilitySupport.SUPPORTED,
                context_caching=CapabilitySupport.SUPPORTED,
                multimodal=CapabilitySupport.UNKNOWN,
                recommended_cache_scope=ModelCacheKeyScope.SESSION_ONLY,
                integration_notes=(
                    "适合作为高质量 Agent/代码/治理问答候选，通过 OpenAI-compatible 或 Anthropic-compatible API 接入成本较低。",
                    "长上下文能力很强，但 DataSmart 仍必须按 tenant/project/session 生成缓存隔离键。",
                ),
                production_gaps=(
                    "验证 DataSmart 工具 schema 在目标 API 或自托管推理框架中的 tool_calls/strict/streaming 兼容性。",
                    "如果使用开源权重自托管，需要单独评估 vLLM/SGLang 版本、显存、并发、量化和上下文缓存策略。",
                ),
                source_refs=(
                    "https://api-docs.deepseek.com/news/news260424",
                    "https://api-docs.deepseek.com/",
                    "https://api-docs.deepseek.com/guides/kv_cache",
                ),
            ),
            ModelCapabilityProfile(
                profile_id="qwen3.7-agent-family",
                family="Qwen",
                generation="3.7",
                aliases=("qwen3.7", "qwen-3.7", "qwen3.7-max", "qwen3.7-plus", "qwen3.7-agent"),
                recommended_workloads=(
                    WorkloadType.AGENT_REASONING,
                    WorkloadType.GOVERNANCE_QA,
                    WorkloadType.CODE_GENERATION,
                    WorkloadType.MULTIMODAL_UNDERSTANDING,
                ),
                preferred_provider_types=(ProviderType.OPENAI_COMPATIBLE, ProviderType.VLLM, ProviderType.SGLANG),
                preferred_serving_engines=(
                    ModelServingEngine.MANAGED_PROVIDER_API,
                    ModelServingEngine.OPENAI_COMPATIBLE_API,
                    ModelServingEngine.VLLM,
                    ModelServingEngine.SGLANG,
                ),
                chat_completion=CapabilitySupport.SUPPORTED,
                streaming=CapabilitySupport.VARIES_BY_SKU,
                tool_calls=CapabilitySupport.VARIES_BY_SKU,
                streaming_tool_calls=CapabilitySupport.VARIES_BY_SKU,
                json_output=CapabilitySupport.VARIES_BY_SKU,
                reasoning_mode=CapabilitySupport.VARIES_BY_SKU,
                context_caching=CapabilitySupport.VARIES_BY_SKU,
                multimodal=CapabilitySupport.VARIES_BY_SKU,
                recommended_cache_scope=ModelCacheKeyScope.SESSION_ONLY,
                integration_notes=(
                    "适合作为 Qwen 系新一代 Agent 候选，但必须按具体 SKU、地区和 API 形态确认能力。",
                    "托管 API 可优先走 Alibaba Cloud Model Studio 的 OpenAI-compatible/Responses 接口；"
                    "本地部署需确认对应权重是否开放及推理框架支持度。",
                ),
                production_gaps=(
                    "不要把 Qwen3.7 家族能力整体等同于某一个 SKU；必须维护具体模型名、上下文窗口、工具调用和多模态能力。",
                    "如果用于 DataSmart 长任务 Agent，需要补充工具调用质量、上下文保持、成本和限流压测。",
                ),
                source_refs=(
                    "https://qwen.ai/blog?id=qwen3.7",
                    "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope",
                    "https://www.alibabacloud.com/blog/qwen3-7-the-agent-frontier_603154",
                ),
            ),
            ModelCapabilityProfile(
                profile_id="glm-5.2",
                family="GLM",
                generation="5.2",
                aliases=("glm-5.2", "glm 5.2", "glm5.2"),
                recommended_workloads=(
                    WorkloadType.AGENT_REASONING,
                    WorkloadType.GOVERNANCE_QA,
                    WorkloadType.CODE_GENERATION,
                ),
                preferred_provider_types=(ProviderType.OPENAI_COMPATIBLE,),
                preferred_serving_engines=(
                    ModelServingEngine.MANAGED_PROVIDER_API,
                    ModelServingEngine.OPENAI_COMPATIBLE_API,
                ),
                context_window_tokens=1_000_000,
                max_output_tokens=128_000,
                chat_completion=CapabilitySupport.SUPPORTED,
                streaming=CapabilitySupport.SUPPORTED,
                tool_calls=CapabilitySupport.SUPPORTED,
                streaming_tool_calls=CapabilitySupport.VARIES_BY_SKU,
                json_output=CapabilitySupport.SUPPORTED,
                reasoning_mode=CapabilitySupport.SUPPORTED,
                context_caching=CapabilitySupport.SUPPORTED,
                multimodal=CapabilitySupport.UNSUPPORTED,
                recommended_cache_scope=ModelCacheKeyScope.SESSION_ONLY,
                integration_notes=(
                    "适合作为长任务工程 Agent 和代码治理候选，重点验证长上下文、函数调用、结构化输出和 MCP 集成体验。",
                    "当前画像按文本输入/输出建模；多模态能力应使用 GLM 视觉模型或独立多模态画像。",
                ),
                production_gaps=(
                    "确认目标 Z.AI API 套餐、地区、限流、上下文窗口和 tool streaming 能力。",
                    "如果未来开放本地权重，再单独评估 vLLM/SGLang 自托管路径，不提前写死。",
                ),
                source_refs=("https://docs.z.ai/guides/llm/glm-5.2",),
            ),
            ModelCapabilityProfile(
                profile_id="current-generation-qwen-embedding",
                family="Qwen",
                generation="current embedding",
                aliases=("qwen-embedding", "qwen_embedding", "current-generation-qwen-embedding"),
                recommended_workloads=(WorkloadType.EMBEDDING,),
                preferred_provider_types=(ProviderType.OPENAI_COMPATIBLE, ProviderType.PYTHON_LOCAL),
                preferred_serving_engines=(
                    ModelServingEngine.MANAGED_PROVIDER_API,
                    ModelServingEngine.OPENAI_COMPATIBLE_API,
                    ModelServingEngine.PYTHON_LOCAL,
                ),
                embedding=CapabilitySupport.SUPPORTED,
                chat_completion=CapabilitySupport.UNSUPPORTED,
                streaming=CapabilitySupport.UNSUPPORTED,
                tool_calls=CapabilitySupport.UNSUPPORTED,
                json_output=CapabilitySupport.UNSUPPORTED,
                recommended_cache_scope=ModelCacheKeyScope.PROJECT_SAFE,
                integration_notes=("Embedding 应与主 Agent 模型分离，便于独立升级召回质量、向量维度和成本策略。",),
                production_gaps=("补充具体 embedding 模型名、维度、最大输入长度、归一化策略和 Chroma/未来向量库兼容性。",),
                source_refs=("https://www.alibabacloud.com/help/en/model-studio/text-generation",),
            ),
            ModelCapabilityProfile(
                profile_id="current-generation-qwen-reranker",
                family="Qwen",
                generation="current reranker",
                aliases=("qwen-reranker", "qwen_reranker", "current-generation-qwen-reranker"),
                recommended_workloads=(WorkloadType.RERANK,),
                preferred_provider_types=(ProviderType.OPENAI_COMPATIBLE, ProviderType.PYTHON_LOCAL),
                preferred_serving_engines=(
                    ModelServingEngine.MANAGED_PROVIDER_API,
                    ModelServingEngine.OPENAI_COMPATIBLE_API,
                    ModelServingEngine.PYTHON_LOCAL,
                ),
                rerank=CapabilitySupport.SUPPORTED,
                chat_completion=CapabilitySupport.UNSUPPORTED,
                streaming=CapabilitySupport.UNSUPPORTED,
                tool_calls=CapabilitySupport.UNSUPPORTED,
                json_output=CapabilitySupport.UNSUPPORTED,
                recommended_cache_scope=ModelCacheKeyScope.PROJECT_SAFE,
                integration_notes=("Rerank 应作为 RAG/GraphRAG 的独立质量层，不应由主聊天模型临时模拟。",),
                production_gaps=("补充具体 rerank 模型名、批量大小、最大文档数、延迟目标和中英文评测集。",),
                source_refs=("https://www.alibabacloud.com/help/en/model-studio/text-generation",),
            ),
            ModelCapabilityProfile(
                profile_id="development-dry-run-placeholder",
                family="DataSmart Local",
                generation="development placeholder",
                aliases=("placeholder", "dry-run", "agent-reasoning-model"),
                recommended_workloads=(
                    WorkloadType.AGENT_REASONING,
                    WorkloadType.GOVERNANCE_QA,
                    WorkloadType.CODE_GENERATION,
                ),
                preferred_provider_types=(ProviderType.DRY_RUN,),
                preferred_serving_engines=(ModelServingEngine.DRY_RUN,),
                chat_completion=CapabilitySupport.UNKNOWN,
                streaming=CapabilitySupport.UNSUPPORTED,
                tool_calls=CapabilitySupport.UNKNOWN,
                json_output=CapabilitySupport.UNKNOWN,
                context_caching=CapabilitySupport.UNSUPPORTED,
                recommended_cache_scope=ModelCacheKeyScope.SESSION_ONLY,
                integration_notes=("用于本地学习、单元测试和流程闭环演示；它证明编排逻辑可运行，但不代表模型层生产就绪。",),
                production_gaps=("生产前必须替换为真实 Provider，并重新验证模型能力、健康探测、预算、工具调用和缓存隔离。",),
            ),
        )
    )
