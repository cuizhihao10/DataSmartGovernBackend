"""Agent API 路由注册模块。

`api.py` 的职责应该停留在“创建 FastAPI 应用、装配运行时依赖、调用路由注册函数”。如果把每个
HTTP/WebSocket handler 都留在 bootstrap 文件里，后续继续增加内部签名校验、服务账号 Token、审计导出、
前端治理卡片等能力时，`api.py` 很快会膨胀成难以维护的巨型入口。

本模块只注册 Agent 规划与事件协议相关路由：
- `/agent/plans`：同步生成 AgentPlan，并在 API 边界重建可信控制面上下文；
- `/agent/events/replay`：HTTP snapshot/replay 协议；
- `/agent/events/control`：WebSocket 控制协议的 HTTP 版本；
- `/agent/events/ws`：实时事件 WebSocket 雏形。
- `/agent/protocol-adapters/a2a/task-planning-preview`：A2A task 合同到 Python 规划决策的只读预览。
- `/agent/protocol-adapters/mcp/tools-call-intake-preview`：MCP tools/call 到 ToolPlan/readiness 的只读预检。
- `/agent/tool-actions/control-flow-preview`：模型 tool_call、MCP tools/call、A2A task/action 的统一控制流预览。
- `/agent/tool-actions/checkpoints/*`：工具动作执行前图 checkpoint 查询与恢复预检。
"""

import asyncio
import logging
from contextlib import suppress
from typing import Any, Callable

from datasmart_ai_runtime.api.agent.a2a_task_planning import build_a2a_task_planning_preview_response
from datasmart_ai_runtime.api.agent.mcp_tool_call_intake import build_mcp_tool_call_intake_preview_response
from datasmart_ai_runtime.api.agent.runtime_event_delivery import (
    publish_single_runtime_event,
    runtime_event_summary,
)
from datasmart_ai_runtime.api.agent.tool_action_checkpoint_routes import register_tool_action_checkpoint_routes
from datasmart_ai_runtime.api.agent.tool_action_control_flow import build_tool_action_control_flow_preview_response
from datasmart_ai_runtime.api.events import (
    build_event_control_response,
    build_event_replay_response,
    runtime_event_from_payload,
    subscription_request_from_payload,
)
from datasmart_ai_runtime.api.gateway.signature import GatewaySignatureVerificationError
from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.api.gateway.trusted_context import enrich_agent_plan_payload_from_gateway_headers
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.tools import build_tool_action_intake_runtime_event
from datasmart_ai_runtime.services.runtime_events.runtime_event_websocket import RuntimeEventWebSocketConnectionAdapter


LOGGER = logging.getLogger(__name__)
GATEWAY_SIGNATURE_ERROR_CODE = "GATEWAY_SIGNATURE_INVALID"


