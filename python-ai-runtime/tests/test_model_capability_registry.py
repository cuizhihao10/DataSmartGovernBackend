import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelCostTier,
    ModelLatencyTier,
    ModelRoute,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.services.model_gateway import CapabilitySupport, default_model_capability_registry


class ModelCapabilityRegistryTest(unittest.TestCase):
    def test_deepseek_v4_pro_route_is_agent_production_candidate(self) -> None:
        """DeepSeek V4 Pro 这类新一代模型应能被识别为 Agent 候选。

        这条测试刻意只验证低敏能力画像，不验证真实 API 调用。原因是项目当前策略不是在单元测试里访问
        外部模型，而是先把“模型名 -> 能力 -> 生产缺口”的控制面契约固定下来。真实上线前再由 health
        probe、工具调用兼容性测试和压测补齐运行时事实。
        """

        registry = default_model_capability_registry()
        route = _route(
            workload=WorkloadType.AGENT_REASONING,
            model_name="deepseek-v4-pro",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            max_context_tokens=1_000_000,
        )

        assessment = registry.assess_route(route)
        summary = assessment.to_summary()

        self.assertEqual("deepseek-v4-pro", summary["matchedProfileId"])
        self.assertEqual("production_candidate", summary["compatibilityLevel"])
        self.assertEqual(1_000_000, summary["profile"]["contextWindowTokens"])
        self.assertEqual(384_000, summary["profile"]["maxOutputTokens"])
        self.assertEqual(CapabilitySupport.SUPPORTED.value, summary["profile"]["capabilities"]["toolCalls"])
        self.assertNotIn("endpoint", str(summary).lower())
        self.assertNotIn("api_key", str(summary).lower())
        self.assertNotIn("prompt", str(summary).lower())

    def test_default_routes_are_development_only_but_embedding_and_rerank_are_recognized(self) -> None:
        """仓库默认路由仍是 dry-run，占位路线必须被明确标记为开发态。

        这能防止我们误以为“默认测试能跑”就等于“模型层已经生产就绪”。同时，Embedding/Rerank 占位模型
        也需要匹配到专用画像，提醒后续不要把主 Agent 模型拿来硬做向量化或重排。
        """

        registry = default_model_capability_registry()
        diagnostics = registry.diagnostics(default_model_routes())
        assessments = diagnostics["routeAssessments"]
        levels = {item["workload"]: item["compatibilityLevel"] for item in assessments}
        profiles = {item["workload"]: item["matchedProfileId"] for item in assessments}

        self.assertEqual("development_only", levels[WorkloadType.AGENT_REASONING.value])
        self.assertEqual("development_only", levels[WorkloadType.EMBEDDING.value])
        self.assertEqual("development_only", levels[WorkloadType.RERANK.value])
        self.assertEqual("current-generation-qwen-embedding", profiles[WorkloadType.EMBEDDING.value])
        self.assertEqual("current-generation-qwen-reranker", profiles[WorkloadType.RERANK.value])
        self.assertIn("不在当前阶段承担模型算法研发", diagnostics["strategyBoundary"])

    def test_qwen37_profile_requires_provider_specific_validation(self) -> None:
        """Qwen3.7 家族不应被项目写死为万能模型。

        Qwen 系模型在 Agent、工具调用、多模态和托管 API 上很有价值，但具体能力会随 SKU、地区、API 形态
        和是否开放权重而变化。因此画像应给出“可选路线 + 需要验证”的判断，而不是把所有能力一概标成
        已生产就绪。
        """

        registry = default_model_capability_registry()
        route = _route(
            workload=WorkloadType.AGENT_REASONING,
            model_name="qwen3.7-max",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            max_context_tokens=131_072,
        )

        assessment = registry.assess_route(route)
        summary = assessment.to_summary()

        self.assertEqual("qwen3.7-agent-family", summary["matchedProfileId"])
        self.assertEqual("needs_provider_validation", summary["compatibilityLevel"])
        self.assertIn("TOOL_CALL_CAPABILITY_REQUIRES_PROVIDER_VALIDATION", summary["warnings"])
        self.assertIn("CONTEXT_CACHE_REQUIRES_PROVIDER_VALIDATION", summary["warnings"])
        self.assertEqual(CapabilitySupport.VARIES_BY_SKU.value, summary["profile"]["capabilities"]["toolCalls"])

    def test_glm52_profile_records_long_context_and_tool_capabilities(self) -> None:
        """GLM-5.2 应被识别为文本长任务 Agent 候选，而不是多模态候选。"""

        registry = default_model_capability_registry()
        agent_route = _route(
            workload=WorkloadType.AGENT_REASONING,
            model_name="glm-5.2",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            max_context_tokens=1_000_000,
        )
        multimodal_route = _route(
            workload=WorkloadType.MULTIMODAL_UNDERSTANDING,
            model_name="glm-5.2",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            max_context_tokens=1_000_000,
        )

        agent_assessment = registry.assess_route(agent_route).to_summary()
        multimodal_assessment = registry.assess_route(multimodal_route).to_summary()

        self.assertEqual("glm-5.2", agent_assessment["matchedProfileId"])
        self.assertEqual(1_000_000, agent_assessment["profile"]["contextWindowTokens"])
        self.assertEqual(128_000, agent_assessment["profile"]["maxOutputTokens"])
        self.assertEqual(CapabilitySupport.SUPPORTED.value, agent_assessment["profile"]["capabilities"]["contextCaching"])
        self.assertEqual("incompatible", multimodal_assessment["compatibilityLevel"])
        self.assertIn("WORKLOAD_REQUIRES_MULTIMODAL_MODEL", multimodal_assessment["issues"])

    def test_chat_model_cannot_be_used_as_reranker(self) -> None:
        """Rerank 工作负载必须使用专用重排模型。

        这是模型层收敛的重要边界：主聊天/Agent 模型可以解释和规划，但不应该承担所有模型职责。把 rerank
        做成独立能力，后续 RAG/GraphRAG 才能独立调优质量、延迟和成本。
        """

        registry = default_model_capability_registry()
        route = _route(
            workload=WorkloadType.RERANK,
            model_name="deepseek-v4-pro",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
        )

        assessment = registry.assess_route(route).to_summary()

        self.assertEqual("incompatible", assessment["compatibilityLevel"])
        self.assertIn("WORKLOAD_REQUIRES_RERANK_MODEL", assessment["issues"])

    def test_unknown_model_is_reported_without_leaking_endpoint(self) -> None:
        """未知模型不能被默认标记为生产就绪，也不能在诊断里泄露 endpoint。"""

        registry = default_model_capability_registry()
        route = _route(
            workload=WorkloadType.AGENT_REASONING,
            model_name="future-frontier-model-x",
            provider_type=ProviderType.OPENAI_COMPATIBLE,
            endpoint="https://internal-model-gateway.example.test/v1/chat/completions",
        )

        summary = registry.assess_route(route).to_summary()

        self.assertEqual("unknown_model_profile", summary["compatibilityLevel"])
        self.assertIn("MODEL_PROFILE_NOT_REGISTERED", summary["warnings"])
        self.assertNotIn("internal-model-gateway", str(summary))
        self.assertIsNone(summary["profile"])


def _route(
    workload: WorkloadType,
    model_name: str,
    provider_type: ProviderType,
    max_context_tokens: int = 131_072,
    endpoint: str | None = None,
) -> ModelRoute:
    return ModelRoute(
        workload=workload,
        provider_name=f"{model_name}-provider",
        provider_type=provider_type,
        model_name=model_name,
        endpoint=endpoint,
        max_context_tokens=max_context_tokens,
        priority=1,
        latency_tier=ModelLatencyTier.STANDARD,
        cost_tier=ModelCostTier.HIGH,
        cache_key_scope=ModelCacheKeyScope.SESSION_ONLY,
    )


if __name__ == "__main__":
    unittest.main()
