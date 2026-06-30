import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import (
    build_default_orchestrator,
    build_event_replay_response,
    build_plan_response,
    create_app,
)
from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayBudgetPolicy
from datasmart_ai_runtime.services.model_gateway import InMemoryModelBudgetLedger, ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import InMemoryRuntimeEventStore


class FakeRuntimeEventPublisher:
    """记录 API 响应层是否把规划事件交给异步发布器。

    真实生产环境里这里会接 Kafka、Java 控制面 outbox 或审计事件总线；单元测试只需要知道：
    - API 响应组装层确实调用了发布器；
    - 发布数量和 `plan.runtime_events` 数量一致；
    - 不需要启动 Kafka，也不需要把事件正文打印到测试日志。
    """

    def __init__(self) -> None:
        self.published_batches: list[int] = []

    def publish(self, events) -> int:
        self.published_batches.append(len(events))
        return len(events)


class ApiResponseIntegrationTest(unittest.TestCase):
    """覆盖 API 响应层与真实 FastAPI 工厂的轻量集成行为。

    本文件从 `test_api_bootstrap.py` 拆出，目的是控制单文件行数并让测试职责更清晰：
    - bootstrap 文件继续关注工具/Skill 目录加载、默认 orchestrator 和事件 replay helper；
    - 本文件关注 `build_plan_response(...)` 对 event store、event publisher、model gateway 摘要的组装；
    - 同时补一条真实 `create_app()` 实例化测试，防止 FakeApp 契约测试漏掉 FastAPI 装配漂移。
    """

    def test_plan_response_can_store_events_for_later_replay(self) -> None:
        """规划响应可以把运行时事件写入内存事件库，供后续 replay 查询。

        这个能力对应真实智能网关里的断线续传和审计回放雏形。测试只使用 in-memory store，
        不连接 MySQL、Redis 或 Kafka，避免把响应组装测试升级成外部依赖测试。
        """

        orchestrator = build_default_orchestrator()
        store = InMemoryRuntimeEventStore()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001", "sessionId": "session-store"},
        )

        build_plan_response(request, orchestrator, event_store=store)
        response = build_event_replay_response(
            RuntimeEventSubscriptionRequest(
                client_id="client-a",
                session_id="session-store",
                after_sequence=0,
            ),
            event_store=store,
        )

        envelope = response["eventEnvelope"]
        self.assertTrue(envelope["events"])
        self.assertEqual("session-store", envelope["session_id"])
        self.assertEqual(1, envelope["sequence_from"])

    def test_plan_response_can_publish_runtime_events_to_async_bus(self) -> None:
        """规划响应可以把运行时事件交给异步发布器。

        这里验证的是发布边界，而不是 Kafka 客户端本身：响应层只负责把低敏事件批次交出去；
        事件是否落 Kafka、审计库或 Java outbox，应由具体 publisher 实现负责。
        """

        orchestrator = build_default_orchestrator()
        publisher = FakeRuntimeEventPublisher()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001", "sessionId": "session-publisher"},
        )

        response = build_plan_response(request, orchestrator, event_publisher=publisher)

        self.assertEqual([len(response["plan"]["runtime_events"])], publisher.published_batches)
        self.assertGreater(publisher.published_batches[0], 0)

    def test_plan_response_exposes_budget_blocked_model_gateway_summary(self) -> None:
        """模型网关预算拒绝时，响应层暴露低敏治理摘要。

        测试关注点是“控制面解释是否进入响应”，而不是模型调用本身。项目当前不做底层推理优化，
        但需要把成熟模型网关的预算、限流、路由和降级结果稳定呈现给 Agent 调度层。
        """

        routes = ModelRouteRegistry(default_model_routes())
        budget_ledger = InMemoryModelBudgetLedger(
            (ModelGatewayBudgetPolicy(tenant_id="tenant-a", project_id="project-a", monthly_token_budget=1),)
        )
        model_gateway = ModelGatewayGovernanceService(routes, budget_ledger=budget_ledger)
        orchestrator = build_default_orchestrator(model_gateway=model_gateway)
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001"},
        )

        response = build_plan_response(request, orchestrator)

        governance = response["modelGatewayGovernance"]
        self.assertFalse(governance["available"])
        self.assertFalse(governance["budgetAllowed"])
        self.assertIsNone(governance["selectedModel"])
        self.assertTrue(any("预算" in action for action in governance["recommendedActions"]))

    def test_create_app_registers_real_fastapi_routes_when_api_dependency_is_available(self) -> None:
        """真实创建 FastAPI app，防止 FakeApp 契约测试漏掉启动装配漂移。

        过去大量 API 测试为了保持核心运行时零 FastAPI 依赖，会使用 FakeApp 捕获路由函数。
        这种方式适合验证 handler 契约，但无法发现真实 `create_app()` 中的框架生命周期注册、
        路由函数签名和关键字参数是否已经随重构漂移。

        本测试只在安装可选 API 依赖时执行，不访问外部网络、不启动端口、不调用工具、不连接 MySQL。
        它仅确认应用工厂可以实例化，并且低敏诊断入口已经挂载到真实 FastAPI 路由表中。
        """

        try:
            app = create_app()
        except RuntimeError as exc:
            if "可选依赖" in str(exc):
                self.skipTest("未安装 python-ai-runtime[api]，跳过真实 FastAPI 工厂测试。")
            raise

        route_paths = {getattr(route, "path", "") for route in getattr(app, "routes", ())}
        self.assertIn("/agent/capabilities/closure-readiness", route_paths)
        self.assertIn("/agent/skills/publication/diagnostics", route_paths)
        self.assertIn("/agent/models/inference-optimization/diagnostics", route_paths)


if __name__ == "__main__":
    unittest.main()
