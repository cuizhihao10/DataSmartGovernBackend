"""可选 FastAPI API 入口。

当前项目的核心测试不依赖 FastAPI，因为 AI Runtime 的第一目标是先稳定领域契约与编排逻辑。
如果本地安装了 `python-ai-runtime[api]`，即可通过 `create_app()` 创建 HTTP 服务，供 Java
控制面或前端网关调用。

文件边界说明：
`api.py` 只保留 FastAPI 应用装配、路由注册和生命周期管理。默认 Agent 编排器、工具目录、Skill 目录、
上下文预算和工具预算策略的构建逻辑已经拆到 `api/agent/orchestrator_factory.py`。这样既保留
`from datasmart_ai_runtime.api import build_default_orchestrator` 这类历史导入方式，又避免 API 入口再次膨胀
成一个超过 500 行的启动巨石。
"""

from __future__ import annotations

import os
from typing import Any

from datasmart_ai_runtime.api.events import (
    build_event_control_response,
    build_event_replay_response,
    build_event_websocket_payloads,
)
from datasmart_ai_runtime.api.gateway.security import (
    GatewaySignatureSecurityStats,
    build_gateway_signature_nonce_store,
    gateway_signature_nonce_store_settings_from_env,
    gateway_signature_security_diagnostics,
)
from datasmart_ai_runtime.api.memory.materialization_admin import register_memory_materialization_admin_routes
from datasmart_ai_runtime.api.memory.runtime import api_memory_runtime_diagnostics, build_api_memory_runtime
from datasmart_ai_runtime.api.memory.write import register_memory_write_routes
from datasmart_ai_runtime.api.platform import register_platform_convergence_routes
from datasmart_ai_runtime.api.agent.orchestrator_factory import (
    build_context_selection_policy,
    build_default_orchestrator,
    build_tool_action_resume_fact_provider,
    build_tool_call_budget_policy_provider,
    build_tool_execution_readiness_policy_provider,
    load_skill_registry,
    load_tool_registry,
    optional_positive_int_env as _optional_positive_int_env,
    positive_int_env as _positive_int_env,
    truthy_env as _truthy_env,
)
from datasmart_ai_runtime.api.agent.capabilities import register_agent_capability_routes
from datasmart_ai_runtime.api.agent.routes import register_agent_runtime_routes
from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
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
from datasmart_ai_runtime.services.agent_capability import default_agent_capability_matrix_service
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnOrchestrator
from datasmart_ai_runtime.services.memory.memory_materialization_metrics import AgentMemoryMaterializationMetrics
from datasmart_ai_runtime.services.memory.memory_materialization_worker import (
    AgentMemoryMaterializationWorker,
    memory_materialization_worker_settings_from_env,
)
from datasmart_ai_runtime.services.model_gateway import (
    InMemoryModelProviderHealthRegistry,
    ModelGatewayGovernanceService,
    ModelProviderHealthProbeService,
    default_inference_optimization_diagnostics_service,
    default_model_capability_registry,
    model_provider_health_probe_settings_from_env,
    render_model_provider_health_probe_prometheus,
)
from datasmart_ai_runtime.services.model_gateway.model_provider import model_provider_registry_from_env
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.platform_convergence import default_platform_convergence_diagnostics_service
from datasmart_ai_runtime.services.runtime_events.runtime_event_components import (
    build_runtime_event_components,
    runtime_event_component_diagnostics,
)
from datasmart_ai_runtime.services.skills import build_skill_publication_manifest_diagnostics_service
from datasmart_ai_runtime.services.tools import (
    ToolActionCheckpointMetrics,
    build_tool_action_execution_checkpoint_store,
    tool_action_execution_checkpoint_store_diagnostics,
    tool_action_execution_checkpoint_store_settings_from_env,
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
    model_routes = model_routes_from_env()
    model_route_registry = ModelRouteRegistry(model_routes)
    # 模型能力矩阵是“模型层收敛”的控制面基线：它不负责训练模型、不执行真实推理，也不读取任何敏感请求正文。
    # 它只用当前路由中的 workload/providerType/modelName/maxContext/cacheScope 做低敏诊断，帮助管理台判断：
    # - 当前路由还是 dry-run 占位，还是真实 Provider；
    # - 模型是否适合 Agent、代码生成、Embedding、Rerank、多模态等工作负载；
    # - DeepSeek/Qwen/GLM/vLLM/SGLang 等接入方式是否仍需要 SKU、工具调用、缓存隔离或压测验证。
    model_capability_registry = default_model_capability_registry()
    # 推理优化诊断服务回答的是“成熟 serving stack 是否已经具备可观测优化闭环”，而不是“模型算法是否更强”。
    # 当前项目不做训练/微调/推理内核开发，只把 vLLM/SGLang/LiteLLM 所需指标固定成低敏合同。
    # FastAPI 路由默认不会主动访问任何外部 endpoint；真实指标后续应由 Prometheus、模型网关或 Java 控制面注入。
    inference_optimization_diagnostics = default_inference_optimization_diagnostics_service()
    platform_convergence_diagnostics = default_platform_convergence_diagnostics_service()
    agent_capability_matrix = default_agent_capability_matrix_service()
    model_provider_health_registry = InMemoryModelProviderHealthRegistry()
    model_provider_health_probe_settings = model_provider_health_probe_settings_from_env()
    model_provider_health_probe = ModelProviderHealthProbeService(
        model_provider_health_registry,
        settings=model_provider_health_probe_settings,
    )
    model_gateway = ModelGatewayGovernanceService(
        model_route_registry,
        health_registry=model_provider_health_registry,
    )
    memory_runtime = build_api_memory_runtime()
    agent_runtime_base_url = os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    # 工具目录在启动阶段集中加载一次，并同时供主 orchestrator 与协议适配 preview 入口使用。
    # 这样可以避免 `/agent/plans`、MCP tools/call preview 和未来确认页在同一进程里看到不同版本的工具目录：
    # - 生产环境可优先从 Java agent-runtime 动态读取；
    # - 本地学习或 Java 服务不可用时回退到 Python 默认工具目录；
    # - 后续如果要引入工具目录缓存、灰度版本或租户能力包，也可以先在这里统一形成快照。
    tool_registry = load_tool_registry(tool_registry_base_url=agent_runtime_base_url)
    orchestrator = build_default_orchestrator(
        model_gateway=model_gateway,
        memory_retriever=memory_runtime.memory_retriever,
        tool_registry=tool_registry,
    )
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
    tool_action_checkpoint_metrics = ToolActionCheckpointMetrics()
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
    skill_publication_manifest_diagnostics = build_skill_publication_manifest_diagnostics_service(agent_runtime_base_url)
    # readiness policy provider 负责把 permission-admin 的标准 `toolExecutionReadinessPolicy` 接入
    # `/agent/plans` 主链路。它与 orchestrator 内部的 `toolCallBudget` provider 分开装配，是为了保持
    # “模型候选工具预算”和“执行前准备度治理”两个阶段解耦。生产环境后续可以由 gateway 一次性注入完整
    # policy envelope，以减少同步策略 HTTP 调用次数；当前先保留远程优先、本地回退的最小闭环。
    tool_execution_readiness_policy_provider = build_tool_execution_readiness_policy_provider()
    # resume fact provider 负责把 checkpoint 恢复预检从“请求自报事实”推进到“服务端事实校验”。
    # 当前优先接 Java agent-runtime 的 fact bundle API：Java 统一聚合审批事实、outbox 写入事实和
    # worker/dry-run receipt；如果未启用 fact bundle，仍兼容 5.69 的 permission-admin 单点审批事实 provider。
    # 两条远程链路默认关闭；开启后，未被 Java 控制面采信的事实会通过 rejectedFactTypes 覆盖请求自报事实。
    tool_action_resume_fact_provider = build_tool_action_resume_fact_provider()
    # 工具动作执行前图 checkpoint store 是“暂停/恢复”能力的短期状态底座。
    #
    # 装配原则：
    # - 本地学习与默认测试环境继续使用 in-memory，保持零 Redis 依赖；
    # - 生产可以通过 DATASMART_TOOL_ACTION_CHECKPOINT_STORE=redis 切到 Redis，让多实例、重启和后续恢复预检
    #   能够读到同一个 checkpoint；
    # - 无论底层存储是什么，payload 都只允许保存低敏执行图摘要，不保存 prompt、SQL、工具参数值、模型输出或凭证。
    tool_action_checkpoint_store_settings = tool_action_execution_checkpoint_store_settings_from_env()
    tool_action_checkpoint_store = build_tool_action_execution_checkpoint_store(tool_action_checkpoint_store_settings)
    # checkpoint query/resume-preview 是恢复控制面的高价值入口：它们不执行工具，但会暴露暂停点是否存在、
    # 审批/澄清/outbox/worker receipt 等恢复事实是否齐备。为了支持“先收紧高风险入口，再逐步收紧全部
    # Python API”的灰度路径，这里提供 checkpoint 专用 fail-closed 开关：
    # - false：本地学习和旧测试仍可直连；
    # - true：必须通过统一 gateway HMAC 或等价服务账号签名访问；
    # - 即使该开关为 false，只要请求声称来自 gateway 且全局签名配置启用，错误签名仍会被拒绝。
    tool_action_checkpoint_gateway_signature_required = _truthy_env(
        "DATASMART_TOOL_ACTION_CHECKPOINT_GATEWAY_SIGNATURE_REQUIRED"
    )

    def _start_memory_materialization_worker() -> None:
        """FastAPI startup 生命周期中启动长期记忆物化 worker。"""

        memory_materialization_worker.start()

    def _refresh_skill_publication_manifest_diagnostics() -> None:
        """FastAPI startup 生命周期中刷新 Skill 发布 Manifest 诊断。

        这一步不会改变当前 AgentOrchestrator 已加载的 Skill descriptor，也不会直接影响模型规划。
        它的作用是让运维在服务启动后立刻看到远端 Skill 发布事实源是否健康、当前 Manifest 指纹是什么、
        READY/非 READY Skill 分布如何。如果远端不可用且未配置 required，服务仍会启动，但诊断会标记 fallback。
        """

        if skill_publication_manifest_diagnostics.should_refresh_on_startup():
            skill_publication_manifest_diagnostics.refresh()

    def _probe_model_provider_health_on_startup() -> None:
        """FastAPI startup 生命周期中按需执行模型 Provider 主动健康探测。

        默认不启用启动探测，是为了避免本地学习环境或 CI 因外部模型 endpoint 不可达而变慢。生产环境如果
        配置了真实 Provider endpoint，可以通过 `DATASMART_AI_MODEL_PROVIDER_HEALTH_PROBE_ON_STARTUP=true`
        让服务启动后立即生成第一批健康快照，减少长时间 UNKNOWN 对路由评分的影响。
        """

        if model_provider_health_probe_settings.startup_probe_enabled:
            model_provider_health_probe.probe_routes(
                model_route_registry.all_routes(),
                requested_by="startup",
            )

    def _stop_memory_materialization_worker() -> None:
        """FastAPI shutdown 生命周期中请求 worker 优雅停止。"""

        memory_materialization_worker.stop()

    app.add_event_handler("startup", _start_memory_materialization_worker)
    app.add_event_handler("startup", _refresh_skill_publication_manifest_diagnostics)
    app.add_event_handler("startup", _probe_model_provider_health_on_startup)
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

    @app.get("/agent/tool-actions/checkpoints/diagnostics")
    def tool_action_checkpoint_store_runtime_diagnostics() -> dict[str, Any]:
        """查询工具动作执行前图 checkpoint store 的启动诊断。

        该接口面向智能网关、Java 控制面和运维排障，用来确认当前 Python Runtime 到底使用 in-memory
        还是 Redis checkpoint store。响应不会读取或返回任何 checkpoint 正文，只返回实现类名、TTL、
        容量和脱敏后的 Redis URL，因此可以作为生产健康检查/配置检查的一部分。
        """

        return tool_action_execution_checkpoint_store_diagnostics(
            tool_action_checkpoint_store,
            tool_action_checkpoint_store_settings,
        )

    @app.get("/agent/skills/publication/diagnostics")
    def skill_publication_manifest_runtime_diagnostics() -> dict[str, Any]:
        """查询 Skill Publication Manifest 启动诊断。

        该接口面向智能网关、Java 控制面和运维台，用来确认 Python Runtime 当前是否看见 Java
        `agent-runtime` 发布的 Skill Manifest，以及远端不可用时是否回退到了本地默认 Skill。
        响应只返回指纹、数量、状态分布和低敏推荐动作，不返回完整 descriptor、权限明细、prompt 或工具参数。
        """

        return skill_publication_manifest_diagnostics.diagnostics()

    @app.post("/agent/skills/publication/refresh")
    @app.post("/api/agent/skills/publication/refresh")
    def refresh_skill_publication_manifest_runtime_cache(request: Request, force: bool = False) -> dict[str, Any]:
        """受控刷新 Skill Publication Manifest 诊断缓存。

        该路由用于项目闭环阶段的运行时校验：Java `agent-runtime` 负责 Skill 发布事实，Python Runtime
        负责消费最近一次低敏 Manifest 指纹与状态分布。为了避免每次 `/agent/plans` 都同步访问 Java
        控制面，刷新动作必须显式触发，并且默认遵守 TTL、冷却时间和失败重试策略。

        `force=true` 只表示绕过普通 TTL 与冷却时间，不表示绕过认证、远端未配置、Manifest required
        失败关闭等生产安全边界。响应只返回刷新原因、前后指纹、是否变化和最新诊断，不返回完整
        descriptor、prompt、工具参数、权限明细、Java URL、token 或异常堆栈。
        """

        try:
            return skill_publication_manifest_diagnostics.refresh_if_needed(
                trace_id=request.headers.get("X-DataSmart-Trace-Id"),
                force=force,
            )
        except Exception as exc:
            raise HTTPException(status_code=503, detail=f"Skill Publication Manifest 刷新失败：{str(exc)[:120]}") from exc

    @app.get("/agent/models/provider-health/diagnostics")
    def model_provider_health_diagnostics() -> dict[str, Any]:
        """查询模型 Provider 健康治理诊断信息。

        该接口面向智能网关运维、前端治理卡片和 Java 控制面排障。它只返回低敏状态事实：
        providerName、workload、modelName、健康状态、错误率、延迟、熔断窗口和推荐动作；不会返回 prompt、
        用户消息、工具参数、模型响应正文或 API Key。

        为什么放在 Python Runtime：
        当前真实 Provider 调用发生在 Python AI Runtime，因此最接近调用结果、延迟和错误码。Java
        agent-runtime 后续可以通过 gateway 调用该诊断，或把摘要同步到统一观测/审计中心；但不应在
        Java 侧凭空猜测 Python Provider 的真实健康状态。
        """

        diagnostics = model_provider_health_registry.diagnostics(model_route_registry.all_routes())
        diagnostics["activeProbe"] = model_provider_health_probe.diagnostics()
        return diagnostics

    @app.get("/agent/models/capabilities/diagnostics")
    def model_capability_diagnostics() -> dict[str, Any]:
        """查询模型能力矩阵与当前路由生产适配状态。

        路由语义：
        - 该接口面向智能网关、平台管理员、运维诊断和上线前检查；
        - 它回答“当前配置的模型是否适合对应 workload”，而不是发起一次模型调用；
        - 它不会访问外部 Provider，也不会触发 health probe，因此适合作为启动后快速配置检查。

        响应边界：
        - 返回模型名、工作负载、provider 类型、能力支持状态、生产缺口和推荐动作；
        - 不返回 endpoint、API Key、prompt、SQL、工具参数、样本数据、模型输出或内部服务地址；
        - `needs_provider_validation` 不代表不可用，而是提示必须补充 SKU/工具调用/缓存/压测验证。
        """

        return model_capability_registry.diagnostics(model_route_registry.all_routes())

    @app.get("/agent/models/inference-optimization/diagnostics")
    @app.get("/api/agent/models/inference-optimization/diagnostics")
    def model_inference_optimization_diagnostics() -> dict[str, Any]:
        """查询成熟推理服务优化诊断。

        路由语义：
        - 该接口面向智能网关、运维面板、Java 控制面和上线前检查；
        - 它不触发模型调用、不抓取 Prometheus、不访问 vLLM/SGLang/LiteLLM endpoint；
        - 当前仅根据模型路由推断 serving engine，并列出 TTFT、TPS、queue time、batching、cache hit rate、
          KV cache/GPU pressure 等生产推理优化所需的低敏指标缺口。

        低敏边界：
        - 返回 providerName、modelName、workload、serving engine、指标名称、状态码和建议动作；
        - 不返回 endpoint、API Key、prompt、messages、SQL、工具参数、样本数据、模型输出或内部网络地址。
        """

        return inference_optimization_diagnostics.diagnostics(model_route_registry.all_routes())

    @app.post("/agent/models/provider-health/probe")
    def model_provider_health_active_probe(dry_run: bool = False) -> dict[str, Any]:
        """显式触发模型 Provider 主动健康探测。

        路由语义：
        - `dry_run=true`：只返回将要探测的 Provider 摘要，不访问外部 endpoint，也不写回健康状态；
        - `dry_run=false`：访问真实健康检查地址，并把 HEALTHY/DEGRADED/UNAVAILABLE 快照写回 registry。

        该接口应由 gateway、平台管理员或运维工具保护。Python Runtime 这里只负责低敏探测与状态回灌，
        不在响应中暴露完整 URL query、API Key、prompt、工具参数、模型输出或真实 KV cache 内容。
        """

        return model_provider_health_probe.probe_routes(
            model_route_registry.all_routes(),
            requested_by="api",
            dry_run=dry_run,
        )

    @app.get("/agent/metrics")
    def agent_runtime_prometheus_metrics() -> Any:
        """导出 Python AI Runtime 的 Prometheus 文本指标。

        该端点是 Python AI Runtime 的轻量指标出口，当前合并两类低基数指标：
        - 长期记忆物化指标：用于观察后台物化批次、候选处理、补偿重排和 fencing/finalize 错误；
        - 模型 Provider 主动探测指标：用于观察健康探测运行次数、success/failure/skipped 分布、
          最近一轮状态分布和探测配置。
        - 工具动作 checkpoint 指标：用于观察 checkpoint query/resume-preview 的访问结果、恢复事实状态和访问问题。

        这里不输出 providerName、tenantId、projectId、runId、traceId、URL、prompt、工具参数或模型正文。
        这些明细属于 runtime event、诊断接口或审计链路，不能进入 Prometheus label，否则会在真实客户环境中
        制造高基数时序，并增加敏感信息泄露面。
        """

        metric_parts = (
            memory_materialization_metrics.render_prometheus().rstrip(),
            render_model_provider_health_probe_prometheus(model_provider_health_probe).rstrip(),
            tool_action_checkpoint_metrics.render_prometheus().rstrip(),
            "",
        )

        return Response(
            content="\n".join(metric_parts),
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
        skill_publication_diagnostics_service=skill_publication_manifest_diagnostics,
        tool_execution_readiness_policy_provider=tool_execution_readiness_policy_provider,
        agent_capability_matrix_service=agent_capability_matrix,
        tool_action_resume_fact_provider=tool_action_resume_fact_provider,
        tool_action_checkpoint_store=tool_action_checkpoint_store,
        tool_action_checkpoint_metrics=tool_action_checkpoint_metrics,
        tool_action_checkpoint_gateway_signature_required=tool_action_checkpoint_gateway_signature_required,
        tool_registry=tool_registry,
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
    register_platform_convergence_routes(app, diagnostics_service=platform_convergence_diagnostics)
    register_agent_capability_routes(app, capability_matrix_service=agent_capability_matrix)

    return app


__all__ = [
    "build_context_selection_policy",
    "build_default_orchestrator",
    "build_event_control_response",
    "build_event_replay_response",
    "build_event_websocket_payloads",
    "build_plan_response",
    "build_tool_action_resume_fact_provider",
    "build_tool_call_budget_policy_provider",
    "build_tool_execution_readiness_policy_provider",
    "create_app",
    "load_skill_registry",
    "load_tool_registry",
]
