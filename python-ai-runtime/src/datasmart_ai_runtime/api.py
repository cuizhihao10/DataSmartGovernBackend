"""可选 FastAPI API 入口。

当前项目的核心测试不依赖 FastAPI，因为 AI Runtime 的第一目标是先稳定领域契约与编排逻辑。
如果本地安装了 `python-ai-runtime[api]`，即可通过 `create_app()` 创建 HTTP 服务，供 Java
控制面或前端网关调用。
"""

from __future__ import annotations

import asyncio
import os
from contextlib import suppress
from typing import Any

from datasmart_ai_runtime.api_events import (
    build_event_control_response,
    build_event_replay_response,
    build_event_websocket_payloads,
    runtime_event_from_payload,
    subscription_request_from_payload,
)
from datasmart_ai_runtime.api_plan_response import build_plan_response
from datasmart_ai_runtime.api_memory_write import register_memory_write_routes
from datasmart_ai_runtime.config import default_skill_registry, default_tool_registry, model_routes_from_env
from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolDefinition
from datasmart_ai_runtime.domain.context import ContextSensitivityLevel
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.hybrid_context_builder import ContextSelectionPolicy, HybridContextBuilder
from datasmart_ai_runtime.services.model_provider import model_provider_registry_from_env
from datasmart_ai_runtime.services.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.memory_planner import AgentMemoryPlanner
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.memory_write_components import (
    build_memory_write_store_runtime,
    memory_write_store_diagnostics,
)
from datasmart_ai_runtime.services.runtime_event_components import (
    build_runtime_event_components,
    runtime_event_component_diagnostics,
)
from datasmart_ai_runtime.services.runtime_event_websocket import RuntimeEventWebSocketConnectionAdapter
from datasmart_ai_runtime.services.tool_registry_client import JavaAgentToolRegistryClient, ToolRegistryClientError
from datasmart_ai_runtime.services.tool_planner import ToolPlanner
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.skill_registry_client import JavaAgentSkillRegistryClient, SkillRegistryClientError
from datasmart_ai_runtime.services.agent_runtime_tool_feedback_client import (
    JavaAgentRuntimeToolFeedbackClient,
    JavaAgentRuntimeToolFeedbackProvider,
)
from datasmart_ai_runtime.services.agent_runtime_event_replay_client import JavaAgentRuntimeEventReplayClient
from datasmart_ai_runtime.services.agent_runtime_event_feedback import AgentRuntimeEventFeedbackBridge
from datasmart_ai_runtime.services.agent_plan_ingestion_client import JavaAgentPlanIngestionClient
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackCollector
from datasmart_ai_runtime.services.agent_loop_control_policy import AgentLoopControlPolicyEvaluator
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import AgentSecondTurnOrchestrator
from datasmart_ai_runtime.services.memory_write_governance import AgentMemoryWriteGovernanceService


def load_tool_registry(
    tool_registry_base_url: str | None = None,
    prefer_remote_tools: bool = False,
    allow_remote_fallback: bool = True,
    trace_id: str | None = None,
    tool_registry_client: Any | None = None,
) -> tuple[ToolDefinition, ...]:
    """加载 Agent 工具目录。

    加载策略分三层：
    1. 如果调用方显式传入 `tool_registry_client`，优先使用它，便于单元测试和未来依赖注入；
    2. 如果配置了 `tool_registry_base_url` 或环境变量 `DATASMART_AGENT_RUNTIME_BASE_URL`，尝试读取 Java 工具目录；
    3. 如果未配置远程目录，或远程目录失败且允许降级，则使用本地默认工具清单。

    如果远程客户端支持 `list_tool_descriptors(...)`，这里会优先读取 Java 的 MCP-style descriptor，
    因为 descriptor 包含敏感字段、租户/项目范围、缓存策略和记忆写入策略，比旧 `list_tools(...)`
    更适合 Agent 规划。旧接口仍作为兼容路径保留，避免某些环境 Java 服务尚未升级时 Python
    Runtime 无法启动。

    这样设计是为了兼顾两种场景：
    - 本地开发：不启动 Java 服务也能测试 Python 编排；
    - 集成/生产：Java `agent-runtime` 成为工具目录单一事实源，减少 Python/Java 双写漂移。
    """

    client = tool_registry_client
    resolved_base_url = tool_registry_base_url or os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    if client is None and resolved_base_url:
        client = JavaAgentToolRegistryClient(base_url=resolved_base_url)

    if client is None:
        return default_tool_registry()

    try:
        if hasattr(client, "list_tool_descriptors"):
            return client.list_tool_descriptors(enabled_only=True, trace_id=trace_id)
        return client.list_tools(enabled_only=True, trace_id=trace_id)
    except ToolRegistryClientError:
        if prefer_remote_tools and not allow_remote_fallback:
            raise
        return default_tool_registry()


