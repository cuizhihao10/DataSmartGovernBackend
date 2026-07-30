"""Agent 规划请求的协作式取消控制。

浏览器中断 NDJSON 连接只能停止页面读取，不能自动停止 ``asyncio.to_thread`` 中正在进行的同步模型
HTTP 请求。该模块为一次 Agent 规划提供进程内取消令牌，并通过 ``ContextVar`` 把令牌安全传递到当前
工作线程中的模型查询引擎和 Provider。

取消语义刻意保持保守：
- 取消当前模型调用、后续推理和尚未提交的工具计划；
- 保留已经产生的低敏过程事件，便于用户理解停止前完成了什么；
- 不回滚已经提交给 Java 控制面的业务操作，业务任务仍应通过任务中心单独取消。
"""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from threading import Event, RLock
from time import monotonic
from typing import Callable, Iterator


class AgentPlanCancelled(RuntimeError):
    """当前 Agent 规划已被用户或连接生命周期取消。"""

    def __init__(self, reason: str = "USER_REQUESTED") -> None:
        self.reason = reason
        super().__init__("AGENT_PLAN_CANCELLED")


@dataclass(frozen=True)
class AgentPlanCancellationIdentity:
    """取消请求的安全隔离键。

    ``request_id`` 本身是不可预测 UUID，但仍不能把它当作授权凭据。租户、项目和操作者必须同时匹配，
    防止一个已登录用户取消另一个项目或另一个用户正在进行的模型请求。
    """

    tenant_id: str
    project_id: str
    actor_id: str
    request_id: str


class AgentPlanCancellationToken:
    """线程安全的单次规划取消令牌。"""

    def __init__(
        self,
        identity: AgentPlanCancellationIdentity,
        *,
        clock: Callable[[], float] = monotonic,
    ) -> None:
        self.identity = identity
        self._clock = clock
        self._cancelled = Event()
        self._lock = RLock()
        self._transport_closers: set[Callable[[], None]] = set()
        self._state = "ACTIVE"
        self._reason: str | None = None
        self._updated_at = clock()

    @property
    def cancelled(self) -> bool:
        return self._cancelled.is_set()

    @property
    def state(self) -> str:
        with self._lock:
            return self._state

    @property
    def reason(self) -> str | None:
        with self._lock:
            return self._reason

    @property
    def updated_at(self) -> float:
        with self._lock:
            return self._updated_at

    def cancel(self, reason: str = "USER_REQUESTED") -> bool:
        """标记取消并主动关闭当前 Provider HTTP 响应。"""

        with self._lock:
            if self._state == "COMPLETED":
                return False
            if self._cancelled.is_set():
                return True
            self._reason = str(reason or "USER_REQUESTED")[:80]
            self._state = "CANCELLED"
            self._updated_at = self._clock()
            self._cancelled.set()
            closers = tuple(self._transport_closers)
        for close in closers:
            try:
                close()
            except Exception:
                # response.close() 属于清理动作；取消状态不能因为传输层清理失败而丢失。
                pass
        return True

    def complete(self) -> None:
        """记录工作线程已退出；已取消状态保持为 CANCELLED 供短时诊断。"""

        with self._lock:
            if self._state == "ACTIVE":
                self._state = "COMPLETED"
            self._updated_at = self._clock()

    def raise_if_cancelled(self) -> None:
        """在模型、重试和副作用边界抛出稳定取消异常。"""

        if self._cancelled.is_set():
            raise AgentPlanCancelled(self.reason or "USER_REQUESTED")

    def register_transport_closer(self, close: Callable[[], None]) -> Callable[[], None]:
        """注册当前活动 HTTP response 的关闭函数并返回反注册函数。"""

        close_immediately = False
        with self._lock:
            if self._cancelled.is_set():
                close_immediately = True
            else:
                self._transport_closers.add(close)
        if close_immediately:
            try:
                close()
            finally:
                self.raise_if_cancelled()

        def unregister() -> None:
            with self._lock:
                self._transport_closers.discard(close)

        return unregister

    def to_summary(self) -> dict[str, object]:
        """返回取消 API 可展示的低敏状态，不包含 prompt、模型输出或工具参数。"""

        return {
            "requestId": self.identity.request_id,
            "state": self.state,
            "cancelled": self.cancelled,
            "reason": self.reason,
        }


class AgentPlanCancellationRegistry:
    """按租户、项目、操作者和 requestId 管理短生命周期取消令牌。"""

    def __init__(
        self,
        *,
        ttl_seconds: int = 300,
        clock: Callable[[], float] = monotonic,
    ) -> None:
        self._ttl_seconds = max(30, int(ttl_seconds))
        self._clock = clock
        self._lock = RLock()
        self._tokens: dict[AgentPlanCancellationIdentity, AgentPlanCancellationToken] = {}

    def register(self, identity: AgentPlanCancellationIdentity) -> AgentPlanCancellationToken:
        """注册新请求；活动 requestId 重复时拒绝覆盖，避免取消令牌串线。"""

        with self._lock:
            self._evict_expired_locked()
            existing = self._tokens.get(identity)
            if existing is not None and existing.state == "ACTIVE":
                raise ValueError("AGENT_PLAN_REQUEST_ALREADY_ACTIVE")
            token = AgentPlanCancellationToken(identity, clock=self._clock)
            self._tokens[identity] = token
            return token

    def cancel(
        self,
        identity: AgentPlanCancellationIdentity,
        *,
        reason: str = "USER_REQUESTED",
    ) -> dict[str, object]:
        """只取消完全匹配当前安全隔离键的请求，并保持幂等响应。"""

        with self._lock:
            self._evict_expired_locked()
            token = self._tokens.get(identity)
        if token is None:
            return {
                "requestId": identity.request_id,
                "state": "NOT_FOUND",
                "cancelled": False,
                "reason": None,
            }
        token.cancel(reason)
        return token.to_summary()

    def complete(self, identity: AgentPlanCancellationIdentity) -> None:
        """标记请求线程结束，终态记录保留到 TTL 以支持重复取消和短时诊断。"""

        with self._lock:
            token = self._tokens.get(identity)
        if token is not None:
            token.complete()

    def _evict_expired_locked(self) -> None:
        cutoff = self._clock() - self._ttl_seconds
        expired = [
            identity
            for identity, token in self._tokens.items()
            if token.state != "ACTIVE" and token.updated_at < cutoff
        ]
        for identity in expired:
            self._tokens.pop(identity, None)


_CURRENT_TOKEN: ContextVar[AgentPlanCancellationToken | None] = ContextVar(
    "datasmart_agent_plan_cancellation_token",
    default=None,
)


@contextmanager
def bind_agent_plan_cancellation(token: AgentPlanCancellationToken) -> Iterator[None]:
    """把取消令牌绑定到当前规划工作线程。"""

    context_token = _CURRENT_TOKEN.set(token)
    try:
        token.raise_if_cancelled()
        yield
    finally:
        _CURRENT_TOKEN.reset(context_token)


def raise_if_agent_plan_cancelled() -> None:
    """供模型查询和 Provider 在不改领域请求合同的前提下检查当前请求。"""

    token = _CURRENT_TOKEN.get()
    if token is not None:
        token.raise_if_cancelled()


@contextmanager
def bind_current_model_transport(close: Callable[[], None]) -> Iterator[None]:
    """把活动模型 HTTP response 绑定到当前取消令牌。"""

    token = _CURRENT_TOKEN.get()
    unregister = token.register_transport_closer(close) if token is not None else None
    try:
        raise_if_agent_plan_cancelled()
        yield
    finally:
        if unregister is not None:
            unregister()
