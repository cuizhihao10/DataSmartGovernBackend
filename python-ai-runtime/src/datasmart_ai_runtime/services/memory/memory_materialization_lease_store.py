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
    - `FAILED`：最近一次尝试失败，当前阶段允许下一轮重新领取。

    下一阶段会在 `FAILED` 上增加 nextRetryAt、最大尝试次数和 DLQ。当前先保留最小状态机，
    避免一次性把调度、补偿和告警全部揉进同一个改动。
    """

    LEASED = "leased"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


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
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """使用 fencing token 记录失败，使候选可在后续轮次重试。"""

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryMaterializationLease | None:
        """按候选 ID 查询租约快照。"""


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

        成功领取会生成新的 token。已成功候选永远跳过；仍在有效期内的租约也跳过；失败或已过期租约可以重新领取。
        """

        current_time = _utc(now)
        safe_lease_seconds = max(1, lease_seconds)
        with self._lock:
            current = self._leases_by_candidate_id.get(candidate_id)
            if current and current.status == AgentMemoryMaterializationLeaseStatus.SUCCEEDED:
                return None
            if current and current.status == AgentMemoryMaterializationLeaseStatus.LEASED:
                if current.leased_until > current_time:
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
                started_at=current_time,
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
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """记录失败，并允许候选在后续轮次再次领取。"""

        current_time = _utc(now)
        with self._lock:
            current = self._required_current_lease(candidate_id, lease_token)
            stored = replace(
                current,
                status=AgentMemoryMaterializationLeaseStatus.FAILED,
                error_message=error_message[:1000],
                finished_at=current_time,
                updated_at=current_time,
            )
            self._leases_by_candidate_id[candidate_id] = stored
            return stored

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryMaterializationLease | None:
        """按候选 ID 查询租约。"""

        with self._lock:
            return self._leases_by_candidate_id.get(candidate_id)

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


def _lease_id(candidate_id: str) -> str:
    """按候选 ID 生成稳定 lease ID。"""

    return f"memory-materialization-lease-{uuid5(NAMESPACE_URL, candidate_id)}"
