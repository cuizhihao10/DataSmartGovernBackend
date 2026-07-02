"""命令 Worker 租约与 fencing token 合同。

本模块补齐的是 command durable action 链路中非常关键、但经常被 demo 项目忽略的一环：同一条 command
只能被一个有效 worker 在一个租约窗口内处理。没有这层保护时，真实 shell/sandbox runner 一旦进入多实例部署，
就可能出现“两个 worker 同时执行同一条命令”“旧 worker 超时后又把过期结果写回”“重试任务覆盖新执行事实”等问题。

当前实现刻意使用进程内 in-memory store，原因是本阶段仍处在 Python Runtime 控制面收敛期：我们先固定 API 语义、
状态流转、低敏输出和测试，再把 `CommandWorkerLeaseStore` 替换为 Redis、MySQL 或 Java host fact store。这样后续
生产级持久化不会推翻业务合同。
"""

from __future__ import annotations

import hashlib
import re
import threading
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable, Protocol

from .command_worker_lease_presentation import message_for, recommended_actions_for


COMMAND_WORKER_LEASE_SCHEMA_VERSION = "datasmart.python-ai-runtime.command-worker-lease.v1"
COMMAND_WORKER_LEASE_PAYLOAD_POLICY = (
    "LOW_SENSITIVE_LEASE_METADATA_ONLY_NO_COMMAND_LINE_NO_STDIO_NO_PAYLOAD_NO_PROMPT_NO_SQL"
)
DEFAULT_COMMAND_WORKER_LEASE_TTL_SECONDS = 60
MAX_COMMAND_WORKER_LEASE_TTL_SECONDS = 600

SAFE_TEXT_PATTERN = re.compile(r"^[a-zA-Z0-9_.:@/+-]{1,260}$")
SAFE_RELEASE_REASON_PATTERN = re.compile(r"^[A-Z0-9_.:-]{1,120}$")
SENSITIVE_TEXT_MARKERS = (
    "select ",
    "insert ",
    "update ",
    "delete ",
    "authorization:",
    "bearer ",
    "password",
    "secret",
    "credential",
    "api_key",
    "apikey",
    "prompt:",
    "commandline",
    "command line",
    "stdout",
    "stderr",
    "workingdirectory",
    "workspaceroot",
    "payload",
    "http://",
    "https://",
    "jdbc:",
)


class CommandWorkerLeaseState(str, Enum):
    """命令租约的业务状态。

    这些状态不是简单的技术返回码，而是给后续 worker 调度、timeline、指标和补偿台共同使用的低敏事实：
    - `ACQUIRED`：当前 worker 首次拿到租约，可以继续进入安全复核或受控执行；
    - `ALREADY_HELD_BY_CALLER`：同一个 worker 重试领取，返回原 token，保障幂等；
    - `ALREADY_HELD_BY_OTHER`：租约仍被其他 worker 持有，当前 worker 必须停止；
    - `RENEWED`：持有者续租成功，适合长任务或队列拥塞时防止误抢占；
    - `RELEASED`：持有者主动释放，后续其他 worker 可以重新领取；
    - `REJECTED`：worker 身份或 fencing token 不匹配，禁止写回；
    - `EXPIRED`：租约已过期，调用方应重新领取而不是继续使用旧 token；
    - `NOT_FOUND`：没有可续租/释放的租约事实。
    """

    ACQUIRED = "ACQUIRED"
    ALREADY_HELD_BY_CALLER = "ALREADY_HELD_BY_CALLER"
    ALREADY_HELD_BY_OTHER = "ALREADY_HELD_BY_OTHER"
    RENEWED = "RENEWED"
    RELEASED = "RELEASED"
    REJECTED = "REJECTED"
    EXPIRED = "EXPIRED"
    NOT_FOUND = "NOT_FOUND"