def register_agent_runtime_routes(
    app: Any,
    *,
    request_type: type[Any],
    orchestrator: Any,
    event_store: Any,
    session_manager: Any,
    live_push_hub: Any,
    event_publisher: Any,
    runtime_event_replay_sources: tuple[Any, ...],
    plan_ingestion_client: Any | None,
    control_plane_feedback_collector: Any | None,
    runtime_event_feedback_bridge: Any | None,
    loop_control_evaluator: Any | None,
    second_turn_orchestrator: Any | None,
    memory_write_governance: Any | None,
    skill_publication_diagnostics_service: Any | None = None,
    tool_execution_readiness_policy_provider: Any | None = None,
    tool_action_resume_fact_provider: Any | None = None,
    tool_action_checkpoint_store: Any | None = None,
    tool_action_checkpoint_metrics: Any | None = None,
    tool_registry: tuple[Any, ...] | None = None,
    gateway_signature_error_factory: Callable[[dict[str, Any]], Exception] | None = None,
    gateway_signature_nonce_store: Any | None = None,
    gateway_signature_security_stats: Any | None = None,
) -> None:
    """注册 Agent 规划、事件回放和 WebSocket 路由。

    `request_type` 由 `api.py` 延迟导入 FastAPI 后传入，避免核心测试强依赖可选 API 包。其他参数都是
    bootstrap 阶段已经装配好的运行时组件；本函数只负责声明路由，不创建全局状态。

    这里采用“注册函数”而不是“直接在模块顶层创建 router”的原因有三点：
    1. 当前运行时的核心测试不安装 FastAPI，可选 API 依赖必须延迟到 `create_app()` 才触发；
    2. Agent 编排依赖、事件组件、Java 控制面客户端都由启动阶段按环境变量装配，注册函数可以显式接收这些
       组件，避免在模块导入时读取环境或创建隐式单例；
    3. 后续如果接入智能网关认证、内部签名、服务账号权限、审计埋点，本函数可以继续拆分为更细的
       plan/event/websocket router，而不影响 `api.py` 的 bootstrap 边界。

    `gateway_signature_error_factory` 的设计目的：
    - 本模块不能在顶层直接依赖 FastAPI，否则核心单元测试必须安装 API 可选依赖；
    - 真实 HTTP 服务需要把验签失败映射为 401/403，而不是让异常冒泡成 500；
    - 因此由 `api.py#create_app()` 注入 `HTTPException` 工厂，离线测试或未来其他运行载体可以注入自己的错误类型。

    `gateway_signature_nonce_store` 与 `gateway_signature_security_stats` 分别承载防重放和安全统计：
    - nonce store 只在 HMAC 校验通过后登记 nonce，避免无效签名污染去重存储；
    - security stats 记录失败 reason 分布，后续可升级为 Prometheus 指标或统一审计事件。

    `skill_publication_diagnostics_service` 用来把 Java agent-runtime 发布的 Skill Manifest 指纹带入
    `/agent/plans` 响应和 runtime event。它不参与本轮 Skill 准入决策，只提供“当前能力目录版本证据”：
    如果生产环境后续发现某个 Skill 在某一版 Manifest 后异常隐藏/暴露，运维可以按指纹回放和定位。

    `tool_execution_readiness_policy_provider` 是执行准备度策略来源：
    - 未注入时，响应组装层使用本地 provider，可读取 gateway 放入 `trustedControlPlane` 的快照；
    - 注入远程 provider 时，Python 会优先向 permission-admin 读取标准 `toolExecutionReadinessPolicy`；
    - 不论来源如何，策略只能影响 readiness 预算、审批/阻断开关和低敏解释码，不能携带工具参数值或 prompt。

    `tool_registry` 是协议适配预览入口使用的工具目录快照。它与 orchestrator 内部工具规划器使用同一份
    启动期目录时，MCP/A2A/模型主链路看到的工具能力才不会漂移；如果测试或本地脚本没有传入，则 MCP
    preview helper 会回退到 Python 默认工具目录，保证核心单元测试不依赖 Java agent-runtime。

    `tool_action_resume_fact_provider` 是 checkpoint 恢复预检的服务端事实源入口。默认不注入时，
    resume-preview 只使用调用方请求内事实；后续接入 permission-admin 审批事实、澄清事实库、Java outbox
    confirmation 或 worker receipt projection 时，只需要注入 provider，而不需要把外部查询逻辑塞进路由文件。

    `tool_action_checkpoint_store` 是工具动作执行前图 checkpoint 的共享存储入口：
    - control-flow-preview 会把低敏执行图 checkpoint 保存到这里；
    - checkpoint query 与 resume-preview 会从同一个 store 读取；
    - 生产环境可注入 Redis 实现，避免多实例或重启后丢失等待审批/澄清的短期状态；
    - 未注入时 helper 仍回退到本地默认 in-memory store，保证离线测试和学习环境不需要 Redis。

    `tool_action_checkpoint_metrics` 是 checkpoint query/resume-preview 的低基数指标记录器。它只按
    operation/result/severity/fact_state 等固定枚举聚合，不把 checkpointId、threadId、tenantId、runId
    或任何 payload locator 放进 Prometheus label。
    """

    @app.post("/agent/plans")
    def create_agent_plan(payload: dict[str, Any], http_request: request_type) -> dict[str, Any]:
        """生成 Agent 工具计划，并在 API 边界重建可信控制面上下文。

        路由职责：
        - 接收来自 Java gateway、前端或测试客户端的规划请求 payload；
        - 从 HTTP Header 中读取 gateway 已经校验过的租户、项目、用户、workspace、权限摘要等可信上下文；
        - 把可信上下文合并回领域层 `AgentRequest`，再交给 `build_plan_response(...)` 进行模型规划、工具筛选、
          事件记录、Java plan ingestion 和长期记忆写入候选治理。

        设计意图：
        不能让客户端直接在 JSON body 中伪造 `tenantId/projectId/workspaceKey` 等控制面信息。真实商业化部署中，
        这些字段应该由 gateway/permission-admin 验证后通过内部 Header 注入；Python Runtime 只信任这条
        受控链路，并把重建后的上下文继续传递给工具预算、workspace 隔离和长期记忆候选逻辑。
        """

        try:
            request = AgentRequest(
                **enrich_agent_plan_payload_from_gateway_headers(
                    payload,
                    http_request.headers,
                    nonce_store=gateway_signature_nonce_store,
                )
            )
        except GatewaySignatureVerificationError as exc:
            # 这是一条安全审计日志，而不是普通业务异常日志。
            #
            # 为什么不把完整 Header 或签名原文写进日志：
            # 1. Header 中可能包含租户、操作者、workspace、数据范围等控制面事实；
            # 2. 签名原文和签名值虽然不是明文密钥，但长期保留在日志里会扩大重放和排障泄漏面；
            # 3. 商业化产品的安全日志应记录“谁、从哪里、访问哪个入口、失败原因是什么”，而不是记录可被复制的认证材料。
            error_detail = _gateway_signature_error_detail(http_request, exc)
            LOGGER.warning(
                "Gateway 内部签名校验失败，code=%s, reason=%s, traceId=%s, sourceService=%s, path=%s",
                error_detail["code"],
                error_detail["reason"],
                error_detail["traceId"],
                error_detail["sourceService"],
                error_detail["path"],
            )
            if gateway_signature_security_stats is not None:
                gateway_signature_security_stats.record_failure(error_detail)
            if gateway_signature_error_factory is not None:
                raise gateway_signature_error_factory(error_detail) from exc
            raise
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
            skill_publication_diagnostics_service=skill_publication_diagnostics_service,
            tool_execution_readiness_policy_provider=tool_execution_readiness_policy_provider,
        )

    @app.post("/agent/protocol-adapters/a2a/task-planning-preview")
    def preview_a2a_task_planning(payload: dict[str, Any]) -> dict[str, Any]:
        """预览 A2A task 控制面合同在 Python Runtime 中的规划决策。

        路由职责：
        - 接收 Java Agent Runtime 5.30 task 查询预览或未来真实 `tasks/get` 的低敏合同；
        - 调用 `A2aTaskPlanningAdapter` 将 task 状态映射为 Master Agent 下一步规划模式；
        - 返回 planning decision、生产化缺口和推荐动作，供网关、控制面或 LangGraph 节点联调。

        安全边界：
        - 该路由不创建、取消或执行 A2A task；
        - 不写 outbox、不发布 Kafka、不读取 artifact 正文；
        - 不回显原始输入 payload，避免 prompt、工具参数、SQL、样本数据、模型输出或凭证被二次扩散。
        """

        return build_a2a_task_planning_preview_response(payload)

    @app.post("/agent/protocol-adapters/mcp/tools-call-intake-preview")
    def preview_mcp_tools_call_intake(payload: dict[str, Any]) -> dict[str, Any]:
        """预览 MCP `tools/call` 在 DataSmart 工具治理链路中的处理结果。

        路由职责：
        - 接收标准 JSON-RPC `method=tools/call`、`params={name, arguments}` 或本地兼容包装；
        - 调用统一 `ToolActionIntakeService` 将 MCP 调用归一为 ToolPlan 候选；
        - 返回 intake 摘要、readiness 摘要和 readiness graph，供智能网关、MCP Host、Java 控制面或
          LangGraph/OpenClaw 风格节点判断下一跳。

        安全边界：
        - 该路由不是 MCP 执行器，也不是生产 MCP Server；
        - 不执行工具、不写 outbox、不创建审批、不调用 worker；
        - 不回显原始 `arguments` 值、prompt、SQL、样本数据、模型输出、凭证或内部 endpoint。
        """

        response = build_mcp_tool_call_intake_preview_response(payload, registered_tools=tool_registry)
        # MCP preview 本身仍然是只读接口，但“外部工具动作意图曾进入过平台治理”是一条值得回放的事实。
        # 这里把低敏响应压缩成 runtime event，并写入 replay/live/publisher 三条旁路：
        # - replay store 让刷新、断线和审计查询能恢复该事实；
        # - live hub 让订阅者可以即时看到外部 Agent 工具意图被接受/拒绝；
        # - publisher 让 Java 控制面和后续 observability 可以异步消费。
        # 事件写入采用 fail-open：旁路失败不应把 preview 请求打成 500，但会在响应里返回低敏 delivery 错误。
        intake_event = build_tool_action_intake_runtime_event(response, request_payload=payload)
        response["runtimeEvent"] = runtime_event_summary(intake_event)
        response["runtimeEventDelivery"] = publish_single_runtime_event(
            intake_event,
            event_store=event_store,
            live_push_hub=live_push_hub,
            event_publisher=event_publisher,
        )
        return response

    @app.post("/agent/tool-actions/control-flow-preview")
    def preview_tool_action_control_flow(payload: dict[str, Any]) -> dict[str, Any]:
        """统一预览工具动作在 Agent runtime 中的执行前控制流。

        路由职责：
        - 接收模型 tool_call、MCP tools/call 或 A2A task/action 的低敏包装；
        - 自动识别来源并进入统一 `ToolActionControlFlowService`；
        - 返回 intake、readiness、readiness graph、生产化缺口和下一步建议。

        与 MCP 专用路由的区别：
        - MCP 专用路由更适合外部 MCP Host 做协议联调，并会写入 `TOOL_ACTION_INTAKE_RECORDED` 事件；
        - 本路由是智能网关/编排器侧的统一预览入口，当前不额外发布事件，避免一次诊断同时生成多条
          语义相近的事实。后续如果网关需要统一事件，可再补 `TOOL_ACTION_CONTROL_FLOW_RECORDED`。

        安全边界：
        - 不执行工具、不写 outbox、不创建审批、不调用 worker；
        - 不回显原始参数值、prompt、SQL、样本数据、模型输出、凭证或内部 endpoint。
        """

        return build_tool_action_control_flow_preview_response(
            payload,
            registered_tools=tool_registry,
            checkpoint_store=tool_action_checkpoint_store,
        )

    register_tool_action_checkpoint_routes(
        app,
        checkpoint_store=tool_action_checkpoint_store,
        resume_fact_provider=tool_action_resume_fact_provider,
        event_store=event_store,
        live_push_hub=live_push_hub,
        event_publisher=event_publisher,
        metrics_recorder=tool_action_checkpoint_metrics,
    )

    @app.post("/agent/events/replay")
    def replay_agent_events(payload: dict[str, Any]) -> dict[str, Any]:
        """按订阅请求回放 Agent 事件。

        路由职责：
        - 根据 subscription 中的 clientId/sessionId/runId/requestId/afterSequence/eventTypes 构造回放条件；
        - 如果调用方显式传入 events，则作为本地协议测试数据进行筛选；
        - 如果没有传入 events，则优先从当前 Python event store 或外部 Java replay source 拉取历史事件。

        设计意图：
        Agent 产品要做到接近 Codex/Claude Code 的可观察执行体验，必须让前端或智能网关能够在断线、刷新、
        多端切换后恢复事件流。这个 HTTP replay 入口是 WebSocket 长连接之外的“补偿读取通道”，后续可以扩展
        为分页回放、审计导出、运行回放和故障诊断接口。
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

        支持的控制动作包括 subscribe、ack、heartbeat、reconnect 和 unsubscribe。HTTP 入口与 WebSocket
        入口复用同一套控制函数，是为了避免出现“两套订阅状态机”：否则 ack 序号、断线恢复、心跳更新时间等
        逻辑很容易在不同协议入口之间漂移，导致生产排障困难。
        """

        return build_event_control_response(payload, session_manager)

    @app.websocket("/agent/events/ws")
    async def websocket_agent_event_subscription(websocket: Any) -> None:
        """实时事件订阅 WebSocket 雏形。

        当前 handler 负责四件事：
        - 接受连接并创建 `RuntimeEventWebSocketConnectionAdapter`，由 adapter 维护当前连接的订阅上下文；
        - 接收客户端 JSON 控制消息，并交给统一控制器处理 subscribe/ack/reconnect 等动作；
        - 通过单独 sender loop 串行写出 frame，避免多个协程同时调用 websocket send 导致帧顺序不可控；
        - 通过 live push loop 周期性消费 live hub 中的实时事件，形成“控制消息 + 事件推送”的最小闭环。

        生产化边界：
        当前不在这里塞入认证、限流、跨实例连接迁移、Redis pub/sub、异常分类和心跳超时策略。那些能力属于
        智能网关/事件基础设施的下一层演进；本模块先保持协议入口清晰，让后续能力有稳定挂点。
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
            # 当前阶段宽容退出即可。后续生产实现应区分 WebSocketDisconnect、认证失败、协议错误和序列化失败。
            await websocket.close()
        finally:
            connection.close(reason="websocket_handler_finished")
            sender_task.cancel()
            live_task.cancel()
            # 任务取消是 WebSocket 正常收尾动作，不应被记录成业务异常。这里同时兼容不同 Python 版本中
            # `asyncio.CancelledError` 继承层级的差异，避免正常断连在日志中表现为“未处理异常”。
            with suppress(asyncio.CancelledError, Exception):
                await sender_task
            with suppress(asyncio.CancelledError, Exception):
                await live_task


def _gateway_signature_error_detail(http_request: Any, exc: GatewaySignatureVerificationError) -> dict[str, Any]:
    """构造可返回给 HTTP 调用方、也可写入安全审计日志的错误详情。

    字段解释：
    - `code`：稳定错误码，便于 gateway、前端 SDK、运维告警和自动化测试识别；
    - `message`：面向调用方的安全提示，不暴露签名原文或密钥；
    - `reason`：机器可读失败原因，来自验签模块；
    - `traceId`：链路排障字段；
    - `sourceService`：调用方自称来源，用于识别“伪造 gateway 来源但无有效签名”的风险；
    - `path`：触发失败的 API 路径，便于后续按路由统计攻击或误配置。
    """

    headers = getattr(http_request, "headers", {})
    return {
        "code": GATEWAY_SIGNATURE_ERROR_CODE,
        "message": "Gateway 内部签名校验失败，请确认请求必须通过统一网关并配置一致的服务间密钥。",
        "reason": exc.reason,
        "traceId": _header(headers, "X-DataSmart-Trace-Id"),
        "sourceService": _header(headers, "X-DataSmart-Source-Service"),
        "path": _request_path(http_request, headers),
    }


def _request_path(http_request: Any, headers: Any) -> str | None:
    """尽可能从 FastAPI Request 或 gateway Header 中提取请求路径。"""

    url = getattr(http_request, "url", None)
    path = getattr(url, "path", None)
    if path:
        return str(path)
    return _header(headers, "X-Gateway-Original-Path")


def _header(headers: Any, name: str) -> str | None:
    """大小写不敏感读取 Header，兼容 Starlette Headers 与测试 dict。"""

    try:
        value = headers.get(name) or headers.get(name.lower())
    except AttributeError:
        return None
    if value is None and hasattr(headers, "items"):
        lowered_name = name.lower()
        value = next((item for key, item in headers.items() if str(key).lower() == lowered_name), None)
    text = str(value).strip() if value is not None else ""
    return text or None
