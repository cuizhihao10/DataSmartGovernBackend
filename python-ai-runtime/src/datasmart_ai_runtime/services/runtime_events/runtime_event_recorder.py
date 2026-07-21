"""请求级 Agent Runtime 事件收集器。

3.35 阶段已经定义了 `AgentRuntimeEvent`，但上下文选择事件仍由 `HybridContextBuilder.last_events()`
暂存，这在本地测试中可用，在生产环境中却不够安全：如果 builder 被复用为单例，多个并发请求可能
覆盖彼此的“最近一次事件”。本文件引入请求级 recorder，让一次 Agent 请求内的所有节点都向同一个
收集器写事件。

后续智能网关落地时，这个 recorder 可以继续演进为事件总线适配层：同步 HTTP 入口可以在请求结束
时一次性返回 events，WebSocket 入口可以边 record 边推送，Kafka 入口可以把事件写入审计 topic。
"""

from __future__ import annotations

from collections.abc import Callable

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


class RuntimeEventRecorder:
    """一次 Agent 请求内的结构化事件收集器。

    该类的核心职责很克制：只负责给事件补齐租户、项目、操作者、请求 ID、运行 ID、会话 ID 和递增
    sequence，然后把事件保存在内存列表里。它不直接依赖 WebSocket、Kafka、数据库或日志框架，
    是为了避免智能网关传输方式尚未定型时把领域契约绑死。
    """

    def __init__(
        self,
        request: AgentRequest,
        request_id: str,
        run_id: str | None = None,
        session_id: str | None = None,
        event_sink: Callable[[AgentRuntimeEvent], None] | None = None,
    ) -> None:
        """初始化请求级事件收集器。

        参数说明：
        - `request`：提供 tenantId/projectId/actorId，这些字段对审计和多租户隔离非常重要；
        - `request_id`：一次 API/规划请求的唯一 ID；
        - `run_id`：一次 Agent 运行实例 ID，未来长任务、重试和恢复执行会依赖它；
        - `session_id`：多轮会话 ID，用于把多次请求串成用户可理解的对话上下文。
        """

        self._request = request
        self._request_id = request_id
        self._run_id = run_id
        self._session_id = session_id
        self._event_sink = event_sink
        self._events: list[AgentRuntimeEvent] = []
        self._next_sequence = 1

    def record(
        self,
        event_type: AgentRuntimeEventType,
        stage: str,
        message: str,
        severity: AgentRuntimeEventSeverity = AgentRuntimeEventSeverity.INFO,
        attributes: dict[str, object] | None = None,
    ) -> AgentRuntimeEvent:
        """记录一条运行时事件并返回该事件。

        `sequence` 在这里递增生成，而不是由各个节点自己填写，是为了保证同一请求内事件顺序一致。
        前端后续做断线续传时，可以请求“从 sequence=N 之后的事件继续发送”。
        """

        event = AgentRuntimeEvent(
            event_type=event_type,
            stage=stage,
            message=message,
            severity=severity,
            tenant_id=self._request.tenant_id,
            project_id=self._request.project_id,
            actor_id=self._request.actor_id,
            request_id=self._request_id,
            run_id=self._run_id,
            session_id=self._session_id,
            sequence=self._next_sequence,
            attributes=dict(attributes or {}),
        )
        self._events.append(event)
        self._next_sequence += 1
        if self._event_sink is not None:
            try:
                # 实时展示是观测旁路，前端断开或传输异常不能中断模型规划与业务控制面主链路。
                self._event_sink(event)
            except Exception:
                pass
        return event

    def events(self) -> tuple[AgentRuntimeEvent, ...]:
        """返回当前已记录事件快照。

        返回 tuple 而不是内部 list，是为了避免调用方意外修改 recorder 的内部状态。
        """

        return tuple(self._events)
