"""长期记忆落成 worker 的租约协议与内存实现。

长期记忆链路现在存在四类互相独立的事实：
- candidate：治理审批事实，回答“是否允许写入”；
- formal memory：可召回经验事实，回答“模型可以读取什么”；
- receipt：执行证据，回答“某次落成尝试成功还是失败”；
- lease：并发控制事实，回答“当前哪个 worker 暂时拥有处理权”。

为什么不能把 lease 字段直接塞进 candidate 或 receipt：
- candidate 应保持纯粹的审批状态机，否则审批台会混入 worker 执行态；
- receipt 应记录每次执行结果，但不适合承担短时抢占锁，否则审计事实与并发协调强耦合；
- lease 生命周期短、更新频繁，未来可能切换到 MySQL、Redis 或专用队列协调器。

本文件先固定可替换协议并提供线程安全内存实现。生产环境应使用 SQL lease store；未来如果任务规模继续扩大，
也可以增加 Redis、Kafka consumer group 或 Java task-management 适配器，而不用推翻 Runner。
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from enum import Enum
from threading import RLock
from typing import Protocol
from uuid import NAMESPACE_URL, uuid4, uuid5


class AgentMemoryMaterializationLeaseStatus(str, Enum):
    """长期记忆落成租约状态。

    - `LEASED`：某个 worker 已在有限时间窗口内取得处理权；
    - `SUCCEEDED`：候选已经成功落成，后续扫描应跳过；
    - `FAILED`：最近一次尝试失败，但尚未达到最大尝试次数，需要等 `next_retry_at` 后再重试；
    - `DEAD_LETTER`：连续失败达到上限，候选进入 DLQ，需要管理员补偿或人工重放。

    `FAILED` 与 `DEAD_LETTER` 的区分很重要：前者是系统自动恢复的一部分，后者是需要运营或管理员介入的异常池。
    这能避免毒性候选在 worker 中无限热循环，也能给后续补偿台、告警和审计导出留下稳定状态语义。
    """

    LEASED = "leased"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    DEAD_LETTER = "dead_letter"


@dataclass(frozen=True)
class AgentMemoryMaterializationRetryDecision:
    """一次失败后的重试/DLQ 判定结果。

    字段说明：
    - `status`：失败后应写回 lease 的状态，可能是 `FAILED` 或 `DEAD_LETTER`；
    - `next_retry_at`：下一次允许领取的时间。进入 DLQ 时为空，表示不能自动重试；
    - `retry_delay_seconds`：本次计算出来的退避秒数，便于 Runner report 和后续指标展示；
    - `max_attempts`：当前策略允许的最大领取尝试次数；
    - `dead_lettered`：是否已经进入 DLQ。

    这里单独建模，而不是把公式散落在内存 store、SQL store 和 Runner 里，是为了保证不同存储实现对失败语义一致。
    """

    status: AgentMemoryMaterializationLeaseStatus
    next_retry_at: datetime | None
    retry_delay_seconds: int
    max_attempts: int
    dead_lettered: bool


@dataclass(frozen=True)
class AgentMemoryMaterializationLease:
    """一条候选的 worker 租约快照。

    字段说明：
    - `lease_id`：稳定业务 ID，便于补偿台、审计和 SQL 唯一约束引用；
    - `candidate_id`：被领取的长期记忆候选 ID；
    - `tenant_id/project_id/workspace_key/memory_namespace`：低敏治理范围，用于排障与隔离；
    - `status`：短时租约或终态执行结果；
    - `attempt_count`：领取次数，用于发现 worker 抖动和毒性候选；
    - `worker_id`：当前或最近处理者；
    - `lease_token`：每次领取生成的新 fencing token，只能在内部传递，不能输出到诊断接口；
    - `leased_until`：租约过期时间。worker 崩溃后，其他实例可以在该时间之后重新领取；
    - `next_retry_at`：失败后的下一次自动领取时间。未到时间时 Runner 应跳过，避免坏候选热循环；
    - `memory_id/outcome/message/error_message`：最近一次低敏结果，不保存正文、SQL、样本或完整异常堆栈。
    """

    lease_id: str
    candidate_id: str
    tenant_id: str
    project_id: str
    workspace_key: str
    memory_namespace: str
    status: AgentMemoryMaterializationLeaseStatus
    attempt_count: int
    worker_id: str
    lease_token: str
    leased_until: datetime
    next_retry_at: datetime | None = None
    memory_id: str | None = None
    outcome: str | None = None
    message: str | None = None
    error_message: str | None = None
    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    finished_at: datetime | None = None
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_summary(self) -> dict[str, object]:
        """转换为低敏摘要。

        `lease_token` 类似一次性内部凭证，摘要只能说明它是否存在，不能返回原值。
        """

        return {
            "leaseId": self.lease_id,
            "candidateId": self.candidate_id,
            "tenantId": self.tenant_id,
            "projectId": self.project_id,
            "workspaceKey": self.workspace_key,
            "memoryNamespace": self.memory_namespace,
            "status": self.status.value,
            "attemptCount": self.attempt_count,
            "workerId": self.worker_id,
            "leaseTokenPresent": bool(self.lease_token),
            "leasedUntil": self.leased_until.isoformat(),
            "nextRetryAt": self.next_retry_at.isoformat() if self.next_retry_at else None,
            "memoryId": self.memory_id,
            "outcome": self.outcome,
            "message": self.message,
            "errorMessage": self.error_message,
            "startedAt": self.started_at.isoformat(),
            "finishedAt": self.finished_at.isoformat() if self.finished_at else None,
            "updatedAt": self.updated_at.isoformat(),
        }


class AgentMemoryMaterializationLeaseStore(Protocol):
    """长期记忆落成租约 store 协议。"""

    def try_acquire(
        self,
        *,
        candidate_id: str,
        tenant_id: str,
        project_id: str,
        workspace_key: str,
        memory_namespace: str,
        worker_id: str,
        lease_seconds: int,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease | None:
        """尝试领取候选；已完成或仍被其他 worker 持有时返回 `None`。"""

    def succeed(
        self,
        *,
        candidate_id: str,
        lease_token: str,
        memory_id: str,
        outcome: str,
        message: str,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """使用 fencing token 把租约标记为成功终态。"""

    def fail(
        self,
        *,
        candidate_id: str,
        lease_token: str,
        error_message: str,
        max_attempts: int = 5,
        retry_base_seconds: int = 30,
        retry_max_seconds: int = 3600,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """使用 fencing token 记录失败，并根据重试策略写入冷却时间或 DLQ 终态。"""

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryMaterializationLease | None:
        """按候选 ID 查询租约快照。"""


    def list_for_compensation(
        self,
        *,
        tenant_id: str | None = None,
        project_id: str | None = None,
        workspace_key: str | None = None,
        statuses: tuple[AgentMemoryMaterializationLeaseStatus, ...] | None = None,
        limit: int = 100,
    ) -> tuple[AgentMemoryMaterializationLease, ...]:
        """查询可进入管理员补偿视图的 lease 快照。

        这个方法不是给普通 Agent 规划链路使用，而是给运维台、管理员补偿 API、CLI 和未来审计查询使用。
        默认只看 `failed/dead_letter`，因为这两类状态才代表“自动链路没有顺利完成，需要人工排查或重排”。
        `tenant_id/project_id/workspace_key` 是低敏范围条件，后续 gateway/permission-admin 应根据操作者角色
        强制收紧这些条件，避免平台管理员以外的用户枚举其他租户的失败候选。
        """

    def schedule_retry(
        self,
        *,
        candidate_id: str,
        operator_id: str,
        reason: str,
        next_retry_at: datetime,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """把 failed/dead_letter lease 重新安排到可重试窗口。

        该操作只调整 lease 执行事实，不修改候选审批状态，也不直接写正式长期记忆。这样可以保证管理员补偿
        不能绕过 `APPROVED` 候选治理：真正落成仍然必须由 Runner 在下一轮领取 lease 后调用 materializer。
        """


class InMemoryAgentMemoryMaterializationLeaseStore:
    """线程安全的内存租约实现。

    该实现用于本地学习、单元测试和零依赖启动，不具备跨进程协调能力。生产多实例部署必须切到 SQL 或未来 Redis
    实现，否则每个 Python 进程都会持有独立字典，无法真正阻止多个实例同时处理同一候选。
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self._leases_by_candidate_id: dict[str, AgentMemoryMaterializationLease] = {}

    def try_acquire(
        self,
        *,
        candidate_id: str,
        tenant_id: str,
        project_id: str,
        workspace_key: str,
        memory_namespace: str,
        worker_id: str,
        lease_seconds: int,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease | None:
        """原子尝试领取候选。

        成功领取会生成新的 token。已成功和 DLQ 候选永远跳过；仍在有效期内的租约也跳过；
        失败候选只有在 `next_retry_at` 到达后才能重新领取。
        """

        current_time = _utc(now)
        safe_lease_seconds = max(1, lease_seconds)
        with self._lock:
            current = self._leases_by_candidate_id.get(candidate_id)
            if current and current.status in {
                AgentMemoryMaterializationLeaseStatus.SUCCEEDED,
                AgentMemoryMaterializationLeaseStatus.DEAD_LETTER,
            }:
                return None
            if current and current.status == AgentMemoryMaterializationLeaseStatus.LEASED:
                if current.leased_until > current_time:
                    return None
            if current and current.status == AgentMemoryMaterializationLeaseStatus.FAILED:
                if current.next_retry_at and current.next_retry_at > current_time:
                    return None
            lease = AgentMemoryMaterializationLease(
                lease_id=_lease_id(candidate_id),
                candidate_id=candidate_id,
                tenant_id=tenant_id,
                project_id=project_id,
                workspace_key=workspace_key,
                memory_namespace=memory_namespace,
                status=AgentMemoryMaterializationLeaseStatus.LEASED,
                attempt_count=(current.attempt_count + 1) if current else 1,
                worker_id=worker_id,
                lease_token=str(uuid4()),
                leased_until=current_time + timedelta(seconds=safe_lease_seconds),
                next_retry_at=None,
                memory_id=None,
                outcome=None,
                message=None,
                error_message=None,
                started_at=current_time,
                finished_at=None,
                updated_at=current_time,
            )
            self._leases_by_candidate_id[candidate_id] = lease
            return lease

    def succeed(
        self,
        *,
        candidate_id: str,
        lease_token: str,
        memory_id: str,
        outcome: str,
        message: str,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """把当前 token 对应的租约推进到成功终态。"""

        current_time = _utc(now)
        with self._lock:
            current = self._required_current_lease(candidate_id, lease_token)
            stored = replace(
                current,
                status=AgentMemoryMaterializationLeaseStatus.SUCCEEDED,
                memory_id=memory_id,
                outcome=outcome,
                message=message[:1024],
                error_message=None,
                next_retry_at=None,
                finished_at=current_time,
                updated_at=current_time,
            )
            self._leases_by_candidate_id[candidate_id] = stored
            return stored

    def fail(
        self,
        *,
        candidate_id: str,
        lease_token: str,
        error_message: str,
        max_attempts: int = 5,
        retry_base_seconds: int = 30,
        retry_max_seconds: int = 3600,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """记录失败，并根据策略决定进入退避冷却还是 DLQ。

        退避策略使用指数退避：第 1 次失败等待 base，第 2 次失败等待 base*2，以此类推，并受 max 限制。
        当 `attempt_count >= max_attempts` 时写入 `DEAD_LETTER`，后续 Runner 不会自动领取，避免毒性候选无限重试。
        """

        current_time = _utc(now)
        with self._lock:
            current = self._required_current_lease(candidate_id, lease_token)
            decision = decide_materialization_retry(
                attempt_count=current.attempt_count,
                now=current_time,
                max_attempts=max_attempts,
                retry_base_seconds=retry_base_seconds,
                retry_max_seconds=retry_max_seconds,
            )
            stored = replace(
                current,
                status=decision.status,
                error_message=error_message[:1000],
                next_retry_at=decision.next_retry_at,
                finished_at=current_time,
                updated_at=current_time,
            )
            self._leases_by_candidate_id[candidate_id] = stored
            return stored

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryMaterializationLease | None:
        """按候选 ID 查询租约。"""

        with self._lock:
            return self._leases_by_candidate_id.get(candidate_id)

    def list_for_compensation(
        self,
        *,
        tenant_id: str | None = None,
        project_id: str | None = None,
        workspace_key: str | None = None,
        statuses: tuple[AgentMemoryMaterializationLeaseStatus, ...] | None = None,
        limit: int = 100,
    ) -> tuple[AgentMemoryMaterializationLease, ...]:
        """按范围查询失败/DLQ lease，用于管理员补偿台。

        内存实现主要服务本地学习和单元测试，但它仍然完整遵守生产语义：
        - 默认只返回 `failed/dead_letter`，避免把成功或正在执行的 lease 暴露成“待补偿”；
        - 支持 tenant/project/workspace 过滤，为后续 RBAC 数据范围下沉保留接口；
        - 返回结果按 `updated_at` 倒序，方便运维先看最近失败的候选；
        - `limit` 做上限裁剪，避免管理接口误把进程内所有 lease 一次性返回。
        """

        selected_statuses = set(statuses or _default_compensation_statuses())
        safe_limit = max(1, min(int(limit), 500))
        with self._lock:
            leases = tuple(
                lease
                for lease in self._leases_by_candidate_id.values()
                if lease.status in selected_statuses
                and _matches_optional(lease.tenant_id, tenant_id)
                and _matches_optional(lease.project_id, project_id)
                and _matches_optional(lease.workspace_key, workspace_key)
            )
        return tuple(sorted(leases, key=lambda item: item.updated_at, reverse=True)[:safe_limit])

    def schedule_retry(
        self,
        *,
        candidate_id: str,
        operator_id: str,
        reason: str,
        next_retry_at: datetime,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """把失败或 DLQ lease 重新放回可领取状态。

        业务约束：
        - 只允许 `FAILED` 与 `DEAD_LETTER` 被重排；
        - 不允许重排 `SUCCEEDED`，否则可能造成已经落成的记忆被重复处理；
        - 不允许重排仍在 `LEASED` 的候选，避免管理员操作覆盖正在执行的 worker；
        - 不重置 `attempt_count`，因为尝试次数是故障证据。后续如果需要“重置尝试预算”，应增加单独的
          高权限动作，并强制写审计事件，而不是在普通重排中悄悄清零。
        """

        current_time = _utc(now)
        scheduled_time = _utc(next_retry_at)
        _validate_compensation_operator(operator_id=operator_id, reason=reason)
        with self._lock:
            current = self._leases_by_candidate_id.get(candidate_id)
            if current is None:
                raise KeyError(f"长期记忆物化 lease 不存在: {candidate_id}")
            _require_requeueable_status(current)
            stored = replace(
                current,
                status=AgentMemoryMaterializationLeaseStatus.FAILED,
                next_retry_at=scheduled_time,
                message=_admin_requeue_message(operator_id=operator_id, reason=reason),
                updated_at=current_time,
            )
            self._leases_by_candidate_id[candidate_id] = stored
            return stored

    def _required_current_lease(self, candidate_id: str, lease_token: str) -> AgentMemoryMaterializationLease:
        """校验 fencing token，阻止过期 worker 覆盖新 worker 的处理结果。"""

        current = self._leases_by_candidate_id.get(candidate_id)
        if (
            current is None
            or current.status != AgentMemoryMaterializationLeaseStatus.LEASED
            or current.lease_token != lease_token
        ):
            raise RuntimeError(f"长期记忆落成租约 fencing 校验失败，candidateId={candidate_id}")
        return current


def _utc(value: datetime | None) -> datetime:
    """把可选时间统一为 UTC aware datetime，方便内存与 SQL 实现保持一致。"""

    current = value or datetime.now(timezone.utc)
    return current.replace(tzinfo=timezone.utc) if current.tzinfo is None else current.astimezone(timezone.utc)


def _default_compensation_statuses() -> tuple[AgentMemoryMaterializationLeaseStatus, ...]:
    """管理员补偿视图的默认状态集合。

    这里刻意不包含 `LEASED/SUCCEEDED`：前者代表仍有 worker 正在执行或租约尚未过期，后者代表已经形成终态。
    把默认集合收窄到 failed/dead_letter，可以降低管理台误操作概率。
    """

    return (
        AgentMemoryMaterializationLeaseStatus.FAILED,
        AgentMemoryMaterializationLeaseStatus.DEAD_LETTER,
    )


def _matches_optional(actual: str, expected: str | None) -> bool:
    """可选范围过滤器。"""

    return expected is None or not str(expected).strip() or actual == str(expected).strip()


def _validate_compensation_operator(*, operator_id: str, reason: str) -> None:
    """校验管理员补偿操作者与原因。

    补偿动作会改变后续 worker 是否重新处理候选，因此必须记录“谁为什么重排”。当前先在 lease message
    中留下低敏摘要；未来接入审计事件后，这两个字段会成为审计事实的最小必填项。
    """

    if not str(operator_id or "").strip():
        raise ValueError("operatorId 必填，用于审计长期记忆物化补偿责任人。")
    if not str(reason or "").strip():
        raise ValueError("reason 必填，用于说明为什么重新安排失败或 DLQ 候选。")


def _require_requeueable_status(lease: AgentMemoryMaterializationLease) -> None:
    """要求 lease 处于可重排状态。"""

    if lease.status not in _default_compensation_statuses():
        raise ValueError(
            "只有 failed/dead_letter 状态的长期记忆物化 lease 可以被管理员补偿重排，"
            f"当前状态为: {lease.status.value}"
        )


def _admin_requeue_message(*, operator_id: str, reason: str) -> str:
    """构造低敏补偿说明。"""

    safe_operator = str(operator_id).strip()[:128]
    safe_reason = " ".join(str(reason).strip().split())[:800]
    return f"管理员 {safe_operator} 已安排长期记忆物化补偿重试：{safe_reason}"


def decide_materialization_retry(
    *,
    attempt_count: int,
    now: datetime | None = None,
    max_attempts: int = 5,
    retry_base_seconds: int = 30,
    retry_max_seconds: int = 3600,
) -> AgentMemoryMaterializationRetryDecision:
    """根据当前尝试次数计算下一步应自动重试还是进入 DLQ。

    业务解释：
    - `attempt_count` 来自 lease 领取次数，而不是 receipt begin 次数，因为它代表 worker 真正取得处理权的次数；
    - 当尝试次数达到上限时进入 `DEAD_LETTER`，后续应由管理员查看低敏错误、修复配置或手动重放；
    - 未达到上限时写入 `next_retry_at`，Runner 在冷却期内跳过该候选，避免外部依赖抖动时毫秒级热循环。

    性能解释：
    退避能显著降低坏候选对数据库、向量库、图谱索引和日志系统的压力。当前采用简单指数退避，是为了先固定
    可解释、可测试的语义；未来可以按租户套餐、错误类型或下游容量切换为更细的策略。
    """

    current_time = _utc(now)
    safe_attempts = max(1, max_attempts)
    safe_base = max(1, retry_base_seconds)
    safe_max = max(safe_base, retry_max_seconds)
    if attempt_count >= safe_attempts:
        return AgentMemoryMaterializationRetryDecision(
            status=AgentMemoryMaterializationLeaseStatus.DEAD_LETTER,
            next_retry_at=None,
            retry_delay_seconds=0,
            max_attempts=safe_attempts,
            dead_lettered=True,
        )
    retry_delay = min(safe_max, safe_base * (2 ** max(0, attempt_count - 1)))
    return AgentMemoryMaterializationRetryDecision(
        status=AgentMemoryMaterializationLeaseStatus.FAILED,
        next_retry_at=current_time + timedelta(seconds=retry_delay),
        retry_delay_seconds=retry_delay,
        max_attempts=safe_attempts,
        dead_lettered=False,
    )


def _lease_id(candidate_id: str) -> str:
    """按候选 ID 生成稳定 lease ID。"""

    return f"memory-materialization-lease-{uuid5(NAMESPACE_URL, candidate_id)}"