def load_skill_registry(
    skill_registry_base_url: str | None = None,
    prefer_remote_skills: bool = False,
    allow_remote_fallback: bool = True,
    trace_id: str | None = None,
    skill_registry_client: Any | None = None,
) -> tuple[Any, ...]:
    """加载 Agent Skill 注册表。

    加载策略与工具目录保持一致，但使用独立函数，避免把工具与 Skill 的降级语义混在一起：
    - 集成/生产环境优先从 Java `agent-runtime` 读取 `/agent-runtime/skills/descriptors`；
    - 本地开发或 Java 服务不可用时，可以回退 `default_skill_registry()`；
    - 如果调用方设置 `prefer_remote_skills=True` 且关闭 fallback，则远程失败会直接抛出，适合 CI 或集成环境。
    """

    client = skill_registry_client
    resolved_base_url = skill_registry_base_url or os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    if client is None and resolved_base_url:
        client = JavaAgentSkillRegistryClient(base_url=resolved_base_url)

    if client is None:
        return default_skill_registry()

    try:
        return client.list_skill_descriptors(enabled_only=True, trace_id=trace_id)
    except SkillRegistryClientError:
        if prefer_remote_skills and not allow_remote_fallback:
            raise
        return default_skill_registry()


def build_context_selection_policy(
    context_max_tokens: int | None = None,
    allowed_context_sensitivity_levels: tuple[ContextSensitivityLevel, ...] | None = None,
) -> ContextSelectionPolicy:
    """构建默认上下文选择策略。

    这里把上下文预算独立出来，而不是写死在 `HybridContextBuilder` 或 `AgentOrchestrator` 中。
    原因是不同部署环境的模型上下文长度、成本预算和合规策略不同：
    - 本地开发可以用较小预算快速测试；
    - 生产环境可以按模型、租户、套餐或任务类型配置不同预算；
    - 高合规环境可以收紧允许进入模型的敏感级别。

    当前先支持 `DATASMART_AI_CONTEXT_MAX_TOKENS` 环境变量。敏感级别仍通过参数注入，避免过早设计
    复杂字符串解析规则；后续接配置中心时可以统一扩展。
    """

    resolved_max_tokens = context_max_tokens
    if resolved_max_tokens is None:
        raw_value = os.getenv("DATASMART_AI_CONTEXT_MAX_TOKENS")
        if raw_value:
            resolved_max_tokens = int(raw_value)

    return ContextSelectionPolicy(
        max_tokens=resolved_max_tokens or 2048,
        allowed_sensitivity_levels=allowed_context_sensitivity_levels
        or (
            ContextSensitivityLevel.PUBLIC,
            ContextSensitivityLevel.INTERNAL,
            ContextSensitivityLevel.CONFIDENTIAL,
        ),
    )


