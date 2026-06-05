import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_skill_registry, default_tool_registry
from datasmart_ai_runtime.domain.contracts import (
    AgentRequest,
    ModelCacheKeyScope,
    ModelCostTier,
    ModelLatencyTier,
    ModelRoute,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.domain.model_gateway import ModelProviderHealthSnapshot, ModelProviderHealthStatus
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.model_gateway import (
    InMemoryModelProviderHealthRegistry,
    ModelGatewayGovernanceService,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class AgentOrchestratorModelGatewayEventTest(unittest.TestCase):
    def test_model_gateway_event_records_route_scoring_and_cache_plan_summary(self) -> None:
        """模型网关路由事件应记录低敏 route scoring，方便后续回放和诊断。

        这个测试模拟一个真实商业化场景：配置主 Provider 已经降级，但备用 Provider 健康且具备
        `PROJECT_SAFE` cache 边界。Agent 主链不应该只在 API response 中解释这件事，还应该把同一份
        低敏事实写入 runtime events。这样前端 timeline、Java projection、审计台和运维面板都能解释：
        - 为什么本次没有使用配置主路由；
        - fallback 是否发生；
        - cache plan 是否开启；
        - 每个候选 Provider 的排序依据是什么。
        """

        orchestrator = _orchestrator_with_gateway(
            routes=(
                _route(
                    "primary-agent",
                    priority=1,
                    cache_key_scope=ModelCacheKeyScope.SESSION_ONLY,
                ),
                _route(
                    "fallback-agent",
                    priority=10,
                    cache_key_scope=ModelCacheKeyScope.PROJECT_SAFE,
                ),
            ),
            health_snapshots=(
                ModelProviderHealthSnapshot(
                    provider_name="primary-agent",
                    status=ModelProviderHealthStatus.DEGRADED,
                    latency_ms=4500,
                    notes="测试中模拟主模型延迟偏高。",
                ),
                ModelProviderHealthSnapshot(
                    provider_name="fallback-agent",
                    status=ModelProviderHealthStatus.HEALTHY,
                    latency_ms=300,
                ),
            ),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析这个数据源的元数据结构",
                variables={"datasourceId": "ds-001", "sessionId": "session-route-event"},
            )
        )

        gateway_event = _gateway_event(plan.runtime_events)

        self.assertEqual("datasmart.ai-runtime.model-gateway-routed.v2", gateway_event.attributes["schemaVersion"])
        self.assertEqual(
            "SUMMARY_ONLY_NO_PROMPT_NO_TOOL_ARGS_NO_MODEL_OUTPUT_NO_KV_CACHE",
            gateway_event.attributes["eventPayloadPolicy"],
        )
        self.assertEqual("primary-agent", gateway_event.attributes["configuredPrimaryProvider"])
        self.assertEqual("fallback-agent", gateway_event.attributes["selectedProvider"])
        self.assertEqual("fallback-agent-model", gateway_event.attributes["selectedModel"])
        self.assertEqual("healthy", gateway_event.attributes["selectedHealthStatus"])
        self.assertTrue(gateway_event.attributes["fallbackUsed"])
        self.assertTrue(gateway_event.attributes["cacheAwareRouting"])
        self.assertEqual(("fallback-agent", "primary-agent"), gateway_event.attributes["orderedCandidateProviders"])
        self.assertEqual(2, gateway_event.attributes["candidateCount"])
        self.assertTrue(gateway_event.attributes["cachePlanEnabled"])
        self.assertEqual("project_safe", gateway_event.attributes["cachePlanScope"])
        self.assertIn("tenant:tenant-a:project:project-a", gateway_event.attributes["cachePlanNamespace"])

        route_scoring = gateway_event.attributes["routeScoring"]
        self.assertEqual(2, gateway_event.attributes["routeScoringCount"])
        self.assertFalse(gateway_event.attributes["routeScoringTruncated"])
        self.assertEqual("primary-agent", route_scoring[0]["providerName"])
        self.assertEqual("degraded", route_scoring[0]["healthStatus"])
        self.assertEqual("fallback-agent", route_scoring[1]["providerName"])
        self.assertEqual("healthy", route_scoring[1]["healthStatus"])

    def test_model_gateway_event_does_not_expose_sensitive_runtime_payload(self) -> None:
        """模型网关事件必须坚持 summary-only，不能把可执行或敏感 payload 带进 timeline。

        Runtime event 往往会被更多系统读取：前端实时窗口、Java 控制面、审计导出、运维诊断和告警系统。
        因此它的安全边界要比内存中的领域对象更严格。这里显式检查若干高风险字段，避免后续开发者为了
        调试方便把 prompt、messages、工具参数、模型输出或真实 KV cache 细节塞进事件。
        """

        orchestrator = _orchestrator_with_gateway(
            routes=(
                _route("primary-agent", cache_key_scope=ModelCacheKeyScope.PROJECT_SAFE),
            ),
            health_snapshots=(
                ModelProviderHealthSnapshot(
                    provider_name="primary-agent",
                    status=ModelProviderHealthStatus.HEALTHY,
                ),
            ),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请读取数据源 ds-001 的表结构，并不要把这段自然语言写进事件属性",
                variables={
                    "datasourceId": "ds-001",
                    "sessionId": "session-sensitive-check",
                    "toolArguments": {"sql": "select * from sensitive_table"},
                },
            )
        )

        serialized_attributes = json.dumps(
            _gateway_event(plan.runtime_events).attributes,
            ensure_ascii=False,
            sort_keys=True,
        )

        for forbidden in (
            "prompt",
            "messages",
            "toolArguments",
            "modelOutput",
            "kvCache",
            "keyPrefix",
            "isolationKey",
            "reusableContextHint",
            "select * from sensitive_table",
        ):
            self.assertNotIn(forbidden, serialized_attributes)


