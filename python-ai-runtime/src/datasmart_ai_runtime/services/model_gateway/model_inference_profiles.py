"""成熟推理服务画像定义。

本文件只维护“推理服务类型应该具备哪些低敏指标”的静态知识，真正的诊断流程放在
`model_inference_optimization.py`。拆分的原因是避免主诊断服务继续膨胀，也让后续新增
vLLM/SGLang/LiteLLM 版本画像时不影响诊断算法。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.domain.contracts import ProviderType


@dataclass(frozen=True)
class ModelInferenceEngineProfile:
    """成熟推理服务或模型网关的能力画像。

    字段说明：
    - `profile_id`：稳定画像 ID，API、测试和能力矩阵都可以引用；
    - `display_name`：面向学习和管理台展示的人读名称；
    - `provider_types`：该画像通常对应的 DataSmart provider type；
    - `expected_metrics`：进入生产验证前建议接入的低敏指标名称；
    - `supports_*`：表达该 serving stack 理论上支持的优化方向；
    - `production_notes`：上线前需要关注的关键事项，避免“能连上模型”被误判为“推理层完成”。
    """

    profile_id: str
    display_name: str
    provider_types: tuple[ProviderType, ...]
    expected_metrics: tuple[str, ...]
    supports_prefix_cache: bool = False
    supports_kv_cache_metrics: bool = False
    supports_continuous_batching: bool = False
    supports_parallel_serving: bool = False
    supports_gateway_fallback: bool = False
    production_notes: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """输出低敏画像摘要，不包含 endpoint、节点地址、凭证或请求正文。"""

        return {
            "profileId": self.profile_id,
            "displayName": self.display_name,
            "providerTypes": tuple(item.value for item in self.provider_types),
            "expectedMetrics": self.expected_metrics,
            "supportsPrefixCache": self.supports_prefix_cache,
            "supportsKvCacheMetrics": self.supports_kv_cache_metrics,
            "supportsContinuousBatching": self.supports_continuous_batching,
            "supportsParallelServing": self.supports_parallel_serving,
            "supportsGatewayFallback": self.supports_gateway_fallback,
            "productionNotes": self.production_notes,
        }


def default_inference_engine_profiles() -> tuple[ModelInferenceEngineProfile, ...]:
    """返回 DataSmart 当前认可的成熟推理服务画像基线。"""

    return (
        ModelInferenceEngineProfile(
            profile_id="vllm",
            display_name="vLLM self-hosted serving",
            provider_types=(ProviderType.VLLM,),
            expected_metrics=(
                "ttftMsP95", "tokensPerSecondP50", "queueTimeMsP95", "prefixCacheHitRate",
                "kvCacheUsageRatio", "activeBatchSize", "waitingRequests", "gpuMemoryUsageRatio",
            ),
            supports_prefix_cache=True,
            supports_kv_cache_metrics=True,
            supports_continuous_batching=True,
            supports_parallel_serving=True,
            production_notes=("重点验证 paged attention、prefix cache、batch tokens、GPU 显存压力和多副本路由。",),
        ),
        ModelInferenceEngineProfile(
            profile_id="sglang",
            display_name="SGLang self-hosted serving",
            provider_types=(ProviderType.SGLANG,),
            expected_metrics=(
                "ttftMsP95", "tokensPerSecondP50", "queueTimeMsP95", "prefixCacheHitRate",
                "kvCacheUsageRatio", "activeBatchSize", "waitingRequests", "gpuMemoryUsageRatio",
            ),
            supports_prefix_cache=True,
            supports_kv_cache_metrics=True,
            supports_continuous_batching=True,
            supports_parallel_serving=True,
            production_notes=("重点验证 radix/prefix cache、并发调度、长上下文吞吐和工具调用兼容性。",),
        ),
        ModelInferenceEngineProfile(
            profile_id="litellm-gateway",
            display_name="LiteLLM or enterprise model gateway",
            provider_types=(ProviderType.OPENAI_COMPATIBLE,),
            expected_metrics=(
                "ttftMsP95", "tokensPerSecondP50", "queueTimeMsP95",
                "cacheHitRate", "runningRequests", "waitingRequests",
            ),
            supports_gateway_fallback=True,
            production_notes=("重点验证多 Provider fallback、预算、限流、缓存透传、审计和上游 serving 指标回灌。",),
        ),
        ModelInferenceEngineProfile(
            profile_id="openai-compatible-provider",
            display_name="Generic OpenAI-compatible provider",
            provider_types=(ProviderType.OPENAI_COMPATIBLE,),
            expected_metrics=("ttftMsP95", "tokensPerSecondP50", "queueTimeMsP95", "cacheHitRate"),
            supports_gateway_fallback=True,
            production_notes=("通用 OpenAI-compatible 只能说明协议可接入，仍需声明背后的 serving engine 和指标来源。",),
        ),
        ModelInferenceEngineProfile(
            profile_id="python-local",
            display_name="Python local model process",
            provider_types=(ProviderType.PYTHON_LOCAL,),
            expected_metrics=("ttftMsP95", "tokensPerSecondP50", "queueTimeMsP95"),
            production_notes=("本地进程适合小模型 fallback 或测试，生产 Agent 主路由应优先使用可观测 serving stack。",),
        ),
        ModelInferenceEngineProfile(
            profile_id="dry-run",
            display_name="DataSmart dry-run placeholder",
            provider_types=(ProviderType.DRY_RUN,),
            expected_metrics=(),
            production_notes=("dry-run 只用于学习、单测和流程演示，不能代表真实推理优化完成。",),
        ),
    )


__all__ = ["ModelInferenceEngineProfile", "default_inference_engine_profiles"]