@dataclass(frozen=True)
class CommandWorkerLeaseRequest:
    """领取、续租或释放 command worker 租约的低敏请求。

    字段说明：
    - `session_id/run_id/command_id` 定位 Agent 会话、单次运行和 command outbox 指令；
    - `executor_id` 是 worker 实例的稳定低敏身份，通常可由 worker pool、pod 名摘要或队列消费者 ID 生成；
    - `tenant_id/project_id/actor_id` 用于未来接入租户隔离、权限和审计标签；
    - `lease_ttl_seconds` 是短租约窗口，不代表命令最大执行时间；长任务必须显式续租；
    - 请求不允许携带 commandLine、stdout/stderr、payload、prompt、SQL、真实路径、凭据或内部 endpoint。
    """

    session_id: str
    run_id: str
    command_id: str
    executor_id: str
    tenant_id: int | None = None
    project_id: int | None = None
    actor_id: int | None = None
    lease_ttl_seconds: int = DEFAULT_COMMAND_WORKER_LEASE_TTL_SECONDS


@dataclass(frozen=True)
class CommandWorkerLeaseRecord:
    """存储层内部租约事实。

    `fencing_token` 是防旧写的关键：worker 后续续租、释放或写回 receipt 时必须带上它。只要租约过期后被新
    worker 重新领取，新 token 与 `lease_version` 都会变化，旧 worker 即使“醒过来”也会被拒绝。
    """

    session_id: str
    run_id: str
    command_id: str
    executor_id: str
    fencing_token: str
    lease_version: int
    tenant_id: int | None
    project_id: int | None
    actor_id: int | None
    acquired_at_ms: int
    expires_at_ms: int
    released_at_ms: int | None = None
    release_reason: str | None = None

    def is_active(self, now_ms: int) -> bool:
        """判断租约在指定时刻是否仍有效。"""

        return self.released_at_ms is None and now_ms < self.expires_at_ms

    def owned_by(self, executor_id: str) -> bool:
        """判断租约是否属于当前 worker。"""

        return self.executor_id == executor_id

    def matches_fence(self, executor_id: str, fencing_token: str) -> bool:
        """同时校验 worker 身份与 fencing token，避免旧 worker 或旁路调用伪造 receipt。"""

        return self.executor_id == executor_id and self.fencing_token == fencing_token


@dataclass(frozen=True)
class CommandWorkerLeaseResult:
    """租约操作的低敏返回。

    `fencing_token` 只会返回给真正持有租约的 caller；当租约被其他 worker 占用时，结果只返回状态、版本和
    retryAfterMs，不泄露对方 token。这样既能让调度器知道何时重试，又不会给错误 worker 写回 receipt 的凭证。
    """

    acquired: bool
    state: CommandWorkerLeaseState
    command_id: str
    run_id: str
    session_id: str
    executor_id: str
    fencing_token: str | None
    lease_version: int | None
    expires_at_ms: int | None
    retry_after_ms: int | None
    message: str
    recommended_actions: tuple[str, ...]
    payload_policy: str = COMMAND_WORKER_LEASE_PAYLOAD_POLICY

    def to_summary(self) -> dict[str, Any]:
        """输出 API/事件/测试可复用的低敏摘要。"""

        summary = {
            "schemaVersion": COMMAND_WORKER_LEASE_SCHEMA_VERSION,
            "payloadPolicy": self.payload_policy,
            "acquired": self.acquired,
            "state": self.state.value,
            "sessionId": self.session_id,
            "runId": self.run_id,
            "commandId": self.command_id,
            "executorId": self.executor_id,
            "leaseVersion": self.lease_version,
            "expiresAtMs": self.expires_at_ms,
            "retryAfterMs": self.retry_after_ms,
            "message": self.message,
            "recommendedActions": self.recommended_actions,
        }
        if self.fencing_token:
            summary["fencingToken"] = self.fencing_token
        return summary


