"""Agent API 路由注册模块。

`api.py` 的职责应该停留在“创建 FastAPI 应用、装配运行时依赖、调用路由注册函数”。如果把每个
HTTP/WebSocket handler 都留在 bootstrap 文件里，后续继续增加内部签名校验、服务账号 Token、审计导出、
前端治理卡片等能力时，`api.py` 很快会膨胀成难以维护的巨型入口。

本模块只注册 Agent 规划与事件协议相关路由：
- `/agent/plans`：同步生成 AgentPlan，并在 API 边界重建可信控制面上下文；
- `/agent/events/replay`：HTTP snapshot/replay 协议；
- `/agent/events/control`：WebSocket 控制协议的 HTTP 版本；
- `/agent/events/ws`：实时事件 WebSocket 雏形。
"""

import asyncio
from contextlib import suppress
from typing import Any

from datasmart_ai_runtime.api_events import (
    build_event_control_response,
    build_event_replay_response,
    runtime_event_from_payload,
    subscription_request_from_payload,
)
from datasmart_ai_runtime.api_plan_response import build_plan_response
from datasmart_ai_runtime.api_trusted_context import enrich_agent_plan_payload_from_gateway_headers
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.runtime_events.runtime_event_websocket import RuntimeEventWebSocketConnectionAdapter


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

        request = AgentRequest(**enrich_agent_plan_payload_from_gateway_headers(payload, http_request.headers))
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
