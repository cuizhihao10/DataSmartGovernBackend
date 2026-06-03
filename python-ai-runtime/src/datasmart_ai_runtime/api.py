"""可选 FastAPI API 入口。

当前项目的核心测试不依赖 FastAPI，因为 AI Runtime 的第一目标是先稳定领域契约与编排逻辑。
如果本地安装了 `python-ai-runtime[api]`，即可通过 `create_app()` 创建 HTTP 服务，供 Java
控制面或前端网关调用。

文件边界说明：
`api.py` 只保留 FastAPI 应用装配、路由注册和生命周期管理。默认 Agent 编排器、工具目录、Skill 目录、
上下文预算和工具预算策略的构建逻辑已经拆到 `api_orchestrator_factory.py`。这样既保留
`from datasmart_ai_runtime.api import build_default_orchestrator` 这类历史导入方式，又避免 API 入口再次膨胀
成一个超过 500 行的启动巨石。
"""

from __future__ import annotations

import os
from typing import Any

from datasmart_ai_runtime.api_events import (
    build_event_control_response,
    build_event_replay_response,
    build_event_websocket_payloads,
)
from datasmart_ai_runtime.api_gateway_security import (
    GatewaySignatureSecurityStats,
    build_gateway_signature_nonce_store,
    gateway_signature_nonce_store_settings_from_env,
    gateway_signature_security_diagnostics,
)
from datasmart_ai_runtime.api_memory_materialization_admin import register_memory_materialization_admin_routes
from datasmart_ai_runtime.api_memory_runtime import api_memory_runtime_diagnostics, build_api_memory_runtime
from datasmart_ai_runtime.api_memory_write import register_memory_write_routes
from datasmart_ai_runtime.api_orchestrator_factory import (
    build_context_selection_policy,
    build_default_orchestrator,
    build_tool_call_budget_policy_provider,
    load_skill_registry,
    load_tool_registry,
    optional_positive_int_env as _optional_positive_int_env,
    positive_int_env as _positive_int_env,
    truthy_env as _truthy_env,
)
from datasmart_ai_runtime.api_agent_routes import register_agent_runtime_routes
from datasmart_ai_runtime.api_plan_response import build_plan_response
from datasmart_ai_runtime.config import model_routes_from_env
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackCollector
from datasmart_ai_runtime.services.agent_loop_control_policy import AgentLoopControlPolicyEvaluator
from datasmart_ai_runtime.services.agent_plan_ingestion_client import JavaAgentPlanIngestionClient
from datasmart_ai_runtime.services.agent_runtime_event_feedback import AgentRuntimeEventFeedbackBridge
from datasmart_ai_runtime.services.agent_runtime_event_replay_client import JavaAgentRuntimeEventReplayClient
from datasmart_ai_runtime.services.agent_runtime_tool_feedback_client import (
    JavaAgentRuntimeToolFeedbackClient,
    JavaAgentRuntimeToolFeedbackProvider,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnOrchestrator
from datasmart_ai_runtime.services.memory.memory_materialization_metrics import AgentMemoryMaterializationMetrics
from datasmart_ai_runtime.services.memory.memory_materialization_worker import (
    AgentMemoryMaterializationWorker,
    memory_materialization_worker_settings_from_env,
)
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_provider import model_provider_registry_from_env
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.runtime_events.runtime_event_components import (
    build_runtime_event_components,
    runtime_event_component_diagnostics,
)


def _build_runtime_event_replay_sources(agent_runtime_base_url: str | None) -> tuple[Any, ...]:
    """按环境变量装配外部 runtime-event replay source。

    当前只接入 Java `agent-runtime` 投影；但返回 tuple 而不是单对象，是为了给未来继续接入 Kafka
    replay、长期审计库、对象归档或多区域事件源预留组合空间。
    """

    if not agent_runtime_base_url or not _truthy_env("DATASMART_AGENT_RUNTIME_EVENT_REPLAY_ENABLED"):
        return ()
    return (
        JavaAgentRuntimeEventReplayClient(
            base_url=agent_runtime_base_url,
            timeout_seconds=_positive_int_env("DATASMART_AGENT_RUNTIME_EVENT_REPLAY_TIMEOUT_SECONDS", 3),
            replay_path=os.getenv("DATASMART_AGENT_RUNTIME_EVENT_REPLAY_PATH")
            or "/agent-runtime/runtime-events/replay",
            ack_path=os.getenv("DATASMART_AGENT_RUNTIME_EVENT_ACK_PATH")
            or "/agent-runtime/runtime-events/replay/acks",
            default_limit=_positive_int_env("DATASMART_AGENT_RUNTIME_EVENT_REPLAY_LIMIT", 200),
        ),
    )


def create_app() -> Any:
    """创建 FastAPI 应用。

    函数内部延迟导入 FastAPI，是为了让没有安装 API 依赖的开发环境仍然能运行核心单元测试。
    如果调用者确实要启动 HTTP 服务但没安装依赖，会得到清晰的错误提示。
    """

    try:
        from fastapi import FastAPI, HTTPException, Request, Response
    except ImportError as exc:  # pragma: no cover - 只有未安装 API 依赖时触发
        raise RuntimeError("启动 API 前请先安装可选依赖：pip install -e python-ai-runtime[api]") from exc
    globals()["Request"] = Request

    app = FastAPI(
        title="DataSmart Govern Python AI Runtime",
        version="0.1.0",
        description="用于模型路由、Agent 编排和工具计划生成的 Python 智能运行时。",
    )
    model_gateway = ModelGatewayGovernanceService(ModelRouteRegistry(model_routes_from_env()))
    memory_runtime = build_api_memory_runtime()
    orchestrator = build_default_orchestrator(
        model_gateway=model_gateway,
        memory_retriever=memory_runtime.memory_retriever,
    )
    agent_runtime_base_url = os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    plan_ingestion_client = (
        JavaAgentPlanIngestionClient(base_url=agent_runtime_base_url)
        if agent_runtime_base_url and _truthy_env("DATASMART_AGENT_RUNTIME_PLAN_INGESTION_ENABLED")
        else None
    )
    control_plane_feedback_collector = (
        AgentControlPlaneFeedbackCollector(
            JavaAgentRuntimeToolFeedbackProvider(
                JavaAgentRuntimeToolFeedbackClient(base_url=agent_runtime_base_url),
                auto_execute_sync_enabled=_truthy_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_ENABLED"),
                auto_execute_dry_run=_truthy_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_DRY_RUN"),
                max_auto_executions=_optional_positive_int_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_MAX"),
            )
        )
        if agent_runtime_base_url and _truthy_env("DATASMART_AGENT_RUNTIME_TOOL_FEEDBACK_ENABLED")
        else None
    )
    loop_control_evaluator = AgentLoopControlPolicyEvaluator() if control_plane_feedback_collector else None
    second_turn_orchestrator = (
        AgentSecondTurnOrchestrator(
            model_providers=model_provider_registry_from_env(),
            model_gateway=model_gateway,
        )
        if control_plane_feedback_collector and _truthy_env("DATASMART_AGENT_RUNTIME_SECOND_TURN_ENABLED")
        else None
    )
    runtime_event_replay_sources = _build_runtime_event_replay_sources(agent_runtime_base_url)
    runtime_event_feedback_bridge = (
        AgentRuntimeEventFeedbackBridge(runtime_event_replay_sources)
        if runtime_event_replay_sources and _truthy_env("DATASMART_AGENT_RUNTIME_EVENT_LOOP_FEEDBACK_ENABLED")
        else None
    )
    runtime_events = build_runtime_event_components(external_replay_sources=runtime_event_replay_sources)
    event_store = runtime_events.event_store
    session_manager = runtime_events.session_manager
    live_push_hub = runtime_events.live_push_hub
    event_publisher = runtime_events.event_publisher
    memory_materialization_metrics = AgentMemoryMaterializationMetrics()
    memory_materialization_worker = AgentMemoryMaterializationWorker(
        runner=memory_runtime.memory_materialization_runner,
        settings=memory_materialization_worker_settings_from_env(),
        event_store=event_store,
        event_publisher=event_publisher,
        metrics_recorder=memory_materialization_metrics,
        audit_outbox_recorder=memory_runtime.audit_outbox_runtime.recorder,
    )
    gateway_signature_nonce_settings = gateway_signature_nonce_store_settings_from_env()
    gateway_signature_nonce_store = build_gateway_signature_nonce_store(gateway_signature_nonce_settings)
    gateway_signature_security_stats = GatewaySignatureSecurityStats()

    def _start_memory_materialization_worker() -> None:
        """FastAPI startup 生命周期中启动长期记忆物化 worker。"""

        memory_materialization_worker.start()

    def _stop_memory_materialization_worker() -> None:
        """FastAPI shutdown 生命周期中请求 worker 优雅停止。"""

        memory_materialization_worker.stop()

    app.add_event_handler("startup", _start_memory_materialization_worker)
    app.add_event_handler("shutdown", _stop_memory_materialization_worker)

    @app.get("/agent/events/diagnostics")
    def runtime_event_diagnostics() -> dict[str, Any]:
        """查询实时事件组件诊断信息。"""

        return runtime_event_component_diagnostics(runtime_events)

    @app.get("/agent/memory/write-candidates/diagnostics")
    def memory_write_candidate_store_diagnostics() -> dict[str, Any]:
        """查询记忆写入候选 store 诊断信息，不返回任何候选内容。"""

        return api_memory_runtime_diagnostics(memory_runtime)["candidateStore"]

    @app.get("/agent/memory/diagnostics")
    def memory_runtime_diagnostics() -> dict[str, Any]:
        """查询长期记忆运行时整体诊断信息。"""

        diagnostics = api_memory_runtime_diagnostics(memory_runtime)
        worker_diagnostics = memory_materialization_worker.diagnostics()
        diagnostics["materializer"]["workerEnabled"] = worker_diagnostics["enabled"]
        diagnostics["materializationRunner"]["workerEnabled"] = worker_diagnostics["enabled"]
        diagnostics["materializationWorker"] = worker_diagnostics
        return diagnostics

    @app.get("/agent/security/gateway-signature/diagnostics")
    def gateway_signature_security_runtime_diagnostics() -> dict[str, Any]:
        """查询 gateway 签名安全诊断信息。"""

        return gateway_signature_security_diagnostics(
            gateway_signature_security_stats,
            gateway_signature_nonce_store,
            gateway_signature_nonce_settings,
        )

    @app.get("/agent/metrics")
    def agent_runtime_prometheus_metrics() -> Any:
        """导出 Python AI Runtime 的 Prometheus 文本指标。"""

        return Response(
            content=memory_materialization_metrics.render_prometheus(),
            media_type="text/plain; version=0.0.4; charset=utf-8",
        )

    register_agent_runtime_routes(
        app,
        request_type=Request,
        orchestrator=orchestrator,
        event_store=event_store,
        session_manager=session_manager,
        live_push_hub=live_push_hub,
        event_publisher=event_publisher,
        runtime_event_replay_sources=runtime_event_replay_sources,
        plan_ingestion_client=plan_ingestion_client,
        control_plane_feedback_collector=control_plane_feedback_collector,
        runtime_event_feedback_bridge=runtime_event_feedback_bridge,
        loop_control_evaluator=loop_control_evaluator,
        second_turn_orchestrator=second_turn_orchestrator,
        memory_write_governance=memory_runtime.memory_write_governance,
        gateway_signature_error_factory=lambda detail: HTTPException(status_code=401, detail=detail),
        gateway_signature_nonce_store=gateway_signature_nonce_store,
        gateway_signature_security_stats=gateway_signature_security_stats,
    )

    register_memory_write_routes(app, memory_runtime.memory_write_governance)
    register_memory_materialization_admin_routes(
        app,
        memory_runtime.memory_materialization_admin,
        event_store=event_store,
        event_publisher=event_publisher,
        metrics_recorder=memory_materialization_metrics,
        audit_outbox_recorder=memory_runtime.audit_outbox_runtime.recorder,
    )

    return app


__all__ = [
    "build_context_selection_policy",
    "build_default_orchestrator",
    "build_event_control_response",
    "build_event_replay_response",
    "build_event_websocket_payloads",
    "build_plan_response",
    "build_tool_call_budget_policy_provider",
    "create_app",
    "load_skill_registry",
    "load_tool_registry",
]