class CommandWorkerLeaseStore(Protocol):
    """租约存储抽象。

    生产环境可以用 Redis `SET NX PX`、MySQL 行锁、Java agent-runtime durable fact 或队列系统的 visibility timeout
    实现该协议。Python 业务层只依赖这个协议，不直接绑定 in-memory 实现。
    """

    def acquire(self, request: CommandWorkerLeaseRequest) -> tuple[CommandWorkerLeaseState, CommandWorkerLeaseRecord | None, bool]:
        """尝试领取租约，并返回状态、当前记录以及 token 是否可见。"""

    def renew(
        self,
        request: CommandWorkerLeaseRequest,
        fencing_token: str,
    ) -> tuple[CommandWorkerLeaseState, CommandWorkerLeaseRecord | None, bool]:
        """续租已有租约。"""

    def release(
        self,
        request: CommandWorkerLeaseRequest,
        fencing_token: str,
        release_reason: str,
    ) -> tuple[CommandWorkerLeaseState, CommandWorkerLeaseRecord | None, bool]:
        """释放已有租约。"""


class InMemoryCommandWorkerLeaseStore:
    """进程内命令租约存储。

    这个实现只适合本地开发、单元测试和控制面合同验证；它不能跨进程、跨机器共享状态。我们仍然把并发访问保护、
    过期抢占、同 worker 幂等领取、fencing token 和版本号都做完整，是为了让未来 Redis/Java 实现有可对齐的行为。
    """

    def __init__(self, *, clock_ms: Callable[[], int] | None = None) -> None:
        self._clock_ms = clock_ms or _current_time_ms
        self._records: dict[str, CommandWorkerLeaseRecord] = {}
        self._lock = threading.RLock()

    def acquire(self, request: CommandWorkerLeaseRequest) -> tuple[CommandWorkerLeaseState, CommandWorkerLeaseRecord | None, bool]:
        """原子领取租约。

        返回第三个布尔值表示是否允许把 token 返回给调用者。被其他 worker 持有时必须为 `False`。
        """

        now_ms = self._clock_ms()
        key = _lease_key(request)
        with self._lock:
            existing = self._records.get(key)
            if existing and existing.is_active(now_ms):
                if existing.owned_by(request.executor_id):
                    return CommandWorkerLeaseState.ALREADY_HELD_BY_CALLER, existing, True
                return CommandWorkerLeaseState.ALREADY_HELD_BY_OTHER, existing, False

            next_version = (existing.lease_version + 1) if existing else 1
            record = self._new_record(request, now_ms, next_version)
            self._records[key] = record
            return CommandWorkerLeaseState.ACQUIRED, record, True

    def renew(
        self,
        request: CommandWorkerLeaseRequest,
        fencing_token: str,
    ) -> tuple[CommandWorkerLeaseState, CommandWorkerLeaseRecord | None, bool]:
        """只有当前持有者才能续租。"""

        now_ms = self._clock_ms()
        key = _lease_key(request)
        with self._lock:
            existing = self._records.get(key)
            if existing is None:
                return CommandWorkerLeaseState.NOT_FOUND, None, False
            if not existing.is_active(now_ms):
                return CommandWorkerLeaseState.EXPIRED, existing, False
            if not existing.matches_fence(request.executor_id, fencing_token):
                return CommandWorkerLeaseState.REJECTED, existing, False

            renewed = CommandWorkerLeaseRecord(
                session_id=existing.session_id,
                run_id=existing.run_id,
                command_id=existing.command_id,
                executor_id=existing.executor_id,
                fencing_token=existing.fencing_token,
                lease_version=existing.lease_version,
                tenant_id=existing.tenant_id,
                project_id=existing.project_id,
                actor_id=existing.actor_id,
                acquired_at_ms=existing.acquired_at_ms,
                expires_at_ms=now_ms + _ttl_ms(request.lease_ttl_seconds),
            )
            self._records[key] = renewed
            return CommandWorkerLeaseState.RENEWED, renewed, True

    def release(
        self,
        request: CommandWorkerLeaseRequest,
        fencing_token: str,
        release_reason: str,
    ) -> tuple[CommandWorkerLeaseState, CommandWorkerLeaseRecord | None, bool]:
        """只有当前持有者才能释放租约。"""

        now_ms = self._clock_ms()
        key = _lease_key(request)
        with self._lock:
            existing = self._records.get(key)
            if existing is None:
                return CommandWorkerLeaseState.NOT_FOUND, None, False
            if not existing.is_active(now_ms):
                return CommandWorkerLeaseState.EXPIRED, existing, False
            if not existing.matches_fence(request.executor_id, fencing_token):
                return CommandWorkerLeaseState.REJECTED, existing, False

            released = CommandWorkerLeaseRecord(
                session_id=existing.session_id,
                run_id=existing.run_id,
                command_id=existing.command_id,
                executor_id=existing.executor_id,
                fencing_token=existing.fencing_token,
                lease_version=existing.lease_version,
                tenant_id=existing.tenant_id,
                project_id=existing.project_id,
                actor_id=existing.actor_id,
                acquired_at_ms=existing.acquired_at_ms,
                expires_at_ms=existing.expires_at_ms,
                released_at_ms=now_ms,
                release_reason=release_reason,
            )
            self._records[key] = released
            return CommandWorkerLeaseState.RELEASED, released, True

    def _new_record(self, request: CommandWorkerLeaseRequest, now_ms: int, lease_version: int) -> CommandWorkerLeaseRecord:
        """创建新的租约记录，并生成不暴露真实命令内容的 fencing token。"""

        return CommandWorkerLeaseRecord(
            session_id=request.session_id,
            run_id=request.run_id,
            command_id=request.command_id,
            executor_id=request.executor_id,
            fencing_token=_fencing_token(request, lease_version, now_ms),
            lease_version=lease_version,
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            acquired_at_ms=now_ms,
            expires_at_ms=now_ms + _ttl_ms(request.lease_ttl_seconds),
        )