def build_default_orchestrator(
    tool_registry_base_url: str | None = None,
    prefer_remote_tools: bool = False,
    allow_remote_fallback: bool = True,
    trace_id: str | None = None,
    tool_registry_client: Any | None = None,
    skill_registry_base_url: str | None = None,
    prefer_remote_skills: bool = False,
    allow_remote_skill_fallback: bool = True,
    skill_registry_client: Any | None = None,
    model_gateway: ModelGatewayGovernanceService | None = None,
    context_max_tokens: int | None = None,
    allowed_context_sensitivity_levels: tuple[ContextSensitivityLevel, ...] | None = None,
    enable_remote_tool_feedback: bool | None = None,
) -> AgentOrchestrator:
    """创建默认 Agent 编排器。

    这个函数把默认模型路由和工具注册表组装起来，方便 API、命令行、测试或未来 Kafka Consumer
    复用同一套启动逻辑。生产环境中可以：
    - 通过 `DATASMART_AGENT_RUNTIME_BASE_URL` 或显式参数从 Java `agent-runtime` 动态拉取工具目录；
    - 通过 `DATASMART_AI_CONTEXT_MAX_TOKENS` 或显式参数控制上下文 token 预算；
    - 通过 `allowed_context_sensitivity_levels` 控制哪些敏感级别允许进入模型上下文。
    """

    tools = load_tool_registry(
        tool_registry_base_url=tool_registry_base_url,
        prefer_remote_tools=prefer_remote_tools,
        allow_remote_fallback=allow_remote_fallback,
        trace_id=trace_id,
        tool_registry_client=tool_registry_client,
    )
    skills = load_skill_registry(
        skill_registry_base_url=skill_registry_base_url or tool_registry_base_url,
        prefer_remote_skills=prefer_remote_skills,
        allow_remote_fallback=allow_remote_skill_fallback,
        trace_id=trace_id,
        skill_registry_client=skill_registry_client,
    )
    context_policy = build_context_selection_policy(
        context_max_tokens=context_max_tokens,
        allowed_context_sensitivity_levels=allowed_context_sensitivity_levels,
    )
    resolved_base_url = tool_registry_base_url or os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    remote_feedback_enabled = (
        _truthy_env("DATASMART_AGENT_RUNTIME_TOOL_FEEDBACK_ENABLED")
        if enable_remote_tool_feedback is None
        else enable_remote_tool_feedback
    )
    tool_feedback_provider = None
    if remote_feedback_enabled and resolved_base_url:
        # 该 Provider 会在 ToolPlan 带有 Java session/run/audit 引用时查询真实结果；
        # 缺少引用或 Java 暂不可用时自动回退模拟反馈，保证本地开发和渐进式集成不被打断。
        tool_feedback_provider = JavaAgentRuntimeToolFeedbackProvider(
            JavaAgentRuntimeToolFeedbackClient(base_url=resolved_base_url),
            trace_id=trace_id,
            auto_execute_sync_enabled=_truthy_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_ENABLED"),
            auto_execute_dry_run=_truthy_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_DRY_RUN"),
            max_auto_executions=_optional_positive_int_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_MAX"),
        )
    return AgentOrchestrator(
        model_routes=ModelRouteRegistry(model_routes_from_env()),
        tool_planner=ToolPlanner(tools),
        model_providers=model_provider_registry_from_env(),
        context_builder=HybridContextBuilder(policy=context_policy),
        memory_planner=AgentMemoryPlanner(),
        model_gateway=model_gateway,
        skill_registry=AgentSkillRegistry(skills),
        tool_execution_feedback_provider=tool_feedback_provider,
    )


def _truthy_env(name: str) -> bool:
    """解析布尔环境变量。

    Python 的 `bool("false")` 会得到 True，因此生产开关不能直接用 bool 转换。
    这里集中处理常见写法，避免运维在 `.env` 中写 `false` 却意外启用远程反馈查询。
    """

    value = os.getenv(name)
    if value is None:
        return False
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _positive_int_env(name: str, default: int) -> int:
    """读取正整数环境变量。

    Java runtime-event replay 属于 WebSocket subscribe/reconnect 的交互链路，timeout 和 limit 不能写死。
    这里单独做一个小 helper，避免 `create_app()` 里堆叠重复的 try/except，也避免非法配置把服务启动到
    一个不可预测的状态。
    """

    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