def _orchestrator_with_gateway(
    *,
    routes: tuple[ModelRoute, ...],
    health_snapshots: tuple[ModelProviderHealthSnapshot, ...],
) -> AgentOrchestrator:
    """构造带自定义模型网关的编排器。

    这里仍然复用真实 `ToolPlanner`、`SkillRegistry` 和 dry-run Provider，只替换模型路由与健康台账。
    这样测试覆盖的是 Agent 主链和模型网关事件的集成效果，而不是只测一个孤立 helper。
    """

    route_registry = ModelRouteRegistry(routes)
    return AgentOrchestrator(
        model_routes=route_registry,
        tool_planner=ToolPlanner(default_tool_registry()),
        model_gateway=ModelGatewayGovernanceService(
            route_registry,
            health_registry=InMemoryModelProviderHealthRegistry(health_snapshots),
        ),
        skill_registry=AgentSkillRegistry(default_skill_registry()),
    )


def _route(
    provider_name: str,
    priority: int = 1,
    cache_key_scope: ModelCacheKeyScope = ModelCacheKeyScope.SESSION_ONLY,
) -> ModelRoute:
    """生成测试用模型路由。

    测试路由只保留模型网关决策所需的关键字段：Provider、模型名、优先级和 cache scope。真实生产配置
    会额外包含 endpoint、超时、健康检查路径、成本等级等字段，但这些不影响本测试关注的事件属性。
    """

    return ModelRoute(
        workload=WorkloadType.AGENT_REASONING,
        provider_name=provider_name,
        provider_type=ProviderType.DRY_RUN,
        model_name=f"{provider_name}-model",
        priority=priority,
        latency_tier=ModelLatencyTier.STANDARD,
        cost_tier=ModelCostTier.MEDIUM,
        cache_key_scope=cache_key_scope,
    )


def _gateway_event(events):
    """从 Agent runtime events 中取出模型网关路由事件。"""

    return next(event for event in events if event.event_type == AgentRuntimeEventType.MODEL_GATEWAY_ROUTED)


if __name__ == "__main__":
    unittest.main()