class CommandWorkerLeaseManager:
    """命令 worker 租约门面。

    service/runner 层不直接操作 store，是为了集中处理：
    - 低敏字段校验；
    - 状态到用户/运维可读 message 的转换；
    - token 可见性控制；
    - 未来从 in-memory store 切换到 Redis/Java durable lease 时保持调用方代码稳定。
    """

    def __init__(self, store: CommandWorkerLeaseStore | None = None) -> None:
        self._store = store or InMemoryCommandWorkerLeaseStore()

    def acquire(self, request: CommandWorkerLeaseRequest) -> CommandWorkerLeaseResult:
        """领取 command 租约。

        只有 `ACQUIRED` 或 `ALREADY_HELD_BY_CALLER` 才允许后续进入 worker precheck/runner；被其他 worker 持有时，
        调用方应停止当前处理并等待队列重试。
        """

        safe_request = _validate_request(request)
        state, record, expose_token = self._store.acquire(safe_request)
        return self._result_for(safe_request, state, record, expose_token)

    def renew(self, request: CommandWorkerLeaseRequest, fencing_token: str) -> CommandWorkerLeaseResult:
        """续租 command 租约。

        长任务或 sandbox 执行前后都可以调用续租。续租失败时，worker 必须停止写回 receipt，因为这说明当前 token
        已经失效、过期或不属于该 worker。
        """

        safe_request = _validate_request(request)
        safe_token = _required_fencing_token(fencing_token)
        state, record, expose_token = self._store.renew(safe_request, safe_token)
        return self._result_for(safe_request, state, record, expose_token)

    def release(
        self,
        request: CommandWorkerLeaseRequest,
        fencing_token: str,
        *,
        release_reason: str = "WORKER_FINISHED",
    ) -> CommandWorkerLeaseResult:
        """释放 command 租约。

        releaseReason 只允许机器码，例如 `WORKER_FINISHED`、`WORKER_ABORTED`、`PRECHECK_BLOCKED`。不要把异常堆栈、
        stdout/stderr、命令参数或模型输出写入 releaseReason。
        """

        safe_request = _validate_request(request)
        safe_token = _required_fencing_token(fencing_token)
        safe_reason = _safe_release_reason(release_reason)
        state, record, expose_token = self._store.release(safe_request, safe_token, safe_reason)
        return self._result_for(safe_request, state, record, expose_token)

    def _result_for(
        self,
        request: CommandWorkerLeaseRequest,
        state: CommandWorkerLeaseState,
        record: CommandWorkerLeaseRecord | None,
        expose_token: bool,
    ) -> CommandWorkerLeaseResult:
        """把存储层状态转换为低敏外部结果。"""

        now_ms = _current_time_ms()
        retry_after_ms = None
        if record and state == CommandWorkerLeaseState.ALREADY_HELD_BY_OTHER:
            retry_after_ms = max(record.expires_at_ms - now_ms, 0)
        return CommandWorkerLeaseResult(
            acquired=state in {CommandWorkerLeaseState.ACQUIRED, CommandWorkerLeaseState.ALREADY_HELD_BY_CALLER, CommandWorkerLeaseState.RENEWED},
            state=state,
            command_id=request.command_id,
            run_id=request.run_id,
            session_id=request.session_id,
            executor_id=request.executor_id,
            fencing_token=record.fencing_token if record and expose_token else None,
            lease_version=record.lease_version if record else None,
            expires_at_ms=record.expires_at_ms if record else None,
            retry_after_ms=retry_after_ms,
            message=message_for(state),
            recommended_actions=recommended_actions_for(state),
        )