def _optional_positive_int_env(name: str) -> int | None:
    """读取可选正整数环境变量。

    该 helper 用于“Python 可以不传，让 Java 控制面按服务端配置决定”的场景。
    例如同步自动执行最大数量：如果 Python 写死默认值，可能会覆盖 Java 侧按环境、租户或灰度配置的上限。
    """

    value = os.getenv(name)
    if value is None or not value.strip():
        return None
    parsed = int(value)
    return parsed if parsed > 0 else None


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
        from fastapi import FastAPI
    except ImportError as exc:  # pragma: no cover - 只有未安装 API 依赖时触发
        raise RuntimeError("启动 API 前请先安装可选依赖：pip install -e python-ai-runtime[api]") from exc

    app = FastAPI(
        title="DataSmart Govern Python AI Runtime",
        version="0.1.0",
        description="用于模型路由、Agent 编排和工具计划生成的 Python 智能运行时。",
    )
    model_gateway = ModelGatewayGovernanceService(ModelRouteRegistry(model_routes_from_env()))
    orchestrator = build_default_orchestrator(model_gateway=model_gateway)
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
        # 该桥接器会把 Java runtime-event replay 结果用于受控 loop 决策。
        # 它与上面的 WebSocket/HTTP replay 开关拆成两个环境变量，是为了给生产灰度留下空间：
        # 先允许前端“看见”Java 事件，再逐步允许这些事件影响自动二轮推理。
        AgentRuntimeEventFeedbackBridge(runtime_event_replay_sources)
        if runtime_event_replay_sources and _truthy_env("DATASMART_AGENT_RUNTIME_EVENT_LOOP_FEEDBACK_ENABLED")
        else None
    )
    runtime_events = build_runtime_event_components(external_replay_sources=runtime_event_replay_sources)
    event_store = runtime_events.event_store
    session_manager = runtime_events.session_manager
    live_push_hub = runtime_events.live_push_hub
    event_publisher = runtime_events.event_publisher
    # 记忆写入候选 store 是长期记忆写入治理的“事实暂存层”：
    # - 默认 in-memory，保证本地学习、单元测试和离线规划不需要任何数据库；
    # - 显式配置 sqlite/mysql 后，候选可以跨 Python Runtime 重启恢复；
    # - 如果配置了持久化但连接失败，是否回退内存由 fail-open 控制，避免生产环境悄悄丢候选。
    # 这里把 store 组装与治理服务拆开，是为了让治理服务只关心候选状态机和审批规则，
    # 不把数据库驱动、DSN、连接超时等基础设施细节耦合进业务逻辑。
    memory_write_store_runtime = build_memory_write_store_runtime()
    memory_write_governance = AgentMemoryWriteGovernanceService(store=memory_write_store_runtime.store)

    @app.get("/agent/events/diagnostics")
    def runtime_event_diagnostics() -> dict[str, Any]:
        """查询实时事件组件诊断信息。

        这个接口面向本地联调、部署排障和平台运维。它不会返回真实事件、订阅内容或用户数据，只返回：
        - 当前 event store / checkpoint store / outbox store 使用的实现；
        - Redis URL 的脱敏摘要；
        - TTL、stream key、replay 扫描窗口、心跳超时等关键参数。

        商业化生产环境中，该接口后续应由 Java gateway 和 permission-admin 保护，只允许平台管理员、
        运维人员或服务账号访问。当前 Python Runtime 先暴露诊断契约，便于多实例和 Redis 模式联调。
        """

        return runtime_event_component_diagnostics(runtime_events)

    @app.get("/agent/memory/write-candidates/diagnostics")
    def memory_write_candidate_store_diagnostics() -> dict[str, Any]:
        """查询记忆写入候选 store 诊断信息。

        这个接口服务于长期记忆能力的生产化排障。它不返回任何候选内容，只回答：
        - 启动时配置的是 `in-memory`、`sqlite` 还是 `mysql`；
        - 当前真实实现是不是持久化 store；
        - 如果配置了 MySQL/SQLite 却回退内存，回退原因是什么；
        - 连接字符串是否已经脱敏。

        后续进入商业化部署时，该路由应像候选审批路由一样由 gateway/permission-admin 保护，
        只允许平台管理员、运维人员或服务账号访问。
        """

        return memory_write_store_diagnostics(memory_write_store_runtime)

    @app.post("/agent/plans")
    def create_agent_plan(payload: dict[str, Any]) -> dict[str, Any]:
        """生成 Agent 工具计划。

        当前先接收 dict 并转换为 `AgentRequest`，后续可替换为 Pydantic DTO。这样能保持核心领域层
        不依赖 Web 框架，同时让 API 层承担请求校验与协议适配职责。
        """

        request = AgentRequest(**payload)
        return build_plan_response(
            request,
            orchestrator,
            event_store=event_store,
            live_push_hub=live_push_hub,
            event_publisher=event_publisher,
            plan_ingestion_client=plan_ingestion_client,
            control_plane_feedback_collector=control_plane_feedback_collector,
            runtime_event_feedback_bridge=runtime_event_feedback_bridge,
            loop_control_evaluator=loop_control_evaluator,
            second_turn_orchestrator=second_turn_orchestrator,
            memory_write_governance=memory_write_governance,
        )

    @app.post("/agent/events/replay")
    def replay_agent_events(payload: dict[str, Any]) -> dict[str, Any]:
        """按订阅请求回放 Agent 事件。

        当前路由是协议适配雏形，并不声称已经具备生产级事件存储能力。调用方需要传入：
        - `subscription`：订阅请求，包含 clientId/sessionId/runId/requestId/afterSequence/eventTypes；
        - `events`：待筛选的事件集合，通常可直接使用 `/agent/plans` 返回的 `plan.runtimeEvents`。

        后续真正接入智能网关时，`events` 应由服务端根据 sessionId/runId 从事件存储中加载，而不是由
        前端回传；这里先让同步 replay 协议可测试、可阅读、可演进。
        """

        subscription = subscription_request_from_payload(payload.get("subscription", {}))
        events = tuple(runtime_event_from_payload(item) for item in payload.get("events", ()))
        return build_event_replay_response(
            subscription,
            events=events,
            event_store=event_store if not events else None,
            external_replay_sources=runtime_event_replay_sources,
        )

    @app.post("/agent/events/control")
    def control_agent_event_subscription(payload: dict[str, Any]) -> dict[str, Any]:
        """处理实时事件订阅控制消息。

        这是 WebSocket 落地前的同步控制入口，主要用于前端/网关联调和单元测试协议：
        - `subscribe`：建立订阅并返回 subscriptionId，可携带 sessionId/runId/requestId/afterSequence；
        - `ack`：确认客户端已经处理到的 sequence；
        - `heartbeat`：连接保活，也可携带 lastSequence；
        - `reconnect`：断线后按 afterSequence 或服务端最近 ack 构建 replay；
        - `unsubscribe`：主动关闭订阅并记录关闭原因。

        真实 WebSocket handler 后续只需要在消息循环里调用 `build_event_control_response(...)`，并把返回
        的响应通过 socket 发回客户端；这样 HTTP 与 WebSocket 不会出现两套状态机。
        """

        return build_event_control_response(payload, session_manager)

    @app.websocket("/agent/events/ws")
    async def websocket_agent_event_subscription(websocket: Any) -> None:
        """实时事件订阅 WebSocket 雏形。

        这是当前项目第一次把实时事件控制协议挂到真正的 WebSocket 入口上。它仍然是最小实现：
        - 连接建立后等待客户端发送 JSON 控制消息；
        - 通过同一套控制/授权/状态机逻辑处理消息；
        - 将结果拆成一个或多个 JSON frame 发回客户端。

        这里不额外实现认证握手、心跳调度和后台推送队列，因为当前阶段的目标是先把协议与 handler
        连起来，而不是一次性把完整消息总线做完。
        """

        await websocket.accept()
        connection = RuntimeEventWebSocketConnectionAdapter(
            session_manager=session_manager,
            live_push_hub=live_push_hub,
        )
        outgoing_frames: asyncio.Queue[dict[str, Any]] = asyncio.Queue()

        async def sender_loop() -> None:
            """统一发送出口，避免多个协程同时直接写 websocket。"""

            while True:
                frame_payload = await outgoing_frames.get()
                await websocket.send_json(frame_payload)

        async def live_push_loop() -> None:
            """周期性把 live push hub 中积压的事件帧推送给当前连接。"""

            while True:
                await asyncio.sleep(0.2)
                for frame_payload in connection.drain_live_payloads():
                    await outgoing_frames.put(frame_payload)

        sender_task = asyncio.create_task(sender_loop())
        live_task = asyncio.create_task(live_push_loop())
        try:
            while True:
                payload = await websocket.receive_json()
                for frame_payload in connection.handle_message(payload):
                    await outgoing_frames.put(frame_payload)
        except Exception:
            # 这里故意保持宽容退出：真正生产环境会细分 WebSocketDisconnect、授权失败和序列化失败，
            # 当前阶段先让连接以可控方式结束，避免把协议雏形复杂化。
            await websocket.close()
        finally:
            connection.close(reason="websocket_handler_finished")
            sender_task.cancel()
            live_task.cancel()
            with suppress(Exception):
                await sender_task
            with suppress(Exception):
                await live_task

    register_memory_write_routes(app, memory_write_governance)

    return app