def _validate_request(request: CommandWorkerLeaseRequest) -> CommandWorkerLeaseRequest:
    """校验请求只包含低敏定位信息。"""

    return CommandWorkerLeaseRequest(
        session_id=_required_safe_text(request.session_id, "session_id"),
        run_id=_required_safe_text(request.run_id, "run_id"),
        command_id=_required_safe_text(request.command_id, "command_id"),
        executor_id=_required_safe_text(request.executor_id, "executor_id"),
        tenant_id=request.tenant_id,
        project_id=request.project_id,
        actor_id=request.actor_id,
        lease_ttl_seconds=_ttl_seconds(request.lease_ttl_seconds),
    )


def _required_safe_text(value: Any, field_name: str) -> str:
    text = str(value or "").strip()
    if not text:
        raise ValueError(f"{field_name} 不能为空")
    if _looks_sensitive(text) or not SAFE_TEXT_PATTERN.fullmatch(text):
        raise ValueError(f"{field_name} 只能使用低敏标识符，不能携带命令、路径、URL、输出、SQL、prompt 或凭据")
    return text


def _required_fencing_token(value: str) -> str:
    token = str(value or "").strip()
    if not token.startswith("cmd-lease:") or _looks_sensitive(token):
        raise ValueError("fencing_token 必须来自 CommandWorkerLeaseManager，不能使用外部 URL、命令输出或凭据片段")
    return token


def _safe_release_reason(value: str) -> str:
    reason = str(value or "").strip().upper()
    if not reason or _looks_sensitive(reason) or not SAFE_RELEASE_REASON_PATTERN.fullmatch(reason):
        raise ValueError("release_reason 只能是低敏机器码")
    return reason


def _lease_key(request: CommandWorkerLeaseRequest) -> str:
    return f"{request.session_id}:{request.run_id}:{request.command_id}"


def _ttl_seconds(value: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = DEFAULT_COMMAND_WORKER_LEASE_TTL_SECONDS
    return max(1, min(parsed, MAX_COMMAND_WORKER_LEASE_TTL_SECONDS))


def _ttl_ms(value: int) -> int:
    return _ttl_seconds(value) * 1000


def _fencing_token(request: CommandWorkerLeaseRequest, lease_version: int, now_ms: int) -> str:
    raw = f"{request.session_id}:{request.run_id}:{request.command_id}:{request.executor_id}:{lease_version}:{now_ms}"
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:20]
    return f"cmd-lease:{lease_version}:{digest}"


def _current_time_ms() -> int:
    return int(time.time() * 1000)


def _looks_sensitive(value: Any) -> bool:
    lowered = str(value or "").lower()
    return any(marker in lowered for marker in SENSITIVE_TEXT_MARKERS)
